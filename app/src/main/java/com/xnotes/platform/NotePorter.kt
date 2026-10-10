package com.xnotes.platform

import android.content.Context
import android.net.Uri
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Document
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.pal.ImageCodec
import com.xnotes.core.pal.TextMeasurer
import com.xnotes.format.CanvasCodec
import com.xnotes.format.DocumentCodec
import com.xnotes.sync.filen.FilenLocalStore
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.max

/**
 * Rewrites the notes and canvases that still hold shapes only earlier n-notes versions wrote (see
 * [com.xnotes.format.ForkShapes]) in today's kinds. A file is replaced only after its new copy reads
 * back as the same note, its old bytes are kept under [backupRoot] first, and a replaced file that
 * then reads back differently is put back from that copy. IO throughout.
 */
class NotePorter(
    private val context: Context,
    private val imageCodec: ImageCodec,
    private val measurer: TextMeasurer,
    private val workDir: File,
    private val backupRoot: File,
) {
    /** A note holding [shapes] shapes from the fork's own kinds. */
    class Found(val entry: FilenLocalStore.LocalEntry, val shapes: Int)

    class Result(val ported: List<String>, val failed: List<String>, val skipped: List<String>, val backupDir: File?)

    /** The notes under [treeUri] that hold fork shapes. Reads manifests only, never the assets. */
    fun scan(treeUri: String, isCancelled: () -> Boolean = { false }): List<Found> {
        val docCodec = DocumentCodec(imageCodec, measurer)
        val canvasCodec = CanvasCodec(imageCodec, measurer)
        val out = ArrayList<Found>()
        for (entry in FilenLocalStore.listNotes(context, treeUri)) {
            if (isCancelled()) break
            if (!FilenLocalStore.isNote(entry.relativePath)) continue
            val count = runCatching {
                open(entry.documentUri)?.use { input ->
                    if (isCanvas(entry)) canvasCodec.read(input).let { canvasCodec.forkShapesRead }
                    else docCodec.read(input).let { docCodec.forkShapesRead }
                } ?: 0
            }.getOrDefault(0)
            if (count > 0) out.add(Found(entry, count))
        }
        return out
    }

    /** Port each of [found], skipping the ones [isOpen] says an editor has open. */
    fun port(found: List<Found>, isOpen: (String) -> Boolean, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): Result {
        val ported = ArrayList<String>()
        val failed = ArrayList<String>()
        val skipped = ArrayList<String>()
        val backupDir = File(backupRoot, "port-" + System.currentTimeMillis())
        for ((i, f) in found.withIndex()) {
            onProgress(i, found.size)
            val path = f.entry.relativePath
            when {
                isOpen(f.entry.documentUri) -> skipped.add(path)
                portOne(f.entry, File(backupDir, path)) -> ported.add(path)
                else -> failed.add(path)
            }
        }
        onProgress(found.size, found.size)
        return Result(ported, failed, skipped, backupDir.takeIf { it.exists() })
    }

    /** Set once the file itself has been written to, after which its backup is never dropped. */
    private var touched = false

    private fun portOne(entry: FilenLocalStore.LocalEntry, backup: File): Boolean {
        val dir = File(workDir, "port-" + System.nanoTime()).apply { mkdirs() }
        touched = false
        var ok = false
        try {
            // The old bytes first: the note is read from this copy, and a file that changes after it is left alone.
            backup.parentFile?.mkdirs()
            open(entry.documentUri)?.use { input -> backup.outputStream().use { input.copyTo(it) } } ?: return false
            ok = if (isCanvas(entry)) portCanvas(entry.documentUri, backup, dir) else portNote(entry.documentUri, backup, dir)
            return ok
        } catch (_: Throwable) {
            if (touched) runCatching { writeTo(entry.documentUri, backup) }
            return false
        } finally {
            if (!ok && !touched) backup.delete()
            dir.deleteRecursively()
        }
    }

    private fun portNote(uri: String, backup: File, dir: File): Boolean {
        val pdfs = PdfSources()
        try {
            val codec = DocumentCodec(imageCodec, measurer) { doc, page -> pdfs.of(doc.pdfFile)?.pageGeometry(page) }
            fun read(input: () -> java.io.InputStream?): Document? = input()?.use { codec.read(it, File(dir, "pdf").apply { mkdirs() }, File(dir, "img").apply { mkdirs() }) }
            val doc = read { backup.inputStream() } ?: return false
            if (codec.forkShapesRead == 0) return false
            val want = noteFingerprint(doc)
            val tmp = File(dir, "new.xnote")
            tmp.outputStream().use { codec.write(doc, it) }
            val again = read { tmp.inputStream() } ?: return false
            if (codec.forkShapesRead != 0 || !same(want, noteFingerprint(again))) return false
            return replace(uri, tmp, backup) { read { open(uri) }?.takeIf { codec.forkShapesRead == 0 }?.let { same(want, noteFingerprint(it)) } == true }
        } finally {
            pdfs.close()
        }
    }

    private fun portCanvas(uri: String, backup: File, dir: File): Boolean {
        val codec = CanvasCodec(imageCodec, measurer)
        fun read(input: () -> java.io.InputStream?): InfiniteDocument? = input()?.use { codec.read(it, File(dir, "img").apply { mkdirs() }) }
        val doc = read { backup.inputStream() } ?: return false
        if (codec.forkShapesRead == 0) return false
        val want = canvasFingerprint(doc)
        val tmp = File(dir, "new.xcanvas")
        tmp.outputStream().use { codec.write(doc, it) }
        val again = read { tmp.inputStream() } ?: return false
        if (codec.forkShapesRead != 0 || !same(want, canvasFingerprint(again))) return false
        return replace(uri, tmp, backup) { read { open(uri) }?.takeIf { codec.forkShapesRead == 0 }?.let { same(want, canvasFingerprint(it)) } == true }
    }

    /**
     * Write [tmp] over the file at [uri] while it still holds exactly [backup], and confirm with
     * [readsBack]; when that fails, the backup goes back in. True only when the new file is in place
     * and confirmed.
     */
    private fun replace(uri: String, tmp: File, backup: File, readsBack: () -> Boolean): Boolean {
        // Written to since the copy (a sync, an editor): it is no longer the note that was checked.
        if (open(uri)?.use(::sha256) != backup.inputStream().use(::sha256)) return false
        touched = true
        if (writeTo(uri, tmp) && runCatching(readsBack).getOrDefault(false)) return true
        writeTo(uri, backup)
        return false
    }

    private fun writeTo(uri: String, from: File): Boolean = runCatching {
        context.contentResolver.openOutputStream(Uri.parse(uri), "wt")?.use { out -> from.inputStream().use { it.copyTo(out) } } != null
    }.getOrDefault(false)

    private fun open(uri: String) = context.contentResolver.openInputStream(Uri.parse(uri))

    private fun isCanvas(entry: FilenLocalStore.LocalEntry) = entry.relativePath.endsWith(".xcanvas", ignoreCase = true)

    /** One PDF reader per extracted source, for the markups' page geometry; closed together. */
    private class PdfSources {
        private val open = HashMap<File, PdfSource>()
        fun of(file: File?): PdfSource? = file?.let { open.getOrPut(it) { PdfSource.open(it) } }
        fun close() = open.values.forEach { it.close() }
    }

    internal class Mark(val what: String, val numbers: DoubleArray)

    private fun noteFingerprint(doc: Document): List<Mark> {
        val out = ArrayList<Mark>()
        out.add(Mark("doc", doubleArrayOf(doc.pages.size.toDouble(), doc.dpi.toDouble(), doc.bookmarks.size.toDouble(), doc.templates.size.toDouble())))
        out.add(Mark("pdf " + (doc.pdfFile?.let { f -> f.inputStream().use(::sha256) } ?: "none"), DoubleArray(0)))
        out.add(Mark("flow " + doc.flow.paragraphs.joinToString("\n") { it.plainText() }, DoubleArray(0)))
        for (b in doc.bookmarks) out.add(Mark("bookmark ${b.label}", doubleArrayOf(b.page.toDouble())))
        for (page in doc.pages) {
            out.add(Mark("page", doubleArrayOf(page.width, page.height, (page.pdfPage ?: -1).toDouble(), page.items.size.toDouble(), page.markups.size.toDouble())))
            for (item in page.items) out.add(itemMark(item))
            for (m in page.markups) out.add(Mark("markup ${m.id} ${m.type}", m.quads.flatMap { listOf(it.left, it.top, it.right, it.bottom, it.quarter.toFloat()) }.map { it.toDouble() }.toDoubleArray()))
        }
        return out
    }

    private fun canvasFingerprint(doc: InfiniteDocument): List<Mark> {
        val out = ArrayList<Mark>()
        out.add(Mark("canvas ${doc.background}", doubleArrayOf(doc.items.size.toDouble(), doc.dpi.toDouble())))
        for (w in doc.waypoints) out.add(Mark("waypoint ${w.name}", doubleArrayOf(w.cx, w.cy, w.zoom)))
        for (item in doc.items) out.add(itemMark(item))
        return out
    }

    private fun itemMark(item: CanvasItem): Mark {
        val kind = when (item) {
            is ShapeItem -> "shape ${item.shape.id} ${item.function?.expr}"
            is ImageItem -> "image ${item.image.width}x${item.image.height}"
            else -> item.kind
        }
        val b = item.bounds()
        return Mark("$kind locked=${item.locked} link=${item.link}", doubleArrayOf(b.x, b.y, b.w, b.h))
    }

    private fun sha256(input: java.io.InputStream): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            md.update(buf, 0, r)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    internal companion object {
        /** Same marks in the same order, each number within a thousandth of its size. */
        fun same(a: List<Mark>, b: List<Mark>): Boolean =
            a.size == b.size && a.indices.all { i ->
                val x = a[i]
                val y = b[i]
                x.what == y.what && x.numbers.size == y.numbers.size &&
                    x.numbers.indices.all { k -> abs(x.numbers[k] - y.numbers[k]) <= 1e-3 * max(1.0, abs(x.numbers[k])) }
            }
    }
}
