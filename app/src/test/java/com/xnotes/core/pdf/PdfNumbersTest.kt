package com.xnotes.core.pdf

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfNumbersTest {

    @Test
    fun dropsTrailingZerosAndKeepsLeadingOnes() {
        assertEquals("12.5", PdfNumbers.format(12.5))
        assertEquals("12", PdfNumbers.format(12.0))
        assertEquals("0.05", PdfNumbers.format(0.05))
        assertEquals("3.001", PdfNumbers.format(3.001))
        assertEquals("-7.25", PdfNumbers.format(-7.25))
    }

    @Test
    fun roundsToTheRequestedPlaces() {
        assertEquals("1.23", PdfNumbers.format(1.2345, 2))
        assertEquals("1.24", PdfNumbers.format(1.2351, 2))
        assertEquals("2", PdfNumbers.format(1.99999, 3))
        assertEquals("-3", PdfNumbers.format(-2.6, 0))
    }

    @Test
    fun negativeZeroAndTinyValuesWriteZero() {
        assertEquals("0", PdfNumbers.format(-0.0))
        assertEquals("0", PdfNumbers.format(-0.0001))
        assertEquals("0", PdfNumbers.format(0.0004, 3))
    }

    @Test
    fun nonFiniteWritesZeroAndLargeValuesStayPlain() {
        assertEquals("0", PdfNumbers.format(Double.NaN))
        assertEquals("0", PdfNumbers.format(Double.POSITIVE_INFINITY))
        assertEquals("14400.5", PdfNumbers.format(14400.5))
        assertEquals("123456789", PdfNumbers.format(123456789.0))
    }
}
