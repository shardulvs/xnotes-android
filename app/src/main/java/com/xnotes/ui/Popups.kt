package com.xnotes.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import com.xnotes.R
import com.xnotes.canvas.PdfColorFilter
import com.xnotes.canvas.ViewOverrides
import com.xnotes.canvas.ViewSettings
import com.xnotes.canvas.ViewingMode
import com.xnotes.core.model.FunctionSpec
import com.xnotes.core.model.PageEdge
import com.xnotes.core.model.PageMargins
import com.xnotes.core.model.PageTemplates
import com.xnotes.core.model.PageStyle
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.FontFace
import com.xnotes.core.tools.EraseMode
import com.xnotes.core.tools.MarkupMode
import com.xnotes.core.tools.ShapeConfig
import com.xnotes.core.tools.ShapeKind
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import com.xnotes.core.tools.ToolConversions
import com.xnotes.platform.FontCatalog
import com.xnotes.settings.Preferences
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlin.math.roundToInt

/**
 * Stroke-tool configuration popup (spec 10 §3): PRESSURE / SENSITIVITY, then the
 * tool's signature control: MULTIPLIER (calligraphy), SPEED (speed pen) or
 * TIP WIDTH PERCENTAGE (taper pen), then WIDTH, and a NEON toggle (with INTENSITY) on
 * any stroke tool but the highlighter.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolConfigPopup(editor: ToolPopupHost, tool: Tool, onDismiss: () -> Unit) {
    val base = remember { editor.toolConfig(tool) }
    var pressure by remember { mutableStateOf(base.pressureEnabled) }
    var sensitivity by remember { mutableStateOf(ToolConversions.minFactorToSensitivity(base.pressureMinFactor).toFloat()) }
    var multiplier by remember { mutableStateOf(ToolConversions.directionStrengthToMultiplier(base.directionStrength).toFloat()) }
    var speed by remember { mutableStateOf(ToolConversions.strengthToSpeed(base.speedStrength).toFloat()) }
    var taperTip by remember { mutableStateOf((base.taperMinFactor * 100).toFloat()) }
    var width by remember { mutableStateOf(base.baseWidth.toFloat()) }
    var glow by remember { mutableStateOf(base.neon) }
    var glowIntensity by remember { mutableStateOf(ToolConversions.neonStrengthToIntensity(base.neonStrength).toFloat()) }
    var dashLen by remember { mutableStateOf(base.dashLength.toFloat()) }
    var gapLen by remember { mutableStateOf(base.dashGap.toFloat()) }
    var straight by remember { mutableStateOf(base.straightLine) }
    var scale by remember { mutableStateOf(base.scale) }
    var intensity by remember { mutableStateOf(ToolConversions.highlighterAlphaToIntensity(base.highlighterAlpha).toFloat()) }
    var inverse by remember { mutableStateOf(base.highlighterInverse) }
    var colorOverride by remember { mutableStateOf(base.colorOverride) }
    var snapShapes by remember { mutableStateOf(editor.snapHeldToShapes) }

    fun emit() {
        val m = ToolConversions.sensitivityToMinFactor(sensitivity.toDouble())
        val ds = if (tool == Tool.CALLIGRAPHY) ToolConversions.multiplierToDirectionStrength(multiplier.toDouble()) else 0.0
        val sp = if (tool == Tool.SPEED) ToolConversions.speedToStrength(speed.toDouble()) else 0.0
        val tmf = if (tool == Tool.TAPER) taperTip.toDouble() / 100.0 else base.taperMinFactor
        val ha = if (tool == Tool.HIGHLIGHTER) ToolConversions.intensityToHighlighterAlpha(intensity.toDouble()) else base.highlighterAlpha
        editor.updateToolConfig(
            tool,
            base.copy(
                baseWidth = width.toDouble(),
                pressureEnabled = pressure,
                pressureMinFactor = m,
                directionStrength = ds,
                speedStrength = sp,
                taperMinFactor = tmf,
                neon = glow,
                neonStrength = ToolConversions.intensityToNeonStrength(glowIntensity.toDouble()),
                dashLength = dashLen.toDouble(),
                dashGap = gapLen.toDouble(),
                straightLine = straight,
                scale = scale,
                highlighterAlpha = ha,
                highlighterInverse = inverse,
                colorOverride = colorOverride,
            ),
        )
    }

    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Column(Modifier.width(250.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
            PopupTitle(stringResource(tool.labelRes))
            // COLOUR override: "Default" follows the toolbar's active ink colour; pick a hue to pin
            // this tool to it regardless of the toolbar selection.
            StyleCaption(stringResource(R.string.caption_colour))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ModeChip(stringResource(R.string.default_choice), colorOverride == null) { colorOverride = null; emit() }
                ColorPickerDot(
                    colorOverride,
                    custom = colorOverride != null,
                    onPick = { colorOverride = it; emit() },
                    dismissOnPick = false,
                ) { d, p ->
                    ColorPickerPopup(
                        initial = colorOverride ?: editor.hostToolbarColors.getOrNull(editor.hostActiveColorIndex),
                        recents = editor.hostRecentColors,
                        onDismiss = d,
                        onPick = p,
                    )
                }
            }
            Spacer(Modifier.size(12.dp))
            val hasPressure = tool == Tool.PEN || tool == Tool.CALLIGRAPHY || tool == Tool.SPEED || tool == Tool.TAPER
            if (hasPressure) {
                ToggleRow(stringResource(R.string.caption_pressure), pressure) { pressure = it; emit() }
                SliderRow(stringResource(R.string.caption_sensitivity), sensitivity, 0f..100f, enabled = pressure) { sensitivity = it; emit() }
            }
            if (tool == Tool.CALLIGRAPHY) {
                SliderRow(stringResource(R.string.caption_multiplier), multiplier, 1f..5f) { multiplier = it; emit() }
            }
            if (tool == Tool.SPEED) {
                SliderRow(stringResource(R.string.caption_speed), speed, 0f..100f) { speed = it; emit() }
            }
            if (tool == Tool.TAPER) {
                SliderRow(stringResource(R.string.caption_tip_width), taperTip, 0f..100f) { taperTip = it; emit() }
            }
            val range = ToolConversions.widthRange(tool)
            SliderRow(stringResource(R.string.caption_width), width, range.start.toFloat()..range.endInclusive.toFloat()) { width = it; emit() }
            // SCALE off: ink keeps a constant on-screen thickness whatever zoom you draw at.
            ToggleRow(stringResource(R.string.caption_scale), scale) { scale = it; emit() }
            // Global dwell shape detection (holding the pen still). Off for the highlighter, which never dwells.
            if (tool.isStroke && tool != Tool.HIGHLIGHTER) {
                ToggleRow(stringResource(R.string.caption_snap_to_shapes), snapShapes) { snapShapes = it; editor.snapHeldToShapes = it }
            }
            if (tool == Tool.DASHED) {
                SliderRow(stringResource(R.string.caption_dash), dashLen, 2f..40f) { dashLen = it; emit() }
                SliderRow(stringResource(R.string.caption_gap), gapLen, 2f..40f) { gapLen = it; emit() }
            }
            // The highlighter's strength (translucency) and an optional straight-segment lock
            // (for ruling/underlining).
            if (tool == Tool.HIGHLIGHTER) {
                SliderRow(stringResource(R.string.caption_intensity), intensity, 10f..90f) { intensity = it; emit() }
                ToggleRow(stringResource(R.string.caption_straight_line), straight) { straight = it; emit() }
                // INVERSE swaps the multiply blend for a screen one, so the marker lightens the
                // page instead of darkening it. A multiply has nothing to darken on a dark page.
                ToggleRow(stringResource(R.string.caption_inverse), inverse) { inverse = it; emit() }
            }
            // Glow is offered on every stroke tool except the highlighter (translucent) and the
            // dashed pen (it draws a line, not a fillable ribbon, so a halo has nothing to hug).
            if (tool.isStroke && tool != Tool.HIGHLIGHTER && tool != Tool.DASHED) {
                ToggleRow(stringResource(R.string.caption_neon), glow) { glow = it; emit() }
                if (glow) {
                    SliderRow(stringResource(R.string.caption_glow_intensity), glowIntensity, 0f..100f) { glowIntensity = it; emit() }
                }
            }
        }
    }
}

/**
 * Page-styles popup (spec 10): two tabs, "All Pages" (the document-wide override) and "Current
 * Page", each editing the paper colour and the template. Controls are tri-state: "Default"
 * leaves the field unset so it inherits the level below (page, document, then the global
 * page-colour preference or a built-in default). The All Pages template has no "Default", since
 * nothing sits below it but None. Customize opens the shown template on a page of its
 * own, with its colours and parameters. Like [ToolConfigPopup], the popup holds the edited style
 * locally and pushes each change to the [Editor] (which persists, but never undoes). The All Pages
 * tab also offers making its style the default stamped onto new notes, plus a Reset back to
 * all-Default.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StylesPopup(editor: Editor, onImportTemplate: () -> Unit = {}, onDismiss: () -> Unit) {
    var tab by remember { mutableStateOf(0) } // 0 = All Pages, 1 = Current Page
    var customizing by remember { mutableStateOf(false) }
    var docStyle by remember { mutableStateOf(editor.documentStyle) }
    var pageStyle by remember { mutableStateOf(editor.currentPageStyle) }
    val style = if (tab == 0) docStyle else pageStyle
    // "Default for new notes" shows once the All Pages style differs from the saved new-note
    // default, and stays for the rest of the popup session (so it doesn't vanish when checked).
    // An all-Default style hides it regardless (e.g. after Reset): there is nothing to save.
    var showNewNoteRow by remember { mutableStateOf(editor.documentStyle != editor.newNoteStyle) }
    fun apply(next: PageStyle) {
        if (tab == 0) {
            docStyle = next; editor.setDocumentStyle(next)
            if (next != editor.newNoteStyle) showNewNoteRow = true
        } else { pageStyle = next; editor.setCurrentPageStyle(next) }
    }

    // What this level shows with no template of its own: the document's, on the page tab.
    val inherited = if (tab == 0) PageTemplates.NONE else docStyle.template ?: PageTemplates.NONE
    val shownKey = style.template ?: inherited
    val shown = if (shownKey == PageTemplates.NONE) null else editor.templateFor(shownKey)
    // The level below whose parameters show through, when they belong to the shown template.
    val lower = docStyle.takeIf { tab == 1 && (it.template == null || PageTemplates.compatible(it.template, shownKey)) }
    val look = TemplateLook.of(style, if (tab == 1) docStyle else null, LocalPalette.current.paper)

    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        if (customizing && shown != null) {
            TemplateCustomizer(editor, shown, shownKey, tab, style, lower, look, ::apply) { customizing = false }
        } else Column(Modifier.width(286.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
            PopupTitle(stringResource(R.string.title_styles))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ModeChip(stringResource(R.string.all_pages), tab == 0) { tab = 0 }
                ModeChip(stringResource(R.string.current_page), tab == 1) { tab = 1 }
            }

            Spacer(Modifier.size(12.dp))
            StyleCaption(stringResource(R.string.caption_page_colour))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ModeChip(stringResource(R.string.default_choice), style.pageColor == null) { apply(style.copy(pageColor = null)) }
                pageColorPresets.forEach { c ->
                    ColorDot(c.toComposeColor(), style.pageColor == c) { apply(style.copy(pageColor = c)) }
                }
                ColorPickerDot(
                    style.pageColor,
                    custom = style.pageColor != null && style.pageColor !in pageColorPresets,
                    onPick = { apply(style.copy(pageColor = it)) },
                    dismissOnPick = false,
                ) { d, p -> PageColorGridPopup(style.pageColor, d, p) }
            }

            Spacer(Modifier.size(12.dp))
            StyleCaption(stringResource(R.string.caption_template))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // With nothing below All Pages, an unset template there already means None.
                if (tab == 1) ModeChip(stringResource(R.string.default_choice), style.template == null) { apply(style.withTemplate(null, inherited)) }
                val none = style.template == PageTemplates.NONE || (tab == 0 && style.template == null)
                ModeChip(stringResource(R.string.none), none) { apply(style.withTemplate(PageTemplates.NONE, inherited)) }
            }
            Spacer(Modifier.size(6.dp))
            val libraryVersion = TemplateLibraryUi.version
            val choices = remember(libraryVersion, tab) { editor.templateChoices() }
            TemplateStrip(
                entries = choices,
                selected = style.template,
                pageMm = editor.currentPageMm,
                ink = look.ink,
                accent = look.accent,
                paper = look.paper,
                onSelect = { apply(style.withTemplate(it, inherited)) },
                onImport = onImportTemplate,
                onRemove = { editor.removeTemplate(it) },
                onKeep = { editor.keepNoteTemplate(it) },
            )
            if (shown != null) {
                Spacer(Modifier.size(6.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        shown.name,
                        color = LocalPalette.current.text.toComposeColor(),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    ModeChip(stringResource(R.string.customize), false) { customizing = true }
                }
            }

            if (tab == 0) {
                Spacer(Modifier.size(8.dp))
                if (showNewNoteRow && !docStyle.isEmpty) {
                    // The checkbox's 48dp touch frame insets the drawn box; pull the row back to align it.
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().offset(x = (-14).dp)) {
                        Checkbox(
                            checked = !editor.newNoteStyle.isEmpty && docStyle == editor.newNoteStyle,
                            onCheckedChange = { on ->
                                editor.saveNewNoteStyle(if (on) docStyle else PageStyle())
                            },
                        )
                        Text(
                            stringResource(R.string.default_for_new_notes),
                            color = LocalPalette.current.text.toComposeColor(),
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(Modifier.size(4.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    ModeChip(stringResource(R.string.reset), false) { apply(PageStyle()) }
                }
            }
        }
    }
}

/** The new-note dialog's page style: page colour, then the template and its settings ([TemplateControls]). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PageStyleControls(editor: Editor, style: PageStyle, pageMm: Pair<Double, Double>, onChange: (PageStyle) -> Unit) {
    StyleCaption(stringResource(R.string.caption_page_colour))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ModeChip(stringResource(R.string.default_choice), style.pageColor == null) { onChange(style.copy(pageColor = null)) }
        pageColorPresets.forEach { c ->
            ColorDot(c.toComposeColor(), style.pageColor == c) { onChange(style.copy(pageColor = c)) }
        }
        ColorPickerDot(
            style.pageColor,
            custom = style.pageColor != null && style.pageColor !in pageColorPresets,
            onPick = { onChange(style.copy(pageColor = it)) },
            dismissOnPick = false,
        ) { d, p -> PageColorGridPopup(style.pageColor, d, p) }
    }

    Spacer(Modifier.size(12.dp))
    TemplateControls(editor, style, pageMm, onChange)
}

/**
 * The Styles popup's second page: [t] (keyed [key]) on a style level ([tab] as in [StylesPopup]),
 * with a live preview, its pattern and accent colours, and every parameter it declares. Reset
 * clears just these, keeping the level's template and paper colour.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TemplateCustomizer(
    editor: Editor,
    t: com.xnotes.core.template.Template,
    key: String,
    tab: Int,
    style: PageStyle,
    lower: PageStyle?,
    look: TemplateLook,
    apply: (PageStyle) -> Unit,
    onBack: () -> Unit,
) {
    val palette = LocalPalette.current
    val dpi = editor.documentDpi
    Column(Modifier.width(286.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(
            stringResource(R.string.back_customize),
            color = palette.accent.toComposeColor(),
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
            modifier = Modifier.clip(MaterialTheme.shapes.extraSmall).clickable(onClick = onBack).padding(vertical = 4.dp),
        )
        Text(
            t.name,
            color = palette.text.toComposeColor(),
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        StyleCaption(stringResource(if (tab == 0) R.string.all_pages else R.string.current_page))
        t.description?.let {
            Spacer(Modifier.size(4.dp))
            Text(it, color = palette.textDim.toComposeColor(), fontSize = 12.sp)
        }

        TemplateSettings(t, key, style, lower, look, editor.currentPageMm, dpi, apply)

        Spacer(Modifier.size(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            ModeChip(stringResource(R.string.reset), false) {
                apply(style.copy(patternColor = null, accentColor = null, spacing = null, params = null, colors = null))
            }
        }
    }
}

/** [t]'s live preview, pattern and accent colours and every parameter it declares, as [style] sets them over [lower]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TemplateSettings(
    t: com.xnotes.core.template.Template,
    key: String,
    style: PageStyle,
    lower: PageStyle?,
    look: TemplateLook,
    pageMm: Pair<Double, Double>,
    dpi: Int,
    apply: (PageStyle) -> Unit,
) {
    Spacer(Modifier.size(12.dp))
    val numbers = HashMap<String, Double>()
    lower?.params?.let(numbers::putAll)
    style.params?.let(numbers::putAll)
    val spacing = t.spacingParam
    (style.spacing ?: lower?.spacing)?.let { px -> if (spacing != null) numbers[spacing.name] = px * 25.4 / dpi }
    val colors = (lower?.colors ?: emptyMap()) + (style.colors ?: emptyMap())
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        TemplatePreview(
            t, key, pageMm, look.ink, look.accent, look.paper,
            com.xnotes.core.template.TemplateValues(numbers, colors), 112.dp,
        )
    }

    Spacer(Modifier.size(12.dp))
    StyleCaption(stringResource(R.string.caption_pattern_colour))
    Spacer(Modifier.size(4.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ModeChip(stringResource(R.string.default_choice), style.patternColor == null) { apply(style.copy(patternColor = null)) }
        ColorPickerDot(
            style.patternColor?.copy(a = 255), // show the hue at full strength; OPACITY sets the alpha
            custom = style.patternColor != null,
            onPick = { apply(style.copy(patternColor = it.copy(a = look.ink.a))) }, // keep current opacity
            dismissOnPick = false,
        ) { d, p -> PageColorGridPopup(style.patternColor?.copy(a = 255), d, p) }
    }
    Spacer(Modifier.size(12.dp))
    val opacityPct = look.ink.a * 100f / 255f
    StyleCaption(stringResource(R.string.caption_opacity_percent, opacityPct.roundToInt()))
    Slider(
        value = opacityPct,
        onValueChange = { pct ->
            apply(style.copy(patternColor = look.ink.copy(a = (pct / 100f * 255f).roundToInt().coerceIn(0, 255))))
        },
        valueRange = 0f..100f,
    )
    if (t.usesAccent) {
        Spacer(Modifier.size(4.dp))
        StyleCaption(stringResource(R.string.caption_accent_colour))
        Spacer(Modifier.size(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ModeChip(stringResource(R.string.default_choice), style.accentColor == null) { apply(style.copy(accentColor = null)) }
            ColorPickerDot(
                style.accentColor?.copy(a = 255),
                custom = style.accentColor != null,
                onPick = { apply(style.copy(accentColor = it.copy(a = look.accent.a))) },
                dismissOnPick = false,
            ) { d, p -> PageColorGridPopup(style.accentColor?.copy(a = 255), d, p) }
        }
    }

    TemplateParamControls(t, style, lower, dpi, apply)
}

/**
 * The new-note template: Default, None or any library template, and under a picked one its colours
 * and parameters as the Styles popup's Customize page has them. [pageMm] sizes the previews.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TemplateControls(editor: Editor, style: PageStyle, pageMm: Pair<Double, Double>, onChange: (PageStyle) -> Unit) {
    StyleCaption(stringResource(R.string.caption_template))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ModeChip(stringResource(R.string.default_choice), style.template == null) { onChange(style.withTemplate(null, PageTemplates.NONE)) }
        ModeChip(stringResource(R.string.none), style.template == PageTemplates.NONE) { onChange(style.withTemplate(PageTemplates.NONE, PageTemplates.NONE)) }
    }
    Spacer(Modifier.size(6.dp))
    val look = TemplateLook.of(style, null, LocalPalette.current.paper)
    val libraryVersion = TemplateLibraryUi.version
    val choices = remember(libraryVersion) { com.xnotes.platform.TemplateLibrary.all() }
    TemplateStrip(
        entries = choices,
        selected = style.template,
        pageMm = pageMm,
        ink = look.ink,
        accent = look.accent,
        paper = look.paper,
        onSelect = { onChange(style.withTemplate(it, PageTemplates.NONE)) },
        onImport = null,
        onRemove = { editor.removeTemplate(it) },
        onKeep = {},
    )
    val key = style.template?.takeIf { it != PageTemplates.NONE } ?: return
    val shown = com.xnotes.platform.TemplateLibrary.entry(key)?.template ?: return
    TemplateSettings(shown, key, style, null, look, pageMm, com.xnotes.core.model.PageSize.DEFAULT_DPI, onChange)
}

/**
 * Page-margins popup: two tabs — "All Pages" (the document-wide override) and "Current Page" — and
 * within each, one tab per edge with a slider for how much paper to add there, as a percentage of
 * the page's own width (left/right) or height (top/bottom). The extra space is ordinary page: it
 * takes the paper colour and the ruling, and nothing on the page moves when it grows. Like
 * [StylesPopup] the popup holds the edited value locally and pushes each change to the [Editor]
 * (which persists, but never undoes); "Default" clears an edge so it inherits the level below.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MarginsPopup(editor: Editor, onDismiss: () -> Unit) {
    var tab by remember { mutableStateOf(0) } // 0 = All Pages, 1 = Current Page
    var edge by remember { mutableStateOf(PageEdge.LEFT) }
    var docMargins by remember { mutableStateOf(editor.documentMargins) }
    var pageMargins by remember { mutableStateOf(editor.currentPageMargins) }
    val margins = if (tab == 0) docMargins else pageMargins
    fun apply(next: PageMargins) {
        if (tab == 0) {
            docMargins = next; editor.setDocumentMargins(next)
        } else {
            pageMargins = next; editor.setCurrentPageMargins(next)
        }
    }

    val own = margins.edge(edge)
    // An unset edge inherits: the current page falls back to the document, the document to none.
    val inherited = if (tab == 1) docMargins.edge(edge) else null
    val percent = ((own ?: inherited ?: 0.0) * 100).toFloat().coerceIn(0f, 100f)

    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Column(Modifier.width(286.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
            PopupTitle(stringResource(R.string.title_margins))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ModeChip(stringResource(R.string.all_pages), tab == 0) { tab = 0 }
                ModeChip(stringResource(R.string.current_page), tab == 1) { tab = 1 }
            }

            Spacer(Modifier.size(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PageEdge.entries.forEach { e ->
                    ModeChip(stringResource(e.labelRes), edge == e) { edge = e }
                }
            }

            Spacer(Modifier.size(12.dp))
            StyleCaption(stringResource(R.string.caption_value_percent, stringResource(edge.labelRes), percent.roundToInt()) + if (own == null) stringResource(R.string.default_suffix) else "")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip(stringResource(R.string.default_choice), own == null) { apply(margins.withEdge(edge, null)) }
                Slider(
                    value = percent,
                    onValueChange = { apply(margins.withEdge(edge, it.toDouble() / 100.0)) },
                    valueRange = 0f..100f,
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.size(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                ModeChip(stringResource(R.string.reset), false) { apply(PageMargins()) }
            }
        }
    }
}

/**
 * Controls for [t]'s parameters on a page-style level: the spacing parameter on the classic
 * spacing slider (content px), the others as sliders or colour pickers, each with a Default chip
 * that falls back to [lower] (the level below, when its values apply) and then the template.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TemplateParamControls(
    t: com.xnotes.core.template.Template,
    style: PageStyle,
    lower: PageStyle?,
    dpi: Int,
    apply: (PageStyle) -> Unit,
) {
    val k = dpi / 25.4
    t.spacingParam?.let { sp ->
        Spacer(Modifier.size(12.dp))
        val lo = ((sp.min ?: (sp.default / 4)) * k).toFloat()
        val hi = ((sp.max ?: (sp.default * 4)) * k).toFloat().coerceAtLeast(lo + 1f)
        val spacing = style.spacing ?: lower?.spacing ?: (sp.default * k)
        StyleCaption(stringResource(R.string.caption_spacing_px, spacing.roundToInt()) + if (style.spacing == null) stringResource(R.string.default_suffix) else "")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeChip(stringResource(R.string.default_choice), style.spacing == null) { apply(style.copy(spacing = null)) }
            Slider(
                value = spacing.toFloat().coerceIn(lo, hi),
                onValueChange = { apply(style.copy(spacing = it.toDouble())) },
                valueRange = lo..hi,
                modifier = Modifier.weight(1f),
            )
        }
    }
    for (p in t.params) {
        if (p === t.spacingParam) continue
        Spacer(Modifier.size(12.dp))
        val label = p.label ?: p.name.replaceFirstChar { it.uppercase() }
        if (p.type == com.xnotes.core.template.ParamType.COLOR) {
            val own = style.colors?.get(p.name)
            StyleCaption(label)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ModeChip(stringResource(R.string.default_choice), own == null) {
                    apply(style.copy(colors = style.colors?.minus(p.name)?.takeIf { it.isNotEmpty() }))
                }
                ColorPickerDot(
                    own,
                    custom = own != null,
                    onPick = { apply(style.copy(colors = (style.colors ?: emptyMap()) + (p.name to it))) },
                    dismissOnPick = false,
                ) { d, pick -> PageColorGridPopup(own, d, pick) }
            }
            continue
        }
        val own = style.params?.get(p.name)
        val value = p.clamp(own ?: lower?.params?.get(p.name) ?: p.default)
        val lo = p.min ?: if (p.default > 0) p.default / 4 else p.default - 10
        val hi = (p.max ?: if (p.default > 0) p.default * 4 else p.default + 10).coerceAtLeast(lo + 1e-6)
        val shown = when (p.type) {
            com.xnotes.core.template.ParamType.LENGTH -> "%.1f mm".format(value)
            com.xnotes.core.template.ParamType.INTEGER -> value.roundToInt().toString()
            else -> "%.2f".format(value).trimEnd('0').trimEnd('.', ',')
        }
        StyleCaption("$label  $shown" + if (own == null) stringResource(R.string.default_suffix) else "")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ModeChip(stringResource(R.string.default_choice), own == null) {
                apply(style.copy(params = style.params?.minus(p.name)?.takeIf { it.isNotEmpty() }))
            }
            val integer = p.type == com.xnotes.core.template.ParamType.INTEGER
            Slider(
                value = value.toFloat().coerceIn(lo.toFloat(), hi.toFloat()),
                onValueChange = { v -> apply(style.copy(params = (style.params ?: emptyMap()) + (p.name to p.clamp(v.toDouble())))) },
                valueRange = lo.toFloat()..hi.toFloat(),
                steps = if (integer) ((hi - lo).roundToInt() - 1).coerceIn(0, 200) else 0,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
internal fun StyleCaption(text: String) {
    Text(
        text,
        color = LocalPalette.current.textDim.toComposeColor(),
        fontSize = 12.sp,
    )
}

/**
 * The toolbar's View menu: one set of controls always showing the open note's effective
 * (resolved) view settings; a change writes that field's per-note override — stored
 * app-side like zoom/scroll, never in the file itself. Like [StylesPopup], the current
 * values can be saved as the global defaults every note without overrides follows
 * ("Default for all notes"), and Reset drops the note's overrides back onto them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ViewMenuPopup(editor: Editor, onDismiss: () -> Unit) {
    val defaults = editor.viewDefaults
    val overrides = editor.viewOverrides
    val vs = editor.viewSettings
    // Same session-sticky rule as the styles popup's "Default for new notes" row.
    var showDefaultRow by remember { mutableStateOf(editor.viewSettings != editor.viewDefaults) }
    // Unchecking "Default for all notes" goes back to the defaults this popup opened with.
    val openedDefaults = remember { editor.viewDefaults }

    fun apply(new: ViewOverrides) {
        editor.updateViewOverrides(new)
        if (editor.viewSettings != editor.viewDefaults) showDefaultRow = true
    }
    fun setMode(v: ViewingMode) = apply(overrides.copy(mode = v))
    fun setVerticalScroll(v: Boolean) = apply(overrides.copy(verticalScroll = v))
    fun setContrast(v: Int) = apply(overrides.copy(contrast = v))
    fun setInvert(v: Int) = apply(overrides.copy(invert = v))
    fun setBrightness(v: Int) = apply(overrides.copy(brightness = v))
    fun setSepia(v: Int) = apply(overrides.copy(sepia = v))
    fun setMultiply(v: Rgba) = apply(overrides.copy(multiply = v))
    fun setScreen(v: Rgba) = apply(overrides.copy(screen = v))
    fun setKeepImages(v: Boolean) = apply(overrides.copy(keepImages = v))
    fun setRotation(v: Int) = apply(overrides.copy(rotation = v))
    fun setScrollbar(v: Boolean) = apply(overrides.copy(scrollbar = v))

    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Column(Modifier.width(300.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
            PopupTitle(stringResource(R.string.title_view))
            StyleCaption(stringResource(R.string.caption_viewing_mode))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ModeChip(stringResource(R.string.view_single), vs.mode == ViewingMode.SINGLE) { setMode(ViewingMode.SINGLE) }
                ModeChip(stringResource(R.string.view_double), vs.mode == ViewingMode.DOUBLE) { setMode(ViewingMode.DOUBLE) }
                ModeChip(stringResource(R.string.view_cover), vs.mode == ViewingMode.COVER) { setMode(ViewingMode.COVER) }
            }

            Spacer(Modifier.size(10.dp))
            ToggleRow(stringResource(R.string.caption_vertical_scrolling), vs.verticalScroll) { setVerticalScroll(it) }

            Spacer(Modifier.size(10.dp))
            StyleCaption(stringResource(R.string.caption_pdf_filters))
            FilterSpinRow(stringResource(R.string.filter_contrast), vs.contrast, 0, 200) { setContrast(it) }
            FilterSpinRow(stringResource(R.string.filter_invert), vs.invert, 0, 100) { setInvert(it) }
            FilterSpinRow(stringResource(R.string.filter_brightness), vs.brightness, 0, 200) { setBrightness(it) }
            FilterSpinRow(stringResource(R.string.filter_sepia), vs.sepia, 0, 200) { setSepia(it) }
            FilterColorRow(stringResource(R.string.filter_multiply), vs.multiply, PdfColorFilter.MULTIPLY_OFF) { setMultiply(it) }
            FilterColorRow(stringResource(R.string.filter_screen), vs.screen, PdfColorFilter.SCREEN_OFF) { setScreen(it) }
            ToggleRow(stringResource(R.string.caption_keep_images), vs.keepImages) { setKeepImages(it) }

            Spacer(Modifier.size(10.dp))
            StyleCaption(stringResource(R.string.caption_rotate_degrees, vs.rotation))
            Slider(
                value = vs.rotation.toFloat(),
                onValueChange = { setRotation((it / 90f).roundToInt() * 90) },
                valueRange = 0f..270f,
                steps = 2,
            )

            ToggleRow(stringResource(R.string.caption_scrollbar), vs.scrollbar) { setScrollbar(it) }

            Spacer(Modifier.size(8.dp))
            if (showDefaultRow && overrides != ViewOverrides()) {
                // The checkbox's 48dp touch frame insets the drawn box; pull the row back to align it.
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().offset(x = (-14).dp)) {
                    Checkbox(
                        checked = vs == defaults,
                        onCheckedChange = { on ->
                            editor.updateViewDefaults(if (on) vs else openedDefaults.takeIf { it != vs } ?: ViewSettings())
                        },
                    )
                    Text(
                        stringResource(R.string.default_for_all_notes),
                        color = LocalPalette.current.text.toComposeColor(),
                        fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.size(4.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                ModeChip(stringResource(R.string.reset), false) { apply(ViewOverrides()) }
            }
        }
    }
}

/** A labelled percentage spinfield (minus / value / plus, stepping by 5) for the PDF filters. */
@Composable
private fun FilterSpinRow(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    val palette = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = palette.textDim.toComposeColor(), fontSize = 13.sp, modifier = Modifier.width(84.dp))
        Box(Modifier.size(34.dp).clickable { onChange((value - 5).coerceIn(min, max)) }, contentAlignment = Alignment.Center) {
            Text("−", color = palette.text.toComposeColor(), fontSize = 18.sp)
        }
        Text(
            "$value%",
            color = palette.text.toComposeColor(),
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(46.dp),
        )
        Box(Modifier.size(34.dp).clickable { onChange((value + 5).coerceIn(min, max)) }, contentAlignment = Alignment.Center) {
            Text("+", color = palette.text.toComposeColor(), fontSize = 18.sp)
        }
    }
}

