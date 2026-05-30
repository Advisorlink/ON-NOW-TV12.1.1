# HANDOFF — Current State (read this FIRST)

> Last updated: end of session that shipped v2.8.66 → v2.8.73.
> If you're the next agent picking up this work, this is the most
> recent, most accurate snapshot of where things stand.  Read this
> top-to-bottom BEFORE touching code.  It captures every trap I
> walked into so you can skip them.

---

## TL;DR — The user's actual ask

The user is building **three Android apps** that share a single
React codebase:

1. **Vesper** (`tv.vesper.app`) — TV/movies/live TV app.  Mature.
2. **ON NOW Tunes** (`tv.onnowtv.tunes`) — Standalone music app.
   THIS is the active focus this session.
3. **ON NOW Launcher** (`tv.onnow.launcher`) — TV box launcher.
   Mostly stable.

Right now the user wants the **Tunes app** to be reliable: open
correctly, scroll on mobile, play music when you tap Play, support
karaoke, and feel like its OWN brand (not a Vesper variant).

The user is in Australia, communicates by voice notes, and is
frustrated when fixes don't appear after a "Save to GitHub" push.
**Treat that frustration seriously** — most of this session's
debugging came from believing the user when they said "nothing
changed" instead of assuming they're testing wrong.

---

## CRITICAL: How code gets to the user (deploy chain)

This took me 4 versions to fully understand.  Get it right
from the start:

```
You edit code  →  user clicks "Save to GitHub"  →  GitHub Actions runs:
                                                    ├── build-apk.yml      (Vesper APK)
                                                    ├── build-tunes.yml    (Tunes APK)
                                                    └── deploy-backend.yml (FastAPI to VPS)
```

**Three crucial facts:**

1. **There is NO workflow that deploys the React frontend to the VPS.**
   `deploy-backend.yml` deploys ONLY `backend/**`.  The React JS
   bundle on `https://onnowtv.duckdns.org/` has been months-stale
   ever since the frontend stopped being synced.

2. **Both APKs bundle React INSIDE the APK** (since v2.8.70 for
   Tunes, longer for Vesper).  The workflow runs `yarn build`
   and copies `frontend/build/*` into `android/<app>/app/src/main/assets/web/`.
   The APK's WebView then loads from those bundled assets.

3. **CHANGELOG.md MUST start with a `## vX.Y.Z` heading** or the
   `build-apk.yml` workflow exits with "Could not parse a version"
   and NO APK is published.  If the user reports "nothing changed
   in the new install", check the CHANGELOG version-heading FIRST.

So the user-visible deploy cycle is:
1. You edit React / Kotlin code
2. You bump CHANGELOG.md heading to `## vX.Y.Z` (next number)
3. User clicks "Save to GitHub"
4. CI rebuilds both APKs (5–7 min)
5. New APK lands in GitHub Releases:
   - Vesper: tag `apk-latest`, asset `onnowtv-v2-debug.apk`
   - Tunes:  tag `tunes-latest`, asset `onnowtv-tunes-debug.apk`
6. The in-app update gate detects the new version and prompts the
   user's box / phone to install.

---

## CRITICAL: WebView origin gotchas

The APKs load React from a non-traditional origin.  This matters
because browser security policies behave differently depending on
origin:

### Tunes APK (current, v2.8.72+)
Loads `https://appassets.androidplatform.net/assets/web/index.html`
via `WebViewAssetLoader` (in `MainActivity.kt`).  This is a real
HTTPS origin from the WebView's perspective, so:
- YouTube IFrame Player `postMessage` works correctly
- Cross-origin `<audio>` works without "secure-degraded" quirks
- `localStorage` / `sessionStorage` work normally
- `REACT_APP_BACKEND_URL=https://onnowtv.duckdns.org` is baked in
  at build time so API calls hit the live backend

### Vesper APK (still v2.6+ pattern)
Loads `file:///android_asset/web/index.html`.  This is `file://` origin,
which has the YouTube IFrame audio-suppression bug (audio events fire
but actual sound never comes out).  **Vesper's music tab has the same
problem** — if the user ever tries to play music inside the Vesper APK,
they'll hit the same silent-audio symptom that bit Tunes in v2.8.70.
**Future work**: port the WebViewAssetLoader change to Vesper too.

### Asset path rewrite (both APKs)
CRA emits absolute paths like `<script src="/static/js/main.xxx.js">`.
On the VPS these work fine.  Inside the APK's bundled HTML they
resolve to `file:///static/...` or `https://appassets/static/...`
which don't exist.  Both `build-apk.yml` and `build-tunes.yml` have
a Python step that rewrites `="/x..."` → `="./x..."` AFTER copying
the build into `assets/web/`.  This MUST stay in both workflows.

