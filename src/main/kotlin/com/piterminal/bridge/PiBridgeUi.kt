package com.piterminal.bridge

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import java.awt.Component
import javax.swing.JComponent

object PiBridgeUi {
    const val TOOL_WINDOW_ID = "Pi Bridge"
    const val NOTIFICATION_GROUP_ID = "Pi Bridge"
    const val PROMPT_FOCUS_PROPERTY = "pi-prompt-input"
    const val TERMINAL_FOCUS_PROPERTY = "pi-terminal"

    fun isPromptFocused(component: Component?): Boolean =
        ancestorProperty(component, PROMPT_FOCUS_PROPERTY) != null

    fun terminalConversationId(component: Component?): String? =
        ancestorProperty(component, TERMINAL_FOCUS_PROPERTY) as? String

    private fun ancestorProperty(component: Component?, key: String): Any? {
        var current = component
        while (current != null) {
            if (current is JComponent) {
                current.getClientProperty(key)?.let { return it }
            }
            current = current.parent
        }
        return null
    }

    fun notify(project: Project, message: String, type: NotificationType) {
        group().createNotification(message, type).notify(project)
    }

    private fun group() =
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP_ID)
}
