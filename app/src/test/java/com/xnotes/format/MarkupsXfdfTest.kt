package com.xnotes.format

import com.xnotes.core.model.MarkupType
import com.xnotes.core.model.Page
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.TextMarkup
import com.xnotes.core.pdf.PdfPageGeometry
import com.xnotes.core.pdf.TextQuad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkupsXfdfTest {

    private fun markup(
        type: MarkupType,
        vararg quads: TextQuad,
        text: String = "marked",
        note: String? = null,
        intensity: Double = 0.5,
    ) = TextMarkup(TextMarkup.newId(), type, Rgba(255, 92, 92), intensity, quads.toList(), text, note, 1_759_320_000_000L, 1_759_320_065_000L)

    private val upright = TextQuad(100f, 200f, 240.5f, 214.25f, 0)
    private val turned = TextQuad(300f, 100f, 314f, 240f, 1)

    private fun pages(n: Int) = List(n) { Page(1240.0, 1754.0) }

    private fun roundTrip(src: List<Page>, geometry: (Int) -> PdfPageGeometry = { PdfPageGeometry.flipped(842.0) }): List<Page> {
        val bytes = MarkupsXfdf.write(src, geometry)
        return pages(src.size).also { MarkupsXfdf.readInto(it, bytes) }
    }

    private fun assertSameMarkup(a: TextMarkup, b: TextMarkup) {
        assertEquals(a.id, b.id)
        assertEquals(a.type, b.type)
        assertEquals(a.color, b.color)
        assertEquals(a.intensity, b.intensity, 1e-9)
        assertEquals(a.quads, b.quads)
        assertEquals(a.text, b.text)
        assertEquals(a.note, b.note)
        assertEquals(a.created, b.created)
        assertEquals(a.modified, b.modified)
    }

    @Test
    fun everythingComesBack() {
        val src = pages(3)
        val tricky = "a & b < c > d \"q\" 'apos'\n\tnext line 𝑥 日本語 שלום"
        src[0].markups = listOf(
            markup(MarkupType.HIGHLIGHT, upright, turned, text = tricky, note = "check eq. 4\nand <this> & that\r\nok"),
            markup(MarkupType.UNDERLINE, upright, intensity = 0.8),
        )
        src[2].markups = listOf(
            markup(MarkupType.STRIKEOUT, TextQuad(1f, 2f, 3f, 4f, 2)),
            markup(MarkupType.SQUIGGLY, TextQuad(5f, 6f, 7f, 8f, 3), note = ""),
        )
        val back = roundTrip(src)
        assertEquals(2, back[0].markups.size)
        assertTrue(back[1].markups.isEmpty())
        assertEquals(2, back[2].markups.size)
        assertSameMarkup(src[0].markups[0].copy(note = "check eq. 4\nand <this> & that\nok"), back[0].markups[0])
        assertSameMarkup(src[0].markups[1], back[0].markups[1])
        assertSameMarkup(src[2].markups[0], back[2].markups[0])
        // An empty note reads back as none.
        assertSameMarkup(src[2].markups[1].copy(note = null), back[2].markups[1])
    }

    @Test
    fun writingTwiceGivesTheSameBytes() {
        val src = pages(1)
        src[0].markups = listOf(markup(MarkupType.HIGHLIGHT, upright, note = "n"))
        val geometry = { _: Int -> PdfPageGeometry.of(0.0, 0.0, 612.0, 792.0, 90) }
        assertEquals(
            String(MarkupsXfdf.write(src, geometry), Charsets.UTF_8),
            String(MarkupsXfdf.write(src, geometry), Charsets.UTF_8),
        )
    }

    @Test
    fun coordsAreInTheSourcePagesUserSpace() {
        val src = pages(1)
        src[0].markups = listOf(markup(MarkupType.HIGHLIGHT, upright))
        // Turned a quarter clockwise: display (x, y) is user (left + y, bottom + x).
        val xml = String(MarkupsXfdf.write(src) { PdfPageGeometry.of(10.0, 20.0, 622.0, 812.0, 90) }, Charsets.UTF_8)
        val coords = Regex(""" coords="([^"]*)"""").find(xml)!!.groupValues[1]
        assertEquals("210,120,210,260.5,224.25,120,224.25,260.5", coords)
        val rect = Regex(""" rect="([^"]*)"""").find(xml)!!.groupValues[1]
        assertEquals("210,120,224.25,260.5", rect)
        assertTrue(xml.contains("""<highlight page="0" """))
        assertTrue(xml.contains(""" opacity="0.5""""))
        assertTrue(xml.contains(""" creationdate="D:20251001120000Z""""))
        assertTrue(xml.contains(""" date="D:20251001120105Z""""))
    }

    @Test
    fun readingIsForgiving() {
        val two = pages(2)
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <xfdf xmlns="http://ns.adobe.com/xfdf/" xmlns:xnotes="urn:xnotes:markups:1.0">
              <annots>
                <circle page="0" rect="0,0,1,1"/>
                <highlight page="0" xnotes:quads="1,2,3,4,0" creationdate="D:2026" color="bogus"/>
                <underline page="1" opacity="0.4" date="D:20260102030405+05'30'" xnotes:quads="1,2,3,4,7"/>
                <strikeout page="5" xnotes:quads="1,2,3,4,0"/>
                <squiggly page="0" coords="1,2,3,4,5,6,7,8"/>
                <highlight page="0" xnotes:quads="1,2,3"/>
                <highlight page="0" xnotes:quads="3,2,1,4,0"/>
              </annots>
            </xfdf>"""
        MarkupsXfdf.readInto(two, xml.toByteArray())
        val h = two[0].markups.single()
        assertEquals(MarkupType.HIGHLIGHT, h.type)
        assertEquals(Rgba(255, 255, 0), h.color)
        assertEquals(1_767_225_600_000L, h.created) // 2026-01-01 UTC
        assertEquals(h.created, h.modified)
        assertNull(h.note)
        val u = two[1].markups.single()
        assertEquals(0.4, u.intensity, 1e-9)
        assertEquals(0, u.quads.single().quarter)
        assertEquals(1_767_303_245_000L, u.modified) // 03:04:05 at +05:30 is 21:34:05 the day before
        MarkupsXfdf.readInto(two, "<xfdf><annots><highlight".toByteArray())
        assertEquals(1, two[0].markups.size)
    }

    @Test
    fun aDocumentTypeIsRefused() {
        val one = pages(1)
        val xml = """<?xml version="1.0"?>
            <!DOCTYPE xfdf [<!ENTITY x "boom">]>
            <xfdf xmlns="http://ns.adobe.com/xfdf/" xmlns:xnotes="urn:xnotes:markups:1.0"><annots>
              <highlight page="0" xnotes:quads="1,2,3,4,0" xnotes:text="&x;"/>
            </annots></xfdf>"""
        MarkupsXfdf.readInto(one, xml.toByteArray())
        assertTrue(one[0].markups.isEmpty())
    }
}
