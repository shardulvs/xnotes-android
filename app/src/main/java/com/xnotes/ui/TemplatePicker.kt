package com.xnotes.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.core.geometry.Rect
import com.xnotes.core.model.PageStyle
import com.xnotes.core.model.PageTemplates
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.TemplateSettings
import com.xnotes.core.template.Template
import com.xnotes.core.template.TemplatePage
import com.xnotes.core.template.TemplatePainter
import com.xnotes.core.template.TemplateValues
import com.xnotes.platform.AndroidRenderer
import com.xnotes.platform.TemplateLibrary
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.max

/** Bumped when the template library changes, so open pickers list it again. */
internal object TemplateLibraryUi {
    var version by mutableIntStateOf(0)
}

private val THUMB_W = 52.dp
private val TILE_W = 62.dp

/**
 * A row of template previews at the current page's proportions, each drawn as [drawing] says, the
 * [selected] one outlined, ending in an Import tile. A long press on an imported template offers
 * to remove it, and on one only this note carries, to add it to the library.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TemplateStrip(
    entries: List<TemplateLibrary.Entry>,
    selected: String?,
    pageMm: Pair<Double, Double>,
    drawing: (TemplateLibrary.Entry) -> TemplateDrawing,
    onSelect: (String) -> Unit,
    onImport: () -> Unit,
    onRemove: (String) -> Unit,
    onKeep: (String) -> Unit,
) {
    val palette = LocalPalette.current
    val aspect = (pageMm.second / pageMm.first).coerceIn(0.5, 2.0)
    val thumbH = THUMB_W * aspect.toFloat()
    val wPx = thumbPx(THUMB_W)
    // Not a LazyRow: the popup measures its content's intrinsic size, which lazy lists refuse.
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (e in entries) key(e.key) {
            var menu by remember { mutableStateOf(false) }
            Column(
                Modifier
                    .width(TILE_W)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .combinedClickable(
                        onClick = { onSelect(e.key) },
                        onLongClick = {
                            if (e.source == TemplateLibrary.Source.IMPORTED || e.source == TemplateLibrary.Source.NOTE) menu = true
                        },
                    )
                    .padding(vertical = 3.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val sel = e.key == selected
                Box(
                    Modifier
                        .size(THUMB_W, thumbH)
                        .border(if (sel) 2.dp else 1.dp, (if (sel) palette.accent else palette.border).toComposeColor(), RoundedCornerShape(3.dp))
                        .padding(if (sel) 2.dp else 1.dp),
                ) {
                    val bmp = rememberTemplateThumb(e.template, TemplateThumbs.spec(e.key, pageMm, drawing(e), wPx), keep = true)
                    if (bmp != null) Image(bmp, contentDescription = e.template.name, Modifier.size(THUMB_W, thumbH))
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    e.template.name,
                    color = palette.text.toComposeColor(),
                    fontSize = 9.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (e.source == TemplateLibrary.Source.IMPORTED) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.remove_template)) }, onClick = { menu = false; onRemove(e.key) })
                    } else {
                        DropdownMenuItem(text = { Text(stringResource(R.string.keep_template)) }, onClick = { menu = false; onKeep(e.key) })
                    }
                }
            }
        }
        Column(
            Modifier
                .width(TILE_W)
                .clip(MaterialTheme.shapes.extraSmall)
                .combinedClickable(onClick = onImport)
                .padding(vertical = 3.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier
                    .size(THUMB_W, thumbH)
                    .border(1.dp, palette.border.toComposeColor(), RoundedCornerShape(3.dp))
                    .background(palette.surface.toComposeColor(), RoundedCornerShape(3.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("+", color = palette.textDim.toComposeColor(), fontSize = 20.sp)
            }
            Spacer(Modifier.height(3.dp))
            Text(
                stringResource(R.string.import_template),
                color = palette.text.toComposeColor(),
                fontSize = 9.sp,
                maxLines = 1,
            )
        }
    }
}

/** [t] with [values] set, as it lands on the whole page, [width] wide and outlined like a thumbnail. */
@Composable
internal fun TemplatePreview(
    t: Template,
    key: String,
    pageMm: Pair<Double, Double>,
    ink: Rgba,
    accent: Rgba,
    paper: Rgba,
    values: TemplateValues,
    width: Dp,
) {
    val h = width * (pageMm.second / pageMm.first).coerceIn(0.5, 2.0).toFloat()
    Box(Modifier.size(width, h).border(1.dp, LocalPalette.current.border.toComposeColor(), RoundedCornerShape(3.dp)).padding(1.dp)) {
        val bmp = rememberTemplateThumb(t, TemplateThumbs.spec(key, pageMm, ink, accent, paper, values, thumbPx(width)), keep = false)
        if (bmp != null) Image(bmp, contentDescription = t.name, Modifier.size(width, h))
    }
}

