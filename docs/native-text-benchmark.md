# XCanvas native text benchmark

This instrumentation microbenchmark runs on a real Android device and compares the existing
outlined-glyph shape representation with editable `TextItem` using identical text and styling.

## Run

Build and install the debug application and instrumentation APK:

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r -t app/build/intermediates/apk/debug/app-debug.apk
adb install -r -t app/build/intermediates/apk/androidTest/debug/app-debug-androidTest.apk
adb logcat -c
adb shell am instrument -w -e class com.xnotes.perf.NativeTextBenchmarkTest com.xnotes.debug.test/android.test.InstrumentationTestRunner
adb logcat -d -s XNOTES_BENCH:I
```

## Measurements

- **cold-mesh:** distinct item instances, each tessellated once. Item construction is outside the timed region.
- **hot-mesh:** the same instances are tessellated repeatedly; exposes cache reuse.
- **serialize / deserialize:** ZIP .xcanvas write/read, including JSON geometry for outlined glyphs.
- **width-reflow-and-mesh:** repeatedly changes text wrapping width before tessellation.
- **size_bytes:** complete compressed .xcanvas file size.

The benchmark uses 1 and 5 text boxes, median and P95 durations in milliseconds, two warmups
per metric, seven measured cold runs, nine measured hot/write runs, and three measured load runs.
It runs on a test worker, not the UI thread. Measurements depend on the tablet's power,
temperature, JIT compilation and background activity.

**Limits:** This is not an FPS, frame-time or GPU-upload test. The previous XCanvas implementation
does not render `TextItem` at all, so its zero work is not a legitimate performance baseline.
Outlined glyph shapes are a functional alternative, not the unmodified upstream native-text
path. Width reflow and scaling an already outlined path are semantically different operations.

## Redmi Pad 2 results (2026-11-10, PR #428 development branch)

Median CPU timings from one instrumentation run:

| Scenario | 1 outlined | 1 native | 5 outlined | 5 native |
| --- | ---: | ---: | ---: | ---: |
| Cold mesh | 25.69 ms | 88.21 ms | 22.75 ms | 296.33 ms |
| Hot mesh | 16.20 ms | 0.049 ms | 18.00 ms | 0.040 ms |
| Save | 38.06 ms | 0.369 ms | 100.31 ms | 0.376 ms |
| Load | 4,734.56 ms | 1.61 ms | 13,650.38 ms | 2.66 ms |
| .xcanvas bytes | 27,505 | 339 | 132,771 | 376 |

Native width reflow + mesh: 59.10 ms median, P95 67.14 ms (35 runs).
The first mesh is still expensive; cache hits are fast, and the edit path dispatches
cold meshes to a background executor. These are not measured end-to-end UI latencies.

The async editor change aims to reduce input-thread stalls. Measure interaction latency separately
with frame timing or a Macrobenchmark before claiming an end-to-end responsiveness improvement.
