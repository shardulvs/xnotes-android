package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.core.tools.ToolbarItem
import com.xnotes.core.tools.ToolbarSection
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

@Composable
internal fun ChipCell(
    item: ToolbarItem,
    visible: Boolean,
    dragging: Boolean,
    selected: Boolean,
    layerStyle: Boolean = false,
    onTap: () -> Unit,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDrop: () -> Unit,
    onCancel: () -> Unit,
    onBounds: (Rect) -> Unit,
) {
    // The pointerInput blocks key on the stable item, so they capture the first-composition
    // lambdas; rememberUpdatedState keeps the gestures calling the latest callbacks instead.
    val tap by rememberUpdatedState(onTap)
    val dStart by rememberUpdatedState(onDragStart)
    val dMove by rememberUpdatedState(onDrag)
    val dEnd by rememberUpdatedState(onDrop)
    val dCancel by rememberUpdatedState(onCancel)
    val faceModifier = Modifier
            .alpha(if (dragging) 0.15f else 1f)
            .onGloballyPositioned { onBounds(it.boundsInRoot()) }
            .pointerInput(item) { detectTapGestures(onTap = { tap() }) }
            .pointerInput(item) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { dStart(it) },
                    onDrag = { _, delta -> dMove(delta) },
                    onDragEnd = { dEnd() },
                    onDragCancel = { dCancel() },
                )
            }
    if (layerStyle) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LayerFace(item, visible, faceModifier.weight(1f), selected = selected, onToggle = onToggle)
            Box(Modifier.size(30.dp).clip(MaterialTheme.shapes.small).clickable(onClick = onRemove),
                contentAlignment = Alignment.Center) {
                Icon(XnotesIcons.close, stringResource(R.string.remove), modifier = Modifier.size(14.dp))
            }
        }
    } else ChipFace(item, visible, faceModifier)
}

/** The visual of a tool chip (icon + label, dimmed when hidden); shared by the bar editor and the
 *  section drag ghost so a lifted section looks exactly like its card. */
@Composable
private fun ChipFace(item: ToolbarItem, visible: Boolean, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Row(
        modifier
            .alpha(if (!visible) 0.4f else 1f)
            .clip(MaterialTheme.shapes.small)
            .background(palette.surface.toComposeColor())
            .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(itemIcon(item), null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(item.labelRes), color = palette.text.toComposeColor(), style = MaterialTheme.typography.labelMedium)
    }
}

/** Compact layer row. Tap toggles visibility; hold the grip/row to reorder. */
@Composable
private fun LayerFace(item: ToolbarItem, visible: Boolean, modifier: Modifier, selected: Boolean = false, onToggle: (() -> Unit)? = null) {
    val toolbarStrings = androidx.compose.ui.platform.LocalContext.current
    val palette = LocalPalette.current
    Row(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(palette.surface.toComposeColor())
            .border(if (selected) 2.dp else 1.dp,
                (if (selected) palette.accent else palette.border).toComposeColor(), MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(XnotesIcons.menu, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(14.dp))
        Icon(itemIcon(item), null, tint = (if (visible) palette.text else palette.textDim).toComposeColor(), modifier = Modifier.size(16.dp))
        Text(
            stringResource(item.labelRes),
            color = (if (visible) palette.text else palette.textDim).toComposeColor(),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (!visible) {
            Text("Oculto", color = palette.textDim.toComposeColor(),
                style = MaterialTheme.typography.labelSmall)
        }
        if (onToggle != null) {
            Box(Modifier.size(30.dp).clip(MaterialTheme.shapes.small).clickable(onClick = onToggle),
                contentAlignment = Alignment.Center) {
                Icon(XnotesIcons.view, if (visible) toolbarStrings.getString(R.string.toolbar_config_hide_tool) else toolbarStrings.getString(R.string.toolbar_config_show_tool),
                    tint = (if (visible) palette.accent else palette.textDim).toComposeColor(),
                    modifier = Modifier.size(16.dp))
            }
        } else {
            Icon(XnotesIcons.view, null, tint = (if (visible) palette.accent else palette.textDim).toComposeColor(), modifier = Modifier.size(16.dp))
        }
    }
}

/** A lifted copy of the actual editor row, kept at its measured width and grab point.
 * The visual reuses LayerFace so new row actions/icons stay consistent automatically. */
@Composable
fun ToolbarDragGhost(item: ToolbarItem, visible: Boolean, widthPx: Float) {
    val palette = LocalPalette.current
    val width = with(LocalDensity.current) { widthPx.toDp() }
    Box(
        Modifier
            .width(width)
            .alpha(0.94f)
            .clip(MaterialTheme.shapes.small),
    ) {
        LayerFace(item, visible, Modifier.fillMaxWidth())
        Box(
            Modifier.matchParentSize()
                .border(2.dp, palette.accent.toComposeColor(), MaterialTheme.shapes.small),
        )
    }
}

/**
 * A faithful floating copy of a whole section during a section drag, pinned to the dragged card's
 * width ([widthPx], root pixels) so its chips wrap exactly like the card and the shape never changes.
 */
@Composable
fun SectionCardGhost(section: ToolbarSection, widthPx: Float) {
    val palette = LocalPalette.current
    val width = with(LocalDensity.current) { widthPx.toDp() }
    Column(
        Modifier
            .width(width)
            .alpha(0.95f)
            .clip(MaterialTheme.shapes.small)
            .background(palette.menuBg.toComposeColor())
            .border(1.dp, palette.accent.toComposeColor(), MaterialTheme.shapes.small)
            .padding(start = 6.dp, top = 2.dp, end = 2.dp, bottom = 6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
                Icon(XnotesIcons.close, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(14.dp))
            }
        }
        if (section.entries.isEmpty()) {
            Text(
                stringResource(R.string.drop_here), color = palette.textDim.toComposeColor(), fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(section.lanes) { lane ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        section.entries.filter { it.lane == lane }.forEach { e ->
                            ChipFace(e.item, e.visible)
                        }
                    }
                }
            }
        }
    }
}

