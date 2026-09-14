# VPlayer

VPlayer is a native Android app that does two things:

- hosts a local HTTP server so you can upload files from a browser on the same Wi-Fi
- plays those local video files on the device

It is a native Kotlin rewrite of the original Expo/React Native app, built with
Jetpack Compose, Media3 (ExoPlayer) and a Ktor CIO server.

## Features

- Local HTTP upload server running on the phone, with a browser upload page.
- Browser-based uploads and library management from another device on the same Wi-Fi.
- Unicode file and folder names, including Chinese characters, are preserved.
- A folder-organised private library with thumbnails, durations and resume badges.
- Local video playback with SRT subtitles, scrub frame preview and playback speed.
- Automatic landscape playback; portrait library screens on phones.
- Player gestures:
  - single tap to show or hide controls
  - one-finger double tap to play or pause
  - two-finger double tap to lock or unlock the player controls
  - press and hold to temporarily speed up playback
- Locked player mode that hides everything except the lock button.
- Continuous scrub preview popup above the seek bar while seeking.

## Requirements

- JDK 17
- Android SDK with platform 36
- A device or emulator running Android 7.0 (API 24) or newer

## Local development

```bash
./gradlew :app:installDebug
```

Create `local.properties` with your SDK location if it is not picked up
automatically:

```properties
sdk.dir=/Users/<your-user>/Library/Android/sdk
```

## Tests

```bash
./gradlew :app:testDebugUnitTest          # parsers, stores, sanitizers, HTTP routes
./gradlew :app:connectedDebugAndroidTest  # on-device server and UI checks
```

## Release builds

Release `versionName` and `versionCode` are derived from git so local and CI
builds agree for the same commit:

- `versionName` is the next patch on the `major.minor` line of
  `vplayer.baseVersion` in `gradle.properties`, taking existing
  `v<major>.<minor>.<patch>-build.<code>` tags into account.
- `versionCode` is `1000000 + git rev-list --count HEAD`.

```bash
./gradlew :app:printVersion     # show what the next release would be called
./gradlew :app:assembleRelease
```

The APK lands at `app/build/outputs/apk/release/vplayer-<versionName>.apk` and
is arm64-v8a only.

To sign a local build with the same key as the GitHub release, so it can upgrade
an installed release build:

```bash
export VPLAYER_RELEASE_STORE_FILE=/absolute/path/to/vplayer-release.keystore
export VPLAYER_RELEASE_STORE_PASSWORD=...
export VPLAYER_RELEASE_KEY_ALIAS=...
export VPLAYER_RELEASE_KEY_PASSWORD=...

./gradlew :app:assembleRelease
```

Without those values the release build falls back to the debug keystore, and
Android will not install it over a release-signed build.

To override the computed version for a one-off build:

```bash
./gradlew :app:assembleRelease -PVPLAYER_VERSION_NAME=1.0.2 -PVPLAYER_VERSION_CODE=123
```

## App flow

1. Launch the app on an Android phone.
2. Open the Upload tab and note the local URL.
3. Visit that URL from a browser on the same Wi-Fi network and upload a video.
4. Open Library and play the uploaded file.

## Player controls

- `Back`: leave the player and save playback progress.
- `Next`: jump to the next video in the folder when available.
- `-10` / `+10`: seek backward or forward by ten seconds.
- `+` / `-` on the right edge: change playback speed between 0.5x and 2.0x.
- Scrub the progress bar to preview frames in a popup before releasing.
- Tap the center lock button to hide the rest of the controls.
- When the app is backgrounded, playback pauses, progress is saved, and the
  player returns to unlocked mode.

## Documentation

- `docs/plan.md` — build plan and architecture decisions
- `docs/spec-app.md` — app shell, data model, library and settings behaviour
- `docs/spec-player.md` — player behaviour, gestures and subtitles
- `docs/spec-server.md` — HTTP server, browser page and release process
