# Toolbar workspace architecture (PR #431)

## Responsibilities and dependency direction

1. **Domain** (`core/tools/ToolbarLayout.kt`, `ToolbarWorkspace.kt`, `ToolbarDockLayout.kt`): immutable bar/section/entry/geometry structures, validation, and collision-free placement. It must not depend on Compose widgets, editor activities, or user preferences.
2. **Persistence** (`settings/ToolbarWorkspaceJson.kt`, `ToolbarWorkspaceTemplates.kt`): version-tolerant JSON, legacy migration and presets. `visual_order` is optional and does not change which bar is the primary bar. Do not persist runtime pixel coordinates or edit a preference merely because a screen is smaller.
3. **Runtime** (`ui/ToolbarDockWorkspace.kt`, `WorkspaceFloatingBars.kt`, `ToolbarFrame.kt`, `ToolbarSectionDistribution.kt`): resolve geometry into real visible bounds. Both XNote and XCanvas must consume the same workspace model and layout rules. Empty Start/End regions reserve no space; only the Center scrolls past fixed regions.
4. **Editor** (`ui/ToolbarPreferencesPane.kt`, `ToolbarBarSettings.kt`, `ToolbarAppearanceSettings.kt`, `ToolbarElementsEditor.kt`, `ToolbarWorkspaceDialogs.kt`, `ToolbarWorkspaceCreationPlanner.kt`): plan creation and duplication immutably, then mutate workspaces through explicit editor callbacks, not direct rendering-state writes. Preview and persisted workspace remain separate while dragging.
5. **Preview interaction** (`ui/ToolbarWorkspacePreview.kt`, `ToolbarPreviewReorderController.kt`, `ToolbarPreviewReorder.kt`): plan-render-hit-test loop. The current source bar follows the stylus, peer bars may animate, and the source lands directly at the committed destination. Its identity and stored order are distinct.

## Drag contract

- A **single** long-press gesture on the bar handles both moving and reordering. Never add a second drag handle or reorder-only button.
- Keep the pointer detector alive while layout bounds change; measurement callbacks must not reset the gesture key.
- On drag start, capture source and peer rectangles once. Candidate hit tests use these frozen rectangles, never positions that are animating.
- While dragging, keep the source at its original *layout slot* plus its pointer delta. Only peers animate to prospective slots.
- On drop, persist the reordered workspace, stop dragging and place the source **directly** at the new slot. Do not animate it from the original slot; otherwise there can be a one-frame flash. Canceling must not persist anything.
- A cross-edge drop is a **move** instead of a reorder. No-op drops must not dirty the workspace.

## Placement invariants

- Rendering order uses `geometry.visualOrder`, falling back to list index. `bars.first()` remains the primary toolbar.
- The dock and floating planners use shared visual-order sorting. Docks reserve their screen-space before floating bars calculate the remaining document extent.
- Extreme saved lengths/depths are clamped *in the plan*, not discarded from preferences.
- Every visible bar must have a positive-size placement fully inside the stage, and no two bars in a dock/floating allocation may overlap.
- A region with zero visible entries is not composed or measured; Start/End use content-sized space, with Center taking the remainder.

## Verification and performance

- Run `:app:testDebugUnitTest`, `:app:assembleDebugAndroidTest` and `:app:lintVitalRelease` using the project Gradle toolchain and Android SDK (recent Redmi QA used Java 21).
- Run `ToolbarTitleAndPreviewReorderComposeTest`, `ToolbarMultiBarComposeTest`, `ToolbarDockComposeTest`, `ToolbarEmptyRegionComposeTest`, `ToolbarBarsBalancedComposeTest` on `Small_Tablet` at 1920×1200, 1400×1000, 1600×720.
- Drag regression must check the bar's **visible bounds during movement and on multiple post-drop frames**; testing final `visual_order` alone misses one-frame flicker.
- For incremental instrumented pointer tests, inject gestures against the **stationary preview stage**, not the dragged bar. A moving injection target can shift the local pointer origin between successive test calls.
- Keep the dock/floating plans memoized while workspace and viewport are unchanged. `onGloballyPositioned` for hit boxes must not write Compose state for every animation frame.
- Before requesting upstream merge: manually confirm with a stylus on Xiaomi and, if available, the reporting OnePlus device. Automated green is not proof of vendor-ROM drag animation correctness.

## Proposed review order

Domain → Persistence → Runtime → Editor core → Dock/region polish → Preview interactions/UX. The sequence is maintained as draft stacked PRs in the fork; the upstream PR remains a draft integration reference until maintainers agree on an upstream submission sequence.

## Maintenance

See [toolbar-workspaces-cleanup-audit.md](toolbar-workspaces-cleanup-audit.md) for verified dead-code removal, responsive sizing rationale, and the next safe modularization tasks. Preserve the optimistic drag transaction and the direct source placement until dedicated regression tests demonstrate a replacement is equivalent.

## Current upstream compatibility (0.9.9)

The integration review branch merges upstream `master` at `0e73e160`. Keep the Android test runner while accepting upstream version code 70. The upstream editor-retirement save lifecycle, ZIP stamp/check logic and current menu/tool-width behavior remain upstream-owned; toolbar customization must not replace those implementation paths. Section picker availability is memoized per immutable toolbar layout and available-item set; repeatable Colors and Spacer entries are intentionally exempt from one-instance restrictions.
