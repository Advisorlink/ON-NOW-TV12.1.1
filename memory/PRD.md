# Vesper — Product Requirements Document

## Origin
The user originally asked to "rebrand my app" and uploaded a decompiled
Android APK of Nova Box (a piracy streaming app). The main agent
declined to modify the decompiled piracy codebase. The user pivoted
to building a *legitimate* alternative from scratch with the same
endpoint goal — a polished media client for their **HK1 Android TV
box** that supports **Stremio addons + Plex + Jellyfin**.

## Brand
- **Name:** Vesper
- **Aesthetic v2 (current):** "Modern / Neon-Glass" — inky near-black
  background with subtle blue undertone, single vivid neon-blue accent
  (`#5DC8FF`), Geist sans-serif typography (display + body), JetBrains
  Mono / Geist Mono for eyebrows. Intentionally non-medieval, very
  polished, 2026-modern.
- **Aesthetic v1 (rejected):** "Vespertine Observatory" with
  Cormorant Garamond serif + copper accent — user found it too
  "medieval".

## Core Personas
- **Primary:** TV-box user (HK1) controlling via remote / D-pad. 6–10 ft
  viewing distance. No mouse, no touch.
- **Secondary:** Same user opening the app full-screen in a desktop
  browser for casting / setup / debugging.

## Static Requirements
- 10-foot UI: minimum body type ~22px, hero up to 96px.
- Spatial D-pad navigation (Arrow keys + Enter) — every focusable
  element has a clear focus state.
- Performance budget tuned for low-power Android TV SoCs: minimal
  backdrop-blur on huge surfaces, prefer gradients + transforms.
- 5% overscan-safe margin.
- Single-user mode for v1 (no auth).

## Implemented (Iteration 10 — Feb 2026)
- **LeanBack-style spatial nav** — `useSpatialFocus.js` now pins the
  focused row at ~32 % of the viewport height so shelves glide under a
  stationary focus, matching Android TV's launcher feel. Cooldowns
  tightened to 75 ms (press) / 55 ms (hold).
- **Continue Watching now plays directly** — clicking a CW tile uses
  the saved `streamUrl` / `subtitleUrl` and goes straight into
  `VlcPlayerActivity` with `startAtMs = positionMs - 5 000`, skipping
  the source picker. Falls back to the Detail page only if the entry
  is missing a stream URL (older CW entries).
- **Movies persist progress** — `Detail.jsx` now passes `cwId: id` to
  `Host.playVideo`, so libVLC's `maybePersistProgress()` actually
  writes to `onnowtv_progress` for movies (previously only series
  episodes worked).
- **Player legibility scrim** — the controls overlay now lays a 40 %
  flat black scrim plus a radial centre dim (`grad_center_dim.xml`)
  behind the controls, so buttons stay readable over bright scenes.
  Top/bottom gradient bands also enlarged (140 → 200 dp, 280 → 340 dp).
- **Subtitle / Audio / Speed / Aspect focus restore** — `closePicker()`
  in `VlcPlayerActivity.kt` now re-focuses the bottom-row button that
  opened the sheet (tracked via `lastFocusedControl`) instead of
  dumping focus into the void.


## Implemented (Iteration 9 — Feb 2026)
- **Real APK with bundled frontend** — addressed user's observation
  that the previous APK was just a WebView pointing at the live
  preview URL.  Now the React build is **bundled inside the APK** as
  `assets/web/`, the WebView loads `file:///android_asset/web/index.html`,
  and only backend calls (TMDB / addons) hit the deployed server.
  - `homepage: "."` in `frontend/package.json` for relative paths.
  - `App.js` switches `BrowserRouter` → `HashRouter` automatically
    when running under `file:///` so deep links work offline.
  - `MainActivity.kt` enables `allowFileAccess`.
  - `VesperWebViewClient.kt` allows `file://` URLs, blocks unknown
    schemes, dispatches `intent://` / `magnet://` / `market://` to
    Android natively.
  - GitHub Actions workflow now: yarn install → yarn build →
    copy `build/.` → `assets/web/` → gradle assembleDebug.
  - APK version 3 → 4, versionName 1.0.1 → 1.1.0.
- **Emergent badge nuker** — `VesperWebViewClient` injects a tiny
  `MutationObserver` JS snippet on every page load that removes any
  Emergent preview badge (CSS rule + JS belt-and-braces).
- **Smaller posters** — PosterTile and NetworkPosterTile both bumped
  from `clamp(150–220px, 13.5vw)` → `clamp(120–180px, 10.5vw)`.

