package com.xnotes.core.workspace

/** A tab identifies a file, not an editor instance or a rendered canvas. */
data class NoteTab(val uri: String, val title: String)

/**
 * Lightweight workspace state. Only the selected documents need an attached editor; inactive tabs
 * retain their identities while the editor can release its GL surface and cached page bitmaps.
 *
 * The pane assignments are independent of tab order. A document cannot occupy both panes at once.
 * Persist [tabs] and the selected URIs to restore the workspace after process recreation.
 */
data class NoteTabs(
    val tabs: List<NoteTab> = emptyList(),
    val primaryUri: String? = null,
    val secondaryUri: String? = null,
) {
    init {
        require(tabs.map { it.uri }.distinct().size == tabs.size) { "Duplicate note tabs" }
        require(primaryUri == null || tabs.any { it.uri == primaryUri })
        require(secondaryUri == null || tabs.any { it.uri == secondaryUri })
        require(primaryUri == null || primaryUri != secondaryUri)
    }

    fun open(uri: String, title: String, secondary: Boolean = false): NoteTabs {
        require(uri.isNotBlank())
        val updated = if (tabs.any { it.uri == uri }) tabs else tabs + NoteTab(uri, title)
        return if (secondary) {
            copy(tabs = updated, primaryUri = if (primaryUri == uri) null else primaryUri, secondaryUri = uri)
        } else {
            copy(tabs = updated, primaryUri = uri, secondaryUri = if (secondaryUri == uri) null else secondaryUri)
        }
    }

    /** Return a tab to the background without closing it. */
    fun hide(uri: String): NoteTabs = copy(
        primaryUri = primaryUri?.takeUnless { it == uri },
        secondaryUri = secondaryUri?.takeUnless { it == uri },
    )

    /** Explicit close removes the tab; choose the nearest remaining tab for its previous pane. */
    fun close(uri: String): NoteTabs {
        val index = tabs.indexOfFirst { it.uri == uri }
        if (index < 0) return this
        val remaining = tabs.filterNot { it.uri == uri }
        fun replacement(excluding: String?): String? {
            if (remaining.isEmpty()) return null
            // Browser-style close: prefer the tab immediately to the left, then the right.
            val left = (index - 1).coerceAtMost(remaining.lastIndex)
            return (left downTo 0).asSequence().filter { it >= 0 }
                .plus((index until remaining.size).asSequence())
                .map { remaining[it].uri }.firstOrNull { it != excluding }
        }
        return copy(
            tabs = remaining,
            primaryUri = if (primaryUri == uri) replacement(secondaryUri) else primaryUri,
            secondaryUri = if (secondaryUri == uri) replacement(primaryUri) else secondaryUri,
        )
    }

    /** Workspace selection remains valid while the focused editor swaps documents asynchronously. */
    fun selectedUri(secondaryFocused: Boolean): String? =
        if (secondaryFocused) secondaryUri ?: primaryUri else primaryUri ?: secondaryUri

    fun move(fromIndex: Int, toIndex: Int): NoteTabs {
        if (fromIndex !in tabs.indices || toIndex !in tabs.indices || fromIndex == toIndex) return this
        val reordered = tabs.toMutableList()
        reordered.add(toIndex, reordered.removeAt(fromIndex))
        return copy(tabs = reordered)
    }
}
