package com.xnotes.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.xnotes.R
import com.xnotes.ui.icons.XnotesIcons
import com.xnotes.ui.theme.LocalPalette
import com.xnotes.ui.theme.toComposeColor
import kotlinx.coroutines.delay

/**
 * A tap here, or any pen stroke, lets the side panel's page selection go; a finger that scrolls or
 * pinches keeps it. Watched on the way in and never consumed, so whatever is under it still acts.
 */
private fun Modifier.endsPageSelection(editor: Editor): Modifier = pointerInput(editor) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (!editor.inPageSelectionMode) return@awaitEachGesture
        if (down.type == PointerType.Stylus || down.type == PointerType.Eraser) {
            editor.clearPageSelection()
            return@awaitEachGesture
        }
        while (true) {
            val e = awaitPointerEvent(PointerEventPass.Initial)
            if (e.changes.any { it.id != down.id && it.pressed }) return@awaitEachGesture
            val c = e.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) return@awaitEachGesture
            if (!c.pressed) {
                editor.clearPageSelection()
                return@awaitEachGesture
            }
        }
    }
}

@Composable
internal fun WorkspaceToolbar(
    editor: Editor,
    layout: com.xnotes.core.tools.ToolbarLayout,
    actions: PaneActions,
    occlusion: com.xnotes.ui.SharedToolbarOcclusion,
) {
    Box(Modifier.onGloballyPositioned { occlusion.bar = it.boundsInRoot() }
        .then(if (!editor.canvasOpen) Modifier.endsPageSelection(editor) else Modifier)) {
        // The frame (scroll position and tool animation) survives focus changes; popups do not.
        com.xnotes.ui.SharedToolbarFrame(if (editor.canvasOpen) editor.infinite.tool else editor.tool) {
            androidx.compose.runtime.key(editor, editor.canvasOpen) {
                if (editor.canvasOpen) {
                    com.xnotes.ui.InfiniteToolbar(editor.infinite,
                        onOpenBackstage = actions.onOpenBackstage,
                        onInsertImage = { actions.onInsertCanvasImage(editor, null) },
                        onInsertAudio = { actions.onInsertAudio(editor) },
                        onRecordAudio = { actions.onRecordAudio(editor) },
                        onTakePhoto = { actions.onTakePhoto(editor) }, layout = layout)
                } else {
                    Toolbar(editor, actions.onToggleFullscreen, actions.onOpenBackstage,
                            onInsertImage = { actions.onInsertImage(editor, null) },
                            onAddStickers = actions.onAddStickers,
                            onImportTemplate = actions.onImportTemplate,
                            onInsertAudio = { actions.onInsertAudio(editor) },
                            onRecordAudio = { actions.onRecordAudio(editor) },
                            onTakePhoto = { actions.onTakePhoto(editor) }, layout = layout)
                }
            }
        }
    }
}

/**
 * One document surface and its local menus; toolbar chrome belongs to the enclosing workspace.
 * [app] is the primary editor, which owns the split; [editor] is this pane's own. Touching anywhere
 * in the pane gives it the focus, so the keyboard and the file actions follow the pen.
 */
