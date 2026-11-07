package com.piterminal.bridge.ui

import com.intellij.ui.AnimatedIcon
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.border.Border

internal enum class PiStatusTone { RUNNING, WORKING, ATTENTION, CLOSED }

/**
 * Visual language of the conversation panel: spacing, colors and small widget
 * factories. Colors resolve lazily so theme switches apply without a rebuild,
 * and `PiBridge.*` keys let a theme override them.
 */
internal object PiPanelStyle {
    const val CARD_GAP = 8
    const val CARD_ARC = 10
    const val INPUT_ARC = 8
    const val FLASH_MS = 450

    private const val CARD_PADDING_X = 10
    private const val ICON_BUTTON_SIZE = 22
    private const val ROW_GAP = 4
    private const val SMALL_BUTTON_PROPERTY = "ActionToolbar.smallVariant"

    /** Horizontal space a card takes from its body text; wrap measurement depends on it. */
    const val CARD_TEXT_INSETS = 2 * CARD_PADDING_X

    val PANEL_BACKGROUND: Color = JBColor.lazy { UIUtil.getPanelBackground() }
    val SECONDARY_TEXT: Color = JBColor.lazy { JBUI.CurrentTheme.ContextHelp.FOREGROUND }
    val DIVIDER: Color = JBColor.namedColor("Separator.separatorColor", JBColor.border())
    val CARD_BACKGROUND: Color = JBColor.namedColor(
        "PiBridge.HistoryCard.background",
        JBColor.lazy { ColorUtil.mix(UIUtil.getPanelBackground(), UIUtil.getLabelForeground(), 0.05) }
    )
    val CARD_FLASH: Color = JBColor.namedColor("PiBridge.HistoryCard.flash", JBColor(0xE3EDFF, 0x2F3946))
    val INPUT_BACKGROUND: Color = JBColor.lazy { UIUtil.getTextFieldBackground() }
    val INPUT_OUTLINE: Color = JBColor.namedColor("Component.borderColor", JBColor.border())
    val INPUT_FOCUS_OUTLINE: Color = JBColor.lazy { JBUI.CurrentTheme.Focus.focusColor() }

    private val LINK_FOREGROUND: Color = JBColor.namedColor("Link.activeForeground", JBColor(0x2A6ED6, 0x6BA6FF))
    private val STATUS_RUNNING: Color = JBColor.namedColor("PiBridge.Status.running", JBColor(0x208A3C, 0x57965C))
    private val STATUS_ATTENTION: Color = JBColor.namedColor("PiBridge.Status.attention", JBColor(0xC27D04, 0xD6AE58))

    private val runningIcon: Icon = StatusDotIcon(STATUS_RUNNING)
    private val attentionIcon: Icon = StatusDotIcon(STATUS_ATTENTION)
    private val closedIcon: Icon = StatusDotIcon(SECONDARY_TEXT)
    private val workingIcon: Icon by lazy { AnimatedIcon.Default() }

    val headerBarBorder: Border get() = JBUI.Borders.empty(6, 8, 4, 8)
    val headerBorder: Border get() = JBUI.Borders.customLine(DIVIDER, 0, 0, 1, 0)
    // The 4px vertical inset matches PiMessageRail, keeping its marks aligned with the scroll track.
    val historyBorder: Border get() = JBUI.Borders.empty(4, 8, 4, 0)
    val inputPanelBorder: Border get() = JBUI.Borders.empty(8)
    val inputFooterBorder: Border get() = JBUI.Borders.empty(0, 2, 2, 2)
    val cardBorder: Border get() = JBUI.Borders.empty(4, CARD_PADDING_X, 6, CARD_PADDING_X)
    val cardBodyBorder: Border get() = JBUI.Borders.empty(2, 0)

    fun secondaryLabel(text: String = ""): JBLabel = JBLabel(text).apply {
        font = JBUI.Fonts.smallFont()
        foreground = SECONDARY_TEXT
    }

    fun statusLabel(): JBLabel = secondaryLabel().apply {
        border = JBUI.Borders.empty(0, 8, 6, 8)
        iconTextGap = JBUI.scale(2)
    }

    fun applyStatus(label: JBLabel, text: String, tone: PiStatusTone) {
        label.text = text
        label.icon = when (tone) {
            PiStatusTone.RUNNING -> runningIcon
            PiStatusTone.WORKING -> workingIcon
            PiStatusTone.ATTENTION -> attentionIcon
            PiStatusTone.CLOSED -> closedIcon
        }
    }

    fun footerCaption(): JBLabel = secondaryLabel().apply {
        border = JBUI.Borders.empty(0, 4)
    }

    fun emptyHint(text: String): JBLabel = secondaryLabel(text).apply {
        horizontalAlignment = SwingConstants.CENTER
        alignmentX = Component.CENTER_ALIGNMENT
    }

    /** Darcula paints the theme's primary style for any button reporting itself as default. */
    fun primaryButton(text: String): JButton = object : JButton(text) {
        override fun isDefaultButton(): Boolean = true
    }.apply {
        putClientProperty(SMALL_BUTTON_PROPERTY, true)
    }

    fun iconButton(icon: Icon, tip: String, onClick: () -> Unit): JButton = PiIconButton(icon).apply {
        toolTipText = tip
        preferredSize = JBUI.size(ICON_BUTTON_SIZE, ICON_BUTTON_SIZE)
        addActionListener { onClick() }
    }

    fun linkButton(text: String, tip: String, onClick: () -> Unit): JButton = JButton(text).apply {
        isBorderPainted = false
        isContentAreaFilled = false
        isOpaque = false
        isFocusable = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        margin = JBUI.emptyInsets()
        font = JBUI.Fonts.smallFont()
        foreground = LINK_FOREGROUND
        toolTipText = tip
        border = JBUI.Borders.emptyRight(4)
        addActionListener { onClick() }
    }

    fun row(alignment: Int, vararg children: JComponent): JPanel =
        JPanel(FlowLayout(alignment, JBUI.scale(ROW_GAP), 0)).apply {
            isOpaque = false
            children.forEach { add(it) }
        }

    fun styleHistoryScroll(scroll: JBScrollPane) {
        scroll.background = PANEL_BACKGROUND
        scroll.viewport.background = PANEL_BACKGROUND
        scroll.border = historyBorder
    }

    /** The surrounding [PiInputSurface] paints the field, so the editor stays transparent. */
    fun styleInputEditor(area: JBTextArea, scroll: JBScrollPane) {
        area.isOpaque = false
        area.border = JBUI.Borders.empty(6, 8)
        scroll.isOpaque = false
        scroll.viewport.isOpaque = false
        scroll.border = JBUI.Borders.empty()
    }

    private class StatusDotIcon(private val color: Color) : Icon {
        override fun getIconWidth(): Int = JBUI.scale(ICON_BOX)
        override fun getIconHeight(): Int = JBUI.scale(ICON_BOX)

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = color
                val size = JBUI.scale(DOT_SIZE)
                val offset = (getIconWidth() - size) / 2
                g2.fillOval(x + offset, y + offset, size, size)
            } finally {
                g2.dispose()
            }
        }

        private companion object {
            // Same box as the animated icon, so the status text never shifts.
            const val ICON_BOX = 16
            const val DOT_SIZE = 8
        }
    }
}
