package com.xnotes.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.canvas.PdfColorFilter
import com.xnotes.canvas.ViewOverrides
import com.xnotes.canvas.ViewSettings
import com.xnotes.canvas.ViewingMode
import com.xnotes.core.model.Orientation
import com.xnotes.core.model.PageEdge
import com.xnotes.core.model.PageMargins
import com.xnotes.core.model.PageSize
import com.xnotes.core.model.PageSizeChoice
import com.xnotes.core.model.PageStyle
import com.xnotes.core.model.PageTemplates
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.TemplateMemory
import com.xnotes.core.model.TemplateSettings
import com.xnotes.core.template.Template
import com.xnotes.settings.Preferences
import com.xnotes.settings.SwatchRole
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * The note bar's gear and the panel it opens. The front page holds the page, the zoom and the
 * toggles reached for while writing; its rows open Styles, Margins and View, which keep every
 * control their popups had.
 */

private enum class NotePage(val depth: Int) { HOME(0), STYLES(1), MARGINS(1), VIEW(1), LIMITS(1), CUSTOMIZE(2), FILTERS(2) }

@Composable
internal fun NoteSettingsButton(editor: Editor, fullscreen: Boolean, onToggleFullscreen: () -> Unit, onImportTemplate: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    PrewarmTemplateThumbs(editor, open)
    SettingsButton(open, { open = it }) { NotePanel(editor, fullscreen, onToggleFullscreen, onImportTemplate) }
}

@Composable
private fun NotePanel(editor: Editor, fullscreen: Boolean, onToggleFullscreen: () -> Unit, onImportTemplate: () -> Unit) {
    var page by remember { mutableStateOf(NotePage.HOME) }
    // Each trip in from the front page reads the page as it is now, since the panel can turn pages.
    var visit by remember { mutableIntStateOf(0) }
    val styles = remember(visit) { StylesState(editor) }
    val margins = remember(visit) { MarginsState(editor) }
    val view = remember(visit) { ViewState(editor) }
    fun go(to: NotePage) {
        if (page == NotePage.HOME) visit++
        page = to
    }
    PanelPages(page, { it.depth }) { p ->
        when (p) {
            NotePage.HOME -> HomePage(editor, fullscreen, onToggleFullscreen, ::go)
            NotePage.STYLES -> StylesPage(editor, styles, onImportTemplate, onBack = { page = NotePage.HOME }, onCustomize = { page = NotePage.CUSTOMIZE })
            NotePage.CUSTOMIZE -> CustomizePage(editor, styles) { page = NotePage.STYLES }
            NotePage.MARGINS -> MarginsPage(editor, margins) { page = NotePage.HOME }
            NotePage.VIEW -> ViewPage(editor, view, onBack = { page = NotePage.HOME }, onFilters = { page = NotePage.FILTERS })
            NotePage.FILTERS -> FiltersPage(editor, view) { page = NotePage.VIEW }
            NotePage.LIMITS -> LimitsPage(editor) { page = NotePage.HOME }
        }
    }
}

// Front page

@Composable
private fun ColumnScope.HomePage(editor: Editor, fullscreen: Boolean, onToggleFullscreen: () -> Unit, go: (NotePage) -> Unit) {
    val vs = editor.viewSettings
    PageCard(editor)
    Spacer(Modifier.height(10.dp))
    PanelCard {
        val prefs = editor.preferences
        PanelCaption(stringResource(R.string.title_zoom)) {
            LimitsLink(prefs.minZoomEnabled || prefs.maxZoomEnabled) { go(NotePage.LIMITS) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZoomStepper(editor.zoomPercent, enabled = !editor.zoomLocked, onOut = editor::zoomOut, onIn = editor::zoomIn, modifier = Modifier.weight(1f))
            ZoomLockButton(editor.zoomLocked) { editor.toggleZoomLock() }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FitTile(stringResource(R.string.fit_page_short), Modifier.weight(1f), glyph = FitPageGlyph) { editor.fitPage() }
            FitTile(stringResource(R.string.fit_width_short), Modifier.weight(1f), shortcut = Keys.FIT_ALL, glyph = FitWidthGlyph) { editor.fitWidth() }
            FitTile(stringResource(R.string.fit_height_short), Modifier.weight(1f), glyph = FitHeightGlyph) { editor.fitHeight() }
        }
    }
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QuickTile(XnotesIcons.fullscreen, stringResource(R.string.toolbar_fullscreen), fullscreenState(fullscreen), fullscreen, Modifier.weight(1f)) { onToggleFullscreen() }
        QuickTile(XnotesIcons.rotateRight, stringResource(R.string.rotate), "${vs.rotation}°", vs.rotation != 0, Modifier.weight(1f)) {
            editor.updateViewOverrides(editor.viewOverrides.copy(rotation = (vs.rotation + 90) % 360))
        }
        val speed = stringResource(R.string.speed_mm_s, tenths(editor.autoScrollSpeed))
        QuickTile(
            XnotesIcons.autoscroll,
            stringResource(R.string.caption_autoscroll),
            if (editor.autoScrollOn) speed else stringResource(R.string.off),
            editor.autoScrollOn,
            Modifier.weight(1f),
            enabled = vs.verticalScroll,
        ) { editor.setAutoScroll(!editor.autoScrollOn) }
    }
    Spacer(Modifier.height(10.dp))
    PanelCard(padding = PaddingValues(0.dp)) {
        NavRow(stringResource(R.string.title_styles), stylesSubtitle(editor), onClick = { go(NotePage.STYLES) }) { StylesPreview(editor) }
        CardDivider(60.dp)
        NavRow(stringResource(R.string.title_margins), marginsSubtitle(editor), onClick = { go(NotePage.MARGINS) }) { MarginsPreview(editor) }
        CardDivider(60.dp)
        NavRow(stringResource(R.string.title_view), viewSubtitle(editor), onClick = { go(NotePage.VIEW) }) { ViewPreview(vs.mode) }
    }
}

/** What the Full screen tile says under its name: its key where there is a keyboard, else whether it is on. */
@Composable
internal fun fullscreenState(on: Boolean): String =
    if (keyboardAttached()) Keys.FULLSCREEN else stringResource(if (on) R.string.on else R.string.off)

