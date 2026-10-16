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

    /** How far a lone shape's chrome sits outside its outline, in device pixels. */
    const val FRAME_MARGIN_PX = 14.0

    /** Vertex dot diameter and the light core inside it, in device pixels. */
    const val VERTEX_DOT_PX = 12.0
    const val VERTEX_CORE_PX = 6.0

    /** Edge midpoint dot diameter, in device pixels. */
    const val EDGE_DOT_PX = 9.0

    /** An edge shorter than this on screen shows no midpoint dot, in device pixels. */
    const val EDGE_DOT_MIN_PX = 48.0

    /** How close a press has to land to a dot to grab it, in device pixels. */
    const val DOT_TOUCH_PX = 20.0

    /** A knob diamond's diagonal and its edge, in device pixels: big enough to stand out among the dots. */
    const val KNOB_PX = 22.0
    const val KNOB_EDGE_PX = 1.5

    /** Knobs are amber, PowerPoint's colour for them, so they never read as a handle or a vertex. */
    val KNOB_FILL = Rgba(255, 196, 0, 255)
    val KNOB_EDGE = Rgba(110, 72, 0, 255)

    /** A knob diamond centred on [c] with half-diagonal [r]. */
    fun knobDiamond(c: Pt, r: Double): List<Pt> = listOf(Pt(c.x, c.y - r), Pt(c.x + r, c.y), Pt(c.x, c.y + r), Pt(c.x - r, c.y))

    /**
     * Dash on/off runs for every marquee, in dp, so chrome reads the same on any screen. Both
     * canvases draw from these, which is what keeps a band, a lasso and a selection box looking
     * like each other and like themselves on the other canvas.
     */
    const val DASH_ON_DP = 8.0
    const val DASH_GAP_DP = 5.0

    /**
     * The selection box, its handles (all eight unless [handles] names fewer), the rotate grip and
     * its stem, and over a lone shape the dots that edit its points: faint ones halfway along each
     * edge, solid ones on the vertices. A [moveGrip] adds the pan glyph, in [onAccent], in a disc
     * below a small box.
     */
    fun selection(
        box: Obb,
        zoom: Double,
        accent: Rgba,
        tolerance: Double,
        devicePxPerDp: Double = 1.0,
        handles: List<Pt>? = null,
        vertexDots: List<Pt> = emptyList(),
        edgeDots: List<Pt> = emptyList(),
        knobs: List<Pt> = emptyList(),
        moveGrip: Pt? = null,
        onAccent: Rgba = Rgba(255, 255, 255, 255),
    ): List<MeshPart> {
        if (zoom <= 0.0) return emptyList()
        val outline = MeshBuilder()
        val half = OUTLINE_PX / zoom / 2.0
        dashInto(outline, box.corners(), half, closed = true, zoom = zoom, devicePxPerDp = devicePxPerDp, tolerance = tolerance)

        // The stem out to the grip, so it reads as attached rather than floating. Solid: it is a
        // join, not a boundary, and a dashed one at this length would be two ticks and a gap.
        val arm = GRIP_ARM_PX / zoom
        val top = com.xnotes.canvas.ResizeMath.obbTopMid(box)
        val grip = com.xnotes.canvas.ResizeMath.obbRotateGrip(box, arm)
        outline.polylineRibbon(listOf(top, grip), half, closed = false, tolerance = tolerance)

        val marks = MeshBuilder()
        val handleHalf = HANDLE_PX / zoom / 2.0
        for (c in handles ?: com.xnotes.canvas.ResizeMath.obbHandles(box).map { it.content }) {
            marks.rect(c.x - handleHalf, c.y - handleHalf, handleHalf * 2, handleHalf * 2)
        }
        marks.circle(grip.x, grip.y, GRIP_PX / zoom / 2.0, tolerance)

        val glyph = MeshBuilder()
        if (moveGrip != null) {
            val r = GRIP_PX / zoom
            marks.circle(moveGrip.x, moveGrip.y, r * 0.8, tolerance)
            for (run in com.xnotes.canvas.ResizeMath.moveGlyph(moveGrip, r * 0.5)) {
                glyph.polylineRibbon(run, half, closed = false, tolerance = tolerance)
            }
        }

        val parts = ArrayList<MeshPart>(6)
        if (!outline.isEmpty) parts.add(MeshPart(outline.build(), accent, InkPass.OPAQUE))
        if (!marks.isEmpty) parts.add(MeshPart(marks.build(), accent, InkPass.OPAQUE))
        if (!glyph.isEmpty) parts.add(MeshPart(glyph.build(), onAccent, InkPass.OPAQUE))
        if (edgeDots.isNotEmpty()) {
            val faint = MeshBuilder()
            for (d in edgeDots) faint.circle(d.x, d.y, EDGE_DOT_PX / zoom / 2.0, tolerance)
            parts.add(MeshPart(faint.build(), accent.withAlpha(EDGE_DOT_ALPHA), InkPass.OPAQUE))
        }
        if (vertexDots.isNotEmpty()) {
            val dots = MeshBuilder()
            val cores = MeshBuilder()
            for (d in vertexDots) {
                dots.circle(d.x, d.y, VERTEX_DOT_PX / zoom / 2.0, tolerance)
                cores.circle(d.x, d.y, VERTEX_CORE_PX / zoom / 2.0, tolerance)
            }
            parts.add(MeshPart(dots.build(), accent, InkPass.OPAQUE))
            parts.add(MeshPart(cores.build(), DOT_CORE, InkPass.OPAQUE))
        }
        if (knobs.isNotEmpty()) {
            val fill = MeshBuilder()
            val edge = MeshBuilder()
            for (k in knobs) {
                val d = knobDiamond(k, KNOB_PX / zoom / 2.0)
                val v = d.map { fill.vertex(it.x, it.y) }
                fill.triangle(v[0], v[1], v[2])
                fill.triangle(v[0], v[2], v[3])
                edge.polylineRibbon(d, KNOB_EDGE_PX / zoom / 2.0, closed = true, tolerance = tolerance)
            }
            parts.add(MeshPart(fill.build(), KNOB_FILL, InkPass.OPAQUE))
            parts.add(MeshPart(edge.build(), KNOB_EDGE, InkPass.OPAQUE))
        }
        return parts
    }

    /** A connection point's cross, end to end, and its line, in device pixels. */
    const val MARK_PX = 9.0
    const val MARK_LINE_PX = 1.4

    /** The ring round the connection point a pen is over, in device pixels. */
    const val HOT_PX = 15.0
    const val HOT_CORE_PX = 7.0

    /**
     * Where a connector can glue, while one is armed: a small cross on every point in [points], and
     * a solid ring on each of [hot], the points under the pen and the one a connector set off from.
     */
    fun connectionMarks(points: List<Pt>, hot: List<Pt>, zoom: Double, accent: Rgba, tolerance: Double): List<MeshPart> {
        if (zoom <= 0.0) return emptyList()
        val parts = ArrayList<MeshPart>(3)
        if (points.isNotEmpty()) {
            val b = MeshBuilder()
            val r = MARK_PX / zoom / 2.0
            val half = MARK_LINE_PX / zoom / 2.0
            for (p in points) {
                b.polylineRibbon(listOf(Pt(p.x - r, p.y - r), Pt(p.x + r, p.y + r)), half, closed = false, tolerance = tolerance)
                b.polylineRibbon(listOf(Pt(p.x - r, p.y + r), Pt(p.x + r, p.y - r)), half, closed = false, tolerance = tolerance)
            }
            parts.add(MeshPart(b.build(), accent, InkPass.OPAQUE))
        }
        if (hot.isNotEmpty()) {
            val rings = MeshBuilder()
            val cores = MeshBuilder()
            for (h in hot) {
                rings.circle(h.x, h.y, HOT_PX / zoom / 2.0, tolerance)
                cores.circle(h.x, h.y, HOT_CORE_PX / zoom / 2.0, tolerance)
            }
            parts.add(MeshPart(rings.build(), accent, InkPass.OPAQUE))
            parts.add(MeshPart(cores.build(), DOT_CORE, InkPass.OPAQUE))
        }
        return parts
    }

    /** A lone connector's end dot, and the light core that marks a loose end, in device pixels. */
    const val END_PX = 14.0
    const val END_CORE_PX = 7.0

    /**
     * A lone selected connector's chrome, standing in for a box: a dot on each end (solid when
     * glued, hollow when loose), a square on each middle segment it can slide, and on a straight
     * one a dot on each waypoint and a faint one halfway along each stretch, where a drag adds one.
     */
    fun connectorChrome(
        ends: List<Pair<Pt, Boolean>>,
        segments: List<Pt>,
        waypoints: List<Pt>,
        midpoints: List<Pt>,
        zoom: Double,
        accent: Rgba,
        tolerance: Double,
    ): List<MeshPart> {
        if (zoom <= 0.0) return emptyList()
        val parts = ArrayList<MeshPart>(6)
        if (midpoints.isNotEmpty()) {
            val faint = MeshBuilder()
            for (d in midpoints) faint.circle(d.x, d.y, EDGE_DOT_PX / zoom / 2.0, tolerance)
            parts.add(MeshPart(faint.build(), accent.withAlpha(EDGE_DOT_ALPHA), InkPass.OPAQUE))
        }
        if (segments.isNotEmpty()) {
            val squares = MeshBuilder()
            val half = HANDLE_PX / zoom / 2.0
            for (c in segments) squares.rect(c.x - half, c.y - half, half * 2, half * 2)
            parts.add(MeshPart(squares.build(), accent, InkPass.OPAQUE))
        }
        val dots = MeshBuilder()
        val cores = MeshBuilder()
        for (d in waypoints) {
            dots.circle(d.x, d.y, VERTEX_DOT_PX / zoom / 2.0, tolerance)
            cores.circle(d.x, d.y, VERTEX_CORE_PX / zoom / 2.0, tolerance)
        }
        for ((at, glued) in ends) {
            dots.circle(at.x, at.y, END_PX / zoom / 2.0, tolerance)
            if (!glued) cores.circle(at.x, at.y, END_CORE_PX / zoom / 2.0, tolerance)
        }
        if (!dots.isEmpty) parts.add(MeshPart(dots.build(), accent, InkPass.OPAQUE))
        if (!cores.isEmpty) parts.add(MeshPart(cores.build(), DOT_CORE, InkPass.OPAQUE))
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
    /** Edge dots read as a hint beside the solid vertex dots. */
    private const val EDGE_DOT_ALPHA = 140

    /** The light core that makes a vertex dot read as a point rather than a handle. */
    val DOT_CORE = Rgba(255, 255, 255, 255)

    fun selectionBounds(box: Obb, zoom: Double): Rect {
        val pad = (GRIP_ARM_PX + GRIP_PX + HANDLE_PX) / maxOf(zoom, 1e-9)
        var acc = Rect.bounding(box.corners())
        acc = acc.outset(pad)
        return acc
    }
}
