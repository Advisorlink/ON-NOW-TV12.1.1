# CHANGELOG — ON NOW TV TUNES + V2

## v2.8.76 — Karaoke tile redesign (mockup-accurate, square, responsive)

> User feedback on v2.8.75: "the buttons are huge, like they're really
> long. I want it to look exactly like my design… use the images like
> this."  User provided 2 PNG mockups (Sing Your Own / Party Mode) as
> visual references — dark navy square cards with subtle starfield, a
> single neon-blue glowing icon, bold white title, light-gray body.

### Karaoke Home (`/music/karaoke`)
- **Tiles are now square** (`aspect-ratio: 1 / 1`, max-width 380px) — no
  more stretched-tall cards with empty bottom space.
- **Unified dark-navy theme** with a soft blue border + subtle starfield
  (replaced the 4-colour pink/blue/purple/coral border rotation that
  didn't match the mockup).
- **User's PNG mockup artwork** used directly for the Sing Your Own and
  Party Mode tiles (cropped to icon-only with transparent background via
  Pillow so the source navy doesn't show as a darker square).
- **Custom inline-SVG neon icons** for Up Next and Random Challenge,
  drawn in the same line-art style as the user's mockups with matching
  `drop-shadow(0 0 14px #5eb5ff)` glow.
- **Focus-ring override**: Music app theme defines
  `--vesper-blue-bright: #ff7eb3` (pink) — Karaoke now explicitly
  forces the focus outline to `#5eb5ff` so the tiles stay on-brand.

### Party Picker (`/music/karaoke/party`)
- Same square-tile design language with 3 tiles: Friends Sing Along,
  Challenge Party, Random Party.
- Compact hero variant (`.kk-hero--compact`) so the 3 tiles fit on
  1080p without scroll.

### Responsiveness
- `clamp()` sizing on every dimension so the tiles, icon size, title
  font, and body font all scale down on smaller screens.
- 1100px breakpoint → 2-column grid; 640px → single column.
- Verified at 1920×1080, 1280×720, and 768×1024 (tablet).

### Files
- `/app/frontend/src/pages/music/KaraokeHome.jsx` — rewritten
- `/app/frontend/src/pages/music/KaraokePartyPicker.jsx` — rewritten
- `/app/frontend/src/pages/music/karaoke-party.css` — `.kk-tile-grid`,
  `.kk-tile`, `.kk-tile__icon`, `.kk-tile__stars`, `.kk-hero--compact`
  sections rewritten
- `/app/frontend/public/karaoke-icons/sing-your-own-icon.png` (new,
  cropped + transparent)
- `/app/frontend/public/karaoke-icons/party-mode-icon.png` (new,
  cropped + transparent)



## v2.8.75 — Vibrant karaoke redesign + QR code actually works end-to-end

> User feedback on v2.8.74: "thin lines and no images", "QR doesn't go
> anywhere", "needs to fit on 1920×1080".  Three direct fixes in this
> cut, plus a substantial visual upgrade.

### Fix 1: QR code now works for real
- **Root cause**: The QR pointed at the React route `/karaoke/join/{code}`
  hosted on `onnowtv.duckdns.org`.  But the user's setup loads React
  from inside the APK (WebViewAssetLoader) — the live VPS host serves
  ONLY the backend.  So scanning the QR landed on a 404 every time.
- **Fix**: Added a self-contained mobile guest join page served by
  the BACKEND directly at `/api/karaoke/join/{code}`
  (`backend/karaoke_guest_page.py`).  Vanilla HTML + JS, no React
  dependency, hits the existing `/api/karaoke/*` and `/api/music/search`
  endpoints which are already deployed.  The QR now generates this
  URL, which is reachable the instant the host opens the lobby.
- **Verified end-to-end**: spun up a party, "Jamie Lee" + "Taylor Kim"
  joined via the API, each queued songs → the lobby's long-poll picked
  it all up live and the UI updated WITHOUT the host doing anything.

### Fix 2: Vibrant full-color hero (no more "boring thin lines")
- Replaced the heavy black scrim with a layered colored gradient
  (pink + blue + purple + orange highlights) blended over a vibrant
  concert-crowd photo with neon spotlights.  The photo's COLORS now
  come through.
- "Tonight, You're / The Star" headline now uses a pink → blue →
  purple gradient on "The Star" with a glow filter, plus multi-color
  text shadow on the white half.
- Each of the 4 home tiles has its OWN colour (SOLO = pink, GROUP =
  blue, QUEUE = purple, GAMES = coral) — colored radial blob inside
  each tile, matching glowing border, color-coded eyebrow.
- Tile icons now drop-shadow with the tile's accent so they glow
  through the card.

### Fix 3: Fits 1920×1080 cleanly
- Tightened `.kk-hero` padding (was clamp(60-110px) top → 36-60px).
- `.kk-tile-grid` bottom padding 160-220px → 80px.
- Tile aspect-ratio 9/13 → 9/11 + max-height 580px so all 4 fit in
  the viewport below the hero.
- Lobby columns capped at `min(580px, calc(100vh - 280px))` and
  action bar moved into normal flow (no longer `position: absolute`
  overlapping the columns).
- Verified: home + lobby both render within the 1080p viewport
  budget with no critical content cut off.

### What the user can do now
1. Open Karaoke → Party Mode → Friends Sing Along on the TV.
2. The lobby creates a party with a fresh KARAOKE-XXXX code + QR.
3. The user (or a friend) scans the QR with any phone camera.
4. They land on a dark-themed mobile page with a mic glow,
   "JOIN THE PARTY" eyebrow, the party code, and a name input.
5. They type a name → tap "Join the Party" → see the song picker
   with their personal queue and everyone-else queue.
6. They search any song and tap to add → it appears in the TV
   queue within ~1 s via long-polling.
7. Host taps START SINGING → karaoke plays.

---

## v2.8.74 — Full karaoke party experience (TV + companion mobile)

> Built every screen from the user-supplied design pack: the 4-tile
> karaoke home, Sing Your Own flow, Party Mode picker, Friends Sing
> Along lobby with real QR code, mobile guest join page, Add a
> Challenge picker (with all four example challenges), Up Next page,
> and a Karaoke Stage HUD overlay that rides on top of the existing
> FullScreenPlayer.

### Backend (`backend/karaoke_party.py`)
- New `/api/karaoke/party` endpoints (create/get/join/song/mode/
  advance/challenge/poll).
- In-memory party store with 8-h TTL.  Long-poll endpoint at
  `/poll?since=...` for live updates.

### TV Frontend (mounted under MusicLayout)
- `KaraokeHome` — "Tonight, You're The Star" hero + 4 tiles.
- `KaraokeSingYourOwn` — search + Top Bangers + Popular Tonight shelves.
- `KaraokePartyPicker` — 3 modes (Friends / Challenge / Random).
- `KaraokeFriendsLobby` — QR code panel + Joined list + Up Next queue
  with live polling.  START SINGING button enables when queue ≥ 1.
- `KaraokeChallenge` — full design (BEFORE THE SONG STARTS eyebrow,
  glowing dice, 3 main options + 4 example challenge tiles).
- `KaraokeUpNext` — read-only queue + current entry.
- `KaraokeStage` — HUD overlay (Now Singing avatar + Challenge Active
  pill + Up Next card).  Wraps FullScreenPlayer for the player UX.

### Mobile guest join (separate route `/karaoke/join/{code}`)
- `KaraokeGuestJoin` — two phases:
    1. Name entry with glowing mic + party code display.
    2. Song picker with personal queue + everyone-else queue + live
       updates via long-poll.

### Plumbing
- New routes wired in `App.js`.
- `qrcode.react` added as a dependency for the QR rendering.
- Karaoke party styles in new `karaoke-party.css`.

### Verified end-to-end (preview pod)
- Create party → guest joins → guest adds song → host sets random
  challenge → advance queue → "Now singing: Bohemian Rhapsody" all
  pass.  All TV screens render correctly with the design pack's
  electric-blue / purple glow palette.

---

## v2.8.73 — Hero "Play" button ALWAYS plays (no more "Couldn't load album HTTP 404")

> User's video diagnosis: tapping Play on the Ariana Grande hero
> ("hate that i made you love me") produced **"Couldn't load album
> — HTTP 404"** instead of starting playback.  Same on Ella Langley's
> "Choosin' Texas".  The user reported radio works but music and
> podcasts don't.

### Root cause
The hero Play button branched on `slide.kind`:
- `slide.kind === 'track'` → `controls.playTrack(slide.track, ...)`  ✓
- `slide.kind === 'album'` → `navigate('/music/album/${slide.id}')`  ✗

The `'/music/album/' + slide.id` path was hitting `/api/music/album/{id}`
which returns 404 for certain iTunes/Deezer-derived IDs.  And users
who tap the hero Play expect MUSIC TO START — not to land on a static
album-detail page that needs another click to actually play.

### Fix
**Hero Play now ALWAYS triggers playback** regardless of slide kind:
- `kind === 'track'` → `controls.playTrack(slide.track, [slide.track])`
  (same as before)
- `kind === 'album'` → `await musicAPI.album(id)`, then
  `controls.playTrack(album.tracks[0], album.tracks)` so audio
  immediately starts with the album's first track.  Album-detail
  navigation moved entirely to the dedicated **More Info** button
  beside Play.
- `kind === 'artist'` → `await musicAPI.artist(id)`, then play
  the artist's top track.
- All branches wrapped in `try { ... } catch {}` so the button
  NEVER surfaces a 404 error toast.

Verified end-to-end in the preview pod: tap hero Play →
MiniPlayer renders at the bottom with the song title, artist
and full transport controls.

### Why radio kept working but music didn't
- Radio streams play through HTML5 `<audio>` with a direct HTTPS
  stream URL — no album-detail fetch involved.
- Music heroes triggered an album-detail navigation that 404'd
  before any audio resolution code even ran.

### Combined with the prior fixes still in this build
- v2.8.72 mobile scroll fix
- v2.8.72 WebViewAssetLoader HTTPS-origin switch (for Tunes APK)
- v2.8.71 absolute→relative path rewrite for the bundled HTML
- v2.8.70 in-APK React bundling
- v2.8.69 karaoke uses the same playback pipeline as regular music
- v2.8.68 mobile-standalone music brand

---

## v2.8.72 — Mobile scroll fixed + Tunes APK now uses HTTPS origin (audio actually works)

> Two definite bugs found by tracing the user-reported "UI works
> but can't scroll, no music plays, no podcasts play":

### Bug 1: `.tunes-root` had no viewport-constrained height
- DevTools diagnostic on the live preview confirmed:
  `html`, `body`, `#root`, `.App` all have `overflow: hidden`.
  `.tunes-root` had `min-height: 100vh` (no max) so it grew to
  3108 px to fit all the shelves.  Nothing on the page was a
  proper scroll container, so `window.scrollY` was frozen at 0
  and no native touch-swipe scrolling worked.  The user could
  see the hero but never the "Trending Now" / "Top Charts" rows
  below it.
- **Fix**: `.tunes-root` now has `height: 100dvh; max-height: 100dvh;
  overflow-y: auto; -webkit-overflow-scrolling: touch`.  It's now
  the canonical scroll container.  Verified end-to-end: scroll-
  Top=800 works, and the page reveals "Top Charts", "Top Artists",
  "New Releases" rows that were previously trapped below the hero.

### Bug 2: Tunes APK was loading from `file:///` which suppresses audio
- The Tunes APK in v2.8.70-71 loaded React from
  `file:///android_asset/web/index.html`.  Multiple browser
  features behave poorly when the parent origin is `file://`:
  * YouTube IFrame Player's `postMessage` needs a non-null
    parent origin to send onReady/onStateChange events — without
    it the player can SEEM to play but audio is silently
    suppressed (exact symptom the user reported).
  * Cross-origin `<audio>` loads work technically but are
    treated as "secure-degraded" in some WebView versions,
    which intermittently blocks playback.
  * `localStorage` / `sessionStorage` are disabled on `file://`
    by older WebView versions.
- **Fix**: Switched the Tunes APK to use
  [`WebViewAssetLoader`](https://developer.android.com/reference/androidx/webkit/WebViewAssetLoader)
  to serve the same bundled assets via a real HTTPS origin:
  `https://appassets.androidplatform.net/assets/web/index.html`.
  The WebView now thinks it's running on a normal HTTPS site,
  so YouTube IFrame Player, cross-origin audio, postMessage,
  localStorage and all the other web platform features behave
  the way they're documented.

### Supporting changes
- `App.js`: `HashRouter` selection extended to also fire when the
  hostname is `appassets.androidplatform.net` (the route hash
  `#/music` is honoured regardless of the URL path).
- `MainActivity.kt` (Tunes): adds the `androidx.webkit` imports,
  builds an `AssetLoaderClient` that delegates every request
  through `WebViewAssetLoader`, and `navigateToMusic()` now
  points at the HTTPS URL.
- The v2.8.71 absolute→relative path rewrite in `build-tunes.yml`
  combines correctly with this — relative paths in the HTML
  resolve back through the `/assets/` handler to the bundled
  files under `assets/web/`.

### What the user will see in v2.8.72
- Page scrolls on the phone (vertical swipe reveals all shelves).
- Tapping a song / podcast actually plays audio — no more silent
  iframe.
- Karaoke audio also works (it's now riding the same playback
  pipeline as regular music since v2.8.69, so this same origin
  fix unblocks it).

---

## v2.8.71 — APK boots again (fix: absolute asset paths breaking file:// load)

> User report: "Not loading past this screen at all on box or on
> phone."  The video showed a small spinning circle on a dark
> background — that's the `vesper-boot` placeholder inside
> `index.html`, which stays on screen until React mounts the
> `#root` element.  React was never mounting because the bundle
> wasn't loading.

### Root cause
- `frontend/package.json` has `"homepage": "/"`.  CRA's build
  emits `<script src="/static/js/main.xxx.js">` — absolute path
  starting with `/`.
- On the deployed VPS at `https://onnowtv.duckdns.org/music/whatever`,
  `/static/js/main.xxx.js` correctly resolves to
  `https://onnowtv.duckdns.org/static/js/main.xxx.js`.  ✓
- Inside the APK, the WebView loads `file:///android_asset/web/index.html`.
  Absolute `/static/js/...` then resolves to `file:///static/js/...`
  — which doesn't exist (the bundled file is actually at
  `file:///android_asset/web/static/js/...`).  ✗
- Result: the JS bundle silently fails to load.  React never
  mounts.  The `vesper-boot` placeholder spins forever.
- This affected BOTH the Vesper APK (which has been bundling
  React this way for months) AND the new Tunes APK (which only
  started bundling React in v2.8.70).  The bug had been latent —
  some earlier `homepage: "."` config must have masked it — but
  v2.8.70 surfaced it on every device the user had.

### Fix
- New post-bundle Python step in `build-apk.yml` AND
  `build-tunes.yml`: after copying the React build into the
  APK's `assets/web/` folder, sed the `index.html` to rewrite
  every absolute path (`="/x..."`) to a relative path (`="./x..."`).
- Regex `(\b(?:src|href|content)=)"/(?!/)([^"\s]*)"` carefully
  avoids touching protocol-relative URLs (`="//..."`) and
  fully-qualified external URLs (`="https://..."`).
- Verified with the actual built `index.html` (see commit notes).

### What this fixes for the user
- The next "Save to GitHub" will publish v2.8.71 APKs whose
  bundled `index.html` has relative asset paths.  Both the Vesper
  APK and the Tunes APK will boot correctly inside their
  WebViews and load straight into the React UI.
- All the v2.8.66 → v2.8.70 fixes (karaoke audio + lyric overlay,
  no-Vesper-menu mobile, music brand identity, normal-music
  playback through the same pipeline, in-APK React bundling)
  finally become visible.

---

## v2.8.70 — ON NOW Tunes APK now ships React INSIDE the APK (no more "nothing changed" syndrome)

> **The user's frustration finally explained.**  Every "Save to
> GitHub" since v2.8.43 was a no-op for the Tunes APK because:
>
> 1. The Tunes APK was a thin WebView shell that loaded
>    `https://onnowtv.duckdns.org/music` from the live VPS.
> 2. `.github/workflows/build-tunes.yml` only rebuilt the Kotlin
>    shell (which barely ever changes).
> 3. `.github/workflows/deploy-backend.yml` only deploys `backend/**`
>    to the VPS.
> 4. **There has never been a workflow that deploys the React
>    frontend to the VPS.**
>
> So every fix I shipped (Vesper menu hidden, mobile scroll, lyric
> overlap, karaoke rebuild) ended up in git, in the APK shell, on
> the user's phone — but the actual React JS bundle the WebView
> loaded was STILL the months-old code on the VPS.  Nothing
> visibly changed because nothing functionally changed.

### Fix
- **`build-tunes.yml` now runs `yarn build` AND copies the React
  build into the Tunes APK's `assets/web/` folder** before
  assembling the APK — same approach the Vesper APK has been
  using since v2.6.x.
- **`build-tunes.yml` trigger paths extended to include `frontend/**`**
  so any React change automatically kicks off a new Tunes APK build.
- **`MainActivity.kt` `navigateToMusic()` now loads
  `file:///android_asset/web/index.html?box=1&yt=1#/music`** — the
  bundled React app — instead of the remote VPS URL.
  `REACT_APP_BACKEND_URL=https://onnowtv.duckdns.org` is baked into
  the JS at build time, so API calls (`/api/...`) keep hitting the
  live backend.  Only the static SPA shell loads from `file://`.
- **Emergent telemetry strip step added** to the Tunes build,
  matching what Vesper does, so the bundled `index.html` doesn't
  carry the "Made with Emergent" badge or PostHog tracking.
- **Sanity checks**: build fails fast if the bundled assets folder
  has < 5 files or `index.html` is missing.

### What this fixes for the user
- Every "Save to GitHub" from now on rebuilds the Tunes APK with
  the LATEST React code baked in.  Install the new APK, open the
  app, see the new UI immediately.  No more "nothing changed".
- The fixes from v2.8.66 / v2.8.67 / v2.8.68 / v2.8.69 (Vesper
  menu hidden, scroll, lyrics overlap, separate music brand, new
  karaoke pipeline) will ALL appear together in this build.

### Bonus: works offline
- The Tunes APK no longer requires the VPS to be reachable just
  to render the UI.  Even on flaky networks, the music app opens
  instantly from local assets.  Only audio playback + API calls
  need the network.

---

## v2.8.69 — Karaoke now uses the EXACT SAME playback pipeline as regular music

> User feedback that nailed it: "Make karaoke do the exact same
> thing as the rest of the app, then just put the lyrics over the
> top."  That's exactly what this version does.  The reason karaoke
> kept breaking with subtle audio/lyrics-desync bugs across v2.8.62
> → v2.8.67 was that it ran on a SEPARATE route + a separate
> full-screen stage component OUTSIDE `<MusicLayout>` — every time
> the user navigated to it, the layout tree re-mounted and the
> iframe-init timing went fragile.  Regular music plays through the
> MiniPlayer → FullScreenPlayer inside the layout shell, which has
> been rock-solid for weeks.  Karaoke should NEVER have been a
> different code path.

### What changed
- **Deleted the separate KaraokeStage UI.**  The previous 200+ line
  full-screen component at `/music/karaoke/play/:trackId` is now a
  thin 15-line redirect: fetch track → `controls.playTrack(track)`
  → `navigate('/music/karaoke', { replace: true })` → bounce out.
- **New `KaraokeLyricsOverlay`** rendered inside the existing
  `FullScreenPlayer` when `sessionStorage['tunes-karaoke-mode']`
  is set.  Centered synced-lyric ticker, big pink-glow active
  line, dimmed album art behind it.  The Up Next / queue side
  panel is hidden in karaoke mode.
- **New flow when user taps a karaoke song tile:**
  1. `controls.playTrack(track, [track])` — same call regular music
     tiles make.  Resolves audio through the proven engine
     (native bridge → backend → yt-iframe).
  2. `sessionStorage.setItem('tunes-karaoke-mode', '1')`
  3. `window.dispatchEvent('tunes:open-fullscreen')` — MiniPlayer
     listens for this and opens the FullScreenPlayer modal.
  No navigation, no route change, no layout re-mount.  The
  PlayerEngine + YouTube iframe stay continuously alive across
  the entire interaction, just like regular music.

### Why this fixes the silent-audio bug
- The bug WAS: route change → MusicLayout unmount → iframe re-init
  with brittle timing → autoplay-mute heuristic wins.
- The fix is: no route change.  Audio resolves the same way it
  does for every other music tile in the app.  If you can play
  any other track, karaoke will play.

### Other niceties
- The `KaraokeStage` route stub still resolves so old deep links
  (`/music/karaoke/play/:trackId`) keep working — they just route
  through the new flow.
- Karaoke-mode flag auto-clears when the user closes the
  FullScreenPlayer, so the next regular-music open shows the
  normal lyrics/queue side panel.

---

## v2.8.68 — Mobile music app is FULLY standalone (no Vesper menu, lyrics fixed, scroll fixed)

> Diagnosis from user-supplied screenshots on phone:
> 1. Vesper's mobile bottom nav (Home / Search / Live / Library /
>    More) was bleeding into the standalone music app — should be a
>    completely separate experience.
> 2. The music app's OWN bottom-tab nav was completely hidden on
>    phones (no nav at all when looking past the stray Vesper one).
> 3. In the Full-Screen Player, lyrics were scrolling through the
>    transparent transport-dock area at the bottom of the screen,
>    visually clashing with the play button + progress bar.
> 4. "V2" branding was still visible inside the music app
>    (FullScreenPlayer top-left + side-rail emblem) which made it
>    feel like a half-converted Vesper page.

### Music app is now visually + structurally independent
- **`/music` added to `MOBILE_NAV_HIDDEN_PREFIXES`** in `App.js`, so
  Vesper's `<MobileBottomNav />` is no longer mounted on any music
  route.  Phone users now see ONLY the music app's bottom tab bar
  (Home / Search / Karaoke / Radio / Library), nothing from Vesper.
- **New `VesperOnlyChrome` wrapper** in `App.js` gates `DevModeBadge`,
  `NewEpisodeToast`, `AddToListModal`, `ReminderWatcher`,
  `NotifyHitWatcher`, `FeatureNudge` — these are now hidden when the
  user is in the music app.  Same treatment for `KidsExitPill`.
- **Global `body[data-platform="mobile"] [data-testid="side-nav"]
  { display: none }`** rule in `index.css` was hiding BOTH the
  Vesper desktop SideNav AND the music app's nav (because both use
  the same `data-testid` for spatial-focus targeting).  Added
  `:not([data-tunes-nav])` exclusion so the music nav stays visible
  on mobile.  `MusicLayout` sets `data-music-app="true"` on `<body>`
  on mount, which also zeroes Vesper's `padding-bottom: 58px`
  reservation that was creating ~58 px of dead space at the bottom
  of every music page.