/** "Limits off ›" at the zoom caption's end. */
@Composable
internal fun LimitsLink(on: Boolean, onClick: () -> Unit) {
    val accent = LocalPalette.current.accent.toComposeColor()
    Row(
        Modifier.clip(MaterialTheme.shapes.extraSmall).clickable(role = Role.Button, onClick = onClick).padding(start = 8.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(if (on) R.string.limits_on else R.string.limits_off), color = accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Icon(XnotesIcons.next, null, tint = accent, modifier = Modifier.size(14.dp))
    }
}

/** Prev and next round a "3 of 12" that turns into a field when tapped, over where the page sits in the note. */
@Composable
private fun PageCard(editor: Editor) {
    val palette = LocalPalette.current
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    fun jump() {
        text.toIntOrNull()?.let { editor.goToPage((it - 1).coerceIn(0, (editor.pageCount - 1).coerceAtLeast(0))) }
        editing = false
    }
    PanelCard {
        val keys = keyboardAttached()
        PanelCaption(stringResource(if (editing) R.string.title_go_to_page else R.string.caption_page)) {
            if (keys) CaptionHint("${Keys.PREV_PAGE} · ${Keys.NEXT_PAGE}")
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RoundButton(XnotesIcons.prev, stringResource(R.string.previous_page)) { editor.prevPage() }
            val shape = MaterialTheme.shapes.medium
            Row(
                Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(shape)
                    .background(palette.bg.toComposeColor())
                    .border(if (editing) 2.dp else 1.dp, (if (editing) palette.accent else palette.border).toComposeColor(), shape)
                    .clickable(enabled = !editing, role = Role.Button) {
                        text = "${editor.pageIndex + 1}"
                        editing = true
                    },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (editing) {
                    NativeField(
                        value = text,
                        onText = { text = it },
                        modifier = Modifier.width(64.dp),
                        numeric = true,
                        maxLen = 5,
                        endAlign = true,
                        autoFocus = true,
                        textSp = 20f,
                        onDone = ::jump,
                    )
                } else {
                    Text("${editor.pageIndex + 1}", color = palette.text.toComposeColor(), fontSize = 20.sp, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.width(7.dp))
                Text(stringResource(R.string.page_of_count, editor.pageCount), color = palette.textDim.toComposeColor(), fontSize = 15.sp)
            }
            if (editing) {
                Box(
                    Modifier
                        .height(44.dp)
                        .clip(CircleShape)
                        .background(palette.accent.toComposeColor())
                        .clickable(role = Role.Button, onClick = ::jump)
                        .padding(horizontal = 20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.go), color = palette.onAccent.toComposeColor(), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            } else {
                RoundButton(XnotesIcons.next, stringResource(R.string.next_page)) { editor.nextPage() }
            }
        }
        Spacer(Modifier.height(12.dp))
        PagePosition(editor.pageIndex, editor.pageCount)
    }
}

private const val MAX_TICKS = 30

/** Where the page sits in the note: a tick a page, or one track once ticks would crowd. */
@Composable
private fun PagePosition(index: Int, count: Int) {
    val palette = LocalPalette.current
    val on = palette.accent.toComposeColor()
    val past = on.copy(alpha = 0.4f)
    val rest = palette.border.toComposeColor()
    Canvas(Modifier.fillMaxWidth().padding(horizontal = 4.dp).height(4.dp)) {
        val h = size.height
        val r = CornerRadius(h / 2)
        if (count <= 0) return@Canvas
        if (count <= MAX_TICKS) {
            val gap = 3.dp.toPx()
            val w = (size.width - gap * (count - 1)) / count
            for (i in 0 until count) {
                drawRoundRect(if (i == index) on else if (i < index) past else rest, Offset(i * (w + gap), 0f), Size(w, h), r)
            }
        } else {
            val mark = max(8.dp.toPx(), size.width / count)
            val x = (size.width - mark) * index / (count - 1)
            drawRoundRect(rest, size = size, cornerRadius = r)
            drawRoundRect(past, size = Size(x + mark, h), cornerRadius = r)
            drawRoundRect(on, Offset(x, 0f), Size(mark, h), r)
        }
    }
}

/** Tenths as the autoscroll slider shows them: "1.2", "5". */
private fun tenths(v: Int): String = if (v % 10 == 0) "${v / 10}" else "%.1f".format(v / 10f)

// What the front page's rows say and show

/** How the current page draws its template: the Styles popup's current-page reading of it. */
private class PageLook(val key: String, val template: Template?, val drawing: TemplateDrawing)

@Composable
private fun currentPageLook(editor: Editor): PageLook {
    val doc = editor.documentStyle
    val page = editor.currentPageStyle
    val key = page.template ?: doc.template ?: PageTemplates.NONE
    val t = if (key == PageTemplates.NONE) null else editor.templateFor(key)
    val lower = doc.takeIf { (it.template ?: PageTemplates.NONE) == key }
    val look = TemplateLook.of(page, lower, LocalPalette.current.paper, below = doc)
    val drawing = if (t != null) TemplateDrawing.of(t, page.settings, lower?.settings, look.paper, editor.documentDpi)
    else TemplateDrawing(look, com.xnotes.core.template.TemplateValues(emptyMap(), emptyMap()))
    return PageLook(key, t, drawing)
}

@Composable
private fun StylesPreview(editor: Editor) {
    val look = currentPageLook(editor)
    val t = look.template
    if (t != null) {
        val d = look.drawing
        TemplatePreview(t, look.key, editor.currentPageMm, d.look.ink, d.look.accent, d.look.paper, d.values, 30.dp)
    } else {
        val (w, h) = editor.currentPageMm
        Box(
            Modifier
                .size(30.dp, 30.dp * (h / w).coerceIn(0.5, 1.4).toFloat())
                .clip(RoundedCornerShape(3.dp))
                .background(look.drawing.look.paper.toComposeColor())
                .border(1.dp, previewEdge(), RoundedCornerShape(3.dp)),
        )
    }
}

/** The outline of a front-page preview, strong enough to hold a page the colour of the card. */
@Composable
private fun previewEdge(): Color = LocalPalette.current.textDim.toComposeColor().copy(alpha = 0.4f)

@Composable
private fun stylesSubtitle(editor: Editor): String {
    val name = currentPageLook(editor).template?.name ?: stringResource(R.string.no_template)
    val size = editor.newPageSize?.let { stringResource(R.string.new_pages_size, pageSizeLabel(it.size)) }
    return listOfNotNull(name, size).joinToString(" · ")
}

/** The current page's margins as fractions: its own edges over the note's. */
private fun effectiveMargins(editor: Editor): FloatArray {
    val doc = editor.documentMargins
    val page = editor.currentPageMargins
    return floatArrayOf(
        (page.left ?: doc.left ?: 0.0).toFloat(),
        (page.top ?: doc.top ?: 0.0).toFloat(),
        (page.right ?: doc.right ?: 0.0).toFloat(),
        (page.bottom ?: doc.bottom ?: 0.0).toFloat(),
    )
}

private val EDGE_ORDER = listOf(PageEdge.LEFT, PageEdge.TOP, PageEdge.RIGHT, PageEdge.BOTTOM)

private fun FloatArray.of(e: PageEdge): Float = this[EDGE_ORDER.indexOf(e)]

@Composable
private fun marginsSubtitle(editor: Editor): String {
    val f = effectiveMargins(editor)
    val set = PageEdge.entries.filter { f.of(it) > 0f }
    if (set.isEmpty()) return stringResource(R.string.none)
    return set.map { stringResource(R.string.margin_edge_value, stringResource(it.labelRes), (f.of(it) * 100).roundToInt()) }.joinToString(" · ")
}

@Composable
private fun MarginsPreview(editor: Editor) {
    val palette = LocalPalette.current
    val look = currentPageLook(editor)
    val paper = look.drawing.look.paper.toComposeColor()
    val color = (editor.currentPageMargins.color ?: editor.documentMargins.color)?.toComposeColor() ?: paper
    val (w, h) = editor.currentPageMm
    val f = effectiveMargins(editor)
    val ink = MarginInk(paper, color, previewEdge(), palette.textDim.toComposeColor().copy(alpha = 0.25f), palette.accent.toComposeColor())
    Canvas(Modifier.size(32.dp, 42.dp)) { marginPage(Rect(Offset.Zero, size), (h / w).toFloat(), f, ink, null, 1.dp.toPx(), rules = 6) }
}

@Composable
private fun viewSubtitle(editor: Editor): String {
    val vs = editor.viewSettings
    val parts = mutableListOf(stringResource(modeLabel(vs.mode)))
    if (vs.verticalScroll) parts += stringResource(R.string.vertical_scrolling_lower)
    if (vs.rotation != 0) parts += stringResource(R.string.rotated_degrees, vs.rotation)
    if (filtersOn(vs)) parts += stringResource(R.string.filters_on)
    return parts.joinToString(" · ")
}

private fun modeLabel(m: ViewingMode) = when (m) {
    ViewingMode.SINGLE -> R.string.view_single
    ViewingMode.DOUBLE -> R.string.view_double
    ViewingMode.COVER -> R.string.view_cover
}

private fun filtersOn(vs: ViewSettings) = vs.contrast != 100 || vs.invert != 0 || vs.brightness != 100 || vs.sepia != 0 ||
    vs.multiply != PdfColorFilter.MULTIPLY_OFF || vs.screen != PdfColorFilter.SCREEN_OFF

@Composable
private fun ViewPreview(mode: ViewingMode) {
    val palette = LocalPalette.current
    // Drawn pages, not the note's paper, which can be the well's own colour.
    val paper = palette.textDim.toComposeColor().copy(alpha = 0.6f)
    val well = palette.bg.toComposeColor()
    val edge = previewEdge()
    Canvas(Modifier.size(32.dp, 42.dp)) {
        val r = CornerRadius(3.dp.toPx())
        drawRoundRect(well, cornerRadius = r)
        drawRoundRect(edge, cornerRadius = r, style = Stroke(1.dp.toPx()))
        val u = size.width / 32f
        val pr = CornerRadius(1.5f * u)
        when (mode) {
            ViewingMode.SINGLE -> {
                drawRoundRect(paper, Offset(8 * u, 4 * u), Size(16 * u, 15 * u), pr)
                drawRoundRect(paper, Offset(8 * u, 22 * u), Size(16 * u, 20 * u), pr)
            }
            ViewingMode.DOUBLE -> {
                drawRoundRect(paper, Offset(3.5f * u, 12 * u), Size(12 * u, 18 * u), pr)
                drawRoundRect(paper, Offset(16.5f * u, 12 * u), Size(12 * u, 18 * u), pr)
            }
            ViewingMode.COVER -> drawRoundRect(paper, Offset(16.5f * u, 12 * u), Size(12 * u, 18 * u), pr)
        }
    }
}

// Styles

/** The Styles page's working copy, kept above the pages so Customize shares it. */
private class StylesState(editor: Editor) {
    var tab by mutableIntStateOf(0) // 0 = All Pages, 1 = Current Page
    var docStyle by mutableStateOf(editor.documentStyle)
    var pageStyle by mutableStateOf(editor.currentPageStyle)
    // "Default for new notes" shows once All Pages differs from the saved default and stays for the visit.
    var showNewNoteRow by mutableStateOf(editor.documentStyle != editor.newNoteStyle)
    val memory = TemplateMemory()
    val style: PageStyle get() = if (tab == 0) docStyle else pageStyle

    // Both levels are read back: a template picked for All Pages also changes the pages following it.
    fun synced(editor: Editor) {
        docStyle = editor.documentStyle
        pageStyle = editor.currentPageStyle
        if (docStyle != editor.newNoteStyle) showNewNoteRow = true
    }

    fun apply(editor: Editor, next: PageStyle) {
        if (tab == 0) editor.setDocumentStyle(next) else editor.setCurrentPageStyle(next)
        synced(editor)
    }

    fun pick(editor: Editor, key: String?) {
        if (tab == 0) editor.setDocumentTemplate(key ?: PageTemplates.NONE, memory) else editor.setCurrentPageTemplate(key, memory)
        synced(editor)
    }
}

/** What the level on show draws: its template, the level below whose settings show through, its colours. */
private class StylesView(val shownKey: String, val shown: Template?, val lower: PageStyle?, val look: TemplateLook)

private fun StylesState.view(editor: Editor, defaultPaper: Rgba): StylesView {
    // With no template of its own, the page tab shows the document's.
    val inherited = if (tab == 0) PageTemplates.NONE else docStyle.template ?: PageTemplates.NONE
    val shownKey = style.template ?: inherited
    val shown = if (shownKey == PageTemplates.NONE) null else editor.templateFor(shownKey)
    val lower = docStyle.takeIf { tab == 1 && (it.template ?: PageTemplates.NONE) == shownKey }
    return StylesView(shownKey, shown, lower, TemplateLook.of(style, lower, defaultPaper, below = if (tab == 1) docStyle else null))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StylesPage(editor: Editor, s: StylesState, onImportTemplate: () -> Unit, onBack: () -> Unit, onCustomize: () -> Unit) {
    val palette = LocalPalette.current
    val style = s.style
    val v = s.view(editor, palette.paper)
    fun drawing(e: com.xnotes.platform.TemplateLibrary.Entry): TemplateDrawing {
        val kept = if (s.tab == 0) s.memory.recall(null, e.key) else editor.currentPage?.let { s.memory.recall(it, e.key) }
        return TemplateDrawing.inStrip(e, style, v.shownKey, kept, s.docStyle.takeIf { s.tab == 1 }, v.look.paper, editor.documentDpi)
    }

    PanelHeader(stringResource(R.string.title_styles), onBack, onReset = if (s.tab == 0) ({ editor.resetDocumentStyle(s.memory); s.synced(editor) }) else null)
    PanelSegmented(listOf(stringResource(R.string.all_pages), stringResource(R.string.current_page)), s.tab, { s.tab = it })

    Spacer(Modifier.height(16.dp))
    PanelCaption(stringResource(R.string.caption_page_colour))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PanelChip(stringResource(R.string.default_choice), style.pageColor == null) { s.apply(editor, style.copy(pageColor = null)) }
        SwatchCircles(editor, SwatchRole.PAPER, style.pageColor) { s.apply(editor, style.copy(pageColor = it)) }
    }

    Spacer(Modifier.height(16.dp))
    PanelCaption(stringResource(R.string.caption_template))
    val libraryVersion = TemplateLibraryUi.version
    // On the cover, its own tab lists cover designs instead of page templates.
    val coverPage = s.tab == 1 && editor.currentPageIsCover
    val choices = remember(libraryVersion, s.tab, coverPage) {
        editor.templateChoices(if (coverPage) com.xnotes.core.template.TemplateKind.COVER else com.xnotes.core.template.TemplateKind.PAGE)
    }
    TemplateGrid(
        entries = choices,
        selected = style.template,
        withDefault = s.tab == 1,
        inheritedKey = s.docStyle.template ?: PageTemplates.NONE,
        paper = v.look.paper,
        pageMm = editor.currentPageMm,
        drawing = ::drawing,
        onSelect = { s.pick(editor, it) },
        onImport = onImportTemplate,
        onRemove = { editor.removeTemplate(it) },
        onKeep = { editor.keepNoteTemplate(it) },
    )
    val shown = v.shown
    if (shown != null) {
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                shown.name,
                color = palette.text.toComposeColor(),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            PanelChip(stringResource(R.string.customize), icon = XnotesIcons.sliders, onClick = onCustomize)
        }
    }
    if (s.tab == 0 && s.showNewNoteRow && !s.docStyle.isEmpty) {
        Spacer(Modifier.height(4.dp))
        PanelCheck(stringResource(R.string.default_for_new_notes), !editor.newNoteStyle.isEmpty && s.docStyle == editor.newNoteStyle) { on ->
            editor.saveNewNoteStyle(if (on) s.docStyle else PageStyle())
        }
    }

    Box(Modifier.padding(vertical = 16.dp).fillMaxWidth().height(1.dp).background(palette.border.toComposeColor()))
    NewPageSize(editor)
}

/** The size of pages added to the note: the page before's, a named size turned either way, or Custom's sides. */
@Composable
private fun NewPageSize(editor: Editor) {
    var choice by remember { mutableStateOf(editor.newPageSize) }
    fun apply(next: PageSizeChoice?) {
        choice = next
        editor.setNewPageSize(next)
    }
    PanelCaption(stringResource(R.string.caption_new_page_size))
    val c = choice
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // A first pick starts from the Preferences size, so Custom opens on the sides typed there.
        SizeField(c?.size, stringResource(R.string.same_as_previous), Modifier.weight(1f)) { size ->
            apply(size?.let { (choice ?: editor.preferences.defaultPageChoice).copy(size = it) })
        }
        if (c != null && c.size != PageSize.CUSTOM) {
            Box(Modifier.width(104.dp)) {
                PanelSegmented(2, if (c.orientation == Orientation.PORTRAIT) 0 else 1, { i ->
                    apply(c.copy(orientation = if (i == 0) Orientation.PORTRAIT else Orientation.LANDSCAPE))
                }, height = 32.dp) { i, on ->
                    val tint = LocalPalette.current.let { (if (on) it.selectionForeground else it.textDim).toComposeColor() }
                    Box(Modifier.size(if (i == 0) 11.dp else 15.dp, if (i == 0) 15.dp else 11.dp).border(1.6.dp, tint, RoundedCornerShape(2.dp)))
                }
            }
        }
    }
    if (c != null && c.size == PageSize.CUSTOM) {
        Spacer(Modifier.height(10.dp))
        // The fields keep the callback they were built with, so it reads the choice as it is now.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SideField(stringResource(R.string.width_mm), c.customWidthMm, Modifier.weight(1f)) { mm -> choice?.let { apply(it.copy(customWidthMm = mm)) } }
            SideField(stringResource(R.string.height_mm), c.customHeightMm, Modifier.weight(1f)) { mm -> choice?.let { apply(it.copy(customHeightMm = mm)) } }
        }
    }
}

