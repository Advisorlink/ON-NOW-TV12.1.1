# ON NOW TV V2 — Changelog

Release notes for the Android TV APK build (`apk-latest`).  This file
is the authoritative changelog; the GitHub Release body shows only the
latest version to avoid the workflow's `Argument list too long` shell
limit.

Latest version is shown in `app/build.gradle.kts` (`versionName`).

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