@Composable
internal fun thumbPx(width: Dp): Int = with(LocalDensity.current) { width.toPx() }.toInt().coerceAtLeast(8)

/** [spec] drawn off the main thread, straight from [TemplateThumbs] when it is there already. */
@Composable
internal fun rememberTemplateThumb(t: Template, spec: TemplateThumbs.Spec, keep: Boolean): ImageBitmap? {
    val state = produceState(if (keep) TemplateThumbs.cached(spec) else null, spec) {
        value = (if (keep) TemplateThumbs.cached(spec) else null) ?: withContext(Dispatchers.Default) { TemplateThumbs.render(t, spec, keep) }
    }
    return state.value
}

/** Strip thumbnails drawn ahead by [prewarm], so the Styles popup opens with them in place. */
internal object TemplateThumbs {

    data class Spec(
        val key: String,
        val pageMm: Pair<Double, Double>,
        val ink: Rgba,
        val accent: Rgba,
        val paper: Rgba,
        val numbers: Map<String, Double>,
        val colors: Map<String, Rgba>,
        val wPx: Int,
    )

    private const val CAPACITY = 96

    private val cache = object : LinkedHashMap<Spec, ImageBitmap>(CAPACITY, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Spec, ImageBitmap>) = size > CAPACITY
    }

    /** Thumbnails show the ruling plainly, whatever opacity the page itself uses. */
    fun spec(key: String, pageMm: Pair<Double, Double>, ink: Rgba, accent: Rgba, paper: Rgba, values: TemplateValues, wPx: Int) =
        Spec(key, pageMm, ink.copy(a = max(ink.a, 170)), accent.copy(a = max(accent.a, 190)), paper, values.numbers, values.colors, wPx)

    fun spec(key: String, pageMm: Pair<Double, Double>, d: TemplateDrawing, wPx: Int) =
        spec(key, pageMm, d.look.ink, d.look.accent, d.look.paper, d.values, wPx)

    fun cached(s: Spec): ImageBitmap? = synchronized(cache) { cache[s] }

    fun render(t: Template, s: Spec, keep: Boolean): ImageBitmap? {
        if (keep) cached(s)?.let { return it }
        val (w, h) = s.pageMm
        val hPx = (s.wPx * h / w).toInt().coerceAtLeast(8)
        val img = runCatching {
            val bmp = Bitmap.createBitmap(s.wPx, hPx, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(s.paper.toArgb())
            val values = TemplateValues(s.numbers, s.colors)
            val out = TemplateLibrary.layouts.layout(t, TemplatePage(w, h, Rect(0.0, 0.0, w, h), s.ink, s.accent, values))
            TemplatePainter.paint(AndroidRenderer(c), out, s.wPx / w, Rect(0.0, 0.0, s.wPx.toDouble(), hPx.toDouble()), minLine = 1.0, picture = TemplateLibrary.pictures(null, s.key))
            bmp.asImageBitmap()
        }.getOrNull() ?: return null
        if (keep) synchronized(cache) { cache[s] = img }
        return img
    }

    /** Draw the strip's [tiles] that are not kept yet, one at a time. */
    suspend fun prewarm(tiles: List<Pair<TemplateLibrary.Entry, TemplateDrawing>>, pageMm: Pair<Double, Double>, wPx: Int) =
        withContext(Dispatchers.Default) {
            for ((e, d) in tiles) {
                ensureActive()
                render(e.template, spec(e.key, pageMm, d, wPx), keep = true)
            }
        }
}

