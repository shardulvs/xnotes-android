package com.xnotes.platform

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.Document
import com.xnotes.core.model.ImageData
import com.xnotes.core.model.Page
import com.xnotes.core.model.PageTemplates
import com.xnotes.core.model.marginStrips
import com.xnotes.core.model.resolvedAccentColor
import com.xnotes.core.model.resolvedColors
import com.xnotes.core.model.resolvedParams
import com.xnotes.core.model.resolvedPatternColor
import com.xnotes.core.model.resolvedSpacing
import com.xnotes.core.model.resolvedTemplate
import com.xnotes.core.pal.Renderer
import com.xnotes.core.template.Template
import com.xnotes.core.template.TemplateEval
import com.xnotes.core.template.TemplateKind
import com.xnotes.core.template.TemplateLayoutCache
import com.xnotes.core.template.TemplatePage
import com.xnotes.core.template.TemplatePainter
import com.xnotes.core.template.TemplateValues
import com.xnotes.format.TemplateFormatException
import com.xnotes.format.TemplatePackage
import com.xnotes.format.TemplateReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * The page templates the app knows: the three built-in rulings, the bundled examples, and the
 * user's imports. Built-ins go by their ruling id (`lines`); every other template goes by a key
 * hashed from its bytes, so the copy a note carries in its bundle and the one in the library are
 * the same template exactly when their bytes are. A template with pictures is a package (a ZIP),
 * always held as a file so its pictures never sit in the heap. Imports live in the notes folder's
 * `.xnote/templates/` through a [Store], so they sync with the notes; ones made before that stay
 * readable in `filesDir/templates`. Also paints a page's template, shared by the canvas caches,
 * thumbnails and PDF export.
 */
object TemplateLibrary {

    /** Where a template comes from; [NOTE] is one a note carries that the library does not have. */
    enum class Source { BUILT_IN, BUNDLED, IMPORTED, NOTE }

    /** One template: [text] is its JSON, and [file] the package holding its pictures, when it has any. */
    class Entry(val key: String, val template: Template, val text: String, val source: Source, val file: File? = null)

    /** One file in the [Store]: [handle] names it for [Store.delete]. */
    class StoredFile(val handle: String, val name: String, val open: () -> InputStream?)

    /** The notes folder's `.xnote/templates/`. Every call is IO; a store creates its folder only when first written. */
    interface Store {
        fun list(): List<StoredFile>
        fun write(name: String, from: File): String?
        fun delete(handle: String): Boolean
    }

    private const val ASSET_DIR = "templates"
    private const val EXAMPLES_DIR = "templates/examples"
    private const val EXT = ".xtemplate"
    private const val LEGACY = "legacy:"
    private const val MAX_PICTURE_SIDE = 8192

    @Volatile
    private var legacyDir: File? = null
    @Volatile
    private var cacheDir: File? = null
    @Volatile
    private var assets: AssetManager? = null
    /** When the installed APK last changed, so bundled packages are copied out once per install. */
    @Volatile
    private var apkStamp = 0L
    @Volatile
    private var packagesLoaded = false
    @Volatile
    private var store: Store? = null
    @Volatile
    private var bundled: List<Entry> = emptyList()
    @Volatile
    private var imported: Map<String, Entry> = emptyMap()
    @Volatile
    private var handles: Map<String, String> = emptyMap()
    private val parsed = ConcurrentHashMap<String, Template>()
    /** Package files seen for a key, the notes' own included, so their pictures can be found again. */
    private val packageFiles = ConcurrentHashMap<String, File>()
    private val pictures = ConcurrentHashMap<String, ImageData>()
    private val missingPictures = ConcurrentHashMap.newKeySet<String>()
    private val lock = Any()

    private val eval = TemplateEval(AndroidGlyphOutliner())
    val layouts = TemplateLayoutCache(eval)

    fun init(context: Context) {
        val app = context.applicationContext
        legacyDir = File(app.filesDir, "templates")
        cacheDir = File(app.cacheDir, "template-library").apply { mkdirs() }
        assets = app.assets
        apkStamp = File(app.applicationInfo.sourceDir).lastModified()
        bundled = loadBundled(app.assets)
        readImports(null)
    }

