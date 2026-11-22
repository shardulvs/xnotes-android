package com.xnotes.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.xnotes.R
import com.xnotes.core.model.Orientation
import com.xnotes.core.model.PageSize
import com.xnotes.core.model.PageStyle
import com.xnotes.core.model.PageTemplates
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.TemplateSettings
import com.xnotes.core.model.withTemplate
import com.xnotes.core.template.Template
import com.xnotes.core.template.TemplateKind
import com.xnotes.platform.TemplateLibrary
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.ColorMath
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

/**
 * The dialog's own window, made to behave like the activity's: the system bars hidden when the app
 * hides them, and no resizing when the keyboard comes and goes, so neither flashes a bar or jumps.
 */
@Composable
private fun MatchActivityWindow() {
    val view = LocalView.current
    val context = LocalContext.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        if (window != null) {
            @Suppress("DEPRECATION")
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            var c: android.content.Context? = context
            while (c is android.content.ContextWrapper && c !is android.app.Activity) c = c.baseContext
            val host = (c as? android.app.Activity)?.window?.decorView
            val barsHidden = host?.let { ViewCompat.getRootWindowInsets(it)?.isVisible(WindowInsetsCompat.Type.statusBars()) == false } ?: false
            if (barsHidden) {
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    hide(WindowInsetsCompat.Type.systemBars())
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
        }
        onDispose { }
    }
}

/** Which sheet the screen is styling. */
private enum class Face { PAGES, COVER }

/** New notes are made at the default resolution, so lengths turn into px at it. */
private const val DPI = PageSize.DEFAULT_DPI

/** On a wide screen: the note's column, the gap either side of it, and the bar above both. */
private val NOTE_W = 360.dp
private val GUTTER = 24.dp
private val TOP_BAR = 72.dp

/** A length in mm as it is typed: no ".0" on a whole number. */
private fun mmText(v: Double): String =
    if (v == Math.floor(v)) v.toInt().toString() else String.format(java.util.Locale.ROOT, "%.1f", v)

/** The screen's cover part alone, for a note that exists: its page size, its cover's style if any, and where the choice goes. */
internal class CoverEdit(val pageMm: Pair<Double, Double>, val current: PageStyle?, val onDone: (PageStyle) -> Unit)