@Composable
internal fun EditorPane(
    editor: Editor,
    app: Editor,
    actions: PaneActions,
    closable: Boolean,
    sharedOcclusion: com.xnotes.ui.SharedToolbarOcclusion?,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val focusRequester = remember { FocusRequester() }
    val focused = app.active === editor
    SideEffect { editor.openInPane = { uri -> actions.onOpenFile(editor, uri) } }
    // This pane owns the keyboard while it is the focused one; (re)grab it as that changes.
    LaunchedEffect(focused, editor.noteOpen) {
        if (focused && editor.noteOpen) runCatching { focusRequester.requestFocus() }
    }
    val takeFocus = Modifier.pointerInput(editor) {
        awaitPointerEventScope {
            while (true) {
                // Initial pass and never consumed: the pane notices the touch, the canvas still gets it.
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press) app.focusPane(editor.pane)
            }
        }
    }
    val localOcclusion = remember(editor) { com.xnotes.ui.SharedToolbarOcclusion() }
    val occlusion = sharedOcclusion ?: localOcclusion
    val nativeLayout = if (editor.canvasOpen) app.canvasToolbarLayout else app.toolbarLayout
    var bounds by remember { mutableStateOf(androidx.compose.ui.geometry.Rect.Zero) }
    val insets = occlusion.insets(bounds, com.xnotes.ui.LocalToolbarLook.current)
    SideEffect {
        if (editor.canvasOpen) editor.infinite.setToolbarCover(insets.left, insets.top, insets.right, insets.bottom)
        else editor.setToolbarCover(insets.left, insets.top, insets.right, insets.bottom)
    }
    val density = LocalDensity.current
    val cover = with(density) { androidx.compose.foundation.layout.PaddingValues.Absolute(
        insets.left.toFloat().toDp(), insets.top.toFloat().toDp(),
        insets.right.toFloat().toDp(), insets.bottom.toFloat().toDp()) }
    CompositionLocalProvider(com.xnotes.ui.LocalToolbarCover provides cover) {
        // The colour a swatch drag carries rides over the whole pane, toolbar and page alike.
        Box(modifier) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(palette.bg.toComposeColor())
                    .then(takeFocus)
                    .focusRequester(focusRequester)
                    .focusable()
                    .onPreviewKeyEvent { ke ->
                        ke.type == KeyEventType.KeyDown && editor.handleKeyDown(ke.nativeKeyEvent)
                    },
            ) {
                if (closable) {
                    Box(Modifier.fillMaxWidth().height(2.dp)
                        .background((if (focused) palette.accent else palette.border).toComposeColor()))
                }
                OptionalToolbarAround(sharedOcclusion == null,
                    bar = { WorkspaceToolbar(editor, nativeLayout, actions, occlusion) }) { floatingBar ->
                    Box(Modifier.fillMaxSize()) {
                        if (editor.canvasOpen) {
                            val canvas = editor.infinite
                            Box(modifier = Modifier.fillMaxSize().clipToBounds()) {
                                AndroidView(
                                    factory = { detached(canvas.surfaces) },
                                    modifier = Modifier.fillMaxSize().onGloballyPositioned { bounds = it.boundsInRoot() },
                                    update = { canvas.view.publish() },
                                )
                                canvas.editingField?.let { field ->
                                    com.xnotes.ui.TextEditorOverlay(canvas, field)
                                }
                                com.xnotes.ui.SelectionMenu(
                                    canvas,
                                    onSaveAudio = { actions.onSaveAudio(editor, it) },
                                    onShareAudio = { actions.onShareAudio(editor, it) },
                                    onShareImage = actions.onShareImage,
                                )
                                com.xnotes.ui.CropOverlay(canvas)
                                com.xnotes.ui.TextStyleBar(canvas)
                                com.xnotes.ui.LongPressMenu(canvas, onInsertImageAt = { c -> actions.onInsertCanvasImage(editor, c) })
                                com.xnotes.ui.CanvasDebugOverlay(canvas)
                            }
                        } else {
                            Row(modifier = Modifier.fillMaxSize()) {
                                if (editor.sidebarVisible) {
                                    com.xnotes.ui.SidePanel(
                                        editor,
                                        onSharePages = { pages, asPdf -> actions.onSharePages(editor, pages, asPdf) },
                                        onSavePagesAsPdf = { pages -> actions.onSavePagesAsPdf(editor, pages) },
                                        onSavePagesAsImages = { pages -> actions.onSavePagesAsImages(editor, pages) },
                                        onInsertPdf = { after -> actions.onInsertPdf(editor, after) },
                                        onImportTemplate = actions.onImportTemplate,
                                        onTemplateFromImage = actions.onTemplateFromImage,
                                    )
                                }
                                Box(modifier = Modifier.weight(1f).fillMaxHeight().clipToBounds().endsPageSelection(editor)) {
                                    AndroidView(
                                        factory = { detached(editor.surfaces) },
                                        modifier = Modifier.fillMaxSize().onGloballyPositioned { bounds = it.boundsInRoot() },
                                        update = { editor.view.requestRender() }, // repaint on (re)attach so a push never flashes blank
                                    )
                                    editor.editingField?.let { field ->
                                        com.xnotes.ui.TextEditorOverlay(editor, field)
                                    }
                                    com.xnotes.ui.SelectionMenu(
                                        editor,
                                        onSaveAudio = { actions.onSaveAudio(editor, it) },
                                        onShareAudio = { actions.onShareAudio(editor, it) },
                                        onShareImage = actions.onShareImage,
                                    )
                                    com.xnotes.ui.CropOverlay(editor)
                                    com.xnotes.ui.ScreenshotMenu(editor, onShare = actions.onShareImage)
                                    com.xnotes.ui.TextStyleBar(editor)
                                    com.xnotes.ui.LongPressMenu(editor, onInsertImageAt = { c -> actions.onInsertImage(editor, c) })
                                    com.xnotes.ui.FlowEditMenu(editor)
                                    com.xnotes.ui.PdfSelectionMenu(editor)
                                    com.xnotes.ui.MarkupNotePeek(editor)
                                    com.xnotes.ui.MarkupMenu(editor)
                                    com.xnotes.ui.MarkupNoteDialog(editor)
                                    com.xnotes.ui.SlashMenu(editor)
                                    com.xnotes.ui.EmojiMenu(editor)
                                    com.xnotes.ui.FlowTableMenu(editor)
                                    com.xnotes.ui.TableChrome(editor)
                                    ZoomLockHint(editor)
                                }
                            }
                        }
                        Box(Modifier.fillMaxSize().zIndex(1f)) { floatingBar() }
                    }
                }
            }
            com.xnotes.ui.ColorDropOverlay(if (editor.canvasOpen) editor.infinite else editor)
        }
    }
}

