package com.xnotes.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelTypesTest {

    @Test fun a4PortraitPixelsAt150Dpi() {
        val (w, h) = PageSize.A4.pixels(Orientation.PORTRAIT, 150)
        // spec 02 §1: A4 portrait @150 DPI ~ 1240 x 1754
        assertEquals(1240.157, w, 0.01)
        assertEquals(1753.937, h, 0.01)
    }

    @Test fun landscapeTransposesSides() {
        val (pw, ph) = PageSize.A4.pixels(Orientation.PORTRAIT, 150)
        val (lw, lh) = PageSize.A4.pixels(Orientation.LANDSCAPE, 150)
        assertEquals(pw, lh, 1e-9)
        assertEquals(ph, lw, 1e-9)
    }

    @Test fun pageSizeNameLookupIsForgiving() {
        assertEquals(PageSize.A4, PageSize.fromName(null))
        assertEquals(PageSize.A4, PageSize.fromName("nonsense"))
        assertEquals(PageSize.SLIDE_16_9, PageSize.fromName("Slide 16:9"))
        assertEquals(PageSize.LETTER, PageSize.fromName("letter"))
    }

    @Test fun orientationName() {
        assertEquals(Orientation.LANDSCAPE, Orientation.fromName("landscape"))
        assertEquals(Orientation.PORTRAIT, Orientation.fromName("bogus"))
        assertEquals("portrait", Orientation.PORTRAIT.toName())
    }

    @Test fun rgbaAlphaScaleForHighlighter() {
        val green = Rgba(0, 230, 118, 255)
        assertEquals(89, green.scaleAlpha(0.35).a) // 255 * 0.35 = 89.25 -> 89
    }

    @Test fun rgbaArgbPacking() {
        assertEquals(0xFF00E676.toInt(), Rgba(0, 230, 118, 255).toArgb())
        assertEquals(Rgba(0, 230, 118, 255), Rgba.fromArgb(0xFF00E676.toInt()))
    }

    @Test fun rgbaFromListForgiving() {
        assertEquals(Rgba(1, 2, 3, 255), Rgba.fromList(listOf(1, 2, 3)))
        assertEquals(Rgba(1, 2, 3, 4), Rgba.fromList(listOf(1, 2, 3, 4)))
        assertNull(Rgba.fromList(listOf(1, 2)))
        assertNull(Rgba.fromList(null))
    }

    @Test fun rgbaHexRoundTrip() {
        assertEquals(Rgba(0, 230, 118), Rgba.fromHex("#00e676"))
        assertEquals("#00e676", Rgba.toHex(Rgba(0, 230, 118, 255)))
        assertNull(Rgba.fromHex("not-a-color"))
    }
}
