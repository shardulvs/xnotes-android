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
            config.neon -> paintNeon(r, g, color)
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
     * Neon: a soft luminous halo in the ink colour, composited once at [NEON_GLOW_ALPHA]
     * (so a self-overlapping scribble doesn't blow out), with a brightened, crisp core
     * laid on top to read as a glowing tube. Overrides the translucent path, so it works
     * on any stroke tool.
     */
    private fun paintNeon(r: Renderer, g: StrokeGeometry, color: Rgba) {
        val glowR = (config.baseWidth * NEON_GLOW_FACTOR).coerceAtLeast(NEON_GLOW_MIN)
        val glow = color.withAlpha(255)
        r.saveLayerAlpha(bounds().outset(glowR * 2 + 4), NEON_GLOW_ALPHA)
        if (g.outline.size >= 3) r.fillPolygonGlow(g.outline, glow, FillRule.NONZERO, glowR)
        for (cap in g.caps) if (cap.radius > 0.0) r.fillCircleGlow(cap.center, cap.radius, glow, glowR)
        r.restore()
        paintFills(r, g, neonCore(color))
    }

    /** The ink colour mixed toward white for the bright neon core (opaque). */
    private fun neonCore(c: Rgba): Rgba {
        val k = NEON_CORE_LIGHTEN
        return Rgba(
            (c.r + (255 - c.r) * k).toInt(),
            (c.g + (255 - c.g) * k).toInt(),
            (c.b + (255 - c.b) * k).toInt(),
            255,
        )
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

        /** Neon glow radius as a multiple of base width, with a page-px floor. */
        private const val NEON_GLOW_FACTOR = 1.7
        private const val NEON_GLOW_MIN = 3.5

        /** Halo opacity, and how far the core is mixed toward white. */
        private const val NEON_GLOW_ALPHA = 0.6
        private const val NEON_CORE_LIGHTEN = 0.6
    }
}