/** The page size menu dressed as a panel field; [unset] picks null. */
@Composable
private fun SizeField(size: PageSize?, unset: String, modifier: Modifier, onSelect: (PageSize?) -> Unit) {
    val palette = LocalPalette.current
    var expanded by remember { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.medium
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(shape)
                .background(palette.bg.toComposeColor())
                .border(1.dp, palette.border.toComposeColor(), shape)
                .clickable(role = Role.DropdownList) { expanded = true }
                .padding(start = 14.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(size?.let { pageSizeLabel(it) } ?: unset, color = palette.text.toComposeColor(), fontSize = 14.sp, maxLines = 1, modifier = Modifier.weight(1f))
            Icon(XnotesIcons.chevronDown, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(unset) }, onClick = { onSelect(null); expanded = false })
            PageSize.entries.forEach { s ->
                DropdownMenuItem(text = { Text(pageSizeLabel(s)) }, onClick = { onSelect(s); expanded = false })
            }
        }
    }
}

/** The Styles page's second page: the shown template's colours and every parameter it declares. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomizePage(editor: Editor, s: StylesState, onBack: () -> Unit) {
    val palette = LocalPalette.current
    val v = s.view(editor, palette.paper)
    val t = v.shown
    if (t == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val style = s.style
    val look = v.look
    val dpi = editor.documentDpi
    fun apply(next: PageStyle) = s.apply(editor, next)

    PanelHeader(t.name, onBack, onReset = { apply(style.withSettings(TemplateSettings())) })
    Text(
        listOfNotNull(stringResource(if (s.tab == 0) R.string.all_pages else R.string.current_page), t.description).joinToString(" · "),
        color = palette.textDim.toComposeColor(),
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
    Spacer(Modifier.height(12.dp))
    val drawn = TemplateDrawing.of(t, style.settings, v.lower?.settings, look.paper, dpi)
    Box(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(palette.bg.toComposeColor()).padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        TemplatePreview(t, v.shownKey, editor.currentPageMm, drawn.look.ink, drawn.look.accent, drawn.look.paper, drawn.values, 112.dp)
    }

    if (t.usesInk) {
        Spacer(Modifier.height(16.dp))
        PanelCaption(stringResource(R.string.caption_pattern_colour))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PanelChip(stringResource(R.string.default_choice), style.patternColor == null) { apply(style.copy(patternColor = null)) }
            // Circles hold the hue at full strength; Opacity sets the alpha, so a pick keeps it.
            SwatchCircles(editor, SwatchRole.LINES, style.patternColor?.copy(a = 255)) { apply(style.copy(patternColor = it.copy(a = look.ink.a))) }
        }
        Spacer(Modifier.height(12.dp))
        val opacityPct = look.ink.a * 100f / 255f
        PanelSlider(stringResource(R.string.caption_opacity), "${opacityPct.roundToInt()}%", opacityPct, 0f..100f) { pct ->
            apply(style.copy(patternColor = look.ink.copy(a = (pct / 100f * 255f).roundToInt().coerceIn(0, 255))))
        }
    }
    if (t.usesAccent) {
        Spacer(Modifier.height(12.dp))
        PanelCaption(stringResource(R.string.caption_accent_colour))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            PanelChip(stringResource(R.string.default_choice), style.accentColor == null) { apply(style.copy(accentColor = null)) }
            SwatchCircles(editor, SwatchRole.ACCENT, style.accentColor?.copy(a = 255)) { apply(style.copy(accentColor = it.copy(a = look.accent.a))) }
        }
    }
    TemplateParamControls(t, style, v.lower, dpi, editor, captionGap = 4.dp, chip = { l, sel, c -> PanelChip(l, sel, onClick = c) }, apply = ::apply)
}

// Margins

private class MarginsState(editor: Editor) {
    var tab by mutableIntStateOf(0) // 0 = All Pages, 1 = Current Page
    var edge by mutableStateOf(PageEdge.LEFT)
    var docMargins by mutableStateOf(editor.documentMargins)
    var pageMargins by mutableStateOf(editor.currentPageMargins)
    val margins: PageMargins get() = if (tab == 0) docMargins else pageMargins

    fun apply(editor: Editor, next: PageMargins) {
        if (tab == 0) {
            docMargins = next
            editor.setDocumentMargins(next)
        } else {
            pageMargins = next
            editor.setCurrentPageMargins(next)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MarginsPage(editor: Editor, s: MarginsState, onBack: () -> Unit) {
    val palette = LocalPalette.current
    val margins = s.margins
    // An unset edge inherits: the current page falls back to the document, the document to none.
    val below = if (s.tab == 1) s.docMargins else null
    fun fraction(e: PageEdge) = (margins.edge(e) ?: below?.edge(e) ?: 0.0).toFloat()
    val own = margins.edge(s.edge)
    val percent = (fraction(s.edge) * 100f).coerceIn(0f, 100f)

    PanelHeader(stringResource(R.string.title_margins), onBack, onReset = { s.apply(editor, PageMargins()) })
    PanelSegmented(listOf(stringResource(R.string.all_pages), stringResource(R.string.current_page)), s.tab, { s.tab = it })

    Spacer(Modifier.height(16.dp))
    PanelCaption(stringResource(R.string.caption_edge)) { CaptionHint(stringResource(R.string.tap_an_edge)) }
    val paper = currentPageLook(editor).drawing.look.paper.toComposeColor()
    val color = (margins.color ?: below?.color)?.toComposeColor() ?: paper
    val (w, h) = editor.currentPageMm
    EdgePicker(
        aspect = (h / w).toFloat(),
        fractions = FloatArray(4) { fraction(EDGE_ORDER[it]) },
        selected = s.edge,
        ink = MarginInk(paper, color, palette.border.toComposeColor(), palette.textDim.toComposeColor().copy(alpha = 0.25f), palette.accent.toComposeColor()),
        label = { e -> stringResource(R.string.margin_edge_value, stringResource(e.labelRes), (fraction(e) * 100).roundToInt()) },
        onPick = { s.edge = it },
    )

    Spacer(Modifier.height(16.dp))
    PanelCaption(stringResource(s.edge.labelRes)) {
        CaptionValue("${percent.roundToInt()}%" + if (own == null) stringResource(R.string.default_suffix) else "")
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PanelChip(stringResource(R.string.default_choice), own == null) { s.apply(editor, margins.withEdge(s.edge, null)) }
        androidx.compose.material3.Slider(
            value = percent,
            onValueChange = { s.apply(editor, margins.withEdge(s.edge, it.toDouble() / 100.0)) },
            valueRange = 0f..100f,
            modifier = Modifier.weight(1f),
        )
    }

    Spacer(Modifier.height(12.dp))
    PanelCaption(stringResource(R.string.caption_colour))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PanelChip(stringResource(R.string.default_choice), margins.color == null) { s.apply(editor, margins.copy(color = null)) }
        SwatchCircles(editor, SwatchRole.PAPER, margins.color) { s.apply(editor, margins.copy(color = it)) }
    }
}

private class MarginInk(val paper: Color, val margin: Color, val edge: Color, val rule: Color, val accent: Color)

/**
 * A page of [aspect] (height over width) with its margins as fractions (left, top, right, bottom)
 * laid outside it, fitted into [area] and centred; the [selected] edge is tinted and outlined.
 */
