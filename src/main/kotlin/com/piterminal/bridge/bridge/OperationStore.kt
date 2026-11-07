package com.piterminal.bridge.bridge

import com.google.gson.JsonObject
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.project.Project
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

/** Independent of the session tree. I/O callers must run off the EDT. */
class OperationStore(project: Project) {
    val directory: Path = Path.of(PathManager.getSystemPath(), "pi-terminal-bridge",
        BridgeProtocol.digest(project.basePath ?: project.locationHash))
    fun remoteDirectory(tabKey: String): Path = directory.resolve("remote").resolve(BridgeProtocol.digest(tabKey))
    private val local = directory.resolve("local")
    private val budget = java.lang.Long.getLong("pi.bridge.maxJournalBytes", 512L * 1024 * 1024).coerceAtLeast(1024)

    @Synchronized fun save(record: JsonObject) {
        val id = BridgeProtocol.string(record, "id") ?: error("Missing operation id")
        require(ID.matches(id))
        val target = local.resolve("$id.json")
        val text = BridgeProtocol.gson.toJson(record)
        val used = if (Files.isDirectory(local)) Files.list(local).use { paths ->
            paths.filter { it.fileName.toString().endsWith(".json") }.mapToLong { Files.size(it) }.sum()
        } else 0L
        val previous = if (Files.exists(target)) Files.size(target) else 0L
        require(used - previous + text.toByteArray(Charsets.UTF_8).size <= budget) { "CONTEXT_TOO_LARGE: local operation journal budget exhausted" }
        atomicWrite(target, text)
    }

    @Synchronized fun records(): List<JsonObject> {
        if (!Files.isDirectory(local)) return emptyList()
        return Files.list(local).use { paths -> paths.filter { it.fileName.toString().endsWith(".json") }
            .map { path ->
                // A corrupt record must not silently disappear and release its resource lock.
                try { BridgeProtocol.parse(Files.readString(path)) }
                catch (_: Exception) { throw IllegalStateException("Damaged bridge operation journal; reconciliation required") }
            }.toList() }
    }

    companion object {
        private val ID = Regex("[a-zA-Z0-9-]{1,128}")
        fun atomicWrite(path: Path, text: String) {
            Files.createDirectories(path.parent)
            // POSIX gets 0700/0600; the Windows IDE system directory already sits
            // under the user's profile ACL, and the loopback token never touches disk.
            runCatching { Files.setPosixFilePermissions(path.parent, PosixFilePermissions.fromString("rwx------")) }
            val tmp = Files.createTempFile(path.parent, ".operation-", ".tmp")
            try {
                runCatching { Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------")) }
                FileChannel.open(tmp, StandardOpenOption.WRITE).use { channel ->
                    val bytes = ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8))
                    while (bytes.hasRemaining()) channel.write(bytes)
                    channel.force(true)
                }
                Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                // Supported on the Unix file system; Windows does not open directories this way.
                if (System.getProperty("os.name").lowercase().contains("win").not()) {
                    FileChannel.open(path.parent, StandardOpenOption.READ).use { it.force(true) }
                }
            } finally { Files.deleteIfExists(tmp) }
        }
    }
}
