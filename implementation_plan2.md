# Implementation Plan - Dual-Editor Split-Screen System

This plan updates the split-screen feature to allow **both PDFs/notes to be fully editable side-by-side**. 

Instead of a custom read-only PDF viewer, we will instantiate two independent `Editor` instances (`editorLeft` and `editorRight`). To prevent file collisions, we will isolate their temporary directories and session storage paths. The split pane will naturally render the backstage file explorer when no note is open on that side, letting the user pick any note from the app's collection to open and edit.

---

## Proposed Changes

### 1. Editor Instance Isolation

#### [MODIFY] [Editor.kt](file:///c:/Users/siddi/Desktop/coding/xnotes-android/app/src/main/java/com/xnotes/ui/Editor.kt)
- Update constructor to accept an `instanceName: String` parameter (defaulting to `"main"`):
  ```kotlin
  class Editor(context: Context, val instanceName: String = "main")
  ```
- Parameterize all temporary folders and session paths using `instanceName`:
  - `pdfDir` $\rightarrow$ `File(appContext.filesDir, "pdfsrc_$instanceName")`
  - `imageDir` $\rightarrow$ `File(appContext.filesDir, "noteimg_$instanceName")`
  - `saveTmpDir` $\rightarrow$ `File(appContext.filesDir, "savetmp_$instanceName")`
  - `session` $\rightarrow$ `SessionStore(File(appContext.filesDir, "session_$instanceName"), ...)`
- Clean up:
  - Remove previous `SplitPdfView`-related temporary variables (`splitPdfSource`, `splitPdfTitle`, etc.) as they are no longer needed; the split pane will be a full `Editor` instance.

---

### 2. Dual-Editor Layout & Backstage Selector

#### [MODIFY] [MainActivity.kt](file:///c:/Users/siddi/Desktop/coding/xnotes-android/app/src/main/java/com/xnotes/MainActivity.kt)
- In `MainActivity.onCreate`, instantiate two distinct editors:
  ```kotlin
  val editorLeft = remember { Editor(context, "left") }
  val editorRight = remember { Editor(context, "right") }
  ```
- Restore sessions for both editors at launch:
  ```kotlin
  edLeft.restoreSession()
  edRight.restoreSession()
  ```
- Update `EditorScreen` layout:
  - Display `editorLeft` on the left.
  - If `editorLeft.splitScreenActive` is true, render a split screen:
    - Vertical Divider.
    - Right pane:
      - If `editorRight.noteOpen` is false, render `Backstage` bound to `editorRight`, pointing to `BackstageView.HOME`. When a note is clicked, call `editorRight.openAsync(...)`.
      - If `editorRight.noteOpen` is true, render a full `Column` containing `Toolbar(editorRight)`, `AndroidView(editorRight.view)`, and all editor overlay controls.

---

### 3. Cleanup & Navigation Menu Toggle

#### [MODIFY] [Popups.kt](file:///c:/Users/siddi/Desktop/coding/xnotes-android/app/src/main/java/com/xnotes/ui/Popups.kt)
- In `ViewMenuPopup`, update the `SPLIT SCREEN` toggle to use `editor.toggleSplitScreen()`.

#### [DELETE] [SplitPdfView.kt](file:///c:/Users/siddi/Desktop/coding/xnotes-android/app/src/main/java/com/xnotes/ui/SplitPdfView.kt)
- Delete the temporary read-only PDF viewer since both panes will now use the standard, fully functional note editors.

---

## Verification Plan

### Manual Verification
- Deploy the updated app.
- Toggle **Split Screen** on the View menu.
- Verify that the right side displays the app's home page file explorer.
- Select a note/PDF from the right side explorer; verify it opens side-by-side with the main note.
- Verify that both sides support full editing, drawing, tools selection, and zoom.
- Verify closing the notes or app stores both sessions separately without conflict.