private fun DrawScope.marginPage(area: Rect, aspect: Float, f: FloatArray, ink: MarginInk, selected: PageEdge?, hairline: Float, rules: Int = 16) {
    val (l, t, r, b) = listOf(f[0], f[1], f[2], f[3])
    val unitsW = 1f + l + r
    val unitsH = aspect * (1f + t + b)
    val s = min(area.width / unitsW, area.height / unitsH)
    val ext = Rect(Offset(area.center.x - unitsW * s / 2, area.center.y - unitsH * s / 2), Size(unitsW * s, unitsH * s))
    val page = Rect(ext.left + l * s, ext.top + t * aspect * s, ext.right - r * s, ext.bottom - b * aspect * s)
    drawRect(ink.margin, ext.topLeft, ext.size)
    drawRect(ink.paper, page.topLeft, page.size)
    val step = max(page.height / rules, 3 * hairline)
    var y = page.top + step
    while (y < page.bottom - step / 2) {
        drawLine(ink.rule, Offset(page.left + step / 2, y), Offset(page.right - step / 2, y), hairline)
        y += step
    }
    if (l + t + r + b > 0f) {
        drawRect(ink.accent.copy(alpha = 0.6f), page.topLeft, page.size, style = Stroke(hairline, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4 * hairline, 3 * hairline))))
    }
    drawRect(ink.edge, ext.topLeft, ext.size, style = Stroke(hairline))
    if (selected == null) return
    val band = when (selected) {
        PageEdge.LEFT -> Rect(ext.left, ext.top, page.left, ext.bottom)
        PageEdge.RIGHT -> Rect(page.right, ext.top, ext.right, ext.bottom)
        PageEdge.TOP -> Rect(ext.left, ext.top, ext.right, page.top)
        PageEdge.BOTTOM -> Rect(ext.left, page.bottom, ext.right, ext.bottom)
    }
    if (band.width < 2 * hairline || band.height < 2 * hairline) {
        // Nothing on that edge yet: mark the edge itself.
        val (a, z) = when (selected) {
            PageEdge.LEFT -> Offset(page.left, ext.top) to Offset(page.left, ext.bottom)
            PageEdge.RIGHT -> Offset(page.right, ext.top) to Offset(page.right, ext.bottom)
            PageEdge.TOP -> Offset(ext.left, page.top) to Offset(ext.right, page.top)
            PageEdge.BOTTOM -> Offset(ext.left, page.bottom) to Offset(ext.right, page.bottom)
        }
        drawLine(ink.accent, a, z, 2.5f * hairline)
    } else {
        drawRect(ink.accent.copy(alpha = 0.3f), band.topLeft, band.size)
        drawRect(ink.accent, band.topLeft, band.size, style = Stroke(2 * hairline))
    }
}

