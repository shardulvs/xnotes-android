package com.xnotes.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmojiShortcodesTest {

    @Test
    fun theBundledTableNamesGitHubsEmoji() {
        assertEquals("😄", EmojiShortcodes.emoji("smile"))
        assertEquals("🎉", EmojiShortcodes.emoji("tada"))
        assertEquals("👍", EmojiShortcodes.emoji("+1"))
        assertNull(EmojiShortcodes.emoji("not_an_emoji"))
    }

    @Test
    fun completionPutsTheExactNameFirstThenShorterOnes() {
        val got = EmojiShortcodes.complete("smile")
        assertEquals("smile", got.first().first)
        assertTrue(got.all { it.first.startsWith("smile") })
        assertTrue(got.size <= 8)
        val names = EmojiShortcodes.complete("ca").map { it.first }
        assertEquals(names.sortedBy { it.length }.map { it.length }, names.map { it.length })
    }

    @Test
    fun onlyFreeStandingShortcodesAreReplaced() {
        assertEquals("hi 😄!", EmojiShortcodes.replaceAll("hi :smile:!"))
        assertEquals("at 10:100: sharp", EmojiShortcodes.replaceAll("at 10:100: sharp"))
        assertEquals(":nope: stays", EmojiShortcodes.replaceAll(":nope: stays"))
    }

    @Test
    fun aCompletionOpensOnAColonStartingAWord() {
        val flow = TextFlow().apply { paragraphs.add(Paragraph(mutableListOf(Run("say :sm")))) }
        val q = EmojiShortcodes.queryAt(flow, FlowPos(0, 7))
        assertNotNull(q)
        assertEquals("sm", q!!.text)
        assertEquals(4, q.colon)
        assertNull(EmojiShortcodes.queryAt(flow, FlowPos(0, 6)))
        val word = TextFlow().apply { paragraphs.add(Paragraph(mutableListOf(Run("a:sm")))) }
        assertNull(EmojiShortcodes.queryAt(word, FlowPos(0, 4)))
        val code = TextFlow().apply { paragraphs.add(Paragraph(mutableListOf(Run(":sm", CharStyle(code = true))))) }
        assertNull(EmojiShortcodes.queryAt(code, FlowPos(0, 3)))
    }
}