/**
 * A labelled colour row for the two blend filters: the shared picker dot plus an Off chip that
 * writes the blend's identity colour (white for multiply, black for screen), so "no filter" and
 * "blend with the no-op colour" are the same state and the row needs no separate enable flag.
 */
@Composable
private fun FilterColorRow(label: String, value: Rgba, off: Rgba, onChange: (Rgba) -> Unit) {
    val palette = LocalPalette.current
    val on = value != off
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = palette.textDim.toComposeColor(), fontSize = 13.sp, modifier = Modifier.width(84.dp))
        ColorPickerDot(
            current = value,
            custom = on,
            onPick = onChange,
            dismissOnPick = false,
        ) { d, p -> PageColorGridPopup(value, d, p) }
        Spacer(Modifier.size(8.dp))
        ModeChip(stringResource(R.string.off), !on) { onChange(off) }
    }
}

/** Page-nav popup: type a page number (1-based) and jump to it; Done/GO both commit. */
@Composable
fun PageJumpPopup(editor: Editor, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("${editor.pageIndex + 1}") }
    fun go() {
        val n = text.toIntOrNull() ?: return
        editor.goToPage(n - 1)
        onDismiss()
    }
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            PopupTitle(stringResource(R.string.title_go_to_page))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldFrame(Modifier.width(72.dp)) {
                    NativeField(
                        value = text,
                        onText = { text = it },
                        modifier = Modifier.weight(1f),
                        numeric = true,
                        maxLen = 5,
                        endAlign = true,
                        autoFocus = true,
                        onDone = { go() },
                    )
                }
                Text(
                    "/ ${editor.pageCount}",
                    color = LocalPalette.current.textDim.toComposeColor(),
                    fontSize = 13.sp,
                )
                ModeChip(stringResource(R.string.go), selected = true) { go() }
            }
        }
    }
}

