package com.xnotes.core.tools

import com.xnotes.core.model.Rgba
import com.xnotes.core.model.TapeItem
import com.xnotes.core.model.TapeMode
import com.xnotes.core.model.TapePattern
import com.xnotes.core.stroke.Stabilizer
import com.xnotes.core.stroke.StrokeEngine
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Eraser behaviour (spec 04 §2): STROKE removes a whole stroke on contact; AREA removes only the
 * portion of a stroke the eraser passes over, splitting it into fragments. Persisted as [id].
 */
enum class EraseMode(val id: String) {
    STROKE("stroke"),
    AREA("area");

    companion object {
        fun fromId(id: String?): EraseMode = entries.firstOrNull { it.id == id } ?: STROKE
    }
}

/** What the text markup tool does with a drag over PDF text: select it, or mark it as [type]. */
enum class MarkupMode(val id: String, val type: com.xnotes.core.model.MarkupType?) {
    SELECT("select", null),
    HIGHLIGHT("highlight", com.xnotes.core.model.MarkupType.HIGHLIGHT),
    UNDERLINE("underline", com.xnotes.core.model.MarkupType.UNDERLINE),
    STRIKEOUT("strikeout", com.xnotes.core.model.MarkupType.STRIKEOUT),
    SQUIGGLY("squiggly", com.xnotes.core.model.MarkupType.SQUIGGLY);

    companion object {
        fun fromId(id: String?): MarkupMode = entries.firstOrNull { it.id == id } ?: HIGHLIGHT
    }
}

/**
 * The style record carried by stroke tools and used as the eraser/lasso size
 * carrier (spec 04 §2). A [com.xnotes.core.model.Stroke] stores a **copy** at
 * pen-down so re-tuning a tool never restyles existing strokes.
 *
 * The default `ToolConfig()` is `(3.0, on, 0.35, 0.0)` (spec 04 §3).
 */
data class ToolConfig(
    val baseWidth: Double = 3.0,
    val pressureEnabled: Boolean = true,
    /** `m` — width fraction at zero pressure. */
    val pressureMinFactor: Double = 0.35,
    /** `ds` — calligraphic direction effect (0 = none). */
    val directionStrength: Double = 0.0,
    val rgba: Rgba = InkPalette.DEFAULT,
    // New fields go *after* `rgba` so the positional constructor stays stable.
    /** Velocity thinning (the speed pen): 0 = none; up the line thins as it moves faster. */
    val speedStrength: Double = 0.0,
    /** Taper pen: when true the width eases across the whole stroke, full at the head down to
     *  [taperMinFactor] of full width at the tip. */
    val taperEnabled: Boolean = false,
    /** Neon glow: a soft luminous halo under a bright core. Composable onto any stroke tool. */
    val neon: Boolean = false,
    /** Glow intensity (the neon halo): 0 = faint & tight, 1 = bright & wide. Only used when [neon]. */
    val neonStrength: Double = 0.6,
    /** Dashed pen: length of each dash, in content px. Only used by [Tool.DASHED]. */
    val dashLength: Double = 10.0,
    /** Dashed pen: gap between dashes, in content px. Only used by [Tool.DASHED]. */
    val dashGap: Double = 8.0,
    /** Eraser behaviour: whole-stroke (STROKE) or partial (AREA). Only used by [Tool.ERASER]. */
    val eraseMode: EraseMode = EraseMode.STROKE,
    /** Eraser: re-arm the pen/highlighter used before the eraser once an erase lifts. Only [Tool.ERASER]. */
    val switchBackAfterErase: Boolean = false,
    /** Select: re-arm the pen/highlighter used before the select tool once a selection action completes. Only [Tool.SELECT]. */
    val switchBackAfterSelect: Boolean = false,
    /** Highlighter: commit each drag as a single straight segment (start → release). Only [Tool.HIGHLIGHTER]. */
    val straightLine: Boolean = false,
    /** When false, the pen draws at a constant on-screen size: at pen-down the width (and the
     *  taper/dash extents) are divided by the current zoom, so the stroke looks the same
     *  thickness whatever zoom you draw at. The eraser uses it to size its radius. */
    val scale: Boolean = true,
    /** Highlighter translucency: render-time alpha scale, default 0.35 (the historical value, so
     *  legacy strokes that lack the field reload unchanged). Kept to [0.10, 1.0] by the UI; it stays
     *  a MULTIPLY-blended marker at any alpha. Only used by [Tool.HIGHLIGHTER]. */
    val highlighterAlpha: Double = 0.35,
    /** Per-tool colour override: when set, new strokes from this tool use this colour instead of
     *  the toolbar's active ink colour. null = follow the toolbar (the default for every tool). */
    val colorOverride: Rgba? = null,
    /** Taper pen: width fraction the tapered end keeps, in `[0, 1]` (the floor of the tail ease).
     *  0 = the tail comes to a sharp point; 0.1 = it bottoms out at a tenth of full width. Only
     *  used when [taperEnabled]. */
    val taperMinFactor: Double = 0.0,
    /** Inverse highlighter: composite with SCREEN instead of MULTIPLY, so the stroke lightens what
     *  is under it. A multiply has nothing to darken on a dark page, where it ends up tinting the
     *  light ink instead of the paper. Only used by [Tool.HIGHLIGHTER]. */
    val highlighterInverse: Boolean = false,
    /** Text markup tool: what a drag over PDF text does. Only used by [Tool.MARKUP]. */
    val markupMode: MarkupMode = MarkupMode.HIGHLIGHT,
    /** Text markup tool: how deep a new highlight's colour goes, in [MARKUP_INTENSITY_MIN, 1]. Only [Tool.MARKUP]. */
    val markupIntensity: Double = DEFAULT_MARKUP_INTENSITY,
    /** Eraser: also take off the PDF text markups it touches. Only used by [Tool.ERASER]. */
    val eraseMarkups: Boolean = true,
    /** Eraser: a pen scribbled over ink erases what it touches. Only used by [Tool.ERASER]. */
    val scribbleErase: Boolean = false,
    /** Tape: what a drag lays, a straight strip, a rectangle or a freehand band. Only used by [Tool.TAPE]. */
    val tapeMode: TapeMode = TapeMode.STRAIGHT,
    /** Tape: what is printed on it. Only used by [Tool.TAPE]. */
    val tapePattern: TapePattern = TapePattern.SOLID,
    /** Speed pen: how far along the path its width changes are blurred, in pen widths. Only used by [Tool.SPEED]. */
    val speedSmoothing: Double = StrokeEngine.DEFAULT_SPEED_SMOOTHING,
    /** Speed pen: the speed in dp/ms at which the line reaches its thinnest. Only used by [Tool.SPEED]. */
    val speedTop: Double = StrokeEngine.SPEED_HI,
    /** Stabilization: steady the nib's path while drawing (see [Stabilizer]). Any ink pen; never written to a file. */
    val stabilize: Boolean = false,
    /** Stabilization: the most the steadied line trails a fast nib, in dp. */
    val stabilizeTrail: Double = Stabilizer.DEFAULT_TRAIL,
    /** Stabilization: the speed in dp/s below which movement counts as slow and is steadied hardest. */
    val stabilizeCrossover: Double = Stabilizer.DEFAULT_CROSSOVER,
) {
    companion object {
        const val DEFAULT_MARKUP_INTENSITY = 0.9
        const val MARKUP_INTENSITY_MIN = 0.1
    }
}

