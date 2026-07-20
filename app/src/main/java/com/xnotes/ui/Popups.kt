package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.canvas.ViewingMode
import com.xnotes.core.model.PagePattern
import com.xnotes.core.model.PageStyle
import com.xnotes.core.model.Rgba
import com.xnotes.core.pal.FontFace
import com.xnotes.core.tools.EraseMode
import com.xnotes.core.tools.ShapeConfig
import com.xnotes.core.tools.ShapeKind
import com.xnotes.core.tools.Tool
import com.xnotes.core.tools.ToolConversions
import com.xnotes.platform.FontCatalog
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
fun ToolConfigPopup(editor: Editor, tool: Tool, onDismiss: () -> Unit) {
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
                colorOverride = colorOverride,
            ),
        )
    }

    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Column(Modifier.width(250.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
            PopupTitle(tool.name)
            // COLOUR override: "Default" follows the toolbar's active ink colour; pick a hue to pin
            // this tool to it regardless of the toolbar selection.
            StyleCaption("COLOUR")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ModeChip("Default", colorOverride == null) { colorOverride = null; emit() }
                ColorPickerDot(
                    colorOverride,
                    custom = colorOverride != null,
                    onPick = { colorOverride = it; emit() },
                    dismissOnPick = false,
                ) { d, p ->
                    ColorPickerPopup(
                        initial = colorOverride ?: editor.toolbarColors.getOrNull(editor.activeColorIndex),
                        recents = editor.recentColors,
                        onDismiss = d,
                        onPick = p,
                    )
                }
            }
            Spacer(Modifier.size(12.dp))
            val hasPressure = tool == Tool.PEN || tool == Tool.CALLIGRAPHY || tool == Tool.SPEED || tool == Tool.TAPER
            if (hasPressure) {
                ToggleRow("PRESSURE", pressure) { pressure = it; emit() }
                SliderRow("SENSITIVITY", sensitivity, 0f..100f, enabled = pressure) { sensitivity = it; emit() }
            }
            if (tool == Tool.CALLIGRAPHY) {
                SliderRow("MULTIPLIER", multiplier, 1f..5f) { multiplier = it; emit() }
            }
            if (tool == Tool.SPEED) {
                SliderRow("SPEED", speed, 0f..100f) { speed = it; emit() }
            }
            if (tool == Tool.TAPER) {
                SliderRow("TIP WIDTH PERCENTAGE", taperTip, 0f..100f) { taperTip = it; emit() }
            }
            val range = ToolConversions.widthRange(tool)
            SliderRow("WIDTH", width, range.start.toFloat()..range.endInclusive.toFloat()) { width = it; emit() }
            // SCALE off: ink keeps a constant on-screen thickness whatever zoom you draw at.
            ToggleRow("SCALE", scale) { scale = it; emit() }
            if (tool == Tool.DASHED) {
                SliderRow("DASH", dashLen, 2f..40f) { dashLen = it; emit() }
                SliderRow("GAP", gapLen, 2f..40f) { gapLen = it; emit() }
            }
            // The highlighter's strength (translucency) and an optional straight-segment lock
            // (for ruling/underlining).
            if (tool == Tool.HIGHLIGHTER) {
                SliderRow("INTENSITY", intensity, 10f..90f) { intensity = it; emit() }
                ToggleRow("STRAIGHT LINE", straight) { straight = it; emit() }
            }
            // Glow is offered on every stroke tool except the highlighter (translucent) and the
            // dashed pen (it draws a line, not a fillable ribbon, so a halo has nothing to hug).
            if (tool.isStroke && tool != Tool.HIGHLIGHTER && tool != Tool.DASHED) {
                ToggleRow("NEON", glow) { glow = it; emit() }
                if (glow) {
                    SliderRow("INTENSITY", glowIntensity, 0f..100f) { glowIntensity = it; emit() }
                }
            }
        }
    }
}