/**
 * Hands a reused View back to [AndroidView]. A pane's canvas can move to a fresh composition node
 * when the split opens or closes, and the new node parents the view itself, so it has to leave the
 * old parent first rather than throw.
 */
private fun <T : android.view.View> detached(view: T): T =
    view.also { (it.parent as? android.view.ViewGroup)?.removeView(it) }

/**
 * Transient zoom-lock affordance, centred just below the toolbar. Appears when a pinch snaps to
 * fit-to-width ([Editor.zoomLockHint] bumps) and auto-dismisses after a moment. Tapping toggles the
 * zoom lock, so the same chip both locks and unlocks; each tap re-arms the dismiss timer so it
 * lingers long enough to tap again.
 */
@Composable
private fun BoxScope.ZoomLockHint(editor: Editor) {
    val palette = LocalPalette.current
    var visible by remember { mutableStateOf(false) }
    // Bumped on the initial fit-width snap and on every tap; (re)starts the auto-dismiss timer below.
    var armToken by remember { mutableStateOf(0) }
    LaunchedEffect(editor.zoomLockHint) {
        if (editor.zoomLockHint > 0) {
            visible = true
            armToken++
        }
    }
    LaunchedEffect(armToken) {
        if (armToken > 0) {
            delay(2500)
            visible = false
        }
    }
    // Breaking past the fit-width magnet dismisses the hint at once.
    LaunchedEffect(editor.zoomLockHintDismiss) {
        if (editor.zoomLockHintDismiss > 0) visible = false
    }
    val locked = editor.zoomLocked
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.align(Alignment.TopCenter).padding(com.xnotes.ui.LocalToolbarCover.current).padding(top = 8.dp),
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        Row(
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .background(palette.surface.toComposeColor())
                .border(1.dp, palette.border.toComposeColor(), MaterialTheme.shapes.small)
                .clickable { editor.toggleZoomLock(); armToken++ }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (locked) XnotesIcons.lock else XnotesIcons.unlock,
                contentDescription = if (locked) stringResource(R.string.unlock_zoom) else stringResource(R.string.lock_zoom_fit_width),
                tint = (if (locked) palette.accent else palette.textDim).toComposeColor(),
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (locked) stringResource(R.string.unlock_zoom) else stringResource(R.string.lock_zoom),
                color = palette.text.toComposeColor(),
                fontSize = 13.sp,
            )
        }
    }
}
