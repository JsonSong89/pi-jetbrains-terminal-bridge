package com.piterminal.bridge.services

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.AppIcon
import com.piterminal.bridge.bridge.FinishedRunTracker
import com.piterminal.bridge.settings.PiSettings
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.Window
import javax.swing.JFrame

/** Best-effort platform alerts; no operation state, replay, or focus changes. */
@Service(Service.Level.PROJECT)
class PiFinishNotifier(private val project: Project) {
    private val logger = Logger.getInstance(PiFinishNotifier::class.java)
    private val finishedRuns = FinishedRunTracker()

    fun finished(tabKey: String, runtimeId: String, event: JsonObject) {
        val app = ApplicationManager.getApplication()
        app.assertIsDispatchThread()
        if (project.isDisposed || !finishedRuns.accept(tabKey, runtimeId, event)) return
        val settings = PiSettings.getInstance().state
        if (settings.soundOnAgentEnd) {
            runCatching { Toolkit.getDefaultToolkit().beep() }
                .onFailure { logger.debug("Pi finish sound unavailable", it) }
        }
        if (!settings.attentionOnAgentEnd) return
        // Attention belongs to this conversation's project window, not to the IDE
        // process: another project window can hold focus in the same instance.
        val frame = runCatching { WindowManager.getInstance().getFrame(project) }.getOrNull()
        if (frame != null && ownsFocus(frame)) return
        runCatching {
            if (!app.isActive) AppIcon.getInstance().requestAttention(project, true)
            else frame?.let(::requestWindowAttention)
        }.onFailure { logger.debug("Pi finish attention unavailable", it) }
    }

    /**
     * True when [frame] or one of its dialogs owns keyboard focus. A dialog is
     * part of the same project window, so it cannot count as "another project".
     */
    private fun ownsFocus(frame: Window): Boolean {
        var window = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
        while (window != null) {
            if (window === frame) return true
            window = window.owner
        }
        return false
    }

    /**
     * The IDE is focused on another project window. [AppIcon] is application-level
     * and deliberately does nothing while the IDE is active, so flash this window
     * directly through platform window attention. macOS only exposes application-
     * level Dock attention, which an already active application does not show.
     * Reflection keeps these platform-impl entry points out of the plugin API surface.
     */
    private fun requestWindowAttention(frame: JFrame) {
        when {
            SystemInfo.isWindows -> Class.forName("com.intellij.ui.Win7TaskBar")
                .getDeclaredMethod("attention", JFrame::class.java)
                .apply { isAccessible = true }
                .invoke(null, frame)
            SystemInfo.isXWindow -> Class.forName("com.intellij.openapi.wm.impl.X11UiUtil")
                .getMethod("requestAttention", Window::class.java)
                .invoke(null, frame)
        }
    }

    companion object { fun getInstance(project: Project): PiFinishNotifier = project.service() }
}
