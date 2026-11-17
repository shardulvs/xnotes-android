package com.xnotes.core.tools

/** A copy operation keeps its source unchanged and filters unsupported tools explicitly. */
data class ToolbarTransferDraft(
    val bar: ToolbarBar,
    val omitted: List<ToolbarItem>,
    val retainedCount: Int,
)

/**
 * Prepare a new bar in the destination, preserving sections, positions, sizes, order,
 * visibility and lane settings. Unsupported tools are omitted, never silently remapped.
 */
fun prepareToolbarTransfer(
    source: ToolbarBar,
    destination: ToolbarWorkspace,
    target: ToolbarContext,
): ToolbarTransferDraft {
    val allowed = if (target == ToolbarContext.NOTE) ToolbarLayout.NOTE_ITEMS else ToolbarLayout.CANVAS_ITEMS
    val omitted = source.layout.sections.flatMap { section ->
        section.entries.filterNot { it.item in allowed }.map { it.item }
    }
    val sections = source.layout.sections.map { section ->
        section.copy(entries = section.entries.filter { it.item in allowed })
    }
    val uniqueId = generateSequence(1) { it + 1 }.map { "copied-bar-$it" }
        .first { candidate -> destination.bars.none { it.id == candidate } }
    return ToolbarTransferDraft(
        bar = source.copy(
            id = uniqueId,
            layout = source.layout.copy(sections = sections),
        ),
        omitted = omitted,
        retainedCount = sections.sumOf { it.entries.size },
    )
}
