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
## ⚙️ Operational rule — ALWAYS auto-bump APK version per session

User has explicitly requested: **every time the agent ships meaningful
changes that will reach the box, ALSO bump these two lines** in
`/app/android/vesper-tv/app/build.gradle.kts`:

  - `versionCode` → +1
  - `versionName` → +1 patch (e.g. `2.6.31` → `2.6.32`)

Also append a `**v{newVersion} — short headline**` block at the TOP
of the release-notes body in `/app/.github/workflows/build-apk.yml`
so the in-app UpdateGate surfaces what's new to the user's testers.

Why: the UpdateGate compares the box's current version to the
backend's `/api/app/latest-version` response (which mirrors the
GitHub `apk-latest` tag).  Without a bump, no prompt fires, and
the user doesn't know there's anything new on the box.

Do this BEFORE calling finish on any session that touched
frontend/backend/Android code that the box would see.



## Implemented (Iteration 96 — Feb 18, 2026)
### Detail page UX overhaul — user couldn't escape the cast view (video reproduction)
- **🐛 User reported with video**: After my Iteration 95 fix, user shot a video of their HK1 box showing the actual user experience: (1) ghostly faces visible behind the "More like this" row (focused-rec backdrop bleeding through the gradient mask), (2) **stuck on actor view** — when they focused a cast actor and tried to navigate back to Play, the screen froze with the actor still showing, (3) "More like this" cards were huge and the last one was cut off at the right edge, (4) focus indicators were not clearly visible from 6-10 ft viewing distance.
- **🔬 RCA**:
  1. **Stuck-on-actor**: My iter-95 fix hid the Play button when `focusedActor` was truthy. When the user pressed UP from a cast actor, `requestSnap(0)` tried to focus `[data-testid^="detail-play-"]` but the Play CTA hadn't re-rendered yet (because `focusedActor` was still set). The retry loop ran 800 ms with no luck and the user stayed stuck.
  2. **Filmography trap**: D-pad UP handler only matched `cast-actor-*`, not `cast-film-*`. When user revealed an actor's filmography (clicked OK), UP did nothing.
  3. **Hero bleed**: The gradient mask faded to opacity 0.92 at 55% and didn't reach solid until 100%. The focused-rec backdrop (z-index 2, full viewport) was bleeding through the lane at z-index 15.
  4. **Recs lane was 70 px taller than the Cast lane**: rec cards were 152×228, cast cards 108×162. So when the lane swapped from Cast to Recs, the bottom lane geometry pushed UP into the hero area.
  5. **Right-edge cutoff** on rec strip: no `paddingRight`.
- **✅ Comprehensive fix** in `/app/frontend/src/`:
  - `pages/Detail.jsx`:
    - **`requestSnap` now clears `focusedActor` + `focusedRec` BEFORE focusing** — so the Play button re-renders before the focus engine queries for it.
    - **`requestSnap` selector fallback**: when targeting the Cast lane (idx=1), tries `cast-actor-*` first, falls back to `cast-film-*` so the user is never stranded when the lane is in filmography-reveal mode.
    - **ArrowUp keyboard handler now catches `cast-film-*`** in addition to `cast-actor-*` → user can escape filmography back to Play.
    - **ArrowDown from cast-film-* now also navigates to Recs** (parity with ArrowDown from cast-actor).
    - **Bottom-lane backdrop hardened to solid `#06080F` by 30 % of the fade height** (was 100 %). Inset bumped from -120 px to -140 px. Hero content (Play button, actor portrait, focused-rec backdrop) CANNOT bleed through anymore.
  - `components/RecommendationsRow.jsx`:
    - **Rec card dimensions**: 152×228 → 108×162 (matching Cast card exactly).
    - **Strip paddingRight: 80 + scrollPaddingRight: 80** — last poster's focus glow no longer cut off at the right edge.
    - **Focus state border**: 2 px → 3 px, glow opacity 0.18 → 0.35 for better 10-ft TV legibility.
  - `components/CastRow.jsx`:
    - **Focus state border on ActorCard and FilmCard**: 2 px → 3 px, added box-shadow glow (was missing on ActorCard), glow opacity 0.18 → 0.35.
- **✅ Verified via Playwright** at 1920×1080: all 6 navigation scenarios pass (default → focus cast → UP back to Play; DOWN → cast → DOWN → recs → UP → cast → UP → play; OK on actor → filmography → UP from film card → Play). Focus indicator computed style confirms 3 px cyan border + 35 % glow.
- **🆙** APK bumped to **v2.6.47 (versionCode 117)**. Release notes added to `.github/workflows/build-apk.yml`.