/** Zoom menu: optional MIN/MAX zoom limits. While a limit is on, every zoom path (pinch,
 *  buttons, keyboard, fit) clamps to it; its spin field greys out when the toggle is off. */
@Composable
fun ZoomMenuPopup(editor: Editor, onDismiss: () -> Unit) {
    val base = remember { editor.preferences }
    var minOn by remember { mutableStateOf(base.minZoomEnabled) }
    var minPct by remember { mutableStateOf(base.minZoomPercent) }
    var maxOn by remember { mutableStateOf(base.maxZoomEnabled) }
    var maxPct by remember { mutableStateOf(base.maxZoomPercent) }

    fun emit() = editor.applyPreferences(
        editor.preferences.copy(
            minZoomEnabled = minOn, minZoomPercent = minPct,
            maxZoomEnabled = maxOn, maxZoomPercent = maxPct,
        ),
    )

    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Column(Modifier.width(280.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
            PopupTitle(stringResource(R.string.title_zoom))
            ZoomLimitRow(
                stringResource(R.string.caption_min_zoom), minOn, minPct,
                onToggle = {
                    minOn = it
                    if (minOn && maxOn && minPct > maxPct) minPct = maxPct
                    emit()
                },
                onValue = {
                    minPct = it.coerceIn(Preferences.ZOOM_LIMIT_MIN_PCT, Preferences.ZOOM_LIMIT_MAX_PCT)
                        .coerceAtMost(if (maxOn) maxPct else Preferences.ZOOM_LIMIT_MAX_PCT)
                    emit()
                },
            )
            ZoomLimitRow(
                stringResource(R.string.caption_max_zoom), maxOn, maxPct,
                onToggle = {
                    maxOn = it
                    if (maxOn && minOn && maxPct < minPct) maxPct = minPct
                    emit()
                },
                onValue = {
                    maxPct = it.coerceIn(Preferences.ZOOM_LIMIT_MIN_PCT, Preferences.ZOOM_LIMIT_MAX_PCT)
                        .coerceAtLeast(if (minOn) minPct else Preferences.ZOOM_LIMIT_MIN_PCT)
                    emit()
                },
            )
        }
    }
}

