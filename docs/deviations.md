# Deviations from the React Native build

Everything here is a deliberate difference from `/Users/f2pgod/Documents/VPlayer`.
Behaviour not listed here follows the specs in `spec-app.md`, `spec-player.md`
and `spec-server.md`.

## Storage

- **Fresh install only.** The user asked for a clean rewrite rather than an
  in-place upgrade, so nothing migrates from the old app: no legacy settings
  files are read, and the old playback state and thumbnail cache are ignored.
- **Playback progress and thumbnails are keyed by library-relative path**
  (`Clips/ep1.mp4`) instead of an absolute `file://` URI. Renames and moves
  re-key fewer entries, and the keys survive the app data directory moving.
- **Thumbnail cache keys use SHA-1** rather than the old djb2 hash. Same inputs
  (path, size, modified time, capture settings), different digest.
- **JSON writes are atomic** (temp file plus rename). The old store wrote in
  place, where a process death mid-write could truncate saved progress.

## Server

- **`Cache-Control: no-store`** on the upload page and JSON responses. The page
  embeds the concurrency setting, and a cached copy silently ignored a change.
- **Chunk staging is handled by the HTTP layer** rather than a native plugin
  writing a temp file and passing its path in a header. The header and its
  path-confinement check no longer exist because nothing outside the server
  ever names that file.
- **Upload activity is published as state** rather than one event per chunk.
  Consumers see the latest value; the visible result is the same.

## Player

- **Both system bars are hidden** while the player is open. The old build hid
  only the status bar and left the navigation bar over the video.
- **The playback speed readout sits on a chip.** As plain white text it was
  invisible whenever the frame behind it was bright.
- **Position updates tick every 250 ms** instead of 500 ms, so the seek bar
  moves smoothly. Progress is still only written when it has moved at least two
  seconds, or at the transitions that force a write.
- **Tablet landscape lock is best-effort.** Android 16 ignores an orientation
  request on large screens for apps targeting API 36, so every screen is built
  to work in either orientation.

## Additions

- **A battery settings shortcut** in Settings. Aggressive battery management is
  the usual reason a long upload dies on a phone that is otherwise fine, and it
  cannot be fixed from inside the app.
- **A foreground service** hosts the server, with a notification and a stop
  action. The old build kept the server alive only while the screen was on.

## Not carried over

Deliberately absent, as in the original: no sorting or search UI in the library,
no breadcrumbs, no grid toggle, no dark mode, no pull-to-refresh, no tab icons,
no health endpoint, no CORS, no authentication, no upload resume, and no
volume, brightness or seek swipe gestures in the player.
