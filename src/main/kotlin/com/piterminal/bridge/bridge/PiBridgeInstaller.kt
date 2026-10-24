package com.piterminal.bridge.bridge

import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Installs/updates the pi-side bridge extension at
 * ~/.pi/agent/extensions/pi-launcher-bridge.ts.
 *
 * The extension source lives in the plugin resources
 * (resources/bridge/pi-launcher-bridge.ts) and ships inside the plugin jar;
 * the `@EXTENSION_VERSION@` placeholder is substituted on load, so the TS is
 * edited as a normal TypeScript file, not as a Kotlin string.
 *
 * The installed file is managed by this plugin: only this single file is
 * written, other files in that directory (user extensions, ...) are
 * untouched. The extension is env-gated: without
 * PI_LAUNCHER_PORT/TOKEN/TAB_KEY it does nothing, so pi instances not
 * launched by the plugin are unaffected.
 *
 * Updates are detected by full resource content comparison; bump VERSION when
 * the extension's observable behavior changes so diagnostics can tell stale
 * copies apart. Removing the managed file also forces reinstall.
 */
object PiBridgeInstaller {

    private val logger = Logger.getInstance(PiBridgeInstaller::class.java)

    private const val VERSION = 7

    private const val RESOURCE_PATH = "/bridge/pi-launcher-bridge.ts"

    private val source: String by lazy {
        val raw = PiBridgeInstaller::class.java.getResourceAsStream(RESOURCE_PATH)
            ?.bufferedReader(Charsets.UTF_8)?.readText()
            ?: throw IllegalStateException("Missing plugin resource: $RESOURCE_PATH")
        raw.replace("@EXTENSION_VERSION@", VERSION.toString()).trim() + "\n"
    }

    fun extensionPath(): Path =
        Paths.get(System.getProperty("user.home"), ".pi", "agent", "extensions", "pi-launcher-bridge.ts")

    /** Ensures the extension file exists and is current. Best-effort. */
    fun ensureInstalled() {
        try {
            val path = extensionPath()
            if (Files.exists(path) && isCurrent(path)) return
            Files.createDirectories(path.parent)
            Files.writeString(path, source)
            logger.info("Installed pi bridge extension at $path (v$VERSION)")
        } catch (e: Exception) {
            logger.warn("Failed to install pi bridge extension: ${e.message}")
        }
    }

    private fun isCurrent(path: Path): Boolean = try {
        // Compare full content, not the version constant: any resource change
        // must rewrite the installed file even if VERSION was not bumped.
        Files.readString(path) == source
    } catch (_: Exception) {
        false
    }

    /** Read-only diagnostics for the settings page: [state, version, path]. */
    fun diagnostics(): String {
        val path = extensionPath()
        return try {
            if (!Files.exists(path)) {
                "extension not installed yet — starts automatically with the first Pi terminal"
            } else {
                val current = isCurrent(path)
                val state = if (current) "installed (v$VERSION, up to date)" else "stale — will be rewritten on next Pi launch"
                "$state · $path"
            }
        } catch (e: Exception) {
            "diagnostics unavailable: ${e.message}"
        }
    }
}
