package com.xnotes.ui

import com.xnotes.core.model.PageSize
import com.xnotes.core.model.PageSizeChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatMmTest {

    private val dpi = PageSize.DEFAULT_DPI

    private fun roundTrip(mm: Double): Double = PageSize.pxToMm(PageSize.mmToPx(mm, dpi), dpi)

    @Test
    fun wholeAndDecimalSidesReadPlainly() {
        assertEquals("210", formatMm(210.0))
        assertEquals("2000", formatMm(2000.0))
        assertEquals("10", formatMm(10.0))
        assertEquals("12.5", formatMm(12.5))
        assertEquals("211.67", formatMm(211.66666666666666))
    }

    @Test
    fun roundTripNoiseNeverShows() {
        assertEquals("13", formatMm(roundTrip(13.0)))
        assertEquals("11.4", formatMm(roundTrip(11.4)))
    }

    @Test
    fun everySettableSideFitsTheFieldAndParsesBack() {
        var hundredths = (PageSize.CUSTOM_MIN_MM * 100).toLong()
        while (hundredths <= (PageSize.CUSTOM_MAX_MM * 100).toLong()) {
            val mm = hundredths / 100.0
            val text = formatMm(roundTrip(mm))
            assertTrue("$mm reads $text", text.length <= 7)
            val back = parseMm(text)
            assertTrue("$mm reads $text", back != null && kotlin.math.abs(back - mm) < 0.005)
            hundredths++
        }
    }

    @Test
    fun pageSizedFromAnOddPdfPageFitsTheField() {
        val choice = PageSizeChoice.of(255.118 * dpi / 72.0, 453.543 * dpi / 72.0, dpi)
        assertEquals(PageSize.CUSTOM, choice.size)
        assertEquals("90", formatMm(choice.customWidthMm))
        assertEquals("160", formatMm(choice.customHeightMm))
    }
}
