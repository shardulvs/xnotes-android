package com.xnotes.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.hrm.latex.renderer.export.ExportConfig
import com.hrm.latex.renderer.export.LatexExporterState
import com.hrm.latex.renderer.measure.LatexMeasurerState
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.LatexFontFamilies
import com.hrm.latex.renderer.model.LatexTheme
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.MathBox
import com.xnotes.core.pal.MathTypesetter

/**
 * Typesets and draws the flow's LaTeX, through a renderer that can only be built
 * inside a composition. One per process like [com.xnotes.settings.LiveSettings],
 * because a [AndroidRenderer] is made fresh around each canvas and there is
 * nowhere else for the formula cache to live. Until [install] runs, measuring
 * returns null and maths reads as its own source, which is also what happens to
 * LaTeX that will not parse.
 */
object MathRendering : MathTypesetter {

    /**
     * The measurer, the exporter and the density they were both built against, plus the pieces
     * they were built from, which drawing a formula as vectors needs directly.
     */
    private class Engine(
        val measurer: LatexMeasurerState,
        val exporter: LatexExporterState,
        val spPx: Float,
        val density: Density,
        val text: TextMeasurer,
        val fonts: LatexFontFamilies,
    )

    @Volatile private var engine: Engine? = null

    // Formulas raster at this multiple of their page size so zooming in stays
    // sharp; past it they soften like any other bitmap, which is the trade for
    // not re-rastering every one of them on every pinch.
    private const val OVERSAMPLE = 3f

    // No bitmap side may pass this. A page-wide formula at full oversample runs
    // to thousands of pixels a side and tens of megabytes; the exporter answers
    // that with null and the page then draws nothing at all, which loses the
    // whole equation rather than softening it.
    private const val MAX_BITMAP_PX = 2048.0

    // Below this a formula would raster smaller than it is drawn, which is worse
    // than a large bitmap.
    private const val MIN_OVERSAMPLE = 1.0

    private const val CACHE_BYTES = 8 shl 20

    private const val BOX_CACHE = 512

    /** Stands in for LaTeX the typesetter refused, so it is only ever asked once. */
    private val REFUSED = MathBox(-1.0, -1.0, -1.0)

    private val bitmaps = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /**
     * Sizes already worked out. Measuring is the slow half, hundreds of
     * milliseconds for a long formula, and the painter asks after every visible
     * one on every frame; the warm path has to answer without reaching the
     * library at all, whose own caches sit behind a lock this would otherwise
     * share with whatever is laying out in the background.
     */
    private val boxes = LruCache<String, MathBox>(BOX_CACHE)

    private val blit = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /**
     * Adopt the renderer built in the composition. Called again whenever the
     * density changes, which invalidates every rastered formula.
     */
    fun install(
        measurer: LatexMeasurerState,
        exporter: LatexExporterState,
        density: Density,
        text: TextMeasurer,
        fonts: LatexFontFamilies,
    ) {
        val spPx = with(density) { 1.sp.toPx() }
        synchronized(this) {
            engine = Engine(measurer, exporter, spPx, density, text, fonts)
            bitmaps.evictAll()
            boxes.evictAll()
        }
    }

    override fun ready(): Boolean = engine != null

    override fun measure(latex: String, sizePt: Double, display: Boolean): MathBox? {
        val e = engine ?: return null
        if (latex.isBlank() || e.spPx <= 0f) return null
        val key = key(latex, sizePt, display)
        boxes.get(key)?.let { return if (it === REFUSED) null else it }
        // The library caches in plain maps, and page caches are built off the main
        // thread, so every call into it is serialized here rather than there.
        val d = synchronized(this) { e.measurer.measure(source(latex, display), config(e, sizePt, null)) }
        val box = if (d == null) {
            REFUSED
        } else {
            MathBox(
                width = d.widthPx.toDouble(),
                ascent = d.baselinePx.toDouble(),
                descent = (d.heightPx - d.baselinePx).toDouble(),
            )
        }
        boxes.put(key, box)
        return if (box === REFUSED) null else box
    }

    private fun key(latex: String, sizePt: Double, display: Boolean): String =
        "$latex|$sizePt|$display"

