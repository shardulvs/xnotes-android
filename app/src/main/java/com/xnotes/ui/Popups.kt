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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
 * A stroke tool's menu (spec 10 §3), in three cards. The stroke: a preview, its colour and WIDTH.
 * How it answers the hand: PRESSURE / SENSITIVITY, the tool's signature control (MULTIPLIER for
 * calligraphy, THINNING / SMOOTHING / TOP SPEED for the speed pen, TIP WIDTH PERCENTAGE for the
 * taper pen) and STABILIZATION. How it sits on the page: OPACITY (hidden under neon), NEON, SCALE.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolConfigPopup(editor: ToolPopupHost, tool: Tool, onDismiss: () -> Unit) {
    val base = remember { editor.toolConfig(tool) }
    var pressure by remember { mutableStateOf(base.pressureEnabled) }
    var sensitivity by remember { mutableStateOf(ToolConversions.minFactorToSensitivity(base.pressureMinFactor).toFloat()) }
    var multiplier by remember { mutableStateOf(ToolConversions.directionStrengthToMultiplier(base.directionStrength).toFloat()) }
    var speed by remember { mutableStateOf(ToolConversions.strengthToSpeed(base.speedStrength).toFloat()) }
    var smoothing by remember { mutableStateOf(ToolConversions.blurToSmoothing(base.speedSmoothing).toFloat()) }
    var topSpeed by remember { mutableStateOf(ToolConversions.dpPerMsToTopSpeed(base.speedTop).toFloat()) }
    var taperTip by remember { mutableStateOf((base.taperMinFactor * 100).toFloat()) }
    var width by remember { mutableStateOf(ToolConversions.snapWidth(tool, base.baseWidth).toFloat()) }
    var glow by remember { mutableStateOf(base.neon) }
    var glowIntensity by remember { mutableStateOf(ToolConversions.neonStrengthToIntensity(base.neonStrength).toFloat()) }
    var dashLen by remember { mutableStateOf(base.dashLength.toFloat()) }
    var gapLen by remember { mutableStateOf(base.dashGap.toFloat()) }
    var straight by remember { mutableStateOf(base.straightLine) }
    var scale by remember { mutableStateOf(base.scale) }
    var intensity by remember { mutableStateOf(ToolConversions.highlighterAlphaToIntensity(base.highlighterAlpha).toFloat()) }
    var inverse by remember { mutableStateOf(base.highlighterInverse) }
    var faded by remember { mutableStateOf(base.opacityEnabled) }
    var opacity by remember { mutableStateOf(ToolConversions.opacityToPercent(base.opacity).toFloat()) }
    var colorOverride by remember { mutableStateOf(base.colorOverride) }
    var steady by remember { mutableStateOf(base.stabilize) }
    var steadyStrength by remember { mutableStateOf(ToolConversions.trailToStrength(base.stabilizeTrail).toFloat()) }
    var crossover by remember { mutableStateOf(ToolConversions.dpPerSToCrossover(base.stabilizeCrossover).toFloat()) }

    fun current(): ToolConfig {
        val m = ToolConversions.sensitivityToMinFactor(sensitivity.toDouble())
        val ds = if (tool == Tool.CALLIGRAPHY) ToolConversions.multiplierToDirectionStrength(multiplier.toDouble()) else 0.0
        val sp = if (tool == Tool.SPEED) ToolConversions.speedToStrength(speed.toDouble()) else 0.0
        val blur = if (tool == Tool.SPEED) ToolConversions.smoothingToBlur(smoothing.toDouble()) else base.speedSmoothing
        val top = if (tool == Tool.SPEED) ToolConversions.topSpeedToDpPerMs(topSpeed.toDouble()) else base.speedTop
        val tmf = if (tool == Tool.TAPER) taperTip.toDouble() / 100.0 else base.taperMinFactor
        val ha = if (tool == Tool.HIGHLIGHTER) ToolConversions.intensityToHighlighterAlpha(intensity.toDouble()) else base.highlighterAlpha
        val op = if (tool != Tool.HIGHLIGHTER) ToolConversions.percentToOpacity(opacity.toDouble()) else base.opacity
        return base.copy(
            baseWidth = ToolConversions.snapWidth(tool, width.toDouble()),
            pressureEnabled = pressure,
            pressureMinFactor = m,
            directionStrength = ds,
            speedStrength = sp,
            speedSmoothing = blur,
            speedTop = top,
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
            stabilize = steady,
            stabilizeTrail = ToolConversions.strengthToTrail(steadyStrength.toDouble()),
            stabilizeCrossover = ToolConversions.crossoverToDpPerS(crossover.toDouble()),
            opacityEnabled = faded,
            opacity = op,
        )
    }

    fun emit() = editor.updateToolConfig(tool, current())

    val active = editor.hostToolbarColors.getOrNull(editor.hostActiveColorIndex)
    val hasPressure = tool == Tool.PEN || tool == Tool.CALLIGRAPHY || tool == Tool.SPEED || tool == Tool.TAPER
    // Glow is offered on every stroke tool except the highlighter (translucent) and the
    // dashed pen (it draws a line, not a fillable ribbon, so a halo has nothing to hug).
    val hasGlow = tool.isStroke && tool != Tool.HIGHLIGHTER && tool != Tool.DASHED
    val range = ToolConversions.widthRange(tool)

    ToolMenu(stringResource(tool.labelRes), onDismiss) {
        // The stroke itself: its colour and its width.
        MenuCard(buildList<MenuGroup> {
            add {
                // "Default" follows the toolbar's active ink colour; a colour picked here pins the tool to it.
                MenuRow(stringResource(R.string.caption_colour)) {
                    ColorOrNone(
                        stringResource(R.string.default_choice),
                        colorOverride,
                        suggested = active ?: LocalPalette.current.text,
                        onChange = { colorOverride = it; emit() },
                    ) { d, p ->
                        ColorPickerPopup(initial = colorOverride ?: active, recents = editor.hostRecentColors, onDismiss = d, onPick = p)
                    }
                }
            }
            add {
                MenuSlider(
                    stringResource(R.string.caption_width), width,
                    range.start.toFloat()..range.endInclusive.toFloat(), step = ToolConversions.WIDTH_STEP.toFloat(),
                ) { width = it; emit() }
            }
            if (tool == Tool.HIGHLIGHTER) add { MenuSlider(stringResource(R.string.caption_intensity), intensity, 10f..100f) { intensity = it; emit() } }
            if (tool == Tool.DASHED) {
                add { MenuSlider(stringResource(R.string.caption_dash), dashLen, 2f..40f) { dashLen = it; emit() } }
                add { MenuSlider(stringResource(R.string.caption_gap), gapLen, 2f..40f) { gapLen = it; emit() } }
            }
        })
        // How it answers the hand.
        MenuCard(buildList<MenuGroup> {
            if (hasPressure) add {
                MenuSwitch(stringResource(R.string.caption_pressure), pressure) { pressure = it; emit() }
                if (pressure) MenuSlider(stringResource(R.string.caption_sensitivity), sensitivity, 0f..100f, child = true) { sensitivity = it; emit() }
            }
            if (tool == Tool.CALLIGRAPHY) add { MenuSlider(stringResource(R.string.caption_multiplier), multiplier, 1f..5f) { multiplier = it; emit() } }
            if (tool == Tool.SPEED) {
                add { MenuSlider(stringResource(R.string.caption_thinning), speed, 0f..100f) { speed = it; emit() } }
                add { MenuSlider(stringResource(R.string.caption_smoothing), smoothing, 0f..100f) { smoothing = it; emit() } }
                add { MenuSlider(stringResource(R.string.caption_top_speed), topSpeed, 0f..100f) { topSpeed = it; emit() } }
            }
            if (tool == Tool.TAPER) add { MenuSlider(stringResource(R.string.caption_tip_width), taperTip, 0f..100f) { taperTip = it; emit() } }
            // A straight-segment lock, for ruling and underlining.
            if (tool == Tool.HIGHLIGHTER) add { MenuSwitch(stringResource(R.string.caption_straight_line), straight) { straight = it; emit() } }
            // STABILIZATION steadies the path while drawing; a straight line has nothing to steady.
            if (tool.isStroke && !(tool == Tool.HIGHLIGHTER && straight)) add {
                MenuSwitch(stringResource(R.string.caption_stabilization), steady) { steady = it; emit() }
                if (steady) {
                    MenuSlider(stringResource(R.string.caption_strength), steadyStrength, 0f..100f, child = true) { steadyStrength = it; emit() }
                    MenuSlider(stringResource(R.string.caption_crossover), crossover, 0f..100f, child = true) { crossover = it; emit() }
                }
            }
        })
        // How it sits on the page.
        MenuCard(buildList<MenuGroup> {
            // Neon paints at full strength whatever the opacity, so opacity hides under it.
            if (tool.isStroke && tool != Tool.HIGHLIGHTER && !(hasGlow && glow)) add {
                MenuSwitch(stringResource(R.string.caption_opacity), faded) { faded = it; emit() }
                if (faded) MenuSlider(stringResource(R.string.caption_intensity), opacity, 10f..100f, step = 1f, child = true) { opacity = it; emit() }
            }
            if (hasGlow) add {
                MenuSwitch(stringResource(R.string.caption_neon), glow) { glow = it; emit() }
                if (glow) MenuSlider(stringResource(R.string.caption_glow_intensity), glowIntensity, 0f..100f, child = true) { glowIntensity = it; emit() }
            }
            // INVERSE swaps the multiply blend for a screen one, so the marker lightens the
            // page instead of darkening it. A multiply has nothing to darken on a dark page.
            if (tool == Tool.HIGHLIGHTER) add { MenuSwitch(stringResource(R.string.caption_inverse), inverse) { inverse = it; emit() } }
            // SCALE off: ink keeps a constant on-screen thickness whatever zoom you draw at.
            add { MenuSwitch(stringResource(R.string.caption_scale), scale) { scale = it; emit() } }
        })
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
    chip: @Composable (label: String, selected: Boolean, onClick: () -> Unit) -> Unit = { l, s, c -> ModeChip(l, s, c) },
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
            chip(stringResource(R.string.default_choice), style.spacing == null) { apply(style.copy(spacing = null)) }
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
                chip(stringResource(R.string.default_choice), own == null) {
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
            chip(stringResource(R.string.default_choice), own == null) {
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

    ToolMenu(stringResource(R.string.title_eraser), onDismiss) {
        MenuCard(buildList<MenuGroup> {
            add {
                MenuSegmented(listOf(stringResource(R.string.eraser_stroke), stringResource(R.string.eraser_area)), if (area) 1 else 0) { area = it == 1; emit() }
                val r = ToolConversions.widthRange(Tool.ERASER)
                MenuSlider(stringResource(R.string.caption_size), size, r.start.toFloat()..r.endInclusive.toFloat()) { size = it; emit() }
            }
        })
        MenuCard(buildList<MenuGroup> {
            // SCALE off: the eraser holds a constant on-screen size whatever zoom you are at.
            add { MenuSwitch(stringResource(R.string.caption_scale), scale) { scale = it; emit() } }
            // Re-arm the previous pen/highlighter once an erase lifts, so a quick fix doesn't strand
            // you in the eraser.
            add { MenuSwitch(stringResource(R.string.caption_switch_back), switchBack) { switchBack = it; emit() } }
            if (editor.hostHasPdf) add { MenuSwitch(stringResource(R.string.caption_erase_markups), markups) { markups = it; emit() } }
            // A pen scribbled over ink erases it without picking up the eraser.
            add { MenuSwitch(stringResource(R.string.caption_scribble_erase), scribble) { scribble = it; emit() } }
        })
    }
}

/** Select-tool configuration popup: just a SWITCH BACK toggle, mirroring the eraser's. */
@Composable
fun SelectConfigPopup(editor: ToolPopupHost, onDismiss: () -> Unit) {
    val base = remember { editor.toolConfig(Tool.SELECT) }
    var switchBack by remember { mutableStateOf(base.switchBackAfterSelect) }

    fun emit() = editor.updateToolConfig(Tool.SELECT, base.copy(switchBackAfterSelect = switchBack))

    ToolMenu(stringResource(R.string.title_select), onDismiss) {
        // Re-arm the previous pen/highlighter once a selection action (move, resize, delete,
        // cut, copy, duplicate) finishes, so a quick edit doesn't strand you in select.
        MenuCard(buildList<MenuGroup> {
            add { MenuSwitch(stringResource(R.string.caption_switch_back), switchBack) { switchBack = it; emit() } }
        })
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
    var width by remember { mutableStateOf(ToolConversions.snapWidth(Tool.TAPE, base.baseWidth).toFloat()) }
    var scale by remember { mutableStateOf(base.scale) }
    var colorOverride by remember { mutableStateOf(base.colorOverride) }

    fun emit() = editor.updateToolConfig(
        Tool.TAPE,
        base.copy(
            tapeMode = mode, tapePattern = pattern, baseWidth = ToolConversions.snapWidth(Tool.TAPE, width.toDouble()),
            scale = scale, colorOverride = colorOverride,
        ),
    )

    ToolMenu(stringResource(R.string.tool_tape), onDismiss) {
        MenuCard(buildList<MenuGroup> {
            add {
                val modes = TapeMode.entries
                MenuSegmented(modes.map { stringResource(it.labelRes) }, modes.indexOf(mode)) { mode = modes[it]; emit() }
                MenuRow(stringResource(R.string.caption_colour)) {
                    val active = editor.hostToolbarColors.getOrNull(editor.hostActiveColorIndex)
                    ColorOrNone(
                        stringResource(R.string.default_choice),
                        colorOverride,
                        suggested = active ?: LocalPalette.current.text,
                        onChange = { colorOverride = it; emit() },
                    ) { d, p ->
                        ColorPickerPopup(initial = colorOverride ?: active, recents = editor.hostRecentColors, onDismiss = d, onPick = p)
                    }
                }
            }
            add {
                Column(Modifier.padding(10.dp)) {
                    PanelCaption(stringResource(R.string.caption_pattern))
                    val patterns = TapePattern.entries
                    PanelSegmented(patterns.map { stringResource(it.labelRes) }, patterns.indexOf(pattern), { pattern = patterns[it]; emit() })
                }
            }
            // A rectangle is as tall as it is dragged, so only a strip and a band take a width.
            if (mode != TapeMode.RECTANGLE) {
                add {
                    val range = ToolConversions.widthRange(Tool.TAPE)
                    MenuSlider(
                        stringResource(R.string.caption_width), width,
                        range.start.toFloat()..range.endInclusive.toFloat(), step = ToolConversions.WIDTH_STEP.toFloat(),
                    ) { width = it; emit() }
                }
                add { MenuSwitch(stringResource(R.string.caption_scale), scale) { scale = it; emit() } }
            }
        })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MenuAction(stringResource(R.string.tape_reveal_all), XnotesIcons.eye, Modifier.weight(1f)) { editor.setAllTapePeeled(true) }
            MenuAction(stringResource(R.string.tape_cover_all), XnotesIcons.eyeOff, Modifier.weight(1f)) { editor.setAllTapePeeled(false) }
        }
    }
}

/** Insert space popup: the colour new room takes. Default leaves it the page's own colour. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InsertSpaceConfigPopup(editor: Editor, onDismiss: () -> Unit) {
    val base = remember { editor.toolConfig(Tool.INSERT_SPACE) }
    var color by remember { mutableStateOf(base.colorOverride) }

    fun pick(next: Rgba?) {
        color = next
        editor.updateToolConfig(Tool.INSERT_SPACE, base.copy(colorOverride = next))
    }

    ToolMenu(stringResource(R.string.tool_insert_space), onDismiss) {
        PanelCard(padding = PaddingValues(start = 12.dp, top = 10.dp, end = 12.dp, bottom = 14.dp)) {
            PanelCaption(stringResource(R.string.caption_colour))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                PanelChip(stringResource(R.string.default_choice), color == null) { pick(null) }
                SwatchCircles(editor, SwatchRole.PAPER, color) { pick(it) }
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

    ToolMenu(stringResource(R.string.tool_markup), onDismiss) {
        val modes = MarkupMode.entries
        val shaded = mode == MarkupMode.HIGHLIGHT
        MenuCard(buildList<MenuGroup> {
            add {
                Box(Modifier.padding(start = 10.dp, top = 10.dp, end = 10.dp, bottom = if (shaded) 0.dp else 10.dp)) {
                    IconSegmented(modes.map { markupIcon(it) }, modes.map { stringResource(it.labelRes) }, modes.indexOf(mode), height = 38.dp) {
                        mode = modes[it]
                        emit()
                    }
                }
                if (shaded) {
                    val min = (ToolConfig.MARKUP_INTENSITY_MIN * 100).toFloat()
                    MenuSlider(stringResource(R.string.caption_intensity), intensity, min..100f) { intensity = it; emit() }
                }
            }
        })
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
internal fun ToggleRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.width(220.dp)) {
        val palette = LocalPalette.current
        Text(label, color = (if (enabled) palette.text else palette.textDim).toComposeColor(), fontSize = 12.sp)
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
internal fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float = 0f,
    suffix: String = "",
    enabled: Boolean = true,
    onChangeFinished: (() -> Unit)? = null,
    onChange: (Float) -> Unit,
) {
    // A stepped row lands on its stops, so its label is the value given, not a rounding of it.
    val shown = if (step > 0f) snapTo(value, range, step) else value
    val number = if (step % 1f != 0f) "%.1f".format(shown).trimEnd('0').trimEnd('.', ',') else "%.0f".format(shown)
    Column {
        Text(
            "$label  $number$suffix",
            color = (if (enabled) LocalPalette.current.text else LocalPalette.current.textDim).toComposeColor(),
            fontSize = 12.sp,
        )
        Slider(
            value = shown,
            onValueChange = { if (step > 0f) onChange(snapTo(it, range, step)) else onChange(it) },
            onValueChangeFinished = onChangeFinished,
            valueRange = range,
            steps = if (step > 0f) {
                (((range.endInclusive - range.start) / step).roundToInt() - 1).coerceAtLeast(0)
            } else {
                0
            },
            enabled = enabled,
            // Stops this fine would draw as a speckled track; the label already names each one.
            colors = if (step > 0f) NoTicks else SliderDefaults.colors(),
        )
    }
}

internal val NoTicks
    @Composable get() = SliderDefaults.colors(
        activeTickColor = Color.Transparent,
        inactiveTickColor = Color.Transparent,
        disabledActiveTickColor = Color.Transparent,
        disabledInactiveTickColor = Color.Transparent,
    )

/** [value] moved to the nearest [step] from the start of [range], held at its ends. */
internal fun snapTo(value: Float, range: ClosedFloatingPointRange<Float>, step: Float): Float {
    return (range.start + ((value - range.start) / step).roundToInt() * step)
        .coerceIn(range.start, range.endInclusive)
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
    shape: Shape = MaterialTheme.shapes.extraSmall,
    containerColor: Color = LocalPalette.current.menuBg.toComposeColor(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalPalette.current
    val opened = remember(expanded) { Eyedropper.ended }
    val ended = expanded && Eyedropper.ended != opened
    LaunchedEffect(ended) { if (ended) onDismissRequest() }
    androidx.compose.material3.DropdownMenu(
        expanded = expanded && Eyedropper.target == null && !ended,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = LocalMenuOffset.current,
        properties = properties,
        shape = shape,
        containerColor = containerColor,
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
    if (Eyedropper.target != null) return
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