/**
 * The full-screen New note. The note sits on the left: its cover and its pages side by side (a tap
 * picks which one to style), their size and orientation, and the picked one's name with Customize,
 * which opens its colours and settings beside it, so the sheets show each change. The gallery sits
 * on the right: Pages or Cover, a filter by tag, and an add menu that imports a template, makes one
 * from an image or opens Generate using AI ([AiTemplateTab]). Narrow screens stack the same parts and
 * open Customize as a bottom sheet. Nothing is written until Create. Given a [coverEdit], it is that
 * note's cover picker instead: the cover alone, and Done.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun NewNoteScreen(
    editor: Editor,
    initialName: String,
    error: String?,
    busy: Boolean,
    onImportTemplate: () -> Unit,
    onTemplateFromImage: (TemplateKind) -> Unit,
    onCreate: (String, Editor.NewNoteSetup) -> Unit,
    onClose: () -> Unit,
    coverEdit: CoverEdit? = null,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        MatchActivityWindow()
        val palette = LocalPalette.current
        val panel = panelColors()
        val text = palette.text.toComposeColor()
        val dim = palette.textDim.toComposeColor()
        val accent = palette.accent.toComposeColor()
        val border = palette.border.toComposeColor()
        val selBg = palette.selectionBackground.toComposeColor()
        val selFg = palette.selectionForeground.toComposeColor()
        val prefs = editor.preferences

        var name by remember { mutableStateOf(TextFieldValue(initialName, TextRange(initialName.length))) }
        // The first tap on the name selects all of it, so typing replaces it. The tap itself moves the
        // caret just after focusing, so for a moment a caret-only change keeps the whole name selected.
        var selectedOnce by remember { mutableStateOf(false) }
        var keepAllUntil by remember { mutableStateOf(0L) }
        var nameFocused by remember { mutableStateOf(false) }
        val focusManager = LocalFocusManager.current
        // Where each text field is, in root coordinates, so a press outside them all ends the typing.
        val fieldBounds = remember { HashMap<String, androidx.compose.ui.geometry.Rect>() }
        var size by remember { mutableStateOf(prefs.defaultPageSize) }
        var orientation by remember { mutableStateOf(prefs.defaultPageOrientation) }
        var customW by remember { mutableStateOf(mmText(prefs.customPageWidthMm)) }
        var customH by remember { mutableStateOf(mmText(prefs.customPageHeightMm)) }
        var style by remember { mutableStateOf(editor.newNoteStyle) }
        // Each page template keeps what was set on it while the screen is open, as each cover design does.
        val pageTweaks = remember { HashMap<String, TemplateSettings>() }
        val version = TemplateLibraryUi.version
        val all = remember(version) { TemplateLibrary.all() }
        val pages = remember(all) { all.filter { it.template.kind == TemplateKind.PAGE } }
        val covers = remember(all) { all.filter { it.template.kind == TemplateKind.COVER } }
        val coverOnly = coverEdit != null
        var coverOn by remember { mutableStateOf(coverOnly) }
        var coverStyle by remember { mutableStateOf(coverEdit?.current ?: PageStyle(template = covers.firstOrNull()?.key)) }
        var face by remember { mutableStateOf(if (coverOnly) Face.COVER else Face.PAGES) }
        // Generate using AI takes the gallery's place; the note keeps showing the face it styles.
        var aiOpen by remember { mutableStateOf(false) }
        var customOpen by remember { mutableStateOf(false) }
        var tag by remember { mutableStateOf<String?>(null) }

        // A template the library gains while this is open was imported from here: select it.
        var handled by remember { mutableStateOf(editor.adoptedTemplate) }
        val adopted = editor.adoptedTemplate
        LaunchedEffect(adopted, all) {
            if (adopted == null || adopted == handled) return@LaunchedEffect
            val e = all.firstOrNull { it.key == adopted } ?: return@LaunchedEffect
            handled = adopted
            when {
                e.template.kind == TemplateKind.COVER -> {
                    coverStyle = PageStyle(template = e.key)
                    coverOn = true
                    face = Face.COVER
                    aiOpen = false
                }
                // The cover picker of an open note has no pages to put it on; it just joins the library.
                !coverOnly -> {
                    style = style.withTemplate(e.key, PageTemplates.NONE, pageTweaks)
                    face = Face.PAGES
                    aiOpen = false
                }
            }
        }

        val custom = size == PageSize.CUSTOM
        val range = PageSize.CUSTOM_MIN_MM..PageSize.CUSTOM_MAX_MM
        val cw = customW.toDoubleOrNull()?.takeIf { it in range }
        val ch = customH.toDoubleOrNull()?.takeIf { it in range }
        val pageMm = when {
            coverEdit != null -> coverEdit.pageMm
            custom -> (cw ?: prefs.customPageWidthMm) to (ch ?: prefs.customPageHeightMm)
            orientation == Orientation.PORTRAIT -> size.shortMm to size.longMm
            else -> size.longMm to size.shortMm
        }
        val portrait = pageMm.first <= pageMm.second
        val sizeOk = !custom || (cw != null && ch != null)
        val canCreate = !busy && sizeOk

        val look = TemplateLook.of(style, null, palette.paper)
        // A cover draws in its own colours, on the pages' paper unless it has its own.
        val coverPaper = coverStyle.pageColor ?: look.paper
        val pageKey = style.template ?: PageTemplates.NONE
        val pageT = pages.firstOrNull { it.key == pageKey }?.template
        val coverKey = coverStyle.template
        val coverT = covers.firstOrNull { it.key == coverKey }?.template
        val hasCover = coverOn && coverT != null
        val canCustomize = face == Face.PAGES || hasCover
        LaunchedEffect(canCustomize) { if (!canCustomize) customOpen = false }
        val faceTitle = when {
            face == Face.PAGES -> pageT?.name ?: stringResource(R.string.plain_paper)
            hasCover -> coverT!!.name
            else -> stringResource(R.string.no_cover)
        }

        fun create() {
            if (coverEdit != null) {
                if (hasCover) coverEdit.onDone(coverStyle)
                return
            }
            if (!canCreate) return
            onCreate(name.text, Editor.NewNoteSetup(pageMm.first, pageMm.second, style, coverStyle.takeIf { hasCover }))
        }

        fun setOrientation(o: Orientation) {
            if (custom && (o == Orientation.PORTRAIT) != portrait) {
                val w = customW
                customW = customH
                customH = w
            }
            orientation = o
        }

        fun pickPage(key: String) {
            style = style.withTemplate(key, PageTemplates.NONE, pageTweaks)
            face = Face.PAGES
        }

        // Each design keeps what was set on it while the screen is open, so trying another loses nothing.
        val coverTweaks = remember { HashMap<String, PageStyle>() }
        fun pickCover(key: String) {
            if (coverKey != key) {
                coverKey?.let { coverTweaks[it] = coverStyle }
                coverStyle = coverTweaks[key] ?: PageStyle(template = key)
            }
            coverOn = true
            face = Face.COVER
        }

        // [t] drawn with [own] settings on [paper]. A [tile] shows the ruling plainly; [keep] holds on to the picture.
        @Composable
        fun Thumb(t: Template?, key: String, own: TemplateSettings, paper: Rgba, w: Dp, h: Dp, keep: Boolean, tile: Boolean = keep) {
            Box(Modifier.size(w, h).background(paper.toComposeColor())) {
                if (t != null) {
                    val d = TemplateDrawing.of(t, own, null, paper, DPI)
                    val spec = if (tile) {
                        TemplateThumbs.spec(key, pageMm, d, thumbPx(w))
                    } else {
                        TemplateThumbs.Spec(key, pageMm, d.look.ink, d.look.accent, paper, d.values.numbers, d.values.colors, thumbPx(w))
                    }
                    val bmp = rememberTemplateThumb(t, spec, keep)
                    // A picture drawn just now fades in; one drawn before shows at once.
                    val shown by animateFloatAsState(if (bmp != null) 1f else 0f, tween(180), label = "thumb")
                    // Cropping, not fitting: a pixel of rounding between the bitmap and its box never shows as a band.
                    if (bmp != null) Image(bmp, contentDescription = t.name, Modifier.size(w, h), contentScale = ContentScale.Crop, alpha = shown)
                }
            }
        }

        // A tile shows its template as set here; the picked one is drawn afresh, so a slider drag never fills the cache.
        @Composable
        fun PageThumb(e: TemplateLibrary.Entry, w: Dp, h: Dp) {
            val picked = e.key == pageKey
            Thumb(e.template, e.key, if (picked) style.settings else pageTweaks[e.key] ?: TemplateSettings(), look.paper, w, h, keep = !picked, tile = true)
        }

        @Composable
        fun CoverThumb(e: TemplateLibrary.Entry, w: Dp, h: Dp) {
            val picked = hasCover && e.key == coverKey
            val set = if (picked) coverStyle else coverTweaks[e.key]
            Thumb(e.template, e.key, set?.settings ?: TemplateSettings(), set?.pageColor ?: look.paper, w, h, keep = !picked, tile = true)
        }

        @Composable
        fun Sheet(label: String, selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
            Column(
                Modifier
                    .clip(MaterialTheme.shapes.small)
                    .semantics { this.selected = selected }
                    .clickable(onClick = onClick)
                    .padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.border(2.dp, if (selected) accent else Color.Transparent, RoundedCornerShape(6.dp)).padding(4.dp)) { content() }
                Text(label, color = if (selected) accent else dim, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }

        @Composable
        fun Dashed(w: Dp, h: Dp, content: @Composable ColumnScope.() -> Unit) {
            Column(
                Modifier.size(w, h).drawBehind {
                    drawRoundRect(
                        color = dim.copy(alpha = 0.55f),
                        cornerRadius = CornerRadius(3.dp.toPx()),
                        style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                    )
                },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                content = content,
            )
        }

        @Composable
        fun MmField(value: String, label: String, onChange: (String) -> Unit) {
            val ok = value.toDoubleOrNull()?.let { it in range } == true
            var focused by remember { mutableStateOf(false) }
            DisposableEffect(label) { onDispose { fieldBounds.remove(label) } }
            val shape = MaterialTheme.shapes.medium
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = text, fontSize = 14.sp, textAlign = TextAlign.Center),
                cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier
                    .width(72.dp)
                    .height(40.dp)
                    .semantics { contentDescription = label }
                    .onGloballyPositioned { fieldBounds[label] = it.boundsInRoot() }
                    .onFocusChanged { focused = it.isFocused }
                    .background(panel.card, shape)
                    .then(
                        when {
                            !ok -> Modifier.border(1.dp, palette.danger.toComposeColor(), shape)
                            focused -> Modifier.border(2.dp, accent, shape)
                            else -> Modifier
                        },
                    )
                    .padding(vertical = 10.dp),
            )
        }

        @Composable
        fun SizeRow() = Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    var open by remember { mutableStateOf(false) }
                    Row(
                        Modifier
                            .height(40.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(panel.card)
                            .clickable(role = Role.DropdownList) { open = true }
                            .padding(start = 14.dp, end = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(pageSizeLabel(size), color = text, fontSize = 14.sp)
                        if (!custom) Text(stringResource(R.string.page_size_mm, mmText(pageMm.first.roundToInt().toDouble()), mmText(pageMm.second.roundToInt().toDouble())), color = dim, fontSize = 13.sp)
                        Icon(XnotesIcons.chevronDown, null, tint = dim, modifier = Modifier.size(16.dp))
                    }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        for (s in PageSize.entries.filter { it != PageSize.CUSTOM } + PageSize.CUSTOM) {
                            val dims = if (s == PageSize.CUSTOM) "" else {
                                val (a, b) = if (portrait) s.shortMm to s.longMm else s.longMm to s.shortMm
                                stringResource(R.string.page_size_mm, mmText(a.roundToInt().toDouble()), mmText(b.roundToInt().toDouble()))
                            }
                            ChoiceMenuItem(s == size, onClick = {
                                if (s == PageSize.CUSTOM && !custom) {
                                    customW = mmText(pageMm.first)
                                    customH = mmText(pageMm.second)
                                }
                                size = s
                                open = false
                            }) { fg ->
                                Row(Modifier.widthIn(min = 220.dp)) {
                                    Text(pageSizeLabel(s), color = fg, modifier = Modifier.weight(1f))
                                    Text(dims, color = if (s == size) fg else dim)
                                }
                            }
                        }
                    }
                }
                Box(Modifier.width(88.dp)) {
                    PanelSegmented(2, if (portrait) 0 else 1, { setOrientation(if (it == 0) Orientation.PORTRAIT else Orientation.LANDSCAPE) }, height = 34.dp, sunken = false) { i, on ->
                        val desc = stringResource(if (i == 0) R.string.orientation_portrait else R.string.orientation_landscape)
                        Box(
                            Modifier
                                .size(if (i == 0) 11.dp else 15.dp, if (i == 0) 15.dp else 11.dp)
                                .semantics { contentDescription = desc }
                                .border(1.6.dp, if (on) selFg else dim, RoundedCornerShape(2.dp)),
                        )
                    }
                }
            }
            if (custom) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MmField(customW, stringResource(R.string.width_mm)) { customW = it }
                    Text("×", color = dim, fontSize = 13.sp)
                    MmField(customH, stringResource(R.string.height_mm)) { customH = it }
                    Text("mm", color = dim, fontSize = 13.sp)
                }
            }
            // Create waits for a size that fits; this says why.
            if (custom && (cw == null || ch == null)) {
                Text(stringResource(R.string.custom_size_range, mmText(range.start), mmText(range.endInclusive)), color = palette.danger.toComposeColor(), fontSize = 12.sp)
            }
        }

        @Composable
        fun Sheets(maxW: Dp, maxH: Dp) = BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Each sheet adds its ring's border and padding (6 dp) and its own (4 dp) on both sides; sized
            // without them, the second sheet was squeezed and its picture shrank inside it.
            val count = if (coverOnly) 1 else 2
            val fits = (maxWidth - 24.dp * (count - 1) - 20.dp * count) / count
            val aspect = (pageMm.second / pageMm.first).toFloat()
            var w = minOf(maxW, fits)
            var h = w * aspect
            if (h > maxH) {
                h = maxH
                w = maxH / aspect
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.Bottom,
            ) {
                // Sheets of paper: a hairline edge and a soft shadow keep a dark page apart from the dark screen.
                val edge = palette.paperBorder.toComposeColor().copy(alpha = 0.7f)
                val paper = Modifier.shadow(3.dp, RectangleShape).border(1.dp, edge)
                Sheet(stringResource(if (hasCover) R.string.cover else R.string.add_cover), face == Face.COVER, {
                    face = Face.COVER
                    aiOpen = false
                    if (!coverOn && coverT != null) coverOn = true
                }) {
                    if (hasCover) {
                        Box(paper) { Thumb(coverT, coverKey!!, coverStyle.settings, coverPaper, w, h, keep = false) }
                    } else {
                        Dashed(w, h) { Icon(XnotesIcons.plus, null, tint = dim, modifier = Modifier.size(22.dp)) }
                    }
                }
                if (!coverOnly) {
                    Sheet(stringResource(R.string.pages), face == Face.PAGES, { face = Face.PAGES; aiOpen = false }) {
                        // Every page after the cover, so a second sheet shows behind the first.
                        val lift = 4.dp
                        val pw = w - lift
                        val ph = pw * aspect
                        Box(Modifier.size(w, ph + lift)) {
                            Box(Modifier.offset(lift, lift).size(pw, ph).background(ColorMath.darken(look.paper, 0.06).toComposeColor()).border(1.dp, edge))
                            Box(paper) { Thumb(pageT, pageKey, style.settings, look.paper, pw, ph, keep = false) }
                        }
                    }
                }
            }
        }

        @Composable
        fun FaceRow(modifier: Modifier) {
            Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(faceTitle, color = text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (canCustomize) {
                    Row(
                        Modifier
                            .height(40.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(if (customOpen) selBg else panel.raised)
                            .semantics { selected = customOpen }
                            .clickable(role = Role.Button) { customOpen = true }
                            .padding(start = 12.dp, end = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(XnotesIcons.sliders, null, tint = if (customOpen) selFg else accent, modifier = Modifier.size(16.dp))
                        Text(stringResource(R.string.customize), color = if (customOpen) selFg else text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        // The tags at least two page templates share, most shared first.
        val tags = remember(pages) {
            pages.flatMap { it.template.tags }.groupingBy { it }.eachCount().filter { it.value >= 2 }
                .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key }
        }
        fun tagLabel(t: String) = t.replaceFirstChar { it.uppercase() }

        @Composable
        fun Toolbar(segmentW: Dp, allLabel: String) {
            Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // The cover picker of an open note styles its cover alone, so it has no Pages to switch to.
                if (!coverOnly) {
                    Box(Modifier.width(segmentW)) {
                        PanelSegmented(
                            listOf(stringResource(R.string.pages), stringResource(R.string.cover)),
                            if (face == Face.PAGES) 0 else 1,
                            { face = if (it == 0) Face.PAGES else Face.COVER },
                            height = 36.dp,
                            sunken = false,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                if (face == Face.PAGES && tags.isNotEmpty()) {
                    Box {
                        var open by remember { mutableStateOf(false) }
                        Row(
                            Modifier
                                .height(40.dp)
                                .clip(MaterialTheme.shapes.medium)
                                .clickable(role = Role.DropdownList) { open = true }
                                .padding(start = 12.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(tag?.let(::tagLabel) ?: allLabel, color = dim, fontSize = 13.sp, maxLines = 1)
                            Icon(XnotesIcons.chevronDown, null, tint = dim, modifier = Modifier.size(16.dp))
                        }
                        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                            ChoiceMenuItem(tag == null, onClick = { tag = null; open = false }) { fg -> Text(stringResource(R.string.all_templates), color = fg) }
                            for (t in tags) ChoiceMenuItem(tag == t, onClick = { tag = t; open = false }) { fg -> Text(tagLabel(t), color = fg) }
                        }
                    }
                }
                Box {
                    var open by remember { mutableStateOf(false) }
                    RoundButton(XnotesIcons.plus, stringResource(R.string.add_template), 40.dp) { open = true }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        val kind = if (face == Face.COVER) TemplateKind.COVER else TemplateKind.PAGE
                        MenuItem(stringResource(R.string.import_template_file), XnotesIcons.importDoc) { open = false; onImportTemplate() }
                        MenuItem(stringResource(if (kind == TemplateKind.COVER) R.string.cover_from_image else R.string.page_from_image), XnotesIcons.image) {
                            open = false
                            onTemplateFromImage(kind)
                        }
                        MenuItem(stringResource(R.string.tab_generate_ai), XnotesIcons.magicWand) { open = false; aiOpen = true }
                    }
                }
            }
        }

        @Composable
        fun AiHeader() {
            Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RoundButton(XnotesIcons.prev, stringResource(R.string.back), 40.dp) { aiOpen = false }
                Text(stringResource(R.string.tab_generate_ai), color = text, fontSize = 17.sp, fontWeight = FontWeight.Medium)
            }
        }

        @Composable
        fun Tile(label: String, selected: Boolean, onClick: () -> Unit, onRemove: (() -> Unit)?, framed: Boolean = true, thumb: @Composable (Dp, Dp) -> Unit) {
            var menu by remember { mutableStateOf(false) }
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.extraSmall)
                    .semantics { this.selected = selected }
                    .combinedClickable(onClick = onClick, onLongClick = onRemove?.let { { menu = true } }),
            ) {
                // The ring sits outside the picture, so the picture keeps one size and is never drawn again for a pick.
                Box(Modifier.fillMaxWidth().border(2.dp, if (selected) accent else Color.Transparent, RoundedCornerShape(7.dp)).padding(5.dp)) {
                    BoxWithConstraints(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio((pageMm.first / pageMm.second).toFloat())
                            .clip(RoundedCornerShape(3.dp))
                            .then(if (framed) Modifier.border(1.dp, border, RoundedCornerShape(3.dp)) else Modifier),
                    ) { thumb(maxWidth, maxHeight) }
                }
                Spacer(Modifier.size(4.dp))
                Text(
                    label,
                    color = if (selected) accent else dim,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuItem(stringResource(R.string.remove_template), XnotesIcons.trash, danger = true, onClick = { menu = false; onRemove?.invoke() })
                }
            }
        }

        // The grid's builder is not composable, so its words are looked up here.
        val sImported = stringResource(R.string.templates_imported)
        val sNone = stringResource(R.string.none)

        fun LazyGridScope.heading(title: String) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(title, color = dim, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp, top = 12.dp))
            }
        }

        fun LazyGridScope.gallery() {
            if (face == Face.PAGES) {
                fun shown(e: TemplateLibrary.Entry) = tag == null || tag in e.template.tags
                val listed = pages.filter { (it.source == TemplateLibrary.Source.BUILT_IN || it.source == TemplateLibrary.Source.BUNDLED) && shown(it) }
                val imported = pages.filter { it.source == TemplateLibrary.Source.IMPORTED && shown(it) }
                if (tag == null) item(key = "none") {
                    Tile(sNone, pageKey == PageTemplates.NONE, { pickPage(PageTemplates.NONE) }, null) { w, h -> Thumb(null, PageTemplates.NONE, TemplateSettings(), look.paper, w, h, keep = true) }
                }
                items(listed, key = { it.key }) { e ->
                    Tile(e.template.name, pageKey == e.key, { pickPage(e.key) }, null) { w, h -> PageThumb(e, w, h) }
                }
                if (imported.isNotEmpty()) {
                    heading(sImported)
                    items(imported, key = { it.key }) { e ->
                        Tile(e.template.name, pageKey == e.key, { pickPage(e.key) }, {
                            editor.removeTemplate(e.key)
                            if (pageKey == e.key) pickPage(PageTemplates.NONE)
                        }) { w, h -> PageThumb(e, w, h) }
                    }
                }
            } else {
                val bundled = covers.filter { it.source != TemplateLibrary.Source.IMPORTED }
                val imported = covers.filter { it.source == TemplateLibrary.Source.IMPORTED }
                // None is the note without a cover, drawn as the empty frame the note shows; picking a design turns one
                // on. A cover being changed has no None here: the pages panel's Remove cover does that.
                if (!coverOnly) item(key = "none") {
                    Tile(sNone, !hasCover, { coverOn = false; face = Face.COVER }, null, framed = false) { w, h -> Dashed(w, h) {} }
                }
                items(bundled, key = { it.key }) { e ->
                    Tile(e.template.name, hasCover && coverKey == e.key, { pickCover(e.key) }, null) { w, h -> CoverThumb(e, w, h) }
                }
                if (imported.isNotEmpty()) {
                    heading(sImported)
                    items(imported, key = { it.key }) { e ->
                        Tile(e.template.name, hasCover && coverKey == e.key, { pickCover(e.key) }, {
                            editor.removeTemplate(e.key)
                            if (coverKey == e.key) coverOn = false
                        }) { w, h -> CoverThumb(e, w, h) }
                    }
                }
            }
        }

        /** Where the picked tile sits in [f]'s gallery as [gallery] lays it out with no tag chosen, or null. */
        fun pickedIndex(f: Face): Int? {
            fun at(base: Int, list: List<TemplateLibrary.Entry>, key: String?) = list.indexOfFirst { it.key == key }.takeIf { it >= 0 }?.let { base + it }
            if (f == Face.PAGES) {
                if (pageKey == PageTemplates.NONE) return 0
                val listed = pages.filter { it.source == TemplateLibrary.Source.BUILT_IN || it.source == TemplateLibrary.Source.BUNDLED }
                val imported = pages.filter { it.source == TemplateLibrary.Source.IMPORTED }
                // None, the listed ones, then Imported's heading.
                return at(1, listed, pageKey) ?: at(2 + listed.size, imported, pageKey)
            }
            val bundled = covers.filter { it.source != TemplateLibrary.Source.IMPORTED }
            val imported = covers.filter { it.source == TemplateLibrary.Source.IMPORTED }
            val first = if (coverOnly) 0 else 1
            if (!hasCover) return if (coverOnly) null else 0
            return at(first, bundled, coverKey) ?: at(first + bundled.size + 1, imported, coverKey)
        }

        // Customize: the picked face's name with Reset once it differs from how the screen began, then its cards.
        @Composable
        fun CustomizeContent() {
            val start = if (face == Face.PAGES) editor.newNoteStyle.withTemplate(style.template, PageTemplates.NONE) else PageStyle(template = coverStyle.template)
            val changed = if (face == Face.PAGES) style != start else hasCover && coverStyle != start
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(faceTitle, color = text, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 8.dp))
                if (changed) PanelChip(stringResource(R.string.reset)) { if (face == Face.PAGES) style = start else coverStyle = start }
            }
            val about = if (face == Face.PAGES) pageT?.description else coverT?.takeIf { hasCover }?.description
            if (!about.isNullOrBlank()) {
                Text(about, color = dim, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(horizontal = 4.dp))
            }
            // The screen is a window of its own, so there is no note under it to sample a colour from.
            CompositionLocalProvider(LocalEyedropper provides false) {
                if (face == Face.PAGES) {
                    TemplateCards(pageT, style, null, look, DPI, editor, withPaper = true, withInk = true) { style = it }
                } else if (hasCover) {
                    TemplateCards(coverT, coverStyle, null, TemplateLook.of(coverStyle, null, look.paper), DPI, editor, withPaper = false, withInk = false) { coverStyle = it }
                }
            }
        }

        val pagesGrid = rememberLazyGridState()
        val coverGrid = rememberLazyGridState()
        val aiScroll = rememberScrollState()
        val revealed = remember { HashSet<Face>() }

        // Messages show here while the screen is open: the main screen's snackbar would sit beneath it.
        val snackbar = remember { SnackbarHostState() }
        DisposableEffect(editor) {
            editor.messageHosts++
            onDispose { editor.messageHosts-- }
        }
        LaunchedEffect(editor.message) { editor.showMessage(snackbar) }

        // A press anywhere but a text field ends the typing: the caret goes and the keyboard closes.
        // Back closes the keyboard without telling the field, so the keyboard going away ends editing too.
        val imeVisible = WindowInsets.isImeVisible
        var imeWasVisible by remember { mutableStateOf(false) }
        LaunchedEffect(imeVisible) {
            if (imeVisible) {
                imeWasVisible = true
            } else if (imeWasVisible) {
                imeWasVisible = false
                focusManager.clearFocus()
            }
        }
        // Back steps out of Generate using AI before it leaves the screen.
        BackHandler(enabled = aiOpen && !customOpen) { aiOpen = false }
        Surface(
            // Read before the insets' padding, a press shares the fields' root coordinates.
            Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (fieldBounds.values.none { it.contains(down.position) }) focusManager.clearFocus()
                }
            }.windowInsetsPadding(WindowInsets.systemBars),
            color = panel.sheet,
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val screenH = maxHeight
                val wide = maxWidth >= 720.dp
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().height(TOP_BAR).padding(start = if (wide) 16.dp else 12.dp, end = if (wide) GUTTER else 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(if (wide) 16.dp else 10.dp),
                    ) {
                        RoundButton(XnotesIcons.prev, stringResource(R.string.close_new_note), 40.dp, onClick = onClose)
                        val titleSize = if (wide) 22.sp else 18.sp
                        if (coverOnly) {
                            Text(stringResource(if (coverEdit?.current == null) R.string.add_cover else R.string.change_cover_title), color = text, fontSize = titleSize, modifier = Modifier.weight(1f))
                        } else Column(Modifier.weight(1f)) {
                            val nameStyle = TextStyle(color = text, fontSize = titleSize)
                            BasicTextField(
                                value = name,
                                onValueChange = { v ->
                                    val caretOnly = v.text == name.text
                                    name = if (caretOnly && android.os.SystemClock.uptimeMillis() < keepAllUntil) {
                                        v.copy(selection = TextRange(0, v.text.length))
                                    } else v
                                },
                                singleLine = true,
                                textStyle = nameStyle,
                                cursorBrush = SolidColor(accent),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                // Done only ends the typing, so the name can come first and the rest after.
                                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                                decorationBox = { field ->
                                    // A cleared name shows, dimmed, the one the note gets anyway.
                                    Box {
                                        if (name.text.isEmpty()) Text(initialName, style = nameStyle.copy(color = dim.copy(alpha = 0.5f)), maxLines = 1)
                                        field()
                                    }
                                },
                                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth()
                                    .onGloballyPositioned { fieldBounds["name"] = it.boundsInRoot() }
                                    .padding(vertical = 4.dp)
                                    .onFocusChanged {
                                        nameFocused = it.isFocused
                                        if (it.isFocused && !selectedOnce) {
                                            selectedOnce = true
                                            keepAllUntil = android.os.SystemClock.uptimeMillis() + 500
                                            name = name.copy(selection = TextRange(0, name.text.length))
                                        }
                                    },
                            )
                            // The line under the name shows only while it is being edited.
                            Box(Modifier.widthIn(max = 480.dp).fillMaxWidth().height(2.dp).background(if (nameFocused) accent else Color.Transparent))
                            if (error != null) Text(error, color = palette.danger.toComposeColor(), fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                        }
                        Button(
                            onClick = { create() },
                            shape = MaterialTheme.shapes.medium,
                            // While the note is written the button stays gold and spins; create() ignores another tap.
                            enabled = if (coverOnly) hasCover else sizeOk,
                            colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = palette.onAccent.toComposeColor()),
                        ) {
                            if (busy) {
                                CircularProgressIndicator(Modifier.size(18.dp), color = palette.onAccent.toComposeColor(), strokeWidth = 2.dp)
                            } else {
                                Icon(XnotesIcons.check, null, modifier = Modifier.size(18.dp))
                            }
                            Spacer(Modifier.size(8.dp))
                            Text(stringResource(if (coverOnly) R.string.done else R.string.create), fontWeight = FontWeight.Medium)
                        }
                    }
                    if (wide) {
                        Row(Modifier.weight(1f).fillMaxWidth().padding(horizontal = GUTTER), horizontalArrangement = Arrangement.spacedBy(GUTTER)) {
                            Column(
                                Modifier.width(NOTE_W).fillMaxHeight().verticalScroll(rememberScrollState()).padding(top = 28.dp, bottom = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(22.dp),
                            ) {
                                Sheets(150.dp, 214.dp)
                                if (!coverOnly) SizeRow()
                                FaceRow(Modifier.width(312.dp))
                            }
                            Column(Modifier.weight(1f).fillMaxHeight()) {
                                if (aiOpen) {
                                    AiHeader()
                                    AiTemplateTab(
                                        coverOnly, wide = true, onImport = onImportTemplate,
                                        modifier = Modifier.fillMaxSize().verticalScroll(aiScroll).padding(start = 4.dp, end = 4.dp, top = 20.dp, bottom = 40.dp),
                                    )
                                } else {
                                    Toolbar(220.dp, stringResource(R.string.all_templates))
                                    // Each face keeps its own scroll, and the first time one shows, its picked tile comes into view.
                                    val grid = if (face == Face.PAGES) pagesGrid else coverGrid
                                    LaunchedEffect(face) {
                                        if (!revealed.add(face)) return@LaunchedEffect
                                        val index = pickedIndex(face) ?: return@LaunchedEffect
                                        val info = snapshotFlow { grid.layoutInfo }.first { it.totalItemsCount > index }
                                        val seen = info.visibleItemsInfo.firstOrNull { it.index == index }
                                        if (seen == null || seen.offset.y + seen.size.height > info.viewportEndOffset) {
                                            grid.scrollToItem(index)
                                            grid.scrollBy(-info.viewportSize.height / 3f)
                                        }
                                    }
                                    key(face) {
                                        LazyVerticalGrid(
                                            columns = GridCells.Adaptive(112.dp),
                                            modifier = Modifier.fillMaxSize(),
                                            state = grid,
                                            contentPadding = PaddingValues(top = 16.dp, bottom = 40.dp),
                                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                                            verticalArrangement = Arrangement.spacedBy(14.dp),
                                        ) { gallery() }
                                    }
                                }
                            }
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(100.dp),
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Column(
                                    Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(16.dp),
                                ) {
                                    Sheets(100.dp, 140.dp)
                                    if (!coverOnly) SizeRow()
                                    FaceRow(Modifier.fillMaxWidth().padding(horizontal = 4.dp))
                                }
                            }
                            item(span = { GridItemSpan(maxLineSpan) }) { if (aiOpen) AiHeader() else Toolbar(168.dp, stringResource(R.string.tag_all)) }
                            if (aiOpen) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    AiTemplateTab(coverOnly, wide = false, onImport = onImportTemplate, modifier = Modifier.padding(top = 12.dp))
                                }
                            } else gallery()
                        }
                    }
                }

                // Beside the note on a wide screen, so the sheets show every change; a tap elsewhere or Back puts it away.
                if (wide && customOpen) {
                    BackHandler { customOpen = false }
                    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { customOpen = false } })
                    val shape = MaterialTheme.shapes.large
                    ProvideTextStyle(LocalTextStyle.current.copy(lineHeight = 1.3.em)) {
                        Column(
                            Modifier
                                .padding(start = GUTTER + NOTE_W + GUTTER, top = TOP_BAR)
                                .width(PANEL_WIDTH + 24.dp)
                                .heightIn(max = screenH - TOP_BAR - 16.dp)
                                .shadow(6.dp, shape)
                                .background(panel.sheet, shape)
                                .border(1.dp, border, shape)
                                .clip(shape)
                                .pointerInput(Unit) { detectTapGestures { } }
                                .verticalScroll(rememberScrollState())
                                .padding(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) { CustomizeContent() }
                    }
                }
                // On a narrow screen it rises from the bottom only as far as the sheets, which stay in view above it.
                if (!wide && customOpen) {
                    ModalBottomSheet(
                        onDismissRequest = { customOpen = false },
                        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                        shape = MaterialTheme.shapes.large.copy(bottomStart = CornerSize(0), bottomEnd = CornerSize(0)),
                        containerColor = panel.sheet,
                        dragHandle = { BottomSheetDefaults.DragHandle(color = dim.copy(alpha = 0.4f)) },
                    ) {
                        ProvideTextStyle(LocalTextStyle.current.copy(lineHeight = 1.3.em)) {
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = screenH * 0.6f)
                                    .verticalScroll(rememberScrollState())
                                    .padding(start = 12.dp, end = 12.dp, bottom = 24.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) { CustomizeContent() }
                        }
                    }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}