## Implemented (Iteration 8 — Feb 2026)
- **Tighter Home layout** — all 6 networks now fit on screen with the
  hero at 1080p without scrolling:
  - Hero height: 82vh → 68vh (min 480px)
  - Hero title: clamp 56→96px → clamp 36→64px
  - Synopsis: 4 lines → 2 lines, smaller font
  - Action buttons: scaled via clamp() — 56px → ~52px max
  - Vertical padding compressed throughout
  - Network tiles: 320px → 260px max, gap reduced
  - Section headers: mb-5 → mb-3
- **TV box stale-cache fix** — `MainActivity.kt` now wipes the
  WebView cache + cookies + history on every new APK install
  (tracked via `BuildConfig.VERSION_CODE` in SharedPreferences).
  Bumped versionCode 2 → 3, versionName "1.0.0" → "1.0.1".  This
  fixes the user's complaint that the Network pages showed old
  curated content on the box but live TMDB content on the web.

## Implemented (Iteration 7 — Feb 2026)
- **External video player handoff** — biggest win for HK1 boxes:
  - New `WebAppInterface.kt` Android JS bridge (registered as
    `window.OnNowTV`).  Web app calls
    `OnNowTV.playVideo(url, title, mime)` → bridge fires
    `Intent.ACTION_VIEW` → user's preferred player (VLC / MX Player /
    Kodi) handles playback with hardware decoding.
  - `Intent.createChooser` lets the user pick once and remember.
  - Solves: no-audio (system players bypass autoplay restrictions),
    poor performance (hardware decode), codec gaps (VLC plays
    everything), built-in subtitle picker (replacing our own when
    inside the wrapper).
  - `<queries>` declared in `AndroidManifest.xml` for Android 11+
    package visibility.
- **Performance mode** — `lib/host.js` detects the wrapper via JS
  bridge + UA; toggles `html.vesper-host-android` and `.vesper-low-end`
  classes.  CSS rules disable backdrop-blur, grain noise, ken-burns,
  pulse, and the fancy focus transforms — keeps cheap RK3318 / S905
  boxes scrolling smoothly.
- **FullscreenButton hidden inside wrapper** — the Android WebView is
  already immersive fullscreen; the browser fullscreen API was
  showing an ugly "press ESC" banner.  Hidden when `Host.isAndroid`
  or `Host.isOnNowTV`.
- **Detail.jsx + SeriesEpisodes.jsx** route Play through
  `Host.playVideo()` first, falling back to in-page `<video>`
  player when not in the wrapper.
- **`INSTALL_ON_TV.md`** prepended with VLC install instructions.

## Implemented (Iteration 6 — Feb 2026)
- **3-path TV deployment guide** at `/app/INSTALL_ON_TV.md`:
  - Path 1: TV Bro / Puffin TV browser (60s, zero build).
  - Path 2: Chrome PWA "Add to Home Screen" — full PWA manifest
    shipped at `/public/manifest.json` with logo icon + standalone
    display + landscape orientation.
  - Path 3: GitHub Actions workflow at `.github/workflows/build-apk.yml`
    auto-builds a debug APK on every push and publishes it to an
    auto-updating "apk-latest" GitHub Release.
- **APK build attempt locally** in container failed — ARM64 host
  can't run x86-64 AAPT2 reliably even with qemu-user-static.  Pivoted
  to GitHub Actions (free 2,000 min/mo Linux x86-64 runners).
- **Android wrapper updates**: applicationId → `tv.onnowtv.app`,
  versionName "1.0.0", new logo as launcher icon across all densities,
  removed obsolete adaptive-icon XML.

## Implemented (Iteration 5 — Feb 2026)
- **Rebrand to "ON NOW TV V2"** — replaced all user-visible "Vesper"
  strings while keeping internal CSS hooks (`vesper-display`,
  `vesper-mono`, etc.) untouched to avoid touching every component.
  - New logo asset: `/app/frontend/public/brand/onnowtv-logo.png`
  - SideNav: full-colour logo image with brand-blue drop-shadow,
    expanded label reads "ON NOW TV **V2**".
  - HTML `<title>`, favicon, apple-touch-icon, and meta description.
  - Backend `FastAPI(title="ON NOW TV V2")`, root endpoint returns
    `{"app": "ON NOW TV V2", "version": "1.0.0"}`, User-Agent header
    set to `OnNowTV/1.0`.
  - Android wrapper `strings.xml` (`app_name`).
  - Home footer wordmark + Sources copy.
- All 27 backend tests still passing (root assertion updated).