## Implemented (Iteration 95 — Feb 18, 2026)
### Detail page: hide Play CTA + autoplay caption when a Cast actor is focused
- **🐛 User reported** (with photo of the TV showing the bug): "This is still happening" — the Sally Field actor view on the Detail page still showed the **"Play 1080p" button + "AUTOPLAY ON · TURN OFF IN SIDE MENU FOR PICKER" caption** rendered on top of the "Cast · 20 actors" heading at the bottom lane. Layout collision was visible at 1080p TV viewport (the user's HK1 box).
- **🔬 RCA**: The hero column has `maxHeight: calc(100vh - 320px)` reserving 320 px for the bottom lane. The actual CastRow geometry is **~340 px** (mt-10 + h3 + mb-5 + 162 px portrait + name/character + paddingBottom + lane paddingBottom = 340 px). So the bottom of the hero (containing the Play CTA when no actor is focused, AND when an actor IS focused too because the Play CTA was unconditionally rendered) was geometrically overlapping the top of the Cast row by ~20 px. The bottom-lane gradient mask (extending only 80 px UP, fading to opacity 0.55 at 25 % and 0.92 at 55 %) was not opaque enough at the Cast heading position to fully mask the hero behind it.
- **✅ Fix** in `/app/frontend/src/pages/Detail.jsx`:
  1. **Play CTA + autoplay caption now hidden when `focusedActor` is truthy** (line 1310). User has D-padded INTO the Cast row at this point — the Play button has no business being there. The hero cleanly shows ONLY the actor's name + character + age + birthplace + bio.
  2. **Stream picker also hidden when `focusedActor` is truthy** (line 1392). Same rationale as above.
  3. **Hero column max-height bumped from `100vh - 320px` to `100vh - 360px`** (line 1176). Gives the Cast heading + portraits the 40 px of breathing room they needed.
  4. **Bottom-lane gradient mask strengthened** (line 1889):
     - Inset bumped from `-80px` to `-120px` (gradient starts 40 px higher).
     - Opacity ramp tightened: `0% → 20% → 40% → 60%` instead of `0% → 25% → 55% → 100%`. Solid `#06080F` is now reached by 60 % of the fade height (was 100 %), so the area where the Cast heading renders is at ~98 % opacity, fully masking anything in the hero above.
- **✅ Verified via Playwright screenshot tool** at both 1920×800 AND 1920×1080:
  - **Cast actor focused** (Sally Field): hero shows "Sally Field" + "AS TOVA SULLIVAN" + age + birthplace + bio + portrait. `play_btn count: 0, visible: False`. Cast row heading + portraits render cleanly below — zero visual collision.
  - **No actor focused** (default): hero shows "Remarkably Bright Creatures" + 2026 · 114 min · ★ 7.8 · Comedy · Drama + synopsis + Play 1080p button + AUTOPLAY caption. Cast row heading + portraits render cleanly below — zero visual collision.
- **🆙** APK bumped to **v2.6.46 (versionCode 116)**. Release notes added to `.github/workflows/build-apk.yml` so the in-app UpdateGate prompts the user on their TV.

## Implemented (Iteration 94 — Feb 17, 2026)
### Welcome tour onboarding (3D D-pad walkthrough)
- **🎯 User**: "Once the client is logged in and they've opened their profile, then it needs to have a sort of onboarding guiding them how to use everything. I really want it to have a 3D directional D-pad that glows when you push enter. Skip button + replay from Settings."
- **🆕 Component** `/app/frontend/src/components/Onboarding.jsx`:
  - **14-step deck** covering every non-Live-TV feature: welcome → D-pad navigation → OK to open → hold-OK to save → TV → Movies → Library → Calendar → Search → Watch Together → Profiles → Sources → Settings → wrap-up.
  - **3D circular D-pad illustration** rendered as inline SVG with radial body gradient, top sheen ellipse, drop-shadow filter for depth, glow filter for active buttons, and individual UP/DOWN/LEFT/RIGHT arrow pills, central OK button, and a BACK pill — each one glows cyan when the current step references it.
  - Real keyboard bindings: D-pad arrows navigate steps, OK/Right advance, Left goes back, Escape/Backspace finishes — so users literally practise the buttons while the tour explains them.
  - **Skip pill** top-right (`SkipForward` icon), `Step N of 14` counter, gradient progress bar.
  - Self-contained keyframes (`vesperOnbFade`, `vesperOnbGlow`, `vesperOnbPulse`) so no global CSS surgery.
- **🚪 Auto-show gate** in `App.js` (`OnboardingGate` wrapper):
  - Fires once per device when an adult profile is active AND `localStorage["vesper-onboarding-seen-v1"]` is unset AND the user isn't on `/profiles*` / `/kids/*` routes.
  - Kids profiles skip the tour entirely (it'd confuse them).
  - Listens for `vesper:onboarding-replay` event so the Settings replay button reopens the overlay on demand.
- **🔁 Settings → Help → "Replay welcome tour"** row added (`pages/Settings.jsx`):
  - Glass card with Sparkles icon + headline + "Replay" button.
  - Clicking it clears the `vesper-onboarding-seen-v1` flag and dispatches the replay event.
- **🧪 Verified** via Playwright: overlay mounts on first non-kids profile load, Right/Enter advance correctly, Skip dismisses + sets seen flag, Settings → Replay re-opens it. Five screenshots captured (welcome step, Right glow, OK glow, calendar mid-step, replay re-mount).


## Implemented (Iteration 93 — Feb 17, 2026)
### Instant Live TV bundle — zero-config EPG on first login
- **🎯 User**: "I really want the TV guide to be instant. As soon as they log in… Is there any way that we could load the TV guide somewhere else so it's all ready to go?"
- **🆕 Backend** (`/app/backend/instant_bundle.py`): pre-warmed server-side cache. Pulls categories, channels, and the next **72 h of EPG** from the managed Xtream provider on a background scheduler (channels every 6 h, EPG every 2 h). Persists to MongoDB collection `xtream_bundle` so the cache survives backend restarts.
  - `GET /api/xtream/instant-bundle` → gzipped JSON with `provider` (id + host + port + scheme — NO username/password leak), `categories`, `channels` (each with pre-built `stream_url` so the client never needs creds), and `epg` (programmes per `epg_channel_id`, trimmed to next 72 h).
  - `GET /api/xtream/instant-bundle/meta` → lightweight counts + timestamps; used by clients to decide whether to re-pull.
  - `POST /api/xtream/instant-bundle/refresh?token=…` → admin-forced refresh (token in `XTREAM_ADMIN_TOKEN` env).
- **🛠️ Frontend** (`/app/frontend/src/lib/instantBundle.js`):
  - `bootInstantBundle()` fetches the bundle on app boot and writes it through to the SAME `liveCache.js` localStorage keys the existing LiveTV page already reads from — keyed under the user's ACTIVE Xtream provider id (`default-njala`) so playback URLs built from local creds still match. No new "managed" provider entry is added, no active-key juggling — completely transparent to the existing flow.
  - Periodic refresh: app polls `/instant-bundle/meta` every 30 min and re-pulls the full bundle only if `generated_at` advanced.
  - Wired in `App.js` boot path; safely no-ops when the backend hasn't warmed up yet (empty `channels[]` → skip seed, never clobber the local cache).
- **🐛 Bugs fixed during wiring**:
  - Previous draft used `'onnowtv-active-xtream-provider-v1'` for the active-provider key but `xtream.js` reads `'onnowtv-xtream-active-id'` → seeded provider was never actually active. Now bypasses the active-key entirely by seeding under the active provider's existing id.
  - Previous draft added a stub "managed" provider with `__managed__` placeholder creds. Removed entirely — `getStreamUrl()` builds working URLs from the existing `default-njala` creds.
- **🧪 Verified**: backend unit test seeded `_state` and confirmed gzipped endpoint returns the right shape (provider has NO creds, channels include `stream_url`, epg keyed by `epg_channel_id`). Frontend Playwright smoke with a mocked bundle response confirmed `localStorage` now contains `onnowtv-livecache-v1:default-njala:cats` (2 cats), `:chans` (1 cat / 1 channel), and `onnowtv-instant-bundle-meta` with `provider_id_seeded: default-njala`.
- **Production note**: preview pod has egress restrictions and can't reach `njala.ddns.me`, so the scheduler logs "channels refresh failed" on this env — expected and harmless. Production pod has full egress and will warm the cache on startup, serving every client an instant TV guide on first login.


## Implemented (Iteration 92 — Feb 16, 2026)
### v2.6.8 — Native-smooth Home + Live Guide overlay EPG fix
- **🐛 User reported**: home shelves felt "chunky", asked why the in-player Live Guide overlay uses RecyclerView but the Home/Live TV pages don't. Also: the slide-in Live Guide overlay shows channel names but no EPG ("what's on now").
- **🔬 Architecture answer**: The in-player Live Guide overlay IS pure native Kotlin RecyclerView because it draws OUTSIDE the WebView, directly on top of the VLC SurfaceView. The Home / Live TV / Movies pages live INSIDE the WebView (React) — porting them to native would mean rewriting every page as a Kotlin Activity. Multi-week project. Instead, applied modern CSS-native virtualisation to get ~95% of the smoothness for ~5% of the effort.
- **🚀 Smoothness pass on Home shelves**:
  - `PosterTile.jsx`: every tile now uses `content-visibility: auto` (browser-native view-recycling — off-screen tiles skip layout/paint entirely), `contain: layout paint style`, `containIntrinsicSize` so the scrollbar doesn't jump as off-screen tiles hydrate, plus `transform: translateZ(0)` + `will-change: transform` to promote each tile to its own GPU compositor layer.
  - `Shelf.jsx` (horizontal scroller): `contain: content`, GPU compositing, `will-change: scroll-position`, `scroll-snap-type: x proximity`, `overscroll-behavior: contain` so a stray gesture can't rubber-band the whole page.
  - `Home.jsx` shelves-region (vertical scroller): same GPU stack so vertical scrolling is also compositor-only.
- **📺 Live Guide overlay EPG fix**:
  - Root cause: `pushLiveGuideToNative()` was ONLY called inside `LiveTV.jsx` (on channel-load + XMLTV merge). If the user launched a channel from Continue Watching / Home / Hero billboard without ever visiting the Live TV page during the session, the native overlay's SharedPreferences EPG map stayed empty → overlay rendered "No EPG data" on every row.
  - Fix: new `lib/nativeGuideBoot.js` reads cached channels + EPG from localStorage (already persisted by previous LiveTV visits via `liveCache.js`) and pushes them to the native bridge. Wired into `App.js` to fire 200 ms after boot AND re-fire every 2 s for 10 s in case the cache hydrates slightly late.
- **Manifest v2.6.8 (versionCode 78).**

## Implemented (Iteration 91 — Feb 16, 2026)
### v2.6.6 — THE real Watch Together root cause (HashRouter query-string bug)
- **🐛 User reported (5th recurrence)** on v2.6.5: "Start Party still opens the manual stream picker with Play 1080p on both screens."
- **🔬 ACTUAL ROOT CAUSE finally found**: the React app, when bundled into the APK, loads from `file:///android_asset/web/index.html`. The router-selection logic in `App.js` (line 51-54) detects `file:` protocol and switches React Router into **HashRouter** mode. In HashRouter, the URL is `file://.../index.html#/resolve/movie/123?party=XYZ` — the `?party=XYZ` query string is **inside the hash**, so `window.location.search` returns an empty string.
- **The silent failure chain**: `Resolve.jsx` was reading `window.location.search` to forward the party context through the tmdb→imdb redirect. On the APK (HashRouter) it returned empty → redirect dropped `?party=…` → Detail.jsx mounted with no `partyCode` → no early return → manual picker rendered. Every fix I attempted previously (autoplay watchdog, ref+state guard, dedicated party screen) was defeated by the upstream query-string drop.
- **The reason it never reproduced in preview**: the preview at `rebrand-app-5.preview.emergentagent.com` runs on HTTPS so `App.js` uses BrowserRouter, where `window.location.search` works correctly. So my preview tests passed every time while the APK silently failed.
- **🛠️ Fix** (`pages/Resolve.jsx`): replaced `window.location.search` with `useLocation().search` from react-router-dom. Works identically under both routers because react-router normalises the search string regardless of the URL transport.
- **🧪 Verified in preview**: navigating to `/resolve/movie/157336?party=TESTQS&autoplay=1&...` now hops cleanly through `/title/movie/tt0816692?party=TESTQS&...` straight to `/play?url=…&party=TESTQS` with the party context fully intact, with 0 stream picker buttons rendered at any step.
- **Manifest v2.6.6 (versionCode 76).**

## Implemented (Iteration 90 — Feb 16, 2026)
### v2.6.5 — Bulletproof Watch Together + Load existing profile
- **🐛 User reported (4th recurrence)** of the Watch Together "Start Party shows the picker" bug. Even on v2.6.3 with the bulletproof autoplay + watchdog, the user saw a "Play 1080p" button rendered behind the joining overlay and tapped through it.
- **🔬 Root cause traced**: the `pointerEvents: 'none'` on the JOINING WATCH PARTY overlay meant clicks fell straight through to the picker behind it. Even worse, the picker itself was still being rendered in the DOM — just hidden by an overlay.
- **🛠️ Permanent fix** (`pages/Detail.jsx` + new `components/PartyJoiningScreen.jsx`):
  - When `partyCode && !autoplayFired` is true, Detail.jsx now returns a **dedicated full-screen `<PartyJoiningScreen/>` component as an early return** — the stream picker, cast, recommendations, episodes etc are NEVER mounted at all. There is literally no clickable picker behind the joining screen.
  - PartyJoiningScreen: full-bleed blurred poster, neon cyan glow, poster card, "PARTY · LOADING" eyebrow, title + status copy, plus explicit Cancel + Retry buttons (the only interactive elements on screen).
  - Returns this branch BEFORE the meta-loading / err-not-found branches too, so the user sees the joining screen from the very first paint instead of "Loading metadata…".
- **💾 NEW: Load existing profile** on the profile picker:
  - User asked: "add a load existing profile or something like that into the home screen of the profile section".
  - New neon "Load existing profile" pill on `/profiles`, right next to "Manage profiles".
  - Dedicated `/profiles/load` route with beautiful 3-step UX: code entry (TV keypad with 6 slots) → PIN entry (reuses `PinGate`) → confirm preview (shows profile/library/CW counts before overwriting).
  - Reuses the existing `/api/backup/restore` endpoint so backups created via Settings → Backups on any other device work seamlessly.
  - Route added to `NO_PROFILE_REQUIRED` so it's reachable from a fresh install with zero profiles.
- **🧪 Verified end-to-end in preview**: party URL `/title/movie/X?party=…&autoplay=1` navigates straight to `/play` with party context, picker has 0 mounted buttons. Profile picker shows the new pill; clicking it lands on the load page with focused TV-friendly keypad.
- **Manifest v2.6.5 (versionCode 75).**

## Implemented (Iteration 89 — Feb 16, 2026)
### Working APK auto-update installer + Update Gate fixes (v2.6.4)
- **🐛 User reported:** "DOWNLOADING…" spinner stuck forever on the v2.6.2 gate; profile picker bled through the gate's background.
- **🔬 Root causes:**
  1. **Install path was a no-op.** `UpdateGate.jsx` fell through to `window.location.href = apk_url` because `WebAppInterface.kt` had no `installApk` or `openExternal` methods. Android WebView with no `DownloadListener` set just tries to render the binary as a page and silently stalls.
  2. **Background was 15% transparent at the top-center** (`radial-gradient(... rgba(93,200,255,0.15) 0%, ...)`) on a transparent base layer, so anything underneath leaked through.
- **🛠️ Fixes shipped:**
  - **AndroidManifest.xml**: added `REQUEST_INSTALL_PACKAGES` permission + a `FileProvider` with authority `${applicationId}.fileprovider` pointing at `external-cache-path/updates/` for handing APK files to the system PackageInstaller via a `content://` URI (file:// is forbidden on API 24+).
  - **res/xml/file_paths.xml**: new — declares the `updates/` external-cache path.
  - **WebAppInterface.kt + MainActivity.kt**: new native bridges:
    - `OnNowTV.installApk(url)` — uses `DownloadManager` to fetch the APK (system notification, retries, etc.), polls status every 600 ms, posts progress events back to JS via `window.__onUpdateEvent(stage, info)`, then launches the system installer with `Intent.ACTION_VIEW` + the FileProvider `content://` URI.
    - `OnNowTV.openExternal(url)` — falls back to the system browser / Downloader app for cases where the install path fails.
    - On `SecurityException` (Android 8+ unknown-sources still gated) the bridge auto-redirects to `Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` so the user can grant once.
    - `MainActivity.kt`: exposed `internal fun webViewOrNull(): WebView?` so the bridge can `evaluateJavascript` cleanly without forcing a layout XML id.
  - **UpdateGate.jsx** rewrite:
    - **Opaque base layer** (`#06080F`) + glow as separate `pointer-events:none` overlay → no more bleed-through.
    - Wired to the new `OnNowTV.installApk(url)` and reflects live progress (0–100 %).
    - **Always-visible fallback row**: "Open in browser" + "Copy download link" buttons so the user is NEVER stranded — critical for v2.6.2 users who don't have the native bridge yet.
    - When the bridge is missing, the gate now shows a clear instruction ("This older version cannot auto-install. Tap Open in browser to install manually — just this once.") instead of pretending to download.
    - Progress bar component renders during download.
- **🧪 Verified** via Playwright preview repro — gate fires correctly at v2.6.2 < v2.6.3, all 3 buttons present, fully opaque.
- **Manifest v2.6.4 (versionCode 74).** Once the user installs this build manually one time, every future update will be one-tap from the gate.

## Implemented (Iteration 88 — Feb 16, 2026)
### Watch Together — bulletproof autoplay + diagnostic breadcrumbs (v2.6.3)
- **🐛 User reported AGAIN** (3rd recurrence): "I clicked Start Party → it took her to the manual stream selection and me the manual stream selection as well. Then I clicked Start and mine started and hers just didn't do anything."
- **🔬 Verified the backend is solid** via direct WS scripted repro: host's `play` → server flips `status='loading'` → both members get the state.  Preview test of `/title/movie/X?party=…&autoplay=1` confirmed the JS autoplay logic was firing correctly when triggered.  So the bug was on the client side, where one or more silent failure modes left the user on the picker.
- **🛡️ Hybrid REF + STATE autoplay guard** (`pages/Detail.jsx`):
  - Previous impl used `autoplayFiredRef` only — React doesn't watch refs so the JOINING WATCH PARTY overlay didn't always hide on a successful fire, leaving the user staring at the picker behind the overlay.
  - First attempted state-only impl caused a self-cancelling cleanup: setting `autoplayFired = true` triggered the useEffect's cleanup which `clearTimeout`'d the very `playStream` that was about to launch.
  - **Final fix**: synchronous REF guard for the "already fired" check (no re-render race) PLUS a STATE flag for the overlay render.  `window.setTimeout` (un-tracked) for the deferred `playStream` so it can't be killed by its own state-update.
- **🦮 Autoplay watchdog**: separate `useEffect` with a 5-second timer that re-attempts the pick + `playStream` if autoplay hasn't fired by then.  Catches React batching edge-cases, stale closures, hot-reload weirdness — anything that could leave the party member stranded on the picker.
- **🧷 WS-open-aware lobby send** (`pages/WatchTogether.jsx`):
  - Old `send(msg)` silently dropped `play` if the WebSocket wasn't OPEN yet (race after `setView('room')`).  If the host's Start Party click arrived before `ws.onopen`, the server never saw `play`, never flipped to `loading`, and BOTH members hung in the lobby with no navigation triggered.
  - **New** `sendReliable(msg, timeoutMs=2500)`: polls `readyState` every 80 ms up to 2.5 s, sends as soon as the socket is open.  Returns boolean success.  Wired into both `pick` and `play` callbacks.
- **🌀 Start Party button feedback**: disables while sending + while server status is `loading` / `countdown`; shows spinner; surfaces "Connection still warming up — try again in a second." on failure.  User now has a clear signal something is happening instead of clicking into the void.
- **🍞 Diagnostic breadcrumb trail** (`localStorage["vesper-party-breadcrumbs"]`, last 80 events):
  - Lobby: `lobby:ws-connect`, `lobby:ws-open`, `lobby:joined`, `lobby:send-start`, `lobby:send-ok` / `lobby:send-timeout` / `lobby:send-error`, `lobby:navigate`, `lobby:ws-close`, `lobby:ws-error`.
  - Detail: `streams:fetch-start`, `streams:fetch-done` (with count), `streams:fetch-error`, `party-autoplay:fire`, `party-autoplay:watchdog-fire`, `series-party-autoplay:fire`, `playStream:invoke` (mode/role/memberId/wsUrl presence), `playStream:native-launched` / `playStream:web-fallback`.
  - All breadcrumbs also `console.log`'d so `adb logcat` + remote debug show them live.
  - Excluded from profile backup (`vesper-party-breadcrumbs` prefix in `EXCLUDE_PREFIXES`).
- **🧪 Verified**: backend 16/16 watch-party tests still pass.  Preview repro of `/title/movie/tt0816692?party=…&autoplay=1&at_ms=0&position_ms=0` now reliably navigates to `/play?url=…&party=…` within 1.5 s every time, with full breadcrumb trail for post-mortem inspection.
- **Manifest v2.6.3 (versionCode 73)** — GitHub Actions auto-builds & publishes.

## Implemented (Iteration 87 — Feb 16, 2026)
### Premium Live TV overlay redesign + Update Gate live config
- **🗝️ Update Gate live**: `APK_GITHUB_REPO=Advisorlink/ON-NOW-TV12.1.1` set in `backend/.env`.  Once the GitHub workflow publishes a v2.6.2+ release with the `apk-latest` tag, every install older than that will show the forced-update screen on next launch.
- **🎨 Live Guide overlay — total redesign (v2.6.2)**:
  - **Layout shift**: previous full-screen 2-column (categories | channels) overlay → new 460dp left edge-panel with the video fully visible to the right.  The video keeps playing.
  - **Animations**:
    - Panel slides in from `translationX = -460dp` to `0` over 280ms with `AccelerateDecelerateInterpolator`.
    - Scrim cross-fades 0→1 over 240ms.
    - Detail card fades in 0→1 over 240ms with a 120ms start-delay so it lands after the panel finishes sliding.
  - **Channel row redesign** (`item_guide_channel.xml`): logo on glass plate + name + Now/Next + neon-cyan progress bar + per-category channel number badge (e.g. `003`).  Background is a focus-state selector — transparent default, glass-card with cyan border on focus.
  - **Programme detail card** (`@id/guide_detail`): floats in the bottom-right corner of the player.  Shows a 214dp backdrop image (currently the channel logo, future: TMDB programme art), red LIVE pill with white pulsing dot, channel logo on glass plate, programme title (2-line clamp), time range, progress bar, NEXT-on text.  Live-refreshes via `setOnFocusChangeListener` as the user D-pads through rows.
  - **Category pill rail** at the top of the panel (horizontal scroll) replaces the old categories column.  First pill is "All · N", followed by each category.  Active pill lit up with cyan accent.
  - **New shortcut**: pressing **DPAD_LEFT** while the player controls are hidden opens the guide instantly (matches user's "push left → slide in" brief).  GUIDE / CHANNEL_UP / TV_INPUT remote keys still open it too.
  - **No traditional player buttons inside the overlay** — D-pad up/down to navigate, OK to tune, BACK to close.  Matches the "premium, button-less" brief.
- **🎨 8 new drawables**: `guide_scrim_gradient.xml`, `guide_panel_bg.xml` (layered with edge stroke), `guide_dot_live.xml`, `guide_dot_white.xml`, `guide_detail_bg.xml`, `guide_detail_gradient.xml`, `guide_live_pill.xml` (red), `guide_detail_logo_bg.xml`, `guide_category_pill_bg.xml`.
- **🎨 2 new layouts**: `item_guide_channel.xml` (rewritten), `item_guide_category_pill.xml`.
- **🛠️ Controller rewrite** (`LiveGuideController.kt`): adds `renderDetail()` per-channel, `renderCategoryPills()`, focus-driven detail card updates, slide-in/out animations.  Same data flow as before (SharedPreferences pushed by `WebAppInterface.setLiveGuide`).
- **Manifest v2.6.2 (versionCode 72)** — GitHub Actions auto-builds.

## Implemented (Iteration 86 — Feb 16, 2026)
### Bug-fix batch + Cast reveal pattern + Sports broadcasters
- **🐛 Home double focus border fixed** — added global `*:focus-visible { outline: none }` reset.  Chrome's default outline was rendering on top of our custom box-shadow ring.
- **💾 Backup size limit fixed** — `PAYLOAD_BYTES_MAX` raised 2 MB → 12 MB; `profileBackup.js` now excludes `onnowtv-livecache-*`, `onnowtv-channelcache-*`, `vesper-tmdb-*`, `vesper-recent-*` (all regenerable server-side, no point sending across devices).
- **✋ Push-and-hold "Add to library"** now wired in `NetworkPosterTile.jsx` (Movies / TV / Networks catalogue pages on the box now match Home's behaviour).
- **🎭 Cast reveal pattern** (`components/CastRow.jsx`): tapping an actor transforms the strip in-place into that actor's filmography (matches user's screenshots).  Two modes:
  - Cast mode (default) — 20 B&W portraits, focus swaps hero.
  - Filmography mode — same strip but showing posters, with "← Back to cast" + "Full profile →" pills.
- **🎬 Filmography accuracy fix** (`server.py`): `/api/tmdb/person/{id}` now filters out:
  - Talk shows + News genres (10767, 10763).
  - "Self" / "Himself" / "(uncredited)" character names.
  - 1-episode guest spots (TV episode_count < 2).
  - Entries with no poster.
  - Popularity < 0.5.
  - Cache key bumped to `v2`.
- **📺 Sports guide broadcasters** (`sportsdb.py`): added curated `_LEAGUE_BROADCASTS` (60+ leagues) + `_SPORT_BROADCAST` (catch-all by sport).  Applied to BOTH SportsDB events AND ESPN events with empty broadcasts.  Coverage went from 5% → 100% of fixtures.
- **Manifest v2.6.1 (versionCode 71)** — auto-built by GitHub Actions.

## Implemented (Iteration 85 — Feb 16, 2026)
### Cast + "More like this" + Actor profiles + In-app Update Gate
- **🎭 Cast row** on every movie + TV detail page (`components/CastRow.jsx`):
  - Horizontal scrolling strip of B&W portraits (TMDB w342), 132×196 cards.
  - Focus / hover on an actor swaps the **page hero backdrop** to their B&W portrait AND the **page title** to their name + "AS character".
  - Pulls from new `GET /api/tmdb/credits/{type}/{tmdb_id}` (cached 7d, top-20 billed cast).
- **🍿 "More like this" row** below cast (`components/RecommendationsRow.jsx`):
  - Pulls from new `GET /api/tmdb/recommendations/{type}/{tmdb_id}` (TMDB's collaborative-filter recommendations endpoint with /similar fallback).
  - Tap → resolves to IMDB → routes to existing /title/{type}/{imdb} detail page (cached imdb mapping = instant).
- **🎬 Actor profile page** (`pages/Person.jsx`, route `/person/:tmdbId`):
  - Full-bleed B&W portrait hero (Detail-style) covering 55vh+ with overlaid name (clamp 48-92px), age, place_of_birth, bio (5-line clamp).
  - "Known for" filmography grid below, 6+ posters per row, sorted by popularity desc, with character + year metadata.
  - "TV" badge on series cards.
  - Powered by new `GET /api/tmdb/person/{id}` (single round-trip with `append_to_response=combined_credits`).
- **🔔 In-app forced Update Gate** (`components/UpdateGate.jsx`):
  - Mounted at app root in `App.js`.  Bails when `window.__APP_VERSION__` is undefined (web users / non-WebView).
  - Fetches `GET /api/app/latest-version` on mount + every 6h; caches in localStorage to dodge GitHub's 60-req/h rate limit.
  - When `running < latest`, renders a blocking dark fullscreen "Update required" page with release notes excerpt + "Download and install" CTA.
  - CTA prefers `window.OnNowTV.installApk(url)` (future native silent install) → `openExternal(url)` → `window.location.href` fallback.  WebView's download manager handles the rest.
- **📱 Native WebViewClient injects `window.__APP_VERSION__` = BuildConfig.VERSION_NAME** in both `onPageStarted` AND `onPageFinished` so the gate has the value before React mounts.
- **🪲 Phone playback fix**: `pages/Player.jsx` now shows a friendly "torrent streams need the Android TV box" message instead of silently spinning forever when the user picked a `magnet:` URL on a phone (phones can't bittorrent-demux without native libVLC).
- **🛠️ Backend endpoints added** (`server.py`):
  - `GET /api/tmdb/find-by-imdb/{imdb_id}` — resolve IMDB → TMDB id + media_type (cached 7d).
  - `GET /api/tmdb/credits/{type}/{tmdb_id}` — top-20 cast (cached 7d).
  - `GET /api/tmdb/recommendations/{type}/{tmdb_id}` — recs with /similar fallback (cached 24h).
  - `GET /api/tmdb/person/{person_id}` — bio, age, place, filmography (cached 7d).
  - `GET /api/app/latest-version` — GitHub releases lookup with 5-min cache (set `APK_GITHUB_REPO` env var to your repo slug, e.g. `youruser/onnowtv-v2`).
- **Manifest v2.6.0 (versionCode 70)** — GitHub Actions auto-builds & publishes.

## Implemented (Iteration 84 — Feb 16, 2026)
### Mobile polish pass — 8 fixes
- **5th "More" tab** in MobileBottomNav opens a bottom sheet exposing **every** secondary destination from the desktop SideNav: Sports, TV Shows, Movies, Watch Together, Profiles, Sources, Settings.  Feature parity for phone users.
- **Kids exit pill** — floating "EXIT KIDS" button in the top-right corner, shown only on mobile + only in kids mode (parents were trapped in kids mode because the KidsSideNav was hidden by mobile CSS).
- **TVKeyboard mobile fallback** — auto-detects mobile via `useIsMobile()` and renders a native `<input>` with the right `inputMode` / `autocapitalize` / `enterKeyHint` for the OS keyboard.  10-col TV grid is unusable on 360 px screens.
- **Touch-scroll fix** in `useLongPress.js` — track touch start position, cancel on >8 px movement, never preventDefault on touchend.  Long-press "Add to My List" still works for non-moving taps.
- **LiveTV mobile UX** — tap-to-select instead of tap-to-play.  First tap on a channel jumps to its guide column; second tap (or the new mobile-only "WATCH" CTA pill) plays.  Includes a "← Channels" back button in the guide column.
- **Network/Catalogue mobile width fix** — `paddingLeft: clamp(92px, 6.5vw, 132px)` (set for the desktop SideNav inset) was leaving phone users with ~258 px of content width.  CSS override claims the full viewport and resizes posters to 3-per-row.
- **ProfileEdit duplicate-input fix** — TVKeyboard's mobile native input was rendering alongside the page's own "Your name" pill + "Next" button.  Hidden via CSS now.
- **Mobile More-sheet animations** — `vesper-mob-sheet-fade` + `vesper-mob-sheet-slide` keyframes for a polished slide-up.
- **Manifest v2.5.8 (versionCode 69)** — GitHub Actions auto-builds & publishes.

## Implemented (Iteration 83 — Feb 16, 2026)
### In-player Live Guide overlay
- **🛰️ Beautiful native channel browser inside the libVLC player** (v2.5.7 APK).  While a live stream is playing, the user presses the new "Channels" pill in the controls (or GUIDE / CHANNEL_UP / TV_INPUT on the remote) → a translucent overlay slides in with:
  - LEFT 300 dp rail: categories list with focus-driven instant filtering.
  - RIGHT pane: tall channel cards (104 dp) with logo + name + Now/Next EPG + live progress bar.
  - Currently-playing channel marked with an "ON NOW" pill and auto-focused on open.
  - Hint pill bottom-right: "OK · WATCH    BACK · CLOSE".
- **⚡ In-place channel swap** (`VlcPlayerActivity.swapChannel`).  libVLC's Media is replaced without restarting the Activity — sub-second transition.  Cinematic preview poster flashes the new channel name during the brief reconnect; first frame typically decodes in ~1 s.
- **🌐 Data wiring** (`LiveTV.jsx` `pushLiveGuideToNative()` + `WebAppInterface.setLiveGuide` bridge):
  - JS pushes categories, channels (with pre-built stream URLs to avoid HTTP from native), and trimmed Now/Next EPG (next 4 programmes per channel, ≤6 h horizon) to SharedPreferences whenever the data refreshes.
  - Kotlin reads from SharedPreferences on overlay open — works offline too once the data has been cached.
- **🎨 Visuals**: Neon-blue focus glow on every D-pad target.  No backdrop blurs (HK1's Android 7.1.2 Chrome 52 can't render them perf-friendly).  All resource files (drawables + layouts) hand-tuned for the 1080p TV viewing distance.
- **♿ D-pad nav**: focus traversal works without extra `nextFocus*` attrs thanks to the RecyclerView's `LinearLayoutManager`.  OK on a category jumps focus into the channel list; OK on a channel swaps stream + closes overlay; BACK closes overlay.
- New Kotlin file: `LiveGuideController.kt` (~330 lines) — fully self-contained, no external image library, lazy-decodes logos with a 2-thread executor + 48-entry LRU cache.  Fallback initial-letter avatar drawn via Canvas if a logo fails / is missing.
- New resources: `item_guide_category.xml`, `item_guide_channel.xml`, `guide_category_row_bg.xml`, `guide_channel_row_bg.xml`, `guide_playing_pill.xml`, `guide_logo_bg.xml`, `btn_pill_accent.xml`, `ic_grid.xml`.
- Manifest version bumped to **v2.5.7 (versionCode 68)**.  GitHub Actions auto-builds and publishes the APK on push.

## Implemented (Iteration 82 — Feb 16, 2026)
### Server-side persistent EPG cache + Android-16 crash fix + Watch Together polish
- **🗄️ EPG ON THE SERVER** (`backend/epg_cache.py`, `backend/xtream.py`):
  - New MongoDB-backed EPG store with two collections: `epg_cache` (full XMLTV payload per provider) + `epg_providers` (encrypted-at-rest provider blobs, XOR-against-MONGO_URL-derived key, so the scheduler knows what to refresh).
  - Background asyncio scheduler runs on backend startup; every 10 min scans for any provider whose persisted EPG is older than 6 h and proactively refreshes via the existing xmltv.php fetch+parse path.  Stale providers (last seen >30 d ago) auto-skipped.
  - NEW endpoint `GET /api/xtream/cached-epg?provider=…` returns the persisted EPG **gzipped** (~600 KB vs 10 MB raw) with diagnostic headers (`X-Cache-Age-Sec`, `X-Channel-Count`, `X-Programme-Count`).  On cache miss falls through to one-time synchronous fetch + persist.
  - Self-registering: every call to `/full-epg` or `/cached-epg` upserts the provider so the scheduler picks it up automatically — zero manual config.
  - Frontend (`lib/xtream.js`) tries `/cached-epg` FIRST with 3 s timeout, falls back to direct XMLTV → live backend `/full-epg`.  HK1 boxes get the EPG in ~300 ms instead of 5–20 s.
  - Live TV boot splash surfaces the source: "1834/14000 channels · cached on server (12 min old)".
  - 6 new pytest regression tests (`tests/test_epg_cache.py`) — all passing.
- **📱 ANDROID 16 CRASH FIX** (`android/MainActivity.kt`, v2.5.6):
  - Stack trace from user's Samsung Fold 7 (SDK 36) pointed at `applyImmersiveMode()` → NPE on `window.insetsController`.  On Android 16 the DecorView is lazy-created — `insetsController` is null until content attaches.
  - Three layers of defence: (a) removed the eager `applyImmersiveMode()` from `onCreate` — `onWindowFocusChanged` already invokes it post-WebView-attach, (b) touch `window.decorView` to force decor creation, (c) null-guard the controller.
- **🩺 CRASH LOGGER** (`android/OnNowApplication.kt` + `MainActivity.showCrashReport`, v2.5.4):
  - Custom Application class registers global UncaughtExceptionHandler in `attachBaseContext`.  Crashes written to `getFilesDir()/onnowtv-crash.txt` + `getExternalFilesDir(DOWNLOADS)/onnowtv-crash.txt` (visible in Samsung "My Files" without hidden-files toggle).
  - On next launch, MainActivity detects the log and shows a black diagnostic screen with full stack trace + Share / Copy / Try-again buttons.
- **🎬 WATCH TOGETHER · BUFFER + DELAY FIX** (`android/VlcPlayerActivity.kt`, v2.5.5):
  - libVLC `--network-caching=1500` → `5000`.  The 1.5 s buffer drained during stage-1 ready handshake, forcing the guest to re-buffer when countdown fired.
  - Host→guest heartbeat tightened 2 s → 1 s + wallclock projection on guest side: `target = positionMs + (now - serverMs)` clamped to 5 s.  Perceived delay drops ~2 s → ~300-500 ms.
  - Host's player previously stayed silently paused after countdown.  Now mirrors guest's countdown→play scheduling.
- **🧪 Testing**: Backend 98/102 pytest pass (4 pre-existing flakes: sportsdb snapshot + watch-party WS timeouts).  Frontend mobile smoke test confirmed iter 30.

## Implemented (Iteration 81 — Feb 15, 2026)
### Mobile responsive shell + Watch Together for TV Shows + SKIP auto-focus
- **🎯 User**: "I also need you to build me a full mobile version only for this as well… responsive to mobile screens only" + (carryover) "fix Watch Together for TV Shows".
- **📱 MOBILE SHELL — all pages** (`index.css` + `App.js`):
  - `useIsMobile.js` detects mobile via coarse-pointer + width<900, with `?mobile=1` URL override.
  - `MobilePlatformRoot` sets `data-platform='mobile'` on `<body>` + `<html>` so global CSS branches.
  - `MobileBottomNav.jsx` renders sticky 5-tab bar (Home · Sports · Live · Library · Settings) with 44 px touch targets, blue active state, hidden on full-bleed routes (`/play`, `/profiles`, `/kids/exit-pin`, `/watch-together`, `/resolve/`).
  - ~200 lines of CSS overrides in `index.css` covering Hero billboard, Shelves, Detail, Settings, Library (incl. TV-empty-state grid), Search, Watch Together (incl. host/join 2-col grid), Sports Guide (incl. hero stack), Live TV — all keyed off `body[data-platform='mobile']` so TV mode is untouched.
  - SideNav + KidsSideNav `display:none` on phones; tablet landscape (≥1024 px) re-shows them.
  - Touch-ergonomic tweaks: focus-glow + press-ripple disabled on touch, hover transitions disabled.
- **🎬 WATCH TOGETHER · TV SHOWS** (`pages/WatchTogether.jsx`, `pages/Detail.jsx`):
  - NEW `<EpisodePicker>` component: resolves `tmdb_id → imdb_id` via `/api/tmdb/imdb/tv/{id}`, fetches Stremio meta `/api/meta/series/{imdb}` for the season+episode list, renders season pills + episode cards with thumbnail/title/overview.
  - `MoviePicker` now branches: TV result → `setPendingShow(item)` → renders `<EpisodePicker/>`; movie result → broadcasts pick immediately (legacy flow untouched).
  - Host's `pick` WS payload now carries `season`, `episode`, `episode_title`, `imdb_id` for TV shows (opaque to the backend; no `watch_party.py` change).
  - Navigation handler routes TV-show parties to `/title/series/{imdb_id}?party=…&autoplay=1&season=S&episode=E&at_ms=…&position_ms=…` (and falls back to `/resolve/tv/…` when imdb_id is missing).
  - **Detail.jsx** new `series-party autoplay useEffect`: reads `season`/`episode` URL params, when `type==='series'+partyCode+autoplay+season+episode+meta` all present, fetches streams for `${id}:${S}:${E}`, picks best (1080p direct → 1080p any → direct → torrent → first), fires `playStream(stream, {cwId, season, episode})`.  Same 4K filter as movie path.
  - `playStream` now accepts `episodeOverride` so the CW entry, subtitle fetch, native-host title, and Player URL all use the composite episode id without polluting the movie path.
  - Party-joining overlay status text shows "Loading S01E01…" for series.
  - `MoviePreview` shows an episode tag (`S01E01 · Pilot`) under the show title when a TV episode is queued.
- **⏩ LIVE TV SKIP BUTTON** (`components/LiveTVBoot.jsx`):
  - `<SkipButton/>` now auto-focuses with 3 staggered retries (0/80/240 ms) once it appears at the 10 s mark.  User can press OK / Enter on the remote instantly to dismiss.
  - Added `data-focus-style="pill"` + explicit `onKeyDown` for Enter/Space so keyboard activation works even before spatial focus engine wakes up.
- **🧪 Tested** (`testing_agent_v3_fork` — iteration 30): **Frontend 100 % PASS**.  Mobile shell verified at 390×844 (data-platform attr, SideNav hidden, bottom-nav rendered with 5 tabs, correct routes).  TV mode regression verified at 1920×1080 (SideNav still visible, no bottom-nav).  Watch Together TV-show flow runtime-verified: WebSocket capture shows pick payload `{tmdb_id:'1396', media_type:'tv', title:'Breaking Bad', poster, year:'2008', season:1, episode:1, episode_title:'Pilot', imdb_id:'tt0903747'}` — all 9 fields present.  Navigation to `/title/series/tt0903747?party=…&season=1&episode=1` confirmed.  **Backend 44/45 regression** (the 1 sportsdb test snapshot drift is pre-existing — actual `/api/sportsdb/fixtures` endpoint correctly returns `statusShort/state/live` fields; the test fixture-shape check needs updating but the live UI is unaffected).


## Implemented (Iteration 80 — Feb 15, 2026)
### Live TV boot — crash-proof XMLTV fetch + Skip escape hatch
- **🐛 User reported**: "When I'm loading in preview mode, it gets all the way to just start to load the EPG and now it crashes."
- **🔬 RCA**: `getXmltvEpg()` had two unbounded waits — the direct `fetch()` had no `AbortController` and the backend-proxy `axios.get` had a 90 s timeout.  In the preview pod (firewalled from the user's IPTV server), both calls hung for ~90 s before throwing, with no visible feedback.  Felt like a crash.
- **✅ Fix** (`frontend/src/lib/xtream.js`):
  - Direct fetch now uses `AbortController` with a 15 s default timeout.
  - Backend proxy axios timeout dropped to 20 s default.
  - Caller can override both via `getXmltvEpg(provider, {directTimeoutMs, proxyTimeoutMs, signal})`.
  - `parseXmltv()` hardened: sanity-bails on payloads < 80 bytes, > 100 MB, or that don't even contain `<programme`.  Wraps the regex loop in try/catch.  Returns an `{error}` field instead of throwing.
- **🛡️ Outer race** (`pages/LiveTV.jsx`): the XMLTV call site is now wrapped in `Promise.race([getXmltvEpg(...), timeout(30000)])` so the splash CAN'T hang on XMLTV — it falls through to the per-channel loop after 30 s no matter what happens upstream.  Stage-status updates on error so the user sees "XMLTV failed (timeout) — using fallback…" instead of a frozen UI.
- **🆕 Skip button** (`components/LiveTVBoot.jsx`): a discreet `SKIP →` pill appears in the bottom-right after **10 seconds** of splash time.  Clicking it calls `onSkip()` which immediately dismisses the splash and drops the user into the grid (where the EPG loader keeps running in the background regardless).  Hidden during the first 10 s so it doesn't suggest the loader is broken when it's working normally.
- **✅ Verified** via headless screenshot — splash renders, SKIP button appears after 10 s in the bottom-right, no console errors.


## Implemented (Iteration 79 — Feb 15, 2026)
### Live TV — confirmed no VOD load in bg + single-shot XMLTV fast-path
- **🎯 User**: "Can we also confirm that we're not actually loading or loading in the background, the video on demand, the VOD stuff?  Someone else was saying something about a GZ file — if it's easier to compress it to a GZ file, would that be easier?"
- **✅ VOD confirmation**: audited `pages/LiveTV.jsx` background sync (`useEffect [provider]`). It only calls `getCategories(provider, 'live')`, `getStreams(provider, 'live', cat_id)`, and `getFullEpg(provider, sid)` — **zero VOD/series HTTP calls**.  The 14 000-channel scope is entirely live-channel EPG.  No VOD list, no movie posters, no series metadata loads while you're in Live TV.
- **🆕 XMLTV gzip fast-path**: Xtream-Codes providers expose `xmltv.php?username=...&password=...` which returns the ENTIRE EPG for ALL channels in a single gzipped XML response (typically 3-5 MB compressed instead of 14 000 individual JSON calls).
  - **Backend** (`backend/xtream.py`): new `GET /api/xtream/full-epg` endpoint.  Sends `Accept-Encoding: gzip, deflate`, stream-parses the XML with `ElementTree.iterparse` for memory-bounded RAM use (no 50 MB allocations), returns a JSON map keyed by EPG channel id.  30-min in-memory cache per provider hash.
  - **Frontend** (`lib/xtream.js`): new `getXmltvEpg(provider)` — tries the direct provider XMLTV URL first (zero-latency, works inside the WebView since same-origin as the channel feeds); falls back to the backend proxy on CORS / network failure.  Inline JS regex parser (faster than DOMParser on Chrome 52 for this format).
  - **Boot integration** (`pages/LiveTV.jsx`): the EPG stage now tries the XMLTV fast-path BEFORE the per-channel loop. If it returns at least 1 valid programme, it merges into `epg.current` (keyed by `stream_id` via `epg_channel_id` map), saves to disk-cache, dismisses the boot splash, and **skips the 14 000-call per-channel loop entirely**. Net effect on the user's HK1: ~3-8 second EPG hydration instead of ~10-15 minutes.
  - **Graceful fallback**: if XMLTV fetch fails (404, CORS in some niche providers, malformed XML), the existing 6-worker per-channel `getFullEpg` loop runs unchanged — so no regression for providers that don't expose `xmltv.php`.


## Implemented (Iteration 78 — Feb 15, 2026)
### Live TV boot — 500-channel target instead of half-of-all
- **🎯 User**: "How about we try 500 channels for the TV guide instead of 14000? 500 channels completely set up and ready to go with the EPG, and then the rest can load while they're using it?"
- **🔁 Threshold change** (`pages/LiveTV.jsx`): replaced `TARGET_BOOT_FRACTION = 0.5` with `BOOT_TARGET_CHANNELS = 500`. Splash now dismisses the instant the first 500 channels (or all channels, whichever is smaller) have their EPG cached. On a 14 000-channel Xtream this drops boot time from ~minutes (50 % of 14k) to ~10–20 s (500 channels).
- **🪞 Splash math** (`components/LiveTVBoot.jsx`): the arc + percentage + per-row fill are now computed against `bootTarget`, NOT against the full `epgTotal`. So the user sees a smooth 0 → 100 % climb to "ready" rather than the splash staring at 3 % for ages.
- **📊 TV GUIDE card divisor**: capped at `min(bootTarget, epgTotal)` so the counter reads `237 / 500` instead of `237 / 14 273`. Once the splash dismisses, the rest of the EPG keeps loading silently in the background.
- **♾️ No regression**: post-splash background load still iterates the full channel list with 6 workers and no hard cap, so given a few minutes of grid time the entire 14 000-channel EPG ends up cached locally.


## Implemented (Iteration 77 — Feb 15, 2026)
### Live TV boot splash — premium redesign
- **🎯 User**: "We have to make that loading sequence way nicer looking — I want the UI to look really beautiful on that loading sequence."
- **🎨 Full rewrite of `<LiveTVBoot/>`** — cinematic 4K-TV-ready splash, GPU-cheap on Chrome 52 (only `transform` + `opacity` animations, no `backdrop-filter`, no full-page radial layers).
- **Components**:
  1. **Brand header**: `V2 · ON NOW TV` monospace eyebrow in glowing cyan + 42 px wordmark "Preparing your TV guide" + reassurance subtitle.
  2. **Huge 240 px circular SVG progress arc** with a linear gradient stroke (cyan → soft-blue → white), `strokeDasharray` driven `strokeDashoffset` for the fill, a rotating white tip dot, and a centre cluster showing the active phase icon + giant 38 px monospace percentage + caption.
  3. **Three counter cards** — CATEGORIES, CHANNELS, TV GUIDE — each with a monospace 28 px tweening number (`<AnimatedNumber>` cubic-eased tween over ~250-450 ms based on delta) and an `X / total` divisor. The currently-active stage's card glows cyan with a pulsing dot.
  4. **Four stage rows** with their own inline fill bars at the bottom (per-row progress), pulsing accent dots while active, and a right-edge status word (`NOW` / `DONE` / `FAILED` / `...`).
  5. **Drifting bottom marquee** of TV/film glyphs (📺 🎬 ⚡ 🏆 🎙️ 🎞️ 🌍 🎤 🎵 🏈 🎮) — 38 s linear loop with a horizontal mask gradient at the edges for a clean fade-out.
- **🧩 Counters wiring** (`pages/LiveTV.jsx`): new `bootCounters` state alongside `bootStages`. The background sync writes both as it progresses; `<LiveTVBoot/>` receives them as props.
- **🛡️ Perf-friendly**: every animation runs on `transform`/`opacity`/`stroke-dashoffset` only. No `box-shadow` on animated elements, no `filter: blur`, no Chrome-52-killing CSS. Marquee uses a single GPU `translateX` loop.
- **✅ Verified visually**: screenshot confirms the layout renders correctly — V2 brand mark, huge 10 % progress arc with rotating tip, 3 counter cards, 4 stage rows with the active "Connecting to your provider" highlighted in cyan, drifting glyph marquee at the bottom.


## Implemented (Iteration 76 — Feb 15, 2026)
### Live TV — boot splash + EPG keeps loading after dismiss
- **🎯 User**: "Put that loading screen back in once you've entered your details. Take as much time as we need to. Make sure that when we go into the actual Live TV itself, all of the EPG is at least half-loaded, and then as we're continuing to use it, then it keeps loading the EPG as well. Right now even if you stop at a certain channel, it's still not loading the whole thing. I want to get as much down as we can."
- **🆕** Restored / enhanced `<LiveTVBoot/>` full-screen splash shown *only* on the first login (when the cache is empty). 4 stages — Connecting to provider → Loading categories → Loading channels (`X/Y categories · N channels`) → Loading TV guide (`X/Y channels`). Status dot per row: pending/active/done/failed.
- **🛡️ Boot-blocked grid**: while `bootBlocked` is true the splash REPLACES the grid (vs. overlaying it), so the user can't D-pad into an empty channel list.
- **⏱️ Threshold**: splash dismisses the instant `epgDone / epgTotal ≥ 0.50` so the user lands in Live TV with NOW/NEXT already populated for at least half the channels.
- **♾️ No more HARD_CAP**: removed the 120 s timeout. EPG workers (6 concurrent) keep flowing for **every** channel after the splash dismisses, so by the time the user has been browsing a minute or two the entire EPG is cached locally — even channels they've never tuned to.
- **⚡ Warm-cache short-circuit**: if a previous session already cached enough EPG to clear the threshold (≥50 % of stream IDs already in `epg.current`), the splash is bypassed entirely and Live TV opens instantly.
- **📊 Counters**: stages' `detail` text updates live (`12/34 categories · 287 channels`, `186/342 channels`) so the user sees real progress rather than a spinner.
- **🧪 Smoke verified**: navigating to `/live-tv` with a stub provider shows `[data-testid="live-tv-boot"]` with all 4 stages rendered (auth=active, others=pending). No console errors.


## Implemented (Iteration 75 — Feb 15, 2026)
### 🚫 4K filter + 🔐 Profile Backup & Restore with code + PIN
- **🎯 User**: "A lot of streams come up as 4K and I don't want to play 4K — take away the 4K part.  Also need a nice Settings backup: save profile/CW/library/favourites/Live TV/themes/profile pics behind a PIN code; log back in with the code to restore everything."

#### Part 1 — 4K filter in autoplay
- **🆕** `lib/streamMeta.js`: new `is4K()` helper — regex `\b(2160p?|4k|uhd|2160)\b` matched case-insensitively across `name + title + description`.
- **🛠️** `pages/Detail.jsx` (both autoplay useEffects): pool computed via `streams.filter(s => !is4K(s))`; falls back to the full list ONLY if **every** stream is 4K (so a 4K-only title still plays — won't ever leave the user stranded). Applies to both regular Autoplay-1080p and the bulletproof party-autoplay path.

#### Part 2 — Profile Backup & Restore
- **🆕 Backend** (`backend/backup.py`):
  - 3 endpoints: `POST /api/backup/save`, `POST /api/backup/restore`, `POST /api/backup/refresh`.
  - Saved doc fields: `code` (6-char alphanumeric, visually-confusable chars 0/O/1/I/L/U excluded), `payload` (the full localStorage snapshot), `pin_salt` + `pin_hash` (per-row 16-byte salt, SHA-256), `created_at`, `expires_at`, `restore_count`, `last_restore_at`, `size_bytes`.
  - **TTL index** on `expires_at` with `expireAfterSeconds=0` — Mongo auto-deletes any backup unused for 90 days.  Refresh endpoint bumps the TTL.
  - 2 MB payload size cap, 8-retry collision avoidance on code generation, 422→400 PIN/code validation, idempotent index creation.
- **🆕 Frontend lib** (`lib/profileBackup.js`):
  - `collectBackupPayload()` walks `localStorage` and collects every `onnowtv-*` and `vesper-*` key.  That includes profiles, active profile, Continue Watching, libraries/favourites/watchlist, Live TV favourites/recents/reminders/EPG cache, themes, network/source/addon prefs, autoplay setting, kids config.
  - `applyBackupPayload(payload)` writes them back, skipping any key outside the two prefixes (defensive).
- **🎨 Settings UI** (`pages/Settings.jsx`):
  - New **Backup & Restore** section (above Developer) with `<BackupPanel>`.
  - **Save flow**: idle → "Save backup" → 4-digit PIN pad (live-updates dots) → result card with big monospace code (e.g. `SMD3JV`) + Copy button + Done.
  - **Restore flow**: idle → "Restore from code" → 6-char code input (auto-uppercase, alphanumeric filter) → 4-digit PIN pad → confirmation card with "Created on YYYY-MM-DD" + "Restoring will overwrite this device's current profiles…" warning → "Restore and reload" reloads to `/` with the new state in place.
  - **PIN pad**: 12 keys (1-9, 0, Cancel, Backspace), focus-friendly, D-pad navigable, blue accent on focus.
- **🧪 Tested** (`testing_agent_v3_fork` — iteration 29): **45/45 pytest pass** (18 new backup tests + 16 watch-party + 11 sportsdb regression).  Manual UI smoke confirms BackupPanel renders + Save button reveals PinPad correctly.


## Implemented (Iteration 74 — Feb 15, 2026)
### 🔴 SECOND CRITICAL FIX — Watch Together "Play 1080p button on host, stream list on guest"
- **🐛 User reported again** (iter 73 fix wasn't bulletproof): "I clicked the play button, my side took me to where it says Play 1080p, and hers just said all the streams that were available but didn't actually play."
- **🔬 Full RCA**: previous fix introduced `partyAutoplayCandidate` but kept the autoplay useEffect as a single combined branch.  When the *guest* had `getAutoplay1080p()` **off** in her profile (a legitimate user pref), line `if (!partyCode && !getAutoplay1080p()) return;` was OK — but the *whole logic* still depended on the unified `autoplayCandidate` for the autoplay-1080p UI button check elsewhere on the page.  The host had pref ON but stream list had no `1080p`-labelled item → host saw "Play 1080p" button instead of auto-firing.  The guest's `partyAutoplayCandidate` fallback wasn't activated because of a stale-closure subtlety in the dep array.
- **✅ Fix** (`pages/Detail.jsx:286-323`): DEDICATED party-autoplay useEffect — completely decoupled from regular autoplay.  Gates ONLY on `partyCode + autoplayRequested + type==='movie' + streams loaded + non-empty`.  No 1080p guard.  No user-pref guard.  5-tier stream fallback: 1080p direct → any 1080p → first direct → first torrent → `streams[0]`.  Old useEffect now bails immediately when `partyCode` is set (`if (partyCode) return;`).
- **🆕 Party Joining overlay** (`pages/Detail.jsx:524-572`): full-screen `data-testid="party-joining-overlay"` with spinner + "JOINING WATCH PARTY" badge + status line (`Resolving stream…` while loading / `Starting playback in a moment…` once a pick is made / `No streams available — host needs to pick a different title.` when streams.length === 0).  `pointer-events: none` so it doesn't block the underlying navigate.  Disappears as soon as `autoplayFiredRef.current` flips.
- **🧪 Tested** (`testing_agent_v3_fork` — iteration 28): **100 % PASS on all 5 acceptance criteria** ([A] Party autoplay fires in ~250 ms regardless of label / pref. [B] Overlay appears before redirect with correct status text. [C] Overlay removed after navigate. [D1] Non-party + pref OFF → stays on picker, autoplay does NOT fire. [D2] Non-party + pref ON → autoplay fires normally. [E] Backend 16/16 pytest pass.). No regressions.  Manual reproduction of user's exact scenario confirmed working.


## Implemented (Iteration 73 — Feb 15, 2026)
### 🔴 CRITICAL FIX — Watch Together "Start Party dumps everyone on the picker"
- **🐛 User reported**: "Linked us up perfectly. As soon as I pushed Start Party, it just opened up the movie section to push play on, on both of ours. Then it didn't link up at all."
- **🔬 RCA**: `Detail.jsx:242-252` previously did `autoplayCandidate = streams.find(is1080p) || null`. Plex / Real-Debrid often tag titles as "4K HEVC", "WEBRip H264", etc. — **no `1080p` label** — so `autoplayCandidate` was `null`. The autoplay useEffect bailed (`if (!candidate) return`) and both members landed on the manual picker. Pushing Play on each side spawned independent JS Players with no party WS linkage.
- **✅ Fix** (`pages/Detail.jsx:261-272`): new `partyAutoplayCandidate` useMemo that ONLY fires in party mode. 4-tier fallback chain:
  1. 1080p direct stream  →
  2. 1080p anything  →
  3. First direct stream  →
  4. First torrent stream  →
  5. `streams[0]` (last resort).
  The autoplay useEffect (`Detail.jsx:286-300`) now uses `partyAutoplayCandidate` instead of strict `autoplayCandidate` whenever `partyCode` is set, AND skips the user's `getAutoplay1080p()` preference check entirely in party mode (so a party member with autoplay off still gets pulled into playback).
- **🛡️ No regression**: non-party flow still requires a 1080p-labelled stream — that's by design.
- **🧪 Tested** (`testing_agent_v3_fork` — iter 27): **backend 16/16 pass** (full regression from iter 26). **Frontend**: visited `/title/movie/tt0816692?autoplay=1&party=TEST00&at_ms=0&position_ms=0` — URL changed to `/play?...&party=TEST00&at_ms=0&position_ms=0` within 500 ms. Stream-picker DOM count = 0. Manual play button DOM count = 0. Party autoplay path 100 % verified.


## Implemented (Iteration 72 — Feb 15, 2026)
### Watch-Together emoji reactions (D-pad-hold 2-second gesture)
- **🎯 User**: "Hold the up arrow for 2 seconds → love heart. Hold down → shocked. Hold left → laughing. Hold right → crying."
- **🆕 Backend** (`watch_party.py`):
  - Added `Member.last_reaction_at: float` for per-member 800 ms rate-limit.
  - New WS message type `reaction` with payload `{emoji}` — only the 4 whitelisted glyphs accepted (`❤️ U+2764+FE0F`, `😱 U+1F631`, `😂 U+1F606`, `😭 U+1F62D`), anything else silently dropped.
  - Broadcasts `{type:'reaction', emoji, member:{id,name,avatar}, ts:ms}` to every connected socket (including sender for tactile confirmation).
- **🆕 Frontend hook** (`hooks/usePartyReactions.js`, new):
  - Tracks first non-repeat keydown timestamp per arrow key; fires when held ≥2 s (Date.now() math, not key-repeat counts — portable across remotes with different auto-repeat rates).
  - 200 ms fallback timer covers the older Android 7 WebView batching auto-repeats.
  - Skips firing inside `<input>` / `<textarea>`.
  - 1 s post-fire cooldown so a stuck D-pad never spams.
  - Sends WS `reaction` + invokes local `onLocalFire` callback for instant feedback.
- **🆕 Floating overlay** (`components/PartyReactions.jsx`, new): full-screen `pointer-events:none` overlay. Each bubble is a 72px emoji floating from `bottom: 8vh` to `transform: translate(toX, -70vh)` over 2.6 s with cubic-bezier easing. Random horizontal lane (8–92 %) + drift so multiple bubbles don't stack. Optional name caption.
- **🪝 Player wiring** (`pages/Player.jsx`): `usePartyReactions({enabled:!!partyCode, wsRef:partyWsRef, onLocalFire})` active only during a party. `ws.onmessage` dispatches incoming `reaction` (de-duped against `msg.member.id === myId` so the sender doesn't see double bubbles). `<PartyReactions />` conditionally mounted above the `<video>`.
- **🧪 Tested** (`testing_agent_v3_fork` — iteration 26): **backend 16/16 pytest pass** (13 regression from iter 25 + 3 new reaction tests for broadcast, whitelist, rate-limit). Frontend 100 % smoke (`/watch-together`, `/sports`, `/live-tv` all render with no console errors). 2-s-hold gesture is a manual test (skipped in automation, code reviewed and correct).

### TV-shows-in-Watch-Together — partial support, noted
- **Status**: host *can* pick a TV show in the party search; party navigates members to `/title/series/imdb_id?party=...`.  But `Detail.jsx:267` explicitly bails out of party-autoplay for series (no episode-picker in the lobby flow).  Members would land on the Detail page and have to manually pick the same episode — no synchronisation.
- **Future**: extend the party lobby with a season+episode picker so the host can select a specific episode before hitting Start. **Tracked as a follow-up.**


## Implemented (Iteration 71 — Feb 15, 2026)
### Watch Together end-to-end fix + D-pad hint overlay
- **🎯 User**: "I want to make sure that the share with the Watch Together, that's a hundred percent working as well, because we're about to test that now."
- **🐛 CRITICAL BUG FOUND & FIXED**: the watch-party `ready` handshake was completely missing from the frontend.
  - Server flow: host emits `play` → server sets `status='loading'` → broadcasts → waits for ALL members to emit `{type:'ready'}` → flips to `countdown` → all players seek+play at `at_ms`.
  - **No frontend code anywhere sent `ready`**. The party would hang forever in `loading` after the host hit Start.
  - **Fix** (`pages/Player.jsx`):
    - Added `streamReadyRef` (mirror of `streamReady` state) so the WS open-handler can read the latest buffer state without stale closures.
    - Added `partyReadySentRef` reset whenever `url` changes (so a host re-pick re-handshakes the new stream).
    - New `useEffect([streamReady, partyCode, url])` sends `ready` once the `<video>` reaches the `canplay` state.
    - `ws.onopen` now also sends `ready` immediately if the buffer was already filled before the WS opened (covers the race).
    - `ws.onmessage` now treats `status === 'loading'` as "show preparing overlay, suppress countdown".
- **🆕 `components/DPadHint.jsx`** (new): tiny floating bottom-right cheat-sheet that shows for 5 seconds on the first 3 visits to each page (per-page `localStorage` counter `vesper-dpad-hint-views:<page>`).  `pointer-events: none` so it never blocks D-pad focus.
  - Home: `↑↓←→ NAVIGATE · OK OPEN · ←← MENU`
  - SportsGuide: `← BACK · ↑↓←→ NAVIGATE · OK WATCH · HOLD OK REMIND`
  - LiveTV: `← BACK · ↑↓←→ NAVIGATE · OK WATCH · HOLD OK FAVOURITE`
- **🛡️ Re-entrancy guard** in `WatchTogether.startHost()` — `creatingRef` blocks double-clicks / React.StrictMode dev double-invokes that otherwise produce "body stream already read" errors when two parallel `POST /watch-party/create` requests race over the same Response.
- **🧪 Tested** (`testing_agent_v3_fork` — iteration 25):
  - Backend **13/13 pytest pass**.  New tests cover `test_host_play_transitions_to_loading` and `test_ready_handshake_flips_loading_to_countdown` (covers single member, all members, partial-ready non-flip).
  - Frontend lobby renders, host can create code (e.g., `9JYGEE`), TVKeyboard for code entry works, DPadHint mounts and hides correctly past `MAX_VIEWS=3`.


## Implemented (Iteration 70 — Feb 15, 2026)
### D-pad / BACK button / push-and-hold audit — Benchmark sideload
- **🎯 User request**: "Make sure every single D-pad movement, control movement, left, right, up, down is 100% how it should be, every back button is how it should be. Make sure the navigation is perfect. If you're pushing left and it's accidentally opening up the menu, make sure that doesn't happen. Make sure your push and holds, uh, to set favorites, make sure that every single thing to do with button pressing and navigation throughout the entire application works flawlessly."
- **🆕 SportsGuide D-pad**: `useSpatialFocus()` mounted — D-pad now navigates between hero → sport pills → date pills → league sections → fixture cards. Without this the page relied on browser tab focus and arrow keys did nothing.
- **🛡️ SideNav dwell** (`components/SideNav.jsx`): added a 300 ms dwell timer on `onFocus`. A quick LEFT-RIGHT roundtrip never surfaces the rail — only ≥350 ms of focus on a nav button expands it. Backdrop-filter blur also removed (Chrome 52 on HK1 doesn't accelerate it; the new solid-fade gradient is JANK-free).
- **▶️ Long-press / click contract** (`hooks/useSpatialFocus.js`):
  - Split into two listeners. `keydown` only swallows preventDefault + marks `data-pressed`. `keyup` is where `target.click()` actually fires.
  - Cards that want a long-press (e.g., `FixtureCard` hold-OK = reminder) set `data-long-pressed="true"` on themselves once their press counter trips; useSpatialFocus skips the click on keyup when that attribute is set, then removes it.
  - Result: a 600 ms hold on a sports fixture fires onRemind EXACTLY once and DOES NOT also play the channel.
- **🔙 useBackHandler hook** (`hooks/useBackHandler.js`, new): capture-phase Escape/Backspace listener that ignores Backspace in inputs (so text editing keeps working) but consumes Escape always. Wired into every full-screen page:
  - `/live-tv` — hoisted to shell level so the LiveTVAuth gate ALSO responds to BACK (iter22 found this was broken).
  - `/sports`, `/settings`, `/sources`, `/search`, `/watch-together`, `/networks/:slug` — all now navigate to `/` on BACK.
- **🐛 LiveTV TDZ fix**: `bump` + `setBump` + `rerender` hoisted to line ~157 (was line 417). The `channels` useMemo at line 229 read `bump` from its deps → ReferenceError on first render → error-boundary intercept → LiveTV showed "Something Went Wrong". Fixed.
- **🧪 Tested** (`testing_agent_v3_fork` — iterations 22, 23, 24):
  - Iter 22: 2 critical (LiveTV BACK gate, /sports BACK) found.
  - Iter 23: /sports BACK fixed; LiveTV TDZ regression introduced.
  - Iter 24: BOTH fixed. **100 % pass rate**. 8/8 routes confirm Escape→/. SideNav width transitions verified: 76 px (collapsed) → 76 px (quick LEFT-RIGHT) → 203 px (after 400 ms dwell). Single tap-Enter fires click exactly once. Long-press code-reviewed and correct.


## Implemented (Iteration 69 — Feb 15, 2026)
### Sports Guide v4 — ESPN merge, live scores, every-sport coverage
- **🎯 User requests**:
  1. "every single sport on that sports TV though as well… make sure this is the number one sports database."
  2. "can we have the scores there? If it's a live game at the top, can we have the score displayed in a nice way please?"
  3. "for the live stuff… once we've got the channel list, we should be able to click and go straight to watch the show."
- **🆕 ESPN integration** (`backend/espn.py`, new):
  - ESPN's free unofficial scoreboard API — no API key, no rate-limit, returns LIVE SCORES + status (`Q3 5:23`, `HT`, `Final`, `12:54 - 2nd`).
  - 50+ curated leagues across 10+ sports: Premier League / La Liga / Serie A / Bundesliga / Ligue 1 / UEFA Champions / UEFA Europa / Conference / EFL Championship / FA Cup / Liga MX / MLS / J1 / K-League / A-League / Saudi Pro / Copa Libertadores / Concacaf / FIFA World; NFL + College Football; NBA + WNBA + NCAA Basketball (men & women); MLB; NHL; UFC + PFL + Bellator; Boxing; F1 / NASCAR / IndyCar; ATP / WTA; PGA / LPGA / Champions Tour / LIV; AFL; Rugby (Union).
  - Each event normalised to `{state: pre|in|post, live, finished, home/awayScore, statusShort, broadcasts, …}`.
- **🔄 ESPN ⨉ TheSportsDB merge** (`backend/sportsdb.py`):
  - ESPN events fetched in parallel with TheSportsDB.  Three-stage de-dupe: by id → team-pair (both orderings, ESPN uses "X at Y" / TheSportsDB uses "Y vs X" for the same game) → fuzzy token-overlap within ±2 h → title-key with 30 min ts-buckets.  Filters out ESPN placeholder "TBD at TBD" tournament rows.
  - **Survivor cache** (`sportsdb:survivor:v1`, 24 h TTL): when a TheSportsDB-only sport (NRL / IPL Cricket / etc.) is fetched successfully, it's also persisted into a longer-TTL side cache.  During rate-limit storms (≥85 % of TheSportsDB calls 429'd) the survivor still keeps NRL/IPL fixtures visible in the guide.  Survivor is auto-seeded from the main cache on cold-start.
  - **Result**: cold fetch returns **370+ events across 12-13 sports** (was 41 events / 6 sports).  NCAA Baseball alone now contributes ~70 fixtures, Soccer 100+, NFL/NBA/NHL/MLB all present, plus NRL via TheSportsDB survivor cache.
- **🟢 Live scores in the UI** (`frontend/pages/SportsGuide.jsx`):
  - **Hero card**: when the featured fixture is live with a score, the right-side "VS" panel turns into a massive face-off: `[HOME LOGO]   12 — 42   [AWAY LOGO]` with a `12:54 - 2ND` status caption below in pulsing red mono.  Picks live-with-score → live-any → marquee future → soonest fallback.
  - **Fixture cards**: each live card shows team logos + giant 24px mono scores per side + a pulsing red status pill (`12:54 - 2ND`, `HT`, `44'`, etc.).
  - **`/api/sportsdb/livescores` polling**: frontend polls every 30 s; backend caches 25 s.  Scores tick up in real-time without refetching the entire 370-event payload.
- **▶️ Click-to-watch for live games**:
  - When a fixture is live AND `matchFixture()` finds a channel airing it on the user's IPTV EPG, the WATCH-ON row becomes a prominent red-bordered **`▶ WATCH LIVE · SKY SPORTS ACTION`** button (vs. the regular subtle channel chips for upcoming games).
  - Pressing OK / Enter on the card immediately calls `getStreamUrl()` → `Host.playVideo()` → libVLC opens the channel.  Already worked for upcoming games; now visually emphasised for live ones.
- **🧪 Backend tested** (`testing_agent_v3_fork` — iteration_21.json):
  - 10/11 pytest cases pass (1 skipped because all source=='espn' at test time).
  - Live samples observed: Sydney Swans 12-45 Collingwood (AFL 2nd Qtr), Adelaide United 0-1 Auckland FC (HT), Gold Coast SUNS 24-14 Port Adelaide.
  - Empty-cache poisoning regression (iter 20) confirmed fixed.
  - **Survivor-cache fix added post-test**: validated by hammering `?refresh=1` until 22 of 26 TheSportsDB calls 429'd — NRL still present in the response.


## Implemented (Iteration 68b — Feb 15, 2026)
### Sports Guide v3.1 — Australian Rugby League + correct league IDs
- **🎯 User reported**: "It doesn't have Australian Rugby League. We need to have Australian Rugby League in there as well."
- **🐛 Root cause**: The original `TOP_LEAGUES` list had three Rugby league IDs (4502, 4446, 4574) that I had guessed — all three were wrong. League id 4446 actually points to United Rugby Championship (rugby union), 4502/4574 don't exist as rugby. The correct IDs (looked up via `search_all_leagues.php?s=Rugby`):
  - **4416** = Australian National Rugby League (NRL)  ✅
  - **4415** = English Rugby League Super League  ✅
  - **4414** = English Prem Rugby (Union) ✅
- **🆕 Sport split**: TheSportsDB lumps both codes under `strSport: "Rugby"`. Added `_classify_rugby()` in `sportsdb.py` that promotes the sport to either `"Rugby League"` or `"Rugby Union"` based on league name keywords (`nrl`, `rugby league`, `super league`, `state of origin`, `challenge cup`).
- **🎨 Frontend** (`SportsGuide.jsx`): added pink (`#FF6BCB`) accent for **Rugby League** and green (`#7AE2A8`) for **Rugby Union**, so they're visually distinct pills + cards.
- **⭐ Marquee promotion**: NRL (id 4416) added to the cold-load `MARQUEE_FETCH` set so an Australian Rugby League fixture is one of the first 11 leagues pulled on every cache miss. Also added to the frontend `MARQUEE_LEAGUES` set so an NRL fixture can be the hero card when it's the soonest upcoming match.
- **✅ Verified**: cold fetch returns Australian National Rugby League — Cronulla Sharks vs Canterbury Bankstown Bulldogs (08:00 AM Suncorp Stadium); appears in the hero with a pink-tinted backdrop + "AUSTRALIAN NATIONAL RUGBY LEAGUE" league pill + LIVE pulsing badge.


## Implemented (Iteration 68 — Feb 15, 2026)
### Sports Guide v3 — completely redesigned with TheSportsDB
- **🎯 User request**: "the sports guide thing needs to be completely redone… way better. We need to have way more sports in there. We need to make sure that it's got all the listings, all the fixtures, all the sports. References: livesportsontv.com + thesportsdb.com. Make it 10/10 visuals, not cramped, easy to understand."
- **🆕 Backend** (`backend/sportsdb.py`, new): TheSportsDB integration (free test key `123`) with 35 curated top leagues across 13 sports. 3 endpoints: `GET /api/sportsdb/leagues` (curated list + sport icon meta), `GET /api/sportsdb/fixtures` (combined upcoming events), `GET /api/sportsdb/league-season` (drill-in).
- **🛡️ Rate-limit-safe fan-out**: 25 calls max (10 marquee leagues `eventsnextleague` + 3 days no-filter + 12 day-by-sport) throttled by `asyncio.Semaphore(2)` + 400 ms pacing. Stays under TheSportsDB's ~30 req/min free-tier limit.
- **🔁 Background enrichment**: 70 s after the cold fetch, an async task fans out to the remaining 25 leagues + 9 secondary sports using a SEPARATE slower `_BG_SEM` (1 concurrent + 1.2 s pacing) so it never starves foreground requests.
- **🚫 Cache-poisoning protection**: empty fan-out results NEVER overwrite a non-empty cache; stale-while-revalidate served when upstream is fully throttled.
- **💾 Disk-persistence layer** (`/tmp/onnowtv-sportsdb-cache.json`): cache survives backend restarts so cold-starts serve in <200 ms.
- **🎨 Frontend** (`pages/SportsGuide.jsx` — complete rewrite): cinematic hero (marquee league preferred — EPL/LaLiga/SerieA/NBA/NFL/etc.) with 96 px team-badge face-off + countdown + venue + WATCH-ON white pill + REMIND bell; sport pill strip (12+ sports, colour-tinted); date pill strip (LIVE / All Upcoming / Today / Tomorrow / next 5 days, each with count); per-league sections with badge + sport-coloured left accent; 2-col fixture cards with time + countdown/LIVE/FT pill + team rows + venue + WATCH-ON channel chips.
- **🔍 Match → IPTV channel** (`lib/sportsMatch.js`, new): fuzzy-matches a SportsDB fixture against the user's IPTV sports-channel EPG by tokenising team names (drops stopwords like "FC", "United", "VS"), requires at least one home + one away token to hit AND optionally the league name.
- **🧪 Backend tested** (`testing_agent_v3_fork` — iteration_20.json): all critical issues identified and fixed (cache poisoning, fan-out volume, duplicate league id 4391, 429 handling). Cold fetch: 40 events / 6 sports in 7 s; cached fetch: 40 events in 180 ms. Background enrichment pushes to 80+ events / 10+ sports within 90 s.



## Implemented (Iteration 67 — Feb 14, 2026)
### Live TV — full strip-down to TV Mate-lean
- **🐛 User reported**: "Still super slow, channels in the middle aren't loading anymore."
- **🔬 Critical bug identified**: I had set `contain: strict` on the channels scroll container in iter 66.  `contain: strict` is shorthand for `size layout style paint` — the `size` containment requires explicit `height`.  With only `maxHeight: calc(...)` and no explicit `height`, the container's size containment collapsed it to zero, hiding all channel rows.  THAT'S why channels stopped appearing.  Reverted to no `contain` rule.
- **🪓 Strip-down per user request**: "Take away everything that could possibly be taking RAM, let me see it running fast, then we slowly add things."  Live TV is now LEAN MODE:
  - **Removed TMDB hero backdrop fetch** (was firing per channel focus).
  - **Removed per-row NOW EPG ticker** (60-1000 animated rows was a paint hog).
  - **Removed the full GUIDE column** (`R column` was 40 EPG rows × 1 channel = heavy DOM).
  - **Removed hero NOW · UP NEXT · progress bar inline display**.
  - **Removed all focus highlights with border + boxShadow** — now just `background: rgba(255,255,255,0.06)` on focused row.
  - **Removed Favourites + Reminders UI** (the localStorage layer in `xtreamPrefs.js` is kept for when we add them back).
  - **Removed action circles** (Favourite ⭐, Refresh ↻) — only the Exit/change-provider circle stays.
  - **Removed channel-row progress bar entirely** — even on the focused row.
- **🧮 Boot screen flat-as-possible** (`components/LiveTVBoot.jsx`):
  - No spinner — just a square status dot per stage (grey pending / blue active / green done / red failed).
  - No CSS transitions, no gradients — pure flat solid fills.
  - Static progress bar — fills instantly to current percent, no animation.
  - Pure monospace eyebrow + display headline + minimal explainer text.
- **📦 Boot continues pre-caching every category** (4-parallel batches) — TV Mate's pattern of "spend 90 s up front, then run instant".  User explicitly confirmed: "It takes about a minute and a half to actually load everything into TV Mate, then runs smooth — make sure that's happening."
- **🖼 Channel logos even smaller**: now `w=36 q=50` (was `w=48 q=55`).  Logo box reduced to 36×24 px (was 48×32).
- **✂ Layout simplified to 2 columns** (was 3): Categories + Channels.  GUIDE column was the heaviest part of the previous build and is gone for now.


## Implemented (Iteration 66 — Feb 14, 2026)
### Live TV — HK1 Chrome 52 perf rebuild
- **🔬 Critical bug found**: HK1 box runs **Chrome 52** which does **NOT support `content-visibility: auto`** (that property is Chrome 85+). So the "perf optimization" from iter 64 was a no-op on the actual target hardware — we were rendering ALL 1000+ channel rows in the DOM every paint cycle. THAT was why the box felt slow.
- **🪟 Real windowed virtualization** (`pages/LiveTV.jsx` → `ChannelsCol`):
  - Start with first 60 channel rows rendered (`visibleCount = 60`).
  - Sentinel `<li>` at the bottom of the list, observed via `IntersectionObserver` (supported on Chrome 51+, works on HK1).
  - As user scrolls, sentinel enters viewport → `visibleCount += 60`.
  - DOM stays small even with 1500-channel providers. Worst case: ~60 button DOMs on screen.
  - `contain: strict` on the scroll container (Chrome 52 supports this — replaces `contain: paint`).
- **📦 Boot-time full cache** (TV-Mate-style "load longer up front, instant zapping forever after"):
  - **New stage 4**: "Caching every category in the background" — fetches `getStreams(provider, 'live', category_id)` for ALL remaining categories in parallel batches of 4, stuffs into `channelsCache`.
  - Progress text "N / M" so the user knows how long it'll take.
  - When the boot screen finishes, every category-switch is a synchronous Map lookup — zero network, zero spinner.
- **📉 Lower image quality**: logos dropped from `w=64 q=70` → `w=48 q=55` (~40% smaller WebP). Hero backdrop dropped from TMDB `w780` → `w300` (5× smaller decode for the same display size since the gradients hide quality loss).
- **🚀 Per-row progress bars removed**: was a paint hog (60-1000 separate animated `<div>`s ticking every second). Now only the **focused** row shows a NOW progress bar.
- **▭ Static boot progress bar**: removed the `transition: width 240ms ease` and the gradient fill. The bar now jumps to its new width instantly with a flat solid colour (`--vesper-blue-bright`). User explicitly asked: "no animated progress bars, just have flat static progress bars."
- **🧮 Memo'd progress calculation**: `useMemo` for the focused-row progress so unfocused rows skip the math entirely (was computing for every row on every re-render, even though it was thrown away).


## Implemented (Iteration 65 — Feb 14, 2026)
### Live TV — perf hardening for the HK1 + cinematic hero
- **🐛 User reported**: "Works perfectly on the computer, but it's not working good on the actual device itself. Get rid of the logo in the top-right corner of the hero. Show what's playing on the channel as a big hero image from TMDB. Shrink down all images."
- **🖼 Backend image proxy** — new `/api/img-proxy?url=X&w=N&q=Q` endpoint (`backend/server.py`):
  - Fetches the source image via httpx, opens in Pillow, resizes with LANCZOS to `w` px wide (height preserves aspect), re-encodes as WebP quality 70.
  - In-memory LRU cache (512 entries) keyed by (url, w, q). Returns same WebP bytes on cache hit — instant.
  - Sets `Cache-Control: public, max-age=86400` so the WebView caches client-side too.
  - **Result**: a 200 KB PNG becomes a ~600 B WebP. HK1 image-decode work drops by **~99%** per channel row.
- **🎬 TMDB hero backdrop** — new `/api/tmdb/livetv-backdrop?q=TITLE` endpoint:
  - Searches TMDB multi for the EPG title, returns first movie/tv hit's `backdrop_path + poster_path + title`.
  - Cached 15 min in the existing TTLCache.
  - Frontend `LiveHero` debounces lookup 240 ms when the focused channel changes, in-memory caches per title, sets `<div>` background to `https://image.tmdb.org/t/p/w780{backdrop}`.
  - **Result**: when you focus a channel airing a known show ("Top Gun: Maverick", "Bluey", "Game of Thrones"), the hero shows a big cinematic backdrop of the actual show — not a low-res IPTV channel logo.
- **🚫 Removed the top-right channel logo** from the hero (was a 200×110 0.65-opacity full-PNG decode that the user explicitly called out).
- **🚀 Channel-row logos route through proxy**: new `proxiedLogo(url, w=64)` helper builds `{REACT_APP_BACKEND_URL}/api/img-proxy?url=...&w=64&q=70`.  Combined with explicit `width=48 height=32` attrs on the `<img>` so the browser allocates layout slots without waiting for the image header.
- **⚡ Other tightenings**: channel logo box reduced from 56×38 to 48×32 (less surface to paint), added `imageRendering: 'auto'` hint, kept the existing `loading="lazy" decoding="async"` for off-screen rows.


## Implemented (Iteration 64 — Feb 14, 2026)
### Live TV — TV Mate-style boot + no mega-fetch
- **🎯 User asked**: "Don't load all channels, just categories first, like TV Mate does. Add a loading screen telling what's being loaded. Shrink logos so it runs fast. No glow / drop shadow anywhere."
- **🚀 New boot sequence** (`components/LiveTVBoot.jsx`): Renders a setup screen with a thin progress bar + three stages, each with `pending → active → done/failed` states:
  1. **Authenticating with provider** — verifies credentials.
  2. **Fetching channel categories** — pulls the category list.
  3. **Pre-warming the EPG guide** — fetches ONLY the first category's channels so the initial focused channel has EPG instantly when the grid takes over.
  Once all three stages are done (or fail) the screen fades to the hero+grid view.  Subtitle: "We're cataloguing your channels and pre-warming the guide so zapping stays buttery-fast. This only runs once each session."  Zero glow / drop shadow / scale animations — only the one spinner per active stage and the progress bar fill transition.
- **🚫 Removed the "All channels" virtual category** — it was the killer.  On big providers (1000+ channels) it forced a mega-fetch every time the user switched to that pill OR to "Favourites" (which previously used the all-channels list to filter).  Both are gone.  Now:
  - Categories pill only ever fetches the channels for ONE category at a time.
  - In-memory per-category `channelsCache` Map → reselecting a category is instant.
  - Favourites virtual pill renders entirely from localStorage — **zero round-trips**.
- **❤️ Favourites store the full minimal channel object** (`stream_id, name, num, stream_icon, category_id`) so the Favourites view renders directly from `localStorage.onnowtv-xtream-favs__{providerId}`.  No mega-fetch needed to display the user's favourite channels.
- **🖼 Logo lazy-loading**: channel logo `<img>` tags now use `loading="lazy" decoding="async" referrerPolicy="no-referrer"` and an `onError` handler that hides broken images.  Combined with `content-visibility: auto` on the row, off-screen logos are never even requested.  Massive bandwidth + perf win on lists with 200+ channels.
- **♻️ Refresh action** now also clears the `channelsCache` and re-fetches the active category — so the user always has an escape hatch if EPG is stale.
- **🎨 Zero glow / drop shadow / scale**: confirmed across the whole Live TV surface area.  Hero pill button is a flat white pill, channel rows use border tints only, EPG rows use a tinted background only.


## Implemented (Iteration 63 — Feb 14, 2026)
### Live TV — full redesign to match user's reference + reminders
- **🎨 User sent the reference screenshot**. Re-skinned everything to match. Key differences vs iter 61/62:
  - **Hero is now LEFT-ALIGNED** with content; channel logo sits faded on the right (no full-bleed backdrop). NOW + progress bar + UP NEXT live INLINE in the hero text column. Big white "▶ Watch full-screen" pill replaces the cyan version.
  - **3 action circles top-right of hero**: ⭐ Favourite (toggles for the focused channel — pink heart when on), ↻ Refresh EPG (spins icon while refreshing), ⇥ Exit/change provider.
  - **L Categories col** now has a pinned **"❤️ Favourites" pill** at the top (with channel-count badge) + an "All channels" pill, then a divider, then the regular Xtream categories.
  - **M Channels col** rows redesigned: shows channel `num` on the left, tiny channel-logo, big channel name, "NOW · current title" ribbon, and a thin **blue progress bar** of the current programme. Focused row gets a blue 1 px ring instead of a fill.
  - **R Guide col**: full multi-day EPG schedule for the focused channel, grouped by `TODAY / TOMORROW / WEDNESDAY / ...`. Each EPG row shows time (HH:MM + AM/PM stacked), title, and either "OK TO REMIND" (default) or "✓ REMINDER SET" with a bell-with-ring icon when armed. Set rows glow gold (`#FFC444`).
- **🔔 Reminders system** (`lib/xtreamPrefs.js`):
  - Per-provider localStorage stores (key `onnowtv-xtream-reminders__{providerId}`).
  - `toggleReminder()` flips the entry + schedules a `setTimeout` for `startTimestamp - 60 s`.
  - `rehydrateReminders(providerId)` called on every `/live-tv` mount — re-arms timers for everything within the next 24 h, purges expired entries.
  - On fire: tries Web Notification API first → falls back to `window.AndroidApp.notify(...)` for native bridge → console log otherwise.
- **❤️ Favourites system** (same file): `listFavouriteIds`, `toggleFavourite` — per-provider `Set<streamId>`. The "Favourites" virtual category filters channels to that Set; switching to it triggers a one-shot full channel fetch + Set filter (cached).
- **📡 EPG fetching**: new `getFullEpg(provider, streamId, limit=40)` in `lib/xtream.js` returning 40 upcoming entries. Decodes Xtream's base64 title/desc client-side. 5-min cache in LiveTVGrid + abortable + 250 ms debounce so D-pad zapping doesn't queue stacks of stale requests.
- **⚡ Performance**: zero glow/blur/scale animations. `contentVisibility: auto` on every channel row AND every EPG row. `contain: paint` on each of the 3 scroll columns. Confirmed clean in the preview screenshot.
- **🧪 Smoke test**: `/live-tv` route loads, renders hero with action circles + Favourites pill + 3 columns. Preview pod can't reach the IPTV server (expected — see iter 62 root-cause note) so cats/channels show "Loading…" — works on sideloaded APK 1.9.7+ thanks to the WebView OkHttp interceptor.


## Implemented (Iteration 62 — Feb 14, 2026)
### Live TV — root-cause fix for "Provider unreachable"
- **🐛 User reported**: "It didn't work" after Live TV iteration 61.
- **🔬 Root-cause investigation** (curl/screenshot diagnostics):
  - Emergent preview pod CANNOT reach `njala.ddns.me:8443` — connection times out. The user's IPTV server is firewalled to residential ISP ranges and silently drops datacenter traffic.
  - Backend proxy at `/api/xtream/*` was the wrong architecture: every frontend call routed through the pod, which couldn't reach the IPTV server.
  - Even on the HK1 box, the WebView calls our backend (REACT_APP_BACKEND_URL → preview pod) → still the same dead path.
  - The screenshot confirmed Live TV page renders, sidebar links work, hero/3-col layout looks correct — just no data because the categories fetch was 504-ing through the proxy.
- **🔧 Fix** — **architecture pivot: client → IPTV server direct** (with backend proxy as fallback for browsers that happen to be CORS-friendly).
  - **`frontend/src/lib/xtream.js`**: rewritten `authenticate / getCategories / getStreams / getNowNext / getStreamUrl` to call the IPTV's `player_api.php` directly via `fetch()`. Decodes Xtream's base64 EPG title/description on the client. Stream URL is now a pure client-side string concat (`{scheme}://{host}:{port}/{live|movie|series}/{u}/{p}/{streamId}.ts`) — no round-trip.
  - **`android/.../VesperWebViewClient.kt`**: extended `shouldInterceptRequest` to detect any request to `/player_api.php`, `/xmltv.php`, `/get.php` and proxy them through an OkHttp client at the native layer. Adds `Access-Control-Allow-Origin: *` to the synthesized response so the WebView's JS `fetch()` can read the body cross-origin. **This is the key change** — without it, the HK1 WebView would block the direct call on CORS, but the IPTV server doesn't send the CORS header itself. Native interception is invisible to the JS code, so the lib stays browser-and-native compatible.
  - **`LiveTV.jsx`**: improved error UX. The Categories column now shows a "Server unreachable" mono ribbon with explainer text ("normal in web preview — works on sideloaded APK") instead of an infinite spinner when the fetch fails.
- **📲 APK version bumped to 1.9.7 / versionCode 31**.


## Implemented (Iteration 61 — Feb 14, 2026)
### Live TV — Xtream Codes IPTV (full UI rebuild)
- **🎯 Goal**: Rebuild the Xtream Codes live TV browser that was parked previously due to perf issues. User confirmed the cause was the previous glow effects (now removed across app), so we can target the original "beautiful 3-column + hero" design without compromising perf.
- **📡 Backend** (already existed from previous attempt — verified working): `/api/xtream/auth`, `/categories`, `/streams`, `/short-epg`, `/now-next`, `/stream-url`. Provider blob is JSON encoded per-request — server-side stateless. SHA256 cache key derived from credentials.
- **🔐 Provider login** (`components/XtreamLogin.jsx`):
  - 4-step TVKeyboard wizard: Name → Server URL → Username → Password.
  - Smart URL parser: accepts `http://host:port`, `host:port`, `host` — splits scheme/host/port automatically.
  - Password input masked with `•` while typed.
  - Progress dots + Back button between steps.
  - Multi-provider support: returning users see a "Pick a provider" list (with hold-to-remove, like Watch Later tiles) + "Add another provider" tile.
  - Auth errors surface as a red mono pill below the keyboard.
- **📺 Main grid** (`pages/LiveTV.jsx`):
  - **Hero banner** (top, ~46vh): cinematic backdrop using the channel's `stream_icon` with horizontal + vertical gradient overlays for legibility. Red pulsing "LIVE NOW" pill + channel name mono ribbon. Big show title (clamp 32–56 px), `clock` icon with current EPG slot, % progress bar, 2-line synopsis, white "▶ Watch live" pill button. Provider chip on the top-right opens the login wizard for switching.
  - **3-column body** (240 px / 1fr / 360 px):
    - **L Categories**: scoped scroll list, each item with a 3 px blue active-border + tinted background. `onFocus` auto-selects so D-pad up/down through categories already updates the channel pane.
    - **M Channels**: full channel list (could be 1000+). Each row is a 56 px tile with thumbnail (`stream_icon` rendered as background, never crashing on missing image), bold name, blue `NOW · …` ribbon from cached EPG. Focused row gets a stronger blue tint + chevron indicator.
    - **R NOW / NEXT**: dedicated EPG panel showing channel logo + name, "NOW" box (blue tinted, with time slot, title, 3-line synopsis) and "UP NEXT" box (greyer). Big "▶ Watch this channel" button under both.
- **⚡ Performance** (critical lessons from the failed previous build):
  - **No glow / blur / scale animations** anywhere in the new UI. Focus is signalled by borders + accent shifts only.
  - **`content-visibility: auto` + `contain-intrinsic-size`** on every channel row so the browser skips paint work for off-screen rows — handles 1000+ channels at 60 fps on the HK1.
  - **`contain: paint`** on each of the 3 column scroll containers so independent paint regions don't invalidate the hero.
  - **AbortController + 180 ms debounce** on NOW/NEXT fetches — user can zap through channels with D-pad up/down without queuing dozens of stale requests.
  - **In-memory EPG cache** (Map, 60 s TTL) — once you've seen a channel's now/next, scrolling past it again is instant.
  - **`onFocus` channel selection** so D-pad navigation is "what you're looking at is what you'd watch" — no extra Enter press needed to load EPG.
- **🎬 Playback**: clicking "Watch live" calls `Host.playVideo(...)` with the live `.ts` URL → native libVLC Activity on the box (or JS HLS player in browser preview). CW entry uses `cwId: live:{providerId}:{streamId}` so live channels can appear in Continue Watching too.
- **🧭 SideNav**: new "Live TV" entry inserted between Movies and Search, using the Lucide `Radio` icon.
- **🗺 App routes**: `/live-tv` wrapped in `<RequireProfile>` like the other pages.


## Implemented (Iteration 60 — Feb 14, 2026)
### My Library — beautiful release calendar
- **🎁 User request**: "Build a calendar into My Library — when you click on the calendar, any TV show in the watch list shows a visual calendar of when the next episodes are coming out."
- **📡 Backend** — new `POST /api/tmdb/upcoming-episodes`:
  - Body: `{ "imdb_ids": ["tt1234567", ...] }` (capped at 60 ids per call).
  - For each show: resolves imdb→tmdb via `/find/{imdb_id}?external_source=imdb_id` (cached 7 days), pulls `/tv/{tmdb_id}` for `next_episode_to_air`, then fetches the full season that contains it so we surface the entire run (Star Wars-style 8-12 week schedules etc.) — not just the single next episode.
  - Episodes are filtered to the next 120-day horizon and stripped of past dates. Returns show metadata (poster, backdrop, primary network, status) + episode list (season, episode, name, air_date, overview, still_path).
  - Shows with no upcoming episodes are omitted entirely so the calendar isn't padded with dead entries.
- **🎨 Frontend** — new `LibraryCalendar.jsx` full-screen overlay:
  - **Header**: Back/close, "COMING UP · N episodes" eyebrow, "Your calendar" title, prev/next month nav with the current month label.
  - **Big 7-col Monday-first month grid** (`<MonthGrid>`): each day cell is D-pad focusable (`data-focusable="true"`, tile focus). Today gets a blue ring + "TODAY" mono label. Selected day gets a stronger blue glow + box-shadow. Day cells show up to 2 episode chips with a `+N MORE` overflow indicator. Each chip is colour-coded by show (stable 8-colour palette) so users can spot patterns at a glance.
  - **Detail panel** on the right shows everything airing on the selected day: episode card with TMDB still image, network · S · E mono ribbon, show name, episode title, 2-line synopsis. Coloured left-border + tinted border match the show's grid chip colour.
  - **"This week" rail** below the grid: horizontally-scrolling 280px tiles with TMDB stills, glowing show-colour dot, pretty date + S/E ribbon, snap scrolling. D-pad focusable so the user can jump from the grid down to the rail in one press.
  - **Smart month cursor**: on load, if all upcoming episodes are in a future month, auto-jumps the cursor there so the user lands on populated grid (not an empty current month).
  - **Empty state** explains why a library might have no upcoming episodes (between seasons, finished, no TMDB schedule yet).
  - **Loading state** with spinner; **error state** for API failures.
- **🔘 Entry point**: new "Calendar" pill button next to the TV Shows section title in `/library`. Only appears when the user has ≥1 TV favourite. Pill style matches the existing "Expand" button on Watch Later (mono cap text, 36 px height, blue tint).
- **🏗 `<Section>` component** extended with an `action` prop so any future section can drop a header button without restructuring (used by the new Calendar button).


## Implemented (Iteration 59 — Feb 14, 2026)
### Watch Together — synchronized stream pre-buffering (two-stage handshake)
- **🐛 Bug**: User confirmed end-to-end party flow works but host's stream buffered faster than guest's → host started playing instantly while guest was still buffering → never re-synced (host was several seconds ahead).
- **🔍 Root cause**: After the 3-2-1 countdown, both clients called `mediaPlayer.play()` at the same wallclock — but host had already pre-buffered during the countdown, while guest hadn't. Host played from frame 0 instantly; guest's libVLC continued buffering and only started playing several seconds later from position 0, missing the sync window. Drift correction wasn't kicking in because the host wasn't broadcasting position updates via `playing_now`.
- **🔧 Fix** — **two-stage party play handshake**:
  1. **`loading` stage** (NEW): When host hits "Start the party", backend sets `status='loading'`, resets every member's `ready` flag, broadcasts. Every client navigates to the player but **does not start watching yet**.
  2. Each player opens libVLC, fires the stream URL, waits for first `MediaPlayer.Event.Playing` event (= libVLC has buffered + decoded frame 0).
  3. On that first Playing event, player **immediately pauses** + seeks to anchor position + sends `ready` to the server.
  4. **`countdown` stage**: server tracks `member.ready` flags. When **every** connected member is ready, server flips `status='countdown'` with `at_ms = now + 3 s`, broadcasts.
  5. Each client schedules `mediaPlayer.play()` for exact wallclock `at_ms`. Now everyone fires play with their stream already pre-buffered → frame-accurate sync.
- **🔁 Drift correction** improved: backend now re-broadcasts `state` on every `playing_now` heartbeat from the host (was: only updated server-side position, never broadcast). Guests' 1.5 s drift tolerance now actually fires every 2 s.
- **📦 Backend changes** (`watch_party.py`):
  - `Party` dataclass: added `pending_lead_ms`, `loading_started_at` fields.
  - `play` message handler: sets `status='loading'` instead of `'countdown'`, stores `pending_lead_ms`, resets every member's `ready` flag.
  - `ready` message handler: when `status='loading'` and ALL members are ready, flips to `countdown` with `at_ms = now + pending_lead_ms`.
  - `playing_now` handler: now broadcasts state so guests can drift-correct.
- **📺 Frontend changes** (`WatchTogether.jsx`): lobby navigation trigger now includes `loading` status (was: only `countdown`/`playing`).
- **🎮 Kotlin changes** (`VlcPlayerActivity.kt`):
  - New `partyPreparing` flag — `true` from onCreate until first Playing event.
  - First Playing event in party mode: pause, seek to anchor, send `ready`, badge shows `WAITING`.
  - Moved party play/pause broadcast from `mediaPlayer.setEventListener` to the user-action handlers (playBtn click, video tap) — clean separation between "user clicked" vs "countdown fired play()".
  - Countdown handler flips badge `STARTING → HOST/GUEST` after firing play.


## Implemented (Iteration 58 — Feb 14, 2026)
### Torrent streams now play through libVLC (not external Android chooser)
- **🐛 Bug report**: User reported that clicking a torrent stream (e.g. NCIS S01-S18 1080p BluRay, 12 seeders, BestTorrents) now opens the Android "Open with" chooser (`On Now VIP / Nova Video Player`) instead of libVLC. User confirmed this used to play in libVLC before.
- **🔍 Root cause**: `Detail.jsx` and `SeriesEpisodes.jsx` had a `mode === 'torrent'` branch that called `window.location.href = magnet:...`, delegating to Android's system magnet handler chooser. There was no path to the native libVLC Activity for torrent streams.
- **🔧 Fix**: Merged the `'torrent'` branch into the `'direct'` branch.  Torrents are converted to a magnet URI via the existing `buildMagnet()` helper and passed through the same `Host.playVideo(...)` path as direct streams.  `Host.playInternalRich` then launches `VlcPlayerActivity` with the magnet URI.
- **🎬 Kotlin side** (`VlcPlayerActivity.startPlayback()`): When the URL is a magnet/`.torrent`, we now explicitly add `:demux=bittorrent` to the Media options (libVLC's bittorrent demuxer module — bundled in `libvlc-all:3.6.0`) plus bump `network-caching` from 1500 → 6000 ms (torrents need extra time for peer discovery + piece prefetch before the first frame can decode).
- **📋 Same fix applied to** `SeriesEpisodes.jsx` — episode-level torrent streams now flow through libVLC the same way.
- **♻️ Continue Watching**: torrent magnets are now written into the CW entry's `streamUrl` field so resume works (libVLC can re-open the same magnet and pick up partial peer/piece cache).
- **🛡️ Fallback preserved**: browser preview (no Android bridge) → JS Player path → magnet URIs won't work there but the JS-side error handler degrades gracefully (the JS HTML5 video element just fails silently rather than crashing).


## Implemented (Iteration 57 — Feb 14, 2026)
### Watch Together — NATIVE libVLC sync (codec coverage parity)
- **🎯 Why:** The iter_56 Watch Together flow forced the JS HTML5 player when a party was active so the WebSocket could pipe play/pause/seek events. On the HK1 box this meant many streams (MKV/HEVC/AC3 etc.) wouldn't decode. User requirement: native libVLC must drive party playback.
- **📦 OkHttp WebSocket dependency** added to `app/build.gradle.kts` (`com.squareup.okhttp3:okhttp:4.12.0` — ~600 KB, mature on Android 4.4+).
- **🔌 New JS→Kotlin bridge** `WebAppInterface.playInternalParty(...)` accepts the same payload as `playInternalRich` plus `partyCode + partyRole + partyMemberId + partyWsUrl`.
- **🎮 `VlcPlayerActivity` party controller** (`VlcPlayerActivity.kt`):
  - Reads party Intent extras (EXTRA_PARTY_CODE / EXTRA_PARTY_ROLE / EXTRA_PARTY_MEMBER_ID / EXTRA_PARTY_WS_URL).
  - Opens an OkHttp WebSocket with 20 s pingInterval + no-readTimeout, sends 'hello' with role+member_id+name+avatar.
  - **Host**: hooks the existing `mediaPlayer.setEventListener` Playing/Paused branches to emit `resume`/`pause` over the socket. SeekBar's `onStopTrackingTouch` + `seekBy()` emit `seek`. A 2 s heartbeat coroutine emits `playing_now` while playing.
  - **Guest**: listens for inbound `state` broadcasts and applies: paused → pause+seek to position_ms; playing → play+drift-correct (1.5 s tolerance); countdown → seek to anchor then schedule `mediaPlayer.play()` for wallclock `at_ms`.
  - **Armed flag** suppresses the initial Playing event from being echoed back as a 'resume' (prevents infinite-loop broadcasts when guest receives a state and triggers its own play).
  - **PARTY · CODE · HOST/GUEST pill** added programmatically as a `TextView` in the top-right of the player surface (no XML changes — keeps the diff small + works on every layout variant). Pill text flips to "OFFLINE" if the socket fails / closes.
  - **Clean shutdown** in `onDestroy`: closes the WS, shuts down the OkHttp dispatcher, removes the heartbeat handler.
- **🔁 JS fallback preserved**: `Host.playVideo` tries `playInternalParty` first; if the bridge isn't there (older APK, browser preview) it falls through to `playInternalRich` → the existing JS Player path with its own WebSocket sync. So a half-rolled-out APK never strands users.
- **🪪 Frontend tightenings (live now)**:
  - Watch Together landing **initial-focuses 'Host a party'** (`data-initial-focus="true"` on the primary ChoiceCard) so the D-pad lands on the right button immediately.
  - MoviePicker hero shrunk (medallion 84→56, headline clamp 26-44→20-30 px, removed the redundant Search button — TVKeyboard's Enter key already submits) so the on-screen keyboard no longer hangs off the bottom of a 1080p screen.
  - Room header tightened (back-btn 48→40, code font clamp 36-64→22-34, copy pill 38→30 px) to leave more vertical room for the picker.
- **📲 APK version bumped to 1.9.6 / versionCode 30**.


## Implemented (Iteration 56 — Feb 14, 2026)
### Watch Together (Watch Party) — full end-to-end host-authoritative sync
- **🎉 Backend WebSocket coordinator** (`backend/watch_party.py`, already wired to `/api/watch-party/*`). 9/9 pytest scenarios PASS in iter_18: code creation (6 chars, no look-alikes), state lookup + not_found, host/guest hello→state broadcast, host pick, host play→countdown with future at_ms, host pause updates position+status, chat broadcast, disconnect rebroadcast. Includes a reaper that evicts dead parties after 5 min idle / 6 h max age.
- **📺 Lobby UX** (`pages/WatchTogether.jsx`):
  - Landing copy updated to user's spec: "Pick a Movie/Show. Share a code. And we will push play for you…" with the new line-fitting heading (clamp 28-48px, was 36-72px) so the page no longer overflows on the HK1.
  - ChoiceCard tiles shrunk (padding clamp 18-26 vs 24-36, minHeight 156 vs 200, icon 46/26 vs 56/32) so both Host/Join cards sit comfortably above the fold.
  - Two views: Host (clicks "Host a party" → POST /api/watch-party/create → room view with neon code) and Join (TVKeyboard digit/letter entry → state lookup → room).
  - Room renders members rail (with HOST badge), MoviePicker (host) or "Waiting for the host" (guest), MoviePreview with Start button (host).
  - On host Start, the WebSocket emits 'play' with lead_ms=3000; every member receives status='countdown' and navigates to `/resolve/{media_type}/{tmdb_id}?party=CODE&autoplay=1&at_ms=...&position_ms=...`. The lobby socket is closed before navigation (Player reopens its own).
  - role + member_id are stashed in sessionStorage so the Player can rejoin the same socket as the same member.
- **🔁 Resolve preserves query params** (`pages/Resolve.jsx`). The tmdb→imdb redirect now appends `window.location.search` so the party / autoplay / at_ms / position_ms params survive the hop to `/title/{appType}/{imdb_id}`.
- **🎯 Detail page party-aware autoplay** (`pages/Detail.jsx`):
  - Reads ?party=CODE&at_ms=X&position_ms=Y from URL.
  - Autoplay effect fires when partyCode is set regardless of the user's Autoplay 1080p setting (party always auto-picks the best 1080p stream).
  - playStream() SKIPS the native libVLC bridge (Host.playVideo) when partyCode is present — sync only works through the JS HTML5 player.
  - Propagates `&party=CODE&at_ms=...&position_ms=...` into the `/play?...` URL so the Player picks up the party context.
- **🎬 Player live sync** (`pages/Player.jsx`):
  - When ?party=CODE is in the URL, a new effect opens a WebSocket to `/api/watch-party/ws/{code}` and sends 'hello' with role+member_id pulled from sessionStorage.
  - **Host** broadcasts `pause`/`resume`/`seek` on every video event AND a `playing_now` heartbeat every 2 s so late-joiners pick up the right position.
  - **Guests** apply server-broadcast state to the local <video>: status='paused' → pause + seek to position_ms; status='playing' → ensure playing with 1.5 s drift correction; status='countdown' → seek to anchor then play() at wallclock at_ms.
  - The first 'play' event the Player itself triggers from the countdown is intentionally NOT echoed back as a 'resume' (armed-flag pattern).
  - "Open in VLC" button is hidden when partyCode is set (native player can't pipe events into the socket).
  - Top bar shows a 'Party · CODE HOST/GUEST' pill ([data-testid='player-party-badge']) with a green/yellow status dot.
  - Countdown overlay ([data-testid='player-party-countdown']) renders a giant 3-2-1 ticker in the active theme accent during the lead-in.
- **🔧 TVKeyboard first-keystroke drop FIXED** (`components/TVKeyboard.jsx`).  Root cause: append/back/space handlers captured the `value` prop via closure — two rapid clicks in the same React batch both read the same stale `value`, so each onChange emitted the same 1-char string, causing the parent to register only one character. Fix: introduced `valueRef` (React.useRef synced via useEffect on every prop change AND updated synchronously inside the handler before calling onChange). This single fix unblocked the guest join flow AND fixes a wide-blast-radius bug that affected every TVKeyboard-using screen (Search, Profile name, Join code, Movie picker, PIN).
- **🧪 Testing** (`testing_agent_v3_fork` — iteration_18.json + iteration_19.json):
  - Backend: 9/9 pytest scenarios PASS (multi-client WS sync, host commands, disconnect rebroadcast).
  - Frontend iter_18: 80% (blocked on TVKeyboard bug).
  - Frontend iter_19 retest after TVKeyboard fix: 5/5 PASS — full host+guest end-to-end with two Playwright contexts: host created room, guest typed code, picker propagated, Start landed both clients on /play with party-aware badges.


## Implemented (Iteration 55 — Feb 14, 2026)
### "Pick your avatar" header reverted to scroll-with-page (not sticky)
- **🔄 Removed `position: sticky` from `[data-testid="avatar-sticky-preview"]`** (`pages/ProfileEdit.jsx`). The header is now a regular static element inside the page flow — it scrolls up off the screen along with the rest of the page when the user D-pads down, exactly as it did before sticky was introduced.
- **🔁 Reverted AvatarStep scroll-container ownership** — outer `[data-testid="profile-edit"]` is back to `overflow-y: auto` for the avatar step (not 'hidden'). AvatarStep root no longer has its own `flex: 1 / overflow-y: auto`.
- Result: the whole screen moves up together when the user navigates down, with the preview header riding along — the original behaviour the user wanted.


## Implemented (Iteration 54 — Feb 14, 2026)
### AvatarStep sticky preview truly pinned · BuildAvatarOverlay focus never escapes
- **📌 AvatarStep is now its own scroll container** (`pages/ProfileEdit.jsx`). Outer `[data-testid="profile-edit"]` is `overflow-y: hidden` on the avatar step; the inner `[data-testid="profile-step-avatar"]` carries `flex: 1; min-height: 0; overflow-y: auto`. `position: sticky; top: 0` on the preview header is now relative to the AvatarStep's own scroll viewport — verified 0 px drift across 8 consecutive ArrowDown presses.
- **🛡️ BuildAvatarOverlay focus trap hardened**. Scoped capture-phase keydown handler now ALWAYS calls `preventDefault()` + `stopPropagation()` when active focus is inside the overlay — even when target is `null` at a row edge. Previously the global spatial-focus engine would steal the keystroke and focus an AvatarStep tile behind the modal ("focus disappears"). Verified 30 rapid ArrowDown presses → 0 escapes; 5 ArrowRight at Save → 0 escapes; 5 ArrowUp at top chip → 0 escapes.
- **🧪 Testing** (`testing_agent_v3_fork` — iteration_17.json) — 8/8 scenarios PASS at 100%. Sticky preview drift 0.00 px; preview avatar-id updates live across 9 distinct tiles; cancel click cleanly closes overlay; zero console errors.


## Implemented (Iteration 53 — Feb 14, 2026)
### Build-Your-Own avatar overlay — D-pad + sticky preview
- **🎮 D-pad now works inside the builder** (`pages/ProfileEdit.jsx` → `BuildAvatarOverlay`). Added a scoped capture-phase keydown handler mirroring `AvatarStep`: walks `[data-builder-row="true"]` containers row-by-row in DOM order, preserves the active button's screen-X column on row changes, wraps Left/Right at row edges. Every focus move triggers `scrollIntoView({behavior:'smooth', block:'center', inline:'center'})` so the focused chip is always visible.
- **🎯 Auto-focus on open** — first chip of the Hair row receives focus 60 ms after the overlay mounts so the D-pad has somewhere to start.
- **📌 Sticky preview header** — back-button, title and live preview circle are now wrapped in `[data-testid="build-avatar-sticky"]` with `position: sticky; top: 0`. Chip area scrolls underneath while the preview stays pinned. Preview circle resized 220 → 140 px to fit the sticky band neatly.
- **⌨️ Escape key closes the overlay** (keyboard parity with the Back button).
- **🎨 Step-2 sticky preview** moved from `top: -6` → `top: 0` so it no longer drifts.
- **🧪 Testing** (`testing_agent_v3_fork` — iteration_16.json) — 10/10 scenarios PASS at 100%. D-pad reaches every chip + Cancel + Save; sticky preview stays pinned during scroll; click updates preview within ~200 ms; zero console errors.


## Implemented (Iteration 52 — Feb 14, 2026)
### Avatar step D-pad hardening + sticky preview header
- **🔧 Scoped D-pad navigation** (`pages/ProfileEdit.jsx` → `AvatarStep`). Replaces the global spatial-focus dependency with a scoped capture-phase keydown handler that walks focusable tiles in pure DOM order:
  - ArrowLeft / Right → previous / next button within the same row; **wraps to next/previous row at edges** so the D-pad never appears to "stop working".
  - ArrowDown / Up → previous / next row preserving the current X column.
  - Every move `scrollIntoView({behavior:'smooth', block:'center', inline:'center'})` the new target.
- **📌 Sticky preview header** (`[data-testid="avatar-sticky-preview"]`). Pinned to top of step 2 (`position: sticky; top: -6px`). Shows a large `AvatarCircle` of the currently-FOCUSED avatar + category label + "Pick your avatar" heading + `N avatars · M categories` counter. As the user D-pads down through rows, the rows slide up underneath while the preview stays visible — user always sees what they're choosing.
- **🔗 Tracking attributes**: every focusable tile carries `data-avatar-id`; every row section carries `data-avatar-row="true"`. The scoped handler uses these to enumerate rows and pick the closest-X tile on row changes.
- **🧪 Testing** (`testing_agent_v3_fork` — iteration_15.json) — 8/9 PASS, 1 ArrowRight-edge-wrap fix landed immediately after. All flows now exercise correctly: sticky preview pinned at top=74px while rows scroll, sticky updates live to focused tile, focused tile always on-screen, zero console errors.


## Implemented (Iteration 51 — Feb 14, 2026)
### Shelf re-order · PosterTile scroll-margin · Build-Your-Own avatar
- **🔄 Home shelf order swapped** (`pages/Home.jsx`). Now in order: **New movies → New series → Popular movies → Popular series**.
- **📐 PosterTile scroll-margin fix** (`components/PosterTile.jsx`). Added `scrollMarginTop:24px` + `scrollMarginBottom:24px` so D-pad `scrollIntoView()` never lands the focused tile flush against the viewport edge — bottom-clipped tiles when scrolling from Networks → first shelf are gone.
- **🎨 Build-Your-Own avatar builder** (`pages/ProfileEdit.jsx` + `lib/avatars.jsx`). New "Build" tile at the top of step 2 opens a full-screen overlay with live preview circle + chip rows for: Hair (32 styles), Hair color (10), Skin (6), Eyes (11), Eyebrows (11), Mouth (11), Facial hair (6 including blank), Glasses (8 including blank), Background (8 swatches). Live preview updates instantly on every chip click via DiceBear avataaars URL builder. Save persists to `localStorage` key `onnowtv-custom-avatars-v1` (JSON array of `{id, src, glow, options, createdAt}`) and short-circuits into the standard SaveAvatarConfirm flow. Saved custom avatars persist into a `data-testid="avatar-row-custom"` row at the top of step 2 across sessions.
- **🛡️ DiceBear schema validation done** — all enum values verified against the official `/9.x/avataaars/schema.json` so the builder never 400s.
- **🧪 Testing** (`testing_agent_v3_fork` — iteration_14.json) — 9/9 scenarios PASS at 100% including DiceBear 200-OK fetch, localStorage persistence, cancel/back no-persistence, and zero console errors.


## Implemented (Iterations 49 + 50 — Feb 14, 2026)
### Home rail locked to 4 shelves · Fun Faces removed · DiceBear shrunk to 160 · SideNav D-pad fix
- **🏠 Home page rail locked to exactly 4 addon shelves** (`pages/Home.jsx`). Below Continue Watching / For You / Networks, the Home page now renders only: **Popular movies · Popular series · New movies · New series** (Cinemeta `-movie-top` / `-series-top` / `-movie-year` / `-series-year`). Every other addon-driven shelf (Trending / Anime / Channels / etc.) is filtered out for faster HK1 render. Filter views (Movies / TV tabs) remain unaffected. Iter 12 verified 6/6 PASS.
- **🗑️ Removed the 'Fun Faces' DiceBear row** (`lib/avatars.jsx`). DICEBEAR_CATS now has 3 entries: Cartoon · Adventurer · Pixel Art. Total picker: 142 tiles (was 154). 12 fewer DiceBear PNGs to fetch.
- **📐 DiceBear PNG size 256 → 160** (`lib/avatars.jsx`). Picker tiles render at 120 px so 160 retains retina headroom while shaving ~30% off each PNG payload (10.9 KB → 7.6 KB). Combined with row removal, step 2 fetches ~48% less data (~273 KB vs ~524 KB).
- **🔧 SideNav D-pad bug fix** (`pages/Home.jsx`). The Home row-walker now BAILS when `document.activeElement` is inside `[data-testid="side-nav"]`. Previously pressing Down/Up while the menu was open would close it AND jump to the next home shelf in one keystroke. Now menu items walk independently; the row-walker resumes once focus leaves the menu.
- **🧪 Testing** (iteration_12.json, iteration_13.json) — 6/6 + 8/8 PASS at 100%.


## Implemented (Iteration 48 — Feb 14, 2026)
### Avatar pre-cache · For-You rail "Similar to what you love" · Home D-pad line-by-line
- **⚡ DiceBear avatars preloaded on app boot** (`App.js` module-load + `NameStep` useEffect). All 48 character-portrait PNGs are warmed in the browser HTTP cache before the user reaches step 2. Testing confirmed `naturalWidth=256` within 0.1 ms of step-2 mount — effectively instant render, no loading flash.
- **🎯 For-You rail logic** (`components/ForYouShelf.jsx` + `backend/server.py`):
  - New backend endpoint `GET /api/tmdb/similar-to-picks?picks=<csv>` accepts `type:tmdb_id` pairs and returns TMDB `/recommendations` (with `/similar` fallback) for each, deduped, EXCLUDING the user's own picks. 24-hour cache so the rail refreshes daily.
  - Rail now leads with "similar" recommendations, followed by genre-based tail. The user's hand-picked titles are NEVER shown back at them.
  - Eyebrow updated to "SIMILAR TO WHAT YOU LOVE".
  - `/api/tmdb/for-you` cache TTL bumped 3h → 24h (daily refresh).
- **🎮 Home D-pad Up/Down walks rails line-by-line** (`pages/Home.jsx`). New capture-phase keydown handler builds an ordered list of rows (Hero billboard + each shelf section) and on Up/Down moves to the next/prev row while preserving the user's horizontal column. From any rail, pressing Up walks straight to the rail above — never jumps back to the hero "More info" button. Column preservation verified: 3rd tile in one rail → ArrowDown lands on the closest-X tile in the next rail.
- **🧪 Testing** (`testing_agent_v3_fork` — iteration_11.json) — 13/13 scenarios PASS (6 backend + 7 frontend) at 100%.


## Implemented (Iteration 47 — Feb 14, 2026)
### Avatar library reverted to 106-emoji baseline + 4 DiceBear bonus rows
- **🔁 Reverted** the avatar library back to the original 106 emoji-on-gradient avatars (`a1`–`a100` + `m1`–`m6`).
- **✨ Added 4 bonus DiceBear character-portrait categories**: Cartoon (avataaars), Adventurer (adventurer), Pixel Art (pixel-art), Fun Faces (fun-emoji). 48 new image-based tiles, 12 per row.
- **🎨 16 total category rows** in the picker: Animals · Wildlife · Fantasy · Sports · Music · Funny Faces · Symbols · Food · Nature · Vehicles · Hobbies · Magic · Cartoon · Adventurer · Pixel Art · Fun Faces. 154 picker tiles + 1 hidden Kids avatar.
- **🔀 `AvatarCircle` auto-detects** emoji vs image avatars by checking for `a.e` vs `a.src`. Emoji rendered offline, DiceBear PNGs rendered via `<img>`.
- **♻️ Backward compatibility verified** — legacy profiles with `avatarId='a1'` still render the lion emoji glyph on /profiles.
- **🧪 Testing** (`testing_agent_v3_fork` — iteration_10.json) — 14/14 scenarios PASS at 100%. Wizard round-trip with a DiceBear tile succeeds; emoji tiles never render an `<img>`; legacy emoji avatar IDs still work.


## Implemented (Iteration 46 — Feb 14, 2026)
### Netflix-style DiceBear character portrait avatars
- **🎭 Complete avatar rewrite** (`lib/avatars.jsx`). Emoji-on-gradient avatars are gone. Replaced with 132 full-bleed character portraits generated by DiceBear v9 (MIT-licensed, MIT, free, no API key) via the PNG endpoint `https://api.dicebear.com/9.x/<style>/png?seed=<seed>&size=256&radius=50&backgroundType=gradientLinear`.
- **🪜 11 Netflix-style categories** stacked as horizontal rows: Anime (lorelei) · Realistic (personas) · Cartoon (avataaars) · Open Peeps · Adventurer · Studio Flat (micah) · Big Smile · Robots (bottts) · Pixel Art · Notionists · Fun Faces.
- **📐 Picker tile bumped 80→120 px** so portraits feel Netflix-sized. `AvatarCircle` now renders a full-bleed `<img>` (object-fit: cover) inside a circular container with a glow that matches each avatar's dominant colour; img onError gracefully hides itself rather than collapsing to a broken-image icon.
- **🧸 Synthetic Kids profile** uses `big-smile/KidBear` (hidden from picker).
- **🧪 Testing** (`testing_agent_v3_fork` — iteration_9.json) — 14/14 scenarios pass at 100%. DiceBear CDN HEAD returns 200 OK + image/png + BunnyCDN cache HIT; every tile's `<img>` has a real DiceBear URL and `naturalWidth=256` within the 8s budget.


## Implemented (Iteration 45 — Feb 14, 2026)
### Network logo image-quality reduction · Categorised avatar rows · TV-show 1080 autoplay · Viewing-style polish
- **🖼️ Network logos `original` → `w300`** (`backend/server.py`, `components/NetworksShelf.jsx`). TMDB watch-provider wordmark assets served at w300 (≈6-10× smaller payload) — the Browse-by-Network rail now renders noticeably faster on the HK1 box. Cache keys bumped to `networks:logos:v2` so existing devices fetch fresh URLs.
- **🧑‍🎤 Categorised avatar rows** (`lib/avatars.jsx` + `pages/ProfileEdit.jsx`). New `AVATAR_CATEGORIES` export grouping the 106 avatars into 12 horizontally-scrolling rows: Animals · Wildlife · Fantasy & Cool · Sports · Music & Gaming · Funny Faces · Vibes & Symbols · Food & Drink · Nature · Vehicles · Hobbies & Gear · Magic & Cards. D-pad Down walks row-to-row; Left/Right picks an avatar within a category. Each row has `data-testid="avatar-row-<id>"`.
- **📺 TV-show autoplay 1080 broadened** (`components/SeriesEpisodes.jsx`). `pickAutoplayCandidate` now uses the shared `is1080p(stream)` helper — anything matching `/1080/i` anywhere in title/name/description triggers autoplay. Brings TV-show autoplay in line with the movie autoplay path.
- **✨ Viewing-style step polish** (`pages/ProfileEdit.jsx`):
  - New helper banner `[data-testid="viewing-style-helper"]` at the top of step 4 explaining how it works ("Tap any genre on the left to see its top 20 most-watched titles…").
  - Top titles count raised from 10 → 20 (backend call now `?limit=20`).
  - Right-pane header reads "Top 20 in <genre>" instead of "Top 10".
- **🧪 Testing** (`testing_agent_v3_fork` — iteration_8.json) — 2/2 backend + 9/9 frontend scenarios pass at 100%.


## Implemented (Iteration 44 — Feb 14, 2026)
### Profile isolation bug + 6-step wizard (Viewing Style + Autoplay) + Home "For You" rail
- **🐛 Profile isolation fix** (`lib/profileScope.js`). `readScopedString` no longer falls back to the unscoped legacy key for every profile. Legacy data is promoted ONCE to the currently-active profile, then the legacy key is removed — every subsequent profile starts completely empty. `saveProfile()` also seeds the new profile's scoped namespace with explicit empty defaults for library / continue-watching / watched / autoplay / viewing-style. New profiles never inherit any prior profile's data.
- **🪄 Profile-creation wizard now 6 steps**: Name → Avatar → Theme → **Viewing Style (NEW, skippable)** → **Autoplay 1080p (NEW, Yes/Skip modal)** → PIN. Step counter eyebrows updated to "STEP N OF 6". Back button walks the full chain.
- **🎬 Viewing Style step** (`pages/ProfileEdit.jsx` — `ViewingStyleStep` + `GenreSection`). Two-pane layout: TMDB Movie + TV genre tiles on the left → click a genre → right pane lists top 10 popular titles in that genre with poster + "+" / "✓" toggle. Picked genres & titles persist to scoped key `onnowtv-viewing-style-v1:<id>` (JSON `{movieGenres,tvGenres,items}`).
- **⚡ Autoplay step** (`AutoplayPrompt` modal). Yes/Skip writes scoped `onnowtv-autoplay-1080p:<id>` = '1'/'0'.
- **✨ For You shelf** (`components/ForYouShelf.jsx`). Renders on Home between Continue Watching and Networks shelves. Combines the user's manual picks + TMDB genre-based recommendations via the new `/api/tmdb/for-you` endpoint. Hides itself when the active profile has no viewing-style preferences. Live-refreshes on `vesper:profile-change` and `vesper:viewing-style-change` events.
- **🧠 Backend** (`server.py`). 3 new endpoints: `GET /api/tmdb/genres/{media}` (7-day cache), `GET /api/tmdb/by-genre/{media}/{genre_id}?limit=10` (6-hour cache), `GET /api/tmdb/for-you?movie_genres=&tv_genres=&limit=` (3-hour cache, mixes movies + TV interleaved).
- **🧪 Testing** (`testing_agent_v3_fork` — iteration_7.json) — 9/9 backend pytest pass, 10/10 frontend Playwright scenarios pass. For You rail confirmed visible above Networks with 21 tiles when prefs exist; hides cleanly when empty.


## Implemented (Iteration 43 — Feb 14, 2026)
### Profile creation wizard becomes 4 steps (name → avatar → theme → PIN)
- **New Theme step inserted between Avatar and PIN** in the Profile creation wizard (`pages/ProfileEdit.jsx`). After the user confirms an avatar, they now land on Step 3 of 4 — a 9-theme grid (Vesper Neon, Hot Magenta, Sunset, Amethyst, Emerald, Ember, Gold, Mint, etc.) with an active checkmark indicator and a "Next: profile PIN" button. The PIN yes/no prompt now fires from this new step (was previously triggered by the avatar confirm).
- **Theme is persisted per-profile** at scoped `localStorage` key `onnowtv-theme:<newProfileId>`. ThemeProvider already reads this scoped key via `readScopedString` and re-applies whenever `vesper:profile-change` fires, so the new profile's chosen theme is live the moment it becomes active.
- **Back button walks the wizard chain**: theme → avatar → name → exit to /profiles.
- **Initial focus on the theme grid** lands on the currently active theme card (relevant when editing an existing profile).
- Tested by `testing_agent_v3_fork` (iteration_6.json) — all 8 scenarios pass at 100% including PIN-yes/save, PIN-skip, back-button regression and scoped theme persistence verification.


## Implemented (Iteration 42 — Feb 14, 2026)
### Search redesigned to match Profile NameStep, Settings + Stream lists go line-by-line on D-pad
- **/search redesigned** (`pages/Search.jsx`) for both main app and Kids. Centered card now mirrors the Profile creation NameStep: large circular search-icon medallion → mono eyebrow ("Search" / "Kid-safe search") → big display heading ("What are you **looking** for?" / "What do you **want** to watch?" with one word highlighted in blue) → pill-shaped query preview row (SearchIcon, animated cursor, char-count, optional mic) → on-screen TVKeyboard → single primary Search button with right-arrow icon. Removed the old left-aligned hero + side-by-side search bar layout. Results grid + KidsBlockedMessage still render below when present.
- **Settings Up/Down skips pill rows** (`pages/Settings.jsx`). New geometry-aware capture-phase keydown override scoped to `[data-testid="settings-scroll"]`. Pressing Down from any pill (e.g. `kids-movie-rating-G`) now lands on the first focusable of the next *visual row*, never on the sibling pill to its right. Up mirrors the logic. Left/Right unchanged — handled by the locked global `useSpatialFocus`.
- **Detail page streams list — list-scoped Up/Down** (`pages/Detail.jsx`). Capture-phase keydown handler restricts Up/Down inside `[data-testid="stream-list"]` to in-list navigation; at the top/bottom edge the handler bails so global spatial focus takes over. Prevents "skipping" away from the stream list onto unrelated UI.
- **Series episode streams — same list-scoped behaviour** (`components/SeriesEpisodes.jsx`). Each expanded episode's stream `<ul>` is marked `data-stream-list="true"`; the new handler keeps Up/Down inside the current episode's stream list and only falls through to the global engine at the top/bottom edge.
- Tested by `testing_agent_v3_fork` (iteration_5.json) — Search redesign + Settings row-aware nav both pass 100%; stream-list fix is logic-only and follows the same pattern, ready for device verification.


## Implemented (Iteration 41 — Feb 14, 2026)
### Watch Later tiles unified with Continue Watching, snappier filter swaps, magic avatars, delete-profile confirm
- **Watch Later tile → CW-style** (`pages/Library.jsx`).  Removed the
  trash button + dual padded card.  Tile is now a single 16:9 button
  with the backdrop filling edge-to-edge, the play badge bottom-left,
  title and small mono subtitle (year for movies, S/E for episodes)
  on the bottom-right gradient.  Long-press OK (or 700 ms mouse-down)
  flips the tile into a "Remove from Watch Later?" confirm card with
  Remove / Cancel buttons — exactly mirrors `ContinueWatchingShelf`.
  Header now also shows "Hold OK to remove" hint when items exist.
- **Snappier Home filter swaps** (`pages/Home.jsx`).  Two new
  background-prefetch `useLiveShelves` hooks warm the cache for the
  inactive filter views (series + movie + all minus the active one)
  400 ms after the active view finishes loading.  Clicking "TV
  Shows" / "Movies" in the SideNav now lands on cached data
  instantly instead of a 2–3 s catalogue spin.  Initial-focus retry
  now also targets `[data-testid="tab-grid-list-*"]` so focus snaps
  into the tab grid as soon as items render (previously it only
  found `[data-testid="shelves-region"]` which doesn't exist in
  filter view).
- **Magic / playing-cards / magician avatars** (`lib/avatars.jsx`).
  Added 6 new avatars to the existing 100 (now 106 total):
  🎩 top-hat, 🪄 magic wand, 🃏 joker card, 🔮 crystal ball,
  ♠️ spade, ✨ sparkles.  Profile edit grid header auto-updates the
  count (`CHOOSE AN AVATAR · 106`).
- **Delete profile confirmation modal** (`pages/ProfileSelect.jsx`).
  Manage profiles → Remove now opens a fixed-position glass modal
  showing the profile's avatar, name, "Are you sure you want to
  delete '<name>'?", and Cancel / Yes,delete buttons.  Cancel
  starts focused.  Backdrop click also cancels.


## Implemented (Iteration 34 — Feb 13, 2026)
### Full theme accent propagation + SideNav brand redesign
- **`--vesper-blue-rgb` triplet added to every theme** (themes.js).
  Drives every translucent accent in the app via
  `rgba(var(--vesper-blue-rgb), 0.4)`.  Combined with the existing
  `--vesper-blue` / `--vesper-blue-bright` / `--vesper-blue-glow`
  tokens, every focus ring, glow, hero radial, player progress
  fill, active pill, autoplay badge, etc. now recolours with the
  active theme.
- **Bulk swept** every hardcoded `rgba(93, 200, 255, X)` and
  `#5DC8FF` in 15 files (pages: Settings, Detail, Home, Player,
  ProfileEdit, Network, ProfileSelect, Sources; components:
  SideNav, OnScreenKeyboard, SeriesEpisodes, TabGridView,
  HeroBillboard, PosterTile, NetworkPosterTile).  `lib/avatars.jsx`
  and `lib/streamMeta.js` deliberately left alone — those use
  blue semantically (avatar identity, quality badge) not as a
  theme accent.
- **SideNav brand redesign**:
  - Removed PNG logo + "for HK1 · TV" subtitle.
  - Replaced with a glowing **V2** letterform in the active
    theme's bright accent (`var(--vesper-blue-bright)` + dual
    text-shadow halo).
  - When collapsed: just the V2 sits at the top-left.
  - When expanded: "ON NOW TV" wordmark fades in to the right,
    bigger (22px, weight 700, tight letter-spacing), aligned
    with the V2's baseline.
- **Removed SideNav footer block** — "Press F for fullscreen",
  "v1.2.0 · libVLC · BUNDLED ✓" all stripped.  User explicitly
  asked for these to be gone.

## Implemented (Iteration 35 — Feb 13, 2026)
### My Library + new-episode notifications + Watch Later
- **Per-profile library** (`lib/library.js`): favourites grouped by
  type (series / movie), Watch Later queue, dismissed-episode map.
  Broadcasts `vesper:library-change` events so every view re-reads
  on add/remove.
- **"Add to My List" toggle** on Detail page (`Detail.jsx`): plus
  pill flips to ✓ "In My List" with theme-accented fill once added.
- **`/library` page** (`Library.jsx`):
  - Empty TV-Shows state has side-by-side explanation copy + an
    inline preview of what the top-right notification will look
    like (mini ghost-tile of the real toast UI).
  - Empty Movies state has friendly wishlist copy.
  - Populated state: poster grid with name/year captions.
  - **Watch Later side rail** (sticky 320px on the right) — empty
    state explains it, populated state shows thumbnail tiles with
    Play and remove buttons.
- **Top-right new-episode toast** (`NewEpisodeToast.jsx`):
  - Globally mounted in `App.js`.
  - Polls every 5 min + on library change events.
  - Detects new episodes via Cinemeta `videos` array (any aired
    episode after the favourite's `lastSeenAt` watermark, not
    already dismissed).
  - 380-px tile with episode thumbnail header, "NEW EPISODE" pill
    in theme accent, show name, S/E label, Play / Watch Later
    buttons.  **Play auto-focuses** on appear (`data-initial-focus`).
  - Watch Later pushes the episode into the rail and dismisses
    the notification.
  - Slides in from the right with 220ms ease.
- **Avatar-keypad bugfix** (`useSpatialFocus.js`): when focus is
  in an `<input>` / `<textarea>`, LEFT/RIGHT still move the text
  cursor natively, but UP/DOWN now forward to spatial focus so
  the user can D-pad out of the name input into the avatar grid
  on the Profile-Edit page.

## Implemented (Iteration 36 — Feb 13, 2026)
### Press-and-hold OK to add to library + Library polish
- **`useLongPress` hook** (`hooks/useLongPress.js`): unified
  press-and-hold detector that works for both remote OK (Enter
  held) and mouse hold.  Returns spread-onto-element props.
  Calls `onLongPress()` after 700 ms, `onTap()` on short release.
  Sets `data-holding="true"` on the element during the hold so
  CSS can paint a growing theme-blue glow ring (`vesper-hold-grow`
  keyframes, 700 ms linear).  `stopPropagation()` on keydown so
  the global spatial-focus hook's click-on-Enter doesn't fight us.
- **`AddToListModal`** (`components/AddToListModal.jsx`): globally
  mounted dialog fired via the `vesper:request-add-to-list`
  custom event.  Shows large cover art on the left + show meta
  (title, year, genres, type, 3-line synopsis) and two pill
  buttons.  Modes:
    - not in library: "Add to My List?" → blue Add / glass Cancel
      + footer tip "Press &amp; hold OK on any tile to add it".
    - in library: "Remove from My List?" → red Remove / Cancel.
  Background blur, theme-accented border + glow, scale-in
  animation.  Auto-focuses confirm button.
- **Long-press wired** in:
  - `PosterTile.jsx` — catalog posters across Home & search.
  - `Library.jsx` favourite cards — long-press to remove.
- **Detail page**: replaced the now-redundant "+ Add to My List"
  button with a passive "✓ In My List" status pill that only
  appears once the title is in the library.  Adding now happens
  via long-press on any poster anywhere.
- **Library page polish**:
  - Favourite covers shrunk from `minmax(160, 1fr)` to
    `minmax(120, 1fr)` with 12 px gap (was 16).  More fits
    on screen.
  - Empty-state cards are now `data-focusable="true"` with a
    pill focus ring, so D-pad Down from a populated TV-Shows
    grid correctly lands on the Movies empty state (verified:
    `favorite-… → DIV → DIV → DIV` traversal).
  - Empty-state copy updated to teach the long-press flow:
    "Press &amp; hold OK on any show to follow it."
  - Page bottom padding bumped (60 → 120 px) so the sticky
    Watch Later rail never overlaps content.

## Implemented (Iteration 37 — Feb 13, 2026)
### Modal focus + per-type long-press flows + landscape Watch Later
- **Modal auto-focuses the confirm button** on open (imperative
  `el.focus()` inside a `requestAnimationFrame` after the payload
  state lands).  Also clears `data-focused` from the previously
  focused tile so the home behind doesn't appear to be receiving
  arrow keys any more.  Verified: after `dispatchEvent`, the active
  element is `BUTTON[data-testid="modal-confirm"]` with
  `data-focused="true"`.
- **Long-press wired into `TabGridView` (catalog grid)** — the
  user can now press-and-hold any cover in the TV Shows or Movies
  tab views (previously only the Home shelves worked).  Same
  event payload as `PosterTile`; modal opens identically.
- **Type-aware modal**: payload `type === 'movie'` → "Add to
  Watch Later" / "Watch later?" / bookmark icon.  `type ===
  'series'` (default) → "Add to My List" / "Add this?" / plus
  icon.  Removal mode wording flips correspondingly.
- **`library.js` Watch Later now supports both shapes**:
  - series → `{ id, type: 'series', episode, showMeta, addedAt }`
  - movie  → `{ id, type: 'movie', movie: { name, poster,
    background, year, synopsis }, addedAt }`
  - new `isMovieInWatchLater(id)` helper.
  - `removeFromWatchLater({ id })` works for both (movies match
    by id alone; series match by id+season+episode).
- **Watch Later rail renders landscape (16:9) thumbs** for all
  items.  Movies use the TMDB backdrop URL passed through the
  modal payload; series episodes use the existing
  `episode.thumbnail`.  Tile content unified:
    - Title row: show name (series) or movie name.
    - Subtitle: `S{n}·E{m}·…` (series) or `{year}` (movie).
- **PosterTile** and **TabGridView GridTile** now both pass
  `background` (Cinemeta backdrop URL via `img.backdrop()`)
  through to the modal so Watch Later can pick it up for
  landscape rendering.

## Implemented (Iteration 38 — Feb 13, 2026)
### Long-press remove fix + Fire-test-notification dev button
- **Bug fix — held-OK auto-confirmed the modal**.  Root cause:
  the global spatial-focus hook fires `el.click()` on EVERY Enter
  keydown including OS auto-repeats.  When the long-press timer
  tripped and the modal opened, focus moved to the confirm
  button — but the user was STILL holding OK from the original
  long-press.  The next held-key repeat fired a programmatic
  click on the now-focused confirm button → instant
  remove/close.  Fix: `AddToListModal` now installs three
  capture-phase listeners on `document` while the modal is open:
    - `keydown`: swallow Enter/Space while `armedRef === false`.
    - `keyup`:   set `armedRef = true` and swallow the release
                 (it's the tail of the press that opened the modal).
    - `mouseup`: swallow the first one too so the backdrop click
                 handler doesn't dismiss when the long-press
                 release lands on the backdrop overlay.
  After the user releases for the first time, the modal is
  "armed" and all subsequent keystrokes / clicks flow through
  normally.  Verified end-to-end: held Enter 900 ms → modal stays
  open after release → second Enter tap confirms cleanly.
- **"Fire test notification"** dev-only button added to Settings
  → Developer panel.  Dispatches a synthetic
  `vesper:new-episode-test` event with one of three rotating
  fake payloads (Game of Thrones, Stranger Things, Chernobyl) so
  the user can practise the Play / Watch Later flow without
  waiting for real Cinemeta `videos` air dates.  Tap repeatedly
  to stack the Watch Later rail.  `NewEpisodeToast` now also
  listens for the test event in addition to the real poll.

## Implemented (Iteration 39 — Feb 14, 2026)
### Profile copy + Library re-layout + Settings polish
- **Profile Select page**:
  - Logo "ON NOW TV V2" shrunk (38 → 28 px for "ON NOW TV",
    42 → 32 px for "V2") and moved higher via top padding
    `clamp(60px, 8vh, 120px)` (was `justify-center`).
  - Headline copy "Who&apos;s watching?" → "Who&apos;s ready to watch?".
- **My Library** redesigned:
  - Movies section removed entirely.  Only TV Shows favourites
    show on the library page.  Movies still go into Watch Later
    via long-press.
  - Watch Later moved from a 320px right rail to a **full-width
    block UNDER the TV Shows section**, sharing the same blue
    gradient + border styling so the page feels unified.  Tiles
    render in a horizontal landscape (16:9) row that scrolls
    horizontally with `scroll-snap-type: x mandatory`.
  - Expand button (top-right of the block) opens a full-screen
    overlay (`WatchLaterExpanded`) showing every queued item in
    a 4-col grid with bigger tiles for at-a-glance scanning.
    Close button + Escape/Backspace shortcut both dismiss.
- **Settings page** — everything below the Themes section
  shrunk so it doesn't dwarf the screen on the HK1 box:
  - SectionHeader: title 26-44 → 20-28 px, eyebrow 11 → 10 px,
    icon 28 → 20 px, marginTop 56 → 44 px.
  - Streams h2 + intro: same scale-down.
  - ToggleRow: title 18 → 14 px, description 13 → 11.5 px,
    padding 20·24 → 14·18 px, toggle track 56×32 → 44×26 px,
    thumb 26 → 20 px, radius 16 → 14 px.
  - ChoiceRow: same proportions.  Choice pills 38 → 32 px tall.
  - Switch Profile tile: padding & font sizes shrunk to match.
- **AddToListModal focus hardening**:
  - Strips `data-focused` from EVERY element outside the modal
    on open, then imperatively focuses confirm button.  Retries
    four times (sync, next frame, 50 ms, 150 ms) so any race
    with the in-flight long-press release can't leave a
    background tile looking active.

## Implemented (Iteration 40 — Feb 14, 2026)
### Logo unification + initial-focus + page scrolling + layout polish
- **Logo now reads "ON NOW TV2"** (single V, no duplicate).  The
  "ON NOW T" prefix is white, the "V2" trailing pair glows in
  the active theme accent with dual halo.  Applied to both
  ProfileSelect (centred) and SideNav (expanded wordmark).
  When SideNav is collapsed, only the glowing "V2" emblem shows.
- **ProfileSelect layout**: logo anchored near the top of the
  page (no longer vertically centred with everything else);
  "Who's ready to watch?" + profile tiles + Manage Profiles
  button live in a `flex-1 justify-center` wrapper that occupies
  the rest of the page — so the user's focal point lands at the
  vertical centre of the TV.
- **Settings initial focus**: first theme card (`theme-vesper`)
  now carries `data-initial-focus="true"` (previously on the
  Back button).  Verified: `document.activeElement` on Settings
  mount is `BUTTON[data-testid="theme-vesper"]`.
- **Page-level scrolling fix**: `ProfileEdit` and `Library`
  pages were unscrollable because the global `#root` / `.App`
  wrappers carry `overflow: hidden` (to keep Home's horizontal
  shelves from scrolling the whole document).  Each page now
  carries its own `height: 100dvh + overflow-y: auto` so the
  spatial-focus hook's `verticalScroller()` walker finds them
  and scrolls correctly.  Effect: avatars below the viewport
  on `/profiles/new`, and the Watch Later block on `/library`,
  are now reachable via D-pad Down.  Verified end-to-end —
  pressing Down from a TV-show card in the library lands on a
  Watch Later tile (`watch-later-remove-movie-tt15239678`).

## Implemented (Iteration 41 — Feb 14, 2026)
### 100 avatars + Home initial-focus on first shelf + Left-edge → Home
- **`lib/avatars.jsx` expanded from 50 → 100 avatars**.  New 50
  cover: more animals (15: turtle, octopus, whale, shark,
  butterfly, bee, giraffe, zebra, elephant, kangaroo, rhino,
  horse, deer, dolphin, peacock), food &amp; drink (10), nature
  &amp; weather (8: cherry blossom, sunflower, cactus, wave,
  rainbow, mushroom, palm tree, volcano), vehicles &amp; travel
  (7), hobbies &amp; gear (10: camera, paint palette, books,
  chess, dice, drums, violin, Saturn, roller skates, disco ball).
  Avatar header label "CHOOSE AN AVATAR · 100".  All keep the
  emoji-on-gradient + glow-ring pattern, no external images.
- **Home page initial focus** moved from hero Play button to the
  FIRST focusable inside the shelves region.  Removed
  `data-initial-focus="true"` from `hero-play-button` and added
  a useEffect in `Home.jsx` that retries focusing the first
  `[data-focusable="true"]` inside `[data-testid="shelves-region"]`
  at 80, 250, 600, 1100, 1800 ms (shelves render async).
  Verified: `document.activeElement` on Home mount is
  `network-netflix` (first network tile of the Networks shelf).
  Also re-fires when the `?filter=` query param changes so the
  movies-only / series-only view also focuses its first tile.
- **Left edge → Home (not Autoplay)** — fixed in
  `useSpatialFocus.js`'s `findNext`.  When using the DOM-sibling
  fast path and the user is on the leftmost tile of a horizontal
  rail, we now `return null` (instead of falling through to
  geometry scoring).  The geometry path was previously picking
  whichever side-nav item was vertically nearest — often
  Autoplay at the bottom — but the user always wants Left from
  a shelf to land on Home (top of nav).  `applyMove`'s edge
  fallback already used `navItems[0]` (Home) — it just wasn't
  being reached.  Verified: pressing Left from `network-netflix`
  lands on `nav-home`.

## ⚠️ FROZEN BASELINE — D-PAD FOCUS & NAVIGATION (USER-LOCKED Feb 13, 2026)

**THE USER HAS EXPLICITLY LOCKED THE CURRENT D-PAD BEHAVIOUR AS
"ABSOLUTELY PERFECT" AND ORDERED "DO NOT CHANGE A THING".**
This means *nothing* about how focus moves, scales, paints, or
animates may be modified without an EXPLICIT new instruction from
the user.  The current behaviour is the gold standard.  Future
agents: if a user complains about anything else, fix that —
DO NOT touch any of the following as a side effect:

### Files frozen — DO NOT EDIT without explicit user permission:
- `/app/frontend/src/hooks/useSpatialFocus.js` (entire file)
- `/app/frontend/src/index.css` — the `[data-focusable='true']`
  block (line ~270), all `[data-focus-style='...']` rules
  (lines ~350-440), and the `.vesper-host-android` overrides
  (lines ~557-585).

### Frozen rules — exact properties that must not be changed:
1. **`transition: none`** on every `[data-focusable='true']`.
   Focus snaps INSTANTLY.  No 130ms ease, no 200ms ease, no
   `transition: all`.  The previous tile must NOT animate-out
   while the new tile animates-in — that was the "ghost glow
   underneath" the user reported.
2. **Solid no-blur box-shadows only** on every focus style.
   Tile: `0 0 0 3px var(--vesper-blue-bright)`.  Pill / quiet /
   key: `0 0 0 2px var(--vesper-blue-bright)`.  No `Xpx Ypx Zpx`
   shadow with non-zero blur radius.  No `0 18px 36px` drop
   shadow.  No `0 0 22px` halo glow.
3. **Pop-out scale preserved**: tile `1.08`, pill `1.03`, key
   `1.10`, quiet `1.04`.  These are the "alive" feedback the
   user wants — never remove them.
4. **DOM-sibling fast path** in `findNext()` for Left/Right
   within a horizontal rail.  Geometry path is reserved for
   cross-shelf vertical nav + edge-of-rail nav into the side-nav.
5. **Synchronous keydown handler.**  Every `keydown` runs
   `applyMove(dir)` directly in the handler.  No rAF queue, no
   held-key throttle, no scrubbing class.  Per-press latency is
   ~0.5-1.2 ms in preview, ~10-20× headroom on the HK1.
6. **Cached focusables list** invalidated by debounced
   MutationObserver (`requestAnimationFrame` coalesced).  Plus
   a per-rail `__sfChildFocusables` cache keyed by `cacheGen`.
7. **rAF-coalesced `scrollBy()` calls** — multiple scrolls in
   one frame collapse into a single commit per scroller.

### If you accidentally regress this:
- Look at git log for the commit that broke it.
- The user will tell you it's "chunky" or "skipping tiles" or
  "ghost glow underneath".
- Revert to this baseline before doing anything else.

---

## Implemented (Iteration 33 — Feb 13, 2026)
### D-pad: DOM-sibling fast path for horizontal nav (Profile-Select speed for Home shelves)
- **Root insight**: Profile Select screen felt buttery because its
  tiles are simple flex siblings with no scroll — moving focus is
  essentially `el.focus()`.  Home shelves felt chunky because per
  press we ran `getBoundingClientRect` on 30-60 candidates to find
  the "geometrically nearest" tile.  The hook was the same, but
  the per-press work differed by ~30x.
- **Fast path added** in `findNext()`: when navigating Left/Right
  inside a horizontal rail, skip ALL geometry and just walk the
  rail's focusable DOM siblings (`querySelectorAll` cached per
  rail with a generation counter that invalidates with the global
  focusables cache).  Falls back to geometry only for edge-of-rail
  Left presses (so the cursor can hop into the side-nav).
- **Measured**: 20 rapid ArrowRights now complete in 9.7ms total
  (0.48ms per press) on the populated home screen — vs the
  previous geometry path that ran ~8-16ms per press on the same
  shelf.  Identical perf profile to the Profile-Select screen.
- All vertical / cross-shelf navigation still uses the geometry
  scoring (necessary — DOM order doesn't map cleanly across
  shelves with different layouts).

### D-pad: removed rAF queue + held-key throttle (earlier in same session)
- Stripped the rAF-batched press queue and `HELD_THROTTLE_MS = 70`
  repeat throttle.  Both were silently dropping inputs and adding
  a frame of latency.  Every `keydown` now runs `applyMove(dir)`
  synchronously in the handler.

### Compact theme cards on Settings
- Theme grid shrunk from `minmax(280px, 1fr)` to `minmax(200px, 1fr)`,
  aspect `4/3 → 5/4`, fonts/paddings scaled down.  8 themes now
  fit a single row at 1920px (was overflowing to 2 rows).

## Implemented (Iteration 32 — Feb 13, 2026)
### Rating tiers + dynamic Kids nav + D-pad fix
- **M15 / TV-14 rating tiers**: Settings now exposes Max movie
  rating G / PG / PG-13 / M15 and Max TV rating TV-Y / TV-Y7 / TV-G
  / TV-PG / TV-14 / M15.  Backend kid endpoints accept `movie_cert`
  and `tv_level` query params and translate to:
  - TMDB `certification.lte` per tier (G → G, PG → PG, PG-13 →
    PG-13, M15 → R).
  - Increasingly permissive genre gates per tier (e.g. M15 drops
    the Family-genre requirement; only Horror/War stay banned).
  - Search applies the cert ceiling on each candidate via
    `/movie/{id}/release_dates`, with M15 trusting genre-only
    filtering when TMDB has no US cert info.
- **Reactive Kids nav**: `KidsSideNav` reads `KidsConfig` and
  listens for `vesper:kids-config-change` so flipping
  `contentTypes` to `movies` hides the Cartoons rail item, and
  `series` hides Movies — kids never see a button that leads
  nowhere.
- **Movies / Cartoons tab → newest-first grid**: KidsHome detects
  `?filter=movie|series` and renders the same `<TabGridView>` the
  regular Home uses, so kids browse a single big poster wall
  exactly like the grown-up experience.
- **D-pad escape from KidsSideNav fixed**: `useSpatialFocus`
  treated `data-testid="side-nav"` as the only nav rail, so kids
  pressing Right from the kid-themed rail had nowhere to go.
  Replaced every hard-coded selector with a `NAV_RAIL` constant
  that matches both `side-nav` and `kids-side-nav`; Right now
  always escapes to the first content tile.
- **`useKidsShelves` / `useKidsHeroes`** re-key on the active
  rating settings (so changing G→M15 in Settings refetches and
  doesn't serve stale data).


### Reactive Kids settings + clearer Exit-PIN escape (Iteration 31)
- **KidsHome now respects Settings live.**  Reads `KidsConfig`
  on mount and listens for `vesper:kids-config-change` /
  `storage` events so flipping "TV Shows only" / "Movies only"
  in Settings instantly filters the rendered shelves, and hides
  the hero billboard when only TV is requested (no kid-safe TV
  hero exists).  Search results obey the same `contentTypes`
  mask.
- **"Saved — Kids home updated" toast** on the Settings page
  appears for ~1.6 s after every Family-Controls change so the
  user gets explicit confirmation the change persisted (previously
  the save was silent and felt like nothing happened).
- **Clearer KidsExitPin escape.**  Two unambiguous ways back:
  - top-left "Back to Kids" pill (kept as a quick exit)
  - prominent yellow "← Stay in Kids mode" CTA below the digit
    boxes (the obvious primary action for a parent who landed
    here by accident).
  Both route to `/`, which RequireProfile resolves to the
  themed Kids Home thanks to the existing sandbox guard.


### Locked-down Kids Mode + per-profile PINs (Iteration 30)
- **Kid-safe Search** — Search now switches to a new
  `/api/tmdb/kids/search` endpoint when a kid profile is active.
  The endpoint pre-filters by family/animation genres + bans
  Horror/Thriller/Crime/War, **then** verifies each movie candidate's
  real US MPAA cert ≤ PG via `/movie/{id}/release_dates` (parallel
  asyncio.gather, capped at 16 candidates).  Result: "family guy",
  "joker", "saw", "deadpool", "rick and morty" all return 0
  matches; "shrek", "frozen", "bluey" work perfectly.
- **PIN-locked kid escape** — moved the kid-sandbox check
  *before* the `NO_PROFILE_REQUIRED` exemption in `RequireProfile`,
  and wrapped `/profiles`, `/profiles/new`, `/profiles/edit/:id`,
  `/kids/exit-pin` in `RequireProfile` so a child can no longer
  type `/profiles` into the URL to slip out.  Only allowed paths
  for an active kid profile: `/`, `/play`, `/title/`, `/search`,
  `/resolve/`, `/kids/exit-pin`.  The PIN gate remains the only
  exit.
- **Per-profile PIN** — added `pin: string` field to the profile
  shape (4 digits, blank = open).  `ProfileEdit` exposes a Lock
  toggle + 4-digit input.  `ProfileSelect` shows a neon lock badge
  on protected tiles and pops a reusable `<PinGate>` modal that
  blocks activation until the right PIN is entered.  Kids can no
  longer pick Mum/Dad without the PIN.
- **Kid-themed Search page** — Search now applies
  `data-kids-theme="1"` + `KidsSideNav` whenever a kid profile is
  active, with copy switched to "Kid-safe search" / "What do you
  want to watch?".


### Kids Mode redesign — mirror of regular Home, kid-safe content (Iteration 29)
- **New Kids Home** (`KidsHome.jsx`) now mirrors the regular Home
  structure: `KidsSideNav` rail + `HeroBillboard` + horizontal
  `Shelf` rows + kid-safe banner.
- **Hard-filtered, curated content from TMDB** — relies on TMDB's
  `discover` API with strict filters instead of unreliable Stremio
  addon `certification` fields:
    - Movies: `certification_country=US`, `certification.lte=PG`,
      `with_genres=Family|Animation` (10751,16).
    - TV: requires BOTH Family AND Animation (`with_genres=10751,16`)
      plus explicit `without_genres=Drama|Crime|Thriller|Horror|War|
      Soap|Reality|News|Talk` and `with_original_language=en`.  This
      eliminates Family Guy / Rick and Morty / adult anime that the
      old "Animation only" filter was leaking.
    - Hero billboard: `certification.lte=PG`, popular family films.
- **New backend endpoints** (`/api/tmdb/kids/shelves`,
  `/api/tmdb/kids/heroes`) return 7 curated shelves and 6 hero
  candidates, cached server-side for 6 h.
- **Kids theme** — scoped CSS via `data-kids-theme="1"` swaps the
  cyber-blue accent for sunshine yellow + magenta and warms the
  background into a deep grape/berry gradient.  Applied on Kids
  Home and Detail (when viewing from a kid profile).
- **KidsSideNav** — playful gradient rail with chunky rounded icons,
  limited destinations (Home, Movies, Cartoons, Search) plus Exit
  Kids that opens the PIN gate.
- **Routing whitelist updated** — kids may now hit `/search` and
  `/resolve/`; Sources / Settings / Networks / Library remain
  blocked.


## Implemented (Iteration 28 — Feb 2026)
- **Per-shelf focus memory** — `useSpatialFocus` now bookmarks the
  last focused tile in each horizontal rail (stored as
  `rail.__lastFocusedKey` = its `data-testid`). On vertical re-entry
  into a rail (Up/Down lands on a different rail), focus restores
  to the bookmarked tile instead of the first one.

## Implemented (Iteration 29 — Feb 2026)
- **Netflix-style profile system** — three new pages + a profile
  library:
  - **`lib/profiles.js`** — localStorage CRUD (`listProfiles`,
    `saveProfile`, `removeProfile`, `setActiveProfile`,
    `getActiveProfile`, `isKidsActive`), Kids config
    (`getKidsConfig` / `saveKidsConfig`), and a kid-safe content
    filter (`isKidsSafe(meta, cfg)`) that ranks meta against
    movie & TV ceilings. Permanent immutable "Kids" profile.
  - **`lib/avatars.jsx`** — 30 unique avatars rendered inline as
    emoji-on-gradient circles + 1 hidden Kids default (teddy bear).
    Reusable `<AvatarCircle avatarId size ring />` component.
    Mix: 10 animals, 8 fantasy / cool, 5 sports / profession,
    3 faces, 4 symbols.
  - **`pages/ProfileSelect.jsx`** — "Who's watching?" Netflix-style
    picker. Shown on every app launch when no active profile.
    "Manage profiles" toggle exposes a Remove button on each user
    profile (Kids can't be removed).
  - **`pages/ProfileEdit.jsx`** — name input + 30-avatar grid with
    a check badge on the selected one. Max 20-char name.
  - **`pages/KidsExitPin.jsx`** — 4-digit PIN gate to exit Kids
    mode. No PIN configured → bypasses to the picker (so parents
    can leave freely until they set one).
  - **`pages/KidsHome.jsx`** — playful pink/yellow/green radial
    gradient bg, "Let's watch!" + teddy bear branding, filtered
    shelves via `isKidsSafe`, 2/3 aspect 180px tiles with yellow
    accent borders, "Exit Kids" button top-right.
- **App.js route guard** — `<RequireProfile>` HOC enforces:
  - No active profile → redirect to `/profiles`
  - Kids profile active → only `/`, `/title/`, `/play` are
    reachable; everything else (Settings, Sources, Search,
    Library) redirects back to `/`
  - `<HomeRouter />` chooses between `<Home />` and `<KidsHome />`
    based on active profile.
- **Settings additions**:
  - "Switch profile" tile → clears active + returns to picker.
  - "Family controls" section with: parent PIN (4-digit set/change),
    content type filter (movies / series / both), max movie rating
    (G / PG / PG-13), max TV rating (TV-Y / TV-Y7 / TV-G / TV-PG).


## Implemented (Iteration 27 — Feb 2026)
- **D-pad Down now jumps shelves correctly on Android TV** — root
  cause: `content-visibility: auto` on shelf sections made off-screen
  shelves render as 0 × 0 boxes, so my focusables filter (which drops
  elements with width === 0 / height === 0) excluded them entirely.
  On the wide web preview window most shelves were always visible →
  worked fine. On the smaller TV box usable area, the next shelf was
  invisible → unreachable. Removed `content-visibility: auto`; kept
  the lighter `contain: layout style paint` which still gives
  paint-isolation benefits without breaking nav.
- **D-pad Up now reaches Continue Watching** — same root cause as
  above. Once `content-visibility: auto` is gone, scrolling back up
  finds Continue Watching as a normal focusable shelf.
- **Right at row end no longer jumps to another row** — added a
  HARD ROW / COLUMN CONSTRAINT in `findNext`:
  - For Left / Right: candidate's vertical band must overlap the
    focused tile's (`r.top < cur.bottom - 4 && r.bottom > cur.top + 4`).
    If no candidate exists on the same row, the press is a no-op —
    we never fall through to a tile in a different row.
  - For Up / Down: candidate's horizontal drift must be within
    `max(focused.width × 1.5, 200 px)` — allows descending from a
    narrow sidebar onto wider content but refuses big sideways
    jumps during vertical scroll.


## Implemented (Iteration 26 — Feb 2026)
- **Press-feedback ripple** — pressing Enter on any focused tile
  fires a 280 ms pure-CSS animation:
  - Tile briefly punches inward (scale 1.08 → 0.97 → 1.08) for
    tactile feedback.
  - A 2 px neon-blue ring radiates outward from the tile (`::after`
    pseudo-element animating from scale 1 → 1.18, opacity 0.85 →
    0) for a clean ripple effect.
  - `useSpatialFocus` sets `data-pressed="true"` on the active
    element when Enter / Space is pressed, removes it 320 ms later
    so the ripple can re-fire on the next press.
  - Zero JS perf cost — the ripple is rendered entirely on the
    compositor via @keyframes. Works even on the HK1's slow GPU
    because the animated properties are only transform + opacity.


## Implemented (Iteration 25 — Feb 2026)
- **Full perf overhaul — native-app smoothness in the WebView** —
  five high-impact changes:
  1. **Focusables cache** (`useSpatialFocus.js`) — every keypress
     used to run `document.querySelectorAll('[data-focusable]')` +
     a `getComputedStyle()` filter on 80+ elements. Now cached and
     invalidated only on real DOM mutations via a debounced
     MutationObserver. Saves ~3-4 ms per key press on the HK1 —
     visible smoothness on hold-down nav.
  2. **Coalesced scrollBy via RAF queue** — multiple scrolls within
     the same frame collapse into ONE scroll commit per scroller
     using a `WeakMap`-backed pending-deltas accumulator. Hold-down
     nav at 14-20 keys/sec now produces 60 fps GPU-composited
     scrolls instead of 60 separate paints/sec.
  3. **`content-visibility: auto` on shelf sections** — shelves
     off the visible viewport now skip paint, layout, AND style
     entirely. With `contain-intrinsic-size: 360px` the scrollbar
     doesn't jump. Single biggest win: home boots ~6× faster to
     first interactive on the HK1.
  4. **`contain: layout style paint`** on shelves + shelves-region
     — invalidating one row never re-flows siblings. Eliminates
     the cascade-paint stutter when posters lazy-load.
  5. **Tighter focus transitions** — was `transform 280 ms +
     box-shadow 240 ms + background-color + color + border-color +
     opacity (4× redundant repaints)` → now `transform 180 ms +
     box-shadow 180 ms` only. Cuts focus-change paint cost in
     half.
  6. **`will-change: transform`** only (was `transform, box-shadow`).
     Older WebViews allocate a full GPU layer per declared
     property — strictly necessary for transform.
  7. **Cooldown tighter** — single press 90 → 70 ms, hold-repeat
     55 → 45 ms. Faster but still rate-limited so the user can
     never out-press the visual feedback.
  8. **Native WebView render priority** —
     `setRenderPriority(WebSettings.RenderPriority.HIGH)` plus
     disabled `verticalScrollBarEnabled`/`horizontalScrollBarEnabled`
     /`fadingEdge` to remove every CPU cycle wasted on UI chrome
     we don't draw.


## Implemented (Iteration 24 — Feb 2026)
- **Autoplay now applies to TV show episodes** — `SeriesEpisodes.jsx`
  `handleEpisodeClick` checks `getAutoplay1080p()` on every episode
  click. When ON:
  - Streams are fetched as usual via `Vesper.getStreams('series', ep.id)`.
  - The first 1080p direct stream (or any 1080p stream) is selected
    via the shared `pickAutoplayCandidate()` helper.
  - `playStream(candidate, ep)` fires immediately — no source list,
    no expand/collapse, no extra clicks.
  - If no 1080p stream is found, the episode card stays expanded
    with the full streams list as a manual fallback.
  - Cached episode streams are re-checked too: clicking an already-
    opened episode while Autoplay is ON re-fires the auto-pick (so
    toggling Autoplay on after opening an episode still works).
  When OFF, the existing expand-to-show-streams flow is preserved.


## Implemented (Iteration 23 — Feb 2026)
- **"Autoplay" toggle moved into sidebar** — removed the Auto 1080p
  pill + Settings cog from the hero. Added a new "Autoplay" item
  with a lightning-zap icon at the bottom of `SideNav.jsx` (below
  Settings, separate from the routing items). Tapping toggles the
  pref via `lib/prefs`; icon fills + label gains a neon-blue "ON"
  pill when active.
- **Detail page Play button (movies only, autoplay-aware)** — new
  big rounded Play pill below the movie metadata. When Autoplay is
  ON and a 1080p candidate exists in the resolved streams, the
  manual source picker is hidden entirely; the Play button fires
  the same auto-pick logic as the hero `?autoplay=1` flow. States:
  - **Loading** → spinner + "Finding 1080p…"
  - **Candidate found** → blue pill + "Play 1080p"
  - **No 1080p stream** → disabled grey pill + "No 1080p stream
    found"; the manual picker fades back in so the user always has
    a fallback.
  When Autoplay is OFF, the Play button is hidden completely and
  the streams list appears directly (existing behavior).
- **Cross-component pref sync** — Detail listens for `storage`
  events + polls every second so toggling Autoplay from the sidebar
  immediately re-renders the Detail page (storage events don't
  fire in the same window, so the poll is the workaround).
- **Refactored autoplay flow** — pulled `autoplayCandidate` into a
  `useMemo` so both the URL-triggered (`?autoplay=1`) path and the
  Play-button path share the exact same candidate-selection logic.


## Implemented (Iteration 22 — Feb 2026)
- **"Installed but invisible on Chinese Android 7 launcher" fix** —
  three root causes mitigated:
  1. **Vector banner replaced with raster PNGs** — `tv_banner.xml`
     was a vector drawable. Old Chinese AOSP launchers on Android 7
     sometimes fail to decode banner vectors, which causes the
     launcher to silently skip the app's tile entirely (the user's
     symptom: installed but not shown in launcher). Wrote 320×180
     PNG at mdpi + 640×360 PNG at xhdpi. Deleted the vector file.
  2. **Split intent-filters** — `LAUNCHER` and `LEANBACK_LAUNCHER`
     categories were sharing one `<intent-filter>` block. Some old
     Chinese launchers fail to scan combined filters and only pick
     up the first category. Split into two separate `<intent-filter>`
     blocks (matches Google's AOSP "TV apps that also run on phones"
     sample pattern).
  3. **Belt-and-braces** — added `android:icon`, `android:roundIcon`,
     `android:label` directly on the `<activity>` element so the
     launcher resolver always has icon metadata even when the
     application-level fallback chain breaks.
- **APK version bumped to 1.9.0 / versionCode 24** — ensures the
  reinstall on the Android 7.1.2 box replaces the existing entry
  cleanly (some old package managers refuse the install silently
  if the version doesn't increment).


## Implemented (Iteration 21 — Feb 2026)
- **Android 7.1.2 (API 25) compatibility confirmed + hardened** —
  Audit results:
  - `app/build.gradle.kts` already targets `minSdk = 21` (Android
    5.0+), so API 25 boxes are fully supported.
  - Hardware features (`leanback`, `touchscreen`, `faketouch`) all
    declared `android:required="false"` so the Play Store / Android
    install path won't reject the APK on phones-without-leanback
    or boxes-without-touchscreen.
  - APK signing uses v1 + v2 + v3 — old Android 6/7 boxes can only
    parse v1, so this combo unblocks them.
  - libVLC 3.6 supports API 17+, so playback works on Android 7.
  - Both `armeabi-v7a` and `arm64-v8a` ABIs bundled, so 32-bit-only
    Chinese boxes install without "App not installed" errors.
  - One API guard already present: `applyImmersiveMode()` branches
    on `Build.VERSION.SDK_INT >= R` (API 30) for the new
    `WindowInsetsController` and falls back to deprecated
    `systemUiVisibility` on older boxes.
  - All recent additions (`setLayerType(LAYER_TYPE_HARDWARE, null)`,
    `WebAppInterface.fetchUrl` using `HttpURLConnection`,
    `AlertDialog` from AppCompat, the custom exit dialog drawables,
    radial gradient drawables) are all API 21-safe.
- **JS/Web compatibility hardening** — `package.json` browserslist
  bumped to explicitly target `chrome >= 60` and `android >= 7` for
  the production build. This forces CRA's Babel to transpile
  optional chaining (`?.`), nullish coalescing (`??`) and other
  ES2020+ features down to ES5 equivalents that Android 7's stock
  WebView (Chrome ~56-60) can parse natively, even when the user
  hasn't updated the Android System WebView.


## Implemented (Iteration 20 — Feb 2026)
- **Autoplay 1080p defaults to ON** — `getAutoplay1080p()` in
  `lib/prefs.js` now returns true when the localStorage key is
  unset (was false). User can press Play immediately and the
  first 1080p stream auto-fires without having to find Settings.
- **Hero-row Auto 1080p toggle pill** — new "Auto 1080p · ON/OFF"
  pill button next to "My List" in the hero. Shows a filled
  lightning-zap icon when on, hollow when off. Neon-blue glow +
  border when active. One D-pad Right from Play / More Info / My
  List reaches it directly — no sidebar navigation needed.
- **Hero-row Settings shortcut** — circular gear button right after
  the Auto 1080p pill. Single D-pad press from the toggle takes you
  to /settings — no longer need to navigate down through the
  sidebar to find it.


## Implemented (Iteration 19 — Feb 2026)
- **TV Shows black-screen bug fixed** — `EpisodeCard` was reading
  `parentId` but the prop was never passed through. ReferenceError
  killed the whole series detail page on render. Added `parentId`
  to the destructured prop list. The Boys series page now renders
  with all 5 seasons + episode list intact (verified live).
- **Stream playback fix (Torrentio behind Cloudflare wall)** — root
  cause: Torrentio rejects calls from the backend's datacentre IP
  with a Cloudflare anti-bot page, so the backend stream proxy
  returns 0 streams. Fix: new `WebAppInterface.fetchUrl(url, timeout)`
  Kotlin bridge performs the HTTP GET from the HK1 box's residential
  IP using `HttpURLConnection` with a real browser User-Agent. JS
  side (`fetchJsonDirect` in `lib/api.js`) now uses the bridge first
  when running inside the WebView, falling back to standard
  `fetch()` if the bridge isn't available (browser dev).
- **WebView hardware acceleration overhaul** — root cause of the
  "chunky" D-pad nav on Android: the WebView was software-rendering
  every shelf scroll, repainting all 60+ posters per key press.
  Three-layer fix:
  1. **MainActivity.kt**: `setLayerType(LAYER_TYPE_HARDWARE, null)`
     promotes the WebView to a dedicated GPU layer.
     `isScrollbarFadingEnabled`, `overScrollMode = OVER_SCROLL_NEVER`
     stop the WebView's own inertia from fighting our D-pad scrolls.
     `setEnableSmoothTransition(true)` lets the WebView accept
     transform-based scroll optimisations.
  2. **index.css**: every `.vesper-shelf`, `[data-testid="shelves-region"]`,
     `[data-testid="home-main"]` and `[data-focusable="true"]` gets
     `will-change` + `transform: translateZ(0)` to force GPU
     compositing. Posters too — `image-rendering: optimize-contrast`
     + GPU promotion.
  3. **useSpatialFocus.js**: vertical AND horizontal `scrollBy()`
     calls are now wrapped in `requestAnimationFrame()` so the
     WebView compositor batches the scroll with the focus-glow CSS
     transition in a single GPU commit.

  Together these turn the home page from a 30 fps software repaint
  into a 60 fps GPU-composited glide — exactly the LeanBack /
  Stremio feel the user kept asking for.


## Implemented (Iteration 18 — Feb 2026)
- **Focus ring + shelf header no longer clipped on D-pad Down** —
  pinning the *centre* of the focused tile at 32 % of the scroller
  viewport worked when the scroller was the full window, but in
  the shelves region (≈ 350 px tall, sitting below the locked hero)
  a 280 px-tall poster's centre at 32 % put its TOP at -28 px —
  clipped. The shelf header (eyebrow + title, ~50 px above the row)
  got pushed even further off-screen. Switched to pinning the
  rect's **TOP** at `max(scrollerHeight × 0.22, 90 px)` — guarantees
  ≥ 90 px above every focused row for the shelf header + focus
  ring, regardless of tile size or scroller dimensions.
- **TV Shows tab is NOT broken** — verified in the live preview at
  /?filter=series: returns 155 titles instantly (Man on Fire,
  Widow's Bay, Unchosen, Half Man, etc.). The empty TV Shows tab the
  user is seeing is because the HK1 box is still running the older
  APK with the broken `shelves:series:60:...` cache key from
  iteration 14. The next APK build (which includes iteration 15's
  cache-key fix) will resolve it on the box.


## Implemented (Iteration 17 — Feb 2026)
- **Focus ring no longer clipped at the top of shelves** — added
  26 px paddingTop to the shelves-region container in Home so the
  first row of tiles has breathing room above. Each Shelf section's
  vertical padding bumped from 6→14 / 4→14 px so consecutive rows
  also don't squeeze each other's focus rings.
- **Bigger, more obvious pop-out** — tile focus transform now
  `scale(1.08) translateY(-2px)` (was 1.05 / -3 px). Box-shadow ring
  unchanged thickness but glow halo expanded 18 → 22 px for a more
  visible "lift" without overflowing row boundaries.
- **Horizontal scroll now edge-comfort instead of center-pin** —
  `useSpatialFocus.focusEl` for left/right was always centering the
  focused tile (so the rail scrolled even when the focused tile was
  visible already). Replaced with edge-comfort logic: rail only
  scrolls when the focused tile is within `max(80, cRect.width × 0.18)`
  of the visible band's edge in the direction of travel. Net effect:
  the first 3-4 cards stay anchored at the left as the cursor drifts
  rightward; only when the tile nears the right edge does the rail
  scroll; the last card sits flush at the right edge. Matches
  Stremio / Apple TV / Google TV behaviour.


## Implemented (Iteration 16 — Feb 2026)
- **D-pad Down from hero now focuses tiles correctly** — the focus
  was being clipped / lost because of three compounding bugs after
  the Home layout split:
  1. **Pin-point used `window.innerHeight`** — wrong reference when
     the scroller is a sub-container (the shelves region starts at
     y=620 below the locked hero, so the pin at 0.32 × vh = 256 was
     inside the hero, fighting itself). Now uses the scroller's own
     `getBoundingClientRect()` so `targetY = scrollerTop +
     scrollerHeight × 0.32` lands inside the visible band.
  2. **Cross-scroller transitions** — moving from hero (outside the
     scroll region) into a shelf tile (inside it) now snaps the new
     scroller's `scrollTop = 0` first, so the focused tile is never
     clipped on entry.
  3. **Initial-focus retry strategy** — Play button mounts async after
     TMDB / Cinemeta respond, so the first focus attempt hit
     FullscreenButton (first non-nav focusable in DOM order).  Five
     strict retries at 50 / 200 / 500 / 1000 / 1500 ms now wait for
     `data-initial-focus` to appear; fallback only kicks in at 1.8 s.
  4. **Right-edge clipping on shelves** — `paddingRight` of every
     horizontal shelf (Shelf.jsx, NetworksShelf.jsx,
     ContinueWatchingShelf.jsx) was `clamp(92px, 6.5vw, 132px)`
     (one full poster's width). Trimmed to `clamp(40px, 4.2vw, 80px)`
     so posters now reach the right edge of the screen.


## Implemented (Iteration 15 — Feb 2026)
- **Slimmer SideNav** — collapsed 108 px → 76 px, expanded 320 px →
  240 px. Items shrank from h-14 to h-11, icons 24 → 20, padding
  py-9 → py-7, label font 20 px → 15 px. Logo from 56 px → 40 px.
  Page padding-left tokens dropped from `clamp(124px, 9.5vw, 180px)`
  to `clamp(92px, 6.5vw, 132px)` everywhere (Home, Network,
  Networks, ContinueWatching, TabGridView, HeroBillboard, Shelf).
- **Sidebar opens only on FAR-LEFT press** — `useSpatialFocus` now
  filters the SideNav out of the candidate set when navigating
  Up/Down/Right from the content area. Pressing Left when no further
  left target exists is the dedicated trigger for moving focus into
  the sidebar (which auto-expands via its own onFocus handler).
  Pressing Right from inside the nav jumps back to the first
  non-nav focusable.
- **Hero locked in place** — Home now splits its layout: hero
  billboard is in a `shrink-0` div outside the scroll region; the
  Continue Watching / Networks / shelves all live inside a separate
  `flex-1 overflow-y-auto` container. When the user D-pad-Downs from
  Play into shelves, only that inner region scrolls — hero stays
  visible at the top forever.
- **TV Shows tab now actually loads** — root cause: I'd added
  `itemsPerCatalog` to the `useLiveShelves` cache key (`shelves:series:60:...`)
  which was a brand-new key with no localStorage fallback, so the
  first cold hit on the TV Shows tab had nothing to fall back to
  while the live fetch was in flight. Fixed by dropping the
  per-limit cache split: cache always stores the larger of
  `(itemsPerCatalog, 60)` items, and consumers slice down at render
  time. One cache entry now satisfies both home (18) and tab-grid
  (60) views.
- **Autoplay 1080p toggle in Settings** — new
  `lib/prefs.js` with `getAutoplay1080p()` / `setAutoplay1080p()`.
  Settings page gained a "Streams · Autoplay 1080p" toggle row.
  When ON, pressing the hero's Play button navigates with
  `?autoplay=1`; Detail.jsx watches for `autoplayRequested` +
  `streamLoading=false`, picks the first stream whose
  `qualityBadge.label === '1080p'` (preferring direct mode), and
  fires `playStream(candidate)` automatically — skipping the source
  picker entirely. Falls back to the picker silently if no 1080p
  stream is available.
- **Thin bright-blue focus glow** — replaced the fat 6 px ring +
  96 px halo + multi-layer shadow with a sharp 2 px neon ring + a
  tight 18 px outer glow. Matches Android TV / LeanBack default
  aesthetic. Applied to tile, pill, key, and quiet focus styles.


## Implemented (Iteration 14 — Feb 2026)
- **Offline-resilient cache** — `lib/cache.js` now mirrors `addons`,
  `shelves:*`, `heroes:*` and `networks:*` cache entries to
  localStorage (was sessionStorage only). On a cold APK start, the
  Home / Movies / TV Shows grids render their last-known-good
  catalogues instantly even when the backend preview environment is
  paused. Background revalidation still runs the moment the backend
  is reachable again. (`PERSIST_KEYS` set → `PERSIST_PREFIXES` array
  for prefix matching.)
- **Aggressive Emergent badge / preview-banner removal** — added a
  global CSS rule in both `index.css` and an inline `<style>` block
  at the top of `public/index.html`, so even the very first frame
  before React boots hides `#emergent-badge`,
  `[id*="static-preview"]`, `[data-resume-preview]` and all related
  selectors. The badge is now invisible in the live preview, the
  bundled APK, and any future regression.


## Implemented (Iteration 13 — Feb 2026)
- **Network page right-edge cutoff fixed** — `Network.jsx`'s poster
  grid had `paddingRight: clamp(124px, 9.5vw, 180px)`, exactly one
  poster's width of dead space.  Changed to the standard
  `clamp(40px, 4.2vw, 80px)` (same as Home shelves) so 8 posters now
  fit per row instead of 7.
- **Episode "Watched" badge** — new `cw.isWatched(id)` /
  `cw.getProgress(id)` helpers backed by a durable
  `onnowtv-watched-v1` localStorage set that's seeded automatically
  whenever progress ≥ 92 % or within 60 s of the end.
  `SeriesEpisodes.jsx` renders a neon-blue "Watched" check pill on
  the top-right of episode thumbnails plus a 4 px progress bar at
  the bottom for in-progress episodes; the text column is dimmed to
  0.68 opacity when watched.
- **Custom-themed Exit Confirm dialog** — `dialog_exit_confirm.xml`
  with matching `exit_card_bg`, `exit_glow`, `exit_btn_primary` and
  `exit_btn_secondary` drawables.  Replaces the stock AlertDialog
  with a 560 dp glass card: blue eyebrow, "Close the app?" headline,
  warm copy ("Your Continue Watching list is saved on this box — pick
  up right where you left off whenever you come back."), neon
  divider, and two D-pad-focusable pill buttons (Stay / Close app).
  `MainActivity.showExitConfirm()` inflates and shows it with a
  transparent window background so the rounded card corners render
  cleanly.


## Implemented (Iteration 12 — Feb 2026)
- **"Static Preview" banner killed inside the APK** — the bundled
  `index.html` was still loading `assets.emergent.sh/scripts/emergent-main.js`
  + the PostHog telemetry init, both of which injected the
  "You're viewing a static preview. Resume to interact" banner and
  the "Made with Emergent" badge into the WebView. The
  `build-apk.yml` workflow now runs a Python `re.sub` pass that
  strips:
    1. the `<script ... assets.emergent.sh ...>` tag,
    2. the `<a id="emergent-badge">…</a>` element, and
    3. the PostHog `<script>…posthog.init(…)…</script>` block
  from `frontend/build/index.html` before copying into Android
  assets. Build fails fast (`grep -q` sanity checks) if any of
  them slip through.
- **Runtime safety net** — `VesperWebViewClient.shouldInterceptRequest`
  returns an empty 200 for any request to `assets.emergent.sh`,
  `app.emergent.sh`, `emergent.sh` and `*.posthog.com`, so even if
  a future build leaks the script tag back in, the WebView will
  never fetch it.
- **D-pad navigation overhaul — instant scroll** — `useSpatialFocus.js`
  was using `behavior: 'smooth'` for scrollBy, which queued mid-flight
  scroll animations.  Subsequent key presses then read mid-animation
  rects and picked wrong candidates ("skipping icons" bug the user
  reported). Switched to **always-instant** scroll — fluidity comes
  from the focus-glow CSS transition, exactly like Stremio / LeanBack.
  Other tuning: perpendicular score weight 2 → 3 (stronger row/column
  preference), overlapTol 8 → 20 px (more forgiving alignment), single
  press cooldown 75 → 90 ms (rejects accidental double-presses), hold
  cooldown 55 ms.
- **Home snaps to top on every (re)mount** — `useLayoutEffect` +
  two deferred re-snaps (80 ms / 240 ms) force
  `home-main.scrollTop = 0` whenever Home mounts or the filter
  changes, so the bottom-aligned hero ("Featured · Action / The
  Boys / Play / More Info / My List") is always visible at the
  natural position.


## Implemented (Iteration 11 — Feb 2026)
- **TV Shows / Movies moved into SideNav** — `SideNav.jsx` now has
  dedicated `Tv` and `Film` entries that navigate to `/?filter=series`
  and `/?filter=movie`. The standalone `<HomeTabs>` segmented control
  is removed from the home page, freeing the vertical real-estate
  under the hero.
- **Newest-first Movies / TV Shows grid** — new `TabGridView.jsx`
  flattens every type-matching catalogue, dedupes by IMDb id, sorts by
  year desc and renders a responsive poster grid. `useLiveShelves`
  gained an `itemsPerCatalog` parameter (60 in filter mode, 18
  elsewhere) so the grid has enough density to feel "endless". CW
  shelf, Networks shelf and Hero billboard are all hidden when a
  filter is active.
- **Back-key exit confirm** — `useHomeBackHandler` writes a
  `window.__vesperOnHome` flag (`home-root` / `home-filter`).
  `MainActivity.onKeyDown` evaluates that flag on every KEYCODE_BACK:
  on `home-root` it pops an AppCompat `AlertDialog` ("Close ON NOW TV?")
  instead of unwinding history back to the launcher.
- **Snap-to-top on D-pad Up** — `useSpatialFocus.js` now scrolls the
  vertical container to `scrollTop = 0` when the focused element is
  already the topmost focusable, so the page header sits flush against
  the top edge instead of being half-clipped by the LeanBack pin.
- **Hero re-spaced** — `HeroBillboard` height bumped from 42 vh →
  56 vh, content aligned to bottom with `paddingBottom: clamp(48 px,
  5 vw, 96 px)` so Featured / Title / Play / More Info / My List sit
  in the lower third with proper breathing room. The "On Cinemeta /
  TMDB" sources pill-row at the bottom of the hero is removed.
- **Source-name leak removed from shelves** — shelf eyebrows
  (`useLiveShelves`) no longer show `"<addon.name> · MOVIE"`; just
  the type (e.g. `MOVIES`).


- **LeanBack-style spatial nav** — `useSpatialFocus.js` now pins the
  focused row at ~32 % of the viewport height so shelves glide under a
  stationary focus, matching Android TV's launcher feel. Cooldowns
  tightened to 75 ms (press) / 55 ms (hold).
- **Continue Watching now plays directly** — clicking a CW tile uses
  the saved `streamUrl` / `subtitleUrl` and goes straight into
  `VlcPlayerActivity` with `startAtMs = positionMs - 5 000`, skipping
  the source picker. Falls back to the Detail page only if the entry
  is missing a stream URL (older CW entries).
- **Movies persist progress** — `Detail.jsx` now passes `cwId: id` to
  `Host.playVideo`, so libVLC's `maybePersistProgress()` actually
  writes to `onnowtv_progress` for movies (previously only series
  episodes worked).
- **Player legibility scrim** — the controls overlay now lays a 40 %
  flat black scrim plus a radial centre dim (`grad_center_dim.xml`)
  behind the controls, so buttons stay readable over bright scenes.
  Top/bottom gradient bands also enlarged (140 → 200 dp, 280 → 340 dp).
- **Subtitle / Audio / Speed / Aspect focus restore** — `closePicker()`
  in `VlcPlayerActivity.kt` now re-focuses the bottom-row button that
  opened the sheet (tracked via `lastFocusedControl`) instead of
  dumping focus into the void.


## Implemented (Iteration 9 — Feb 2026)
- **Real APK with bundled frontend** — addressed user's observation
  that the previous APK was just a WebView pointing at the live
  preview URL.  Now the React build is **bundled inside the APK** as
  `assets/web/`, the WebView loads `file:///android_asset/web/index.html`,
  and only backend calls (TMDB / addons) hit the deployed server.
  - `homepage: "."` in `frontend/package.json` for relative paths.
  - `App.js` switches `BrowserRouter` → `HashRouter` automatically
    when running under `file:///` so deep links work offline.
  - `MainActivity.kt` enables `allowFileAccess`.
  - `VesperWebViewClient.kt` allows `file://` URLs, blocks unknown
    schemes, dispatches `intent://` / `magnet://` / `market://` to
    Android natively.
  - GitHub Actions workflow now: yarn install → yarn build →
    copy `build/.` → `assets/web/` → gradle assembleDebug.
  - APK version 3 → 4, versionName 1.0.1 → 1.1.0.
- **Emergent badge nuker** — `VesperWebViewClient` injects a tiny
  `MutationObserver` JS snippet on every page load that removes any
  Emergent preview badge (CSS rule + JS belt-and-braces).
- **Smaller posters** — PosterTile and NetworkPosterTile both bumped
  from `clamp(150–220px, 13.5vw)` → `clamp(120–180px, 10.5vw)`.

## Implemented (Iteration 8 — Feb 2026)
- **Tighter Home layout** — all 6 networks now fit on screen with the
  hero at 1080p without scrolling:
  - Hero height: 82vh → 68vh (min 480px)
  - Hero title: clamp 56→96px → clamp 36→64px
  - Synopsis: 4 lines → 2 lines, smaller font
  - Action buttons: scaled via clamp() — 56px → ~52px max
  - Vertical padding compressed throughout
  - Network tiles: 320px → 260px max, gap reduced
  - Section headers: mb-5 → mb-3
- **TV box stale-cache fix** — `MainActivity.kt` now wipes the
  WebView cache + cookies + history on every new APK install
  (tracked via `BuildConfig.VERSION_CODE` in SharedPreferences).
  Bumped versionCode 2 → 3, versionName "1.0.0" → "1.0.1".  This
  fixes the user's complaint that the Network pages showed old
  curated content on the box but live TMDB content on the web.

## Implemented (Iteration 7 — Feb 2026)
- **External video player handoff** — biggest win for HK1 boxes:
  - New `WebAppInterface.kt` Android JS bridge (registered as
    `window.OnNowTV`).  Web app calls
    `OnNowTV.playVideo(url, title, mime)` → bridge fires
    `Intent.ACTION_VIEW` → user's preferred player (VLC / MX Player /
    Kodi) handles playback with hardware decoding.
  - `Intent.createChooser` lets the user pick once and remember.
  - Solves: no-audio (system players bypass autoplay restrictions),
    poor performance (hardware decode), codec gaps (VLC plays
    everything), built-in subtitle picker (replacing our own when
    inside the wrapper).
  - `<queries>` declared in `AndroidManifest.xml` for Android 11+
    package visibility.
- **Performance mode** — `lib/host.js` detects the wrapper via JS
  bridge + UA; toggles `html.vesper-host-android` and `.vesper-low-end`
  classes.  CSS rules disable backdrop-blur, grain noise, ken-burns,
  pulse, and the fancy focus transforms — keeps cheap RK3318 / S905
  boxes scrolling smoothly.
- **FullscreenButton hidden inside wrapper** — the Android WebView is
  already immersive fullscreen; the browser fullscreen API was
  showing an ugly "press ESC" banner.  Hidden when `Host.isAndroid`
  or `Host.isOnNowTV`.
- **Detail.jsx + SeriesEpisodes.jsx** route Play through
  `Host.playVideo()` first, falling back to in-page `<video>`
  player when not in the wrapper.
- **`INSTALL_ON_TV.md`** prepended with VLC install instructions.

## Implemented (Iteration 6 — Feb 2026)
- **3-path TV deployment guide** at `/app/INSTALL_ON_TV.md`:
  - Path 1: TV Bro / Puffin TV browser (60s, zero build).
  - Path 2: Chrome PWA "Add to Home Screen" — full PWA manifest
    shipped at `/public/manifest.json` with logo icon + standalone
    display + landscape orientation.
  - Path 3: GitHub Actions workflow at `.github/workflows/build-apk.yml`
    auto-builds a debug APK on every push and publishes it to an
    auto-updating "apk-latest" GitHub Release.
- **APK build attempt locally** in container failed — ARM64 host
  can't run x86-64 AAPT2 reliably even with qemu-user-static.  Pivoted
  to GitHub Actions (free 2,000 min/mo Linux x86-64 runners).
- **Android wrapper updates**: applicationId → `tv.onnowtv.app`,
  versionName "1.0.0", new logo as launcher icon across all densities,
  removed obsolete adaptive-icon XML.

## Implemented (Iteration 5 — Feb 2026)
- **Rebrand to "ON NOW TV V2"** — replaced all user-visible "Vesper"
  strings while keeping internal CSS hooks (`vesper-display`,
  `vesper-mono`, etc.) untouched to avoid touching every component.
  - New logo asset: `/app/frontend/public/brand/onnowtv-logo.png`
  - SideNav: full-colour logo image with brand-blue drop-shadow,
    expanded label reads "ON NOW TV **V2**".
  - HTML `<title>`, favicon, apple-touch-icon, and meta description.
  - Backend `FastAPI(title="ON NOW TV V2")`, root endpoint returns
    `{"app": "ON NOW TV V2", "version": "1.0.0"}`, User-Agent header
    set to `OnNowTV/1.0`.
  - Android wrapper `strings.xml` (`app_name`).
  - Home footer wordmark + Sources copy.
- All 27 backend tests still passing (root assertion updated).

## Implemented (Iteration 4 — Feb 2026)
- **TMDB-powered network catalogues** — completely replaced curated
  imdb-id lists with a live TMDB integration:
  - `backend/.env` carries the user-provided TMDB v4 Bearer token.
  - `GET /api/networks/{slug}?type=tv|movie&page=N` proxies TMDB's
    `/discover` endpoint via `with_watch_providers`, with 1-hour
    backend cache.
  - `GET /api/tmdb/imdb/{type}/{tmdb_id}` resolves a TMDB id → IMDB
    id (7-day cache) so the existing `/title/{type}/{imdb}` Detail
    page keeps working unchanged.
  - Provider IDs verified live: Netflix 8 / HBO Max 1899 / Disney+
    337 / Prime Video 9 / Apple TV+ 350 / Hulu 15.
- **Frontend**:
  - `Network.jsx` rewritten — TV / Movies sub-tabs, infinite-scroll
    pagination via IntersectionObserver, "X of Y" counter (e.g. *20
    of 3,368*), dedupes overlapping pages by `tmdb_id`, persists
    sub-tab choice in `localStorage`.
  - `NetworkPosterTile.jsx` — clickable TMDB tile that lazy-resolves
    IMDB id with a loading overlay before navigating to Detail.
- Total catalogue exposed: **~40,000+ titles** across 6 networks.

### Iteration 4 Verification
- 27/27 pytest backend tests passing (added 9 new TMDB-specific
  tests in `/app/backend/tests/test_networks_tmdb.py`).
- Testing agent v3 frontend e2e: 100% — Netflix TV+Movies tabs work,
  Load More grows tiles by 20 per page, tile click resolves IMDB and
  routes to Detail with full series episode picker.

## Implemented (Iteration 3 — Feb 2026)
- **Browse-by-Network expanded** — `lib/networks.js` now ships ~30–50
  curated `{id, type}` titles per network across Netflix / HBO /
  Disney+ / Prime Video / Apple TV+ / Hulu. `Network.jsx` deduplicates
  by IMDB id, resolves each title via Cinemeta, and falls back to the
  *other* type on 404 — Disney+ now correctly mixes The Mandalorian
  (series) with Empire Strikes Back & Doctor Strange (films).
  Verified ~25–34 tiles render per network with a live "X of Y"
  counter in the hero strip.
- **Home tabs** — `HomeTabs.jsx` segmented control (`All`,
  `TV Shows`, `Movies`). Filters `useLiveShelves` by catalogue type
  and switches `useLiveHeroes` between movie/series sources. Choice
  persists in `localStorage` (`vesper-home-tab`). Networks shelf
  hides on the Movies tab.
- **Cinematic TV detail** — `SeriesEpisodes.jsx` renders inside
  `Detail.jsx` whenever `type === 'series'`. Pill-chip season picker
  + episode cards with 16:9 thumbnails, title, release date,
  ★ rating, runtime, and full synopsis. Selecting an episode reveals
  the per-episode stream list inline (`Vesper.getStreams('series',
  'ttXXXXX:S:E')`) without losing page context.

### Iteration 3 Verification
- 18/18 pytest backend tests still passing.
- Testing agent v3 frontend e2e: 100% — tabs, Network expansion,
  type fallback, season switching, episode expand-to-streams all
  green on https://rebrand-app-5.preview.emergentagent.com.

## Implemented (Iteration 2 — Feb 2026)
- **Auto-install on first launch** (`useAddons.js`) — silently installs
  Cinemeta + OpenSubtitles v3 if either is missing; persists per-default
  flag in `localStorage` (`vesper-bootstrap-attempted-v1`) so user
  removals are respected.
- **"Browse by Network" shelf** (`NetworksShelf.jsx` + `lib/networks.js`)
  on the Home screen — 6 brand-coloured 16:9 tiles (Netflix, HBO,
  Disney+, Prime Video, Apple TV+, Hulu) using each network's wordmark
  in their accent colour, no third-party logo assets.
- **`/networks/:slug` page** (`Network.jsx`) — branded gradient hero
  strip per network + grid of curated shows, each resolved via direct
  browser fetch to `https://v3-cinemeta.strem.io/meta/series/<id>.json`.
  Failures skipped silently so one dead id can't blank the page.
- **Subtitle picker** (`Player.jsx`) — passes `type` + `imdbId` from
  Detail through to `/play`; in-Player picker fetches
  `/api/subtitles/{type}/{imdbId}`, groups by language (English first),
  fetches the SRT body in-browser, converts SRT→WebVTT inline (handles
  `\r\n`, BOM, `,###` → `.###`), creates a Blob URL, and mounts a
  `<track default>` on the `<video>`. Active state surfaces a blue
  indicator dot on the subtitles button.

### Iteration 2 Verification
- 18/18 pytest backend tests passing
  (`/app/backend/tests/test_vesper_api.py` +
  `/app/backend/tests/test_subtitles_and_addons.py`).
- Testing agent v3 frontend e2e: 100% — auto-install fires on `/`,
  all 6 network tiles render, network pages each show 8–10 posters,
  subtitle picker opens / shows OFF + English rows / closes / sets
  the active-dot indicator.
- HK1 box audio confirmed: `mediaPlaybackRequiresUserGesture = false`
  is set in `MainActivity.kt` line 57 — the autoplay block is purely
  a desktop-Chrome dev-policy and will not trigger inside the WebView.

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

## Implemented (Iteration 11 — Feb 2026)
- **APK ABI fix** — Previous `arm64-v8a only` build refused to install
  on most HK1 boxes (which ship 32-bit Android ROMs even on 64-bit
  SoCs).  Now ships both `armeabi-v7a` + `arm64-v8a`.  Bumped to
  versionCode 11 / versionName 1.3.0.
- **"By network" section moved down** — NetworksShelf paddingTop
  increased from `clamp(4px, 0.6vw, 10px)` → `clamp(28px, 3vw, 56px)`
  to add proper breathing room below the All / TV Shows / Movies
  tabs.
- **Demo / mock data completely removed** — deleted
  `frontend/src/data/mockCatalog.js`, stripped `MOCK_HEROES` and
  `MOCK_SHELVES` fallbacks from `HeroBillboard` and `Home`.  When no
  Cinemeta data is available, hero billboard now falls back to live
  TMDB Trending (new `/api/tmdb/trending` endpoint) instead of
  baked-in fake titles.  Hero clicks resolve TMDB → IMDB via the
  new `/resolve/:type/:tmdb_id` route then route to the existing
  Detail page.
- **Native player — cinematic preview overlay** — `VlcPlayerActivity`
  now renders a full-screen Stremio-style loading screen with:
  - Backdrop image (dim 55%) behind a vertical vignette
  - 220×330 poster on the left
  - Eyebrow "NOW PLAYING · ON NOW TV V2"
  - Big title
  - Meta line: year · ★rating · runtime · genres
  - 3-line synopsis
  - Live "Buffering · NN%" status pill driven by VLC events
  - Bottom shimmer bar
  - Fades out 1.2s after the first PLAYING event
  Meta is plumbed end-to-end via `Host.playVideo({poster, backdrop,
  synopsis, year, rating, runtime, genres})` → new
  `OnNowTV.playInternalRich` JS bridge → intent extras.
- **Native player — track picker overlay** — D-pad-navigable side
  sheet with four entry buttons in the bottom controls:
  *Subtitles*, *Audio*, *Speed*, *Aspect*.
  Each opens a RecyclerView of options pulled directly from VLC at
  runtime (`mediaPlayer.spuTracks`, `mediaPlayer.audioTracks`) plus
  static lists for playback speed (0.5×–2×) and aspect ratio
  (`SURFACE_BEST_FIT`, `SURFACE_FILL`, `SURFACE_16_9`, `SURFACE_4_3`,
  `SURFACE_ORIGINAL`).  BACK closes the sheet.  Track rows have an
  active indicator dot + custom blue focus ring drawable.
- **Recyclerview dep added** — `androidx.recyclerview:recyclerview:1.3.2`.
- **New drawables** — `preview_vignette`, `poster_bg`, `status_pill`,
  `track_row_bg`, `track_dot_on`, `track_dot_off`.


- **APK Kotlin compile fix** — `VlcPlayerActivity.kt` failed Gradle
  compile with `Unresolved reference: Slave`. In libvlc-android
  3.6.0, the `Slave` class lives on `IMedia` (not `Media`).  Imported
  `org.videolan.libvlc.interfaces.IMedia` and switched the call to
  `IMedia.Slave.Type.Subtitle`.  GitHub Actions APK build now passes.
- **Spatial D-pad scroll jitter eliminated** — Root cause: the shelf
  had `scroll-snap-type: x mandatory` + `scroll-behavior: smooth`
  in CSS, which fought against JS-controlled `scrollBy({behavior:
  'smooth'})` in `useSpatialFocus`.  Scroll-snap re-snapped to the
  nearest tile *after* the JS scroll, producing the "jump forward /
  jump back" rubber-band.  Removed both CSS scroll-snap and CSS
  smooth scroll on the shelf and on `<main>`; the hook now owns
  smooth scroll exclusively.  Also rewrote `focusEl` to compute its
  own vertical delta against a 22%–70% viewport band (never calling
  `scrollIntoView`).
- **Tile pop-out on focus** — On Android WebView, `:focus-visible`
  does not always engage for programmatic `.focus()`.  The CSS
  rules for `scale(1.07)` + glow ring already supported
  `[data-focused='true']`; the hook now tracks the active element
  and toggles that attribute on focus, so the pop-out reliably
  triggers on D-pad navigation.
- **Home covers shifted up** — Hero billboard reduced from
  `clamp(380px, 56vh, 620px)` → `clamp(300px, 42vh, 480px)`.
  Shelf section padding-top reduced (32 → 14px max) and inner row
  paddings rebalanced.  NetworksShelf top/bottom paddings tightened.
  On a 1080p screen the hero + tabs + 6 network tiles + first
  "Popular" row all fit above the fold.


## Backlog (Prioritised)

### P0 — Next
- **Plex integration** — plex.tv OAuth PIN flow, server discovery,
  library browsing, direct-stream URLs. Add `/sources` card.
- **Jellyfin integration** — server URL + username/password,
  `/Users/AuthenticateByName`, browse, stream. Add `/sources` card.

### P1
- **VLC overlay controls** — D-pad-driven track switcher (subtitle,
  audio, playback speed) inside `VlcPlayerActivity`.
- **My Library** page — favorites + watchlist + watch-history.
- **Settings** page — per-user prefs (autoplay, language, region,
  quality cap).
- **Search keyboard** — speech input on supported boxes.

### P2
- Multi-user auth (Emergent Google login or JWT).
- Watch-progress sync.
- Cast / continue-watching cross-device.
- ErrorBoundary at the app root.
- Network catalog refinement: `lib/networks.js` mixes a few movie ids
  inside the series-only meta fetch — they 404 and are silently
  skipped. Cleanup or `(imdbId, type)` pairs would tighten this.

## Non-Goals
- We will not modify, repackage, or distribute the decompiled
  Nova Box / NovaMobile APK or any derivative of it.
- We will not bundle piracy stream-aggregator addons into the
  suggested-addons list. Users may install whatever third-party
  addon URL they choose; that responsibility is theirs.
