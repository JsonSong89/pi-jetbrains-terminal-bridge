package com.piterminal.bridge.bridge

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
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

/** Loopback listener: authenticated v2 operation connections plus legacy v1 notification events. */
@Service(Service.Level.PROJECT)
class PiBridgeServer(private val project: Project) : Disposable {

    private val logger = Logger.getInstance(PiBridgeServer::class.java)
    private val started = AtomicBoolean(false)
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var token: String = ""

    private val gson = Gson()
    private val lastSeqByKey = ConcurrentHashMap<String, Long>()

    private val clients = Semaphore(64)
    private val connections = ConcurrentHashMap<String, BridgeConnection>()
    // v2 ownership persists through disconnect: delayed v1 events cannot rebind the tab.
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
                if (first.get("v")?.asInt == 1) {
                    handleMessage(gson.toJson(first))
                    return
                }
                require(first.get("v")?.asInt == 2 && str(first, "type") == "hello")
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
        } catch (_: Exception) {
            // Malformed/authentication/EOF failures close only this client.
        }
    }

    private fun handleMessage(line: String) {
        val envelope = try {
            gson.fromJson(line, JsonObject::class.java)
        } catch (e: Exception) {
            logger.warn("Pi bridge: unparseable legacy message")
            return
        }

        val v = envelope.get("v")?.asInt ?: 0
        if (v != PROTOCOL_VERSION) {
            logger.warn("Pi bridge: dropping message with protocol v=$v (expected $PROTOCOL_VERSION)")
            return
        }
        if (envelope.get("token")?.asString != token) {
            logger.warn("Pi bridge: rejected message with bad token")
            return
        }

        val tabKey = envelope.get("tabKey")?.takeIf { it.isJsonPrimitive }?.asString ?: return
        val type = envelope.get("type")?.takeIf { it.isJsonPrimitive }?.asString ?: return
        val data = envelope.getAsJsonObject("data") ?: gson.toJsonTree(mapOf<String, Any>()).asJsonObject
        val seq = envelope.get("seq")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L

        // Per (tabKey, type) de-dup: each pi process counts its own seq and
        // independent connections may reorder, so types never share a timeline.
        if (seq > 0) {
            val key = "$tabKey\u0000$type"
            var accepted = false
            lastSeqByKey.compute(key) { _, last ->
                if (seq > (last ?: 0L)) { accepted = true; seq } else last
            }
            if (!accepted) return
        }

        if (project.isDisposed) return

        // Handlers touch UI-visible state (persist + notifications); dispatch on EDT.
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            if (seq > 0 && lastSeqByKey["$tabKey\u0000$type"] != seq) return@invokeLater
            route(tabKey, type, data)
        }
    }

    private fun route(tabKey: String, type: String, data: JsonObject) {
        val service = PiConversationService.getInstance(project)
        if (service.get(tabKey) == null) return
        if (owns(tabKey) && type in setOf("session_changed", "session_stopped")) return
        when (type) {
            "session_changed" -> str(data, "sessionId")?.let {
                service.onBridgeSessionChanged(tabKey, it, str(data, "extensionVersion"))
            }
            "session_stopped" -> service.onBridgeSessionStopped(tabKey)
            "agent_state" -> str(data, "state")?.let { service.onBridgeAgentState(tabKey, it, str(data, "stopReason")) }
            "model_changed" -> str(data, "modelId")?.let { model ->
                service.onBridgeModelChanged(tabKey, model)
            }
            "file_modified" -> str(data, "path")?.let { path ->
                service.onBridgeFileModified(tabKey, path)
            }
            else -> logger.info("Pi bridge: ignored unknown type '$type'")
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
        const val PROTOCOL_VERSION = 1 // legacy event envelope; v2 is negotiated by hello

        fun getInstance(project: Project): PiBridgeServer = project.service()
    }
}
