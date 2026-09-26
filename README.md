<h1 align="center">n-notes</h1>

<p align="center">
  A handwriting-first notebook for Android, built for pen and stylus. A customized fork of xnotes.
</p>

<p align="center">
  <a href="https://github.com/NeDDy3z/n-notes/releases/latest"><img src="https://img.shields.io/github/v/release/NeDDy3z/n-notes?style=flat-square&label=release&color=blue" alt="Release" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-orange?style=flat-square" alt="License" /></a>
  <img src="https://img.shields.io/badge/Android-8.0%2B-3ddc84?style=flat-square&logo=android&logoColor=white" alt="Android 8.0+" />
</p>

---

## Additions to the original xnotes

n-notes is a fork of [xnotes](https://github.com/shardulvs/xnotes-android). On top of it:

- **File import**: import images, txt, md, rtf, html, epub, docx, xlsx, and csv as notes.
- **Filen cloud sync**: back up and sync notes and settings to Filen, with an on-demand Sync now action in the sidebar (hold it to open the sync settings), or pull down on Home to sync.
- **Sidebar layout**: Recent, Home, Trash and a foldable Pinned group on top, the file tree in the middle, Sync now, Preferences and About pinned to the bottom; Files, Sync now and About can each be hidden in Preferences. The collapsed sidebar shows Sync now too.
- **Update check**: check GitHub for a newer n-notes release from Preferences.
- **Home swipes**: swipe right on Home to open the sidebar.
- **Create menu**: the new-note button is now a list to create a note, canvas, folder, or import a file.
- **Sort button**: moved sorting out into its own dedicated button.
- **Default page style**: set a default page style in settings, preselected when creating a note.
- **Mixed page orientation**: add a landscape page after a portrait one (or the other way round) from the page menu, for example under an imported PDF; PDF export keeps each page's orientation.
- **Tables**: add a table shape and edit it (columns, rows, and per-line sizing).
- **Image cropping**: crop a selected image with an interactive overlay.
- **X-Y graph shape**: an x-y coordinate axes shape that can be rotated.
- **X line shape**: a single number line with an arrowhead and tick marks.
- **Curve shape**: a smooth line through movable points; add or remove points from the selection three-dots menu.
- **Function curves**: swipe the shape picker to its second page for x^2, x^(1/2), ln x, e^x, sin x and cos x; retype the function (for example sin(2x)) and its x range with Edit function in the selection three-dots menu.
- **New shapes stay selected**: a freshly drawn shape or table stays selected, so it can be moved or resized straight away without drawing another one.
- **Plain text to equation**: select maths typed as plain text (lim x->0 sin(x)/x, int_0^1 x^2 dx, d/dx x^2, dy/dx, x^(1/2)) and tap the equation button to turn it into a formula; /equation converts the same way.
- **Move grip**: a small selection gets a pan-icon grip to drag it by, so tiny items can be moved without hitting the resize handles.
- **Finger tap to select**: with the select tool, a finger tap picks the item under it while a finger drag still pans.
- **Whole or partial selection**: the select and lasso tools can take only fully enclosed items or anything they touch.
- **Handwriting to text**: convert selected handwriting into an editable text box (Czech and English) from the selection three-dots menu; reopen it later with Edit in the same menu, with font, size, and colour controls docked at the bottom.
- **Handwriting to math**: an optional on-device add-on (downloaded and deleted in Preferences, under OCR) adds Convert to math to the selection three-dots menu, which turns handwritten formulas, fractions, exponents and integrals included, into a typeset formula; Edit in the same menu opens its LaTeX in a dialog with a live preview and a maths keyboard (fractions, powers, roots, derivatives, definite and indefinite integrals, sums, limits, functions and symbols).
- **Pen shape snapping toggle**: turn "snap held strokes to shapes" on or off directly from the pen settings popup.

## Versioning

Releases use the scheme `{xnotes version}-{nnotes version}`, for example `0.8.16-0.14`:

- `{xnotes version}` is the upstream [xnotes](https://github.com/shardulvs/xnotes-android) release this build is based on (`0.8.16`).
- `{nnotes version}` is the n-notes fork revision on top of that upstream base (`0.14`), bumped on every published fork build.

So a bump of the second number is a fork-only change; a bump of the first number means the fork was synced onto a newer upstream xnotes.

## Install

| Channel | |
|---|---|
| [GitHub Releases](https://github.com/NeDDy3z/n-notes/releases/latest) | Signed APK |

## Build from source

Requires **JDK 17** (the project pins Java 17):

```bash
git clone https://github.com/NeDDy3z/n-notes.git
cd n-notes
JAVA_HOME=/path/to/jdk-17 ./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

## License

Released under the MIT License, the same as upstream. See [LICENSE](LICENSE).

n-notes is a fork of [xnotes](https://github.com/shardulvs/xnotes-android) by Shardul Vikram Singh.
