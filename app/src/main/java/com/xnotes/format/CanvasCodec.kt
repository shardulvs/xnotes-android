package com.xnotes.format

import com.xnotes.core.geometry.Pt
import com.xnotes.core.geometry.Rect
import com.xnotes.core.infinite.CanvasBackground
import com.xnotes.core.infinite.InfiniteDocument
import com.xnotes.core.infinite.Waypoint
import com.xnotes.core.model.CanvasItem
import com.xnotes.core.model.ConnectorEnd
import com.xnotes.core.model.ConnectorItem
import com.xnotes.core.model.FunctionSpec
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.ImageItem
import com.xnotes.core.model.PagePattern
import com.xnotes.core.model.PageSize
import com.xnotes.core.model.PageStyle
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.Routing
import com.xnotes.core.model.ShapeItem
import com.xnotes.core.model.ShapeLabel
import com.xnotes.core.model.Stroke
import com.xnotes.core.model.TextItem
import com.xnotes.core.model.TextStyle
import com.xnotes.core.pal.FontFace
import com.xnotes.core.pal.ImageCodec
import com.xnotes.core.pal.TextMeasurer
import com.xnotes.core.stroke.Sample
import com.xnotes.core.shapes.Axis
import com.xnotes.core.shapes.Elbow
import com.xnotes.core.shapes.FreePath
import com.xnotes.core.shapes.Head
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import com.xnotes.core.tools.ToolDefaults
import com.xnotes.core.util.Svg
import com.xnotes.core.vector.PathFlattener
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Thrown when a file is not a valid `.xcanvas` bundle. */
class XCanvasFormatException(message: String) : Exception(message)

/**
 * Reads and writes the `.xcanvas` bundle: the same shape as `.xnote` (a ZIP with a deflated
 * `manifest.json` plus stored binary assets under `assets/`), but with no page array. The manifest
 * holds one flat item list in content space plus the canvas metadata a page has no place for: the
 * background ruling, the view the canvas was left at, and the named waypoints.
 *
 * This is a sibling of [DocumentCodec], not a mode of it. The two document models are different
 * enough that one codec serving both would branch in every method, and `DocumentCodec`'s emitted
 * bytes are pinned by a test, so its writers must not be refactored into shared helpers. The item
 * serialization here is deliberately identical to `.xnote`'s, so ink written by either format reads
 * the same way and a converter would be a straight copy.
 *
 * Loading is forgiving, exactly like `.xnote`: unknown item kinds are skipped, missing fields take
 * model defaults, and new optional fields are written only when set so older readers stay
 * compatible. Strokes stay editable vector samples; nothing is flattened.
 */
class CanvasCodec(private val imageCodec: ImageCodec, private val textMeasurer: TextMeasurer) {

    /** Thrown out of [write] when [isCancelled] turns true mid-copy, so the caller can discard the partial file. */
    class WriteCancelled : Exception()

    fun write(doc: InfiniteDocument, out: OutputStream, isCancelled: () -> Boolean = { false }) {
        // Named up front by the same walk the manifest makes, so the entries can go in before it.
        val assets = imageAssets(doc)
        ZipOutputStream(out).use { zos ->
            // ALWAYS LEVEL 1, for the reasons spelled out at the same line in [DocumentCodec.write].
            // Do not put it back to the default 6 to save disk. Speed wins here, always.
            zos.setLevel(java.util.zip.Deflater.BEST_SPEED)
            // Assets first and the manifest LAST, matching [DocumentCodec.write]: nothing behind
            // the manifest means it can be replaced in place later without moving the assets.
            // Each image streams straight from its temp file into the bundle, never as a byte[].
            for ((name, file) in assets) zos.putStored(name, file, isCancelled)
            // The manifest streams straight into the deflater, so a dense canvas's JSON is never
            // materialized as a DOM, a String, or a byte[].
            zos.putNextEntry(ZipEntry("manifest.json").apply { method = ZipEntry.DEFLATED })
            val w = java.io.BufferedWriter(java.io.OutputStreamWriter(zos, Charsets.UTF_8), 32 * 1024)
            writeManifest(JsonWrite(w), doc, assets)
            w.flush()
            zos.closeEntry()
        }
    }

    // --- model -> streaming json ---

    /**
     * The image entries a canvas needs, named in the order the manifest mentions them. The manifest
     * walks the same items and takes the names from this list positionally, so there is one naming
     * walk rather than two that could drift apart.
     */
    private fun imageAssets(doc: InfiniteDocument): List<Pair<String, File>> {
        val out = ArrayList<Pair<String, File>>()
        for (item in doc.items) {
            if (item !is ImageItem) continue
            // Readers match assets by manifest name (any extension); .svg keeps the bundle honest
            // and older readers skip the item they can't decode.
            val ext = if (Svg.isSvgFile(item.image.file)) "svg" else "png"
            out.add("assets/image-%03d.%s".format(out.size, ext) to item.image.file)
        }
        return out
    }

    private fun writeManifest(j: JsonWrite, doc: InfiniteDocument, assets: List<Pair<String, File>>) {
        j.beginObject()
        j.name("format").value(FORMAT)
        j.name("version").value(VERSION)
        j.name("writer").value(WRITER)
        doc.created?.let { j.name("created").value(java.time.Instant.ofEpochMilli(it).toString()) }
        j.name("dpi").value(doc.dpi)
        writeBackground(j, doc.background)
        // The last view and the waypoints are written only when there is something to say.
        doc.lastView?.let {
            j.name("view")
            writeView(j, it)
        }
        if (doc.waypoints.isNotEmpty()) {
            j.name("waypoints").beginArray()
            for (wp in doc.waypoints) writeWaypoint(j, wp)
            j.endArray()
        }
        j.name("items").beginArray()
        val nextAsset = intArrayOf(0)
        val ids = GlueIds.of(doc.items)
        for (item in doc.items) writeItem(j, item, assets, nextAsset, ids)
        j.endArray()
        j.endObject()
    }

