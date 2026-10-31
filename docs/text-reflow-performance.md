# Text reflow performance

## Current implementation

The infinite canvas shares the last native StaticLayout between measurement and
outline extraction, keyed by the integer width actually used by Android. Line
outlines retain the original text and range as their shaping context, including
Arabic joins, combining marks and ligatures. Unchanged ranges reuse their contours.

Android 14+ glyph outlines use PathIterator to read actual curves. Earlier devices
and rational conic paths use native Path.approximate adaptive flattening. The old
PathMeasure sampler is retained only as an instrumentation benchmark reference.

Glyph contour geometry is cached independently of position and line membership.
Relative coordinates are rounded to 1/1024 content pixel to remove native float
placement noise. Cached signed triangle fans need no ear clipping or hole bridging.
The renderer accumulates front/back triangle winding in the stencil and covers
nonzero pixels once, preserving holes and overlaps. CPU assembly writes directly
to numeric vertex/index buffers and produces a single text mesh part.

Cache budgets: 200,000 weighted source characters / contour points per
AndroidTextMeasurer, and an estimated 8 MiB for shared canonical contour geometry.
Oversized entries are used without retention; eviction is synchronized LRU.

Reflow runs on a dedicated worker against detached geometry snapshots, with one
pending request per item. Completed previews with the same immutable label can
publish in increasing revision order while input continues. New content/style,
removal, hiding the editor label or document reset invalidates older results.

Moving or rotating a rendered closed shape without changing its layout reuses its
mesh immediately. That update is queued before the selection lift is released,
preventing the old geometry from flashing at the original position while a worker
finishes. Cache records are dropped on removal and document rebuild.

## Validation

2,288 JVM tests passed, including cache budgets, stale result rejection, preview
progress, cancellation, document reset, and immediate rigid mesh placement.
Three Android instrumentation tests passed on the connected Redmi Pad 2:

- Local fixture: a private copy of the user's actual 1,266-character monospace note.
- Offscreen OpenGL rendering compared against Android native text paths for mono
  and sans fonts, including holes, repeated letters, ligatures and mixed Hebrew.
  Interior pixels must match within 0.5%, allowing a one-pixel boundary band at 2x
  resolution for rasterizer differences; binary covered area must match within 3%.
- Cached outlines compared with native paths for accents, combining marks,
  ligatures, Arabic and mixed Hebrew/Latin, plus unchanged-line identity reuse.

