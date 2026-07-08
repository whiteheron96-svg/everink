# Work Log

## 2026-07-08

Observed starting state:

- No project-local Git commits exist.
- `git rev-parse --show-toplevel` resolves to `~`, not this project directory.
- No README, PRD, roadmap, or TODO file was present inside `everink`.
- Codex memory search did not find an EverInk-specific prior work record.
- Existing code was a minimal Android/Kotlin MuPDF spike:
  - `BenchmarkActivity` for render benchmarking
  - `BenchmarkRunner` for open/render timing and native heap sampling
  - `StorageActivity` for a hidden autorun storage spike
  - `StorageSpike` for incremental-save and annotation persistence checks

Work completed in this pass:

- Added this work log and a project README with build, run, validation, and next-work notes.
- Added a launcher path from `BenchmarkActivity` to `StorageActivity`.
- Made `StorageActivity` usable from the device UI:
  - SAF PDF picker
  - app-folder PDF scan
  - autorun still supported for ADB automation
- Removed the hard dependency on a file named `big_pages.pdf`; it is now only preferred when present.
- Reworked `StorageSpike.prefixHash` to stream bytes instead of allocating the full PDF prefix in memory.
- Changed document replacement verification to use `Files.move` with `ATOMIC_MOVE` and `REPLACE_EXISTING`.
- Sorted backup trimming by file modification time.
- Added app icon, backup exclusion rules, and data extraction rules.
- Suppressed hardcoded text lint only for the two spike/debug activities.

Validation:

- Plain `./gradlew build` could not run because macOS did not discover Java:

```text
The operation couldn't be completed. Unable to locate a Java Runtime.
```

- Homebrew `openjdk@17 17.0.19` was present.
- `JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew :app:assembleDebug` completed successfully.
- `JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.19/libexec/openjdk.jdk/Contents/Home ./gradlew :app:assembleDebug :app:lintDebug` completed successfully.
- Lint now reports `0 errors, 3 warnings`; all remaining warnings are dependency-update notices:
  - `androidx.core:core-ktx` 1.15.0 -> 1.19.0
  - `androidx.appcompat:appcompat` 1.7.0 -> 1.7.1
  - `com.google.android.material:material` 1.12.0 -> 1.14.0
- Dependency updates were deferred because only Android SDK `android-35` is installed locally, and newer AndroidX artifacts may require a newer compile SDK.
- `adb devices` ran successfully with elevated permissions, but no Android device was attached.
- Debug APK produced: `app/build/outputs/apk/debug/app-debug.apk`.

Immediate next action:

- Connect an Android device, install the debug APK, and run the rendering/storage spikes with the target PDF corpus.

Resume after interrupted cell:

- Re-ran `:app:assembleDebug`; build completed successfully and remained up-to-date.
- Re-ran `:app:lintDebug`; lint completed successfully.
- Checked `~/Library/Android/sdk/platform-tools/adb devices`; no Android device was attached.
- The next actionable step is still to connect a device, then install and run the spike activities.

Follow-up next-step attempt:

- Re-checked `~/Library/Android/sdk/platform-tools/adb devices`; no Android device was attached.
- Checked for a local Android emulator at `~/Library/Android/sdk/emulator/emulator`; emulator is not installed in this SDK.
- No PDFs were present under the project directory to push into the app folder.
- APK remains available at `app/build/outputs/apk/debug/app-debug.apk`.

Device execution attempt:

- Connected device detected: `Galaxy S25`.
- Installed `app/build/outputs/apk/debug/app-debug.apk` successfully with `adb install -r`.
- Pushed smoke-test PDF `window.pdf` to `/sdcard/Android/data/app.everink/files/window.pdf`.
- Device app folder also already contained:
  - `big_pages.pdf` (about 1.2MB, 2,000 pages)
  - `big_scan.pdf` (about 375MB)
- Rendering autorun produced this `EverInkBench` result for `big_pages.pdf`:
  - open: 42ms
  - first page: 148ms, gate pass (`<=2000ms`)
  - average render: 8.2ms over 50 sampled pages
  - max render: 9ms
  - native heap peak: 50.9MB
- The run did not progress to a logged result for `big_scan.pdf` while the phone was in keyguard/AOD state.
- `dumpsys activity activities` showed `KeyguardShowing=true`, `AodShowing=true`, and `BenchmarkActivity` in `STOPPED` state.
- Next action: unlock the device and keep EverInk visible, then rerun rendering autorun and the storage spike.

Device execution resumed after reconnect:

