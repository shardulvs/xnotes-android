package com.xnotes.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.LruCache
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.Slider
import androidx.compose.runtime.MutableFloatState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.xnotes.R
import com.xnotes.core.model.Rgba
import com.xnotes.core.util.ReaderKind
import com.xnotes.platform.ReaderHtml
import com.xnotes.platform.ReaderPdf
import com.xnotes.platform.ReaderPrint
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.Palette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.math.abs
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.ceil
import kotlin.math.roundToInt

/** A file open in the read-only reader. */
data class ReaderFile(val uri: String, val name: String)

private sealed interface ReaderContent {
    class Html(val body: String, val wide: Boolean) : ReaderContent
    class Pdf(val doc: PdfDoc) : ReaderContent
    object Failed : ReaderContent
}

/** A PdfRenderer is single-threaded and must not render after close, so every use goes through [lock]. */
private class PdfDoc(val pdf: ReaderPdf, val ratios: List<Float>) {
    val lock = Mutex()
    var closed = false
    val pages = object : LruCache<String, ImageBitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
}

/**
 * The read-only reader over the explorer: Markdown, Word, PowerPoint and CSV as HTML in a script-less,
 * offline WebView, PDFs as rendered pages. The light/dark choice is an app preference and never touches the file.
 */
@Composable
internal fun ReaderScreen(editor: Editor, file: ReaderFile, onClose: () -> Unit, onWikiLink: (String) -> Unit) {
    val context = LocalContext.current
    val appPalette = LocalPalette.current
    val prefs = remember(editor.prefsVersion) { editor.preferences }
    val dark = prefs.readerDark ?: appPalette.isDark
    // The app theme itself, or its other light/dark variant when the reader is switched.
    val palette = remember(appPalette, dark) { if (dark == appPalette.isDark) appPalette else editor.readerPalette(dark) }
    val background = palette.paper
    val text = palette.text
    val labels = readerLabels(context)
    BackHandler(onBack = onClose)

    val content by produceState<ReaderContent?>(null, file.uri) {
        value = withContext(Dispatchers.IO) { runCatching { loadReader(context, file, labels) }.getOrNull() ?: ReaderContent.Failed }
        awaitDispose {
            (value as? ReaderContent.Pdf)?.doc?.let { doc ->
                CoroutineScope(Dispatchers.IO).launch { doc.lock.withLock { doc.closed = true; doc.pdf.close() } }
            }
        }
    }

    val pdfList = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val zoom = remember(file.uri) { mutableFloatStateOf(1f) }
    val pages = (content as? ReaderContent.Pdf)?.doc?.ratios?.size ?: 0

    // Find in page: the WebView finds in HTML itself; a PDF is searched through its text layer.
    var finding by remember(file.uri) { mutableStateOf(false) }
    var query by remember(file.uri) { mutableStateOf("") }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var webHits by remember { mutableStateOf(0 to 0) }
    var pdfHits by remember(file.uri) { mutableStateOf<List<com.xnotes.platform.PdfMatch>>(emptyList()) }
    var pdfHit by remember(file.uri) { mutableStateOf(0) }
    val isPdf = content is ReaderContent.Pdf
    LaunchedEffect(query, finding, content, webView) {
        val q = if (finding) query.trim() else ""
        if (!isPdf) {
            webView?.let { wv -> if (q.isEmpty()) { wv.clearMatches(); webHits = 0 to 0 } else wv.findAllAsync(q) }
            return@LaunchedEffect
        }
        if (q.isEmpty()) { pdfHits = emptyList(); return@LaunchedEffect }
        delay(250)
        pdfHits = withContext(Dispatchers.IO) {
            val text = com.xnotes.platform.ReaderPdfText.pages(context, file.uri) { context.contentResolver.openInputStream(Uri.parse(file.uri))!! }
            com.xnotes.platform.ReaderPdfText.find(text, q)
        }
        pdfHit = 0
        pdfHits.firstOrNull()?.let { pdfList.animateScrollToItem(it.page) }
    }
    fun step(forward: Boolean) {
        if (!isPdf) { webView?.findNext(forward); return }
        if (pdfHits.isEmpty()) return
        pdfHit = (pdfHit + if (forward) 1 else pdfHits.size - 1) % pdfHits.size
        scope.launch { pdfList.animateScrollToItem(pdfHits[pdfHit].page) }
    }
    val findStatus = when {
        query.isBlank() -> ""
        isPdf -> if (pdfHits.isEmpty()) "0 / 0" else "${pdfHit + 1} / ${pdfHits.size}"
        else -> "${webHits.first} / ${webHits.second}"
    }
    Column(Modifier.fillMaxSize().background(background.toComposeColor())) {
        // The bar is app chrome like the note toolbar; only the page below follows the reader's light/dark.
        ReaderBar(
            file, dark,
            page = if (pages > 0) (pdfList.firstVisibleItemIndex + 1) to pages else null,
            onPage = { i -> scope.launch { pdfList.animateScrollToItem(i.coerceIn(0, pages - 1)) } },
            zoom = zoom.floatValue,
            onZoom = { zoom.floatValue = it },
            onToggleDark = { editor.applyHomePreferences(editor.preferences.copy(readerDark = !dark)) },
            onFind = { finding = !finding },
            onClose = onClose,
        )
        if (finding) FindBar(query, { query = it }, findStatus, { step(false) }, { step(true) }, { finding = false })
        CompositionLocalProvider(LocalPalette provides palette) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (val c = content) {
                    null -> ReaderMessage(stringResource(R.string.loading), text)
                    ReaderContent.Failed -> ReaderMessage(stringResource(R.string.reader_open_failed), text)
                    is ReaderContent.Html -> {
                        val html = remember(c, palette) { ReaderHtml.page(c.body, readerColors(palette), c.wide) }
                        ReaderWebView(
                            html, background.toComposeColor(), (zoom.floatValue * 100).roundToInt(),
                            onCreated = { wv ->
                                webView = wv
                                wv.setFindListener { active, count, _ -> webHits = (if (count == 0) 0 else active + 1) to count }
                            },
                        ) { url -> openLink(context, url, onWikiLink) }
                    }
                    // Light shows PDF pages as they are; dark inverts them to the reader colours.
                    is ReaderContent.Pdf -> PdfPages(
                        c.doc, pdfList, zoom, background.toComposeColor(), if (dark) duotone(background, text) else null,
                        highlights = pdfHits, current = pdfHit, highlight = palette.accent.toComposeColor(),
                    )
                }
            }
        }
    }
}