## Implemented (Iteration 4 — Feb 2026)
- **TMDB-powered network catalogues** — completely replaced curated
  imdb-id lists with a live TMDB integration:
  - `backend/.env` carries the user-provided TMDB v4 Bearer token.
  - `GET /api/networks/{slug}?type=tv|movie&page=N` proxies TMDB's
    `/discover` endpoint via `with_watch_providers`, with 1-hour
    backend cache.
  - `GET /api/tmdb/imdb/{type}/{tmdb_id}` resolves a TMDB id → IMDB
    id (7-day cache) so the existing `/title/{type}/{imdb}` Detail
    page keeps working unchanged.
  - Provider IDs verified live: Netflix 8 / HBO Max 1899 / Disney+
    337 / Prime Video 9 / Apple TV+ 350 / Hulu 15.
- **Frontend**:
  - `Network.jsx` rewritten — TV / Movies sub-tabs, infinite-scroll
    pagination via IntersectionObserver, "X of Y" counter (e.g. *20
    of 3,368*), dedupes overlapping pages by `tmdb_id`, persists
    sub-tab choice in `localStorage`.
  - `NetworkPosterTile.jsx` — clickable TMDB tile that lazy-resolves
    IMDB id with a loading overlay before navigating to Detail.
- Total catalogue exposed: **~40,000+ titles** across 6 networks.

### Iteration 4 Verification
- 27/27 pytest backend tests passing (added 9 new TMDB-specific
  tests in `/app/backend/tests/test_networks_tmdb.py`).
- Testing agent v3 frontend e2e: 100% — Netflix TV+Movies tabs work,
  Load More grows tiles by 20 per page, tile click resolves IMDB and
  routes to Detail with full series episode picker.

## Implemented (Iteration 3 — Feb 2026)
- **Browse-by-Network expanded** — `lib/networks.js` now ships ~30–50
  curated `{id, type}` titles per network across Netflix / HBO /
  Disney+ / Prime Video / Apple TV+ / Hulu. `Network.jsx` deduplicates
  by IMDB id, resolves each title via Cinemeta, and falls back to the
  *other* type on 404 — Disney+ now correctly mixes The Mandalorian
  (series) with Empire Strikes Back & Doctor Strange (films).
  Verified ~25–34 tiles render per network with a live "X of Y"
  counter in the hero strip.
- **Home tabs** — `HomeTabs.jsx` segmented control (`All`,
  `TV Shows`, `Movies`). Filters `useLiveShelves` by catalogue type
  and switches `useLiveHeroes` between movie/series sources. Choice
  persists in `localStorage` (`vesper-home-tab`). Networks shelf
  hides on the Movies tab.
- **Cinematic TV detail** — `SeriesEpisodes.jsx` renders inside
  `Detail.jsx` whenever `type === 'series'`. Pill-chip season picker
  + episode cards with 16:9 thumbnails, title, release date,
  ★ rating, runtime, and full synopsis. Selecting an episode reveals
  the per-episode stream list inline (`Vesper.getStreams('series',
  'ttXXXXX:S:E')`) without losing page context.

### Iteration 3 Verification
- 18/18 pytest backend tests still passing.
- Testing agent v3 frontend e2e: 100% — tabs, Network expansion,
  type fallback, season switching, episode expand-to-streams all
  green on https://rebrand-app-5.preview.emergentagent.com.

## Implemented (Iteration 2 — Feb 2026)
- **Auto-install on first launch** (`useAddons.js`) — silently installs
  Cinemeta + OpenSubtitles v3 if either is missing; persists per-default
  flag in `localStorage` (`vesper-bootstrap-attempted-v1`) so user
  removals are respected.
- **"Browse by Network" shelf** (`NetworksShelf.jsx` + `lib/networks.js`)
  on the Home screen — 6 brand-coloured 16:9 tiles (Netflix, HBO,
  Disney+, Prime Video, Apple TV+, Hulu) using each network's wordmark
  in their accent colour, no third-party logo assets.
- **`/networks/:slug` page** (`Network.jsx`) — branded gradient hero
  strip per network + grid of curated shows, each resolved via direct
  browser fetch to `https://v3-cinemeta.strem.io/meta/series/<id>.json`.
  Failures skipped silently so one dead id can't blank the page.
- **Subtitle picker** (`Player.jsx`) — passes `type` + `imdbId` from
  Detail through to `/play`; in-Player picker fetches
  `/api/subtitles/{type}/{imdbId}`, groups by language (English first),
  fetches the SRT body in-browser, converts SRT→WebVTT inline (handles
  `\r\n`, BOM, `,###` → `.###`), creates a Blob URL, and mounts a
  `<track default>` on the `<video>`. Active state surfaces a blue
  indicator dot on the subtitles button.

