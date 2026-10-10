package com.xnotes.core.model

import com.xnotes.core.geometry.Affine
import com.xnotes.core.geometry.Geometry
import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.pal.BlendMode
import com.xnotes.core.pal.FillRule
import com.xnotes.core.pal.Pen
import com.xnotes.core.pal.Renderer
import com.xnotes.core.stroke.Sample
import com.xnotes.core.stroke.StrokeEngine
import com.xnotes.core.stroke.StrokeGeometry
import com.xnotes.core.stroke.WetRibbon
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig

/**
 * The unit of inking (spec 02 §5.1). Holds the raw samples plus a snapshot of
 * the tool style; the ribbon geometry is derived from the samples on demand and
 * cached until the samples change.
 *
 * Samples live in parallel primitive arrays, not a `List<Sample>`: a dense note holds millions of
 * them, and one boxed 4-double object per sample costs ~60 bytes against the 12 the numbers need.
 * Positions are **offsets from a per-stroke double origin** taken at pen-down, so a float never has
 * to carry a large absolute coordinate — the infinite canvas has no coordinate bound, and float32
 * spacing reaches a whole pixel around 1e7. An offset spans one stroke, a few hundred px at most,
 * where float resolves to ~1e-5 px. [samples] is a read-only view over the arrays for the cold call
 * sites; the hot loops read [xAt]/[yAt]/[pAt] and allocate nothing.
 *
 * The arrays and the count they belong to are held together in one immutable [Samples], swapped
 * whole on every edit, so a stroke can be read from another thread while the pen draws on it.
 */
