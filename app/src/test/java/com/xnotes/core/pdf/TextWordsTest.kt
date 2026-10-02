package com.xnotes.core.pdf

import org.junit.Assert.assertEquals
import org.junit.Test

class TextWordsTest {

    /** H0 e1 l2 l3 o4 ,5 _6 b7 i8 g9 _10 w11 o12 r13 l14 d15 */
    private val page = FakePageText().line("Hello, big world", 0f, 0f).build()

    @Test
    fun selectsTheWordAroundACharacter() {
        assertEquals(0..4, TextWords.wordAt(page, 2))
        assertEquals(7..9, TextWords.wordAt(page, 7))
        assertEquals(11..15, TextWords.wordAt(page, 15))
    }

    @Test
    fun spacesAndPunctuationStandAlone() {
        assertEquals(5..5, TextWords.wordAt(page, 5))
        assertEquals(6..6, TextWords.wordAt(page, 6))
    }

    @Test
    fun wordsStopAtLineBreaks() {
        val lines = FakePageText().line("one", 0f, 0f).line("two", 0f, 14f).build()
        assertEquals(0..2, TextWords.wordAt(lines, 2))
        assertEquals(5..7, TextWords.wordAt(lines, 5))
        assertEquals(3..3, TextWords.wordAt(lines, 3))
    }

    @Test
    fun aHyphenatedWordIsOneWord() {
        val hyphenated = FakePageText().line("a transfor-", 0f, 0f, hyphen = true).line("mation here", 0f, 14f).build()
        assertEquals(2..16, TextWords.wordAt(hyphenated, 4))
        assertEquals(2..16, TextWords.wordAt(hyphenated, 10))
        assertEquals(2..16, TextWords.wordAt(hyphenated, 13))
        assertEquals(18..21, TextWords.wordAt(hyphenated, 18))
    }

    @Test
    fun glyphsWithoutTextStayInTheirWord() {
        val text = PageText(intArrayOf('a'.code, 0, 'b'.code, 0, ' '.code, 'c'.code, 0), FloatArray(28), ByteArray(7), null)
        assertEquals(0..3, TextWords.wordAt(text, 0))
        assertEquals(0..3, TextWords.wordAt(text, 1))
        assertEquals(5..6, TextWords.wordAt(text, 5))
        assertEquals(6..6, TextWords.wordAt(text, 6))
    }
}