/** One zoom-limit row: label, a percent spinfield (stepping by 10, greyed while off), a toggle. */
@Composable
private fun ZoomLimitRow(label: String, enabled: Boolean, value: Int, onToggle: (Boolean) -> Unit, onValue: (Int) -> Unit) {
    val palette = LocalPalette.current
    val color = (if (enabled) palette.text else palette.textDim).toComposeColor()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = color, fontSize = 12.sp, modifier = Modifier.width(76.dp))
        Box(Modifier.size(34.dp).clickable(enabled = enabled) { onValue(value - 10) }, contentAlignment = Alignment.Center) {
            Text("−", color = color, fontSize = 18.sp)
        }
        Text(
            "$value%",
            color = color,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(52.dp),
        )
        Box(Modifier.size(34.dp).clickable(enabled = enabled) { onValue(value + 10) }, contentAlignment = Alignment.Center) {
            Text("+", color = color, fontSize = 18.sp)
        }
        Spacer(Modifier.weight(1f))
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

/** Eraser configuration popup: a STROKE/AREA mode picker and a SIZE slider (the eraser radius). */
@Composable
fun EraserConfigPopup(editor: ToolPopupHost, onDismiss: () -> Unit) {
    val base = remember { editor.toolConfig(Tool.ERASER) }
    var area by remember { mutableStateOf(base.eraseMode == EraseMode.AREA) }
    var size by remember { mutableStateOf(base.baseWidth.toFloat()) }
    var switchBack by remember { mutableStateOf(base.switchBackAfterErase) }
    var scale by remember { mutableStateOf(base.scale) }
    var markups by remember { mutableStateOf(base.eraseMarkups) }

    fun emit() = editor.updateToolConfig(
        Tool.ERASER,
        base.copy(
            baseWidth = size.toDouble(),
            eraseMode = if (area) EraseMode.AREA else EraseMode.STROKE,
            switchBackAfterErase = switchBack,
            scale = scale,
            eraseMarkups = markups,
        ),
    )

    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Column(Modifier.width(250.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
            PopupTitle(stringResource(R.string.title_eraser))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ModeChip(stringResource(R.string.eraser_stroke), selected = !area) { area = false; emit() }
                ModeChip(stringResource(R.string.eraser_area), selected = area) { area = true; emit() }
            }
            val r = ToolConversions.widthRange(Tool.ERASER)
            SliderRow(stringResource(R.string.caption_size), size, r.start.toFloat()..r.endInclusive.toFloat()) { size = it; emit() }
            // SCALE off: the eraser holds a constant on-screen size whatever zoom you are at.
            ToggleRow(stringResource(R.string.caption_scale), scale) { scale = it; emit() }
            // Re-arm the previous pen/highlighter once an erase lifts, so a quick fix doesn't strand
            // you in the eraser.
            ToggleRow(stringResource(R.string.caption_switch_back), switchBack) { switchBack = it; emit() }
            if (editor.hostHasPdf) ToggleRow(stringResource(R.string.caption_erase_markups), markups) { markups = it; emit() }
        }
    }
}

