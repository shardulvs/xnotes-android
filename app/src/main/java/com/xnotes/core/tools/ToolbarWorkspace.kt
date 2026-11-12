package com.xnotes.core.tools

/**
 * An interface workspace contains multiple independently configured toolbars. The existing
 * [ToolbarLayout] still describes the tools *within* each bar, so old layouts can be carried
 * forward without changing the tool-dispatch or drawing models.
 *
 * Bar IDs are stable persistence keys and must not be derived from display names.
 */
enum class ToolbarAnchor {
    TOP_LEFT, TOP_CENTER, TOP_RIGHT,
    CENTER_LEFT, CENTER, CENTER_RIGHT,
    BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT,
}

enum class ToolbarContext { NOTE, CANVAS }

enum class ToolbarScale { COMPACT, REGULAR, COMFORTABLE }

/** Geometry is kept in logical dp; null dimensions mean wrap-content/automatic size. */
data class ToolbarGeometry(
    val anchor: ToolbarAnchor = ToolbarAnchor.TOP_CENTER,
    val scale: ToolbarScale = ToolbarScale.REGULAR,
    val floating: Boolean = false,
    val movable: Boolean = true,
    val widthDp: Int? = null,
    val heightDp: Int? = null,
    /** Null means the usual size for the selected compact/regular/comfortable preset. */
    val paddingDp: Int? = null,
    val spacingDp: Int? = null,
    val elementSizeDp: Int? = null,
    val columns: Int = 1,
    /** Null keeps legacy floating/primary behavior. Non-null requests a docked edge. */
    val dockEdge: ToolbarDockEdge? = null,
    val dockLane: Int = 0,
    val dockAlignment: ToolbarDockAlignment = ToolbarDockAlignment.CENTER,
    /** Rendering order within dock/floating lanes; independent of primary toolbar identity. */
    val visualOrder: Int? = null,
) {
    /** Logical dimensions remain stable to the user when the toolbar rotates. The saved
     * width/height fields are retained for backwards-compatible workspace JSON. */
    fun lengthDp(vertical: Boolean): Int? = if (vertical) heightDp else widthDp
    fun thicknessDp(vertical: Boolean): Int? = if (vertical) widthDp else heightDp
    fun withLengthDp(value: Int?, vertical: Boolean): ToolbarGeometry =
        if (vertical) copy(heightDp = value) else copy(widthDp = value)
    fun withThicknessDp(value: Int?, vertical: Boolean): ToolbarGeometry =
        if (vertical) copy(widthDp = value) else copy(heightDp = value)

    /** Move an edge without leaking length from the previous axis into the new thickness.
     * A 600×72 horizontal bar becomes 72×600 when moved to either vertical edge.
     * Null dimensions (automatic sizing) rotate exactly like explicit values.
     * [fromEdge] supplies the effective old dock for legacy primary bars whose saved anchor
     * differs from the global ToolbarLook position.
     */
    fun movedTo(anchor: ToolbarAnchor, floating: Boolean, fromEdge: ToolbarDockEdge? = null): ToolbarGeometry {
        fun ToolbarAnchor.cardinalEdge(): ToolbarDockEdge? = when (this) {
            ToolbarAnchor.TOP_CENTER -> ToolbarDockEdge.TOP
            ToolbarAnchor.BOTTOM_CENTER -> ToolbarDockEdge.BOTTOM
            ToolbarAnchor.CENTER_LEFT -> ToolbarDockEdge.LEFT
            ToolbarAnchor.CENTER_RIGHT -> ToolbarDockEdge.RIGHT
            else -> null
        }
        fun ToolbarAnchor.isVertical() = this == ToolbarAnchor.CENTER_LEFT || this == ToolbarAnchor.CENTER_RIGHT
        fun ToolbarDockEdge.isVertical() = this == ToolbarDockEdge.LEFT || this == ToolbarDockEdge.RIGHT
        val oldVertical = (fromEdge ?: dockEdge)?.isVertical() ?: this.anchor.isVertical()
        val newVertical = anchor.isVertical()
        val changedAxis = oldVertical != newVertical
        return copy(
            anchor = anchor,
            floating = floating,
            widthDp = if (changedAxis) heightDp else widthDp,
            heightDp = if (changedAxis) widthDp else heightDp,
            dockEdge = if (floating) null
                else if (dockEdge != null || fromEdge != null) anchor.cardinalEdge() ?: dockEdge
                else null,
        )
    }

    /** Clamp untrusted persisted dimensions before rendering; do not force an exact size on auto bars. */
    fun constrained(): ToolbarGeometry = copy(
        // CENTER is kept in the enum only to read older saved layouts safely.
        anchor = if (anchor == ToolbarAnchor.CENTER) ToolbarAnchor.TOP_CENTER else anchor,
        widthDp = widthDp?.coerceIn(48, 2048),
        heightDp = heightDp?.coerceIn(48, 2048),
        paddingDp = paddingDp?.coerceIn(0, 32),
        spacingDp = spacingDp?.coerceIn(0, 32),
        elementSizeDp = elementSizeDp?.coerceIn(28, 72),
        columns = columns.coerceIn(1, 6),
        visualOrder = visualOrder?.coerceIn(0, 127),
        dockLane = dockLane.coerceIn(0, ToolbarDockLayout.MAX_LANES - 1),
    )
}

data class ToolbarBar(
    val id: String,
    val name: String,
    val layout: ToolbarLayout,
    val visible: Boolean = true,
    val geometry: ToolbarGeometry = ToolbarGeometry(),
)

