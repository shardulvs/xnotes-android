package com.xnotes.core.pdf

import org.junit.Assert.assertEquals
import org.junit.Test

class TextHitTest {

    /** "Hello world" (0-10, x 72..138, y 72..84), CR LF (11, 12), "second line" (13-23, y 90..102). */
    private val page = FakePageText().line("Hello world", 72f, 72f).line("second line", 72f, 90f).build()

    @Test
    fun findsTheCharacterUnderThePoint() {
        assertEquals(0, TextHit.charAt(page, 73f, 78f))
        assertEquals(4, TextHit.charAt(page, 97f, 80f))
        assertEquals(13, TextHit.charAt(page, 75f, 95f))
    }

    @Test
    fun takesTheNearestWithinReach() {
        assertEquals(1, TextHit.charAt(page, 81f, 85f))
        assertEquals(10, TextHit.charAt(page, 150f, 78f))
        assertEquals(-1, TextHit.charAt(page, 300f, 78f))
        assertEquals(10, TextHit.charAt(page, 300f, 78f, reach = Float.POSITIVE_INFINITY))
        assertEquals(10, TextHit.charAt(page, 170f, 78f, slop = 40f))
    }

    @Test
    fun neverHitsInferredCharacters() {
        val spaced = FakePageText().line("a␣b", 0f, 0f).build()
        assertEquals(0, TextHit.charAt(spaced, 7f, 9f))
    }

    @Test
    fun offsetsFallBeforeOrAfterByHalves() {
        assertEquals(0, TextHit.offsetAt(page, 73f, 78f))
        assertEquals(1, TextHit.offsetAt(page, 77f, 78f))
        assertEquals(-1, TextHit.offsetAt(page, 300f, 300f))
    }

    @Test
    fun offsetsFollowTheReadingDirection() {
        val down = FakePageText().line("ab", 100f, 0f, angle = 90).build()
        assertEquals(listOf(0, 1, 2), listOf(TextHit.offsetAt(down, 94f, 2f), TextHit.offsetAt(down, 94f, 4f), TextHit.offsetAt(down, 94f, 10f)))
        val left = FakePageText().line("ab", 100f, 100f, angle = 180).build()
        assertEquals(listOf(0, 1), listOf(TextHit.offsetAt(left, 99f, 94f), TextHit.offsetAt(left, 95f, 94f)))
        val up = FakePageText().line("ab", 0f, 100f, angle = 270).build()
        assertEquals(listOf(0, 1), listOf(TextHit.offsetAt(up, 6f, 99f), TextHit.offsetAt(up, 6f, 95f)))
    }

    @Test
    fun ligaturesAreNeverSplit() {
        val office = PageText(
            intArrayOf('o'.code, 'f'.code, 'f'.code, 'i'.code, 'c'.code, 'e'.code),
            floatArrayOf(0f, 0f, 6f, 12f, 6f, 0f, 12f, 12f, 12f, 0f, 20f, 12f, 12f, 0f, 20f, 12f, 20f, 0f, 26f, 12f, 26f, 0f, 32f, 12f),
            ByteArray(6), null,
        )
        assertEquals(2, TextHit.charAt(office, 15f, 6f))
        assertEquals(2, TextHit.offsetAt(office, 13f, 6f))
        assertEquals(4, TextHit.offsetAt(office, 19f, 6f))
    }
}
