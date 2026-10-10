package com.xnotes.core.text

import org.junit.Assert.assertEquals
import org.junit.Test

class PlainMathTest {

    private fun t(input: String) = PlainMath.toLatex(input)

    @Test fun arithmeticStaysAsItIs() {
        assertEquals("1+2=3", t("1+2=3"))
        assertEquals("x+x^{2}=0", t("x+x^2=0"))
        assertEquals("a \\le b", t("a <= b"))
    }

    @Test fun exponentsAndFractions() {
        assertEquals("x^{\\frac{1}{2}}", t("x^(1/2)"))
        assertEquals("e^{2x}", t("e^(2x)"))
        assertEquals("x^{-1}", t("x^-1"))
        assertEquals("\\frac{x+1}{x-1}", t("(x+1)/(x-1)"))
        assertEquals("\\frac{\\sin(x)}{x}", t("sin(x)/x"))
    }

    @Test fun limits() {
        assertEquals("\\lim_{x\\to y}", t("lim x->y"))
        assertEquals("\\lim_{x\\to 0} \\frac{\\sin x}{x}", t("lim x->0 sinx/x"))
        assertEquals("\\lim_{n\\to \\infty} (1+\\frac{1}{n})^{n}", t("lim n->inf (1+1/n)^n"))
        assertEquals("\\lim_{x\\to0}", t("lim_(x->0)"))
        assertEquals("\\lim_{x\\to 0^+}", t("lim x->0+"))
    }

    @Test fun integrals() {
        assertEquals("\\int_{0}^{1} x^{2} \\,dx", t("int_0^1 x^2 dx"))
        assertEquals("\\int_{0}^{\\pi} \\sin x \\,dx", t("int from 0 to pi sin x dx"))
        assertEquals("\\int x \\,dx", t("integral x dx"))
    }

    @Test fun derivatives() {
        assertEquals("\\frac{d}{dx} x^{2}", t("d/dx x^2"))
        assertEquals("\\frac{dy}{dx}", t("dy/dx"))
        assertEquals("\\frac{d^{2}y}{dx^{2}}", t("d^2y/dx^2"))
        assertEquals("\\frac{\\partial f}{\\partial x}", t("partial f/partial x"))
        assertEquals("f'(x)", t("f'(x)"))
    }

    @Test fun sumsAndRoots() {
        assertEquals("\\sum_{i=1}^{n} i^{2}", t("sum_(i=1)^n i^2"))
        assertEquals("\\sqrt{x+1}", t("sqrt(x+1)"))
    }

    @Test fun latexIsLeftAlone() {
        assertEquals("\\frac{a}{b}", t("\\frac{a}{b}"))
    }
}