/**
 * Page-styles popup (spec 10): two tabs — "All Pages" (the document-wide override) and "Current
 * Page" — each editing the same controls: paper colour, a ruling (None/Lines/Dots/Grid), its spacing
 * and colour. Every control is tri-state: "Default" leaves the field unset so it inherits the level
 * below (page → document → the global page-colour preference / a built-in default); the global
 * default itself is unchanged here (it lives in Preferences). Like [ToolConfigPopup], the popup holds
 * the edited style locally and pushes each change to the [Editor] (which persists, but never undoes).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StylesPopup(editor: Editor, onDismiss: () -> Unit) {
    var tab by remember { mutableStateOf(0) } // 0 = All Pages, 1 = Current Page
    var docStyle by remember { mutableStateOf(editor.documentStyle) }
    var pageStyle by remember { mutableStateOf(editor.currentPageStyle) }
    val style = if (tab == 0) docStyle else pageStyle
    fun apply(next: PageStyle) {
        if (tab == 0) { docStyle = next; editor.setDocumentStyle(next) }
        else { pageStyle = next; editor.setCurrentPageStyle(next) }
    }

    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Column(Modifier.width(286.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
            PopupTitle("STYLES")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ModeChip("All Pages", tab == 0) { tab = 0 }
                ModeChip("Current Page", tab == 1) { tab = 1 }
            }

            Spacer(Modifier.size(12.dp))
            StyleCaption("PAGE COLOUR")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ModeChip("Default", style.pageColor == null) { apply(style.copy(pageColor = null)) }
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
            StyleCaption("PATTERN")
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ModeChip("Default", style.pattern == null) { apply(style.copy(pattern = null)) }
                ModeChip("None", style.pattern == PagePattern.NONE) { apply(style.copy(pattern = PagePattern.NONE)) }
                ModeChip("Lines", style.pattern == PagePattern.LINES) { apply(style.copy(pattern = PagePattern.LINES)) }
                ModeChip("Dots", style.pattern == PagePattern.DOTS) { apply(style.copy(pattern = PagePattern.DOTS)) }
                ModeChip("Grid", style.pattern == PagePattern.GRID) { apply(style.copy(pattern = PagePattern.GRID)) }
            }

            Spacer(Modifier.size(12.dp))
            val spacing = style.spacing ?: PageStyle.DEFAULT_SPACING
            StyleCaption("SPACING  ${spacing.toInt()} px" + if (style.spacing == null) "  (default)" else "")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModeChip("Default", style.spacing == null) { apply(style.copy(spacing = null)) }
                Slider(
                    value = spacing.toFloat().coerceIn(PageStyle.MIN_SPACING.toFloat(), PageStyle.MAX_SPACING.toFloat()),
                    onValueChange = { apply(style.copy(spacing = it.toDouble())) },
                    valueRange = PageStyle.MIN_SPACING.toFloat()..PageStyle.MAX_SPACING.toFloat(),
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.size(12.dp))
            // Effective pattern colour: the page's own, else (on the Current Page tab) the document's,
            // else the built-in grey. Its alpha is the opacity the slider below edits.
            val effPatternColor = style.patternColor
                ?: (if (tab == 1) docStyle.patternColor else null)
                ?: PageStyle.DEFAULT_PATTERN_COLOR
            StyleCaption("PATTERN COLOUR")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ModeChip("Default", style.patternColor == null) { apply(style.copy(patternColor = null)) }
                ColorPickerDot(
                    style.patternColor?.copy(a = 255), // show the hue at full strength; OPACITY sets the alpha
                    custom = style.patternColor != null,
                    onPick = { apply(style.copy(patternColor = it.copy(a = effPatternColor.a))) }, // keep current opacity
                    dismissOnPick = false,
                ) { d, p -> PageColorGridPopup(style.patternColor?.copy(a = 255), d, p) }
            }

            Spacer(Modifier.size(12.dp))
            val opacityPct = effPatternColor.a * 100f / 255f
            StyleCaption("OPACITY  ${opacityPct.roundToInt()}%")
            Slider(
                value = opacityPct,
                onValueChange = { pct ->
                    apply(style.copy(patternColor = effPatternColor.copy(a = (pct / 100f * 255f).roundToInt().coerceIn(0, 255))))
                },
                valueRange = 0f..100f,
            )
        }
    }
}

@Composable
private fun StyleCaption(text: String) {
    Text(
        text,
        color = LocalPalette.current.textDim.toComposeColor(),
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
    )
}

/**
 * The toolbar's View menu, in two tabs: GLOBAL edits the app-wide default view settings
 * (persisted with the app settings), THIS DOC edits the open note's overrides of those
 * defaults — stored app-side like zoom/scroll, never in the file itself. The This Doc tab
 * always shows the note's effective (resolved) values; a control changed there shadows
 * the global default for this note only.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ViewMenuPopup(editor: Editor, onDismiss: () -> Unit) {
    var tab by remember { mutableStateOf(1) } // open on This Doc — the everyday target
    val global = tab == 0
    val defaults = editor.viewDefaults
    val overrides = editor.viewOverrides
    val vs = if (global) defaults else editor.viewSettings

    // While the Global tab is open the canvas previews the pure defaults on this document;
    // switching back (or closing the menu however it closes) re-applies the note's own view.
    LaunchedEffect(global) { editor.setGlobalViewPreview(global) }
    DisposableEffect(Unit) { onDispose { editor.setGlobalViewPreview(false) } }

    fun setMode(v: ViewingMode) =
        if (global) editor.updateViewDefaults(defaults.copy(mode = v)) else editor.updateViewOverrides(overrides.copy(mode = v))
    fun setVerticalScroll(v: Boolean) =
        if (global) editor.updateViewDefaults(defaults.copy(verticalScroll = v)) else editor.updateViewOverrides(overrides.copy(verticalScroll = v))
    fun setContrast(v: Int) =
        if (global) editor.updateViewDefaults(defaults.copy(contrast = v)) else editor.updateViewOverrides(overrides.copy(contrast = v))
    fun setInvert(v: Int) =
        if (global) editor.updateViewDefaults(defaults.copy(invert = v)) else editor.updateViewOverrides(overrides.copy(invert = v))
    fun setBrightness(v: Int) =
        if (global) editor.updateViewDefaults(defaults.copy(brightness = v)) else editor.updateViewOverrides(overrides.copy(brightness = v))
    fun setSepia(v: Int) =
        if (global) editor.updateViewDefaults(defaults.copy(sepia = v)) else editor.updateViewOverrides(overrides.copy(sepia = v))
    fun setKeepImages(v: Boolean) =
        if (global) editor.updateViewDefaults(defaults.copy(keepImages = v)) else editor.updateViewOverrides(overrides.copy(keepImages = v))
    fun setRotation(v: Int) =
        if (global) editor.updateViewDefaults(defaults.copy(rotation = v)) else editor.updateViewOverrides(overrides.copy(rotation = v))
    fun setScrollbar(v: Boolean) =
        if (global) editor.updateViewDefaults(defaults.copy(scrollbar = v)) else editor.updateViewOverrides(overrides.copy(scrollbar = v))

    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Column(Modifier.width(300.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
            PopupTitle("VIEW")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ModeChip("Global", global) { tab = 0 }
                ModeChip("This Doc", !global) { tab = 1 }
            }
            Text(
                if (global) "Applies on every document" else "Overrides the Global view settings for this document",
                color = LocalPalette.current.textDim.toComposeColor(),
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 6.dp),
            )

            Spacer(Modifier.size(10.dp))
            StyleCaption("VIEWING MODE")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ModeChip("Single", vs.mode == ViewingMode.SINGLE) { setMode(ViewingMode.SINGLE) }
                ModeChip("Double", vs.mode == ViewingMode.DOUBLE) { setMode(ViewingMode.DOUBLE) }
                ModeChip("Cover", vs.mode == ViewingMode.COVER) { setMode(ViewingMode.COVER) }
            }

            Spacer(Modifier.size(10.dp))
            ToggleRow("VERTICAL SCROLLING", vs.verticalScroll) { setVerticalScroll(it) }

            Spacer(Modifier.size(10.dp))
            StyleCaption("PDF COLOUR FILTERS")
            FilterSpinRow("Contrast", vs.contrast, 0, 200) { setContrast(it) }
            FilterSpinRow("Invert", vs.invert, 0, 100) { setInvert(it) }
            FilterSpinRow("Brightness", vs.brightness, 0, 200) { setBrightness(it) }
            FilterSpinRow("Sepia", vs.sepia, 0, 100) { setSepia(it) }
            ToggleRow("DON'T FILTER IMAGES", vs.keepImages) { setKeepImages(it) }

            Spacer(Modifier.size(10.dp))
            StyleCaption("ROTATE  ${vs.rotation}°")
            Slider(
                value = vs.rotation.toFloat(),
                onValueChange = { setRotation((it / 90f).roundToInt() * 90) },
                valueRange = 0f..270f,
                steps = 2,
            )

            ToggleRow("SCROLLBAR", vs.scrollbar) { setScrollbar(it) }

            Spacer(Modifier.size(10.dp))
            ToggleRow("SPLIT SCREEN", editor.splitScreenActive) {
                editor.toggleSplitScreen()
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
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(46.dp),
        )
        Box(Modifier.size(34.dp).clickable { onChange((value + 5).coerceIn(min, max)) }, contentAlignment = Alignment.Center) {
            Text("+", color = palette.text.toComposeColor(), fontSize = 18.sp)
        }
    }
}

/** Eraser configuration popup: a STROKE/AREA mode picker and a SIZE slider (the eraser radius). */
@Composable
fun EraserConfigPopup(editor: Editor, onDismiss: () -> Unit) {
    val base = remember { editor.toolConfig(Tool.ERASER) }
    var area by remember { mutableStateOf(base.eraseMode == EraseMode.AREA) }
    var size by remember { mutableStateOf(base.baseWidth.toFloat()) }
    var switchBack by remember { mutableStateOf(base.switchBackAfterErase) }
    var scale by remember { mutableStateOf(base.scale) }

    fun emit() = editor.updateToolConfig(
        Tool.ERASER,
        base.copy(
            baseWidth = size.toDouble(),
            eraseMode = if (area) EraseMode.AREA else EraseMode.STROKE,
            switchBackAfterErase = switchBack,
            scale = scale,
        ),
    )

    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Column(Modifier.width(250.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
            PopupTitle("ERASER")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ModeChip("STROKE", selected = !area) { area = false; emit() }
                ModeChip("AREA", selected = area) { area = true; emit() }
            }
            val r = ToolConversions.widthRange(Tool.ERASER)
            SliderRow("SIZE", size, r.start.toFloat()..r.endInclusive.toFloat()) { size = it; emit() }
            // SCALE off: the eraser holds a constant on-screen size whatever zoom you are at.
            ToggleRow("SCALE", scale) { scale = it; emit() }
            // Re-arm the previous pen/highlighter once an erase lifts, so a quick fix doesn't strand
            // you in the eraser.
            ToggleRow("SWITCH BACK", switchBack) { switchBack = it; emit() }
        }
    }
}

