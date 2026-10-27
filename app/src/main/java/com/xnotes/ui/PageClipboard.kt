package com.xnotes.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.xnotes.core.model.Document
import com.xnotes.core.model.PageCargo
import java.io.File
import java.lang.ref.WeakReference

/**
 * The pages Copy and Cut put by, for Paste in any note in either pane until something replaces
 * them or the app closes. Pages showing PDF pages take a second name for their note's PDF along,
 * since a note's own PDF is deleted once it closes.
 */
internal object PageClipboard {

    class Clip(
        val cargo: PageCargo,
        /** The note the pages came from, while it lives, so pasting back into it is the plain paste it always was. */
        private val from: WeakReference<Document>,
        /** The source note's PDF under a name of the clip's own, or null when the pages show none. */
        val sourcePdf: File?,
        /** Put by with Cut: its bookmarks go with it, even back into its own note. */
        val cut: Boolean,
    ) {
        fun isFrom(doc: Document): Boolean = from.get() === doc

        internal var users = 0
        internal var released = false

        internal fun deleteFiles() {
            sourcePdf?.delete()
            cargo.pdf?.delete()
        }
    }

    var clip by mutableStateOf<Clip?>(null)
        private set

    /** Put [next] by, or empty the clipboard with null. */
    fun put(next: Clip?) {
        val old = clip
        clip = next
        if (old != null) synchronized(this) {
            old.released = true
            if (old.users == 0) old.deleteFiles()
        }
    }

    /** Keep [c]'s files while a paste reads them, until [done]. */
    fun use(c: Clip) = synchronized(this) { c.users++ }

    fun done(c: Clip) = synchronized(this) {
        c.users--
        if (c.users == 0 && c.released) c.deleteFiles()
    }
}