/** The effective colours a style level draws its template with. */
internal data class TemplateLook(val ink: Rgba, val accent: Rgba, val paper: Rgba) {
    companion object {
        /** [own] colours over [lower]'s, on [paper]. */
        fun of(own: TemplateSettings, lower: TemplateSettings?, paper: Rgba) = TemplateLook(
            ink = own.patternColor ?: lower?.patternColor ?: PageStyle.DEFAULT_PATTERN_COLOR,
            accent = own.accentColor ?: lower?.accentColor ?: PageStyle.DEFAULT_ACCENT_COLOR,
            paper = paper,
        )

        /** [style]'s colours over [lower] (the document's style, when it shows the same template), its paper over [below]'s. */
        fun of(style: PageStyle, lower: PageStyle?, defaultPaper: Rgba, below: PageStyle? = lower) =
            of(style.settings, lower?.settings, style.pageColor ?: below?.pageColor ?: defaultPaper)
    }
}

/** How a style level draws one template: its colours and parameter values. */
internal class TemplateDrawing(val look: TemplateLook, val values: TemplateValues) {
    companion object {
        /** [t] with [own] settings over [lower]'s, on [paper]; a spacing in content px turns into mm at [dpi]. */
        fun of(t: Template, own: TemplateSettings, lower: TemplateSettings?, paper: Rgba, dpi: Int): TemplateDrawing {
            val numbers = HashMap<String, Double>()
            lower?.params?.let(numbers::putAll)
            own.params?.let(numbers::putAll)
            val spacing = own.spacing ?: lower?.spacing
            t.spacingParam?.let { sp -> if (spacing != null) numbers[sp.name] = spacing * 25.4 / dpi }
            val colors = (lower?.colors ?: emptyMap()) + (own.colors ?: emptyMap())
            return TemplateDrawing(TemplateLook.of(own, lower, paper), TemplateValues(numbers, colors))
        }

        /** How [style] draws [e] in the strip: as it shows it ([shownKey]), else as [kept]; over [below]'s if that shows [e]. */
        fun inStrip(e: TemplateLibrary.Entry, style: PageStyle, shownKey: String, kept: TemplateSettings?, below: PageStyle?, paper: Rgba, dpi: Int): TemplateDrawing {
            val own = if (e.key == shownKey) style.settings else kept ?: TemplateSettings()
            val lower = below?.takeIf { (it.template ?: PageTemplates.NONE) == e.key }?.settings
            return of(e.template, own, lower, paper, dpi)
        }
    }
}

/** Keeps the Styles popup's thumbnails drawn for the open note, again after each close. */
@Composable
internal fun PrewarmTemplateThumbs(editor: Editor, popupOpen: Boolean) {
    val defaultPaper = LocalPalette.current.paper
    val wPx = thumbPx(THUMB_W)
    val version = TemplateLibraryUi.version
    LaunchedEffect(popupOpen, editor.noteOpen, editor.title, editor.pageIndex, version, defaultPaper, wPx) {
        if (popupOpen || !editor.noteOpen) return@LaunchedEffect
        delay(PREWARM_DELAY_MS)
        val doc = editor.documentStyle
        val page = editor.currentPageStyle
        val docKey = doc.template ?: PageTemplates.NONE
        val docPaper = doc.pageColor ?: defaultPaper
        val pagePaper = page.pageColor ?: docPaper
        val dpi = editor.documentDpi
        // Both tabs' tiles as the popup opens on them, before it remembers anything.
        val tiles = editor.templateChoices().flatMap { e ->
            listOf(
                e to TemplateDrawing.inStrip(e, doc, docKey, null, null, docPaper, dpi),
                e to TemplateDrawing.inStrip(e, page, page.template ?: docKey, null, doc, pagePaper, dpi),
            )
        }
        TemplateThumbs.prewarm(tiles, editor.currentPageMm, wPx)
    }
}

/** Lets a just-opened note draw its first pages before thumbnails take the CPU. */
private const val PREWARM_DELAY_MS = 600L