/** Built from the note toolbar's own pieces (icons, monospace labels, separators) so the two bars match. */
@Composable
private fun ReaderBar(
    file: ReaderFile,
    dark: Boolean,
    page: Pair<Int, Int>?,
    onPage: (Int) -> Unit,
    zoom: Float,
    onZoom: (Float) -> Unit,
    onToggleDark: () -> Unit,
    onFind: () -> Unit,
    onClose: () -> Unit,
) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().height(50.dp).background(palette.panel.toComposeColor()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolbarIcon(XnotesIcons.prev, stringResource(R.string.reader_close), onClick = onClose)
            Label(file.name, Modifier.widthIn(max = 160.dp))
            Separator()
            Icon(XnotesIcons.lock, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.padding(start = 4.dp).size(16.dp))
            Label(stringResource(R.string.reader_read_only))
            if (page != null) {
                val (current, total) = page
                Separator()
                ToolbarIcon(XnotesIcons.prev, stringResource(R.string.previous_page), enabled = current > 1) { onPage(current - 2) }
                Label("$current / $total")
                ToolbarIcon(XnotesIcons.next, stringResource(R.string.next_page), enabled = current < total) { onPage(current) }
            }
            Separator()
            // A pinch can go past the slider's range; the slider then rests at its nearest end.
            Slider(
                value = zoom.coerceIn(ZOOM_STEPS.first(), ZOOM_STEPS.last()),
                onValueChange = { v -> onZoom(ZOOM_STEPS.minBy { abs(it - v) }) },
                valueRange = ZOOM_STEPS.first()..ZOOM_STEPS.last(),
                steps = ZOOM_STEPS.size - 2,
                modifier = Modifier.width(140.dp),
            )
            Label("${(zoom * 100).roundToInt()}%", Modifier.widthIn(min = 44.dp))
            ToolbarIcon(Icons.Outlined.RestartAlt, stringResource(R.string.reader_zoom_reset), enabled = zoom != 1f) { onZoom(1f) }
        }
        ToolbarIcon(XnotesIcons.search, stringResource(R.string.find_in_page), onClick = onFind)
        ToolbarIcon(
            if (dark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
            stringResource(if (dark) R.string.reader_switch_to_light else R.string.reader_switch_to_dark),
            onClick = onToggleDark,
        )
        Spacer(Modifier.width(4.dp))
    }
}