class Stroke(
    val tool: Tool,
    /** Scaled in place when the stroke is resized (var so [applyTransform] can swap a width-scaled
     *  copy); otherwise the immutable style snapshot captured at pen-down. */
    var config: ToolConfig,
    samples: List<Sample> = emptyList(),
    /** Content-px → dp scale captured at pen-down (zoom ÷ display density), so the speed
     *  pen judges gesture speed in zoom- and device-independent units. 1.0 = unscaled. */
    val speedScale: Double = 1.0,
    /** Straight-line mode: render as a raw segment (no position smoothing) so the line reaches
     *  exactly from the first sample to the last — the EMA low-pass otherwise pulls a 2-point
     *  stroke's far end to the midpoint, lagging it behind the stylus. */
    val straight: Boolean = false,
    /** Scale on every arc-length constant the engine measures the hand against: the low-pass
     *  lengths, the nib's head window and its widen/thin rates. Captured at pen-down
     *  as 1 ÷ zoom and capped at 1, so they are lengths of *gesture* rather than of page, and
     *  writing small at high zoom behaves as writing at 100% does. 1.0 = drawn at 100% zoom, and
     *  the default for anything built without a pen. */
    val smoothScale: Double = 1.0,
) : CanvasItem {

    override val kind = KIND
    override val resizable = false
    override var locked = false
    override var link: String? = null

    /**
     * The samples, frozen (see [Samples]). Volatile, and every edit publishes a whole new tuple in
     * one write, so a reader that takes it once holds a count and arrays that belong together.
     * That is what lets the autosave writer serialize this stroke off the main thread while the
     * pen keeps drawing on it, with nothing copied.
     */
    @Volatile
    private var pts: Samples = Samples.EMPTY

    /** The ribbon cache. Volatile because cache builds read it off the UI thread while
     *  [releaseGeometry] may be nulling it on the UI thread; [StrokeGeometry] is immutable, so a
     *  race costs at worst one redundant rebuild. */
    @Volatile
    private var cachedGeometry: StrokeGeometry? = null
    private var cachedRawBounds: Rect? = null
    private var cachedBounds: Rect? = null

    /**
     * The ribbon while the pen is still down, grown a sample at a time rather than rebuilt (see
     * [WetRibbon]). Present only for a live stroke whose pen settles as it is drawn; the taper pen
     * and the straight-line tools have none, and neither does a finished stroke, which goes back
     * through [StrokeEngine.build] so the lift-time rules can fire.
     */
    @Volatile
    private var wet: WetRibbon? = null

    /** The live ribbon, for renderers that want to draw only the part of it still moving. */
    val wetRibbon: WetRibbon? get() = wet

    init {
        if (samples.isNotEmpty()) setSamples(samples)
    }

    /** Copy constructor: duplicates the sample arrays without materializing a single [Sample]. */
    constructor(src: Stroke) : this(src.tool, src.config, emptyList(), src.speedScale, src.straight, src.smoothScale) {
        pts = src.pts.detached()
    }

    // --- sample access ---

    /** Number of raw samples. */
    val sampleCount get() = pts.n

    fun xAt(i: Int): Double = pts.let { it.ox + it.xa[i] }
    fun yAt(i: Int): Double = pts.let { it.oy + it.ya[i] }
    fun pAt(i: Int): Double = pts.pa[i].toDouble()
    fun tAt(i: Int): Double = pts.ta?.get(i)?.toDouble() ?: 0.0

    fun sampleAt(i: Int): Sample = pts.sample(i)

    /** Read-only `List<Sample>` view for the call sites that still speak in [Sample]s. Each `get`
     *  builds a short-lived value; the hot loops use the primitive accessors instead. The view
     *  pins the [Samples] it was taken over, so iterating it off the main thread (the writer) sees
     *  one consistent stroke however the pen edits this one meanwhile. */
    val samples: List<Sample> get() = SampleView(pts)

    private class SampleView(private val s: Samples) : AbstractList<Sample>() {
        override val size get() = s.n
        override fun get(index: Int): Sample = s.sample(index)
    }

    /** Replace every sample (pen-up reduction, undo restore, legacy compaction). Allocates exactly
     *  [list].size: a finished stroke never grows again, and rounding up to the growth step would
     *  waste most of a byte budget where the median stroke is ~30 samples. */
    fun setSamples(list: List<Sample>) {
        val m = list.size
        if (m == 0) {
            pts = Samples.EMPTY
            invalidate()
            return
        }
        val first = list[0]
        val ox = first.x
        val oy = first.y
        var timed = false
        for (i in 0 until m) if (list[i].t != 0.0) { timed = true; break }
        val xa = FloatArray(m)
        val ya = FloatArray(m)
        val pa = FloatArray(m)
        val ta = if (timed) FloatArray(m) else null
        for (i in 0 until m) {
            val s = list[i]
            xa[i] = (s.x - ox).toFloat()
            ya[i] = (s.y - oy).toFloat()
            pa[i] = s.pressure.toFloat()
            ta?.set(i, s.t.toFloat())
        }
        pts = Samples(ox, oy, xa, ya, pa, ta, m)
        invalidate()
    }

    /**
     * Replace every sample from parallel raw arrays [count] long, which is how the codec hands them
     * over: a dense note otherwise materializes one boxed [Sample] per point on the way in and then
     * walks the whole list again to pack it. Same result as [setSamples], one copy earlier.
     */
    fun setSamples(xs: DoubleArray, ys: DoubleArray, ps: DoubleArray, ts: DoubleArray?, count: Int) {
        if (count == 0) {
            pts = Samples.EMPTY
            invalidate()
            return
        }
        val ox = xs[0]
        val oy = ys[0]
        var timed = false
        if (ts != null) for (i in 0 until count) if (ts[i] != 0.0) { timed = true; break }
        val xa = FloatArray(count)
        val ya = FloatArray(count)
        val pa = FloatArray(count)
        val ta = if (timed) FloatArray(count) else null
        for (i in 0 until count) {
            xa[i] = (xs[i] - ox).toFloat()
            ya[i] = (ys[i] - oy).toFloat()
            pa[i] = ps[i].toFloat()
            ta?.set(i, ts!![i].toFloat())
        }
        pts = Samples(ox, oy, xa, ya, pa, ta, count)
        invalidate()
    }

    /**
     * Hand back the slack [addSample]'s doubling left over. A live stroke grows by doubling, so at
     * pen-up it holds up to twice the arrays it needs, and it never grows again. Called once the
     * stroke is committed.
     */
    fun trimToSize() {
        val s = pts
        if (s.xa.size == s.n) return
        pts = s.detached()
    }

    /** False only while the pen is still down on this stroke: lift-time rules (the calligraphy
     *  dot swell, a stroke that never fills [StrokeEngine.HEAD_LEN]) are held off so the live
     *  preview can't open thick at every pen-down. Loaded, cloned and eraser-split strokes are
     *  complete. */
    var finished = true
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Ink colour with the tool's alpha scale applied (the highlighter uses its configurable
     *  [ToolConfig.highlighterAlpha]; every other tool is opaque, scale 1.0). */
    val renderColor get() = config.rgba.scaleAlpha(
        if (tool == Tool.HIGHLIGHTER) config.highlighterAlpha else tool.alphaScale,
    )

    /** How this stroke's translucent ink composites: the highlighter darkens with MULTIPLY, or
     *  lightens with SCREEN when it is the inverse variant; every other ink blends normally. */
    val blendMode: BlendMode get() = when {
        tool != Tool.HIGHLIGHTER -> BlendMode.SRC_OVER
        config.highlighterInverse -> BlendMode.SCREEN
        else -> BlendMode.MULTIPLY
    }

    val isEmpty get() = pts.n == 0

    /** Lazily-built ribbon geometry; rebuilt only when samples change. */
    fun geometry(): StrokeGeometry {
        wet?.let { return it.geometry() }
        cachedGeometry?.let { return it }
        // Unpack to the doubles the engine works in. It allocates these three arrays anyway, so
        // reading the float storage here costs nothing over passing a list of boxed samples.
        val s = pts
        val n = s.n
        val rx = DoubleArray(n)
        val ry = DoubleArray(n)
        val rp = DoubleArray(n)
        for (i in 0 until n) {
            rx[i] = s.ox + s.xa[i]
            ry[i] = s.oy + s.ya[i]
            rp[i] = s.pa[i].toDouble()
        }
        val rt = s.ta?.let { src -> DoubleArray(n) { src[it].toDouble() } }
        return StrokeEngine.build(
            rx, ry, rp, rt,
            config.baseWidth,
            config.pressureEnabled,
            config.pressureMinFactor,
            config.directionStrength,
            config.speedStrength,
            config.taperEnabled,
            config.taperMinFactor,
            speedScale,
            smooth = !straight,
            holdEnds = tool == Tool.PEN || tool == Tool.HIGHLIGHTER,
            finished = finished,
            smoothScale = smoothScale,
        ).also { cachedGeometry = it }
    }

    fun invalidate() {
        wet = null
        cachedGeometry = null
        cachedRawBounds = null
        cachedBounds = null
    }

    /**
     * Drop the ribbon but keep the bounds. Used by the canvas to reclaim geometry for pages that
     * scrolled out of the cached band. The two [Rect]s stay because [bounds] is what selection
     * reads, and rebuilding the whole ribbon to hand back a rectangle would cost more than the
     * ribbon saved. Not [invalidate]: nothing about the samples changed.
     */
    fun releaseGeometry() {
        // A live ribbon holds the bounds too, so bank them before letting it go.
        wet?.let {
            cachedBounds = it.bounds()
            wet = null
        }
        cachedGeometry = null
    }

    /** True once [geometry] has been built and not since released; lets the canvas skip pages
     *  that never rendered instead of walking their items. */
    val hasGeometry get() = wet != null || cachedGeometry != null

    fun addSample(s: Sample) {
        val cur = pts
        val n = cur.n
        val ox = if (n == 0) s.x else cur.ox
        val oy = if (n == 0) s.y else cur.oy
        var xa = cur.xa
        var ya = cur.ya
        var pa = cur.pa
        var ta = cur.ta
        if (xa.size < n + 1) { // grow by doubling, into arrays nothing else is holding
            var c = if (xa.isEmpty()) INITIAL_CAPACITY else xa.size
            while (c < n + 1) c *= 2
            xa = xa.copyOf(c)
            ya = ya.copyOf(c)
            pa = pa.copyOf(c)
            ta = ta?.copyOf(c)
        }
        if (s.t != 0.0 && ta == null) ta = FloatArray(xa.size)
        // Index n only, which is past the count every published [Samples] carries, so filling the
        // slack a doubling left cannot disturb a reader holding the previous one.
        xa[n] = (s.x - ox).toFloat()
        ya[n] = (s.y - oy).toFloat()
        pa[n] = s.pressure.toFloat()
        ta?.set(n, s.t.toFloat())
        pts = Samples(ox, oy, xa, ya, pa, ta, n + 1)
        val ribbon = wetOrStart()
        if (ribbon == null) {
            invalidate()
            return
        }
        // Fed from the packed arrays, not from [s]: the samples are stored as floats, and the
        // ribbon has to see the same rounded numbers a rebuild would, or the ink would shift a
        // hair as the stroke crosses from one to the other.
        ribbon.append(xAt(n), yAt(n), pAt(n), tAt(n))
        cachedGeometry = null
        cachedRawBounds = null
        cachedBounds = null
    }

    /**
     * The live ribbon, started on the first sample of a stroke whose pen settles as it is drawn.
     * Anything that edits the samples wholesale drops it; the next sample builds a fresh one over
     * what is already there and carries on from the end, so a stroke never falls back to rebuilding
     * per frame for good.
     */
    private fun wetOrStart(): WetRibbon? {
        wet?.let { return it }
        if (finished || !WetRibbon.supports(config.taperEnabled, straight)) return null
        val w = WetRibbon(
            baseWidth = config.baseWidth,
            pressureEnabled = config.pressureEnabled,
            m = config.pressureMinFactor,
            ds = config.directionStrength,
            speedStrength = config.speedStrength,
            speedScale = speedScale,
            smooth = !straight,
            holdEnds = tool == Tool.PEN || tool == Tool.HIGHLIGHTER,
            smoothScale = smoothScale,
        )
        // Everything before the sample being added; the caller appends that one itself.
        val s = pts
        for (i in 0 until s.n - 1) w.append(s.ox + s.xa[i], s.oy + s.ya[i], s.pa[i].toDouble(), s.t(i))
        return w.also { wet = it }
    }

    /**
     * Straight-line tools: collapse to a single segment from the pen-down sample to [end],
     * replacing any prior moving endpoint. The first sample's origin stays fixed while the far
     * end tracks the pointer, so the live preview and committed stroke are one straight ribbon.
     */
    fun setStraightEnd(end: Sample) {
        val cur = pts
        // Dropping back to one sample means the next [addSample] rewrites index 1, which a reader
        // holding the two-sample tuple would be reading; hand it its own arrays instead.
        if (cur.n > 1) pts = Samples(cur.ox, cur.oy, cur.xa.copyOf(2), cur.ya.copyOf(2), cur.pa.copyOf(2), cur.ta?.copyOf(2), 1)
        addSample(end)
    }

    override fun paint(r: Renderer) {
        val g = geometry()
        val color = renderColor
        when {
            // The dashed pen draws its (uniform-width) centreline as a dashed, round-capped
            // line rather than a solid ribbon; the full ribbon geometry is still used for
            // bounds/hit-testing/erasing, so the whole line stays selectable through the gaps.
            tool == Tool.DASHED -> paintDashed(r, g, color)
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
                r.saveLayerBlended(bounds().outset(2.0), color.a / 255.0, blendMode)
                paintFills(r, g, color.withAlpha(255))
                r.restore()
            }
        }
    }

    // The ribbon is a circular brush disc swept down the centreline: a disc at every sample plus
    // the body bridging them. Round caps and joins come for free on every pen, and the union of
    // convex pieces can't hole the way one self-overlapping outline does at a sharp turn.
    private fun paintFills(r: Renderer, g: StrokeGeometry, color: Rgba) {
        r.fillDiskRibbon(g.centerline, g.halfWidths, color)
    }

    /**
     * Paint just the opaque highlighter ribbon (full alpha, no blend) into [r]. The canvas
     * pre-renders this once into a bitmap and composites it live at [renderColor]'s alpha with
     * MULTIPLY, so a self-overlapping highlight isn't re-tessellated every frame.
     */
    fun paintHighlighterRibbon(r: Renderer) {
        paintFills(r, geometry(), renderColor.withAlpha(255))
    }

    /**
     * Paint [count] points of the live ribbon starting at [from]. The wet cache draws a stroke in
     * two runs — the settled one baked into a raster once, the moving one over it every frame — and
     * a growing ribbon's arrays are over-allocated, so neither run is describable by array length.
     *
     * Only for opaque, non-neon ink, which is the only kind the cache takes: overlapping runs of a
     * solid colour union to the same colour, while a translucent one would darken where they meet
     * and a bloom would compound. [dashPhase] is how far into the dash pattern this run starts, so
     * the dashed pen's rhythm carries across the seam instead of restarting at it.
     */
    fun paintRun(r: Renderer, ribbon: WetRibbon, from: Int, count: Int, dashPhase: Double) {
        if (count <= 0) return
        val color = renderColor
        val centers = ribbon.centerlineArray()
        if (tool == Tool.DASHED && ribbon.pointCount >= 2) {
            r.strokePolyline(
                centers, from, count,
                Pen(
                    color = color,
                    width = config.baseWidth,
                    cosmetic = false,
                    dashed = true,
                    dashOn = config.dashLength,
                    dashGap = config.dashGap,
                    dashPhase = dashPhase,
                ),
            )
        } else {
            r.fillDiskRibbon(centers, ribbon.halfWidthArray(), from, count, color)
        }
    }

    /** Whether the wet cache may bake this stroke's settled ink into a raster. Neon stacks blurred
     *  layers composited at an alpha, and translucent ink compounds where it overlaps itself, so
     *  neither survives being painted in two runs; both keep the plain every-frame redraw. */
    val wetCacheable: Boolean
        get() = !config.neon && renderColor.a >= 255

    /**
     * The dashed pen: a constant-width, round-capped dashed line traced down the smoothed
     * centreline (so its rounded dashes match the tool's icon). Dash/gap runs are in content
     * px so they scale with zoom like the ink. A single tap (no line) is drawn as a dot.
     */
    private fun paintDashed(r: Renderer, g: StrokeGeometry, color: Rgba) {
        if (g.pointCount >= 2) {
            r.strokePolyline(
                g.centerline,
                Pen(
                    color = color,
                    width = config.baseWidth,
                    cosmetic = false,
                    dashed = true,
                    dashOn = config.dashLength,
                    dashGap = config.dashGap,
                ),
            )
        } else {
            r.fillDiskRibbon(g.centerline, g.halfWidths, color)
        }
    }

    /**
     * Neon as a laser trail. Four layers, back to front:
     *   1. a wide, faint outer bloom (the blurred ribbon in the ink colour);
     *   2. a tighter, brighter bloom that keeps the colour saturated at the line;
     *   3. the tube body: the saturated colour, lifted slightly toward white so it
     *      reads as lit;
     *   4. the white-hot core: a thin *solid* white inner ribbon (no blur, so it
     *      reaches full white even on a 1px line instead of smudging out).
     * Each bloom composites once at a glow-intensity alpha so a self-overlapping
     * scribble can't compound into a darker blob. [config.neonStrength] scales the
     * bloom's size and brightness; it overrides the translucent path, so neon works
     * on any stroke tool.
     */
    internal fun neonGlowRadius(): Double {
        val s = config.neonStrength.coerceIn(0.0, 1.0)
        return (config.baseWidth * (NEON_BLOOM_WIDE_FACTOR_MIN + NEON_BLOOM_WIDE_FACTOR_SPAN * s))
            .coerceAtLeast(NEON_BLOOM_WIDE_MIN)
    }

    override fun paintBounds(): Rect =
        if (config.neon && tool != Tool.HIGHLIGHTER) bounds().outset(neonGlowRadius() * 2 + 4)
        else bounds()

    private fun paintNeon(r: Renderer, g: StrokeGeometry, color: Rgba) {
        val s = config.neonStrength.coerceIn(0.0, 1.0)
        val body = color.withAlpha(255)

        // 1) Wide, faint outer bloom.
        paintBloom(r, g, body, neonGlowRadius(), NEON_BLOOM_WIDE_ALPHA_MIN + NEON_BLOOM_WIDE_ALPHA_SPAN * s)

        // 2) Tight, brighter bloom: saturated colour hugging the line.
        val tightR = (config.baseWidth * (NEON_BLOOM_TIGHT_FACTOR_MIN + NEON_BLOOM_TIGHT_FACTOR_SPAN * s))
            .coerceAtLeast(NEON_BLOOM_TIGHT_MIN)
        paintBloom(r, g, body, tightR, NEON_BLOOM_TIGHT_ALPHA_MIN + NEON_BLOOM_TIGHT_ALPHA_SPAN * s)

        // 3) Tube body, lifted slightly toward white so it reads as lit.
        paintFills(r, g, lighten(body, NEON_BODY_LIGHTEN))

        // 4) Solid white-hot core (no blur, so thin lines still read pure white): the same swept
        //    disc at a fraction of the width, so it rounds with the body on every pen.
        val core = FloatArray(g.halfWidths.size) { (g.halfWidths[it] * NEON_CORE_FRAC).toFloat() }
        r.fillDiskRibbon(g.centerline, core, Rgba(255, 255, 255, 255))
    }

    /** One bloom pass: the blurred ribbon body plus its two rounded ends, composited once at [alpha]. */
    private fun paintBloom(r: Renderer, g: StrokeGeometry, color: Rgba, radius: Double, alpha: Double) {
        r.saveLayerAlpha(paintBounds(), alpha)
        if (g.outlineCount >= 3) r.fillPolygonGlow(g.outline, color, FillRule.NONZERO, radius)
        val n = g.pointCount
        if (n > 0) {
            if (g.hw(0) > 0.0) r.fillCircleGlow(Pt(g.cx(0), g.cy(0)), g.hw(0), color, radius)
            if (n > 1 && g.hw(n - 1) > 0.0) r.fillCircleGlow(Pt(g.cx(n - 1), g.cy(n - 1)), g.hw(n - 1), color, radius)
        }
        r.restore()
    }

    /** [c] lerped a fraction [t] toward white (alpha preserved). */


    override fun bounds(): Rect {
        // A live ribbon keeps a running box over its settled points, so a growing stroke's bounds
        // cost what its moving tail costs rather than a walk of every point it has laid down.
        wet?.let { return it.bounds() }
        cachedBounds?.let { return it }
        val g = geometry()
        if (g.pointCount == 0) {
            val b = if (pts.n == 0) Rect(0.0, 0.0, 0.0, 0.0) else rawBounds()
            return b.also { cachedBounds = it }
        }
        // Bound the swept-disc ribbon by the discs themselves: each centre +/- its half-width holds
        // that sample's disc, and the bridging body never reaches past the discs it joins, so this
        // covers the round caps every pen now grows (the old outline box clipped them flat).
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (i in 0 until g.pointCount) {
            val cx = g.cx(i)
            val cy = g.cy(i)
            val h = g.hw(i)
            if (cx - h < minX) minX = cx - h
            if (cx + h > maxX) maxX = cx + h
            if (cy - h < minY) minY = cy - h
            if (cy + h > maxY) maxY = cy + h
        }
        return Rect(minX, minY, maxX - minX, maxY - minY).also { cachedBounds = it }
    }

    /** O(1): the samples are offsets, so moving the stroke only moves the origin they hang off. */
    override fun translate(dx: Double, dy: Double) {
        val s = pts
        pts = Samples(s.ox + dx, s.oy + dy, s.xa, s.ya, s.pa, s.ta, s.n)
        wet = null
        cachedGeometry = null
        cachedRawBounds = cachedRawBounds?.translate(dx, dy)
        cachedBounds = cachedBounds?.translate(dx, dy)
    }

    override fun snapshotGeometry(): GeometrySnapshot = pts.let {
        StrokeSnapshot(it.ox, it.oy, it.xa.copyOf(it.n), it.ya.copyOf(it.n), it.pa.copyOf(it.n), it.ta?.copyOf(it.n), config)
    }

    override fun restoreGeometry(snap: GeometrySnapshot) {
        if (snap !is StrokeSnapshot) return
        pts = Samples(
            snap.ox, snap.oy,
            snap.xs.copyOf(), snap.ys.copyOf(), snap.ps.copyOf(), snap.ts?.copyOf(),
            snap.xs.size,
        )
        config = snap.config
        invalidate()
    }

    /** Transform every sample and scale the width-bearing style fields (base width, taper, dash
     *  runs) by the transform's linear factor, so a resized stroke looks zoomed; a pure rotation
     *  (factor 1) leaves the width untouched. */
    override fun applyTransform(t: Affine) {
        val s = pts
        if (s.n > 0) {
            // Re-origin on the transformed first sample so the offsets stay stroke-local; a rotation
            // about a far-away pivot would otherwise push them out to the pivot's magnitude.
            val first = t.apply(Pt(s.ox + s.xa[0], s.oy + s.ya[0]))
            // Into new arrays: rewriting the old ones in place would move under a reader holding them.
            val xa = FloatArray(s.n)
            val ya = FloatArray(s.n)
            for (i in 0 until s.n) {
                val p = t.apply(Pt(s.ox + s.xa[i], s.oy + s.ya[i]))
                xa[i] = (p.x - first.x).toFloat()
                ya[i] = (p.y - first.y).toFloat()
            }
            pts = Samples(first.x, first.y, xa, ya, s.pa, s.ta, s.n)
        }
        val k = t.linearScale
        if (k != 1.0) {
            config = config.copy(
                baseWidth = config.baseWidth * k,
                dashLength = config.dashLength * k,
                dashGap = config.dashGap * k,
            )
        }
        invalidate()
    }

    /** `p` within the swept disc: inside any sample's disc (catching the dot, ends and joins) or
     *  inside the ribbon body. */
    override fun contains(p: Pt): Boolean {
        val g = geometry()
        for (i in 0 until g.pointCount) {
            val h = g.hw(i)
            if (h <= 0.0) continue
            val dx = p.x - g.cx(i)
            val dy = p.y - g.cy(i)
            if (dx * dx + dy * dy <= h * h) return true
        }
        return g.bodyContains(p)
    }

    override fun outlinePoints(): List<Pt> {
        val s = pts
        return List(s.n) { Pt(s.ox + s.xa[it], s.oy + s.ya[it]) }
    }

    /** Mean of the sample positions. */
    override fun centroid(): Pt {
        val s = pts
        if (s.n == 0) return Pt.ZERO
        var sx = 0.0
        var sy = 0.0
        for (i in 0 until s.n) {
            sx += s.xa[i]
            sy += s.ya[i]
        }
        return Pt(s.ox + sx / s.n, s.oy + sy / s.n)
    }

    /**
     * AABB of the *raw* input samples, cached and invalidated with the geometry.
     * Built in one allocation-free pass (no `samples.map { it.pos }`), so an eraser
     * sweep that bbox-rejects thousands of strokes per move stays cheap.
     */
    private fun rawBounds(): Rect {
        cachedRawBounds?.let { return it }
        val s = pts
        var minX = s.xa[0]
        var minY = s.ya[0]
        var maxX = minX
        var maxY = minY
        for (i in 1 until s.n) {
            val x = s.xa[i]
            val y = s.ya[i]
            if (x < minX) minX = x else if (x > maxX) maxX = x
            if (y < minY) minY = y else if (y > maxY) maxY = y
        }
        return Rect(s.ox + minX, s.oy + minY, (maxX - minX).toDouble(), (maxY - minY).toDouble())
            .also { cachedRawBounds = it }
    }

    /** Cheap sample test after a bounding-box reject (spec 02 §5.1). */
    override fun intersectsCircle(cx: Double, cy: Double, radius: Double): Boolean {
        val s = pts
        if (s.n == 0) return false
        // Reject against the *raw* sample box (the smoothed geometry lags inward).
        if (rawBounds().distanceTo(Pt(cx, cy)) > radius) return false
        // A straight stroke carries only its two endpoints, so a mid-line tap falls between
        // samples; test the segments they span (point-to-line) instead of the sample points.
        if (straight) {
            val c = Pt(cx, cy)
            if (s.n == 1) return c.distanceTo(Pt(s.ox + s.xa[0], s.oy + s.ya[0])) <= radius
            for (i in 1 until s.n) {
                val a = Pt(s.ox + s.xa[i - 1], s.oy + s.ya[i - 1])
                val b = Pt(s.ox + s.xa[i], s.oy + s.ya[i])
                if (Geometry.distancePointToSegment(c, a, b) <= radius) return true
            }
            return false
        }
        // Compare in offset space so the eraser sweep does no per-sample origin arithmetic.
        val lx = cx - s.ox
        val ly = cy - s.oy
        val r2 = radius * radius
        for (i in 0 until s.n) {
            val dx = s.xa[i] - lx
            val dy = s.ya[i] - ly
            if (dx * dx + dy * dy <= r2) return true
        }
        return false
    }

    /**
     * AREA-erase: the surviving fragments after an eraser circle (page-local [cx], [cy], [radius])
     * passes over this stroke. A sample is erased when within [radius] of the centre — the same
     * point test as [intersectsCircle], so a stroke this splits is exactly one [intersectsCircle]
     * reports as hit. Surviving samples are partitioned into maximal contiguous runs; each run
     * becomes a new stroke sharing this stroke's tool/config/speedScale.
     *  - `null`      — no sample erased (keep the original untouched)
     *  - empty list  — every sample erased (remove the whole stroke)
     *  - one stroke  — an end was trimmed, or a hole left a single run
     *  - two or more — a mid-stroke hole split it
     */
    fun erasedBy(cx: Double, cy: Double, radius: Double): List<Stroke>? {
        val s = pts
        if (s.n == 0) return null
        if (rawBounds().distanceTo(Pt(cx, cy)) > radius) return null
        // A straight stroke is just two endpoints — it has no mid-line samples to split on, so any
        // contact erases the whole segment (consistent with how the eraser hit-tests it).
        if (straight) return if (intersectsCircle(cx, cy, radius)) emptyList() else null
        val lx = cx - s.ox
        val ly = cy - s.oy
        val r2 = radius * radius
        var anyErased = false
        var runStart = -1
        val fragments = mutableListOf<Stroke>()
        for (i in 0 until s.n) {
            val dx = s.xa[i] - lx
            val dy = s.ya[i] - ly
            if (dx * dx + dy * dy <= r2) {
                anyErased = true
                if (runStart >= 0) {
                    fragments.add(fragment(s, runStart, i))
                    runStart = -1
                }
            } else if (runStart < 0) {
                runStart = i
            }
        }
        if (runStart >= 0) fragments.add(fragment(s, runStart, s.n))
        return if (anyErased) fragments else null
    }

    /** A new stroke from [src]'s samples `[from, to)`, copied so it shares no backing storage. */
    private fun fragment(src: Samples, from: Int, to: Int): Stroke {
        val m = to - from
        val s = Stroke(tool, config, emptyList(), speedScale, straight, smoothScale)
        // Re-origin on the fragment's own first sample; the offsets stay small either way, but this
        // keeps a fragment indistinguishable from a stroke drawn where it sits.
        s.pts = Samples(
            src.ox + src.xa[from],
            src.oy + src.ya[from],
            FloatArray(m) { src.xa[from + it] - src.xa[from] },
            FloatArray(m) { src.ya[from + it] - src.ya[from] },
            src.pa.copyOfRange(from, to),
            src.ta?.copyOfRange(from, to),
            m,
        )
        return s
    }

    companion object {
        const val KIND = "stroke"

        /** First allocation for a live stroke; grows by doubling from here. */
        private const val INITIAL_CAPACITY = 32

        /** [c] lerped a fraction [t] toward white (alpha preserved). */
        internal fun lighten(c: Rgba, t: Double): Rgba {
            val f = t.coerceIn(0.0, 1.0)
            return Rgba(
                (c.r + (255 - c.r) * f).toInt(),
                (c.g + (255 - c.g) * f).toInt(),
                (c.b + (255 - c.b) * f).toInt(),
                c.a,
            )
        }

        /** Neon bloom: two stacked NORMAL-blur passes, a wide faint halo under a
         *  tighter brighter one. Radius = base_width * (MIN + SPAN * neonStrength),
         *  floored in page px; alpha = MIN + SPAN * neonStrength. */
        internal const val NEON_BLOOM_WIDE_FACTOR_MIN = 1.8
        internal const val NEON_BLOOM_WIDE_FACTOR_SPAN = 5.0
        internal const val NEON_BLOOM_WIDE_MIN = 6.0
        internal const val NEON_BLOOM_WIDE_ALPHA_MIN = 0.0
        internal const val NEON_BLOOM_WIDE_ALPHA_SPAN = 0.42

        internal const val NEON_BLOOM_TIGHT_FACTOR_MIN = 0.7
        internal const val NEON_BLOOM_TIGHT_FACTOR_SPAN = 1.8
        internal const val NEON_BLOOM_TIGHT_MIN = 2.5
        internal const val NEON_BLOOM_TIGHT_ALPHA_MIN = 0.0
        internal const val NEON_BLOOM_TIGHT_ALPHA_SPAN = 0.85

        /** Fraction of the tube width filled by the solid white-hot core. */
        internal const val NEON_CORE_FRAC = 0.3

        /** Body colour lifted this fraction toward white so the tube reads as lit. */
        internal const val NEON_BODY_LIGHTEN = 0.10
    }
}

