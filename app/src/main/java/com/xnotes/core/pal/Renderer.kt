package com.xnotes.core.pal

import com.xnotes.core.geometry.Geometry
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.Rgba

/** Polygon fill rule (spec 01 §1). Both are required. */
enum class FillRule { NONZERO, EVEN_ODD }

/**
 * How a layer composites onto what's beneath it. `SRC_OVER` is normal alpha
 * blending; `MULTIPLY` darkens (the highlighter — it can't lighten dark ink, so
 * text stays legible). `SCREEN` is its mirror image, lightening instead, which is what
 * highlights a dark page. A renderer without real separable blends falls back to `SRC_OVER`.
 */
enum class BlendMode { SRC_OVER, MULTIPLY, SCREEN }

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
    /** Dash on/off run lengths used when [dashed]; device px when [cosmetic], else content px. */
    val dashOn: Double = 6.0,
    val dashGap: Double = 4.0,
    /** How far into the dash pattern this line starts, in the same units as [dashOn]. Lets a line
     *  be drawn in two pieces without the rhythm restarting at the seam: the wet cache bakes a
     *  dashed pen's settled run and hands the tail the arc the run already spent. */
    val dashPhase: Double = 0.0,
    /** Soft outward glow (page-px blur) on the stroke — the neon halo for shapes. 0 = crisp. */
    val glowRadius: Double = 0.0,
)

/**
 * What a stretch of drawing is, for a backend that records a document's structure (a tagged PDF
 * a screen reader can follow). Painters announce it with [Renderer.beginMark]; every other
 * backend ignores it. Marks that stand for one thing on the page are instances, so two images
 * drawn back to back stay two images.
 */
sealed class Mark {
    /** Paper, ruling, highlights, chips, rules: what a reader skips. */
    object Decoration : Mark()

    /** Characters [start, end) of flow paragraph [para]. */
    class FlowText(val para: Int, val start: Int, val end: Int) : Mark()

    /** The bullet, number or box of flow list paragraph [para]. */
    class FlowMarker(val para: Int) : Mark()

    /** Handwriting and shapes. */
    object Drawing : Mark()

    /** One inserted image. */
    class Image : Mark()

    /** One text box, occupying [bounds] on the page. */
    class TextBox(val bounds: Rect) : Mark()
}

/**
 * Immediate-mode 2D vector painter (spec 01 §1). The application issues draw
 * calls every frame; the renderer retains no scene. The same interface draws to
 * the screen, to offscreen [RasterSurface]s, and to PDF export.
 *
 * Only translation, uniform/axis scaling and quarter-turn [rotate] are used —
 * never free rotation or shear. The one exception is [drawImage], which turns a
 * placed bitmap about its own centre because pixels cannot be rotated by moving
 * points the way ink and shapes are.
 */
interface Renderer {
    // --- transform / clip stack ---
    fun save()
    fun restore()

    /**
     * Rotate the coordinate system clockwise by [degrees] — always a multiple of 90
     * (the rotated-page view), so implementations stay exact and axis-aligned. The
     * default no-op is for backends that never draw rotated views (PDF export).
     */
    fun rotate(degrees: Double) {}

    /**
     * Begin an offscreen layer over [bounds] that is composited at [alpha] when
     * the matching [restore] runs. Content drawn into the layer is accumulated
     * opaquely first, so overlapping fills don't compound — used to render a
     * translucent stroke (e.g. the highlighter) uniformly. Pair with [restore].
     */
    fun saveLayerAlpha(bounds: Rect, alpha: Double)

    /**
     * Like [saveLayerAlpha] but the layer composites with [blend] when [restore]
     * runs. Used to multiply the highlighter onto the page so it tints light areas
     * and preserves dark ink. Default falls back to plain alpha compositing.
     */
    fun saveLayerBlended(bounds: Rect, alpha: Double, blend: BlendMode) = saveLayerAlpha(bounds, alpha)

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

