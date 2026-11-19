package com.piterminal.bridge.clipboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ClipboardPathParserTest {
    private val home = "/home/u"

    private fun parse(text: String) = ClipboardPathParser.parse(text, home)

    @Test fun `project relative with line`() {
        val loc = parse("src/main/kotlin/com/piterminal/bridge/PiSettings.kt:76")!!
        assertEquals("src/main/kotlin/com/piterminal/bridge/PiSettings.kt", loc.path)
        assertEquals(76, loc.line)
        assertNull(loc.column)
    }

    @Test fun `pi file ref with range`() {
        val loc = parse("@src/a.go#L10-25")!!
        assertEquals("src/a.go", loc.path)
        assertEquals(10, loc.line)
        assertEquals(25, loc.endLine)
    }

    @Test fun `file uri`() {
        assertEquals("/home/u/proj/a.kt", parse("file:///home/u/proj/a.kt")!!.path)
        assertEquals("/home/u/proj/a.kt", parse("file://localhost/home/u/proj/a.kt")!!.path)
        assertEquals("C:/proj/a.kt", parse("file:///C:/proj/a.kt")!!.path)
    }

    @Test fun `windows absolute with line and column`() {
        val loc = parse("""C:\proj\a.kt:10:2""")!!
        assertEquals("""C:\proj\a.kt""", loc.path)
        assertEquals(10, loc.line)
        assertEquals(2, loc.column)
    }

    @Test fun `windows drive is not a line number`() {
        assertNull(parse("C:76"))
        val loc = parse("""C:\proj\a.kt:76""")!!
        assertEquals("""C:\proj\a.kt""", loc.path)
        assertEquals(76, loc.line)
    }

    @Test fun `quoted basename with line`() {
        val loc = parse("`PiSettings.kt:76`")!!
        assertEquals("PiSettings.kt", loc.path)
        assertEquals(76, loc.line)
    }

    @Test fun `compiler location tail`() {
        val loc = parse("src/Foo.kt(76,12)")!!
        assertEquals("src/Foo.kt", loc.path)
        assertEquals(76, loc.line)
        assertEquals(12, loc.column)
    }

    @Test fun `at prefix and wrapping parens`() {
        val loc = parse("at (/home/ubuntu/project/index.js:12:55)")!!
        assertEquals("/home/ubuntu/project/index.js", loc.path)
        assertEquals(12, loc.line)
        assertEquals(55, loc.column)
    }

    @Test fun `home expansion`() {
        assertEquals("/home/u/proj/a.kt", parse("~/proj/a.kt")!!.path)
    }

    @Test fun `hash line query`() {
        val loc = parse("Foo.kt#line=76")!!
        assertEquals("Foo.kt", loc.path)
        assertEquals(76, loc.line)
    }

    @Test fun `rejects tool heading and prose`() {
        assertNull(parse("read src/a.kt:76-90"))
        assertNull(parse("https://github.com/foo"))
        assertNull(parse("hello world"))
        assertNull(parse("src/a.kt\nsrc/b.kt"))
        assertNull(parse(""))
        assertNull(parse("git commit -m"))
        assertNull(parse("error"))
        assertNull(parse("error:1"))
    }

    @Test fun `drops nonsense line numbers but keeps a real path`() {
        val loc = parse("src/a.kt:1000000000")!!
        assertEquals("src/a.kt", loc.path)
        assertNull(loc.line)
    }

    @Test fun `quoted path may contain spaces`() {
        val loc = parse("\"My Documents/a.kt:3\"")!!
        assertEquals("My Documents/a.kt", loc.path)
        assertEquals(3, loc.line)
    }
}
