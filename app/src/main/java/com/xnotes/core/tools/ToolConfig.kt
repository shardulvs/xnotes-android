package com.xnotes.core.tools

import com.xnotes.core.model.Rgba

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
    /** Entrance/exit taper (the taper pen): 0 = none; the fixed arc length (content px)
     *  over which each end eases to a point, independent of the stroke's total length. */
    val taperLength: Double = 0.0,
    /** Neon glow: a soft luminous halo under a bright core. Composable onto any stroke tool. */
    val neon: Boolean = false,
    /** Glow intensity (the neon halo): 0 = faint & tight, 1 = bright & wide. Only used when [neon]. */
    val neonStrength: Double = 0.6,
    /** Dashed pen: length of each dash, in content px. Only used by [Tool.DASHED]. */
    val dashLength: Double = 10.0,
    /** Dashed pen: gap between dashes, in content px. Only used by [Tool.DASHED]. */
    val dashGap: Double = 8.0,
)

/** Factory defaults per tool (spec 04 §3). */
object ToolDefaults {
    fun configFor(tool: Tool): ToolConfig = when (tool) {
        Tool.PEN -> ToolConfig(baseWidth = 3.0, pressureEnabled = true, pressureMinFactor = 0.35, directionStrength = 0.0)
        // Dashed pen: uniform-width (pressure off), so its dashes stay even; dash/gap set the rhythm.
        Tool.DASHED -> ToolConfig(baseWidth = 3.0, pressureEnabled = false, pressureMinFactor = 1.0, directionStrength = 0.0, dashLength = 10.0, dashGap = 8.0)
        Tool.CALLIGRAPHY -> ToolConfig(baseWidth = 6.0, pressureEnabled = true, pressureMinFactor = 0.40, directionStrength = 0.60)
        Tool.SPEED -> ToolConfig(baseWidth = 4.0, pressureEnabled = true, pressureMinFactor = 0.35, directionStrength = 0.0, speedStrength = 0.8)
        Tool.TAPER -> ToolConfig(baseWidth = 4.0, pressureEnabled = true, pressureMinFactor = 0.45, directionStrength = 0.0, taperLength = 40.0)
        Tool.HIGHLIGHTER -> ToolConfig(baseWidth = 16.0, pressureEnabled = false, pressureMinFactor = 1.0, directionStrength = 0.0)
        Tool.ERASER -> ToolConfig(baseWidth = 24.0, pressureEnabled = false, pressureMinFactor = 1.0, directionStrength = 0.0)
        Tool.LASSO -> ToolConfig(baseWidth = 2.0, pressureEnabled = false, pressureMinFactor = 1.0, directionStrength = 0.0)
        else -> ToolConfig()
    }

    /** Tools whose config is persisted in settings (spec 09 §2). */
    val persistedTools = listOf(Tool.PEN, Tool.DASHED, Tool.CALLIGRAPHY, Tool.SPEED, Tool.TAPER, Tool.HIGHLIGHTER, Tool.ERASER, Tool.LASSO)
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

    /** INTENSITY (0..100, higher = brighter/wider halo) -> `neonStrength` in [0, 1]. */
    fun intensityToNeonStrength(intensity: Double): Double = intensity.coerceIn(0.0, 100.0) / 100.0

    fun neonStrengthToIntensity(s: Double): Double = s * 100.0

    /** WIDTH slider range per tool (spec 04 §5): 4..40 for the highlighter, else 1..20. */
    fun widthRange(tool: Tool): ClosedFloatingPointRange<Double> =
        if (tool == Tool.HIGHLIGHTER) 4.0..40.0 else 1.0..20.0
}
