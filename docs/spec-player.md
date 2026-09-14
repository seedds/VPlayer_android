# Appendix B — Player spec (from `src/components/PlayerScreen.tsx`, `src/lib/subtitles.ts`, `src/lib/videoThumbnails.ts`)

## B1. Entry / queue / exit
- Inputs: `videos` = current folder's videos only (non-recursive), sorted natural/numeric case-insensitive; `currentIndex`; `subtitleFontSize` (24..48, default 36); `longPressSpeedTenths` (10..30, default 30); exit orientation (phone → portrait, tablet [shortest side ≥ 600dp] → landscape).
- `hasNext = currentIndex < videos.size-1`. No Previous, shuffle or repeat. Advancing swaps the media item on the **same** player instance (no re-creation).
- If the current video disappears from the library (deleted/renamed) the player screen pops automatically. On leaving the player the parent refreshes the library.
- Player config: keepScreenOn while playing, pitch preserved on rate change, time updates every **500 ms**.
- Resume rule (`RESUME_NEAR_END = 10 s`): saved ≤ 0 → 0; unknown duration → saved; `clamp(saved,0,dur)`; if `dur - clamped ≥ 10` → clamped else `max(dur-10, 0)`.
- Autoplay after load iff app is foregrounded, has window focus, and `playbackInterrupted == false`. Interrupted flag is set on background/blur, cleared only by an explicit play or a video change. Returning to foreground never auto-resumes.
- Exit: force-persist position, release player, restore exit orientation. Status bar hidden (light icons) in player; **nav bar is not hidden** in the original (Android rewrite: use immersive sticky mode for both bars; acceptable improvement).
- Landscape is locked for the whole player lifetime.

## B2. Layout (colours/sizes)
- Root bg `#050505`; video `contentFit=contain`. Z-order: video → subtitle overlay → boost badge → error overlay → controls or tap area.
- Palette: accent fill `rgba(31,111,104,0.9)`; progress fill `#1f6f68`; dark chip `rgba(8,12,16,0.78)`; popup `rgba(8,12,16,0.96)`; seek track `rgba(10,18,26,0.72)`; hairline `rgba(255,255,255,0.12)`; pressed opacity 0.78.
- Auto-hide: controls hide after **2500 ms** only when visible && playing && !scrubbing. Any touch-down on the background cancels the timer; release restarts it.
- **Top bar** (hidden when locked): `paddingTop = insetTop+10`, horizontal padding 18, gap 14. Left slot width 78: pill **"Back"** (padding 16×10, radius 999, accent fill, white 15sp bold). Centre: wall-clock `HH:MM` 24h, refreshed every 1 s, white 15sp semibold. Right slot width 78: pill **"Next"** only when `hasNext`.
- **Centre stack** (always rendered), gap 16: lock/unlock button 56×56 circle, dark chip fill + hairline border, 24dp white padlock icon (closed = currently unlocked, open = currently locked). Below it a transport row (minHeight 48, rendered only when unlocked): **"-10"** pill (minWidth 64, padding 16×10), **Play/Pause** pill (minWidth 92, padding 22×12, 20dp icon), **"+10"** pill; gap 14, all accent fill.
- **Speed control** (hidden when locked): vertically centred at right edge, `right = insetRight+12`, gap 12, order **+ / value / −**. Buttons 52×52 radius 16 accent fill hairline border, glyph 28sp bold. Value = rate with one decimal, no "×" suffix, white 17sp bold minWidth 52.
- **Bottom bar** (hidden when locked): `paddingBottom = insetBottom+12`, horizontal margins = side insets. Seek bar is a full-width **44dp tall strip**; the whole strip is the track (bg seek-track colour), progress = filled rect from left (`#1f6f68`), **no thumb**. Overlaid label row (padding 12): left elapsed `MM:SS`/`HH:MM:SS`, right `-remaining`; white 12sp bold. Centred file name (with extension), single line, white 12sp semibold, horizontal padding 78.
- **Boost badge** (while holding): `top = insetTop+56`, centred pill (padding 16×8, dark chip, hairline), text `"3.0×"` (rate one decimal + `×`) 15sp bold + 20dp play icon.
- **Error overlay**: centred ⚠ (40sp) + message 16sp semibold; message = player error or `"This video could not be played."`. Controls forced visible on error. No retry/skip.
- No menus, track pickers, aspect toggle, brightness/volume UI, PiP, buffering spinner.

## B3. Gestures (`DOUBLE_TAP_MS = 250`, `HOLD_MS = 500`)
- Track max touch count during a gesture (3+ fingers count as 2).
- **Single tap** (1 finger, fires after the 250 ms window): if controls visible → hide; else → show. A single 2-finger tap does nothing.
- **Double tap** = second tap within 250 ms of first with same finger count. 1-finger → toggle play/pause **without** changing control visibility (clears interrupted flag when playing). 2-finger → toggle lock, clear scrub preview, show controls.
- **Hold-to-boost**: after 500 ms of a single-finger press while playing and not scrubbing → set rate to `longPressSpeedTenths/10` (bypasses the 2.0 cap), hide controls, show boost badge, and swallow the eventual tap. A second finger cancels a pending boost. On release restore the base rate. Boost is also dropped on video change, background/blur, gesture cancel.
- Ignore all background taps while scrubbing.
- **Locked mode** only removes chrome (top bar, transport row, speed control, bottom bar). All gestures above still work; the lock button remains in the centre when controls are visible. Lock state is not persisted; reset on mount and on background/blur.
- No swipe gestures of any kind exist.

