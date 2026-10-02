package com.xnotes.canvas

import com.xnotes.core.tools.Tool

/**
 * Whether a pointer is free: not about to draw, erase, select or type with the armed tool, so a
 * long press may select the PDF text under it. A finger is free when it pans, under the PAN tool or
 * a tool that leaves the finger to pan while "Draw with finger" is off. A stylus or mouse is free
 * only when the PAN tool itself is armed; a pan from its side button is a pen moving the page.
 */
object FreePointer {
    fun isFree(armed: Tool, effective: Tool, isFinger: Boolean): Boolean =
        effective == Tool.PAN && (isFinger || armed == Tool.PAN)
}
