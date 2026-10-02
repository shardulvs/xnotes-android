package com.xnotes.core.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTextTest {

    @Test
    fun copiesSpacesAndLineBreaks() {
        val page = FakePageText().line("Hello␣world", 72f, 72f).line("next line", 72f, 86f).build()
        assertEquals("Hello world\nnext line", page.text())
        assertEquals("world\nnext", page.text(6, 17))
    }

    @Test
    fun aLineEndingInAHyphenGetsItsBreakBack() {
        val page = FakePageText().line("transfor-", 72f, 72f, hyphen = true).line("mation", 72f, 86f).build()
        assertTrue(page.isHyphen(8))
        assertEquals("transfor-\nmation", page.text())
        assertEquals("transfor-", page.text(0, 9))
        assertEquals("-\nm", page.text(8, 10))
    }

    @Test
    fun aRangeThatSplitsCrLfStillBreaksOnce() {
        val page = FakePageText().line("ab", 0f, 0f).line("cd", 0f, 14f).build()
        assertEquals("ab\n", page.text(0, 3))
        assertEquals("\ncd", page.text(3, 6))
        assertEquals("b\nc", page.text(1, 5))
        assertTrue(page.isLineBreak(2) && page.isLineBreak(3) && !page.isLineBreak(4))
    }

    @Test
    fun aLoneCrBecomesLf() {
        val page = PageText(intArrayOf('a'.code, '\r'.code, 'b'.code), FloatArray(12), ByteArray(3), null)
        assertEquals("a\nb", page.text())
    }

    @Test
    fun charactersWithoutTextAreLeftOutAndAstralOnesKept() {
        val page = PageText(intArrayOf('x'.code, 0, 0x1D400, 'y'.code), FloatArray(16), ByteArray(4), null)
        assertEquals(4, page.length)
        assertEquals("x𝐀y", page.text())
    }

    @Test
    fun generatedCharactersHaveNoBox() {
        val page = FakePageText(cell = 5f, height = 10f).line("a␣b", 10f, 20f).build()
        assertEquals("a b", page.text())
        assertTrue(page.hasBox(0) && page.hasBox(2))
        assertFalse(page.hasBox(1))
        assertTrue(page.isGenerated(1))
        assertFalse(page.isGenerated(0) || page.isUnmapped(0))
        assertEquals(listOf(20f, 20f, 25f, 30f), listOf(page.left(2), page.top(2), page.right(2), page.bottom(2)))
    }

    @Test
    fun anglesAreZeroUnlessGiven() {
        val flat = FakePageText().line("ab", 0f, 0f).build()
        assertEquals(0f, flat.angle(1))
        val turned = FakePageText(cell = 5f, height = 10f).line("ab", 0f, 0f).line("up", 100f, 100f, angle = 270).build()
        assertEquals(listOf(0f, 0f, 0f, 0f, 270f, 270f), List(turned.length) { turned.angle(it) })
        assertEquals(listOf(100f, 90f, 110f, 95f), listOf(turned.left(5), turned.top(5), turned.right(5), turned.bottom(5)))
    }

    @Test
    fun unmappedGlyphsAreFlagged() {
        val page = PageText(intArrayOf(0x41), FloatArray(4), byteArrayOf(PageText.UNMAPPED.toByte()), null)
        assertTrue(page.isUnmapped(0))
        assertEquals("A", page.text())
    }

    @Test(expected = IllegalArgumentException::class)
    fun misalignedArraysAreRejected() {
        PageText(IntArray(2), FloatArray(4), ByteArray(2), null)
    }
}
