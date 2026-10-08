package com.piterminal.bridge.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.colors.EditorColorsManager
import java.awt.Font
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.text.StyleContext

/**
 * Persistent settings for Pi Terminal Bridge.
 * Storage name was reset so older XML (boolean defaults that could not persist
 * unchecked options) is ignored.
 */
@Service(Service.Level.APP)
@State(
    name = "PiTerminalBridgeSettings",
    storages = [Storage("PiTerminalBridgeSettings.xml")]
)
class PiSettings : PersistentStateComponent<PiSettings.State> {

    class State {
        var piCommand: String = "pi"
        var model: String = "Default"
        var customModelId: String = ""
        var thinkingLevel: String = "Default"
        var extraArgs: String = ""
        var shellPath: String = ""
        var conversationFontSize: Int = DEFAULT_FONT_SIZE
        // Align with Java/XML boolean default (false) so unchecked values persist.
        var sendWithCtrlEnter: Boolean = false
        var notifyOnAgentEnd: Boolean = false
        var openModifiedFiles: Boolean = false
    }

    private var myState = State()
    private val listeners = CopyOnWriteArrayList<ChangeListener>()

    fun interface ChangeListener {
        fun settingsChanged()
    }

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    override fun noStateLoaded() {
        myState.sendWithCtrlEnter = true
        myState.conversationFontSize = DEFAULT_FONT_SIZE
    }

    fun addChangeListener(listener: ChangeListener) {
        listeners.add(listener)
    }

    fun removeChangeListener(listener: ChangeListener) {
        listeners.remove(listener)
    }

    fun notifyChanged() {
        listeners.forEach { it.settingsChanged() }
    }

    fun conversationFont(): Font {
        val size = myState.conversationFontSize.coerceIn(MIN_FONT_SIZE, MAX_FONT_SIZE)
        val family = EditorColorsManager.getInstance().globalScheme.editorFontName
        // Match Editor > Font family; StyleContext adds CJK fallback the editor uses.
        return StyleContext.getDefaultStyleContext().getFont(family, Font.PLAIN, size)
    }

    companion object {
        const val DEFAULT_FONT_SIZE = 14
        const val MIN_FONT_SIZE = 8
        const val MAX_FONT_SIZE = 32

        fun getInstance(): PiSettings = service()
    }
}
