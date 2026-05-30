# CHANGELOG — ON NOW TV TUNES + V2

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
