# CHANGELOG — ON NOW TV TUNES + V2

## 2026-02-e — US-mainstream home + Karaoke/Radio diagnosis (LIVE on VPS)

### 🇺🇸 Home shelves now sourced from **iTunes US Top Charts**
- Switched `/api/music/home` from Deezer's FR-biased global chart
  (which was returning Jul, Ninho, GIMS, Damso, etc.) to the
  **Apple Music US RSS feeds** (`itunes.apple.com/us/rss/...`).
- For each iTunes chart item we resolve via Deezer search to
  retain playable preview_url + matching IDs.
- Result on live VPS:
  - Hero now opens with **Ariana Grande – "hate that i made
    you love me"**.
  - Trending Now: Ella Langley, aespa, Hilary Duff, Michael
    Jackson (Thriller), Paul McCartney, Boards of Canada.
  - Trending Artists: Ariana Grande, Cody Johnson, Ella Langley.
- Cache key bumped to `music:home:v6` so the fresh feed is served
  immediately instead of waiting for the old 1 h TTL.
- "Top Albums" shelf removed (duplicated New Releases at the
  feed level after the slice fix landed; 3 shelves keep the
  home cleaner anyway).

### 📻 Radio — HTTPS-only filter
- Android WebView blocks **mixed-content** (HTTP audio on an
  HTTPS page) by default — which is why several Radio Browser
  stations silently failed on the HK1 box even though they
  played fine in a desktop Chrome.
- Added a strict filter to `/api/music/radio/top` and
  `/api/music/radio/by-tag/{tag}` that only returns stations
  whose stream URL is `https://`.  Verified: 0 HTTP, 8 HTTPS in
  the first page.
- This also kills the silent "no audio" bug on the HK1.

### 🎤 Karaoke — diagnosed as working (30 s preview limit)
- Headless click + audio inspection confirmed the Karaoke stage:
  - Loads the track, plays the 30 s Deezer preview, syncs LRCLIB
    lyrics ("Caught in a landslide, no escape from reality…").
- The user-perceived "Karaoke not working" was almost certainly
  the 30 s preview cut-off (the only fallback we have without
  YouTube cookies).  To unlock full tracks the user still needs
  to either:
    1. Sign into YouTube via the WebView (native InnerTubeResolver).
    2. Upload `cookies.txt` via the admin endpoint.

### Verified live on VPS
- ✅ `/api/music/home`: 3 shelves (25 + 24 + 8 items), all US-
  mainstream content.
- ✅ `/api/music/radio/top`: HTTPS-only results.
- ✅ `/api/music/podcasts/top`: 48 top shows (Joe Rogan, Daily,
  Crime Junkie, etc.) — unchanged, still works.
- ✅ Karaoke clicks play the synced preview + lyrics.

---

## 2026-02-d — Smooth-as-Vesper polish + ROUTE FIX
[…earlier notes unchanged…]

## 2026-02-c — Vesper-exact tile pattern + snap shelves
[…earlier notes unchanged…]

## 2026-02-b — Tunes Pink ↔ Blue themes + Vesper full-bleed hero
[…earlier notes unchanged…]

## 2026-02-a — Vesper-style Tunes redesign (initial drop)
[…earlier notes unchanged…]
