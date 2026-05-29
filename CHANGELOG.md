# CHANGELOG — ON NOW TV TUNES + V2

## 2026-02-b — Tunes Pink ↔ Blue themes + Vesper-style full-bleed hero (LIVE)

### Theme system restored
- `.tunes-root` locally redefines Vesper's CSS variables, so the
  entire music app inherits the **classic Tunes identity** —
  hot pink on deep grape (default) — while keeping Vesper itself
  untouched.
- Two themes, switchable from the side-nav theme picker:
  - **Pink** (default)   `#ff2d7f` on `#0a0118 → #160329 → #2a0945`
  - **Electric Blue**    `#00b3ff` on `#02060f → #051a32 → #082a55`
- Theme picker visible only when the side rail is expanded
  (matches Vesper's collapse-on-blur pattern).

### Hero billboard — Vesper full-bleed pattern
- Removed the framed cover on the right.  The hero image now
  fills the **whole** banner area and fades smoothly into the
  page background at the bottom (180° scrim → `--vesper-bg-0`),
  exactly like Vesper.
- Image is anchored at `center 30%` so faces / album-cover
  centerpieces sit comfortably.
- Vibrancy boost: `saturate(1.18) contrast(1.04)` + slow
  ken-burns (28 s loop) for cinematic motion.
- 90° horizontal scrim darkens the left side for title
  legibility; soft theme-coloured radial glow on the right.
- Hero now prefers the **artist photo** as the backdrop (more
  cinematic) and falls back to the album cover.

### Deployed
- React build rsync'd to the Contabo VPS at
  `/var/www/onnowtv-frontend/`.  Old hashed assets cleaned with
  `--delete-after`.
- HK1 box picks up the new look the next time the Tunes APK
  opens (no APK rebuild required).

### Notes for existing users
- Users whose `localStorage.onnowtv-tunes-theme` is already set
  to `electric-blue` will keep seeing the blue theme — their
  preference is preserved.  Toggle to Pink via the side-rail
  theme picker.

---

## 2026-02-a — Vesper-style Tunes redesign (initial drop)

### Music app frontend (`/app/frontend/src/pages/music`)
- **Complete visual overhaul** to mirror Vesper's polished home/billboard
  feel, adapted for music.  Three deliverables driven by the user's
  reference designs:

  1. **Music Home** (`MusicHome.jsx`)
     - Rotating hero billboard cycles through trending tracks +
       new releases (~9.5 s cadence).
     - "Trending Now" shelf — **square** album-cover tiles.
     - "Top Charts", "Top Artists" (round), "New Releases" shelves.
     - **NEW Moods grid** — six colour-gradient mood tiles.
     - "Browse Genres" photographic grid.

  2. **Album detail** (`MusicAlbum.jsx`)
     - Big cover top-left + ALBUM eyebrow + uppercase display
       title + cyan/pink "by ARTIST" link + meta + synopsis.
     - Play Album / Shuffle / Add to Library / ⋯
     - Track list with currently-playing row tinted to theme.

  3. **Full-screen Now Playing** (`FullScreenPlayer.jsx`)
     - Cover artwork (1:1) on the left with animated neon ring.
     - Title + artist + album + chips + heart-like.
     - LRCLIB synced lyrics + Up Next queue on the right.
     - Bottom dock: scrubber + Shuffle / Prev / BIG circular
       play / Next / Repeat + volume slider.

- **Mini player** (`MiniPlayer.jsx`) — bottom-bar redesign with
  cover thumb, transport, scrub, volume + maximize.

- **Layout** (`MusicLayout.jsx`) — Vesper-style collapsible side
  rail with glowing V2 emblem and theme picker.

### Music search
- Reads `?q=` URL search params so Mood-tile deep-links work.

### Deployment
- Karaoke `Unexpected token '<'` chunk-loading error resolved
  by the fresh build + `--delete-after` rsync cleanup.