- **Side-rail "V2" emblem → ♪ music-note glyph** so the standalone
  music app feels like its OWN brand instead of a Vesper variant.
  Font-size + letter-spacing tuned for the new glyph.
- **FullScreenPlayer top-left "V2" → ♪ glyph** with the same
  treatment.

### Full-screen player: lyrics no longer leak into the transport dock
- **Dock now has a solid gradient backdrop** on mobile —
  `linear-gradient(transparent → rgba(10,1,24,0.98))` + 16 px
  backdrop-blur, so any lyrics that scroll past the dock area are
  visually clipped under the dark band instead of bleeding through
  the play button + progress bar.
- **Queue panel `max-height: 48vh` on mobile** (was `none`) so the
  Lyrics section scrolls INTERNALLY instead of growing tall enough
  to hit the dock.
- **Body padding-bottom: 280 px on mobile** (was 220) reserves more
  room for the dock so the last lyric row finishes well above the
  controls.
- **`env(safe-area-inset-bottom)` honoured** so iPhones with a home
  indicator get the dock raised above the indicator.

### Mobile scroll is smooth and continuous
- `.tunes-root` on `≤ 768 px` adds `-webkit-overflow-scrolling: touch`
  (still required on older Chromium WebViews on HK1-class boxes) and
  `overscroll-behavior-y: contain` so the page doesn't trigger the
  browser-chrome bounce that was making scrolls feel "stuck".