    /** Draw [latex] with its left edge at [x], sitting on [baseline], in [color]. */
    fun draw(
        canvas: Canvas,
        latex: String,
        x: Double,
        baseline: Double,
        sizePt: Double,
        color: Rgba,
        display: Boolean = false,
    ) {
        val e = engine ?: return
        val box = measure(latex, sizePt, display) ?: return
        val bmp = raster(e, source(latex, display), sizePt, color, box) ?: return
        canvas.drawBitmap(
            bmp,
            null,
            RectF(
                x.toFloat(),
                (baseline - box.ascent).toFloat(),
                (x + box.width).toFloat(),
                (baseline + box.descent).toFloat(),
            ),
            blit,
        )
    }

    /**
     * Draw [latex] with the renderer's own drawing calls into [canvas], its box's top-left at the
     * canvas origin and in content px: the same box [measure] reports, so a backend recording the
     * calls gets the formula as outlines where the bitmap would have gone. False when there is no
     * engine yet or the LaTeX does not set.
     */
    fun drawVector(canvas: Canvas, latex: String, sizePt: Double, color: Rgba, display: Boolean): Boolean {
        val e = engine ?: return false
        if (latex.isBlank() || e.spPx <= 0f) return false
        return synchronized(this) {
            runCatching {
                val layout = LatexInternals.layout(source(latex, display), config(e, sizePt, color), e.fonts, e.text, e.density)
                    ?: return@runCatching false
                val size = Size(LatexInternals.width(layout), LatexInternals.height(layout))
                CanvasDrawScope().draw(e.density, LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(canvas), size) {
                    LatexInternals.draw(this, layout)
                }
                true
            }.getOrDefault(false)
        }
    }

    /**
     * The bitmap [draw] blits for [latex] and the box it fills, for a backend that places the
     * image itself. The bitmap belongs to the cache: callers copy it and never recycle it.
     */
    fun formulaBitmap(latex: String, sizePt: Double, color: Rgba, display: Boolean): Pair<Bitmap, MathBox>? {
        val e = engine ?: return null
        val box = measure(latex, sizePt, display) ?: return null
        val bmp = raster(e, source(latex, display), sizePt, color, box) ?: return null
        return bmp to box
    }

    /**
     * What actually goes to the renderer. Display form is asked for in LaTeX's own
     * terms rather than a render flag, so it is the same string the user would
     * have written by hand and the measurement and the raster cannot disagree.
     */
    private fun source(latex: String, display: Boolean): String =
        if (display) "\\displaystyle $latex" else latex

    private fun raster(e: Engine, latex: String, sizePt: Double, color: Rgba, box: MathBox): Bitmap? {
        val key = "$latex|$sizePt|${color.toArgb()}"
        bitmaps.get(key)?.let { return it }
        return synchronized(this) {
            bitmaps.get(key) ?: e.exporter.export(
                latex = latex,
                config = config(e, sizePt, color),
                exportConfig = ExportConfig(scale = oversampleFor(box), transparentBackground = true),
            )?.imageBitmap?.asAndroidBitmap()?.also { bitmaps.put(key, it) }
        }
    }

    /**
     * How far to oversample [box] before the bitmap grows unreasonable. A long
     * formula across a page is already a thousand points wide, and three times
     * that is a bitmap of tens of megabytes: the exporter answers a request that
     * size with null, and a null bitmap is an equation that does not appear at
     * all. Sharpness gives way before the equation does.
     */
    private fun oversampleFor(box: MathBox): Float {
        val longest = maxOf(box.width, box.height)
        if (longest <= 0.0) return OVERSAMPLE
        return (MAX_BITMAP_PX / longest).coerceIn(MIN_OVERSAMPLE, OVERSAMPLE.toDouble()).toFloat()
    }

    /**
     * Ask for the formula in page pixels rather than screen ones: dividing the
     * size back out of the measurer's own density is what keeps an equation the
     * same size on the page whatever the display or the reader's font scale.
     */
    private fun config(e: Engine, sizePt: Double, color: Rgba?): LatexConfig {
        val px = sizePt * AndroidText.POINTS_TO_PX
        return LatexConfig(
            fontSize = (px / e.spPx).sp,
            theme = if (color == null) {
                LatexTheme.auto()
            } else {
                LatexTheme.light(color = Color(color.toArgb()), backgroundColor = Color.Transparent)
            },
        )
    }
}
