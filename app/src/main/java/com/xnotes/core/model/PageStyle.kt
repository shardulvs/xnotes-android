package com.xnotes.core.model

/**
 * A page background ruling. [NONE] is an **explicit** "off" — distinct from a null
 * [PageStyle.pattern], which means *inherit from the level below*. Serialized by [id].
 */
enum class PagePattern(val id: String) {
    NONE("none"),
    LINES("lines"),
    DOTS("dots"),
    GRID("grid");

    companion object {
        fun fromId(id: String?): PagePattern? = entries.firstOrNull { it.id == id }
    }
}

/** The page template keys that need no embedded file: none, and the three built-in rulings. */
object PageTemplates {
    const val NONE = "none"
    val BUILT_IN = setOf(PagePattern.LINES.id, PagePattern.DOTS.id, PagePattern.GRID.id)

    fun isBuiltIn(key: String?) = key != null && key in BUILT_IN
}

/** What a level sets on the template it shows, the Customize page's values; every template keeps its own. */
data class TemplateSettings(
    val patternColor: Rgba? = null,
    val spacing: Double? = null,
    val accentColor: Rgba? = null,
    val params: Map<String, Double>? = null,
    val colors: Map<String, Rgba>? = null,
) {
    val isEmpty: Boolean
        get() = patternColor == null && spacing == null && accentColor == null && params.isNullOrEmpty() && colors.isNullOrEmpty()
}

/**
 * A per-field-inheritable page style override, held on both [Page] (current page) and [Document]
 * ("all pages" of this note). Each field is **independently** nullable: a null field inherits from
 * the next level down — page → document → global preference (page colour) or a built-in default
 * (template/colours/spacing). This per-field nullability is what gives the UI its tri-state controls
 * (Default / explicit value / — for the template — an explicit None). Immutable: callers replace it
 * with a [copy]; sharing the reference across a [Page.deepCopy] is safe.
 *
 * Its [settings] belong to the template the level shows, so they only reach a page showing that
 * same template.
 */
data class PageStyle(
    val pageColor: Rgba? = null,
    /** [PageTemplates.NONE], a built-in ruling id, or an embedded template's key ([Document.templates]). */
    val template: String? = null,
    val patternColor: Rgba? = null,
    /** The template's spacing parameter, in content pixels. */
    val spacing: Double? = null,
    /** The template's `accent` colour. */
    val accentColor: Rgba? = null,
    /** The template's other parameters by name: numbers, and lengths in mm. */
    val params: Map<String, Double>? = null,
    /** The template's colour parameters by name. */
    val colors: Map<String, Rgba>? = null,
) {
    /** The part of this style its template owns. */
    val settings: TemplateSettings
        get() = TemplateSettings(patternColor, spacing, accentColor, params, colors)

    fun withSettings(s: TemplateSettings) =
        copy(patternColor = s.patternColor, spacing = s.spacing, accentColor = s.accentColor, params = s.params, colors = s.colors)

    /**
     * This style set to template [key] (null inherits), dropping its [settings] when the template
     * shown changes. [inherited] is what the level shows with no template of its own.
     */
    fun withTemplate(key: String?, inherited: String): PageStyle =
        if ((template ?: inherited) == (key ?: inherited)) copy(template = key) else copy(template = key).withSettings(TemplateSettings())

    /** True when nothing is overridden — the codec writes no `style` object in this case. */
    val isEmpty: Boolean
        get() = pageColor == null && template == null && patternColor == null && spacing == null &&
            accentColor == null && params.isNullOrEmpty() && colors.isNullOrEmpty()

    companion object {
        const val DEFAULT_SPACING = 64.0 // content px (~10.8 mm at 150 dpi)
        const val MIN_SPACING = 16.0
        const val MAX_SPACING = 200.0
        val DEFAULT_PATTERN_COLOR = Rgba(150, 150, 150, 128) // grey at 50% opacity by default
        val DEFAULT_ACCENT_COLOR = Rgba(214, 72, 72, 150)

        /** Fixed (non-configurable) ruling thickness / dot radius, in content pixels. */
        const val LINE_THICKNESS = 1.5
        const val DOT_RADIUS = 2.0
    }
}

// --- resolution: a page's own override -> its document's ("all pages") override -> a caller default ---
// Shared by the live canvas ([CanvasState]) and PDF export so both honour the same hierarchy, and so
// each resolves against the document actually being drawn/exported (not necessarily the open one).

/** Resolved paper colour, or null to fall back to the theme paper. [global] is the app-wide preference. */
fun Page.resolvedPageColor(doc: Document, global: Rgba?): Rgba? =
    style.pageColor ?: doc.style.pageColor ?: global

fun Page.resolvedTemplate(doc: Document): String =
    style.template ?: doc.style.template ?: PageTemplates.NONE

/** The levels whose template settings apply to this page, nearest first: its own, and the note's while it shows the same template. */
private fun Page.settingLevels(doc: Document): List<PageStyle> =
    if ((doc.style.template ?: PageTemplates.NONE) == resolvedTemplate(doc)) listOf(style, doc.style) else listOf(style)

fun Page.resolvedPatternColor(doc: Document): Rgba =
    settingLevels(doc).firstNotNullOfOrNull { it.patternColor } ?: PageStyle.DEFAULT_PATTERN_COLOR

fun Page.resolvedAccentColor(doc: Document): Rgba =
    settingLevels(doc).firstNotNullOfOrNull { it.accentColor } ?: PageStyle.DEFAULT_ACCENT_COLOR

/** The template's spacing in content px, or null to use the template's own default. */
fun Page.resolvedSpacing(doc: Document): Double? = settingLevels(doc).firstNotNullOfOrNull { it.spacing }

/** The template's parameter values set anywhere in the chain, nearest level winning. */
fun Page.resolvedParams(doc: Document): Map<String, Double> {
    val out = HashMap<String, Double>()
    for (s in settingLevels(doc).asReversed()) s.params?.let { out.putAll(it) }
    return out
}

fun Page.resolvedColors(doc: Document): Map<String, Rgba> {
    val out = HashMap<String, Rgba>()
    for (s in settingLevels(doc).asReversed()) s.colors?.let { out.putAll(it) }
    return out
}

/**
 * Copy into each page what older notes let it borrow from the note's style across templates: the
 * colours always, the spacing and parameters between built-in rulings or from no template at all.
 */
fun Document.keepBorrowedTemplateSettings() {
    val noteKey = style.template ?: PageTemplates.NONE
    for (page in pages) {
        val key = page.style.template ?: continue
        if (key == PageTemplates.NONE || key == noteKey) continue
        var s = page.style
        if (s.patternColor == null) s = s.copy(patternColor = style.patternColor)
        if (s.accentColor == null) s = s.copy(accentColor = style.accentColor)
        if (style.template == null || (PageTemplates.isBuiltIn(key) && PageTemplates.isBuiltIn(noteKey))) {
            if (s.spacing == null) s = s.copy(spacing = style.spacing)
            style.params?.let { s = s.copy(params = it + (s.params ?: emptyMap())) }
            style.colors?.let { s = s.copy(colors = it + (s.colors ?: emptyMap())) }
        }
        page.style = s
    }
}
