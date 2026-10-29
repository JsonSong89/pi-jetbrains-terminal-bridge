package com.piterminal.bridge.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Rectangle2D
import javax.swing.JComponent
import javax.swing.ToolTipManager

/**
 * Thin message rail drawn next to the conversation history scroll bar.
 *
 * One slot per user message, distributed **uniformly by index** (never by
 * content height): a single huge message must not squeeze the remaining ticks
 * into a few pixels — that is exactly the case this rail exists for.
 *
 * The rail only knows the message count, the index of the message currently at
 * the viewport top, and (via [titleProvider]) hover text.
 */
class PiMessageRail : JComponent() {

    /** Invoked with the message index when a slot is clicked. */
    var onJump: ((Int) -> Unit)? = null

    /** Hover text for a slot; returning null hides the tooltip. */
    var titleProvider: ((Int) -> String?)? = null

    private var messageCount = 0
    private var highlight = -1
    private var hoverIndex = -1

    init {
        isOpaque = false
        // Makes Swing call getToolTipText(MouseEvent) for this component.
        toolTipText = ""
        preferredSize = Dimension(JBUI.scale(RAIL_WIDTH), 0)
        minimumSize = Dimension(JBUI.scale(RAIL_WIDTH), 0)
        // Match the scroll pane's own 4px top/bottom border so marks line up
        // with the scroll bar track.
        border = JBUI.Borders.empty(4, 0, 4, JBUI.scale(2))
        cursor = Cursor.getDefaultCursor()
        ToolTipManager.sharedInstance().registerComponent(this)

        val mouse = object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                slotIndexAt(e.y)?.let { index -> onJump?.invoke(index) }
            }

            override fun mouseMoved(e: MouseEvent) {
                val index = slotIndexAt(e.y) ?: -1
                if (index != hoverIndex) {
                    hoverIndex = index
                    cursor = if (index >= 0) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    else Cursor.getDefaultCursor()
                    repaint()
                }
            }

            override fun mouseExited(e: MouseEvent) {
                if (hoverIndex != -1) {
                    hoverIndex = -1
                    cursor = Cursor.getDefaultCursor()
                    repaint()
                }
            }
        }
        addMouseListener(mouse)
        addMouseMotionListener(mouse)
    }

    /** Updates the slot count and which message sits at the viewport top. */
    fun update(count: Int, topIndex: Int) {
        val clampedTop = if (count <= 0) -1 else topIndex.coerceIn(-1, count - 1)
        val changed = count != messageCount || clampedTop != highlight
        messageCount = count
        highlight = clampedTop
        if (hoverIndex >= count) hoverIndex = -1
        if (changed) repaint()
    }

    fun setHighlight(index: Int) {
        val clamped = if (messageCount <= 0) -1 else index.coerceIn(-1, messageCount - 1)
        if (clamped != highlight) {
            highlight = clamped
            repaint()
        }
    }

    override fun getToolTipText(event: MouseEvent): String? {
        val index = slotIndexAt(event.y) ?: return null
        return titleProvider?.invoke(index)
    }

    override fun paintComponent(g: Graphics) {
        if (messageCount <= 0) return
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val area = height - insets.top - insets.bottom
            if (area <= 0) return
            val slot = area.toDouble() / messageCount
            // Below the minimum slot height marks would merge into a solid bar;
            // draw one mark per aggregated slot instead of letting them overlap.
            val minSlot = JBUI.scale(MIN_SLOT_PX).toDouble()
            val step = if (slot < minSlot) Math.ceil(minSlot / slot).toInt().coerceAtLeast(1) else 1
            val tickLength = JBUI.scale(TICK_LENGTH_PX).toDouble()
            val thickness = JBUI.scale(TICK_THICKNESS_PX).toDouble().coerceAtLeast(1.0)
            val right = (width - insets.right).toDouble()
            var index = 0
            while (index < messageCount) {
                val blockEnd = (index + step).coerceAtMost(messageCount)
                val center = insets.top + (index + (blockEnd - index) / 2.0) * slot
                val active = highlight in index until blockEnd
                val hovered = hoverIndex in index until blockEnd
                g2.color = if (active) ACTIVE_MARK else NORMAL_MARK
                val length = if (hovered || active) tickLength else (tickLength - JBUI.scale(1)).coerceAtLeast(1.0)
                g2.fill(Rectangle2D.Double(right - length, center - thickness / 2, length, thickness))
                index = blockEnd
            }
        } finally {
            g2.dispose()
        }
    }

    private fun slotIndexAt(y: Int): Int? {
        if (messageCount <= 0) return null
        val area = height - insets.top - insets.bottom
        if (area <= 0) return null
        val relative = y - insets.top
        if (relative < 0 || relative >= area) return null
        return (relative.toDouble() / area * messageCount).toInt().coerceIn(0, messageCount - 1)
    }

    private companion object {
        const val RAIL_WIDTH = 8
        const val MIN_SLOT_PX = 3
        const val TICK_LENGTH_PX = 4
        const val TICK_THICKNESS_PX = 2

        val NORMAL_MARK: JBColor = JBColor.namedColor("PiBridge.MessageRail.mark", JBColor(0xBFC5CE, 0x5C6066))
        val ACTIVE_MARK: JBColor = JBColor.namedColor("PiBridge.MessageRail.markActive", JBColor(0x3D7EFF, 0x7FA8FF))
    }
}
