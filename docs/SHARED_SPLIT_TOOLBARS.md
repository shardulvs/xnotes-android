# Shared tools for split documents

This feature is developed on `feat/shared-split-toolbars`, starting directly from upstream
`master` at `3926304a` (0.9.7). It does not depend on community builds, document tabs,
customizable multi-toolbar workspaces, or the earlier selection/split fixes. There is no file
format migration. The reference supplied by the user was a FreeNotes screenshot: one document
strip and one tools area spanning the window, with two document surfaces beneath it. The image
establishes the visual organization, not FreeNotes' internal action-routing behavior.

## Behavior

- Shared tools are the default. Preferences offers a dedicated collapsible **Split view** group
  with visual cards for **Shared tools / Tools per pane**. The same controls appear in a compact
  settings popover inside the split, rather than a long menu mixed with unrelated toolbar options.
  The per-pane mode retains independent native toolbars and tool selection.
- In shared mode, two xnotes share the configured note toolbar. Two xcanvases share the canvas toolbar.
- A mixed split offers **xnote interface / xcanvas interface** in the split-settings popover and Preferences. These choices are shown only with shared tools;
  inside the note they are additionally limited to a mixed split. This
  selects the configured layout, not the document that receives commands. Focus never changes
  the chosen interface. The choice is stored in `prefs.mixed_split_toolbar`, default `note`.
- Clicking a document title or touching its surface activates that document. Its title and the
  pane focus line identify it. The tools render the active surface's live state.
- Common tool, ink, palette, shape and tool configurations follow focus before the first pointer
  DOWN reaches the newly active surface. Unsupported canvas tools fall back to Pan. Changing
  focus copies future ink directly; it must not call `pickColor` or a restyling API, since those
  can change selected text/objects and create undo commands merely by changing focus.
- Undo/redo, selection, zoom, PDF/page commands, sidebar, text sessions, autosave and history
  remain per document. Async file/image/audio pickers capture their target editor when opened;
  a later focus change must not redirect their result. An existing selection or text-edit session
  keeps its tool when its pane is activated, so local actions are not invalidated before dispatch.
