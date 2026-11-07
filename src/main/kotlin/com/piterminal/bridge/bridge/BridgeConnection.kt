package com.piterminal.bridge.bridge

import com.google.gson.JsonObject
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** One authenticated runtime. Dedicated reader and writer continuously drain each direction. */
class BridgeConnection(
    val socket: Socket,
    val hello: JsonObject,
    private val receive: (BridgeConnection, JsonObject) -> Unit,
    private val closed: (BridgeConnection) -> Unit
) {
    val tabKey = BridgeProtocol.string(hello, "tabKey") ?: error("Missing tabKey")
    val runtimeId = BridgeProtocol.string(hello, "runtimeId") ?: error("Missing runtimeId")
    val id = UUID.randomUUID().toString()
    private val queue = ArrayBlockingQueue<ByteArray>(128)
    private val queuedBytes = AtomicInteger()
    private val stopped = AtomicBoolean()
    @Volatile private var lastRead = System.nanoTime()
    val alive: Boolean get() = !stopped.get()
    fun supports(method: String): Boolean = runCatching {
        hello.getAsJsonObject("capabilities").getAsJsonArray(method).any { it.asInt == 1 }
    }.getOrDefault(false)

    /** False proves not queued. Once true, callers must treat delivery as uncertain. */
    fun send(frame: JsonObject): Boolean {
        if (!alive) return false
        val bytes = (BridgeProtocol.gson.toJson(frame) + "\n").toByteArray(Charsets.UTF_8)
        if (bytes.size > BridgeProtocol.maxFrameBytes || queuedBytes.addAndGet(bytes.size) > BridgeProtocol.maxQueueBytes) {
            if (bytes.size <= BridgeProtocol.maxFrameBytes) queuedBytes.addAndGet(-bytes.size)
            close()
            return false
        }
        if (!queue.offer(bytes)) {
            queuedBytes.addAndGet(-bytes.size)
            close()
            return false
        }
        return true
    }

    fun run() {
        val writer = Thread({
            try {
                val output = socket.getOutputStream()
                while (alive) {
                    val bytes = queue.poll(5, TimeUnit.SECONDS)
                    if (bytes != null) {
                        output.write(bytes)
                        output.flush()
                        queuedBytes.addAndGet(-bytes.size)
                    } else {
                        output.write("{\"v\":2,\"type\":\"ping\"}\n".toByteArray(Charsets.UTF_8))
                        output.flush()
                    }
                    if (System.nanoTime() - lastRead > TimeUnit.SECONDS.toNanos(15)) close()
                }
            } catch (_: Exception) { close() }
        }, "PiBridgeWriter").apply { isDaemon = true }
        writer.start()
        try {
            socket.soTimeout = 15_000
            val input = socket.getInputStream().buffered()
            while (alive) {
                val frame = BridgeProtocol.readFrame(input) ?: break
                lastRead = System.nanoTime()
                require(frame.get("v")?.asInt == 2)
                when (BridgeProtocol.string(frame, "type")) {
                    "ping" -> send(BridgeProtocol.frame("pong"))
                    "pong" -> Unit
                    else -> receive(this, frame)
                }
            }
        } finally { close() }
    }

    fun close() {
        if (!stopped.compareAndSet(false, true)) return
        runCatching { socket.close() }
        queue.clear()
        runCatching { closed(this) }
    }
}