The original note is not included in the repository and is never modified by the
fixture test. Supply a copy in the debug app's cache as `reflow-fixture.xcanvas` to
run that optional measurement; without it the test logs that it was skipped.

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --offline
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class 'com.xnotes.platform.TextReflowTest#testDeviceFixtureReflowIfPresent,com.xnotes.platform.TextReflowTest#testWindingMeshMatchesNativeTextCoverage,com.xnotes.platform.TextReflowTest#testReflowMatchesNativeOutlinesAndReusesLines' com.xnotes.debug.test/android.test.InstrumentationTestRunner
adb logcat -d -s XnotesReflow:I
```

The tests use the platform instrumentation runner and optional SDK test libraries.
`connectedDebugAndroidTest` also works when Gradle UTP dependencies are available.

## Device results (2026-10-23)

Device model `25040RP0AG`, debug build, 24 different widths on a copy of the real
note. Times include height layout, outline preparation, text assembly, host shape
meshing and final bounds. The benchmark also probes each stage separately; those
probes add some overhead. GPU upload, selection chrome and complete frame times
are excluded. These are generation times, not FPS.

| Real note, current implementation | Time |
| --- | ---: |
| Median generation | 57.03 ms |
| p95 generation | 87.70 ms |
| Maximum including first use | 302.38 ms |

Native layout itself usually took 1–3 ms. Earlier versions were confirmed by the
Android ANR trace to be ear-clipping glyphs on the main input thread for over five
seconds. The first line-cache-only benchmark used a synthetic repeated sans text
and was insufficient to validate this real monospace case.

Generation still exceeds a 16.7 ms frame budget on this debug build. It is now off
the input thread and previews can progress during continuous movement rather
than waiting for all input to stop. Release profiling and full gesture/frame timing
are still required before claiming 60 FPS or 120 FPS.

Trace sections: `Xnotes.text.layout`, `Xnotes.text.contours`, `Xnotes.text.mesh`
(background generation), and `Xnotes.item.mesh` (other synchronous item meshes).

Platform references:
- https://developer.android.com/reference/android/graphics/PathIterator
- https://developer.android.com/reference/android/graphics/Path#approximate(float)

## Native bitmap preview for imported editable text (2026-10-24)

The older vector-mesh numbers above are historical, not the current render path for
invisible text-only `rectangle` and `polygon` hosts. Those hosts now use a native
`StaticLayout` bitmap and a textured OpenGL quad, never the per-glyph outline mesh.

A lone text-only shape resized by a side handle preserves its font size and uses a
GPU-only translation to follow the box centre on each pointer sample. A low-priority
worker checks the line ends at most once every 90 ms. Since the host's native text
layout is centre-aligned, unchanged line breaks can reuse the last texture at the
new centre. Only a changed line-break signature causes a new temporary bitmap.
Previews are capped at 350,000 pixels (approximately 1.4 MiB RGBA); releasing the
pointer schedules the normal full-quality raster. Corner handles still change font
size proportionally, independently of side-handle reflow.

### Isolated Redmi Pad 2 benchmarks

All values below were measured on the connected tablet in the debug Android
instrumentation runner with synthetic 1,266-character monospace content.
No personal note was opened, read, or modified during these measurements.

| Preview preparation (48 width samples) | Result |
| --- | ---: |
| New bitmaps needed | 9 / 48 (81.25% reused) |
| Total temporary bitmap allocations | ~5.80 MiB |
| Median total preparation time | 2.89 ms |
| P95 total preparation time | 16.04 ms |
| P95 native layout | 6.64 ms |
| P95 bitmap rendering when required | 15.58 ms |

| Offscreen GLES3 quad benchmark | Result |
| --- | ---: |
| Frames / texture replacements | 48 / 8 |
| Median draw + glFinish | 2.58 ms |
| P95 draw + glFinish | 3.96 ms |
| Slowest draw + glFinish | 22.91 ms |

### Private copy of the real imported text

The existing fixture in the debug application's private cache was also read by
an instrumentation test. It contained 1,266 characters in an invisible `polygon`
host, so the native raster path is eligible. Its text was not printed or exported,
and the original user note was not changed.

| Real fixture, 24 wider resize steps | Result |
| --- | ---: |
| Bitmap previews generated | 24 / 24 (each step changed line breaks) |
| Temporary bitmap allocations in total | ~31.30 MiB |
| Median native preview generation | 9.38 ms |
| P95 native preview generation | 12.94 ms |
| P95 native line layout | 3.18 ms |
| P95 temporary bitmap rendering | 9.73 ms |

The earlier vector-mesh benchmark on this copy had P95 ~80.96 ms in the latest
device run. It performed more work (including glyph outlines and host meshing),
so the figures are not a strictly matched comparison, but the native bitmap
preview generation is substantially less expensive.

The synthetic polygon/rectangle test, private 1,266-character fixture test,
offscreen GLES3 preview test, and three earlier Android text instrumentation
tests passed together: `OK (6 tests)`. The private cached fixture was present
and measured successfully. JVM tests and `assembleDebug` also passed.

### 2026-10-24: stable scaling and alignment follow-up

- Vertical side handles now track the text's top-edge midpoint (not the box centre):
  moving the bottom no longer shifts the first line, and moving the top keeps the
  text attached to it, even if the host is rotated.
- A pure rigid-quad test allows rotation/translation to reuse the existing GL
  texture on release, avoiding the temporary translation-only placement.
- Text-only boxes have an 8 pt minimum during corner scaling and a readable
  width floor during side resize. Other shape resize behavior is unchanged.
- Native line breaking was not invariant under proportional font/width scaling:
  on the private 1,266-character `polygon` fixture, three of seven corner sizes
  changed line boundaries. A normalized 48 pt reference layout now produces
  identical line boundaries at all seven sizes, without editing the text.

| Normalized preview, same private fixture | Result |
| --- | ---: |
| Width changes tested | 24 |
| Bitmap previews | 24 / 24 |
| Median bitmap preparation | 8.84 ms |
| P95 bitmap preparation | 12.72 ms |
| Temporary bitmap allocation | ~31.25 MiB total |

All eight Android instrumentation tests passed together; JVM tests and the debug
build passed. The main debug APK was installed on the Redmi Pad 2. This benchmark
uses a different layout strategy from the historical measurements above, and is
not equivalent to overall interactive frame latency.

**Scope:** These results measure isolated layout, temporary raster and GLES
drawing only. They do not include Android touch dispatch, selection chrome,
Compose composition, full-app rendering, or a real stylus gesture. Do not claim
sustained 60/120 FPS from these numbers. Re-test the live editor and collect fresh
`dumpsys gfxinfo`/logcat data during a real resize before declaring the
interaction regression resolved.

### 2026-10-24: narrow bitmap quality and large selection movement

- The actual 1,266-character polygon-host fixture, when narrowed to 158.7 page
  pixels, laid out to 5,323.5 pixels high. The old 1,024-pixel preview and
  2,048-pixel final raster caps forced 19.2% and 38.5% bitmap scales, explaining
  the blurry text without any change to its font size or actual line layout.
- Texture scale now respects the queried GLES maximum (16,383 px on this Redmi
  Pad 2), a bounded pixel budget (350k ordinary preview, 1M extreme-aspect
  preview, 2M final), and 8,192 px side ceiling. The real narrowed fixture now
  reaches 100% bitmap scale at preview and final raster dimensions.
- A full-resolution synthetic narrow text texture (159 x 5,229, ~3.17 MiB)
  took median 2.10 ms and P95 2.25 ms to draw and synchronize in the latest
  offscreen GLES3 device run (24 movement frames; upload frame 5.24 ms).
- Moving the selected object was also regenerating selection dashes outside
  the screen, with rounded geometry for every little dash. Selection outlines
  are now viewport-clipped, and visible dashes use simple four-vertex quads.
  The dashed outline remains cosmetic; text and note geometry are unchanged.

| Tall selection on Redmi Pad 2, latest device benchmark | Before | After |
| --- | ---: | ---: |
| Selection outline vertices | 41,189 | 649 |
| Selection construction, median | 28.22 ms | 0.38 ms |
| Selection construction, P95 | 35.72 ms | 1.96 ms |

All 9 selected Android instrumentation tests, the JVM debug unit-test suite,
`assembleDebug`, `assembleDebugAndroidTest`, and `git diff --check` passed.
Both debug APKs were installed successfully on Redmi Pad 2. No saved note was
modified by the benchmarks. These timings isolate the chrome CPU and texture
GPU costs; physical stylus movement at 50% zoom in landscape remains the next
necessary validation. An 8 pt document font may still be physically tiny at
50% zoom, which resolution improvements alone cannot remedy.

### 2026-10-24: zoom-adaptive text raster, first stage

- The native editable-text bitmap can now request more than 1 raster pixel per
  document pixel, in camera-detail tiers 1x/2x/4x. The 2-million-pixel final
  allocation limit, GL texture limit and 8192-pixel-side safety ceiling remain.
- A viewport change schedules visible text refinement after 190 ms of inactivity.
  Pinching and panning keep the existing uploaded quad instead of re-rasterizing
  text per frame. Rigid moves/rotation preserve the highest available raster
  scale; a cancelled zoom refinement is retried after the gesture.
- The text's canonical StaticLayout and document font size are independent of
  zoom; only the output bitmap pixel density changes. A single current raster is
  cached by the scene and reused on zoom-out/repeated pan. A multi-tier LRU cache
  and rendering in visible line/region tiles are **not** part of this first stage.
- Zoom refinements only target visible text-only shapes and are queued on the
  existing serial background raster worker, with a 25% minimum resolution gain
  to avoid marginal replacement work.

| Device-side GLES3 1x/2x/4x smoke test | 1x | 2x | 4x |
| --- | ---: | ---: | ---: |
| Synthetic 6-line canonical text output | 420x191 | 840x381 | 1680x761 |

Nine Android instrumentation tests passed, including the zoom-detail GPU
smoke test, plus JVM unit tests and debug APK compilation. Updated debug APK
was reinstalled on Redmi Pad 2 (data preserved). Physical stylus pinch and
pan tests at high zoom remain necessary; the isolated smoke test cannot
establish actual end-to-end frame pacing.