/**
 * Pure, immutable multi-bar operations. Tools may appear on different bars simultaneously:
 * uniqueness across an entire workspace would make quick tool and color palettes impossible.
 * Each bar's existing [ToolbarLayout] remains independently editable.
 */
data class ToolbarWorkspace(val bars: List<ToolbarBar>) {
    fun addBar(bar: ToolbarBar): ToolbarWorkspace =
        if (bar.id.isBlank() || bars.any { it.id == bar.id }) this
        else copy(bars = bars + bar)

    fun duplicateBar(id: String, newId: String, name: String): ToolbarWorkspace {
        val source = bars.firstOrNull { it.id == id } ?: return this
        return addBar(source.copy(id = newId, name = name,
            geometry = source.geometry.copy(visualOrder = null)))
    }

    /** Keep a primary bar as a recovery path, even if all its items are hidden. */
    fun removeBar(id: String): ToolbarWorkspace =
        if (bars.size <= 1 || bars.none { it.id == id }) this
        else copy(bars = bars.filterNot { it.id == id })

    fun replaceBar(bar: ToolbarBar): ToolbarWorkspace {
        val index = bars.indexOfFirst { it.id == bar.id }
        if (index < 0) return this
        return copy(bars = bars.toMutableList().also { it[index] = bar })
    }

    /** Keep legacy single-bar edits in sync after secondary bars have been added. */
    fun replacePrimaryLayout(layout: ToolbarLayout): ToolbarWorkspace =
        bars.firstOrNull()?.let { replaceBar(it.copy(layout = layout)) } ?: this

    /** A drag changes only lane ordering, never the first bar's primary identity.
     * Existing workspaces use their saved list order until their first visual reorder. */
    fun reorderVisualBar(id: String, targetId: String): ToolbarWorkspace {
        if (id == targetId || bars.none { it.id == id } || bars.none { it.id == targetId }) return this
        val ordered = bars.withIndex().sortedWith(compareBy(
            { it.value.geometry.visualOrder ?: it.index }, { it.index },
        )).map { it.value }.toMutableList()
        val from = ordered.indexOfFirst { it.id == id }
        val to = ordered.indexOfFirst { it.id == targetId }
        ordered.add(to, ordered.removeAt(from))
        val ranks = ordered.withIndex().associate { it.value.id to it.index }
        return copy(bars = bars.map { bar ->
            bar.copy(geometry = bar.geometry.copy(visualOrder = ranks.getValue(bar.id)))
        })
    }

    fun moveBar(id: String, toIndex: Int): ToolbarWorkspace {
        val index = bars.indexOfFirst { it.id == id }
        if (index < 0 || toIndex !in bars.indices || index == toIndex) return this
        return copy(bars = bars.toMutableList().also { it.add(toIndex, it.removeAt(index)) })
    }

    /**
     * Filter context-specific tools without appending every "missing" tool to every bar.
     * [ToolbarLayout.fromRaw] does append them for legacy *single* bars, but that behavior
     * would produce duplicates when loading an intentionally minimal secondary bar.
     */
    fun onlyItems(allowed: Set<ToolbarItem>): ToolbarWorkspace = copy(
        bars = bars.map { bar ->
            bar.copy(layout = bar.layout.copy(sections = bar.layout.sections.map { section ->
                section.copy(entries = section.entries.filter { it.item in allowed })
            }))
        },
    )

    companion object {
        fun fromLegacy(id: String, name: String, layout: ToolbarLayout): ToolbarWorkspace =
            ToolbarWorkspace(listOf(ToolbarBar(id = id, name = name, layout = layout)))
    }
}

/**
 * Two persisted profiles, with a switch to reuse the note profile on the canvas. Canvas-only
 * tools are filtered when sharing; the independent canvas profile stays intact for later reuse.
 */
data class ToolbarWorkspaces(
    val note: ToolbarWorkspace,
    val canvas: ToolbarWorkspace,
    val shared: Boolean = false,
) {
    /**
     * Edit a stored profile without filtering tools or resolving shared rendering.
     * Callers editing the displayed shared canvas must explicitly target NOTE.
     */
    fun updateWorkspace(
        context: ToolbarContext,
        transform: (ToolbarWorkspace) -> ToolbarWorkspace,
    ): ToolbarWorkspaces = when (context) {
        ToolbarContext.NOTE -> copy(note = transform(note))
        ToolbarContext.CANVAS -> copy(canvas = transform(canvas))
    }

    fun forContext(context: ToolbarContext): ToolbarWorkspace = when (context) {
        ToolbarContext.NOTE ->
            note.onlyItems(ToolbarLayout.NOTE_ITEMS)
        ToolbarContext.CANVAS ->
            (if (shared) note else canvas).onlyItems(ToolbarLayout.CANVAS_ITEMS)
    }

    /** Import one preset profile without replacing the other. Unshare first so the
     * canvas does not keep rendering the note profile after restoring either side. */
    fun replaceContextFrom(preset: ToolbarWorkspaces, target: ToolbarContext): ToolbarWorkspaces = when (target) {
        ToolbarContext.NOTE -> copy(note = preset.forContext(ToolbarContext.NOTE), shared = false)
        ToolbarContext.CANVAS -> copy(canvas = preset.forContext(ToolbarContext.CANVAS), shared = false)
    }

    companion object {
        fun fromLegacy(
            noteLayout: ToolbarLayout,
            canvasLayout: ToolbarLayout,
        ) = ToolbarWorkspaces(
            note = ToolbarWorkspace.fromLegacy("note-primary", "Main toolbar", noteLayout),
            canvas = ToolbarWorkspace.fromLegacy("canvas-primary", "Main toolbar", canvasLayout),
        )
    }
}
