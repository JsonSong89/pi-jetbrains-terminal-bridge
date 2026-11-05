package com.piterminal.bridge.ui

import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.AlphaComposite
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Timer

private inline fun Graphics.antialiased(paint: (Graphics2D) -> Unit) {
    val g2 = create() as Graphics2D
    try {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        paint(g2)
    } finally {
        g2.dispose()
    }
}

/** Borderless icon button with the rounded hover and pressed states of New UI toolbars. */
internal class PiIconButton(icon: Icon) : JButton(icon) {
    init {
        isBorderPainted = false
        isContentAreaFilled = false
        isOpaque = false
        isFocusable = false
        isRolloverEnabled = true
        border = JBUI.Borders.empty()
        margin = JBUI.emptyInsets()
    }

    override fun paintComponent(g: Graphics) {
        val fill = when {
            !isEnabled -> null
            model.isPressed -> JBUI.CurrentTheme.ActionButton.pressedBackground()
            model.isRollover -> JBUI.CurrentTheme.ActionButton.hoverBackground()
            else -> null
        }
        if (fill != null) {
            g.antialiased { g2 ->
                val arc = JBUI.scale(PiPanelStyle.INPUT_ARC).toFloat()
                g2.color = fill
                g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arc, arc))
            }
        }
        super.paintComponent(g)
    }
}

/** Rounded history card: time, body text, then the expand link and always-visible actions. */
internal class PiHistoryCard(
    val body: JBTextArea,
    time: JComponent,
    actions: JComponent,
    expand: JComponent?
) : JPanel(BorderLayout()) {
    private var flashAlpha = 0f
    private var flashTimer: Timer? = null

    init {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        border = PiPanelStyle.cardBorder
        add(time, BorderLayout.NORTH)
        add(body, BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            expand?.let { add(it, BorderLayout.WEST) }
            add(actions, BorderLayout.EAST)
        }, BorderLayout.SOUTH)
    }

    override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)

    fun flash() {
        flashTimer?.stop()
        val startedAt = System.currentTimeMillis()
        flashAlpha = 1f
        repaint()
        flashTimer = Timer(FLASH_FRAME_MS) {
            val progress = (System.currentTimeMillis() - startedAt).toFloat() / PiPanelStyle.FLASH_MS
            flashAlpha = (1f - progress).coerceAtLeast(0f)
            if (flashAlpha == 0f) flashTimer?.stop()
            repaint()
        }.apply { start() }
    }

    override fun removeNotify() {
        flashTimer?.stop()
        flashAlpha = 0f
        super.removeNotify()
    }

    override fun paintComponent(g: Graphics) {
        g.antialiased { g2 ->
            val arc = JBUI.scale(PiPanelStyle.CARD_ARC).toFloat()
            val shape = RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arc, arc)
            g2.color = PiPanelStyle.CARD_BACKGROUND
            g2.fill(shape)
            if (flashAlpha > 0f) {
                g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, flashAlpha)
                g2.color = PiPanelStyle.CARD_FLASH
                g2.fill(shape)
            }
        }
    }

    private companion object {
        const val FLASH_FRAME_MS = 30
    }
}

/** One rounded field holding the editor and its footer; the outline follows editor focus. */
internal class PiInputSurface(
    private val focusTarget: JComponent,
    content: JComponent,
    footer: JComponent
) : JPanel(BorderLayout()) {
    init {
        isOpaque = false
        border = JBUI.Borders.empty(1)
        add(content, BorderLayout.CENTER)
        add(footer, BorderLayout.SOUTH)
        focusTarget.addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) = repaint()
            override fun focusLost(e: FocusEvent) = repaint()
        })
    }

    override fun paintComponent(g: Graphics) {
        g.antialiased { g2 ->
            val arc = JBUI.scale(PiPanelStyle.INPUT_ARC).toFloat()
            g2.color = PiPanelStyle.INPUT_BACKGROUND
            g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arc, arc))
            g2.color = if (focusTarget.isFocusOwner) PiPanelStyle.INPUT_FOCUS_OUTLINE else PiPanelStyle.INPUT_OUTLINE
            g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, arc, arc))
        }
    }
}