### React Router on the Tunes APK
`appassets.androidplatform.net` would confuse `BrowserRouter` (it
would try to interpret `/assets/web/index.html` as the route).  In
`App.js` the Router selection is:
```js
const Router = (
    window.location.protocol === 'file:' ||
    window.location.hostname === 'appassets.androidplatform.net'
) ? HashRouter : BrowserRouter;
```
This is why the URL in `MainActivity.kt` ends with `#/music` — the
hash routes the user straight into the music app.

---

## CRITICAL: Music app playback architecture

The music engine is `useMusicPlayer.js` (a singleton attached to
`window.__musicEngine`).  Tracks are played through ONE of three
sources in order of preference:

1. **Native bridge** (`window.OnNowTV.resolveYouTubeAudio`) — only
   present in the Tunes APK.  Resolves a YouTube ID into a direct
   googlevideo.com URL using the WebView's signed-in YouTube cookies.
   Plays through HTML5 `<audio>`.  Best path when available.
2. **Backend stream** (`/api/music/stream/{id}`) — server-side
   InnerTube fallback.  Plays through HTML5 `<audio>`.
3. **YouTube IFrame Player** — last resort.  Loads
   `https://www.youtube.com/embed/{id}` in a hidden iframe.  Has
   the off-screen-positioning workaround in
   `YouTubeIFrameHost.jsx` and the force-unmute retry in
   `useMusicPlayer.js`.

### The "karaoke" mode (since v2.8.69)
Karaoke is NOT a separate route or component.  Tapping a karaoke
song tile:
1. `sessionStorage.setItem('tunes-karaoke-mode', '1')`
2. `controls.playTrack(track, [track])`  (same call as regular music)
3. `window.dispatchEvent('tunes:open-fullscreen')` — MiniPlayer
   listens for this and opens the `FullScreenPlayer`.

`FullScreenPlayer` checks the `tunes-karaoke-mode` flag and renders
a `KaraokeLyricsOverlay` (centered big synced lyrics) instead of
the side queue panel.  Audio uses the **identical pipeline** as
regular music — so if regular music plays, karaoke plays.

### The legacy `/music/karaoke/play/:trackId` route
Still exists for backward-compat with old deep links.  It's now
a thin redirect (in `Karaoke.jsx → KaraokeStage`) that fetches the
track and calls the same flow above, then `navigate('/music/karaoke',
{ replace: true })`.  Don't restore the old standalone KaraokeStage
component — it was the source of the v2.8.62-67 audio-desync bugs.

---

## CRITICAL: Hero "Play" button (v2.8.73)

The hero "Play" button on the music home (`MusicHome.jsx`) **always**
triggers `controls.playTrack(...)`, regardless of `slide.kind`:
- track-kind: plays the track directly
- album-kind: fetches the album, plays track #1
- artist-kind: fetches the artist, plays top track

All wrapped in `try { ... } catch {}` so no error toast EVER surfaces
from this button.  Album/artist DETAIL navigation lives on the
adjacent **More Info** button only.

If the user reports "Play does nothing" or "Couldn't load album HTTP
404", check that this onClick still uses the play-only pattern and
hasn't been reverted to a navigation pattern.

---

## CRITICAL: Mobile scroll on the music app (v2.8.72)

`html`, `body`, `#root`, `.App` all have `overflow: hidden`.  No
root-level native scrolling.  Each page must provide its own scroll
container.

`.tunes-root` in `tunes.css` is now:
```css
.tunes-root {
    height: 100dvh;
    max-height: 100dvh;
    overflow-y: auto;
    overflow-x: hidden;
    -webkit-overflow-scrolling: touch;
}
```
This makes `.tunes-root` itself the scroll container.  On TV,
useSpatialFocus's `scrollIntoView` still works because it walks up
to find the scrollable ancestor.

If the user reports "can't scroll on mobile" again, check this
block is intact and `.tunes-root`'s computed `clientHeight` is the
viewport height (not the full content height).

---

## File map (jump straight to these when bug-hunting)

### React (frontend)
- `frontend/src/App.js`
  - `MOBILE_NAV_HIDDEN_PREFIXES` — `/music` is in it; do NOT
    re-add Vesper's MobileBottomNav to music routes.
  - `VesperOnlyChrome` — Vesper toasts/badges hidden on /music
  - Router selection for HashRouter vs BrowserRouter
- `frontend/src/pages/music/MusicLayout.jsx`
  - Mounts the music app shell.  Sets `body[data-music-app="true"]`
    on mount so global CSS rules can scope.
- `frontend/src/pages/music/MusicHome.jsx`
  - `buildHeroSlides()` builds the rotating hero.
  - Hero Play button onClick — see "Hero Play" section above.
