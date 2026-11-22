package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupProperties
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.xnotes.R
import com.xnotes.core.model.Rgba
import com.xnotes.settings.SwatchRole
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlin.math.roundToInt

/*
 * The pieces of a tool's menu, the one a second tap on an armed tool opens. They are the settings
 * panel's sheet, cards and controls at a menu's width, so the two read as one design.
 */

internal val TOOL_MENU_WIDTH = 300.dp

/** A card's rows start and end this far in. */
private val ROW_START = 14.dp
private val ROW_END = 10.dp

/** One group of a card: a row, or a switch and the sliders it opens. */
internal typealias MenuGroup = @Composable ColumnScope.() -> Unit

/** A tool's menu: its [title], an optional [action] at the title's end, and the cards of [content]. */
@Composable
internal fun ToolMenu(
    title: String,
    onDismiss: () -> Unit,
    width: Dp = TOOL_MENU_WIDTH,
    focusable: Boolean = true,
    action: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = true,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = focusable),
        shape = MaterialTheme.shapes.large,
        containerColor = panelColors().sheet,
    ) {
        // Sized text keeps material's 24sp line otherwise, which the fixed rows clip.
        ProvideTextStyle(LocalTextStyle.current.copy(lineHeight = 1.3.em)) {
            Column(Modifier.width(width).padding(horizontal = 10.dp, vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Held in from the sheet's round corners, which a title or chip at the cards' edge would crowd.
                Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 4.dp, end = 4.dp).height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        title,
                        color = LocalPalette.current.text.toComposeColor(),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    action?.invoke(this)
                }
                content()
            }
        }
    }
}

/** A card of [groups], a hairline between each; nothing when there are none. */
@Composable
internal fun MenuCard(groups: List<MenuGroup>) {
    if (groups.isEmpty()) return
    PanelCard(padding = PaddingValues(0.dp)) {
        groups.forEachIndexed { i, group ->
            if (i > 0) CardDivider(ROW_START)
            group()
        }
    }
}

/** A switch across the card, the whole row its target. */
@Composable
internal fun MenuSwitch(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
            .padding(start = ROW_START, end = ROW_END),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = (if (enabled) palette.text else palette.disabled).toComposeColor(),
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** A [label] with its control at the row's end. */
@Composable
internal fun MenuRow(label: String, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(start = ROW_START, end = ROW_END),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = LocalPalette.current.text.toComposeColor(),
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(end = 8.dp),
        )
        content()
    }
}

/**
 * A slider under its [label] and value. One a switch opens is its [child] and sits close under it.
 * A stepped slider lands on its stops, so the value shown is the value given.
 */
@Composable
internal fun MenuSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    step: Float = 0f,
    child: Boolean = false,
    onChangeFinished: (() -> Unit)? = null,
    onChange: (Float) -> Unit,
) {
    val shown = if (step > 0f) snapTo(value, range, step) else value.coerceIn(range.start, range.endInclusive)
    val number = if (step % 1f != 0f) "%.1f".format(shown).trimEnd('0').trimEnd('.', ',') else "%.0f".format(shown)
    Column(Modifier.padding(start = 10.dp, end = 10.dp, top = if (child) 0.dp else 10.dp, bottom = 8.dp)) {
        PanelCaption(label) { CaptionValue(number) }
        Slider(
            value = shown,
            onValueChange = { onChange(if (step > 0f) snapTo(it, range, step) else it) },
            onValueChangeFinished = onChangeFinished,
            valueRange = range,
            steps = if (step > 0f) (((range.endInclusive - range.start) / step).roundToInt() - 1).coerceAtLeast(0) else 0,
            // Stops this fine would draw as a speckled track; the value already names each one.
            colors = if (step > 0f) NoTicks else SliderDefaults.colors(),
            modifier = Modifier.fillMaxWidth().height(32.dp),
        )
    }
}

/**
 * A slider whose [shown] value reads dim while it follows a default ([own] false); with its own
 * value it takes the accent and, given [onReset], a way back to the default.
 */
