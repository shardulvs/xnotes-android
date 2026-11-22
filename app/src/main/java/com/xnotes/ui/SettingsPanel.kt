package com.xnotes.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.nested
import com.xnotes.ui.theme.toComposeColor

/*
 * The pieces of the settings panel both bars' gear opens: a front page of the controls reached for
 * while writing, and rows that slide into pages of their own. The note's and the canvas's panels
 * are built from these, so the two read as one design.
 */

/** Wide enough for four template tiles and a peek of the fifth. */
internal val PANEL_WIDTH = 380.dp

private const val PAGE_MS = 220

/**
 * The panel's three layers: the sheet, a card on it, and a button raised off a card. Dark sits each
 * a step under the chrome's own lifts, which glare once they cover a whole panel; light and OLED
 * (whose menu is already its darkest tone) keep the chrome's.
 */
internal class PanelColors(val sheet: Color, val card: Color, val raised: Color)

@Composable
internal fun panelColors(): PanelColors {
    val p = LocalPalette.current
    val deep = p.isDark && p.menuBg != p.bg
    return if (deep) PanelColors(p.bg.toComposeColor(), p.menuBg.toComposeColor(), p.surface.toComposeColor())
    else PanelColors(p.menuBg.toComposeColor(), p.surface.toComposeColor(), p.surfaceHi.toComposeColor())
}

/** The gear, pinned past the bar's scrolling tools, and the panel [content] fills while [open]. */
@Composable
internal fun SettingsButton(open: Boolean, onOpen: (Boolean) -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Rule()
    Box {
        ToolbarIcon(XnotesIcons.settings, stringResource(R.string.settings), active = open) { onOpen(!open) }
        DropdownMenu(expanded = open, onDismissRequest = { onOpen(false) }, shape = MaterialTheme.shapes.large, containerColor = panelColors().sheet) {
            // Sized text keeps material's 24sp line otherwise, which the panel's fixed rows and tiles clip.
            ProvideTextStyle(LocalTextStyle.current.copy(lineHeight = 1.3.em)) {
                Column(Modifier.width(PANEL_WIDTH).padding(horizontal = 12.dp, vertical = 4.dp), content = content)
            }
        }
    }
}

/** Shows [page]; a deeper one (by [depth]) slides in from the end and Back slides it out again. */
@Composable
internal fun <P> PanelPages(page: P, depth: (P) -> Int, content: @Composable ColumnScope.(P) -> Unit) {
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            val dir = if (depth(targetState) >= depth(initialState)) 1 else -1
            (slideInHorizontally(tween(PAGE_MS)) { w -> dir * w / 6 } + fadeIn(tween(PAGE_MS))) togetherWith
                (slideOutHorizontally(tween(PAGE_MS)) { w -> -dir * w / 6 } + fadeOut(tween(PAGE_MS / 2))) using
                SizeTransform { _, _ -> tween(PAGE_MS) }
        },
        label = "settingsPage",
    ) { p -> Column(Modifier.fillMaxWidth()) { content(p) } }
}

/** A raised block of related controls on the panel. */
@Composable
internal fun PanelCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 14.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(panelColors().card)
            .padding(padding),
        content = content,
    )
}

/** A sub-page's title row: Back, the [title], and an optional Reset. */
@Composable
internal fun PanelHeader(title: String, onBack: () -> Unit, onReset: (() -> Unit)? = null) {
    val palette = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundButton(XnotesIcons.prev, stringResource(R.string.back), 40.dp, onClick = onBack)
        Text(
            title,
            color = palette.text.toComposeColor(),
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
        )
        if (onReset != null) PanelChip(stringResource(R.string.reset), onClick = onReset)
    }
}

/** A small heading over a group, with room for a value or a link at its [end]. */
@Composable
internal fun PanelCaption(text: String, modifier: Modifier = Modifier, end: (@Composable RowScope.() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().height(20.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            color = LocalPalette.current.textDim.toComposeColor(),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        end?.invoke(this)
    }
    Spacer(Modifier.height(6.dp))
}

/** The accent figure at a caption's end, such as an edge's percentage. */
@Composable
internal fun CaptionValue(text: String) {
    Text(text, color = LocalPalette.current.accent.toComposeColor(), fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
}

/** A quiet hint at a caption's end. */
@Composable
internal fun CaptionHint(text: String) {
    Text(text, color = LocalPalette.current.textDim.toComposeColor().copy(alpha = 0.8f), fontSize = 12.sp, maxLines = 1)
}

/** A circular button on a card, such as the page arrows. */
@Composable
internal fun RoundButton(icon: ImageVector, label: String, size: Dp = 44.dp, enabled: Boolean = true, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Tooltip(label) {
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(panelColors().raised)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, tint = (if (enabled) palette.text else palette.disabled).toComposeColor(), modifier = Modifier.size(20.dp))
        }
    }
}

