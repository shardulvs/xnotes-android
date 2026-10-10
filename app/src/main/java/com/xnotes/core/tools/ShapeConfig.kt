package com.xnotes.core.tools

import com.xnotes.core.geometry.Pt
import com.xnotes.core.model.ConnectorEnd
import com.xnotes.core.model.ConnectorItem
import com.xnotes.core.model.FunctionSpec
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.shapes.ConnectorPreset
import com.xnotes.core.shapes.ConnectorPresets
import com.xnotes.core.shapes.Head
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.shapes.ShapePreset
import com.xnotes.core.shapes.ShapePresets

/** The style carried by the shape tool (spec 04 §6); persisted between sessions. */
data class ShapeConfig(
    /** The picker's chosen chip, by its preset key. */
    val preset: String = "rectangle",
    val strokeWidth: Double = 3.0,
    val fill: Boolean = false,
    /** Opacity of the fill in [0, 1], applied to its colour; used only when [fill]. */
    val fillAlpha: Double = FILL_ALPHA,
    /** The fill's own colour, or null to fill in the outline's. */
    val fillColor: Rgba? = null,
    /** Neon glow: a luminous halo + white-hot core on the shape's outline. */
    val neon: Boolean = false,
    /** Glow intensity in [0, 1] (halo size + brightness); used only when [neon]. */
    val neonStrength: Double = 0.6,
    /** Dashed outline: dash/gap run lengths in content px; used only when [dashed]. */
    val dashed: Boolean = false,
    val dashLength: Double = 10.0,
    val dashGap: Double = 8.0,
    /** Every shape dragged out takes its kind's perfect proportions; lines and arrows keep to 15 degree steps. */
    val perfect: Boolean = false,
    /** A counted chip's point or side count; 0 takes the chip's own. */
    val n: Int = 0,
    /** The heads an open chip draws with. */
    val headStart: Head = Head.NONE,
    val headEnd: Head = Head.NONE,
    /** The picker page last shown. */
    val shapePage: Int = 0,
    /** The connector picker page last shown. */
    val connectorPage: Int = 0,
) {
    /** The chosen connector chip, when the choice is a connector rather than a shape. */
    val connector: ConnectorPreset? get() = ConnectorPresets.byKey(preset)

    /** The chosen chip, or the rectangle for a key this version does not know (or a connector's). */
    val chip: ShapePreset get() = ShapePresets.byKey(preset) ?: ShapePresets.forKind(ShapeKind.RECTANGLE)

    val shape: ShapeKind get() = chip.kind

    /** What the chosen function chip plots, or null when the chip is not a function. */
    val function: FunctionSpec? get() =
        preset.takeIf { it.startsWith(ShapePresets.FUNCTION_PREFIX) }?.let { FunctionSpec.preset(it.removePrefix(ShapePresets.FUNCTION_PREFIX)) }

    /** The point or side count a new shape gets. */
    val count: Int get() = if (n > 0 && chip.counted) n else chip.n

    /** This config with [chip] chosen: its own heads, and its own count until the minus/plus moves it. */
    fun choose(chip: ShapePreset): ShapeConfig =
        copy(preset = chip.key, n = 0, headStart = chip.headStart, headEnd = chip.headEnd)

    /** This config with connector [chip] chosen: its heads, and its dash when it has a say. */
    fun choose(chip: ConnectorPreset): ShapeConfig =
        copy(preset = chip.key, n = 0, headStart = chip.headStart, headEnd = chip.headEnd, dashed = chip.dashed ?: dashed)

    /** A connector of the chosen chip setting off from [from], in [outline] colour and [width] wide; null for a shape. */
    fun startConnector(from: ConnectorEnd, outline: Rgba, width: Double): ConnectorItem? {
        val chip = connector ?: return null
        return ConnectorItem(chip.routing, from, ConnectorEnd(from.at), outline, width, headStart, headEnd, dashed, dashLength, dashGap, neon, neonStrength)
    }

    /** The fill a [kind] outlined in [outline] gets, drawn or snapped; null when unfilled or open. */
    fun fillFor(kind: ShapeKind, outline: Rgba): Rgba? =
        if (fill && kind.isClosed) (fillColor ?: outline).scaleAlpha(fillAlpha) else null

    /** A shape of the chosen chip, started at [at] in [outline] colour and [width] wide. */
    fun startShape(at: Pt, outline: Rgba, width: Double): ShapeItem {
        val kind = chip.kind
        return ShapeItem(
            kind, at, at, outline, width, fillFor(kind, outline), neon, neonStrength,
            dashed = dashed, dashLength = dashLength, dashGap = dashGap,
            adj = chip.adj.copyOf(), n = count,
            headStart = if (kind.isOpen) headStart else Head.NONE,
            headEnd = if (kind.isOpen) headEnd else Head.NONE,
        ).also { shape -> function?.let { shape.setFunction(it) } }
    }

    /** True when the chosen chip places a polygon corner by corner rather than dragging a box. */
    val placesCorners: Boolean get() = preset == ShapePresets.FREE_POLYGON

    /** A polygon (closed) or polyline (open) through page-space [points], in this style. */
    fun placedShape(points: List<Pt>, closed: Boolean, outline: Rgba, width: Double): ShapeItem {
        val kind = if (closed) ShapeKind.POLYGON else ShapeKind.POLYLINE
        return ShapeItem.poly(kind, points, outline, width, fillFor(kind, outline), neon, neonStrength, dashed, dashLength, dashGap)
            .also {
                if (!closed) {
                    it.headStart = headStart
                    it.headEnd = headEnd
                }
            }
    }

    companion object {
        /** Default reduced opacity applied to the ink colour when a closed shape is filled. */
        const val FILL_ALPHA = 0.25

        /** Settable fill-opacity range; never fully transparent, never fully opaque. */
        const val FILL_ALPHA_MIN = 0.05
        const val FILL_ALPHA_MAX = 1.0
    }
}
