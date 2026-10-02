package com.xnotes.core.search

import com.xnotes.core.pdf.FakePageText
import com.xnotes.core.pdf.PageText
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class SearchTextTest {

    /** The source text of each match of [query] in [source], traced back through the map. */
    private fun found(source: String, query: String, matchCase: Boolean = false, wholeWords: Boolean = false): List<String> {
        val t = SearchText.of(source)
        val m = t.find(SearchQuery(query, matchCase, wholeWords))
        return (m.indices step 2).map { source.substring(t.sourceStart(m[it]), t.sourceEnd(m[it + 1])) }
    }

    @Test
    fun caseFoldsUnlessItMatters() {
        assertEquals(listOf("The", "THE", "the"), found("The cat. THE END, the", "the"))
        assertEquals(listOf("The"), found("The cat. THE END, the", "The", matchCase = true))
    }

    @Test
    fun accentsFoldBothWays() {
        assertEquals(listOf("Café", "cafe", "CAFÉ"), found("Café crème, cafe, CAFÉ", "cafe"))
        assertEquals(listOf("Café", "cafe", "CAFÉ"), found("Café crème, cafe, CAFÉ", "CAFÉ"))
        assertEquals(listOf("crème"), found("Café crème", "creme"))
        assertEquals(listOf("Tiếng Việt"), found("Tiếng Việt", "tieng viet"))
    }

    @Test
    fun matchCaseStillFoldsAccents() {
        assertEquals(listOf("École"), found("École, ecole", "Ecole", matchCase = true))
        assertEquals(listOf("ecole"), found("École, ecole", "ecole", matchCase = true))
    }

    @Test
    fun decomposedAccentsMatchAndGoWithTheirLetter() {
        val decomposed = "cafe\u0301 au lait"
        assertEquals(listOf("cafe\u0301"), found(decomposed, "café"))
        assertEquals(listOf("cafe\u0301"), found(decomposed, "cafe"))
        assertEquals(listOf("cafe\u0301 au"), found(decomposed, "cafe au"))
    }

    @Test
    fun marksThatAreNotAccentsStay() {
        assertEquals(listOf("かっこう"), found("がっこう かっこう", "かっこう"))
        assertEquals(listOf("がっこう"), found("がっこう かっこう", "がっこう"))
        assertEquals(listOf("мои"), found("мой мои", "мои"))
        assertEquals(listOf("कताब"), found("किताब कताब", "कताब"))
        assertEquals(listOf("하"), found("한국 하국", "하"))
    }

    @Test
    fun hebrewPointsAndArabicVowelMarksFold() {
        assertEquals(listOf("שָׁלוֹם"), found("שָׁלוֹם", "שלום"))
        assertEquals(listOf("مُحَمَّد"), found("مُحَمَّد", "محمد"))
        assertEquals(listOf("آمن"), found("آمن", "امن"))
        assertEquals(listOf("كتـــاب"), found("كتـــاب", "كتاب"))
    }

    @Test
    fun greekTonosAndFinalSigmaFold() {
        assertEquals(listOf("Ελληνικός"), found("Ελληνικός", "ελληνικοσ"))
        assertEquals(listOf("ΕΛΛΗΝΙΚΟΣ"), found("ΕΛΛΗΝΙΚΟΣ", "ελληνικός"))
    }

    @Test
    fun strokeLettersFoldLikeAccents() {
        assertEquals(listOf("Łódź"), found("Łódź", "lodz"))
        assertEquals(listOf("København"), found("København", "kobenhavn"))
    }

    @Test
    fun dottedCapitalIKeepsItsMapEntry() {
        assertEquals(listOf("İstanbul"), found("İstanbul", "istanbul"))
        assertEquals(listOf("İstanbul"), found("İstanbul", "Istanbul", matchCase = true))
        val t = SearchText.of("İİ x")
        assertEquals("İİ x", t.text)
        assertArrayEquals(intArrayOf(0, 1, 1, 2), t.find(SearchQuery("i")))
    }

    @Test
    fun compatibilityFormsAreSpeltOut() {
        assertEquals(listOf("ﬁnal"), found("the ﬁnal ﬂoor", "final"))
        assertEquals(listOf("ﬂoor"), found("the ﬁnal ﬂoor", "floor"))
        assertEquals(listOf("ﬁ"), found("the ﬁnal", "fi"))
        assertEquals(listOf("ﬁ"), found("the ﬁnal", "ﬁ"))
        assertEquals(listOf("…"), found("wait…", "..."))
        assertEquals(listOf("ＡＢＣ"), found("ＡＢＣ", "abc"))
        assertEquals(listOf("x²"), found("x²", "x2"))
        assertEquals(listOf("𝑥"), found("𝑥 + 1", "x"))
        assertEquals(listOf("½"), found("½ cup", "1/2"))
        assertEquals(listOf("𝑥𝑦"), found("𝑥𝑦 = 1", "xy"))
    }

    @Test
    fun quotesAndHyphensStraighten() {
        val s = "don’t “quote” e\u2010mail 2\u22121"
        assertEquals(listOf("don’t"), found(s, "don't"))
        assertEquals(listOf("“quote”"), found(s, "\"quote\""))
        assertEquals(listOf("e\u2010mail"), found(s, "e-mail"))
        assertEquals(listOf("2\u22121"), found(s, "2-1"))
    }

    @Test
    fun invisibleCharactersAndSpacingAccentsVanish() {
        assertEquals(listOf("co\u00ADoperate"), found("co\u00ADoperate", "cooperate"))
        assertEquals(listOf("zero\u200Bwidth"), found("zero\u200Bwidth", "zerowidth"))
        assertEquals(listOf("u¨ber"), found("u¨ber", "über"))
        assertEquals("uber", SearchText.of("u¨ber").text)
    }

    @Test
    fun whitespaceRunsAreOneSpace() {
        assertEquals(listOf("a  \t b\u00A0c"), found("a  \t b\u00A0c", "a b c"))
        assertEquals(listOf("a\nb"), found("a\nb", "a   b"))
        assertEquals("a b", SearchText.of("  a \n b \n").text)
    }

    @Test
    fun queriesAreTrimmedAndBlankOnesFindNothing() {
        assertEquals(listOf("cat"), found("a cat", " cat "))
        assertTrue(SearchQuery("   ").isEmpty)
        assertTrue(SearchQuery("").isEmpty)
        assertTrue(SearchQuery("\u0301").isEmpty)
        assertEquals(0, SearchText.of("a cat").find(SearchQuery("  ")).size)
    }

    @Test
    fun wholeWordsMayNotTouchALetterOrDigit() {
        val s = "cat cats concat (cat) cat's cat9 Cat"
        assertEquals(listOf("cat", "cat", "cat", "Cat"), found(s, "cat", wholeWords = true))
        assertEquals(7, found(s, "cat").size)
        assertEquals(listOf("cafe\u0301"), found("cafe\u0301", "cafe", wholeWords = true))
        assertEquals(emptyList<String>(), found("naïve", "na", wholeWords = true))
        assertEquals(emptyList<String>(), found("नमस्ते", "नमस", wholeWords = true))
    }

    @Test
    fun aWholeWordMatchMissedAtOneSpotIsFoundLater() {
        assertEquals(listOf("ab"), found("abab ab", "ab", wholeWords = true))
    }

    @Test
    fun matchesDoNotOverlap() {
        assertArrayEquals(intArrayOf(0, 2, 2, 4), SearchText.of("aaaaa").find(SearchQuery("aa")))
    }

    @Test
    fun pdfLineBreaksBecomeSpacesAndLineEndHyphensJoin() {
        val page = FakePageText()
            .line("a transfor-", 0f, 0f, hyphen = true)
            .line("mation here", 0f, 14f)
            .line("next line", 0f, 28f)
            .build()
        val t = SearchText.of(page)
        assertEquals("a transformation here next line", t.text)
        val m = t.find(SearchQuery("transformation"))
        assertEquals(2, m.size)
        assertEquals("transfor-\nmation", page.text(t.sourceStart(m[0]), t.sourceEnd(m[1])))
        val across = t.find(SearchQuery("here next"))
        assertEquals("here\nnext", page.text(t.sourceStart(across[0]), t.sourceEnd(across[1])))
    }

    @Test
    fun pdfGeneratedSpacesCountAndTextlessCharactersVanish() {
        val page = FakePageText().line("one␣tw\u0000o", 0f, 0f).build()
        val t = SearchText.of(page)
        assertEquals("one two", t.text)
        val m = t.find(SearchQuery("two"))
        assertEquals(4, t.sourceStart(m[0]))
        assertEquals(8, t.sourceEnd(m[1]))
    }

    @Test
    fun everyCharTracesBackToItsSource() {
        val page = FakePageText()
            .line("Hello,␣big", 0f, 0f)
            .line("wor-", 0f, 14f, hyphen = true)
            .line("ld  end", 0f, 28f)
            .build()
        val t = SearchText.of(page)
        assertEquals("Hello, big world end", t.text)
        for (i in 0 until t.length) {
            val cp = page.codepoint(t.sourceIndex(i))
            if (t.text[i] == ' ') assertTrue("$i", Character.isWhitespace(cp)) else assertEquals("$i", t.text[i].code, cp)
        }
    }

    @Test
    fun charactersBeyondTheBmpTraceBackInBothSources() {
        val page = PageText(intArrayOf('a'.code, 0x1F600, 'b'.code), FloatArray(12), ByteArray(3), null)
        val fromPage = SearchText.of(page)
        assertEquals("a\uD83D\uDE00b", fromPage.text)
        assertArrayEquals(intArrayOf(0, 1, 1, 2), (0 until 4).map(fromPage::sourceIndex).toIntArray())
        val m = fromPage.find(SearchQuery("\uD83D\uDE00"))
        assertEquals(1, fromPage.sourceStart(m[0]))
        assertEquals(2, fromPage.sourceEnd(m[1]))

        val fromString = SearchText.of("a\uD83D\uDE00b")
        assertArrayEquals(intArrayOf(0, 1, 2, 3), (0 until 4).map(fromString::sourceIndex).toIntArray())
        val n = fromString.find(SearchQuery("\uD83D\uDE00"))
        assertEquals(1, fromString.sourceStart(n[0]))
        assertEquals(3, fromString.sourceEnd(n[1]))
    }

    @Test
    fun spelledOutFormsTraceBackToTheirOneSource() {
        val t = SearchText.of("ﬁx it")
        assertEquals("fix it", t.text)
        assertArrayEquals(intArrayOf(0, 0, 1, 2, 3, 4), (0 until 6).map(t::sourceIndex).toIntArray())
        val m = t.find(SearchQuery("fi"))
        assertEquals(0, t.sourceStart(m[0]))
        assertEquals(1, t.sourceEnd(m[1]))
        val i = t.find(SearchQuery("ix"))
        assertEquals(0, t.sourceStart(i[0]))
        assertEquals(2, t.sourceEnd(i[1]))
    }

    @Test
    fun everyMatchTracesBackToSourceThatReadsTheSame() {
        val pool = listOf(
            "a", "b", "é", "e\u0301", "ﬁ", " ", "\n", "\u00AD", "Ä", "𝑥", "\uD83D\uDE00", "ß", "İ", "’", "²", "…", "ـ",
        )
        val rnd = Random(7)
        repeat(2000) {
            val source = buildString { repeat(rnd.nextInt(1, 30)) { append(pool[rnd.nextInt(pool.size)]) } }
            val t = SearchText.of(source)
            if (t.length == 0) return@repeat
            val a = rnd.nextInt(t.length)
            val query = SearchQuery(t.text.substring(a, rnd.nextInt(a + 1, t.length + 1)), matchCase = rnd.nextBoolean())
            if (query.isEmpty) return@repeat
            val m = t.find(query)
            assertTrue("$source / ${query.text}", m.isNotEmpty())
            for (k in m.indices step 2) {
                val back = source.substring(t.sourceStart(m[k]), t.sourceEnd(m[k + 1]))
                assertTrue("$source / ${query.text} / $back", SearchQuery(back, query.matchCase).pattern.contains(query.pattern))
            }
        }
    }

    @Test
    fun leadingAndTrailingSpaceIsTrimmed() {
        val t = SearchText.of("\n abc \n")
        assertEquals("abc", t.text)
        assertEquals(2, t.sourceIndex(0))
        assertEquals(4, t.sourceIndex(2))
    }
}
