package com.xnotes.ui

import com.xnotes.settings.ToolbarLook
import com.xnotes.settings.ToolbarPosition

/** Presentation only: document session and pane state remain independent of the toolbar. */
enum class TabBarPlacement { DOCKED_TOP, FLOATING_SELECTOR, ATTACHED_TOP }

fun tabBarPlacement(look: ToolbarLook): TabBarPlacement = when {
    look.position == ToolbarPosition.TOP && !look.floating -> TabBarPlacement.DOCKED_TOP
    look.position == ToolbarPosition.TOP && look.floating -> TabBarPlacement.ATTACHED_TOP
    else -> TabBarPlacement.FLOATING_SELECTOR
}
