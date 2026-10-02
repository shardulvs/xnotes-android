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

## Why this fork

Xnotes is a great app, and I'm grateful that the developer puts so much work into it and shares it openly. However, in my day to day use I noticed a few missing features and some details that bothered me. Above all, I really wanted some kind of cloud sync, so I made this fork. I do my best to keep it in sync with the original repository, and I almost always prefer the original developer's changes over mine, since they are much better implemented.

## Additions to the original xnotes

- **Filen cloud sync**: sync notes and settings to Filen from the sidebar or by pulling down on Home, with a synced trash and a banner for conflicting edits.
- **Settings backup**: export settings, fonts, templates and stickers to one file and import them on another device.
- **Handwriting to text**: turn selected handwriting (Czech, English) into an editable text box.
- **Handwriting to math**: an optional on-device add-on turns handwritten formulas into editable LaTeX.
- **Plain text to equation**: turn typed maths like `int_0^1 x^2 dx` into a formula.
- **Maths keyboard**: a dedicated keyboard for equations and the formula editor.
- **File import**: create notes from PDFs, images, txt, md, rtf, html, epub, docx, xlsx and csv.
- **Import and merge**: import a file into an existing note, merge notes, and add images or photos as pages.
- **Read-only reader**: open md, docx, pptx, xlsx, csv and pdf files as they are, or convert them to notes.
- **Reader mode**: a toolbar button that locks a note for reading, with only navigation, search and zoom left in the bar.
- **Find**: search text in the reader and Preferences.
- **Shape snapping**: held strokes snap to circles, squares, triangles, waves, parabolas, cubics, exponentials and logarithms, or become an editable curve; toggle it in the pen popup.
- **Tables**: a table shape with row and column bars for selecting, resizing, adding and removing.
- **Function curves**: x^n, roots, logs, exponentials, trig and inverse trig graphs with an editable formula and range.
- **Curve shape**: a smooth line through points you can add, move and remove.
- **Axes shapes**: rotatable x-y axes and a single number line.
- **Circles from the centre**: circles grow out from where the drag starts.
- **New shapes stay selected**: move or resize a shape right after drawing it.
- **Mixed page orientation**: mix portrait and landscape pages in one note, kept in PDF export.
- **Default page style**: pick any template (Cornell, checklist, planners and more) and its settings for new notes in Preferences or the new-note dialog, in portrait or landscape.
- **Hyperlinks**: add tappable links to canvas items and text.
- **Image cropping**: crop selected images.
- **Eyedropper**: pick a colour from anywhere on screen.
- **Whole or partial selection**: select only fully enclosed items or anything touched.
- **Finger tap to select**: a finger tap selects, a finger drag still pans.
- **Move grip**: drag small selections without hitting the resize handles.
- **Rotation snapping**: optionally snap rotation to 90 degrees.
- **Sidebar layout**: pinned notes, Sync now and optional sections in a reorganised sidebar.
- **Home swipes**: swipe right on Home to open the sidebar.
- **Hide dot files**: hide .obsidian, .git and other dot files from the explorer.
- **Update check**: check GitHub for a newer release from Preferences.
- ~~**Create menu**: the new-note button is now a list to create a note, canvas, folder, or import a file.~~
- ~~**Sort button**: moved sorting out into its own dedicated button.~~
- ~~**Find in notes**: search the text of a note.~~

Crossed-out items were later added to xnotes by its original developer.

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
