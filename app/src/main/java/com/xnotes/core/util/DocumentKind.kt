package com.xnotes.core.util

/**
 * The document types the app stores, and the file extension each uses.
 *
 * The extension is spelled once here rather than at the two dozen sites that used to hard-code
 * `.xnote`. That matters now there are two: every place that lists, names, renames or opens a file
 * has to agree on which extensions are documents, and a place that forgets one silently hides the
 * user's files from the explorer.
 */
enum class DocumentKind(val extension: String) {
    /** The paged notebook. */
    NOTE("xnote"),

    /** The infinite canvas. */
    CANVAS("xcanvas"),

    /** A PDF: a paged notebook too, one that keeps its note inside it once it has been written on. */
    PDF("pdf"),
    ;

    /** The extension with its dot, as it appears at the end of a file name. */
    val suffix: String get() = ".$extension"

    companion object {
        /** The kind [name] denotes, or null when it is not a document at all. */
        fun ofName(name: String): DocumentKind? =
            entries.firstOrNull { name.endsWith(it.suffix, ignoreCase = true) }

        fun isDocument(name: String): Boolean = ofName(name) != null

        /** [name] without its document extension, or unchanged when it has none. */
        fun stripSuffix(name: String): String {
            val kind = ofName(name) ?: return name
            return name.dropLast(kind.suffix.length)
        }

        /** [name] with [kind]'s extension, added only if it is not already the right one. */
        fun withSuffix(name: String, kind: DocumentKind): String =
            if (name.endsWith(kind.suffix, ignoreCase = true)) name else "$name${kind.suffix}"
    }
}
