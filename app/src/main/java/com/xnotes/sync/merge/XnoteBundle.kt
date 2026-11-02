package com.xnotes.sync.merge

import com.xnotes.format.ZipTail
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.FileChannel
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * An .xnote read from its zip directory, with the manifest's `pages` array split into the raw text of
 * each page. Pages are never re-serialized, so an unchanged page keeps its exact bytes. Only sync
 * uses this, to fingerprint pages and to merge two versions page by page; the format is unchanged.
 * Only the manifest is held in memory: assets are streamed from [ch] when a merge writes them out.
 */
class XnoteBundle private constructor(
    private val ch: FileChannel,
    private val entries: List<ZipTail.Entry>,
    private val head: String,
    val pages: List<String>,
    private val tail: String,
) {
    /** What sync remembers of a note to merge it later: one hash per page and one for the rest. */
    class Fingerprints(val pages: List<String>, val doc: String)

    /** The fingerprints of a merged note, and the 1-based page numbers that hold both devices' versions of one part. */
    class Merged(val fingerprints: Fingerprints, val keptBothPages: List<Int>)

    private val byName = entries.associateBy { it.name }

    fun fingerprints(): Fingerprints = Fingerprints(pages.map { pageFingerprint(it) }, docFingerprint())

    private fun pageFingerprint(page: String): String =
        hash(ASSET_REF.replace(page) { m -> byName[assetName(m)]?.let { "\"${it.crc}:${it.size}\"" } ?: m.value })

    /** The manifest outside the pages (minus the writer's version, which differs between app builds) and the flow. */
    private fun docFingerprint(): String =
        hash(WRITER.replace(head, "") + "\u0000" + tail + "\u0000" + (byName[FLOW]?.crc ?: ""))

    /** Entries the merge copies from one side as they are and so must match on both sides. */
    private fun sharedEntries(): Set<String> =
        entries.filter { !isPageAsset(it.name) && it.name != MANIFEST && it.name != FLOW }.mapTo(HashSet()) { "${it.name}:${it.crc}" }

    private fun hasPageIndexes(): Boolean = MARKUPS in byName || !NO_BOOKMARKS.containsMatchIn(head)

    private fun write(refs: List<PageMerge.Ref>, remote: XnoteBundle, docFrom: XnoteBundle, out: OutputStream) {
        val newPages = ArrayList<String>(refs.size)
        ZipOutputStream(out).use { zos ->
            zos.setLevel(java.util.zip.Deflater.BEST_SPEED)
            val images = ArrayList<Triple<XnoteBundle, ZipTail.Entry, String>>()
            val audio = ArrayList<Triple<XnoteBundle, ZipTail.Entry, String>>()
            // Page assets first, renamed in page order the way the codec names them.
            for (ref in refs) {
                val from = if (ref.local) this else remote
                val renamed = HashMap<String, String>()
                newPages.add(ASSET_REF.replace(from.pages[ref.index]) { m ->
                    val old = assetName(m)
                    val entry = from.byName[old] ?: return@replace m.value
                    val name = renamed.getOrPut(old) {
                        val ext = old.substringAfterLast('.', "")
                        val list = if (old.startsWith(IMAGE)) images else audio
                        val name = (if (list === images) IMAGE else AUDIO) + "%03d.%s".format(list.size, ext)
                        list.add(Triple(from, entry, name))
                        name
                    }
                    "\"assets${m.groupValues[1]}${name.removePrefix("assets/")}\""
                })
            }
            // All images, then all audio, as the codec's storedEntries orders them, so the editor's in-place save still applies.
            for ((from, entry, name) in images + audio) zos.put(from, entry, name)
            // Then everything else in the order the chosen side had it, and the manifest last, as the codec writes it.
            for (entry in docFrom.entries) {
                if (isPageAsset(entry.name) || entry.name == MANIFEST) continue
                zos.put(docFrom, entry, entry.name)
            }
            val manifest = (docFrom.head + newPages.joinToString(",") + docFrom.tail).toByteArray(Charsets.UTF_8)
            zos.putNextEntry(ZipEntry(MANIFEST).apply { method = ZipEntry.DEFLATED })
            zos.write(manifest)
            zos.closeEntry()
        }
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

        /** Reads only the zip directory and the manifest, so a note's size does not matter. The caller owns [ch]. */
        fun fingerprintsOf(ch: FileChannel): Fingerprints? = parse(ch)?.fingerprints()

        fun fingerprintsOf(file: File): Fingerprints? = FileInputStream(file).use { fingerprintsOf(it.channel) }

        private fun parse(ch: FileChannel): XnoteBundle? = runCatching {
            val entries = ZipTail.read(ch)?.entries ?: return null
            val manifest = entries.firstOrNull { it.name == MANIFEST } ?: return null
            val text = String(readAll(ch, manifest), Charsets.UTF_8)
            val (open, slices, close) = splitPages(text) ?: return null
            XnoteBundle(ch, entries, text.substring(0, open + 1), slices, text.substring(close))
        }.getOrNull()

        /**
         * Merges [local] and [remote] against what both had at the last sync into [out], or returns null when
         * it can't be done safely and the caller should keep a conflict copy instead ([out] is then not a note).
         */
        fun merge(basePages: List<String>, baseDoc: String, local: File, remote: File, out: File): Merged? =
            FileInputStream(local).use { lIn ->
                FileInputStream(remote).use { rIn ->
                    val l = parse(lIn.channel) ?: return null
                    val r = parse(rIn.channel) ?: return null
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
                    FileOutputStream(out).use { l.write(result.pages, r, docFrom, it.buffered(64 * 1024)) }
                    val check = FileInputStream(out).use { parse(it.channel)?.fingerprints() } ?: return null
                    if (check.pages.size != result.pages.size) return null
                    Merged(check, result.keptBoth.map { it + 1 })
                }
            }

        private fun ZipOutputStream.put(from: XnoteBundle, entry: ZipTail.Entry, name: String) {
            putNextEntry(ZipEntry(name).apply {
                if (entry.method == ZipEntry.STORED) {
                    method = ZipEntry.STORED
                    size = entry.size
                    compressedSize = size
                    crc = entry.crc
                } else {
                    method = ZipEntry.DEFLATED
                }
            })
            ZipTail.readEntry(from.ch, entry) { copy(it, entry.size, this) } ?: error("cannot read ${entry.name}")
            closeEntry()
        }

        private fun readAll(ch: FileChannel, entry: ZipTail.Entry): ByteArray {
            val out = java.io.ByteArrayOutputStream(entry.size.toInt())
            ZipTail.readEntry(ch, entry) { copy(it, entry.size, out) } ?: error("cannot read ${entry.name}")
            return out.toByteArray()
        }

        /** Exactly [size] bytes: a stored entry's stream runs on past its end. The stream is not closed, as it would close the channel. */
        private fun copy(input: InputStream, size: Long, out: OutputStream) {
            val buf = ByteArray(64 * 1024)
            var left = size
            while (left > 0) {
                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) throw java.io.EOFException()
                out.write(buf, 0, n)
                left -= n
            }
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