/** Select-tool configuration popup: just a SWITCH BACK toggle, mirroring the eraser's. */
@Composable
fun SelectConfigPopup(editor: Editor, onDismiss: () -> Unit) {
    val base = remember { editor.toolConfig(Tool.SELECT) }
    var switchBack by remember { mutableStateOf(base.switchBackAfterSelect) }

    fun emit() = editor.updateToolConfig(Tool.SELECT, base.copy(switchBackAfterSelect = switchBack))

    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Column(Modifier.width(250.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
            PopupTitle("SELECT")
            // Re-arm the previous pen/highlighter once a selection action (move, resize, delete,
            // cut, copy, duplicate) finishes, so a quick edit doesn't strand you in select.
            ToggleRow("SWITCH BACK", switchBack) { switchBack = it; emit() }
        }
    }
}

/** Shape-tool configuration popup (spec 10 §3 / 04 §6): kind picker, WIDTH, FILL. */
@Composable
fun ShapeConfigPopup(editor: Editor, onDismiss: () -> Unit) {
    var kind by remember { mutableStateOf(editor.shapeConfig.shape) }
    var width by remember { mutableStateOf(editor.shapeConfig.strokeWidth.toFloat()) }
    var fill by remember { mutableStateOf(editor.shapeConfig.fill) }
    var glow by remember { mutableStateOf(editor.shapeConfig.neon) }
    var glowIntensity by remember { mutableStateOf(ToolConversions.neonStrengthToIntensity(editor.shapeConfig.neonStrength).toFloat()) }

    fun emit() = editor.updateShapeConfig(
        ShapeConfig(kind, width.toDouble(), fill, glow, ToolConversions.intensityToNeonStrength(glowIntensity.toDouble())),
    )

    DropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        Column(Modifier.width(284.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
            PopupTitle("SHAPE")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ShapeKind.DRAW_TOOL_KINDS.forEach { k ->
                    KindChip(shapeIcon(k), k.id, selected = kind == k) { kind = k; emit() }
                }
            }
            SliderRow("WIDTH", width, 1f..20f) { width = it; emit() }
            ToggleRow("FILL", fill) { fill = it; emit() }
            ToggleRow("NEON", glow) { glow = it; emit() }
            if (glow) {
                SliderRow("INTENSITY", glowIntensity, 0f..100f) { glowIntensity = it; emit() }
            }
        }
    }
}