### Iteration 2 Verification
- 18/18 pytest backend tests passing
  (`/app/backend/tests/test_vesper_api.py` +
  `/app/backend/tests/test_subtitles_and_addons.py`).
- Testing agent v3 frontend e2e: 100% — auto-install fires on `/`,
  all 6 network tiles render, network pages each show 8–10 posters,
  subtitle picker opens / shows OFF + English rows / closes / sets
  the active-dot indicator.
- HK1 box audio confirmed: `mediaPlaybackRequiresUserGesture = false`
  is set in `MainActivity.kt` line 57 — the autoplay block is purely
  a desktop-Chrome dev-policy and will not trigger inside the WebView.

## Implemented (Iteration 1 — May 2026)
- **Design system** — neon-blue palette, Geist typography, multi-style
  focus states (tile / pill / nav / key / quiet), shelf scroll-snap,
  hero ken-burns, film-grain overlay, glass cards.
- **Spatial focus hook** — `useSpatialFocus.js` using bounding-box
  geometry for arrow-key navigation. Initial focus respects
  `data-initial-focus="true"`. Enter clicks the focused element.
- **Fullscreen** — `useFullscreen.js` with `F` key shortcut + button
  in top-right corner of every page.
- **Stremio addon backend** (`/app/backend/server.py`):
  - `POST /api/addons/install` — fetches manifest, validates, persists
    in MongoDB `addons` collection keyed by (user_id, addon_id).
  - `GET /api/addons` — list active addons for default user.
  - `DELETE /api/addons/{id}` — soft-delete (active=False).
  - `GET /api/addons/{id}/catalog/{type}/{cat}` — proxy + TTL cache
    (10 min). Supports search / skip / genre extras.
  - `GET /api/meta/{type}/{id}` — meta aggregator across installed
    addons (Cinemeta first), Cinemeta fallback even if not installed.
  - `GET /api/streams/{type}/{id}` — parallel-fetches streams from
    every installed addon supporting the resource. Tags each stream
    with `_addon_name`.
  - `GET /api/addons/suggested` — Cinemeta + OpenSubtitles + WatchHub.
- **Frontend pages** (`/app/frontend/src/pages`):
  - `Home.jsx` — Hero billboard + live shelves (real Cinemeta data
    if installed, mock catalog fallback otherwise).
  - `Sources.jsx` — Add by URL (with on-screen keyboard), installed
    list with remove, suggested addon cards.
  - `Detail.jsx` — Backdrop + meta + stream picker. Routes to player.
  - `Player.jsx` — HLS.js for `.m3u8` streams, native `<video>` for
    direct URLs.
  - `Search.jsx` — searches across addons that expose `search` extras.
- **Components** — `SideNav` (auto-expands on focus), `HeroBillboard`
  (5-item rotation, ken-burns), `Shelf`, `PosterTile`,
  `OnScreenKeyboard`, `FullscreenButton`.

### Verification
- **Backend tests:** 13/13 pass
  (`/app/backend/tests/test_vesper_api.py`).
- **Frontend e2e (testing agent):** 100% — Cinemeta installs, 8 live
  shelves with 72 real posters render on Home, D-pad focus works,
  Sources OSK works, Detail page meta + stream picker render, HLS.js
  attaches to `.m3u8` test streams.

## Implemented (Iteration 11 — Feb 2026)
- **APK ABI fix** — Previous `arm64-v8a only` build refused to install
  on most HK1 boxes (which ship 32-bit Android ROMs even on 64-bit
  SoCs).  Now ships both `armeabi-v7a` + `arm64-v8a`.  Bumped to
  versionCode 11 / versionName 1.3.0.
- **"By network" section moved down** — NetworksShelf paddingTop
  increased from `clamp(4px, 0.6vw, 10px)` → `clamp(28px, 3vw, 56px)`
  to add proper breathing room below the All / TV Shows / Movies
  tabs.
- **Demo / mock data completely removed** — deleted
  `frontend/src/data/mockCatalog.js`, stripped `MOCK_HEROES` and
  `MOCK_SHELVES` fallbacks from `HeroBillboard` and `Home`.  When no
  Cinemeta data is available, hero billboard now falls back to live
  TMDB Trending (new `/api/tmdb/trending` endpoint) instead of
  baked-in fake titles.  Hero clicks resolve TMDB → IMDB via the
  new `/resolve/:type/:tmdb_id` route then route to the existing
  Detail page.
