package com.xnotes.core.model

import com.xnotes.core.geometry.Pt
import com.xnotes.core.tools.ShapeKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class FunctionCurveTest {

    private fun eval(expr: String, x: Double) = FunctionCurve.parse(expr)!!.invoke(x)

    @Test fun evaluatesTheBasics() {
        assertEquals(9.0, eval("x^2", 3.0), 1e-9)
        assertEquals(2.0, eval("x^(1/2)", 4.0), 1e-9)
        assertEquals(1.0, eval("ln(x)", kotlin.math.E), 1e-9)
        assertEquals(kotlin.math.E, eval("e^x", 1.0), 1e-9)
        assertEquals(-4.0, eval("-x^2", 2.0), 1e-9)
        assertEquals(7.0, eval("2x+1", 3.0), 1e-9)
    }

    @Test fun functionNamesTakeATightArgument() {
        assertEquals(sin(2.0), eval("sin2x", 1.0), 1e-9)
        assertEquals(sin(2.0) + 1, eval("sin 2x + 1", 1.0), 1e-9)
        assertEquals(sin(1.0) * sin(1.0), eval("sin(x)^2", 1.0), 1e-9)
        assertEquals(3 * sin(1.0), eval("3sin(x)", 1.0), 1e-9)
        assertEquals(6.0, eval("x(x+1)", 2.0), 1e-9)
    }

    @Test fun oddRootsOfNegativesStayReal() {
        assertEquals(-2.0, eval("x^(1/3)", -8.0), 1e-9)
        assertTrue(eval("x^(1/2)", -4.0).isNaN())
    }

    @Test fun rejectsGarbage() {
        assertNull(FunctionCurve.parse("sin("))
        assertNull(FunctionCurve.parse("y+1"))
        assertEquals(2 * PI, FunctionCurve.constant("2pi")!!, 1e-9)
    }

    @Test fun samplesFillTheUnitBox() {
        val pts = FunctionSpec("x^2", -2.0, 2.0).normalizedSamples()!!
        assertEquals(0.0, pts.minOf { it.x }, 1e-9)
        assertEquals(1.0, pts.maxOf { it.x }, 1e-9)
        assertEquals(0.0, pts.minOf { it.y }, 1e-9)
        assertEquals(1.0, pts.maxOf { it.y }, 1e-9)
        // y runs down: the vertex of the parabola sits at the bottom of the box.
        assertEquals(1.0, pts[FunctionSpec.SAMPLES / 2].y, 1e-9)
    }

    @Test fun shapeReplotsAndRoundTripsThroughUndo() {
        val s = ShapeItem(ShapeKind.FUNCTION, Pt(0.0, 0.0), Pt(100.0, 50.0), Rgba(0, 0, 0, 255), function = FunctionSpec.preset("sin(x)"))
        assertNotNull(s.vertices())
        val before = s.snapshotGeometry()
        assertTrue(s.setFunction(FunctionSpec("sin(2x)", -PI, PI)))
        assertEquals("sin(2x)", s.function!!.expr)
        assertFalse(s.setFunction(FunctionSpec("nope(", 0.0, 1.0)))
        s.restoreGeometry(before)
        assertEquals("sin(x)", s.function!!.expr)
    }

    @Test fun tangentBreaksAtItsPoles() {
        val pts = FunctionSpec.preset("tan(x)").normalizedSamples()!!
        val runs = FunctionSpec.runs(pts)
        assertEquals(3, runs.size)
        for (run in runs) for (p in run) assertTrue(p.y in -1e-9..1.0 + 1e-9)
        // Each run reaches the clip edges rather than stopping a sample short of them.
        for (run in runs) {
            assertEquals(1.0, run.maxOf { it.y }, 0.02)
            assertEquals(0.0, run.minOf { it.y }, 0.02)
        }
    }

    @Test fun inverseTrigPresetsPlotAsOneUnbrokenRun() {
        for (expr in listOf("arcsin(x)", "arccos(x)", "arctan(x)")) {
            val pts = FunctionSpec.preset(expr).normalizedSamples()!!
            assertEquals(expr, 1, FunctionSpec.runs(pts).size)
            assertEquals(expr, FunctionSpec.SAMPLES + 1, pts.size)
        }
    }

    @Test fun naturalAspectMatchesEqualScales() {
        assertEquals(1.0, FunctionSpec("x^2", -2.0, 2.0).naturalAspect()!!, 1e-9)
        assertEquals(2.0 / (4 * PI), FunctionSpec.preset("sin(x)").naturalAspect()!!, 1e-3)
    }
}
