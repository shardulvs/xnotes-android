package com.xnotes.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xnotes.R
import com.xnotes.core.model.Rgba
import com.xnotes.core.util.DocumentKind
import com.xnotes.core.util.ReaderKind
import com.xnotes.platform.DocMetaStore
import com.xnotes.settings.ExplorerSortKey
import com.xnotes.settings.ExplorerView
import com.xnotes.settings.ThumbShape
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import java.time.ZoneId

/** What tiles do when they're used: every callback an explorer tile reaches for, built once per folder tree. */
@Stable
internal class TileHost(
    val isSelected: (BrowseEntry) -> Boolean,
    val selecting: () -> Boolean,
    val isCut: (BrowseEntry) -> Boolean,
    val isPinned: (BrowseEntry) -> Boolean,
    val isDropTarget: (BrowseEntry) -> Boolean,
    val isPulsing: (BrowseEntry) -> Boolean,
    val onPulseDone: (BrowseEntry) -> Unit,
    val onClick: (BrowseEntry) -> Unit,
    val onLongClick: (BrowseEntry) -> Unit,
    val onPlaced: (BrowseEntry, LayoutCoordinates?) -> Unit,
    val menu: EntryActions,
)

/** The overflow menu's actions for one entry. */
@Stable
internal class EntryActions(
    val rename: (BrowseEntry) -> Unit,
    val copy: (BrowseEntry) -> Unit,
    val cut: (BrowseEntry) -> Unit,
    val moveTo: (BrowseEntry) -> Unit,
    val delete: (BrowseEntry) -> Unit,
    val color: (BrowseEntry, Rgba?) -> Unit,
    val nameColor: (Rgba) -> Unit,
    val togglePin: (BrowseEntry) -> Unit,
    val share: (BrowseEntry) -> Unit,
    val saveCopy: (BrowseEntry) -> Unit,
    val exportPdf: (BrowseEntry) -> Unit,
    val preview: (BrowseEntry) -> Unit,
    val convertToNote: (BrowseEntry) -> Unit,
    val importInto: (BrowseEntry) -> Unit,
)

/** Everything an explorer layout draws from: the view, what's in it, and how to describe each item. */
@Stable
internal class ExplorerBody(
    val editor: Editor,
    val view: ExplorerView,
    /** Folders drawn in their own row above the files; empty when they're mixed in or hidden. */
    val folders: List<BrowseEntry>,
    val groups: List<EntryGroup>,
    val metas: Map<String, DocMetaStore.Meta?>,
    /** Item counts for folders, by uri; empty when folder counts are off. */
    val counts: Map<String, Int>,
    val now: Long,
    val clock24: Boolean,
    val dateStyle: String,
    val showExtensions: Boolean,
    /** What the menus call deleting: "Move to trash" while Trash is on. */
    val deleteLabel: String,
    val host: TileHost,
    val words: ExplorerWords,
    /** A line to show under an item in place of its date, as Recent does with when it was opened. */
    val metaOverride: ((BrowseEntry) -> String?)? = null,
    /** The folder each item sits in, shown where items come from many folders. */
    val whereOf: ((BrowseEntry) -> String?)? = null,
) {
    val zone: ZoneId = ZoneId.systemDefault()

    fun label(e: BrowseEntry): String = if (e.isDir || showExtensions) e.name else DocumentKind.stripSuffix(e.name)

    fun meta(e: BrowseEntry): DocMetaStore.Meta? = metas[e.documentUri]

    fun kind(e: BrowseEntry): EntryKind = entryKind(e, meta(e))

    fun pages(e: BrowseEntry): Int = meta(e)?.pages ?: 0

    fun whenText(e: BrowseEntry, withTime: Boolean = view.showTime): String =
        formatWhen(words, if (view.sortKey == ExplorerSortKey.CREATED) e.created else e.modified, now, dateStyle, withTime, clock24, zone)

    /** The line under a tile's name: its date, and its size when tiles show sizes. */
    fun metaText(e: BrowseEntry): String {
        metaOverride?.invoke(e)?.let { return it }
        if (e.isDir) return counts[e.documentUri]?.let { itemsLabel(words, it) } ?: whenText(e)
        return listOfNotNull(whenText(e).ifEmpty { null }, formatSize(e.size).takeIf { view.showSize }).joinToString(" · ")
    }

    fun colorOf(e: BrowseEntry): Rgba? = e.color.takeIf { view.showColour }
}