    private fun writeItem(
        j: JsonWrite,
        item: CanvasItem,
        assets: List<Pair<String, File>>,
        nextAsset: IntArray,
        ids: Map<CanvasItem, Int>,
    ) {
        when (item) {
            is Stroke -> writeStroke(j, item)
            is ImageItem -> {
                // The name was decided by [imageAssets]' walk of these same items; taking it from
                // there keeps the entry and the manifest agreeing by construction.
                assets.getOrNull(nextAsset[0]++)?.let { writeImage(j, item, it.first, ids[item]) }
            }
            is ShapeItem -> writeShape(j, item, ids[item])
            is ConnectorItem -> writeConnector(j, item, ids)
            else -> {} // text and any unrecognized kind: not written, the canvas has none
        }
    }

    /**
     * The background ruling. Written in full rather than as a sparse override set: unlike a page,
     * a canvas has no level below it to inherit from, so every field carries a real value and
     * spelling them out keeps a reopened canvas immune to a change of defaults later.
     */
    private fun writeBackground(j: JsonWrite, bg: CanvasBackground) {
        j.name("background").beginObject()
        j.name("pattern").value(bg.pattern.id)
        j.name("pattern_color")
        writeRgba(j, bg.patternColor)
        j.name("spacing").value(bg.spacing)
        // Additive: written only when the canvas overrides the theme paper.
        bg.paperColor?.let {
            j.name("paper_color")
            writeRgba(j, it)
        }
        j.endObject()
    }

    private fun writeView(j: JsonWrite, v: Waypoint) {
        j.beginObject()
        j.name("cx").value(v.cx)
        j.name("cy").value(v.cy)
        j.name("zoom").value(v.zoom)
        j.endObject()
    }

    private fun writeWaypoint(j: JsonWrite, w: Waypoint) {
        j.beginObject()
        j.name("name").value(w.name)
        j.name("cx").value(w.cx)
        j.name("cy").value(w.cy)
        j.name("zoom").value(w.zoom)
        j.endObject()
    }

    private fun writeStroke(j: JsonWrite, s: Stroke) {
        // Per-sample time is only meaningful to the speed pen, so it's written as an optional
        // 4th element only then; every other stroke serializes without it.
        val withTime = s.config.speedStrength > 0.0
        j.beginObject()
        j.name("kind").value(Stroke.KIND)
        j.name("tool").value(s.tool.id)
        j.name("config").beginObject()
        j.name("base_width").value(s.config.baseWidth)
        j.name("pressure_enabled").value(s.config.pressureEnabled)
        j.name("pressure_min_factor").value(s.config.pressureMinFactor)
        j.name("direction_strength").value(s.config.directionStrength)
        j.name("rgba")
        writeRgba(j, s.config.rgba)
        // New style fields are written only when set, so a plain pen stroke's config stays minimal.
        if (s.config.speedStrength != 0.0) j.name("speed_strength").value(s.config.speedStrength)
        if (s.config.taperEnabled) {
            j.name("taper_enabled").value(true)
            j.name("taper_min_factor").value(s.config.taperMinFactor)
        }
        if (s.config.neon) {
            j.name("neon").value(true)
            j.name("neon_strength").value(s.config.neonStrength)
        }
        if (s.tool == Tool.DASHED) {
            j.name("dash_length").value(s.config.dashLength)
            j.name("dash_gap").value(s.config.dashGap)
        }
        if (s.tool == Tool.HIGHLIGHTER) {
            j.name("highlighter_alpha").value(s.config.highlighterAlpha)
            if (s.config.highlighterInverse) j.name("highlighter_inverse").value(true)
        }
        j.endObject()
        // Samples are almost all of a dense manifest's bytes, so they go out through [JsonWrite.samplePoint], which rounds them: 0.01
        // content px and 0.001 pressure are far below anything visible. Rounding is idempotent,
        // so re-saving an untouched canvas stays byte-stable.
        j.name("samples").beginArray()
        for (sm in s.samples) j.samplePoint(sm.x, sm.y, sm.pressure, if (withTime) sm.t else null)
        j.endArray()
        if (withTime) j.name("speed_scale").value(s.speedScale)
        // The zoom the stroke was drawn at, as the scale on the ink low-pass lengths, so it
        // re-smooths on load exactly as it did under the pen.
        if (s.smoothScale != 1.0) j.name("smooth_scale").value(s.smoothScale)
        // Straight-line strokes must reload un-smoothed, else the EMA pulls their far end inward.
        if (s.straight) j.name("straight").value(true)
        if (s.locked) j.name("locked").value(true)
        j.endObject()
    }

    private fun writeImage(j: JsonWrite, item: ImageItem, assetName: String, id: Int?) {
        j.beginObject()
        j.name("kind").value(ImageItem.KIND)
        // Only on an item a connector glues to.
        id?.let { j.name("id").value(it) }
        j.name("asset").value(assetName)
        j.name("rect").beginArray().value(item.rect.x).value(item.rect.y).value(item.rect.w).value(item.rect.h).endArray()
        j.name("src_w").value(item.image.width)
        j.name("src_h").value(item.image.height)
        if (item.orientation != 0) j.name("orientation").value(item.orientation)
        if (item.angle != 0.0) j.name("angle").value(item.angle)
        if (item.locked) j.name("locked").value(true)
        j.endObject()
    }

