# Appendix A — App shell, data model, library, settings (from `App.tsx`, `src/lib/*`, `src/components/{VideoCard,FolderPickerModal,PromptModal}.tsx`)

## A1. Navigation & theme
- Root stack (no headers): `MainTabs` → bottom tabs **Library, Upload, Settings** (labels only, no icons); `SettingPicker(key)` with a header (title = setting navTitle, back label "Settings", no shadow); `Player` full-screen.
- Tab bar: bg `#efe7db`, top border `#ded1c2`, active `#1f6f68`, inactive `#4f463f`, label 12sp bold, hides when keyboard shown. Scene bg `#efe7db`. Status bar dark icons. **Light theme only, no dark mode.**
- Modals (New folder prompt, Rename prompt, Move folder picker) are hosted above the whole navigator, so they can appear over any screen.
- Tab focus side-effects: Library focus → refresh library (if not loading); Upload focus → cancel selection + refresh network; Settings focus → cancel selection. Leaving Player → clear selected video + refresh library.
- Orientation: tablet = shortest side ≥ 600dp → app locked landscape; phone → locked portrait; player always landscape; on player exit restore the tablet/phone lock. Predictive back **disabled** (`android:enableOnBackInvokedCallback="false"`).
- Palette: bg `#efe7db`; surface `#fff8f1`; raised surface/input `#fffdf9`; border `#ead8c4`; border alt `#dfcfbd`; dividers `#ded1c2`/`#e0d3c4`/`#ddcfbf`; primary teal `#1f6f68` (pressed `#175551`, tint bg `#dceeea`, selected row bg `#eef7f5`); CTA orange `#c6673d`; danger `#9e3e28`; swipe delete `#c84630` (pressed `#a93523`); secondary button `#e3d7ca`; pressed row `#e6ddd2`; text `#1d1917`, secondary `#4f463f`, muted `#6b6158`/`#70665d`/`#6f655c`/`#645a51`/`#62574e`/`#756a61`; placeholder/chevron `#8f857b`; URL text `#b35a36`; thumb bg `#d7ccc1`; folder thumb bg `#fff5eb` (icon fill `#f8f1e8`, stroke `#c97846`); scrim `rgba(20,16,12,0.45)`.
- Keep-screen-on is held exactly while ≥1 upload is in flight (in addition to the player's keep-on-while-playing).

## A2. Data model & storage
```
LibraryEntry { name, uri (absolute file URI), modified (ms epoch, 0 when unknown — never "now"), parentPath (relative or null at root), relativePath }
FolderItem   = LibraryEntry + kind=folder
VideoItem    = LibraryEntry + kind=video    + size + extension
SubtitleItem = LibraryEntry + kind=subtitle + size + extension
FileItem     = LibraryEntry + kind=file     + size + extension   // listed, manageable, never playable
UploadStatus = idle | receiving | complete | error | stopped
ActiveUploadRow { uploadId, fileName, message, updatedAt, receivedBytes, totalBytes }
UploadActivity  { status, message, updatedAt, activeUploads[], receivedBytes?, totalBytes? }
PlaybackStateEntry { positionSeconds, durationSeconds?, hasStartedPlayback?, updatedAt }   // map keyed by absolute URI
Settings { maxParallelUploads, subtitleFontSize, longPressSpeedTenths }  // ints
```
- Directory layout under `filesDir` (`/data/user/0/com.seedds.vplayer/files/`): `videos/` (whole user library, unbounded folder depth), `uploads-tmp/` (`<epochMs>-<6 base36>.upload`), `thumbnails/` (`<hash>.jpg`), `app-settings.json`, `playback-state.json`. Under `cacheDir`: `upload-chunk-temp/` (swept at server start). Ensure dirs at bootstrap, server start, and defensively before FS ops.
- Extensions: video `.mp4 .mov .m4v .webm .mkv`; subtitle `.srt`; extension = last dot-segment, lowercased. Kind order: dir → folder; subtitle check; video check; else file.
- `sanitizeFolderName`: NFC → replace chars not in `[\p{L}\p{M}\p{N}._ -]` with `_` → collapse whitespace → trim → strip dot-only names and trailing dots → trim → empty ⇒ `"folder"`.
- `sanitizeFileName`: leaf after `/`/`\` split, trim, empty ⇒ `"upload"` → NFC, same charset filter, collapse whitespace → split base/ext → base: strip dot-only, trailing dots, trim, empty ⇒ `"upload"` → `base + ext.lowercase()`.
- Path normalisation: split on `[\\/]+`, trim, drop empties, sanitise each; reject any `.`/`..` segment or zero segments (lookup returns null).
- Collisions are **errors, never auto-renamed**: `"A file or folder with that name already exists."` (create/rename), `"A file or folder with that name already exists in the destination."` (move), `"A folder cannot be moved into itself."`, `"Destination folder not found."`, `"Library item not found."`, `"Could not create the folder." / "Could not rename the item." / "Could not move the item."`. Rename to same path and move to same parent are no-ops. Rename must preserve kind: `"Keep the <ext> extension so the file stays playable."` (video must stay a video ext; subtitle must stay `.srt`).
- Sort: folders first, then name with `Collator` primary strength + numeric-aware comparison (ICU `Collator` with numeric collation, or an alphanum comparator over `Collator.PRIMARY`). Recursive video walk sorts by relativePath the same way.
- JSON stores: single in-memory cache per file, serialized mutation queue, updater may return null = no write. Corrupt file ⇒ treated as empty. Android: use atomic write (temp + rename) — allowed improvement.
- Settings migration from the RN app's legacy files: **not required** (fresh install decision). Just create `app-settings.json` with defaults on first read.
- Playback state and thumbnail cache are keyed by **library-relative path** in the Android app (the RN app keyed by absolute `file://` URI; compatibility is not required). Rename/move still re-keys entries.
- Formatting: `formatBytes` → `0 B` for ≤0; units B/KB/MB/GB/TB base 1024; 0 decimals if value ≥ 10 or unit B, else 1 (`1.5 KB`, `10 MB`, `1.0 GB`). `formatDuration` → `--:--` for unknown/negative, `HH:MM:SS` if ≥1h else `MM:SS`. `formatDate` → device-locale default date+time. `normalizePort(text, fallback)` → int in [1025, 65535] else fallback.

## A3. Settings
| key | default | options | header title | section title | subtitle | row label | footnote | display |
|---|---|---|---|---|---|---|---|---|
| maxParallelUploads | 3 | 1,2,3,4,5 | Concurrent Uploads | Concurrent uploads | Choose how many files the browser uploader can send in parallel. | Select upload count | Refresh the browser upload page to apply changes. | number |
| subtitleFontSize | 36 | 24,28,32,36,40,44,48 | Subtitle Size | Subtitle size | Choose how large subtitles appear during playback. | Select subtitle size | — | number |
| longPressSpeedTenths | 30 | 10,15,20,25,30 | Hold-to-Speed-Up | Hold-to-speed-up rate | Press and hold the video to temporarily play at this speed. | Select speed | — | `(v/10).toFixed(1)+"×"` |
- Clamp: non-number ⇒ default; else round and clamp to [first option, last option] (bounds, not membership).
- Settings screen: scroll of two panels. Panel 1 **"Upload settings"** / "Control how many files the browser uploader sends at once." → maxParallelUploads. Panel 2 **"Player settings"** / "Tune subtitles and the hold-to-speed-up gesture." → subtitleFontSize, longPressSpeedTenths. Each section: title 16sp bold, subtitle 14sp muted, a nav row (radius 18, border `#ead8c4`, bg `#fffdf9`, padding 16×14) with rowLabel left and teal value + `›` right, optional footnote.
- Panel style: radius 24, bg `#fff8f1`, border `#ead8c4`, padding 18, title 21sp/800, subtitle 14sp `#70665d`.
- Picker screen: one panel with a wrapping grid of option chips (minWidth 56, radius 16, border `#dfcfbd`, bg `#fffdf9`, padding 18×14; selected: border+text teal, bg `#dceeea`). Tap → save → pop back; on failure `Alert("Save failed", msg or "Could not save settings.")` and stay.
- Effects: maxParallelUploads is pushed to the server (applied when the browser page is next rendered); the other two are passed to the player.

## A4. Library screen
- Layout: safe-area top/left/right, content paddingTop 12, toolbar row (padding 16 horizontal, 8 bottom, gap 10), then an edge-to-edge list (rows have their own 16dp padding). Loading state = card with spinner + **"Preparing storage, network, and local upload server..."**.
- Toolbar left: selection mode → `"{n} selected"`; inside a folder → 40×40 `←` button (bg `#e3d7ca`); root → spacer. **No breadcrumbs; current folder name is not shown.**
- Toolbar right, normal: secondary buttons **"New Folder"**, **"Clear All History"** (radius 14, bg `#e3d7ca`, padding 12×8, 13sp bold `#4f463f`). Selection mode: **"Cancel"**, **"Select All"/"Deselect All"**, **"Move"**, **"Clear History"**, **"Delete"** (danger bg `#9e3e28`, text `#fff7f2`); padding 14×10. `allSelected` counts all items of any kind in the folder.
- Row: padding 16×10, bg `#fff8f1`, bottom border `#ddcfbf`, pressed `#e6ddd2`, selected `#eef7f5`. Thumb slot 54×46 radius 16 bg `#d7ccc1`: video with thumb → cover image; folder → folder glyph on `#fff5eb`; else teal box with label `"Video"`/`"SRT"`/`"File"` (11sp bold `#f6f1eb`). Title 16sp bold single line. Meta 12sp `#6b6158`: video `"<pos> / <dur>"` (e.g. `03:12 / 24:07`, `--:-- / --:--` before hydration); folder `"Folder"`; subtitle `"Subtitle file"`; file `"File cannot be played"`.
- Right: 44dp badge slot; in selection mode also a 78dp slot with a 34×30 pill indicator (border `#d8c7b6`, bg `#fff8f1`; selected → teal fill + white `✓`).
- Badge (videos only): `hasStartedPlayback != true` → text `"[new]"` 12sp bold teal; else 18dp pie: base `#f4e7da`, teal wedge from 12 o'clock clockwise by `clamp(pos/dur,0,1)` (full circle at ≥1), outline `#c7b4a5` 1.25.
- Tap: selection mode → toggle any kind; folder → open; subtitle/file → nothing; video → open Player.
- Long press: not selecting → anchor = index, selection = {item}. Selecting → if anchor null or ≥ list size → anchor = index and toggle item; else **union** the range [min(anchor,index)..max] into the selection (anchor unchanged, never deselects). Leaving selection mode clears the anchor and closes any open swipe.
- Swipe left (not in selection mode, all kinds): reveals a 176dp panel with **"Rename"** (bg teal, pressed `#175551`, text `#f2fbf9`) and **"Delete"** (bg `#c84630`, pressed `#a93523`, text `#fff3ef`), 13sp/800. Thresholds: activate after 24dp horizontal, fail after 10dp vertical, open threshold 56dp, no overshoot. Only one row open at a time; scroll start closes it; entering selection closes it; tapping an action closes then acts.
- Empty states (card radius 24 bg `#fff8f1` border `#ead8c4`): root → **"No media yet"** / **"Use the Upload tab at the bottom, open the device URL on your computer, and send a file here."**; in folder → **"This folder is empty"** / **"Use the Upload tab to add files here, or go up to another folder."**
- Dialogs (Cancel + destructive confirm): Delete one → title `Delete folder?`/`Delete file?`, message = name, confirm `Delete`, failure `Delete failed` / `Could not delete the file.`; Delete selection → `Delete selected items?`, `"{n} item(s) will be removed."`, `Delete`, failure `Could not delete the selected files.`; Clear History (selection) → `Clear playback history?`, `Saved playback positions will be reset for videos inside the selected items.`, `Clear`, failure `Clear failed` / `Could not clear playback history for the selected files.`; Clear All History → `Clear playback history?`, `This resets all saved playback positions and marks every video as new.`, `Clear`, failure `Could not clear playback history.`. Other failures: `New folder failed`, `Rename failed`, `Some items could not be moved` (lines `"<name>: <msg or 'move failed'>"`).
- Mutation flows: delete = collect videos under item(s) → remove playback entries + thumbnails → recursive delete → refresh. Clear history = reset position 0 + hasStarted false, keep duration. Rename/move = collect videos → FS op → re-key playback entries and move thumbnails (`newUri = newRoot + oldUri.removePrefix(oldRoot)`), delete thumb if move fails → refresh. Move processes items sequentially and continues past failures.
- New-folder prompt: title "New folder", confirm "Create", placeholder "Folder name". Rename prompt: title "Rename", confirm "Rename", initial = name with the **base name pre-selected** (extension unselected). Prompt: fade modal, scrim tap cancels, card maxWidth 420 radius 24, autofocus input (radius 16, border `#dfcfbd`, bg `#fffdf9`), buttons "Cancel" (bg `#e3d7ca`) + confirm (bg `#c6673d`, text `#fff7f2`, disabled at 0.5 opacity when trimmed input empty), value trimmed on submit.
- Folder picker (Move): bottom sheet slide, maxHeight 82%, top radius 28, bg `#efe7db`. Header: "Cancel" (bg `#e3d7ca`), title = current folder leaf or **"Library"**, **"Move Here"** teal (disabled 0.4 when not allowed). Body: `← ..` row when not at root; folders only (📁 name ›), rows inside a moved subtree disabled; **"No folders here."** when empty. Footer hint: `Items are already in this folder.` / `A folder cannot be moved into itself.` / `Move here into "<name>".`. Resets to root when opened; stale loads discarded.
- Reload triggers: bootstrap; Library focus; app foreground; leaving player; navigate up/into folder; after every mutation; server `onLibraryChanged` (also bumps `serverLibraryRevision`). If the current folder vanished, walk up to the nearest existing parent. Selection pruned to existing URIs after each reload. Thumbnail map persists in memory across reloads.

## A5. Media hydration (thumbnails + durations) — performance-critical design
- Constants: probe concurrency **1**; source-load timeout **4000 ms**; result flush batch **250 ms**; thumb at **10 s** (or `dur-1` for short clips, fallback 0 s), **240×240 max**, JPEG 0.9.
- Probe key = `uri|size|modified`; keep a **process-lifetime set** of probed keys (added when dequeued, released if cancelled) so each file is probed at most once per app session, even if it failed.
- `probeVideo`: if cached thumbnail exists **and** duration already known → return without touching media APIs. Else open `MediaMetadataRetriever` (Android replacement for the probe player), read duration, save via `savePlaybackDuration`, generate + cache thumbnail if missing, always release.
- Pass A (current folder): keyed on the **content** of the folder's video list (`uri|size|modified` lines), not list identity, so focus/foreground refreshes do not cancel in-flight probes. First apply cached thumbs from disk (stat only, batched), then start probes.
- Pass B (whole library): runs after first frame / interactions settle, only when `serverLibraryRevision` changes (server-side library change), recursive list → cached thumbs → probes → **prune orphan thumbnails** once complete and not cancelled.
- Results are buffered and flushed every 250 ms with copy-on-write maps so one arriving thumbnail re-composes one row (`LazyColumn` with `key = uri`, stable row state).
- Cache file: `thumbnails/<sha1(relativePath|size|modified|10|240|240)>.jpg` (RN used a djb2 hash of the absolute URI; not required to match).

## A6. Upload tab UI
- Panel 1 **"HTTP upload server"** / "Keep this tab open while sending files from your computer.": status **"Server is running"**/**"Server is stopped"** (16sp bold); URL line 18sp/800 `#b35a36` = `http://<ip>:<port>` or **"Server is running. Discovering device IP..."** or **"Server is stopped"**; label **"PORT"** + numeric input (placeholder `8081`); buttons minWidth 120 radius 16 padding 16×13: primary orange **"Restart server"**/**"Start server"**, danger **"Stop"** (disabled when stopped); pressed/disabled opacity 0.76.
- Panel 2 **"Upload activity"** / "Each finished upload appears automatically in Library.": `activity.message` + `formatDate(updatedAt)`; aggregate progress bar (h12, track `#e7d8c9`, fill teal); meta row `"{n} active upload(s)"` or **"No active uploads"** left, right `"<recv> / <total>"` or **"Upload finished"** (complete) or **"Waiting for browser upload"**; per-upload cards (radius 18, border `#ead8c4`, bg `#fffdf9`) with name, date, message, bar, `"<recv> / <total>"` + `"NN%"`; empty → card **"No active uploads"**.

## A7. App lifecycle
- Initial activity: `{status: idle, message: "Starting local server...", activeUploads: []}`; port input `"8081"`.
- Bootstrap order: load settings (push maxParallelUploads to server) → ensure dirs → refresh library → refresh network (not awaited) → `loading=false` → start server on **8081**. Errors → activity `error` with message or `"App startup failed."`. Library becomes visible before the server finishes starting.
- Server start (app side): emit `"Starting server on port {port}..."`; on success set port input to the resolved port, running=true, refresh network; on failure port=null, running=false, activity error `msg or "Unable to start the server."`.
- LAN IP: only when connected via Wi-Fi or Ethernet, address non-null and not `0.0.0.0`; poll every **2000 ms** while running && ip unknown && !loading; also refresh at bootstrap, after server start, on foreground, on Upload tab focus. Android: `ConnectivityManager` + `LinkProperties` IPv4 site-local address of the active Wi-Fi/Ethernet network.
- Foreground: refresh library + network. Background: nothing torn down; server keeps running while the process lives (see Phase 6 for the Android foreground-service decision).
- **No storage/free-space snapshot exists in the current app.** Do not add one.
- Deliberately absent: sorting UI, search, breadcrumbs, grid toggle, dark mode, pull-to-refresh, tab icons, onboarding, runtime permission prompts.

---

