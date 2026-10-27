package com.xnotes.platform

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Paint
import android.graphics.Typeface
import com.xnotes.R
import com.xnotes.core.pal.FontFace
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves [FontFace] ids to concrete typefaces. The four generic tokens map to
 * system fonts; family names map to the fonts bundled under `assets/fonts/`
 * (an upright and a true-italic file per family; bold comes from the `wght`
 * axis of variable families or the bold instances of static ones); unknown ids
 * fall back to the sans face so notes referencing missing fonts still render.
 * Call [init] once before any canvas work. Thread-safe: paints are built on
 * background cache threads.
 */
object FontCatalog {

    /** A bundled family: [face].id is the display name, [slug] the asset folder. */
    class Family(
        val face: FontFace,
        val slug: String,
        val mono: Boolean,
        val variable: Boolean,
        val hasItalic: Boolean = true,
    )

    /** What a face resolves to; fake flags are paint-level bold/slant synthesis. */
    class Resolved(val typeface: Typeface, val fakeBold: Boolean, val fakeItalic: Boolean)

    /** One pickable font for the UI dropdowns, generic tokens included. */
    class Choice(val face: FontFace, val label: String, val mono: Boolean)

    private val BUNDLED = listOf(
        Family(FontFace("Fira Sans"), "fira-sans", mono = false, variable = false),
        Family(FontFace("Inter"), "inter", mono = false, variable = true),
        Family(FontFace("Lato"), "lato", mono = false, variable = false),
        Family(FontFace("Montserrat"), "montserrat", mono = false, variable = true),
        Family(FontFace("Nunito"), "nunito", mono = false, variable = true),
        Family(FontFace("Open Sans"), "open-sans", mono = false, variable = true),
        Family(FontFace("Poppins"), "poppins", mono = false, variable = false),
        Family(FontFace("Raleway"), "raleway", mono = false, variable = true),
        Family(FontFace("Roboto"), "roboto", mono = false, variable = true),
        Family(FontFace("Source Sans 3"), "source-sans-3", mono = false, variable = true),
        Family(FontFace("Ubuntu"), "ubuntu", mono = false, variable = false),
        Family(FontFace("Lora"), "lora", mono = false, variable = true),
        Family(FontFace("Playfair Display"), "playfair-display", mono = false, variable = true),
        Family(FontFace("Fira Code"), "fira-code", mono = true, variable = true, hasItalic = false),
        Family(FontFace("IBM Plex Mono"), "ibm-plex-mono", mono = true, variable = false),
        Family(FontFace("JetBrains Mono"), "jetbrains-mono", mono = true, variable = true),
        Family(FontFace("Roboto Mono"), "roboto-mono", mono = true, variable = true),
        Family(FontFace("Source Code Pro"), "source-code-pro", mono = true, variable = true),
        Family(FontFace("Ubuntu Mono"), "ubuntu-mono", mono = true, variable = false),
    )

    /** A user-imported font: one file, [mono] encoded in its name (`Name.mono.ttf`). */
    class CustomFont(val face: FontFace, val file: File, val mono: Boolean)

    private val byId = BUNDLED.associateBy { it.face.id }
    private val hand: Typeface = Typeface.create("cursive", Typeface.NORMAL)

    @Volatile
    private var assets: AssetManager? = null
    @Volatile
    private var customDir: File? = null
    private val custom = ConcurrentHashMap<String, CustomFont>()
    private val cache = ConcurrentHashMap<String, Resolved>()

    fun init(context: Context) {
        assets = context.applicationContext.assets
        customDir = File(context.applicationContext.filesDir, "fonts")
        reloadCustom()
    }

    /** The pickable fonts, generic tokens first, then bundled, then imported. */
    fun choices(): List<Choice> = buildList {
        add(Choice(FontFace.SANS, "Sans", mono = false))
        add(Choice(FontFace.SERIF, "Serif", mono = false))
        add(Choice(FontFace.MONO, "Mono", mono = true))
        add(Choice(FontFace.HAND, "Hand", mono = false))
        for (f in BUNDLED) add(Choice(f.face, f.face.id, f.mono))
        for (c in custom.values.sortedBy { it.face.id.lowercase() }) add(Choice(c.face, c.face.id, c.mono))
    }