    private fun writeShape(j: JsonWrite, s: ShapeItem, id: Int?) {
        // A shape older readers cannot draw goes down as the polygon they can, its real geometry after.
        val standIn = s.legacyOutline()
        val drawn = standIn?.let { Rect.bounding(it) }
        // Drawn text has no shape an older reader could stand in, so it goes under a kind they skip.
        val glyphs = s.shape == ShapeKind.GLYPHS
        j.beginObject()
        j.name("kind").value(if (glyphs) ShapeItem.GLYPHS_KIND else ShapeItem.KIND)
        id?.let { j.name("id").value(it) }
        j.name("shape").value(s.legacyId())
        j.name("start").beginArray().value(drawn?.left ?: s.start.x).value(drawn?.top ?: s.start.y).endArray()
        j.name("end").beginArray().value(drawn?.right ?: s.end.x).value(drawn?.bottom ?: s.end.y).endArray()
        j.name("stroke_rgba")
        writeRgba(j, s.strokeRgba)
        j.name("stroke_width").value(s.strokeWidth)
        j.name("fill_rgba")
        s.fillRgba?.let { writeRgba(j, it) } ?: j.nullValue()
        // Polygon/polyline carry their vertices (absolute content px); other kinds omit them.
        (standIn ?: s.vertices()?.takeUnless { glyphs })?.let { verts ->
            j.name("points").beginArray()
            for (p in verts) j.beginArray().value(p.x).value(p.y).endArray()
            j.endArray()
        }
        if (standIn != null || glyphs) {
            if (!s.isFree || s.shape == ShapeKind.FUNCTION) j.name("geom").value(s.shape.id)
            val f = s.box
            j.name("rect").beginArray().value(f.x).value(f.y).value(f.w).value(f.h).endArray()
            if (s.rotation != 0.0) j.name("angle").value(s.rotation)
            s.path?.takeIf { s.isFree && (glyphs || !FreePath.isVertexList(it)) }?.let { j.name("path").value(FreePath.toSvg(it)) }
        }
        if (s.n != s.shape.defaultN) j.name("n").value(s.n)
        if (s.headStart != s.shape.defaultHeadStart) j.name("head_start").value(s.headStart.id)
        if (s.headEnd != s.shape.defaultHeadEnd) j.name("head_end").value(s.headEnd.id)
        if (!s.adj.contentEquals(s.shape.defaultAdj)) {
            j.name("adj").beginArray()
            for (v in s.adj) j.value(v)
            j.endArray()
        }
        if (s.neon) {
            j.name("neon").value(true)
            j.name("neon_strength").value(s.neonStrength)
        }
        if (s.dashed) {
            j.name("dashed").value(true)
            j.name("dash_length").value(s.dashLength)
            j.name("dash_gap").value(s.dashGap)
        }
        // A label is additive too: written only on a labelled shape.
        s.label?.takeIf { it.text.isNotEmpty() }?.let { l ->
            j.name("label").beginObject()
            j.name("text").value(l.text)
            j.name("rgba")
            writeRgba(j, l.style.rgba)
            j.name("point_size").value(l.style.pointSize)
            if (l.style.face != TextItem.DEFAULT_FACE) j.name("font_face").value(l.style.face.id)
            if (s.labelAt != 0.5) j.name("at").value(s.labelAt)
            j.endObject()
        }
        s.function?.let {
            j.name("function").value(it.expr)
            j.name("function_from").value(it.from)
            j.name("function_to").value(it.to)
            if (it.yMin != null && it.yMax != null) {
                j.name("function_ymin").value(it.yMin)
                j.name("function_ymax").value(it.yMax)
            }
        }
        if (s.locked) j.name("locked").value(true)
        j.endObject()
    }

    /**
     * A connector goes down as the plain polyline it draws, which older readers show as it is, and
     * then as itself: its routing, its two ends (glued ones naming their item's id and point) and its
     * route. Its style and label are a shape's.
     */
    private fun writeConnector(j: JsonWrite, c: ConnectorItem, ids: Map<CanvasItem, Int>) {
        val line = PathFlattener.flatten(c.body(), STAND_IN_FLATTEN)
        val drawn = Rect.bounding(line)
        j.beginObject()
        j.name("kind").value(ShapeItem.KIND)
        ids[c]?.let { j.name("id").value(it) }
        j.name("shape").value(ShapeKind.POLYLINE.id)
        j.name("start").beginArray().value(drawn.left).value(drawn.top).endArray()
        j.name("end").beginArray().value(drawn.right).value(drawn.bottom).endArray()
        j.name("stroke_rgba")
        writeRgba(j, c.strokeRgba)
        j.name("stroke_width").value(c.strokeWidth)
        j.name("fill_rgba").nullValue()
        j.name("points").beginArray()
        for (p in line) j.beginArray().value(p.x).value(p.y).endArray()
        j.endArray()
        if (c.headStart != Head.NONE) j.name("head_start").value(c.headStart.id)
        if (c.headEnd != Head.NONE) j.name("head_end").value(c.headEnd.id)
        if (c.neon) {
            j.name("neon").value(true)
            j.name("neon_strength").value(c.neonStrength)
        }
        if (c.dashed) {
            j.name("dashed").value(true)
            j.name("dash_length").value(c.dashLength)
            j.name("dash_gap").value(c.dashGap)
        }
        j.name("connector").beginObject()
        j.name("routing").value(c.routing.id)
        j.name("from")
        writeEnd(j, c.start, ids)
        j.name("to")
        writeEnd(j, c.end, ids)
        if (c.routing.orthogonal) {
            j.name("axis").value(if (c.elbow.first == Axis.H) "h" else "v")
            j.name("coords").beginArray()
            for (v in c.elbow.coords) j.value(v)
            j.endArray()
        } else if (c.waypoints.isNotEmpty()) {
            j.name("waypoints").beginArray()
            for (p in c.waypoints) j.beginArray().value(p.x).value(p.y).endArray()
            j.endArray()
        }
        j.endObject()
        c.label?.takeIf { it.text.isNotEmpty() }?.let { l ->
            j.name("label").beginObject()
            j.name("text").value(l.text)
            j.name("rgba")
            writeRgba(j, l.style.rgba)
            j.name("point_size").value(l.style.pointSize)
            if (l.style.face != TextItem.DEFAULT_FACE) j.name("font_face").value(l.style.face.id)
            if (c.labelAt != 0.5) j.name("at").value(c.labelAt)
            j.endObject()
        }
        if (c.locked) j.name("locked").value(true)
        j.endObject()
    }

    /** Where a connector end sits, and what it is glued to when that is in the file too. */
    private fun writeEnd(j: JsonWrite, e: ConnectorEnd, ids: Map<CanvasItem, Int>) {
        j.beginObject()
        j.name("at").beginArray().value(e.at.x).value(e.at.y).endArray()
        e.target?.let { ids[it] }?.let { id ->
            j.name("item").value(id)
            j.name("point").value(e.point)
        }
        j.endObject()
    }

    private fun writeRgba(j: JsonWrite, c: Rgba) {
        j.beginArray().value(c.r).value(c.g).value(c.b).value(c.a).endArray()
    }

    // --- streaming json -> model ---

    /** How many shapes the last [read] took from the fork's own kinds onto upstream's. */
    var forkShapesRead = 0
        private set