    /** Every template to offer, built-ins first, then the bundled examples, then imports. */
    fun all(): List<Entry> = bundled + imported.values.sortedBy { it.template.name.lowercase() }

    fun entry(key: String): Entry? = bundled.firstOrNull { it.key == key } ?: imported[key]

    /** The key of a plain template that is not built in: a hash of its text. */
    fun keyOf(text: String): String = hex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))

    /** The key of the template in [file]: a hash of its bytes, matching [keyOf] for plain JSON. */
    fun keyOf(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return hex(md.digest())
    }

    private fun hex(d: ByteArray) = d.take(8).joinToString("") { "%02x".format(it) }

    /**
     * The template [key] names for [doc]: the note's own embedded copy first, so a note keeps
     * drawing what it was drawn with, then the library.
     */
    fun template(doc: Document, key: String): Template? {
        if (key == PageTemplates.NONE) return null
        if (!PageTemplates.isBuiltIn(key)) {
            doc.templates[key]?.let { return parse(key, it) }
            doc.templatePackages[key]?.let { return parsePackage(key, it) }
        }
        return entry(key)?.template
    }

    /**
     * Carry [key] in [doc] when it is a library template the note does not have yet. A package is
     * copied into [dir], the note's own asset folder, so removing it from the library never takes
     * it from the note.
     */
    fun embed(doc: Document, key: String?, dir: File?) {
        if (key == null || key == PageTemplates.NONE || PageTemplates.isBuiltIn(key)) return
        if (doc.templates.containsKey(key) || doc.templatePackages.containsKey(key)) return
        val e = entry(key) ?: return
        val pkg = e.file
        if (pkg == null) {
            doc.templates = doc.templates + (key to e.text)
            return
        }
        val d = dir ?: return
        val copy = runCatching { File.createTempFile("tpl", EXT, d).also { pkg.copyTo(it, overwrite = true) } }.getOrNull() ?: return
        doc.templatePackages = doc.templatePackages + (key to copy)
    }

    /** Read the bundled packages once, then the store's and the legacy folder's templates as the imports. IO; call off the main thread. */
    fun reload(newStore: Store?) {
        loadBundledPackages()
        readImports(newStore)
    }

    private fun readImports(newStore: Store?) {
        synchronized(lock) {
            store = newStore
            val found = HashMap<String, Entry>()
            val foundHandles = HashMap<String, String>()
            legacyDir?.listFiles()?.forEach { f ->
                if (!f.isFile || !f.name.endsWith(EXT)) return@forEach
                val e = runCatching { entryOf(f, Source.IMPORTED, move = false) }.getOrNull() ?: return@forEach
                found[e.key] = e
                foundHandles[e.key] = LEGACY + f.path
            }
            for (sf in runCatching { newStore?.list() }.getOrNull().orEmpty()) {
                if (!sf.name.endsWith(EXT)) continue
                val tmp = copyToCache(sf.open) ?: continue
                val e = runCatching { entryOf(tmp, Source.IMPORTED, move = true) }.getOrNull()
                tmp.delete()
                if (e == null) continue
                found[e.key] = e
                foundHandles[e.key] = sf.handle
            }
            imported = found
            handles = foundHandles
        }
    }

    /**
     * Adopt the template in [file], plain or packaged, saving a copy in the store under [name];
     * throws [TemplateFormatException] when it is not one, or when it cannot be saved. IO.
     */
    fun import(file: File, name: String?): Entry {
        val e = entryOf(file, Source.IMPORTED, move = false)
        entry(e.key)?.let { return it }
        synchronized(lock) {
            val s = store
            val handle = if (s != null) {
                s.write(fileName(name ?: e.template.name), e.file ?: file)
                    ?: throw TemplateFormatException("the notes folder could not be written")
            } else {
                val dir = legacyDir ?: throw TemplateFormatException("template storage is unavailable")
                dir.mkdirs()
                val out = File(dir, e.key + EXT)
                file.copyTo(out, overwrite = true)
                LEGACY + out.path
            }
            imported = imported + (e.key to e)
            handles = handles + (e.key to handle)
            parsed[e.key] = e.template
        }
        return e
    }

    /** Adopt plain template [text]; see [import]. IO. */
    fun importText(text: String): Entry {
        val tmp = File.createTempFile("imp", EXT, cacheDir ?: throw TemplateFormatException("template storage is unavailable"))
        try {
            tmp.writeText(text, Charsets.UTF_8)
            return import(tmp, null)
        } finally {
            tmp.delete()
        }
    }

    fun remove(key: String) {
        synchronized(lock) {
            val e = imported[key] ?: return
            val handle = handles[key]
            imported = imported - key
            handles = handles - key
            if (handle != null) {
                if (handle.startsWith(LEGACY)) File(handle.removePrefix(LEGACY)).delete()
                else runCatching { store?.delete(handle) }
            }
            e.file?.delete()
        }
    }

    /**
     * Write a one-picture package of [kind] from [image], the picture filling the page (`fit:
     * "cover"`). PNG, JPEG and WebP are kept as they are; anything else, a picture turned by its
     * EXIF data, or one longer than the spec's 8192 px is decoded and saved again upright. IO.
     */
    fun packageFromImage(image: File, kind: TemplateKind, name: String): File {
        val dir = cacheDir ?: throw TemplateFormatException("template storage is unavailable")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(image.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw TemplateFormatException("not a picture")
        val rotation = runCatching {
            when (android.media.ExifInterface(image.path).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 0)) {
                android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }.getOrDefault(0)
        val ext = when (bounds.outMimeType) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/webp" -> "webp"
            else -> null
        }
        val picture: File
        val pictureExt: String
        if (ext != null && rotation == 0 && maxOf(bounds.outWidth, bounds.outHeight) <= MAX_PICTURE_SIDE) {
            picture = image
            pictureExt = ext
        } else {
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_PICTURE_SIDE) sample *= 2
            val decoded = BitmapFactory.decodeFile(image.path, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: throw TemplateFormatException("not a picture")
            val upright = if (rotation == 0) decoded else Bitmap.createBitmap(
                decoded, 0, 0, decoded.width, decoded.height, Matrix().apply { postRotate(rotation.toFloat()) }, true,
            ).also { if (it !== decoded) decoded.recycle() }
            val png = upright.hasAlpha()
            pictureExt = if (png) "png" else "jpg"
            picture = File.createTempFile("pic", ".$pictureExt", dir)
            FileOutputStream(picture).use { upright.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 92, it) }
            upright.recycle()
        }
        val src = "images/picture.$pictureExt"
        val json = """
            |{
            |  "xtemplate": 1,
            |  "name": ${org.json.JSONObject.quote(name.ifBlank { "Picture" })},
            |  "kind": "${if (kind == TemplateKind.COVER) "cover" else "page"}",
            |  "items": [
            |    { "type": "image", "src": "$src", "fit": "cover" }
            |  ]
            |}
            |""".trimMargin()
        val out = File.createTempFile("pkg", EXT, dir)
        try {
            TemplatePackage.write(out, json, listOf(src to picture))
        } finally {
            if (picture !== image) picture.delete()
        }
        return out
    }

    /** [file] read as a template entry; a package is kept, or [move]d, into the cache under its key. */
    private fun entryOf(file: File, source: Source, move: Boolean): Entry {
        if (TemplatePackage.isPackage(file)) {
            val text = TemplatePackage.readJson(file)
            val t = TemplateReader.read(text)
            val key = keyOf(file)
            val dir = cacheDir ?: throw TemplateFormatException("template storage is unavailable")
            val kept = File(dir, key + EXT)
            if (!kept.exists()) {
                if (!(move && file.renameTo(kept))) file.copyTo(kept, overwrite = true)
            }
            return Entry(key, t, text, source, kept)
        }
        if (file.length() > TemplateReader.MAX_BYTES) throw TemplateFormatException("template is larger than 1 MiB")
        val text = file.readText(Charsets.UTF_8)
        val t = TemplateReader.read(text)
        return Entry(keyOf(text), t, text, source)
    }

    /** A stored file copied into the cache, up to the package limit; null when unreadable or too big. */
    private fun copyToCache(open: () -> InputStream?): File? {
        val dir = cacheDir ?: return null
        val tmp = File.createTempFile("lib", EXT, dir)
        val ok = runCatching {
            open()?.use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > TemplatePackage.MAX_BYTES) return@runCatching false
                        out.write(buf, 0, n)
                    }
                }
                true
            } ?: false
        }.getOrDefault(false)
        if (!ok) tmp.delete()
        return tmp.takeIf { ok }
    }

    /** A file name for [name] in the store: no path separators, with the template extension. */
    private fun fileName(name: String): String {
        val clean = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), " ").trim().take(80).ifEmpty { "Template" }
        return clean + EXT
    }

    private fun parse(key: String, text: String): Template? =
        parsed[key] ?: runCatching { TemplateReader.read(text) }.getOrNull()?.also { parsed[key] = it }

    private fun parsePackage(key: String, file: File): Template? {
        packageFiles[key] = file
        return parsed[key] ?: runCatching { TemplateReader.read(TemplatePackage.readJson(file)) }.getOrNull()?.also { parsed[key] = it }
    }

    /** Template [key]'s pictures by `src`: [doc]'s copy first, then the library's. */
    fun pictures(doc: Document?, key: String): (String) -> ImageData? = { src -> picture(doc, key, src) }

    /** One picture, copied out of its package into the cache the first time; IO then, instant after. */
    private fun picture(doc: Document?, key: String, src: String): ImageData? {
        val id = "$key/$src"
        pictures[id]?.let { if (it.file.exists()) return it }
        val pkg = doc?.templatePackages?.get(key) ?: entry(key)?.file ?: packageFiles[key] ?: return null
        val miss = "${pkg.path}|$src"
        if (miss in missingPictures) return null
        val dir = cacheDir ?: return null
        val out = File(dir, "pic-$key-" + keyOf(src))
        if (!out.exists()) {
            val tmp = File.createTempFile("pic", null, dir)
            if (!TemplatePackage.extract(pkg, src, tmp) || !tmp.renameTo(out)) {
                tmp.delete()
                if (!out.exists()) {
                    missingPictures += miss
                    return null
                }
            }
        }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(out.path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) {
            missingPictures += miss
            return null
        }
        return ImageData(out, o.outWidth, o.outHeight).also { pictures[id] = it }
    }

    /** The built-ins and the plain bundled examples; packages wait for [loadBundledPackages]. */
    private fun loadBundled(am: AssetManager): List<Entry> {
        fun read(path: String) = runCatching { am.open(path).use { String(it.readBytes(), Charsets.UTF_8) } }.getOrNull()
        val out = ArrayList<Entry>()
        for (id in listOf("lines", "dots", "grid")) {
            val text = read("$ASSET_DIR/$id$EXT") ?: continue
            val t = runCatching { TemplateReader.read(text) }.getOrNull() ?: continue
            out += Entry(id, t, text, Source.BUILT_IN)
        }
        out += examples(am).filterNot { isPackageAsset(am, it) }.mapNotNull { name ->
            val text = read("$EXAMPLES_DIR/$name") ?: return@mapNotNull null
            val t = runCatching { TemplateReader.read(text) }.getOrNull() ?: return@mapNotNull null
            Entry(keyOf(text), t, text, Source.BUNDLED)
        }.sortedBy { it.template.name.lowercase() }
        return out
    }

    /** Add the bundled examples that are packages, the first time only. IO. */
    private fun loadBundledPackages() {
        synchronized(lock) {
            if (packagesLoaded) return
            packagesLoaded = true
            val am = assets ?: return
            val dir = cacheDir ?: return
            val found = examples(am).filter { isPackageAsset(am, it) }.mapNotNull { name -> runCatching { bundledPackage(am, dir, name) }.getOrNull() }
            if (found.isEmpty()) return
            val (builtIn, examples) = bundled.partition { it.source == Source.BUILT_IN }
            bundled = builtIn + (examples + found).sortedBy { it.template.name.lowercase() }
        }
    }

    /** Bundled package [name] as a file, copied out of the APK once per install; a marker keeps its key so later launches never hash it. */
    private fun bundledPackage(am: AssetManager, dir: File, name: String): Entry {
        val marker = File(dir, "bundled-$name.key")
        val known = runCatching { marker.readLines() }.getOrNull()
            ?.takeIf { it.size == 2 && it[0] == apkStamp.toString() && File(dir, it[1] + EXT).exists() }?.get(1)
        val key = known ?: copyOut(am, dir, name).also { marker.writeText("$apkStamp\n$it") }
        val file = File(dir, key + EXT)
        val text = TemplatePackage.readJson(file)
        return Entry(key, TemplateReader.read(text), text, Source.BUNDLED, file)
    }

    /** Copy bundled package [name] into [dir] under its key, returning the key. */
    private fun copyOut(am: AssetManager, dir: File, name: String): String {
        val tmp = File.createTempFile("bnd", EXT, dir)
        try {
            am.open("$EXAMPLES_DIR/$name").use { input -> FileOutputStream(tmp).use { input.copyTo(it) } }
            val key = keyOf(tmp)
            val kept = File(dir, key + EXT)
            if (!kept.exists() && !tmp.renameTo(kept)) tmp.copyTo(kept, overwrite = true)
            return key
        } finally {
            tmp.delete()
        }
    }

    private fun examples(am: AssetManager): List<String> =
        (runCatching { am.list(EXAMPLES_DIR) }.getOrNull() ?: emptyArray()).filter { it.endsWith(EXT) }

    private fun isPackageAsset(am: AssetManager, name: String): Boolean = runCatching {
        am.open("$EXAMPLES_DIR/$name").use { input ->
            val head = ByteArray(2)
            var n = 0
            while (n < 2) {
                val r = input.read(head, n, 2 - n)
                if (r < 0) break
                n += r
            }
            TemplatePackage.isPackage(head.copyOf(n))
        }
    }.getOrDefault(false)

    // --- painting ---

    /** [page]'s parameter values from its style chain, lengths converted to template mm. */
    fun values(t: Template, doc: Document, page: Page): TemplateValues {
        val numbers = HashMap(page.resolvedParams(doc))
        val spacing = t.spacingParam
        page.resolvedSpacing(doc)?.let { px -> if (spacing != null) numbers[spacing.name] = px * 25.4 / doc.dpi }
        return TemplateValues(numbers, page.resolvedColors(doc))
    }

    /**
     * Paint [page]'s template into [r] in page-local content px. [cover] is the page's whole paper,
     * margins included; [region] is the part being painted. A page showing an imported PDF gets its
     * template in the margins only, so the PDF itself is never drawn over. [minLine] is the thinnest
     * line in content px, so a thumbnail's ruling stays visible.
     */
    fun paint(r: Renderer, doc: Document, page: Page, cover: Rect, region: Rect, minLine: Double = 0.0) {
        val key = page.resolvedTemplate(doc)
        val t = template(doc, key) ?: return
        val mm = doc.dpi / 25.4
        val content = Rect(0.0, 0.0, page.width, page.height)
        val papers = if (page.pdfPage == null) listOf(cover) else marginStrips(cover, content)
        val values = values(t, doc, page)
        val ink = page.resolvedPatternColor(doc)
        val accent = page.resolvedAccentColor(doc)
        for (paper in papers) {
            if (!paper.intersects(region)) continue
            val tp = TemplatePage(
                page.width / mm, page.height / mm,
                Rect(paper.x / mm, paper.y / mm, paper.w / mm, paper.h / mm),
                ink, accent, values,
            )
            TemplatePainter.paint(r, layouts.layout(t, tp), mm, region, minLine, pictures(doc, key))
        }
    }
}
