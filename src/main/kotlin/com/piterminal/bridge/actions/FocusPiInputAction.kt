package com.piterminal.bridge.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.project.DumbAware
import com.piterminal.bridge.PiBridgeUi
import com.piterminal.bridge.conversations.PiConversationService

/**
 * Focus the Pi prompt. Only enabled while a Pi terminal tab has focus.
 */
class FocusPiInputAction : AnAction(), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val component = e.getData(PlatformDataKeys.CONTEXT_COMPONENT)
        val tabKey = PiBridgeUi.terminalConversationId(component)
        PiConversationService.getInstance(project).focusInput(tabKey)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null &&
            PiBridgeUi.terminalConversationId(e.getData(PlatformDataKeys.CONTEXT_COMPONENT)) != null
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
