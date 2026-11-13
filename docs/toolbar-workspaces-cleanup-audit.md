# Toolbar workspace cleanup audit — PR #431

## Scope and policy

This document tracks **changes in the final file tree**, not the number of historical attempts. A commit that did not fix a bug by itself is not evidence that every line it introduced is unused. Rewriting the public PR's commit history to erase those attempts is **not required** and can invalidate the dependent review stack. Each deletion must have a reproducible reference search, an affected test, and a functional justification.

The six dependent draft PRs in the contributor fork are review checkpoints, not six standalone merges. The original PR #431 remains the integration reference.

## Pass 1: verified cleanup

| Area | Finding | Change / reason |
| --- | --- | --- |
| Inspector sizing | The inner geometry grid switched to two columns at 350dp, even though the overall inspector was already split into placement and appearance cards beside the application sidebar. Five sliders then filled only half of the appearance card. | Require at least 640dp **inside the geometry card** before using two columns; otherwise each measure fills its full row. Tested at representative sidebar-open and stacked widths. |
| Geometry measurement | Responsive controls lacked a stable per-measure test tag. | Expose the geometry measure semantics tag and add a viewport-width regression to Compose tests; ignore only controls fully off-screen in the test harness. |
| Instrumentation reliability | Some frame-by-frame tests injected pointers relative to a **moving** toolbar. On the Redmi this changed the injection coordinate frame between calls and produced occasional artificial backward jumps. | Add a stable preview-stage test tag, inject the incremental gesture relative to this root, and assert source positions after each frame. The previously intermittent incremental test passed **4/4 repeated Redmi runs** after this fixture correction. |
| Dock lane feedback | `ToolbarBarsInspector` rebuilt both dock/floating layout plans during unrelated recompositions. | Memoize the plan using workspace, look, dimensions and color count. Preserve dock/floating semantics, including the primary bar. |
| Preview render | `placement` was known non-null, yet the renderer had obsolete nullable fallback expressions and computed stage dimensions solely for those branches. | Use the non-null placement dimensions directly. The planner is responsible for rejecting unavailable positions. |
| Section grid | `toolbarCrossCells()` had **zero callers** across app main, JVM tests and Android instrumentation. | Delete the unused helper and its now-unused import. The used `section.crossCells` property remains unchanged. |

No persistence schema, migration, gesture transaction, primary toolbar identity or section layout semantics were modified in this pass.

## Pass 2: compact expanded editor and command isolation

- The earlier 640dp rule solved the half-width-slider bug with the left navigation rail **open**, but left excessively long slider tracks with the icon-only rail. The geometry card now uses a **460dp internal breakpoint** and groups five equal-width sliders by purpose: **Dimensions** (Length, Thickness, Buttons) and **Inner spacing** (Padding, Spacing). Columns cap at 300dp when side-by-side and take the card width when stacked. This avoids an oversized Length slider, an orphan final row and cross-row mismatches. Uniform header heights keep tracks aligned whether a value is numeric or automatic. The available width calculation reserves a few dp for pixel rounding. The Redmi Pad 2 editor was visually inspected in icon-only mode; both expanded and collapsed navigation states were covered by instrumentation.
- Added an instrumented layout test for the icon-only rail, maximized inspector, expanded rail and narrow stacked layout. The new grid is measured through Compose layout bounds rather than assuming the screen always equals the usable editor width.
- Extracted **immutable add and duplicate planners** into `ui/ToolbarWorkspaceCreationPlanner.kt`. ID assignment, new compact color-palette layout construction and placement rejection no longer live inside Compose menu callbacks; `ToolbarPreferencesPane` only translates planned success/failure to an editor update and user-visible error. Retained the distinct legacy behavior of new floating-bar placement vs full-workspace duplicate validation; this refactor is *not* a new placement policy.
- Added JVM tests for ID collision, primary-bar preservation, context-specific palette visibility, duplicate visual-order normalization and missing-source handling. No storage schema, existing toolbar identity or gesture code was changed.

## Pass 3: upstream 0.9.9 and section-picker optimization (2026-11-13)

