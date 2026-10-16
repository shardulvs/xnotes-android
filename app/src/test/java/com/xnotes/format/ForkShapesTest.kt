package com.xnotes.format

import com.xnotes.core.FakeImageCodec
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.shapes.SolidMathKinds
import com.xnotes.core.shapes.catmullRom
import com.xnotes.core.vector.VectorSeg
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.PI
import kotlin.math.atan2

class ForkShapesTest {

    private val codec = CanvasCodec(FakeImageCodec(), FakeTextMeasurer())

    private fun read(manifest: String): InfiniteDocument {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write(manifest.toByteArray())
            zos.closeEntry()
        }
        return codec.read(ByteArrayInputStream(out.toByteArray()))
    }

    private fun roundTrip(doc: InfiniteDocument): InfiniteDocument {
        val out = ByteArrayOutputStream()
        codec.write(doc, out)
        return codec.read(ByteArrayInputStream(out.toByteArray()))
    }

    /** The fork's own spline, as it drew it. */
    private fun catmullRom(c: List<Pt>, i: Int, t: Double): Pt {
        val p0 = c[maxOf(i - 1, 0)]
        val p1 = c[i]
        val p2 = c[i + 1]
        val p3 = c[minOf(i + 2, c.size - 1)]
        fun f(a: Double, b: Double, cc: Double, d: Double) =
            0.5 * (2 * b + (-a + cc) * t + (2 * a - 5 * b + 4 * cc - d) * t * t + (-a + 3 * b - 3 * cc + d) * t * t * t)
        return Pt(f(p0.x, p1.x, p2.x, p3.x), f(p0.y, p1.y, p2.y, p3.y))
    }

    @Test fun theCurveTracesTheForkSplineExactly() {
        val c = listOf(Pt(0.0, 0.0), Pt(40.0, 90.0), Pt(120.0, 10.0), Pt(160.0, 70.0), Pt(200.0, 0.0))
        val contour = catmullRom(c)
        assertEquals(c.size - 1, contour.segments.size)
        var from = contour.start
        for ((i, seg) in contour.segments.withIndex()) {
            val cubic = seg as VectorSeg.Cubic
            for (k in 0..10) {
                val t = k / 10.0
                val u = 1 - t
                val x = u * u * u * from.x + 3 * u * u * t * cubic.c1.x + 3 * u * t * t * cubic.c2.x + t * t * t * cubic.end.x
                val y = u * u * u * from.y + 3 * u * u * t * cubic.c1.y + 3 * u * t * t * cubic.c2.y + t * t * t * cubic.end.y
                val want = catmullRom(c, i, t)
                assertEquals(want.x, x, 1e-9)
                assertEquals(want.y, y, 1e-9)
            }
            from = cubic.end
        }
    }

    @Test fun aForkCanvasReadsAsUpstreamKindsAndStaysThatWay() {
        val doc = read(
            """{"format":"xcanvas","items":[
              {"kind":"shape","shape":"axes","start":[0,0],"end":[100,80],"stroke_rgba":[1,2,3,255],"stroke_width":2,"fill_rgba":null,"angle":0.5},
              {"kind":"shape","shape":"numberline","start":[0,0],"end":[0,200],"stroke_rgba":[1,2,3,255],"stroke_width":2,"fill_rgba":null},
              {"kind":"shape","shape":"spline","start":[0,0],"end":[200,90],"stroke_rgba":[1,2,3,255],"stroke_width":2,"fill_rgba":null,
               "points":[[0,0],[40,90],[120,10],[200,70]]},
              {"kind":"shape","shape":"function","start":[0,0],"end":[300,200],"stroke_rgba":[1,2,3,255],"stroke_width":2,"fill_rgba":null,
               "points":[[0,0],[10,10],[],[20,0],[30,10]],
               "function":"tan(x)","function_from":-4.71238898,"function_to":4.71238898,"function_ymin":-4,"function_ymax":4}
            ]}""",
        )
        assertEquals(4, codec.forkShapesRead)
        val (axes, line, curve, graph) = doc.items.map { it as ShapeItem }
        assertEquals(SolidMathKinds.AXES_2D, axes.shape)
        assertEquals(0.5, axes.rotation, 1e-9)
        val origin = axes.toLocal(axes.knobDots().single())
        assertEquals("the fork's axes crossed mid-box", 50.0, origin.x, 1e-9)
        assertEquals(40.0, origin.y, 1e-9)
        assertEquals(SolidMathKinds.NUMBER_LINE, line.shape)
        assertEquals(atan2(200.0, 0.0), line.rotation, 1e-9)
        assertEquals(Pt(0.0, 100.0), line.box.center)
        assertEquals(200.0, line.box.w, 1e-9)
        assertEquals(ShapeKind.SPLINE, curve.shape)
        assertEquals(listOf(Pt(0.0, 0.0), Pt(40.0, 90.0), Pt(120.0, 10.0), Pt(200.0, 70.0)), curve.vertexDots().map { it.at })
        assertEquals(ShapeKind.FUNCTION, graph.shape)
        assertEquals("tan(x)", graph.function!!.expr)
        assertEquals("tan breaks at its two poles", 3, graph.path!!.size)

        val back = roundTrip(doc)
        assertEquals(0, codec.forkShapesRead)
        assertEquals(listOf(SolidMathKinds.AXES_2D, SolidMathKinds.NUMBER_LINE, ShapeKind.SPLINE, ShapeKind.FUNCTION), back.items.map { (it as ShapeItem).shape })
        assertTrue((back.items[0] as ShapeItem).adj.contentEquals(doubleArrayOf(0.5, 0.5)))
        assertEquals(4, (back.items[2] as ShapeItem).vertexDots().size)
        val graphBack = back.items[3] as ShapeItem
        assertEquals("tan(x)", graphBack.function!!.expr)
        assertEquals(3, graphBack.path!!.size)
    }

    @Test fun aGraphWhoseFunctionNoLongerParsesKeepsItsSavedLine() {
        val doc = read(
            """{"format":"xcanvas","items":[
              {"kind":"shape","shape":"function","start":[0,0],"end":[30,10],"stroke_rgba":[1,2,3,255],"stroke_width":2,"fill_rgba":null,
               "points":[[0,0],[10,10],[],[20,0],[30,10]],"function":"nope(","function_from":0,"function_to":1}
            ]}""",
        )
        val s = doc.items.single() as ShapeItem
        assertEquals(ShapeKind.POLYLINE, s.shape)
        assertEquals(2, s.path!!.size)
    }

    @Test fun anUpstreamShapeIsNotTakenForAForkOne() {
        val doc = InfiniteDocument()
        doc.add(ShapeItem(ShapeKind.FUNCTION, Pt(0.0, 0.0), Pt(100.0, 60.0), com.xnotes.core.model.Rgba(1, 2, 3, 255)))
        doc.add(ShapeItem(SolidMathKinds.AXES_2D, Pt(0.0, 0.0), Pt(100.0, 60.0), com.xnotes.core.model.Rgba(1, 2, 3, 255), rotation = PI / 6))
        val back = roundTrip(doc)
        assertEquals(0, codec.forkShapesRead)
        assertNotNull((back.items[0] as ShapeItem).function)
        assertEquals(PI / 6, (back.items[1] as ShapeItem).rotation, 1e-9)
    }
}