    /**
     * Like [fillPolygon]/[fillCircle] but with a soft Gaussian glow of [blurRadius]
     * page pixels — the neon halo. When [inner] is true the blur is confined to the
     * shape's interior (edges fade *inward* to transparent, nothing spills outside) —
     * used for the white-hot core so the ink colour stays crisp at the tube's rim.
     * [pts] is a packed interleaved x,y polygon (stroke geometry is stored packed).
     * A renderer with no blur primitive (or export target) falls back to a crisp
     * fill, so the ink stays visible.
     */
    fun fillPolygonGlow(pts: FloatArray, color: Rgba, rule: FillRule, blurRadius: Double, inner: Boolean = false) =
        fillPolygon(unpackPts(pts), color, rule)

    fun fillCircleGlow(center: Pt, radius: Double, color: Rgba, blurRadius: Double, inner: Boolean = false) =
        fillCircle(center, radius, color)

    /**
     * Fill an ink ribbon as a circular brush disc swept along [centers] (packed interleaved x,y)
     * with per-point [radii]: a disc at every centre, plus the [Geometry.ribbonQuad] bridging each
     * consecutive pair, all in [color]. Because the nib is a disc, round caps and joins fall out
     * for free on every pen, and because every piece is convex and merely filled (never one
     * self-overlapping outline) a sharp turn can't leave a winding-cancelled gap. The default
     * fills each piece on its own — correct for an opaque single-colour ribbon since the overlaps
     * just repaint the same colour; an anti-aliasing backend should override to union them into
     * one path so shared edges don't seam.
     */
    fun fillDiskRibbon(centers: FloatArray, radii: FloatArray, color: Rgba) =
        fillDiskRibbon(centers, radii, 0, minOf(centers.size / 2, radii.size), color)

    /**
     * [fillDiskRibbon] over the [count] points starting at [from], so a run of a ribbon can be
     * drawn without slicing its arrays. The wet cache paints in two runs — the settled prefix into
     * a raster, the moving tail live over it — and a live stroke's arrays are over-allocated, so
     * neither piece can be described by an array's own length.
     */
    fun fillDiskRibbon(centers: FloatArray, radii: FloatArray, from: Int, count: Int, color: Rgba) {
        if (count <= 0) return
        fun pt(i: Int) = Pt(centers[2 * i].toDouble(), centers[2 * i + 1].toDouble())
        val end = from + count
        for (i in from until end - 1) {
            val q = Geometry.ribbonQuad(pt(i), radii[i].toDouble(), pt(i + 1), radii[i + 1].toDouble())
            if (q.size >= 3) fillPolygon(q, color, FillRule.NONZERO)
        }
        for (i in from until end) if (radii[i] > 0f) fillCircle(pt(i), radii[i].toDouble(), color)
    }

    // --- outlines (cosmetic for chrome, page-space for shapes) ---
    fun strokeRect(rect: Rect, pen: Pen)
    fun strokePolyline(points: List<Pt>, pen: Pen)

    /** [strokePolyline] over a packed interleaved x,y polyline (the dashed pen's centerline). */
    fun strokePolyline(pts: FloatArray, pen: Pen) = strokePolyline(pts, 0, pts.size / 2, pen)

    /** [strokePolyline] over [count] packed points starting at [from]; see [fillDiskRibbon]. */
    fun strokePolyline(pts: FloatArray, from: Int, count: Int, pen: Pen) =
        strokePolyline(List(count) { Pt(pts[2 * (from + it)].toDouble(), pts[2 * (from + it) + 1].toDouble()) }, pen)

    fun strokePolygon(points: List<Pt>, pen: Pen)
    fun strokeEllipse(center: Pt, rx: Double, ry: Double, pen: Pen)

    // --- raster + text ---
    /** Draw [raster] scaled into [dest] (optionally only [src] sub-rect), smooth sampling. */
    fun drawRaster(raster: RasterSurface, dest: Rect, src: Rect? = null)

