package com.xnotes.sync.merge

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * An .xnote held as its zip entries, with the manifest's `pages` array split into the raw text of
 * each page. Pages are never re-serialized, so an unchanged page keeps its exact bytes. Only sync
 * uses this, to fingerprint pages and to merge two versions page by page; the format is unchanged.
 */
class XnoteBundle private constructor(
    private val entries: List<Entry>,
    private val head: String,
    val pages: List<String>,
    private val tail: String,
) {
    private class Entry(val name: String, val bytes: ByteArray, val stored: Boolean, val crc: Long)

    /** What sync remembers of a note to merge it later: one hash per page and one for the rest. */
    class Fingerprints(val pages: List<String>, val doc: String)

    /** A merged note and the 1-based page numbers that were changed on both devices (both kept). */
    class Merged(val bytes: ByteArray, val keptBothPages: List<Int>)

    private val byName = entries.associateBy { it.name }

    fun fingerprints(): Fingerprints = Fingerprints(pages.map { pageFingerprint(it) }, docFingerprint())

    private fun pageFingerprint(page: String): String =
        hash(ASSET_REF.replace(page) { m -> byName[assetName(m)]?.let { "\"${it.crc}:${it.bytes.size}\"" } ?: m.value })

    /** The manifest outside the pages (minus the writer's version, which differs between app builds) and the flow. */
    private fun docFingerprint(): String =
        hash(WRITER.replace(head, "") + "\u0000" + tail + "\u0000" + (byName[FLOW]?.crc ?: ""))

    /** Entries the merge copies from one side as they are and so must match on both sides. */
    private fun sharedEntries(): Set<String> =
        entries.filter { !isPageAsset(it.name) && it.name != MANIFEST && it.name != FLOW }.mapTo(HashSet()) { "${it.name}:${it.crc}" }

    private fun hasPageIndexes(): Boolean = MARKUPS in byName || !NO_BOOKMARKS.containsMatchIn(head)

    private fun write(refs: List<PageMerge.Ref>, remote: XnoteBundle, docFrom: XnoteBundle): ByteArray {
        val out = ByteArrayOutputStream()
        val newPages = ArrayList<String>(refs.size)
        ZipOutputStream(out).use { zos ->
            zos.setLevel(java.util.zip.Deflater.BEST_SPEED)
            var images = 0
            var audio = 0
            // Page assets first, renamed in page order the way the codec names them.
            for (ref in refs) {
                val from = if (ref.local) this else remote
                val renamed = HashMap<String, String>()
                newPages.add(ASSET_REF.replace(from.pages[ref.index]) { m ->
                    val old = assetName(m)
                    val entry = from.byName[old] ?: return@replace m.value
                    val name = renamed.getOrPut(old) {
                        val ext = old.substringAfterLast('.', "")
                        val name = if (old.startsWith(IMAGE)) IMAGE + "%03d.%s".format(images++, ext) else AUDIO + "%03d.%s".format(audio++, ext)
                        zos.put(entry, name)
                        name
                    }
                    "\"assets${m.groupValues[1]}${name.removePrefix("assets/")}\""
                })
            }
            // Then everything else in the order the chosen side had it, and the manifest last, as the codec writes it.
            for (entry in docFrom.entries) {
                if (isPageAsset(entry.name) || entry.name == MANIFEST) continue
                zos.put(entry, entry.name)
            }
            val manifest = (docFrom.head + newPages.joinToString(",") + docFrom.tail).toByteArray(Charsets.UTF_8)
            zos.putNextEntry(ZipEntry(MANIFEST).apply { method = ZipEntry.DEFLATED })
            zos.write(manifest)
            zos.closeEntry()
        }
        return out.toByteArray()
    }

    companion object {
        private const val MANIFEST = "manifest.json"
        private const val FLOW = "flow.xml"
        private const val MARKUPS = "markups.xfdf"
        private const val IMAGE = "assets/image-"
        private const val AUDIO = "assets/audio-"
        // The codec escapes the slash ("assets\\/image-000.png"), so both spellings are matched.
        private val ASSET_REF = Regex("\"assets(\\\\?/)((?:image|audio)-[^\"\\\\]*)\"")
        private val WRITER = Regex("\"writer\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|[^,}]*)")
        private val NO_BOOKMARKS = Regex("\"bookmarks\"\\s*:\\s*\\[\\s*]")

        private fun assetName(m: MatchResult) = "assets/" + m.groupValues[2]

        private fun isPageAsset(name: String) = name.startsWith(IMAGE) || name.startsWith(AUDIO)

        fun parse(bytes: ByteArray): XnoteBundle? = runCatching {
            val entries = ArrayList<Entry>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                while (true) {
                    val e = zis.nextEntry ?: break
                    if (e.isDirectory) continue
                    val data = zis.readBytes()
                    entries.add(Entry(e.name, data, e.method == ZipEntry.STORED, CRC32().apply { update(data) }.value))
                }
            }
            val manifest = entries.firstOrNull { it.name == MANIFEST } ?: return null
            val text = String(manifest.bytes, Charsets.UTF_8)
            val (open, slices, close) = splitPages(text) ?: return null
            XnoteBundle(entries, text.substring(0, open + 1), slices, text.substring(close))
        }.getOrNull()

        /**
         * Merges [local] and [remote] against what both had at the last sync, or returns null when it
         * can't be done safely and the caller should keep a conflict copy instead.
         */
        fun merge(basePages: List<String>, baseDoc: String, local: ByteArray, remote: ByteArray): Merged? {
            val l = parse(local) ?: return null
            val r = parse(remote) ?: return null
            if (l.hasPageIndexes() || r.hasPageIndexes()) return null
            if (l.sharedEntries() != r.sharedEntries()) return null
            val lf = l.fingerprints()
            val rf = r.fingerprints()
            val docFrom = when {
                lf.doc == rf.doc || rf.doc == baseDoc -> l
                lf.doc == baseDoc -> r
                else -> return null
            }
            val result = PageMerge.merge(basePages, lf.pages, rf.pages) ?: return null
            val bytes = l.write(result.pages, r, docFrom)
            val check = parse(bytes) ?: return null
            if (check.pages.size != result.pages.size) return null
            return Merged(bytes, result.keptBoth.map { it + 1 })
        }

        private fun ZipOutputStream.put(entry: Entry, name: String) {
            putNextEntry(ZipEntry(name).apply {
                if (entry.stored) {
                    method = ZipEntry.STORED
                    size = entry.bytes.size.toLong()
                    compressedSize = size
                    crc = entry.crc
                } else {
                    method = ZipEntry.DEFLATED
                }
            })
            write(entry.bytes)
            closeEntry()
        }

        private fun hash(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }.take(32)

        /**
         * Finds the top-level "pages" array: the index of its '[', the raw text of each element, and the
         * index of its ']'. Null when the manifest has no such array or is malformed.
         */
        internal fun splitPages(json: String): Triple<Int, List<String>, Int>? {
            var i = 0
            var depth = 0
            var keyAtDepth1: String? = null
            while (i < json.length) {
                when (val c = json[i]) {
                    '"' -> {
                        val end = stringEnd(json, i) ?: return null
                        if (depth == 1) {
                            var j = end + 1
                            while (j < json.length && json[j].isWhitespace()) j++
                            if (j < json.length && json[j] == ':') keyAtDepth1 = json.substring(i + 1, end)
                        }
                        i = end
                    }
                    '{', '[' -> {
                        if (depth == 1 && c == '[' && keyAtDepth1 == "pages") return splitArray(json, i)
                        depth++
                    }
                    '}', ']' -> depth--
                }
                i++
            }
            return null
        }

        private fun splitArray(json: String, open: Int): Triple<Int, List<String>, Int>? {
            val slices = ArrayList<String>()
            var depth = 0
            var start = -1
            var i = open + 1
            while (i < json.length) {
                when (json[i]) {
                    '"' -> i = stringEnd(json, i) ?: return null
                    '{', '[' -> { if (depth == 0) start = i; depth++ }
                    '}', ']' -> {
                        if (depth == 0) return Triple(open, slices, i)
                        depth--
                        if (depth == 0) slices.add(json.substring(start, i + 1))
                    }
                }
                i++
            }
            return null
        }

        /** The index of the quote closing the string that opens at [start]. */
        private fun stringEnd(json: String, start: Int): Int? {
            var i = start + 1
            while (i < json.length) {
                when (json[i]) {
                    '\\' -> i++
                    '"' -> return i
                }
                i++
            }
            return null
        }
    }
}
