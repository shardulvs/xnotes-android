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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
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
import com.xnotes.core.model.PagePattern
import com.xnotes.core.model.PageTemplates
import com.xnotes.core.model.PageStyle
import com.xnotes.core.model.TapeMode
import com.xnotes.core.model.TapePattern
import com.xnotes.core.model.Rgba
import com.xnotes.core.model.TemplateMemory
import com.xnotes.core.model.TemplateSettings
import com.xnotes.core.pal.FontFace
import com.xnotes.core.tools.EraseMode
import com.xnotes.core.tools.MarkupMode
import com.xnotes.core.tools.ShapeConfig
import com.xnotes.core.shapes.ShapeKind
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConfig
import com.xnotes.core.tools.ToolConversions
import com.xnotes.platform.FontCatalog
import com.xnotes.platform.TemplateLibrary
import com.xnotes.settings.Preferences
import com.xnotes.settings.SwatchRole
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
            if (tool == Tool.DASHED) {
                SliderRow(stringResource(R.string.caption_dash), dashLen, 2f..40f) { dashLen = it; emit() }
                SliderRow(stringResource(R.string.caption_gap), gapLen, 2f..40f) { gapLen = it; emit() }
            }
            // The highlighter's strength (translucency) and an optional straight-segment lock
            // (for ruling/underlining).
            if (tool == Tool.HIGHLIGHTER) {
                SliderRow(stringResource(R.string.caption_intensity), intensity, 10f..100f) { intensity = it; emit() }
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
 * nothing sits below it but None. Customize opens the shown template on a page of its own, with
 * its colours and parameters, which belong to that template alone; while the popup is open each
 * level keeps them for every template it showed ([TemplateMemory]), so trying another loses
 * nothing. Like [ToolConfigPopup], the popup holds the edited style locally and pushes each
 * change to the [Editor] (which persists, but never undoes). The All Pages tab also offers making
 * its style the default stamped onto new notes, plus a Reset back to all-Default, which forgets
 * that memory too. Below both tabs sits the note's new page size, which neither tab nor Reset
 * touches.
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
    // What each level had for the templates it showed during this visit.
    val memory = remember { TemplateMemory() }
    // Both levels are read back: a template picked for All Pages also changes the pages following it.
    fun synced() {
        docStyle = editor.documentStyle
        pageStyle = editor.currentPageStyle
        if (docStyle != editor.newNoteStyle) showNewNoteRow = true
    }
    fun apply(next: PageStyle) {
        if (tab == 0) editor.setDocumentStyle(next) else editor.setCurrentPageStyle(next)
        synced()
    }
    fun pick(key: String?) {
        if (tab == 0) editor.setDocumentTemplate(key ?: PageTemplates.NONE, memory) else editor.setCurrentPageTemplate(key, memory)
        synced()
    }

    // What this level shows with no template of its own: the document's, on the page tab.
    val inherited = if (tab == 0) PageTemplates.NONE else docStyle.template ?: PageTemplates.NONE
    val shownKey = style.template ?: inherited
    val shown = if (shownKey == PageTemplates.NONE) null else editor.templateFor(shownKey)
    // The level below whose settings show through, when it shows the same template.
    val lower = docStyle.takeIf { tab == 1 && (it.template ?: PageTemplates.NONE) == shownKey }
    val look = TemplateLook.of(style, lower, LocalPalette.current.paper, below = if (tab == 1) docStyle else null)
    // A strip tile draws its template as this tab shows it, as last set during this visit, or as it comes.
    fun drawing(e: TemplateLibrary.Entry): TemplateDrawing {
        val kept = if (tab == 0) memory.recall(null, e.key) else editor.currentPage?.let { memory.recall(it, e.key) }
        return TemplateDrawing.inStrip(e, style, shownKey, kept, docStyle.takeIf { tab == 1 }, look.paper, editor.documentDpi)
    }

    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        if (customizing && shown != null) {
            TemplateCustomizer(editor, shown, shownKey, tab, style, lower, look, ::apply) { customizing = false }
        } else Column(Modifier.width(286.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
            PopupTitle(stringResource(R.string.title_styles))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                SwatchCircles(editor, SwatchRole.PAPER, style.pageColor) { apply(style.copy(pageColor = it)) }
            }

            Spacer(Modifier.size(12.dp))
            StyleCaption(stringResource(R.string.caption_template))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // With nothing below All Pages, an unset template there already means None.
                if (tab == 1) ModeChip(stringResource(R.string.default_choice), style.template == null) { pick(null) }
                val none = style.template == PageTemplates.NONE || (tab == 0 && style.template == null)
                ModeChip(stringResource(R.string.none), none) { pick(PageTemplates.NONE) }
            }
            Spacer(Modifier.size(6.dp))
            val libraryVersion = TemplateLibraryUi.version
            // On the cover, its own tab lists cover designs instead of page templates.
            val coverPage = tab == 1 && editor.currentPageIsCover
            val choices = remember(libraryVersion, tab, coverPage) {
                editor.templateChoices(if (coverPage) com.xnotes.core.template.TemplateKind.COVER else com.xnotes.core.template.TemplateKind.PAGE)
            }
            TemplateStrip(
                entries = choices,
                selected = style.template,
                pageMm = editor.currentPageMm,
                drawing = ::drawing,
                onSelect = { pick(it) },
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
                    ModeChip(stringResource(R.string.reset), false) {
                        editor.resetDocumentStyle(memory)
                        synced()
                    }
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = LocalPalette.current.border.toComposeColor())
            NewPageSizeControls(editor)
        }
    }
}