internal fun entryKind(e: BrowseEntry, meta: DocMetaStore.Meta?): EntryKind = when {
    e.isDir -> EntryKind.FOLDER
    DocumentKind.ofName(e.name) == DocumentKind.CANVAS -> EntryKind.CANVAS
    ReaderKind.isReadable(e.name) -> EntryKind.FILE
    meta?.pdf == true -> EntryKind.PDF
    else -> EntryKind.NOTE
}

internal fun kindIcon(k: EntryKind): ImageVector = when (k) {
    EntryKind.FOLDER -> XnotesIcons.folder
    EntryKind.NOTE -> XnotesIcons.file
    EntryKind.PDF -> XnotesIcons.pdf
    EntryKind.CANVAS -> XnotesIcons.canvas
    EntryKind.FILE -> XnotesIcons.lock
}

@Composable
internal fun kindLabel(k: EntryKind): String = when (k) {
    EntryKind.FOLDER -> stringResource(R.string.folder)
    EntryKind.NOTE -> stringResource(R.string.kind_note)
    EntryKind.PDF -> stringResource(R.string.kind_pdf_note)
    EntryKind.CANVAS -> stringResource(R.string.kind_canvas)
    EntryKind.FILE -> stringResource(R.string.kind_read_only_file)
}

/** A document's first page: the top cropped to fill [shape]'s square, or the whole page fitted in. */
@Composable
internal fun EntryThumb(editor: Editor, entry: BrowseEntry, shape: ThumbShape, modifier: Modifier = Modifier) {
    val whole = shape == ThumbShape.PAGE
    ThumbImage(rememberThumb(editor, entry, whole), whole, modifier, ReaderKind.ofName(entry.name)?.label)
}

/** The top of [entry]'s first page as a square, or with [whole] the page entire; seeded from memory, null until loaded. */
@Composable
internal fun rememberThumb(editor: Editor, entry: BrowseEntry, whole: Boolean): ImageBitmap? = key(editor, editor.thumbnailVersion) {
    val thumb by produceState<ImageBitmap?>(
        if (whole) editor.cachedPageThumb(entry.documentUri) else editor.cachedNoteTile(entry.documentUri),
        entry.documentUri, entry.modified, whole,
    ) {
        value = if (whole) editor.pageThumbnail(entry.documentUri, entry.name) else editor.tileThumbnail(entry.documentUri, entry.name)
    }
    thumb
}

/** Width over height of an A4 page upright, and of the landscape frame a canvas is drawn in. */
internal const val A4_RATIO = 100f / 141f
internal const val CANVAS_RATIO = 141f / 100f

/** A page's width over height from its rendered thumbnail, or [fallback] until that has loaded. */
internal fun pageRatio(img: ImageBitmap?, fallback: Float): Float = img?.let { it.width.toFloat() / it.height } ?: fallback

/** The largest size with a page's shape ([ratio], width over height) that fits within [maxW] by [maxH]. */
internal fun pageFit(ratio: Float, maxW: Dp, maxH: Dp): DpSize =
    if (maxW / maxH > ratio) DpSize(maxH * ratio, maxH) else DpSize(maxW, maxW / ratio)