/** Select/lasso configuration popup: whether a selection needs items fully inside it, plus the select tool's SWITCH BACK. */
@Composable
fun SelectConfigPopup(editor: ToolPopupHost, tool: Tool = Tool.SELECT, onDismiss: () -> Unit) {
    val base = remember { editor.toolConfig(tool) }
    var switchBack by remember { mutableStateOf(base.switchBackAfterSelect) }
    var whole by remember { mutableStateOf(base.selectWholeItems) }

    fun emit() = editor.updateToolConfig(tool, base.copy(switchBackAfterSelect = switchBack, selectWholeItems = whole))

    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Column(Modifier.width(250.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
            PopupTitle(stringResource(if (tool == Tool.LASSO) R.string.tool_lasso else R.string.title_select))
            ToggleRow(stringResource(R.string.caption_select_whole_items), whole) { whole = it; emit() }
            // Re-arm the previous pen/highlighter once a selection action (move, resize, delete,
            // cut, copy, duplicate) finishes, so a quick edit doesn't strand you in select.
            if (tool == Tool.SELECT) {
                ToggleRow(stringResource(R.string.caption_switch_back), switchBack) { switchBack = it; emit() }
            }
        }
    }
}

/** Shape-tool configuration popup (spec 10 §3 / 04 §6): kind picker, WIDTH, FILL, DASHED, NEON. */
@Composable
fun ShapeConfigPopup(editor: ToolPopupHost, onDismiss: () -> Unit) {
    var kind by remember { mutableStateOf(editor.hostShapeConfig.shape) }
    var width by remember { mutableStateOf(editor.hostShapeConfig.strokeWidth.toFloat()) }
    var fill by remember { mutableStateOf(editor.hostShapeConfig.fill) }
    var fillOpacity by remember { mutableStateOf((editor.hostShapeConfig.fillAlpha * 100).toFloat()) }
    var glow by remember { mutableStateOf(editor.hostShapeConfig.neon) }
    var glowIntensity by remember { mutableStateOf(ToolConversions.neonStrengthToIntensity(editor.hostShapeConfig.neonStrength).toFloat()) }
    var dashed by remember { mutableStateOf(editor.hostShapeConfig.dashed) }
    var dashLen by remember { mutableStateOf(editor.hostShapeConfig.dashLength.toFloat()) }
    var gapLen by remember { mutableStateOf(editor.hostShapeConfig.dashGap.toFloat()) }
    var function by remember { mutableStateOf(editor.hostShapeConfig.function) }

    fun emit() = editor.updateShapeConfig(
        ShapeConfig(
            shape = kind,
            strokeWidth = width.toDouble(),
            fill = fill,
            fillAlpha = (fillOpacity / 100.0).coerceIn(ShapeConfig.FILL_ALPHA_MIN, ShapeConfig.FILL_ALPHA_MAX),
            neon = glow,
            neonStrength = ToolConversions.intensityToNeonStrength(glowIntensity.toDouble()),
            dashed = dashed,
            dashLength = dashLen.toDouble(),
            dashGap = gapLen.toDouble(),
            function = function,
        ),
    )

    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Column(Modifier.width(284.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
            PopupTitle(stringResource(R.string.title_shape))
            // Swipe between the geometric shapes and the function curves; the dots show which page is up.
            // Both pages stay composed so the pager is as tall as the fuller one, whatever the font scale.
            val pager = rememberPagerState(initialPage = if (kind == ShapeKind.FUNCTION) 1 else 0) { 2 }
            NoIntrinsics {
                HorizontalPager(pager, beyondViewportPageCount = 1, verticalAlignment = Alignment.Top) { page ->
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (page == 0) {
                            ShapeKind.DRAW_TOOL_KINDS.forEach { k ->
                                KindChip(shapeIcon(k), k.id, selected = kind == k) { kind = k; emit() }
                            }
                        } else {
                            FunctionSpec.PRESETS.forEach { f ->
                                FunctionChip(functionLabel(f.expr), selected = kind == ShapeKind.FUNCTION && function == f.expr) {
                                    kind = ShapeKind.FUNCTION
                                    function = f.expr
                                    emit()
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.Center) {
                repeat(pager.pageCount) { i ->
                    Box(
                        Modifier
                            .padding(horizontal = 3.dp)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(
                                (if (pager.currentPage == i) LocalPalette.current.accent else LocalPalette.current.border).toComposeColor(),
                            ),
                    )
                }
            }
            SliderRow(stringResource(R.string.caption_width), width, 1f..20f) { width = it; emit() }
            ToggleRow(stringResource(R.string.caption_fill), fill) { fill = it; emit() }
            if (fill) {
                val minPct = (ShapeConfig.FILL_ALPHA_MIN * 100).toFloat()
                SliderRow(stringResource(R.string.caption_opacity), fillOpacity, minPct..100f) { fillOpacity = it; emit() }
            }
            ToggleRow(stringResource(R.string.caption_dashed), dashed) { dashed = it; emit() }
            if (dashed) {
                SliderRow(stringResource(R.string.caption_dash), dashLen, 2f..40f) { dashLen = it; emit() }
                SliderRow(stringResource(R.string.caption_gap), gapLen, 2f..40f) { gapLen = it; emit() }
            }
            ToggleRow(stringResource(R.string.caption_neon), glow) { glow = it; emit() }
            if (glow) {
                SliderRow(stringResource(R.string.caption_glow_intensity), glowIntensity, 0f..100f) { glowIntensity = it; emit() }
            }
        }
    }
}

/**
 * Table-tool configuration popup: the column/row counts and line thickness for the next table.
 * The table itself is drawn on the page by dragging, like the shape tool; these are just its
 * defaults. An existing table's columns/rows are edited in table edit mode, its width via the
 * selection's Change-style slider.
 */
@Composable
fun TableConfigPopup(editor: Editor, onDismiss: () -> Unit) {
    var cols by remember { mutableStateOf(editor.tableToolCols) }
    var rows by remember { mutableStateOf(editor.tableToolRows) }
    var width by remember { mutableStateOf(editor.tableToolWidth.toFloat()) }
    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Column(Modifier.width(250.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
            PopupTitle(stringResource(R.string.title_table))
            TableStepperRow(stringResource(R.string.caption_columns), cols, 1, 20) { cols = it; editor.tableToolCols = it }
            TableStepperRow(stringResource(R.string.caption_rows), rows, 1, 20) { rows = it; editor.tableToolRows = it }
            SliderRow(stringResource(R.string.caption_width), width, 1f..12f) { width = it; editor.tableToolWidth = it.toDouble() }
        }
    }
}

/**
 * Text markup tool popup: what a drag over PDF text does (select it, or one of the four marks) and,
 * in Highlight mode, how deep it goes. A mark takes the toolbar's active ink colour.
 */
@Composable
fun MarkupToolPopup(editor: ToolPopupHost, onDismiss: () -> Unit) {
    val base = remember { editor.toolConfig(Tool.MARKUP) }
    var mode by remember { mutableStateOf(base.markupMode) }
    var intensity by remember { mutableStateOf((base.markupIntensity * 100).toFloat()) }

    fun emit() = editor.updateToolConfig(Tool.MARKUP, base.copy(markupMode = mode, markupIntensity = intensity / 100.0))

    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Column(Modifier.width(250.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
            PopupTitle(stringResource(R.string.tool_markup))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MarkupMode.entries.forEach { m ->
                    KindChip(markupIcon(m), stringResource(m.labelRes), selected = mode == m) { mode = m; emit() }
                }
            }
            if (mode == MarkupMode.HIGHLIGHT) {
                val min = (ToolConfig.MARKUP_INTENSITY_MIN * 100).toFloat()
                SliderRow(stringResource(R.string.caption_intensity), intensity, min..100f) { intensity = it; emit() }
            }
        }
    }
}

/** A −/value/+ integer stepper row, used by [TableConfigPopup]. */
@Composable
private fun TableStepperRow(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    val palette = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            "$label  $value",
            color = palette.text.toComposeColor(),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            modifier = Modifier.weight(1f),
        )
        TableStepButton("−") { onChange((value - 1).coerceAtLeast(min)) }
        Spacer(Modifier.size(8.dp))
        TableStepButton("+") { onChange((value + 1).coerceAtMost(max)) }
    }
}

@Composable
private fun TableStepButton(label: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Box(
        Modifier.size(32.dp).clip(MaterialTheme.shapes.extraSmall).background(palette.surface.toComposeColor()).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = palette.text.toComposeColor(), fontSize = 16.sp) }
}