/** Add a missing tool (or another Colors / Spacer block) directly to this section's chosen line. */
@Composable
internal fun AddItemPicker(items: List<ToolbarItem>, onChoose: (ToolbarItem) -> Unit) {
    val palette = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    Box {
        Row(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                .clickable(enabled = items.isNotEmpty()) { query = ""; open = true }
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(XnotesIcons.plus, null, tint = palette.accent.toComposeColor(), modifier = Modifier.size(14.dp))
            Text(stringResource(R.string.toolbar_add_element), color = palette.accent.toComposeColor(), fontSize = 12.sp)
        }
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.width(300.dp),
        ) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.toolbar_search_elements)) },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
            Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                val filtered = items.filter { item ->
                    stringResource(item.labelRes).contains(query, ignoreCase = true) ||
                        item.id.contains(query, ignoreCase = true) ||
                        (item == ToolbarItem.COLORS && "colours".contains(query, ignoreCase = true))
                }
                if (filtered.isEmpty()) {
                    Text(stringResource(R.string.toolbar_no_elements), modifier = Modifier.padding(12.dp), fontSize = 12.sp)
                }
                filtered.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(stringResource(item.labelRes), fontSize = 13.sp) },
                        leadingIcon = { Icon(itemIcon(item), null, modifier = Modifier.size(18.dp)) },
                        onClick = { open = false; onChoose(item) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun itemIcon(item: ToolbarItem): ImageVector = when (item) {
    ToolbarItem.HOME -> XnotesIcons.home
    ToolbarItem.TITLE -> XnotesIcons.edit
    ToolbarItem.SIDEBAR -> XnotesIcons.sidebar
    ToolbarItem.PEN -> ImageVector.vectorResource(R.drawable.ic_stroke_regular)
    ToolbarItem.DASHED -> ImageVector.vectorResource(R.drawable.ic_stroke_dashed)
    ToolbarItem.CALLIGRAPHY -> ImageVector.vectorResource(R.drawable.ic_stroke_calligraphy)
    ToolbarItem.SPEED -> ImageVector.vectorResource(R.drawable.ic_stroke_speed)
    ToolbarItem.TAPER -> ImageVector.vectorResource(R.drawable.ic_stroke_taper)
    ToolbarItem.HIGHLIGHTER -> ImageVector.vectorResource(R.drawable.ic_stroke_highlighter)
    ToolbarItem.ERASER -> XnotesIcons.eraser
    ToolbarItem.PAN -> XnotesIcons.pan
    ToolbarItem.SELECT -> XnotesIcons.select
    ToolbarItem.LASSO -> XnotesIcons.lasso
    ToolbarItem.INSERT_SPACE -> XnotesIcons.insertSpace
    ToolbarItem.SCREENSHOT -> XnotesIcons.scissors
    ToolbarItem.WAND -> XnotesIcons.magicWand
    ToolbarItem.TAPE -> XnotesIcons.tape
    ToolbarItem.SHAPE -> XnotesIcons.shape
    ToolbarItem.RULER -> XnotesIcons.ruler
    ToolbarItem.TEXT -> XnotesIcons.text
    ToolbarItem.TEXT_BOX -> XnotesIcons.textBox
    ToolbarItem.MARKUP -> XnotesIcons.markup
    ToolbarItem.IMAGE -> XnotesIcons.paperclip
    ToolbarItem.UNDO -> XnotesIcons.undo
    ToolbarItem.REDO -> XnotesIcons.redo
    ToolbarItem.PAGE_NAV -> XnotesIcons.prev
    ToolbarItem.STYLES -> XnotesIcons.sliders
    ToolbarItem.MARGINS -> XnotesIcons.margins
    ToolbarItem.VIEW -> XnotesIcons.view
    ToolbarItem.ZOOM -> XnotesIcons.zoomIn
    ToolbarItem.FIT -> XnotesIcons.fit
    ToolbarItem.ZOOM_LOCK -> XnotesIcons.lock
    ToolbarItem.FULLSCREEN -> XnotesIcons.fullscreen
    ToolbarItem.COLORS -> XnotesIcons.more
    ToolbarItem.SPACER -> XnotesIcons.grip
    ToolbarItem.WAYPOINTS -> XnotesIcons.bookmark
    ToolbarItem.MINIMAP -> XnotesIcons.map
}