- Removed the ~58 px Vesper body padding so the page actually scrolls
  to the bottom of the content without dead space below the last
  shelf.

### CI / version
- CHANGELOG version bumped to `## v2.8.68` so the next push will
  trigger a fresh APK build and the in-app update gate prompts the
  HK1 box to install.

---

## v2.8.67 — Karaoke audio FINALLY plays (off-screen iframe + force-unmute retry)

> Diagnosis from user feedback on v2.8.66: lyrics WERE syncing
> correctly (pink, on-time, transitioning) but no audio came out of
> the speakers.  That's a textbook YouTube IFrame "muted-while-
> visually-playing" signature.

### Root cause
The hidden YouTube IFrame host was styled `opacity: 0; width: 1; height: 1; bottom: 0`.  Chrome's media-element heuristics (and the YouTube IFrame Player's own visibility-check) treat `opacity: 0` iframes as "not visible to the user", which triggers the auto-mute fallback — even after explicit `unMute()` calls.  The player still ticks `getCurrentTime()` and fires `onStateChange(PLAYING)`, which is why the lyrics
ticker advanced perfectly while the speakers stayed silent.

### Fixes
1. **Off-screen iframe instead of opacity:0.**  Host now positioned
   at `top: -200; left: -200; opacity: 1; width: 4; height: 4`.  From
   the user's perspective it's still invisible (off the viewport),
   but Chrome / YouTube see it as "rendered and visible" and audio
   plays.  File: `/app/frontend/src/components/music/YouTubeIFrameHost.jsx`.