- Merged the actual upstream `master` (`0e73e160`) into the integration PR without history rewriting. This incorporates upstream 0.9.9 lifecycle/save-safety changes (`RetiredEditors`, `CopyOver`, ZIP change detection), updated tool-width snapping and PDF filter presentation, plus its resources, tests and launcher updates. The only textual merge conflict was in `app/build.gradle.kts`: retain `androidx.test.runner.AndroidJUnitRunner`, and use upstream `versionCode = 70` / `versionName = "0.9.9"`. Compilation and both test suites validate semantic compatibility beyond the clean textual merge.
- The tool picker formerly scanned *every section and entry once per available item, once per lane*, potentially during each pointer-driven recomposition of `ToolbarCustomizerBody`. New pure `toolbarAddableItems` computes the set of visible unique items once and excludes them in a single pass; Colors and Spacers remain repeatable, hidden entries remain addable, and order follows the original input set. `remember(layout, availableItems)` shares that result across all lane pickers. Dedicated JVM tests cover these invariants.
- The previously intermittent instrumented vertical preview title assertion remains strict; on failure it now reports the title, bar and preview stage rectangles. A prior run intermittently reported invisible titles, so these added diagnostics are **not** presented as a production fix.
- Verification on the **merged and optimized tree**: `:app:assembleDebug`, `:app:assembleDebugAndroidTest`, `:app:testDebugUnitTest` (**2,646 tests, no failures**) and `:app:lintVitalRelease` all passed; 30/30 Compose instrumentation tests passed on the connected Redmi Pad 2. Upstream/native builds were compiled for the supported ABIs. Tests simulate touch input, not a manual pen session.
- Six draft review stages (#4–#9) continue to carry only their incremental feature changes. Their original bases were updated locally via forward merges; the final review-stage tree is checked against the integration tree before the branches are published. No force pushes or historical commit deletions.

## Pass 4: editor component boundaries and isolated reviewer builds (2026-11-13)

- `ToolbarCustomizer.kt` was previously ~697 lines and owned section card layout, item rows, drag gesture surfaces, ghost painting, and icon mapping in one file. Its **main body is now ~229 lines**, while `ToolbarSectionEditorCards.kt` owns section headers, drag strips, add-section control and insertion marks, and `ToolbarItemEditorRows.kt` owns chips, drag ghosts, item menus and icon mapping. This was a **source relocation**, not an algorithm change: pointer callbacks, `rememberUpdatedState`, bounds capture, and ghost drawing retain the original logic.
- `ToolbarPreferencesPane.kt` previously ~588 lines; `ToolbarWorkspaceSelector.kt` now owns the XNote/XCanvas tabs, chosen-bar menu and create/duplicate/remove/copy controls as a pure presentation component. The PreferencesPane still validates placement and owns editor updates, persistence and workspaces; the selector only dispatches callbacks and manages popup-open state. Isolated Compose tests guard selection/context and disallow deleting the primary bar.
- An opt-in `-PtoolbarReviewBuild=true` Gradle property builds a signed review APK under **`com.xnotes.pr431review`**, with a review suffix in the version name. Normal releases still use their original Android package and signing behavior. This makes a reviewer build installable alongside the official XNotes and the debug app. The signing key and `keystore.properties` remain outside Git.
- A short Redmi Pad 2 demo plan is maintained in [toolbar-workspaces-demo-guide.md](toolbar-workspaces-demo-guide.md); reviewers need to see the **continuous cross-edge drag**, not only the toolbar after release. The PR description must not imply the video exists until the new recording is actually uploaded.

## Pass 5: bidirectional placement and side-alignment fix (2026-11-13)

- Reproduced the report: LEFT/RIGHT edges only have `CENTER_LEFT`/`CENTER_RIGHT` anchors, while START/CENTER/END resides in `dockAlignment`. Old drags changed the anchor without resetting a stale alignment. Dragging a left-side bar back to the center could therefore leave it at START.
- `ToolbarAnchorResolver.nearestPlacement` now computes **edge and alignment together**, using all twelve target positions; corner ties prefer the current edge. `ToolbarDragSurface` waits for both values to be acknowledged by the editor so an alignment-only move cannot flash back to the previous slot. The XNote/XCanvas runtime and preview propagate the same values.
- Dragging a bar no longer implicitly flips Docked/Floating mode. Moving an existing docked bar to another edge preserves docking, while moving a floating bar preserves floating; the placement mode has its own explicit setting.
- The old 8-arrow grid plus three separate alignment toggle chips have been replaced with one **12-stop position map** (four edges × Start/Center/End). Its stops have accessible descriptions and 44dp click areas. Lane 1–3 selection remains separate and is clearly labelled **Preferred lane**, since actual packing may redistribute a lane when it is occupied.
- New JVM tests cover all twelve placements, left-side recentering, corner ties and invalid dimensions. Compose tests verify the selected stop follows its geometry and an actual LEFT/START → LEFT/CENTER preview gesture. Rechecked **2,650 JVM tests and 35 Compose tests** (Small_Tablet at 1920×1200, 240dpi), plus `lintVitalRelease`. Real stylus/OEM QA remains open.

## Important code that must **not** be removed as apparent leftovers

- `ToolbarWorkspaceReorderController` keeps the last valid hover target when a pointer crosses empty space, and holds a pending reorder until the workspace acknowledges it. Removing either behavior reintroduces observed flicker.
- `ToolbarDragSurface` retains a released move's offset until its anchor is acknowledged; it has a bounded fallback for rejected changes. A one-frame snapback regression guards this.
- Preview placement marks the released source for direct movement while only peer bars spring. A separate cross-edge test guards the TOP-to-RIGHT transition.
- Docking dimensions rotate when moving between perpendicular edges; saved requested sizes are not overwritten by viewport clamps.
- Floating and docked placement, pinned Start/End regions and the legacy single-bar migration paths are distinct responsibilities, not redundant implementations.
- The inspector's non-drag controls and the stacked PR boundaries remain useful for accessibility and review.

## Further refactoring (not yet performed)

1. Continue separating **editor state mutations/transactions** from `ToolbarPreferencesPane` (now ~523 source lines); the XNote/XCanvas selector and bar action menus have already moved to a presentational component. Do not redirect persistence through unstable state snapshots.
2. Benchmark geometry planning and section hit-testing before more performance changes; the visuals and long-press strips have already moved out of `ToolbarCustomizer` (now ~229 lines), but gesture transitions still require end-to-end tests.
3. Add manual QA for landscape/portrait, floating/docked moves, multi-bar pinned-region interactions, split view, and real Xiaomi Smart Pen gestures. The existing Compose suite injects touch events, not stylus hardware input.
4. Keep tests, release notes and the modular PR stack in sync. No force pushes or history removal solely to reduce the commit count.

## Current validation snapshot (refactor pass 4)

- `2,646/2,646` JVM tests passed with JVM numeric formatting fixed to English (`JAVA_TOOL_OPTIONS='-Duser.language=en -Duser.country=US'`); without that explicit locale, an unrelated existing `SteadyTraceTest` expects dot decimals and fails on the Brazilian host's comma formatting. This was an environment-dependent assertion, not a toolbar regression.
- `32/32` Compose tests passed on the `Small_Tablet` emulator at 1920×1200 and **240 dpi**, including two new isolated selector-menu tests. The wide-inspector geometry test intentionally needs sufficient available dp to form two columns; at the emulator's default 320 dpi it correctly stacks into one column instead.
- The new preview/demo APK is isolated under a distinct review application ID. Do not install it over the official package or mistake its signing certificate for upstream's official release key.

## Validation and remaining caveats

- After the responsive-layout and conservative cleanup changes: **2,622 JVM tests (0 failures)**, a successful release lint, and Android Compose verification on a Redmi Pad 2. The full-device suite passed **29/29 Compose tests** after fixture stabilization; the incremental drag test also passed four consecutive standalone device runs. A Redmi Pad 2 screenshot with the navigation rail open showed all five geometry sliders spanning the appearance card.

- Device: Redmi Pad 2, Android 16, `com.xnotes.debug`, connected over ADB. The user previously reported that the flicker appeared gone after hardware review; this is encouraging, not proof of every timing/ROM configuration.
- Current tests: the inspector-width Compose regression, its existing responsive layout test, preview move/reorder instrumentation, full JVM and release lint should be rerun after every cleanup batch. Record actual outcomes in the PR description rather than claiming suites that have not been rerun.
- Risk: stateful Compose gesture code is particularly sensitive to recomposition and pointer timing; keep changes small, reversible and independently validated.

## Pass 6: live gestures and shared lane-depth alignment (2026-11-13)

- XNote and XCanvas now support **the same long-press reorder of same-lane peer toolbars** as the preview, whether floating or docked. Runtime layouts reuse the shared reorder controller, display the moved source under the pointer, animate peers only, and persist visual order with stable bar IDs. Prevent nested drag handling on the primary bar when multi-dock rendering is active.
- Fixed the live renderer's top-left placement inside a lane whose depth was reserved for a taller/two-row peer. The common edge-alignment helper now places every shorter bar flush to its appropriate physical edge (notably BOTTOM). Both floating and docked runtime use it, matching the preview.
- Tests cover real `ToolbarFrame` one-row/two-row layouts, explicit-height mismatched peers, floating/docked reorder gestures and the pre-existing preview drag regressions. Hardware stylus, split-screen and orientation still need manual visual QA.

- Additional legacy-primary parity fix: when two floating toolbars are visible and the primary still uses `ToolbarLook.floating`, both now use the shared floating layer. This prevents the primary from being separately aligned/handled outside the lane and avoids nested drag handlers. A lone legacy primary retains the old rendering path.

## Pass 7: guided drag/drop (2026-11-13)

- Added a shared projected-slot function for preview, live docked bars and live floating bars. A translucent, accent-coloured outline previews the destination while dragging across edges/alignments; subsequent iterations also show the source insertion slot during same-lane reorder while peers animate.
- Release translation now eases to the projected root-space target **before** persisting the move, preserving the existing pending-placement acknowledgement and avoiding a flash through the original slot.
- Tested target-plan equivalence, legacy floating-primary parity, one-vs-two-row bottom alignment, continued preview reorder, an actual live-gesture indicator and monotonic frame-by-frame movement without a reverse snapback.

## Pass 8: stable cross-edge docking and peer pre-layout (2026-11-13)

- Gesture target hysteresis (36 px edge, 5.5% alignment) reduces indicator jitter near boundaries. Snap and indicator use the identical stable target.
- Project complete docked, floating and preview layouts while dragging. Peers animate away from, or into, the planned insertion slot before release; the dragged source stays tied to its pointer and stable ID.
- Crossing to a new edge or alignment cancels a sticky reorder target, revealing the drop indicator even if the gesture first hovered over a sibling. Empty gaps on the *same* edge/alignment still preserve the existing reorder preview.
- Keep peer projection alive during snap settlement and clear only after workspace acknowledgment to avoid a second old/new flash. Orientation changes animate only width/height of the toolbar's container and indicator, without rotating or fading its contents.
- Test coverage includes hysteresis thresholds, preview-state persistence, accidental-reorder cross-edge recovery and live peer movement before the persisted commit.

## Pass 9: consistent insertion indicators and resize-only orientation (2026-11-13)

- During floating, docked, and editor-preview reordering, draw the existing drop outline at the moving bar's destination slot in the provisional reordered plan. The peer animation and visual target share the same planner; no new persistent state, special drag mode, or duplicate gesture implementation.
- Remove whole-bar rotation and alpha transitions. Animate the bar's width and height instead when switching orientation so text and tool icons stay upright.
- Test insertion-indicator visibility *before* releasing the pointer, no premature reorder commit, real dock/floating cases, the preview gesture, and equality between projected planner and indicator geometry.

## Pass 10: synchronized move and resize (2026-11-13)

- Begin the orientation width/height transition on pointer release together with the 220-ms position tween, rather than waiting for the committed anchor update. No rotation, scaling of icons, or alpha effect.
- Preserve stable source hitboxes and wait to persist placement until translation and size settlement have completed; the preview changes its internal layout while retaining its outer drag coordinates.
- Added instrumented timing regression to assert that width, height and horizontal position all progress while the committed workspace still has its old anchor.
