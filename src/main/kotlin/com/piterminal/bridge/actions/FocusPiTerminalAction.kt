package com.piterminal.bridge.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.project.DumbAware
import com.piterminal.bridge.PiBridgeUi
import com.piterminal.bridge.conversations.PiConversationService

/**
 * Focus the Pi terminal. Only enabled while the prompt input has focus,
 * so Ctrl+Alt+Shift+T does not steal Refactor This from the editor.
 */
class FocusPiTerminalAction : AnAction(), DumbAware {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val conversations = PiConversationService.getInstance(project)
        val id = conversations.active()?.id ?: return
        conversations.revealTerminal(id)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null &&
            PiBridgeUi.isPromptFocused(e.getData(PlatformDataKeys.CONTEXT_COMPONENT))
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
}
