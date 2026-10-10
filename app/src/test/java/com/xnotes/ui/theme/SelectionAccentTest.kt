package com.xnotes.ui.theme

import com.xnotes.core.model.Rgba
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionAccentTest {

    private val white = Rgba(255, 255, 255)
    private val black = Rgba(0, 0, 0)

    @Test fun lightAccentDarkensOnLightPaper() {
        val accent = Rgba(200, 255, 200)
        val out = ColorMath.contrastingOn(accent, white)
        assertTrue(ColorMath.contrastRatio(out, white) >= 3.0)
        assertTrue(ColorMath.relativeLuminance(out) < ColorMath.relativeLuminance(accent))
    }

    @Test fun darkAccentLightensOnDarkPaper() {
        val accent = Rgba(20, 60, 30)
        val out = ColorMath.contrastingOn(accent, black)
        assertTrue(ColorMath.contrastRatio(out, black) >= 3.0)
        assertTrue(ColorMath.relativeLuminance(out) > ColorMath.relativeLuminance(accent))
    }

    @Test fun accentThatAlreadyStandsOutIsKept() {
        val accent = Rgba(30, 90, 200)
        assertEquals(accent, ColorMath.contrastingOn(accent, white))
    }

    @Test fun glyphStaysReadableOnPushedAccent() {
        val m = MaterialColors.seeded(Rgba(0, 230, 118), dark = true)
        val p = Palette.materialDark(m)
        val sel = p.selectionAccent(white)
        assertTrue(ColorMath.contrastRatio(p.onSelectionAccent(white), sel) >= 3.0)
    }
}
