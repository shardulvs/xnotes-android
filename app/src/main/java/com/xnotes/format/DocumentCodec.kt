package com.xnotes.format

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Bookmark
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.Document
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.Orientation
import com.xnotes.core.model.Page
import com.xnotes.core.model.PageSize
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.TextItem
import com.xnotes.core.pal.FontFace
import com.xnotes.core.pal.ImageCodec
import com.xnotes.core.pal.TextMeasurer
import com.xnotes.core.stroke.Sample
import com.xnotes.core.tools.ShapeKind
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Thrown when a file is not a valid `.xnote` bundle. */
class XNoteFormatException(message: String) : Exception(message)

/**
 * Reads and writes the native `.xnote` bundle (spec 08): a ZIP with a deflated
 * `manifest.json` plus stored binary assets (`assets/image-NNN.png`,
 * `assets/source.pdf`). Strokes are kept as editable vector samples; nothing is
 * flattened. Loading is forgiving — unknown item kinds are skipped and missing
 * fields take model defaults.
 */
class DocumentCodec(
    private val imageCodec: ImageCodec,
    private val textMeasurer: TextMeasurer,
) {

    /** Thrown out of [write] when [isCancelled] turns true mid-copy, so the caller can discard the partial file. */
    class WriteCancelled : Exception()

    fun write(doc: Document, out: OutputStream, isCancelled: () -> Boolean = { false }) {
        val assets = ArrayList<Pair<String, ByteArray>>()
        var imageIndex = 0

        val pagesArr = JSONArray()
        for (page in doc.pages) {
            val itemsArr = JSONArray()
            for (item in page.items) {
                val obj = when (item) {
                    is Stroke -> strokeToJson(item)
                    is ImageItem -> {
                        val name = "assets/image-%03d.png".format(imageIndex++)
                        assets.add(name to imageCodec.encodePng(item.raster))
                        imageToJson(item, name)
                    }
                    is TextItem -> textToJson(item)
                    is ShapeItem -> shapeToJson(item)
                    else -> null // unrecognized kind: not written
                }
                if (obj != null) itemsArr.put(obj)
            }
            pagesArr.put(
                JSONObject()
                    .put("width", page.width)
                    .put("height", page.height)
                    .put("pdf_page", page.pdfPage ?: JSONObject.NULL)
                    .put("items", itemsArr),
            )
        }

        val bookmarksArr = JSONArray()
        for (b in doc.bookmarks) {
            bookmarksArr.put(JSONObject().put("page", b.page).put("label", b.label))
        }

        val manifest = JSONObject()
            .put("format", FORMAT)
            .put("version", VERSION)
            .put("dpi", doc.dpi)
            .put("has_pdf", doc.pdfFile != null)
            .put("bookmarks", bookmarksArr)
            .put("pages", pagesArr)

        ZipOutputStream(out).use { zos ->
            zos.putDeflated("manifest.json", manifest.toString().toByteArray(Charsets.UTF_8))
            for ((name, bytes) in assets) zos.putStored(name, bytes)
            // Stream the source PDF straight from disk into the bundle so a big PDF is never
            // materialized as a byte[]. [isCancelled] lets a long copy abort (e.g. import cancel).
            doc.pdfFile?.let { zos.putStored("assets/source.pdf", it, isCancelled) }
        }
    }

    /**
     * Read a `.xnote` from [input]. When [pdfDir] is non-null, an embedded source PDF is streamed
     * out to a fresh temp file in it (set as [Document.pdfFile]) instead of being held in RAM —
     * the caller owns that file's lifetime. When [pdfDir] is null the PDF is skipped entirely
     * (so [Document.pdfFile] stays null), which is what validation-only reads want.
     */
    fun read(input: InputStream, pdfDir: File? = null): Document {
        val entries = HashMap<String, ByteArray>()
        var pdfFile: File? = null
        ZipInputStream(input).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    if (entry.name == "assets/source.pdf") {
                        // Never slurp the PDF into memory: stream it to disk (or skip it).
                        if (pdfDir != null) {
                            val f = File.createTempFile("src", ".pdf", pdfDir)
                            FileOutputStream(f).use { zis.copyTo(it) }
                            pdfFile = f
                        }
                    } else {
                        entries[entry.name] = zis.readBytes()
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val manifestBytes = entries["manifest.json"] ?: throw XNoteFormatException(NOT_XNOTE)
        val manifest = try {
            JSONObject(String(manifestBytes, Charsets.UTF_8))
        } catch (_: JSONException) {
            throw XNoteFormatException(NOT_XNOTE)
        }
        if (manifest.optString("format") != FORMAT) throw XNoteFormatException(NOT_XNOTE)

        val dpi = manifest.optInt("dpi", PageSize.DEFAULT_DPI)
        val doc = Document(dpi = dpi)

        if (manifest.optBoolean("has_pdf", false)) {
            doc.pdfFile = pdfFile
        } else {
            pdfFile?.delete() // a stray PDF with no manifest flag: don't leak the temp file
        }

        manifest.optJSONArray("bookmarks")?.let { bm ->
            for (i in 0 until bm.length()) {
                val o = bm.optJSONObject(i) ?: continue
                doc.bookmarks.add(Bookmark(o.optInt("page", 0), o.optString("label", "")))
            }
        }

        val pagesArr = manifest.optJSONArray("pages")
        if (pagesArr == null || pagesArr.length() == 0) {
            doc.pages.add(Page.blank(PageSize.A4, Orientation.PORTRAIT, dpi))
            return doc
        }

        val (fallbackW, fallbackH) = PageSize.A4.pixels(Orientation.PORTRAIT, dpi)
        for (i in 0 until pagesArr.length()) {
            val po = pagesArr.optJSONObject(i) ?: continue
            val page = Page(
                width = po.optDouble("width", fallbackW),
                height = po.optDouble("height", fallbackH),
                pdfPage = if (po.isNull("pdf_page")) null else po.optInt("pdf_page"),
            )
            po.optJSONArray("items")?.let { items ->
                for (j in 0 until items.length()) {
                    val io = items.optJSONObject(j) ?: continue
                    parseItem(io, entries)?.let { page.items.add(it) }
                }
            }
            doc.pages.add(page)
        }
        if (doc.pages.isEmpty()) doc.pages.add(Page.blank(PageSize.A4, Orientation.PORTRAIT, dpi))
        return doc
    }

    // --- item -> json ---

    private fun strokeToJson(s: Stroke): JSONObject {
        // Per-sample time is only meaningful to the speed pen, so it's written as an
        // optional 4th element only then — every other stroke serializes unchanged.
        val withTime = s.config.speedStrength > 0.0
        val samples = JSONArray()
        for (sm in s.samples) {
            val a = JSONArray().put(sm.x).put(sm.y).put(sm.pressure)
            if (withTime) a.put(sm.t)
            samples.put(a)
        }
        val config = JSONObject()
            .put("base_width", s.config.baseWidth)
            .put("pressure_enabled", s.config.pressureEnabled)
            .put("pressure_min_factor", s.config.pressureMinFactor)
            .put("direction_strength", s.config.directionStrength)
            .put("rgba", rgbaToJson(s.config.rgba))
        // New style fields are written only when set, so a plain pen/calligraphy
        // stroke's config is byte-for-byte what older versions wrote.
        if (s.config.speedStrength != 0.0) config.put("speed_strength", s.config.speedStrength)
        if (s.config.taperLength != 0.0) config.put("taper_length", s.config.taperLength)
        if (s.config.neon) {
            config.put("neon", true)
            config.put("neon_strength", s.config.neonStrength)
        }
        // Dash runs matter only to the dashed pen, so a plain stroke's config is unchanged.
        if (s.tool == Tool.DASHED) {
            config.put("dash_length", s.config.dashLength)
            config.put("dash_gap", s.config.dashGap)
        }
        val obj = JSONObject()
            .put("kind", Stroke.KIND)
            .put("tool", s.tool.id)
            .put("config", config)
            .put("samples", samples)
        // The speed pen's gesture-speed scale (zoom ÷ density at pen-down) reconstructs its
        // width on reload; written alongside the per-sample times, only for that tool.
        if (withTime) obj.put("speed_scale", s.speedScale)
        // Straight-line strokes must reload un-smoothed, else the EMA pulls their far end inward.
        if (s.straight) obj.put("straight", true)
        return obj
    }

    private fun imageToJson(item: ImageItem, assetName: String): JSONObject =
        JSONObject()
            .put("kind", ImageItem.KIND)
            .put("asset", assetName)
            .put("rect", JSONArray().put(item.rect.x).put(item.rect.y).put(item.rect.w).put(item.rect.h))

    private fun textToJson(t: TextItem): JSONObject {
        val o = JSONObject()
            .put("kind", TextItem.KIND)
            .put("pos", JSONArray().put(t.pos.x).put(t.pos.y))
            .put("width", t.width)
            .put("text", t.text)
            .put("rgba", rgbaToJson(t.rgba))
            .put("point_size", t.pointSize)
        // Additive fields: written only when set, so older readers stay compatible
        // and notes that use neither serialize exactly as before.
        if (t.height > 0.0) o.put("height", t.height)
        if (t.face != TextItem.DEFAULT_FACE) o.put("font_face", t.face.id)
        return o
    }

    private fun shapeToJson(s: ShapeItem): JSONObject {
        val obj = JSONObject()
            .put("kind", ShapeItem.KIND)
            .put("shape", s.shape.id)
            .put("start", JSONArray().put(s.start.x).put(s.start.y))
            .put("end", JSONArray().put(s.end.x).put(s.end.y))
            .put("stroke_rgba", rgbaToJson(s.strokeRgba))
            .put("stroke_width", s.strokeWidth)
            .put("fill_rgba", s.fillRgba?.let { rgbaToJson(it) } ?: JSONObject.NULL)
        // Glow is additive: a plain shape serializes exactly as before.
        if (s.neon) {
            obj.put("neon", true)
            obj.put("neon_strength", s.neonStrength)
        }
        return obj
    }

    // --- json -> item ---

    private fun parseItem(o: JSONObject, entries: Map<String, ByteArray>): CanvasItem? =
        when (o.optString("kind")) {
            Stroke.KIND -> parseStroke(o)
            ImageItem.KIND -> parseImage(o, entries)
            TextItem.KIND -> parseText(o)
            ShapeItem.KIND -> parseShape(o)
            else -> null
        }

    private fun parseStroke(o: JSONObject): Stroke {
        val tool = Tool.fromId(o.optString("tool")) ?: Tool.PEN
        val c = o.optJSONObject("config")
        val def = ToolConfig()
        val config = ToolConfig(
            baseWidth = c?.optDouble("base_width", def.baseWidth) ?: def.baseWidth,
            pressureEnabled = c?.optBoolean("pressure_enabled", def.pressureEnabled) ?: def.pressureEnabled,
            pressureMinFactor = c?.optDouble("pressure_min_factor", def.pressureMinFactor) ?: def.pressureMinFactor,
            directionStrength = c?.optDouble("direction_strength", def.directionStrength) ?: def.directionStrength,
            rgba = readRgba(c?.optJSONArray("rgba")) ?: def.rgba,
            speedStrength = c?.optDouble("speed_strength", def.speedStrength) ?: def.speedStrength,
            taperLength = c?.optDouble("taper_length", def.taperLength) ?: def.taperLength,
            neon = c?.optBoolean("neon", def.neon) ?: def.neon,
            neonStrength = c?.optDouble("neon_strength", def.neonStrength) ?: def.neonStrength,
            dashLength = c?.optDouble("dash_length", def.dashLength) ?: def.dashLength,
            dashGap = c?.optDouble("dash_gap", def.dashGap) ?: def.dashGap,
        )
        val samples = ArrayList<Sample>()
        o.optJSONArray("samples")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optJSONArray(i) ?: continue
                // 4th element (relative ms) is present only for speed-pen strokes; absent ⇒ 0.
                samples.add(Sample(s.optDouble(0, 0.0), s.optDouble(1, 0.0), s.optDouble(2, 1.0), s.optDouble(3, 0.0)))
            }
        }
        return Stroke(tool, config, samples, o.optDouble("speed_scale", 1.0), o.optBoolean("straight", false))
    }

    private fun parseImage(o: JSONObject, entries: Map<String, ByteArray>): ImageItem? {
        val asset = o.optString("asset").ifEmpty { return null }
        val bytes = entries[asset] ?: return null
        val raster = imageCodec.decode(bytes) ?: return null
        val rect = readRect(o.optJSONArray("rect")) ?: Rect(0.0, 0.0, raster.width.toDouble(), raster.height.toDouble())
        return ImageItem(raster, rect)
    }

    private fun parseText(o: JSONObject): TextItem {
        val pos = readPt(o.optJSONArray("pos")) ?: Pt.ZERO
        return TextItem(
            pos = pos,
            width = o.optDouble("width", TextItem.DEFAULT_WIDTH),
            height = o.optDouble("height", 0.0),
            text = o.optString("text", ""),
            rgba = readRgba(o.optJSONArray("rgba")) ?: TextItem.DEFAULT_COLOR,
            pointSize = o.optDouble("point_size", TextItem.DEFAULT_POINT_SIZE),
            face = FontFace.fromId(o.optString("font_face", "")),
            measurer = textMeasurer,
        )
    }

    private fun parseShape(o: JSONObject): ShapeItem {
        val start = readPt(o.optJSONArray("start")) ?: Pt.ZERO
        val end = readPt(o.optJSONArray("end")) ?: Pt.ZERO
        return ShapeItem(
            shape = ShapeKind.fromId(o.optString("shape")),
            start = start,
            end = end,
            strokeRgba = readRgba(o.optJSONArray("stroke_rgba")) ?: Rgba(0, 230, 118, 255),
            strokeWidth = o.optDouble("stroke_width", 3.0),
            fillRgba = if (o.isNull("fill_rgba")) null else readRgba(o.optJSONArray("fill_rgba")),
            neon = o.optBoolean("neon", false),
            neonStrength = o.optDouble("neon_strength", 0.6),
        )
    }

    // --- json helpers ---

    private fun rgbaToJson(c: Rgba): JSONArray = JSONArray().put(c.r).put(c.g).put(c.b).put(c.a)

    private fun readRgba(arr: JSONArray?): Rgba? {
        if (arr == null || arr.length() < 3) return null
        val list = (0 until arr.length()).map { arr.optInt(it, 0) }
        return Rgba.fromList(list)
    }

    private fun readPt(arr: JSONArray?): Pt? {
        if (arr == null || arr.length() < 2) return null
        return Pt(arr.optDouble(0, 0.0), arr.optDouble(1, 0.0))
    }

    private fun readRect(arr: JSONArray?): Rect? {
        if (arr == null || arr.length() < 4) return null
        return Rect(arr.optDouble(0, 0.0), arr.optDouble(1, 0.0), arr.optDouble(2, 0.0), arr.optDouble(3, 0.0))
    }

    companion object {
        const val FORMAT = "xnote"
        const val VERSION = 1
        private const val NOT_XNOTE = "Not an xnotes document"
    }
}