/** The page drawn with its margins; tapping near an edge, or its label, picks that edge. */
@Composable
private fun EdgePicker(aspect: Float, fractions: FloatArray, selected: PageEdge, ink: MarginInk, label: @Composable (PageEdge) -> String, onPick: (PageEdge) -> Unit) {
    val palette = LocalPalette.current
    val shape = MaterialTheme.shapes.large
    Box(
        Modifier
            .fillMaxWidth()
            .height(206.dp)
            .clip(shape)
            .background(palette.bg.toComposeColor())
            .border(1.dp, palette.border.toComposeColor(), shape),
    ) {
        Canvas(
            Modifier
                .matchParentSize()
                .pointerInput(Unit) {
                    detectTapGestures { p ->
                        val dx = (p.x - size.width / 2f) / (size.width / 2f)
                        val dy = (p.y - size.height / 2f) / (size.height / 2f)
                        onPick(
                            if (abs(dx) > abs(dy)) (if (dx < 0) PageEdge.LEFT else PageEdge.RIGHT)
                            else (if (dy < 0) PageEdge.TOP else PageEdge.BOTTOM),
                        )
                    }
                },
        ) {
            val area = Rect(84.dp.toPx(), 30.dp.toPx(), size.width - 84.dp.toPx(), size.height - 30.dp.toPx())
            marginPage(area, aspect, fractions, ink, selected, 1.dp.toPx())
        }
        EdgeLabel(label(PageEdge.TOP), selected == PageEdge.TOP, Modifier.align(Alignment.TopCenter).padding(top = 4.dp)) { onPick(PageEdge.TOP) }
        EdgeLabel(label(PageEdge.BOTTOM), selected == PageEdge.BOTTOM, Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp)) { onPick(PageEdge.BOTTOM) }
        EdgeLabel(label(PageEdge.LEFT), selected == PageEdge.LEFT, Modifier.align(Alignment.CenterStart).padding(start = 8.dp)) { onPick(PageEdge.LEFT) }
        EdgeLabel(label(PageEdge.RIGHT), selected == PageEdge.RIGHT, Modifier.align(Alignment.CenterEnd).padding(end = 8.dp)) { onPick(PageEdge.RIGHT) }
    }
}

