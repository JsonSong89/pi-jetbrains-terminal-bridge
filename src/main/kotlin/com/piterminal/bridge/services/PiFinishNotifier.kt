package com.piterminal.bridge.services

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.AppIcon
import com.piterminal.bridge.bridge.FinishedRunTracker
import com.piterminal.bridge.settings.PiSettings
import java.awt.Toolkit

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
        if (settings.attentionOnAgentEnd && !app.isActive) {
            runCatching { AppIcon.getInstance().requestAttention(project, true) }
                .onFailure { logger.debug("Pi finish attention unavailable", it) }
        }
    }

    companion object { fun getInstance(project: Project): PiFinishNotifier = project.service() }
}