    /** The files of the user's imported fonts, for a settings export to carry. */
    fun customFontFiles(): List<File> = custom.values.map { it.file }.filter { it.isFile }

    fun customFonts(): List<Choice> =
        custom.values.sortedBy { it.face.id.lowercase() }.map { Choice(it.face, it.face.id, it.mono) }

    fun label(face: FontFace): String = when (face) {
        FontFace.SANS -> "Sans"
        FontFace.SERIF -> "Serif"
        FontFace.MONO -> "Mono"
        FontFace.HAND -> "Hand"
        else -> face.id
    }

    fun resolve(face: FontFace, bold: Boolean, italic: Boolean): Resolved {
        generic(face)?.let { return Resolved(Typeface.create(it, style(bold, italic)), false, false) }
        return cache.getOrPut("${face.id}/$bold/$italic") {
            val am = assets
            val family = byId[face.id]
            val fallback = { Resolved(Typeface.create(Typeface.SANS_SERIF, style(bold, italic)), false, false) }
            when {
                family != null && am != null ->
                    runCatching { loadBundled(family, bold, italic, am) }.getOrElse { fallback() }
                else -> custom[face.id]?.let { c ->
                    // Single-file families: the framework synthesizes bold/italic.
                    runCatching {
                        Resolved(Typeface.create(Typeface.createFromFile(c.file), style(bold, italic)), false, false)
                    }.getOrElse { fallback() }
                } ?: fallback()
            }
        }
    }

    // --- user-imported fonts (filesDir/fonts; the file name carries family + mono) ---

    private fun reloadCustom() {
        custom.clear()
        val files = customDir?.listFiles() ?: return
        for (f in files) {
            if (!f.isFile || !f.name.endsWith(".ttf")) continue
            val mono = f.name.endsWith(".mono.ttf")
            val name = f.name.removeSuffix(".ttf").removeSuffix(".mono")
            if (name.isNotEmpty()) custom[name] = CustomFont(FontFace(name), f, mono)
        }
    }

    /**
     * Adopt [bytes] as a user font. The family name comes from the font's name
     * table (falling back to [sourceName]); monospace is detected by measuring.
     * Returns the new face, or a human-readable problem.
     */
    fun importFont(context: Context, bytes: ByteArray, sourceName: String?): Result<FontFace> {
        val dir = customDir ?: return Result.failure(IllegalStateException(context.getString(R.string.font_err_unavailable)))
        val fallback = sourceName?.substringBeforeLast('.')?.trim().orEmpty()
        val name = sanitizeFamily(parseFamilyName(bytes) ?: fallback)
            ?: return Result.failure(IllegalArgumentException(context.getString(R.string.font_err_unnamed)))
        if (name.lowercase() in listOf("sans", "serif", "mono", "hand", "default") || byId.containsKey(name)) {
            return Result.failure(IllegalArgumentException(context.getString(R.string.font_err_built_in, name)))
        }
        dir.mkdirs()
        val tmp = File(dir, ".import.tmp")
        tmp.writeBytes(bytes)
        val tf = runCatching { Typeface.Builder(tmp).build() }.getOrNull()
        if (tf == null) {
            tmp.delete()
            return Result.failure(IllegalArgumentException(context.getString(R.string.font_err_unreadable)))
        }
        val mono = looksMonospace(tf)
        custom[name]?.file?.delete() // re-import replaces (the mono marker may differ)
        val dest = File(dir, if (mono) "$name.mono.ttf" else "$name.ttf")
        if (!tmp.renameTo(dest)) {
            dest.writeBytes(bytes)
            tmp.delete()
        }
        val face = FontFace(name)
        custom[name] = CustomFont(face, dest, mono)
        cache.clear() // the id may have been resolving to the sans fallback
        return Result.success(face)
    }

    fun removeCustomFont(face: FontFace): Boolean {
        val c = custom.remove(face.id) ?: return false
        c.file.delete()
        cache.clear()
        return true
    }

