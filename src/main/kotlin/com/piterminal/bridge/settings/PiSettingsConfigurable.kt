package com.piterminal.bridge.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.EmptyIcon
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.piterminal.bridge.bridge.PiBridgeInstaller
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.*

/**
 * Settings UI: Settings → Tools → Pi Terminal Bridge
 */
class PiSettingsConfigurable : Configurable {

    private var panel: JPanel? = null
    private var piCommandField: JBTextField? = null
    private var modelCombo: ComboBox<String>? = null
    private var customModelField: JBTextField? = null
    private var thinkingLevelCombo: ComboBox<String>? = null
    private var extraArgsField: JBTextField? = null
    private var sendShortcutCombo: ComboBox<String>? = null
    private var conversationFontSizeSpinner: JSpinner? = null
    private var inputFontCombo: ComboBox<InputFontChoice>? = null
    private var soundAgentEndCheck: JCheckBox? = null
    private var attentionAgentEndCheck: JCheckBox? = null
    private var openModifiedFilesCheck: JCheckBox? = null

    companion object {
        const val SEND_CTRL_ENTER = "Ctrl+Enter"
        const val SEND_ENTER = "Enter"
        val SEND_SHORTCUTS = arrayOf(SEND_CTRL_ENTER, SEND_ENTER)

        val THINKING_LEVELS = arrayOf(
            "Default",
            "none",
            "low",
            "medium",
            "high",
            "max"
        )

        fun loadModelOptions(): Array<String> {
            val models = PiModelLoader.loadModels()
            val options = mutableListOf("Default")
            models.forEach { options.add("${it.provider}/${it.id}") }
            return options.toTypedArray()
        }

        private val emptyMark: Icon = EmptyIcon.create(JBUI.scale(16))
    }

    override fun getDisplayName(): String = "Pi Terminal Bridge"