    /**
     * Read a `.xcanvas` from [input]. When [imageDir] is non-null the inserted images are streamed
     * out to fresh temp files there (never held in RAM) and the caller owns their lifetime; a null
     * dir skips them, which is what a validation-only read wants.
     */
    fun read(input: InputStream, imageDir: File? = null): InfiniteDocument {
        forkShapesRead = 0
        var manifest: ParsedManifest? = null
        val imageFiles = HashMap<String, File>()
        ZipInputStream(input).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = entry.name
                    if (name == "manifest.json") {
                        // Parsed straight off the zip stream, so a dense canvas's manifest is never
                        // materialized as bytes, a String, or a DOM.
                        if (manifest == null) {
                            manifest = try {
                                parseManifest(JsonPull(InputStreamReader(zis, Charsets.UTF_8)))
                            } catch (_: JsonPullException) {
                                throw XCanvasFormatException(NOT_XCANVAS)
                            }
                        }
                    } else if (name.startsWith("assets/image-")) {
                        if (imageDir != null) {
                            val f = File.createTempFile("img", null, imageDir)
                            FileOutputStream(f).use { zis.copyTo(it) }
                            imageFiles[name] = f
                        }
                    }
                    // Anything else is an asset from a newer version: skipped, never buffered.
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val m = manifest ?: throw XCanvasFormatException(NOT_XCANVAS)
        if (!m.formatOk) throw XCanvasFormatException(NOT_XCANVAS)

        val doc = InfiniteDocument(dpi = m.dpi)
        doc.created = m.created
        doc.background = m.background
        doc.lastView = m.view
        doc.waypoints.addAll(m.waypoints)

        // Image entries stream out of the zip after the manifest, so image items materialize only
        // now that their files exist; the recorded slot restores each one's z-order position.
        val items = ArrayList<CanvasItem>(m.items.size + m.images.size)
        items.addAll(m.items)
        var dropped = 0
        for (spec in m.images) {
            val item = materializeImage(spec, imageFiles)
            if (item == null) {
                dropped++
            } else {
                items.add(spec.index - dropped, item)
                m.glue.named(item, spec.id)
            }
        }
        // Every item is in, so connectors can glue to whatever their ends name.
        m.glue.resolve()
        doc.addAll(items)
        return doc
    }

    /**
     * A canvas's created time, read from [ch] through the zip's central directory straight to the head of
     * the manifest, so no item or image is read. Null when [ch] is not a canvas it can read that way, for
     * [peek] from a stream instead.
     */
    fun peek(ch: java.nio.channels.FileChannel): CanvasPeek? =
        runCatching { ZipTail.readEntry(ch, "manifest.json") { peekManifest(it) } }.getOrNull()

