package com.xnotes.core.model

/** What the Styles popup remembers while open: each level's settings for every template it showed. */
class TemplateMemory {
    private val note = HashMap<String, TemplateSettings>()
    private val perPage = HashMap<Page, HashMap<String, TemplateSettings>>()

    /** A change to the note's style, and to the own styles of the pages that follow it. */
    class NoteChange(val style: PageStyle, val pages: Map<Page, PageStyle>)

    /** [doc]'s style set to template [key], each template's settings kept and restored, the following pages' too. */
    fun pickForNote(doc: Document, key: String): NoteChange {
        val from = doc.style.template ?: PageTemplates.NONE
        if (from == key) return NoteChange(doc.style.copy(template = key), emptyMap())
        return NoteChange(doc.style.withTemplate(key, PageTemplates.NONE, note), followers(doc, from, key))
    }

    /** [doc]'s style back to all Default, forgetting everything; the pages following the note lose their settings. */
    fun resetNote(doc: Document): NoteChange {
        note.clear()
        perPage.clear()
        val pages = doc.pages.filter { it.style.template == null && !it.style.settings.isEmpty }
        return NoteChange(PageStyle(), pages.associateWith { it.style.withSettings(TemplateSettings()) })
    }

    /** [page]'s style set to template [key], null following the note, keeping and restoring its settings likewise. */
    fun pickForPage(doc: Document, page: Page, key: String?): PageStyle =
        page.style.withTemplate(key, doc.style.template ?: PageTemplates.NONE, perPage.getOrPut(page) { HashMap() })

    /** What the note, or [page] when given, last had for template [key] while it showed another. */
    fun recall(page: Page?, key: String): TemplateSettings? = (if (page == null) note else perPage[page])?.get(key)

    private fun followers(doc: Document, from: String, to: String): Map<Page, PageStyle> {
        val out = LinkedHashMap<Page, PageStyle>()
        for (p in doc.pages) {
            if (p.style.template != null) continue
            val own = p.style.settings
            if (own.isEmpty && p !in perPage) continue
            val next = perPage.getOrPut(p) { HashMap() }.swap(from, own, to)
            if (next != own) out[p] = p.style.withSettings(next)
        }
        return out
    }
}

/** [PageStyle.withTemplate], putting the left template's settings in [kept] and taking back [kept]'s for the new one. */
fun PageStyle.withTemplate(key: String?, inherited: String, kept: MutableMap<String, TemplateSettings>): PageStyle {
    val from = template ?: inherited
    val to = key ?: inherited
    if (from == to) return copy(template = key)
    return copy(template = key).withSettings(kept.swap(from, settings, to))
}

/** Keep [s] as [from]'s settings, and hand back what is kept for [to]. */
private fun MutableMap<String, TemplateSettings>.swap(from: String, s: TemplateSettings, to: String): TemplateSettings {
    if (s.isEmpty) remove(from) else this[from] = s
    return this[to] ?: TemplateSettings()
}