/** Factory defaults per tool (spec 04 §3). */
object ToolDefaults {
    /** Tip width the taper pen eases down to. */
    const val DEFAULT_TAPER_TIP = 0.50

    /** Tip assumed for legacy taper strokes that predate the setting, so they reload tapered
     *  rather than as a sharp point. Pinned: it must not follow [DEFAULT_TAPER_TIP]. */
    const val LEGACY_TAPER_TIP = 0.30

    fun configFor(tool: Tool): ToolConfig = when (tool) {
        Tool.PEN -> ToolConfig(baseWidth = 3.0, pressureEnabled = true, pressureMinFactor = 0.35, directionStrength = 0.0)
        // Dashed pen: uniform-width (pressure off), so its dashes stay even; dash/gap set the rhythm.
        Tool.DASHED -> ToolConfig(baseWidth = 3.0, pressureEnabled = false, pressureMinFactor = 1.0, directionStrength = 0.0, dashLength = 10.0, dashGap = 8.0)
        Tool.CALLIGRAPHY -> ToolConfig(baseWidth = 6.0, pressureEnabled = true, pressureMinFactor = 0.40, directionStrength = 0.60)
        Tool.SPEED -> ToolConfig(baseWidth = 4.0, pressureEnabled = true, pressureMinFactor = 0.35, directionStrength = 0.0, speedStrength = 0.8)
        Tool.TAPER -> ToolConfig(baseWidth = 4.0, pressureEnabled = true, pressureMinFactor = 0.45, directionStrength = 0.0, taperEnabled = true, taperMinFactor = DEFAULT_TAPER_TIP)
        Tool.HIGHLIGHTER -> ToolConfig(baseWidth = 16.0, pressureEnabled = false, pressureMinFactor = 1.0, directionStrength = 0.0, highlighterAlpha = 0.90)
        Tool.ERASER -> ToolConfig(baseWidth = 24.0, pressureEnabled = false, pressureMinFactor = 1.0, directionStrength = 0.0)
        Tool.LASSO -> ToolConfig(baseWidth = 2.0, pressureEnabled = false, pressureMinFactor = 1.0, directionStrength = 0.0)
        Tool.TAPE -> ToolConfig(
            baseWidth = TapeItem.DEFAULT_WIDTH, pressureEnabled = false, pressureMinFactor = 1.0, directionStrength = 0.0,
            tapePattern = TapePattern.STRIPES,
        )
        else -> ToolConfig()
    }

    /** Tools whose config is persisted in settings (spec 09 §2). */
    val persistedTools = listOf(Tool.PEN, Tool.DASHED, Tool.CALLIGRAPHY, Tool.SPEED, Tool.TAPER, Tool.HIGHLIGHTER, Tool.ERASER, Tool.SELECT, Tool.LASSO, Tool.MARKUP, Tool.TAPE)
}