- `frontend/src/pages/music/FullScreenPlayer.jsx`
  - `KaraokeLyricsOverlay` rendered when sessionStorage flag set.
  - Sessionstorage flag cleared on close.
- `frontend/src/pages/music/Karaoke.jsx`
  - Landing page + legacy redirect stub.  Both call `startKaraokeFor`.
- `frontend/src/pages/music/tunes.css`
  - `.tunes-root` is the canonical scroll container.
  - Mobile @media block at the bottom.
  - Karaoke overlay styles for FullScreenPlayer.
- `frontend/src/hooks/useMusicPlayer.js`
  - Singleton engine.  Tracks resolve via `musicResolver.js`.
  - `_forceUnmuteRetry()` re-unmutes at 0/250/750/1500 ms on
    PLAYING transitions.
- `frontend/src/components/music/MiniPlayer.jsx`
  - Listens for `tunes:open-fullscreen` event to open the modal.
  - Hosts the FullScreenPlayer when expanded.
- `frontend/src/components/music/YouTubeIFrameHost.jsx`
  - Off-screen (`top:-200, left:-200`) NOT `opacity:0`.  Chrome's
    media policy treats opacity:0 iframes as hidden → silently mutes.
- `frontend/src/lib/musicResolver.js`
  - Native bridge → backend stream → YouTube iframe → 30s preview.
- `frontend/src/lib/music-api.js`
  - All `/api/music/*` calls. `BASE = REACT_APP_BACKEND_URL + '/api/music'`.

### Backend (FastAPI)
- `backend/music_api.py` — All music API endpoints.
- `backend/server.py` — Mounts the music routes.  CORS allow_origins=`*`.

### Android (Kotlin)
- `android/onnowtv-tunes/app/src/main/java/tv/onnowtv/tunes/MainActivity.kt`
  - `navigateToMusic()` loads via WebViewAssetLoader.
  - `assetLoader` builder at the bottom.
  - `OnNowTvBridge.kt` exposes `window.OnNowTV.resolveYouTubeAudio`.
- `android/vesper-tv/app/src/main/java/tv/vesper/app/MainActivity.kt`
  - Still loads from `file:///android_asset/web/index.html`.
  - WebView is configured with `mediaPlaybackRequiresUserGesture = false`.

### CI workflows
- `.github/workflows/build-apk.yml`     — Vesper APK
- `.github/workflows/build-tunes.yml`   — Tunes APK
- `.github/workflows/deploy-backend.yml`— Backend only
- ALL workflows trigger on `frontend/**` (added in v2.8.70) so React
  edits always rebuild the APKs.

---

## Known sharp edges (don't get caught by these)

1. **CHANGELOG version heading is mandatory.**  Forget to bump it
   and the CI builds silently produce no APK.
2. **VPS frontend is stale.**  Don't waste time testing fixes on
   `https://onnowtv.duckdns.org/music` in a browser — that's the
   months-old code.  Test on the Emergent preview pod URL
   (`https://rebrand-app-5.preview.emergentagent.com/music`) which
   serves the latest local build, OR ship and let the user test the
   APK on-device.
3. **Don't use BrowserRouter on the Tunes APK.**  Use HashRouter.
   The `App.js` Router selection above handles this.
4. **Don't restore the standalone Karaoke route.**  Karaoke must
   ride the same `controls.playTrack` pipeline as regular music.
5. **Don't add infinite CSS animations** (pulse, ken-burns, shimmer).
   The HK1 box stutters and the user has complained about it.
6. **Don't add `overflow-y: auto` to `body`/`html`/`.App`.**  Those
   are kept at `overflow: hidden` to keep the Vesper app's layout
   working.  Add scroll to the music app's own root instead.
7. **Don't change `homepage` in `package.json`.**  It's `"/"`.  The
   absolute→relative path rewrite in the workflows handles the
   APK side.  Changing this would break the VPS deployment.
8. **API base URL is from `REACT_APP_BACKEND_URL`.**  Baked in at
   build time.  Don't hardcode hosts.
9. **Vesper's mobile bottom-nav and Music app's bottom nav share
   `data-testid="side-nav"`.**  The global rule that hides side-nav
   on mobile has a `:not([data-tunes-nav])` exclusion for the
   music app.  Don't remove that exclusion.
10. **Music CDN preview URLs from Deezer can be HTTP.**  WebView
    might block as mixed-content on file:// origins.  WebViewAssetLoader
    (HTTPS origin) sidesteps this for Tunes APK.

---

## Pending / Backlog

### P1 — Jellyfin / Plex native integration (the user's next big ask)
- Goal: let users connect a Jellyfin or Plex server to play their
  own music library through the Tunes app.