- An item exclusive to the other document format remains visibly disabled in the chosen layout.
  It never operates on the other, inactive document as a fallback. Common items use native
  renderers for the active format (e.g. Fit and Styles retain that format's semantics).
- Tools are laid out once at the workspace edge (top, bottom, left or right), floating or docked.
  The divider only partitions document content. Floating tools are composed over both surfaces.
- Each document can be closed from its title. The survivor returns to its normal native toolbar.
  The mixed-interface preference remains saved for the next mixed split.

## Implementation boundaries

`MainActivity.SplitHost` owns the shared chrome and the two stable `EditorPane` nodes. Toolbar
contents are keyed to the active editor to dismiss document-owned popups when focus changes.
`SharedToolbarFrame` owns scrolling and the tool-glide animation outside that key. The scoped
`LocalToolbarContentsOnly` tells the native renderers to supply content to the existing frame
rather than nesting another one. Focus changes therefore retain the toolbar scroll position.
The native contents must remain direct layout children of that outer frame: put gesture modifiers
and layout wrappers outside `SharedToolbarFrame`, not around the native toolbar inside its slot.
Otherwise skipping the native frame can lay all items on top of one another inside a Box.
Neither document surface is keyed to toolbar focus, layout choice or split ratio. `EditorPane`
contains its Android surface, sidebar, overlays, text/selection menus and focus routing only.
The keyboard text-format bar is drawn once for the active paged editor.

Both `Toolbar` and `InfiniteToolbar` accept an optional `layout` parameter, defaulting to their
previous native layout for other callers. This allows layout ownership and action target to be
different without substituting one editor for another. `UnavailableSharedToolbarItem` reuses the
customizer's icon identity and labels. Never give the paged renderer a hidden paged editor when
the active pane is a canvas merely to make the layout look right.

`SharedSplitTools` is a small tool-state bridge invoked by `Editor.focusPane`, independent of the
composables and toolbar placement. `adoptSharedInk` belongs to each editor so private setters
stay private. These helpers do not serialize, modify document items, copy history or run a
document command.

`SharedToolbarOcclusion` measures the actual shared bar and each native drawing surface in
**root coordinates**. The pure `sharedToolbarInsets` function computes each surface's overlap.
This matters for side rails, stacked panes, unequal ratios and open page sidebars: a floating
left rail must not reserve left space in the right-hand document. Docked tools already reduce
the content viewport, so their extra insets are zero. `LocalToolbarCover` receives pane-local
padding rather than the whole-workspace padding produced by `ToolbarAround`.

## Porting to community or other toolbar branches

Cherry-pick the feature commit onto a branch based on upstream. A branch that already changes
`SplitHost`, `EditorPane`, toolbar signatures or preference serialization can require conflict
resolution. Independence means no dependency on those earlier feature commits; it does not mean
every heavily modified `MainActivity` can accept a patch without conflicts.

For multi-toolbar workspaces:

1. Resolve the chosen note/canvas workspace once, outside the individual panes. Preserve its
   configured bar order, geometry, row/section layout, visibility and floating positions.
2. Instantiate every workspace bar once. Feed its layout to the **active surface's** renderer.
   Do not duplicate `WorkspaceFloatingBars` or dock layout inside `EditorPane`.
3. Keep document tabs separate from tools; existing tabs can replace the minimal two-document
   strip. Retain a clear active-document indication, close actions and the mixed-layout chooser.
4. Measure all shared floating bars and compute per-surface covers/hit exclusions. Each upstream
   toolbar has its own occlusion object; a shared split measures the one workspace toolbar. A multi-bar
   adapter must combine the actual bounds, not treat one pane's old toolbar cover as global.
5. Preserve native stylus routing and selection hit-area protections from that target branch.
   Register the shared bars once and exclude them from both surfaces. Do not remove community's
   native event bridges or selection action hosts when applying this structural change.
6. Keep n-notes' reader/PDF/link/math menus and its format-specific handlers. Port the shared
   host arrangement; do not replace its entire activity with XNotes' activity.
7. Keep the tool-state bridge and async picker targets. Verify actual drawing and undo on each
   surface; counting visible buttons alone cannot prove correct routing.

## Validation

Validated on 2026-11-09: debug APK and instrumentation APK built successfully; **2,428 JVM tests**
and **12 instrumentation tests on the Redmi Pad 2** passed. The validation package is
`com.xnotes.sharedsplit`, separate from the installed community/debug packages. The in-note
preference test uses real system-dispatched touches, including the popover window.

`lintDebug` was also run. It is not green: it reports 243 errors in source lines already present
in upstream `3926304a` (API/constant/restricted-call checks, resource calls, missing existing
translations and byte-order marks). Each error location's offending source line was compared
against upstream. No error was on a new source line; the new strings have both existing locales.
This does not claim that a complete lint run on an untouched upstream checkout was performed.


JVM tests cover floating toolbar overlap on all four edges, side-by-side and stacked panes,
unmeasured surfaces, and mixed-interface preference round trips/fallbacks.

`SharedSplitToolbarDeviceTest` exercises the production activity, toolbar and native surfaces:

- one tools instance for notes and canvases across four edges, floating/docked and 25/50/75 ratios;
- mixed-layout switching, disabled format-exclusive controls, and shared/per-pane mode switching;
- retaining an existing note/canvas selection on return to that pane;
- stylus strokes on each surface, then shared Undo/Redo affecting only the active document;
- rotation and closing a pane while retaining the remaining editor's surface instance.

The device suite intentionally requires an isolated `.sharedsplit` application ID. Do not run it
against the community apps or a debug package containing personal documents. The validation
build can set this suffix and restrict ABIs using an external Gradle init script without changing
the upstream application's release ID or signing.

Commands (with the local Android SDK/JDK environment configured):

```sh
./gradlew :app:testDebugUnitTest
./gradlew -I tools/shared-split-validation.gradle -PsharedSplitAbi=arm64-v8a :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class com.xnotes.ui.SharedSplitToolbarDeviceTest \
  com.xnotes.sharedsplit.test/android.test.InstrumentationTestRunner
```

`prefs.shared_split_toolbars` defaults to true and stores false only when explicitly opted out.
The mixed interface remains saved while independent toolbars are selected. Both controls apply
live and persist immediately. Neither changes either saved toolbar layout.

This change does not add multiple independently positioned toolbars, a new document-tabs model,
cross-document clipboard behavior, or new drawing tools to either format.
