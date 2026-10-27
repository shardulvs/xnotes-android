package com.xnotes.format

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.model.Document
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.FontFace
import com.xnotes.core.text.CharStyle
import com.xnotes.core.text.FlowMargins
import com.xnotes.core.text.ListKind
import com.xnotes.core.text.ParaAlign
import com.xnotes.core.text.Paragraph
import com.xnotes.core.text.Run
import com.xnotes.core.text.TextFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.xnotes.core.text.CalloutKind
import com.xnotes.core.text.FlowQuote
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class FlowXmlTest {

    private fun roundTrip(flow: TextFlow): TextFlow =
        TextFlow().also { FlowXml.readInto(it, FlowXml.write(flow)) }

    @Test
    fun fullRoundTrip() {
        val flow = TextFlow().apply {
            margins = FlowMargins(15.0, 12.5, 15.0, 10.0)
            defaultFace = FontFace.SERIF
            defaultSizePt = 14.0
            defaultColor = Rgba(10, 20, 30, 255)
            monoFace = FontFace("JetBrains Mono")
            paragraphs.add(
                Paragraph(
                    mutableListOf(
                        Run("plain "),
                        Run("bold red", CharStyle(bold = true, color = Rgba(255, 0, 0, 255))),
                        Run(" tail", CharStyle(italic = true, underline = true, strike = true, sizePt = 18.0)),
                        Run(" named", CharStyle(face = FontFace("Playfair Display"))),
                    ),
                    align = ParaAlign.JUSTIFY,
                ),
            )
            paragraphs.add(
                Paragraph(mutableListOf(Run("todo item")), indent = 2, list = ListKind.CHECK, checked = true),
            )
            paragraphs.add(Paragraph(mutableListOf(Run("second")), list = ListKind.CHECK))
            paragraphs.add(Paragraph(mutableListOf(Run("numbered")), list = ListKind.ORDERED))
            paragraphs.add(Paragraph())
            paragraphs.add(
                Paragraph(
                    mutableListOf(Run("    if (x)\treturn", CharStyle(code = true, highlight = Rgba(40, 40, 40, 255)))),
                    codeLang = "kotlin",
                ),
            )
        }
        val back = roundTrip(flow)
        assertEquals(flow.margins, back.margins)
        assertEquals(FontFace.SERIF, back.defaultFace)
        assertEquals(FontFace("JetBrains Mono"), back.monoFace)
        assertEquals(14.0, back.defaultSizePt, 1e-9)
        assertEquals(Rgba(10, 20, 30, 255), back.defaultColor)
        assertEquals(flow.plainText(), back.plainText())
        assertEquals(flow.paragraphs.size, back.paragraphs.size)
        for (i in flow.paragraphs.indices) {
            val a = flow.paragraphs[i]
            val b = back.paragraphs[i]
            assertEquals("para $i align", a.align, b.align)
            assertEquals("para $i indent", a.indent, b.indent)
            assertEquals("para $i list", a.list, b.list)
            assertEquals("para $i checked", a.checked, b.checked)
            assertEquals("para $i lang", a.codeLang, b.codeLang)
            assertEquals("para $i runs", a.runs.map { it.text to it.style }, b.runs.map { it.text to it.style })
        }
    }

    @Test
    fun defaultColorReadsLegacyNearWhiteAsAuto() {
        assertNull(roundTrip(TextFlow()).defaultColor)
        val legacy = TextFlow().apply { defaultColor = TextFlow.DEFAULT_COLOR }
        assertNull(roundTrip(legacy).defaultColor)
    }

    @Test
    fun whitespaceSurvivesOdfEncoding() {
        val flow = TextFlow().apply {
            paragraphs.add(Paragraph(mutableListOf(Run("  lead, three   in, trail  "))))
            paragraphs.add(Paragraph(mutableListOf(Run("\t\ttabs\tinside"))))
        }
        assertEquals(flow.plainText(), roundTrip(flow).plainText())
    }

    @Test
    fun plainContentStaysUnstyledInTheMarkup() {
        val flow = TextFlow().apply { paragraphs.add(Paragraph(mutableListOf(Run("just words")))) }
        val xml = String(FlowXml.write(flow), Charsets.UTF_8)
        assertFalse(xml.contains("<text:span"))
        assertFalse(xml.contains("<style:style"))
        assertTrue(xml.contains("<text:p>just words</text:p>"))
    }

    @Test
    fun malformedAndForeignContentIsForgiven() {
        val untouched = TextFlow()
        FlowXml.readInto(untouched, "not xml at all".toByteArray())
        assertTrue(untouched.isEmpty)

        val exotic = """
            <?xml version="1.0"?>
            <office:document-content xmlns:office="urn:o" xmlns:text="urn:t" xmlns:future="urn:f"
                xnotes:margin-left-mm="7" xmlns:xnotes="urn:xnotes:flow:1.0">
              <future:mystery>outside the body, ignored</future:mystery>
              <office:body><office:text>
                <text:p future:attr="x">hi<future:inline> there</future:inline></text:p>
              </office:text></office:body>
            </office:document-content>
        """.trimIndent()
        val flow = TextFlow()
        FlowXml.readInto(flow, exotic.toByteArray())
        assertEquals(7.0, flow.margins.leftMm, 1e-9)
        assertEquals(1, flow.paragraphs.size)
        assertEquals("hi there", flow.paragraphs[0].plainText())
    }

    @Test
    fun bundleCarriesTheFlowOnlyWhenNonEmpty() {
        val codec = DocumentCodec(FakeImageCodec(), FakeTextMeasurer())

        val bare = Document(pages = mutableListOf(Page(100.0, 100.0)))
        val bareOut = ByteArrayOutputStream()
        codec.write(bare, bareOut)
        assertNull(zipEntryNames(bareOut.toByteArray()).find { it == FlowXml.ENTRY_NAME })

        val doc = Document(pages = mutableListOf(Page(100.0, 100.0)))
        doc.flow.paragraphs.add(Paragraph(mutableListOf(Run("flowing", CharStyle(bold = true)))))
        val out = ByteArrayOutputStream()
        codec.write(doc, out)
        assertTrue(zipEntryNames(out.toByteArray()).contains(FlowXml.ENTRY_NAME))
        val back = codec.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals("flowing", back.flow.plainText())
        assertTrue(back.flow.paragraphs[0].runs[0].style.bold)
    }

    @Test
    fun bundleKeepsCustomDefaultsWithNoTypedText() {
        val codec = DocumentCodec(FakeImageCodec(), FakeTextMeasurer())
        val doc = Document(pages = mutableListOf(Page(100.0, 100.0)))
        doc.flow.defaultColor = Rgba(200, 40, 40, 255)
        doc.flow.defaultSizePt = 16.0
        val out = ByteArrayOutputStream()
        codec.write(doc, out)
        assertTrue(zipEntryNames(out.toByteArray()).contains(FlowXml.ENTRY_NAME))
        val back = codec.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(Rgba(200, 40, 40, 255), back.flow.defaultColor)
        assertEquals(16.0, back.flow.defaultSizePt, 1e-9)
        assertTrue(back.flow.isEmpty)
    }

    private fun zipEntryNames(bytes: ByteArray): List<String> {
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                names.add(e.name)
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        return names
    }

    @Test
    fun aHeadingRoundTripsItsLevel() {
        val flow = TextFlow().apply {
            paragraphs.add(Paragraph(mutableListOf(Run("Title", Paragraph.HEADING_STYLE)), headingLevel = 2))
        }
        assertTrue(String(FlowXml.write(flow)).contains("xnotes:heading=\"2\""))
        val back = roundTrip(flow).paragraphs.single()
        assertEquals(2, back.headingLevel)
        assertEquals(Paragraph.HEADING_STYLE, back.runs.single().style)
    }

    @Test
    fun aHeadingWritesTheSizeItsLevelSetsForOlderReaders() {
        val flow = TextFlow().apply {
            defaultSizePt = 12.0
            paragraphs.add(Paragraph(mutableListOf(Run("Title", Paragraph.HEADING_STYLE)), headingLevel = 2))
        }
        assertTrue(String(FlowXml.write(flow)).contains("fo:font-size=\"21.0pt\""))
    }

    @Test
    fun aHeadingSizedFromAnOlderBaseFollowsTheBaseOnceRead() {
        // What earlier versions wrote after an h1 made at 18pt had its base turned down to 12.
        val flow = TextFlow().apply {
            defaultSizePt = 12.0
            paragraphs.add(Paragraph(mutableListOf(Run("Title", CharStyle(bold = true, sizePt = 36.0))), headingLevel = 1))
        }
        val back = roundTrip(flow)
        val p = back.paragraphs.single()
        assertNull(p.runs.single().style.sizePt)
        assertEquals(24.0, p.textSizePt(back), 1e-9)
    }

    @Test
    fun aBodyParagraphWritesNoHeadingAttribute() {
        val flow = TextFlow().apply { paragraphs.add(Paragraph(mutableListOf(Run("body")))) }
        assertFalse(String(FlowXml.write(flow)).contains("xnotes:heading"))
        assertEquals(0, roundTrip(flow).paragraphs.single().headingLevel)
    }

    @Test
    fun anOutOfRangeHeadingAttributeClampsOnRead() {
        val flow = TextFlow().apply { paragraphs.add(Paragraph(mutableListOf(Run("x")), headingLevel = 2)) }
        val xml = String(FlowXml.write(flow)).replace("xnotes:heading=\"2\"", "xnotes:heading=\"99\"")
        val back = TextFlow().also { FlowXml.readInto(it, xml.toByteArray()) }
        assertEquals(6, back.paragraphs.single().headingLevel)
    }

    @Test
    fun aFormulaSurvivesTheRoundTripAsItsLatex() {
        val flow = TextFlow().apply {
            paragraphs.add(
                Paragraph(
                    mutableListOf(
                        Run("mass is "),
                        Run("E=mc^2", CharStyle(math = true)),
                        Run("."),
                    ),
                ),
            )
        }
        val back = roundTrip(flow)
        val runs = back.paragraphs[0].runs
        val formula = runs.first { it.style.math }
        assertEquals("E=mc^2", formula.text)
        assertEquals("mass is E=mc^2.", back.plainText())
        assertFalse(runs.first().style.math)
        assertFalse(runs.last().style.math)
    }

    @Test
    fun ordinaryTextWritesNoMathAttributeAtAll() {
        val flow = TextFlow().apply { paragraphs.add(Paragraph(mutableListOf(Run("plain")))) }
        assertFalse("xnotes:math" in FlowXml.write(flow).decodeToString())
    }

    @Test
    fun aRuleRoundTripsAndPlainParagraphsDoNotMentionIt() {
        val flow = TextFlow().apply {
            paragraphs.add(Paragraph(mutableListOf(Run("a"))))
            paragraphs.add(Paragraph(rule = true))
        }
        val xml = String(FlowXml.write(flow))
        assertEquals(1, Regex("xnotes:rule").findAll(xml).count())
        val back = roundTrip(flow).paragraphs
        assertFalse(back[0].rule)
        assertTrue(back[1].rule)
    }

    @Test
    fun aPageBreakRoundTripsAsOdfBreakBefore() {
        val flow = TextFlow().apply {
            paragraphs.add(Paragraph(mutableListOf(Run("a"))))
            paragraphs.add(Paragraph(mutableListOf(Run("b")), indent = 1, breakBefore = true))
        }
        val xml = String(FlowXml.write(flow))
        assertTrue(xml.contains("fo:break-before=\"page\""))
        val back = roundTrip(flow).paragraphs
        assertFalse(back[0].breakBefore)
        assertTrue(back[1].breakBefore)
        assertEquals(1, back[1].indent)
    }

    @Test
    fun aBreakToAPageRoundTripsBesideTheOdfBreak() {
        val flow = TextFlow().apply {
            paragraphs.add(Paragraph(mutableListOf(Run("a"))))
            paragraphs.add(Paragraph(mutableListOf(Run("b")), breakBefore = true, breakToPage = 4))
            paragraphs.add(Paragraph(mutableListOf(Run("c")), breakToPage = 7))
        }
        val xml = String(FlowXml.write(flow))
        assertTrue(xml.contains("xnotes:break-page=\"4\""))
        assertFalse(xml.contains("xnotes:break-page=\"7\""))
        val back = roundTrip(flow).paragraphs
        assertEquals(4, back[1].breakToPage)
        assertEquals(null, back[2].breakToPage)
    }

    @Test
    fun aListStartRoundTripsAsOdfStartValue() {
        val flow = TextFlow().apply {
            paragraphs.add(Paragraph(mutableListOf(Run("a")), list = ListKind.ORDERED, listStart = 4))
            paragraphs.add(Paragraph(mutableListOf(Run("b")), list = ListKind.ORDERED))
        }
        assertTrue(String(FlowXml.write(flow)).contains("text:start-value=\"4\""))
        val back = roundTrip(flow).paragraphs
        assertEquals(4, back[0].listStart)
        assertEquals(null, back[1].listStart)
    }

    @Test
    fun aLinkRoundTripsAsOdfTextA() {
        val flow = TextFlow().apply {
            paragraphs.add(
                Paragraph(
                    mutableListOf(
                        Run("go "),
                        Run("bold", CharStyle(bold = true, link = "https://a.org/?x=1&y=2")),
                        Run(" on", CharStyle(link = "https://a.org/?x=1&y=2")),
                        Run(" end"),
                    ),
                ),
            )
        }
        val xml = String(FlowXml.write(flow))
        assertEquals(1, Regex("<text:a ").findAll(xml).count())
        assertTrue(xml.contains("xlink:href=\"https://a.org/?x=1&amp;y=2\""))
        val runs = roundTrip(flow).paragraphs.single().runs
        assertEquals(listOf("go ", "bold", " on", " end"), runs.map { it.text })
        assertEquals(listOf(null, "https://a.org/?x=1&y=2", "https://a.org/?x=1&y=2", null), runs.map { it.style.link })
        assertTrue(runs[1].style.bold)
    }

    @Test
    fun aFlowWithoutLinksDeclaresNoXlink() {
        val flow = TextFlow().apply { paragraphs.add(Paragraph(mutableListOf(Run("a")))) }
        assertFalse(String(FlowXml.write(flow)).contains("xlink"))
    }

    @Test
    fun quotesAndCalloutsRoundTripAsContainers() {
        val note = FlowQuote(CalloutKind.NOTE)
        val other = FlowQuote(CalloutKind.NOTE)
        val plain = FlowQuote()
        val flow = TextFlow().apply {
            paragraphs.add(Paragraph(mutableListOf(Run("a")), quote = note))
            paragraphs.add(Paragraph(mutableListOf(Run("b")), list = ListKind.BULLET, quote = note))
            paragraphs.add(Paragraph(mutableListOf(Run("c")), quote = other))
            paragraphs.add(Paragraph(mutableListOf(Run("d"))))
            paragraphs.add(Paragraph(mutableListOf(Run("e")), quote = plain))
        }
        val xml = String(FlowXml.write(flow))
        assertTrue(xml.contains("xnotes:callout=\"note\""))
        val back = roundTrip(flow).paragraphs
        assertTrue(back[0].quote != null && back[0].quote === back[1].quote)
        assertTrue(back[2].quote != null && back[2].quote !== back[1].quote)
        assertEquals(CalloutKind.NOTE, back[2].quote?.kind)
        assertEquals(null, back[3].quote)
        assertEquals(null, back[4].quote?.kind)
        assertNotNull(back[4].quote)
    }
}
