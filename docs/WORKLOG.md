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

Viewer v1 pass (same day, third session): document-of-record + first annotation UX.

- Wired `DocumentStore` into the viewer. Opening a PDF now imports it into
  `filesDir/documents/<id>/` as a document of record (id = SHA-256 of the first
  64KB + length, so reopening the same source resumes the same annotated copy).
  The original file is never modified.
- First annotation UX: long-press on a page → note dialog → square annotation at
  the pressed position. Save path is `DocumentStore.saveEdit` = backup → temp
  incremental save → atomic replace. After saving, the session reopens and the
  page re-renders with the annotation; scroll position is preserved.
- View coordinates map to PDF points via the new `PdfSession.pageBounds`.
- Recent-documents list on the start screen (name.txt per store dir, newest 5).
- Added the AGPL-3.0 `LICENSE` file.

Device validation (Galaxy S25, all via adb automation):

- Annotation E2E: long-press on `window.pdf` page 1 → typed `EverInk-note-1` →
  saved. Pulled the document of record with `run-as`: page 0 has 1 `/Square`
  annotation with the exact contents; 1 backup generation existed. The yellow
  square rendered immediately in the viewer.
- Persistence: force-stopped the app, relaunched, opened via the recent list —
  the annotation is still rendered. Core "annotations never disappear" promise
  now demonstrated end-to-end in the product UI.
- Password-protected PDF (`locked.pdf`, pypdf-encrypted, password `everink123`):
  password dialog appeared, correct password opened the document (11p shown).
  The wrong-password branch was not exercised on device.
- Truncated/damaged PDF (`corrupt.pdf`, front half with zeroed bytes): MuPDF
  auto-repairs, opens without crash, but pages render blank — acceptable for v0,
  should later surface a "document was repaired" notice.
- Unparseable file (`garbage.pdf`, random bytes): no crash, toast
  `문서를 열 수 없습니다: no objects found`, start screen stays usable.
- Note: the first automated save attempt failed because the IME shifted the
  dialog and the scripted tap missed the save button — an automation artifact,
  not an app bug. Re-run with uiautomator-derived coordinates succeeded.

Known gaps noted for later:

- Large documents copy fully on each `saveEdit` staging step (375MB doc → ~seconds);
  consider staging once per editing session instead of per annotation.
- No way to delete a document from the recent list yet.
- `corrupt.pdf`-style repaired documents show blank pages with no notice.

Viewer v2 pass (same day, fourth session): zoom + annotation management.

- `ZoomableRecyclerView`: pinch zoom (1x–4x) and double-tap zoom toggle (1x ↔ 2x).
  Drawing uses a canvas transform; child views receive inverse-transformed touch
  events, so tap/long-press coordinate mapping is zoom-independent. Vertical
  scrolling stays native; vertical panning takes over at list edges; horizontal
  panning is active while zoomed. Render resolution is still base width, so high
  zoom is soft — per-scale re-render is future work.
- Annotation management: single tap on a note opens a dialog showing its contents
  with 수정/삭제/닫기. Edit and delete both go through `DocumentStore.saveEdit`
  (backup → incremental temp save → atomic replace), so the pre-edit and
  pre-delete states are always recoverable from backup generations.
  `AnnotationWriter` gained `list`/`updateContents`/`delete`.
- Recent-list management: long-press a recent document → confirm → the store
  directory (document of record + backups) is deleted. Originals are unaffected.
- Bug fix: a file that fails to open (e.g. random bytes) no longer leaves an
  imported store directory behind in the recent list.

Device validation (Galaxy S25, adb automation):

- Tap on the `EverInk-note-1` square → dialog showed the exact contents.
- Edit appended `-edited`; pulled document of record shows
  `EverInk-note-1-edited`, backups grew to 2 generations.
- Double-tap zoomed to 2x at the tap point (screenshot-verified) and a second
  double-tap returned pixel-identical to the 1x baseline. Pinch zoom shares the
  same transform path but was not automatable over adb — verify by hand on device.
- Delete flow: confirm dialog → page 0 annotation count 0 in the pulled file,
  3 backup generations retained (deletion is recoverable).
- Long-pressed the stale `garbage.pdf` recent entry → deleted; after the bug fix,
  reopening `garbage.pdf` fails with a toast and leaves no recent entry.

Viewer v3 pass (same day, fifth session): render quality, save-pipeline IO, repair notice.

- High-resolution re-render while zoomed: `ZoomableRecyclerView.onScaleSettled`
  fires on pinch end and double-tap; the viewer switches render quality between
  1x and 2x page width (threshold: scale > 1.2), evicts the page cache (resized
  to 96MB at 2x), and re-renders. Measured text sharpness at 2x zoom improved
  5.4x by Laplacian variance versus the previous upscale-only build, and is
  clearly crisper in side-by-side screenshots.