/** A thumbnail on the page colour: cropped to its top, or with [whole] fitted in entire; a file icon (or [placeholder] text) until it loads. */
@Composable
internal fun ThumbImage(img: ImageBitmap?, whole: Boolean, modifier: Modifier = Modifier, placeholder: String? = null) {
    val palette = LocalPalette.current
    Box(modifier.background(palette.paper.toComposeColor())) {
        if (img != null) {
            Image(
                img, null,
                contentScale = if (whole) ContentScale.Fit else ContentScale.Crop,
                alignment = if (whole) Alignment.Center else Alignment.TopCenter,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (placeholder != null) {
            Text(placeholder, color = palette.textDim.toComposeColor(), fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.align(Alignment.Center))
        } else {
            Icon(XnotesIcons.file, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(28.dp).align(Alignment.Center))
        }
    }
}

/** The kind and page-count badges a thumbnail carries, as far as the view shows them. */
@Composable
internal fun ThumbBadges(b: ExplorerBody, e: BrowseEntry, inset: Dp = 8.dp) {
    Box(Modifier.fillMaxSize().padding(inset)) {
        val kind = b.kind(e)
        if (b.view.showKind && (kind == EntryKind.PDF || kind == EntryKind.CANVAS)) {
            TileBadge(kindIcon(kind), if (kind == EntryKind.PDF) "PDF" else stringResource(R.string.kind_canvas), Modifier.align(Alignment.BottomStart))
        }
        if (b.view.showKind && kind == EntryKind.FILE) {
            TileBadge(kindIcon(kind), ReaderKind.ofName(e.name)?.label.orEmpty(), Modifier.align(Alignment.BottomStart))
        }
        val pages = b.pages(e)
        if (b.view.showPages && pages > 1) TileBadge(XnotesIcons.pages, "$pages", Modifier.align(Alignment.BottomEnd))
    }
}

@Composable
private fun EntryMenuButton(b: ExplorerBody, e: BrowseEntry, tint: Color, size: Dp = 32.dp) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(width = size, height = 36.dp)) {
            Icon(XnotesIcons.more, stringResource(R.string.more_for, b.label(e)), tint = tint, modifier = Modifier.size(17.dp))
        }
        EntryMenuFor(b, e, open) { open = false }
    }
}

/** An entry's overflow menu, with the file-only block for notes and canvases and the pin toggle for folders. */
@Composable
internal fun EntryMenuFor(b: ExplorerBody, e: BrowseEntry, expanded: Boolean, onDismiss: () -> Unit) {
    val m = b.host.menu
    // Reader files have no share/copy/export block: those flows name and render editable documents.
    val readOnly = ReaderKind.isReadable(e.name)
    EntryMenu(
        expanded, onDismiss,
        onRename = { m.rename(e) }, onCopy = { m.copy(e) }, onCut = { m.cut(e) }, onDelete = { m.delete(e) },
        onShare = if (e.isDir || readOnly) null else ({ m.share(e) }),
        onSaveCopy = { m.saveCopy(e) },
        onExportPdf = { m.exportPdf(e) },
        onColor = { c -> m.color(e, c) },
        pinned = e.isDir && b.host.isPinned(e),
        onTogglePin = if (e.isDir) ({ m.togglePin(e) }) else null,
        onNameColor = e.color?.let { c -> { m.nameColor(c) } },
        onMoveTo = { m.moveTo(e) },
        onPreview = if (e.isDir || readOnly) null else ({ m.preview(e) }),
        onConvertToNote = if (readOnly) ({ m.convertToNote(e) }) else null,
        onImportInto = if (DocumentKind.ofName(e.name) == DocumentKind.NOTE) ({ m.importInto(e) }) else null,
        deleteLabel = b.deleteLabel,
    )
}

/** Keeps a tile's coordinates registered for hit-testing a drag, and drops them when it leaves. */
@Composable
private fun Modifier.registered(b: ExplorerBody, e: BrowseEntry): Modifier {
    DisposableEffect(e.documentUri) { onDispose { b.host.onPlaced(e, null) } }
    return this.onPlaced { b.host.onPlaced(e, it) }
}

