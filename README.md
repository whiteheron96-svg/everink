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

- Android/Kotlin project scaffold is in place, with a dedicated Git repository.
- Package/application id is `app.everink`.
- MuPDF `com.artifex.mupdf:fitz` is included, so the project license direction is AGPL-3.0.
- Production-shaped core modules:
  - `core/render/PdfSession` — document open/auth/page rendering
  - `core/store/DocumentStore` — backup + staged incremental save + atomic commit
  - `core/annot/AnnotationWriter` — appearance-stream annotation writes
- `viewer/ViewerActivity` is the launcher: continuous vertical page viewing, SAF and
  ACTION_VIEW open paths, password prompt, background rendering with an LRU cache.
- `BenchmarkActivity` runs rendering benchmarks for PDFs selected through SAF or pushed into the app folder.
- `StorageActivity` runs the storage pipeline spike from SAF-selected PDFs or PDFs in the app folder.
- `StorageSpike` verifies the `DocumentStore`/`AnnotationWriter` pipeline byte-for-byte: incremental saves, prefix preservation, atomic replacement, backup generations, annotation round trips.

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

1. Viewer polish: pinch/double-tap zoom, scroll position restore, recent-files list.
2. Route viewer opens through `DocumentStore` so every opened PDF becomes a document of record.
3. First annotation UX (square note) via `AnnotationWriter` + `DocumentStore.saveEdit`.
4. Device-test password-protected and corrupt PDFs in the viewer.
5. Decide the final package id and AGPL/F-Droid release posture; add a LICENSE file and set up the GitHub repository (GitHub → IzzyOnDroid → Play → F-Droid order).
