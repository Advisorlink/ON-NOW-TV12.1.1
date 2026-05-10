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

## Backlog (Prioritised)

### P0 — Next
- **Plex integration** — plex.tv OAuth PIN flow, server discovery,
  library browsing, direct-stream URLs. Add `/sources` card.
- **Jellyfin integration** — server URL + username/password,
  `/Users/AuthenticateByName`, browse, stream. Add `/sources` card.

### P1
- **WebView APK wrapper** — generate Kotlin source for a thin Android
  WebView app that loads `https://<vesper-host>` full-screen, with
  proper remote / TV input handling for HK1 sideloading.
- **My Library** page — favorites + watchlist + watch-history (already
  hinted at in nav).
- **Settings** page — per-user prefs (autoplay, language, region,
  quality cap).
- **Search keyboard** — speech input on supported boxes.

### P2
- Multi-user auth (Emergent Google login or JWT).
- Watch-progress sync.
- Subtitle picker (OpenSubtitles addon already plugs in).
- Cast / continue-watching cross-device.
- ErrorBoundary at the app root.

## Non-Goals
- We will not modify, repackage, or distribute the decompiled
  Nova Box / NovaMobile APK or any derivative of it.
- We will not bundle piracy stream-aggregator addons into the
  suggested-addons list. Users may install whatever third-party
  addon URL they choose; that responsibility is theirs.
