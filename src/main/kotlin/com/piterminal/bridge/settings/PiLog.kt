package com.piterminal.bridge.settings

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.components.service

/**
 * Tiny leveled logging facade over the IntelliJ [Logger] so the log level can
 * be tuned from Settings (INFO by default; DEBUG adds per-file refresh traces
 * and other verbose bridge activity). Messages go to idea.log, viewable via
 * Help → Diagnostic Tools → Debug Log Settings / Show Log.
 */
object PiLog {

    enum class Level(val label: String) {
        ERROR("error"),
        WARN("warn"),
        INFO("info"),
        DEBUG("debug");

        companion object {
            fun fromLabel(label: String?): Level =
                entries.firstOrNull { it.label.equals(label, ignoreCase = true) } ?: INFO
        }
    }

    private val logger = Logger.getInstance("PiTerminalBridge")

    fun level(): Level = Level.fromLabel(service<PiSettings>().state.logLevel)

    fun error(message: String, t: Throwable? = null) {
        if (t != null) logger.error(message, t) else logger.error(message)
    }

    fun warn(message: String) = logger.warn(message)

    fun info(message: String) {
        if (level().ordinal >= Level.INFO.ordinal) logger.info(message)
    }

    /** Verbose: file refresh traces, per-message bridge routing, ... */
    fun debug(message: String) {
        if (level() == Level.DEBUG) logger.info("[debug] $message")
    }
}
