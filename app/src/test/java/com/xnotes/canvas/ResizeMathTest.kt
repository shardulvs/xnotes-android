package com.xnotes.canvas

import com.xnotes.core.FakeRasterSurface
import com.xnotes.core.FakeTextMeasurer
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.TextItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResizeMathTest {

    @Test fun imageResizeIsAspectLocked() {
        val r = ResizeMath.resizeImage(Rect(0.0, 0.0, 100.0, 50.0), HandleId.BR, Pt(200.0, 60.0))
        // scale = max(200/100, 60/50) = 2 -> 200x100, anchored at TL
        assertEquals(Rect(0.0, 0.0, 200.0, 100.0), r)
    }

    @Test fun imageResizeAnchorsOppositeCorner() {
        val r = ResizeMath.resizeImage(Rect(10.0, 10.0, 100.0, 50.0), HandleId.TL, Pt(-90.0, -40.0))
        // anchor = BR (110,60); rawW=200,rawH=100 -> scale 2 -> 200x100 from BR
        assertEquals(110.0, r.right, 1e-9)
        assertEquals(60.0, r.bottom, 1e-9)
        assertEquals(200.0, r.w, 1e-9)
    }

    @Test fun imageResizeEnforcesMinSize() {
        val r = ResizeMath.resizeImage(Rect(0.0, 0.0, 100.0, 100.0), HandleId.BR, Pt(5.0, 5.0))
        assertTrue(r.w >= ResizeMath.MIN_SIZE && r.h >= ResizeMath.MIN_SIZE)
    }

    @Test fun closedShapeResizesFreely() {
        val (s, e) = ResizeMath.resizeClosedShape(Pt(0.0, 0.0), Pt(100.0, 50.0), HandleId.BR, Pt(200.0, 60.0))
        assertEquals(Pt(0.0, 0.0), s)
        assertEquals(Pt(200.0, 60.0), e) // not aspect-locked
    }

    @Test fun openShapeDragsGrabbedEndpoint() {
        val (s, e) = ResizeMath.resizeOpenShape(Pt(0.0, 0.0), Pt(100.0, 0.0), HandleId.END, Pt(50.0, 50.0))
        assertEquals(Pt(0.0, 0.0), s)
        assertEquals(Pt(50.0, 50.0), e)
    }

    @Test fun textResizeRightWidensWidthOnly() {
        val (pos, w, h) = ResizeMath.resizeText(Pt(0.0, 0.0), 100.0, 40.0, HandleId.R, Pt(150.0, 20.0))
        assertEquals(Pt(0.0, 0.0), pos)
        assertEquals(150.0, w, 1e-9)
        assertEquals(40.0, h, 1e-9) // a side handle leaves the other axis untouched
    }

    @Test fun textResizeLeftMovesLeftEdge() {
        val (pos, w, _) = ResizeMath.resizeText(Pt(0.0, 0.0), 100.0, 40.0, HandleId.L, Pt(20.0, 0.0))
        assertEquals(20.0, pos.x, 1e-9) // left moved, right edge (100) fixed
        assertEquals(80.0, w, 1e-9)
    }

    @Test fun textResizeBottomGrowsHeight() {
        val (pos, w, h) = ResizeMath.resizeText(Pt(0.0, 0.0), 100.0, 40.0, HandleId.B, Pt(50.0, 120.0))
        assertEquals(Pt(0.0, 0.0), pos)
        assertEquals(100.0, w, 1e-9)
        assertEquals(120.0, h, 1e-9)
    }

    @Test fun textResizeCornerChangesBothAxes() {
        val (pos, w, h) = ResizeMath.resizeText(Pt(10.0, 10.0), 100.0, 40.0, HandleId.TL, Pt(-90.0, -40.0))
        assertEquals(Pt(-90.0, -40.0), pos) // top-left follows the pointer; bottom-right (110,50) fixed
        assertEquals(200.0, w, 1e-9)
        assertEquals(90.0, h, 1e-9)
    }

    @Test fun textResizeEnforcesMinSize() {
        val (_, w, h) = ResizeMath.resizeText(Pt(0.0, 0.0), 100.0, 100.0, HandleId.BR, Pt(5.0, 5.0))
        assertTrue(w >= ResizeMath.MIN_SIZE && h >= ResizeMath.MIN_SIZE)
    }

    @Test fun imageHandlesAreFourCorners() {
        val img = ImageItem(FakeRasterSurface(10, 10), Rect(0.0, 0.0, 100.0, 50.0))
        val handles = ResizeMath.handles(img, Pt(48.0, 48.0))
        assertEquals(4, handles.size)
        assertTrue(handles.any { it.id == HandleId.TL && it.content == Pt(48.0, 48.0) })
        assertTrue(handles.any { it.id == HandleId.BR && it.content == Pt(148.0, 98.0) })
    }

    @Test fun textHandlesAreEightAroundTheBox() {
        val t = TextItem(Pt(0.0, 0.0), width = 100.0, height = 50.0, text = "x", measurer = FakeTextMeasurer())
        val handles = ResizeMath.handles(t, Pt(0.0, 0.0))
        assertEquals(8, handles.size)
        val ids = handles.map { it.id }.toSet()
        assertEquals(setOf(HandleId.TL, HandleId.T, HandleId.TR, HandleId.R, HandleId.BR, HandleId.B, HandleId.BL, HandleId.L), ids)
    }
}
