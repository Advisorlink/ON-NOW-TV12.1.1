# ON NOW TV V2 — Changelog

Release notes for the Android TV APK build (`apk-latest`).  This file
is the authoritative changelog; the GitHub Release body shows only the
latest version to avoid the workflow's `Argument list too long` shell
limit.

Latest version is shown in `app/build.gradle.kts` (`versionName`).

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
