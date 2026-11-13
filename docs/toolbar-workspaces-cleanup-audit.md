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

## Important code that must **not** be removed as apparent leftovers

- `ToolbarPreviewReorderController` keeps the last valid hover target when a pointer crosses empty space, and holds a pending reorder until the workspace acknowledges it. Removing either behavior reintroduces observed flicker.
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