/** A file in the grid: the outlined thumbnail with its badges, and under it, outside the outline, the name, date and ⋮. */
@Composable
internal fun GridFileTile(b: ExplorerBody, e: BrowseEntry) {
    val palette = LocalPalette.current
    val selecting = b.host.selecting()
    val selected = b.host.isSelected(e)
    val code = b.colorOf(e)?.let { codeTint(it, palette) }
    val accent = palette.accent.toComposeColor()
    val shape = MaterialTheme.shapes.medium
    Column(
        Modifier
            .alpha(if (b.host.isCut(e)) 0.4f else 1f)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { b.host.onClick(e) }
            .registered(b, e),
    ) {
        // Only the thumbnail is outlined, so the colour code and selection show on it alone.
        Box(
            Modifier.fillMaxWidth().aspectRatio(if (b.view.thumb == ThumbShape.PAGE) 100f / 141f else 1f)
                .clip(shape).border(if (selected) 2.dp else 1.dp, if (selected) accent else (code ?: palette.border.toComposeColor()), shape),
        ) {
            EntryThumb(b.editor, e, b.view.thumb, Modifier.fillMaxSize())
            if (selected) Box(Modifier.fillMaxSize().background(palette.selectionBackground.withAlpha(77).toComposeColor()))
            ThumbBadges(b, e)
            if (selecting) CheckRing(selected, Modifier.align(Alignment.TopStart).padding(8.dp))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 50.dp)
                .padding(start = 10.dp, end = 2.dp, top = 7.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(b.label(e), color = palette.text.toComposeColor(), fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val meta = b.metaText(e)
                if (meta.isNotEmpty()) {
                    Text(meta, color = palette.textDim.toComposeColor(), fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (!selecting) EntryMenuButton(b, e, palette.textDim.toComposeColor())
        }
    }
}

/**
 * A folder as a chip in the folder row: its icon, name, how many items it holds, and ⋮. Selected (or under a
 * dragged selection) it fills with the accent; a dropped move flicks it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FolderChipTile(b: ExplorerBody, e: BrowseEntry, height: Dp = 60.dp) {
    val palette = LocalPalette.current
    val selecting = b.host.selecting()
    val active = b.host.isSelected(e) || b.host.isDropTarget(e)
    val code = b.colorOf(e)?.let { codeTint(it, palette) }
    val accent = palette.accent.toComposeColor()
    val onAccent = palette.selectionForeground.toComposeColor()
    val pulse = remember { Animatable(1f) }
    val pulsing = b.host.isPulsing(e)
    LaunchedEffect(pulsing) {
        if (pulsing) {
            pulse.animateTo(1.08f, tween(110))
            pulse.animateTo(1f, tween(160))
            b.host.onPulseDone(e)
        }
    }
    val shape = MaterialTheme.shapes.small
    Row(
        Modifier
            .fillMaxWidth()
            .height(height)
            .scale(pulse.value)
            .registered(b, e)
            .clip(shape)
            .background(if (active) palette.selectionBackground.toComposeColor() else Color.Transparent)
            .then(if (!active && code != null) Modifier.colorHatch(code) else Modifier)
            .border(1.dp, if (active) accent else (code ?: palette.border.toComposeColor()), shape)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { b.host.onClick(e) },
                onLongClick = { b.host.onLongClick(e) },
            )
            .alpha(if (b.host.isCut(e)) 0.4f else 1f)
            .padding(start = 12.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(XnotesIcons.folder, null, tint = if (active) onAccent else (code ?: palette.textDim.toComposeColor()), modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(b.label(e), color = if (active) onAccent else palette.text.toComposeColor(), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = b.metaOverride?.invoke(e) ?: b.counts[e.documentUri]?.let { itemsLabel(b.words, it) }
            if (meta != null) Text(meta, color = if (active) onAccent else palette.textDim.toComposeColor(), fontSize = 11.5.sp, maxLines = 1)
        }
        if (b.host.isPinned(e)) Icon(XnotesIcons.pin, stringResource(R.string.pinned_to_sidebar), tint = if (active) onAccent else palette.textDim.toComposeColor(), modifier = Modifier.size(14.dp))
        // Kept in select mode so the chip never changes width; there it only ends the selection.
        if (selecting) {
            IconButton(onClick = { b.host.onClick(e) }, modifier = Modifier.size(width = 32.dp, height = 40.dp)) {
                Icon(XnotesIcons.more, null, tint = if (active) onAccent else palette.textDim.toComposeColor(), modifier = Modifier.size(17.dp))
            }
        } else {
            EntryMenuButton(b, e, palette.textDim.toComposeColor())
        }
    }
}

/** A folder sorted in among file cards: the same outlined square with the folder's icon where a page would be, its name under it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun FolderCardTile(b: ExplorerBody, e: BrowseEntry) {
    val palette = LocalPalette.current
    val selecting = b.host.selecting()
    val active = b.host.isSelected(e) || b.host.isDropTarget(e)
    val code = b.colorOf(e)?.let { codeTint(it, palette) }
    val accent = palette.accent.toComposeColor()
    val shape = MaterialTheme.shapes.medium
    Column(
        Modifier
            .alpha(if (b.host.isCut(e)) 0.4f else 1f)
            .registered(b, e)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { b.host.onClick(e) },
                onLongClick = { b.host.onLongClick(e) },
            ),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(if (b.view.thumb == ThumbShape.PAGE) 100f / 141f else 1f)
                .clip(shape).background(palette.surface.toComposeColor())
                .border(if (active) 2.dp else 1.dp, if (active) accent else (code ?: palette.border.toComposeColor()), shape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(XnotesIcons.folder, null, tint = code ?: palette.textDim.toComposeColor(), modifier = Modifier.size(44.dp))
            if (selecting) CheckRing(b.host.isSelected(e), Modifier.align(Alignment.TopStart).padding(8.dp))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 50.dp)
                .padding(start = 10.dp, end = 2.dp, top = 7.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(b.label(e), color = palette.text.toComposeColor(), fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(b.metaText(e), color = palette.textDim.toComposeColor(), fontSize = 11.5.sp, maxLines = 1)
            }
            if (!selecting) EntryMenuButton(b, e, palette.textDim.toComposeColor())
        }
    }
}

/** One row of the List layout. [wide] shows the Kind, Pages and Size columns. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ListRow(b: ExplorerBody, e: BrowseEntry, wide: Boolean) {
    val palette = LocalPalette.current
    val selecting = b.host.selecting()
    val selected = b.host.isSelected(e) || b.host.isDropTarget(e)
    val compact = b.view.compactRows
    val code = b.colorOf(e)?.let { codeTint(it, palette) }
    val kind = b.kind(e)
    val fg = (if (selected) palette.selectionForeground else palette.text).toComposeColor()
    val dim = if (selected) fg.copy(alpha = 0.72f) else palette.textDim.toComposeColor()
    Row(
        Modifier
            .fillMaxWidth()
            .height(if (compact) 40.dp else 56.dp)
            .alpha(if (b.host.isCut(e)) 0.4f else 1f)
            .background(if (selected) palette.selectionBackground.toComposeColor() else Color.Transparent)
            .registered(b, e)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { b.host.onClick(e) },
                onLongClick = if (e.isDir) ({ b.host.onLongClick(e) }) else null,
            )
            .padding(start = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selecting) {
            RowCheck(b.host.isSelected(e))
            Spacer(Modifier.width(14.dp))
        }
        val box = if (compact) 28.dp else 40.dp
        val shape = MaterialTheme.shapes.small
        if (e.isDir || compact) {
            Box(Modifier.size(box).clip(shape).background(palette.surface.toComposeColor()), contentAlignment = Alignment.Center) {
                Icon(kindIcon(kind), null, tint = code ?: dim, modifier = Modifier.size(if (compact) 17.dp else 22.dp))
            }
        } else {
            EntryThumb(b.editor, e, ThumbShape.TOP, Modifier.size(box).clip(shape).border(1.dp, palette.border.toComposeColor(), shape))
        }
        Row(Modifier.weight(1f).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f, fill = false)) {
                Text(b.label(e), color = fg, fontSize = 14.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val where = b.whereOf?.invoke(e)
                if (!wide && !compact) Text(listOfNotNull(where, b.metaText(e).ifEmpty { null }).joinToString(" · "), color = dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                else if (where != null && !compact) Text(where, color = dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (code != null && !e.isDir) Box(Modifier.size(8.dp).clip(CircleShape).background(code))
            if (b.host.isPinned(e)) Icon(XnotesIcons.pin, stringResource(R.string.pinned_to_sidebar), tint = dim, modifier = Modifier.size(14.dp))
        }
        if (wide) {
            Row(Modifier.width(LIST_KIND_W), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(kindIcon(kind), null, tint = dim, modifier = Modifier.size(16.dp))
                Text(kindLabel(kind), color = dim, fontSize = 13.sp, maxLines = 1)
            }
            Text(if (e.isDir || kind == EntryKind.CANVAS || kind == EntryKind.FILE) "–" else b.pages(e).takeIf { it > 0 }?.toString() ?: "", color = fg, fontSize = 13.sp, textAlign = TextAlign.End, modifier = Modifier.width(LIST_PAGES_W))
            Text(
                if (e.isDir) b.counts[e.documentUri]?.let { itemsLabel(b.words, it) } ?: "" else formatSize(e.size),
                color = if (e.isDir) dim else fg, fontSize = 13.sp, textAlign = TextAlign.End, maxLines = 1,
                modifier = Modifier.width(LIST_SIZE_W),
            )
            Text(b.metaOverride?.invoke(e) ?: b.whenText(e, withTime = true), color = dim, fontSize = 13.sp, maxLines = 1, modifier = Modifier.width(LIST_WHEN_W).padding(start = 28.dp))
        }
        if (!selecting) EntryMenuButton(b, e, dim, size = 40.dp) else Spacer(Modifier.width(40.dp))
    }
}

internal val LIST_KIND_W = 120.dp
internal val LIST_PAGES_W = 70.dp
internal val LIST_SIZE_W = 90.dp
internal val LIST_WHEN_W = 170.dp

/** A page standing on the gallery shelf: the whole first page, with sheets behind it for a longer note. */
@Composable
internal fun GalleryItem(b: ExplorerBody, e: BrowseEntry, shelf: Dp) {
    val palette = LocalPalette.current
    val selecting = b.host.selecting()
    val selected = b.host.isSelected(e)
    val kind = b.kind(e)
    val img = rememberThumb(b.editor, e, whole = true)
    val ratio = pageRatio(img, if (kind == EntryKind.CANVAS) CANVAS_RATIO else A4_RATIO)
    val code = b.colorOf(e)?.let { codeTint(it, palette) }
    val edge = if (selected) palette.accent.toComposeColor() else (code ?: palette.paperBorder.toComposeColor())
    val pages = b.pages(e)
    Column(
        Modifier
            .fillMaxWidth()
            .alpha(if (b.host.isCut(e)) 0.4f else 1f)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { b.host.onClick(e) }
            .registered(b, e),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(shelf), contentAlignment = Alignment.BottomCenter) {
            // In its own shape: upright pages stand the shelf's height, landscape ones lower, neither wider than the column.
            val (pageW, pageH) = pageFit(ratio, maxWidth - 8.dp, if (ratio > 1f) shelf * 0.68f else shelf - 14.dp)
            Box(Modifier.size(pageW + 8.dp, pageH + 8.dp)) {
                val sheet = RoundedCornerShape(3.dp)
                if (pages > 8) Box(Modifier.offset(8.dp, 0.dp).size(pageW, pageH).clip(sheet).background(palette.paper.toComposeColor()).border(1.dp, edge, sheet))
                if (pages > 1) Box(Modifier.offset(4.dp, 4.dp).size(pageW, pageH).clip(sheet).background(palette.paper.toComposeColor()).border(1.dp, edge, sheet))
                Box(Modifier.offset(0.dp, 8.dp).size(pageW, pageH).clip(sheet).border(if (selected) 2.dp else 1.dp, edge, sheet)) {
                    ThumbImage(img, whole = true, Modifier.fillMaxSize(), ReaderKind.ofName(e.name)?.label)
                    if (selected) Box(Modifier.fillMaxSize().background(palette.selectionBackground.withAlpha(77).toComposeColor()))
                    if (selecting) CheckRing(selected, Modifier.align(Alignment.TopStart).padding(6.dp))
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
            if (code != null) Box(Modifier.size(8.dp).clip(CircleShape).background(code))
            Text(b.label(e), color = palette.text.toComposeColor(), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
            if (b.view.showKind && (kind == EntryKind.PDF || kind == EntryKind.CANVAS)) {
                Icon(kindIcon(kind), null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(13.dp))
            }
            val count = when {
                !b.view.showPages -> null
                kind == EntryKind.CANVAS -> stringResource(R.string.kind_canvas)
                pages > 0 -> pluralStringResource(R.plurals.pages_count, pages, pages)
                else -> null
            }
            Text(listOfNotNull(count, b.metaText(e).ifEmpty { null }).joinToString(" · "), color = palette.textDim.toComposeColor(), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A file on the timeline: a square card, then its name, the time that placed it there and the folder it's in. */
@Composable
internal fun TimelineCard(b: ExplorerBody, e: BrowseEntry, side: Dp) {
    val palette = LocalPalette.current
    val selecting = b.host.selecting()
    val selected = b.host.isSelected(e)
    val code = b.colorOf(e)?.let { codeTint(it, palette) }
    val shape = MaterialTheme.shapes.medium
    Column(
        Modifier
            .width(side)
            .alpha(if (b.host.isCut(e)) 0.4f else 1f)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { b.host.onClick(e) }
            .registered(b, e),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(Modifier.size(side).clip(shape).border(if (selected) 2.dp else 1.dp, if (selected) palette.accent.toComposeColor() else (code ?: palette.border.toComposeColor()), shape)) {
            EntryThumb(b.editor, e, ThumbShape.TOP, Modifier.fillMaxSize())
            if (selected) Box(Modifier.fillMaxSize().background(palette.selectionBackground.withAlpha(77).toComposeColor()))
            if (b.view.showKind) {
                val kind = b.kind(e)
                if (kind == EntryKind.PDF || kind == EntryKind.CANVAS) {
                    TileBadge(kindIcon(kind), if (kind == EntryKind.PDF) "PDF" else stringResource(R.string.kind_canvas), Modifier.align(Alignment.BottomStart).padding(6.dp))
                }
            }
            if (selecting) CheckRing(selected, Modifier.align(Alignment.TopStart).padding(6.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(b.label(e), color = palette.text.toComposeColor(), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(formatClock(b.words, b.view.timelineTime(e), b.clock24, b.zone), color = palette.textDim.toComposeColor(), fontSize = 11.5.sp, maxLines = 1)
                val where = b.whereOf?.invoke(e)
                if (where != null) {
                    Icon(XnotesIcons.folder, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(12.dp))
                    Text(where, color = palette.textDim.toComposeColor(), fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** A small folder chip for the gallery's folder row: the icon, the name and how many items it holds. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GalleryFolderChip(b: ExplorerBody, e: BrowseEntry) {
    val palette = LocalPalette.current
    val active = b.host.isSelected(e) || b.host.isDropTarget(e)
    val code = b.colorOf(e)?.let { codeTint(it, palette) }
    val shape = MaterialTheme.shapes.small
    Row(
        Modifier
            .height(44.dp)
            .registered(b, e)
            .clip(shape)
            .background(if (active) palette.selectionBackground.toComposeColor() else Color.Transparent)
            .then(if (!active && code != null) Modifier.colorHatch(code) else Modifier)
            .border(1.dp, if (active) palette.accent.toComposeColor() else (code ?: palette.border.toComposeColor()), shape)
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { b.host.onClick(e) },
                onLongClick = { b.host.onLongClick(e) },
            )
            .padding(start = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val fg = if (active) palette.selectionForeground.toComposeColor() else palette.text.toComposeColor()
        Icon(XnotesIcons.folder, null, tint = if (active) fg else (code ?: palette.textDim.toComposeColor()), modifier = Modifier.size(20.dp))
        Text(b.label(e), color = fg, fontSize = 14.sp, maxLines = 1)
        b.counts[e.documentUri]?.let { Text("$it", color = if (active) fg else palette.textDim.toComposeColor(), fontSize = 12.sp) }
    }
}

internal fun formatClock(w: ExplorerWords, time: Long, clock24: Boolean, zone: ZoneId): String {
    if (time <= 0) return ""
    val t = java.time.Instant.ofEpochMilli(time).atZone(zone).toLocalTime()
    return clockText(w, t, clock24)
}

/**
 * What Home can open with above the top folder's items: a shelf of what was opened lately, the pinned folders,
 * then a heading for the folder itself.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun HomeShelves(
    b: ExplorerBody,
    recents: List<RecentEntry>,
    pins: List<com.xnotes.settings.PinnedFolder>,
    rootName: String,
    counts: String,
    onSeeAll: () -> Unit,
    onOpenRecent: (BrowseEntry) -> Unit,
    onOpenPin: (com.xnotes.settings.PinnedFolder) -> Unit,
) {
    val palette = LocalPalette.current
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        if (recents.isNotEmpty()) {
            ShelfTitle(stringResource(R.string.recent), stringResource(R.string.see_all), onSeeAll)
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                items(recents.take(8).size) { i -> RecentCard(b, recents[i], onOpenRecent) }
            }
            Spacer(Modifier.height(20.dp))
        }
        if (pins.isNotEmpty()) {
            ShelfTitle(stringResource(R.string.pinned))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                pins.forEach { pin ->
                    val shape = MaterialTheme.shapes.small
                    Row(
                        Modifier.height(44.dp).clip(shape).border(1.dp, palette.border.toComposeColor(), shape)
                            .clickable { onOpenPin(pin) }.padding(start = 12.dp, end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(XnotesIcons.folder, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(20.dp))
                        Text(pin.name, color = palette.text.toComposeColor(), fontSize = 14.sp, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.in_folder, rootName), color = palette.text.toComposeColor(), fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            Text(counts, color = palette.textDim.toComposeColor(), fontSize = 12.5.sp)
        }
    }
}

/** One card on the Recent shelf: the page, its name, when it was opened and the folder it's in. */
@Composable
private fun RecentCard(b: ExplorerBody, r: RecentEntry, onOpen: (BrowseEntry) -> Unit) {
    val palette = LocalPalette.current
    val e = r.entry
    val shape = MaterialTheme.shapes.medium
    val code = b.colorOf(e)?.let { codeTint(it, palette) }
    Column(
        Modifier.width(168.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onOpen(e) },
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Box(Modifier.size(168.dp).clip(shape).border(1.dp, code ?: palette.border.toComposeColor(), shape)) {
            EntryThumb(b.editor, e, ThumbShape.TOP, Modifier.fillMaxSize())
            ThumbBadges(b, e, 6.dp)
        }
        Spacer(Modifier.height(4.dp))
        Text(b.label(e), color = palette.text.toComposeColor(), fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(openedLabel(b.words, r.opened, b.now, b.zone), color = palette.textDim.toComposeColor(), fontSize = 11.5.sp, maxLines = 1)
        r.where?.let { where ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(XnotesIcons.folder, null, tint = palette.textDim.toComposeColor(), modifier = Modifier.size(12.dp))
                Text(where, color = palette.textDim.toComposeColor(), fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A heading over a Home shelf, with an optional action at its end. */
@Composable
internal fun ShelfTitle(title: String, action: String? = null, onAction: () -> Unit = {}) {
    val palette = LocalPalette.current
    Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = palette.text.toComposeColor(), fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        if (action != null) {
            Text(action, color = palette.accent.toComposeColor(), fontSize = 13.5.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.clip(MaterialTheme.shapes.extraSmall).clickable(onClick = onAction).padding(horizontal = 8.dp, vertical = 4.dp))
        }
    }
}