    override fun createComponent(): JComponent {
        val settings = PiSettings.getInstance().state

        piCommandField = JBTextField(settings.piCommand)
        val modelOptions = loadModelOptions()
        modelCombo = ComboBox(modelOptions).apply {
            selectedItem = if (settings.model in modelOptions) settings.model else "Default"
            preferredSize = java.awt.Dimension(400, preferredSize.height)
        }
        customModelField = JBTextField(settings.customModelId).apply {
            emptyText.text = "e.g. claude-sonnet-4-20250514"
            preferredSize = java.awt.Dimension(400, preferredSize.height)
        }
        thinkingLevelCombo = ComboBox(THINKING_LEVELS).apply {
            selectedItem = if (settings.thinkingLevel in THINKING_LEVELS) settings.thinkingLevel else "Default"
            preferredSize = java.awt.Dimension(400, preferredSize.height)
        }
        extraArgsField = JBTextField(settings.extraArgs).apply {
            emptyText.text = "e.g. --no-themes — not --session / --resume"
        }
        sendShortcutCombo = ComboBox(SEND_SHORTCUTS).apply {
            selectedItem = if (settings.sendWithCtrlEnter) SEND_CTRL_ENTER else SEND_ENTER
            preferredSize = java.awt.Dimension(400, preferredSize.height)
        }
        conversationFontSizeSpinner = JSpinner(
            SpinnerNumberModel(
                settings.conversationFontSize.coerceIn(PiSettings.MIN_FONT_SIZE, PiSettings.MAX_FONT_SIZE),
                PiSettings.MIN_FONT_SIZE,
                PiSettings.MAX_FONT_SIZE,
                1
            )
        )
        val inputFontChoices = PiInputFonts.choices()
        inputFontCombo = ComboBox(inputFontChoices.toTypedArray()).apply {
            renderer = InputFontRenderer()
            selectedItem = inputFontChoices.firstOrNull { it.family.equals(settings.inputFontFamily, ignoreCase = true) }
                ?: inputFontChoices.first()
            preferredSize = java.awt.Dimension(400, preferredSize.height)
        }
        soundAgentEndCheck = JCheckBox(
            "Play a system sound when the agent finishes",
            settings.soundOnAgentEnd
        )
        attentionAgentEndCheck = JCheckBox(
            "Request taskbar / Dock attention when the agent finishes",
            settings.attentionOnAgentEnd
        )
        openModifiedFilesCheck = JCheckBox(
            "Open files modified by Pi in the editor (the project always refreshes as soon as Pi writes)",
            settings.openModifiedFiles
        )

        panel = FormBuilder.createFormBuilder()
            // Model section
            .addSeparator()
            .addComponent(JBLabel("Model").apply {
                font = font.deriveFont(java.awt.Font.BOLD)
                border = JBUI.Borders.emptyTop(4)
            })
            .addLabeledComponent(JBLabel("Model:"), modelCombo!!, 1, false)
            .addLabeledComponent(JBLabel("Custom model id:"), customModelField!!, 1, false)
            .addComponentToRightColumn(JBLabel("If set, overrides the model dropdown.").apply {
                foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
                font = JBUI.Fonts.smallFont()
            }, 0)
            .addLabeledComponent(JBLabel("Thinking level:"), thinkingLevelCombo!!, 1, false)
            .addComponentToRightColumn(JBLabel("Some models may not support all thinking levels.").apply {
                foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
                font = JBUI.Fonts.smallFont()
            }, 0)

            // General section
            .addSeparator()
            .addComponent(JBLabel("General").apply {
                font = font.deriveFont(java.awt.Font.BOLD)
                border = JBUI.Borders.emptyTop(4)
            })
            .addLabeledComponent(JBLabel("Pi command:"), piCommandField!!, 1, false)
            .addLabeledComponent(JBLabel("Extra arguments:"), extraArgsField!!, 1, false)
            .addComponentToRightColumn(JBLabel("Do not pass --session, --session-id, --resume, --continue or --fork; the plugin sets those. Conflicting flags are dropped at launch.").apply {
                foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
                font = JBUI.Fonts.smallFont()
            }, 0)

            // Options section
            .addSeparator()
            .addComponent(JBLabel("Options").apply {
                font = font.deriveFont(java.awt.Font.BOLD)
                border = JBUI.Borders.emptyTop(4)
            })
            .addLabeledComponent(JBLabel("Send shortcut:"), sendShortcutCombo!!, 1, false)
            .addComponentToRightColumn(JBLabel("Ctrl+Enter avoids IME Enter confirming a candidate and sending by mistake.").apply {
                foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
                font = JBUI.Fonts.smallFont()
            }, 0)
            .addLabeledComponent(JBLabel("Conversation font size:"), conversationFontSizeSpinner!!, 1, false)
            .addComponentToRightColumn(JBLabel("History follows Settings → Editor → Font. This page only overrides the size.").apply {
                foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
                font = JBUI.Fonts.smallFont()
            }, 0)
            .addLabeledComponent(JBLabel("Input font:"), inputFontCombo!!, 1, false)
            .addComponentToRightColumn(JBLabel("Prompt only. Default keeps the conversation font. A 汉 mark means that face includes Chinese, so the caret can follow those glyphs.").apply {
                foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
                font = JBUI.Fonts.smallFont()
            }, 0)
            .addComponent(soundAgentEndCheck!!)
            .addComponent(JBLabel("Sound plays in the foreground and background. System sound and volume settings apply.").apply {
                foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
                font = JBUI.Fonts.smallFont()
            })
            .addComponent(attentionAgentEndCheck!!)
            .addComponent(JBLabel("Shown only while this project's window is not focused; appearance and duration follow the platform. Manual aborts stay silent.").apply {
                foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
                font = JBUI.Fonts.smallFont()
            })
            .addComponent(openModifiedFilesCheck!!)

            // Diagnostics (read-only)
            .addSeparator()
            .addComponent(JBLabel("Bridge diagnostics").apply {
                font = font.deriveFont(java.awt.Font.BOLD)
                border = JBUI.Borders.emptyTop(4)
            })
            .addComponent(JBLabel(bridgeStatusText()).apply {
                foreground = JBUI.CurrentTheme.ContextHelp.FOREGROUND
                font = JBUI.Fonts.smallFont()
            })

            .addComponentFillVertically(JPanel(), 0)
            .panel

        return panel!!
    }

    override fun isModified(): Boolean {
        val settings = PiSettings.getInstance().state
        return piCommandField?.text != settings.piCommand ||
                modelCombo?.selectedItem != settings.model ||
                customModelField?.text != settings.customModelId ||
                thinkingLevelCombo?.selectedItem != settings.thinkingLevel ||
                extraArgsField?.text != settings.extraArgs ||
                (sendShortcutCombo?.selectedItem == SEND_CTRL_ENTER) != settings.sendWithCtrlEnter ||
                fontSizeValue() != settings.conversationFontSize ||
                selectedInputFont() != displayedInputFont(settings.inputFontFamily) ||
                soundAgentEndCheck?.isSelected != settings.soundOnAgentEnd ||
                attentionAgentEndCheck?.isSelected != settings.attentionOnAgentEnd ||
                openModifiedFilesCheck?.isSelected != settings.openModifiedFiles
    }

