# VPlayer → Native Android (Kotlin + Jetpack Compose + Media3) Rewrite Plan

## Context

VPlayer (`/Users/f2pgod/Documents/VPlayer`) is an Expo 55 / React Native 0.83 Android-first app. It runs a LAN HTTP upload server on the phone (browser uploads plus browser-side library management), keeps a private, folder-organised video library inside the app sandbox, and plays videos with a custom gesture-driven player (SRT subtitles, scrub frame preview, lock mode, resume, speed control, hold-to-boost). The goal is a **native Android rewrite with feature parity** in a new project at `/Users/f2pgod/Documents/VPlayer_android` (currently empty), written so other agents (e.g. Opus 5) can implement it phase by phase. Implementers may read the RN source for reference, but Appendices A–C below are the authoritative behavioural spec and were derived from the **current** source (the old `docs/swiftui-rebuild-spec.md` predates folders, rename/move, settings tab, swipe delete, range select, upload concurrency and browser library management, and is wrong about port 8080, the 95% "watched" badge, breadcrumbs and the storage snapshot).

**Fresh install only (user decision).** No in-place upgrade over the RN build is required: no legacy settings migration, no compatibility of playback-state keys, thumbnail hash or JSON layout with the old app. Storage formats below are therefore chosen for cleanliness, not compatibility.

## Decisions (made; change only if the user disagrees)

| Area | Decision | Why |
|---|---|---|
| Language/UI | Kotlin, Jetpack Compose (Material3 components, fully custom light palette), single `:app` module | Modern default, matches spec's custom look |
| Player | Media3 ExoPlayer (`media3-exoplayer`, `media3-ui-compose` `PlayerSurface` or `AndroidView(SurfaceView)`), no default controller | MKV/WebM/MP4 via ExoPlayer extractors; full control of overlay |
| HTTP server | **Ktor 3 server, CIO engine**, kotlinx.serialization JSON | Streaming multipart, keep-alive with browsers, maintained; NanoHTTPD is unmaintained and buffers whole bodies |
| Server process model | Process-wide `UploadServerController` singleton + thin **foreground service** (`dataSync`) that holds the notification and locks while running | Keeps the server alive when backgrounded, correct place for wake/Wi-Fi locks; `stopWithTask=true` so swiping the task stops the server |
| Frame extraction | `MediaMetadataRetriever` for both the library probe (duration + 240px JPEG) and live scrub preview (one retriever per video, `OPTION_CLOSEST_SYNC`, 160×90 scaled), confined to a single-thread dispatcher | No second hardware decoder; cheap continuous previews |
| Gestures | One `pointerInput { awaitEachGesture }` state machine (finger-count aware, 250 ms deferred tap, 500 ms hold) on a full-screen layer below the controls; seek strip has its own `pointerInput` | GestureDetector cannot express two-finger double tap or tap-swallowing hold |
| Swipe rows | `AnchoredDraggableState` (Closed=0, Open=−176dp) with hoisted `openRowId` | Partial-anchor reveal with settle physics; single-open and close-on-scroll are trivial |
| Persistence | `AtomicFile` + kotlinx.serialization; `JsonFileStore<T>` with in-memory cache and `Mutex`-serialised updates | Same shape as the RN store, atomic writes |
| Storage keys | Playback state and thumbnail cache keyed by **library-relative path** (`Shows/ep1.mp4`) | Fresh install, no compat; relative keys survive app-dir changes |
| Package id | Keep `com.seedds.vplayer`, label `VPlayer`, same icon assets | Nothing depends on it; avoids inventing a new identity |
| Immersive | Hide status **and** navigation bars in the player (sticky immersive) | Allowed improvement over RN (which left the nav bar visible) |
| Deviations allowed | Atomic JSON writes; conflated activity emission; `Cache-Control: no-store`; no health endpoint added | Spirit-preserving |

## Toolchain (verified on this machine)

