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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GridOff
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.window.DialogProperties
import com.hrm.latex.renderer.Latex
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.LatexTheme
import com.xnotes.R
import com.xnotes.core.model.DrawStyle
import com.xnotes.core.model.FunctionCurve
import com.xnotes.core.model.FunctionSpec
import com.xnotes.core.model.Rgba
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/**
 * What the selection menu needs from whichever editor is open.
 *
 * The bar is the same bar on either canvas, so it is the same composable rather than a second one
 * that resembles it. Taking the few members it reads through an interface is what makes "identical"
 * a property of the code rather than something to keep checking, exactly as [ToolPopupHost] does
 * for the tool popups.
 */
interface SelectionMenuHost {
    /** Where the settled selection sits in viewport pixels, or null to hide the bar. */
    val selectionMenuRect: com.xnotes.core.geometry.Rect?

    fun deleteSelection()
    fun cutSelection()
    fun copySelection()
    fun bringToFront()
    fun duplicateSelection()
    fun dismissSelectionMenu()

    /** Pin the selection where it is and put it away; a held finger over it offers to release it. */
    fun lockSelection()

    /** True when the selection is a single table (shows the table edit toggle in the menu). */
    val selectionIsTable: Boolean

    /** Whether table edit mode is currently on. */
    val tableEditing: Boolean

    /** Toggle the table edit overlay (add/remove columns and rows, drag interior lines). */
    fun toggleTableEditMode()

    /** Control points on the lone selected spline, or 0 when the selection is not one. */
    val selectionSplinePoints: Int

    /** Add a control point to the selected spline, or remove one (it keeps at least one in the middle). */
    fun editSelectionSpline(add: Boolean)

    /** What the lone selected function curve plots, or null when the selection is not one. */
    val selectionFunction: FunctionSpec?

    /** Replot the selected function curve; false when [spec] does not parse or has no values. */
    fun setSelectionFunction(spec: FunctionSpec): Boolean

    /** True when the selection is a single image (shows the Crop action). */
    val selectionIsImage: Boolean

    /** Enter crop mode for the selected image. */
    fun cropSelection()

    /** True when the selection is a single text box (shows the Edit action). */
    val selectionIsText: Boolean

    /** Reopen the text editor on the selected text box. */
    fun editSelectionText()

    /** The LaTeX of the lone selected formula box, or null when the selection is not one. */
    val selectionMath: String?

    /** Replace the selected formula's LaTeX; false when it does not set. */
    fun setSelectionMath(latex: String): Boolean

    /** True when exactly one item is selected (it can carry a hyperlink). */
    val selectionCanLink: Boolean

    /** The selected item's hyperlink, or null when none / not a single item. */
    val selectionLink: String?

    /** Set (or clear, when null) the selected item's hyperlink. */
    fun setSelectionLink(url: String?)

    /** True when the selection contains handwriting (shows the Convert to text action). */
    val selectionHasInk: Boolean

    /** Recognize the selected handwriting and replace it with a text box (async). */
    fun convertSelectionToText()

    /** True when the handwritten-maths add-on is downloaded (shows Convert to math). */
    val canConvertToMath: Boolean

    /** Recognize the selected handwriting as a formula and replace it with a formula box (async). */
    fun convertSelectionToMath()

    /** The colour and width of every selected stroke/shape, for the restyle popup to open on. */
    fun selectionStyles(): List<DrawStyle>

    /**
     * Recolour and/or re-thicken the selection; a null [color] or [width] leaves that half alone.
     * A [preview] call skips history, and the next call without it records everything since as one
     * undo step, so dragging the thickness slider is a single edit rather than one per sample.
     */
    fun restyleSelection(color: Rgba?, width: Double?, preview: Boolean = false)

    /** The toolbar's ink swatches and recently picked colours, offered by the restyle popup. */
    val hostToolbarColors: List<Rgba>
    val hostRecentColors: List<Rgba>
}

/**
 * Floating action bar shown above a settled selection (spec-adjacent): delete,
 * cut, copy, bring-to-front, duplicate, and an overflow menu for the rest.
 * Hidden while moving/resizing. Rotation is not here: everything the bar can be
 * shown over turns by its own grip.
 */