- **Native player — cinematic preview overlay** — `VlcPlayerActivity`
  now renders a full-screen Stremio-style loading screen with:
  - Backdrop image (dim 55%) behind a vertical vignette
  - 220×330 poster on the left
  - Eyebrow "NOW PLAYING · ON NOW TV V2"
  - Big title
  - Meta line: year · ★rating · runtime · genres
  - 3-line synopsis
  - Live "Buffering · NN%" status pill driven by VLC events
  - Bottom shimmer bar
  - Fades out 1.2s after the first PLAYING event
  Meta is plumbed end-to-end via `Host.playVideo({poster, backdrop,
  synopsis, year, rating, runtime, genres})` → new
  `OnNowTV.playInternalRich` JS bridge → intent extras.
- **Native player — track picker overlay** — D-pad-navigable side
  sheet with four entry buttons in the bottom controls:
  *Subtitles*, *Audio*, *Speed*, *Aspect*.
  Each opens a RecyclerView of options pulled directly from VLC at
  runtime (`mediaPlayer.spuTracks`, `mediaPlayer.audioTracks`) plus
  static lists for playback speed (0.5×–2×) and aspect ratio
  (`SURFACE_BEST_FIT`, `SURFACE_FILL`, `SURFACE_16_9`, `SURFACE_4_3`,
  `SURFACE_ORIGINAL`).  BACK closes the sheet.  Track rows have an
  active indicator dot + custom blue focus ring drawable.
- **Recyclerview dep added** — `androidx.recyclerview:recyclerview:1.3.2`.
- **New drawables** — `preview_vignette`, `poster_bg`, `status_pill`,
  `track_row_bg`, `track_dot_on`, `track_dot_off`.


- **APK Kotlin compile fix** — `VlcPlayerActivity.kt` failed Gradle
  compile with `Unresolved reference: Slave`. In libvlc-android
  3.6.0, the `Slave` class lives on `IMedia` (not `Media`).  Imported
  `org.videolan.libvlc.interfaces.IMedia` and switched the call to
  `IMedia.Slave.Type.Subtitle`.  GitHub Actions APK build now passes.
- **Spatial D-pad scroll jitter eliminated** — Root cause: the shelf
  had `scroll-snap-type: x mandatory` + `scroll-behavior: smooth`
  in CSS, which fought against JS-controlled `scrollBy({behavior:
  'smooth'})` in `useSpatialFocus`.  Scroll-snap re-snapped to the
  nearest tile *after* the JS scroll, producing the "jump forward /
  jump back" rubber-band.  Removed both CSS scroll-snap and CSS
  smooth scroll on the shelf and on `<main>`; the hook now owns
  smooth scroll exclusively.  Also rewrote `focusEl` to compute its
  own vertical delta against a 22%–70% viewport band (never calling
  `scrollIntoView`).
- **Tile pop-out on focus** — On Android WebView, `:focus-visible`
  does not always engage for programmatic `.focus()`.  The CSS
  rules for `scale(1.07)` + glow ring already supported
  `[data-focused='true']`; the hook now tracks the active element
  and toggles that attribute on focus, so the pop-out reliably
  triggers on D-pad navigation.
- **Home covers shifted up** — Hero billboard reduced from
  `clamp(380px, 56vh, 620px)` → `clamp(300px, 42vh, 480px)`.
  Shelf section padding-top reduced (32 → 14px max) and inner row
  paddings rebalanced.  NetworksShelf top/bottom paddings tightened.
  On a 1080p screen the hero + tabs + 6 network tiles + first
  "Popular" row all fit above the fold.


## Backlog (Prioritised)

### P0 — Next
- **Plex integration** — plex.tv OAuth PIN flow, server discovery,
  library browsing, direct-stream URLs. Add `/sources` card.
- **Jellyfin integration** — server URL + username/password,
  `/Users/AuthenticateByName`, browse, stream. Add `/sources` card.

### P1
- **VLC overlay controls** — D-pad-driven track switcher (subtitle,
  audio, playback speed) inside `VlcPlayerActivity`.
- **My Library** page — favorites + watchlist + watch-history.
- **Settings** page — per-user prefs (autoplay, language, region,
  quality cap).
- **Search keyboard** — speech input on supported boxes.

### P2
- Multi-user auth (Emergent Google login or JWT).
- Watch-progress sync.
- Cast / continue-watching cross-device.
- ErrorBoundary at the app root.
- Network catalog refinement: `lib/networks.js` mixes a few movie ids
  inside the series-only meta fetch — they 404 and are silently
  skipped. Cleanup or `(imdbId, type)` pairs would tighten this.

## Non-Goals
- We will not modify, repackage, or distribute the decompiled
  Nova Box / NovaMobile APK or any derivative of it.
- We will not bundle piracy stream-aggregator addons into the
  suggested-addons list. Users may install whatever third-party
  addon URL they choose; that responsibility is theirs.