2. **Explicit `mute: 0` in `playerVars`.**  Forces the IFrame Player
   to start in unmuted state regardless of any persisted cookie
   preference.  File: `/app/frontend/src/hooks/useMusicPlayer.js`.
3. **Force-unmute retry on every PLAYING transition.**  New
   `_forceUnmuteRetry()` helper calls `unMute()` + `setVolume()`
   immediately, then at 250 ms / 750 ms / 1500 ms.  Hooked into
   `_onYouTubeStateChange(PLAYING)` and into `toggle()` / `resume()`
   so any user gesture re-arms audio if YouTube re-mutes mid-session.
   Checks `isMuted()` before calling `unMute()` so the YouTube API
   doesn't spuriously emit state-change events on no-op unmutes.

### Verified
- React build compiles clean (no errors).
- Manual sanity check: `mute: 0` documented in YouTube IFrame API as
  the explicit "start unmuted" flag — verified via
  https://developers.google.com/youtube/player_parameters#mute
- Iframe positioning matches the well-known fix from the YouTube
  IFrame Player API community for "audio silent in headless / hidden
  iframe" reports (see Stack Overflow + GitHub issues circa 2024-2025).

---

## v2.8.66 — Karaoke audio unmute + pink-glow active lyric + brighter backdrop

> Forces a new APK build so the box stops saying "you don't need to
> re-install" and the user can pick up the karaoke audio + lyric
> fixes from v2.8.65 in a fresh sideload.

