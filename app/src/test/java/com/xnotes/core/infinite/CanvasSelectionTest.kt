package com.xnotes.core.infinite

import com.xnotes.canvas.HandleId
import com.xnotes.canvas.SelectionMath
import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.ConnectorEnd
import com.xnotes.core.model.ConnectorItem
import com.xnotes.core.model.Glue
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Routing
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.stroke.Sample
import com.xnotes.core.shapes.Axis
import com.xnotes.core.shapes.Elbow
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CanvasSelectionTest {

    private fun box(x: Double, y: Double, w: Double = 40.0, h: Double = 30.0): ImageItem =
        ImageItem(ImageData(File("none"), 10, 10), Rect(x, y, w, h))

    private fun line(x: Double, y: Double): Stroke = Stroke(
        Tool.PEN,
        ToolDefaults.configFor(Tool.PEN),
        mutableListOf(Sample(x, y, 1.0), Sample(x + 40.0, y, 1.0)),
    )

    private fun docOf(vararg items: CanvasItem) = InfiniteDocument().apply { addAll(items.toList()) }

    // --- membership ---

    @Test fun aBandTakesWhateverItOverlaps() {
        val inside = box(10.0, 10.0)
        val outside = box(500.0, 500.0)
        val hits = SelectionMath.bandMembers(listOf(inside, outside), Rect(0.0, 0.0, 100.0, 100.0))
        assertEquals(listOf<CanvasItem>(inside), hits)
    }

    @Test fun aBandTakesAnItemItOnlyClips() {
        val straddling = box(90.0, 10.0)
        val hits = SelectionMath.bandMembers(listOf(straddling), Rect(0.0, 0.0, 100.0, 100.0))
        assertEquals(1, hits.size)
    }

    @Test fun aLassoTakesWhateverItEncloses() {
        val inside = box(40.0, 40.0, 10.0, 10.0)
        val outside = box(400.0, 400.0, 10.0, 10.0)
        val loop = listOf(Pt(0.0, 0.0), Pt(200.0, 0.0), Pt(200.0, 200.0), Pt(0.0, 200.0))
        val hits = SelectionMath.lassoMembers(listOf(inside, outside), loop)
        assertEquals(listOf<CanvasItem>(inside), hits)
    }

    @Test fun aWholeItemsBandSkipsAnItemItOnlyClips() {
        val straddling = box(90.0, 10.0)
        assertTrue(SelectionMath.bandMembers(listOf(straddling), Rect(0.0, 0.0, 100.0, 100.0), whole = true).isEmpty())
    }

    @Test fun aLassoCrossingAStrokeTakesItOnlyWhenPartialIsEnough() {
        val stroke = line(80.0, 50.0) // runs from x=80 to x=120, the loop ends at x=100
        val loop = listOf(Pt(0.0, 0.0), Pt(100.0, 0.0), Pt(100.0, 100.0), Pt(0.0, 100.0))
        assertEquals(listOf<CanvasItem>(stroke), SelectionMath.lassoMembers(listOf(stroke), loop, whole = false))
        assertTrue(SelectionMath.lassoMembers(listOf(stroke), loop, whole = true).isEmpty())
    }

    @Test fun aDegenerateLassoSelectsNothing() {
        assertTrue(SelectionMath.lassoMembers(listOf(box(0.0, 0.0)), listOf(Pt(0.0, 0.0), Pt(1.0, 1.0))).isEmpty())
    }

    // --- the box ---

    @Test fun theBoxWrapsEverythingSelected() {
        val doc = docOf()
        val sel = CanvasSelection(doc)
        sel.select(listOf(box(0.0, 0.0, 10.0, 10.0), box(90.0, 40.0, 10.0, 10.0)))
        val b = sel.box!!
        assertEquals(50.0, b.center.x, 1e-9)
        assertEquals(25.0, b.center.y, 1e-9)
        assertEquals(50.0, b.halfW, 1e-9)
        assertEquals(25.0, b.halfH, 1e-9)
        assertEquals(0.0, b.angle, 1e-12)
    }

    @Test fun anEmptySelectionHasNoBox() {
        val sel = CanvasSelection(docOf())
        assertTrue(sel.isEmpty)
        assertNull(sel.box)
        assertTrue(sel.handles().isEmpty())
        assertNull(sel.rotateGrip(30.0))
    }

    @Test fun aPressInsideTheBoxGrabsIt() {
        val sel = CanvasSelection(docOf())
        sel.select(listOf(box(0.0, 0.0, 100.0, 100.0)))
        assertTrue(sel.contains(Pt(50.0, 50.0)))
        assertTrue(!sel.contains(Pt(500.0, 50.0)))
    }

    @Test fun everyCornerAndEdgeHasAHandle() {
        val sel = CanvasSelection(docOf())
        sel.select(listOf(box(0.0, 0.0, 100.0, 60.0)))
        assertEquals(8, sel.handles().size)
        assertEquals(HandleId.TL, sel.hitHandle(Pt(0.0, 0.0), 5.0))
        assertEquals(HandleId.BR, sel.hitHandle(Pt(100.0, 60.0), 5.0))
        assertNull(sel.hitHandle(Pt(50.0, 30.0), 5.0))
    }

    @Test fun theRotateGripSitsAboveTheBox() {
        val sel = CanvasSelection(docOf())
        sel.select(listOf(box(0.0, 0.0, 100.0, 60.0)))
        val grip = sel.rotateGrip(30.0)!!
        assertEquals(50.0, grip.x, 1e-9)
        assertEquals(-30.0, grip.y, 1e-9)
    }

    // --- moving ---

    @Test fun aMoveShiftsEveryItemTogether() {
        val a = box(0.0, 0.0)
        val b = box(100.0, 0.0)
        val doc = docOf(a, b)
        val sel = CanvasSelection(doc)
        sel.select(listOf(a, b))
        sel.beginTransform()
        sel.moveLive(25.0, 10.0)
        assertEquals(25.0, a.rect.x, 1e-9)
        assertEquals(125.0, b.rect.x, 1e-9)
        assertEquals(10.0, a.rect.y, 1e-9)
    }

    @Test fun aMoveDragDoesNotCompound() {
        val a = box(0.0, 0.0)
        val sel = CanvasSelection(docOf(a))
        sel.select(listOf(a))
        sel.beginTransform()
        sel.moveLive(10.0, 0.0)
        sel.moveLive(20.0, 0.0)
        sel.moveLive(30.0, 0.0)
        assertEquals("each frame is measured from the gesture start", 30.0, a.rect.x, 1e-9)
    }

    @Test fun theBoxFollowsAMove() {
        val a = box(0.0, 0.0, 100.0, 100.0)
        val sel = CanvasSelection(docOf(a))
        sel.select(listOf(a))
        sel.beginTransform()
        sel.moveLive(40.0, 0.0)
        assertEquals(90.0, sel.box!!.center.x, 1e-9)
    }

    @Test fun aMoveIsUndoable() {
        val a = box(0.0, 0.0)
        val doc = docOf(a)
        val sel = CanvasSelection(doc)
        sel.select(listOf(a))
        sel.beginTransform()
        sel.moveLive(25.0, 10.0)
        val cmd = sel.buildCommand(movedOnly = true, dx = 25.0, dy = 10.0)!!
        cmd.undo()
        assertEquals(0.0, a.rect.x, 1e-9)
        cmd.redo()
        assertEquals(25.0, a.rect.x, 1e-9)
    }

    @Test fun aMoveOfNothingRecordsNothing() {
        val a = box(0.0, 0.0)
        val sel = CanvasSelection(docOf(a))
        sel.select(listOf(a))
        sel.beginTransform()
        assertNull(sel.buildCommand(movedOnly = true, dx = 0.0, dy = 0.0))
    }

    @Test fun aMoveRefilesTheSpatialIndex() {
        val a = box(0.0, 0.0)
        val doc = docOf(a)
        val sel = CanvasSelection(doc)
        sel.select(listOf(a))
        sel.beginTransform()
        sel.moveLive(9000.0, 9000.0)
        assertTrue(doc.itemsIn(Rect(-10.0, -10.0, 100.0, 100.0)).isEmpty())
        assertSame(a, doc.itemsIn(Rect(8990.0, 8990.0, 100.0, 100.0)).single())
    }

    // --- resizing ---

    @Test fun aCornerDragScalesTheSelection() {
        val a = box(0.0, 0.0, 100.0, 100.0)
        val sel = CanvasSelection(docOf(a))
        sel.select(listOf(a))
        sel.beginTransform()
        sel.resizeLive(HandleId.BR, Pt(200.0, 200.0))
        assertTrue("the box must have grown", sel.box!!.halfW > 50.0)
        assertTrue(a.rect.w > 100.0)
    }

    @Test fun aResizeAnchorsAtTheOppositeCorner() {
        val a = box(0.0, 0.0, 100.0, 100.0)
        val sel = CanvasSelection(docOf(a))
        sel.select(listOf(a))
        sel.beginTransform()
        sel.resizeLive(HandleId.BR, Pt(200.0, 200.0))
        assertEquals("the far corner stays put", 0.0, a.rect.x, 1e-6)
        assertEquals(0.0, a.rect.y, 1e-6)
    }

    @Test fun aResizeDragDoesNotCompound() {
        val a = box(0.0, 0.0, 100.0, 100.0)
        val sel = CanvasSelection(docOf(a))
        sel.select(listOf(a))
        sel.beginTransform()
        sel.resizeLive(HandleId.BR, Pt(200.0, 200.0))
        val once = a.rect.w
        sel.resizeLive(HandleId.BR, Pt(200.0, 200.0))
        assertEquals("the same pointer must give the same size", once, a.rect.w, 1e-9)
    }

    @Test fun aResizeIsUndoable() {
        val a = box(0.0, 0.0, 100.0, 100.0)
        val sel = CanvasSelection(docOf(a))
        sel.select(listOf(a))
        sel.beginTransform()
        sel.resizeLive(HandleId.BR, Pt(200.0, 200.0))
        val cmd = sel.buildCommand(movedOnly = false)!!
        cmd.undo()
        assertEquals(100.0, a.rect.w, 1e-6)
        cmd.redo()
        assertTrue(a.rect.w > 100.0)
    }

    @Test fun scalingAStrokeScalesItsWidthToo() {
        val s = line(0.0, 0.0)
        val before = s.config.baseWidth
        val sel = CanvasSelection(docOf(s))
        sel.select(listOf(s))
        sel.beginTransform()
        sel.resizeLive(HandleId.BR, Pt(200.0, 200.0))
        assertTrue("a resized stroke should look zoomed, not just longer", s.config.baseWidth > before)
    }

    @Test fun scalingAShapeKeepsItsOutlineWidth() {
        val r = ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(100.0, 100.0), Rgba(0, 0, 0), 3.0)
        val sel = CanvasSelection(docOf(r))
        sel.select(listOf(r))
        sel.beginTransform()
        sel.resizeLive(HandleId.BR, Pt(200.0, 200.0))
        assertTrue(r.box.w > 100.0)
        assertEquals(3.0, r.strokeWidth, 1e-9)
    }

    @Test fun aPartialBakeLeavesTheRestAlone() {
        val r = ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(100.0, 100.0), Rgba(0, 0, 0), 3.0)
        val s = line(0.0, 200.0)
        val sel = CanvasSelection(docOf(r, s))
        sel.select(listOf(r, s))
        sel.beginTransform()
        val twice = Affine.scaleAbout(Pt.ZERO, 2.0, 2.0)
        sel.applyLiveTo(setOf(r), twice)
        sel.applyLiveTo(setOf(r), twice)
        assertEquals("baked from the start, never compounded", Pt(200.0, 200.0), r.end)
        assertEquals("the stroke rides the lift instead", Pt(20.0, 200.0), s.centroid())
        sel.applyLiveTo(setOf(r), Affine.IDENTITY)
        assertEquals(Pt(100.0, 100.0), r.end)
    }

    @Test fun aLoneShapeIsBoxedByItsOwnTurnedFrame() {
        val r = ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(100.0, 50.0), Rgba(0, 0, 0), 2.0)
        r.applyTransform(Affine.rotateAbout(Pt(50.0, 25.0), 0.5))
        val sel = CanvasSelection(docOf(r))
        sel.select(listOf(r))
        val box = sel.box!!
        assertEquals(0.5, box.angle, 1e-12)
        assertEquals("the box is the frame itself", 50.0, box.halfW, 1e-9)
        assertTrue(sel.isFramedShape)
        assertEquals("the outline's reach plus the gap", 12.0, sel.chromeMargin(10.0), 1e-9)
        assertEquals(62.0, sel.chromeBox(10.0)!!.halfW, 1e-9)
    }

    @Test fun aCircleOffersCornerHandlesOnly() {
        val c = ShapeItem(ShapeKind.CIRCLE, Pt(0.0, 0.0), Pt(50.0, 50.0), Rgba(0, 0, 0), 2.0)
        val sel = CanvasSelection(docOf(c))
        sel.select(listOf(c))
        assertEquals(setOf(HandleId.TL, HandleId.TR, HandleId.BL, HandleId.BR), sel.handles(10.0).map { it.id }.toSet())
    }

    @Test fun aFramedResizeKeepsTheShapeEdgeBehindTheHandle() {
        val r = ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(100.0, 50.0), Rgba(0, 0, 0), 2.0)
        val sel = CanvasSelection(docOf(r))
        sel.select(listOf(r))
        val margin = sel.chromeMargin(10.0)
        sel.beginTransform()
        sel.resizeLive(HandleId.R, Pt(100.0 + margin + 30.0, 25.0), margin)
        assertEquals(130.0, r.box.right, 1e-9)
        assertEquals(2.0, r.strokeWidth, 1e-9)
    }

    @Test fun resizingATurnedShapeByItsOwnHandleKeepsItARectangle() {
        val r = ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(100.0, 50.0), Rgba(0, 0, 0), 2.0)
        r.applyTransform(Affine.rotateAbout(Pt(50.0, 25.0), 0.5))
        val sel = CanvasSelection(docOf(r))
        sel.select(listOf(r))
        sel.beginTransform()
        val box = sel.box!!
        sel.resizeLive(HandleId.R, box.localToWorld(Pt(box.halfW + 40.0, 0.0)))
        assertEquals(ShapeKind.RECTANGLE, r.shape)
        assertEquals(0.5, r.rotation, 1e-9)
        assertTrue(r.box.w > 130.0)
        assertEquals(50.0, r.box.h, 1e-9)
    }

    @Test fun aGroupKeepsTheUprightBox() {
        val r = ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(100.0, 50.0), Rgba(0, 0, 0), 2.0)
        r.applyTransform(Affine.rotateAbout(Pt(50.0, 25.0), 0.5))
        val s = line(0.0, 200.0)
        val sel = CanvasSelection(docOf(r, s))
        sel.select(listOf(r, s))
        assertEquals(0.0, sel.box!!.angle, 0.0)
        assertTrue(!sel.isFramedShape)
    }

    // --- rotating ---

    @Test fun aRotationTurnsTheBox() {
        val a = box(0.0, 0.0, 100.0, 60.0)
        val sel = CanvasSelection(docOf(a))
        sel.select(listOf(a))
        sel.beginTransform()
        sel.rotateLive(Pt(200.0, 30.0)) // pointer to the right of centre
        assertTrue("the box must have turned", kotlin.math.abs(sel.box!!.angle) > 1e-6)
    }

    @Test fun aRotationLeavesTheCentreWhereItWas() {
        val s = line(0.0, 0.0)
        val sel = CanvasSelection(docOf(s))
        sel.select(listOf(s))
        val centre = sel.box!!.center
        sel.beginTransform()
        sel.rotateLive(Pt(centre.x, centre.y - 100.0))
        assertEquals(centre.x, sel.box!!.center.x, 1e-6)
        assertEquals(centre.y, sel.box!!.center.y, 1e-6)
    }

    @Test fun aRotationIsUndoable() {
        val s = line(10.0, 10.0)
        val sel = CanvasSelection(docOf(s))
        sel.select(listOf(s))
        val before = s.samples.map { it.x to it.y }
        sel.beginTransform()
        sel.rotateLive(Pt(500.0, 500.0))
        val cmd = sel.buildCommand(movedOnly = false)!!
        cmd.undo()
        assertEquals(before, s.samples.map { it.x to it.y })
        cmd.redo()
        assertTrue(before != s.samples.map { it.x to it.y })
    }

    @Test fun aRotationDragDoesNotCompound() {
        val s = line(0.0, 0.0)
        val sel = CanvasSelection(docOf(s))
        sel.select(listOf(s))
        sel.beginTransform()
        sel.rotateLive(Pt(0.0, -100.0))
        val once = s.samples.map { it.x to it.y }
        sel.rotateLive(Pt(0.0, -100.0))
        assertEquals(once, s.samples.map { it.x to it.y })
    }

    @Test fun grabbingTheGripOffCentreDoesNotSnapTheSelection() {
        val a = box(0.0, 0.0, 100.0, 60.0)
        val sel = CanvasSelection(docOf(a))
        sel.select(listOf(a))
        val grip = sel.rotateGrip(34.0)!!
        // A press at the far edge of the grip's touch target, then no movement at all.
        val grab = Pt(grip.x + 22.0, grip.y)
        sel.beginTransform(grab)
        sel.rotateLive(grab)
        assertEquals("a press alone must not turn anything", 0.0, sel.box!!.angle, 1e-9)
    }

    @Test fun aRotationTurnsByWhatTheFingerSwept() {
        val a = box(0.0, 0.0, 100.0, 60.0)
        val sel = CanvasSelection(docOf(a))
        sel.select(listOf(a))
        val centre = sel.box!!.center
        val grab = Pt(centre.x + 22.0, centre.y - 100.0)
        sel.beginTransform(grab)
        // A quarter turn of the grab point about the centre must turn the box a quarter turn.
        val swept = Pt(centre.x + 100.0, centre.y + 22.0)
        sel.rotateLive(swept)
        assertEquals(Math.PI / 2.0, sel.box!!.angle, 1e-9)
    }

    @Test fun aRotatedBoxKeepsItsOwnShape() {
        val s = line(0.0, 0.0)
        val sel = CanvasSelection(docOf(s))
        sel.select(listOf(s))
        val wide = sel.box!!.halfW
        val thin = sel.box!!.halfH
        val centre = sel.box!!.center
        sel.beginTransform(Pt(centre.x, centre.y - 100.0))
        sel.rotateLive(Pt(centre.x + 100.0, centre.y))
        assertEquals("a turn must not resize the box", wide, sel.box!!.halfW, 1e-9)
        assertEquals(thin, sel.box!!.halfH, 1e-9)
    }

    @Test fun refreshingTheBoxBringsItBackUpright() {
        val s = line(0.0, 0.0)
        val sel = CanvasSelection(docOf(s))
        sel.select(listOf(s))
        val centre = sel.box!!.center
        sel.beginTransform(Pt(centre.x, centre.y - 100.0))
        sel.rotateLive(Pt(centre.x + 100.0, centre.y))
        sel.refreshBox()
        assertEquals("item bounds cannot say what angle the ink is at", 0.0, sel.box!!.angle, 1e-9)
    }

    // --- overlay geometry ---

    @Test fun theOverlayScalesItsOutlineWithTheZoom() {
        val sel = CanvasSelection(docOf())
        sel.select(listOf(box(0.0, 0.0, 100.0, 100.0)))
        val zoomedIn = OverlayTessellator.selection(sel.box!!, 8.0, com.xnotes.core.model.Rgba(0, 255, 0, 255), 0.01)
        val zoomedOut = OverlayTessellator.selection(sel.box!!, 0.5, com.xnotes.core.model.Rgba(0, 255, 0, 255), 0.01)
        assertNotNull(zoomedIn.firstOrNull())
        assertNotNull(zoomedOut.firstOrNull())
        // A constant device width means the content-space geometry is wider when zoomed out.
        val inSpan = span(zoomedIn[0].mesh)
        val outSpan = span(zoomedOut[0].mesh)
        assertTrue("the outline must stay one thickness on screen", outSpan > inSpan)
    }

    @Test fun theBandAndLassoProduceGeometry() {
        val accent = com.xnotes.core.model.Rgba(0, 255, 0, 255)
        assertTrue(OverlayTessellator.band(Rect(0.0, 0.0, 50.0, 40.0), 1.0, accent, 0.01).isNotEmpty())
        val loop = listOf(Pt(0.0, 0.0), Pt(50.0, 0.0), Pt(25.0, 40.0))
        assertTrue(OverlayTessellator.lasso(loop, 1.0, accent, 0.01).isNotEmpty())
        assertTrue(OverlayTessellator.lasso(listOf(Pt(0.0, 0.0)), 1.0, accent, 0.01).isEmpty())
    }

    /** How far the mesh reaches beyond the box it outlines, which is its content-space thickness. */
    private fun span(mesh: MeshData): Double {
        var maxX = Double.NEGATIVE_INFINITY
        for (i in 0 until mesh.vertexCount) maxX = maxOf(maxX, mesh.positions[2 * i])
        return maxX - 100.0
    }

    // --- a shape leaves what it holds ---

    private fun frame() = ShapeItem(ShapeKind.RECTANGLE, Pt(0.0, 0.0), Pt(200.0, 100.0), Rgba(1, 2, 3, 255), 2.0)

    @Test fun aMovedFrameLeavesWhatItHoldsWhereItWas() {
        val f = frame()
        val note = line(20.0, 50.0)
        val doc = docOf(f, note)
        val sel = CanvasSelection(doc).apply { select(listOf(f)) }
        val was = note.samples.map { it.pos }
        sel.beginTransform(rigid = true)
        sel.moveLive(30.0, 5.0)
        assertEquals(30.0, f.box.left, 1e-9)
        assertEquals(was, note.samples.map { it.pos })
        val cmd = sel.buildCommand(movedOnly = true, dx = 30.0, dy = 5.0)!!
        cmd.undo()
        assertEquals(0.0, f.box.left, 1e-9)
        assertEquals(was, note.samples.map { it.pos })
    }

    @Test fun aResizedFrameLeavesWhatItHoldsItsSize() {
        val f = frame()
        val note = line(80.0, 50.0)
        val doc = docOf(f, note)
        val sel = CanvasSelection(doc).apply { select(listOf(f)) }
        val was = note.samples.map { it.pos }
        val width = note.config.baseWidth
        sel.beginTransform(rigid = true)
        val right = sel.handles().first { it.id == HandleId.R }.content
        sel.resizeLive(HandleId.R, Pt(right.x + 200.0, right.y))
        assertTrue("the frame widened", f.box.w > 300.0)
        assertEquals(was, note.samples.map { it.pos })
        assertEquals(width, note.config.baseWidth, 0.0)
    }

    @Test fun aTurnedFrameLeavesWhatItHoldsUpright() {
        val f = frame()
        val note = line(20.0, 50.0)
        val doc = docOf(f, note)
        val sel = CanvasSelection(doc).apply { select(listOf(f)) }
        val was = note.samples.map { it.pos }
        sel.beginTransform(Pt(100.0, -40.0), rigid = true)
        sel.rotateLive(Pt(240.0, 50.0))
        assertTrue("the frame turned", f.bounds().h > f.bounds().w)
        assertEquals(was, note.samples.map { it.pos })
    }

    // --- connectors ---

    private fun rect(l: Double, t: Double, r: Double, b: Double) = ShapeItem(ShapeKind.RECTANGLE, Pt(l, t), Pt(r, b), Rgba(1, 2, 3, 255))

    private fun joining(a: ShapeItem, b: ShapeItem) = ConnectorItem(
        Routing.ELBOW, ConnectorEnd(Glue.point(a, 5)!!.at, a, 5), ConnectorEnd(Glue.point(b, 7)!!.at, b, 7), Rgba(1, 2, 3, 255),
    ).apply { elbow = Elbow(Axis.H, doubleArrayOf(200.0)) }

    @Test fun movingAShapeTakesWhatIsGluedToItAlongInOneUndoStep() {
        val a = rect(0.0, 0.0, 100.0, 60.0)
        val b = rect(300.0, 100.0, 400.0, 160.0)
        val c = joining(a, b)
        val doc = docOf(a, b, c)
        val sel = CanvasSelection(doc)
        sel.select(listOf(b))
        sel.beginTransform(rigid = true)
        sel.moveLive(0.0, 50.0)
        assertEquals(Pt(300.0, 180.0), c.end.at)
        val cmd = sel.buildCommand(movedOnly = true, dx = 0.0, dy = 50.0)!!
        cmd.undo()
        assertEquals(Pt(300.0, 130.0), c.end.at)
        assertEquals(Pt(300.0, 100.0), b.box.topLeft)
        cmd.redo()
        assertEquals(Pt(300.0, 180.0), c.end.at)
    }

    @Test fun aMirrorFlipsEverythingSelectedAboutItsBoxInOneUndoStep() {
        val a = ShapeItem(ShapeKind.RIGHT_TRIANGLE, Pt(0.0, 0.0), Pt(100.0, 60.0), Rgba(1, 2, 3, 255))
        val b = rect(300.0, 100.0, 400.0, 160.0)
        val c = joining(a, b)
        val ink = line(0.0, 200.0)
        val doc = docOf(a, b, c, ink)
        val sel = CanvasSelection(doc)
        sel.select(listOf(a, ink))
        val box = sel.box!!
        val was = ink.samples.map { it.pos }
        val start = c.start.at
        val cmd = sel.mirror(upsideDown = false)!!
        assertEquals(com.xnotes.core.geometry.Flip.X, a.flip)
        assertEquals(ink.samples.map { it.pos }, was.map { Pt(2 * box.center.x - it.x, it.y) })
        assertSame("still glued to the mirrored shape", a, c.start.target)
        assertTrue(c.start.at.distanceTo(Pt(2 * box.center.x - start.x, start.y)) < 1e-9)
        assertEquals(Glue.point(a, c.start.point)!!.at, c.start.at)
        assertEquals("what it leads to stays put", Pt(300.0, 130.0), c.end.at)
        cmd.undo()
        assertEquals(com.xnotes.core.geometry.Flip.NONE, a.flip)
        assertEquals(was, ink.samples.map { it.pos })
        assertEquals(start, c.start.at)
    }

    @Test fun aMirroredBoxTurnsTheOtherWay() {
        val items = listOf(line(0.0, 0.0), line(0.0, 80.0))
        val doc = docOf(*items.toTypedArray())
        val turned = CanvasSelection(doc)
        turned.select(items)
        turned.beginTransform()
        turned.rotateLive(Pt(200.0, 200.0))
        val angle = turned.box!!.angle
        turned.mirror(upsideDown = true)
        assertEquals(-angle, turned.box!!.angle, 1e-12)
        val lone = ShapeItem(ShapeKind.RIGHT_TRIANGLE, Pt(0.0, 0.0), Pt(100.0, 60.0), Rgba(1, 2, 3, 255))
        val one = CanvasSelection(docOf(lone))
        one.select(listOf(lone))
        one.mirror(upsideDown = true)
        assertEquals(com.xnotes.core.geometry.Flip.Y, lone.flip)
        assertEquals("a lone shape is boxed by its own frame", lone.frameObb(), one.box)
    }

    @Test fun textNeverMirrors() {
        assertTrue(line(0.0, 0.0).mirrorable)
        assertTrue(box(0.0, 0.0).mirrorable)
        assertTrue(rect(0.0, 0.0, 10.0, 10.0).mirrorable)
        assertTrue(!com.xnotes.core.model.TextItem(Pt.ZERO, text = "a", measurer = com.xnotes.core.FakeTextMeasurer()).mirrorable)
        assertTrue(!ShapeItem(ShapeKind.GLYPHS, Pt(0.0, 0.0), Pt(10.0, 10.0), Rgba(1, 2, 3, 255)).mirrorable)
    }

    @Test fun aLoneConnectorDraggedByItsLineLetsGoAndUndoGluesItBack() {
        val a = rect(0.0, 0.0, 100.0, 60.0)
        val b = rect(300.0, 100.0, 400.0, 160.0)
        val c = joining(a, b)
        val doc = docOf(a, b, c)
        val sel = CanvasSelection(doc)
        sel.select(listOf(c))
        assertNull("a lone connector has no box to grab", sel.chromeBox())
        sel.beginTransform(rigid = true, pin = false)
        sel.moveLive(20.0, 20.0)
        c.letGo()
        val cmd = sel.buildCommand(movedOnly = true, dx = 20.0, dy = 20.0)!!
        assertNull(c.start.target)
        cmd.undo()
        assertSame(a, c.start.target)
        assertEquals(Pt(100.0, 30.0), c.start.at)
    }
}