/**
 * Conversions between the friendly popup controls and the internal style fields
 * (spec 04 §5). All are exact inverses on their valid ranges.
 */
object ToolConversions {
    /** SENSITIVITY (0..100, higher = thinner light strokes) -> `m` in [0.1, 1.0]. */
    fun sensitivityToMinFactor(sensitivity: Double): Double =
        1.0 - (sensitivity.coerceIn(0.0, 100.0) / 100.0) * 0.9

    fun minFactorToSensitivity(m: Double): Double = (1.0 - m) / 0.9 * 100.0

    /** MULTIPLIER (thick:thin ratio) -> `ds`, clamped to [0, 0.95]. */
    fun multiplierToDirectionStrength(multiplier: Double): Double =
        ((multiplier - 1.0) / (multiplier + 1.0)).coerceIn(0.0, 0.95)

    fun directionStrengthToMultiplier(ds: Double): Double = (1.0 + ds) / (1.0 - ds)

    /** SPEED (0..100, higher = stronger thinning at speed) -> `speedStrength` in [0, 0.92]. */
    fun speedToStrength(speed: Double): Double = speed.coerceIn(0.0, 100.0) / 100.0 * 0.92

    fun strengthToSpeed(s: Double): Double = (s / 0.92) * 100.0

    /** SMOOTHING (0..100) -> `speedSmoothing`, the blur along the path in pen widths, in [0, 1]. */
    fun smoothingToBlur(smoothing: Double): Double = smoothing.coerceIn(0.0, 100.0) / 100.0

    fun blurToSmoothing(b: Double): Double = (b * 100.0).coerceIn(0.0, 100.0)

    /** TOP SPEED (0..100) -> `speedTop` in dp/ms, log-scaled from a third of the default to three times it. */
    fun topSpeedToDpPerMs(top: Double): Double = StrokeEngine.SPEED_HI * 3.0.pow((top.coerceIn(0.0, 100.0) - 50.0) / 50.0)

    fun dpPerMsToTopSpeed(v: Double): Double =
        if (v > 0.0) (50.0 + 50.0 * ln(v / StrokeEngine.SPEED_HI) / ln(3.0)).coerceIn(0.0, 100.0) else 0.0

    /** STRENGTH (0..100) -> `stabilizeTrail` in dp, linear: 100 trails a fast nib by at most 24 dp, about 4 mm. */
    fun strengthToTrail(strength: Double): Double = strength.coerceIn(0.0, 100.0) * TRAIL_PER_STRENGTH

    fun trailToStrength(trail: Double): Double = (trail / TRAIL_PER_STRENGTH).coerceIn(0.0, 100.0)

    /** CROSSOVER (0..100) -> `stabilizeCrossover` in dp/s, log-scaled from a third of the default to three times it. */
    fun crossoverToDpPerS(crossover: Double): Double =
        Stabilizer.DEFAULT_CROSSOVER * 3.0.pow((crossover.coerceIn(0.0, 100.0) - 50.0) / 50.0)

    fun dpPerSToCrossover(v: Double): Double =
        if (v > 0.0) (50.0 + 50.0 * ln(v / Stabilizer.DEFAULT_CROSSOVER) / ln(3.0)).coerceIn(0.0, 100.0) else 0.0

    private const val TRAIL_PER_STRENGTH = 0.24

    /** INTENSITY (0..100, higher = brighter/wider halo) -> `neonStrength` in [0, 1]. */
    fun intensityToNeonStrength(intensity: Double): Double = intensity.coerceIn(0.0, 100.0) / 100.0

    fun neonStrengthToIntensity(s: Double): Double = s * 100.0

    /** Highlighter INTENSITY (10..100 percent opacity) -> alpha in [0.10, 1.0]. */
    fun intensityToHighlighterAlpha(intensity: Double): Double = intensity.coerceIn(10.0, 100.0) / 100.0

    fun highlighterAlphaToIntensity(a: Double): Double = a * 100.0

    /** WIDTH slider range per tool (spec 04 §5): 4..120 for the highlighter, 1..80 for the eraser
     *  (its radius, default 24), tape's own range, else 1..20. */
    fun widthRange(tool: Tool): ClosedFloatingPointRange<Double> = when (tool) {
        Tool.HIGHLIGHTER -> 4.0..120.0
        Tool.ERASER -> 1.0..80.0
        Tool.TAPE -> TapeItem.MIN_WIDTH..TapeItem.MAX_WIDTH
        else -> 1.0..20.0
    }

    /** Every ink width slider moves in these steps, so the width its label shows is the width drawn. */
    const val WIDTH_STEP = 0.5

    /** [value] on the [WIDTH_STEP] grid, held inside the tool's own range. */
    fun snapWidth(tool: Tool, value: Double): Double {
        val range = widthRange(tool)
        return ((value / WIDTH_STEP).roundToInt() * WIDTH_STEP).coerceIn(range.start, range.endInclusive)
    }
}
