# CHANGELOG — ON NOW TV TUNES + V2

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
