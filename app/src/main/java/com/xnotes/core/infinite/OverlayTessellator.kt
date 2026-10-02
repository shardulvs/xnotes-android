package com.xnotes.core.infinite

import com.xnotes.core.geometry.Obb
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Rgba

/**
 * The chrome drawn over the content: the selection box and its handles, the band a drag sweeps out,
 * the lasso loop.
 *
 * All of it is built as ordinary triangles and pushed through the same transient buffer the wet
 * stroke uses, rather than through a shader of its own. That keeps one path for everything the
 * canvas draws, and the two are never needed at once: you cannot be inking and selecting.
 *
 * Widths arrive in device pixels and are divided by the zoom here, so the outline stays one
 * thickness however far in or out the canvas is. That means the overlay has to be rebuilt when the
 * view changes, which is cheap: it is a few dozen triangles.
 */
object OverlayTessellator {

    /** Selection outline thickness, in device pixels. */
    const val OUTLINE_PX = 1.6

    /** Handle square edge, in device pixels. */
    const val HANDLE_PX = 16.5

    /** Rotate grip diameter, in device pixels. */
    const val GRIP_PX = 19.5

    /** How far the rotate grip sits past the box's top edge, in device pixels. */
    const val GRIP_ARM_PX = 50.0

    /** Band and lasso outline thickness, in device pixels. */
    const val MARQUEE_PX = 1.4

    /**
     * Dash on/off runs for every marquee, in dp, so chrome reads the same on any screen. Both
     * canvases draw from these, which is what keeps a band, a lasso and a selection box looking
     * like each other and like themselves on the other canvas.
     */
    const val DASH_ON_DP = 8.0
    const val DASH_GAP_DP = 5.0

    /**
     * The selection box, its eight handles and the rotate grip and its stem. A lone spline swaps the
     * handles and grip for its [points]; a [moveGrip] adds the pan glyph, in [onAccent], in a disc below a small box.
     */
    fun selection(
        box: Obb,
        zoom: Double,
        accent: Rgba,
        tolerance: Double,
        devicePxPerDp: Double = 1.0,
        points: List<Pt>? = null,
        moveGrip: Pt? = null,
        onAccent: Rgba = Rgba(255, 255, 255, 255),
    ): List<MeshPart> {
        if (zoom <= 0.0) return emptyList()
        val outline = MeshBuilder()
        val half = OUTLINE_PX / zoom / 2.0
        dashInto(outline, box.corners(), half, closed = true, zoom = zoom, devicePxPerDp = devicePxPerDp, tolerance = tolerance)

        // The stem out to the grip, so it reads as attached rather than floating. Solid: it is a
        // join, not a boundary, and a dashed one at this length would be two ticks and a gap.
        val marks = MeshBuilder()
        val handleHalf = HANDLE_PX / zoom / 2.0
        if (points == null) {
            val arm = GRIP_ARM_PX / zoom
            val top = com.xnotes.canvas.ResizeMath.obbTopMid(box)
            val grip = com.xnotes.canvas.ResizeMath.obbRotateGrip(box, arm)
            outline.polylineRibbon(listOf(top, grip), half, closed = false, tolerance = tolerance)
            for (handle in com.xnotes.canvas.ResizeMath.obbHandles(box)) {
                marks.rect(handle.content.x - handleHalf, handle.content.y - handleHalf, handleHalf * 2, handleHalf * 2)
            }
            marks.circle(grip.x, grip.y, GRIP_PX / zoom / 2.0, tolerance)
        } else {
            for (p in points) marks.circle(p.x, p.y, handleHalf, tolerance)
        }

        val glyph = MeshBuilder()
        if (moveGrip != null) {
            val r = GRIP_PX / zoom
            marks.circle(moveGrip.x, moveGrip.y, r * 0.8, tolerance)
            for (run in com.xnotes.canvas.ResizeMath.moveGlyph(moveGrip, r * 0.5)) {
                glyph.polylineRibbon(run, half, closed = false, tolerance = tolerance)
            }
        }

        val parts = ArrayList<MeshPart>(3)
        if (!outline.isEmpty) parts.add(MeshPart(outline.build(), accent, InkPass.OPAQUE))
        if (!marks.isEmpty) parts.add(MeshPart(marks.build(), accent, InkPass.OPAQUE))
        if (!glyph.isEmpty) parts.add(MeshPart(glyph.build(), onAccent, InkPass.OPAQUE))
        return parts
    }