    /** Path-hostile characters stripped; null when nothing usable remains. */
    private fun sanitizeFamily(raw: String?): String? {
        val cleaned = raw.orEmpty()
            .replace(Regex("[/\\\\\\x00-\\x1f]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return cleaned.take(64).trim().ifEmpty { null }
    }

    private fun looksMonospace(tf: Typeface): Boolean {
        val paint = Paint().apply { typeface = tf; textSize = 100f }
        val widths = "ilWM. ".map { paint.measureText(it.toString()) }
        return widths.all { kotlin.math.abs(it - widths[0]) < 0.5f }
    }

    /**
     * The typographic (id 16) or plain (id 1) family name from an sfnt name
     * table; TTF, OTF and the first face of a TTC all work. Null on anything
     * unparsable, then the file name is used instead.
     */
    private fun parseFamilyName(bytes: ByteArray): String? = runCatching {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        var base = 0
        if (buf.getInt(0) == 0x74746366) base = buf.getInt(12) // 'ttcf': first face
        val numTables = buf.getShort(base + 4).toInt() and 0xFFFF
        var nameTable = -1
        for (i in 0 until numTables) {
            val rec = base + 12 + i * 16
            if (buf.getInt(rec) == 0x6E616D65) { // 'name'
                nameTable = buf.getInt(rec + 8)
                break
            }
        }
        if (nameTable < 0) return@runCatching null
        val count = buf.getShort(nameTable + 2).toInt() and 0xFFFF
        val strings = nameTable + (buf.getShort(nameTable + 4).toInt() and 0xFFFF)
        var best: String? = null
        var bestScore = -1
        for (i in 0 until count) {
            val rec = nameTable + 6 + i * 12
            val platform = buf.getShort(rec).toInt() and 0xFFFF
            val nameId = buf.getShort(rec + 6).toInt() and 0xFFFF
            if (nameId != 16 && nameId != 1) continue
            val len = buf.getShort(rec + 8).toInt() and 0xFFFF
            val off = strings + (buf.getShort(rec + 10).toInt() and 0xFFFF)
            if (off + len > bytes.size || len == 0) continue
            val value = when (platform) {
                0, 3 -> String(bytes, off, len, Charsets.UTF_16BE)
                1 -> String(bytes, off, len, Charsets.ISO_8859_1)
                else -> continue
            }
            // Prefer the typographic name, and Windows/Unicode entries within each id.
            val score = (if (nameId == 16) 2 else 0) + (if (platform == 3) 1 else 0)
            if (score > bestScore && value.isNotBlank()) {
                best = value
                bestScore = score
            }
        }
        best
    }.getOrNull()

    private fun generic(face: FontFace): Typeface? = when (face) {
        FontFace.SANS -> Typeface.SANS_SERIF
        FontFace.SERIF -> Typeface.SERIF
        FontFace.MONO -> Typeface.MONOSPACE
        FontFace.HAND -> hand
        else -> null
    }

    private fun style(bold: Boolean, italic: Boolean): Int = when {
        bold && italic -> Typeface.BOLD_ITALIC
        bold -> Typeface.BOLD
        italic -> Typeface.ITALIC
        else -> Typeface.NORMAL
    }

    private fun loadBundled(f: Family, bold: Boolean, italic: Boolean, am: AssetManager): Resolved {
        val dir = "fonts/${f.slug}"
        val slantFake = italic && !f.hasItalic
        if (f.variable) {
            val path = "$dir/${if (italic && f.hasItalic) "italic" else "regular"}.ttf"
            val tf = if (bold) {
                Typeface.Builder(am, path).setFontVariationSettings("'wght' 700").build()
            } else {
                Typeface.Builder(am, path).build()
            }
            return if (tf != null) {
                Resolved(tf, fakeBold = false, fakeItalic = slantFake)
            } else {
                Resolved(Typeface.createFromAsset(am, path), fakeBold = bold, fakeItalic = slantFake)
            }
        }
        val name = when {
            bold && italic -> "bolditalic"
            bold -> "bold"
            italic -> "italic"
            else -> "regular"
        }
        return Resolved(Typeface.createFromAsset(am, "$dir/$name.ttf"), false, fakeItalic = slantFake)
    }
}