@Composable
private fun EdgeLabel(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Box(
        modifier
            .height(24.dp)
            .clip(MaterialTheme.shapes.small)
            .background(if (selected) palette.selectionBackground.toComposeColor() else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = (if (selected) palette.selectionForeground else palette.textDim).toComposeColor(),
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

// View

private class ViewState(editor: Editor) {
    // Same visit-sticky rule as the Styles page's "Default for new notes" row.
    var showDefaultRow by mutableStateOf(editor.viewSettings != editor.viewDefaults)
    // Unchecking "Default for all notes" goes back to the defaults this visit opened with.
    val openedDefaults: ViewSettings = editor.viewDefaults

    fun apply(editor: Editor, new: ViewOverrides) {
        editor.updateViewOverrides(new)
        if (editor.viewSettings != editor.viewDefaults) showDefaultRow = true
    }
}

@Composable
private fun ViewPage(editor: Editor, s: ViewState, onBack: () -> Unit, onFilters: () -> Unit) {
    val palette = LocalPalette.current
    val vs = editor.viewSettings
    fun set(f: (ViewOverrides) -> ViewOverrides) = s.apply(editor, f(editor.viewOverrides))

    PanelHeader(stringResource(R.string.title_view), onBack, onReset = { s.apply(editor, ViewOverrides()) })
    PanelCaption(stringResource(R.string.caption_viewing_mode))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (m in ViewingMode.entries) {
            ModeCard(m, stringResource(modeLabel(m)), vs.mode == m, Modifier.weight(1f)) { set { it.copy(mode = m) } }
        }
    }
    Spacer(Modifier.height(16.dp))
    PanelCard(padding = PaddingValues(vertical = 2.dp)) {
        PanelSwitch(stringResource(R.string.caption_vertical_scrolling), vs.verticalScroll) { on -> set { it.copy(verticalScroll = on) } }
        CardDivider()
        PanelSwitch(stringResource(R.string.caption_free_movement), vs.freeMovement) { on -> set { it.copy(freeMovement = on) } }
        CardDivider()
        PanelSwitch(stringResource(R.string.caption_scrollbar), vs.scrollbar) { on -> set { it.copy(scrollbar = on) } }
        CardDivider()
        // Autoscroll lives outside the View settings, so it never stirs the "Default for all notes" row.
        PanelSwitch(stringResource(R.string.caption_autoscroll), editor.autoScrollOn, enabled = vs.verticalScroll) { editor.setAutoScroll(it) }
        if (editor.autoScrollOn) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
                val speed = editor.autoScrollSpeed
                PanelSlider(
                    stringResource(R.string.caption_autoscroll_speed),
                    stringResource(R.string.speed_mm_s, tenths(speed)),
                    speed.toFloat(),
                    Preferences.AUTOSCROLL_MIN.toFloat()..Preferences.AUTOSCROLL_MAX.toFloat(),
                ) { editor.pickAutoScrollSpeed(it.roundToInt()) }
            }
        }
    }
    Spacer(Modifier.height(16.dp))
    PanelCaption(stringResource(R.string.rotate))
    PanelSegmented(ViewSettings.ROTATIONS.map { "$it°" }, ViewSettings.ROTATIONS.indexOf(vs.rotation).coerceAtLeast(0), { i ->
        set { it.copy(rotation = ViewSettings.ROTATIONS[i]) }
    })
    Spacer(Modifier.height(16.dp))
    PanelCard(padding = PaddingValues(0.dp)) {
        NavRow(stringResource(R.string.caption_pdf_filters), filtersSummary(vs), onClick = onFilters) {
            Icon(XnotesIcons.contrast, null, tint = palette.accent.toComposeColor(), modifier = Modifier.size(22.dp))
        }
    }
    if (s.showDefaultRow && editor.viewOverrides != ViewOverrides()) {
        Spacer(Modifier.height(6.dp))
        PanelCheck(stringResource(R.string.default_for_all_notes), vs == editor.viewDefaults) { on ->
            editor.updateViewDefaults(if (on) vs else s.openedDefaults.takeIf { it != vs } ?: ViewSettings())
        }
    }
}

