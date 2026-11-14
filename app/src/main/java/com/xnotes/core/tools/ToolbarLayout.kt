package com.xnotes.core.tools

/**
 * User-customisable toolbar layout: ordered [sections], each an ordered list of [ToolbarEntry].
 * Pure model (no Android, no JSON) so the add/remove/move/migration logic is unit-testable on
 * the JVM; the settings layer persists it and the UI renders from it.
 */

/** Every atomic, movable toolbar element. The three block ids each render a fixed multi-control
 *  cluster but move and hide as one unit. [id] is the persistence key (never rename without a
 *  migration); the tool ids match the matching [Tool.id]. */
enum class ToolbarItem(val id: String) {
    HOME("home"),
    TITLE("title"),
    SIDEBAR("sidebar"),
    PEN("pen"),
    DASHED("dashed"),
    CALLIGRAPHY("calligraphy"),
    SPEED("speed"),
    TAPER("taper"),
    HIGHLIGHTER("highlighter"),
    ERASER("eraser"),
    PAN("pan"),
    SELECT("select"),
    LASSO("lasso"),
    SCREENSHOT("screenshot"),
    WAND("wand"),
    TAPE("tape"),
    SHAPE("shape"),
    RULER("ruler"),
    TEXT("text"),
    TEXT_BOX("text_box"),

    /** The text markup tool; only notes with a PDF show it. */
    MARKUP("markup"),
    IMAGE("image"),
    UNDO("undo"),
    REDO("redo"),
    PAGE_NAV("page_nav"),
    STYLES("styles"),
    MARGINS("margins"),
    VIEW("view"),
    ZOOM("zoom"),
    FIT("fit"),
    ZOOM_LOCK("zoom_lock"),
    FULLSCREEN("fullscreen"),
    COLORS("colors"),
    /** An invisible, independently sized gap along the toolbar's main axis. */
    SPACER("spacer"),

    /** Canvas only: saved views, which are what page numbers are on an unbounded surface. */
    WAYPOINTS("waypoints"),

    /** Canvas only: the overview map in the corner. */
    MINIMAP("minimap"),

    /** Notes only: opens and closes room on a page. Last, so a stored bar missing older items too still gets it right after the lasso. */
    INSERT_SPACE("insert_space");

    companion object {
        fun fromId(id: String?): ToolbarItem? =
            entries.firstOrNull { it.id == id } ?: if (id == "unified_pen") PEN else null
    }
}

/** A movable occurrence. Each Colors block owns a fraction of the CURRENT enabled palette.
 * Fractions survive changes to the number of swatches without leaving gaps or stale indices. */
data class ToolbarEntry(
    val item: ToolbarItem,
    val visible: Boolean = true,
    val lane: Int = 0,
    val colorPart: Int = 0,
    val colorParts: Int = 1,
    /** Optional visual size for this occurrence; null inherits the toolbar's preset. */
    val sizeDp: Int? = null,
) {
    fun colorIndices(total: Int): IntRange {
        val safeTotal = total.coerceAtLeast(0)
        val denominator = colorParts.coerceIn(1, 64)
        val part = colorPart.coerceIn(0, denominator - 1)
        fun boundary(position: Int) = (safeTotal * position + denominator - 1) / denominator
        return boundary(part) until boundary(part + 1)
    }

    companion object {
        const val DEFAULT_SPACER_DP = 24
    }
}

enum class ToolbarDistribution { CONTINUOUS, REGIONS }

enum class ToolbarRegion { START, CENTER, END }

/** Each section contains one to three independently ordered lanes.
 * Horizontal bars render them as rows; vertical bars transpose them into columns. */