- Save-pipeline IO halved via rename-based staging in `DocumentStore`:
  backup = atomic rename of the document (zero IO regardless of file size),
  temp = one full copy from the backup, edit incrementally, commit = atomic
  rename back. Previously each edit cost two full copies (backup copy + stage
  copy). The document path is briefly empty between backup and commit, so
  `recoverIfNeeded()` (run at store init and before edits) promotes the newest
  backup if a crash interrupted an edit, and clears stale temp files.
  - A hardlink-based backup (`Files.createLink`) was tried first and failed on
    device: Android SELinux blocks link creation in app data directories. The
    inode check proved the fallback copy ran; the approach was replaced by
    rename staging, which the same inode check proved correct
    (pre-edit document inode 1224505 became exactly the newest backup's inode).
- Repaired-document notice: `PdfSession.wasRepaired()` (MuPDF `PDFDocument
  .wasRepaired`) → status bar shows `⚠︎복구됨` and a one-time toast warns that
  content may be incomplete. Verified on device with `corrupt.pdf`.
- `StorageSpike` updated to the new primitives (`renameToBackup`/`stageFrom`);
  prefix-hash verification now compares temp against the renamed backup.
  Device autorun: all 5 checks pass with the rename pipeline.

Viewer v4 pass (same day, sixth session): search and navigation.

- In-document text search: `PdfSession.searchPage` wraps MuPDF `Page.search`
  (quads → PDF-point rects). The viewer searches all pages on a background
  thread with progress in the status bar, then shows a per-page result list
  (`N쪽 · M건`); picking an entry jumps to the page. Matches are painted as
  translucent yellow overlays by the new `PageImageView` (PDF-point rects
  converted at draw time, so highlights track zoom/re-render). The status bar
  shows the total hit count; searching with an empty query or "지우기" clears.
- Go-to-page dialog and outline (책갈피) navigation: `PdfSession.outline()`
  flattens the MuPDF outline tree with depth + resolved page numbers; documents
  without an outline get a toast.
- A slim top toolbar (검색/이동/목차) appears while a document is open.
- Bug found and fixed while testing: targetSdk 35 forces edge-to-edge, so the
  toolbar rendered under the system status bar (taps landed on system UI) and
  the bottom status text overlapped the navigation bar. Window insets are now
  applied to both overlays.

Device validation (Galaxy S25, adb automation):

- Searched `Supabase` in `window.pdf`: 2 hits (pages 1 and 9); result list
  correct; picking `9쪽` jumped there and the match rendered with a yellow
  highlight; status bar showed `🔍 2건`.
- 이동 dialog jumped to page 3 exactly; 목차 on an outline-less document showed
  the "이 문서에는 목차가 없습니다" toast. An outline-bearing PDF has not been
  tested yet — worth one manual check with a real book/manual PDF.

Manual verification follow-up (same day):

- Pinch zoom verified by hand on the device by the user: works normally.
- Outline navigation verified with a purpose-built TOC PDF (`outline_test.pdf`:
  `window.pdf` + 9 hierarchical bookmarks added via pypdf). The 목차 dialog
  showed all 9 items with correct indentation and page numbers, and tapping
  `3. 데이터베이스 구조 · 9쪽` jumped exactly to that page.
- Note: the adb connection needed re-authorization mid-session (device showed
  `unauthorized` after the adb daemon restarted); resolved by revoking and
  re-accepting USB debugging on the phone with "always allow".

Viewer v5 pass (same day, seventh session): ink/freehand annotations.

- `AnnotationWriter.addInk`: standard PDF Ink annotations (MuPDF `TYPE_INK` +
  `inkList` + appearance stream) written per page in a single incremental save,
  through the same backup → stage → atomic-commit pipeline as notes.
- Viewer ink mode: 필기 button switches the toolbar to 저장/취소, scrolling and
  zoom are suspended, and finger strokes are captured in content coordinates,
  converted to PDF points against the page under the stroke's starting point,
  and clamped to that page's bounds. `PageImageView` draws live blue previews
  (2.5pt round-cap strokes) that match the saved rendering.
- Strokes spanning multiple pages in one session are grouped per page and saved
  as one Ink annotation per page in one incremental save.
- Tapping saved ink reuses the existing annotation dialog (edit contents /
  delete), since ink rects participate in the same hit test.

Device validation (Galaxy S25, adb automation):

- Drew two strokes (a horizontal underline and a diagonal that crossed the page
  bottom — correctly clamped to the page edge). Preview matched the post-save
  MuPDF rendering pixel-for-pixel in position and shape.
- Pulled document of record: page 0 gained one `/Ink` annotation with 2 strokes
  (50 and 57 points); pypdf parsed the `/InkList` structure cleanly.
- Force-stopped and relaunched via the recent list: ink still renders
  (3,150 blue stroke pixels detected on the reopened page).

Viewer v6 pass (same day, eighth session): ink polish.

- Pen picker (펜 button in ink mode): 4 colors (파랑/빨강/초록/검정) × 3 widths
  (가늘게 1.5pt / 보통 2.5pt / 굵게 4.5pt), applied per stroke; the status bar
  shows the current pen.
- Undo (↩ button): removes the most recent stroke (draw order preserved across
  pages); repeated taps keep peeling strokes back.
- Save now groups strokes by (page, color, width) — each group becomes one
  standard /Ink annotation, since a PDF Ink annotation carries a single color
  and width — still one incremental save for the whole session.
- `PageImageView` previews carry per-stroke color/width, so the preview matches
  the saved rendering exactly.

Device validation (Galaxy S25, adb automation):

- Drew stroke 1 with the default pen, switched to 빨강+굵게 via the picker,
  drew strokes 2 and 3, pressed undo — stroke 3 disappeared from the preview.
- Saved; pulled document of record shows the new annotations exactly as
  expected: one /Ink (blue, 2.5pt) and one /Ink (red, 4.5pt); the undone
  stroke is absent. Colors/widths verified from /C and /BS /W entries.
- Exported the annotated document of record to
  `/sdcard/Download/EverInk_ink_check.pdf` for a manual external-viewer check
  (Polaris/Xodo), mirroring the earlier note round-trip test.

Manual verification: the user confirmed ink renders correctly in an external
viewer ("잘 되네") — colors/widths intact outside EverInk.

GitHub publication (same day):

- Pre-publish audit: no secrets/keys/tokens tracked; `local.properties` never
  tracked. Two hygiene issues fixed BEFORE the first push, while history was
  still local: a stray `.kotlin` build log was removed from all history, and
  personal identifiers in this work log (macOS username paths, device serial)
  were scrubbed across all commits via `git filter-branch` (original kept in
  `refs/original`). Post-rewrite grep over full main history: 0 hits.
- Published to https://github.com/whiteheron96-svg/everink — public, AGPL-3.0
  recognized by GitHub, 10 commits of history intact.
- The `app.everink` application id is now considered final.

Release v0.1.0 (same day):

- Generated a 4096-bit RSA release keystore (30-year validity) at
  `~/.everink-release/` with a random password; keystore + properties backed up
  to the external volume (`2TB white/everink-release-keys-backup`). Nothing
  signing-related is in the repo; Gradle reads
  `~/.everink-release/keystore.properties` when present.
- Version set to 0.1.0 (versionCode 1). Minify stays off for 0.1.0 to avoid
  proguard risk at the MuPDF JNI boundary.
- Bug found during release smoke testing and fixed: ACTION_VIEW delivered to a
  running instance was silently ignored (no onNewIntent handler). Now the
  running viewer switches documents.
- Release smoke on device (signed build): cold ACTION_VIEW open, warm re-entry
  document switch, page rendering, note save with visible yellow annotation —
  all pass, no crashes. Note: release builds are not debuggable, so `run-as`
  file inspection no longer works; verification is screenshot-based. One
  test-only quirk: if adb creates the app-external dir before the app first
  touches it, file reads fail — real users (SAF/content URIs) are unaffected.
- Published: https://github.com/whiteheron96-svg/everink/releases/tag/v0.1.0
  with `EverInk-0.1.0.apk` attached (45.6MB, all ABIs), marked pre-release.

Beta testing can start now: send testers the release link.

Design system pass ("잉크 & 종이", same day):

- Direction chosen by the user from three proposals: ink navy + paper cream +
  annotation amber. Full token set documented in `docs/DESIGN.md`.
- Implementation:
  - Color tokens in `values/colors.xml` + `values-night/colors.xml` (심야 잉크
    dark palette switches automatically).
  - `Theme.EverInk` overrides the Material3 palette, so dialogs and widgets
    follow the brand without per-view styling.
  - `app.everink.ui.InkUi` helper for programmatic pills/cards/dp.
  - Home redesigned: Ever(navy)+Ink(amber) wordmark, navy pill primary button,
    card-style recent list, de-emphasized bench link on paper background.
  - Viewer overlays redesigned: toolbar and status are ink-navy pills with
    cream text, inset-aware margins.
  - New adaptive launcher icon: navy fountain-pen nib + amber ink drop on
    paper cream (with monochrome layer); app label now "EverInk".
- New feature (user request): document close. ✕ button in the toolbar and the
  back key both close the document back to the home/recent screen; back exits
  ink mode first if active.
- Device-verified (light + dark screenshots): home, viewer pills, ✕ close
  returning to home, and the 심야 잉크 dark palette all render as designed.

Immediate next actions:

- IzzyOnDroid submission (release with APK now exists) — cut v0.1.1 with the
  design system first so store screenshots use the new look.
- Recruit beta testers; enroll them in Play closed testing once the developer
  account is ready (the 12 testers × 14 days clock starts there).
- Consider ABI splits or app bundle later to cut the 45MB APK size.