- Reconnected device detected: `Galaxy S25`.
- Enabled stay-awake while USB powered with `svc power stayon usb`; `stay_on_while_plugged_in=2`.
- Re-ran rendering autorun with PDFs in `/sdcard/Android/data/app.everink/files/`.
- Rendering results:
  - `big_pages.pdf`: 1.3MB, 2,000 pages, open 33ms, first page 130ms, average 3.1ms over 50 sampled pages, max 4ms, native heap peak 52.3MB.
  - `big_scan.pdf`: 375.1MB, 120 pages, open 0ms, first page 84ms, average 54.8ms over 60 sampled pages, max 64ms, native heap peak 268.5MB.
  - `window.pdf`: 11 pages, first page 49ms, average 2.5ms over 11 sampled pages, native heap peak 304.1MB.
- Rendering gate `first page <= 2000ms` passed for all tested PDFs.
- Ran storage spike for `big_pages.pdf`; all checks passed:
  - 5 incremental save sessions
  - atomic move
  - original prefix unchanged
  - page 0 annotation count 5
  - backup retention count 3
- Temporarily renamed `big_pages.pdf` to let the storage Activity select `big_scan.pdf`, then restored the original name.
- Ran storage spike for `big_scan.pdf`; all checks passed:
  - 5 incremental save sessions
  - atomic move
  - original prefix unchanged
  - page 0 annotation count 5
  - backup retention count 3
- Copied the latest saved document of record to `/sdcard/Android/data/app.everink/files/big_scan_doc_of_record.pdf` and set it world-readable for external viewer inspection.
- Copied the same file to `/sdcard/Download/EverInk_big_scan_doc_of_record.pdf` for manual external-viewer checks.
- Manual external-viewer inspection:
  - Samsung Notes showed the annotation appearance, but did not expose the annotation contents in the tested path.
  - Polaris Office showed the annotation contents, including `EverInk 세션 4`.
  - Xodo showed all 5 annotations. One annotation's contents were edited in Xodo, saved, and confirmed to persist after reopening.

Production restructuring pass (same day, later session):

- Initialized a dedicated Git repository inside `everink` and committed the spike state as the initial commit (`d453cd4`), so the pre-refactor baseline is permanently recoverable.
- Split spike code into production-shaped modules:
  - `app.everink.core.render.PdfSession` — document open/auth/page render; serializes MuPDF access per session.
  - `app.everink.core.store.DocumentStore` — document-of-record pipeline: generation backups (keep 3), staged temp copy, atomic commit, `saveEdit` convenience entry point.
  - `app.everink.core.annot.AnnotationWriter` — standards-compliant annotation writes (appearance stream + incremental save).
- `BenchmarkRunner` now renders through `PdfSession`; `StorageSpike` now drives `DocumentStore` + `AnnotationWriter` primitives and keeps its byte-level verification (prefix hash) as the test layer.
- Added the first production viewer UX: `app.everink.viewer.ViewerActivity`.
  - Vertical continuous page list (RecyclerView), background single-thread rendering, 48MB LRU page cache, aspect-ratio placeholders.
  - Opens PDFs via SAF picker and via external `ACTION_VIEW` intents (`content`/`file` schemes declared for lint compliance).
  - Password-protected documents prompt for a password; corrupt documents surface a toast instead of crashing.
  - ViewerActivity is now the launcher; bench activities remain exported for ADB autorun.
- Added `androidx.recyclerview:recyclerview:1.3.2`.

Validation:

- `:app:assembleDebug` and `:app:lintDebug` pass; lint `0 errors, 4 warnings` (dependency-update notices plus the pre-existing ones).
- Installed on `Galaxy S25` and smoke-tested the viewer with `window.pdf` via a `file://` ACTION_VIEW intent: pages render, scrolling works, status overlay shows `11p`. Screenshots verified.
- Regression on refactored modules (device autoruns):
  - Rendering: `big_pages.pdf` first page 57ms, `big_scan.pdf` first page 74ms, `window.pdf` 37ms — all gates PASS, no OOM.
  - Storage: all 5 spike checks pass for `big_pages.pdf` (incremental, atomic move, prefix unchanged, 5 annotations, 3 backups).

Immediate next actions:

- Viewer: pinch zoom + double-tap zoom, page position restore, and open-history list.
- Wire `DocumentStore` into the viewer so opened PDFs become documents of record (currently read-only cache copies).
- First annotation UX (select area → square note) using `AnnotationWriter` through `DocumentStore.saveEdit`.
- Test password-protected and corrupt PDFs on device (dialog path was only exercised locally).
- Decide GitHub repository setup (AGPL-3.0 LICENSE file, README in English) before publishing.