@Composable
private fun filtersSummary(vs: ViewSettings): String {
    val parts = buildList {
        if (vs.contrast != 100) add(stringResource(R.string.filter_contrast) + " ${vs.contrast}%")
        if (vs.invert != 0) add(stringResource(R.string.filter_invert) + " ${vs.invert}%")
        if (vs.brightness != 100) add(stringResource(R.string.filter_brightness) + " ${vs.brightness}%")
        if (vs.sepia != 0) add(stringResource(R.string.filter_sepia) + " ${vs.sepia}%")
        if (vs.multiply != PdfColorFilter.MULTIPLY_OFF) add(stringResource(R.string.filter_multiply))
        if (vs.screen != PdfColorFilter.SCREEN_OFF) add(stringResource(R.string.filter_screen))
    }
    return if (parts.isEmpty()) stringResource(R.string.off) else parts.joinToString(" · ")
}

/** A viewing mode drawn as its pages, filled while chosen. */
@Composable
private fun ModeCard(mode: ViewingMode, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val shape = MaterialTheme.shapes.large
    val fill = if (selected) palette.selectionForeground.toComposeColor() else panelColors().raised
    val line = (if (selected) palette.selectionForeground else palette.textDim).toComposeColor()
    Column(
        modifier
            .height(92.dp)
            .clip(shape)
            .background(if (selected) palette.selectionBackground.toComposeColor() else panelColors().card)
            .border(1.5.dp, if (selected) palette.accent.toComposeColor() else Color.Transparent, shape)
            .clickable(role = Role.RadioButton, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Canvas(Modifier.size(56.dp, 36.dp)) {
            val u = size.width / 56f
            val r = CornerRadius(2 * u)
            fun page(x: Float, filled: Boolean) {
                if (filled) drawRoundRect(fill, Offset(x * u, 2 * u), Size(18 * u, 32 * u), r)
                drawRoundRect(
                    line,
                    Offset(x * u, 2 * u),
                    Size(18 * u, 32 * u),
                    r,
                    style = Stroke(1.4f * u, pathEffect = if (filled) null else PathEffect.dashPathEffect(floatArrayOf(2.5f * u, 2.5f * u))),
                )
            }
            when (mode) {
                ViewingMode.SINGLE -> page(19f, true)
                ViewingMode.DOUBLE -> { page(9f, true); page(29f, true) }
                ViewingMode.COVER -> { page(9f, false); page(29f, true) }
            }
        }
        Spacer(Modifier.height(9.dp))
        Text(
            label,
            color = (if (selected) palette.selectionForeground else palette.text).toComposeColor(),
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

@Composable
private fun FiltersPage(editor: Editor, s: ViewState, onBack: () -> Unit) {
    val palette = LocalPalette.current
    val vs = editor.viewSettings
    fun set(f: (ViewOverrides) -> ViewOverrides) = s.apply(editor, f(editor.viewOverrides))

    PanelHeader(stringResource(R.string.caption_pdf_filters), onBack)
    HeldSlider(stringResource(R.string.filter_contrast), vs.contrast, 200) { v -> set { it.copy(contrast = v) } }
    HeldSlider(stringResource(R.string.filter_invert), vs.invert, 100) { v -> set { it.copy(invert = v) } }
    HeldSlider(stringResource(R.string.filter_brightness), vs.brightness, 200) { v -> set { it.copy(brightness = v) } }
    HeldSlider(stringResource(R.string.filter_sepia), vs.sepia, 200) { v -> set { it.copy(sepia = v) } }
    Spacer(Modifier.height(6.dp))
    PanelCard(padding = PaddingValues(vertical = 2.dp)) {
        BlendRow(stringResource(R.string.filter_multiply), vs.multiply, PdfColorFilter.MULTIPLY_OFF) { c -> set { it.copy(multiply = c) } }
        CardDivider()
        BlendRow(stringResource(R.string.filter_screen), vs.screen, PdfColorFilter.SCREEN_OFF) { c -> set { it.copy(screen = c) } }
        CardDivider()
        PanelSwitch(stringResource(R.string.caption_keep_images), vs.keepImages) { on -> set { it.copy(keepImages = on) } }
    }
    Spacer(Modifier.height(10.dp))
    Text(stringResource(R.string.filters_hint), color = palette.textDim.toComposeColor(), fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(horizontal = 4.dp))
}

/** A filter's percentage slider. It applies on release, since every change re-renders the PDF. */
@Composable
private fun HeldSlider(label: String, value: Int, max: Int, onSet: (Int) -> Unit) {
    var held by remember { mutableStateOf<Int?>(null) }
    val shown = held ?: value
    PanelSlider(label, "$shown%", shown.toFloat(), 0f..max.toFloat(), onChangeFinished = { held?.let(onSet); held = null }) { held = it.roundToInt() }
    Spacer(Modifier.height(6.dp))
}

/**
 * A blend filter's row: the shared picker dot plus an Off chip that writes the blend's identity
 * colour (white for multiply, black for screen), so "no filter" needs no flag of its own.
 */
@Composable
private fun BlendRow(label: String, value: Rgba, off: Rgba, onChange: (Rgba) -> Unit) {
    val palette = LocalPalette.current
    val on = value != off
    Row(Modifier.fillMaxWidth().height(52.dp).padding(start = 16.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = palette.text.toComposeColor(), fontSize = 14.sp, modifier = Modifier.weight(1f))
        ColorPickerDot(current = value, custom = on, onPick = onChange, dismissOnPick = false) { d, p -> PageColorGridPopup(value, d, p) }
        Spacer(Modifier.width(8.dp))
        PanelChip(stringResource(R.string.off), !on) { onChange(off) }
    }
}

// Zoom limits

/** Optional min and max zoom. While a limit is on, every zoom path (pinch, buttons, keys, fit) clamps to it. */
@Composable
private fun LimitsPage(editor: Editor, onBack: () -> Unit) {
    val palette = LocalPalette.current
    val base = remember { editor.preferences }
    var minOn by remember { mutableStateOf(base.minZoomEnabled) }
    var minPct by remember { mutableStateOf(base.minZoomPercent) }
    var maxOn by remember { mutableStateOf(base.maxZoomEnabled) }
    var maxPct by remember { mutableStateOf(base.maxZoomPercent) }
    fun emit() = editor.applyPreferences(
        editor.preferences.copy(minZoomEnabled = minOn, minZoomPercent = minPct, maxZoomEnabled = maxOn, maxZoomPercent = maxPct),
    )

    PanelHeader(stringResource(R.string.title_zoom_limits), onBack)
    PanelCard(padding = PaddingValues(vertical = 2.dp)) {
        LimitRow(stringResource(R.string.caption_min_zoom), minOn, minPct, onToggle = {
            minOn = it
            if (minOn && maxOn && minPct > maxPct) minPct = maxPct
            emit()
        }) {
            minPct = it.coerceIn(Preferences.ZOOM_LIMIT_MIN_PCT, Preferences.ZOOM_LIMIT_MAX_PCT)
                .coerceAtMost(if (maxOn) maxPct else Preferences.ZOOM_LIMIT_MAX_PCT)
            emit()
        }
        CardDivider()
        LimitRow(stringResource(R.string.caption_max_zoom), maxOn, maxPct, onToggle = {
            maxOn = it
            if (maxOn && minOn && maxPct < minPct) maxPct = minPct
            emit()
        }) {
            maxPct = it.coerceIn(Preferences.ZOOM_LIMIT_MIN_PCT, Preferences.ZOOM_LIMIT_MAX_PCT)
                .coerceAtLeast(if (minOn) minPct else Preferences.ZOOM_LIMIT_MIN_PCT)
            emit()
        }
    }
    Spacer(Modifier.height(10.dp))
    Text(stringResource(R.string.zoom_limits_hint), color = palette.textDim.toComposeColor(), fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(horizontal = 4.dp))
}

/** One limit: its name, a stepper by tens (greyed while off) and its switch. */
@Composable
private fun LimitRow(label: String, enabled: Boolean, value: Int, onToggle: (Boolean) -> Unit, onValue: (Int) -> Unit) {
    val palette = LocalPalette.current
    val color = (if (enabled) palette.text else palette.textDim).toComposeColor()
    val tint = (if (enabled) palette.text else palette.disabled).toComposeColor()
    val shape = MaterialTheme.shapes.medium
    Row(Modifier.fillMaxWidth().height(60.dp).padding(start = 16.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = color, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Row(
            Modifier.height(36.dp).clip(shape).background(palette.bg.toComposeColor()).border(1.dp, palette.border.toComposeColor(), shape),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(36.dp).fillMaxHeight().clickable(enabled = enabled, role = Role.Button) { onValue(value - 10) }, contentAlignment = Alignment.Center) {
                Icon(XnotesIcons.minus, null, tint = tint, modifier = Modifier.size(16.dp))
            }
            Text("$value%", color = color, fontSize = 14.sp, fontWeight = FontWeight.Medium, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.width(60.dp))
            Box(Modifier.width(36.dp).fillMaxHeight().clickable(enabled = enabled, role = Role.Button) { onValue(value + 10) }, contentAlignment = Alignment.Center) {
                Icon(XnotesIcons.plus, null, tint = tint, modifier = Modifier.size(16.dp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}
