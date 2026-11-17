package com.xnotes.core.tools

/**
 * A compact launcher for existing toolbar tools. The group owns no drawing state:
 * its last selected item is only a shortcut target and never overrides the editor's tool.
 *
 * This pure model describes shortcuts to existing tools, independent of workspace geometry.
 */
@ConsistentCopyVisibility
data class ToolbarToolGroup private constructor(
    val id: String,
    val items: List<ToolbarItem>,
    val lastUsed: ToolbarItem,
) {
    fun select(item: ToolbarItem): ToolbarToolGroup =
        if (item in items) copy(lastUsed = item) else this

    /**
     * Project onto one editor context without altering the saved source profile.
     * A missing last-used tool falls back to the first compatible item.
     */
    fun forContext(allowed: Set<ToolbarItem>): ToolbarToolGroup? {
        val compatible = items.filter { it in allowed }
        if (compatible.isEmpty()) return null
        return copy(
            items = compatible,
            lastUsed = lastUsed.takeIf { it in compatible } ?: compatible.first(),
        )
    }

    companion object {
        /** Context-independent choices; all must arm a real drawing tool. */
        val WRITING_TOOLS: Set<ToolbarItem> = setOf(
            ToolbarItem.PEN, ToolbarItem.DASHED, ToolbarItem.CALLIGRAPHY,
            ToolbarItem.SPEED, ToolbarItem.TAPER, ToolbarItem.HIGHLIGHTER,
        )

        fun create(
            id: String,
            entries: List<ToolbarItem>,
            lastUsed: ToolbarItem? = null,
        ): ToolbarToolGroup? {
            if (id.isBlank() || id.length > 64) return null
            val unique = entries.distinct()
            if (unique.isEmpty() || unique.size > 32 || unique.any { it !in WRITING_TOOLS }) return null
            return ToolbarToolGroup(id, unique, lastUsed?.takeIf { it in unique } ?: unique.first())
        }

        fun defaultWritingGroup(id: String = "writing-tools"): ToolbarToolGroup =
            requireNotNull(create(id, WRITING_TOOLS.toList()))
    }
}