- JDK 17 (Homebrew), Android Studio installed, SDK platforms 33–36, build-tools 34–36. Gradle 9.1 wrapper, AGP 8.13.0 and 9.0.1, Kotlin 2.2.20 and 2.3.20 already in `~/.gradle` cache.
- No device attached at planning time; use an emulator (API 34/35 phone + one sw600dp tablet profile) or attach a phone.

## Tech stack & versions (pin in `gradle/libs.versions.toml`; verify each resolves on day one)

- AGP **8.13.x**, Kotlin **2.2.20** + `org.jetbrains.kotlin.plugin.compose` + `org.jetbrains.kotlin.plugin.serialization` same version, Gradle 9.1, JDK 17, Kotlin DSL.
- Compose BOM latest stable ≥ 2025.09; `activity-compose`, `lifecycle-runtime-compose`, `lifecycle-viewmodel-compose`, `navigation-compose` 2.9 (type-safe `@Serializable` routes), `material3`, `foundation` (AnchoredDraggable), `core-ktx`, `core-splashscreen`.
- Media3 ≥ 1.8: `media3-exoplayer`, `media3-ui-compose` (optional; `AndroidView(SurfaceView)` is fine).
- Ktor ≥ 3.3: `ktor-server-core`, `ktor-server-cio`, `ktor-server-content-negotiation`, `ktor-serialization-kotlinx-json`; `slf4j-nop`.
- kotlinx-serialization-json 1.9, kotlinx-coroutines 1.10, `desugar_jdk_libs` 2.1 (core library desugaring on; minSdk 24).
- Tests: JUnit4/5, kotlinx-coroutines-test, Ktor `ktor-server-test-host`, Turbine; androidTest with OkHttp + Compose UI test.
- minSdk **24**, compileSdk/targetSdk **36**. R8 + resource shrinking on for release with `-dontwarn io.ktor.**`, `-dontwarn org.slf4j.**`, `-dontwarn kotlinx.serialization.**`. ABI split arm64-v8a only, no universal APK.

## Package layout (`com.seedds.vplayer`, module `:app`)

```
app/                 VPlayerApp (Application; owns AppContainer: stores, repos, controller), MainActivity (enableEdgeToEdge, splash)
ui/theme/            VPlayerColors (palette in A1), Typography, Theme
ui/nav/              Routes (@Serializable), AppScaffold (bottom tabs Library/Upload/Settings), orientation policy (sw600dp)
ui/components/       Panel, ActionButton, SecondaryButton, TextPromptDialog, LoadingCard
data/model/          LibraryItem sealed class (Folder/Video/Subtitle/File), UploadActivity, ActiveUploadRow, PlaybackEntry, Settings
data/fs/             LibraryPaths (root dirs, ensureDirs), NameSanitizer (A2 rules), PathNormalizer, NaturalOrderComparator
data/library/        LibraryRepository (list, listAllVideos, create/rename/move/delete, findMatchingSubtitle, collectVideos, artifact relink/forget)
data/store/          JsonFileStore<T> (AtomicFile), SettingsStore, PlaybackStateStore
data/media/          MediaProbe (MMR queue, concurrency 1, 4 s timeout), ThumbnailCache (hash, read/move/delete/prune), HydrationCoordinator (pass A/B, 250 ms batching)
server/              UploadServerController (StateFlow), UploadServerService (FGS, locks, notification), UploadRoutes (Ktor routing), UploadSessionManager, UploadPage (asset + substitution), LanAddressMonitor
library/             LibraryViewModel, LibraryScreen, LibraryRow (thumb, badge, pie), SwipeRevealRow, FolderPickerSheet, SelectionToolbar
player/              PlayerViewModel, PlayerScreen, PlayerGestures, ControlsOverlay, SeekStrip, FramePreviewPopup, SubtitleOverlay, SrtParser, ResumePolicy
upload/              UploadViewModel, UploadScreen
settings/            SettingMeta (table in A3), SettingsScreen, SettingPickerScreen
```