data class ToolbarSection(
    val entries: List<ToolbarEntry>,
    val lanes: Int = 1,
    val region: ToolbarRegion = ToolbarRegion.START,
) {
    val visibleEntries: List<ToolbarEntry> get() = entries.filter { it.visible }
    val hasVisible: Boolean get() = entries.any { it.visible }
    val crossCells: Int get() = (visibleEntries.maxOfOrNull { it.lane } ?: 0) + 1
    fun entriesInLane(lane: Int): List<ToolbarEntry> =
        visibleEntries.filter { it.lane == lane.coerceIn(0, lanes - 1) }
    fun normalized(): ToolbarSection = copy(
        lanes = lanes.coerceIn(1, 3),
        entries = entries.map { it.copy(lane = it.lane.coerceIn(0, lanes.coerceIn(1, 3) - 1)) },
    )
}

data class ToolbarLayout(
    val sections: List<ToolbarSection>,
    val distribution: ToolbarDistribution = ToolbarDistribution.CONTINUOUS,
    /** Keep Start visible while the remaining regions scroll; ignored in continuous mode. */
    val pinStart: Boolean = false,
    /** Keep End visible while the remaining regions scroll; ignored in continuous mode. */
    val pinEnd: Boolean = false,
) {
    val hasPinnedRegions: Boolean get() = distribution == ToolbarDistribution.REGIONS &&
        sections.any { it.hasVisible &&
            ((pinStart && it.region == ToolbarRegion.START) || (pinEnd && it.region == ToolbarRegion.END)) }

    /** Sections that paint on the real bar; a separator goes between consecutive ones. */
    val visibleSections: List<ToolbarSection> get() = sections.filter { it.hasVisible }

    fun addSection(lanes: Int = 1, region: ToolbarRegion = ToolbarRegion.START): ToolbarLayout =
        if (lanes !in 1..3) this else copy(sections = sections + ToolbarSection(emptyList(), lanes, region))

    /** Switching the display mode must never silently move, split or reorder sections.
     * Start/Center/End placement is edited explicitly by moving a section in the editor. */
    fun setDistribution(mode: ToolbarDistribution): ToolbarLayout =
        if (mode == distribution) this else copy(distribution = mode)

    /** Repair a previously auto-distributed layout without discarding tool settings or lanes. */
    fun gatherSectionsAtStart(): ToolbarLayout =
        if (sections.all { it.region == ToolbarRegion.START }) this
        else copy(sections = sections.map { it.copy(region = ToolbarRegion.START) })

    fun setSectionRegion(sectionIndex: Int, region: ToolbarRegion): ToolbarLayout =
        if (distribution == ToolbarDistribution.REGIONS) moveSectionToRegion(sectionIndex, region)
        else copy(sections = sections.mapIndexed { index, section ->
            if (index == sectionIndex) section.copy(region = region) else section
        })

    /** Move a section as a single block, optionally before another section in the target column.
     * A null anchor appends within that column; a newly populated column follows Start/Center/End. */
    fun moveSectionToRegion(from: Int, region: ToolbarRegion, beforeSectionIndex: Int? = null): ToolbarLayout {
        val original = sections.getOrNull(from) ?: return this
        if (beforeSectionIndex == from && original.region == region) return this
        if (beforeSectionIndex != null && sections.getOrNull(beforeSectionIndex)?.region != region) return this
        val rebuilt = sections.toMutableList()
        rebuilt.removeAt(from)
        val anchor = beforeSectionIndex?.let { (if (it > from) it - 1 else it).coerceIn(0, rebuilt.size) }
        val insertion = anchor ?: (rebuilt.indexOfLast { it.region == region }.takeIf { it >= 0 }?.plus(1)
            ?: rebuilt.indexOfFirst { it.region.ordinal > region.ordinal }.takeIf { it >= 0 }
            ?: rebuilt.size)
        rebuilt.add(insertion, original.copy(region = region))
        return copy(sections = rebuilt)
    }

    /** Target an occurrence by position, not tool ID (Colors may occur several times). */
    /** Add a tool to one section row. Colors can occur repeatedly; other tools are unique.
     * Re-adding a hidden tool moves it to the chosen row and makes it visible. */
    fun addItem(sectionIndex: Int, lane: Int, item: ToolbarItem): ToolbarLayout {
        val destination = sections.getOrNull(sectionIndex) ?: return this
        if (item == ToolbarItem.COLORS && colorBlockCount >= InkPalette.MAX_SWATCHES) return this
        val entries = sections.map { it.entries.toMutableList() }
        if (item != ToolbarItem.COLORS && item != ToolbarItem.SPACER) {
            val existing = sections.withIndex().firstNotNullOfOrNull { (si, section) ->
                section.entries.indexOfFirst { it.item == item }.takeIf { it >= 0 }?.let { si to it }
            }
            if (existing != null) {
                if (entries[existing.first][existing.second].visible) return this
                entries[existing.first].removeAt(existing.second)
            }
        }
        entries[sectionIndex].add(ToolbarEntry(item, lane = lane.coerceIn(0, destination.lanes - 1)))
        return copy(sections = sections.mapIndexed { index, sec -> sec.copy(entries = entries[index]) })
            .repartitionColors()
    }

    fun removeItem(sectionIndex: Int, entryIndex: Int): ToolbarLayout {
        val section = sections.getOrNull(sectionIndex) ?: return this
        if (entryIndex !in section.entries.indices) return this
        val updated = section.entries.toMutableList().also { it.removeAt(entryIndex) }
        return copy(sections = sections.toMutableList().also { it[sectionIndex] = section.copy(entries = updated) })
            .repartitionColors()
    }

    private fun repartitionColors(): ToolbarLayout {
        val count = colorBlockCount
        var index = 0
        return copy(sections = sections.map { sec ->
            sec.copy(entries = sec.entries.map { entry ->
                if (entry.item == ToolbarItem.COLORS) entry.copy(colorPart = index++, colorParts = count)
                else entry
            })
        })
    }

    fun setEntrySize(sectionIndex: Int, entryIndex: Int, sizeDp: Int?): ToolbarLayout {
        val section = sections.getOrNull(sectionIndex) ?: return this
        val entry = section.entries.getOrNull(entryIndex) ?: return this
        val range = if (entry.item == ToolbarItem.SPACER) 4..320 else 28..72
        if (sizeDp != null && sizeDp !in range) return this
        val entries = section.entries.toMutableList()
        entries[entryIndex] = entry.copy(sizeDp = sizeDp)
        val changed = sections.toMutableList()
        changed[sectionIndex] = section.copy(entries = entries)
        return copy(sections = changed)
    }

    fun setSectionLanes(sectionIndex: Int, lanes: Int): ToolbarLayout {
        if (lanes !in 1..3) return this
        val section = sections.getOrNull(sectionIndex) ?: return this
        val updated = section.copy(
            lanes = lanes,
            // Reducing 3 → 2 (or 2 → 1) merges removed lanes into the last
            // surviving one; no tool is left in an invisible row.
            entries = section.entries.map { it.copy(lane = it.lane.coerceIn(0, lanes - 1)) },
        )
        return copy(sections = sections.toMutableList().also { it[sectionIndex] = updated })
    }

    /** Number of color strips in this toolbar. They always share one global palette. */
    val colorBlockCount: Int get() = sections.sumOf { section ->
        section.entries.count { it.item == ToolbarItem.COLORS }
    }

    /** Set how many strips share the enabled swatches, keeping retained blocks in place.
     * New blocks are added after the last existing one and alternate lanes when possible.
     * Existing blocks are never moved or hidden by a colour-count change. */
    fun setColorBlocks(count: Int): ToolbarLayout {
        if (count !in 1..InkPalette.MAX_SWATCHES) return this
        val existing = sections.flatMapIndexed { s, section ->
            section.entries.mapIndexedNotNull { i, entry ->
                if (entry.item == ToolbarItem.COLORS) s to i else null
            }
        }
        if (existing.isEmpty()) return this
        val mutable = sections.map { it.entries.toMutableList() }.toMutableList()
        if (count < existing.size) {
            existing.drop(count).asReversed().forEach { (s, i) -> mutable[s].removeAt(i) }
        } else if (count > existing.size) {
            val (s, i) = existing.last()
            val prototype = mutable[s][i]
            val section = sections[s]
            repeat(count - existing.size) { offset ->
                val lane = (prototype.lane + offset + 1) % section.lanes
                mutable[s].add(i + 1 + offset, prototype.copy(lane = lane))
            }
        }
        var ordinal = 0
        return copy(sections = sections.mapIndexed { s, section ->
            section.copy(entries = mutable[s].map { entry ->
                if (entry.item == ToolbarItem.COLORS)
                    entry.copy(colorPart = ordinal++, colorParts = count)
                else entry
            })
        })
    }





    /**
     * Remove section [i], merging its entries into the previous section, or the next one when [i]
     * is the first. Order and visibility are preserved. No-op when only one section remains.
     */
    fun removeSection(i: Int): ToolbarLayout {
        if (sections.size <= 1 || i !in sections.indices) return this
        val target = if (i == 0) i + 1 else i - 1
        val moved = sections[i].entries
        val rebuilt = sections.mapIndexedNotNull { idx, sec ->
            when (idx) {
                i -> null
                target -> sec.copy(entries = if (target < i) sec.entries + moved else moved + sec.entries).normalized()
                else -> sec
            }
        }
        return copy(sections = rebuilt)
    }

    fun moveSection(from: Int, to: Int): ToolbarLayout {
        if (from !in sections.indices || to !in sections.indices || from == to) return this
        val list = sections.toMutableList()
        list.add(to, list.removeAt(from))
        return copy(sections = list)
    }

    fun toggleVisible(sectionIndex: Int, entryIndex: Int): ToolbarLayout {
        val sec = sections.getOrNull(sectionIndex) ?: return this
        val e = sec.entries.getOrNull(entryIndex) ?: return this
        val entries = sec.entries.toMutableList().also { it[entryIndex] = e.copy(visible = !e.visible) }
        return copy(sections = sections.toMutableList().also { it[sectionIndex] = sec.copy(entries = entries) })
    }

    /**
     * Move the entry at ([fromSection], [fromIndex]) to ([toSection], [toIndex]), keeping its
     * visibility. The target index is interpreted against the layout after the source is removed.
     */
    fun moveItem(fromSection: Int, fromIndex: Int, toSection: Int, toIndex: Int, toLane: Int = 0): ToolbarLayout {
        val src = sections.getOrNull(fromSection) ?: return this
        val entry = src.entries.getOrNull(fromIndex) ?: return this
        if (toSection !in sections.indices) return this
        val mutable = sections.map { it.entries.toMutableList() }.toMutableList()
        mutable[fromSection].removeAt(fromIndex)
        val adjusted = if (toSection == fromSection && toIndex > fromIndex) toIndex - 1 else toIndex
        val destinationLane = toLane.coerceIn(0, sections[toSection].lanes - 1)
        mutable[toSection].add(adjusted.coerceIn(0, mutable[toSection].size), entry.copy(lane = destinationLane))
        return copy(sections = mutable.mapIndexed { idx, e -> sections[idx].copy(entries = e) })
            .repartitionColors()
    }

    /** Move an individual tool into an empty region by creating a one-lane section there.
     * The old section remains editable, and all other tools retain their order and settings. */
    fun moveItemToNewRegionSection(fromSection: Int, fromIndex: Int, region: ToolbarRegion): ToolbarLayout {
        if (distribution != ToolbarDistribution.REGIONS) return this
        val source = sections.getOrNull(fromSection) ?: return this
        val entry = source.entries.getOrNull(fromIndex) ?: return this
        val rebuilt = sections.toMutableList()
        rebuilt[fromSection] = source.copy(entries = source.entries.toMutableList().also { it.removeAt(fromIndex) })
        val insertAt = (rebuilt.indexOfLast { it.region == region }.takeIf { it >= 0 }?.plus(1)
            ?: rebuilt.indexOfFirst { it.region.ordinal > region.ordinal }.takeIf { it >= 0 }
            ?: rebuilt.size)
        rebuilt.add(insertAt, ToolbarSection(listOf(entry.copy(lane = 0)), region = region))
        return copy(sections = rebuilt).repartitionColors()
    }

    /** Add any of [among] missing from this layout, visible, so the bar never goes stale
     *  across versions: an item with a designated neighbour ([INSERT_AFTER]) slots in right
     *  after it; anything else is appended to the last section. */
    fun withMissingItemsAppended(among: Set<ToolbarItem> = NOTE_ITEMS): ToolbarLayout {
        var layout = this
        for (item in ToolbarItem.entries) {
            if (item !in among || item == ToolbarItem.SPACER) continue
            if (layout.sections.any { s -> s.entries.any { it.item == item } }) continue
            layout = layout.insertMissing(item)
        }
        return layout
    }

    private fun insertMissing(item: ToolbarItem): ToolbarLayout {
        val anchor = INSERT_AFTER[item]
        val entry = ToolbarEntry(item, visible = true)
        if (anchor != null) {
            sections.forEachIndexed { si, sec ->
                val i = sec.entries.indexOfFirst { it.item == anchor }
                if (i >= 0) {
                    val entries = sec.entries.toMutableList().also { it.add(i + 1, entry) }
                    return copy(sections = sections.toMutableList().also { it[si] = sec.copy(entries = entries).normalized() })
                }
            }
        }
        val last = sections.last()
        return copy(sections = sections.dropLast(1) + last.copy(entries = last.entries + entry).normalized())
    }

    companion object {
        /** Later-added items that belong beside an existing one in stored layouts. */
        private val INSERT_AFTER = mapOf(
            ToolbarItem.TEXT_BOX to ToolbarItem.TEXT,
            ToolbarItem.VIEW to ToolbarItem.STYLES,
            ToolbarItem.MARGINS to ToolbarItem.STYLES,
            ToolbarItem.WAND to ToolbarItem.LASSO,
            ToolbarItem.TAPE to ToolbarItem.WAND,
            ToolbarItem.INSERT_SPACE to ToolbarItem.LASSO,
            ToolbarItem.MARKUP to ToolbarItem.TEXT_BOX,
        )

        /**
         * The two bars hold different things, so each has its own set and neither can be handed the
         * other's. Page navigation means nothing on an unbounded canvas, and a waypoint means
         * nothing in a paged note; an item outside a layout's set is dropped on load rather than
         * drawn as a dead chip.
         */
        val CANVAS_ITEMS: Set<ToolbarItem> = setOf(
            ToolbarItem.HOME, ToolbarItem.TITLE,
            ToolbarItem.PEN, ToolbarItem.DASHED, ToolbarItem.CALLIGRAPHY, ToolbarItem.SPEED,
            ToolbarItem.TAPER, ToolbarItem.HIGHLIGHTER, ToolbarItem.ERASER,
            ToolbarItem.PAN, ToolbarItem.SELECT, ToolbarItem.LASSO, ToolbarItem.WAND,
            ToolbarItem.TAPE, ToolbarItem.SHAPE,
            ToolbarItem.IMAGE, ToolbarItem.COLORS, ToolbarItem.UNDO, ToolbarItem.REDO,
            ToolbarItem.STYLES, ToolbarItem.WAYPOINTS, ToolbarItem.MINIMAP,
            ToolbarItem.ZOOM, ToolbarItem.FIT, ToolbarItem.ZOOM_LOCK,
            ToolbarItem.SPACER,
        )

        /** Everything the paged bar can hold: the whole enum bar the two canvas-only additions. Insert space is the note bar's alone. */
        val NOTE_ITEMS: Set<ToolbarItem> =
            ToolbarItem.entries.toSet() - setOf(ToolbarItem.WAYPOINTS, ToolbarItem.MINIMAP)

        /** Mirrors the hardcoded bar exactly (see Toolbar.kt) so existing users see no change. */
        val DEFAULT: ToolbarLayout = of(
            listOf(ToolbarItem.HOME, ToolbarItem.TITLE),
            listOf(ToolbarItem.SIDEBAR),
            listOf(
                ToolbarItem.PEN, ToolbarItem.DASHED, ToolbarItem.CALLIGRAPHY, ToolbarItem.SPEED,
                ToolbarItem.TAPER, ToolbarItem.HIGHLIGHTER, ToolbarItem.ERASER,
            ),
            listOf(ToolbarItem.PAN, ToolbarItem.SELECT, ToolbarItem.LASSO, ToolbarItem.INSERT_SPACE, ToolbarItem.SCREENSHOT),
            listOf(ToolbarItem.WAND, ToolbarItem.TAPE, ToolbarItem.SHAPE, ToolbarItem.RULER, ToolbarItem.TEXT, ToolbarItem.TEXT_BOX, ToolbarItem.MARKUP),
            listOf(ToolbarItem.IMAGE),
            listOf(ToolbarItem.COLORS),
            listOf(ToolbarItem.UNDO, ToolbarItem.REDO),
            listOf(ToolbarItem.PAGE_NAV, ToolbarItem.STYLES, ToolbarItem.MARGINS, ToolbarItem.VIEW),
            listOf(ToolbarItem.ZOOM, ToolbarItem.FIT, ToolbarItem.ZOOM_LOCK),
            listOf(ToolbarItem.FULLSCREEN),
        )

        /** Mirrors the hardcoded canvas bar (see InfiniteToolbar.kt). */
        val CANVAS_DEFAULT: ToolbarLayout = of(
            listOf(ToolbarItem.HOME, ToolbarItem.TITLE),
            listOf(
                ToolbarItem.PEN, ToolbarItem.DASHED, ToolbarItem.CALLIGRAPHY, ToolbarItem.SPEED,
                ToolbarItem.TAPER, ToolbarItem.HIGHLIGHTER, ToolbarItem.ERASER,
            ),
            listOf(ToolbarItem.PAN, ToolbarItem.SELECT, ToolbarItem.LASSO),
            listOf(ToolbarItem.WAND, ToolbarItem.TAPE, ToolbarItem.SHAPE),
            listOf(ToolbarItem.IMAGE),
            listOf(ToolbarItem.COLORS),
            listOf(ToolbarItem.UNDO, ToolbarItem.REDO),
            listOf(ToolbarItem.STYLES, ToolbarItem.WAYPOINTS, ToolbarItem.MINIMAP),
            listOf(ToolbarItem.ZOOM, ToolbarItem.FIT, ToolbarItem.ZOOM_LOCK),
        )

        private fun of(vararg groups: List<ToolbarItem>): ToolbarLayout =
            ToolbarLayout(groups.map { g -> ToolbarSection(g.map { ToolbarEntry(it, visible = true) }) })

        /**
         * Build from raw (id, visible) pairs per section as read from storage: ids outside [among]
         * are dropped, duplicates keep their first occurrence, empty input falls back to [fallback],
         * and any item of [among] not present is appended so the bar never goes stale across
         * versions.
         */
        fun fromRaw(
            rawSections: List<List<Pair<String, Boolean>>>,
            among: Set<ToolbarItem> = NOTE_ITEMS,
            fallback: ToolbarLayout = DEFAULT,
        ): ToolbarLayout {
            if (rawSections.isEmpty()) return fallback
            val seen = LinkedHashSet<ToolbarItem>()
            val sections = rawSections.map { raw ->
                ToolbarSection(
                    raw.mapNotNull { (id, visible) ->
                        val item = ToolbarItem.fromId(id) ?: return@mapNotNull null
                        if (item !in among) return@mapNotNull null
                        if (item != ToolbarItem.SPACER && !seen.add(item)) return@mapNotNull null
                        ToolbarEntry(item, visible)
                    },
                )
            }
            if (sections.all { it.entries.isEmpty() }) return fallback
            return ToolbarLayout(sections).withMissingItemsAppended(among)
        }
    }
}
