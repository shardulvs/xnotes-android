package com.xnotes.platform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.LruCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.LatexFontFamilies
import com.hrm.latex.renderer.model.LatexTheme
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.MathBox
import com.xnotes.core.pal.MathSize
import com.xnotes.core.pal.MathTypesetter
import java.lang.ref.WeakReference
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.ceil
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Typesets and draws the flow's LaTeX, through a renderer that can only be built
 * inside a composition. One per process like [com.xnotes.settings.LiveSettings],
 * because a [AndroidRenderer] is made fresh around each canvas and there is
 * nowhere else for the formula cache to live.
 *
 * Setting a formula takes the library milliseconds, so the main thread never does it: [size]
 * answers pending and queues the formula for a worker, and a canvas draws a stand-in until it
 * lands, when [Listener]s hear of it. Only callers off the main thread ([Blocking], [Pace.WAIT])
 * set formulas where they stand.
 *
 * A formula is laid out once, and that one layout gives both its box and its rasters. Rasters are
 * alpha masks the text colour tints when drawn, so a new colour or theme costs nothing, except for
 * the few formulas that paint in colours of their own.
 */
object MathRendering : MathTypesetter {

    /** The renderer's pieces, all built against one density. */
    private class Engine(
        val spPx: Float,
        val density: Density,
        val text: TextMeasurer,
        val fonts: LatexFontFamilies,
        val metrics: Any,
    )

    @Volatile private var engine: Engine? = null

    private val installed = CountDownLatch(1)

    /** A formula as the caches know it. */
    private data class Formula(val latex: String, val sizePt: Double, val display: Boolean)

    /**
     * What setting a formula found: the box it fills, null for LaTeX that will not set, and whether
     * it paints in colours of its own, which a tinted mask cannot carry.
     */
    private class Setting(val box: MathBox?, val colored: Boolean)

    /** A raster of [formula] at [bucket]; [argb] is the colour it was painted in, or 0 for a mask. */
    private data class RasterKey(val formula: Formula, val bucket: Int, val argb: Int)

    /** How a canvas draws a formula that is not ready to draw. */
    enum class Pace {
        /** Set it there and then: thumbnails and exports, off the main thread. */
        WAIT,

        /** Queue it and shimmer in its place: the live canvas. */
        LIVE,

        /** Queue it and hold a flat tint in its place: a still of a page. */
        STILL,
    }

    /** Told on the main thread as queued formulas land. */
    fun interface Listener {
        /** [sized] when a box landed, which moves the layout; [idle] once nothing more is queued. */
        fun landed(sized: Boolean, idle: Boolean)
    }

    // Settings are a few dozen bytes each and the layout pass asks after every formula in the note,
    // so this holds far more than any note has.
    private const val SETTINGS = 8192

    // Layouts hold the library's text layouts, so only the latest few are kept: enough to raster a
    // screenful again at a new zoom without setting any of it twice.
    private const val LAYOUTS = 48

    private const val RASTER_BYTES = 32 shl 20

    // No raster side may pass this; a long formula at a deep zoom softens rather than costing tens
    // of megabytes.
    private const val MAX_SIDE_PX = 4096.0

    // Rasters come in powers of two of their page size, from a quarter to eight times.
    private const val MIN_BUCKET = -2
    private const val MAX_BUCKET = 3

    // The bitmap a PDF export falls back to when it cannot take a formula as outlines.
    private const val EXPORT_SCALE = 3f

    // A formula a canvas stopped asking for this long ago has scrolled away; one fetched ahead
    // for a page nearby is given longer, since nothing asks again while the canvas is still.
    private const val SHOWN_STALE_MS = 750L
    private const val NEARBY_STALE_MS = 2000L

    // Each batch that brings boxes lays the whole flow out again, so results reach the main thread
    // in batches: soon for formulas on screen, which shimmer meanwhile, less often for the rest.
    private const val ON_SCREEN_MS = 60L
    private const val OFF_SCREEN_MS = 300L

    // How long a caller that can wait gives the composition to hand over a renderer.
    private const val ENGINE_WAIT_MS = 5000L

    // The stand-in: a tint of the formula's ink, with a paler sheen sweeping across it.
    private const val STAND_IN_ALPHA = 0.14f
    private const val SHEEN_ALPHA = 0.05f
    private const val SHEEN_MS = 1300L
    private const val CORNER = 0.18f

