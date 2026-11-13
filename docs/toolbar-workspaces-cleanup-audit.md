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

## Important code that must **not** be removed as apparent leftovers

- `ToolbarPreviewReorderController` keeps the last valid hover target when a pointer crosses empty space, and holds a pending reorder until the workspace acknowledges it. Removing either behavior reintroduces observed flicker.
- `ToolbarDragSurface` retains a released move's offset until its anchor is acknowledged; it has a bounded fallback for rejected changes. A one-frame snapback regression guards this.
- Preview placement marks the released source for direct movement while only peer bars spring. A separate cross-edge test guards the TOP-to-RIGHT transition.
- Docking dimensions rotate when moving between perpendicular edges; saved requested sizes are not overwritten by viewport clamps.
- Floating and docked placement, pinned Start/End regions and the legacy single-bar migration paths are distinct responsibilities, not redundant implementations.
- The inspector's non-drag controls and the stacked PR boundaries remain useful for accessibility and review.

## Pass 2: proposed, not yet performed

1. Refactor `ToolbarPreferencesPane` (currently ~613 source lines) into a dedicated selection/command adapter, leaving composition independent of persistence and `Editor`. Preserve the exact workspace update path and primary identity.
2. Separate toolbar section drag presentation from palette and section editing in `ToolbarCustomizer` (currently ~696 lines). Consolidate shared geometry/hit testing through existing pure helpers; avoid creating tiny files with circular dependencies.
3. Review geometry layout planning under frequent slider changes with benchmarks before optimizing hot paths further. A cache is justified only when it avoids identical computations.
4. Add tests for landscape/portrait, sidebar open/closed, 1–3 simultaneous bars, floating/docked transitions, accessibility, split view, and real stylus gestures. Keep manual QA independent from automated touch injection.
5. Compare each stacked stage to its parent after changes. Do not force-push history or delete tests simply to reduce diff size.

## Validation and remaining caveats

- After the responsive-layout and conservative cleanup changes: **2,622 JVM tests (0 failures)**, a successful release lint, and Android Compose verification on a Redmi Pad 2. The full-device suite passed **29/29 Compose tests** after fixture stabilization; the incremental drag test also passed four consecutive standalone device runs. A Redmi Pad 2 screenshot with the navigation rail open showed all five geometry sliders spanning the appearance card.

- Device: Redmi Pad 2, Android 16, `com.xnotes.debug`, connected over ADB. The user previously reported that the flicker appeared gone after hardware review; this is encouraging, not proof of every timing/ROM configuration.
- Current tests: the inspector-width Compose regression, its existing responsive layout test, preview move/reorder instrumentation, full JVM and release lint should be rerun after every cleanup batch. Record actual outcomes in the PR description rather than claiming suites that have not been rerun.
- Risk: stateful Compose gesture code is particularly sensitive to recomposition and pointer timing; keep changes small, reversible and independently validated.