    /** [peek] for a canvas that only comes as a stream. */
    fun peek(input: InputStream): CanvasPeek? = runCatching {
        ZipInputStream(input).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == "manifest.json") return@runCatching peekManifest(zis)
                entry = zis.nextEntry
            }
            null
        }
    }.getOrNull()

    /** The header fields of manifest [json], stopping at the items; null when it isn't a canvas's. */
    fun peekManifest(json: InputStream): CanvasPeek? {
        val p = JsonPull(InputStreamReader(json, Charsets.UTF_8))
        var isCanvas = false
        var created: Long? = null
        p.beginObject()
        while (p.hasNext()) {
            when (p.nextName()) {
                "format" -> isCanvas = stringOr(p, "") == FORMAT
                "created" -> created = stringOrNull(p)?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
                // The writer puts every field the peek wants ahead of the items, so the rest can go unread.
                "items" -> break
                else -> p.skipValue()
            }
        }
        return if (isCanvas) CanvasPeek(created) else null
    }

    private class ParsedManifest {
        var formatOk = false
        var writer = 0
        var created: Long? = null
        var dpi = PageSize.DEFAULT_DPI
        var background = CanvasBackground()
        var view: Waypoint? = null
        val waypoints = ArrayList<Waypoint>()
        val items = ArrayList<CanvasItem>()
        val images = ArrayList<PendingImage>()
        val glue = GlueRead()
    }

    /** An image item parsed before its asset entry has streamed out of the zip. */
    private class PendingImage(
        val index: Int,
        val asset: String,
        val rect: Rect?,
        val srcW: Int,
        val srcH: Int,
        val orientation: Int,
        val angle: Double,
        val locked: Boolean,
        val id: Int?,
    )

    private fun parseManifest(p: JsonPull): ParsedManifest {
        val m = ParsedManifest()
        p.beginObject()
        while (p.hasNext()) {
            when (p.nextName()) {
                "format" -> {
                    if (stringOr(p, "") != FORMAT) throw XCanvasFormatException(NOT_XCANVAS)
                    m.formatOk = true
                }
                "writer" -> m.writer = intOr(p, 0)
                "created" -> m.created = stringOrNull(p)?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
                "dpi" -> m.dpi = intOr(p, PageSize.DEFAULT_DPI)
                "background" -> m.background = parseBackground(p)
                "view" -> m.view = parseWaypoint(p, named = false)
                "waypoints" -> parseWaypoints(p, m.waypoints)
                "items" -> parseItems(p, m.items, m.images, m.glue)
                else -> p.skipValue()
            }
        }
        p.endObject()
        return m
    }

    private fun parseBackground(p: JsonPull): CanvasBackground {
        if (p.peek() != JsonPull.Token.BEGIN_OBJECT) {
            p.skipValue()
            return CanvasBackground()
        }
        val def = CanvasBackground()
        var pattern = def.pattern
        var patternColor = def.patternColor
        var spacing = def.spacing
        var paperColor: Rgba? = null
        p.beginObject()
        while (p.hasNext()) {
            when (p.nextName()) {
                "pattern" -> pattern = PagePattern.fromId(stringOrNull(p)) ?: def.pattern
                "pattern_color" -> patternColor = rgbaOrNull(p) ?: def.patternColor
                "spacing" -> spacing = doubleOr(p, def.spacing)
                "paper_color" -> paperColor = rgbaOrNull(p)
                else -> p.skipValue()
            }
        }
        p.endObject()
        return CanvasBackground(pattern, patternColor, spacing, paperColor)
    }

    private fun parseWaypoints(p: JsonPull, out: MutableList<Waypoint>) {
        if (p.peek() != JsonPull.Token.BEGIN_ARRAY) return p.skipValue()
        p.beginArray()
        while (p.hasNext()) parseWaypoint(p, named = true)?.let { out.add(it) }
        p.endArray()
    }

    /** One saved view. A malformed or non-object entry is skipped rather than failing the load. */
    private fun parseWaypoint(p: JsonPull, named: Boolean): Waypoint? {
        if (p.peek() != JsonPull.Token.BEGIN_OBJECT) {
            p.skipValue()
            return null
        }
        var name = ""
        var cx = 0.0
        var cy = 0.0
        var zoom = 1.0
        p.beginObject()
        while (p.hasNext()) {
            when (p.nextName()) {
                "name" -> name = stringOr(p, "")
                "cx" -> cx = doubleOr(p, 0.0)
                "cy" -> cy = doubleOr(p, 0.0)
                "zoom" -> zoom = doubleOr(p, 1.0)
                else -> p.skipValue()
            }
        }
        p.endObject()
        if (!cx.isFinite() || !cy.isFinite() || !zoom.isFinite() || zoom <= 0.0) return null
        return Waypoint(if (named) Waypoint.sanitizeName(name) else "", cx, cy, zoom)
    }

    private fun parseItems(p: JsonPull, items: MutableList<CanvasItem>, pending: MutableList<PendingImage>, glue: GlueRead) {
        if (p.peek() != JsonPull.Token.BEGIN_ARRAY) return p.skipValue()
        p.beginArray()
        while (p.hasNext()) parseItem(p, items, pending, glue)
        p.endArray()
    }

    /** Union of every kind's fields, so an item parses in one pass whatever its key order. */
    private class ItemScratch {
        var locked = false
        var kind: String? = null
        var id: Int? = null
        var connector: ConnectorScratch? = null
        var tool: String? = null
        var config: ConfigScratch? = null
        var samples: RawSamples? = null
        var speedScale = 1.0
        var smoothScale = 1.0
        var straight = false
        var asset: String? = null
        var rect: Rect? = null
        var srcW = 0
        var srcH = 0
        var orientation = 0
        var angle = 0.0
        var shape: String? = null
        var geom: String? = null
        var path: String? = null
        var adj: DoubleArray? = null
        var n = 0
        var headStart: String? = null
        var headEnd: String? = null
        var label: LabelScratch? = null
        var start: Pt? = null
        var end: Pt? = null
        var strokeRgba: Rgba? = null
        var strokeWidth = 3.0
        var fillRgba: Rgba? = null
        var points: List<Pt>? = null
        var neon = false
        var neonStrength = 0.6
        var dashed = false
        var dashLength = 10.0
        var dashGap = 8.0
        var function: String? = null
        var functionFrom = 0.0
        var functionTo = 0.0
        var functionYMin: Double? = null
        var functionYMax: Double? = null
    }

    /** Stroke config fields as written; null = absent, so defaults resolve exactly as before. */
    private class ConfigScratch {
        var baseWidth: Double? = null
        var pressureEnabled: Boolean? = null
        var pressureMinFactor: Double? = null
        var directionStrength: Double? = null
        var rgba: Rgba? = null
        var speedStrength: Double? = null
        var taperEnabled: Boolean? = null
        var taperMinFactor: Double? = null
        var neon: Boolean? = null
        var neonStrength: Double? = null
        var dashLength: Double? = null
        var dashGap: Double? = null
        var highlighterAlpha: Double? = null
        var highlighterInverse: Boolean? = null
    }

    private fun parseItem(p: JsonPull, items: MutableList<CanvasItem>, pending: MutableList<PendingImage>, glue: GlueRead) {
        if (p.peek() != JsonPull.Token.BEGIN_OBJECT) return p.skipValue()
        val s = ItemScratch()
        p.beginObject()
        while (p.hasNext()) {
            when (p.nextName()) {
                "kind" -> s.kind = stringOr(p, "")
                "id" -> s.id = intOrNull(p)
                "connector" -> s.connector = parseConnector(p)
                "tool" -> s.tool = stringOr(p, "")
                "config" -> s.config = parseConfig(p)
                "samples" -> s.samples = parseSamples(p)
                "speed_scale" -> s.speedScale = doubleOr(p, 1.0)
                "smooth_scale" -> s.smoothScale = doubleOr(p, 1.0)
                "straight" -> s.straight = boolOr(p, false)
                "asset" -> s.asset = stringOr(p, "")
                "rect" -> s.rect = rectOrNull(p)
                "src_w" -> s.srcW = intOr(p, 0)
                "src_h" -> s.srcH = intOr(p, 0)
                "orientation" -> s.orientation = intOr(p, 0)
                "angle" -> s.angle = doubleOr(p, 0.0)
                "shape" -> s.shape = stringOr(p, "")
                "geom" -> s.geom = stringOrNull(p)
                "path" -> s.path = stringOrNull(p)
                "adj" -> s.adj = doublesOrNull(p)
                "n" -> s.n = intOr(p, 0)
                "head_start" -> s.headStart = stringOrNull(p)
                "head_end" -> s.headEnd = stringOrNull(p)
                "label" -> s.label = parseLabel(p)
                "start" -> s.start = ptOrNull(p)
                "end" -> s.end = ptOrNull(p)
                "stroke_rgba" -> s.strokeRgba = rgbaOrNull(p)
                "stroke_width" -> s.strokeWidth = doubleOr(p, 3.0)
                "fill_rgba" -> s.fillRgba = rgbaOrNull(p)
                "points" -> s.points = pointsOrNull(p)
                "neon" -> s.neon = boolOr(p, false)
                "neon_strength" -> s.neonStrength = doubleOr(p, 0.6)
                "dashed" -> s.dashed = boolOr(p, false)
                "dash_length" -> s.dashLength = doubleOr(p, 10.0)
                "dash_gap" -> s.dashGap = doubleOr(p, 8.0)
                "function" -> s.function = stringOr(p, "")
                "function_from" -> s.functionFrom = doubleOr(p, 0.0)
                "function_to" -> s.functionTo = doubleOr(p, 0.0)
                "function_ymin" -> s.functionYMin = doubleOr(p, 0.0)
                "function_ymax" -> s.functionYMax = doubleOr(p, 0.0)
                "locked" -> s.locked = boolOr(p, false)
                else -> p.skipValue()
            }
        }
        p.endObject()
        val before = items.size
        when (s.kind) {
            Stroke.KIND -> items.add(buildStroke(s))
            ImageItem.KIND -> {
                val asset = s.asset
                if (!asset.isNullOrEmpty()) {
                    pending.add(
                        PendingImage(
                            items.size + pending.size, asset, s.rect, s.srcW, s.srcH,
                            s.orientation, s.angle, s.locked, s.id,
                        ),
                    )
                }
            }
            ShapeItem.KIND, ShapeItem.GLYPHS_KIND -> {
                val c = s.connector
                items.add(if (c != null && s.kind == ShapeItem.KIND) buildConnector(s, c, glue) else buildShape(s))
            }
            else -> {} // text and any unrecognized kind: skipped (forgiving)
        }
        if (items.size > before) glue.named(items[before], s.id)
        // Absent on every canvas written before locking existed, which reads back as unlocked.
        if (s.locked && items.size > before) items[before].locked = true
    }

    private fun buildStroke(s: ItemScratch): Stroke {
        val tool = Tool.fromId(s.tool) ?: Tool.PEN
        val c = s.config
        val def = ToolConfig()
        val config = ToolConfig(
            baseWidth = c?.baseWidth ?: def.baseWidth,
            pressureEnabled = c?.pressureEnabled ?: def.pressureEnabled,
            pressureMinFactor = c?.pressureMinFactor ?: def.pressureMinFactor,
            directionStrength = c?.directionStrength ?: def.directionStrength,
            rgba = c?.rgba ?: def.rgba,
            speedStrength = c?.speedStrength ?: def.speedStrength,
            taperEnabled = c?.taperEnabled ?: def.taperEnabled,
            taperMinFactor = c?.taperMinFactor ?: ToolDefaults.LEGACY_TAPER_TIP,
            neon = c?.neon ?: def.neon,
            neonStrength = c?.neonStrength ?: def.neonStrength,
            dashLength = c?.dashLength ?: def.dashLength,
            dashGap = c?.dashGap ?: def.dashGap,
            highlighterAlpha = c?.highlighterAlpha ?: def.highlighterAlpha,
            highlighterInverse = c?.highlighterInverse ?: def.highlighterInverse,
        )
        val stroke = Stroke(tool, config, emptyList(), s.speedScale, s.straight, s.smoothScale)
        s.samples?.let { stroke.setSamples(it.xs, it.ys, it.ps, it.ts, it.n) }
        return stroke
    }

    private fun buildShape(s: ItemScratch): ShapeItem {
        val shape = buildDrawnShape(s)
        // The real geometry behind a stand-in polygon; or else an old file's baked turn.
        val frame = s.rect
        if (frame != null && (s.geom != null || s.angle != 0.0 || s.path != null)) {
            shape.restoreFrame(ShapeKind.fromIdOrNull(s.geom), frame, s.angle, FreePath.fromSvg(s.path))
        } else {
            shape.unbake()
        }
        if (s.n > 0) shape.n = s.n
        s.adj?.let { shape.adj = it }
        s.headStart?.let { shape.headStart = Head.fromId(it) }
        s.headEnd?.let { shape.headEnd = Head.fromId(it) }
        s.label?.takeIf { it.text.isNotEmpty() }?.let { l ->
            shape.label = ShapeLabel(l.text, TextStyle(l.rgba ?: shape.strokeRgba, l.pointSize, FontFace.fromId(l.fontFace)), textMeasurer)
            l.at?.takeIf { it.isFinite() }?.let { shape.labelAt = it.coerceIn(0.0, 1.0) }
        }
        return shape
    }

    private class LabelScratch {
        var text = ""
        var rgba: Rgba? = null
        var pointSize = TextItem.DEFAULT_POINT_SIZE
        var fontFace = ""
        var at: Double? = null
    }

    private fun parseLabel(p: JsonPull): LabelScratch? {
        if (p.peek() != JsonPull.Token.BEGIN_OBJECT) {
            p.skipValue()
            return null
        }
        val l = LabelScratch()
        p.beginObject()
        while (p.hasNext()) {
            when (p.nextName()) {
                "text" -> l.text = stringOr(p, "")
                "rgba" -> l.rgba = rgbaOrNull(p)
                "point_size" -> l.pointSize = doubleOr(p, TextItem.DEFAULT_POINT_SIZE)
                "font_face" -> l.fontFace = stringOr(p, "")
                "at" -> l.at = doubleOrNull(p)
                else -> p.skipValue()
            }
        }
        p.endObject()
        return l
    }

    private class ConnectorScratch {
        var routing: String? = null
        var from: EndRead? = null
        var to: EndRead? = null
        var axis: String? = null
        var coords: DoubleArray? = null
        var waypoints: List<Pt>? = null
    }

    private fun parseConnector(p: JsonPull): ConnectorScratch? {
        if (p.peek() != JsonPull.Token.BEGIN_OBJECT) {
            p.skipValue()
            return null
        }
        val c = ConnectorScratch()
        p.beginObject()
        while (p.hasNext()) {
            when (p.nextName()) {
                "routing" -> c.routing = stringOrNull(p)
                "from" -> c.from = parseEnd(p)
                "to" -> c.to = parseEnd(p)
                "axis" -> c.axis = stringOrNull(p)
                "coords" -> c.coords = doublesOrNull(p)
                "waypoints" -> c.waypoints = anyPoints(p)
                else -> p.skipValue()
            }
        }
        p.endObject()
        return c
    }

    /** Every point of an array, however few: a straight connector may bend just once. */
    private fun anyPoints(p: JsonPull): List<Pt>? {
        if (p.peek() != JsonPull.Token.BEGIN_ARRAY) {
            p.skipValue()
            return null
        }
        val out = ArrayList<Pt>()
        p.beginArray()
        while (p.hasNext()) ptOrNull(p)?.let { out.add(it) }
        p.endArray()
        return out
    }

    private fun parseEnd(p: JsonPull): EndRead? {
        if (p.peek() != JsonPull.Token.BEGIN_OBJECT) {
            p.skipValue()
            return null
        }
        var at: Pt? = null
        var id: Int? = null
        var point = -1
        p.beginObject()
        while (p.hasNext()) {
            when (p.nextName()) {
                "at" -> at = ptOrNull(p)
                "item" -> id = intOrNull(p)
                "point" -> point = intOr(p, -1)
                else -> p.skipValue()
            }
        }
        p.endObject()
        return EndRead(at, id, point)
    }

    /**
     * A connector from its stand-in polyline's fields and its own. Its ends stay loose until every
     * item is read and [glue] can find what they name; a routing this version does not know draws
     * as the line the stand-in runs along.
     */
    private fun buildConnector(s: ItemScratch, cs: ConnectorScratch, glue: GlueRead): ConnectorItem {
        val pts = s.points.orEmpty()
        val from = cs.from?.at ?: pts.firstOrNull() ?: s.start ?: Pt.ZERO
        val to = cs.to?.at ?: pts.lastOrNull() ?: s.end ?: Pt.ZERO
        val known = Routing.fromId(cs.routing)
        val c = ConnectorItem(
            known ?: Routing.STRAIGHT, ConnectorEnd(from), ConnectorEnd(to), s.strokeRgba ?: DEFAULT_SHAPE_STROKE, s.strokeWidth,
            Head.fromId(s.headStart), Head.fromId(s.headEnd), s.dashed, s.dashLength, s.dashGap, s.neon, s.neonStrength,
        )
        val coords = cs.coords?.takeIf { v -> v.all { it.isFinite() } }
        when {
            known == null -> c.waypoints = pts.drop(1).dropLast(1)
            known.orthogonal -> c.elbow = coords?.let { Elbow(if (cs.axis == "v") Axis.V else Axis.H, it) } ?: Elbow.of(pts.ifEmpty { listOf(from, to) })
            else -> c.waypoints = cs.waypoints.orEmpty()
        }
        s.label?.takeIf { it.text.isNotEmpty() }?.let { l ->
            c.label = ShapeLabel(l.text, TextStyle(l.rgba ?: c.strokeRgba, l.pointSize, FontFace.fromId(l.fontFace)), textMeasurer)
            l.at?.takeIf { it.isFinite() }?.let { c.labelAt = it.coerceIn(0.0, 1.0) }
        }
        glue.waiting(c, cs.from, cs.to)
        return c
    }

    private fun buildDrawnShape(s: ItemScratch): ShapeItem {
        val kind = ShapeKind.fromId(s.shape)
        val strokeRgba = s.strokeRgba ?: DEFAULT_SHAPE_STROKE
        val spec = s.function?.let { FunctionSpec(it, s.functionFrom, s.functionTo, s.functionYMin, s.functionYMax) }
        ForkShapes.build(s.shape, s.start, s.end, s.points, s.angle, spec, s.rect, strokeRgba, s.strokeWidth)?.let { shape ->
            forkShapesRead++
            shape.fillRgba = s.fillRgba?.takeIf { shape.shape.isClosed }
            shape.neon = s.neon
            shape.neonStrength = s.neonStrength
            shape.dashed = s.dashed
            shape.dashLength = s.dashLength
            shape.dashGap = s.dashGap
            return shape
        }
        s.points?.let { verts ->
            return ShapeItem.poly(
                kind, verts, strokeRgba, s.strokeWidth, s.fillRgba, s.neon, s.neonStrength,
                s.dashed, s.dashLength, s.dashGap,
            ).also { shape -> shape.function = spec }
        }
        return ShapeItem(
            shape = kind,
            start = s.start ?: Pt.ZERO,
            end = s.end ?: Pt.ZERO,
            strokeRgba = strokeRgba,
            strokeWidth = s.strokeWidth,
            fillRgba = s.fillRgba,
            neon = s.neon,
            neonStrength = s.neonStrength,
            dashed = s.dashed,
            dashLength = s.dashLength,
            dashGap = s.dashGap,
        )
    }

    private fun parseConfig(p: JsonPull): ConfigScratch? {
        if (p.peek() != JsonPull.Token.BEGIN_OBJECT) {
            p.skipValue()
            return null
        }
        val c = ConfigScratch()
        p.beginObject()
        while (p.hasNext()) {
            when (p.nextName()) {
                "base_width" -> c.baseWidth = doubleOrNull(p)
                "pressure_enabled" -> c.pressureEnabled = boolOrNull(p)
                "pressure_min_factor" -> c.pressureMinFactor = doubleOrNull(p)
                "direction_strength" -> c.directionStrength = doubleOrNull(p)
                "rgba" -> c.rgba = rgbaOrNull(p)
                "speed_strength" -> c.speedStrength = doubleOrNull(p)
                "taper_enabled" -> c.taperEnabled = boolOrNull(p)
                "taper_min_factor" -> c.taperMinFactor = doubleOrNull(p)
                "neon" -> c.neon = boolOrNull(p)
                "neon_strength" -> c.neonStrength = doubleOrNull(p)
                "dash_length" -> c.dashLength = doubleOrNull(p)
                "dash_gap" -> c.dashGap = doubleOrNull(p)
                "highlighter_alpha" -> c.highlighterAlpha = doubleOrNull(p)
                "highlighter_inverse" -> c.highlighterInverse = boolOrNull(p)
                else -> p.skipValue()
            }
        }
        p.endObject()
        return c
    }

    private fun parseSamples(p: JsonPull): RawSamples? {
        if (p.peek() != JsonPull.Token.BEGIN_ARRAY) {
            p.skipValue()
            return null
        }
        val out = RawSamples()
        val tuple = DoubleArray(4)
        p.beginArray()
        while (p.hasNext()) {
            if (p.peek() != JsonPull.Token.BEGIN_ARRAY) {
                p.skipValue()
                continue
            }
            // NaN back from [JsonPull.nextSample] means the slot held nothing readable, which is
            // not the same as a zero: pressure then defaults to full, and the 4th element
            // (relative ms, only speed-pen strokes carry it) to none.
            p.nextSample(tuple)
            out.add(
                if (tuple[0].isNaN()) 0.0 else tuple[0],
                if (tuple[1].isNaN()) 0.0 else tuple[1],
                if (tuple[2].isNaN()) 1.0 else tuple[2],
                if (tuple[3].isNaN()) 0.0 else tuple[3],
            )
        }
        p.endArray()
        return out
    }

    private fun materializeImage(spec: PendingImage, imageFiles: Map<String, File>): ImageItem? {
        val file = imageFiles[spec.asset] ?: return null
        var w = spec.srcW
        var h = spec.srcH
        if (w <= 0 || h <= 0) {
            val probed = imageCodec.probeFile(file.path) ?: return null
            w = probed.width
            h = probed.height
        }
        val rect = spec.rect ?: Rect(0.0, 0.0, w.toDouble(), h.toDouble())
        return ImageItem(ImageData(file, w, h), rect, spec.orientation, spec.angle)
            .also { it.locked = spec.locked }
    }

    // --- streaming value helpers (mirroring org.json's forgiving opt* coercions) ---

    private fun doubleOr(p: JsonPull, def: Double): Double = doubleOrNull(p) ?: def

    private fun doubleOrNull(p: JsonPull): Double? = when (p.peek()) {
        JsonPull.Token.NUMBER -> p.nextDouble()
        JsonPull.Token.STRING -> p.nextString().toDoubleOrNull()
        else -> {
            p.skipValue()
            null
        }
    }

    private fun intOr(p: JsonPull, def: Int): Int = intOrNull(p) ?: def

    private fun intOrNull(p: JsonPull): Int? = when (p.peek()) {
        JsonPull.Token.NUMBER -> p.nextInt()
        JsonPull.Token.STRING -> p.nextString().let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() }
        else -> {
            p.skipValue()
            null
        }
    }

    private fun boolOr(p: JsonPull, def: Boolean): Boolean = boolOrNull(p) ?: def

    private fun boolOrNull(p: JsonPull): Boolean? = when (p.peek()) {
        JsonPull.Token.BOOLEAN -> p.nextBoolean()
        JsonPull.Token.STRING -> when (p.nextString().lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
        else -> {
            p.skipValue()
            null
        }
    }

    private fun stringOr(p: JsonPull, def: String): String = stringOrNull(p) ?: def

    private fun stringOrNull(p: JsonPull): String? = when (p.peek()) {
        JsonPull.Token.STRING -> p.nextString()
        else -> {
            p.skipValue()
            null
        }
    }

    private fun rgbaOrNull(p: JsonPull): Rgba? {
        if (p.peek() != JsonPull.Token.BEGIN_ARRAY) {
            p.skipValue()
            return null
        }
        val channels = ArrayList<Int>(4)
        p.beginArray()
        while (p.hasNext()) channels.add(intOr(p, 0))
        p.endArray()
        return Rgba.fromList(channels)
    }

    private fun ptOrNull(p: JsonPull): Pt? {
        if (p.peek() != JsonPull.Token.BEGIN_ARRAY) {
            p.skipValue()
            return null
        }
        var count = 0
        var x = 0.0
        var y = 0.0
        p.beginArray()
        while (p.hasNext()) {
            when (count) {
                0 -> x = doubleOr(p, 0.0)
                1 -> y = doubleOr(p, 0.0)
                else -> p.skipValue()
            }
            count++
        }
        p.endArray()
        return if (count >= 2) Pt(x, y) else null
    }

    private fun rectOrNull(p: JsonPull): Rect? {
        if (p.peek() != JsonPull.Token.BEGIN_ARRAY) {
            p.skipValue()
            return null
        }
        val v = DoubleArray(4)
        var count = 0
        p.beginArray()
        while (p.hasNext()) {
            if (count < 4) v[count] = doubleOr(p, 0.0) else p.skipValue()
            count++
        }
        p.endArray()
        return if (count >= 4) Rect(v[0], v[1], v[2], v[3]) else null
    }

    private fun doublesOrNull(p: JsonPull): DoubleArray? {
        if (p.peek() != JsonPull.Token.BEGIN_ARRAY) {
            p.skipValue()
            return null
        }
        val out = ArrayList<Double>()
        p.beginArray()
        while (p.hasNext()) doubleOrNull(p)?.takeIf { it.isFinite() }?.let { out.add(it) }
        p.endArray()
        return out.toDoubleArray()
    }

    private fun pointsOrNull(p: JsonPull): List<Pt>? {
        if (p.peek() != JsonPull.Token.BEGIN_ARRAY) {
            p.skipValue()
            return null
        }
        val out = ArrayList<Pt>()
        p.beginArray()
        while (p.hasNext()) out.add(ptOrNull(p) ?: FunctionSpec.BREAK)
        p.endArray()
        while (out.isNotEmpty() && out.last().x.isNaN()) out.removeAt(out.size - 1)
        while (out.isNotEmpty() && out.first().x.isNaN()) out.removeAt(0)
        return if (out.count { !it.x.isNaN() } >= 2) out else null
    }

    companion object {
        const val FORMAT = "xcanvas"
        const val VERSION = 1

        /** File extension of the bundle, without the dot. */
        const val EXTENSION = InfiniteDocument.EXTENSION

        /** The com.xnotes versionCode stamped into manifests this build writes. Old readers
         *  ignore the unknown key; new readers use it to date a file's conventions. */
        const val WRITER = 47

        /** Default shape outline colour for a shape whose stroke colour did not survive. */
        private val DEFAULT_SHAPE_STROKE = Rgba(0, 230, 118, 255)

        /** How closely a curved connector's stand-in polyline follows it, in content px. */
        private const val STAND_IN_FLATTEN = 0.25

        private const val NOT_XCANVAS = "Not an xnotes canvas"
    }
}

/** What the explorer reads from a canvas without loading it. */
class CanvasPeek(val created: Long?)

private fun ZipOutputStream.putStored(name: String, file: File, isCancelled: () -> Boolean) {
    val buf = ByteArray(64 * 1024)
    val size = file.length()
    val entry = ZipEntry(name).apply {
        method = ZipEntry.STORED
        this.size = size
        compressedSize = size
        this.crc = AssetCrc.of(file)
    }
    putNextEntry(entry)
    FileInputStream(file).use { input ->
        while (true) {
            if (isCancelled()) throw CanvasCodec.WriteCancelled()
            val n = input.read(buf)
            if (n < 0) break
            write(buf, 0, n)
        }
    }
    closeEntry()
}