@Composable
private fun ReaderMessage(message: String, text: Rgba) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(message, color = text.toComposeColor(), fontSize = 15.sp)
    }
}

@Composable
private fun ReaderWebView(html: String, background: Color, textZoom: Int, onCreated: (WebView) -> Unit, onLink: (String) -> Unit) {
    val onLinkNow = rememberUpdatedState(onLink)
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                // Scripts, file access and the network stay off: the page is only the file's own content.
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.blockNetworkLoads = true
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url.toString()
                        if (url.startsWith("about:blank#")) return false
                        onLinkNow.value(url)
                        return true
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        // A colour change reloads the same layout, so the old offset still points at the same text.
                        val y = (view.getTag(R.id.reader_scroll) as? Int) ?: return
                        view.postDelayed({ view.scrollTo(0, y) }, 60)
                    }
                }
                onCreated(this)
            }
        },
        update = { wv ->
            wv.setBackgroundColor(background.toArgb())
            if (wv.settings.textZoom != textZoom) wv.settings.textZoom = textZoom
            if (wv.tag != html) {
                if (wv.tag != null) wv.setTag(R.id.reader_scroll, wv.scrollY)
                wv.tag = html
                wv.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
        onRelease = { it.destroy() },
    )
}

private fun readerLabels(context: Context) = ReaderHtml.Labels(
    slide = { n, total -> context.getString(R.string.reader_slide, n, total) },
    notes = context.getString(R.string.reader_notes),
    truncatedRows = { context.getString(R.string.reader_rows_truncated, it) },
    truncatedCells = { r, c -> context.getString(R.string.reader_truncated_cells, r, c) },
    image = context.getString(R.string.reader_image),
    empty = context.getString(R.string.reader_empty),
)

private fun readerColors(p: Palette, background: String = Rgba.toHex(p.paper)) = ReaderHtml.Colors(
    background, Rgba.toHex(p.text), Rgba.toHex(p.textDim), Rgba.toHex(p.accent), Rgba.toHex(raisedOnPaper(p)), Rgba.toHex(p.border),
)

/**
 * Prints a non-PDF reader file as the light reader shows it (on white paper) into a PDF in the cache,
 * for turning into a note; null when it will not open. Each call replaces the previous one's file.
 */
internal suspend fun readerFileToPdf(context: Context, editor: Editor, file: ReaderFile): File? {
    val content = withContext(Dispatchers.IO) { runCatching { readerHtmlBody(context, file, readerLabels(context)) }.getOrNull() } ?: return null
    val html = ReaderHtml.printPage(content.body, readerColors(editor.readerPalette(dark = false), "#ffffff"), content.wide)
    val out = withContext(Dispatchers.IO) {
        File(context.cacheDir, "convert").apply { deleteRecursively(); mkdirs() }.resolve("${file.name.substringBeforeLast('.')}.pdf")
    }
    return out.takeIf { ReaderPrint.toPdf(context, html, landscape = content.wide, out) }
}

