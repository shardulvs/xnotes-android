package com.xnotes.core.infinite

import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.ShapeLabel
import com.xnotes.core.model.TextStyle
import com.xnotes.core.pal.FillRule
import com.xnotes.core.pal.FontFace
import com.xnotes.core.pal.Pen
import com.xnotes.core.shapes.PathRole
import com.xnotes.core.shapes.ShapePath
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.vector.PathFlattener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShapeTessellatorTest {

    private fun shape(
        kind: ShapeKind,
        fill: Rgba? = null,
        dashed: Boolean = false,
    ): ShapeItem = ShapeItem(
        kind, Pt(0.0, 0.0), Pt(100.0, 60.0), Rgba(10, 20, 30, 255), 4.0, fill,
        dashed = dashed, dashLength = 10.0, dashGap = 6.0,
    )

    private fun covers(m: MeshData, px: Double, py: Double): Boolean {
        for (t in 0 until m.triangleCount) {
            val a = m.indices[3 * t]
            val b = m.indices[3 * t + 1]
            val c = m.indices[3 * t + 2]
            if (inTriangle(
                    px, py,
                    m.positions[2 * a], m.positions[2 * a + 1],
                    m.positions[2 * b], m.positions[2 * b + 1],
                    m.positions[2 * c], m.positions[2 * c + 1],
                )
            ) {
                return true
            }
        }
        return false
    }

    private fun inTriangle(
        px: Double, py: Double,
        ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double,
    ): Boolean {
        fun side(x1: Double, y1: Double, x2: Double, y2: Double) =
            (x2 - x1) * (py - y1) - (y2 - y1) * (px - x1)
        val d1 = side(ax, ay, bx, by)
        val d2 = side(bx, by, cx, cy)
        val d3 = side(cx, cy, ax, ay)
        return !((d1 < -1e-12 || d2 < -1e-12 || d3 < -1e-12) && (d1 > 1e-12 || d2 > 1e-12 || d3 > 1e-12))
    }

    private fun area(m: MeshData): Double {
        var sum = 0.0
        for (t in 0 until m.triangleCount) {
            val a = m.indices[3 * t]
            val b = m.indices[3 * t + 1]
            val c = m.indices[3 * t + 2]
            sum += kotlin.math.abs(
                (m.positions[2 * b] - m.positions[2 * a]) * (m.positions[2 * c + 1] - m.positions[2 * a + 1]) -
                    (m.positions[2 * c] - m.positions[2 * a]) * (m.positions[2 * b + 1] - m.positions[2 * a + 1]),
            ) / 2.0
        }
        return sum
    }

    // --- parts ---

    @Test fun anUnfilledShapeIsJustItsOutline() {
        val parts = ShapeTessellator.tessellate(shape(ShapeKind.RECTANGLE))
        assertEquals(1, parts.size)
        assertEquals(Rgba(10, 20, 30, 255), parts[0].color)
    }

    @Test fun aFilledShapeDrawsItsFillUnderItsOutline() {
        val fill = Rgba(200, 40, 40, 64)
        val parts = ShapeTessellator.tessellate(shape(ShapeKind.RECTANGLE, fill = fill))
        assertEquals(2, parts.size)
        assertEquals("the fill goes down first", fill, parts[0].color)
        assertEquals(Rgba(10, 20, 30, 255), parts[1].color)
    }

    @Test fun everyPartTakesTheDirectPass() {
        // A fill is a simple polygon and an outline is opaque, so neither needs masking.
        val parts = ShapeTessellator.tessellate(shape(ShapeKind.ELLIPSE, fill = Rgba(1, 2, 3, 64)))
        assertTrue(parts.all { it.pass == InkPass.OPAQUE })
    }

    @Test fun anOpenShapeNeverFills() {
        for (kind in listOf(ShapeKind.LINE, ShapeKind.ARROW)) {
            val parts = ShapeTessellator.tessellate(shape(kind, fill = Rgba(9, 9, 9, 90)))
            assertEquals("$kind should have outline only", 1, parts.size)
        }
    }

    @Test fun everyShapeKindProducesGeometry() {
        for (kind in ShapeKind.DRAW_TOOL_KINDS) {
            val parts = ShapeTessellator.tessellate(shape(kind))
            assertTrue("$kind produced nothing", parts.isNotEmpty() && !parts[0].mesh.isEmpty)
        }
    }

    // --- fills ---

    @Test fun aRectangleFillCoversItsInterior() {
        val mesh = ShapeTessellator.fillMesh(shape(ShapeKind.RECTANGLE), 0.01)
        assertTrue(covers(mesh, 50.0, 30.0))
        assertTrue(covers(mesh, 2.0, 2.0))
        assertTrue(!covers(mesh, 120.0, 30.0))
    }

    @Test fun anEllipseFillCoversTheCentreAndNotTheCorner() {
        val mesh = ShapeTessellator.fillMesh(shape(ShapeKind.ELLIPSE), 0.01)
        assertTrue(covers(mesh, 50.0, 30.0))
        assertTrue("the box corner is outside an ellipse", !covers(mesh, 1.0, 1.0))
    }

    @Test fun anEllipseFillIsCloseToItsTrueArea() {
        val mesh = ShapeTessellator.fillMesh(shape(ShapeKind.ELLIPSE), 0.01)
        val exact = Math.PI * 50.0 * 30.0
        assertEquals(exact, area(mesh), exact * 0.01)
    }

    @Test fun aTriangleFillIsOneTriangle() {
        val mesh = ShapeTessellator.fillMesh(shape(ShapeKind.TRIANGLE), 0.01)
        assertEquals(1, mesh.triangleCount)
        assertTrue(covers(mesh, 50.0, 50.0))
    }

    @Test fun aConvexPolygonFillsByEarClipping() {
        val verts = listOf(Pt(0.0, 0.0), Pt(100.0, 0.0), Pt(120.0, 60.0), Pt(50.0, 100.0), Pt(-20.0, 60.0))
        val poly = ShapeItem.poly(ShapeKind.POLYGON, verts, Rgba(1, 1, 1, 255), 2.0, Rgba(5, 5, 5, 80), false, 0.6, false, 10.0, 8.0)
        val mesh = ShapeTessellator.fillMesh(poly, 0.01)
        assertEquals("n vertices ear-clip into n-2 triangles", verts.size - 2, mesh.triangleCount)
        assertTrue(covers(mesh, 50.0, 50.0))
        assertTrue(!covers(mesh, 300.0, 300.0))
    }

    @Test fun aConcavePolygonDoesNotFillItsNotch() {
        // An arrowhead-shaped polygon: the notch between the barbs must stay empty.
        val verts = listOf(Pt(0.0, 0.0), Pt(100.0, 50.0), Pt(0.0, 100.0), Pt(30.0, 50.0))
        val poly = ShapeItem.poly(ShapeKind.POLYGON, verts, Rgba(1, 1, 1, 255), 2.0, Rgba(5, 5, 5, 80), false, 0.6, false, 10.0, 8.0)
        val mesh = ShapeTessellator.fillMesh(poly, 0.01)
        assertEquals(verts.size - 2, mesh.triangleCount)
        assertTrue("inside the body", covers(mesh, 40.0, 50.0))
        assertTrue("inside the notch", !covers(mesh, 10.0, 50.0))
    }

    // --- outlines ---

    @Test fun anOutlineRunsAlongTheEdgeAndNotThroughTheMiddle() {
        val mesh = ShapeTessellator.outlineMesh(shape(ShapeKind.RECTANGLE), 0.01)
        assertTrue("on the top edge", covers(mesh, 50.0, 0.0))
        assertTrue("on the left edge", covers(mesh, 0.0, 30.0))
        assertTrue("the interior is not stroked", !covers(mesh, 50.0, 30.0))
    }

    @Test fun aClosedOutlineHasNoGapAtItsCorners() {
        val mesh = ShapeTessellator.outlineMesh(shape(ShapeKind.RECTANGLE), 0.01)
        for (corner in listOf(Pt(0.0, 0.0), Pt(100.0, 0.0), Pt(100.0, 60.0), Pt(0.0, 60.0))) {
            assertTrue("gap at $corner", covers(mesh, corner.x, corner.y))
        }
    }

    @Test fun aLineOutlineIsRoundedPastItsEnds() {
        val line = ShapeItem(ShapeKind.LINE, Pt(0.0, 0.0), Pt(100.0, 0.0), Rgba(1, 1, 1, 255), 8.0)
        val mesh = ShapeTessellator.outlineMesh(line, 0.01)
        assertTrue("round cap past the start", covers(mesh, -2.0, 0.0))
        assertTrue("round cap past the end", covers(mesh, 102.0, 0.0))
        assertTrue(!covers(mesh, -20.0, 0.0))
    }

    @Test fun anArrowAddsAHeadPastItsShaft() {
        val arrow = ShapeItem(ShapeKind.ARROW, Pt(0.0, 0.0), Pt(100.0, 0.0), Rgba(1, 1, 1, 255), 4.0)
        val plain = ShapeItem(ShapeKind.LINE, Pt(0.0, 0.0), Pt(100.0, 0.0), Rgba(1, 1, 1, 255), 4.0)
        assertTrue(
            "the head must add geometry",
            ShapeTessellator.outlineMesh(arrow, 0.01).triangleCount >
                ShapeTessellator.outlineMesh(plain, 0.01).triangleCount,
        )
    }

    @Test fun aDashedOutlineLeavesGaps() {
        val solid = ShapeTessellator.outlineMesh(shape(ShapeKind.RECTANGLE), 0.01)
        val dashed = ShapeTessellator.outlineMesh(shape(ShapeKind.RECTANGLE, dashed = true), 0.01)
        assertTrue("dashes must cover less than a solid line", area(dashed) < area(solid))
        assertTrue(!dashed.isEmpty)
    }

    @Test fun everyIndexPointsAtARealVertex() {
        for (kind in ShapeKind.all) {
            val s = if (kind == ShapeKind.POLYGON || kind == ShapeKind.POLYLINE) {
                ShapeItem.poly(
                    kind, listOf(Pt(0.0, 0.0), Pt(40.0, 5.0), Pt(20.0, 40.0)),
                    Rgba(1, 1, 1, 255), 3.0, Rgba(2, 2, 2, 60), false, 0.6, false, 10.0, 8.0,
                )
            } else {
                shape(kind, fill = Rgba(2, 2, 2, 60))
            }
            for (part in ShapeTessellator.tessellate(s)) {
                assertEquals(0, part.mesh.indices.size % 3)
                for (i in part.mesh.indices) {
                    assertTrue("$kind index $i out of range", i >= 0 && i < part.mesh.vertexCount)
                }
            }
        }
    }

    // --- paths ---

    private val ink = Rgba(1, 1, 1, 255)
    private val pen = Pen(ink, 4.0, cosmetic = false)

    @Test fun aBigCircleOutlineSkipsDiscsAlongItsCurve() {
        val circle = ShapeItem(ShapeKind.CIRCLE, Pt(0.0, 0.0), Pt(1000.0, 1000.0), ink, 4.0)
        val tol = StrokeTessellator.DEFAULT_TOLERANCE
        val spine = PathFlattener.flatten(circle.paths().single().contours.single(), tol).size
        assertEquals("a quad per step and no disc", 4 * spine, ShapeTessellator.outlineMesh(circle, tol).vertexCount)
    }

    @Test fun aCircleOutlineFollowsTheTrueCurve() {
        val circle = ShapeItem(ShapeKind.CIRCLE, Pt(0.0, 0.0), Pt(1000.0, 1000.0), ink, 4.0)
        val mesh = ShapeTessellator.outlineMesh(circle, StrokeTessellator.DEFAULT_TOLERANCE)
        val d = 500.0 / kotlin.math.sqrt(2.0)
        assertTrue("on the curve at 45 degrees", covers(mesh, 500.0 + d, 500.0 + d))
        val out = (500.0 + 3.0) / kotlin.math.sqrt(2.0)
        assertTrue("clear of it past the half width", !covers(mesh, 500.0 + out, 500.0 + out))
    }

    @Test fun aConvexFillIsAFan() {
        val e = shape(ShapeKind.ELLIPSE)
        val n = PathFlattener.flatten(e.paths().single().contours.single(), 0.01).size
        assertEquals(n - 2, ShapeTessellator.fillMesh(e, 0.01).triangleCount)
    }

    private fun ring(tolerance: Double): List<MeshPart> {
        val outer = ShapePath.ellipseContour(50.0, 50.0, 50.0, 50.0)
        val inner = ShapePath.ellipseContour(50.0, 50.0, 25.0, 25.0)
        val path = ShapePath(listOf(outer, inner), PathRole.OUTLINE, FillRule.EVEN_ODD)
        return ShapeTessellator.tessellate(listOf(path), Rgba(9, 9, 9, 80), pen, tolerance)
    }

    @Test fun aRingFillLeavesItsHoleEmpty() {
        val fill = ring(0.05).first()
        assertEquals(InkPass.OPAQUE, fill.pass)
        assertTrue("in the band", covers(fill.mesh, 50.0, 12.0))
        assertTrue("not in the hole", !covers(fill.mesh, 50.0, 50.0))
    }

    @Test fun aFillTooFineToClipTakesTheStencil() {
        assertEquals(InkPass.EVEN_ODD, ring(1e-5).first().pass)
    }

    @Test fun shadedFacesBecomeTheirOwnParts() {
        val box = Rect(0.0, 0.0, 10.0, 10.0)
        val paths = listOf(
            ShapePath.rect(box, PathRole.FILL_ONLY),
            ShapePath.rect(box, PathRole.SHADE_DARK),
            ShapePath.rect(box, PathRole.SHADE_LIGHT),
            ShapePath.rect(box),
        )
        val fill = Rgba(100, 100, 100, 255)
        val parts = ShapeTessellator.tessellate(paths, fill, pen, 0.01)
        assertEquals(5, parts.size)
        assertEquals("fill, dark and light", 3, parts.dropLast(1).map { it.color }.distinct().size)
        assertEquals(ink, parts.last().color)
    }

    @Test fun hiddenEdgesDashOnASolidShape() {
        val line = listOf(Pt(0.0, 0.0), Pt(200.0, 0.0))
        fun area(role: PathRole) = area(ShapeTessellator.tessellate(listOf(ShapePath.polyline(line, role)), null, pen, 0.01).single().mesh)
        assertTrue(area(PathRole.HIDDEN) < area(PathRole.STROKE_ONLY) * 0.9)
    }

    @Test fun aHeadStaysSolidOnADashedShape() {
        val line = listOf(Pt(0.0, 0.0), Pt(200.0, 0.0))
        val dashed = pen.copy(dashed = true, dashOn = 10.0, dashGap = 10.0)
        fun area(p: Pen) = area(ShapeTessellator.tessellate(listOf(ShapePath.polyline(line, PathRole.HEAD)), null, p, 0.01).single().mesh)
        assertEquals(area(pen), area(dashed), 1e-6)
    }

    // --- dash runs ---

    @Test fun dashRunsAlternateAlongThePath() {
        val runs = MeshBuilder.dashRuns(listOf(Pt(0.0, 0.0), Pt(100.0, 0.0)), 10.0, 10.0, closed = false)
        assertEquals(5, runs.size)
        assertEquals(0.0, runs[0].first().x, 1e-9)
        assertEquals(10.0, runs[0].last().x, 1e-9)
        assertEquals(20.0, runs[1].first().x, 1e-9)
    }

    @Test fun aClosedDashedPathWrapsBackToItsStart() {
        val square = listOf(Pt(0.0, 0.0), Pt(40.0, 0.0), Pt(40.0, 40.0), Pt(0.0, 40.0))
        val runs = MeshBuilder.dashRuns(square, 10.0, 10.0, closed = true)
        assertTrue(runs.isNotEmpty())
        // 160 px of perimeter at a 20 px period is eight dashes.
        assertEquals(8, runs.size)
    }

    @Test fun aDegenerateDashSettingDrawsSolid() {
        val path = listOf(Pt(0.0, 0.0), Pt(50.0, 0.0))
        assertEquals(1, MeshBuilder.dashRuns(path, 0.0, 10.0, closed = false).size)
        assertEquals(1, MeshBuilder.dashRuns(path, 10.0, 0.0, closed = false).size)
    }

    @Test fun aLabelMeshesAsGlyphsInItsColourTurnedWithTheShape() {
        val red = Rgba(220, 20, 20, 255)
        val s = ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(200.0, 100.0), Rgba(0, 0, 0, 255), 2.0)
        s.label = ShapeLabel("ab c", TextStyle(red, 10.0, FontFace.SANS), FakeTextMeasurer())
        s.applyTransform(com.xnotes.core.geometry.Affine.rotateAbout(s.box.center, 0.3))
        val part = ShapeTessellator.labelParts(s).single()
        assertEquals(red, part.color)
        assertEquals("a glyph square per visible character, two triangles each", 6, part.mesh.triangleCount)
        val frame = s.labelRect()!!.outset(1e-6)
        for (i in 0 until part.mesh.vertexCount) {
            val local = s.toLocal(Pt(part.mesh.positions[2 * i], part.mesh.positions[2 * i + 1]))
            assertTrue("glyph vertex $local outside the label", frame.contains(local))
        }
        val meshed = ItemMesher.mesh(s)!!
        assertEquals("outline, then the words over it", red, meshed.parts.last().color)
        s.labelHidden = true
        assertTrue(ShapeTessellator.labelParts(s).isEmpty())
        assertEquals(1, ItemMesher.mesh(s)!!.parts.size)
    }
    @Test fun reusableLinesArePlacedIndependentlyAndKeepTheirColourAfterResize() {
        val fake = FakeTextMeasurer()
        val font = com.xnotes.core.pal.FontSpec(10.0)
        val contours = fake.outline("a", font, 100.0, com.xnotes.core.pal.TextFlags())
        val measurer = object : com.xnotes.core.pal.TextMeasurer by fake {
            override fun outlineLines(text: String, font: com.xnotes.core.pal.FontSpec,
                wrapWidth: Double, flags: com.xnotes.core.pal.TextFlags) = listOf(
                com.xnotes.core.pal.TextOutlineLine(contours, Pt(wrapWidth / 4, -5.0)),
                com.xnotes.core.pal.TextOutlineLine(contours, Pt(wrapWidth / 3, 8.0)),
            )
        }
        val s = shape(ShapeKind.RECTANGLE)
        for (width in listOf(100.0, 160.0, 100.0)) {
            s.end = Pt(width, 60.0)
            val color = Rgba(width.toInt(), 20, 30)
            s.label = ShapeLabel("a\na", TextStyle(color, 10.0, FontFace.SANS), measurer)
            val part = ShapeTessellator.labelParts(s).single()
            val frame = s.labelRect()!!
            val origins = listOf(Pt(frame.w / 4, -5.0), Pt(frame.w / 3, 8.0))
            val expected = ShapeTessellator.textPart(origins.flatMap { origin ->
                com.xnotes.core.shapes.FreePath.map(contours) {
                    s.toPage(Pt(frame.left + origin.x + it.x, frame.top + origin.y + it.y))
                }
            }, color) { it }!!
            assertEquals(color, part.color)
            assertEquals(expected.mesh.triangleCount, part.mesh.triangleCount)
            for (y in -10..70) for (x in -10..180) {
                assertEquals("coverage at $x,$y width $width", covers(expected.mesh, x + .2, y + .2),
                    covers(part.mesh, x + .2, y + .2))
            }
        }
    }

}