@Composable
internal fun ParamSlider(
    label: String,
    shown: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    own: Boolean,
    steps: Int = 0,
    child: Boolean = false,
    onReset: (() -> Unit)? = null,
    onChange: (Float) -> Unit,
) {
    val palette = LocalPalette.current
    Column(Modifier.padding(start = 10.dp, end = 10.dp, top = if (child) 0.dp else 8.dp, bottom = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().height(28.dp).padding(start = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                label,
                color = palette.textDim.toComposeColor(),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(shown, color = (if (own) palette.accent else palette.textDim).toComposeColor(), fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            if (own && onReset != null) {
                val tip = stringResource(R.string.use_default)
                Tooltip(tip) {
                    Box(
                        Modifier.size(28.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onReset),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(22.dp).background(panelColors().raised, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(XnotesIcons.restore, tip, tint = palette.text.toComposeColor(), modifier = Modifier.size(13.dp))
                        }
                    }
                }
            }
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            colors = if (steps > 0) NoTicks else SliderDefaults.colors(),
            modifier = Modifier.fillMaxWidth().height(32.dp),
        )
    }
}

/**
 * A colour on a card: [label], and Default beside the colour. Its picker offers the [role]'s
 * circles first, and the colour last picked joins the front of them.
 */
@Composable
internal fun ColourRow(label: String, value: Rgba?, suggested: Rgba, store: SwatchStore, role: SwatchRole, onChange: (Rgba?) -> Unit) {
    MenuRow(label) {
        ColorOrNone(stringResource(R.string.default_choice), value, suggested, onChange) { onDismiss, onPick ->
            var last by remember { mutableStateOf<Rgba?>(null) }
            ColorPickerPopup(
                initial = value ?: suggested,
                recents = store.swatches(role),
                onDismiss = {
                    last?.let { picked ->
                        val next = (listOf(picked) + store.swatches(role).filter { it != picked }).take(store.swatches(role).size)
                        next.forEachIndexed { i, c -> if (store.swatches(role).getOrNull(i) != c) store.setSwatch(role, i, c) }
                    }
                    onDismiss()
                },
                onPick = { last = it.copy(a = 255); onPick(it) },
            )
        }
    }
}

/** A segmented choice across the top of a card. */
@Composable
internal fun MenuSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Box(Modifier.padding(start = 10.dp, top = 10.dp, end = 10.dp)) { PanelSegmented(options, selected, onSelect) }
}

/** A segmented choice of [icons], each named by its tip. */
@Composable
internal fun IconSegmented(icons: List<ImageVector>, labels: List<String>, selected: Int, height: Dp = 36.dp, onSelect: (Int) -> Unit) {
    val palette = LocalPalette.current
    PanelSegmented(icons.size, selected, onSelect, height) { i, on ->
        Tooltip(labels[i], Modifier.fillMaxSize()) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    icons[i], labels[i],
                    tint = (if (on) palette.selectionForeground else palette.textDim).toComposeColor(),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** Minus, the [value], plus: a number stepped one at a time. */
@Composable
internal fun MenuStepper(
    value: String,
    less: String,
    more: String,
    modifier: Modifier = Modifier,
    canLess: Boolean = true,
    canMore: Boolean = true,
    onLess: () -> Unit,
    onMore: () -> Unit,
) {
    val palette = LocalPalette.current
    val shape = MaterialTheme.shapes.small
    Row(
        modifier
            .height(36.dp)
            .clip(shape)
            .background(palette.bg.toComposeColor())
            .border(1.dp, palette.border.toComposeColor(), shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepperEnd(XnotesIcons.minus, less, canLess, onLess)
        Text(
            value,
            color = palette.text.toComposeColor(),
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        StepperEnd(XnotesIcons.plus, more, canMore, onMore)
    }
}

@Composable
private fun StepperEnd(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Tooltip(label) {
        Box(
            Modifier.width(36.dp).fillMaxHeight().clickable(enabled = enabled, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, tint = (if (enabled) palette.textDim else palette.disabled).toComposeColor(), modifier = Modifier.size(16.dp))
        }
    }
}

/** An icon tile of a single-choice grid, such as the shapes; the chosen one is filled. */
@Composable
internal fun MenuTile(icon: ImageVector, label: String, selected: Boolean, size: Dp, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Tooltip(label) {
        Box(
            Modifier
                .size(size)
                .clip(MaterialTheme.shapes.small)
                .background(if (selected) palette.selectionBackground.toComposeColor() else Color.Transparent)
                .clickable(role = Role.RadioButton, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = (if (selected) palette.selectionForeground else palette.textDim).toComposeColor(),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** A button on the sheet under the cards, such as the tape's Reveal all. */
@Composable
internal fun MenuAction(label: String, icon: ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        modifier
            .height(44.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(panelColors().card)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        Icon(icon, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(16.dp))
        Text(label, color = palette.text.toComposeColor(), fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}