/** The theme surface that stands out from the page enough to show code blocks and table headers. */
private fun raisedOnPaper(p: Palette): Rgba {
    fun luma(c: Rgba) = (c.r * 299 + c.g * 587 + c.b * 114) / 1000
    val gaps = listOf(p.panel, p.bg, p.surface, p.surfaceHi).map { it to abs(luma(it) - luma(p.paper)) }
    return (gaps.filter { it.second >= 14 }.minByOrNull { it.second } ?: gaps.maxBy { it.second }).first
}

private fun openLink(context: Context, url: String, onWikiLink: (String) -> Unit) {
    val uri = Uri.parse(url)
    when (uri.scheme?.lowercase()) {
        ReaderHtml.WIKI_SCHEME -> onWikiLink(Uri.decode(uri.schemeSpecificPart.orEmpty()))
        "http", "https", "mailto", "tel" -> runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/** Maps a page's white to [background] and its black to [text], so a PDF follows the reader colours. */
private fun duotone(background: Rgba, text: Rgba): ColorFilter {
    fun row(bg: Int, fg: Int): FloatArray {
        val k = (bg - fg) / 255f
        return floatArrayOf(k * 0.299f, k * 0.587f, k * 0.114f, 0f, fg.toFloat())
    }
    val m = row(background.r, text.r) + row(background.g, text.g) + row(background.b, text.b) + floatArrayOf(0f, 0f, 0f, 1f, 0f)
    return ColorFilter.colorMatrix(ColorMatrix(m))
}

private val ZOOM_STEPS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f)

/** A sharp render of the visible part of a zoomed page, in the page's own pixels at [pageWidth]. */
private class PdfTile(val bitmap: ImageBitmap, val pageWidth: Int, val left: Int, val top: Int)

/**
 * PDF pages in a column; pinch zooms around the fingers and pans sideways. Pages are laid out at the zoomed
 * size so the column scrolls over all of them, and once a zoom or scroll settles the visible parts re-render sharp.
 */
@Composable
private fun PdfPages(
    doc: PdfDoc,
    listState: LazyListState,
    zoom: MutableFloatState,
    background: Color,
    filter: ColorFilter?,
    highlights: List<com.xnotes.platform.PdfMatch>,
    current: Int,
    highlight: Color,
) {
    val palette = LocalPalette.current
    val density = LocalDensity.current
    var scale by zoom
    var offsetX by remember { mutableFloatStateOf(0f) }
    var pinching by remember { mutableStateOf(false) }
    val tiles = remember(doc) { mutableStateMapOf<Int, PdfTile>() }
    val pageCoords = remember(doc) { HashMap<Int, LayoutCoordinates>() }
    var viewport by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val padPx = with(density) { 8.dp.toPx() }
    BoxWithConstraints(Modifier.fillMaxSize().background(background).clipToBounds()) {
        val contentWidth = (constraints.maxWidth - 2 * padPx).coerceAtLeast(1f)
        val baseWidth = contentWidth.roundToInt().coerceIn(1, 3000)
        fun clampX(x: Float) = x.coerceIn(-(scale - 1f).coerceAtLeast(0f) * contentWidth, 0f)
        fun topOf(i: Int): Float? {
            val v = viewport ?: return null
            val c = pageCoords[i]?.takeIf { it.isAttached } ?: return null
            return v.localBoundingBoxOf(c, clipBounds = false).top
        }

        LaunchedEffect(doc, baseWidth) {
            snapshotFlow { listOf(scale, offsetX, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset, pinching || listState.isScrollInProgress) }
                .collectLatest { key ->
                    if (key.last() == true) return@collectLatest
                    // At or below 1x the base render is already at least as sharp as the screen.
                    if (scale < 1.05f) { tiles.clear(); return@collectLatest }
                    delay(150)
                    val v = viewport ?: return@collectLatest
                    val view = Rect(0f, 0f, v.size.width.toFloat(), v.size.height.toFloat())
                    val wanted = pageCoords.filterValues { it.isAttached }.mapNotNull { (i, c) ->
                        val bounds = v.localBoundingBoxOf(c, clipBounds = false)
                        val seen = bounds.intersect(view)
                        if (seen.width < 1f || seen.height < 1f) null else Triple(i, c.size.width, seen.translate(-bounds.left, -bounds.top))
                    }
                    tiles.keys.retainAll(wanted.map { it.first }.toSet())
                    for ((i, pageWidth, r) in wanted) {
                        val left = r.left.toInt()
                        val top = r.top.toInt()
                        val bmp = withContext(Dispatchers.IO) {
                            doc.lock.withLock {
                                if (doc.closed) null else runCatching {
                                    doc.pdf.renderRegion(i, pageWidth, left, top, ceil(r.right).toInt() - left, ceil(r.bottom).toInt() - top).asImageBitmap()
                                }.getOrNull()
                            }
                        } ?: continue
                        tiles[i] = PdfTile(bmp, pageWidth, left, top)
                    }
                }
        }

        Box(
            Modifier.fillMaxSize().onGloballyPositioned { viewport = it }.pointerInput(contentWidth) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val e = awaitPointerEvent(PointerEventPass.Initial)
                        if (e.changes.count { it.pressed } >= 2) {
                            pinching = true
                            val newScale = (scale * e.calculateZoom()).coerceIn(ZOOM_STEPS.first(), 5f)
                            val z = newScale / scale
                            val c = e.calculateCentroid(useCurrent = true)
                            if (z != 1f && c.isSpecified) {
                                // Keep the content under the fingers in place: sideways by the offset, down the column by scrolling.
                                offsetX = c.x - padPx - (c.x - padPx - offsetX) * z
                                val firstTop = topOf(listState.firstVisibleItemIndex) ?: -listState.firstVisibleItemScrollOffset.toFloat()
                                listState.dispatchRawDelta((c.y - firstTop) * (z - 1f))
                                scale = newScale
                            }
                            val pan = e.calculatePan()
                            offsetX = clampX(offsetX + pan.x)
                            listState.dispatchRawDelta(-pan.y)
                            e.changes.forEach { if (it.positionChanged()) it.consume() }
                        } else if (scale > 1f) {
                            // One finger pans sideways; the column keeps the vertical scroll.
                            e.changes.firstOrNull()?.let { offsetX = clampX(offsetX + it.positionChange().x) }
                        }
                    } while (e.changes.any { it.pressed })
                    pinching = false
                }
            },
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(doc.ratios.size) { i ->
                    val key = "$i@$baseWidth"
                    val bmp by produceState(doc.pages.get(key), key) {
                        if (value != null) return@produceState
                        value = withContext(Dispatchers.IO) {
                            doc.lock.withLock {
                                if (doc.closed) null else runCatching { doc.pdf.render(i, baseWidth).asImageBitmap() }.getOrNull()
                            }
                        }?.also { doc.pages.put(key, it) }
                    }
                    DisposableEffect(i) { onDispose { pageCoords.remove(i); tiles.remove(i) } }
                    // Recoloured pages share the background, so an outline keeps them apart.
                    val edge = if (filter != null) Modifier.border(1.dp, palette.border.toComposeColor()) else Modifier
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .layout { m, cs ->
                                val w = (cs.maxWidth * scale).roundToInt()
                                val h = (w / doc.ratios[i]).roundToInt()
                                val p = m.measure(Constraints.fixed(w, h))
                                val x = if (w <= cs.maxWidth) (cs.maxWidth - w) / 2 else offsetX.roundToInt().coerceIn(cs.maxWidth - w, 0)
                                layout(cs.maxWidth, h) { p.place(x, 0) }
                            }
                            .onGloballyPositioned { pageCoords[i] = it }
                            .then(edge)
                            .background(if (filter != null) background else Color.White)
                            .drawBehind {
                                bmp?.let { drawImage(it, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), colorFilter = filter) }
                                tiles[i]?.let { t ->
                                    val f = size.width / t.pageWidth
                                    drawImage(
                                        t.bitmap,
                                        dstOffset = IntOffset((t.left * f).roundToInt(), (t.top * f).roundToInt()),
                                        dstSize = IntSize((t.bitmap.width * f).roundToInt(), (t.bitmap.height * f).roundToInt()),
                                        colorFilter = filter,
                                    )
                                }
                                highlights.forEachIndexed { h, match ->
                                    if (match.page != i) return@forEachIndexed
                                    val tint = highlight.copy(alpha = if (h == current) 0.45f else 0.2f)
                                    for (r in match.rects) {
                                        drawRect(
                                            tint,
                                            topLeft = androidx.compose.ui.geometry.Offset((r.x * size.width).toFloat(), (r.y * size.height).toFloat()),
                                            size = androidx.compose.ui.geometry.Size((r.w * size.width).toFloat(), (r.h * size.height).toFloat()),
                                        )
                                    }
                                }
                            },
                    )
                }
            }
        }
    }
}