/** The size of pages added to the note: the page before's, a named size turned either way, or Custom's sides. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewPageSizeControls(editor: Editor) {
    var choice by remember { mutableStateOf(editor.newPageSize) }
    fun apply(next: PageSizeChoice?) {
        choice = next
        editor.setNewPageSize(next)
    }

    StyleCaption(stringResource(R.string.caption_new_page_size))
    Spacer(Modifier.size(6.dp))
    // A first pick starts from the Preferences size, so Custom opens on the sides typed there.
    SizeDropdown(choice?.size, stringResource(R.string.same_as_previous)) { size ->
        apply(size?.let { (choice ?: editor.preferences.defaultPageChoice).copy(size = it) })
    }
    val c = choice
    if (c != null) {
        Spacer(Modifier.size(8.dp))
        if (c.size == PageSize.CUSTOM) {
            // The fields keep the callback they were built with, so it reads the choice as it is now.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SideField(stringResource(R.string.width_mm), c.customWidthMm, Modifier.weight(1f)) { mm ->
                    choice?.let { apply(it.copy(customWidthMm = mm)) }
                }
                SideField(stringResource(R.string.height_mm), c.customHeightMm, Modifier.weight(1f)) { mm ->
                    choice?.let { apply(it.copy(customHeightMm = mm)) }
                }
            }
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ModeChip(stringResource(R.string.orientation_portrait), c.orientation == Orientation.PORTRAIT) {
                    apply(c.copy(orientation = Orientation.PORTRAIT))
                }
                ModeChip(stringResource(R.string.orientation_landscape), c.orientation == Orientation.LANDSCAPE) {
                    apply(c.copy(orientation = Orientation.LANDSCAPE))
                }
            }
        }
    }
}

/** One side of a custom page in millimetres; only a value inside the settable range reaches [onChange]. */
@Composable
internal fun SideField(label: String, mm: Double, modifier: Modifier, onChange: (Double) -> Unit) {
    Column(modifier) {
        StyleCaption(label)
        Spacer(Modifier.size(4.dp))
        FieldFrame(Modifier.fillMaxWidth()) {
            NativeField(
                value = formatMm(mm),
                onText = { raw -> parseMm(raw)?.let(onChange) },
                modifier = Modifier.weight(1f),
                decimal = true,
                maxLen = 7,
                endAlign = true,
            )
        }
    }
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

        Spacer(Modifier.size(12.dp))
        val drawn = TemplateDrawing.of(t, style.settings, lower?.settings, look.paper, dpi)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TemplatePreview(t, key, editor.currentPageMm, drawn.look.ink, drawn.look.accent, drawn.look.paper, drawn.values, 112.dp)
        }

        if (t.usesInk) {
            Spacer(Modifier.size(12.dp))
            StyleCaption(stringResource(R.string.caption_pattern_colour))
            Spacer(Modifier.size(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ModeChip(stringResource(R.string.default_choice), style.patternColor == null) { apply(style.copy(patternColor = null)) }
                // Circles hold the hue at full strength; OPACITY sets the alpha, so a pick keeps it.
                SwatchCircles(editor, SwatchRole.LINES, style.patternColor?.copy(a = 255)) {
                    apply(style.copy(patternColor = it.copy(a = look.ink.a)))
                }
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
        }
        if (t.usesAccent) {
            Spacer(Modifier.size(4.dp))
            StyleCaption(stringResource(R.string.caption_accent_colour))
            Spacer(Modifier.size(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ModeChip(stringResource(R.string.default_choice), style.accentColor == null) { apply(style.copy(accentColor = null)) }
                SwatchCircles(editor, SwatchRole.ACCENT, style.accentColor?.copy(a = 255)) {
                    apply(style.copy(accentColor = it.copy(a = look.accent.a)))
                }
            }
        }

        TemplateParamControls(t, style, lower, dpi, editor, apply = apply)

        Spacer(Modifier.size(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            ModeChip(stringResource(R.string.reset), false) {
                apply(style.withSettings(TemplateSettings()))
            }
        }
    }
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
 * [captionGap] spaces a colour row's caption from its circles, as the host's own rows do.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TemplateParamControls(
    t: com.xnotes.core.template.Template,
    style: PageStyle,
    lower: PageStyle?,
    dpi: Int,
    store: SwatchStore,
    captionGap: Dp = 0.dp,
    apply: (PageStyle) -> Unit,
) {
    val k = dpi / 25.4
    t.spacingParam?.let { sp ->
        Spacer(Modifier.size(12.dp))
        val lo = ((sp.min ?: (sp.default / 4)) * k).toFloat()
        val hi = ((sp.max ?: (sp.default * 4)) * k).toFloat().coerceAtLeast(lo + 1f)
        val spacing = style.spacing ?: lower?.spacing ?: (sp.default * k)
        // In mm under the template's own name for it, like every other length.
        val label = sp.label ?: stringResource(R.string.caption_spacing)
        StyleCaption("$label  ${"%.1f mm".format(spacing / k)}" + if (style.spacing == null) stringResource(R.string.default_suffix) else "")
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
            Spacer(Modifier.size(captionGap))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ModeChip(stringResource(R.string.default_choice), own == null) {
                    apply(style.copy(colors = style.colors?.minus(p.name)?.takeIf { it.isNotEmpty() }))
                }
                SwatchCircles(store, SwatchRole.TEMPLATE, own) {
                    apply(style.copy(colors = (style.colors ?: emptyMap()) + (p.name to it)))
                }
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
    fun setFreeMovement(v: Boolean) = apply(overrides.copy(freeMovement = v))
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ModeChip(stringResource(R.string.view_single), vs.mode == ViewingMode.SINGLE) { setMode(ViewingMode.SINGLE) }
                ModeChip(stringResource(R.string.view_double), vs.mode == ViewingMode.DOUBLE) { setMode(ViewingMode.DOUBLE) }
                ModeChip(stringResource(R.string.view_cover), vs.mode == ViewingMode.COVER) { setMode(ViewingMode.COVER) }
            }

            Spacer(Modifier.size(10.dp))
            ToggleRow(stringResource(R.string.caption_vertical_scrolling), vs.verticalScroll) { setVerticalScroll(it) }
            if (!vs.verticalScroll) {
                ToggleRow(stringResource(R.string.caption_free_movement), vs.freeMovement) { setFreeMovement(it) }
            }

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
    var scribble by remember { mutableStateOf(base.scribbleErase) }

    fun emit() = editor.updateToolConfig(
        Tool.ERASER,
        base.copy(
            baseWidth = size.toDouble(),
            eraseMode = if (area) EraseMode.AREA else EraseMode.STROKE,
            switchBackAfterErase = switchBack,
            scale = scale,
            eraseMarkups = markups,
            scribbleErase = scribble,
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
            // A pen scribbled over ink erases it without picking up the eraser.
            ToggleRow(stringResource(R.string.caption_scribble_erase), scribble) { scribble = it; emit() }
        }
    }
}

/** Select-tool configuration popup: just a SWITCH BACK toggle, mirroring the eraser's. */
@Composable
fun SelectConfigPopup(editor: ToolPopupHost, onDismiss: () -> Unit) {
    val base = remember { editor.toolConfig(Tool.SELECT) }
    var switchBack by remember { mutableStateOf(base.switchBackAfterSelect) }

    fun emit() = editor.updateToolConfig(Tool.SELECT, base.copy(switchBackAfterSelect = switchBack))

    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Column(Modifier.width(250.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
            PopupTitle(stringResource(R.string.title_select))
            // Re-arm the previous pen/highlighter once a selection action (move, resize, delete,
            // cut, copy, duplicate) finishes, so a quick edit doesn't strand you in select.
            ToggleRow(stringResource(R.string.caption_switch_back), switchBack) { switchBack = it; emit() }
        }
    }
}

/**
 * Tape popup: what a drag lays (a straight strip, a rectangle or a freehand band), its colour and
 * pattern, the width of a strip or band, and Reveal all / Cover all for every tape in the note or
 * canvas. Like the pens, tape follows the toolbar's ink colour unless a colour is picked here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TapeConfigPopup(editor: ToolPopupHost, onDismiss: () -> Unit) {
    val base = remember { editor.toolConfig(Tool.TAPE) }
    var mode by remember { mutableStateOf(base.tapeMode) }
    var pattern by remember { mutableStateOf(base.tapePattern) }
    var width by remember { mutableStateOf(base.baseWidth.toFloat()) }
    var scale by remember { mutableStateOf(base.scale) }
    var colorOverride by remember { mutableStateOf(base.colorOverride) }

    fun emit() = editor.updateToolConfig(
        Tool.TAPE,
        base.copy(tapeMode = mode, tapePattern = pattern, baseWidth = width.toDouble(), scale = scale, colorOverride = colorOverride),
    )

    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Column(Modifier.width(250.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
            PopupTitle(stringResource(R.string.tool_tape))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (m in TapeMode.entries) ModeChip(stringResource(m.labelRes), selected = mode == m) { mode = m; emit() }
            }
            Spacer(Modifier.size(8.dp))
            StyleCaption(stringResource(R.string.caption_colour))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
            Spacer(Modifier.size(8.dp))
            StyleCaption(stringResource(R.string.caption_pattern))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (p in TapePattern.entries) ModeChip(stringResource(p.labelRes), selected = pattern == p) { pattern = p; emit() }
            }
            // A rectangle is as tall as it is dragged, so only a strip and a band take a width.
            if (mode != TapeMode.RECTANGLE) {
                Spacer(Modifier.size(4.dp))
                val range = ToolConversions.widthRange(Tool.TAPE)
                SliderRow(stringResource(R.string.caption_width), width, range.start.toFloat()..range.endInclusive.toFloat()) { width = it; emit() }
                ToggleRow(stringResource(R.string.caption_scale), scale) { scale = it; emit() }
            }
            Spacer(Modifier.size(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ModeChip(stringResource(R.string.tape_reveal_all), selected = false) { editor.setAllTapePeeled(true) }
                ModeChip(stringResource(R.string.tape_cover_all), selected = false) { editor.setAllTapePeeled(false) }
            }
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
internal fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.width(220.dp)) {
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


@Composable
internal fun KindChip(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Tooltip(label) {
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
        // A menu or tip opened from inside this one hangs off its own row, not beside the rail.
        CompositionLocalProvider(LocalMenuOffset provides DpOffset.Zero, LocalTipSide provides TipSide.BELOW) { content() }
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