## B4. Seek bar & scrub preview
- Position mapping is absolute: `t = clamp(x,0,w)/w * duration`. Touch-down jumps the indicator to the finger.
- Down: clear preview, `scrubbing=true`, update scrubTime, force controls visible, request preview. Move: same. Up/cancel: commit.
- Commit: clear preview → `player.seekTo(t)` → update times → `scrubbing=false` → recompute subtitle → **force-persist** → controls visible. Playback is never paused for scrubbing. While scrubbing, displayed time = scrubTime and the timeUpdate path does not persist.
- Preview popup: only while scrubbing and a frame is available; 160×90, radius 12, popup bg + border `rgba(255,255,255,0.08)`, `bottom = 56` above the bar, `left = clamp(progress*w - 80, 0, w-160)`, image crop-to-fill, no fade.
- Frame pipeline: dedupe when `|t - lastPreviewed| ≤ 0.05 s`; one request in flight, a single "latest" slot overwrites queued requests; request id invalidates stale results; on failure hide popup silently. Frames are full-res from the live source (Android: `MediaMetadataRetriever.getFrameAtTime(..., OPTION_CLOSEST_SYNC)` on a background thread, scale to ~320px wide; keep one retriever open per video for speed).
- ±10 buttons: relative seek, update labels, show controls (restart timer); no forced persist.
- Time format: negative/unknown → `"--:--"`; hours>0 → `HH:MM:SS` else `MM:SS`.

## B5. Playback speed
- Base rate state starts at 1.0 each time the player screen opens; not persisted; not reset on Next. Step 0.1, clamp [0.5, 2.0], round to one decimal. Changing the base while boosting takes effect after release. On Android re-apply the rate after every media item swap.

## B6. Subtitles
- Match: `.srt` sibling in the same folder whose basename (strip last extension) equals video basename case-insensitively; first match wins. No language suffixes, no embedded tracks, no toggle, no offset.
- Parse: normalise CRLF/CR → LF, trim; split blocks on blank lines; per block trim lines and drop empties; use the first line containing `-->`; timestamps `^(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})$` with ms right-padded to 3 digits; drop the cue if either timestamp fails or `end ≤ start`; text = remaining lines joined by `\n` with `<i> <b> <u> <font …>` open/close tags stripped (case-insensitive); drop empty text; sort by start. Read as UTF-8 (Android: strip a leading BOM). Any error → no subtitles, silently.
- Active cue: first cue with `start ≤ ms ≤ end` (inclusive). Recomputed on every time update (500 ms), on resume apply, on scrub commit, after load. Not updated while dragging.
- Render: only when a cue is active; horizontal inset 18, centred, maxWidth 92%; `bottom = insetBottom + (controlsVisible && !locked ? 54 : 14)`. Text `#F2F2F2`, weight 800, size = setting sp, lineHeight = size×44/36, centred, **2 px black outline** (original draws 8 offset copies at ±2px; Android: draw stroke pass with `Paint.Style.STROKE` width 4px under fill, or use Compose `drawStyle = Stroke`).

## B7. Lifecycle
- On app background (`ON_STOP`) or window focus loss: set interrupted, cancel boost, clear preview, `scrubbing=false`, **unlock**, force-persist, pause, show controls.
- On return: only re-mark focus; stay paused.
- On end of media: force-persist (position ≈ duration, so next open resumes at `dur-10`), hide controls, auto-advance if `hasNext` (even in background). On last video: stop at end, controls hidden.
- Next button: force-persist, hide controls, advance.
- On video change: reset interrupted=false, lastPersisted=0, clear preview/cues/error, release boost, load matching SRT, read saved position, set media item, seek to resume, autoplay per rule.
- On duration known: store it via `savePlaybackDuration` (does not mark started).

## B8. Persistence cadence (entries keyed by library-relative path in the Android app)
- `persistPosition(uri,pos,force)`: non-forced writes skipped if `|pos - lastPersisted| < 2 s`. Forced on close, Next, scrub commit, interruption, end, unmount.
- `savePlaybackPosition` rejects negative/NaN; keeps old duration if new one invalid; **always sets `hasStartedPlayback=true`**; stamps `updatedAt`.
- No "watched" threshold exists in the current app (the SwiftUI doc's 95% checkmark is obsolete); the library shows `[new]` vs progress pie only.

## B9. Thumbnails
- Library thumb: time = `dur>0 ? min(10, max(0, dur-1)) : 10`; candidates `[preferred, 0]`; max 240×240, JPEG q=0.9; cache path `thumbnails/<sha1(relativePath|size|modified|10|240|240)>.jpg`. Invalidated by any change in path/size/mtime. Helpers: move on rename, delete on remove, prune orphans.
- Hydration probe (duration + thumb): concurrency 1, 4000 ms timeout per item, always release the probe.
- Scrub preview frames: never cached to disk.

## B10. Strings
`"Back"`, `"Next"`, `"-10"`, `"+10"`, `"+"`, `"-"`, `"This video could not be played."`, `"--:--"`, boost `"<rate>×"`.

---