private fun markupIcon(mode: MarkupMode): ImageVector = when (mode) {
    MarkupMode.SELECT -> XnotesIcons.textSelect
    MarkupMode.HIGHLIGHT -> XnotesIcons.highlight
    MarkupMode.UNDERLINE -> XnotesIcons.underline
    MarkupMode.STRIKEOUT -> XnotesIcons.strikethrough
    MarkupMode.SQUIGGLY -> XnotesIcons.squiggly
}

/** Colour switcher (spec 10 §4): the toolbar swatch picker — opens the shared [ColorPickerPopup]
 *  and writes the chosen colour back to swatch [index]. Picks apply live; the final colour is
 *  remembered into recents when the popup closes. */
@Composable
fun ColorSwitcherPopup(host: ToolPopupHost, index: Int, onDismiss: () -> Unit) {
    ColorPickerPopup(
        initial = host.hostToolbarColors.getOrNull(index),
        recents = host.hostRecentColors,
        onDismiss = { host.rememberSwatchColor(index); onDismiss() },
        onPick = { host.setSwatchColor(index, it) },
    )
}

@Composable
internal fun PopupTitle(text: String) {
    Text(
        text,
        color = LocalPalette.current.accent.toComposeColor(),
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = LocalPalette.current.text.toComposeColor(), fontSize = 12.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
internal fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean = true,
    onChangeFinished: (() -> Unit)? = null,
    onChange: (Float) -> Unit,
) {
    Column {
        Text(
            "$label  ${"%.0f".format(value)}",
            color = (if (enabled) LocalPalette.current.text else LocalPalette.current.textDim).toComposeColor(),
            fontSize = 12.sp,
        )
        Slider(
            value = value,
            onValueChange = onChange,
            onValueChangeFinished = onChangeFinished,
            valueRange = range,
            enabled = enabled,
        )
    }
}

/** Lays out [content] as a plain box but answers intrinsic queries with zero, so a pager can sit
 *  in a menu, whose column sizes itself by intrinsics that a pager can't report. */
@Composable
private fun NoIntrinsics(content: @Composable () -> Unit) = Layout(content, measurePolicy = NoIntrinsicsPolicy)

private object NoIntrinsicsPolicy : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val placeables = measurables.map { it.measure(constraints) }
        return layout(placeables.maxOfOrNull { it.width } ?: 0, placeables.maxOfOrNull { it.height } ?: 0) {
            placeables.forEach { it.place(0, 0) }
        }
    }
    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) = 0
    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) = 0
    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) = 0
    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) = 0
}

