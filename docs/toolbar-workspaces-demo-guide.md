# PR #431 — updated Redmi Pad 2 demo recording

A short uncut or lightly trimmed capture is preferable to a montage: reviewers need to see that **the toolbar stays under the stylus throughout the drag**, not only at the final position.

Suggested recording, landscape, roughly 60–120 seconds:

1. Open **Toolbar workspaces**; show the XNote/XCanvas tabs and the live preview.
2. Create a second toolbar, then duplicate or remove it to demonstrate the workspace actions.
3. **Long-press and move one floating bar continuously from TOP to RIGHT** (or LEFT to BOTTOM), showing the preview throughout the gesture and after release. This specifically demonstrates the fixed cross-edge preview flicker.
5. Reorder two bars within the same edge/lane and show the live destination preview.
6. Show pinned **Start / Center / End**, including collapsing empty regions and a floating bar beside a docked one.
7. Collapse and expand the left navigation rail to show that the five equally sized controls (**Length, Thickness, Buttons, Padding, Spacing**) adapt without clipping.
8. Switch from XNote to XCanvas; optionally show workspace preset save/import/export and that changes persist when reopening the editor.

Before sharing: hide or remove any private note content; leave touch indicators visible if possible; avoid zooming the capture or masking the pointer while dragging. A short real-device clip is more valuable than screenshots of the end state.

**Where to add it:** edit the integration [PR #431](https://github.com/shardulvs/xnotes-android/pull/431), upload the video via the GitHub Markdown editor (or its conversation), then replace the *Demo video pending* line in the PR body with the upload URL. If the URL is provided to the maintainer/reviewer, it can also be added to the [fork stage #9](https://github.com/wesleydanieell/xnotes-android/pull/9).
