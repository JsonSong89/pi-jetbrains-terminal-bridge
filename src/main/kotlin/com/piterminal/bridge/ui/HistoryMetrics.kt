package com.piterminal.bridge.ui

import java.awt.Font
import java.awt.font.FontRenderContext
import java.awt.font.LineBreakMeasurer
import java.awt.font.TextAttribute
import java.text.AttributedString
import java.text.BreakIterator
import java.util.LinkedHashMap

/**
 * Visual (wrapped) line counting for conversation history cards.
 *
 * [javax.swing.JTextArea.getRows] counts *logical* lines, so a 4k-char
 * single-paragraph message reports `rows = 1` and the card renders one clipped
 * line. Cards need the number of wrapped lines the text occupies at the current
 * width instead.
 *
 * Measurement uses [LineBreakMeasurer] with a line [BreakIterator], which wraps
 * at word boundaries (so it matches `wrapStyleWord = true`) and handles CJK.
 * Results are memoized per (text, font, width).
 */
internal object HistoryMetrics {

    private const val CACHE_LIMIT = 128

    /** Hard stop for pathological input; callers cap rows separately. */
    private const val MAX_LINES = 5_000

    private val fontRenderContext = FontRenderContext(null, true, false)

    private data class Key(val text: String, val fontKey: String, val width: Int)

    private val cache = object : LinkedHashMap<Key, Int>(CACHE_LIMIT, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Int>): Boolean = size > CACHE_LIMIT
    }

    /** Number of visual lines [text] needs when wrapped to [wrapWidth] pixels. */
    fun visualLineCount(text: String, font: Font, wrapWidth: Int): Int {
        if (text.isEmpty()) return 1
        val width = wrapWidth.coerceAtLeast(1)
        val key = Key(text, font.name + '/' + font.style + '/' + font.size, width)
        synchronized(cache) { cache[key] }?.let { return it }
        val lines = measure(text, font, width)
        synchronized(cache) { cache[key] = lines }
        return lines
    }

    private fun measure(text: String, font: Font, width: Int): Int {
        var lines = 0
        var start = 0
        while (true) {
            var end = text.indexOf('\n', start)
            if (end < 0) end = text.length
            lines += wrappedLines(text, start, end, font, width)
            if (lines >= MAX_LINES) return MAX_LINES
            if (end >= text.length) return lines.coerceAtLeast(1)
            start = end + 1
        }
    }

    private fun wrappedLines(text: String, start: Int, end: Int, font: Font, width: Int): Int {
        if (start >= end) return 1
        val attributed = AttributedString(text.substring(start, end))
        attributed.addAttribute(TextAttribute.FONT, font)
        val measurer = LineBreakMeasurer(attributed.iterator, BreakIterator.getLineInstance(), fontRenderContext)
        val limit = width.toFloat()
        var lines = 0
        while (measurer.position < attributed.length) {
            if (measurer.nextLayout(limit) == null) break
            lines++
            if (lines >= MAX_LINES) break
        }
        return lines.coerceAtLeast(1)
    }
}
