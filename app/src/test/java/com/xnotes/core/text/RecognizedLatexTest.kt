package com.xnotes.core.text

import org.junit.Assert.assertEquals
import org.junit.Test

class RecognizedLatexTest {

    private fun c(s: String) = RecognizedLatex.clean(s)

    @Test fun joinsTokens() {
        assertEquals("x^{2}+1=y", c("x ^ { 2 } + 1 = y"))
        assertEquals("\\frac{1}{x+1}", c("\\frac { 1 } { x + 1 }"))
    }

    @Test fun keepsSpaceAfterCommandsBeforeLetters() {
        assertEquals("\\alpha x", c("\\alpha x"))
        assertEquals("\\int_{0}^{1}xdx", c("\\int _ { 0 } ^ { 1 } x d x"))
    }

    @Test fun foldsSpelledOperators() {
        assertEquals("\\lim f(x)=0", c("\\mathrm { ~ \\operatorname* { l i m } ~ f ( x ) = 0 ~ }"))
        assertEquals("\\operatorname{sgn}x", c("\\operatorname { s g n } x"))
    }

    @Test fun dropsPrintedFontWrappers() {
        assertEquals("\\frac{1}{X+1}", c("\\frac { 1 } { \\mathrm { X + 1 } }"))
        assertEquals("x^{2}", c("\\mathbf { x } ^ { \\mathbf { 2 } }"))
        assertEquals("\\mathcal{Y}", c("\\mathcal { Y }"))
    }
}
