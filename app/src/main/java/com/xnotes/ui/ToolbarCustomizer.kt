package com.xnotes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.core.tools.ToolbarItem
import com.xnotes.core.tools.ToolbarLayout
import com.xnotes.core.tools.ToolbarSection
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor

/**
 * The toolbar customizer, rendered inline inside Preferences. Each section is a bordered card whose
 * top strip (the empty space beside the delete X) is the grab area for dragging the whole section;
 * the cards sit side by side in a wrapping [FlowRow], and within a card the tool chips flow like the
 * real bar. Chips tap to show/hide and long-press to drag (within or across cards). The drag ghost
 * and the edge auto-scroll live in the caller (PreferencesPane) so the ghost can float above the
 * scroll; this body only paints, captures bounds, and forwards gesture events up.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolbarCustomizerBody(
    layout: ToolbarLayout,
    dragItem: ToolbarItem?,
    dropTarget: Pair<Int, Int>?,
    dragSection: Int?,
    sectionDropTarget: Int?,
    chipBounds: MutableMap<ToolbarItem, Rect>,
    sectionBounds: MutableMap<Int, Rect>,
    sectionGrabBounds: MutableMap<Int, Rect>,
    onToggle: (Int, Int) -> Unit,
    onDelete: (Int) -> Unit,
    onAdd: () -> Unit,
    onDragStart: (ToolbarItem, Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDrop: () -> Unit,
    onCancel: () -> Unit,
    onSectionDragStart: (Int, Offset) -> Unit,
    onSectionDrag: (Offset) -> Unit,
    onSectionDrop: () -> Unit,
    onSectionCancel: () -> Unit,
) {
    val palette = LocalPalette.current
    val dragging = dragItem != null
    val draggingSection = dragSection != null
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        layout.sections.forEachIndexed { sec, section ->
            if (draggingSection && sectionDropTarget == sec) SectionCaret()
            SectionCard(
                canDelete = layout.sections.size > 1,
                targeted = dragging && dropTarget?.first == sec,
                dimmed = dragSection == sec,
                onDelete = { onDelete(sec) },
                onBounds = { sectionBounds[sec] = it },
                onGrabBounds = { sectionGrabBounds[sec] = it },
                onGrabDragStart = { onSectionDragStart(sec, it) },
                onGrabDrag = onSectionDrag,
                onGrabDrop = onSectionDrop,
                onGrabCancel = onSectionCancel,
            ) {
                if (section.entries.isEmpty()) {
                    Text(
                        stringResource(R.string.drop_here),
                        color = (if (dragging && dropTarget == sec to 0) palette.accent else palette.textDim).toComposeColor(),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        section.entries.forEachIndexed { idx, entry ->
                            if (dragging && dropTarget == sec to idx) InsertionCaret()
                            ChipCell(
                                item = entry.item,
                                visible = entry.visible,
                                dragging = dragItem == entry.item,
                                onTap = { onToggle(sec, idx) },
                                onDragStart = { local -> onDragStart(entry.item, local) },
                                onDrag = onDrag,
                                onDrop = onDrop,
                                onCancel = onCancel,
                                onBounds = { chipBounds[entry.item] = it },
                            )
                        }
                        if (dragging && dropTarget == sec to section.entries.size) InsertionCaret()
                    }
                }
            }
        }
        if (draggingSection && sectionDropTarget == layout.sections.size) SectionCaret()
        AddSectionChip(onClick = onAdd)
    }
}

/**
 * Resolve the drop slot (sectionIndex, entryIndex) for a finger position in root coordinates. The
 * section is the card the finger is inside, else the nearest card. Within it, the first chip the
 * finger sits "before" (an earlier row, or left of its centre on the same row) wins; an empty card
 * takes slot 0. Returns null only when no card has been measured yet.
 */
