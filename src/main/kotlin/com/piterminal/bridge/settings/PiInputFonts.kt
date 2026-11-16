package com.piterminal.bridge.settings

import java.awt.Font
import java.awt.GraphicsEnvironment

/** One row in the prompt-font dropdown. An empty [family] means the conversation font. */
data class InputFontChoice(
    val family: String,
    val supportsChinese: Boolean,
)

/**
 * Physical font families the prompt may use instead of [PiSettings.conversationFont].
 * Logical fonts (Dialog, Monospaced, …) are omitted: they are composite faces, so Chinese
 * still comes from a fallback and the caret stays on the primary face's integer metrics.
 */
object PiInputFonts {
    private val logicalFamilies = setOf("dialog", "dialoginput", "sansserif", "serif", "monospaced")

    @Volatile
    private var cached: List<InputFontChoice>? = null

    fun choices(): List<InputFontChoice> {
        cached?.let { return it }
        val scanned = scan()
        cached = scanned
        return scanned
    }

    /**
     * [Font] constructed directly, not via [javax.swing.text.StyleContext.getFont].
     * StyleContext wraps faces that miss the default encoding in a composite fallback;
     * the caret then measures the primary face while Chinese glyphs come from the fallback.
     * A missing family falls back to [fallback] (the conversation font).
     */
    fun resolve(family: String, fallback: Font): Font {
        val name = family.trim()
        if (name.isEmpty()) return fallback
        val font = Font(name, Font.PLAIN, fallback.size)
        if (!font.family.equals(name, ignoreCase = true)) return fallback
        return font
    }

    private fun scan(): List<InputFontChoice> {
        val families = GraphicsEnvironment.getLocalGraphicsEnvironment().availableFontFamilyNames
        val found = ArrayList<InputFontChoice>(families.size)
        for (family in families) {
            if (family.lowercase() in logicalFamilies) continue
            val probe = Font(family, Font.PLAIN, 12)
            if (!probe.family.equals(family, ignoreCase = true)) continue
            found.add(InputFontChoice(family, probe.canDisplay('汉')))
        }
        found.sortBy { it.family.lowercase() }
        return listOf(InputFontChoice("", supportsChinese = false)) + found
    }
}
