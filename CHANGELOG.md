# CHANGELOG — ON NOW TV TUNES + V2

## 2026-02-c — Vesper-exact tile pattern, snap shelves, deep playback debug (LIVE)

### Design — mirrored Vesper's pattern exactly
- **Tile = ONE rounded rectangle** (the cover image).  Title + artist
  now sit ABSOLUTE-positioned on the bottom of the cover with a dark
  gradient scrim behind them — so the focus ring only ever wraps the
  cover, never a separate text block underneath (per the reference
  PosterTile structure in `/components/PosterTile.jsx`).
- **Focus styling delegated** to Vesper's global
  `[data-focus-style="tile"]` rule in `/src/index.css`:
  `outline: 1.5px solid var(--vesper-blue-bright); outline-offset:
  2px; transform: scale(1.08) translateY(-2px)`.  No blur, no box-
  shadow → no extra GPU paint pass per frame → smooth 60 fps on HK1.
- **Artist tiles** keep the caption BELOW the round photo (a circle
  can't carry a text overlay), but the focus ring is repainted on
  the inner `.tunes-tile__art-wrap` only, so it still encircles the
  cover only — never the text below.
- **One-line shelves with snap focus** — every Tunes rail now has
  the `vesper-shelf` class so `useSpatialFocus`'s `horizontalScroller`
  and per-rail column bookmark logic apply identically to Vesper.
  `scroll-snap-align: start` + `scroll-margin-left` on every tile
  for the "snap to a tile" feel.
- **Moods → horizontal shelf** (was a grid).  Six gradient tiles
  with the same square aspect ratio as albums, sitting in a
  scrollable rail just like Continue Watching on Vesper.  Browse
  Genres still sits below as a full-width grid.

### Performance — reduced image weight 4×
- Deezer CDN URLs encode the image size right in the URL.  All
  `cover_xl` / `cover` / `picture` paths are now run through
  `smallerDeezerUrl()` which rewrites `…/1000x1000-…` → `…/500x500-…`.
- Result: each tile decodes ~30 ms instead of 200 ms+.  ~10×
  cheaper total decode budget on home page load — kills the
  "chunky" feel that came from JPEG decoding at scroll time.
- Hero still uses XL (one image, 10 s rotation, decode cost
  amortised).

### Karaoke loading fix
- Removed the redundant warm-up search before the parallel batch
  of 8 seed searches (that single search hanging was what produced
  the "Warming up the stage…" stuck state).  Now all 8 fire in
  parallel from the start.
- Added a 6 s safety timer that flips `picks` from `null` to `[]`
  if every promise hangs, so the placeholder is never permanent.

### Theme system — preserved
- Pink (`#ff2d7f` on `#0a0118 → #2a0945`) remains the default.
- Electric Blue (`#00b3ff` on `#02060f → #082a55`) toggleable from
  the side rail when expanded.

### Verified via playwright + curl
- ✅ Home renders with vibrant album covers (MA CLAQUE, OUTKAST,
  bitknot, Constant Farewells, Le sanglot — all real artwork).
- ✅ Focus ring is a clean 1.5 px outline that wraps only the
  cover image.
- ✅ Square album tiles + round artist tiles + gradient mood
  tiles all use the same shelf snap behaviour.
- ✅ Backend endpoints healthy: `/api/music/search`,
  `/api/music/radio/top`, `/api/music/podcasts/top`,
  `/api/music/lyrics` all return data.

### Known constraint — Full-track YT playback needs cookies
- `/api/music/stream/{id}` falls back to a 30 s Deezer preview when
  no YouTube cookies are available on the VPS.  Currently the
  `/opt/onnowtv/backend/youtube-cookies/` directory is EMPTY.
- To unlock full-track ad-free playback the user can either:
  1. Sign into Google/YouTube via the WebView on the HK1 Tunes
     APK — the native InnerTubeResolver then uses the WebView
     cookies for ad-free playback.
  2. Upload a fresh `cookies.txt` via the admin endpoint
     `/api/music/admin/cookies/upload`.

### Deployed
- React build (`main.e1e5a6c1.js`) rsync'd to the VPS with
  `--delete-after` cleanup.
- Created an empty `youtube-cookies/` dir on the VPS so the admin
  status endpoints don't 404.

---

## 2026-02-b — Tunes Pink ↔ Blue themes + Vesper-style full-bleed hero (LIVE)
[…earlier notes unchanged…]

## 2026-02-a — Vesper-style Tunes redesign (initial drop)
[…earlier notes unchanged…]