    /** What a mask is painted in: its alpha is all a mask keeps. */
    private val MASK_INK = Rgba(0, 0, 0, 255)

    /** Stands in the cache for a raster that could not be made, so it is not asked for again. */
    private val NONE: Bitmap by lazy { Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8) }

    private val settings = LruCache<Formula, Setting>(SETTINGS)

    /** Touched only holding the lock, like everything else that reaches the library. */
    private val layouts = LruCache<Formula, Any>(LAYOUTS)

    private val rasters = object : LruCache<RasterKey, Bitmap>(RASTER_BYTES) {
        override fun sizeOf(key: RasterKey, value: Bitmap): Int = value.allocationByteCount
    }

    /**
     * What a drawing thread paints formulas with. Each renderer keeps its own, so the colour a mask
     * is tinted with is never set by two threads at once.
     */
    class Ink {
        internal val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        internal val standIn = Paint(Paint.ANTI_ALIAS_FLAG)
        internal val rect = RectF()
        internal val matrix = Matrix()
        private var sheen: LinearGradient? = null
        private var sheenOf = 0

        /** A band from [base] to [light] and back, one unit wide, for the stand-in's sweep. */
        internal fun sheen(base: Int, light: Int): LinearGradient {
            sheen?.let { if (sheenOf == base) return it }
            return LinearGradient(0f, 0f, 1f, 0f, intArrayOf(base, light, base), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
                .also {
                    sheen = it
                    sheenOf = base
                }
        }
    }

    /**
     * Adopt the renderer built in the composition. Called again whenever the
     * density or the fonts change, which invalidates every formula set so far.
     */
    fun install(density: Density, text: TextMeasurer, fonts: LatexFontFamilies) {
        val spPx = with(density) { 1.sp.toPx() }
        synchronized(this) {
            engine = Engine(spPx, density, text, fonts, LatexInternals.metrics(fonts))
            settings.evictAll()
            layouts.evictAll()
            rasters.evictAll()
        }
        installed.countDown()
        queueLock.withLock { work.signal() }
    }

    /** The engine, waited for a while by a caller off the main thread that would rather not go without. */
    private fun awaitEngine(): Engine? {
        engine?.let { return it }
        if (Looper.myLooper() != Looper.getMainLooper()) installed.await(ENGINE_WAIT_MS, TimeUnit.MILLISECONDS)
        return engine
    }

    /** Formulas queue before the renderer exists, and set once it does. */
    override fun ready(): Boolean = true

    override fun measure(latex: String, sizePt: Double, display: Boolean): MathBox? {
        if (latex.isBlank()) return null
        val e = awaitEngine() ?: return null
        if (e.spPx <= 0f) return null
        val f = Formula(latex, sizePt, display)
        settings.get(f)?.let { return it.box }
        // The library caches in plain maps, so every call into it is serialized here.
        return synchronized(this) { settings.get(f) ?: layOut(e, f).first }.box
    }

    override fun size(latex: String, sizePt: Double, display: Boolean): MathSize {
        if (latex.isBlank()) return MathSize.Refused
        val f = Formula(latex, sizePt, display)
        settings.get(f)?.let { return it.box ?: MathSize.Refused }
        queueLock.withLock {
            if (laid.add(f)) {
                earlier.remove(f)
                wake()
            }
        }
        return MathSize.Pending
    }

    override fun beginLayout() {
        queueLock.withLock {
            if (laid.isEmpty()) return
            earlier.addAll(laid)
            laid = LinkedHashSet()
        }
    }

    /** The same typesetter for a caller off the main thread that would rather wait than guess. */
    object Blocking : MathTypesetter {
        override fun measure(latex: String, sizePt: Double, display: Boolean): MathBox? =
            MathRendering.measure(latex, sizePt, display)

        override fun ready(): Boolean = awaitEngine() != null
    }

    /** Set [f] afresh, remembering the setting and the layout it came from. Call holding the lock. */
    private fun layOut(e: Engine, f: Formula): Pair<Setting, Any?> {
        val nodes = runCatching { LatexInternals.parse(source(f.latex, f.display)) }.getOrDefault(emptyList())
        val layout = runCatching {
            LatexInternals.layout(nodes, config(e, f.sizePt, MASK_INK), e.fonts, e.metrics, e.text, e.density)
        }.getOrNull()
        val setting = Setting(layout?.let(::boxOf), LatexInternals.ownColors(nodes))
        settings.put(f, setting)
        if (layout != null) layouts.put(f, layout)
        return setting to layout
    }

    private fun boxOf(layout: Any): MathBox {
        val baseline = LatexInternals.baseline(layout).toDouble()
        return MathBox(
            width = LatexInternals.width(layout).toDouble(),
            ascent = baseline,
            descent = LatexInternals.height(layout) - baseline,
        )
    }

    /**
     * Draw [latex] with its left edge at [x], sitting on [baseline], in [color], on a canvas that
     * shows one page px as [scale] of its own. True when it is drawn, or never will be because it
     * does not set; false when it is still being set at the [pace] given, and the caller stands in.
     */
    fun draw(
        canvas: Canvas,
        ink: Ink,
        latex: String,
        x: Double,
        baseline: Double,
        sizePt: Double,
        color: Rgba,
        display: Boolean,
        scale: Float,
        pace: Pace,
    ): Boolean {
        if (latex.isBlank()) return true
        val f = Formula(latex, sizePt, display)
        val bucket = bucketFor(scale)
        if (pace == Pace.WAIT) {
            val e = awaitEngine() ?: return true
            val cached = settings.get(f)?.let { s -> rasters.get(RasterKey(f, bucket, argbFor(s, color))) }
            val bmp = cached ?: synchronized(this) { rasterLocked(e, f, bucket, color) }
            val box = settings.get(f)?.box ?: return true
            blit(canvas, ink, bmp, x, baseline, box, color)
            return true
        }
        val setting = settings.get(f)
        if (setting == null) {
            ask(f, bucket, color, color.toArgb())
            return false
        }
        val box = setting.box ?: return true
        val argb = argbFor(setting, color)
        val bmp = rasters.get(RasterKey(f, bucket, argb)) ?: run {
            ask(f, bucket, color, argb)
            // Whatever size of it there is stands in meanwhile, so a pinch never flashes a formula.
            nearest(f, bucket, argb)
        } ?: return false
        blit(canvas, ink, bmp, x, baseline, box, color)
        return true
    }

    /** Queue [latex] for a page the canvas will likely show next, after the formulas it shows now. */
    fun prefetch(latex: String, sizePt: Double, display: Boolean, color: Rgba, scale: Float) {
        if (latex.isBlank()) return
        val f = Formula(latex, sizePt, display)
        val bucket = bucketFor(scale)
        val setting = settings.get(f)
        val argb = setting?.let { argbFor(it, color) } ?: color.toArgb()
        if (setting != null && (setting.box == null || rasters.get(RasterKey(f, bucket, argb)) != null)) return
        ask(f, bucket, color, argb, nearby = true)
    }

    /** Queue [latex] for a canvas that is showing a stand-in for it now. */
    fun want(latex: String, sizePt: Double, display: Boolean, color: Rgba, scale: Float) {
        if (latex.isBlank()) return
        val f = Formula(latex, sizePt, display)
        val argb = settings.get(f)?.let { argbFor(it, color) } ?: color.toArgb()
        ask(f, bucketFor(scale), color, argb)
    }

    /** A stand-in at [left]..[bottom] for a formula not drawn yet, in a tint of [color]; it sweeps unless [still]. */
    fun drawStandIn(canvas: Canvas, ink: Ink, left: Double, top: Double, right: Double, bottom: Double, color: Rgba, still: Boolean) {
        val w = (right - left).toFloat()
        val h = (bottom - top).toFloat()
        if (w <= 0f || h <= 0f) return
        ink.rect.set(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
        val radius = min(w, h) * CORNER
        val base = tint(color, STAND_IN_ALPHA)
        val paint = ink.standIn
        if (still) {
            paint.shader = null
            paint.color = base
        } else {
            val band = max(h * 2.5f, w * 0.3f)
            val phase = (SystemClock.uptimeMillis() % SHEEN_MS) / SHEEN_MS.toFloat()
            ink.matrix.setScale(band, 1f)
            ink.matrix.postTranslate(left.toFloat() - band + phase * (w + band), 0f)
            val sheen = ink.sheen(base, tint(color, SHEEN_ALPHA))
            sheen.setLocalMatrix(ink.matrix)
            paint.shader = sheen
            paint.color = android.graphics.Color.BLACK
        }
        canvas.drawRoundRect(ink.rect, radius, radius, paint)
    }

    private fun tint(color: Rgba, alpha: Float): Int =
        (color.toArgb() and 0xFFFFFF) or ((color.a * alpha).toInt().coerceIn(0, 255) shl 24)

    private fun argbFor(setting: Setting, color: Rgba): Int = if (setting.colored) color.toArgb() else 0

    /** [f]'s raster nearest [bucket], larger ones first since shrinking stays sharp. */
    private fun nearest(f: Formula, bucket: Int, argb: Int): Bitmap? {
        for (b in bucket + 1..MAX_BUCKET) rasters.get(RasterKey(f, b, argb))?.let { return it }
        for (b in bucket - 1 downTo MIN_BUCKET) rasters.get(RasterKey(f, b, argb))?.let { return it }
        return null
    }

    /** [f]'s raster at [bucket] for [color], painted now unless it is cached. Call holding the lock. */
    private fun rasterLocked(e: Engine, f: Formula, bucket: Int, color: Rgba): Bitmap {
        var setting = settings.get(f)
        var layout = layouts.get(f)
        if (setting == null || (setting.box != null && layout == null)) {
            val (s, l) = layOut(e, f)
            setting = s
            layout = l
        }
        val box = setting.box ?: return NONE
        val key = RasterKey(f, bucket, argbFor(setting, color))
        rasters.get(key)?.let { return it }
        // The colours a formula paints in sit in its layout, so one that has its own is set again in
        // the text colour; any other paints its mask from the layout it was measured with.
        val painted = if (setting.colored) layoutIn(e, f, color) else layout
        val bmp = painted?.let { paint(e, it, box, scaleOf(bucket), setting.colored) } ?: NONE
        rasters.put(key, bmp)
        return bmp
    }

    private fun layoutIn(e: Engine, f: Formula, color: Rgba): Any? = runCatching {
        LatexInternals.layout(LatexInternals.parse(source(f.latex, f.display)), config(e, f.sizePt, color), e.fonts, e.metrics, e.text, e.density)
    }.getOrNull()

    /** [layout] painted to fill [box] at [scale], as a mask unless it is [colored]. */
    private fun paint(e: Engine, layout: Any, box: MathBox, scale: Float, colored: Boolean): Bitmap? {
        val longest = max(box.width, box.height)
        if (longest <= 0.0) return null
        val s = min(scale.toDouble(), MAX_SIDE_PX / longest)
        val w = ceil(box.width * s).toInt().coerceAtLeast(1)
        val h = ceil(box.height * s).toInt().coerceAtLeast(1)
        val bmp = runCatching {
            Bitmap.createBitmap(w, h, if (colored) Bitmap.Config.ARGB_8888 else Bitmap.Config.ALPHA_8)
        }.getOrNull() ?: return null
        val canvas = Canvas(bmp)
        canvas.scale((w / box.width).toFloat(), (h / box.height).toFloat())
        val size = Size(box.width.toFloat(), box.height.toFloat())
        CanvasDrawScope().draw(e.density, LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(canvas), size) {
            LatexInternals.draw(this, layout)
        }
        return bmp
    }

    private fun blit(canvas: Canvas, ink: Ink, bmp: Bitmap, x: Double, baseline: Double, box: MathBox, color: Rgba) {
        if (bmp === NONE) return
        // A mask takes the text colour from the paint; a coloured raster already carries it.
        ink.paint.color = if (bmp.config == Bitmap.Config.ALPHA_8) color.toArgb() else android.graphics.Color.BLACK
        ink.rect.set(
            x.toFloat(),
            (baseline - box.ascent).toFloat(),
            (x + box.width).toFloat(),
            (baseline + box.descent).toFloat(),
        )
        canvas.drawBitmap(bmp, null, ink.rect, ink.paint)
    }

    // --- the queue ---

    /** A raster a canvas is standing in for now, or will soon, and when it last asked. */
    private class Shown(val formula: Formula, val bucket: Int, val color: Rgba, var asked: Long, val nearby: Boolean)

    private val queueLock = ReentrantLock()
    private val work = queueLock.newCondition()

    /** Rasters canvases are standing in for, first come first set. */
    private val shown = LinkedHashMap<RasterKey, Shown>()

    /** Rasters for pages beside the ones in view, set once those in view are done. */
    private val nearby = LinkedHashMap<RasterKey, Shown>()

    /** Formulas the latest layout pass asked after, in its order, which is the note's. */
    private var laid = LinkedHashSet<Formula>()

    /** Formulas asked after before that and not since, set once the rest are done. */
    private val earlier = LinkedHashSet<Formula>()

    private var idle = true
    private var sizedSince = false
    private var deliverAt = 0L

    /** Held weakly, since this outlives any editor that listens; each editor holds its own. */
    private val listeners = CopyOnWriteArrayList<WeakReference<Listener>>()

    private val main by lazy { Handler(Looper.getMainLooper()) }

    private val worker: Thread by lazy {
        Thread({ work() }, "xnotes-math").apply {
            isDaemon = true
            start()
        }
    }

    fun addListener(l: Listener) {
        listeners.add(WeakReference(l))
    }

    fun removeListener(l: Listener) {
        listeners.removeAll { it.get().let { held -> held == null || held === l } }
    }

    private fun ask(f: Formula, bucket: Int, color: Rgba, argb: Int, nearby: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        val key = RasterKey(f, bucket, argb)
        queueLock.withLock {
            val queue = if (nearby) this.nearby else shown
            val s = queue[key]
            if (s != null) {
                s.asked = now
                return
            }
            queue[key] = Shown(f, bucket, color, now, nearby)
            wake()
        }
    }

    /** Call holding [queueLock]. */
    private fun wake() {
        idle = false
        worker
        work.signal()
    }

    private fun work() {
        while (true) {
            val job = next()
            var sized = false
            synchronized(this) {
                val e = engine ?: return@synchronized
                val f = if (job is Shown) job.formula else job as Formula
                val had = settings.get(f) != null
                if (job is Shown) rasterLocked(e, f, job.bucket, job.color) else if (!had) layOut(e, f)
                sized = !had
            }
            landed(sized, onScreen = job is Shown && !job.nearby)
        }
    }

    /** The next formula to set, waiting while there is none or no engine to set it with. */
    private fun next(): Any {
        queueLock.lock()
        try {
            while (true) {
                if (engine != null) {
                    val now = SystemClock.uptimeMillis()
                    val shownLeft = shown.values.iterator()
                    while (shownLeft.hasNext()) {
                        val s = shownLeft.next()
                        shownLeft.remove()
                        if (now - s.asked <= SHOWN_STALE_MS) return s
                    }
                    val nearbyLeft = nearby.values.iterator()
                    while (nearbyLeft.hasNext()) {
                        val s = nearbyLeft.next()
                        nearbyLeft.remove()
                        if (now - s.asked <= NEARBY_STALE_MS) return s
                    }
                    laid.firstOrNull()?.let {
                        laid.remove(it)
                        return it
                    }
                    earlier.firstOrNull()?.let {
                        earlier.remove(it)
                        return it
                    }
                    if (!idle) {
                        idle = true
                        deliver(ON_SCREEN_MS)
                    }
                }
                work.await()
            }
        } finally {
            queueLock.unlock()
        }
    }

    private fun landed(sized: Boolean, onScreen: Boolean) = queueLock.withLock {
        if (sized) sizedSince = true
        deliver(if (onScreen) ON_SCREEN_MS else OFF_SCREEN_MS)
    }

    /** Hand what has landed to the main thread within [delayMs], unless it is going sooner. Call holding [queueLock]. */
    private fun deliver(delayMs: Long) {
        val at = SystemClock.uptimeMillis() + delayMs
        if (deliverAt != 0L && deliverAt <= at) return
        deliverAt = at
        main.removeCallbacks(delivery)
        main.postAtTime(delivery, at)
    }

    private val delivery = Runnable {
        val sized: Boolean
        val wasIdle: Boolean
        queueLock.withLock {
            sized = sizedSince
            wasIdle = idle
            sizedSince = false
            deliverAt = 0L
        }
        for (ref in listeners) ref.get()?.landed(sized, wasIdle) ?: listeners.remove(ref)
    }

    /**
     * Draw [latex] with the renderer's own drawing calls into [canvas], its box's top-left at the
     * canvas origin and in content px: the same box [measure] reports, so a backend recording the
     * calls gets the formula as outlines where the bitmap would have gone. False when there is no
     * engine yet or the LaTeX does not set.
     */
    fun drawVector(canvas: Canvas, latex: String, sizePt: Double, color: Rgba, display: Boolean): Boolean {
        if (latex.isBlank()) return false
        val e = awaitEngine() ?: return false
        if (e.spPx <= 0f) return false
        return synchronized(this) {
            runCatching {
                val layout = layoutIn(e, Formula(latex, sizePt, display), color) ?: return@runCatching false
                val size = Size(LatexInternals.width(layout), LatexInternals.height(layout))
                CanvasDrawScope().draw(e.density, LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(canvas), size) {
                    LatexInternals.draw(this, layout)
                }
                true
            }.getOrDefault(false)
        }
    }

    /**
     * [latex] painted in [color] and the box it fills, for a backend that places the image itself.
     * The bitmap is a fresh one the caller owns.
     */
    fun formulaBitmap(latex: String, sizePt: Double, color: Rgba, display: Boolean): Pair<Bitmap, MathBox>? {
        if (latex.isBlank()) return null
        val e = awaitEngine() ?: return null
        if (e.spPx <= 0f) return null
        return synchronized(this) {
            val f = Formula(latex, sizePt, display)
            val box = (settings.get(f) ?: layOut(e, f).first).box ?: return@synchronized null
            val layout = layoutIn(e, f, color) ?: return@synchronized null
            paint(e, layout, box, EXPORT_SCALE, colored = true)?.let { it to box }
        }
    }

    /** The power of two at least [scale], so a pinch rasters a formula again only at doublings. */
    private fun bucketFor(scale: Float): Int =
        ceil(log2(scale.coerceAtLeast(1e-3f).toDouble())).toInt().coerceIn(MIN_BUCKET, MAX_BUCKET)

    private fun scaleOf(bucket: Int): Float = 2f.pow(bucket)

    /**
     * What actually goes to the renderer. Display form is asked for in LaTeX's own
     * terms rather than a render flag, so it is the same string the user would
     * have written by hand and the measurement and the raster cannot disagree.
     */
    private fun source(latex: String, display: Boolean): String =
        braceFractionArgs(latex).let { if (display) "\\displaystyle $it" else it }

    /**
     * Ask for the formula in page pixels rather than screen ones: dividing the
     * size back out of the measurer's own density is what keeps an equation the
     * same size on the page whatever the display or the reader's font scale.
     */
    private fun config(e: Engine, sizePt: Double, color: Rgba): LatexConfig {
        val px = sizePt * AndroidText.POINTS_TO_PX
        return LatexConfig(
            fontSize = (px / e.spPx).sp,
            theme = LatexTheme.light(color = Color(color.toArgb()), backgroundColor = Color.Transparent),
        )
    }
}

private val FRACTION_COMMAND = Regex("""\\(?:[dtc]?frac|[dt]?binom)(?![A-Za-z])""")

/**
 * TeX reads `\frac x2` as x over 2, one character per bare argument, but the library takes the
 * whole run `x2` as the numerator. Bracing each bare argument gives it what TeX means.
 */
internal fun braceFractionArgs(latex: String): String {
    if ('\\' !in latex) return latex
    val out = StringBuilder(latex.length + 8)
    var i = 0
    for (m in FRACTION_COMMAND.findAll(latex)) {
        if (m.range.first < i) continue
        out.append(latex, i, m.range.last + 1)
        i = m.range.last + 1
        repeat(2) {
            var j = i
            while (j < latex.length && latex[j].isWhitespace()) j++
            if (j >= latex.length) return@repeat
            val c = latex[j]
            val end = when {
                c == '{' -> {
                    var depth = 0
                    var k = j
                    while (k < latex.length) {
                        if (latex[k] == '\\') k++
                        else if (latex[k] == '{') depth++
                        else if (latex[k] == '}' && --depth == 0) break
                        k++
                    }
                    if (k >= latex.length) return@repeat
                    out.append(latex, i, j + 1).append(braceFractionArgs(latex.substring(j + 1, k))).append('}')
                    i = k + 1; null
                }
                c == '\\' -> {
                    var k = j + 1
                    if (k < latex.length && latex[k].isLetter()) while (k < latex.length && latex[k].isLetter()) k++ else k++
                    out.append(latex, i, minOf(k, latex.length)); i = minOf(k, latex.length); null
                }
                c.isLetterOrDigit() -> j
                else -> return@repeat
            }
            if (end != null) { out.append('{').append(c).append('}'); i = end + 1 }
        }
    }
    return out.append(latex, i, latex.length).toString()
}
