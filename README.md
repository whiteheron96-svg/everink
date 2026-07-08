# EverInk

EverInk is an open-source, no-ad PDF app spike for Android.

This repository is currently focused on proving the risky technical parts before
building the production viewer UI:

- large PDF rendering with MuPDF
- non-destructive annotation saves
- no network permission at the Android manifest level

The app intentionally does not declare `INTERNET`. Document access goes through
the Android Storage Access Framework or the app-specific external files folder.

## Current State

As of 2026-07-08:

- Android/Kotlin project scaffold is in place.
- Package/application id is `app.everink`.
- MuPDF `com.artifex.mupdf:fitz` is included, so the project license direction is AGPL-3.0.
- `BenchmarkActivity` runs rendering benchmarks for PDFs selected through SAF or pushed into the app folder.
- `StorageActivity` runs the storage pipeline spike from SAF-selected PDFs or PDFs in the app folder.
- `StorageSpike` checks incremental saves, prefix byte preservation, atomic replacement, backup generation, and annotation round trips.

There are no project-local Git commits yet. The current Git root resolves to the
user home directory, so project history should be treated as file state plus this
work log until a dedicated repository is initialized for `everink`.

## Build

Requirements:

- JDK 17
- Android SDK with API 35
- `local.properties` containing `sdk.dir=/path/to/Android/sdk`

Build:

```sh
./gradlew :app:assembleDebug
```

If macOS does not find Java but Homebrew `openjdk@17` is installed, run:

```sh
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew :app:assembleDebug
```

Install:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Latest local verification:

- `:app:assembleDebug` passes.
- `:app:lintDebug` passes with `0 errors, 3 warnings`.
- Remaining lint warnings are dependency update notices. They are deferred until a newer Android SDK is installed or compatibility is confirmed.

## Rendering Spike

Run from the launcher, select `PDF 선택 (SAF)`, or push files into the app folder:

```sh
adb push sample.pdf /sdcard/Android/data/app.everink/files/
adb shell am start -n app.everink/.bench.BenchmarkActivity --ez autorun true
```

The result is printed on screen and in Logcat with tag `EverInkBench`.

Validation gates from the spike comments:

- 200MB scanned PDF first page render at or below 2000ms on a mid-range device.
- 2,000 page document traversal without OOM crashes.

## Storage Spike

Run from the benchmark screen via `저장 파이프라인 스파이크 열기`, select a PDF, or push a PDF into the app folder:

```sh
adb push big_pages.pdf /sdcard/Android/data/app.everink/files/
adb shell am start -n app.everink/.bench.StorageActivity --ez autorun true
```

The result is printed on screen and in Logcat with tag `EverInkStore`.

The storage spike currently verifies:

- pre-save backup generation with a 3-generation retention window
- incremental save preserving the original byte prefix
- same-directory atomic replacement using `ATOMIC_MOVE`
- annotation appearance stream update
- annotation count after reopening the saved document

## Next Work

1. Make the Homebrew JDK discoverable for normal shell builds, or keep using the explicit `JAVA_HOME=...` command above.
2. Install `app/build/outputs/apk/debug/app-debug.apk` on a real Android device.
3. Run the rendering spike with:
   - a 200MB scanned PDF
   - a 2,000 page PDF
   - a password-protected PDF
   - a corrupt PDF
4. Run the storage spike on the same PDFs and inspect saved annotations in at least two external viewers.
5. Record benchmark results in `docs/WORKLOG.md`.
6. Split spike code into production-shaped modules:
   - document opening/rendering
   - document-of-record storage
   - annotation writing
   - benchmark/debug UI
7. Decide the final package id and AGPL/F-Droid release posture before publishing.