private fun ZipOutputStream.putDeflated(name: String, data: ByteArray) {
    val entry = ZipEntry(name).apply { method = ZipEntry.DEFLATED }
    putNextEntry(entry)
    write(data)
    closeEntry()
}

private fun ZipOutputStream.putStored(name: String, data: ByteArray) {
    val crc = CRC32().apply { update(data) }
    val entry = ZipEntry(name).apply {
        method = ZipEntry.STORED
        size = data.size.toLong()
        compressedSize = data.size.toLong()
        this.crc = crc.value
    }
    putNextEntry(entry)
    write(data)
    closeEntry()
}

/**
 * Stream [file] into a STORED (uncompressed) zip entry without ever holding it whole in memory.
 * STORED entries need size+CRC up front, so the file is read twice — once to checksum, once to
 * copy — both in small buffers. [isCancelled] is polled per buffer so a long copy can abort by
 * throwing [DocumentCodec.WriteCancelled] (the entry is left unfinished for the caller to discard).
 */
private fun ZipOutputStream.putStored(name: String, file: File, isCancelled: () -> Boolean) {
    val crc = CRC32()
    val buf = ByteArray(64 * 1024)
    FileInputStream(file).use { input ->
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            crc.update(buf, 0, n)
        }
    }
    val size = file.length()
    val entry = ZipEntry(name).apply {
        method = ZipEntry.STORED
        this.size = size
        compressedSize = size
        this.crc = crc.value
    }
    putNextEntry(entry)
    FileInputStream(file).use { input ->
        while (true) {
            if (isCancelled()) throw DocumentCodec.WriteCancelled()
            val n = input.read(buf)
            if (n < 0) break
            write(buf, 0, n)
        }
    }
    closeEntry()
}
