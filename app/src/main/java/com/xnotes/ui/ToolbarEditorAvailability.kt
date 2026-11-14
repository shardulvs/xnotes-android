package com.xnotes.ui

import com.xnotes.core.tools.ToolbarItem
import com.xnotes.core.tools.ToolbarLayout

/**
 * Items the section picker may offer. A visible, non-repeatable item belongs to
 * just one section across the entire workspace; hidden entries can be re-added.
 * Colours and spacers are deliberately repeatable.
 *
 * Keeping this scan outside each section/lane's composition avoids walking all
 * other sections once per picker and once per pointer-driven recomposition.
 */
internal fun toolbarAddableItems(layout: ToolbarLayout, availableItems: Set<ToolbarItem>): List<ToolbarItem> {
    val visible = layout.sections.asSequence()
        .flatMap { it.entries.asSequence() }
        .filter { it.visible }
        .mapTo(HashSet()) { it.item }
    return availableItems.filter { item ->
        item == ToolbarItem.COLORS || item == ToolbarItem.SPACER || item !in visible
    }
}