/** Glyph shown in the shape-kind picker for each [ShapeKind]. */
private fun shapeIcon(kind: ShapeKind): ImageVector = when (kind) {
    ShapeKind.LINE, ShapeKind.POLYLINE, ShapeKind.CURVE -> XnotesIcons.shapeLine
    ShapeKind.FUNCTION -> XnotesIcons.shapeSpline
    ShapeKind.ARROW -> XnotesIcons.shapeArrow
    ShapeKind.RECTANGLE -> XnotesIcons.shapeRect
    ShapeKind.ELLIPSE -> XnotesIcons.shapeEllipse
    ShapeKind.CIRCLE -> XnotesIcons.shapeCircle
    ShapeKind.TRIANGLE, ShapeKind.POLYGON -> XnotesIcons.shapeTriangle
    ShapeKind.COORD_AXES -> XnotesIcons.shapeAxes
    ShapeKind.NUMBER_LINE -> XnotesIcons.shapeNumberLine
    ShapeKind.SPLINE -> XnotesIcons.shapeSpline
}

private fun functionLabel(expr: String): String = when (expr) {
    "x^2" -> "x\u00B2"
    "x^3" -> "x\u00B3"
    "x^(1/2)" -> "\u221Ax"
    "ln(x)" -> "ln x"
    "e^x" -> "e\u02E3"
    "sin(x)" -> "sin x"
    "cos(x)" -> "cos x"
    "tan(x)" -> "tan x"
    "cot(x)" -> "cot x"
    "arcsin(x)" -> "arcsin x"
    "arccos(x)" -> "arccos x"
    "arctan(x)" -> "arctan x"
    else -> expr
}

