# Review and refactoring plan for toolbar workspace customization

## Stage boundaries

| Stage | Commit checkpoint | Main responsibility |
| --- | --- | --- |
| Model | `3445be71` | Layout, workspace domain and geometry invariants |
| Persistence | `4e04aaa2` | JSON and compatibility/migration |
| Runtime | `086030eb` | Placement and rendering of configurable bars |
| Editor core | `23730c35` | Dedicated workspace editor, legacy fixes and localization |
| Dock & regions | `d4e4d219` | Multiple bars, pinned Start/End and safe resize |
| Preview & UX | Final HEAD | Drag reordering, inspector, titles, visual regression fixes |

These are **stacked review diffs**. Each PR must have exactly the preceding stage as its base. Never compare all stages to upstream `master` simultaneously and describe them as independent. Only the first stage has no feature dependency. The integration PR (#431) remains open for full-workspace testing.

## Refactoring priorities

1. **Stability first:** no release/merge until a frame-based drag test and a manual stylus run verify there is no snapback.
2. **Preview controller:** keep gesture transaction and frozen hit-test state outside composables. Do not recompute dock/floating plans inside the per-bar loop.
3. **Editor state:** decouple selection and workspace mutation from `ToolbarPreferencesPane.kt` in small, test-covered extractions. Avoid callbacks that silently change the primary-bar identity.
4. **Custom item editor:** split the large `ToolbarCustomizer.kt` by selection/drop planning, palette elements and canvas rendering; do not simply rearrange source lines between files.
5. **Guardrails:** no unused options, no duplicate locale strings, no hard-coded UI text, no logging in hot pointer paths, and no new persistent properties without a backward-compatible JSON test.

## Acceptance checks

- Compile each stage against its parent and run the relevant tests before publishing a stack revision.
- The final integration branch must pass full JVM + Compose + release lint, on multiple screen sizes.
- Every draft PR must explain its base dependency, scope, test status and remaining work.
- Any outstanding vendor ROM, split-view or stylus issues belong in the PR checklist; do not equate emulator tests with complete hardware verification.
