package com.xnotes.ui

import com.xnotes.core.model.Rgba
import com.xnotes.core.tools.ShapeConfig
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig

/**
 * What a tool popup needs from whichever editor is open.
 *
 * The popups are the same controls on either surface, so they are the same composables rather than
 * a second set that resembles them. A lookalike drifts: a control added to one, a label reworded, a
 * range widened, and the two stop matching in ways nobody notices until a user does. Taking the few
 * members the popups actually read through an interface is what makes "identical" a property of the
 * code rather than a thing to keep checking.
 */
interface ToolPopupHost {
    /** The stored style for [tool], without any live ink colour folded into it. */
    fun toolConfig(tool: Tool): ToolConfig

    /** Apply and persist a tool's style. */
    fun updateToolConfig(tool: Tool, config: ToolConfig)

    /** Global "snap held strokes to shapes" (dwell shape detection), shared across every stroke tool. */
    var snapHeldToShapes: Boolean

    /** The shape tool's style. */
    val hostShapeConfig: ShapeConfig

    fun updateShapeConfig(config: ShapeConfig)

    /** The toolbar's ink swatches, the active one, and the recently picked colours. */
    val hostToolbarColors: List<Rgba>
    val hostActiveColorIndex: Int
    val hostRecentColors: List<Rgba>

    /** Recolour swatch [index] and arm it, live while the picker is open. */
    fun setSwatchColor(index: Int, color: Rgba)

    /** The picker closed: keep the swatch's colour among the recents. */
    fun rememberSwatchColor(index: Int)

    /** Whether the open note has a PDF, whose text markups the eraser can take. */
    val hostHasPdf: Boolean get() = false
}