private fun loadReader(context: Context, file: ReaderFile, labels: ReaderHtml.Labels): ReaderContent {
    if (ReaderKind.ofName(file.name) == ReaderKind.PDF) {
        val pdf = ReaderPdf.open(context, Uri.parse(file.uri)) ?: return ReaderContent.Failed
        val ratios = runCatching { List(pdf.pageCount) { pdf.ratio(it) } }.getOrNull()
        return if (ratios == null || ratios.isEmpty()) { pdf.close(); ReaderContent.Failed } else ReaderContent.Pdf(PdfDoc(pdf, ratios))
    }
    return readerHtmlBody(context, file, labels) ?: ReaderContent.Failed
}

/** The HTML body of every reader format but PDF; null for a PDF or an unknown kind. */
private fun readerHtmlBody(context: Context, file: ReaderFile, labels: ReaderHtml.Labels): ReaderContent.Html? {
    val uri = Uri.parse(file.uri)
    return when (ReaderKind.ofName(file.name)) {
        ReaderKind.MARKDOWN -> ReaderContent.Html(ReaderHtml.markdown(readText(context, uri), labels), wide = false)
        ReaderKind.CSV -> ReaderContent.Html(ReaderHtml.csv(readText(context, uri), labels), wide = true)
        ReaderKind.DOCX -> ReaderContent.Html(withZip(context, uri) { ReaderHtml.docx(it, ::newParser, labels) }, wide = false)
        ReaderKind.PPTX -> ReaderContent.Html(withZip(context, uri) { ReaderHtml.pptx(it, ::newParser, labels) }, wide = false)
        ReaderKind.XLSX -> ReaderContent.Html(withZip(context, uri) { ReaderHtml.xlsx(it, ::newParser, labels) }, wide = true)
        ReaderKind.PDF, null -> null
    }
}

/** Office XML is read with prefixed names (w:p, r:id), which the converters match on. */
private fun newParser(): XmlPullParser =
    android.util.Xml.newPullParser().apply { setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false) }

private fun <T> withZip(context: Context, uri: Uri, block: (ReaderHtml.ZipRead) -> T): T {
    val temp = File.createTempFile("reader", ".zip", context.cacheDir)
    try {
        context.contentResolver.openInputStream(uri)?.use { input -> temp.outputStream().use { input.copyTo(it) } }
            ?: throw IllegalStateException("cannot read file")
        return ZipFile(temp).use { zip ->
            block(ReaderHtml.ZipRead { path -> zip.getEntry(path)?.let { e -> zip.getInputStream(e).use { it.readBytes() } } })
        }
    } finally {
        temp.delete()
    }
}

/** UTF-8, falling back to the local legacy code page for files that are not (Excel's CSV export). */
private fun readText(context: Context, uri: Uri): String {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalStateException("cannot read file")
    return try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        val legacy = if (Locale.getDefault().language in setOf("cs", "sk", "pl", "hu", "sl", "hr")) "windows-1250" else "windows-1252"
        String(bytes, Charset.forName(legacy))
    }
}