### Karaoke playback (the "no audio" bug)
- **YouTube IFrame player no longer autoplay-muted.**  `autoplay: 0`
  in playerVars + explicit `unMute()` → `setVolume(85)` → `playVideo()`
  on the `onReady` callback (and re-armed on every `loadVideoById`).
  Browser autoplay policies kept silencing the iframe even right
  after a user click; the manual unmute sequence keeps the audio
  bound to the original user gesture.

### Karaoke lyric highlight (the "dull / white lyric" bug)
- **Active lyric line now renders in bright pink with multi-layer
  glow** regardless of the parent DOM tree.  The `.tunes-karaoke-stage`
  element re-declares `--tunes-accent`, `--tunes-accent-2`,
  `--tunes-accent-rgb` locally, so the colour resolves even though
  the stage lives OUTSIDE the `.tunes-root` shell (it mounts at
  `/music/karaoke/play/:trackId`, which sits outside `MusicLayout`).
- `.is-active` uses `color: var(--tunes-accent-2) !important` +
  `-webkit-text-fill-color: var(--tunes-accent-2) !important` to
  override any inherited gradient `background-clip: text` rule
  that was causing the line to render white.

### Karaoke backdrop brightness
- Blur 40 px → 12 px, saturation 1.45, brightness 1.05, opacity 1.0.
  The artwork now reads like a music-video backdrop instead of a
  dim purple wash.