- Architecture sketch:
  - Native (Kotlin) layer: persist Jellyfin/Plex server URL + auth
    token in `SharedPreferences`.
  - Expose `window.OnNowTV.jellyfinFetch(path)` and `window.OnNowTV.
    plexFetch(path)` to the React app — these proxy through the
    native client so credentials don't leak into the React layer.
  - React: new "Library" section in the music nav that lists
    Jellyfin/Plex albums/artists/playlists.  Reuse `MusicAlbum.jsx`
    layout — just point it at a different data source.
- Reference doc: `/app/memory/MUSIC_APP_STRATEGY.md` has the spec.

### P2 — Phone EPG reminders
- Web notifications + scheduled triggers for upcoming live-TV
  programs.

### P3 — Launcher device-registration email/SMS notification
- When a new launcher APK first checks in to the launcher backend,
  email/SMS the admin.

### Quality-of-life backlog (NOT urgent, just noting)
- Auto-update prompt in the music app (cold-start poll
  `https://api.github.com/repos/.../releases/latest`).
- "Tap to record karaoke" overlay (uses MediaRecorder for
  sharability).
- Long-press on shelf tile = play immediately (vs tap = open album).
- Port WebViewAssetLoader to Vesper APK so its music tab works.
- An "Update available — Tap to retry" overlay in `index.html` for
  the rare case the JS bundle fails to load.

---

## How to test changes (before pushing)

### Quick sanity checks
- `cd /app/frontend && yarn build` — should compile clean.
- Lint: `mcp_lint_javascript` on the changed file.
- Screenshot the preview pod (`https://rebrand-app-5.preview.emergentagent.com/music`) with mobile UA `(Linux; Android 13)` to check
  responsive behaviour.

### Audio verification
You can't actually hear audio in headless tests.  But you CAN check:
- `window.__musicEngine.state.isPlaying` → should be `true`
- `window.__musicEngine.activeEngine` → `'audio'` or `'youtube'`
- For YouTube engine: iframe should be present at `#onnowtv-ytplayer-host iframe`

### Scroll verification (mobile bug regression)
```js
const r = document.querySelector('.tunes-root');
console.log({
    scrollH: r.scrollHeight,
    clientH: r.clientHeight,
    overflowY: getComputedStyle(r).overflowY,
});
// clientH SHOULD equal viewport height, NOT scrollHeight.
// overflowY MUST be 'auto'.
```

### API health
```bash
curl -sk https://onnowtv.duckdns.org/api/music/home | python3 -c "import sys,json; d=json.load(sys.stdin); print([s['id'] for s in d['data']['shelves']])"
# Expect: ['top-tracks', 'new-releases', 'top-artists']
```

---

## User communication style

- The user is Australian, talks naturally, often in voice notes
  that get transcribed.  Tone is sometimes terse when frustrated.
- They don't always know technical terminology — describe the user
  experience, not the implementation.
- They share screen recordings — USE THEM (`analyze_file_tool`).
  The v2.8.73 fix was found purely from the video, not from logs.
- They want fast turnaround.  Each "Save to GitHub" is a real
  install cycle for them — don't ship unverified changes.
- They prefer when you say "verified end-to-end" with proof
  (screenshot, curl, engine state).
- They have shipped on a Contabo VPS, use a HK1 TV box and a phone.

---

## Test credentials & build state at handoff

See `/app/memory/test_credentials.md` for backend/admin credentials.

Latest version shipped: **v2.8.73**.  Both APKs (Vesper + Tunes)
will pick up the v2.8.73 React bundle on the next CI run.

### Files at end of session
- `frontend/src/pages/music/MusicHome.jsx` — Hero Play always plays.
- `frontend/src/pages/music/tunes.css` — `.tunes-root` is scroll container.
- `android/onnowtv-tunes/.../MainActivity.kt` — WebViewAssetLoader.
- `frontend/src/App.js` — HashRouter selection extended.
- `.github/workflows/build-tunes.yml` — Bundles React, rewrites paths.
- `.github/workflows/build-apk.yml` — Same path rewrite.
- `CHANGELOG.md` — Top heading is `## v2.8.73`.

### Outstanding pending user action
The user has been asked to "Save to GitHub" to publish v2.8.73 but
had not done so when this session ended.  When they do, the new APK
should fix:
- Hero Play not playing music (v2.8.73)
- Mobile scroll on the music app (v2.8.72)
- All audio silent inside Tunes APK (v2.8.72)
- "Couldn't load album HTTP 404" toast (v2.8.73)
- Vesper menu showing inside music app (v2.8.68)
- Karaoke silent / dull lyrics (v2.8.69 + v2.8.72)
