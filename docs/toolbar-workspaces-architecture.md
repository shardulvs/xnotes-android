# Toolbar workspace architecture (PR #431)

## Responsibilities and dependency direction

1. **Domain** (`core/tools/ToolbarLayout.kt`, `ToolbarWorkspace.kt`, `ToolbarDockLayout.kt`): immutable bar/section/entry/geometry structures, validation, and collision-free placement. It must not depend on Compose widgets, editor activities, or user preferences.
2. **Persistence** (`settings/ToolbarWorkspaceJson.kt`, `ToolbarWorkspaceTemplates.kt`): version-tolerant JSON, legacy migration and presets. `visual_order` is optional and does not change which bar is the primary bar. Do not persist runtime pixel coordinates or edit a preference merely because a screen is smaller.
3. **Runtime** (`ui/ToolbarDockWorkspace.kt`, `WorkspaceFloatingBars.kt`, `ToolbarFrame.kt`, `ToolbarSectionDistribution.kt`): resolve geometry into real visible bounds. Both XNote and XCanvas must consume the same workspace model and layout rules. Empty Start/End regions reserve no space; only the Center scrolls past fixed regions.
4. **Editor** (`ui/ToolbarPreferencesPane.kt`, `ToolbarBarSettings.kt`, `ToolbarAppearanceSettings.kt`, `ToolbarElementsEditor.kt`, `ToolbarWorkspaceDialogs.kt`, `ToolbarWorkspaceCreationPlanner.kt`): plan creation and duplication immutably, then mutate workspaces through explicit editor callbacks, not direct rendering-state writes. Preview and persisted workspace remain separate while dragging.
5. **Preview interaction** (`ui/ToolbarWorkspacePreview.kt`, `ToolbarWorkspaceReorderController.kt`, `ToolbarPreviewReorder.kt`): plan-render-hit-test loop. The current source bar follows the stylus, peer bars may animate, and the source lands directly at the committed destination. Its identity and stored order are distinct.

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

## Component boundaries after PR #431 cleanup

- `ToolbarCustomizer.kt`: section/region composition, drop targets, and callback routing (no visual row/section implementations).
- `ToolbarSectionEditorCards.kt`: section card scaffold, long-press grip, add-section controls, and insertion guides.
- `ToolbarItemEditorRows.kt`: item rows, item/section drag-ghost visuals, searchable tool picker, and icon mapping.
- `ToolbarWorkspaceSelector.kt`: independent presentation of context tabs, bar selection and bar-action menus; actual editor mutations and constraints remain in `ToolbarPreferencesPane.kt`.
- `ToolbarWorkspaceCreationPlanner.kt`: immutable add/duplicate planning; no UI state or persistent file mutations.

Reviewer builds are opt-in (`./gradlew :app:assembleRelease -PtoolbarReviewBuild=true`), use Android package `com.xnotes.pr431review` and require a local signing configuration. Normal upstream builds are not changed by the flag's absence. See [the demo guide](toolbar-workspaces-demo-guide.md) for the new real-stylus demonstration requested before review.

## One placement contract across editor, preview, and runtime

A toolbar placement is now defined by the **edge**, **alignment** (START/CENTER/END) and **preferred lane**. `ToolbarAnchor` alone cannot encode alignment on a vertical edge. `ToolbarAnchorResolver.nearestPlacement` resolves an edge + alignment from drag coordinates and preserves the prior edge when corners are ambiguous. The `ToolbarDragSurface` optimistic commit waits for *both* `anchor` and `dockAlignment`, including moves from vertical START to CENTER that do not change the anchor value. `Editor.moveToolbarBar` persists both values while retaining Docked/Floating mode; that mode is changed only in the inspector.

The twelve visible position-map stops represent four edges × three alignments; they control the same fields as a completed drag. `dockLane` remains a separate preferred lane, not a fourth positional axis inferred from the pointer. The layout planner can redistribute to a free lane if the preferred lane has insufficient capacity. Test the re-centering scenario from LEFT/START to LEFT/CENTER on both the preview and the actual toolbar before accepting the PR.

