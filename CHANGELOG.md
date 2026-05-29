# CHANGELOG — ON NOW TV TUNES + V2

## 2026-02-d — Smooth-as-Vesper polish + ROUTE FIX (LIVE on VPS)

### 🔴 CRITICAL: deeper routes were broken (the real cause of "Karaoke / Radio / Podcasts not working")
- `frontend/package.json` had `"homepage": "."` → CRA emitted
  **relative** bundle paths (`./static/js/main.x.js`).
- Loaded at `/music` the browser resolved that to
  `/static/js/main.x.js` (works).
- Loaded at `/music/radio` it resolved to
  `/music/static/js/main.x.js` → **404** → the SPA boot spinner
  on `index.html` stayed forever.  That's why Radio, Podcasts AND
  Karaoke "didn't work" — the JS for them literally never loaded.
- Changed to `"homepage": "/"` so paths are absolute now.
- Verified all four routes render: home, /music/radio (30 k stations),
  /music/podcasts (top shows), /music/karaoke (Crowd-pleasers grid).

### Smoothness pass — killed every constant-running animation
- Removed the 28 s ken-burns scale animation on `.tunes-hero__bg`.
  That constant transform was repainting a full-viewport image
  every frame → blew the HK1's GPU budget → made vertical shelf-
  to-shelf scrolls feel chunky.
- Removed the 6 s pulse animation on `.tunes-fullplayer__art-ring`
  (a blurred 12 px filter at 60 fps is the worst-case GPU op).
- Removed the 700 ms fade-up on `.tunes-hero__text` (single-shot
  but caused brief layout thrash on mount).
- Removed our additional `focusin` listener in `MusicLayout.jsx`
  that was calling `scrollIntoView({behavior: 'smooth'})` — it
  was racing Vesper's own `useSpatialFocus` which already does
  hardware-accelerated `behavior: 'auto'` scrolls.  No more
  conflicting scrollers → instant snap up/down between shelves.
- Page background simplified from a three-layer gradient to a
  single flat colour so the compositor never has to repaint a
  full-viewport gradient on focus scroll.

### Hero fade — covers the wallpaper properly
- `.tunes-hero__scrim-y` extended downward: 30 % transparent → 50 %
  45 % bg → 78 % 85 % bg → 95 % solid `--vesper-bg-0`.  No more
  visible image edge against the page background.
- Combined with the flat page background this gives a perfectly
  seamless transition from hero to shelves.

### Verified live on VPS (https://onnowtv.duckdns.org)
- ✅ Home: hero billboard + 5 shelves + Moods + Genres.
- ✅ Radio: 30,000+ stations grid loads.
- ✅ Podcasts: 48+ top shows grid loads.
- ✅ Karaoke: Crowd-pleasers (Bohemian Rhapsody, Rolling in the
  Deep, Livin' on a Prayer, Shake It Off, Don't Stop Believin',
  Perfect, My Heart Will Go On, I Will Always Love You) render.
- ✅ Build: `main.254b2705.js`, rsync'd, old chunks cleaned.

---

## 2026-02-c — Vesper-exact tile pattern + snap shelves
[…earlier notes unchanged…]

## 2026-02-b — Tunes Pink ↔ Blue themes + Vesper full-bleed hero
[…earlier notes unchanged…]

## 2026-02-a — Vesper-style Tunes redesign (initial drop)
[…earlier notes unchanged…]