    override fun apply() {
        val state = PiSettings.getInstance().state
        state.piCommand = piCommandField?.text ?: "pi"
        state.model = modelCombo?.selectedItem as? String ?: "Default"
        state.customModelId = customModelField?.text ?: ""
        state.thinkingLevel = thinkingLevelCombo?.selectedItem as? String ?: "Default"
        state.extraArgs = extraArgsField?.text ?: ""
        state.sendWithCtrlEnter = sendShortcutCombo?.selectedItem == SEND_CTRL_ENTER
        state.conversationFontSize = fontSizeValue()
        state.inputFontFamily = selectedInputFont()
        state.soundOnAgentEnd = soundAgentEndCheck?.isSelected == true
        state.attentionOnAgentEnd = attentionAgentEndCheck?.isSelected == true
        state.openModifiedFiles = openModifiedFilesCheck?.isSelected == true
        PiSettings.getInstance().notifyChanged()
    }

    override fun reset() {
        val settings = PiSettings.getInstance().state
        piCommandField?.text = settings.piCommand
        modelCombo?.selectedItem = settings.model
        customModelField?.text = settings.customModelId
        thinkingLevelCombo?.selectedItem = settings.thinkingLevel
        extraArgsField?.text = settings.extraArgs
        sendShortcutCombo?.selectedItem = if (settings.sendWithCtrlEnter) SEND_CTRL_ENTER else SEND_ENTER
        conversationFontSizeSpinner?.value = settings.conversationFontSize.coerceIn(
            PiSettings.MIN_FONT_SIZE,
            PiSettings.MAX_FONT_SIZE
        )
        inputFontCombo?.let { combo ->
            val choices = (0 until combo.itemCount).map { combo.getItemAt(it) }
            combo.selectedItem = choices.firstOrNull { it.family.equals(settings.inputFontFamily, ignoreCase = true) }
                ?: choices.firstOrNull()
        }
        soundAgentEndCheck?.isSelected = settings.soundOnAgentEnd
        attentionAgentEndCheck?.isSelected = settings.attentionOnAgentEnd
        openModifiedFilesCheck?.isSelected = settings.openModifiedFiles
    }

    private fun fontSizeValue(): Int {
        return (conversationFontSizeSpinner?.value as? Number)?.toInt() ?: PiSettings.DEFAULT_FONT_SIZE
    }

    private fun selectedInputFont(): String =
        (inputFontCombo?.selectedItem as? InputFontChoice)?.family.orEmpty()

    /** A family that disappeared from this machine compares as Default, so opening Settings is not dirty. */
    private fun displayedInputFont(stored: String): String {
        val combo = inputFontCombo ?: return stored.trim()
        return (0 until combo.itemCount)
            .map { combo.getItemAt(it).family }
            .firstOrNull { it.equals(stored, ignoreCase = true) }
            .orEmpty()
    }

    private fun bridgeStatusText(): String {
        val extension = PiBridgeInstaller.diagnostics()
        val server = ProjectManager.getInstance().openProjects.firstOrNull()
            ?.let { com.piterminal.bridge.bridge.PiBridgeServer.getInstance(it).endpoint() }
            ?.let { "server listening on 127.0.0.1:${it.port}" }
            ?: "server not started — starts with the first Pi terminal"
        val windows = if (SystemInfo.isWindows) {
            "\nWindows: PowerShell 7 (pwsh.exe) is recommended as the IDE Terminal shell; cmd.exe cannot run the live bridge."
        } else ""
        return "$extension\n$server$windows"
    }

    override fun disposeUIResources() {
        panel = null
        inputFontCombo = null
    }

    private class InputFontRenderer : ListCellRenderer<InputFontChoice> {
        private val label = JBLabel()

        override fun getListCellRendererComponent(
            list: JList<out InputFontChoice>?,
            value: InputFontChoice?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            label.text = when {
                value == null -> ""
                value.family.isEmpty() -> "Default"
                else -> value.family
            }
            label.icon = if (value?.supportsChinese == true) HanMarkIcon else emptyMark
            if (isSelected && list != null) {
                label.background = list.selectionBackground
                label.foreground = list.selectionForeground
            } else {
                label.background = list?.background
                label.foreground = list?.foreground
            }
            label.isOpaque = true
            label.border = JBUI.Borders.empty(1, 4)
            return label
        }
    }

    private object HanMarkIcon : Icon {
        override fun getIconWidth(): Int = JBUI.scale(16)
        override fun getIconHeight(): Int = JBUI.scale(16)

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                g2.font = JBUI.Fonts.smallFont()
                g2.color = c?.foreground ?: UIManager.getColor("Label.foreground")
                val text = "汉"
                val fm = g2.fontMetrics
                val tx = x + (iconWidth - fm.stringWidth(text)) / 2
                val ty = y + (iconHeight - fm.height) / 2 + fm.ascent
                g2.drawString(text, tx, ty)
            } finally {
                g2.dispose()
            }
        }
    }
}