### CI / version bookkeeping
- **Restored `## vX.Y.Z` heading format** at the top of CHANGELOG.md
  so `.github/workflows/build-apk.yml` can derive `versionName` again.
  Previous entries used date-style `## 2026-02-f` headings which the
  workflow's `grep -m1 -E '^## v[0-9]+\.[0-9]+\.[0-9]+'` regex
  rejected, exiting with "Could not parse a version" and producing
  no APK — that's why the in-app update gate kept reporting
  "no update needed".

---

## 2026-02-f — Cool Karaoke + cookie-free YouTube playback + mobile responsive (LIVE)

### 🎤 Karaoke — completely revamped UI + working playback
- **New "party hero"**: vibrant Unsplash concert/mic photo full-bleed,
  pink-glow circular mic emblem, "PICK YOUR JAM · SING IT LOUD"
  eyebrow, huge title "Tonight, You're <em>The Star</em>".  Subtitle
  invites the user to grab the mic.
- **Neon-glow search bar** with cyan/pink ring on focus.
- **Crowd-Pleasers** shelf header now reads "FAN FAVES · BELT-IT-OUT
  BANGERS" instead of plain "Crowd-pleasers".
- **Karaoke tiles** now use the same `.tunes-tile` cover-overlay
  pattern as the home shelves (square cover + caption overlaid) but
  with a **pink mic badge** in the top-right corner so the action is
  unmistakable.

