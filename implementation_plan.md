# Implementation Plan - Split Screen PDF Viewing

This plan adds a vertical split-screen feature to the **xnotes** editor. When split screen is enabled, the editor viewport is split in half:
1. **Left side**: The main note canvas (`CanvasView`), which may have an imported PDF background and is editable.
2. **Right side**: A high-performance, scrollable, and zoomable secondary PDF viewer for referencing other documents.

---

## Proposed Changes

### 1. Editor State Management

#### [MODIFY] [Editor.kt](file:///c:/Users/siddi/Desktop/coding/xnotes-android/app/src/main/java/com/xnotes/ui/Editor.kt)
- Add split screen properties:
  - `splitScreenActive`: Boolean state.
  - `splitPdfSource`: `PdfSource?` of the secondary PDF.
  - `splitPdfTitle`: Title string.
  - `splitPdfPageIndex`: Index of the currently visible page in the split view.
  - `splitPdfPageCount`: Total page count.
  - `splitPdfToc`: List of outline entries.
  - `splitPdfFile`: Local temp file backing the secondary PDF.
- Add methods:
  - `openSplitPdf(file, title)`: Creates the PDF source, kicks off background outline extraction, and activates the split view.
  - `toggleSplitScreen()`: Toggles the split state. If activated without a file, shows the placeholder.
  - `closeSplitScreen()`: Safely closes `splitPdfSource` and deletes `splitPdfFile` to avoid disk/memory leaks.
- Hook system picker:
  - Add `var onRequestOpenSplitPdf: (() -> Unit)? = null` callback.
- Clean up:
  - In `goHome()`, call `closeSplitScreen()` so returning to home drops any open split PDF.

---

### 2. UI Layout & Picker Hook

#### [MODIFY] [MainActivity.kt](file:///c:/Users/siddi/Desktop/coding/xnotes-android/app/src/main/java/com/xnotes/MainActivity.kt)
- Add `openSplitPdfLauncher` using `ActivityResultContracts.OpenDocument()`.
  - Reads selected PDF from Uri content resolver, copies it safely to `cacheDir/split_temp.pdf` on `Dispatchers.IO`, and calls `editor.openSplitPdf`.
- Wire `editor.onRequestOpenSplitPdf` to trigger the launcher.
- Update `EditorScreen` layout:
  - Inside the main `Row` where `editor.view` is embedded, check `editor.splitScreenActive`.
  - If true, display a split screen layout:
    - Left side: Bounding Box holding `AndroidView` for `editor.view`.
    - Split Divider: Vertical hairline border.
    - Right side: Bounding Box holding the new `SplitPdfView(editor)` Composable.
  - Ensure the split state is closed correctly when the activity destroys or note changes.

---

### 3. Split Screen Composable Viewer

#### [NEW] [SplitPdfView.kt](file:///c:/Users/siddi/Desktop/coding/xnotes-android/app/src/main/java/com/xnotes/ui/SplitPdfView.kt)
- Implement `SplitPdfView(editor: Editor)`:
  - **Header Bar**: Displays the name of the PDF, a Table of Contents (Outline) icon button (with dropdown), and a Close button.
  - **Page Navigation Bar**: Shows chevrons to move between pages and a text label indicating current page of total pages.
  - **Scrollable List**: Uses `LazyColumn` and `LazyListState` to monitor visible pages and handle programmatic scrolling.
  - **High-Performance Page Rendering**:
    - Each page is rendered off-thread dynamically via `produceState` on `Dispatchers.IO`.
    - Bitmaps are recycled on disposal to keep memory usage low and prevent Out-Of-Memory (OOM) errors.
  - **Smooth Pinch-to-Zoom & Pan**:
    - Uses Compose's `Modifier.pointerInput` with `detectTransformGestures` and `graphicsLayer` to enable fluid viewport scaling.

---

### 4. View Menu Toggle

#### [MODIFY] [Popups.kt](file:///c:/Users/siddi/Desktop/coding/xnotes-android/app/src/main/java/com/xnotes/ui/Popups.kt)
- In `ViewMenuPopup`, add a `ToggleRow` for `SPLIT SCREEN` (tied to `editor.splitScreenActive` and calling `editor.toggleSplitScreen()`).

---

## Verification Plan

### Automated Verification
- We will build the final project and check for any syntax/import errors.

### Manual Verification
- Deploy to device/emulator.
- Open a note.
- Open the View options popup, toggle **Split Screen**.
- Observe the right pane placeholder and tap **Choose PDF**.
- Select any PDF; verify it opens side-by-side with the note.
- Verify scrolling and pinch-to-zoom in both panes.
- Verify Table of Contents jumps to the correct pages.
- Verify closing the note/app cleans up the temp files.
