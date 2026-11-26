package com.piterminal.bridge.clipboard

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class ParsedLocation(
    val path: String,
    val line: Int? = null,
    val column: Int? = null,
    val endLine: Int? = null,
)

/**
 * Clipboard text → a single file location. No IntelliJ types so the rules can be unit-tested.
 * `null` means the string is not exactly one path-like token.
 */
object ClipboardPathParser {
    private const val MAX_POSITION = 99_999_999
    private val knownNames = setOf(
        "readme", "makefile", "gnumakefile", "dockerfile", "jenkinsfile",
        "license", "copying", "changelog", "gemfile", "rakefile", "procfile",
        "vagrantfile", "podfile", "brewfile",
    )
    private val driveAbs = Regex("^[A-Za-z]:[\\\\/]")
    private val hashL = Regex("#L(\\d+)(?:-(\\d+))?$", RegexOption.IGNORE_CASE)
    private val hashLine = Regex("#line=(\\d+)$", RegexOption.IGNORE_CASE)
    private val compilerTail = Regex("\\((\\d+)[,:](\\d+)\\)$")
    private val colonLineCol = Regex(":(\\d+):(\\d+)$")
    private val colonLineRange = Regex(":(\\d+)-(\\d+)$")
    private val colonLine = Regex(":(\\d+)$")

    fun parse(raw: String?, home: String = System.getProperty("user.home") ?: ""): ParsedLocation? {
        if (raw == null) return null
        var text = raw.replace("\uFEFF", "").replace("\r\n", "\n").replace('\r', '\n').trim()
        if (text.isEmpty() || text.contains('\n')) return null

        val atPrefixed = text.length >= 3 && text.regionMatches(0, "at ", 0, 3, ignoreCase = true)
        if (atPrefixed) text = text.substring(3).trim()

        val quoted = isWrapped(text)
        text = unwrapOnce(text).trim()
        text = text.trimEnd(',', ';', '.')
        if (text.isEmpty()) return null

        text = stripFileUri(text)
        text = expandHome(text, home)
        if (text.isEmpty()) return null
        if (!quoted && text.any { it.isWhitespace() }) return null

        if (text.startsWith("@")) text = text.substring(1)
        if (text.isEmpty()) return null

        // A compiler-style `path(12,34)` tail is the only place a comma may appear; drop it before
        // rejecting comma-bearing prose such as `hello, world`.
        val withoutCompilerTail = compilerTail.find(text)?.let { text.removeRange(it.range) } ?: text
        if (',' in withoutCompilerTail) return null

        val (path, line, column, endLine) = stripPosition(text)
        if (path.isEmpty()) return null
        if (!looksLikePath(path)) return null
        return ParsedLocation(path, line, column, endLine)
    }

    fun looksLikePath(path: String): Boolean {
        val lower = path.lowercase()
        if (lower.startsWith("https:") || lower.startsWith("http:") || lower.startsWith("mailto:")) return false
        if (path.startsWith("/")) return true
        if (driveAbs.containsMatchIn(path)) return true
        if (path.startsWith("\\\\") || path.startsWith("//")) return true
        if (path.contains('/') || path.contains('\\')) return true
        val base = path.substringAfterLast('/').substringAfterLast('\\')
        if (base == "." || base == "..") return false
        if (base.lowercase() in knownNames) return true
        return base.contains('.') && base != "." && base != ".."
    }

    private fun isWrapped(text: String): Boolean {
        if (text.length < 2) return false
        val pair = wrapPair(text.first()) ?: return false
        return text.last() == pair
    }

    private fun wrapPair(start: Char): Char? = when (start) {
        '"' -> '"'
        '\'' -> '\''
        '`' -> '`'
        '<' -> '>'
        '(' -> ')'
        else -> null
    }

    private fun unwrapOnce(text: String): String {
        if (!isWrapped(text)) return text
        return text.substring(1, text.length - 1)
    }

    private fun stripFileUri(text: String): String {
        if (!text.startsWith("file:", ignoreCase = true)) return text
        return try {
            val uri = URI(text)
            if (!uri.scheme.equals("file", ignoreCase = true)) return text
            var path = uri.path ?: ""
            if (path.length >= 3 && path[0] == '/' && path[2] == ':') {
                path = path.substring(1)
            }
            if (path.isEmpty()) text.substringAfter("file:", missingDelimiterValue = text).trimStart('/', '\\')
            else URLDecoder.decode(path, StandardCharsets.UTF_8)
        } catch (_: Exception) {
            text.removePrefix("file://").removePrefix("FILE://").removePrefix("file:").removePrefix("FILE:")
        }
    }

    private fun expandHome(text: String, home: String): String {
        if (home.isEmpty()) return text
        if (text == "~") return home
        if (text.startsWith("~/") || text.startsWith("~\\")) return home + text.substring(1)
        return text
    }

    private data class Positioned(val path: String, val line: Int?, val column: Int?, val endLine: Int?)

    private fun stripPosition(text: String): Positioned {
        hashL.find(text)?.let { m ->
            return positioned(text.removeRange(m.range), m.groupValues[1], null, m.groupValues[2].ifEmpty { null })
        }
        hashLine.find(text)?.let { m ->
            return positioned(text.removeRange(m.range), m.groupValues[1], null, null)
        }
        compilerTail.find(text)?.let { m ->
            return positioned(text.removeRange(m.range), m.groupValues[1], m.groupValues[2], null)
        }

        val driveLen = if (driveAbs.containsMatchIn(text)) 2 else 0
        val body = text.substring(driveLen)
        colonLineCol.find(body)?.let { m ->
            return positioned(text.substring(0, driveLen) + body.removeRange(m.range), m.groupValues[1], m.groupValues[2], null)
        }
        colonLineRange.find(body)?.let { m ->
            return positioned(text.substring(0, driveLen) + body.removeRange(m.range), m.groupValues[1], null, m.groupValues[2])
        }
        colonLine.find(body)?.let { m ->
            return positioned(text.substring(0, driveLen) + body.removeRange(m.range), m.groupValues[1], null, null)
        }
        return Positioned(text, null, null, null)
    }

    private fun positioned(path: String, lineRaw: String, columnRaw: String?, endRaw: String?): Positioned {
        val line = parsePos(lineRaw)
        val column = columnRaw?.let { parsePos(it) }
        val endLine = endRaw?.let { parsePos(it) }
        if (line == null && columnRaw == null && endRaw == null) return Positioned(path, null, null, null)
        if (line == null) return Positioned(path, null, null, null)
        return Positioned(path, line, column, endLine)
    }

    private fun parsePos(raw: String): Int? {
        val n = raw.toLongOrNull() ?: return null
        if (n <= 0 || n > MAX_POSITION) return null
        return n.toInt()
    }
}