@Composable
fun SelectionMenu(host: SelectionMenuHost) {
    val rect = host.selectionMenuRect ?: return
    val palette = LocalPalette.current
    val density = LocalDensity.current
    var overflowOpen by remember { mutableStateOf(false) }
    var styleOpen by remember { mutableStateOf(false) }
    var linkDialogOpen by remember { mutableStateOf(false) }
    var functionDialogOpen by remember { mutableStateOf(false) }
    var mathDialogOpen by remember { mutableStateOf(false) }

    val barHeightPx = with(density) { 48.dp.toPx() }
    val barWidthPx = with(density) { (6 * 46).dp.toPx() }
    val gap = with(density) { 10.dp.toPx() }
    val centerX = ((rect.left + rect.right) / 2.0).toFloat()
    val xPx = (centerX - barWidthPx / 2f).coerceAtLeast(with(density) { 8.dp.toPx() })
    val yPx = if (rect.top.toFloat() - barHeightPx - gap > 0f) {
        rect.top.toFloat() - barHeightPx - gap
    } else {
        rect.bottom.toFloat() + gap
    }
    val xDp = with(density) { xPx.toDp() }
    val yDp = with(density) { yPx.toDp() }

    Row(
        modifier = Modifier
            .offset(xDp, yDp)
            .clip(MaterialTheme.shapes.medium)
            .background(palette.menuBg.toComposeColor())
            .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.medium),
    ) {
        ActionIcon(XnotesIcons.trash, stringResource(R.string.delete)) { host.deleteSelection() }
        ActionIcon(XnotesIcons.cut, stringResource(R.string.cut)) { host.cutSelection() }
        ActionIcon(XnotesIcons.copy, stringResource(R.string.copy)) { host.copySelection(); host.dismissSelectionMenu() }
        ActionIcon(XnotesIcons.front, stringResource(R.string.bring_to_front)) { host.bringToFront(); host.dismissSelectionMenu() }
        ActionIcon(XnotesIcons.duplicate, stringResource(R.string.duplicate)) { host.duplicateSelection() }
        if (host.selectionIsTable) {
            ActionIcon(XnotesIcons.table, stringResource(R.string.edit_table), active = host.tableEditing) { host.toggleTableEditMode() }
        }
        Box {
            ActionIcon(XnotesIcons.more, stringResource(R.string.more)) { overflowOpen = true }
            DropdownMenu(
                expanded = overflowOpen,
                onDismissRequest = { overflowOpen = false },
                properties = PopupProperties(focusable = false),
            ) {
                // Empty when nothing selected carries ink: an image or a text box has no
                // colour-and-width pair to restyle.
                val styles = host.selectionStyles()
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.change_style)) },
                    enabled = styles.isNotEmpty(),
                    onClick = { overflowOpen = false; styleOpen = true },
                )
                var splinePoints by remember { mutableStateOf(host.selectionSplinePoints) }
                if (splinePoints > 0) {
                    // Left open so a few taps in a row add or strip several points.
                    DropdownMenuItem(
                        text = { Text("Add curve point") },
                        onClick = { host.editSelectionSpline(add = true); splinePoints = host.selectionSplinePoints },
                    )
                    DropdownMenuItem(
                        text = { Text("Remove curve point") },
                        enabled = splinePoints > 3,
                        onClick = { host.editSelectionSpline(add = false); splinePoints = host.selectionSplinePoints },
                    )
                }
                if (host.selectionFunction != null) {
                    DropdownMenuItem(
                        text = { Text("Edit function") },
                        onClick = { overflowOpen = false; functionDialogOpen = true },
                    )
                }
                if (host.selectionIsText) {
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        // A formula is edited as its LaTeX source, not in the text box editor.
                        onClick = { overflowOpen = false; if (host.selectionMath != null) mathDialogOpen = true else host.editSelectionText() },
                    )
                }
                if (host.selectionIsImage) {
                    DropdownMenuItem(
                        text = { Text("Crop") },
                        onClick = { overflowOpen = false; host.cropSelection() },
                    )
                }
                if (host.selectionHasInk) {
                    DropdownMenuItem(
                        text = { Text("Convert to text") },
                        onClick = { overflowOpen = false; host.convertSelectionToText() },
                    )
                    if (host.canConvertToMath) {
                        DropdownMenuItem(
                            text = { Text("Convert to math") },
                            onClick = { overflowOpen = false; host.convertSelectionToMath() },
                        )
                    }
                }
                if (host.selectionCanLink) {
                    DropdownMenuItem(
                        text = { Text(if (host.selectionLink != null) "Edit link" else "Add link") },
                        onClick = { overflowOpen = false; linkDialogOpen = true },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.lock)) },
                    onClick = { overflowOpen = false; host.lockSelection() },
                )
            }
            if (styleOpen) {
                // Closing settles any preview the slider left open.
                SelectionStylePopup(host) { host.restyleSelection(null, null); styleOpen = false }
            }
            val latex = host.selectionMath
            if (mathDialogOpen && latex != null) {
                LatexDialog(
                    initial = latex,
                    onConfirm = { if (host.setSelectionMath(it)) mathDialogOpen = false },
                    onDismiss = { mathDialogOpen = false },
                )
            }
            val function = host.selectionFunction
            if (functionDialogOpen && function != null) {
                FunctionDialog(
                    initial = function,
                    onConfirm = { if (host.setSelectionFunction(it)) functionDialogOpen = false },
                    onDismiss = { functionDialogOpen = false },
                )
            }
            if (linkDialogOpen) {
                LinkDialog(
                    initial = host.selectionLink ?: "",
                    onConfirm = { host.setSelectionLink(it); linkDialogOpen = false },
                    onRemove = { host.setSelectionLink(null); linkDialogOpen = false },
                    onDismiss = { linkDialogOpen = false },
                )
            }
        }
    }
}