/** A text chip for a function-curve preset, the same size and colours as [KindChip]. */
@Composable
private fun FunctionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(if (selected) palette.selectionBackground.toComposeColor() else palette.surface.toComposeColor())
            .border(1.dp, if (selected) palette.accent.toComposeColor() else palette.border.toComposeColor(), MaterialTheme.shapes.extraSmall)
            .clickable(onClick = onClick)
            .height(36.dp)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) palette.selectionForeground.toComposeColor() else palette.text.toComposeColor(),
            fontSize = 14.sp,
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
        )
    }
}

@Composable
private fun KindChip(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(if (selected) palette.selectionBackground.toComposeColor() else palette.surface.toComposeColor())
            .border(1.dp, if (selected) palette.accent.toComposeColor() else palette.border.toComposeColor(), MaterialTheme.shapes.extraSmall)
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (selected) palette.selectionForeground.toComposeColor() else palette.text.toComposeColor(),
            modifier = Modifier.size(20.dp),
        )
    }
}

/**
 * The app's dropdown menu: material's, pinned to the palette menu surface and given a
 * hairline border so it reads against same-tone surfaces (the backstage, OLED black)
 * where a shadow alone vanishes. Shadows material3's composable for every same-package
 * caller that doesn't import material's directly.
 */
@Composable
internal fun DropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    properties: PopupProperties = PopupProperties(focusable = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalPalette.current
    androidx.compose.material3.DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = LocalMenuOffset.current,
        properties = properties,
        containerColor = palette.menuBg.toComposeColor(),
        border = BorderStroke(1.dp, palette.border.toComposeColor()),
    ) {
        // A menu opened from inside this one hangs off its own row, not beside the rail.
        CompositionLocalProvider(LocalMenuOffset provides DpOffset.Zero) { content() }
    }
}

/**
 * The app's dialog: material's, pinned to the palette menu surface and given the same hairline
 * border and 14dp corners the hand-rolled progress dialogs use, so a dialog reads against
 * same-tone surfaces (the backstage, OLED black) and matches the menus. Shadows material3's
 * composable for every same-package caller that doesn't import material's directly.
 */
@Composable
internal fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = MaterialTheme.shapes.large,
    containerColor: Color = LocalPalette.current.menuBg.toComposeColor(),
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier.border(1.dp, LocalPalette.current.border.toComposeColor(), shape),
        dismissButton = dismissButton,
        title = title,
        text = text,
        shape = shape,
        containerColor = containerColor,
    )
}

/** A text-label chip for a segmented picker (e.g. the eraser's STROKE/AREA modes). */
/** A menu row for one of several choices; the current one wears the selection container. */
@Composable
internal fun ChoiceMenuItem(
    selected: Boolean,
    onClick: () -> Unit,
    trailingIcon: (@Composable (Color) -> Unit)? = null,
    text: @Composable (Color) -> Unit,
) {
    val palette = LocalPalette.current
    val fg = (if (selected) palette.selectionForeground else palette.text).toComposeColor()
    DropdownMenuItem(
        text = { text(fg) },
        onClick = onClick,
        trailingIcon = trailingIcon?.let { icon -> { icon(fg) } },
        modifier = if (selected) Modifier.background(palette.selectionBackground.toComposeColor()) else Modifier,
    )
}

@Composable
internal fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(if (selected) palette.selectionBackground.toComposeColor() else palette.surface.toComposeColor())
            .border(1.dp, if (selected) palette.accent.toComposeColor() else palette.border.toComposeColor(), MaterialTheme.shapes.extraSmall)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            color = if (selected) palette.selectionForeground.toComposeColor() else palette.text.toComposeColor(),
            fontWeight = FontWeight.Medium,
            fontSize = 12.sp,
        )
    }
}

/**
 * The items of a font dropdown, shared by the flow format bar, the text tool
 * config popup and the text box style bar. Each entry previews in its own
 * family; [monoOnly] restricts the list to monospace families. A null pick
 * (offered when [withDefault]) means "inherit the document default".
 */
@Composable
fun FontMenuItems(
    current: FontFace?,
    monoOnly: Boolean = false,
    withDefault: Boolean = false,
    onPick: (FontFace?) -> Unit,
) {
    val palette = LocalPalette.current
    if (withDefault) {
        ChoiceMenuItem(current == null, onClick = { onPick(null) }) { fg ->
            Text(stringResource(R.string.default_choice), color = fg, fontSize = 14.sp)
        }
    }
    for (choice in FontCatalog.choices()) {
        if (monoOnly && !choice.mono) continue
        ChoiceMenuItem(choice.face == current, onClick = { onPick(choice.face) }) { fg ->
            Text(fontLabel(choice.face), color = fg, style = TextStyle(fontFamily = choice.face.toComposeFamily(), fontSize = 14.sp))
        }
    }
}