## Storage layout (fresh design)

```
filesDir/videos/                 user library (unbounded depth)
filesDir/uploads-tmp/            in-flight uploads  <epochMs>-<6 base36>.upload
filesDir/thumbnails/<sha1(relativePath|size|mtime|10|240|240)>.jpg
filesDir/app-settings.json       {"maxParallelUploads":3,"subtitleFontSize":36,"longPressSpeedTenths":30}
filesDir/playback-state.json     {"<relativePath>": {"positionSeconds":..,"durationSeconds":..,"hasStartedPlayback":..,"updatedAt":..}}
cacheDir/upload-chunk-temp/      Ktor multipart staging, swept at server start
```
Semantics of every field, clear/move/remove operations and cadence: A2, A5, B8. Kotlin Json config: `ignoreUnknownKeys=true, explicitNulls=false, encodeDefaults=false`.

## Implementation phases (each ends runnable on device; do them in order)

**Phase 0 — Repo bootstrap.** Create `/Users/f2pgod/Documents/VPlayer_android` as a standalone git repo. Copy this plan into `docs/plan.md` and Appendices A–C into `docs/spec-app.md`, `docs/spec-player.md`, `docs/spec-server.md`. Copy icon/splash PNGs from `/Users/f2pgod/Documents/VPlayer/assets/`. Add `.gitignore` (Android Studio template), `README.md` (adapted from the RN README: features, build, player controls), `.github/ISSUE_TEMPLATE/*` copied as-is.

**Phase 1 — Skeleton + CI.** Gradle Kotlin DSL, version catalog, `:app` with Compose, theme (A1 palette), bottom tabs with placeholder screens, splash, adaptive icon, `enableOnBackInvokedCallback=false`, `configChanges` for orientation, tablet/phone orientation policy. `app/build.gradle.kts`: signing from `VPLAYER_RELEASE_*` env/props with debug fallback; `versionName`/`versionCode` from `VPLAYER_VERSION_NAME`/`VPLAYER_VERSION_CODE` else computed (`1000000 + git rev-list --count HEAD`, next patch of `vplayer.baseVersion=1.0.0` in `gradle.properties` scanning tags `v<M>.<m>.<p>[-build.N]`); ABI split arm64; R8 rules. Port `scripts/android-release-meta.js` to `scripts/android-release-meta.sh` emitting `apk_name`, `app_version`, `build_number`, `release_tag`. Workflow `.github/workflows/android-release.yml` per C5 (no Node steps; rename `app-arm64-v8a-release.apk` → `vplayer-<v>.apk`). Acceptance: `./gradlew :app:assembleRelease` yields a signed-or-debug APK; CI green on push to `main`.

**Phase 2 — Pure logic + unit tests.** `NameSanitizer`, `PathNormalizer` (A2), `NaturalOrderComparator` (folders first, ICU/`Collator` primary + numeric), `SrtParser` + active-cue lookup (B6), `ResumePolicy` (B1), `formatBytes/formatDuration/normalizePort` (A2), `JsonFileStore`, `SettingsStore` (clamp rules A3), `PlaybackStateStore` (save position/duration, clearAll, clearFor, remove, move), `UploadSessionManager` state machine (C2: sequential chunks, size checks, TTL sweep, replace-on-complete). Acceptance: `./gradlew testDebugUnitTest` green with fixtures for CRLF/BOM/`.` separator SRT, Unicode names, `..` handling, collision errors, sort order `ep2 < ep10`.

**Phase 3 — Library repository + Library tab (no thumbnails yet).** `LibraryRepository` over `filesDir/videos`; Library screen with toolbar states, rows (placeholder thumbs), badges (`[new]`/pie), folder navigation with `←`, empty/loading states, New Folder prompt, Clear All History dialog, reload triggers (A4). Seed test files via `adb push` into `/data/data/com.seedds.vplayer/files/videos/` (run-as) to verify. Acceptance: nested folders navigate, sort is natural, `[new]` badges show.