## Runtime bar gestures and mixed-depth lane alignment

`ToolbarWorkspaceReorderController` is shared by the editor preview and XNote/XCanvas toolbars. Long-press and drag uses the same frozen peer-hitboxes and pending commit acknowledgement, while bars persist visual order without changing their stable IDs. `ToolbarLiveReorderBar.kt` provides the shared Compose surface for floating and docked peers. Reordering only swaps bars inside the same rendered edge, lane and alignment; cross-edge movement continues to use the normal anchor resolver.

The dock/floating packer allocates a lane's **maximum** thickness. `ToolbarDockEdge.toolbarContentAlignment()` anchors each shorter bar to the actual screen edge *within* that allocation, matching the editor preview and preventing one-row toolbars from appearing pushed inward beside two-row siblings. Compose regression coverage uses actual `ToolbarFrame`/`ToolbarSections` layouts as well as fixed-size two-row/one-row cases.

When the primary toolbar still uses the legacy `ToolbarLook.floating` flag and a second visible bar is added, `ToolbarWorkspace.usesSharedFloatingPrimary()` routes the primary into the same live floating container as its peers. The old independent legacy overlay must be suppressed to avoid duplicates or nested drag surfaces; a solitary legacy primary keeps its original rendering and interactions.

## Predicted drop targets and snap settlement

`ToolbarDropIndicator.kt` projects a tentative move through the *same geometry update* used by `Editor.moveToolbarBar`, then runs the existing dock/floating/preview layout planners. It draws a lightweight, non-interactive target outline at the **actual bar footprint**, accounting for taller peers in a shared lane. The gesture owns no persistence during hover.

`ToolbarDragSurface` shares the projected target with the overlay and, on release, animates its current translation **from the pen's release position to that target** (190 ms). Only after landing does it commit the anchor/alignment to the editor. Parent acknowledgement discards the translation atomically; peer reorder still takes precedence and retains its own animation. A missing projected slot uses the previous safe fallback and does not advertise an unavailable destination.

## Hysteresis, orientation transitions and preparatory peer movement

`ToolbarAnchorResolver.stablePlacement` adds a ~36 physical-pixel edge deadband and a 5.5%-of-edge alignment deadband during dragging. The same **stable** candidate is used for the outline and drop commit, preventing a late change from the position that was visibly promised to the user; explicit toolbar position controls remain exact and unchanged.

Hovering over a new edge/alignment now projects the **entire workspace** through the normal planner, rather than showing only a ghost. Animated peers open or reclaim space while the source continues to track the pointer in its **original slot**; the candidate workspace is not saved until drop settlement. The reorder controller clears a sticky same-lane target only when the pointer moves to a different edge/alignment; it retains the projected layout throughout the 220-ms settling movement and clears it only once the saved geometry acknowledges the new anchor and alignment. This avoids both hidden indicators after accidental reorder gestures and a return-to-old-slot flash during release.

The indicator and the toolbar's own container smoothly resize width and height when changing between horizontal and vertical. The toolbar's icons remain upright; no rotation or opacity transition is applied. During same-lane reordering, the indicator now shows the source bar's **proposed insertion slot** from the exact provisional reorder plan used to animate the other bars; changing to a different edge still selects the placement indicator instead.

## Concurrent orientation resize and drop settlement

`ToolbarDragSurface` emits `onSettleTarget` **before the first 220-ms translation frame**, then clears it after the new anchor/alignment is acknowledged or the gesture cancels. The live bar derives its pending width/height from the same projected drop slot and uses the same 220-ms `FastOutSlowInEasing` as the movement; the position is persisted only after both complete. In the settings preview, `PreviewBar` begins its existing `animateContentSize` when the projected orientation is announced, not after the final move commit; its outer hitbox stays fixed until the committed placement to prevent reintroducing the old-edge snapback.