/**
 * Retype what a function curve plots, e.g. sin(2x) or x^3 - x, and over which x range. The range
 * fields take expressions too, so "2pi" works. Save stays disabled until the curve can be drawn.
 */
@Composable
private fun FunctionDialog(initial: FunctionSpec, onConfirm: (FunctionSpec) -> Unit, onDismiss: () -> Unit) {
    var expr by remember { mutableStateOf(initial.expr) }
    var from by remember { mutableStateOf(formatBound(initial.from)) }
    var to by remember { mutableStateOf(formatBound(initial.to)) }
    val spec = run {
        val a = FunctionCurve.constant(from) ?: return@run null
        val b = FunctionCurve.constant(to) ?: return@run null
        FunctionSpec(expr.trim(), a, b).takeIf { it.normalizedSamples() != null }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Function") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = expr,
                    onValueChange = { expr = it },
                    singleLine = true,
                    label = { Text("y =") },
                    isError = spec == null,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = from,
                        onValueChange = { from = it },
                        singleLine = true,
                        label = { Text("x from") },
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = to,
                        onValueChange = { to = it },
                        singleLine = true,
                        label = { Text("x to") },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = spec != null, onClick = { spec?.let(onConfirm) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Keys under the LaTeX field, like a maths keyboard. `#` marks where the cursor lands (and where a
 * selection is wrapped); the empty slots show as boxes on the key.
 */
private val LATEX_STRUCTURE_KEYS = listOf(
    "\\frac{#}{}", "^{#}", "_{#}", "\\sqrt{#}", "\\sqrt[3]{#}", "\\sqrt[#]{}",
    "\\left(#\\right)'", "\\frac{d}{d#}", "\\frac{d^{2}}{d#^{2}}", "\\int # \\, dx", "\\int_{#}^{} \\, dx",
    "\\sum_{#}^{}", "\\lim_{# \\to }", "\\left(#\\right)", "\\left|#\\right|", "\\log_{#}", "\\ln #",
    "\\sin #", "\\cos #", "\\tan #",
)

private val LATEX_SYMBOL_KEYS = listOf(
    "+", "-", "\\cdot", "\\div", "=", "\\neq", "<", ">", "\\le", "\\ge", "\\pm", "\\infty",
    "\\pi", "e", "\\alpha", "\\beta", "\\theta", "\\lambda", "\\Delta", "\\to",
)

private fun latexKeyLabel(template: String): String =
    template.replace("#", "\\square").replace("{}", "{\\square}")

/** Put [template] at the cursor, wrapping the selection at its `#`, and leave the cursor there. */
private fun insertLatex(value: TextFieldValue, template: String): TextFieldValue {
    val sel = value.selection
    val selected = value.text.substring(sel.min, sel.max)
    val mark = template.indexOf('#').takeIf { it >= 0 } ?: template.length
    val body = template.replace("#", "")
    // A letter right after a command would run into its name: \pi then e must not read \pie.
    val afterCommand = Regex("\\\\[a-zA-Z]+$").containsMatchIn(value.text.substring(0, sel.min))
    val spaced = if (body.first().isLetter() && afterCommand) " " else ""
    val inserted = spaced + body.substring(0, mark) + selected + body.substring(mark)
    val text = value.text.substring(0, sel.min) + inserted + value.text.substring(sel.max)
    val cursor = sel.min + spaced.length + mark + selected.length
    return TextFieldValue(text, TextRange(cursor))
}

/** Move the cursor into the next empty `{}` after it, wrapping to the start. */
private fun nextLatexSlot(value: TextFieldValue): TextFieldValue {
    val from = value.selection.max
    val i = value.text.indexOf("{}", from).takeIf { it >= 0 } ?: value.text.indexOf("{}")
    return if (i < 0) value else value.copy(selection = TextRange(i + 1))
}

/** Edit a formula box's LaTeX. Save stays on the dialog when the renderer cannot set the result. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LatexDialog(initial: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    val keyConfig = LatexConfig(fontSize = 14.sp, theme = LatexTheme.material3())
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.widthIn(max = 720.dp).padding(horizontal = 16.dp),
        title = { Text("Formula") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 72.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp),
                ) {
                    Latex(
                        latex = "\\displaystyle " + value.text,
                        config = LatexConfig(fontSize = 22.sp, theme = LatexTheme.material3()),
                    )
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("LaTeX") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = androidx.compose.ui.text.TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    (LATEX_STRUCTURE_KEYS + LATEX_SYMBOL_KEYS).forEach { key ->
                        LatexKey(onClick = { value = insertLatex(value, key) }) {
                            Latex(latex = latexKeyLabel(key), config = keyConfig)
                        }
                    }
                    LatexKey(onClick = { value = nextLatexSlot(value) }) { Text("Next") }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = value.text.isNotBlank(), onClick = { onConfirm(value.text.trim()) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun LatexKey(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(width = 56.dp, height = 48.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(onClick = onClick),
    ) { content() }
}

/** A range bound as the user would type it: multiples of pi as "2pi", the rest trimmed. */
private fun formatBound(v: Double): String {
    val k = v / Math.PI
    val rounded = Math.round(k * 2) / 2.0
    if (v != 0.0 && kotlin.math.abs(k - rounded) < 1e-9) {
        return when (rounded) {
            1.0 -> "pi"
            -1.0 -> "-pi"
            else -> "${rounded.toString().removeSuffix(".0")}pi"
        }
    }
    return v.toString().removeSuffix(".0")
}

/**
 * Add/edit a hyperlink on the selected item. A blank field with Save clears the link; the Remove
 * button (shown only when one exists) does the same. Only http/https/mailto actually open on tap
 * (the opener rejects the rest), so no scheme validation is forced here.
 */
@Composable
private fun LinkDialog(initial: String, onConfirm: (String) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Link") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text("https://example.com") },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }) { Text("Save") }
        },
        dismissButton = {
            if (initial.isNotEmpty()) {
                TextButton(onClick = onRemove) { Text("Remove") }
            } else {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/**
 * "Change style" on a settled selection: the toolbar's swatches plus the full colour picker, and a
 * thickness slider. Both apply live, so the result can be judged on the page rather than guessed
 * at. The controls open on the styles the selection had when the popup did — a shared colour when
 * every item agrees, the first item's otherwise.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectionStylePopup(host: SelectionMenuHost, onDismiss: () -> Unit) {
    val palette = LocalPalette.current
    val opened = remember { host.selectionStyles() }
    val first = opened.firstOrNull()
    val shared = opened.firstOrNull()?.color?.takeIf { c -> opened.all { it.color == c } }
    var color by remember { mutableStateOf(shared ?: first?.color ?: Rgba(0, 0, 0)) }
    var width by remember { mutableStateOf((first?.width ?: 1.0).toFloat()) }
    if (first == null) return

    DropdownMenu(expanded = true, onDismissRequest = onDismiss, properties = PopupProperties(focusable = false)) {
        Column(Modifier.width(250.dp).padding(horizontal = 14.dp, vertical = 8.dp)) {
            PopupTitle(stringResource(R.string.title_change_style))
            StyleCaption(stringResource(R.string.caption_colour))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                host.hostToolbarColors.forEach { c ->
                    ColorDot(c.toComposeColor(), selected = color == c) {
                        color = c
                        host.restyleSelection(c, null)
                    }
                }
                ColorPickerDot(
                    color,
                    custom = color !in host.hostToolbarColors,
                    onPick = { color = it; host.restyleSelection(it, null) },
                    dismissOnPick = false,
                ) { dismiss, pick ->
                    ColorPickerPopup(
                        initial = color,
                        recents = host.hostRecentColors,
                        onDismiss = dismiss,
                        onPick = pick,
                    )
                }
            }
            Spacer(Modifier.size(8.dp))
            // The drag previews live and commits on release, so it is one undo step, not fifty.
            SliderRow(
                stringResource(R.string.caption_thickness),
                width,
                DrawStyle.MIN_WIDTH.toFloat()..DrawStyle.MAX_WIDTH.toFloat(),
                onChangeFinished = { host.restyleSelection(null, null) },
            ) { w ->
                width = w
                host.restyleSelection(null, w.toDouble(), preview = true)
            }
            Text(
                stringResource(R.string.change_style_hint),
                color = palette.textDim.toComposeColor(),
                fontSize = 11.sp,
            )
        }
    }
}

/** Crop mode's floating bar, above the image being cropped: Cancel and Apply. */
@Composable
fun CropMenu(editor: Editor) {
    val rect = editor.cropMenu ?: return
    val palette = LocalPalette.current
    val density = LocalDensity.current
    val barHeightPx = with(density) { 44.dp.toPx() }
    val barWidthPx = with(density) { 150.dp.toPx() }
    val gap = with(density) { 10.dp.toPx() }
    val centerX = ((rect.left + rect.right) / 2.0).toFloat()
    val xPx = (centerX - barWidthPx / 2f).coerceAtLeast(with(density) { 8.dp.toPx() })
    val yPx = if (rect.top.toFloat() - barHeightPx - gap > 0f) rect.top.toFloat() - barHeightPx - gap
    else rect.bottom.toFloat() + gap
    Row(
        modifier = Modifier
            .offset(with(density) { xPx.toDp() }, with(density) { yPx.toDp() })
            .clip(RoundedCornerShape(10.dp))
            .background(palette.menuBg.toComposeColor())
            .border(1.dp, palette.border.toComposeColor(), RoundedCornerShape(10.dp)),
    ) {
        ActionIcon(XnotesIcons.close, "Cancel crop") { editor.cancelCrop() }
        ActionIcon(XnotesIcons.check, "Apply crop") { editor.applyActiveCrop() }
    }
}

/**
 * The screenshot tool's floating action, shown above the frozen capture rectangle: a single
 * "Copy as image" button that renders the region and puts it on the system clipboard.
 */
@Composable
fun ScreenshotMenu(editor: Editor) {
    val rect = editor.screenshotMenu ?: return
    val palette = LocalPalette.current
    val density = LocalDensity.current

    val barHeightPx = with(density) { 44.dp.toPx() }
    val barWidthPx = with(density) { 170.dp.toPx() }
    val gap = with(density) { 10.dp.toPx() }
    val centerX = ((rect.left + rect.right) / 2.0).toFloat()
    val xPx = (centerX - barWidthPx / 2f).coerceAtLeast(with(density) { 8.dp.toPx() })
    val yPx = if (rect.top.toFloat() - barHeightPx - gap > 0f) {
        rect.top.toFloat() - barHeightPx - gap
    } else {
        rect.bottom.toFloat() + gap
    }
    Row(
        modifier = Modifier
            .offset(with(density) { xPx.toDp() }, with(density) { yPx.toDp() })
            .clip(MaterialTheme.shapes.medium)
            .background(palette.menuBg.toComposeColor())
            .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.medium)
            .clickable { editor.copyScreenshotAsImage() }
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            XnotesIcons.copy,
            contentDescription = stringResource(R.string.copy_as_image),
            tint = palette.text.toComposeColor(),
            modifier = Modifier.size(20.dp),
        )
        Text(
            stringResource(R.string.copy_as_image),
            color = palette.text.toComposeColor(),
            fontSize = 14.sp,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun ActionIcon(icon: ImageVector, desc: String, enabled: Boolean = true, active: Boolean = false, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val tint = when {
        !enabled -> palette.text.toComposeColor().copy(alpha = 0.35f)
        active -> palette.accent.toComposeColor()
        else -> palette.text.toComposeColor()
    }
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(46.dp)) {
        Icon(icon, contentDescription = desc, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/**
 * What the long-press menu needs from whichever editor is open. Same reasoning as
 * [SelectionMenuHost]: one menu, taken through an interface, rather than two that resemble each
 * other and drift.
 */
interface LongPressMenuHost {
    /** Where the press landed, or null when no menu is open. */
    val contextMenu: ContextMenuTarget?

    val hasClipboardItems: Boolean
    val clipboardHasImage: Boolean

    fun pasteItemsAt(content: com.xnotes.core.geometry.Pt)
    fun pasteClipboardImageAt(content: com.xnotes.core.geometry.Pt)
    fun dismissContextMenu()

    /** Release [item], so it can be selected again. */
    fun unlockItem(item: com.xnotes.core.model.CanvasItem)
}

/**
 * Long-press menu: paste copied items or an image from the system clipboard at the press point, or
 * insert an image there. A press that landed on a locked item offers only to release it, since
 * nothing else can be done with one and there is no other way back.
 */
@Composable
fun LongPressMenu(host: LongPressMenuHost, onInsertImageAt: (com.xnotes.core.geometry.Pt) -> Unit) {
    val target = host.contextMenu ?: return
    val density = LocalDensity.current
    val xDp = with(density) { target.viewportX.toFloat().toDp() }
    val yDp = with(density) { target.viewportY.toFloat().toDp() }

    Box(modifier = Modifier.offset(xDp, yDp).size(1.dp)) {
        DropdownMenu(expanded = true, onDismissRequest = { host.dismissContextMenu() }) {
            val locked = target.locked
            if (locked != null) {
                DropdownMenuItem(text = { Text(stringResource(R.string.unlock)) }, onClick = {
                    host.unlockItem(locked); host.dismissContextMenu()
                })
                return@DropdownMenu
            }
            if (host.hasClipboardItems) {
                DropdownMenuItem(text = { Text(stringResource(R.string.paste_here)) }, onClick = {
                    host.pasteItemsAt(target.content); host.dismissContextMenu()
                })
            }
            if (host.clipboardHasImage) {
                DropdownMenuItem(text = { Text(stringResource(R.string.paste_image)) }, onClick = {
                    host.pasteClipboardImageAt(target.content); host.dismissContextMenu()
                })
            }
            DropdownMenuItem(text = { Text(stringResource(R.string.insert_image_ellipsis)) }, onClick = {
                onInsertImageAt(target.content); host.dismissContextMenu()
            })
        }
    }
}

/**
 * The flow-editing action bar (long press with the text tool, after the word
 * selection lands): a thin icon row like [SelectionMenu], anchored above the
 * selection so the drag handles stay visible. Not a popup: it never steals
 * focus (the keyboard stays up) and any canvas touch quietly retires it. The
 * paste icon expands the explicit paste modes (no auto-detection anywhere).
 */
@Composable
fun FlowEditMenu(editor: Editor) {
    val rect = editor.flowContextMenu ?: return
    val palette = LocalPalette.current
    val density = LocalDensity.current
    val hasClip = editor.clipboardHasText()
    val hasSelection = editor.flowHasSelection
    var pasteOpen by remember { mutableStateOf(false) }

    val barHeightPx = with(density) { 48.dp.toPx() }
    val barWidthPx = with(density) { (4 * 46).dp.toPx() }
    val gap = with(density) { 10.dp.toPx() }
    // When pushed below the selection, also clear the teardrop handles hanging there.
    val handleClearance = with(density) { (2 * com.xnotes.canvas.FlowTextController.HANDLE_RADIUS_DP).dp.toPx() }
    val centerX = ((rect.left + rect.right) / 2.0).toFloat()
    val xPx = (centerX - barWidthPx / 2f).coerceAtLeast(with(density) { 8.dp.toPx() })
    val yPx = if (rect.top.toFloat() - barHeightPx - gap > 0f) {
        rect.top.toFloat() - barHeightPx - gap
    } else {
        rect.bottom.toFloat() + handleClearance + gap
    }

    Row(
        modifier = Modifier
            .offset(with(density) { xPx.toDp() }, with(density) { yPx.toDp() })
            .clip(MaterialTheme.shapes.medium)
            .background(palette.menuBg.toComposeColor())
            .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.medium),
    ) {
        ActionIcon(XnotesIcons.cut, stringResource(R.string.cut), enabled = hasSelection) {
            editor.flowCut(); editor.dismissFlowContextMenu()
        }
        ActionIcon(XnotesIcons.copy, stringResource(R.string.copy), enabled = hasSelection) {
            editor.flowCopy(); editor.dismissFlowContextMenu()
        }
        Box {
            ActionIcon(XnotesIcons.paste, stringResource(R.string.paste), enabled = hasClip) { pasteOpen = true }
            DropdownMenu(
                expanded = pasteOpen,
                onDismissRequest = { pasteOpen = false },
                properties = PopupProperties(focusable = false),
            ) {
                DropdownMenuItem(text = { Text(stringResource(R.string.paste)) }, onClick = {
                    pasteOpen = false; editor.pastePlainAtCaret(); editor.dismissFlowContextMenu()
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.paste_markdown)) }, onClick = {
                    pasteOpen = false; editor.pasteMarkdownAtCaret(); editor.dismissFlowContextMenu()
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.paste_code)) }, onClick = {
                    pasteOpen = false; editor.pasteAsCodeAtCaret(); editor.dismissFlowContextMenu()
                })
            }
        }
        ActionIcon(XnotesIcons.trash, stringResource(R.string.delete), enabled = hasSelection) {
            editor.flowDeleteSelection(); editor.dismissFlowContextMenu()
        }
    }
}

private const val TABLE_BAR_ICONS = 4

/**
 * Where the table bar sits in viewport px: centred over the top of the table's
 * first slice and kept on screen (it overlaps the table rather than leave the
 * top of the view). Null when the table is not laid out.
 */
private fun tableBarRect(editor: Editor, table: com.xnotes.core.text.FlowTable, density: androidx.compose.ui.unit.Density): androidx.compose.ui.geometry.Rect? {
    editor.tableChromeTick
    val (pi, frag) = editor.tableFrags(table).firstOrNull() ?: return null
    val t = editor.pageRectToViewport(pi, frag.rect) ?: return null
    val w = with(density) { (TABLE_BAR_ICONS * 46).dp.toPx() }
    val h = with(density) { 48.dp.toPx() }
    val margin = with(density) { 8.dp.toPx() }
    val gap = with(density) { 10.dp.toPx() }
    val x = (((t.left + t.right) / 2.0).toFloat() - w / 2f).coerceAtLeast(margin)
    val y = (t.top.toFloat() - h - gap).coerceAtLeast(margin)
    return androidx.compose.ui.geometry.Rect(x, y, x + w, y + h)
}

/**
 * The table's action bar: edit its structure, fit its columns (the magic wand),
 * restyle it, delete it. Opens the moment a long press holds the table itself
 * (a rule, padding, empty cell space), above the table whatever part was
 * pressed. Like the text bar it never takes focus; the next canvas touch
 * retires it.
 */
@Composable
fun FlowTableMenu(editor: Editor) {
    val table = editor.tableMenu ?: return
    if (editor.editingTable != null) return
    val palette = LocalPalette.current
    val density = LocalDensity.current
    val bar = tableBarRect(editor, table, density) ?: return
    Row(
        modifier = Modifier
            .offset(with(density) { bar.left.toDp() }, with(density) { bar.top.toDp() })
            .clip(MaterialTheme.shapes.medium)
            .background(palette.menuBg.toComposeColor())
            .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.medium),
    ) {
        ActionIcon(XnotesIcons.tableEdit, stringResource(R.string.edit_table)) { editor.startTableEdit(table) }
        ActionIcon(XnotesIcons.magicWand, stringResource(R.string.fit_columns)) { editor.tableAutoFit(table) }
        ActionIcon(Icons.Outlined.TableChart, stringResource(R.string.table_style)) { editor.openTableStyle(table) }
        ActionIcon(Icons.Outlined.GridOff, stringResource(R.string.delete_table)) { editor.tableDelete(table) }
    }
}