**Phase 4 — Ktor server + routes + upload page (in-process, no service yet).** `UploadRoutes` implementing every endpoint in C2 with exact error strings, `UploadPage` served from `assets/upload.html` with token substitution (C4; port the HTML verbatim from `src/server/uploadPage.ts`), activity emission (C3) into `StateFlow<UploadActivity>`; Upload tab UI (A6) with Start/Restart/Stop, port field, activity panel, per-upload cards; library refresh on `libraryChanged`. Tests: Ktor `testApplication` covering init/chunk/complete happy path, out-of-order chunk, size mismatch, complete-incomplete, replace existing file, folder collision, list/folder/rename/move/delete shapes, 404 JSON. Acceptance: from a laptop browser on the same Wi-Fi, upload a folder of files with drag & drop at concurrency 3, rename/move/delete from the browser, phone library updates live.

**Phase 5 — Foreground service, locks, LAN IP.** `UploadServerService` (dataSync type, `onTimeout` handling on API 35+, notification channel "Upload server" showing `http://ip:port` and active uploads), `POST_NOTIFICATIONS` runtime request on first Start (service works if denied), `PARTIAL_WAKE_LOCK` + `WifiLock` held only while sessions > 0, `FLAG_KEEP_SCREEN_ON` on the Activity while sessions > 0, `stopWithTask=true`. `LanAddressMonitor` via `ConnectivityManager` network callback (Wi-Fi/Ethernet, IPv4, non-loopback/link-local) with 2 s retry while unknown and a `NetworkInterface` fallback for hotspot mode. Autostart on port 8081 at app launch after the library is visible (A7). Acceptance: start server, background the app for 5 minutes with a 2 GB upload running, upload completes; stop from the notification-less path (Upload tab Stop) tears everything down; port change restarts cleanly.

**Phase 6 — Thumbnails + durations.** `MediaProbe` (MMR, concurrency 1, 4 s timeout, release on a throwaway thread on timeout), `ThumbnailCache` (10 s rule, 240 max, JPEG q90, move/delete/prune), `HydrationCoordinator` (pass A keyed on folder content, pass B after first frame and on server revision, 250 ms batched flush, per-session probed-key set). Wire into Library rows with `key = relativePath` and stable row state. Acceptance: 200-file folder scrolls smoothly during hydration; relaunch shows cached thumbs without opening media; orphan thumbs pruned after a delete.

**Phase 7 — Player core.** ExoPlayer with `PlaybackParameters(speed, 1f)`, `keepScreenOn` while playing, 500 ms position ticker; landscape lock + sticky immersive via `WindowInsetsControllerCompat`; controls overlay per B2 (top bar with clock, centre lock + transport, right speed column, bottom 44dp seek strip with labels + filename), auto-hide 2.5 s; ±10 s; resume policy; queue = current folder videos, Next + auto-advance; error overlay; pause + unlock + persist on `ON_STOP` and window-focus loss, never auto-resume; persistence cadence (B8); pop the player if the video disappears. Acceptance checklist B7/B8 items pass on phone and tablet emulator.

**Phase 8 — Gestures, boost, lock, scrub preview.** `PlayerGestures` state machine (B3) with `rememberUpdatedState` callbacks; hold-to-boost using `longPressSpeedTenths/10` bypassing the 2.0 cap and swallowing the tap; lock mode hides chrome only (all gestures still work); `SeekStrip` absolute mapping + `FramePreviewPopup` pipeline (B4: 0.05 s dedupe, single in-flight, latest-slot queue, request-id staleness, one MMR per video). Acceptance: 1-finger double tap toggles play without showing controls; 2-finger double tap locks/unlocks; single tap fires after 250 ms; hold shows `3.0×` badge and restores on release; preview follows finger continuously.

