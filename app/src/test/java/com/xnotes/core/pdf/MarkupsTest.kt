package com.xnotes.core.pdf

import com.xnotes.core.model.MarkupType
import com.xnotes.core.model.Rgba
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkupsTest {

    /** f0 i1 r2 s3 t4 _5 p6 a7 g8 e9 CR10 LF11 e12 n13 d14 s15 _16 h17 e18 r19 e20 */
    private val one = FakePageText().line("first page", 0f, 0f).line("ends here", 0f, 14f).build()

    /** s0 e1 c2 o3 n4 d5 _6 p7 a8 g9 e10 */
    private val two = FakePageText().line("second page", 0f, 0f).build()

    /** Note page 1 has no PDF. */
    private val texts = mapOf(0 to one, 2 to two)

    private val green = Rgba(0, 230, 118, 90)

    @Test
    fun aSelectionAcrossPagesMarksEachPageItCovers() {
        val sel = TextSelection(TextPos(0, 6), TextPos(2, 6))
        val made = Markups.of(sel, { texts[it] }, MarkupType.UNDERLINE, green, 0.5, 42L)
        assertEquals(listOf(0, 2), made.map { it.first })
        val (first, second) = made.map { it.second }
        assertEquals("page\nends here", first.text)
        assertEquals(2, first.quads.size)
        assertEquals("second", second.text)
        assertEquals(TextQuad(0f, 0f, 36f, 12f, 0), second.quads.single())
        assertEquals(MarkupType.UNDERLINE, first.type)
        assertEquals(255, first.color.a)
        assertEquals(0.5, first.intensity, 1e-9)
        assertNull(first.note)
        assertEquals(42L, first.created)
        assertEquals(42L, first.modified)
        assertNotEquals(first.id, second.id)
    }

    @Test
    fun nothingButALineBreakMakesNoMarkup() {
        assertTrue(Markups.of(TextSelection(TextPos(0, 10), TextPos(0, 12)), { texts[it] }, MarkupType.HIGHLIGHT, green, 0.5, 0L).isEmpty())
        assertTrue(Markups.of(TextSelection(TextPos(0, 3), TextPos(0, 3)), { texts[it] }, MarkupType.HIGHLIGHT, green, 0.5, 0L).isEmpty())
    }
}
