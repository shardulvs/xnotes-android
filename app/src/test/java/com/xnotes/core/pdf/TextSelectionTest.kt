package com.xnotes.core.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSelectionTest {

    /** f0 i1 r2 s3 t4 _5 p6 a7 g8 e9 CR10 LF11 e12 n13 d14 s15 _16 h17 e18 r19 e20 */
    private val one = FakePageText().line("first page", 0f, 0f).line("ends here", 0f, 14f).build()

    /** s0 e1 c2 o3 n4 d5 _6 p7 a8 g9 e10 */
    private val two = FakePageText().line("second page", 0f, 0f).build()

    /** Note page 1 has no PDF; note pages 2 and 3 show the same PDF page. */
    private val texts = mapOf(0 to one, 2 to two, 3 to two)

    @Test
    fun ordersByPageThenCharacter() {
        val a = TextPos(2, 3)
        val b = TextPos(0, 9)
        assertEquals(TextSelection(b, a), TextSelection.between(a, b))
        assertTrue(TextPos(1, 0) > TextPos(0, 99))
        assertTrue(TextSelection(a, a).isEmpty)
    }

    @Test
    fun copiesAcrossPagesJoinedByLf() {
        assertEquals("page\nends here\nsecond", TextSelection(TextPos(0, 6), TextPos(2, 6)).text { texts[it] })
    }

    @Test
    fun aPageWithNothingSelectedAddsNoLine() {
        assertEquals("second", TextSelection(TextPos(0, one.length), TextPos(2, 6)).text { texts[it] })
    }

    @Test
    fun duplicatedPagesSelectApart() {
        val sel = TextSelection(TextPos(2, 7), TextPos(3, 6))
        assertEquals("page\nsecond", sel.text { texts[it] })
        assertEquals(7 until 11, sel.rangeOn(2, two))
        assertEquals(0 until 6, sel.rangeOn(3, two))
        assertTrue(sel.rangeOn(4, two).isEmpty())
    }

    @Test
    fun oneQuadPerLine() {
        val sel = TextSelection(TextPos(0, 6), TextPos(0, 16))
        assertEquals(listOf(TextQuad(36f, 0f, 60f, 12f, 0), TextQuad(0f, 14f, 24f, 26f, 0)), sel.quads(0, one))
        assertEquals(emptyList<TextQuad>(), sel.quads(1, one))
    }

    @Test
    fun inferredSpacesSitInsideTheirRun() {
        val page = FakePageText().line("ab␣cd", 0f, 0f).build()
        assertEquals(listOf(TextQuad(0f, 0f, 30f, 12f, 0)), TextQuads.of(page, 0, 5))
    }

    @Test
    fun columnsPdfiumJoinedSplitApart() {
        val page = FakePageText().line("left␣␣␣␣␣␣right", 0f, 0f).build()
        assertEquals(listOf(TextQuad(0f, 0f, 24f, 12f, 0), TextQuad(60f, 0f, 90f, 12f, 0)), TextQuads.of(page, 0, page.length))
    }

    @Test
    fun turnedAndColumnSetLinesMakeOneQuadEach() {
        val turned = FakePageText().line("abc", 100f, 0f, angle = 90).build()
        assertEquals(listOf(TextQuad(88f, 0f, 100f, 18f, 1)), TextQuads.of(turned, 0, 3))
        val upright = FakePageText().line("abc", 100f, 0f, vertical = true).build()
        assertEquals(listOf(TextQuad(88f, 0f, 100f, 18f, 0)), TextQuads.of(upright, 0, 3))
        val upsideDown = FakePageText().line("abc", 100f, 100f, angle = 180).build()
        assertEquals(listOf(TextQuad(82f, 88f, 100f, 100f, 2)), TextQuads.of(upsideDown, 0, 3))
    }

    @Test
    fun aLineEndHyphenEndsItsQuad() {
        val page = FakePageText().line("transfor-", 0f, 0f, hyphen = true).line("mation", 0f, 14f).build()
        assertEquals(listOf(TextQuad(0f, 0f, 54f, 12f, 0), TextQuad(0f, 14f, 36f, 26f, 0)), TextQuads.of(page, 0, page.length))
    }

    @Test
    fun caretsSitAtTheLeadingAndTrailingEdges() {
        val sel = TextSelection(TextPos(0, 6), TextPos(0, 16))
        assertEquals(TextCaret(36f, 0f, 36f, 12f), sel.startCaret(one))
        assertEquals(TextCaret(24f, 14f, 24f, 26f), sel.endCaret(one))
        val turned = FakePageText().line("abc", 100f, 0f, angle = 90).build()
        assertEquals(TextCaret(100f, 0f, 88f, 0f), TextQuads.caret(turned, 0, 3, false))
        assertEquals(TextCaret(100f, 18f, 88f, 18f), TextQuads.caret(turned, 0, 3, true))
    }

    @Test
    fun caretsSkipInferredCharacters() {
        val page = FakePageText().line("ab␣cd", 0f, 0f).build()
        assertEquals(TextCaret(18f, 0f, 18f, 12f), TextQuads.caret(page, 2, 5, false))
        assertNull(TextQuads.caret(page, 2, 3, false))
    }
}
