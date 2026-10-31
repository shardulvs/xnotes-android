package com.xnotes.core.infinite

import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.*
import com.xnotes.core.pal.FontFace
import com.xnotes.core.shapes.ShapeKind
import org.junit.Assert.*
import org.junit.Test

class PreparedShapeTextTest {
    private val measurer = FakeTextMeasurer()
    private fun shape() = ShapeItem(ShapeKind.RECTANGLE, Pt(10.0, 20.0), Pt(310.0, 120.0), Rgba(0, 0, 0), 2.0).also {
        it.label = ShapeLabel("moving text", TextStyle(Rgba(0, 0, 0), 13.0, FontFace.MONO), measurer)
    }

    @Test fun translationAndRotationReuseTheRenderedGeometryAtTheFinalPosition() {
        val source = shape()
        val original = ItemMesher.mesh(source)!!
        val prepared = PreparedShapeText(source, original)
        val next = source.deepCopy(measurer) as ShapeItem
        next.translate(123.25, -76.5)
        next.rotation = .4
        val result = prepared.rigidCopyFor(next)!!
        for (part in result.parts.indices) {
            val before = original.parts[part].mesh
            val after = result.parts[part].mesh
            assertSame(before.indices, after.indices)
            assertEquals(original.parts[part].pass, result.parts[part].pass)
            for (i in before.positions.indices step 2) {
                val expected = next.toPage(Pt(before.positions[i] + 123.25, before.positions[i + 1] - 76.5))
                assertEquals(expected.x, after.positions[i], 1e-7)
                assertEquals(expected.y, after.positions[i + 1], 1e-7)
            }
        }
        assertEquals(next.paintBounds(), result.bounds)
    }

    @Test fun reflowStyleAndShapeChangesCannotReuseOldGeometry() {
        val source = shape()
        val prepared = PreparedShapeText(source, ItemMesher.mesh(source)!!)
        for (change in listOf<(ShapeItem) -> Unit>(
            { it.end = it.end + Pt(20.0, 0.0) },
            { it.label = it.label!!.withText("new text") },
            { it.strokeWidth = 8.0 },
            { it.fillRgba = Rgba(255, 0, 0) },
            { it.labelHidden = true },
            { it.shape = ShapeKind.ELLIPSE },
        )) {
            val next = source.deepCopy(measurer) as ShapeItem
            change(next)
            assertNull(prepared.rigidCopyFor(next))
        }
    }
}