/** Colour switcher (spec 10 §4): the toolbar swatch picker — opens the shared [ColorPickerPopup]
 *  and writes the chosen colour back to swatch [index]. Picks apply live; the final colour is
 *  remembered into recents when the popup closes. */
@Composable
fun ColorSwitcherPopup(editor: Editor, index: Int, onDismiss: () -> Unit) {
    ColorPickerPopup(
        initial = editor.toolbarColors.getOrNull(index),
        recents = editor.recentColors,
        onDismiss = { editor.rememberSwatchColor(index); onDismiss() },
        onPick = { editor.setSwatchColor(index, it) },
    )
}

@Composable
private fun PopupTitle(text: String) {
    Text(
        text,
        color = LocalPalette.current.accent.toComposeColor(),
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.width(220.dp)) {
        Text(label, color = LocalPalette.current.text.toComposeColor(), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, enabled: Boolean = true, onChange: (Float) -> Unit) {
    Column {
        Text(
            "$label  ${"%.0f".format(value)}",
            color = (if (enabled) LocalPalette.current.text else LocalPalette.current.textDim).toComposeColor(),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
        )
        Slider(value = value, onValueChange = onChange, valueRange = range, enabled = enabled)
    }
}

/** Glyph shown in the shape-kind picker for each [ShapeKind]. */
private fun shapeIcon(kind: ShapeKind): ImageVector = when (kind) {
    ShapeKind.LINE, ShapeKind.POLYLINE, ShapeKind.CURVE -> XnotesIcons.shapeLine
    ShapeKind.ARROW -> XnotesIcons.shapeArrow
    ShapeKind.RECTANGLE -> XnotesIcons.shapeRect
    ShapeKind.ELLIPSE -> XnotesIcons.shapeEllipse
    ShapeKind.CIRCLE -> XnotesIcons.shapeCircle
    ShapeKind.TRIANGLE, ShapeKind.POLYGON -> XnotesIcons.shapeTriangle
}

@Composable
private fun KindChip(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(if (selected) palette.accentAlpha(48).toComposeColor() else palette.surface.toComposeColor())
            .border(1.dp, if (selected) palette.accent.toComposeColor() else palette.border.toComposeColor(), RoundedCornerShape(5.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (selected) palette.accent.toComposeColor() else palette.text.toComposeColor(),
            modifier = Modifier.size(20.dp),
        )
    }
}

/** A text-label chip for a segmented picker (e.g. the eraser's STROKE/AREA modes). */
@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .clip(RoundedCornerShape(5.dp))
            .background(if (selected) palette.accentAlpha(48).toComposeColor() else palette.surface.toComposeColor())
            .border(1.dp, if (selected) palette.accent.toComposeColor() else palette.border.toComposeColor(), RoundedCornerShape(5.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            color = if (selected) palette.accent.toComposeColor() else palette.text.toComposeColor(),
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
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
        DropdownMenuItem(
            text = {
                Text(
                    "Default",
                    color = (if (current == null) palette.accent else palette.text).toComposeColor(),
                    fontSize = 14.sp,
                )
            },
            onClick = { onPick(null) },
        )
    }
    for (choice in FontCatalog.choices()) {
        if (monoOnly && !choice.mono) continue
        DropdownMenuItem(
            text = {
                Text(
                    choice.label,
                    color = (if (choice.face == current) palette.accent else palette.text).toComposeColor(),
                    style = TextStyle(fontFamily = choice.face.toComposeFamily(), fontSize = 14.sp),
                )
            },
            onClick = { onPick(choice.face) },
        )
    }
}
