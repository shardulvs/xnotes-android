# PR #431 — updated Redmi Pad 2 demo recording

A short uncut or lightly trimmed capture is preferable to a montage: reviewers need to see that **the toolbar stays under the stylus throughout the drag**, not only at the final position.

Suggested recording, landscape, roughly 60–120 seconds:

1. Open **Toolbar workspaces**; show the XNote/XCanvas tabs and the live preview.
2. Create a second toolbar, then duplicate or remove it to demonstrate the workspace actions.
3. **Long-press and move a floating bar continuously from TOP to RIGHT** (or LEFT to BOTTOM), showing the live preview while the stylus is down and after release.
4. **Regression demonstration:** select **Left · Start** in the new 12-position map. Drag the bar toward the middle of the *same left edge* and show that the selected position changes to **Left · Center**. Tap **Left · End** on the map and show the bar reposition. Test both floating and docked modes if useful.
5. Reorder two bars along the same edge/lane, showing their destination preview before release.
6. Show pinned **Start / Center / End** groups, empty-region collapse, and a floating bar beside a docked one.
7. Collapse/expand the left navigation rail to show the five equal-width size controls adapt without clipping.
8. Switch between XNote and XCanvas; optionally show preset save/import/export and reopening the editor to verify persistence.

Before sharing: hide or remove any private note content; leave touch indicators visible if possible; avoid zooming the capture or masking the pointer while dragging. A short real-device clip is more valuable than screenshots of the end state.

**Where to add it:** edit the integration [PR #431](https://github.com/shardulvs/xnotes-android/pull/431), upload the video via the GitHub Markdown editor (or its conversation), then replace the *Demo video pending* line in the PR body with the upload URL. If the URL is provided to the maintainer/reviewer, it can also be added to the [fork stage #9](https://github.com/wesleydanieell/xnotes-android/pull/9).
