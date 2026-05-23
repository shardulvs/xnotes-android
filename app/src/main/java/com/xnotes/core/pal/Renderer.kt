package com.xnotes.core.pal

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Rgba

/** Polygon fill rule (spec 01 §1). Both are required. */
enum class FillRule { NONZERO, EVEN_ODD }

/**
 * An outline pen. When [cosmetic] is true the [width] is in *device* pixels and
 * does **not** scale with the current transform (selection outlines, page
 * borders, resize handles stay ~1–1.3 px regardless of zoom — spec 01 §1). When
 * false the width is in the current coordinate system (page pixels), so shape
 * outlines thicken with zoom like the content they belong to.
 */
data class Pen(
    val color: Rgba,
    val width: Double = 1.0,
    val cosmetic: Boolean = true,
    val dashed: Boolean = false,
)

/**
 * Immediate-mode 2D vector painter (spec 01 §1). The application issues draw
 * calls every frame; the renderer retains no scene. The same interface draws to
 * the screen, to offscreen [RasterSurface]s, and to PDF export.
 *
 * Only translation and uniform/axis scaling are used — never rotation or shear.
 */
interface Renderer {
    // --- transform / clip stack ---
    fun save()
    fun restore()

    /**
     * Begin an offscreen layer over [bounds] that is composited at [alpha] when
     * the matching [restore] runs. Content drawn into the layer is accumulated
     * opaquely first, so overlapping fills don't compound — used to render a
     * translucent stroke (e.g. the highlighter) uniformly. Pair with [restore].
     */
    fun saveLayerAlpha(bounds: Rect, alpha: Double)
    fun translate(dx: Double, dy: Double)
    fun scale(sx: Double, sy: Double)

    /** Intersect the clip region with an axis-aligned rectangle (content space). */
    fun clipRect(rect: Rect)

    /**
     * Clear the current clip region to fully transparent (replace, not composite).
     * Lets a cached surface be repaired in place: clip to a dirty rect, [clear] it,
     * then repaint only the items there — instead of rebuilding the whole page
     * (used by the eraser).
     */
    fun clear()

    // --- fills ---
    fun fillBackground(rect: Rect, color: Rgba)
    fun fillRect(rect: Rect, color: Rgba)
    fun fillPolygon(points: List<Pt>, color: Rgba, rule: FillRule = FillRule.NONZERO)
    fun fillCircle(center: Pt, radius: Double, color: Rgba)
    fun fillEllipse(center: Pt, rx: Double, ry: Double, color: Rgba)

    // --- outlines (cosmetic for chrome, page-space for shapes) ---
    fun strokeRect(rect: Rect, pen: Pen)
    fun strokePolyline(points: List<Pt>, pen: Pen)
    fun strokePolygon(points: List<Pt>, pen: Pen)
    fun strokeEllipse(center: Pt, rx: Double, ry: Double, pen: Pen)

    // --- raster + text ---
    /** Draw [raster] scaled into [dest] (optionally only [src] sub-rect), smooth sampling. */
    fun drawRaster(raster: RasterSurface, dest: Rect, src: Rect? = null)

    fun drawText(text: String, rect: Rect, font: FontSpec, color: Rgba, flags: TextFlags = TextFlags())

    /** Run [block] between matching [save]/[restore] calls. */
    fun withSave(block: () -> Unit) {
        save()
        try {
            block()
        } finally {
            restore()
        }
    }
}
