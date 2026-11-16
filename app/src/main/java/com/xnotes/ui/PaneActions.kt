package com.xnotes.ui

/**
 * What a pane can ask the screen around it to do: open a SAF picker, run an export, or change the
 * window. Each callback takes the pane it is acting for, because in a split there are two of them
 * and a picker's result has to come back to the one that opened it.
 */
internal class PaneActions(
    val onToggleFullscreen: () -> Unit,
    /** Leave for the backstage, closing every open pane. */
    val onOpenBackstage: () -> Unit,
    /** Close just this pane, leaving the other one to fill the window. */
    val onClosePane: (Editor) -> Unit,
    val onInsertImage: (Editor, com.xnotes.core.geometry.Pt?) -> Unit,
    val onInsertCanvasImage: (Editor, com.xnotes.core.geometry.Pt?) -> Unit,
    /** Take a photo with the camera app and place it like an inserted picture, on the canvas when [Editor.canvasOpen]. */
    val onTakePhoto: (Editor) -> Unit,
    val onAddStickers: () -> Unit,
    val onInsertAudio: (Editor) -> Unit,
    val onRecordAudio: (Editor) -> Unit,
    val onSaveAudio: (Editor, com.xnotes.core.model.AudioItem) -> Unit,
    val onShareAudio: (Editor, com.xnotes.core.model.AudioItem) -> Unit,
    /** Hand a PNG the pane rendered (a selection or a capture) to the share sheet. */
    val onShareImage: (java.io.File) -> Unit,
    /** Open a stored note in [Editor]'s pane, as a tap on it in the explorer would. */
    val onOpenFile: (Editor, String) -> Unit,
    val onImportTemplate: () -> Unit,
    val onTemplateFromImage: (com.xnotes.core.template.TemplateKind) -> Unit,
    val onSharePages: (Editor, List<Int>, Boolean) -> Unit,
    val onSavePagesAsPdf: (Editor, List<Int>) -> Unit,
    val onSavePagesAsImages: (Editor, List<Int>) -> Unit,
    val onInsertPdf: (Editor, Int) -> Unit,
)
