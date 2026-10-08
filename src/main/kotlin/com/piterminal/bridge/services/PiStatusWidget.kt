package com.piterminal.bridge.services

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.Consumer
import com.piterminal.bridge.conversations.PiConversationService
import java.awt.event.MouseEvent

/**
 * Status bar widget showing how many Pi conversations are currently working.
 */
class PiStatusWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = "PiBridgeStatus"
    override fun getDisplayName(): String = "Pi Bridge"
    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget {
        return PiStatusWidget(project)
    }
}

class PiStatusWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {

    private var statusBar: StatusBar? = null

    override fun ID(): String = "PiBridgeStatus"

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
    }

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun getText(): String {
        val working = PiConversationService.getInstance(project).workingCount()
        return when {
            working <= 0 -> "π Idle"
            working == 1 -> "π Working"
            else -> "π $working Working"
        }
    }

    override fun getTooltipText(): String {
        val working = PiConversationService.getInstance(project).workingCount()
        return if (working > 0) {
            "Pi Bridge: $working conversation(s) working. Click to open the panel."
        } else {
            "Pi Bridge is idle. Click to open the panel."
        }
    }

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer {
        val conversations = PiConversationService.getInstance(project)
        conversations.showToolWindow(focus = true)
        conversations.ensureActiveConversation()
    }

    override fun getAlignment(): Float = 0f

    override fun dispose() {
        statusBar = null
    }

    companion object {
        fun update(project: Project) {
            val statusBar = WindowManager.getInstance().getStatusBar(project) ?: return
            statusBar.updateWidget("PiBridgeStatus")
        }
    }
}