internal fun toolbarDropTarget(
    layout: ToolbarLayout,
    finger: Offset,
    chipBounds: Map<ToolbarItem, Rect>,
    sectionBounds: Map<Int, Rect>,
): Pair<Int, Int>? {
    var sec = layout.sections.indices.firstOrNull { sectionBounds[it]?.contains(finger) == true } ?: -1
    if (sec < 0) {
        var best = Float.MAX_VALUE
        for (i in layout.sections.indices) {
            val r = sectionBounds[i] ?: continue
            val dx = maxOf(r.left - finger.x, 0f, finger.x - r.right)
            val dy = maxOf(r.top - finger.y, 0f, finger.y - r.bottom)
            val d = dx * dx + dy * dy
            if (d < best) { best = d; sec = i }
        }
    }
    if (sec < 0) return null
    val entries = layout.sections[sec].entries
    if (entries.isEmpty()) return sec to 0
    fun before(r: Rect): Boolean = finger.y < r.top || (finger.y <= r.bottom && finger.x < r.center.x)
    for (idx in entries.indices) {
        val r = chipBounds[entries[idx].item] ?: continue
        if (before(r)) return sec to idx
    }
    return sec to entries.size
}

/** Resolve the section insertion index (0..size) for a finger position in root coordinates. */
internal fun toolbarSectionDropTarget(
    layout: ToolbarLayout,
    finger: Offset,
    sectionBounds: Map<Int, Rect>,
): Int {
    fun before(r: Rect): Boolean = finger.y < r.top || (finger.y <= r.bottom && finger.x < r.center.x)
    for (sec in layout.sections.indices) {
        val r = sectionBounds[sec] ?: continue
        if (before(r)) return sec
    }
    return layout.sections.size
}

/**
 * A bordered section box: a header strip (grab area for the section drag) with the delete X at its
 * right, then the tool chips. The header is sized to the chips' width by [SectionCardScaffold] so
 * the strip beside the X fills the whole top edge without forcing the card to the full pane width.
 */
@Composable
private fun SectionCard(
    canDelete: Boolean,
    targeted: Boolean,
    dimmed: Boolean,
    onDelete: () -> Unit,
    onBounds: (Rect) -> Unit,
    onGrabBounds: (Rect) -> Unit,
    onGrabDragStart: (Offset) -> Unit,
    onGrabDrag: (Offset) -> Unit,
    onGrabDrop: () -> Unit,
    onGrabCancel: () -> Unit,
    content: @Composable () -> Unit,
) {
    val palette = LocalPalette.current
    SectionCardScaffold(
        modifier = Modifier
            .alpha(if (dimmed) 0.35f else 1f)
            .onGloballyPositioned { onBounds(it.boundsInRoot()) }
            .clip(MaterialTheme.shapes.small)
            .border(1.dp, (if (targeted) palette.accent else palette.border).toComposeColor(), MaterialTheme.shapes.small)
            .padding(start = 6.dp, top = 2.dp, end = 2.dp, bottom = 6.dp),
        header = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SectionDragStrip(
                    // Explicit height: the card is measured with an unbounded max height, so
                    // fillMaxHeight would collapse the grab strip to 0px and nothing could be pressed.
                    modifier = Modifier.weight(1f).height(34.dp),
                    onBounds = onGrabBounds,
                    onDragStart = onGrabDragStart,
                    onDrag = onGrabDrag,
                    onDrop = onGrabDrop,
                    onCancel = onGrabCancel,
                )
                IconButton(onClick = onDelete, enabled = canDelete, modifier = Modifier.size(26.dp)) {
                    Icon(
                        XnotesIcons.close, stringResource(R.string.delete_section),
                        tint = (if (canDelete) palette.textDim else palette.border).toComposeColor(),
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        },
        body = content,
    )
}

/** Stacks [header] above [body] and sizes the header to the body's measured width. */
@Composable
private fun SectionCardScaffold(
    modifier: Modifier,
    header: @Composable () -> Unit,
    body: @Composable () -> Unit,
) {
    Layout(contents = listOf(body, header), modifier = modifier) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val bodyPlaceable = measurables[0].first().measure(loose)
        val width = bodyPlaceable.width.coerceIn(constraints.minWidth, constraints.maxWidth)
        val headerPlaceable = measurables[1].first().measure(loose.copy(minWidth = width, maxWidth = width))
        layout(width, headerPlaceable.height + bodyPlaceable.height) {
            headerPlaceable.place(0, 0)
            bodyPlaceable.place(0, headerPlaceable.height)
        }
    }
}

