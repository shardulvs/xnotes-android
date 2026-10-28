package com.xnotes.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import com.xnotes.settings.SwatchRole
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.ColorMath
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
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

/** A length in mm as it is typed: no ".0" on a whole number. */
private fun mmText(v: Double): String =
    if (v == Math.floor(v)) v.toInt().toString() else String.format(java.util.Locale.ROOT, "%.1f", v)

/** The screen's cover part alone, for a note that exists: its page size, its cover's style if any, and where the choice goes. */
internal class CoverEdit(val pageMm: Pair<Double, Double>, val current: PageStyle?, val onDone: (PageStyle) -> Unit)

/**
 * The full-screen New note. The note sits on the left: its cover and its pages side by side (a tap
 * picks which one to style), their size and orientation, then the picked one's colours and
 * settings. The gallery sits on the right with Pages and Cover tabs, and a Generate using AI tab
 * that has a chat assistant write a template ([AiTemplateTab]). Narrow screens stack the same
 * parts and move the settings into a bottom sheet. Nothing is written until Create. Given a
 * [coverEdit], it is that note's cover picker instead: the cover alone, and Done.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun NewNoteScreen(
    editor: Editor,
    folderName: String,
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
        val text = palette.text.toComposeColor()
        val dim = palette.textDim.toComposeColor()
        val accent = palette.accent.toComposeColor()
        val border = palette.border.toComposeColor()
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
        // The Generate using AI tab takes the gallery's place; the note keeps showing the face it styles.
        var aiOpen by remember { mutableStateOf(false) }
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
                    .padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.border(2.dp, if (selected) accent else Color.Transparent, RoundedCornerShape(5.dp)).padding(4.dp)) { content() }
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
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
                content = content,
            )
        }

        @Composable
        fun MmField(value: String, label: String, onChange: (String) -> Unit) {
            val ok = value.toDoubleOrNull()?.let { it in range } == true
            var focused by remember { mutableStateOf(false) }
            DisposableEffect(label) { onDispose { fieldBounds.remove(label) } }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = TextStyle(color = text, fontSize = 14.sp, textAlign = TextAlign.Center),
                cursorBrush = SolidColor(accent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                modifier = Modifier
                    .width(64.dp)
                    .height(40.dp)
                    .semantics { contentDescription = label }
                    .onGloballyPositioned { fieldBounds[label] = it.boundsInRoot() }
                    .onFocusChanged { focused = it.isFocused }
                    .border(
                        if (focused) 2.dp else 1.dp,
                        when {
                            !ok -> palette.danger.toComposeColor()
                            focused -> accent
                            else -> border
                        },
                        MaterialTheme.shapes.small,
                    )
                    .background(palette.menuBg.toComposeColor(), MaterialTheme.shapes.small)
                    .padding(vertical = 10.dp),
            )
        }

        @Composable
        fun OrientationButton(o: Orientation, desc: String) {
            val on = (o == Orientation.PORTRAIT) == portrait
            Box(
                Modifier
                    .size(44.dp, 38.dp)
                    .background(if (on) palette.selectionBackground.toComposeColor() else Color.Transparent)
                    .semantics { contentDescription = desc; selected = on }
                    .clickable { setOrientation(o) },
                contentAlignment = Alignment.Center,
            ) {
                val fg = if (on) palette.selectionForeground.toComposeColor() else dim
                Box(Modifier.size(if (o == Orientation.PORTRAIT) 11.dp else 17.dp, if (o == Orientation.PORTRAIT) 17.dp else 11.dp).border(2.dp, fg, RoundedCornerShape(2.dp)))
            }
        }

        @Composable
        fun FormatRow() = Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box {
                    var open by remember { mutableStateOf(false) }
                    Row(
                        Modifier
                            .height(40.dp)
                            .clip(MaterialTheme.shapes.small)
                            .border(1.dp, border, MaterialTheme.shapes.small)
                            .background(palette.menuBg.toComposeColor())
                            .clickable { open = true }
                            .padding(start = 14.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(pageSizeLabel(size), color = text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        if (!custom) Text(stringResource(R.string.page_size_mm, mmText(pageMm.first.roundToInt().toDouble()), mmText(pageMm.second.roundToInt().toDouble())), color = dim, fontSize = 13.sp)
                        Icon(XnotesIcons.chevronDown, null, tint = dim, modifier = Modifier.size(18.dp))
                    }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        for (s in PageSize.entries.filter { it != PageSize.CUSTOM } + PageSize.CUSTOM) {
                            val dims = if (s == PageSize.CUSTOM) "" else {
                                val (a, b) = if (portrait) s.shortMm to s.longMm else s.longMm to s.shortMm
                                stringResource(R.string.page_size_mm, mmText(a.roundToInt().toDouble()), mmText(b.roundToInt().toDouble()))
                            }
                            DropdownMenuItem(
                                text = {
                                    Row(Modifier.widthIn(min = 220.dp)) {
                                        Text(pageSizeLabel(s), Modifier.weight(1f))
                                        Text(dims, color = dim)
                                    }
                                },
                                leadingIcon = {
                                    if (s == size) Icon(XnotesIcons.check, null, tint = accent, modifier = Modifier.size(18.dp)) else Spacer(Modifier.size(18.dp))
                                },
                                onClick = {
                                    if (s == PageSize.CUSTOM && !custom) {
                                        customW = mmText(pageMm.first)
                                        customH = mmText(pageMm.second)
                                    }
                                    size = s
                                    open = false
                                },
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
                Row(Modifier.clip(MaterialTheme.shapes.small).border(1.dp, border, MaterialTheme.shapes.small)) {
                    OrientationButton(Orientation.PORTRAIT, stringResource(R.string.orientation_portrait))
                    Box(Modifier.width(1.dp).height(38.dp).background(border))
                    OrientationButton(Orientation.LANDSCAPE, stringResource(R.string.orientation_landscape))
                }
            }
            // Create waits for a size that fits; this says why.
            if (custom && (cw == null || ch == null)) {
                Text(
                    stringResource(R.string.custom_size_range, mmText(range.start), mmText(range.endInclusive)),
                    color = palette.danger.toComposeColor(), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        @Composable
        fun Sheets(maxW: Dp, maxH: Dp) = BoxWithConstraints(Modifier.fillMaxWidth()) {
            // Each sheet adds its ring's padding (4 dp) and its own (6 dp) on both sides; sized
            // without them, the second sheet was squeezed and its picture shrank inside it.
            val count = if (coverOnly) 1 else 2
            val fits = (maxWidth - 16.dp * (count - 1) - 20.dp * count) / count
            val aspect = (pageMm.second / pageMm.first).toFloat()
            var w = minOf(maxW, fits)
            var h = w * aspect
            if (h > maxH) {
                h = maxH
                w = maxH / aspect
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.Bottom,
            ) {
                // Sheets of paper: a hairline edge and a soft shadow keep a dark page apart from the dark stage.
                val edge = palette.paperBorder.toComposeColor().copy(alpha = 0.7f)
                val paper = Modifier.shadow(3.dp, RectangleShape).border(1.dp, edge)
                Sheet(stringResource(if (hasCover) R.string.cover else R.string.no_cover), face == Face.COVER, {
                    face = Face.COVER
                    aiOpen = false
                    if (!coverOn && coverT != null) coverOn = true
                }) {
                    if (hasCover) {
                        Box(paper) { Thumb(coverT, coverKey!!, coverStyle.settings, coverPaper, w, h, keep = false) }
                    } else {
                        Dashed(w, h) {
                            Icon(XnotesIcons.plus, null, tint = dim, modifier = Modifier.size(22.dp))
                            Text(stringResource(R.string.add_cover), color = dim, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        }
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
        fun ColourRow(caption: String, role: SwatchRole, value: Rgba?, onDefault: () -> Unit, onPick: (Rgba) -> Unit) {
            Spacer(Modifier.size(12.dp))
            StyleCaption(caption)
            Spacer(Modifier.size(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ModeChip(stringResource(R.string.default_choice), value == null, onDefault)
                SwatchCircles(editor, role, value, onPick)
            }
        }

        @Composable
        fun Tune() {
            Column {
                // Reset returns to how the screen began: the saved new-note style with this template, or the design as drawn.
                val start = if (face == Face.PAGES) editor.newNoteStyle.withTemplate(style.template, PageTemplates.NONE) else PageStyle(template = coverStyle.template)
                val changed = if (face == Face.PAGES) style != start else hasCover && coverStyle != start
                Row(Modifier.heightIn(min = 40.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(if (face == Face.PAGES) R.string.every_page else R.string.cover), color = dim, fontSize = 12.sp)
                        Text(
                            when {
                                face == Face.PAGES -> pageT?.name ?: stringResource(R.string.plain_paper)
                                hasCover -> coverT!!.name
                                else -> stringResource(R.string.no_cover)
                            },
                            color = text, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                        )
                        // What the template is for, or for a cover, whose picture it is.
                        val about = if (face == Face.PAGES) pageT?.description else coverT?.takeIf { hasCover }?.description
                        if (!about.isNullOrBlank()) {
                            Text(
                                about, color = dim, fontSize = 13.sp, lineHeight = 19.sp, maxLines = 4, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                    if (changed) {
                        TextButton(
                            onClick = { if (face == Face.PAGES) style = start else coverStyle = start },
                            colors = ButtonDefaults.textButtonColors(contentColor = accent),
                        ) { Text(stringResource(R.string.reset), fontWeight = FontWeight.Medium) }
                    }
                }
                if (face == Face.PAGES) {
                    ColourRow(stringResource(R.string.caption_paper), SwatchRole.PAPER, style.pageColor, { style = style.copy(pageColor = null) }) {
                        style = style.copy(pageColor = it)
                    }
                    if (pageT == null) {
                        Spacer(Modifier.size(12.dp))
                        Text(stringResource(R.string.pick_template_hint), color = dim, fontSize = 13.sp)
                    } else {
                        if (pageT.usesInk) {
                            // Circles hold the hue at full strength; the opacity slider sets the alpha.
                            ColourRow(stringResource(R.string.caption_pattern_colour), SwatchRole.LINES, style.patternColor?.copy(a = 255), {
                                style = style.copy(patternColor = null)
                            }) { style = style.copy(patternColor = it.copy(a = look.ink.a)) }
                            Spacer(Modifier.size(12.dp))
                            val pct = look.ink.a * 100f / 255f
                            StyleCaption(stringResource(R.string.caption_opacity_percent, pct.roundToInt()))
                            Slider(
                                value = pct,
                                onValueChange = { v -> style = style.copy(patternColor = look.ink.copy(a = (v / 100f * 255f).roundToInt().coerceIn(0, 255))) },
                                valueRange = 0f..100f,
                            )
                        }
                        if (pageT.usesAccent) {
                            ColourRow(stringResource(R.string.caption_accent_colour), SwatchRole.ACCENT, style.accentColor?.copy(a = 255), {
                                style = style.copy(accentColor = null)
                            }) { style = style.copy(accentColor = it.copy(a = look.accent.a)) }
                        }
                        TemplateParamControls(pageT, style, null, DPI, editor, captionGap = 6.dp) { style = it }
                    }
                } else if (hasCover) {
                    TemplateParamControls(coverT!!, coverStyle, null, DPI, editor, captionGap = 6.dp) { coverStyle = it }
                } else {
                    Spacer(Modifier.size(12.dp))
                    Text(stringResource(R.string.pick_cover_hint), color = dim, fontSize = 13.sp)
                }
                Spacer(Modifier.size(24.dp))
            }
        }

        @Composable
        fun Tab(label: String, on: Boolean, onClick: () -> Unit) {
            // The underline is drawn, not laid out: a full-width child would widen this tab over the others.
            Box(
                Modifier
                    .fillMaxHeight()
                    .semantics { selected = on }
                    .drawBehind {
                        if (on) {
                            val h = 3.dp.toPx()
                            drawRoundRect(accent, topLeft = Offset(0f, this.size.height - h), size = Size(this.size.width, h), cornerRadius = CornerRadius(h))
                        }
                    }
                    .clickable(onClick = onClick)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) { Text(label, color = if (on) accent else dim, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
        }

        @Composable
        fun Tabs() {
            Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 12.dp)) {
                // The cover picker of an open note styles its cover alone, so it has no Pages tab.
                if (!coverOnly) Tab(stringResource(R.string.pages), !aiOpen && face == Face.PAGES) { face = Face.PAGES; aiOpen = false }
                Tab(stringResource(R.string.cover), !aiOpen && face == Face.COVER) { face = Face.COVER; aiOpen = false }
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
                BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio((pageMm.first / pageMm.second).toFloat())) {
                    val w = maxWidth
                    val h = maxHeight
                    // The ring draws over the picture's edge, so the picture keeps one size and is never drawn again for a pick.
                    Box(
                        Modifier
                            .fillMaxSize()
                            .then(
                                when {
                                    selected -> Modifier.border(2.dp, accent, RoundedCornerShape(3.dp))
                                    framed -> Modifier.border(1.dp, border, RoundedCornerShape(3.dp))
                                    else -> Modifier
                                },
                            )
                            .padding(1.dp),
                    ) { thumb(w - 2.dp, h - 2.dp) }
                    if (selected) {
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).background(accent, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { Icon(XnotesIcons.check, null, tint = palette.onAccent.toComposeColor(), modifier = Modifier.size(14.dp)) }
                    }
                }
                Spacer(Modifier.size(8.dp))
                Text(
                    label, color = if (selected) palette.selectionForeground.toComposeColor() else text,
                    fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.remove_template)) }, onClick = { menu = false; onRemove?.invoke() })
                }
            }
        }

        @Composable
        fun ActionTile(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
            Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.extraSmall).clickable(onClick = onClick)) {
                BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio((pageMm.first / pageMm.second).toFloat())) {
                    Dashed(maxWidth, maxHeight) { Icon(icon, null, tint = dim, modifier = Modifier.size(26.dp)) }
                }
                Spacer(Modifier.size(8.dp))
                Text(label, color = text, fontSize = 12.sp, maxLines = 2)
            }
        }

        // The grid's builder is not composable, so its headings are looked up here.
        val sBuiltIn = stringResource(R.string.templates_built_in)
        val sExamples = stringResource(R.string.templates_examples)
        val sImported = stringResource(R.string.templates_imported)
        val sImportedHelp = stringResource(R.string.templates_imported_help)
        val sDesigns = stringResource(R.string.cover_designs)

        fun LazyGridScope.heading(title: String, count: Int?, help: String? = null) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(top = 18.dp, bottom = 2.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(title, color = dim, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        if (count != null) Text(count.toString(), color = dim.copy(alpha = 0.75f), fontSize = 13.sp)
                    }
                    if (help != null) Text(help, color = dim, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }

        fun LazyGridScope.gallery() {
            if (face == Face.PAGES) {
                val tags = pages.flatMap { it.template.tags }.groupingBy { it }.eachCount().filter { it.value >= 2 }
                    .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).map { it.key }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ModeChip(stringResource(R.string.tag_all), tag == null) { tag = null }
                        for (t in tags) ModeChip(t.replaceFirstChar { it.uppercase() }, tag == t) { tag = t }
                    }
                }
                fun shown(e: TemplateLibrary.Entry) = tag == null || tag in e.template.tags
                val builtIn = pages.filter { it.source == TemplateLibrary.Source.BUILT_IN && shown(it) }
                val examples = pages.filter { it.source == TemplateLibrary.Source.BUNDLED && shown(it) }
                val imported = pages.filter { it.source == TemplateLibrary.Source.IMPORTED && shown(it) }
                if (tag == null || builtIn.isNotEmpty()) {
                    heading(sBuiltIn, builtIn.size)
                    if (tag == null) item {
                        Tile(stringResource(R.string.none), pageKey == PageTemplates.NONE, { pickPage(PageTemplates.NONE) }, null) { w, h -> Thumb(null, PageTemplates.NONE, TemplateSettings(), look.paper, w, h, keep = true) }
                    }
                    items(builtIn, key = { it.key }) { e ->
                        Tile(e.template.name, pageKey == e.key, { pickPage(e.key) }, null) { w, h -> PageThumb(e, w, h) }
                    }
                }
                if (examples.isNotEmpty()) {
                    heading(sExamples, examples.size)
                    items(examples, key = { it.key }) { e ->
                        Tile(e.template.name, pageKey == e.key, { pickPage(e.key) }, null) { w, h -> PageThumb(e, w, h) }
                    }
                }
                heading(sImported, null, sImportedHelp)
                items(imported, key = { it.key }) { e ->
                    Tile(e.template.name, pageKey == e.key, { pickPage(e.key) }, {
                        editor.removeTemplate(e.key)
                        if (pageKey == e.key) pickPage(PageTemplates.NONE)
                    }) { w, h ->
                        PageThumb(e, w, h)
                    }
                }
                item { ActionTile(XnotesIcons.importDoc, stringResource(R.string.import_template_file), onImportTemplate) }
                item { ActionTile(XnotesIcons.image, stringResource(R.string.page_from_image)) { onTemplateFromImage(TemplateKind.PAGE) } }
            } else {
                val bundled = covers.filter { it.source != TemplateLibrary.Source.IMPORTED }
                val imported = covers.filter { it.source == TemplateLibrary.Source.IMPORTED }
                heading(sDesigns, bundled.size)
                // None is the note without a cover, drawn as the empty frame the stage shows; picking a design turns one
                // on. A cover being changed has no None here: the pages panel's Remove cover does that.
                if (!coverOnly) item {
                    Tile(stringResource(R.string.none), !hasCover, {
                        coverOn = false
                        face = Face.COVER
                    }, null, framed = false) { w, h -> Dashed(w, h) {} }
                }
                items(bundled, key = { it.key }) { e ->
                    Tile(e.template.name, hasCover && coverKey == e.key, { pickCover(e.key) }, null) { w, h ->
                        CoverThumb(e, w, h)
                    }
                }
                heading(sImported, null, sImportedHelp)
                items(imported, key = { it.key }) { e ->
                    Tile(e.template.name, hasCover && coverKey == e.key, { pickCover(e.key) }, {
                        editor.removeTemplate(e.key)
                        if (coverKey == e.key) coverOn = false
                    }) { w, h ->
                        CoverThumb(e, w, h)
                    }
                }
                item { ActionTile(XnotesIcons.importDoc, stringResource(R.string.import_template_file), onImportTemplate) }
                item { ActionTile(XnotesIcons.image, stringResource(R.string.cover_from_image)) { onTemplateFromImage(TemplateKind.COVER) } }
            }
        }

        /** Where the picked tile sits in [f]'s gallery as [gallery] lays it out with no tag chosen, or null. */
        fun pickedIndex(f: Face): Int? {
            fun at(base: Int, list: List<TemplateLibrary.Entry>, key: String?) = list.indexOfFirst { it.key == key }.takeIf { it >= 0 }?.let { base + it }
            if (f == Face.PAGES) {
                val builtIn = pages.filter { it.source == TemplateLibrary.Source.BUILT_IN }
                val examples = pages.filter { it.source == TemplateLibrary.Source.BUNDLED }
                val imported = pages.filter { it.source == TemplateLibrary.Source.IMPORTED }
                // The tag chips, then Built-in's heading and its None tile.
                if (pageKey == PageTemplates.NONE) return 2
                at(3, builtIn, pageKey)?.let { return it }
                var next = 3 + builtIn.size
                if (examples.isNotEmpty()) {
                    at(next + 1, examples, pageKey)?.let { return it }
                    next += 1 + examples.size
                }
                return at(next + 1, imported, pageKey)
            }
            val bundled = covers.filter { it.source != TemplateLibrary.Source.IMPORTED }
            val imported = covers.filter { it.source == TemplateLibrary.Source.IMPORTED }
            val first = if (coverOnly) 1 else 2
            if (!hasCover) return if (coverOnly) null else 1
            return at(first, bundled, coverKey) ?: at(first + bundled.size + 1, imported, coverKey)
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
        Surface(
            // Read before the insets' padding, a press shares the fields' root coordinates.
            Modifier.fillMaxSize().pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (fieldBounds.values.none { it.contains(down.position) }) focusManager.clearFocus()
                }
            }.windowInsetsPadding(WindowInsets.systemBars),
            color = palette.panel.toComposeColor(),
        ) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val screenW = maxWidth
                val wide = screenW >= 720.dp
                Column(Modifier.fillMaxSize()) {
                    Row(
                        Modifier.fillMaxWidth().height(76.dp).padding(start = 8.dp, end = 20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        IconButton(onClick = onClose) { Icon(XnotesIcons.close, stringResource(R.string.close_new_note), tint = text) }
                        if (coverOnly) {
                            Text(stringResource(if (coverEdit?.current == null) R.string.add_cover else R.string.change_cover_title), color = text, fontSize = 22.sp, modifier = Modifier.weight(1f))
                        } else Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.new_note_in, folderName), color = dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val nameStyle = TextStyle(color = text, fontSize = 22.sp)
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
                            // The line under the name stays quiet until the name is being edited.
                            Box(Modifier.widthIn(max = 480.dp).fillMaxWidth().height(2.dp), contentAlignment = Alignment.BottomStart) {
                                Box(Modifier.fillMaxWidth().height(if (nameFocused) 2.dp else 1.dp).background(if (nameFocused) accent else dim.copy(alpha = 0.4f)))
                            }
                            if (error != null) Text(error, color = palette.danger.toComposeColor(), fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                        }
                        Button(
                            onClick = { create() },
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
                    HorizontalDivider(color = border)
                    if (wide) {
                        val stageW = (screenW * 0.38f).coerceIn(340.dp, 520.dp)
                        Row(Modifier.weight(1f).fillMaxWidth()) {
                            Column(
                                Modifier.width(stageW).fillMaxHeight().background(palette.bg.toComposeColor()).padding(horizontal = 24.dp, vertical = 20.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                Sheets(260.dp, 300.dp)
                                if (!coverOnly) FormatRow()
                                HorizontalDivider(color = border)
                                // The settings fade at an edge with more beyond it, so a cut-off row reads as scrollable.
                                val tuneScroll = rememberScrollState()
                                val stage = palette.bg.toComposeColor()
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .drawWithContent {
                                            drawContent()
                                            val fade = 28.dp.toPx()
                                            val box = this.size
                                            if (tuneScroll.canScrollForward) {
                                                drawRect(
                                                    Brush.verticalGradient(listOf(Color.Transparent, stage), startY = box.height - fade, endY = box.height),
                                                    topLeft = Offset(0f, box.height - fade), size = Size(box.width, fade),
                                                )
                                            }
                                            if (tuneScroll.canScrollBackward) {
                                                drawRect(Brush.verticalGradient(listOf(stage, Color.Transparent), endY = fade / 2), size = Size(box.width, fade / 2))
                                            }
                                        }
                                        .verticalScroll(tuneScroll),
                                ) { Tune() }
                            }
                            Column(Modifier.weight(1f).fillMaxHeight()) {
                                Tabs()
                                HorizontalDivider(color = border)
                                if (aiOpen) {
                                    AiTemplateTab(
                                        coverOnly, wide = true, onImport = onImportTemplate,
                                        modifier = Modifier.fillMaxSize().verticalScroll(aiScroll).padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 40.dp),
                                    )
                                } else {
                                    // Each tab keeps its own scroll, and the first time one shows, its picked tile comes into view.
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
                                            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 40.dp),
                                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                                            verticalArrangement = Arrangement.spacedBy(16.dp),
                                        ) { gallery() }
                                    }
                                }
                            }
                        }
                    } else {
                        var tuneOpen by remember { mutableStateOf(false) }
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(100.dp),
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                item(span = { GridItemSpan(maxLineSpan) }) {
                                    Column(
                                        Modifier.fillMaxWidth().padding(top = 12.dp).background(palette.bg.toComposeColor(), RoundedCornerShape(12.dp)).padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Sheets(120.dp, 170.dp)
                                        if (!coverOnly) FormatRow()
                                    }
                                }
                                item(span = { GridItemSpan(maxLineSpan) }) { Tabs() }
                                if (aiOpen) {
                                    item(span = { GridItemSpan(maxLineSpan) }) {
                                        AiTemplateTab(coverOnly, wide = false, onImport = onImportTemplate, modifier = Modifier.padding(top = 12.dp))
                                    }
                                } else gallery()
                            }
                            Row(
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .fillMaxWidth()
                                    .background(palette.menuBg.toComposeColor(), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                                    .clickable { tuneOpen = true }
                                    .padding(horizontal = 20.dp, vertical = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        when {
                                            face == Face.PAGES -> pageT?.name ?: stringResource(R.string.plain_paper)
                                            hasCover -> coverT!!.name
                                            else -> stringResource(R.string.no_cover)
                                        },
                                        color = text, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                                    )
                                    Text(stringResource(if (face == Face.PAGES) R.string.every_page else R.string.cover), color = dim, fontSize = 12.sp)
                                }
                                Text(stringResource(R.string.customize), color = accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                        if (tuneOpen) {
                            ModalBottomSheet(onDismissRequest = { tuneOpen = false }, containerColor = palette.menuBg.toComposeColor()) {
                                Column(Modifier.padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) { Tune() }
                            }
                        }
                    }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}
