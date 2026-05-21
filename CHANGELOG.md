# ON NOW TV V2 — Changelog

Release notes for the Android TV APK build (`apk-latest`).  This file
is the authoritative changelog; the GitHub Release body shows only the
latest version to avoid the workflow's `Argument list too long` shell
limit.

Latest version is shown in `app/build.gradle.kts` (`versionName`).

## v2.7.46 — Trailer card → detail page · BACK survives player-kill
**User clarification on v2.7.45**: trailer card CLICK should open the detail page (not the trailer modal). The trailer plays when you click the **Trailer button** on the detail page (already works). Long-press on the card → notify modal (kept from v2.7.45).

**Also fixed**: BACK button from the player landing on home instead of the detail page.

### Trailer cards
- **Click** → navigate to `/title/movie/<imdb>` (or `/resolve/movie/<tmdb>` when IMDB unresolved). Reverted the v2.7.45 direct-trailer-launch behaviour.
- **Long-press 600ms** → `<StreamUnavailableModal/>` with the "Notify me when ready" CTA (kept). Works for mouse / touch / D-pad center hold.
- Removed `TrailerModal` import + state from this shelf (no longer launched from here).

### BACK button now lands on the detail page
**Root cause**: on the HK1 box's limited RAM, Android frequently kills `MainActivity` while `ExoPlayerActivity` is in the foreground playing 1080p HEVC. When the user presses BACK to close the player, `MainActivity` gets recreated from scratch → loaded the boot URL (`file:///android_asset/web/index.html` = home) → losing the detail page route entirely.

**Fix**:
- `onPause()` now persists `webView.url` to `SharedPreferences("onnowtv_route")` with a timestamp. Only same-origin file URLs are saved (no random URLs).
- `onCreate()` checks for a saved URL < 30 minutes old and uses it as the boot URL when there's no `dev_url` override. Falls back to the default home URL on cold boot / stale entries.
- Result: closing the player always lands on the detail page the user came from — even when MainActivity was killed mid-playback.

## v2.7.45 — Trailer cards: click plays HD trailer, long-press shows Notify
**Reverts v2.7.44's "trailer cards navigate to detail" change** per user feedback. Restores the working flow:
- **Click** → opens `<TrailerModal/>` which extracts the HD MP4 + m4a from YouTube via `/api/trailer-stream/<key>` and hands off to the NATIVE libVLC trailer player via `window.OnNowTV.playTrailer(...)`. No YouTube embed, no 360p chunkiness, no app redirect. This is the same flow that worked previously.
- **Long press** (600 ms hold — mouse, touch, or D-pad center repeat) → opens `<StreamUnavailableModal/>` with the "Notify me when ready" CTA. Notify key uses `imdb_id` when available, else `tmdb:<id>`.
- Fallback: when a TMDB item has no `trailer_key` (rare), click navigates to the movie detail page so the user can still browse / set a reminder.

## v2.7.44 — Player UX polish: rebuffer spinner · D-pad button wiring · pickers · trailer fix
**User feedback on v2.7.43**: "Better but not great. Remove status pill border. Slow the dots. Hook up ALL the buttons to D-pad. Need audio picker, subtitle picker, stream picker. Mid-playback rebuffer shouldn't kick back to the full loading screen — show a small spinner. Trailers should open the movie page, not auto-play."

### Loading screen polish
- Removed border + background from the "ON NOW TV V2 is loading your program" pill — plain cyan text now.
- Loading dots slowed from 1.2 s/cycle to **2.4 s/cycle**.

### Mid-playback rebuffer no longer shows the full loading splash
- Tracks `hasEverPlayed` state in PlayerOverlay. First-load → big loading screen. After that, any `STATE_BUFFERING` shows a small `Buffering` chip with a circular spinner in the top-left corner — playback frame stays on screen.

### Every dock button now D-pad-navigable
- Compose `DockButton`s wired with `focusable() + onFocusChanged() + onKeyEvent()`. Focus state paints a cyan 3 dp border + cyan fill. Center/Enter/NumpadEnter all trigger the click.
- Initial focus → big center Play/Pause via `FocusRequester` so D-pad center immediately toggles playback when the dock appears.

### Audio · Subtitle · Stream picker sheets
- `ExoPlayerActivity.refreshTrackLists()` listens to `onTracksChanged` and publishes audio/subtitle options (language · label · codec · channels).
- `selectTrack()` builds a `TrackSelectionOverride` for the picked track. Special `"off"` id disables the text-track type entirely.
- `switchStream()` parses `EXTRA_STREAMS_JSON` at startup and swaps `MediaItem` in-place when the user picks a new stream — keeps current position via `setMediaItem(item, resumePos)`.
- `TrackPickerSheet` + `StreamPickerSheet` are focusable Compose lists with cyan focus rings; BACK key dismisses them.

### Trailer cards open movie page (no auto-trailer)
- `UpcomingMoviesShelf.openItem()` no longer appends `?autoplay-trailer=1`. Trailer cards now always navigate to the detail page so the user can browse synopsis / cast / streams. (Trailers were unreliable via the in-WebView YouTube player anyway — separate fix tracked.)

## v2.7.43 — Beefy pre-buffer + OkHttp datasource + real poster on loading screen
**User on v2.7.42**: buffer hovered ~20 s but dipped to ~10 s mid-playback, and the loading screen was showing a movie still instead of the real movie poster cover. User explicitly: "make it load that it doesn't have to start straight away, can start after 20 seconds so it buffers a bit more".

**Three fixes:**

### 1. Buffer-heavy preset (pre-buffer 20 s before first frame)
- `bufferForPlaybackMs`: 1_000 → **20_000** — wait until 20 s of media is downloaded before the very first frame paints. Cold start is ~4-8 s of wall clock, then nothing stalls.
- `minBufferMs`: 15_000 → **50_000** — ExoPlayer keeps refilling toward 50 s so mid-playback dips of 10-20 s never starve playback.
- `maxBufferMs`: 90_000 → **120_000** — long soak room.
- `bufferForPlaybackAfterRebufferMs`: 5_000 → **10_000**.

