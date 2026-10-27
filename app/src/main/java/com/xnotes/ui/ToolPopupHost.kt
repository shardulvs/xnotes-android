package com.xnotes.ui

import com.xnotes.core.model.Rgba
import com.xnotes.core.tools.ShapeConfig
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig

/**
 * The toolbar's ink swatches, which every colour row that offers them reads through here, so a row
 * in a popup is always the toolbar's own set: the same colours, in the same order, as many as shown.
 */
interface SwatchHost {
    /** Every swatch; the toolbar shows the first [hostToolbarColorCount]. */
    val hostToolbarColors: List<Rgba>
    val hostToolbarColorCount: Int
    val hostRecentColors: List<Rgba>

    /** Recolour swatch [index] everywhere without arming it, live while a picker is open; the ink follows if it is the active one. */
    fun recolorSwatch(index: Int, color: Rgba)

    /** The picker closed: keep the swatch's colour among the recents. */
    fun rememberSwatchColor(index: Int)
}

/** The swatches a colour row shows: the toolbar's, as many as it shows. */
val SwatchHost.shownSwatches: List<Rgba> get() = hostToolbarColors.take(hostToolbarColorCount)

/**
 * What a tool popup needs from whichever editor is open.
 *
 * The popups are the same controls on either surface, so they are the same composables rather than
 * a second set that resembles them. A lookalike drifts: a control added to one, a label reworded, a
 * range widened, and the two stop matching in ways nobody notices until a user does. Taking the few
 * members the popups actually read through an interface is what makes "identical" a property of the
 * code rather than a thing to keep checking.
 */
interface ToolPopupHost : SwatchHost {
    /** The stored style for [tool], without any live ink colour folded into it. */
    fun toolConfig(tool: Tool): ToolConfig

    /** Apply and persist a tool's style. */
    fun updateToolConfig(tool: Tool, config: ToolConfig)

    /** The shape tool's style. */
    val hostShapeConfig: ShapeConfig

    fun updateShapeConfig(config: ShapeConfig)

    /** The active swatch. */
    val hostActiveColorIndex: Int

    /** Recolour swatch [index] and arm it, live while the picker is open. */
    fun setSwatchColor(index: Int, color: Rgba)

    /** Whether the open note has a PDF, whose text markups the eraser can take. */
    val hostHasPdf: Boolean get() = false

    /** Peel back every tape in the open note or canvas, or cover them all again. */
    fun setAllTapePeeled(peeled: Boolean)
}