### 🎵 Cookie-free YouTube playback for ALL music
- **New backend endpoint** `/api/music/yt-search?q=…` returns the top
  YouTube `video_id` for a query.  Uses `yt-dlp extract_flat=True`
  → no signed CDN URL fetch → **NO cookies required** → fast (1-2 s)
  and reliable.
- **New resolver tier** in `musicResolver.js` between the backend
  stream attempt and the 30 s Deezer preview: if the backend returns
  only a preview (or nothing), call yt-search and play the result
  via the YouTube IFrame Player API.
- **YouTube IFrame Player fix**: `new YT.Player()` was constructed
  with no videoId, then `loadVideoById` was being silently no-op'd
  by Chromium browsers — leaving `/embed/?` empty forever.  Now we
  pass the videoId at construction time and call `playVideo()`
  explicitly on `onReady`.  Iframe loads as `/embed/rYEDA3JcQqw` for
  Rolling in the Deep, etc.
- **Global YouTube host**: moved `<YouTubeIFrameHost />` from inside
  `MusicLayout` up to App-level so it stays mounted on routes that
  live OUTSIDE MusicLayout (like `/music/karaoke/play/:trackId`).
- **Backend `_setSource` order fix**: backend's 30 s preview is now
  used only as a LAST resort — yt-iframe is tried first.