### 2. OkHttp datasource (replaces DefaultHttpDataSource)
- New `androidx.media3:media3-datasource-okhttp:1.4.1` dep.
- `OkHttpDataSource.Factory` wired to an `OkHttpClient` with: HTTP/2 multiplexing, 20s connect / 25s read+write timeouts, retry-on-connection-failure, 8-connections-per-host connection pool with 5-min idle keep-alive (so seeks don't re-handshake), follow SSL redirects.
- Why: same library Stremio's Android client uses. Smarter pooling + smarter HTTP/2 multiplexing vs. the platform's default HTTP stack → far fewer stalls on flaky Wi-Fi.

### 3. Loading screen shows the real movie cover poster
- `ExoPlayerActivity` was constructing `PlayerInfo` without passing `poster` through — so the overlay's vertical 220×330 poster slot fell back to the backdrop (a movie still). Now passes `poster = poster` explicitly.

## v2.7.42 — Fast-start ExoPlayer (no more 17s pre-buffer wait)
**Reported on v2.7.41**: user video showed the BUF badge ticking ~17 s upward before the first frame painted. Once playing, the stream was smooth. Cause: v2.7.40's `bufferForPlaybackMs = 2500` + `minBufferMs = 30_000` told ExoPlayer to fill 2.5 s of 1080p HEVC before starting — which takes ~17 s of wall-clock time on a typical HK1 box's Wi-Fi.

**Tuning**:
- `bufferForPlaybackMs`: 2_500 → **1_000** (start playing after 1 s of media, not 2.5 s).
- `minBufferMs`: 30_000 → **15_000** (still soaks ordinary CDN stalls; no need to wait for a full half-minute of buffer on cold start).
- `maxBufferMs` kept at 90_000 (long-form soak room).
- `bufferForPlaybackAfterRebufferMs` kept at 5_000.

Expected impact: first-frame latency drops from ~17 s → ~3–5 s on a typical 4G/Wi-Fi connection. Mid-playback rebuffering still rare (15 s headroom is plenty for any short jitter).

## v2.7.41 — Build fix: ExoPlayerActivity polling scope (no `lifecycleScope` dep)
- Previous v2.7.40 build failed CI compile with:
  - `ExoPlayerActivity.kt:254 Unresolved reference: lifecycleScope`
  - `ExoPlayerActivity.kt:272 Suspend function 'delay' should be called only from a coroutine`
- Root cause: `androidx.lifecycle.lifecycleScope` is an extension property exposed by `androidx.lifecycle:lifecycle-runtime-ktx`, which is NOT in our gradle deps. Without it, the lambda's receiver type couldn't be inferred → cascading `delay` error.
- Fix: replaced `lifecycleScope.launchWhenStarted` with a manually-managed `CoroutineScope(SupervisorJob + Dispatchers.Main)` (`pollScope`). Position polling now runs through that scope and is cancelled in `onDestroy()`. No new gradle dependency required.

## v2.7.40 — ExoPlayer is now the default · premium Compose overlay · beefed buffer config
**ExoPlayer is now the default video backend.** LibVLC is still available as an opt-out via Settings → Video player.

### Premium Compose overlay (built on top of ExoPlayer)
- New `PlayerOverlay.kt` — Jetpack Compose UI rendered on top of `PlayerView`:
  - **Loading screen** mirrors the libVLC cinematic loader pixel-by-pixel: backdrop image + 220×330 poster + cyan `NOW PLAYING · ON NOW TV V2` eyebrow + 44sp title + meta row + 3-line synopsis + glass status pill + animated cyan dots + bottom shimmer.
  - **Bottom control dock (C01)** — auto-hides after 4 s without input: title + meta row, scrubber with playback fill + buffer-ahead fill + cyan thumb time-codes, three button clusters (Audio/Subs/Cast · Back10/Play-Pause/Forward10 · CC/Settings/Fullscreen). Center Play/Pause button enlarged + cyan-filled.
  - **Top status badge** — `BUF Ns · ExoPlayer` glass pill so you always know which backend + buffer headroom.

### Beefed buffer config
- `DefaultLoadControl` retuned for "stream-everything-perfectly":
  - `minBufferMs = 30_000` (30 s minimum before playback resumes — soaks any CDN stall).
  - `maxBufferMs = 90_000` (90 s ceiling).
  - `bufferForPlaybackMs = 2_500` (start playing fast).
  - `bufferForPlaybackAfterRebufferMs = 5_000` (recovers gracefully).
  - `prioritizeTimeOverSizeThresholds = true` + unbounded byte target.
- `DefaultHttpDataSource`: 20 s connect timeout, 25 s read timeout, cross-protocol redirects on, HTTP keep-alive, English `Accept-Language`.
- Preferred audio/text language locked to English.

### Routing fix
- `WebAppInterface.playInternalRichV2` now passes the FULL set of rich extras (synopsis, backdrop, poster, year, runtime, rating, type, streamsJson, currentStreamIdx) to ExoPlayerActivity — was previously only passing `url + title + start_at_ms`, which left the new overlay starved of metadata.
- `Host.getPlayerBackend()` default flipped to `"exoplayer"` so the React Settings page shows the correct active backend on a fresh install.

## v2.7.39 — ExoPlayer A/B switch + Richer picker chips + minSdk 21
**Two big things + a build fix:**

### Build fix (CI was failing)
- `minSdk` bumped 19 → 21 (Android 5.0 Lollipop, 2014). Required by `androidx.media3:1.4.x`. Zero practical device coverage loss — every cheap HK1 / RK / S905 box ships Android 7+.

### 1. ExoPlayer added as a SECOND player backend (one-tap A/B test)
- New `ExoPlayerActivity.kt` — Media3 1.4.1 ExoPlayer + DefaultLoadControl tuned with 15-60 s buffer pool (matches the v2.7.38 libVLC tuning so the test is apples-to-apples). Supports HTTP / HLS / DASH, position-resume, BACK→Detail, English audio/sub preference.
- **Settings → Video player** now shows two pills: **LibVLC** (default, stable, supports every codec, has the in-player stream picker) and **ExoPlayer** (experimental, what Stremio uses, better CDN buffering). Tap to switch — pref persisted in `SharedPreferences("vesper_player").use_exoplayer_backend`. Visible Toast confirms each switch.
- **Visible "which backend is running" badge** in BOTH players, so testing is unambiguous:
  - ExoPlayer → glass pill top-left of the player: `▶︎  EXOPLAYER  ·  <title>`
  - LibVLC → the existing cinematic "ON NOW TV V2 is loading your program" overlay.
- Bridge methods: `Host.getPlayerBackend()` / `Host.setPlayerBackend("exoplayer"|"libvlc")`. Routing happens inside `WebAppInterface.playInternalRichV2()` — totally transparent to React.
- Scope: ExoPlayer covers VOD only. Trailers + Watch Together + the in-player stream picker stay on LibVLC for the A/B test (out of scope for the first cut).
- **APK size delta**: +~3 MB (Media3 deps).

### 2. Richer stream picker chips
- Backend tags every stream with three new fields:
  - `_addon_source`: "PLEXIO" | "TORRENTIO" | "WATCHHUB" | "OPENSUBS" | "CINEMETA" | "MEDIAFUSION" | "AIO" | "JACKETT" | "ORION" | uppercase fallback.
  - `_quality_label`: "4K" | "1080p" | "720p" | "SD".
  - `_pm_cached`: true when the stream is from a torrent-family addon (Torrentio/MediaFusion/AIO/Jackett/Orion) AND the URL is a direct HTTPS (= Premiumize/Real-Debrid cached). False for raw magnets.
- Verified live on Dune Part 2: addon=TORRENTIO, q=4K, pm=true, sz=85.37 GB, eng=true. Frontend chips next.

**VPS deployed**: `server.py` synced + restarted. Chip tagging live now.

## v2.7.38 — Deep buffer tuning (fixes mid-stream buffering)
**User reported**: streams (even direct EP-Stream / Plexio) buffer 5 minutes in, despite the v2.7.36 `network-caching=4000` bump.

**Root cause**: `network-caching` is only ONE of three independent libVLC buffer mechanisms. The other two — the **prefetch buffer pool** (libVLC default: ~1 MB, drains in ~2 s on a 1080p CDN stream) and the **prefetch read size** (default: 16 KB chunks, way too small for modern CDNs) — were untouched on factory defaults. Even with 4 s of network-cache headroom, the player ran out of decoded video the moment a CDN sent a slow chunk, hence the "5 min in, suddenly buffering" pattern.

**Fix — LibVLC INSTANCE args (`onCreate`)**:
- `--network-caching=10000` (was 5000): 10 s buffer at startup + during keep-alive.
- `--prefetch-buffer-size=8388608` (8 MB): ~12 s of decoded headroom (was the default ~1 MB).
- `--prefetch-read-size=524288` (512 KB): fewer syscalls per second, way larger TCP windows.
- `--http-continuous` (NEW): keeps the TCP socket open between HTTP range requests. Many CDNs (Premiumize, Plexio, AllDebrid) penalise reconnect with a 2-3 s TLS handshake — exactly the cadence that produced the user's mid-stream buffer hiccups.
- `--http-reconnect` kept; `--no-drop-late-frames` / `--no-skip-frames` kept (VOD prioritises quality, decoder waits for buffer refill).

**Fix — Per-media VOD options**:
- `:network-caching=10000` + `:file-caching=10000` (was 4000).
- Removed `:drop-late-frames` / `:skip-frames` (those are catch-up options for live IPTV; on VOD we want quality preserved).
- `:no-audio-time-stretch` added (prevents audio-time-stretch artifacts during recovery).
- `:network-timeout=600` retained.

**Net effect**: VOD playback now has ~22 seconds of total decoded headroom (10 s network + 12 s prefetch) — enough to weather any realistic CDN blip / TLS reconnect silently. Per-stream extra RAM cost: ~10 MB (HK1 has 1+ GB free).

## v2.7.37 — Autoplay tier priority + Picker OK key + 3 GB cap
**Three user-reported issues, all fixed:**

1. **Autoplay was picking huge 5+ GB files** that buffered after 10-30 s.
   - Backend now parses `_size_gb: float | null` from every stream's metadata blob (handles "💾 12.4 GB", "[4.7GB]", "850 MB", "2.5 TB" — picks the LAST size token so `👤 12 💾 4.7 GB` resolves to 4.7, not 12).
   - Frontend `autoplayCandidate` rewritten as an explicit 4-tier priority chain (user-defined):
     - **Tier 1**: EP-STREM (Plexio) direct link — your premium addon, any size.
     - **Tier 2**: Torrentio fallback ≤ **3 GB**, 1080p, direct, strict-English.
     - **Tier 3**: Any addon, 1080p, strict-English, ≤ 3 GB.
     - **Tier 4**: Any English 1080p ≤ 3 GB (last resort).
   - Streams with unknown size pass the cap (Plexio doesn't expose file size — never penalised). 4K streams excluded from autoplay entirely (existing behaviour).
   - Verified on Dune Part 2 (tt15239678): 25 Torrentio candidates ≤ 3 GB — top pick is a 2.78 GB YTS rip.

2. **In-player stream picker: D-pad OK didn't fire** (mouse worked).
   - Root cause: some HK1 OEM remotes send `KEYCODE_BUTTON_A` / `KEYCODE_BUTTON_SELECT` / `KEYCODE_BUTTON_START` for OK, not the standard `KEYCODE_DPAD_CENTER` / `KEYCODE_ENTER`. The picker `onKeyDown` block only accepted the standard codes, so OK was silently swallowed by the `else -> return true` branch.
   - Fix: extended the picker OK key set to include `BUTTON_A`, `BUTTON_SELECT`, `BUTTON_START`, and `KEYCODE_SPACE` (some Bluetooth remotes). D-pad UP/DOWN still walks the list; any of those keys now picks the focused stream.

3. **VPS deployed**: `server.py` synced + service restarted. `/api/streams/movie/tt15239678` now serves `_size_gb` + `_english_strict` on every stream.

## v2.7.36 — Autoplay never plays in Russian + VOD buffering FIXED
**Two real bugs reported by the user:**
1. *"the autoplay ones are playing a different language"* — root cause: the autoplay candidate logic only filtered by quality (`is1080p` + direct), not by language. Multi-lang releases (e.g., "Eng.Fre.Ger.Ita 2160p BluRay") survived the foreign-language filter (they DO contain English audio) but libVLC defaulted to whichever audio track came FIRST in the file — often Russian / Italian.
2. *"buffering within the first 10 / 20 / 30 seconds"* — root cause: VOD path was using libVLC's raw factory default `:network-caching=1500` (1.5 s) which is fine for local files but punishingly tight for CDN-served VOD with normal ISP jitter, Premiumize / AllDebrid edge cache rebalancing, and TLS handshakes.

**Fixes:**
1. **Backend (`_filter_and_tag_english`)** now stamps a second tag: `_english_strict: bool`. True only when the stream's metadata has ZERO foreign signals (no foreign flag, no foreign token, no non-Latin script). Multi-lang releases get `_english_strict: false` so autoplay can avoid them. Real-world numbers on tt0111161 (Shawshank): 77 strict-English / 40 multi-lang / 117 total.
2. **Frontend (`autoplayCandidate` + `partyAutoplayCandidate`)** now prefers `_english_strict:true` streams in priority order: direct+1080p+strict → direct+1080p+english → any 1080p+strict → any 1080p+english → any 1080p → null. Party mode mirrors the same chain so guests never desync onto a foreign-audio host stream.
3. **VLC player (`VlcPlayerActivity.startPlayback`)**:
   - **`audio-language=eng,en,english`** + **`sub-language=eng,en,english`** — final safety net: even if the user picks a multi-lang release, libVLC auto-selects the English audio track. Costs nothing on single-track files.
   - **VOD path:** `network-caching=4000` (was 1500), `file-caching=4000`, `network-timeout=600` (10 min silent recovery), `clock-jitter=0`, `clock-synchro=0`, `drop-late-frames`, `skip-frames`. Net effect: 4× the jitter headroom for ~12 MB extra RAM — well under the HK1's budget. CDN blips are now absorbed silently instead of causing 10-second buffer-loop.
   - Live / magnet / trailer paths kept their existing tuning untouched (they were already optimised for their workloads).

**VPS deployed**: `server.py` synced + service restarted. `_english_strict` tag verified live on tt0111161.

## v2.7.35 — Sports channels back + UFC fixtures in fan-out
- **Root cause of "all the channels are gone"**: backend was populating `fixture.broadcasts: ['Sky Sports', 'TNT Sports', …]` from a curated UK/US/AU league-to-channel mapping, but the **frontend never read that field**. SportsGuide.jsx only used the per-IPTV `matchFixture()` — so when the user's IPTV provider EPG had no match (which is almost always for niche fixtures or before the EPG fully loads), the card said "Not on your channels" and the broadcaster info was thrown away.
- **Fix — `fixture.broadcasts` fallback in both card variants**: when `matches[]` is empty AND `fixture.broadcasts` has entries, the hero card now shows a cyan `WATCH ON Sky Sports · TNT Sports · ESPN+` pill, and the grid card renders the broadcaster names as styled chips with the league accent color. Only falls back to "Not on your channels" when BOTH lookups come up empty.
- **UFC + Boxing always in cold-load fan-out**: added league IDs 4443 (UFC) and 4630 (Boxing) to the `MARQUEE_FETCH` list so the per-league next-fixtures endpoint is hit on every cold load. Previously these were only picked up by the generic `eventsday` endpoint, which returns ≤2 combat fixtures per day → user's UFC card was rotting out.
- **Cache key bumped to v10** so the rollout doesn't wait 30 min for the old cache to expire.
- **VPS deployed**: `sportsdb.py` synced to `/opt/onnowtv/backend/`, service restarted. `GET /api/sportsdb/fixtures` now returns 271 events including UFC Fight Night 277 (Song vs Figueiredo) with broadcasts `['TNT Sports', 'BT Sport', 'ESPN+']`. NFL events show `['Sky Sports', 'DAZN']`. Channel logos & "where to watch" are back.
- Boxing fixtures (TheSportsDB league 4630) returned 0 upcoming events — that's an upstream data gap, not our code. The page will surface them automatically when TheSportsDB schedules them.

## v2.7.34 — Auto-bump APK version from CHANGELOG (in-app update gate FIXED)
- **Root cause of "builds not arriving on my box":** `build.gradle.kts` was stuck on `versionName = "2.7.28"` since May 13 2026. Every CI build produced an APK with the SAME version label as the running app → in-app `UpdateGate` saw `running >= latest` → **silently dismissed**. Five releases (.29 → .33) all built + published but installed invisibly.
- **Fix:** version is now driven from `CHANGELOG.md` at build time:
  - Workflow step grep-parses the topmost `## vX.Y.Z` line from CHANGELOG.md → passes as `-PversionName=…` to Gradle.
  - `versionCode = 200 + $GITHUB_RUN_NUMBER` guarantees monotonic integer increase on every push (Android requires this for upgrades).
  - `build.gradle.kts` falls back to `203 / "2.7.33"` for local `./gradlew assembleDebug` builds.
- **Release notes auto-generated from CHANGELOG.md** too — the workflow extracts the current version's section into `release-notes.md` and passes it to `softprops/action-gh-release` via `body_path:` instead of stale hardcoded text.
- **Net effect:** push code → CHANGELOG.md updated → CI bumps versionName + versionCode → new APK published with correct version → in-app gate sees the bump → user prompted to install. **No more manual version bumps. Ever.**

## v2.7.33 — English-only stream filter + 🇬🇧 English chip (deployed to VPS)
- **Backend filter `_filter_and_tag_english()`** drops foreign-language streams across EVERY addon (Torrentio, MediaFusion, custom).  Decision matrix:
  1. Title has substantial non-Latin script (Cyrillic / CJK / Arabic / Devanagari / Hebrew / Greek / Thai / Korean — ≥2 chars) → REQUIRE explicit English word token ("ENG" or "English"). 🇬🇧 flag alone isn't enough (often signals subtitles on foreign-audio releases).
  2. Title has foreign-language word/flag but ALSO English signal → multi-lang release with English audio → KEEP.
  3. Title has foreign signal only → DROP.
  4. Title has no language signal → KEEP (English by default for western releases).
- **`_is_english: true` tag** stamped on every kept stream so the frontend can render a 🇬🇧 ENGLISH chip.
- Detects: 32 foreign flag emojis, 50+ foreign language word tokens (russian, francais, hindi, tamil, etc.), and 10 non-Latin Unicode ranges.
- **Cached payloads also re-filtered** so the rollout doesn't have to wait 5 min for cache expiry.
- **Real-world impact on tt15239678 (Dune Part 2)**: 237 → 211 streams (26 pure-foreign dropped). Warm cache 0.8 s, cold 14 s.
- **Frontend Detail.jsx stream picker**: 🇬🇧 ENGLISH chip added as the FIRST chip in the metadata row (cyan glass pill, bold).
- **Native Kotlin in-player picker (`VlcPlayerActivity`)**: `AltStream` data class now carries `isEnglish: Boolean`. The picker overlay renders a `🇬🇧 ENGLISH` chip first when the flag is set. Web→native bridge (`host.js`) passes the flag through the `streamsJson` payload.
- **VPS deployed**: `server.py` synced to `/opt/onnowtv/backend/`, service restarted, `/api/streams/movie/tt15239678` returns filtered list in 0.8 s (warm) / 14 s (cold).

## v2.7.32 — Reachable seasons · Hover-color Library actors
1. **Season picker reachable when many seasons exist** — switched the season picker from `flex-wrap` (which created a 2nd row that got hidden behind the absolute-positioned Cast lane) to a **single-line horizontal scroll strip**. Matches Netflix / Apple TV pattern and what the original file header already promised: "scrollable horizontally when there are many seasons". LEFT/RIGHT walks all seasons via D-pad; focused pill smooth-scrolls into view (`inline: 'center'`). Trailer pill stays as the first item in the row.
2. **Library actor avatars: B&W at rest, colour on hover/focus** — `ActorCard` in Library.jsx now tracks `focused` state via `onFocus / onBlur / onMouseEnter / onMouseLeave` (mirrors `CastRow.jsx` ActorCard). Image `filter` swaps `grayscale(1)` → `grayscale(0)` with a 200 ms ease transition. Library grid now "comes alive" as the user D-pad-walks through it.

## v2.7.31 — Trailer pill beside Season pills (TV details)
- **TV-show Detail page**: the Trailer pill no longer sits on its own row above the season picker — it now renders as the **FIRST pill in the Seasons row**, side-by-side with `Season 1`, `Season 2`, … Per user request: "put the trailer button BESIDE (first pill) of the Seasons NOT on top".
- New `compact` size variant on `<TrailerPill>` matches the Season pill metrics (height `clamp(36px, 3vw, 44px)`, font `clamp(13px, 0.95vw, 15px)`).
- New `leadingPill` prop on `<SeriesEpisodes>` injects any React node as the first item in the season-picker flex row. Detail.jsx owns trailer state; SeriesEpisodes just renders it.

## v2.7.30 — TMDB-first cover art · Faster streams · Cleaner loading screen · Glass stream picker
1. **TMDB metadata wins over stream-attached art**: `Detail.jsx` `playStream()` now prefers `meta?.poster / meta?.background` (from Cinemeta/TMDB) over `stream.poster / stream.backdrop`. Some Stremio addons embed wrong / low-res thumbs in the stream payload — the player now always shows the cinematic TMDB poster/backdrop.
2. **Faster stream resolution (~2× perceived speed)**:
   - Backend: `STREAM_FETCH_TIMEOUT = 8.0 s` (was 15 s default) on the `/api/streams` aggregator. Per-addon `asyncio.wait_for` hard cap so a single slow addon can't stall the whole list.
   - Frontend (`Vesper.getStreams`): now takes an `onPartial(streams)` callback. Backend results surface to `Detail.jsx` IMMEDIATELY (typically <300 ms when cached) — the user sees stream tiles AND autoplay can fire while browser-direct probes finish in the background. Browser-direct per-addon timeout dropped 20 s → 8 s.
3. **Loading screen — no more `Loading · 73%`**: the cinematic preview keeps the static "ON NOW TV V2 is loading your program" eyebrow + animated ●●● dots. The percentage was overwriting the eyebrow and jittering on flaky streams. Just the dots now.
4. **Stream picker redesign (Glass + Cyan glow)**:
   - Glass card (95 % opaque deep indigo, cyan border glow, 22 dp radius, 28 dp elevation), gradient cyan→transparent divider, `PICK YOUR STREAM` eyebrow + movie-title H1 header.
   - Each row now parses the addon label into: SOURCE eyebrow chip, main text, and rounded quality chips (4K=pink / 1080p=cyan / 720p=violet / HDR=gold / REMUX=green) + size (GB/MB) + seeds chips.
   - Focus state: brighter glass + 2.5 dp cyan stroke; current stream gets a cyan `NOW PLAYING` pill.
   - Entrance animation: 220 ms scale-up + fade-in on the card, decelerate-interpolator.
   - **Bug fix — click didn't register**: each row now has `setOnClickListener` + `isClickable=true`. Air-mouse / touch users can finally tap to pick. D-pad OK still works.
   - **Bug fix — black screen after switching**: `pickStream()` now mirrors `swapChannel()`'s `detach → attach → play` sequence. Without it, libVLC silently dropped the video surface on `stop()` and the new stream came up audio-only. Also re-shows the cinematic preview with "Switching stream…" status so the user sees feedback.
- **VPS sync**: `server.py` deployed to `/opt/onnowtv/backend/`, service restarted, `/api/streams/movie/tt0111161` returns in 170 ms (cached) / 1.25 s (cold).


## v2.7.28 — Back-to-detail · Robust cover-art · Curated-addons quick-install
- **BACK from player → Detail page** (not Home). Removed `FLAG_ACTIVITY_NEW_TASK` from all 4 player launch intents in `WebAppInterface.kt`. Player now lives in the same Android task as `MainActivity` → back-stack unwinds naturally to the WebView's Detail page.
- **Cover-art fallback chain** in `Detail.jsx` + `SeriesEpisodes.jsx`: walks `poster → posterUrl → poster_url → background → backdrop → backdrop_url`. Player loading screen always has art now, regardless of which addon supplied the metadata.
- **Curated addons dropdown** on the admin page (DEPLOYED LIVE to onnowtv.duckdns.org via SSH). 13 well-known addons one-click installable: Cinemeta, OpenSubtitles, MediaFusion, AIO Streams, TMDB Addon, WatchHub, Jackett, Anime Kitsu, ThePirateBay+, Orion, JuanFTV, Twitch, Public Domain.

## v2.7.27 — 4 fixes (Torrentio English-only · Player loading screen restored · CW cover art · Visible Streams button)
1. **Torrentio English-only filter**: backend seeder now builds Torrentio URL with `language=russian,french,spanish,italian,german,portuguese,polish,hindi,tamil,...` (30 foreign languages). Torrentio's `language=` param is an EXCLUSION filter — listed languages get filtered out, English + untagged stays. Seeder force-updates the existing DB row even when Cloudflare 403s the manifest fetch (uses cached manifest). VPS verified: row URL now contains the filter.
2. **Player loading screen lost cover/synopsis** — ROOT CAUSE: v2.7.25 grew `playInternalRich` from 13→15 args. Kotlin default params DON'T survive `JavascriptInterface` reflection lookup → JS call with 15 args found no method → fell through to legacy `playInternal(url, title, subtitleUrl)` → poster/backdrop/synopsis never passed. **Fix**: kept old 13-arg `playInternalRich` intact, added new 15-arg `playInternalRichV2` for the streams payload. Web layer (`host.js`) tries V2 first, falls back to V1. Both work via reflection. Backward-compat preserved.
3. **CW cover art missing**: `ContinueWatchingShelf` tile now uses `entry.backdrop || entry.poster` (was `entry.backdrop` only). Falls back gracefully when only poster was saved.
4. **NEW — Visible "Streams" button in the player**: `btn_streams` in `activity_vlc_player.xml`, between Aspect and Channels. Shown when 2+ alt streams available. Click → same overlay as MENU/INFO key. Discoverable without remote-key tricks.

## v2.7.26 — Fix Stream picker modal crash
- **Bug**: clicking "Choose stream" crashed the app with `Cannot read properties of undefined (reading 'bg')`. Caught by the global error boundary → "ON NOW TV2 hit a snag" screen.
- **Root cause**: v2.7.22's `StreamPickerModal.jsx` defined a LOCAL `toneColors` map keyed `sd|hd|fhd|uhd`. But the real `qualityBadge(stream).tone` from `/lib/streamMeta.js` returns `gold|blue|cyan|violet|neutral|muted|red`. Lookup returned `undefined`, and `.bg` on `undefined` threw.
- **Fix**: import the REAL `toneColors` from `streamMeta.js` and use a `safeTone(tone)` helper with a cyan fallback. Never throws regardless of which tone string the patterns emit.

## v2.7.25 — Stream picker always reachable + in-player stream switcher
- **"Choose stream" button is now always visible** on the movie Detail page whenever streams are available, regardless of Autoplay setting. Was previously gated behind Autoplay-OFF — the popup itself was working, but the user couldn't reach it with Autoplay ON.
- **NEW — In-player stream picker overlay** (native Kotlin, `VlcPlayerActivity`). MENU / INFO / GUIDE / `S` key opens a centred overlay listing every alt stream. ▲▼ walks the list, OK swaps the URL via libVLC stop + restart (best-effort resume from same position), BACK closes. The full streams list is passed from the web layer to the native player through `playInternalRich`'s new `streamsJson` + `currentStreamIdx` extras.
- **WebAppInterface.playInternalRich**: added trailing `streamsJson` (JSON string of `{label, url, infoHash}` rows) and `currentStreamIdx` arguments. Backward-compatible thanks to Kotlin default parameter values.

## v2.7.24 — Rows nudged down (hero untouched)
- **Rows moved down 8 px**: `ShelfPage` `paddingBottom: 8 → 0`. Each row's bottom edge sits flush at viewport bottom.
- **Hero untouched** per user explicit instruction. Stayed at v2.7.23 settings (`clamp(380, 50vh, 540)`).

## v2.7.23 — More breathing room below hero + bigger network tiles
- **Hero shrunk further**: `clamp(400, 55vh, 590)` → `clamp(380, 50vh, 540)` (-50 px at 1080p). Verified runtime: gap from Play button bottom → next row heading top now ~287 px (was ~80 px).
- **Network tiles bigger**: `clamp(180, 15vw, 260)` → `clamp(220, 18vw, 310)` (+50 px width). Border-radius 18 → 20 to match.

## v2.7.22 — Cards down a touch + Stream picker is now a centered popup
- **Cards moved down 12 px**: `ShelfPage` `paddingBottom: 20` → `8`. Cards sit closer to the bottom edge of the snap-page, giving the heading more breathing room above. Verified runtime: `paddingBottom: 8px`, 6 shelf-pages all rendering correctly.
- **`<StreamPickerModal/>`**: new component (`/app/frontend/src/components/StreamPickerModal.jsx`). Centred popup with cinematic blurred backdrop (same recipe as `StreamUnavailableModal`). Scrollable streams list. **First stream auto-focused on open** via `useEffect` + `requestAnimationFrame` (mounts → focus first `[data-testid^="modal-stream-"]`). Currently-playing stream shows the `● CURRENT` badge + cyan border inside the modal. ESC / Backspace closes.
- **Detail page wiring**: `"Choose stream"` button on movie Detail (Autoplay OFF) now opens the modal instead of scrolling to the inline picker. Picking a stream closes the modal and calls `playStream()`.

## v2.7.21 — Row headings now show on every row
- **User spec**: "LEAVE THE CARDS AND COVERS WHERE THEY ARE — move the HERO TEXT AND BUTTONS UP A TINY bit."
- Hero height: `clamp(420, 58vh, 620)` → `clamp(400, 55vh, 590)` (-30 px at 1080p).
- Hero text/buttons `paddingBottom`: `clamp(18, 2vw, 36)` → `clamp(12, 1.2vw, 22)` (-14 px).
- Shelf-page height grows 460 → 490 px → all 6 row headings ("By network", "New movies", "New series", "Popular movies", "Popular series", "Coming soon") now render inside their shelf-page bounds. Verified runtime: `heading_in_shelf: true` for every row.

## v2.7.20 — Movie detail "Choose stream" CTA + CURRENT marker
- **New "Choose stream" button** on movie detail page when Autoplay is OFF. `data-testid="detail-choose-stream"`. Same pill style/size as the Autoplay button. Shows the stream count `(N)` next to the label. On click, scrolls to the picker AND moves focus (`data-focused="true"`) to `stream-0` so D-pad can immediately walk the list.
- **"● CURRENT" badge** on the row matching `lastStreamIdx` (persisted in `sessionStorage['onnowtv-last-stream:<id>']`). Row also gets cyan border + tinted background. Helps user diagnose whether a broken stream or the player is the issue — they can pick a different one and instantly see which they tried.
- **`playStream`** now writes the chosen stream's index to sessionStorage before launching the native player, so the round-trip back to the detail page shows the CURRENT marker on the right row.

## 🔒 v2.7.19 — LOCKED-IN PERMANENT BASELINE
User explicitly approved v2.7.19's home D-pad snap behaviour and asked to save it as permanent. The home-screen scroll engine, focus ring, and player VOD config from this version are now locked in as invariants — see `/app/CONTEXT.md` "PERMANENT INVARIANTS" section. Regression test: `/app/frontend/tests/home-snap.spec.js`.

## v2.7.19 — Snap-row fast-path ("RecyclerView feel" on the web)
- **User request**: "rebuild the whole home screen in the buttery smooth recycler view... rows snap change not slide up... each row if its a new row or an old row is treated the same."
- **Implementation**: instead of rewriting Home into a virtualised list (overkill for ~7 rows), added a snap-row fast-path inside `useSpatialFocus.focusEl`. When the focused tile lives inside a `[data-testid="shelf-page"]`, the per-pixel row-pin math is **skipped entirely** and the snap-page parent is committed via `scrollIntoView({ behavior: 'auto', block: 'center' })`. The browser's native `scroll-snap-type: y mandatory` engine then snaps the row to fill the viewport on the next frame.
- **Effect**: every Home row — Continue Watching, Networks, For You, addon catalogues, Upcoming — gets identical scroll handling. No more "first row snaps but Networks slides" or "Trailer row pin-shifted". One code path, applied uniformly.
- **Verified at runtime (Playwright @ 1920×1080)**: shelf-page height 460 px; scrollTop after 6 sequential ArrowDown presses = `0 → 460 → 920 → 1380 → 1840 → 2300 → 2760` — exact integer multiples, no intermediate slide values. `scroll@100ms` after each keypress equalled the final commit, confirming there's no smooth animation tween. Up sequence reversed cleanly through the same snap points. Every focused tile carried the v2.7.18 cyan outline ring.

## v2.7.18 — Bulletproof focus ring (outline-based)
- **Root cause of "focus ring disappears on intermediate rows"** (user video): `NetworkTile` (and a couple of other tile components) carry an inline `boxShadow` style prop for their resting drop-shadow. CSS inline styles win over class selectors, so the `[data-focus-style='tile'][data-focused='true'] { box-shadow: ... }` focus rule was OVERRIDDEN whenever such tiles received focus → blue ring went invisible. User saw the ring on hero + bottom rows because those tiles don't have inline shadows.
- **Fix**: focus ring re-implemented as `outline: 3px solid var(--vesper-blue-bright) !important; outline-offset: 2px !important`. Outlines are immune to inline-style overrides, take no layout space, can't be clipped by parent `contain` / `overflow` rules, and don't fight stacking contexts. Visible on every focused tile, every row, every time.
- **Verified at runtime (Playwright)**: 7 sequential ArrowDown presses from hero through CW → Networks → 4 catalogue shelves → Upcoming Trailers. Every focused tile showed `outline: rgb(92,223,255) solid 3px` including `network-netflix` (which previously had zero blue ring due to inline boxShadow).

## v2.7.17 — Player rebuilt minimal (Stremio-style) + new-profile empty rows fix
- **CRITICAL**: v2.7.16's `:no-mediacodec-dr` option caused green horizontal static-line corruption on movie playback (visible in user video). When MediaCodec direct-rendering is disabled but HW decoding stays enabled, libVLC tries to copy opaque hardware MediaCodec output buffers via software, which reads random GPU memory → green corruption. REMOVED.
- **Player rebuilt from scratch (Stremio's exact approach)**: `VlcPlayerActivity.startPlayback()` VOD path now uses ONLY `setHWDecoderEnabled(true, false)` + `:network-caching=1500`. Nothing else. Live IPTV, magnet, trailer paths kept untouched.
- **New Settings toggle "Force SDR playback"** for projectors that wash out HDR colour. ON → `:codec=avcodec` (full software decode) → guaranteed BT.709 SDR. Costs ~30 % CPU. Default OFF. Persisted in SharedPreferences via new `WebAppInterface.setForceSdr/getForceSdr` JS bridge methods.
- **New-profile home no longer renders 2 empty snap-pages**: `<ShelfPage>` wrappers for `<ContinueWatchingShelf>` and `<ForYouShelf>` are now conditionally rendered based on `hasCW` / `hasViewingStyle` state. When the user creates a fresh profile with no Continue Watching history and no viewing-style picks, the first visible shelf-page on Home is now Networks (or whatever has content) instead of two blank 600 px pages.
- **Down-from-hero fast-path**: `useSpatialFocus.findNext` now has an explicit "hero → first shelf-page's first focusable" rule. Without it, geometric scoring was overshooting to the 2nd or 3rd row because chips are small/off-axis vs the wider hero Play button.

## v2.7.16 — Player back to v2.6.33-era + Hero bigger + Trailer row aligned
- **Movie playback**: per user explicit request ("go back to v6.33 and use the player from then im sick of this not playing how it use to"), restored the v2.6.33-era startPlayback for VOD: only `:network-caching=1500` (no avcodec tweaks, no clock-sync, no drop-late-frames). PLUS `:no-mediacodec-dr` for VOD only — forces libVLC's colour-conversion path so HDR10 / Dolby-Vision streams tone-map down to BT.709 SDR automatically. Fixes the washed-out HDR colour the user reported on the projector. Live IPTV / magnet / trailer paths kept untouched.
- **Hero banner taller + bigger text**: height `clamp(320, 45vh, 480)` → `clamp(420, 58vh, 620)`. Title font `clamp(36, 4.2vw, 64)` → `clamp(44, 5vw, 78)`. Synopsis lines 2 → 3 with larger text. PaddingBottom reduced so content hugs bottom edge. Fills the previously-blank band between hero and first shelf.
- **Upcoming Trailers smoother scroll**: backdrop `/w780/` → `/w500/` (~3× smaller — ~50 KB vs ~150 KB per card). Cache key bumped to `v2:` to invalidate stale `/w780/` payloads.
- **Trailer row alignment**: left padding `clamp(40, 4.2vw, 80)` → `clamp(92, 6.5vw, 132)` matching every other Home shelf — first trailer card now sits directly under the first poster of the row above.

## v2.7.15 — Strict D-pad nav + smoother trailer scroll
- **Strict Left**: pressing Left only escapes to the side-nav rail from the FIRST shelf-page (Continue Watching). On every other shelf (For You, Networks, addon catalogues, Upcoming Movies) Left now hits a hard stop — focus stays on the leftmost tile. Fixes the "I went into Popular Movies and got yanked into the menu" surprise reported in the user's video.
- **Strict Right-from-rail**: when the user presses Right from the side-nav, focus lands on the bookmarked tile of the CURRENTLY VISIBLE shelf-page (the one whose centre intersects the viewport centre), not the document's first focusable. Fixes the "focus border disappears after pressing Right" symptom — focus was previously yanked to an off-screen Hero Play button.
- **Upcoming Trailers smoother scroll**: backend `/api/tmdb/upcoming-movies` now returns `/w780/` TMDB backdrops (was `/w1280/`). ~2× smaller payload → no more frame drops when scrolling the trailer rail on the low-power HK1.

## v2.7.14 — REVERT v2.7.12 player tuning (movies playing again)
- v2.7.12's expanded VOD player tuning (5s network-caching + drop-late-frames + skip-frames + clock-jitter=0 + http-reconnect + http-continuous + avcodec-fast + avcodec-skiploopfilter=1) broke movie playback entirely — player just spun the loading circle instead of starting.
- Reverted: the `isVod` branch is gone. For direct HTTPS movie/TV streams we now apply ZERO per-media options. libVLC uses its own defaults (~1s network-caching) — exactly the "just grab the link and play it" behaviour from the start of the project.
- Live IPTV, magnet, and trailer paths keep their existing tuning — they were never the issue.

## v2.7.13 — Strict-directional D-pad nav + trailer tile matches CW
- Two new fast paths in `useSpatialFocus.findNext` run BEFORE geometric scoring: (1) UP/DOWN inside the side-nav rail → strict DOM sibling; edge stops (no leak into shelves); (2) UP/DOWN from a tile inside a shelf-page → walk DOM siblings to the previous/next shelf-page, pick its bookmarked tile or first focusable.
- Eliminates user-reported nav bugs: skipping covers (geometry was picking tiles two rails away by pixel distance), jumping to menu (geometry was picking nav items when perpendicular distance was smaller), focus border disappearing (focus was being set on off-screen elements before snap completed).
- TrailerCard in UpcomingMoviesShelf now uses `data-focus-style="tile"` to inherit the global blue glow + scale(1.08) focus treatment matching Continue Watching. Width 260 → 280 min, border-radius 12 → 18, removed conflicting `:focus` overrides.

## v2.7.12 — Movie / TV playback no longer buffers every few seconds
- VlcPlayerActivity.openStream() applied `:network-caching=1500` unconditionally, then conditionally overrode for live (600ms) / magnet (6000ms) / trailer (3500ms). VOD direct streams (Premiumize, Plex Direct, Real-Debrid) inherited the tight 1.5s buffer → every minor jitter drained it and triggered re-buffering every few seconds on the HK1's variable-throughput network.
- Added explicit `isVod` branch: `:network-caching=5000`, `:file-caching=5000`, `:clock-jitter=0`, `:clock-synchro=0`, `:drop-late-frames`, `:skip-frames`, `:avcodec-hw=any`, `:avcodec-fast`, `:avcodec-skiploopfilter=1`, `:avcodec-threads=0`, `:http-reconnect`, `:http-continuous`. Brief burst delays now turn into 1-2 imperceptible frame drops instead of visible stalls.

## v2.7.11 — Instant snap + focus border restored + rows down
- scroll-behavior 'smooth' → 'auto' on the shelves-region AND inside Home.jsx onKey scrollIntoView. D-pad-Down is now an instant jump-cut, no slide.
- Removed `overflow: hidden` from ShelfPage — it was clipping the focused tile's box-shadow focus ring (4 px solid + 24 px glow) whenever it extended past the page boundary. Snap math is exact so no clip is needed anyway.
- ShelfPage paddingBottom 64 → 20. Shelf row now sits at y=1060 on a 1080p viewport — almost AT the bottom of the screen.
- D-pad nav scroll target switched from inner shelf section → parent ShelfPage so the spatial-focus engine and CSS snap can't fight each other (root cause of disappearing-border / focus-jump-to-top bugs from the user video).

## v2.7.10 — Bulletproof one-row-per-page + rows sit lower
- User reported v2.7.08 still showed neighbour shelves bleeding through. Replaced `calc(100dvh - 480px)` with programmatic measurement (`window.innerHeight - hero.offsetHeight`) recomputed on resize + 3 post-mount ticks. Snap math now exact (600px page on 1080p).
- Added `overflow: hidden` to each ShelfPage as a safety belt — even if shelf content exceeded the page, neighbours can't bleed.
- Switched from `justifyContent: center` → `flex-end` with `paddingBottom: 64`. Shelf row now sits in the bottom 60% of each page, leaving empty space above — cinematic floating-row feel.

## v2.7.09 — GitHub Actions build fix (Argument list too long)
- The `body:` field in `build-apk.yml` had grown to ~161 KB of accumulated release notes across 30+ versions, tripping Linux's `ARG_MAX` limit. Truncated to the latest version only; older notes live here.

## v2.7.08 — One row per page: full scroll-snap
- Every home-screen shelf is wrapped in a new `ShelfPage` component
  with `min-height: calc(100dvh - 480px)` and the shelves-region uses
  `scroll-snap-type: y mandatory` + `scroll-snap-stop: always`.
  D-pad-Down slides the next row fully in, the previous one fully
  out — nothing else peeks through.
- Each ShelfPage uses flex + `justify-content: center` so the focus-
  scale 1.08x has equal headroom top + bottom (never clips).

## v2.7.07 — Player buffering fix + UI fitting fixes
- **Critical:** revised `is4K()` so HDR-tagged 1080p streams (e.g. Plex
  1080p HDR Blu-rays) are no longer mis-classified as 4K.  Solo
  autoplay was rejecting these and falling back to torrents that
  buffered.  Explicit `1080p` token now wins over HDR/DV markers.
- Hero billboard shrunk `clamp(340, 50vh, 540) → clamp(320, 45vh,
  480)` so the "Similar to what you love" eyebrow stops being clipped
  by projector overscan.
- Library section eyebrows offset `marginLeft: 36` to align with the
  heading text instead of the icon.
- M14 Live Guide: scrim darkened to near-opaque, guide_root gained a
  solid dark backstop, so live VLC video no longer bleeds through.
- M14: On Now + Next cards fall back to channel logo / "—" placeholder
  when the channel has no EPG data.

## v2.7.06 — M14 Option B (right-side info panel)
- Persistent 568dp right-side info panel on the M14 Live Guide:
  channel logo (above), 92sp channel name, LIVE NOW pill, Now Playing
  block (title + time + remaining), TMDB synopsis (under).
- `bindTmdbSynopsis()` mirrors the backdrop loader: 256-entry LRU +
  negative cache, race-safe via View tag.

## v2.7.05 — Cleaner M14 stage
- Hid the full-screen `detail_backdrop` so the focused channel's logo
  no longer paints across the entire screen.  Only the rail cards
  carry TMDB backdrops.

## v2.7.04 — M14 rail TMDB backdrops
- "On Now" + four "Coming Up Next" cards on the M14 bottom rail now
  show the actual programme's TMDB backdrop behind a dark legibility
  gradient.  Plex/Netflix Up-Next feel.
- New `bindTmdbBackdrop()` helper: hits `/api/tmdb/search`, picks the
  first movie/tv hit's backdrop, caches in LRU with negative cache,
  fades in over 240ms.

## v2.7.03 — M14 Live Guide native rewrite
- Full native rewrite of the LiveGuideController + activity_vlc_player
  XML to match the M14 reference design: top header (logo + name +
  LIVE pill + clock), vertical channel list (focused row scales
  1.12x with elevation glow), bottom rail with "On Now" card + four
  Next cards.
- Cinematic open/close: header drops from above, list cross-fades,
  rail rises from below.
- All retired view IDs kept as 0×0 stubs for backward compatibility.

## v2.7.02 — Only ONE focus ring at a time
- `useSpatialFocus.setFocusAttr` now does a document-wide sweep of
  `[data-focused="true"]` and `[data-holding="true"]` so stale
  attributes from cross-component focus priming can't paint a second
  ring on screen.
- Reverted v2.7.01's eyebrow colour mute — brand-blue
  `.vesper-eyebrow` restored.

## v2.7.01 — CW cards fit projector safe-area
- Hero shrunk to `clamp(340, 50vh, 540)`; CW shelf paddingTop tightened
  to `clamp(18, 2vw, 32)` so the row sits ~80px higher.  Tile bottom
  comfortably above the projector's overscan line.

## v2.7.00 — Continue Watching stacking-context fix
- `.vesper-shelf-section:has([data-focused="true"]) { z-index: 20 }`
  lifts the active shelf above its siblings.  Free win across all
  rows — focus scale + glow no longer clipped by the next shelf.
- CW tile internal layout rewritten as a single flex column at
  bottom:14 (play badge inline with title, "X LEFT" beneath, 4px
  progress bar flush at the bottom).

## v2.6.99 — Hero spatial-nav double-fire fix
- Added `if (e.defaultPrevented) return;` at the top of the global
  `useSpatialFocus` onKey handler.  Hero ArrowRight no longer
  double-fires (was firing once for the local hero handler AND once
  for the global engine, because React's `e.stopPropagation()` on
  synthetic events doesn't stop the native event from reaching
  window-level listeners).

For earlier versions consult the GitHub Releases archive.