**Phase 9 — Subtitles.** Sibling `.srt` matching, `SrtParser` wired, `SubtitleOverlay` (B6 rendering: `#F2F2F2` weight 800, size from setting, line height ×44/36, 2 px black stroke drawn under fill via `TextStyle(drawStyle = Stroke(4px))`, bottom offset 54/14 depending on controls). Acceptance: cue changes within 500 ms; position shifts when controls hide; stays on old cue while dragging until commit.

**Phase 10 — Library management polish.** `SwipeRevealRow` (Rename/Delete, 176dp, one open, close on scroll/selection), long-press multi-select with anchor/range semantics, Select All/Deselect All, Move via `FolderPickerSheet` (disable rules + hints), Rename prompt with base-name preselected, Clear History (selection), Delete selection, all dialog copy from A4, artifact relink/forget on rename/move/delete, selection pruning after reload. Settings tab + picker screen (A3) with exact copy; server concurrency pushed to the page. Acceptance: A4 interaction matrix passes; renaming a folder keeps `[new]`/pie state of the videos inside it.

**Phase 11 — Release hardening.** R8 release build smoke test on device (Ktor/serialization keep rules), API 24 emulator smoke test (desugaring), tablet API 36 check (orientation lock is best-effort on large screens targeting 36; player must render in portrait too), battery-optimisation hint row in Settings (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) as the only addition beyond parity, README finalised, tag `v1.0.0-build.<code>` via the workflow.

## Verification (end-to-end)

1. `./gradlew testDebugUnitTest connectedDebugAndroidTest` green (unit: parsers/sanitizers/stores/sessions/routes; androidTest: real CIO server on an ephemeral port driven by OkHttp for 20 keep-alive chunks + finalize into `filesDir`).
2. Manual acceptance on a phone (portrait library, landscape player) and a sw600dp emulator (landscape library):
   - Upload: start/stop/restart; browser page from a laptop; drag-drop a folder tree; concurrency setting applied after refresh; unsupported types stored as `File cannot be played`; duplicate upload replaces file; kill the browser tab mid-upload → session swept after 5 min with `"Cleaned up an inactive upload."`.
   - Library: A4 matrix (tap/long-press/range/swipe/move/rename/delete/clear), empty states, folder walk-up after deleting the current folder.
   - Player: B7 checklist, including background pause → return stays paused and unlocked; end-of-video auto-advance; last video stops.
   - Hydration: no jank while thumbnails arrive; relaunch uses cache.
3. Release: CI produces `vplayer-1.0.x.apk`, installs, and the server binds 0.0.0.0:8081 on first launch.

## Notes for implementing agents

- Treat Appendices A–C as the contract; quote user-visible strings exactly. When the RN source and the appendix disagree, prefer the RN source and note the discrepancy in `docs/deviations.md`.
- Keep deliberately absent features absent (A7 last bullet, B2 last bullet, "no health endpoint/CORS/auth" in C1) unless listed in Decisions.
- Behaviours easy to get wrong: `modified = 0` when unknown (never "now"); `hasStartedPlayback` set only by position saves; near-end resume rule; boost bypasses the 2.0 cap; lock mode keeps all gestures; move continues past per-item failures (app) and returns `failures[]` (server); `..` → `folder` on listing but `Library item not found.` on mutation; rename must preserve kind; selection range never deselects and never moves the anchor.
- Ktor: consume every multipart part and `dispose()` even on error paths so keep-alive survives; validate chunk length before writing; `Cache-Control: no-store` on `/` and JSON.
- MMR is not thread-safe; one instance per dispatcher-confined owner; release on scrub end / player dispose.
- API 36 large-screen orientation restriction: tablet landscape lock is best-effort.

---


---

The full behavioural spec lives in `docs/spec-app.md` (Appendix A), `docs/spec-player.md` (Appendix B) and `docs/spec-server.md` (Appendix C).