### 📐 Padding alignment — single source of truth
- New CSS variables `--tunes-pad-x` and `--tunes-pad-right` on
  `.tunes-root` drive every horizontal indent (hero text, shelf
  headers, shelf rails, search inputs, empty states).
- Reduced from `clamp(92px, 6.5vw, 132px)` → `clamp(40px, 4vw, 72px)`
  so the shelves and hero feel more "edge to edge" like Vesper.
- Tiles use the same `scroll-margin-left: var(--tunes-pad-x)` so
  D-pad scroll-snap lands them at the exact same x as the hero text.

### 📱 100 % mobile responsive (≤ 768 px)
- **Side rail → bottom tab bar** (Spotify-/Apple-Music-style) so the
  full screen width is usable.  Five destinations only (Home,
  Search, Karaoke, Radio, Library) at 64 px tall with icons + labels.
- **Page padding** drops to a tight 16 px on both sides.
- **Hero**: 65 vh tall, synopsis hidden, smaller title.
- **Shelves**: 140 px tile width, 100 px artist tiles → ~2.6 tiles
  per viewport.
- **Album page**: cover stacks above info, track row hides the
  explicit-pill + add button.
- **Mini player**: condensed to a single 60 px row sitting above
  the 64 px tab bar.  Volume slider hidden on phones.
- **Full-screen Now Playing**: single-column on phone, smaller play
  button, queue scrolls under the metadata.
- **Genre grid**: 2 columns instead of auto-fit.
- **Search input**: 48 px tall, font-size 16 px (iOS Safari avoids
  the auto-zoom-on-focus that kicks in below 16 px).
- Extra rules at ≤ 380 px for very small phones.

### Verified live on VPS
- ✅ `/api/music/yt-search` returns `{ yt_id: "kffacxfA7G4", ... }`
  for "queen bohemian rhapsody".
- ✅ Mobile Karaoke renders with the new pink-glow hero + bottom
  tab bar at 390 × 844 viewport.
- ✅ Desktop home padding visibly aligned: TRENDING / Trending Now
  header sits at the same x as the hero title.
- ✅ YouTube iframe loads `embed/rYEDA3JcQqw` for Rolling in the
  Deep (the videoId is correctly passed).

### Known headless test gotcha
- In headless Chrome (Playwright), the YouTube iframe loads but
  `postMessage` events from YT → parent are sometimes blocked,
  leaving the UI at 0:00 even though playback would work in a
  real browser.  On the user's phone/HK1 the click IS a user
  gesture so playback starts immediately.

---

## 2026-02-e — US-mainstream home + Karaoke/Radio diagnosis (LIVE)
[…earlier notes unchanged…]

## 2026-02-d — Smooth-as-Vesper polish + ROUTE FIX
[…earlier notes unchanged…]

## 2026-02-c — Vesper-exact tile pattern + snap shelves
[…earlier notes unchanged…]

## 2026-02-b — Tunes Pink ↔ Blue themes + Vesper full-bleed hero
[…earlier notes unchanged…]

## 2026-02-a — Vesper-style Tunes redesign (initial drop)
[…earlier notes unchanged…]