/** The transparent top strip beside the X; long-press and drag it to reorder the whole section. */
@Composable
private fun SectionDragStrip(
    modifier: Modifier,
    onBounds: (Rect) -> Unit,
    onDragStart: (Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDrop: () -> Unit,
    onCancel: () -> Unit,
) {
    val dStart by rememberUpdatedState(onDragStart)
    val dMove by rememberUpdatedState(onDrag)
    val dEnd by rememberUpdatedState(onDrop)
    val dCancel by rememberUpdatedState(onCancel)
    Box(
        modifier
            .onGloballyPositioned { onBounds(it.boundsInRoot()) }
            .pointerInput(Unit) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { dStart(it) },
                    onDrag = { _, delta -> dMove(delta) },
                    onDragEnd = { dEnd() },
                    onDragCancel = { dCancel() },
                )
            },
    )
}

@Composable
private fun ChipCell(
    item: ToolbarItem,
    visible: Boolean,
    dragging: Boolean,
    onTap: () -> Unit,
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
    ChipFace(
        item = item,
        visible = visible,
        modifier = Modifier
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
            },
    )
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
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(itemIcon(item), null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(stringResource(item.labelRes), color = palette.text.toComposeColor(), fontSize = 12.sp)
    }
}

/** The floating chip that tracks the finger during a chip drag (drawn at the pane root). */
@Composable
fun ToolbarDragGhost(item: ToolbarItem) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .alpha(0.9f)
            .clip(MaterialTheme.shapes.small)
            .background(palette.surface.toComposeColor())
            .border(1.dp, palette.accent.toComposeColor(), MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(itemIcon(item), null, tint = palette.accent.toComposeColor(), modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(stringResource(item.labelRes), color = palette.accent.toComposeColor(), fontSize = 12.sp)
    }
}

/**
 * A faithful floating copy of a whole section during a section drag, pinned to the dragged card's
 * width ([widthPx], root pixels) so its chips wrap exactly like the card and the shape never changes.
 */
@OptIn(ExperimentalLayoutApi::class)
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
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                section.entries.forEach { e -> ChipFace(e.item, e.visible) }
            }
        }
    }
}

@Composable
private fun AddSectionChip(onClick: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .clip(MaterialTheme.shapes.small)
            .border(1.dp, palette.accent.toComposeColor(), MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(XnotesIcons.plus, stringResource(R.string.add_section), tint = palette.accent.toComposeColor(), modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.add_section), color = palette.accent.toComposeColor(), fontSize = 12.sp)
    }
}

@Composable
private fun InsertionCaret() {
    Box(Modifier.width(2.dp).height(28.dp).background(LocalPalette.current.accent.toComposeColor()))
}

/** Taller caret marking where a dragged section will land between cards. */
@Composable
private fun SectionCaret() {
    Box(Modifier.width(3.dp).height(56.dp).clip(RoundedCornerShape(2.dp)).background(LocalPalette.current.accent.toComposeColor()))
}

@Composable
private fun itemIcon(item: ToolbarItem): ImageVector = when (item) {
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
    ToolbarItem.SCREENSHOT -> XnotesIcons.scissors
    ToolbarItem.WAND -> XnotesIcons.magicWand
    ToolbarItem.SHAPE -> XnotesIcons.shape
    ToolbarItem.RULER -> XnotesIcons.ruler
    ToolbarItem.TEXT -> XnotesIcons.text
    ToolbarItem.TEXT_BOX -> XnotesIcons.textBox
    ToolbarItem.IMAGE -> XnotesIcons.image
    ToolbarItem.TABLE -> XnotesIcons.table
    ToolbarItem.UNDO -> XnotesIcons.undo
    ToolbarItem.REDO -> XnotesIcons.redo
    ToolbarItem.PAGE_NAV -> XnotesIcons.prev
    ToolbarItem.SEARCH -> XnotesIcons.search
    ToolbarItem.STYLES -> XnotesIcons.sliders
    ToolbarItem.MARGINS -> XnotesIcons.margins
    ToolbarItem.VIEW -> XnotesIcons.view
    ToolbarItem.ZOOM -> XnotesIcons.zoomIn
    ToolbarItem.FIT -> XnotesIcons.fit
    ToolbarItem.ZOOM_LOCK -> XnotesIcons.lock
    ToolbarItem.FULLSCREEN -> XnotesIcons.fullscreen
    ToolbarItem.COLORS -> XnotesIcons.more
    ToolbarItem.WAYPOINTS -> XnotesIcons.bookmark
    ToolbarItem.MINIMAP -> XnotesIcons.map
}