/**
 * One stroke's samples, frozen: [n] entries of the parallel arrays, positions as offsets from
 * [ox]/[oy]. A [Stroke] never edits a published tuple; it builds a new one and publishes it in a
 * single volatile write, which is what makes a stroke safe to read from another thread while the
 * pen is drawing on it. The autosave writer relies on that: it serializes the live items rather
 * than a deep copy of them, so saving a note no longer costs a second copy of it on the heap.
 *
 * The arrays may be longer than [n] (a live stroke grows by doubling) and are append-only past it:
 * [Stroke.addSample] fills the slack, which no reader looks at, while anything that rewrites an
 * entry that has been published allocates new arrays instead.
 */
private class Samples(
    val ox: Double,
    val oy: Double,
    val xa: FloatArray,
    val ya: FloatArray,
    val pa: FloatArray,
    /** Milliseconds since the first sample, allocated only once a sample carries a non-zero time
     *  (only the speed pen records any), so ordinary ink pays nothing for the channel. */
    val ta: FloatArray?,
    val n: Int,
) {
    fun t(i: Int): Double = ta?.get(i)?.toDouble() ?: 0.0

    fun sample(i: Int): Sample = Sample(ox + xa[i], oy + ya[i], pa[i].toDouble(), t(i))

    /** The same samples over arrays of exactly [n], shared with nothing. */
    fun detached(): Samples = Samples(ox, oy, xa.copyOf(n), ya.copyOf(n), pa.copyOf(n), ta?.copyOf(n), n)

    companion object {
        val EMPTY = Samples(0.0, 0.0, FloatArray(0), FloatArray(0), FloatArray(0), null, 0)
    }
}

/** Snapshot of a stroke's transformable geometry: the sample path and the width-bearing style.
 *  Holds the packed arrays rather than a list of [Sample]s, so a resize drag snapshotting a big
 *  selection every frame does not allocate one object per sample. */
private class StrokeSnapshot(
    val ox: Double,
    val oy: Double,
    val xs: FloatArray,
    val ys: FloatArray,
    val ps: FloatArray,
    val ts: FloatArray?,
    val config: ToolConfig,
) : GeometrySnapshot
