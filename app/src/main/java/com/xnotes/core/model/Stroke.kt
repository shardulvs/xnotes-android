package com.xnotes.core.model

import com.xnotes.core.geometry.Geometry
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.pal.BlendMode
import com.xnotes.core.pal.FillRule
import com.xnotes.core.pal.Renderer
import com.xnotes.core.stroke.Sample
import com.xnotes.core.stroke.StrokeEngine
import com.xnotes.core.stroke.StrokeGeometry
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig

/**
 * The unit of inking (spec 02 §5.1). Holds the raw samples plus a snapshot of
 * the tool style; the ribbon geometry is derived from the samples on demand and
 * cached until the samples change.
 */
class Stroke(
    val tool: Tool,
    val config: ToolConfig,
    val samples: MutableList<Sample> = mutableListOf(),
    /** Content-px → dp scale captured at pen-down (zoom ÷ display density), so the speed
     *  pen judges gesture speed in zoom- and device-independent units. 1.0 = unscaled. */
    val speedScale: Double = 1.0,
) : CanvasItem {

    override val kind = KIND
    override val resizable = false

    private var cachedGeometry: StrokeGeometry? = null
    private var cachedRawBounds: Rect? = null
    private var cachedBounds: Rect? = null

    /** Ink colour with the tool's alpha scale applied (highlighter ×0.35). */
    val renderColor get() = config.rgba.scaleAlpha(tool.alphaScale)

    val isEmpty get() = samples.isEmpty()

    /** Lazily-built ribbon geometry; rebuilt only when samples change. */
    fun geometry(): StrokeGeometry {
        cachedGeometry?.let { return it }
        return StrokeEngine.build(
            samples,
            config.baseWidth,
            config.pressureEnabled,
            config.pressureMinFactor,
            config.directionStrength,
            config.speedStrength,
            config.taperAmount,
            speedScale,
        ).also { cachedGeometry = it }
    }

    fun invalidate() {
        cachedGeometry = null
        cachedRawBounds = null
        cachedBounds = null
    }

    fun addSample(s: Sample) {
        samples.add(s)
        invalidate()
    }

    override fun paint(r: Renderer) {
        val g = geometry()
        val color = renderColor
        when {
            // The highlighter never glows (a translucent marker; glow is meaningless there).
            config.neon && tool != Tool.HIGHLIGHTER -> paintNeon(r, g, color)
            color.a >= 255 -> {
                // Opaque ink: draw ribbon + caps directly.
                paintFills(r, g, color)
            }
            else -> {
                // Translucent ink: accumulate the whole stroke opaquely in a layer, then
                // composite once at the ink's alpha, so the cap/ribbon and self-overlaps
                // don't compound into darker patches. The highlighter composites with
                // MULTIPLY so it tints light areas but can't lighten dark ink underneath
                // (text stays legible); other translucent inks blend normally.
                val blend = if (tool == Tool.HIGHLIGHTER) BlendMode.MULTIPLY else BlendMode.SRC_OVER
                r.saveLayerBlended(bounds().outset(2.0), color.a / 255.0, blend)
                paintFills(r, g, color.withAlpha(255))
                r.restore()
            }
        }
    }

    private fun paintFills(r: Renderer, g: StrokeGeometry, color: Rgba) {
        if (g.outline.size >= 3) r.fillPolygon(g.outline, color, FillRule.NONZERO)
        for (cap in g.caps) if (cap.radius > 0.0) r.fillCircle(cap.center, cap.radius, color)
    }

    /**
     * Neon, as a glowing glass tube: a **white-hot centre** that bleeds out to the
     * saturated ink colour at the rim, wrapped in a soft luminous **halo** that
     * emanates from the edges. Three layers, back to front:
     *   1. the outer halo — a blurred fill in the ink colour, composited once at a
     *      glow-intensity alpha so a self-overlapping scribble doesn't blow out;
     *   2. the tube body — the opaque, saturated ink colour (what shows at the rim);
     *   3. the core — a white fill blurred *inward* (INNER), solid along the
     *      centreline and fading to nothing at the rim, letting the colour show there.
     * [config.neonStrength] scales the halo's size and brightness. Overrides the
     * translucent path, so it works on any stroke tool.
     */
    private fun paintNeon(r: Renderer, g: StrokeGeometry, color: Rgba) {
        val s = config.neonStrength.coerceIn(0.0, 1.0)
        val body = color.withAlpha(255)

        // 1) Outer halo (ink colour), wider and brighter as intensity rises.
        val glowR = (config.baseWidth * (NEON_GLOW_FACTOR_MIN + NEON_GLOW_FACTOR_SPAN * s)).coerceAtLeast(NEON_GLOW_MIN)
        val glowAlpha = NEON_GLOW_ALPHA_MIN + NEON_GLOW_ALPHA_SPAN * s
        r.saveLayerAlpha(bounds().outset(glowR * 2 + 4), glowAlpha)
        if (g.outline.size >= 3) r.fillPolygonGlow(g.outline, body, FillRule.NONZERO, glowR)
        for (cap in g.caps) if (cap.radius > 0.0) r.fillCircleGlow(cap.center, cap.radius, body, glowR)
        r.restore()

        // 2) Tube body — the saturated colour shows at the rim.
        paintFills(r, g, body)

        // 3) White-hot core, blurred inward so colour survives at the rim.
        val coreR = (config.baseWidth * NEON_CORE_BLUR_FACTOR).coerceAtLeast(NEON_CORE_BLUR_MIN)
        val white = Rgba(255, 255, 255, 255)
        if (g.outline.size >= 3) r.fillPolygonGlow(g.outline, white, FillRule.NONZERO, coreR, inner = true)
        for (cap in g.caps) if (cap.radius > 0.0) r.fillCircleGlow(cap.center, cap.radius, white, coreR, inner = true)
    }

    override fun bounds(): Rect {
        cachedBounds?.let { return it }
        val g = geometry()
        if (g.outline.isEmpty() && g.caps.isEmpty()) {
            val b = if (samples.isEmpty()) Rect(0.0, 0.0, 0.0, 0.0) else rawBounds()
            return b.also { cachedBounds = it }
        }
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (p in g.outline) {
            if (p.x < minX) minX = p.x else if (p.x > maxX) maxX = p.x
            if (p.y < minY) minY = p.y else if (p.y > maxY) maxY = p.y
        }
        for (cap in g.caps) {
            if (cap.center.x - cap.radius < minX) minX = cap.center.x - cap.radius
            if (cap.center.x + cap.radius > maxX) maxX = cap.center.x + cap.radius
            if (cap.center.y - cap.radius < minY) minY = cap.center.y - cap.radius
            if (cap.center.y + cap.radius > maxY) maxY = cap.center.y + cap.radius
        }
        return Rect(minX, minY, maxX - minX, maxY - minY).also { cachedBounds = it }
    }

    override fun translate(dx: Double, dy: Double) {
        for (i in samples.indices) {
            val s = samples[i]
            samples[i] = s.copy(x = s.x + dx, y = s.y + dy)
        }
        invalidate()
    }

    /** `p` inside the filled ribbon or any cap disc. */
    override fun contains(p: Pt): Boolean {
        val g = geometry()
        for (cap in g.caps) if (p.distanceTo(cap.center) <= cap.radius) return true
        return g.outline.size >= 3 && Geometry.pointInPolygon(g.outline, p)
    }

    /** Mean of the sample positions. */
    override fun centroid(): Pt {
        if (samples.isEmpty()) return Pt.ZERO
        var sx = 0.0
        var sy = 0.0
        for (s in samples) {
            sx += s.x
            sy += s.y
        }
        return Pt(sx / samples.size, sy / samples.size)
    }

    /**
     * AABB of the *raw* input samples, cached and invalidated with the geometry.
     * Built in one allocation-free pass (no `samples.map { it.pos }`), so an eraser
     * sweep that bbox-rejects thousands of strokes per move stays cheap.
     */
    private fun rawBounds(): Rect {
        cachedRawBounds?.let { return it }
        var minX = samples[0].x
        var minY = samples[0].y
        var maxX = minX
        var maxY = minY
        for (i in 1 until samples.size) {
            val s = samples[i]
            if (s.x < minX) minX = s.x else if (s.x > maxX) maxX = s.x
            if (s.y < minY) minY = s.y else if (s.y > maxY) maxY = s.y
        }
        return Rect(minX, minY, maxX - minX, maxY - minY).also { cachedRawBounds = it }
    }

    /** Cheap sample test after a bounding-box reject (spec 02 §5.1). */
    override fun intersectsCircle(cx: Double, cy: Double, radius: Double): Boolean {
        if (samples.isEmpty()) return false
        // Reject against the *raw* sample box (the smoothed geometry lags inward).
        if (rawBounds().distanceTo(Pt(cx, cy)) > radius) return false
        val r2 = radius * radius
        for (s in samples) {
            val dx = s.x - cx
            val dy = s.y - cy
            if (dx * dx + dy * dy <= r2) return true
        }
        return false
    }

    companion object {
        const val KIND = "stroke"

        /** Halo radius = base_width × (MIN + SPAN × neonStrength), floored in page px. */
        private const val NEON_GLOW_FACTOR_MIN = 0.9
        private const val NEON_GLOW_FACTOR_SPAN = 2.0
        private const val NEON_GLOW_MIN = 3.5

        /** Halo opacity = MIN + SPAN × neonStrength. */
        private const val NEON_GLOW_ALPHA_MIN = 0.25
        private const val NEON_GLOW_ALPHA_SPAN = 0.55

        /** White core's inward blur as a multiple of base width, with a page-px floor. */
        private const val NEON_CORE_BLUR_FACTOR = 0.5
        private const val NEON_CORE_BLUR_MIN = 1.5
    }
}