/** A choice or action chip; selected it wears the selection container, as the popups' chips do. */
@Composable
internal fun PanelChip(text: String, selected: Boolean = false, icon: ImageVector? = null, enabled: Boolean = true, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val fg = when {
        !enabled -> palette.disabled
        selected -> palette.selectionForeground
        else -> palette.text
    }.toComposeColor()
    val shape = MaterialTheme.shapes.small
    Row(
        Modifier
            .height(30.dp)
            .clip(shape)
            .background(if (selected) palette.selectionBackground.toComposeColor() else Color.Transparent)
            .border(1.dp, (if (selected) palette.accent else palette.border).toComposeColor(), shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = fg, modifier = Modifier.size(15.dp))
        Text(text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** A row of [count] equal segments, the [selected] one filled; [segment] draws each. [sunken] on a card, not on the sheet. */
@Composable
internal fun PanelSegmented(
    count: Int,
    selected: Int,
    onSelect: (Int) -> Unit,
    height: Dp = 34.dp,
    sunken: Boolean = true,
    segment: @Composable (index: Int, on: Boolean) -> Unit,
) {
    val palette = LocalPalette.current
    val outer = if (sunken) MaterialTheme.shapes.small else MaterialTheme.shapes.medium
    val inner = outer.nested(3.dp, LocalDensity.current)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(outer)
            .then(
                if (sunken) Modifier.background(palette.bg.toComposeColor()).border(1.dp, palette.border.toComposeColor(), outer)
                else Modifier.background(panelColors().card),
            )
            .padding(3.dp),
    ) {
        for (i in 0 until count) {
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(height)
                    .clip(inner)
                    .background(if (on) palette.selectionBackground.toComposeColor() else Color.Transparent)
                    .clickable(role = Role.Tab) { onSelect(i) }
                    .padding(horizontal = 6.dp),
                contentAlignment = Alignment.Center,
            ) { segment(i, on) }
        }
    }
}

/** [PanelSegmented] with a word on each segment. */
@Composable
internal fun PanelSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, height: Dp = 34.dp, sunken: Boolean = true) {
    val palette = LocalPalette.current
    PanelSegmented(options.size, selected, onSelect, height, sunken) { i, on ->
        Text(
            options[i],
            color = (if (on) palette.selectionForeground else palette.textDim).toComposeColor(),
            fontSize = 13.sp,
            fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One switch on a card, the whole row its target. */
@Composable
internal fun PanelSwitch(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) }
            .padding(start = 16.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = (if (enabled) palette.text else palette.disabled).toComposeColor(), fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** The hairline between rows of a card, inset from its start. */
@Composable
internal fun CardDivider(start: Dp = 16.dp) {
    Box(Modifier.fillMaxWidth().padding(start = start).height(1.dp).background(LocalPalette.current.border.toComposeColor()))
}

/** A labelled checkbox, its box pulled back so it lines up with the captions above it. */
@Composable
internal fun PanelCheck(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().offset(x = (-10).dp).clickable(role = Role.Checkbox) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label, color = LocalPalette.current.text.toComposeColor(), fontSize = 13.sp)
    }
}

/** A labelled slider with its value at the caption's end. */
@Composable
internal fun PanelSlider(
    label: String,
    value: String,
    position: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean = true,
    onChangeFinished: (() -> Unit)? = null,
    onChange: (Float) -> Unit,
) {
    PanelCaption(label) { CaptionValue(value) }
    Slider(
        value = position.coerceIn(range.start, range.endInclusive),
        onValueChange = onChange,
        onValueChangeFinished = onChangeFinished,
        valueRange = range,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(32.dp),
    )
}

/** A front-page row that opens a page: a small [preview], its [title] and what it is set to now. */
@Composable
internal fun NavRow(title: String, subtitle: String, onClick: () -> Unit, preview: @Composable () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 14.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(32.dp, 42.dp), contentAlignment = Alignment.Center) { preview() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = palette.text.toComposeColor(), fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(subtitle, color = palette.textDim.toComposeColor(), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(XnotesIcons.next, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(18.dp))
    }
}

/** A square-ish toggle on the front page: an accent [icon], its [name] and its [state] under it. */
@Composable
internal fun QuickTile(
    icon: ImageVector,
    name: String,
    state: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    val fg = (if (active) palette.selectionForeground else palette.text).toComposeColor()
    val iconTint = when {
        !enabled -> palette.disabled
        active -> palette.selectionForeground
        else -> palette.accent
    }.toComposeColor()
    Column(
        modifier
            .height(92.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(if (active) palette.selectionBackground.toComposeColor() else panelColors().card)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(start = 14.dp, top = 14.dp, end = 12.dp, bottom = 13.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Icon(icon, null, tint = iconTint, modifier = Modifier.size(22.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, color = if (enabled) fg else palette.disabled.toComposeColor(), fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(state, color = if (active) fg.copy(alpha = 0.8f) else palette.textDim.toComposeColor(), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The zoom stepper: out, the percentage, in. */
@Composable
internal fun ZoomStepper(percent: Int, enabled: Boolean, onOut: () -> Unit, onIn: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val shape = MaterialTheme.shapes.small
    val tint = (if (enabled) palette.text else palette.disabled).toComposeColor()
    Row(
        modifier
            .height(44.dp)
            .clip(shape)
            .background(palette.bg.toComposeColor())
            .border(1.dp, palette.border.toComposeColor(), shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val out = stringResource(R.string.zoom_out)
        Tooltip(out, shortcut = Keys.ZOOM_OUT) {
            Box(Modifier.width(54.dp).fillMaxHeight().clickable(enabled = enabled, role = Role.Button, onClick = onOut), contentAlignment = Alignment.Center) {
                Icon(XnotesIcons.minus, out, tint = tint, modifier = Modifier.size(20.dp))
            }
        }
        Box(Modifier.width(1.dp).height(26.dp).background(palette.border.toComposeColor()))
        Text(
            "$percent%",
            color = palette.text.toComposeColor(),
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        Box(Modifier.width(1.dp).height(26.dp).background(palette.border.toComposeColor()))
        val inLabel = stringResource(R.string.zoom_in)
        Tooltip(inLabel, shortcut = Keys.ZOOM_IN) {
            Box(Modifier.width(54.dp).fillMaxHeight().clickable(enabled = enabled, role = Role.Button, onClick = onIn), contentAlignment = Alignment.Center) {
                Icon(XnotesIcons.plus, inLabel, tint = tint, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** The zoom lock beside the stepper, filled while locked. */
@Composable
internal fun ZoomLockButton(locked: Boolean, onToggle: () -> Unit) {
    val palette = LocalPalette.current
    val shape = MaterialTheme.shapes.small
    val label = stringResource(if (locked) R.string.unlock_zoom else R.string.lock_zoom)
    Tooltip(label) {
        Box(
            Modifier
                .size(44.dp)
                .clip(shape)
                .background((if (locked) palette.selectionBackground else palette.bg).toComposeColor())
                .border(1.dp, (if (locked) palette.accent else palette.border).toComposeColor(), shape)
                .clickable(role = Role.Switch, onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (locked) XnotesIcons.lock else XnotesIcons.unlock,
                label,
                tint = (if (locked) palette.selectionForeground else palette.text).toComposeColor(),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** A fit button: the [icon] of what it fits over its [label]. */
@Composable
internal fun FitTile(label: String, icon: ImageVector, modifier: Modifier = Modifier, enabled: Boolean = true, shortcut: String? = null, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Tooltip(label, modifier, shortcut = shortcut) {
        Column(
            Modifier
                .height(62.dp)
                .clip(MaterialTheme.shapes.small)
                .background(panelColors().raised)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, null, tint = (if (enabled) palette.accent else palette.disabled).toComposeColor(), modifier = Modifier.size(28.dp, 24.dp))
            Spacer(Modifier.height(6.dp))
            Text(label, color = (if (enabled) palette.text else palette.disabled).toComposeColor(), fontSize = 12.sp, maxLines = 1)
        }
    }
}