    /**
     * Like [drawRaster] but composites the bitmap at [alpha] with [blend]. Lets a pre-rendered
     * (opaque) highlighter ribbon be blitted so it MULTIPLY-darkens against the live page each
     * frame without re-tessellating the stroke. Default ignores [alpha]/[blend] and draws opaque,
     * so backends without a real blend (export/tests) stay correct for the paths that use them.
     */
    fun drawRasterBlended(raster: RasterSurface, dest: Rect, alpha: Double, blend: BlendMode, src: Rect? = null) =
        drawRaster(raster, dest, src)

    /**
     * Draw [image] (its encoded source) scaled into [dest], turned [orientation] degrees clockwise
     * (0/90/180/270) and then [angle] radians clockwise about [dest]'s centre. The backend decodes
     * only as large as [dest] needs, so a big photo is never fully decoded into memory. A placed
     * bitmap is the one thing that cannot rotate by moving its own points, which is why this is the
     * single primitive that takes a free angle. Default is a no-op for backends that don't place
     * images.
     */
    fun drawImage(image: ImageData, dest: Rect, orientation: Int = 0, angle: Double = 0.0) {}

    fun drawText(text: String, rect: Rect, font: FontSpec, color: Rgba, flags: TextFlags = TextFlags())

    /**
     * Draw a single pre-positioned line fragment with its left edge at [x] and its
     * baseline at [baseline] (content space, no wrapping). The flow-text painter
     * places these from [TextMeasurer.advances] prefix sums, so the two must share
     * one paint. Default is a no-op for backends that never see flow text.
     */
    fun drawTextRun(text: String, x: Double, baseline: Double, font: FontSpec, color: Rgba) {}

    /**
     * True for a backend that writes real text a reader can select and copy (PDF
     * export). Painters then also hand it what the screen never draws: the spaces
     * between words, as runs of their own, so copied text keeps its word breaks
     * and a code block its indentation.
     */
    val writesText: Boolean get() = false

    /** What the drawing from here to [endMark] is. Only a structure-recording backend cares. */
    fun beginMark(mark: Mark) {}

    fun endMark() {}

    /**
     * A list bullet: a dot of [radius] at [center], on a line whose baseline is at
     * [baseline] and whose text is set in [font]. A backend writing real text makes it
     * a glyph that copies as "•"; everything else just paints the dot.
     */
    fun drawBullet(center: Pt, radius: Double, baseline: Double, font: FontSpec, color: Rgba) =
        fillCircle(center, radius, color)

    /**
     * A checklist box outlined [stroke] wide, filled inside when [checked]. A backend
     * writing real text makes it a glyph that copies as "☐" or "☑".
     */
    fun drawCheckbox(box: Rect, stroke: Double, checked: Boolean, baseline: Double, font: FontSpec, color: Rgba) {
        strokeRect(box, Pen(color, width = stroke, cosmetic = false))
        // Checked state is a solid inner fill: legible on any paper without knowing it.
        if (checked) fillRect(box.outset(-box.w * 0.25), color)
    }

    /**
     * Draw the formula [latex] sets at [sizePt], its left edge at [x] and its own
     * baseline on [baseline] so it sits on the line like a word. The backend that
     * draws this must be the one behind [MathTypesetter], which already measured
     * the same string, or the text will wrap around a box that is not there.
     * Default is a no-op, like [drawTextRun], for backends that never see maths.
     */
    fun drawMath(
        latex: String,
        x: Double,
        baseline: Double,
        sizePt: Double,
        color: Rgba,
        display: Boolean = false,
    ) {}

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

/** Packed interleaved x,y → [Pt] list, for the default (non-hot) packed-primitive fallbacks. */
private fun unpackPts(pts: FloatArray): List<Pt> =
    List(pts.size / 2) { Pt(pts[2 * it].toDouble(), pts[2 * it + 1].toDouble()) }
