package com.piterminal.bridge.bridge

import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.piterminal.bridge.conversations.PiConversationService
import java.util.concurrent.Semaphore
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** Loopback listener for authenticated v2 operation and event connections. */
@Service(Service.Level.PROJECT)
class PiBridgeServer(private val project: Project) : Disposable {

    private val logger = Logger.getInstance(PiBridgeServer::class.java)
    private val started = AtomicBoolean(false)
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var token: String = ""

    private val clients = Semaphore(64)
    private val connections = ConcurrentHashMap<String, BridgeConnection>()
    // Remember authenticated tabs through disconnect for session rebind handling.
    private val managedTabs = ConcurrentHashMap.newKeySet<String>()

    fun connection(tabKey: String): BridgeConnection? = connections[tabKey]?.takeIf { it.alive }
    fun owns(tabKey: String): Boolean = tabKey in managedTabs

    class Endpoint(val port: Int, val token: String)

    /**
     * Idempotent. Returns the endpoint pi should be told about, or null when
     * the server could not start (bridge degrades to no-op, core features fine).
     */
    fun ensureStarted(): Endpoint? {
        if (!started.compareAndSet(false, true)) return currentEndpoint()
        return try {
            val socket = ServerSocket()
            socket.bind(InetSocketAddress("127.0.0.1", 0))
            token = token()
            serverSocket = socket
            val acceptThread = Thread({ acceptLoop(socket) }, "PiBridgeServer")
            acceptThread.isDaemon = true
            acceptThread.start()
            currentEndpoint()
        } catch (e: Exception) {
            started.set(false)
            logger.warn("Pi bridge server failed to start: ${e.message}")
            null
        }
    }

    /** Current endpoint, or null when the server has not been started/failed. */
    fun endpoint(): Endpoint? = currentEndpoint()

    private fun currentEndpoint(): Endpoint? =
        serverSocket?.let { Endpoint(it.localPort, token) }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            try {
                val client = socket.accept()
                if (!clients.tryAcquire()) { client.close(); continue }
                val handler = Thread({ try { handle(client) } finally { clients.release() } }, "PiBridgeClient")
                handler.isDaemon = true
                handler.start()
            } catch (_: Exception) {
                // closed socket ends the loop; transient accept failures retry
            }
        }
    }

    private fun handle(client: java.net.Socket) {
        try {
            client.use { s ->
                s.soTimeout = 5_000
                // Do not prefetch past hello; BridgeConnection owns the remaining stream.
                val first = BridgeProtocol.readFrame(s.getInputStream()) ?: return
                if (first.get("v")?.asInt != 2) {
                    logger.warn("Pi bridge: unsupported protocol; upgrade the bridge extension and restart the terminal")
                    return
                }
                require(str(first, "type") == "hello")
                require(str(first, "token") == token)
                val tab = str(first, "tabKey") ?: return
                if (PiConversationService.getInstance(project).get(tab) == null) return
                val operations = BridgeOperations.getInstance(project)
                val connection = BridgeConnection(s, first, operations::receive) { old ->
                    if (connections.remove(old.tabKey, old)) operations.disconnected(old)
                }
                // No unproven simultaneous runtime takeover. Reconnect after a closed socket is allowed;
                // unresolved requests keep their original runtime/session ownership in the journal.
                synchronized(connections) {
                    if (connections[tab]?.alive == true) return
                    connections[tab] = connection
                    managedTabs.add(tab)
                }
                connection.send(BridgeProtocol.frame("welcome").apply {
                    addProperty("connectionId", connection.id)
                    addProperty("protocolVersion", 2)
                })
                operations.connected(connection)
                connection.run()
            }
        } catch (e: Exception) {
            // Malformed/authentication/EOF failures close only this client.
            logger.debug("Pi bridge client closed: ${e.message}")
        }
    }

    private fun str(data: JsonObject, key: String): String? =
        data.get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun token(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    override fun dispose() {
        connections.values.toList().forEach { it.close() }
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        started.set(false)
    }

    override fun toString(): String = "PiBridgeServer(port=${serverSocket?.localPort})"

    companion object {
        fun getInstance(project: Project): PiBridgeServer = project.service()
    }
}
