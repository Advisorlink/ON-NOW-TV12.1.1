# CHANGELOG — ON NOW TV TUNES + V2

## 2026-02 — Vesper-style Tunes redesign (LIVE on VPS)

### Music app frontend (`/app/frontend/src/pages/music`)
- **Complete visual overhaul** to mirror Vesper's polished home/billboard
  feel, adapted for music.  Three deliverables driven by the user's
  reference designs:

  1. **Music Home** (`MusicHome.jsx`)
     - Rotating hero billboard cycles through trending tracks +
       new releases (~9.5 s cadence) with blurred album-cover
       background, cyan glow ring, eyebrow / huge title / meta /
       Play · More Info · Add to Library pills.
     - "Trending Now" shelf — **square** album-cover tiles
       with title + artist underneath (per the user's request).
     - "Top Charts" shelf — square track tiles.
     - "Top Artists" shelf — round artist tiles.
     - "New Releases" shelf.
     - **NEW Moods grid** — six colour-gradient mood tiles
       (Chill, Energetic, Romantic, Focus, Party, Sunshine) that
       deep-link into `/music/search?q=<curated query>`.
     - "Browse Genres" photographic grid (when API returns
       genre images).

  2. **Album detail** (`MusicAlbum.jsx`)
     - Big cover top-left + "ALBUM" eyebrow + uppercase display
       title + cyan "by ARTIST" link + meta row + synopsis.
     - Play Album (white pill) · Shuffle · Add to Library
       (cyan toggle when liked) · ⋯ more.
     - Track list with the currently-playing row tinted cyan
       (matches the Neon Dreams reference).

  3. **Full-screen Now Playing** (`FullScreenPlayer.jsx`)
     - V2 cyan emblem + "NOW PLAYING" eyebrow top-left.
     - Cover artwork (1:1) on the left with **animated neon
       ring** backdrop.
     - Center column: huge title + cyan artist + album + year /
       Full Track or 30 s Preview chip + heart-like toggle.
     - Right panel: **synced LRCLIB lyrics** (auto-scrolling
       with active line highlighted) + **Up Next** queue
       (next 5 tracks).
     - Bottom dock: scrubber + Shuffle · Prev · BIG circular
       play (cyan ring) · Next · Repeat + volume slider +
       visualizer icon.

- **Mini player** (`MiniPlayer.jsx`)
  - Bottom-bar redesign: cover thumb + title/artist + heart
    on the left, transport cluster (with big circular cyan-ringed
    play) + scrub bar in the center, volume + maximize on the right.

- **Layout** (`MusicLayout.jsx`)
  - Vesper-style collapsible side rail: 76 → 248 px on focus
    dwell (300 ms) or mouse hover.
  - Glowing cyan **V2** emblem when collapsed; "ON NOW TV / Tunes"
    wordmark when expanded.
  - Nav: Home, Search, Karaoke, Radio, Australia, Podcasts,
    Library, Profile, Settings.
  - Theme picker (Electric Blue / Pink) revealed only when expanded.

- **Stylesheet** (`tunes.css`)
  - Rewritten end-to-end using Vesper's existing CSS variables
    (`--vesper-bg-0`, `--vesper-blue`, etc.) so the focus ring
    (3 px cyan outline) and spatial navigation behave identically
    to Vesper.

### Music search
- `MusicSearch.jsx` now reads the initial query from `?q=` URL
  search params so Mood-tile deep-links work end-to-end.

### Deployment
- React build produced (`main.5c75351f.js`) and rsync'd to the
  Contabo VPS (`/var/www/onnowtv-frontend/`).  Stale old chunks
  cleaned up with `--delete-after` so the Karaoke chunk-loading
  error (`Unexpected token '<'`) from the previous session is
  resolved as a side-effect.

### Verified
- ✅ `/music` home: hero + Trending shelves + Moods render on VPS.
- ✅ `/music/album/<id>`: cover + track list + Play/Shuffle/Add.
- ✅ Mini-player + Full-screen player visuals match references.
- ✅ `/music/karaoke` and `/music/radio/au` SPA routes return 200.
- ✅ `/api/music/lyrics` endpoint healthy.

### Not changed (intentionally)
- Native Android `InnerTubeResolver.kt` + `WebViewCookieJar.kt`
  untouched — ad-free YouTube playback flow preserved.
- Vesper (`/vesper`) and Launcher (`/launcher`) UI untouched.