    /** The rectangle a band-select drag has swept out so far, dashed like every other marquee. */
    fun band(
        rect: Rect,
        zoom: Double,
        accent: Rgba,
        tolerance: Double,
        devicePxPerDp: Double = 1.0,
    ): List<MeshPart> {
        if (zoom <= 0.0 || rect.w <= 0.0 && rect.h <= 0.0) return emptyList()
        val b = MeshBuilder()
        val corners = listOf(
            Pt(rect.left, rect.top),
            Pt(rect.right, rect.top),
            Pt(rect.right, rect.bottom),
            Pt(rect.left, rect.bottom),
        )
        dashInto(b, corners, MARQUEE_PX / zoom / 2.0, closed = true, zoom = zoom, devicePxPerDp = devicePxPerDp, tolerance = tolerance)
        if (b.isEmpty) return emptyList()
        return listOf(MeshPart(b.build(), accent, InkPass.OPAQUE))
    }

    /**
     * The lasso as drawn so far: a dashed, open line that stays where the pen put it.
     *
     * Open because the loop is the pen's, not a shape: drawing a chord back to the start would
     * claim an edge the hand never made. What the lasso *encloses* is worked out at pen up, and is
     * not what this shows.
     */
    fun lasso(
        points: List<Pt>,
        zoom: Double,
        accent: Rgba,
        tolerance: Double,
        devicePxPerDp: Double = 1.0,
    ): List<MeshPart> = lassoRun(points, 0, points.size, zoom, accent, tolerance, 0.0, devicePxPerDp)

    /**
     * A stretch of the lasso: [count] points from [from], dashed and open, picking the pattern up
     * [phase] content units in.
     *
     * A lasso only ever grows at its end, and every vertex carries a disc of its own, so building
     * it whole on each touch sample costs the whole line again. A settled stretch is uploaded once
     * and never rewritten, exactly as a settled run of wet ink is, and [phase] is the arc the runs
     * before it spent so the dashes land where an unbroken line would have put them.
     */
    fun lassoRun(
        points: List<Pt>,
        from: Int,
        count: Int,
        zoom: Double,
        accent: Rgba,
        tolerance: Double,
        phase: Double,
        devicePxPerDp: Double = 1.0,
    ): List<MeshPart> {
        if (zoom <= 0.0 || count < 2 || from < 0 || from + count > points.size) return emptyList()
        val b = MeshBuilder()
        val half = MARQUEE_PX / zoom / 2.0
        val span = points.subList(from, from + count)
        dashInto(b, span, half, closed = false, zoom = zoom, devicePxPerDp = devicePxPerDp, tolerance = tolerance, phase = phase)
        if (b.isEmpty) return emptyList()
        return listOf(MeshPart(b.build(), accent, InkPass.OPAQUE))
    }

    /** The moving end of the lasso, from [from] to the last point it has. */
    fun lassoTail(
        points: List<Pt>,
        from: Int,
        zoom: Double,
        accent: Rgba,
        tolerance: Double,
        phase: Double,
        devicePxPerDp: Double = 1.0,
    ): List<MeshPart> {
        if (from < 0 || from >= points.size) return emptyList()
        return lassoRun(points, from, points.size - from, zoom, accent, tolerance, phase, devicePxPerDp)
    }

    /**
     * [points] as a dashed ribbon into [b]. The dash is an on-screen length, so it comes back out
     * of the zoom into content px; [phase] lets a line split into runs keep one rhythm.
     */
    private fun dashInto(
        b: MeshBuilder,
        points: List<Pt>,
        half: Double,
        closed: Boolean,
        zoom: Double,
        devicePxPerDp: Double,
        tolerance: Double,
        phase: Double = 0.0,
    ) {
        val on = DASH_ON_DP * devicePxPerDp / zoom
        val gap = DASH_GAP_DP * devicePxPerDp / zoom
        for (run in MeshBuilder.dashRuns(points, on, gap, closed = closed, phase = phase)) {
            b.polylineRibbon(run, half, closed = false, tolerance = tolerance)
        }
    }

    /** Content-space bounds of an oriented box grown by its handles and grip, for the cover quad. */
    fun selectionBounds(box: Obb, zoom: Double): Rect {
        val pad = (GRIP_ARM_PX + GRIP_PX + HANDLE_PX) / maxOf(zoom, 1e-9)
        var acc = Rect.bounding(box.corners())
        acc = acc.outset(pad)
        return acc
    }
}
