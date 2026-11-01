package com.xnotes.platform

import org.junit.Assert.assertEquals
import org.junit.Test

class BraceFractionArgsTest {
    @Test
    fun bareFractionArgumentsTakeOneTokenEach() {
        assertEquals("\\left(\\frac{x}{2}\\right)", braceFractionArgs("\\left(\\frac x2\\right)"))
        assertEquals("\\frac{1}{2}", braceFractionArgs("\\frac12"))
        assertEquals("\\tfrac{1}{2} + \\binom{n}{k}", braceFractionArgs("\\tfrac12 + \\binom nk"))
        assertEquals("\\frac\\pi{2}x", braceFractionArgs("\\frac\\pi2x"))
        assertEquals("\\frac{a+b}{2}c", braceFractionArgs("\\frac{a+b}2c"))
        assertEquals("\\frac{\\frac{1}{2}}{3}", braceFractionArgs("\\frac{\\frac12}3"))
    }

    @Test
    fun bracedAndOtherCommandsAreLeftAlone() {
        for (s in listOf("\\frac{x}{2}", "\\fraction12", "\\sqrt2", "x^23", "\\frac{a}{\\sqrt{2}}", "\\frac{"))
            assertEquals(s, braceFractionArgs(s))
    }
}
