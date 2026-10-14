package com.piterminal.bridge

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationListener
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.WindowManager
import java.awt.Component
import javax.swing.JComponent
import javax.swing.event.HyperlinkEvent

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

    fun isIdeActive(project: Project): Boolean {
        val frame = WindowManager.getInstance().getFrame(project) ?: return false
        return frame.isActive
    }

    fun notify(project: Project, message: String, type: NotificationType) {
        group().createNotification(message, type).notify(project)
    }

    fun notifyOpenTerminal(
        project: Project,
        title: String,
        body: String,
        type: NotificationType,
        onOpen: () -> Unit
    ) {
        val notification = group().createNotification(
            title,
            "$body <a href=\"open\">Open terminal</a>",
            type
        )
        notification.addAction(object : NotificationAction("Open terminal") {
            override fun actionPerformed(e: AnActionEvent, n: Notification) {
                n.expire()
                onOpen()
            }
        })
        notification.setListener(NotificationListener { n, event ->
            if (event.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                n.expire()
                onOpen()
            }
        })
        notification.notify(project)
    }

    private fun group() =
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP_ID)
}
