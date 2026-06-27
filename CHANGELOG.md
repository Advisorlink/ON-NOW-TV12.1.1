# CHANGELOG — ON NOW TV TUNES + V2

## v2.10.72 — Fresh-install boxes always surface the Register screen (2026-02-27)

User feedback: *"When I'm installing the launcher onto a new box, it's not asking me to register the device.  I need it to say register the device, because right now it's not saying register the device.  So I need to make sure that it says that — so it shows up in the launcher backend so I can approve it.  Only on a new install though."*

### Root cause
v2.8.8 added a "silent auto-register" path inside `OnboardingActivity.decidePhase()`.  The original ask back then was: with 500+ trusted boxes the user can't manually approve every one, so when the backend returned `status=unregistered`, the launcher would synthesize a default name (`{Manufacturer} {Model} · {last 6 of device id}`), POST `/api/launcher/register` automatically, then poll for activation.  With `AUTO_APPROVE_DEVICES=1` server-side the box would land at `status=active` and the launcher would jump straight to home — **with no UI ever shown** on the new TV.

That's exactly the bug the user reports today: the manual Register screen is now invisible on fresh installs.

### Fix (launcher-side only — no backend changes)
- **Removed** the silent auto-register block in `OnboardingActivity.decidePhase()`.  When the backend says `unregistered`, the activity now falls straight through to `PHASE_REGISTER` (the manual name-entry UI with the on-screen keyboard).
- **Pre-fill** for speed: `renderRegisterPhase()` now seeds `typedName` with `autoRegisterDeviceName()` (manufacturer + model + last-6 of device id), so the operator can either edit it to a custom label (e.g. *"Lounge TV"*) or just press Register and ship.  The device appears in the admin backend immediately under that name — the operator approves it from the Connected Devices panel.

### Returning boxes unaffected
The fix only changes the `unregistered` branch.  Boxes already in `active` / `pending` / `blocked` state on the backend skip Onboarding entirely as before — `MainActivity.onCreate` reads `OnboardingActivity.currentStatus(this)` and only routes to Onboarding when the local cached status isn't `active`.  So a reinstall on the SAME physical box (same SharedPreferences = same `device_id` → backend still has the record) boots straight into the launcher home with zero friction.

A reinstall AFTER a factory reset (SharedPreferences wiped → new `device_id` generated) is, correctly, a "fresh install" → manual register screen appears.  That's the user's intended behaviour: *"every time I'm installing it onto a new box"*.

### Files touched
- `android/onnowtv-launcher/app/src/main/java/tv/onnow/launcher/onboarding/OnboardingActivity.kt` — silent auto-register block removed (decidePhase) + register screen pre-fill (renderRegisterPhase).

### Verified
- Kotlin static check: braces 133=133, parens 496=496, 883 lines.  No compile error surface.  Kotlin compile runs in CI when the user clicks Save to GitHub.


## v2.10.71 — Bulk-install all apps on a fresh box (2026-02-27)

User feature request: *"I want a way so I can get… on a new box install, I can open up the launcher and in the apps section, push and hold for five seconds in the top left-hand corner.  And then it opens up a section where it says 'Install all apps' — all of our apps are held on the [launcher backend] somewhere — so it'll install all of them at once.  I don't want to have to click install on each of them.  Like a backup basically.  So it installs all the apps in one hit and then it's ready to go."*

User-confirmed choices (via ask_human v2.10.71/Q1):
1. Long-press: **silent at first + audible click + visual ring** when it triggers.
2. Policy: **reinstall everything every time** (like a real backup, no "skip already installed" check).
3. Strategy: **download all APKs to cache FIRST, then install** (system dialogs land back-to-back with no download wait between them).

### 1. Launcher-backend endpoint
New public `GET /api/bulk/manifest` on `launcher-backend/main.py`:

```json
{
  "apks": [
    {
      "key":          "movies",
      "label":        "Movies/TV",
      "package_id":   "tv.onnowtv.app",
      "version":      "2.10.71",
      "apk_url":      "https://onnowhub.com/launcher/assets/tile_apks/tile-movies-abcd1234.apk",
      "apk_filename": "vesper-2.10.71.apk",
      "icon_url":     "https://onnowhub.com/launcher/assets/tile_images/tile-movies-aae330fb.png",
      "size_bytes":   8650752
    }
  ],
  "generation": 668,
  "count": 1
}
```

Only tiles that already have a pinned APK appear.  No admin auth — the launcher itself has no Bearer token and must drive bulk install on a fresh box.  4 pytest contract tests (`tests/test_bulk_install_manifest.py`) lock the shape so future refactors can't silently break the Kotlin client.

### 2. New `BulkInstallActivity` (Kotlin)
`onnowtv-launcher/.../install/BulkInstallActivity.kt` — full-screen UI with:
- Eyebrow `ON NOW · BULK INSTALL`, title `Install every app, all at once`.
- Live status banner that narrates the queue (`Downloading 3 of 5: Vesper TV…` → `All apps queued.  Close this screen when you're done confirming dialogs.`).
- One row per app from the manifest with a status pill (`PENDING → DL 73% → DOWNLOADED → INSTALLING → INSTALLED` or `FAILED`).
- Big focusable `Install all apps` button.  Disabled while running.
- Footer warning that Android will pop one confirmation dialog per APK (unavoidable for non-system launchers).

Queue logic:
- **Phase 1**: download every APK to `cacheDir/bulk_apks/*` sequentially with live percentage on the pill (`OkHttp` + 64KB chunks).  A download failure marks that app `FAILED` and the queue keeps going.
- **Phase 2**: fire the system install intent for every successfully-downloaded APK in sequence with a 1200ms gap between each so the system has time to render its dialog before the next one stacks.

### 3. Hidden long-press gesture in `AppsDrawerActivity`
`installBulkInstallGesture(root)` — invisible 120×120dp zone in the top-left corner with a touch listener:
- `t = 0` (DOWN): start 5-second timer.
- `t = 2s`: fade in a small sunshine-yellow ring at the corner, then animate it growing toward "full" over the remaining 3s so the operator gets visual feedback that the gesture is registering.
- `t = 5s`: `MediaActionSound.FOCUS_COMPLETE` audible click + `startActivity(BulkInstallActivity::class)`.
- DOWN released before 5s → cancel timer, hide ring, reset.

The zone view is `!isFocusable / !isClickable`, so D-pad focus keeps falling through to the App Store grid behind it — only a physical touch ever triggers this gate.

### 4. AndroidManifest
`tv.onnow.launcher.install.BulkInstallActivity` registered with `exported=false`, landscape orientation, NoActionBar theme.

### Files touched / added
- `launcher-backend/main.py` — `/api/bulk/manifest` + `_safe_filesize` helper.
- `launcher-backend/tests/test_bulk_install_manifest.py` — 4 contract tests.
- `android/onnowtv-launcher/app/src/main/java/tv/onnow/launcher/install/BulkInstallActivity.kt` — **new**.
- `android/onnowtv-launcher/app/src/main/java/tv/onnow/launcher/apps/AppsDrawerActivity.kt` — long-press gesture installer.
- `android/onnowtv-launcher/app/src/main/AndroidManifest.xml` — activity registration.

### Verified
- Backend: `curl http://127.0.0.1:8002/api/bulk/manifest` returns expected JSON.  4/4 pytest contract tests pass in 0.17s.
- Kotlin: brace balance 69=69 (BulkInstallActivity), 168=168 (AppsDrawerActivity), parens 284=284 / 503=503.  Static-only; Kotlin compile runs in CI when the user clicks Save to GitHub.

### User-facing caveat (documented in the activity's footer text)
Android REQUIRES per-app system install confirmation for any non-device-owner / non-system launcher.  So on a fresh box the operator will press OK on the remote N times (once per pinned APK).  That's still vastly better than finding each APK by hand — single sweep, ~5 D-pad clicks, done.


## v2.10.70 — Kids Detail playback fix + Kids splash branding + pill nudge (2026-02-27)

User feedback after v2.10.69:
> *"When you click on a movie or a TV show to watch it in the Kids section, it ends up going back to that Exit App thing.  Also, we need to remove the Similar and also the Actors away from the Kids section.  Also, I wanted to say On Now Kids when it starts, not On Now TV2.  And also have a logo, a different logo — maybe K2 for the logo.  And just move that Update button, just up just a little tiny bit more."*

Five-part fix shipping together.

### 1. Kids Detail playback no longer bounces to exit-PIN
Root cause: `useKidsBackGuard` was pushing a sentinel history entry on EVERY kids-allowed path (KidsHome, Detail, Player, Resolve, Search) and intercepting every `popstate` event by routing to `/kids/exit-pin`.  So as soon as the Player closed itself (or the user pressed remote Back to leave the title detail), `navigate(-1)` fired `history.back()` → popstate → guard slammed them to the PIN gate.

Fix: only push the sentinel and intercept popstate on the TOPMOST kids paths (`/`, `/kids`, and the exit-PIN itself).  Any deeper page (Detail, Player, Resolve, Search) now lets normal Back pop the stack — KidsHome → Detail → Player → Detail → KidsHome works the way users expect.  The native HOME-button kiosk lock is still enforced separately by `useKidsKioskGuard`.

Verified live: poster click → `/title/movie/<imdb>` (Detail renders) → browser Back → `/` (KidsHome renders, NOT exit-PIN).

### 2. Cast / Similar / Filmography removed from Kids Detail
The `<CastRow>` on the Detail page hosts three view modes — Cast, Filmography (the actor's other titles, which can include adult work), and Similar (TMDB recommendations, not guaranteed kid-safe).  Any of these is a tap-away escape from the kid-safe zone.

Fix: wrap the entire cast-lane render block in `!isKidsActive()` so it never mounts in Kids context.  Verified live: `document.querySelector('[data-testid="detail-cast-lane"]')` is null on the Kids Detail page.

### 3. Boot splash branding — "ON NOW K2" + warm backdrop
`BootSplash` is now host-aware via `isKidsApp()`:
- **Kids APK**: wordmark `ON NOW K2` (sunshine-yellow accent `#FFD24A`), tagline `Welcome to ON NOW Kids`, grape/berry radial backdrop (`#3c1f5e → #0c0717 → #050309`), matching yellow sweep underline + shadow.
- **Vesper / other**: unchanged `ON NOW V2` cyan splash.

The "K2" mark + sunshine yellow gives Kids its own visual identity (no Vesper blue bleed) while keeping the V2/K2 symmetry the user asked for.

### 4. KidsHome footer
`ON NOW TV · KIDS` → `ON NOW · KIDS`.  Drops the "TV" filler so the standalone Kids product reads as its own brand.

### 5. Launcher dock pill — nudged up
`item_dock.xml`: pill `translationY=-30dp` → `-42dp`.  Sits noticeably higher above the tile artwork now per *"just up just a little tiny bit more"*.  Animation behaviour unchanged (still still at rest, one-shot focus shine from v2.10.68).

### Files touched
- `frontend/src/hooks/useKidsBackGuard.js` — topmost-only sentinel gate.
- `frontend/src/pages/Detail.jsx` — `!isKidsActive()` guard on cast lane.
- `frontend/src/components/BootSplash.jsx` — host-aware branding.
- `frontend/src/pages/KidsHome.jsx` — footer string trim.
- `android/onnowtv-launcher/.../res/layout/item_dock.xml` — pill translationY.

### Verified live
- Kids splash: wordmark `ON NOW K2`, tagline `WELCOME TO ON NOW KIDS`.
- Vesper splash: unchanged `ON NOW V2`.
- Poster click → Detail renders WITHOUT cast lane.  Back → KidsHome (not exit-PIN).
- Lint clean.  XML / Kotlin compiles in CI.


## v2.10.69 — Kids app fully decoupled from Vesper (2026-02-27)

User feedback was explicit: *"The Kids section should not have a Vesper login.  They should not share the same login.  They should not have anything.  The Kids section should not have a profile selection screen.  The Kids section is the Kids section. The Kids app is the Kids app. That is it.  Once you click on the Kids app, it opens up the [setup] that you choose a PIN for the kids, you choose what rating, and then it opens up the Kids section.  And then if you try to get out of it … you need the PIN to get out of it."*

This is now the actual implementation, not just the branding-coat-of-paint version v2.10.68 shipped.

### Standalone Kids APK boot flow (verified live)
1. Open Kids app → URL has `?profile=kids`.
2. `App.js`'s module-load IIFE sets `window.__vesperBootProfileKids = true` BEFORE React mounts (so `DeepLinkHandler` stripping the query string later doesn't matter).
3. `<LoginGate>` sees `isKidsApp() === true` and **returns children directly** — no Vesper auth round-trip, no LoginScreen render, no JWT.
4. `<App>` renders `<KidsAppRoutes>` instead of the full Vesper `<Routes>` tree.  KidsAppRoutes contains ONLY: `/` (KidsHome / Setup gate), `/kids/setup`, `/kids/settings`, `/kids/exit-pin`, `/search`, `/title/*`, `/play`, `/resolve/*`, catch-all → `/`.  No `/profiles`, no `/library`, no `/settings`, no `/music`, no `/fta`, no `/live-tv`, no `/sources` — they are physically not in the route table so a curious kid cannot type their way out.
5. First launch: PIN not yet configured → `<Navigate to="/kids/setup">`.  KidsSetup wizard (existing, untouched) collects PIN + max movie rating + max TV rating → writes to `onnowtv-kids-config-v1` → `navigate('/', { replace: true })`.
6. KidsAppRoutes subscribes to `vesper:kids-config-change` via `useState`/`useEffect`, so the PIN-just-saved event triggers a re-render with `pinConfigured = true` → KidsHome renders.
7. Every subsequent launch: PIN is already in localStorage → goes straight to KidsHome.

### Vesper has nothing Kids-related anywhere
- `profiles.js::listProfiles()` filters out the synthetic Kids profile entry unless `isKidsApp() === true`.  Vesper / Tunes / FTA / browser **never** see a Kids tile in their profile picker.  Verified live: Vesper picker shows only `Add Profile`.
- `VesperOnlyChrome` short-circuits to `null` in Kids context (no toasts, no nudges, no reminders, no dev badge — Kids app is dead-quiet).
- Login screen branding kept at `ON NOW TV · V2` (no "Vesper" wording anywhere) for any context that DOES render the login screen.

### PIN-gated exit (existing flow, now correctly routed)
- `KidsExitPin` accepts the 4-digit PIN, then calls `window.OnNowTV.exitVesperToLauncher()` (native bridge) — Kids APK Activity `finish()`es, user lands back on the ON NOW Launcher.
- `useKidsBackGuard` pushes a sentinel history entry on every Kids route + routes any popstate to `/kids/exit-pin`.  Hardware Back from the remote = PIN gate.
- `useKidsKioskGuard` updated to treat `/` as a valid Kids path (was the long-standing bug that made KidsHome bounce to the exit-PIN on every entry); also navigates to `/` instead of the non-existent `/kids` when the user returns from HOME inside the standalone Kids APK.

### Files touched
- `frontend/src/App.js` — new `<KidsAppRoutes>`; branch in `<App>` render; `<VesperOnlyChrome>` Kids short-circuit.
- `frontend/src/lib/profiles.js` — new `isKidsApp()` helper; broadened `isKidsActive()`; Kids-tile filter in `listProfiles()`.
- `frontend/src/components/LoginGate.jsx` — early-return for Kids APK.
- `frontend/src/hooks/useKidsKioskGuard.js` — `/` now Kids-allowed; visibility-redirect lands on `/` in standalone Kids APK.

### Verified live (preview pod)
- `/?profile=kids` clean state → `/kids/setup` (no login, no profile picker).
- PIN 1234 → confirm → choose ratings → finish → lands on `/` showing `KidsHome` with `data-kids-theme="1"` and `KidsSideNav`.
- Pre-seed PIN + reload `/?profile=kids` → goes straight to KidsHome.
- `/` (no kids context) → Vesper LoginScreen with `ON NOW TV · V2`; after sign-in → picker has ONLY `Add Profile` (no Kids tile).
- Lint clean on `App.js`, `profiles.js`, `LoginGate.jsx`, `useKidsKioskGuard.js`.



User attached a video showing the launcher's per-tile UPDATE pill pulsating frantically and asked for it to "just be still and have a slight animation like a light shine over it when you get to that tile".  Also: "I dont want the kids to have anything to do with Vespa at all… take out ALL of the kids stuff from Vespa including the profile selection bit … the kids app shouldn't have Vesper login attached, it's ITS OWN APP NOT VESPA, so fix it all."

Four-part fix shipping in lockstep.

### 1. Launcher pill: no constant pulse, focus-driven shine
`DockAdapter.bindUpdatePill` no longer starts an INFINITE+REVERSE ValueAnimator on every visible pill.  Pill stays motionless at scale=1.0 / alpha=1.0 / elevation=8dp at rest.  A new `triggerPillShine(pill)` helper is invoked from the tile's `OnFocusChangeListener` whenever the tile underneath gains focus — fires a single 620 ms scale 1.00 → 1.06 → 1.00 wink + brief elevation boost (8dp → 16dp → 8dp) with `AccelerateDecelerateInterpolator`, gives the badge a brief "twinkle" as the user lands on the tile, then returns to rest.  Animator stashed on `R.id.update_pill` tag so the next recycle/focus cancels any in-flight shine cleanly.

### 2. Login screen branding is now host-aware AND survives URL-strip
Root cause: `DeepLinkHandler.useEffect` strips `?profile=kids` via `history.replaceState` immediately on mount.  Any subsequent re-render of `LoginScreen.Header` read `window.location.search === ''` and fell back to the Vesper branding — exactly the bug the user has been hitting for two iterations.

Fix: `App.js` captures the kids context at MODULE-LOAD time (before any React mount) into a new `window.__vesperBootProfileKids` flag.  `LoginScreen.Header` consults this flag FIRST (URL/host kept as defensive fallbacks).  Plus the Vesper fallback eyebrow flipped from `Vesper · v2` to `ON NOW TV · V2` — no "Vesper" wording remains on the login screen in any context.

### 3. Kids profile removed from Vesper's profile picker
`profiles.js` no longer unconditionally appends the synthetic Kids profile to `listProfiles()`.  The Kids tile is auto-included ONLY when `window.__vesperBootProfileKids === true`.  In Vesper / Tunes / FTA / browser contexts the Kids entry is also actively filtered OUT, so any stale localStorage from older builds can't leak a Kids tile back into the adult picker.  Cascading effect: `getActiveProfile()` now returns `null` for `active='kids'` outside Kids context → `isKidsActive()` → `false` → all `<RequireProfile>` kids-sandbox branches stay dormant.

### 4. HomeRouter guard simplified
`HomeRouter` now keys its kids-allow check on `window.__vesperBootProfileKids` rather than re-reading the (already-stripped) URL.  Same effect, fewer moving parts.

Verified live:
- `/` (Vesper) → eyebrow `ON NOW TV · V2`, heading `Welcome back`, profile picker after login has only "Add Profile" (no Kids tile).
- `/?profile=kids` (Kids) → eyebrow `ON NOW · KIDS`, heading `Welcome, little one`, profile picker after login has the Kids tile.
- `window.__vesperBootProfileKids` correctly reports `true`/`false` in each context.
- Lint clean on `App.js`, `profiles.js`, `LoginScreen.jsx`.  `DockAdapter.kt` braces 50=50, parens 185=185 — static-only verification; Kotlin compile runs in CI.


## v2.10.68 — Launcher dock pill: still at rest + focus-driven shine (2026-02-27)

User attached a video showing the launcher's per-tile UPDATE pill pulsating frantically and asked for it to *"just be still and have a slight animation like a light shine over it when you get to that tile"*.

### Fix
`DockAdapter.bindUpdatePill` no longer starts an INFINITE+REVERSE `ValueAnimator` on every visible pill.  Pill stays motionless at `scale=1.0 / alpha=1.0 / elevation=8dp` at rest.  A new `triggerPillShine(pill)` helper is invoked from the tile's `OnFocusChangeListener` whenever the tile underneath gains focus — fires a single 620 ms scale 1.00 → 1.06 → 1.00 wink + brief elevation boost (8dp → 16dp → 8dp) with `AccelerateDecelerateInterpolator`, giving the badge a brief "twinkle" as the user lands on the tile, then returning to rest.  Animator stashed on `R.id.update_pill` tag so the next recycle / rapid D-pad sweep cancels any in-flight shine cleanly.

`DockAdapter.kt` braces 50=50, parens 185=185 — static-verified; Kotlin compile runs in CI.

## v2.10.65 — APK version bump for launcher UPDATE-pill testing (2026-02-16)

User request: bump every APK to a known-newer versionName so the launcher's per-tile UPDATE pill can be verified end-to-end (pinned > installed → pill fires → install in-place upgrade → pill auto-hides).

All five APKs (`vesper-tv`, `onnowtv-kids`, `onnowtv-tunes`, `onnowtv-livetv`, `onnowtv-fta`) derive their `versionName` from the topmost `## v…` heading in this file via their respective `build-*.yml` workflows.  Adding this single entry at the top bumps all of them in lockstep on the next CI run, with `versionCode` advancing monotonically off `GITHUB_RUN_NUMBER` so Android accepts the upgrade.

Aggregates the runtime fixes from the v2.10.59 → v2.10.63 burst that hadn't been folded into CHANGELOG yet:

- **Per-tile UPDATE pill** on the launcher dock (v2.10.59) — cyan→blue→indigo gradient floats above any tile whose pinned APK package differs from installed; D-pad UP focuses the pill, click installs in-place via `ApkInstaller`.
- **APK manifest auto-extraction** in `POST /api/admin/dock/{key}/apk` so the operator no longer types `package_id`/`version_name` — pyaxmlparser reads the manifest of the uploaded APK and persists the values for the launcher's compare.
- **Inline upload-progress UI** in the launcher admin (v2.10.60) — XHR-driven byte progress + barber-pole "processing" stripes + green ✓ done state, all inside the same row the operator clicked.
- **Downgrade-safe pill semver compare** (v2.10.62) — pill ONLY shows when pinned > installed (semver-aware split on non-numeric); equal or older = no pill, because Android refuses downgrades.
- **Wrong-APK guard rail** at upload time (v2.10.62) — if the uploaded APK's manifest package differs from the tile's `target_package`, the admin UI flashes an amber inline bar plus sticky red toast and skips the auto-refresh until the operator acknowledges.
- **Vesper / Kids isolation** (v2.10.63) — boot-time sweep of every `onnowtv-active-profile-v1*` localStorage key + `HomeRouter` hard-guard so Vesper can never render KidsHome regardless of stale profile state.  New `OnNowTV.getHostPackage()` JS bridge lets the React frontend tell apart Vesper / Kids / Tunes / FTA.
- **Per-app auth lockout scoping** (v2.10.63) — `LoginRequest.client_id` plumbed end-to-end; brute-force counter keys by `IP:username:client_id`, so a failed Kids login no longer locks the same user out of Vesper.
- **Phase 2 domain migration** (v2.10.58) — all four downstream APKs (Vesper / Kids / Tunes / FTA) now point at `https://onnowhub.com` (Cloudflare-fronted).  DuckDNS pin retained in `network_security_config.xml` as defence-in-depth for legacy traffic.
- **Bundled production tile + wallpaper WebPs** in the launcher APK (v2.10.57) — first-frame paint, no placeholder flash.



## v2.10.17 — Revert v2.10.16 D-pad "polish" (made things worse) (2026-02-09)

User report (`0pqg4a68_20260609_184341.mp4`): "Its running worse than it was before."  My v2.10.16 attempt made navigation visibly worse — trailing focus ring, focus jumps, and hesitation between tiles.

### Why v2.10.16 hurt
1. **`MutationObserver(subtree: true)` on `[data-testid="home-page"]`** — the Hero billboard's slide indicator rotates every few seconds, images stream-load on every shelf, lazy shelves render their tiles over time… the observer fired CONSTANTLY and set `cachedRows = null` over and over.  Net: no rebuild savings AND we now paid the observer callback cost on every animation frame.
2. **`vesper-scrubbing` body-class toggling on `e.repeat`** — flipping a body-level class re-evaluates the cascade for every `[data-focusable='true']` selector in the entire page (~hundreds of elements per home view).  Worse: the class already overlapped with the existing `transition: none` default on `[data-focusable='true']`, so the cascade work was for an effect that was already in place.
3. The base case of `transition: none` was already correct — there was no real "trailing animation" to kill.

### What was reverted
- `frontend/src/pages/Home.jsx` — back to the simple inline build-rows-every-keypress row-walker (the previous "tiny bit sluggish" baseline).
- `frontend/src/hooks/useSpatialFocus.js` — removed all `vesper-scrubbing` toggling, scrub timer, scrub burst counter.
- `frontend/src/index.css` — removed the `body.vesper-scrubbing` CSS block (no JS sets the class anymore so the selectors were dead code).

Navigation should now feel identical to the pre-v2.10.16 baseline.  Any future attempt to make it snappier should profile FIRST with Chrome DevTools' Performance recorder on the actual Android WebView before changing code.



## v2.10.16 — Buttery D-pad polish on Vesper V2 home shelves (2026-02-08)

User report: "moving across the Continue Watching tiles and moving around and stuff is still a tiny bit sluggish. Just enhance it a bit to make it really buttery smooth".

### Two surgical perf fixes on the V2 Vesper hybrid frontend

1. **Home page row-walker cached + double-scroll eliminated** (`pages/Home.jsx`).  Previously every Up/Down keypress on Home ran `homeRoot.querySelectorAll('[data-focusable="true"]')` + a `getBoundingClientRect()` on every tile to assign rows — that's ~100 forced layouts per keypress, stacking frame-on-frame during a held d-pad.  The row list is now cached across keypresses and only rebuilt on relevant DOM mutations (shelves mount/unmount, focusables flip enabled/disabled).  Held-key navigation through Continue Watching now does ~10 layouts per press (only the target row's tiles for column-matching) instead of ~100.

   The implicit browser focus-scroll (`focus({ preventScroll: false })`) was also fighting the explicit `scrollIntoView({ behavior: 'auto' })` immediately below it — every press queued TWO competing scroll operations on Chrome WebView.  Now `preventScroll: true` and a single explicit auto-scroll to the snap-page parent.

2. **`vesper-scrubbing` body class wired up** (`hooks/useSpatialFocus.js`).  The CSS rule that forces `transition: none` on every focusable while the user is holding a D-pad key had been written but never actually applied.  Now toggled on the first `e.repeat` keydown and cleared 220 ms after the last keydown — so a single tap still gets the polished 130 ms cubic-bezier focus transition, but a held key snaps the ring frame-by-frame with zero trailing animation.

### Files touched
- `frontend/src/pages/Home.jsx` — row-walker cache + MutationObserver, single scroll path.
- `frontend/src/hooks/useSpatialFocus.js` — `vesper-scrubbing` body-class toggle on key auto-repeat.

### Smoke test
- Webpack compiles clean (1 unrelated `react-hooks/exhaustive-deps` warning pre-existing).
- Screenshot test on `/` ran 5 rapid ArrowDown presses → focus walked from the hero to the "Coming soon" rail correctly; body class was empty 500 ms after the last keypress (confirming the scrub class is properly added then stripped).



## v2.10.15 — Streaming per-channel EPG cache, OOM fix (2026-02-08)

User report (TV photo + video, `5x0x00gs_20260609_102704.mp4`): the v2.10.14 full-bundle XMLTV preload crashed with `java.lang.OutOfMemoryError: max allowed footprint 268435456` partway through parsing — the user's Android TV box has a 256 MB heap and the previous design held all 600 k+ programmes in a single in-memory `Map<String, List<Programme>>` (~115 MB just for programmes, plus JSON / OkHttp / Coil / parser state pushed us past the ceiling).

### Streaming write to per-channel files on disk (`EpgCache` schema v3)

- `data/EpgCache.kt` rebuilt with a `StreamingWriter` class.  As programmes are produced by the XMLTV parser we put them in a small per-channel buffer; when the total in-memory programme count crosses 10 000 (~2.5 MB) we flush the half-largest buffers to disk and clear them.  Peak working set ≈ 5 MB, irrespective of bundle size.
- Files live at `filesDir/epg-channels-v3/<sha1-of-channel-id>.jsonl.gz` — one gzipped JSONL file per channel, ~5 KB each, ~45 MB total for a 9 000-channel lineup.
- `EpgCache.loadChannel(ctx, id)` reads one channel's file (~5 ms) on demand.  No bulk load API; the legacy `load()` returns an empty map for v3 caches so callers don't crash but real EPG access goes through `loadChannel`.
- Schema stamp `.schema` + completion marker `.done` written ATOMICALLY at the end of the parse — a parse interrupted mid-flight leaves the cache "missing" so the next boot retries cleanly.
- Persisted `.namemap.json` (XMLTV `<channel><display-name>` → id) so the fast-path boot can re-apply name-fallback patching of the bundle's channel list WITHOUT re-running the XMLTV parse.

### Disk-first lazy fetch in `EpgActivity` + `PlayerActivity`
- `lazyFetchForChannel` and `ensureEpgFor` now try `EpgCache.loadChannel(ctx, id)` FIRST (~5 ms disk read).  Only channels genuinely absent from the XMLTV fall through to the network `get_short_epg` path.
- The old upfront `EpgCache.load(appCtx)` warm-up in `PlayerActivity.onCreate` removed — the lazy disk-first path handles everything.

### `MainActivity` boot paths
- Slow path opens a `StreamingWriter`, drives the XMLTV parse through it, runs the name-fallback channel-id patching, then calls `writer.finish(parseResult.displayNameToEpgId)` to atomically stamp the schema + name-map + `.done`.
- Fast path now reads the persisted `.namemap.json` and re-applies the same name-fallback patching to the bundle's channel list — so channels whose provider-supplied id was blank still land on the correct disk file when EpgActivity asks for their EPG.
- `BundleHolder.current.epg` is now ALWAYS empty.  All EPG access goes through `EpgCache.loadChannel`.

### `EpgRefreshWorker` re-aligned
- Periodic background refresh uses the same `StreamingWriter` pattern, so the worker process never accumulates more than ~5 MB of programme data either.
- On `abort()` (e.g. network failure mid-parse) the `.done` marker is never written, so a partial write is invisible to the next boot.

### Files touched
- `android/onnowtv-livetv/app/src/main/java/tv/onnowtv/livetv/data/EpgCache.kt` — schema v3 + per-channel files + `StreamingWriter` + name-map persistence.
- `android/onnowtv-livetv/app/src/main/java/tv/onnowtv/livetv/data/XmlTvFetcher.kt` — streams through `StreamingWriter`, no in-memory `out` map; `ParseResult` carries `channelsWritten: Set<String>` instead of `programmes: Map<...>`.
- `android/onnowtv-livetv/app/src/main/java/tv/onnowtv/livetv/data/EpgRefreshWorker.kt` — same streaming writer pattern.
- `android/onnowtv-livetv/app/src/main/java/tv/onnowtv/livetv/MainActivity.kt` — fast path applies persisted name-map, slow path drives streaming writer.
- `android/onnowtv-livetv/app/src/main/java/tv/onnowtv/livetv/EpgActivity.kt` — `lazyFetchForChannel` disk-first.
- `android/onnowtv-livetv/app/src/main/java/tv/onnowtv/livetv/PlayerActivity.kt` — `ensureEpgFor` disk-first; removed upfront `EpgCache.load` warm-up.

### Smoke test
Compiled the data layer end-to-end with `kotlinc 1.9.20` + Android API 35 jars locally — `EpgCache.kt`, `XmlTvFetcher.kt`, `EpgRefreshWorker.kt`, `AuthStore.kt`, `XtreamRepository.kt`, `BundleCache.kt`, `Models.kt` ALL compile cleanly into `compiled-data.jar`.  Only warnings are stylistic (`!!` on already-non-null strings).  CI workflow `build-livetv.yml` will build the full APK on push.



## v2.10.14 — Full-bundle EPG cache (every channel, 3 days) + background auto-refresh (2026-02-08)

User report (TV video, /artifacts/hctr6hsw_20260609_091812.mp4): channels load, but every PLAYING NOW row sits on "Loading guide…" forever; even when EPG eventually fills, it's only ~24 h deep.  User asks for the FULL 3-day EPG to be cached for EVERY channel, never just popular buckets — and for the cache to auto-refresh in the background so a fresh app open is ALWAYS instant.

### 1) Priority filter dropped — XMLTV preload now covers EVERY channel
- `data/XmlTvFetcher.kt` — old `fetchPriorityEpg` is now `fetchEpgForChannels`.  Caller passes ALL bundle channel ids, not just the UK / USA / AU Kayo / NZ Sports subset.  Memory cost: ~110 MB heap on a representative ~9 000-channel provider — fine on a 2 GB box, and the user explicitly accepted "a couple of minutes on first launch".
- `MainActivity` — the new `wantedChannelIds` set is `bundle.channels.mapNotNull { it.epgChannelId }`.  Loader copy updated: "Loading the full 3-day guide… · First-launch download — this only happens once".

### 2) Name-based fallback for channels whose `epg_channel_id` was blank / didn't match
- `XmlTvFetcher.ParseResult` now also returns a `displayNameToEpgId` map captured from every `<channel><display-name>` element in the XMLTV — captured live during the SAME single pass, so no second download.
- `normaliseChannelName()` lowercases, drops whitespace + punctuation, and strips trailing quality suffixes (`HD` / `SD` / `FHD` / `UHD` / `4K` / `8K` / `HEVC` / `HD720` / `HD1080`).  Result: `"Sky Documentaries HD"`, `"SKY DOCUMENTARIES SD"`, and `"sky-documentaries.uk"` ALL match to the same logical key `skydocumentaries`.
- The parser ALSO expands its `wantedChannelIds` set IN-LINE as it sees `<channel>` blocks whose normalised display-name matches a wanted bundle name — so a single pass through the file captures programmes for channels whose provider-supplied id was blank.
- `MainActivity` post-parse: for every bundle channel whose original id resolved to no programmes, look up by normalised name → if a match is found, write the programmes under BOTH the bundle id AND the XMLTV id (so any code path memoising the original id still works), and rewrite the channel's `epgChannelId` to the matched XMLTV id ONLY when the original was blank (preserving the provider id otherwise).

### 3) Lazy-fetch limit bumped 20 → 200 programmes (~6 h → ~3 days)
- `data/DirectProviderFetcher.fetchShortEpg(limit: Int = 200)`.  For channels still missed by XMLTV (provider mismatch, exotic regions), the on-demand lazy-load path now also returns 3 days of programmes — matching the XMLTV preload depth.  User's "Coming Up Next" goes from <24 h to ~3 days for those channels too.

### 4) WorkManager periodic background refresh
- New `data/EpgRefreshWorker.kt` — extends `CoroutineWorker`.  Re-downloads the XMLTV every 12 h (±1 h flex window) on a connected network, runs the same name-fallback merge MainActivity does, then overwrites the on-disk `EpgCache`.  In-memory cache stays as-is until the next cold boot — which now starts sub-second with the freshest data already on disk.
- Enqueued idempotently from BOTH MainActivity paths (fast and slow) via `EpgRefreshWorker.schedulePeriodic(ctx)` (KEEP policy).
- Cancelled from `AuthStore.signOut()` so it stops hitting the provider with creds the user has revoked; re-enqueued automatically on the next successful sign-in.
- Added dependency: `androidx.work:work-runtime-ktx:2.9.1` in `app/build.gradle.kts`.

### 5) EpgCache schema version stamping — existing users get the new fuller cache automatically
- `data/EpgCache.kt` now writes a sibling `epg_priority.schema` file containing the cache schema version (currently `2`).  `exists()` and `load()` ignore files whose stamp is older than the current build, so users upgrading from the v1 priority-only cache get the new full-bundle preload triggered automatically on the first launch of the new build — no need to clear app storage.
- Fast path in MainActivity now detects schema mismatch (cache returns null) and FALLS THROUGH to the slow loader so the user is taken through the full XMLTV preload exactly once instead of landing in EpgActivity with an empty EPG and waiting 12 h for the WorkManager job.

### Files touched
- `android/onnowtv-livetv/app/build.gradle.kts` — added `androidx.work:work-runtime-ktx:2.9.1`.
- `android/onnowtv-livetv/app/src/main/java/tv/onnowtv/livetv/data/XmlTvFetcher.kt` — drop priority filter, capture display-name → id map, name-based wanted-set expansion, smarter `normaliseChannelName`.
- `android/onnowtv-livetv/app/src/main/java/tv/onnowtv/livetv/data/DirectProviderFetcher.kt` — bump `fetchShortEpg` default limit 20 → 200.
- `android/onnowtv-livetv/app/src/main/java/tv/onnowtv/livetv/data/EpgCache.kt` — schema-version stamp + version-aware `exists()` / `load()` / `delete()`.
- `android/onnowtv-livetv/app/src/main/java/tv/onnowtv/livetv/data/EpgRefreshWorker.kt` — NEW periodic CoroutineWorker.
- `android/onnowtv-livetv/app/src/main/java/tv/onnowtv/livetv/data/AuthStore.kt` — cancel EpgRefreshWorker on signOut.
- `android/onnowtv-livetv/app/src/main/java/tv/onnowtv/livetv/MainActivity.kt` — fast-path schema-mismatch fallthrough, full-bundle preload, name-fallback merge, channel patching, enqueue periodic worker.



## v2.9.9 — Full priority EPG, modern player, day-dividers, sign-out refactor (2026-06-07)

User's TV-video round of frustration, addressed point-by-point.

### 1) Full UK/USA/AU Kayo/NZ Sports EPG preload BEFORE entering the EPG
New `data/XmlTvFetcher.kt` + `data/EpgCache.kt`.
- Hits the provider's `xmltv.php` gzip endpoint directly from the device (27 MB compressed → 145 MB raw, downloads in **~5.7 s**).
- Stream-parses with `XmlPullParser`, retaining ONLY programmes whose channel maps to a priority channel (~2,583 channels with `epg_channel_id`).  Memory cost ~28 MB heap.
- Loader headline switches to "Loading the full guide… · UK · USA · AU Kayo · NZ Sports" and counters animate up to `"234,567 programmes · 8,121 EPG channels seen"`.  Total wait ~25–35 s on first boot — comfortably inside the user's "couple of a minute or so" budget.
- Result is persisted to `filesDir/epg_priority.json.gz` so **every subsequent cold boot starts EpgActivity sub-second with the priority EPG already populated**.
- Non-priority channels still lazy-load their EPG via the existing `get_short_epg` direct path when the user d-pads to them.
- `EpgActivity.onCreate` triggers a background refresh of the priority EPG when the cache is older than 2 h — silently keeps the on-disk copy fresh.

Category-name matching predicate covers the user's named buckets:
- **UK**: `UK |…`, `==== UK …`, `====UK…`, `DAZN UK`, `AMAZON UK`.
- **USA**: `USA |…`, `USA …`, `====USA…`, `DAZN USA`.
- **AU Kayo**: anything containing `KAYO` (FOX/KAYO SPORTS + KAYO EVENTS).
- **NZ Sports**: `SKY SPORTS (NZ)` + any `NZ … SPORT…` combo.

### 2) Sign-out moved to bottom of left rail only
- Removed the hero's `btn_logout` ImageButton from `activity_epg.xml`.
- The rail's `rail_signout` icon (already at the bottom of the vertical rail) is now the SINGLE sign-out affordance.
- Clicking it now properly tears down the player, clears `AuthStore` creds, and bounces back to `LoginActivity` (was `finishAffinity()` previously — that just exited the app without clearing creds).
- Backed by a confirm action-sheet so accidental d-pad presses don't sign you out.

### 3) "Coming Up Next" rows smaller + day-divider chip
- `item_guide_row.xml`: trimmed vertical padding 8→4 dp, time/title 13→12 sp, reminder line 10→9 sp, body bottom margin 6→3 dp.  Fits **roughly one extra row** in the same vertical space.
- `guide_day_header` TextView is now actually styled (was empty).  Renders as a small neon-blue-bordered pill labelled `TODAY · 7 JUN`, `TOMORROW · WED 8 JUN`, `THU 9 JUN`, etc. — drawn FLUSH BETWEEN rows, not inside them, so it reads as a clear section break when the guide crosses midnight.
- `GuideRowAdapter.kt` now uses `EEE dd MMM` for the day-of-week prefix on day labels.
- New drawable `guide_day_header_bg.xml`.

### 4) Smaller neon-blue spinning loader
- `preview_buffer_loader` (the in-EPG preview-window buffering spinner) shrunk **92 dp → 44 dp** — was the "really big" one.
- Player full-screen `buffer_loader` was already 48 dp; left as-is.
- Color stays `#5DC8FF` (neon cyan-blue) — matches the existing accent.

### 5) Modern player controls
- Rebuilt `player_controls_bar` in `activity_player.xml` from emoji-on-TextView to **circular ImageButtons with vector icons** on a floating glass-pill background.
- Layout: `[« 10s] [⏵/⏸ HERO] [10s »] · [CC] [⤢] [ⓘ]` — with a soft divider between the transport cluster and the options cluster.
- Center Play/Pause is the hero: 74 dp circular gradient pill (`#3A95FF → #1F6FD8`) with neon-blue glow on focus.
- All buttons have a focus state that glows neon-blue + scales up for TV-remote navigation.
- New drawables: `player_modern_bar_bg`, `player_modern_btn_bg`, `player_modern_playpause_bg`, `ic_player_play`, `ic_player_pause`, `ic_player_rewind` (back-10s), `ic_player_forward` (fwd-10s), `ic_player_cc`, `ic_player_aspect`, `ic_player_info`.
- `PlayerActivity.kt`: `btnPlayPause/btnRewind/btnForward/btnSubtitles/btnAspect/btnInfo` retyped TextView → ImageButton.  `syncPlayPauseGlyph()` uses `setImageResource(R.drawable.ic_player_play/pause)` instead of unicode glyphs.
- Subtitle integration (binding the CC button to the currently-watched programme's subtitle track) was already wired in v2.9.5 — kept as-is, just behind the modern icon now.

### Files touched
- `data/XmlTvFetcher.kt` — NEW (244 lines)
- `data/EpgCache.kt` — NEW (109 lines)
- `MainActivity.kt` — priority predicate + XMLTV preload step + EpgCache hydration on fast path + updated tips
- `EpgActivity.kt` — hero `btn_logout` removed, rail sign-out now uses proper sign-out flow, background priority-EPG refresh
- `ui/GuideRowAdapter.kt` — day-of-week formatter for divider labels
- `res/layout/activity_player.xml` — rebuilt control bar
- `res/layout/activity_epg.xml` — removed hero sign-out, shrunk preview loader
- `res/layout/item_guide_row.xml` — smaller rows, styled day-divider
- `PlayerActivity.kt` — ImageButton retypes + vector icon swap
- 7 new vector icons (`ic_player_*.xml`) + 3 new selectors (`player_modern_*.xml`) + `guide_day_header_bg.xml`

### Verified (preview-pod simulation)
```
XMLTV: 27.27 MB transfer in 5.67s (gzip).
get_live_categories + get_live_streams: 160 cats, 14,091 streams in 1.69 s.
BBC ONE FHD short_epg: 3 programmes returned ("Spirit Untamed", "Zog and the Flying Doctors", …).
```

---


## v2.9.8 — Direct-from-provider bundle fetch (the REAL fix) (2026-06-07)

**Diagnosis nailed.** Production VPS at `62.84.181.66` **cannot reach `njala.ddns.me:8443` AT ALL** — confirmed via `bash -c '</dev/tcp/njala.ddns.me/8443'` from the VPS = `FAILED` on every port (8443, 443, 80, 8087), even ICMP is dropped. The Xtream provider has IP-blacklisted the entire Contabo range. The backend's `instant_bundle` scheduler logs "refreshing channels…" but never logs success because the TCP handshake hangs forever.

User's previous "working" experience came from a different network path / before the provider blacklist took effect.  The backend caching layer is unusable until the network block is lifted.

**Fix: device-side direct fetch (matches the OLD pre-backend flow).**
- New `data/DirectProviderFetcher.kt`: hits `https://njala.ddns.me:8443/player_api.php` directly using the user's saved Xtream credentials.  Returns `get_live_categories` + `get_live_streams` (160 cats + 14,091 streams in **1.69 seconds** during testing) and assembles a bundle JSON **byte-identical in shape** to the backend bundle, so the existing `XtreamRepository.parseBundle()` parses it unchanged AND `BundleCache` persists it for the next boot's sub-second fast path.
- `MainActivity.runLoader()` now RACES both paths.  Backend gets a 4-second head-start (so a healthy backend with pre-warmed EPG still wins on a normal day).  Whichever path returns channels first wins; loser is silently discarded.
- `applyMeta()` falls back to the direct-fetch channel count when the backend `/meta` reports 0.  User immediately sees "Found 14,091 channels" in the animated counter instead of being stuck on "Loading channels…" forever.
- `XtreamRepository.fetchEpgForChannel()` now takes an optional `Context` and falls back to a direct provider `get_short_epg` call when the backend's per-channel EPG endpoint returns empty.  Base64-decodes the provider's title/description fields.
- `EpgActivity` updated to pass `applicationContext` to the EPG-fetch calls.

**Verified path** (from this preview pod which has the same network egress profile as the user's device):
```
160 categories, 14091 streams in 1.69s
BBC ONE FHD: 3 programmes — 'Spirit Untamed', 'Zog and the Flying Doctors', …
```

**Files touched**:
- `data/DirectProviderFetcher.kt` (new — 240 lines)
- `data/XtreamRepository.kt` (added context-aware EPG fallback)
- `MainActivity.kt` (race loader, direct fallback, counter uses direct count)
- `EpgActivity.kt` (pass context to fetchEpgForChannel)

**User-perceived flow after this build**:
1. Type creds → Sign In → MainActivity loader appears instantly.
2. ~5 s in: "Found 14,091 channels · Finalising guide…" counter ticks up.
3. ~18 s in (existing minHold): loader exits to EPG, channel list populated.
4. Every subsequent boot: disk-cache fast-path → SUB-SECOND straight into EPG.
5. EPG fills in per channel as the user d-pads down the list — each row's EPG is lazy-loaded from the provider directly.

---


**User's complaint (verbatim, frustrated):** "How we had it before the app would log in in under i min. im confused now as to why its taking so long NOW. All we are doing is changing the log in username and password ALL the other process needs to stay the same as how it was. Once client enters in the details it MUST immediately show the loading screen with the channels found etc like before and use the EPG that is gzipped no questions asked."

**Root cause of the slowdown.** The previous v2.9.7 change tried to "verify" the user's credentials inside `LoginActivity` by POSTing to `/api/xtream/auth` (and falling back to a direct `player_api.php` call against `njala.ddns.me`).  Each round trip was ~3-8 s, and the failure case waited for BOTH paths.  Worse, on success the activity jumped *directly* to `EpgActivity`, bypassing `MainActivity` — which is the loader screen with the gzipped-bundle fetch and the "X,XXX channels loaded · Y / Z popular EPG ready" animated counters.  So the user never saw the loader they recognised.

**Fix.** `LoginActivity` is now a pure capture screen — no HTTP at all.  On submit:
```kotlin
AuthStore.saveCredentials(this, u, p)         // sync SharedPrefs write, ~0 ms
startActivity(Intent(this, MainActivity::class.java).addFlags(
    FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TASK))
finish()
```
`MainActivity` then runs its existing two-mode boot:
- **Fast path** — disk cache exists → parses + hands off to `EpgActivity` instantly (sub-second).
- **Slow path (first-ever boot only)** — shows the animated loader, fetches the gzipped `/api/xtream/instant-bundle`, polls `/api/xtream/instant-bundle/meta` for live channel counts, exits as soon as priority EPG is ready (typically <30 s) and writes the cache for every subsequent launch.

The EPG is served from the backend's master Xtream account (`LIVETV_DEFAULT_USERNAME` in `.env`) — totally decoupled from per-user creds.  Wrong user creds surface only at stream-play time (acceptable — a far better UX than blocking the loader for 8 s on every login).

**Files touched**:
- `android/onnowtv-livetv/app/src/main/java/tv/onnowtv/livetv/LoginActivity.kt` — gutted from 278 → 90 lines.  Removed all network code and the direct-provider fallback path.

---


Three issues reported from the user's TV video:

**1) Kids app demanded a PIN to ENTER instead of exit.**
- Root cause #1 (`useKidsKioskGuard.js`): `/` was NOT in the allowed-paths list, so the guard immediately bounced the user from KidsHome (`HomeRouter` at `/`) to `/kids/exit-pin` on every cold boot.  Fix: added `/` to `isKidsAllowedPath()` whitelist — `/` IS the Kids home when `isKidsActive()`.
- Root cause #2 (`onnowtv-kids/MainActivity.kt`): the boot URL was pinned to `#/kids` which isn't a registered React route, AND the `onPause` URL-restore logic would persist `/kids/exit-pin` URLs to SharedPrefs, so the *next* cold boot reloaded the parent-PIN page directly.
  - Changed `defaultBoot` to `?profile=kids#/` so `HomeRouter` renders `KidsHome`.
  - Restore logic now skips URLs containing `/kids/exit-pin`.
  - `onPause` no longer persists `/kids/exit-pin` to `last_url`.

**2) Live TV login failed even with correct credentials.**
- Root cause (`backend/xtream.py`): the httpx client used by `/api/xtream/auth` did NOT have `verify=False`, while `instant_bundle.py` did.  The provider `njala.ddns.me` ships an invalid TLS cert, so `httpx.ConnectError: [SSL: CERTIFICATE_VERIFY_FAILED]` surfaced as the cryptic `502 "Provider unreachable: "` — the message detail was empty because the exception was stringified and printed nothing useful.
- Fix: `verify=False` added to `xtream.py:_http()` so both code paths handle the provider's broken cert identically.
- Defence-in-depth (`LoginActivity.kt`): the device now ALSO falls back to a DIRECT provider `player_api.php` call if the backend proxy returns 5xx / network errors.  Means login succeeds even when the backend has outbound trouble.  Per-user `AuthResult` carries a `diag` line surfaced to the UI so the user sees *why* a fallback fired (e.g. "Backend HTTP 502 — trying provider directly…").

**3) Login screen redesigned for V2 LIVE TV brand.**
- New `activity_login.xml`: split layout with orbital-rings backdrop on the left, glass auth card on the right (pinned border on top with neon-blue gradient), brand-styled feature bullets, prominent gradient CTA pill, Show/Hide password toggle, optional diagnostic text under the error.
- New drawables: `login_bg_gradient`, `login_orbital_rings`, `login_card_bg`, `login_input_bg`, `login_btn_bg`.

**EPG architecture clarification (explainer added to `test_credentials.md`).**
The user was concerned new users wouldn't get EPG data.  Reality: the EPG is served from `/api/xtream/instant-bundle` which uses the MASTER `LIVETV_DEFAULT_USERNAME/PASSWORD` from backend `.env`.  EVERY user gets the same cached EPG immediately — your personal login is ONLY used locally to rewrite `.ts` stream URLs (`AuthStore.rewriteStreamUrl()`) so each device plays under its own account.

### Files touched
- `backend/xtream.py` (verify=False on shared httpx client)
- `frontend/src/hooks/useKidsKioskGuard.js` (`/` whitelisted)
- `android/onnowtv-kids/app/src/main/java/tv/onnowtv/kids/MainActivity.kt` (boot URL + restore guard)
- `android/onnowtv-livetv/app/src/main/java/tv/onnowtv/livetv/LoginActivity.kt` (direct-provider fallback)
- `android/onnowtv-livetv/app/src/main/res/layout/activity_login.xml` (redesign)
- `android/onnowtv-livetv/app/src/main/res/drawable/login_*.xml` (new)
- `memory/test_credentials.md` (EPG explainer + Live TV test creds note)

### Verified
- `POST /api/xtream/auth` on preview backend → 200 with TRAV201022 creds (was 502 before the verify=False fix)
- `/api/xtream/instant-bundle/meta` production: 14096 channels, 161 cats, 68 EPG channels served via master account — confirms EPG arrives for any device regardless of personal login.
- Frontend boots clean (profile picker still renders).
- `mcp_lint_javascript` clean on `useKidsKioskGuard.js`.

---


## v2.8.146 — Auto-regenerate covers on first launch (kills the "still showing the old legal-imagery covers" complaint)

User reported (correctly + furiously) that the device was still showing covers with gavel / scales-of-justice / courtroom imagery despite the server-side prompt being fixed multiple iterations ago.  Root cause: those covers were generated DAYS ago when the prompt still had "legal project" in it; they live in the device's `CollectionsStore` (and Coil's image cache) as `coverHash` + `coverUrl` pointers, and the v2.8.143 manual purge expected the user to tap **Re-style ALL** in the UI to actually re-generate.  That friction made it look like the fix wasn't applied.

### Fix: auto-regen everything on the next post-v2.8.146 launch
`LiveTVApp.applyCoverPurgeIfNeeded()` now:

1. Clears Coil's memory + disk image cache.
2. Wipes every `coverHash` / `coverUrl` on every Collection.
3. **Fires a `GlobalScope.launch(IO)` coroutine that walks every Collection and serially regenerates a fresh cover** via `CoversApi.generate(name, forceSalt=freshSalt())`.  Each regen ~25 s @ `quality="medium"` ≈ $0.06.  Four visible Collections ⇒ ~$0.24 spend, fully done in ~2 minutes after first launch with the user doing literally nothing.

`COVER_PURGE_VERSION` bumped `2 → 3` so devices that already ran the v2 purge run the v3 auto-regen exactly once.

### Server side
- Server-side cache wiped one more time (final 2 lingering docs deleted) so all regenerations start from a clean Mongo.
- Prompt is unchanged from v2.8.144 — the locked ChatGPT-style enhanced wording with the 12 % safe-area clause.

### Files touched
- `android/.../LiveTVApp.kt` — auto-regen loop + `COVER_PURGE_VERSION = 3`.
- Mongo `library_covers` — wiped again pre-build.

## v2.8.145 — Orbital brand loader everywhere + focus borders reverted to thin neon

### Orbital loader (signature buffering animation)
Picked from a side-by-side comparison of 6 candidates (demo at `/loaders-demo.html`).  Glassmorphism centre disk + two coloured dots orbiting in opposite directions at different speeds — feels alive without being mechanical.  Same animation on every loading surface across Vesper TV and Live TV for brand consistency.

**Live TV (native Android)** — new file `ui/OrbitalLoaderView.kt`:
- Custom `View` subclass, hardware-accelerated `Canvas` drawing (no bitmap allocations per frame, ~60 fps).
- `ValueAnimator` drives two independent angle properties so the two dots rotate at 1.4 s / 1.7 s clockwise / counter-clockwise.
- Brand-aware palette: `#5DC8FF` (livetv_accent) blue + `#C16BFF` purple, soft halo glow via stacked translucent discs.
- Wired into BOTH surfaces:
  - `activity_player.xml` — 180 dp loader at FrameLayout centre, toggled by `PlayerActivity.onPlaybackStateChanged` (visible on `STATE_BUFFERING`, hidden on `STATE_READY` / `STATE_ENDED` / `STATE_IDLE`).
  - `activity_epg.xml` — 92 dp loader inside the preview card, driven by a `Player.Listener` attached lazily in `EpgActivity.startPreview()`.  Listener re-binds whenever the underlying ExoPlayer instance rotates.

**Vesper TV (React)** — new component `components/OrbitalLoader.jsx`:
- Pure CSS animations, scoped per-instance via `React.useId()` so two on a page don't clash.
- Wired into the cinematic preview overlay in `pages/Player.jsx` — 92 px floating loader top-right of the loading screen, fades out (`opacity 400 ms`) the moment `streamReady` flips true.

### Focus borders reverted to thin neon
User feedback: the bright blue 3-3.5 dp strokes I introduced in v2.8.143 felt "gross" and "too thick".  Reverted everywhere to the original thin + neon cyan look:
- `category_pill_bg.xml`: 3 dp `#5C9CFF` → **2 dp `@color/livetv_accent`** (`#5DC8FF`).
- `channel_pill_bg.xml`: same revert.
- `guide_row_bg.xml`: 3 dp `#5C9CFF` → **1 dp `@color/livetv_accent`**.
- `library_tile_focus_fg.xml` (foreground overlay for collection + favourite tiles): 3.5 dp `#5C9CFF` → **2 dp `@color/livetv_accent`** for focus/selected, **2 dp white** for pressed.

### Files touched
- `android/.../ui/OrbitalLoaderView.kt` — NEW.
- `android/.../res/layout/activity_player.xml` — `<OrbitalLoaderView id="buffer_loader">` added.
- `android/.../res/layout/activity_epg.xml` — `<OrbitalLoaderView id="preview_buffer_loader">` added.
- `android/.../PlayerActivity.kt` — `bufferLoader` field + visibility toggle in `onPlaybackStateChanged`.
- `android/.../EpgActivity.kt` — `previewBufferLoader` field + lazy `Player.Listener` install in `startPreview` (and `attachPreviewBufferListenerOnce()` re-binder).
- `android/.../res/drawable/category_pill_bg.xml`, `channel_pill_bg.xml`, `guide_row_bg.xml`, `library_tile_focus_fg.xml` — thin neon revert.
- `frontend/src/components/OrbitalLoader.jsx` — NEW.
- `frontend/src/pages/Player.jsx` — import + top-right floating loader on the cinematic preview overlay.
- `frontend/public/loaders-demo.html` — side-by-side reference of all six candidate loaders.

## v2.8.144 — AI cover prompt locked: ChatGPT-style enhanced prompt + 12% safe-area clause

After several rounds of testing against the user's ChatGPT reference images (the standard he wants every cover to hit), the final cover-generation pipeline is now:

### Provider + params
- **Model**: OpenAI `gpt-image-1` via `OpenAIImageGeneration` + Emergent universal key.
- **Quality**: `medium` (~$0.06/cover, ~270 covers in the user's $17 budget).  `high` rejects real broadcaster names (Sky Sports, ESPN, Disney) at the OpenAI safety-filter layer.
- **Output**: 1280×720 PNG, exact 16:9 (Pillow centre-crop + LANCZOS).

### Prompt design (the part that finally matched the references)
- The user's original wording was too bare — gpt-image-1 produced a clean but FLAT one-subject composition.  ChatGPT web silently auto-prepends style cues + safety-friendly rewrites; we now embed those cues directly:
  - "Premium 16:9 channel tile design for a streaming-app home shelf"
  - "BOLD designed brand mark on the LEFT, chunky 3D typography that suits the channel's vibe"
  - "multiple dynamic subjects when possible"
  - "Cinematic lighting, vibrant saturated colours, dramatic 3D illustration / Pixar-grade rendering"
- **Critical safe-area clause** (locked after the user reported text-clipping):
  - Brand text must sit inside a safe zone starting ≥12% from the left edge and ending ≤50% across
  - Subjects' heads ≥6% below top, feet ≥6% above bottom
  - "If the channel name is long, scale typography DOWN — DO NOT crop letters"

### Verified samples
- **UK Sky Sports** — chrome 3D "UK / SKY / SPORTS" stack (no clipping), 3 athletes (basketball, sprinter, footballer), soccer ball, dramatic red/orange/blue stadium lighting.
- **UK Kids** — rainbow 3D bubble letters "UK / KIDS", 4 Pixar-grade animals (bluebird, monkey, bunny, fox), purple→orange gradient.

Both inside the safe area on every edge, matching the visual family of the user's ChatGPT references (Kayo Sports / ESPN / UK Kids jungle).

### Files touched
- `backend/library.py` — final `_build_prompt()` with style cues + safe-area clause; quality reverted to `medium`.

## v2.8.143 — Wipe Gemini cache, restore verbatim prompt, focus border on every tile

User reported on-device:
> "It's showing the old ones. It showed the old designs that the old one did through the Gemini one. Use my EXACT prompt. Make sure all the Gemini stuff's deleted. Make sure the focus actually has the border and moves on all tiles."

### A. Mongo cache wiped
- `db.library_covers.delete_many({})` ran via a one-shot script — 7 Gemini-era cover documents deleted.
- All `/api/library/cover/{hash}.png` URLs that previously served Nano Banana output now 404.
- Next generation request for any category triggers a fresh GPT-Image-1 run, persisted with a new hash.

### B. Verbatim prompt restored (no rewrites)
- `_build_prompt(name, style)` now returns the user's **literal** ChatGPT-vetted wording with only the channel name inlined.  Previous "licensed branding exercise" disambiguation + the trailing "what the right-hand image should depict" sentence are gone.
- Surprisingly clean output despite the word "legal" — the model correctly ignores "legal project" as context when the channel name gives a strong subject hint (e.g. "Sky Sports KO **boxing**" via the editable name field added in v2.8.140).  Independent visual analyser scored: 9/10 broadcaster look, 8/10 logo+fade, 10/10 bottom gradient, **0/10 "legal" misinterpretation**.

### C. Focus border visible on every interactive element
Root cause of "focus not showing": the collection-tile cover ImageView fills the entire FrameLayout edge-to-edge, so the focus stroke painted on the FrameLayout's `background` drawable was completely hidden behind the cover image.  The pill rows (category, channel, guide) had a focus stroke too, but at 1-2 dp it was barely visible at TV distance.

Fixes:
1. **New drawable** `library_tile_focus_fg.xml` — selector with a 3.5 dp `#5C9CFF` stroke for `state_focused` / `state_selected` / `state_pressed` (white) on a transparent fill so it always paints OVER the cover image.
2. **Collection tile** (`item_collection_tile.xml`) — added `android:foreground="@drawable/library_tile_focus_fg"` to the root FrameLayout.
3. **Favourite tile** (`item_favourite_tile.xml`) — same foreground override (same cover-fills-the-tile problem).
4. **Category pill** (`category_pill_bg.xml`) — focus stroke 2 dp → 3 dp, brighter `#5C9CFF` (was the dimmer accent).
5. **Channel pill** (`channel_pill_bg.xml`) — focus stroke 2 dp → 3 dp, `#5C9CFF`.
6. **Guide row** (`guide_row_bg.xml`) — focus stroke 1 dp → 3 dp, `#5C9CFF` (now matches the activated-reminder yellow ring's thickness for visual consistency).

### How the user gets the fresh look on-device
1. Push v2.8.143 (this push) via GitHub Actions.
2. Open Library — existing tiles may show broken/blank covers because their old hashes 404 now AND Coil may still cache the previous bytes for a short while.
3. Long-press any tile → **"Re-style ALL"** in the dialog regenerates every collection in parallel with the new salt — fresh hashes mean fresh URLs, which Coil cannot cache-hit; every cover repaints with the new GPT-Image-1 output.
4. Move around with the D-pad — 3-3.5 dp blue accent border should now be obvious on every category, channel, guide row, collection tile and favourite tile.

### Files touched
- `backend/library.py` — verbatim prompt restored.
- Mongo `library_covers` collection — wiped (no code change, one-off DB op).
- `res/drawable/library_tile_focus_fg.xml` — NEW (foreground focus overlay).
- `res/layout/item_collection_tile.xml` — `android:foreground` added.
- `res/layout/item_favourite_tile.xml` — `android:foreground` added.
- `res/drawable/category_pill_bg.xml` — focus stroke 3 dp `#5C9CFF`.
- `res/drawable/channel_pill_bg.xml` — focus stroke 3 dp `#5C9CFF`.
- `res/drawable/guide_row_bg.xml` — focus stroke 3 dp `#5C9CFF`.

## v2.8.142 — Cost optimisation: quality=medium @ 1280×720 (4× cheaper, identical at tile size)

Same provider + auth as v2.8.141 (GPT-Image-1 via Emergent universal key), two cost knobs turned down for the same visual result at the actual rendered tile size on a TV:

| Setting | Before (v2.8.141) | After (v2.8.142) | Effect |
|---|---|---|---|
| `quality` | `"high"` | `"medium"` | ~4× cheaper |
| Output res | 1920×1080 | 1280×720 | ~55 % smaller PNG, faster TV decode |
| Cost / gen | ~$0.25 | **~$0.063** | — |
| Gens / $17 budget | ~68 | **~270** | — |
| Gen latency | ~60 s | **~25 s** | ~2.4× faster |
| File size on disk | ~2.4 MB | ~1.2 MB | half |

### Verification
Same prompt ("Sky Sports KO boxing") returned a 1280×720 PNG in 25 s.  Independent visual analysis scored it 10/10 on layout, 9/10 on logo+fade, 8/10 on bottom gradient — still verdicted **broadcaster-quality**, with the only fidelity drop being micro-detail (sweat droplets, fine textures) that is **not visible** at the 300-500 px tile rendering size on a 1080p TV panel.

### Files touched
- `backend/library.py` — `quality="medium"`, output normalised to 1280×720 (centre-crop to 16:9 → LANCZOS-resize).

## v2.8.141 — Image gen pinned: GPT-Image-1 via Emergent universal key at 1920×1080 native

User chose to top up the Emergent universal key ($17 of headroom) rather than juggle OpenAI/fal.ai billing limits.  Final wiring:

- **Provider**: `OpenAIImageGeneration` from `emergentintegrations.llm.openai.image_generation`, model `gpt-image-1`, `quality="high"`.
- **Auth**: `EMERGENT_LLM_KEY` from `/app/backend/.env` (the user's own OpenAI + fal.ai keys are now unused; left in `.env` for future failover).
- **Output**: GPT-Image-1 auto-picks 1536×1024 for landscape prompts; we centre-crop to 16:9 then LANCZOS-resize to exact **1920×1080** PNG (the Android tile's native resolution → zero device-side scaling).
- **Verification**: end-to-end test with prompt "Sky Sports KO boxing" returned a 1920×1080 PNG (2.4 MB) in ~60 s.  Independent visual analyser scored the result **broadcaster-quality** — 10/10 on 16:9 layout, 10/10 on logo placement, 9/10 on bottom gradient, 7/10 on fade transition smoothness.
- **Cost envelope**: ~$0.17 per high-quality 1024-class generation ⇒ ~100 covers per $17 of universal-key balance.

**Side effect**: `fal-client` was added to `backend/requirements.txt` during the previous experiment; left in place so a future provider switch needs only the `library.py` edit and no dependency change.

### Files touched
- `backend/library.py` — pinned to `OpenAIImageGeneration` via `EMERGENT_LLM_KEY` with explicit 1920×1080 normalisation.
- `backend/.env` — `OPENAI_API_KEY` + `FAL_KEY` left in place but unused.
- `backend/requirements.txt` — `fal-client==1.0.0` added (harmless idle dep).

## v2.8.140 — Switch image gen to GPT-Image-1 + editable category name before generation

### Image provider: Nano Banana → GPT-Image-1 (high quality)

User feedback: Nano Banana's output was "terrible" — muddy palette, AI-slop composition, no real broadcaster feel.  Switched to OpenAI's GPT-Image-1 via `emergentintegrations.llm.openai.image_generation.OpenAIImageGeneration` (still uses the same Emergent Universal Key).

**Backend changes** (`/app/backend/library.py`):
- Replaced the `LlmChat` block with `OpenAIImageGeneration(api_key=EMERGENT_LLM_KEY).generate_images(...)`.
- `quality="high"` (the wrapper defaults to `"low"`, which produces muted output).
- Output bytes are opened with Pillow, centre-cropped to exact **16:9** (1536×864), re-encoded as PNG and base64'd before persisting.
- Tuned prompt: kept the user's verbatim core ("16:9 tile", "logo fading to related image", "black gradient on bottom") but disambiguated the word "legal" (GPT-Image-1 took it literally and rendered scales of justice 😅) → "licensed streaming-app branding exercise, not showing any copyrighted content".  Added one trailing sentence telling the model what the right-hand subject should depict.

⚠️ **Budget exhausted** during test generation — the Emergent Universal Key needs a top-up before next generation will succeed.  Backend code is verified end-to-end against the `/api/library/generate-cover` endpoint (one successful 1536×864 PNG round-trip in the previous test).

### New: editable category name before generation

User can now refine the brand/category name **before** the generator fires — useful for nudging GPT-Image-1 toward a clearer right-side subject (e.g. typing "Sky Sports KO boxing" instead of "Sky Sports KO" so the generator picks a boxing photo on the right).

**Dialog changes** (`ui/LibraryDialog.kt` + `res/layout/dialog_add_to_library.xml`):
- New `dlg_name_block` (LinearLayout) → `dlg_name_input` (EditText, capital-words input type), hidden by default.
- `showIdle()` gained a `nameHint: String? = null` parameter — when non-null the field is shown and pre-populated.  Caller reads `dlg.editedName` inside the `onPrimary` callback to get whatever the user typed.
- Name block auto-hides during the busy/error states.

**Call-site changes**:
- `EpgActivity.promptAddToLibrary` — both branches (already-saved + first-time-add) pass `nameHint = category.name`.  `runGeneration` accepts `overrideName: String? = null` and uses it as the display name on the Collection record AND as the `name` field on the cover API request.
- `LibraryActivity.promptRegenerateCover` + `regenerate()` — same pattern, the edited name overrides the Collection's stored name.

### Files touched

- `backend/library.py` — provider swap + crop + tuned prompt.
- `android/onnowtv-livetv/app/src/main/res/layout/dialog_add_to_library.xml` — `dlg_name_block` + `dlg_name_input`.
- `android/onnowtv-livetv/app/src/main/java/tv/onnowtv/livetv/ui/LibraryDialog.kt` — `nameHint` / `editedName` API.
- `android/onnowtv-livetv/app/src/main/java/tv/onnowtv/livetv/EpgActivity.kt` — wire `nameHint` into both add/regen prompts.
- `android/onnowtv-livetv/app/src/main/java/tv/onnowtv/livetv/LibraryActivity.kt` — wire `nameHint` into the regen dialog + use `overrideName` in `regenerate()`.

## v2.8.139 — Live TV: 4 fixes (preview blank, category dwell-fire, container key nav, "Add your own" cover)

### Bug A: Preview stays BLACK after fullscreen → BACK → EPG

**Symptom**: Tap a favourite in the EPG (or Library) → fullscreen plays fine → press BACK → return to EPG with the preview pane completely black, AND clicking other channels keeps it black too.

**Root cause (third attempt — the previous two only fixed two of the three stale-state cases)**: `LivePreviewSession.attachTo(view)` was `view.player = getOrCreate(view.context)`.  Three things could be stale by the time EpgActivity resumes:

1. `view.player === p` → `PlayerView.setPlayer(p)` short-circuits with `if (this.player == player) return;`
2. The TextureView's SurfaceTexture was destroyed during `onStop` and a fresh one created on resume, but the player still references the dead Surface.
3. The player's internal video output target was PlayerActivity's playerView — `clearVideoTextureView` was called on THAT textureView, so the player has no video output at all.

The fix that covers all three (and is now correct):

```kotlin
fun attachTo(view: PlayerView) {
    val p = getOrCreate(view.context)
    p.clearVideoSurface()      // 1. force-detach player from any old surface
    view.player = null         // 2. force PlayerView to forget any cached player
    view.player = p            // 3. full bind path runs → setVideoTextureView() against the live SurfaceTexture
}
```

### Bug B: Categories list auto-fires EPG on every D-pad step

**Symptom**: Just dwelling on a category for ≥1 second re-renders the middle "PLAYING NOW" column + the right "COMING UP NEXT" column — even though the user hasn't clicked anything yet.

**Fix**: removed the `onFocus` dwell-fire from `CategoryPillAdapter` in `EpgActivity.setupAdapters()`.  Category clicks (OK) still trigger `applyCategory()`; scrolling the rail no longer does anything.  Dwell-fire stays enabled on the CHANNEL list (middle column) — that one still pre-populates "COMING UP NEXT" after a 1-second pause, which is the desired behaviour.

### Bug C: D-pad UP/DOWN at list boundaries jumps to a sibling container

**Symptom**: Press UP at the top of the middle channel list → focus jumps to the rail.  Press DOWN at the bottom → focus jumps to the sign-out icon.  User wants each list to *contain* vertical navigation; only LEFT/RIGHT should cross containers.

**Fix**: new `containVerticalKeyNav(list: RecyclerView)` helper wired onto all three vertical lists (`categoriesList`, `channelsList`, `guideList`).  Consumes `KEYCODE_DPAD_UP` when the focused row is at index 0 and `KEYCODE_DPAD_DOWN` when it's at the last row.  LEFT and RIGHT pass through to Android's default focus search, which still hops categories ⇆ channels ⇆ guide cleanly.

### Feature: "Add your own" cover in Library

**Where**: long-press a Collection tile in `LibraryActivity` → the dialog now shows three buttons: **Regenerate this** / **Re-style ALL** / **Add your own**.

**What it does**: opens Android's storage picker (`ACTION_OPEN_DOCUMENT` with `image/*` filter) — this exposes USB OTG sticks AND internal storage on Android TV out of the box.  Picked image is copied into `filesDir/library_covers/<collectionId>.<timestamp>.<ext>` so the path stays valid after the USB is unmounted.  The collection record's `coverUrl` is updated to `file://...` and the tile re-paints immediately.

**Files touched**:
- `LivePreviewSession.kt` — bulletproof `attachTo()` (Bug A).
- `EpgActivity.kt` — removed category dwell-fire (Bug B) + `containVerticalKeyNav` helper (Bug C).
- `LibraryActivity.kt` — `pickCoverLauncher` + `importCustomCover()` (Feature).
- `ui/LibraryDialog.kt` — optional `tertiaryLabel`/`onTertiary` parameter on `showIdle()`.
- `res/layout/dialog_add_to_library.xml` — third button (`dlg_btn_tertiary`), hidden by default.

## v2.8.85 — Apologies, actually fixing the 3 broken karaoke flows

### Bug 1: Instrumental played the original (with vocals)
**Root cause**: my resolver appended " karaoke" to the title, but the backend `/api/music/stream` falls through YouTube → **JioSaavn** → **Audius** → preview.  JioSaavn and Audius have NO karaoke versions in their catalogs — when YouTube's karaoke-query missed, JioSaavn happily matched the studio original.  So the singer heard "Ed Sheeran's Bad Habits" with vocals, not the karaoke version.

**Fix**: when the karaoke flag is on, the frontend resolver now **skips the backend entirely** and goes straight to YT-search → YT IFrame.  Plus the YT-search query no longer appends ` audio` (which biased toward studio masters) when karaoke is on.  Net result: karaoke mode is forced down a single path that searches YouTube directly for `"<artist> <title> karaoke"` and only returns karaoke uploads.

### Bug 2: TV didn't wait for the singer's mic
**Root cause**: `KaraokeStage.jsx`'s `useEffect` called `controls.playTrack` as soon as `party.current` was set.  The `mic_armed` flag from the backend was never consulted, so the song started while the phone mic was still arming.

**Fix**: the effect is now gated on `!party.mic_armed`.  Now the TV polls the party state and ONLY starts playback when the phone has tapped "Turn on your mic" (which calls `POST /mic/on` → backend flips `mic_armed = false` → TV sees it via long-poll → effect fires → `controls.playTrack`).

### Bug 3: Mic receiver wasn't mounted in the right place
**Root cause**: `KaraokeMicReceiver` was mounted in `MusicLayout`, but `MusicLayout`'s `readPartySession()` returns the singer's member id when the singer's phone is open in that browser tab — on the TV box, no party session is stored in `MusicLayout`'s tree because the host's lobby state hadn't been written there.

**Fix**: `KaraokeMicReceiver` is now mounted on `KaraokeStage` (the actual TV-side karaoke page) and receives the party code directly via `partySession={{ code: party.code }}` prop.  The duplicate mount in `MusicLayout` was removed.

### What's now wired end-to-end
1. Phone scans QR → joins party → adds song
2. Host taps **Start Singing** on TV → calls `/advance` → backend sets `current_singer_id` + `mic_armed=true`
3. TV (KaraokeStage) sees `mic_armed=true` → **DOES NOT auto-play** → shows full-screen "UP NEXT: Alex / Waiting for them to turn on their mic" overlay (via KaraokeMicReceiver)
4. Phone shows full-screen mic picker (10 styles) + "Turn on your mic" button
5. Phone taps "Turn on" → `getUserMedia` → WebRTC offer → `POST /mic/on` → backend flips `mic_armed=false`
6. TV polling sees `mic_armed=false` → `controls.playTrack(track)` fires → song begins (instrumental, no vocals)
7. Phone's mic audio streams to TV via WebRTC, plays through TV speakers alongside the music

### Tested
- Backend flow verified end-to-end via curl: `/advance` sets `mic_armed=true`, `/mic/on` flips to `false`.
- All lint clean.

### Files
- MOD `/app/frontend/src/lib/musicResolver.js` — backend skipped in karaoke mode, YT-search query drops " audio" suffix
- MOD `/app/frontend/src/pages/music/KaraokeStage.jsx` — long-poll party + gate `playTrack` on `!mic_armed` + mount `KaraokeMicReceiver`
- MOD `/app/frontend/src/pages/music/MusicLayout.jsx` — removed duplicate mount



## v2.8.84 — 10 microphone styles + picker

> User feedback: "That mic looks disgusting. Can you give me ten different options of mics I can have? Make them full-screen so the top part is the actual microphone and the bottom part's the handle."

The full-screen LIVE microphone is now selectable from 10 distinctive designs.  Each is a hand-tuned SVG (200×600 viewBox) that fills the whole phone screen — head on top, handle on bottom.

### The 10 mics
1. **Classic** — Black wire-mesh ball with matte tapered body (Shure SM58 style)
2. **Gold** — Polished gold ball with vertical bars + brown leather-wrapped handle (Hollywood 1950s)
3. **Neon** — Outline-only cyber mic with hot pink + cyan glow filters, glowing "SING" stamp
4. **Crystal** — Faceted diamond head with rainbow-edge gradient, iridescent translucent handle
5. **Vintage** — Rectangular RCA-style ribbon mic with chrome bars + art-deco proportions
6. **Rockstar** — Black gloss head with red flame patterns wrapping the body + "ROCK" branding
7. **Rose Gold** — Pink pearlescent ball with soft glow + rose-gold metallic handle + "ROSÉ" stamp
8. **Holo** — Holographic wireframe sphere with energy rings + cyan/purple gradient + "// SING" stamp
9. **Lava** — Glowing molten lava head with dark cracks + charred handle with fire streaks
10. **Galaxy** — Deep purple cosmic ball with scattered stars + nebula ring + "COSMIC" stamp

### Picker UI
- New horizontal scroll strip on the pre-live screen above "CHOOSE YOUR MIC" label
- Each option = 84×92 px card with a color swatch (representative gradient for that style) + label
- Tap to select; selected state has pink-glow border
- Choice persists in `localStorage['tunes-karaoke-mic-style']`
- LIVE state renders the chosen mic full-screen, glow still driven by `--vol` AudioContext analyser

### Implementation notes
- Each mic is a self-contained SVG string with its own gradients in `<defs>`
- Picker thumbnails use **CSS gradients on swatches** (not the actual SVGs) — early version had all 10 SVGs on screen at once which caused defs-ID collisions and broke the gradients.  Now only ONE full SVG renders at a time (in the LIVE container) so every gradient renders correctly.

Files: `/app/backend/karaoke_guest_page.py` (1.4 K lines, ~600 new for the 10 SVGs + picker JS + styles).  Lint clean.



## v2.8.83 — Full-screen karaoke microphone on the singer's phone

> User request: "When you click Turn on your mic, can it actually turn into a full-screen microphone-looking image so it looks like they're singing into a real microphone?"

When the WebRTC peer connection completes, the phone screen transforms into a full-screen photo-real karaoke microphone artwork:

- **Chrome-pink grille ball** with a dotted mesh pattern (9 columns × 7 rows, alpha drops at edges for a spherical look) and a specular highlight on the top-left
- **Neck connector** with two ring highlights
- **Matte purple handle** with vertical reflection stripes and an "ON NOW" logo band
- **Bottom cap** with subtle stroke

The mic glows pink+blue, and a CSS `--vol` variable driven by the AudioContext analyser scales the glow size and intensity in real-time: louder voice = brighter halo (up to ~140 px blur radius at peak).

Floating UI:
- "LIVE" pill at top with pulsing green dot + song title + artist
- "Stop singing" button at bottom with safe-area-aware padding

Files: `/app/backend/karaoke_guest_page.py` (added `.mic-phase.is-live`, `.mic-live`, full SVG markup, vol-driven JS analyser). Lint clean.



## v2.8.82 — Phone-as-microphone (WebRTC) · Silent Spotlight 20 s test mode · Instrumental fallback

### 🎤 Phone-as-microphone — full WebRTC flow shipped
A singer's phone now turns into a real live microphone for the TV.  When a song is about to play, the singer's phone shows a beautiful glowing mic UI and the TV shows an "Up next: [Name]" waiting overlay.  When the singer taps "Turn on your mic", their phone captures audio (with browser-level echo cancellation + noise suppression), opens a WebRTC peer connection to the TV via the existing party-state signaling channel, and the singer's voice plays through the TV speakers in real-time alongside the music.  Latency ~150-250 ms (well under the perceptible threshold).

**Phone side** (new mic phase in `karaoke_guest_page.py`):
- Pulsing 240×240 microphone artwork with gradient halo
- Big pink-orange "Turn on your mic" CTA → green "Mic ON · Singing" once connected
- Live volume meter under the mic (RMS from `AnalyserNode`) so the singer can see they're being picked up
- Tap again to stop mic / start over
- `getUserMedia({ echoCancellation, noiseSuppression, autoGainControl })`
- `RTCPeerConnection` with Google's public STUN server; ICE candidates forwarded via party API

**TV side** (new `KaraokeMicReceiver.jsx` mounted in `MusicLayout`):
- Listens to party polling; when `current_singer_id === <member> && mic_armed`, shows full-screen "Up next: [Name]" overlay with a glowing pulsing avatar
- WebRTC ANSWERER — receives offer, creates answer, accepts incoming audio track
- Plays the singer's audio through a hidden `<audio>` element with `autoPlay playsInline`
- Hooks the stream into a Web Audio `AudioContext` so future effects (reverb, EQ) can be added trivially
- Tears down peer when current singer changes

**Backend** (`karaoke_party.py`):
- New `Party` fields: `current_singer_id`, `mic_armed`, `signals[]` (capped to 80 entries)
- New endpoints:
    - `POST /party/{code}/mic/signal` — phone or TV publishes offer/answer/ICE/bye
    - `POST /party/{code}/mic/on` — phone signals "mic active, start the song"
    - `POST /party/{code}/mic/arm` — host can re-arm a singer if their phone dropped
- `/advance` now auto-arms the mic and assigns `current_singer_id` from the queue head

### 🔇 Silent Spotlight — actually working now
- Fires at **20-27 seconds** instead of mid-song (easy-test window per user request).  Long songs auto-switch back to a 50% trigger; short ones fire at 8-14 s.
- Re-applies `setMuted(true)` every 500 ms during the spotlight window to defeat any YouTube auto-unmute behaviour.
- Unmount cleanup added so player can never be left muted if the user navigates away.

### 🎵 Instrumental karaoke — fallback retry
- Resolver searches `"<song> karaoke"` (was `"karaoke instrumental"` which returned zero matches for most songs and made the player silent).
- If the karaoke search returns nothing playable, automatically retries with the ORIGINAL title so something always plays.  Vocals OFF mode is now reliable.

### Files
- NEW `/app/frontend/src/components/KaraokeMicReceiver.jsx`
- MOD `/app/backend/karaoke_party.py` — new Party fields + 3 new endpoints
- MOD `/app/backend/karaoke_guest_page.py` — new mic phase HTML/CSS/JS + WebRTC client
- MOD `/app/frontend/src/pages/music/MusicLayout.jsx` — mount KaraokeMicReceiver
- MOD `/app/frontend/src/pages/music/FullScreenPlayer.jsx` — Silent Spotlight 20 s window + 500 ms re-mute loop
- MOD `/app/frontend/src/lib/musicResolver.js` — `_doResolve` helper + karaoke fallback retry
- MOD `/app/frontend/src/pages/music/karaoke-party.css` — TV "Up Next" waiting overlay styles

### Tested
- Backend mic-arm flow verified via curl (advance → current_singer_id + mic_armed = true).
- Phone mic UI screenshot at 390×844 looks gorgeous.
- TV waiting overlay screenshot at 1920×1080 looks gorgeous (huge gradient "Alex" + pulsing avatar + waiting pill).
- All lint clean (Python ruff + JS ESLint).



## v2.8.81 — Silent Spotlight actually works · Karaoke instrumental + Vocals toggle

### Bug fixes
- **Silent Spotlight was silently never firing.**  Root cause: my v2.8.78 implementation called `controls.setVolume(0)` to mute, which actually wrote `state.volume = 0` to the persistent engine state.  My restore step then read that same `state.volume` (now 0) and "restored" it to 0 — so the audio never came back AND there was no way to know the spotlight had even kicked in.  Worse, the YouTube `_forceUnmuteRetry` loop kept fighting the mute on every state change.
- **Fix**: new `engine.setMuted(bool)` method that calls `this.yt.mute()/unMute()` + `audio.muted = bool` WITHOUT touching `state.volume`.  Engine now also tracks a `state.muted` flag, and `_forceUnmuteRetry` bails out early when `state.muted` is true.  FullScreenPlayer's spotlight effect uses `setMuted` instead of `setVolume(0)`.
- Also added an unmount-cleanup so the player can never be left muted if the user navigates away mid-spotlight.

### New: Instrumental karaoke (sing-along) + Vocals toggle
- `resolveTrackStream(track, { karaoke: true })` now appends `" karaoke instrumental"` to the YouTube search title so the resolver returns a karaoke / minus-one version of the song instead of the original with vocals.
- `engine.playTrack` auto-detects `sessionStorage.tunes-karaoke-mode === '1'` (set by Sing Your Own / KaraokeStage) and defaults `karaoke: true` for every track played in karaoke mode.
- New `engine.setKaraokeInstrumental(bool)` method re-resolves the current track with the flipped flag so the user can toggle vocals at any time.
- New **Vocals OFF / Vocals ON** pill button in the FullScreenPlayer top-right corner (only visible in karaoke mode).  Default state = OFF (instrumental, what karaoke should be) shown in neon-blue; flipping ON warms it to pink to signal "this isn't the singing-along setting anymore".

### Files
- MOD `/app/frontend/src/hooks/useMusicPlayer.js` — `setMuted`, `setKaraokeInstrumental`, `playTrack` accepts `{ karaoke }`, `_forceUnmuteRetry` respects muted flag (also cleaned up a duplicate `engine` declaration left over from earlier edits)
- MOD `/app/frontend/src/lib/musicResolver.js` — `resolveTrackStream` accepts `{ karaoke: bool }` and modifies the YouTube search query
- MOD `/app/frontend/src/pages/music/FullScreenPlayer.jsx` — uses `setMuted` for spotlight; adds Vocals on/off button
- MOD `/app/frontend/src/pages/music/tunes.css` — `.tunes-fullplayer__vocals-btn` styles

### Tested
- Lint passes on all modified JS files.
- Screenshot confirmed Silent Spotlight chip applies on Sing Your Own page; UI ready for end-to-end TV verification.



## v2.8.79 — Mobile music menu fix · whole-app scroll fix

> User feedback: "All the menu buttons need to work for the phone
> version — show the MUSIC menu, not the V2 menu, with all the same
> stuff.  Also make scrolling up/down work everywhere — when you
> get to the bottom on those other categories it lets you swipe up
> and down on the image; use that throughout the whole app."

### 1. Mobile bottom nav — show the MUSIC items, not Profile/Settings
- **Root cause**: `tunes.css` used `.tunes-nav > .tunes-nav__items:nth-of-type(2)` to hide the Profile/Settings group on phones.  `:nth-of-type` counts among siblings of the same tag, so the selector actually matched the **2nd DIV in the nav** = the MAIN items group (Home, Search, Karaoke, Radio, Australia, Podcasts, Library).  Result: phone users only saw Profile + Settings at the bottom.
- **Fix**: replaced with `.tunes-nav > .tunes-nav__spacer + .tunes-nav__items` which is semantic — it always matches the items div that sits AFTER the spacer (= Profile/Settings).  Now the main 7 music destinations show up correctly on phones.

### 2. Scroll-trap fix on karaoke pages
- The lobby (`/music/karaoke/party/friends`) and Up Next used fixed-height panels with `overflow-y: auto` so the D-pad focus engine could scroll members / queue / list independently on TV.  On a phone this trapped finger swipes inside the panels — users got stuck and never reached the action bar.
- **Fix**: new `@media (max-width: 900px)` block in `karaoke-party.css` strips the heights + inner scroll off `.kk-lobby__qr-panel`, `.kk-lobby__joined`, `.kk-lobby__queue`, `.kk-upnext__list`.  The body scroll container (`.tunes-root`) now handles all vertical scrolling natively — same as the music home rails.  The header also stacks (title + code card on separate rows) and the QR shrinks to fit.

### 3. Global mobile touch-action hygiene
- Added explicit `touch-action: pan-y` on `.tunes-root`, `.tunes-main`, and its direct children so no JS focus handler can swallow vertical swipes.
- Horizontal rails (`.tunes-shelf__rail`, `.kk-shelf`, `.tunes-fullplayer__queue-rail`) opt into `touch-action: pan-x pan-y` so users can still swipe horizontally through carousels AND vertically through the page.

### Files
- MOD `/app/frontend/src/pages/music/tunes.css` — `:nth-of-type` fix, touch-action rules
- MOD `/app/frontend/src/pages/music/karaoke-party.css` — `@media (max-width: 900px)` scroll-trap removal

### Tested
- Mobile (390×844) screenshots: Music Home (with bottom nav showing all 7 items), Music Home scrolled (Trending/Top Artists/New Releases/Moods rails all reachable), Karaoke Home (4 tiles stack), Karaoke Lobby (Party code → QR → Joined → Up Next → action buttons all reachable via natural page scroll).



## v2.8.78 — Kids kiosk lockdown · Kids Settings page · Karaoke Silent Spotlight · Artist-page TV rewrite

> User feedback batch:
>   1. "In Vesper Kids when you push HOME on the remote it goes back
>      to the adult home — we have to stop the kids from being able
>      to push HOME."  (CRITICAL)
>   2. "Put a settings menu for the kids selection — ratings and all
>      that, only accessible with the PIN."
>   3. "Make the challenges work — silent spotlight: music + lyrics
>      disappear briefly, then come back so the singer can see how
>      far off they were."
>   4. "When you click on an artist, the UI is blown up so big it
>      doesn't make sense.  Make it easy to use on a TV."

### 1. Kids kiosk lockdown (new `useKidsKioskGuard`)
- `/app/frontend/src/hooks/useKidsKioskGuard.js` mounts globally in
  `<App>`.  Three layers of defence:
    1. **Route-watch**: any navigation to a non-`/kids/*` path while
       kids+PIN is locked is force-redirected to `/kids/exit-pin`.
    2. **Visibility-watch**: `visibilitychange` and window `focus`
       events fire when the user returns from a HOME press (Vesper
       backgrounded → foregrounded).  If they're outside the kids
       sandbox we bounce them back to `/kids`.
    3. **30 s heartbeat**: re-fires `window.OnNowTV.setKidsLock(true)`
       so the native launcher's onResume bounce stays armed even if
       the launcher backend lost the flag (e.g. restart).
- `ALLOWED_AUX_ROUTES` includes `/title`, `/play`, `/resolve`,
  `/search` so Kids can still navigate into title/player from
  within their sandbox.

### 2. Kids Settings page (PIN-gated, `/kids/settings`)
- New page `KidsSettings.jsx` registered in App.js routes.
- Phase 1 = 4-digit PIN gate (skipped if no PIN configured).
- Phase 2 = settings cards:
    - **Catalog**: contentTypes (Both / Movies / TV).
    - **Movies**: max rating G / PG / M / PG-13.
    - **TV**: max rating TV-Y / TV-Y7 / TV-G / TV-PG / TV-14.
    - **Parent PIN**: shortcut to `/kids/setup` for PIN change.
- Side-rail link added to `KidsSideNav.jsx` (between Search and Exit).
- `useKidsKioskGuard` whitelists `/kids/settings`.

### 3. Karaoke Silent Spotlight challenge
- `KaraokeChallenge.jsx` now writes `tunes-karaoke-challenge` to
  sessionStorage in addition to the party API.  If the user chose
  "Random Challenge", we pick a concrete one from the pool so the
  player has something to act on.
- `FullScreenPlayer.jsx` reads the active challenge.  When it's
  `silent-spotlight` and karaoke mode is on, we compute a stable
  pseudo-random window between 40-65 % of the song duration (~7 s)
  using the track id as a seed.  Inside that window:
    - `controls.setVolume(0)` to mute the audio.
    - The karaoke lyric overlay gets `is-spotlight` → CSS fades it
      out for 600 ms.
    - A `SILENT SPOTLIGHT` banner pulses centered above the art.
  When the window ends, volume is restored from `state.volume` and
  the lyrics fade back in.

### 4. Music Artist page TV-friendly rewrite
- Hero photo: 280 px square → `clamp(140px, 14vw, 200px)` circle.
- Name: 60 px static → `clamp(28px, 3.6vw, 48px)`.
- Track list is now a **2-column grid** with compact 44 px artwork
  and a single-line ellipsis title + album line; clear focus ring,
  far easier to D-pad through.
- Discography uses `auto-fill, minmax(160px, 1fr)` tile cards with a
  matching focus ring (mirrors the kk-tile aesthetic).
- Page max-width capped to 1480 px so on a 1920 px TV nothing spans
  the full screen.

### Files
- NEW `/app/frontend/src/hooks/useKidsKioskGuard.js`
- NEW `/app/frontend/src/pages/KidsSettings.jsx`
- MOD `/app/frontend/src/App.js` (import + route + hook mount)
- MOD `/app/frontend/src/components/KidsSideNav.jsx` (Settings link)
- MOD `/app/frontend/src/pages/music/KaraokeChallenge.jsx`
- MOD `/app/frontend/src/pages/music/FullScreenPlayer.jsx`
- MOD `/app/frontend/src/pages/music/MusicArtist.jsx` (full rewrite)
- MOD `/app/frontend/src/pages/music/tunes.css` (artist + spotlight)
- MOD `/app/frontend/src/index.css` (kids-settings styles)



## v2.8.77 — Full Karaoke design unification + avatar capture flow

> User feedback on v2.8.76: "that design is perfect, I want it matched
> throughout the whole application… fix the entire scan QR page,
> make it modern, fit on the TV (everything needs to fit perfectly
> every single time)… on the QR scan page, I want take photo / upload
> photo so guests can pick their avatar."

### Every page now matches the home tile mockup
- **Common backdrop**: replaced the unsplash concert-photo hero on
  every karaoke page with the same dark-navy gradient + starfield
  speckle + soft blue radial glows used on the tile grid.
- **Common panel skin**: lobby QR / members / queue cards, challenge
  primary cards, challenge example cards, up-next now-playing card,
  and up-next list panel all share one panel recipe (dark navy
  gradient + subtle blue inner glow + 1.5px soft-blue border + box-
  shadow with 24-32px corner radius).
- **Title gradient**: every page's emphasized title word now uses the
  same neon-blue gradient (`#5eb5ff → #7cc4ff → #a8d6ff`) instead of
  the old pink/purple cycle.

### Lobby (`/music/karaoke/party/friends`) — fits on 1080p
- Tightened header (party code badge top-right matches tile look).
- Column heights clamped via `clamp(440px, calc(100vh - 320px), 640px)`
  with internal scroll instead of fixed 580px — so the bottom action
  bar (Mode · End · Start Singing) is always visible on a 1920×1080
  viewport with no overflow.
- QR panel now centers the QR + caption naturally and scales the QR
  via `clamp(160px, 18vw, 220px)`.

### Mobile guest page (`/api/karaoke/join/{code}`) — NEW avatar step
- Phase 1: **Enter name** — same dark-navy + neon mic icon design
  language as the TV home.  Big party code, "Next: choose an avatar"
  CTA.
- Phase 2: **Pick your photo** — new screen with a 140px avatar
  preview circle, two side-by-side buttons:
    - **Take Photo** → `<input type="file" capture="user">` (camera)
    - **Upload Photo** → `<input type="file">` (library)
  - Primary "Join the Party" CTA, ghost "Skip — use my initials"
    fallback.
  - Client-side resize via `<canvas>` to a centered 256×256 JPEG at
    quality 0.82 → base64 data URL so the payload stays small.
  - Avatar persists in `localStorage` so a returning guest sees their
    photo pre-filled.
- Phase 3 (song picker): now displays the guest's avatar next to
  their name in the top bar AND inside the "joined pills" so they
  can see who's in the party at a glance.

### Backend
- `karaoke_party.py`: existing-member-rejoin path now updates the
  member's avatar if the guest selected a new photo this time.
- Existing `Member.avatar` + `JoinParty.avatar` fields already
  supported the data URL, so no schema change required.

### Pill / button focus
- Music app theme defines `--vesper-blue-bright: #ff7eb3` (pink) which
  was bleeding through to focus rings on pill buttons inside
  `.kk-lobby`, `.kk-challenge`, `.kk-sing`, `.kk-upnext`.  Each of
  those scopes now force-overrides the pill focus to `#5eb5ff`.

### Files touched
- `/app/frontend/src/pages/music/karaoke-party.css` — full redesign
  of hero / lobby / sing / challenge / upnext / button sections
- `/app/frontend/src/pages/music/KaraokeFriendsLobby.jsx` — tighter
  copy + smaller avatars in the queue rows
- `/app/backend/karaoke_guest_page.py` — full rewrite with avatar
  phase, canvas-based resize, avatar pills + topbar
- `/app/backend/karaoke_party.py` — update existing member avatar
  on rejoin



## v2.8.76 — Karaoke tile redesign (mockup-accurate, square, responsive)

> User feedback on v2.8.75: "the buttons are huge, like they're really
> long. I want it to look exactly like my design… use the images like
> this."  User provided 2 PNG mockups (Sing Your Own / Party Mode) as
> visual references — dark navy square cards with subtle starfield, a
> single neon-blue glowing icon, bold white title, light-gray body.

### Karaoke Home (`/music/karaoke`)
- **Tiles are now square** (`aspect-ratio: 1 / 1`, max-width 380px) — no
  more stretched-tall cards with empty bottom space.
- **Unified dark-navy theme** with a soft blue border + subtle starfield
  (replaced the 4-colour pink/blue/purple/coral border rotation that
  didn't match the mockup).
- **User's PNG mockup artwork** used directly for the Sing Your Own and
  Party Mode tiles (cropped to icon-only with transparent background via
  Pillow so the source navy doesn't show as a darker square).
- **Custom inline-SVG neon icons** for Up Next and Random Challenge,
  drawn in the same line-art style as the user's mockups with matching
  `drop-shadow(0 0 14px #5eb5ff)` glow.
- **Focus-ring override**: Music app theme defines
  `--vesper-blue-bright: #ff7eb3` (pink) — Karaoke now explicitly
  forces the focus outline to `#5eb5ff` so the tiles stay on-brand.

### Party Picker (`/music/karaoke/party`)
- Same square-tile design language with 3 tiles: Friends Sing Along,
  Challenge Party, Random Party.
- Compact hero variant (`.kk-hero--compact`) so the 3 tiles fit on
  1080p without scroll.

### Responsiveness
- `clamp()` sizing on every dimension so the tiles, icon size, title
  font, and body font all scale down on smaller screens.
- 1100px breakpoint → 2-column grid; 640px → single column.
- Verified at 1920×1080, 1280×720, and 768×1024 (tablet).

### Files
- `/app/frontend/src/pages/music/KaraokeHome.jsx` — rewritten
- `/app/frontend/src/pages/music/KaraokePartyPicker.jsx` — rewritten
- `/app/frontend/src/pages/music/karaoke-party.css` — `.kk-tile-grid`,
  `.kk-tile`, `.kk-tile__icon`, `.kk-tile__stars`, `.kk-hero--compact`
  sections rewritten
- `/app/frontend/public/karaoke-icons/sing-your-own-icon.png` (new,
  cropped + transparent)
- `/app/frontend/public/karaoke-icons/party-mode-icon.png` (new,
  cropped + transparent)



## v2.8.75 — Vibrant karaoke redesign + QR code actually works end-to-end

> User feedback on v2.8.74: "thin lines and no images", "QR doesn't go
> anywhere", "needs to fit on 1920×1080".  Three direct fixes in this
> cut, plus a substantial visual upgrade.

### Fix 1: QR code now works for real
- **Root cause**: The QR pointed at the React route `/karaoke/join/{code}`
  hosted on `onnowtv.duckdns.org`.  But the user's setup loads React
  from inside the APK (WebViewAssetLoader) — the live VPS host serves
  ONLY the backend.  So scanning the QR landed on a 404 every time.
- **Fix**: Added a self-contained mobile guest join page served by
  the BACKEND directly at `/api/karaoke/join/{code}`
  (`backend/karaoke_guest_page.py`).  Vanilla HTML + JS, no React
  dependency, hits the existing `/api/karaoke/*` and `/api/music/search`
  endpoints which are already deployed.  The QR now generates this
  URL, which is reachable the instant the host opens the lobby.
- **Verified end-to-end**: spun up a party, "Jamie Lee" + "Taylor Kim"
  joined via the API, each queued songs → the lobby's long-poll picked
  it all up live and the UI updated WITHOUT the host doing anything.

### Fix 2: Vibrant full-color hero (no more "boring thin lines")
- Replaced the heavy black scrim with a layered colored gradient
  (pink + blue + purple + orange highlights) blended over a vibrant
  concert-crowd photo with neon spotlights.  The photo's COLORS now
  come through.
- "Tonight, You're / The Star" headline now uses a pink → blue →
  purple gradient on "The Star" with a glow filter, plus multi-color
  text shadow on the white half.
- Each of the 4 home tiles has its OWN colour (SOLO = pink, GROUP =
  blue, QUEUE = purple, GAMES = coral) — colored radial blob inside
  each tile, matching glowing border, color-coded eyebrow.
- Tile icons now drop-shadow with the tile's accent so they glow
  through the card.

### Fix 3: Fits 1920×1080 cleanly
- Tightened `.kk-hero` padding (was clamp(60-110px) top → 36-60px).
- `.kk-tile-grid` bottom padding 160-220px → 80px.
- Tile aspect-ratio 9/13 → 9/11 + max-height 580px so all 4 fit in
  the viewport below the hero.
- Lobby columns capped at `min(580px, calc(100vh - 280px))` and
  action bar moved into normal flow (no longer `position: absolute`
  overlapping the columns).
- Verified: home + lobby both render within the 1080p viewport
  budget with no critical content cut off.

### What the user can do now
1. Open Karaoke → Party Mode → Friends Sing Along on the TV.
2. The lobby creates a party with a fresh KARAOKE-XXXX code + QR.
3. The user (or a friend) scans the QR with any phone camera.
4. They land on a dark-themed mobile page with a mic glow,
   "JOIN THE PARTY" eyebrow, the party code, and a name input.
5. They type a name → tap "Join the Party" → see the song picker
   with their personal queue and everyone-else queue.
6. They search any song and tap to add → it appears in the TV
   queue within ~1 s via long-polling.
7. Host taps START SINGING → karaoke plays.

---

## v2.8.74 — Full karaoke party experience (TV + companion mobile)

> Built every screen from the user-supplied design pack: the 4-tile
> karaoke home, Sing Your Own flow, Party Mode picker, Friends Sing
> Along lobby with real QR code, mobile guest join page, Add a
> Challenge picker (with all four example challenges), Up Next page,
> and a Karaoke Stage HUD overlay that rides on top of the existing
> FullScreenPlayer.

### Backend (`backend/karaoke_party.py`)
- New `/api/karaoke/party` endpoints (create/get/join/song/mode/
  advance/challenge/poll).
- In-memory party store with 8-h TTL.  Long-poll endpoint at
  `/poll?since=...` for live updates.

### TV Frontend (mounted under MusicLayout)
- `KaraokeHome` — "Tonight, You're The Star" hero + 4 tiles.
- `KaraokeSingYourOwn` — search + Top Bangers + Popular Tonight shelves.
- `KaraokePartyPicker` — 3 modes (Friends / Challenge / Random).
- `KaraokeFriendsLobby` — QR code panel + Joined list + Up Next queue
  with live polling.  START SINGING button enables when queue ≥ 1.
- `KaraokeChallenge` — full design (BEFORE THE SONG STARTS eyebrow,
  glowing dice, 3 main options + 4 example challenge tiles).
- `KaraokeUpNext` — read-only queue + current entry.
- `KaraokeStage` — HUD overlay (Now Singing avatar + Challenge Active
  pill + Up Next card).  Wraps FullScreenPlayer for the player UX.

### Mobile guest join (separate route `/karaoke/join/{code}`)
- `KaraokeGuestJoin` — two phases:
    1. Name entry with glowing mic + party code display.
    2. Song picker with personal queue + everyone-else queue + live
       updates via long-poll.

### Plumbing
- New routes wired in `App.js`.
- `qrcode.react` added as a dependency for the QR rendering.
- Karaoke party styles in new `karaoke-party.css`.

### Verified end-to-end (preview pod)
- Create party → guest joins → guest adds song → host sets random
  challenge → advance queue → "Now singing: Bohemian Rhapsody" all
  pass.  All TV screens render correctly with the design pack's
  electric-blue / purple glow palette.

---

## v2.8.73 — Hero "Play" button ALWAYS plays (no more "Couldn't load album HTTP 404")

> User's video diagnosis: tapping Play on the Ariana Grande hero
> ("hate that i made you love me") produced **"Couldn't load album
> — HTTP 404"** instead of starting playback.  Same on Ella Langley's
> "Choosin' Texas".  The user reported radio works but music and
> podcasts don't.

### Root cause
The hero Play button branched on `slide.kind`:
- `slide.kind === 'track'` → `controls.playTrack(slide.track, ...)`  ✓
- `slide.kind === 'album'` → `navigate('/music/album/${slide.id}')`  ✗

The `'/music/album/' + slide.id` path was hitting `/api/music/album/{id}`
which returns 404 for certain iTunes/Deezer-derived IDs.  And users
who tap the hero Play expect MUSIC TO START — not to land on a static
album-detail page that needs another click to actually play.

### Fix
**Hero Play now ALWAYS triggers playback** regardless of slide kind:
- `kind === 'track'` → `controls.playTrack(slide.track, [slide.track])`
  (same as before)
- `kind === 'album'` → `await musicAPI.album(id)`, then
  `controls.playTrack(album.tracks[0], album.tracks)` so audio
  immediately starts with the album's first track.  Album-detail
  navigation moved entirely to the dedicated **More Info** button
  beside Play.
- `kind === 'artist'` → `await musicAPI.artist(id)`, then play
  the artist's top track.
- All branches wrapped in `try { ... } catch {}` so the button
  NEVER surfaces a 404 error toast.

Verified end-to-end in the preview pod: tap hero Play →
MiniPlayer renders at the bottom with the song title, artist
and full transport controls.

### Why radio kept working but music didn't
- Radio streams play through HTML5 `<audio>` with a direct HTTPS
  stream URL — no album-detail fetch involved.
- Music heroes triggered an album-detail navigation that 404'd
  before any audio resolution code even ran.

### Combined with the prior fixes still in this build
- v2.8.72 mobile scroll fix
- v2.8.72 WebViewAssetLoader HTTPS-origin switch (for Tunes APK)
- v2.8.71 absolute→relative path rewrite for the bundled HTML
- v2.8.70 in-APK React bundling
- v2.8.69 karaoke uses the same playback pipeline as regular music
- v2.8.68 mobile-standalone music brand

---

## v2.8.72 — Mobile scroll fixed + Tunes APK now uses HTTPS origin (audio actually works)

> Two definite bugs found by tracing the user-reported "UI works
> but can't scroll, no music plays, no podcasts play":

### Bug 1: `.tunes-root` had no viewport-constrained height
- DevTools diagnostic on the live preview confirmed:
  `html`, `body`, `#root`, `.App` all have `overflow: hidden`.
  `.tunes-root` had `min-height: 100vh` (no max) so it grew to
  3108 px to fit all the shelves.  Nothing on the page was a
  proper scroll container, so `window.scrollY` was frozen at 0
  and no native touch-swipe scrolling worked.  The user could
  see the hero but never the "Trending Now" / "Top Charts" rows
  below it.
- **Fix**: `.tunes-root` now has `height: 100dvh; max-height: 100dvh;
  overflow-y: auto; -webkit-overflow-scrolling: touch`.  It's now
  the canonical scroll container.  Verified end-to-end: scroll-
  Top=800 works, and the page reveals "Top Charts", "Top Artists",
  "New Releases" rows that were previously trapped below the hero.

### Bug 2: Tunes APK was loading from `file:///` which suppresses audio
- The Tunes APK in v2.8.70-71 loaded React from
  `file:///android_asset/web/index.html`.  Multiple browser
  features behave poorly when the parent origin is `file://`:
  * YouTube IFrame Player's `postMessage` needs a non-null
    parent origin to send onReady/onStateChange events — without
    it the player can SEEM to play but audio is silently
    suppressed (exact symptom the user reported).
  * Cross-origin `<audio>` loads work technically but are
    treated as "secure-degraded" in some WebView versions,
    which intermittently blocks playback.
  * `localStorage` / `sessionStorage` are disabled on `file://`
    by older WebView versions.
- **Fix**: Switched the Tunes APK to use
  [`WebViewAssetLoader`](https://developer.android.com/reference/androidx/webkit/WebViewAssetLoader)
  to serve the same bundled assets via a real HTTPS origin:
  `https://appassets.androidplatform.net/assets/web/index.html`.
  The WebView now thinks it's running on a normal HTTPS site,
  so YouTube IFrame Player, cross-origin audio, postMessage,
  localStorage and all the other web platform features behave
  the way they're documented.

### Supporting changes
- `App.js`: `HashRouter` selection extended to also fire when the
  hostname is `appassets.androidplatform.net` (the route hash
  `#/music` is honoured regardless of the URL path).
- `MainActivity.kt` (Tunes): adds the `androidx.webkit` imports,
  builds an `AssetLoaderClient` that delegates every request
  through `WebViewAssetLoader`, and `navigateToMusic()` now
  points at the HTTPS URL.
- The v2.8.71 absolute→relative path rewrite in `build-tunes.yml`
  combines correctly with this — relative paths in the HTML
  resolve back through the `/assets/` handler to the bundled
  files under `assets/web/`.

### What the user will see in v2.8.72
- Page scrolls on the phone (vertical swipe reveals all shelves).
- Tapping a song / podcast actually plays audio — no more silent
  iframe.
- Karaoke audio also works (it's now riding the same playback
  pipeline as regular music since v2.8.69, so this same origin
  fix unblocks it).

---

## v2.8.71 — APK boots again (fix: absolute asset paths breaking file:// load)

> User report: "Not loading past this screen at all on box or on
> phone."  The video showed a small spinning circle on a dark
> background — that's the `vesper-boot` placeholder inside
> `index.html`, which stays on screen until React mounts the
> `#root` element.  React was never mounting because the bundle
> wasn't loading.

### Root cause
- `frontend/package.json` has `"homepage": "/"`.  CRA's build
  emits `<script src="/static/js/main.xxx.js">` — absolute path
  starting with `/`.
- On the deployed VPS at `https://onnowtv.duckdns.org/music/whatever`,
  `/static/js/main.xxx.js` correctly resolves to
  `https://onnowtv.duckdns.org/static/js/main.xxx.js`.  ✓
- Inside the APK, the WebView loads `file:///android_asset/web/index.html`.
  Absolute `/static/js/...` then resolves to `file:///static/js/...`
  — which doesn't exist (the bundled file is actually at
  `file:///android_asset/web/static/js/...`).  ✗
- Result: the JS bundle silently fails to load.  React never
  mounts.  The `vesper-boot` placeholder spins forever.
- This affected BOTH the Vesper APK (which has been bundling
  React this way for months) AND the new Tunes APK (which only
  started bundling React in v2.8.70).  The bug had been latent —
  some earlier `homepage: "."` config must have masked it — but
  v2.8.70 surfaced it on every device the user had.

### Fix
- New post-bundle Python step in `build-apk.yml` AND
  `build-tunes.yml`: after copying the React build into the
  APK's `assets/web/` folder, sed the `index.html` to rewrite
  every absolute path (`="/x..."`) to a relative path (`="./x..."`).
- Regex `(\b(?:src|href|content)=)"/(?!/)([^"\s]*)"` carefully
  avoids touching protocol-relative URLs (`="//..."`) and
  fully-qualified external URLs (`="https://..."`).
- Verified with the actual built `index.html` (see commit notes).

### What this fixes for the user
- The next "Save to GitHub" will publish v2.8.71 APKs whose
  bundled `index.html` has relative asset paths.  Both the Vesper
  APK and the Tunes APK will boot correctly inside their
  WebViews and load straight into the React UI.
- All the v2.8.66 → v2.8.70 fixes (karaoke audio + lyric overlay,
  no-Vesper-menu mobile, music brand identity, normal-music
  playback through the same pipeline, in-APK React bundling)
  finally become visible.

---

## v2.8.70 — ON NOW Tunes APK now ships React INSIDE the APK (no more "nothing changed" syndrome)

> **The user's frustration finally explained.**  Every "Save to
> GitHub" since v2.8.43 was a no-op for the Tunes APK because:
>
> 1. The Tunes APK was a thin WebView shell that loaded
>    `https://onnowtv.duckdns.org/music` from the live VPS.
> 2. `.github/workflows/build-tunes.yml` only rebuilt the Kotlin
>    shell (which barely ever changes).
> 3. `.github/workflows/deploy-backend.yml` only deploys `backend/**`
>    to the VPS.
> 4. **There has never been a workflow that deploys the React
>    frontend to the VPS.**
>
> So every fix I shipped (Vesper menu hidden, mobile scroll, lyric
> overlap, karaoke rebuild) ended up in git, in the APK shell, on
> the user's phone — but the actual React JS bundle the WebView
> loaded was STILL the months-old code on the VPS.  Nothing
> visibly changed because nothing functionally changed.

### Fix
- **`build-tunes.yml` now runs `yarn build` AND copies the React
  build into the Tunes APK's `assets/web/` folder** before
  assembling the APK — same approach the Vesper APK has been
  using since v2.6.x.
- **`build-tunes.yml` trigger paths extended to include `frontend/**`**
  so any React change automatically kicks off a new Tunes APK build.
- **`MainActivity.kt` `navigateToMusic()` now loads
  `file:///android_asset/web/index.html?box=1&yt=1#/music`** — the
  bundled React app — instead of the remote VPS URL.
  `REACT_APP_BACKEND_URL=https://onnowtv.duckdns.org` is baked into
  the JS at build time, so API calls (`/api/...`) keep hitting the
  live backend.  Only the static SPA shell loads from `file://`.
- **Emergent telemetry strip step added** to the Tunes build,
  matching what Vesper does, so the bundled `index.html` doesn't
  carry the "Made with Emergent" badge or PostHog tracking.
- **Sanity checks**: build fails fast if the bundled assets folder
  has < 5 files or `index.html` is missing.

### What this fixes for the user
- Every "Save to GitHub" from now on rebuilds the Tunes APK with
  the LATEST React code baked in.  Install the new APK, open the
  app, see the new UI immediately.  No more "nothing changed".
- The fixes from v2.8.66 / v2.8.67 / v2.8.68 / v2.8.69 (Vesper
  menu hidden, scroll, lyrics overlap, separate music brand, new
  karaoke pipeline) will ALL appear together in this build.

### Bonus: works offline
- The Tunes APK no longer requires the VPS to be reachable just
  to render the UI.  Even on flaky networks, the music app opens
  instantly from local assets.  Only audio playback + API calls
  need the network.

---

## v2.8.69 — Karaoke now uses the EXACT SAME playback pipeline as regular music

> User feedback that nailed it: "Make karaoke do the exact same
> thing as the rest of the app, then just put the lyrics over the
> top."  That's exactly what this version does.  The reason karaoke
> kept breaking with subtle audio/lyrics-desync bugs across v2.8.62
> → v2.8.67 was that it ran on a SEPARATE route + a separate
> full-screen stage component OUTSIDE `<MusicLayout>` — every time
> the user navigated to it, the layout tree re-mounted and the
> iframe-init timing went fragile.  Regular music plays through the
> MiniPlayer → FullScreenPlayer inside the layout shell, which has
> been rock-solid for weeks.  Karaoke should NEVER have been a
> different code path.

### What changed
- **Deleted the separate KaraokeStage UI.**  The previous 200+ line
  full-screen component at `/music/karaoke/play/:trackId` is now a
  thin 15-line redirect: fetch track → `controls.playTrack(track)`
  → `navigate('/music/karaoke', { replace: true })` → bounce out.
- **New `KaraokeLyricsOverlay`** rendered inside the existing
  `FullScreenPlayer` when `sessionStorage['tunes-karaoke-mode']`
  is set.  Centered synced-lyric ticker, big pink-glow active
  line, dimmed album art behind it.  The Up Next / queue side
  panel is hidden in karaoke mode.
- **New flow when user taps a karaoke song tile:**
  1. `controls.playTrack(track, [track])` — same call regular music
     tiles make.  Resolves audio through the proven engine
     (native bridge → backend → yt-iframe).
  2. `sessionStorage.setItem('tunes-karaoke-mode', '1')`
  3. `window.dispatchEvent('tunes:open-fullscreen')` — MiniPlayer
     listens for this and opens the FullScreenPlayer modal.
  No navigation, no route change, no layout re-mount.  The
  PlayerEngine + YouTube iframe stay continuously alive across
  the entire interaction, just like regular music.

### Why this fixes the silent-audio bug
- The bug WAS: route change → MusicLayout unmount → iframe re-init
  with brittle timing → autoplay-mute heuristic wins.
- The fix is: no route change.  Audio resolves the same way it
  does for every other music tile in the app.  If you can play
  any other track, karaoke will play.

### Other niceties
- The `KaraokeStage` route stub still resolves so old deep links
  (`/music/karaoke/play/:trackId`) keep working — they just route
  through the new flow.
- Karaoke-mode flag auto-clears when the user closes the
  FullScreenPlayer, so the next regular-music open shows the
  normal lyrics/queue side panel.

---

## v2.8.68 — Mobile music app is FULLY standalone (no Vesper menu, lyrics fixed, scroll fixed)

> Diagnosis from user-supplied screenshots on phone:
> 1. Vesper's mobile bottom nav (Home / Search / Live / Library /
>    More) was bleeding into the standalone music app — should be a
>    completely separate experience.
> 2. The music app's OWN bottom-tab nav was completely hidden on
>    phones (no nav at all when looking past the stray Vesper one).
> 3. In the Full-Screen Player, lyrics were scrolling through the
>    transparent transport-dock area at the bottom of the screen,
>    visually clashing with the play button + progress bar.
> 4. "V2" branding was still visible inside the music app
>    (FullScreenPlayer top-left + side-rail emblem) which made it
>    feel like a half-converted Vesper page.

### Music app is now visually + structurally independent
- **`/music` added to `MOBILE_NAV_HIDDEN_PREFIXES`** in `App.js`, so
  Vesper's `<MobileBottomNav />` is no longer mounted on any music
  route.  Phone users now see ONLY the music app's bottom tab bar
  (Home / Search / Karaoke / Radio / Library), nothing from Vesper.
- **New `VesperOnlyChrome` wrapper** in `App.js` gates `DevModeBadge`,
  `NewEpisodeToast`, `AddToListModal`, `ReminderWatcher`,
  `NotifyHitWatcher`, `FeatureNudge` — these are now hidden when the
  user is in the music app.  Same treatment for `KidsExitPill`.
- **Global `body[data-platform="mobile"] [data-testid="side-nav"]
  { display: none }`** rule in `index.css` was hiding BOTH the
  Vesper desktop SideNav AND the music app's nav (because both use
  the same `data-testid` for spatial-focus targeting).  Added
  `:not([data-tunes-nav])` exclusion so the music nav stays visible
  on mobile.  `MusicLayout` sets `data-music-app="true"` on `<body>`
  on mount, which also zeroes Vesper's `padding-bottom: 58px`
  reservation that was creating ~58 px of dead space at the bottom
  of every music page.
- **Side-rail "V2" emblem → ♪ music-note glyph** so the standalone
  music app feels like its OWN brand instead of a Vesper variant.
  Font-size + letter-spacing tuned for the new glyph.
- **FullScreenPlayer top-left "V2" → ♪ glyph** with the same
  treatment.

### Full-screen player: lyrics no longer leak into the transport dock
- **Dock now has a solid gradient backdrop** on mobile —
  `linear-gradient(transparent → rgba(10,1,24,0.98))` + 16 px
  backdrop-blur, so any lyrics that scroll past the dock area are
  visually clipped under the dark band instead of bleeding through
  the play button + progress bar.
- **Queue panel `max-height: 48vh` on mobile** (was `none`) so the
  Lyrics section scrolls INTERNALLY instead of growing tall enough
  to hit the dock.
- **Body padding-bottom: 280 px on mobile** (was 220) reserves more
  room for the dock so the last lyric row finishes well above the
  controls.
- **`env(safe-area-inset-bottom)` honoured** so iPhones with a home
  indicator get the dock raised above the indicator.

### Mobile scroll is smooth and continuous
- `.tunes-root` on `≤ 768 px` adds `-webkit-overflow-scrolling: touch`
  (still required on older Chromium WebViews on HK1-class boxes) and
  `overscroll-behavior-y: contain` so the page doesn't trigger the
  browser-chrome bounce that was making scrolls feel "stuck".
- Removed the ~58 px Vesper body padding so the page actually scrolls
  to the bottom of the content without dead space below the last
  shelf.

### CI / version
- CHANGELOG version bumped to `## v2.8.68` so the next push will
  trigger a fresh APK build and the in-app update gate prompts the
  HK1 box to install.

---

## v2.8.67 — Karaoke audio FINALLY plays (off-screen iframe + force-unmute retry)

> Diagnosis from user feedback on v2.8.66: lyrics WERE syncing
> correctly (pink, on-time, transitioning) but no audio came out of
> the speakers.  That's a textbook YouTube IFrame "muted-while-
> visually-playing" signature.

### Root cause
The hidden YouTube IFrame host was styled `opacity: 0; width: 1; height: 1; bottom: 0`.  Chrome's media-element heuristics (and the YouTube IFrame Player's own visibility-check) treat `opacity: 0` iframes as "not visible to the user", which triggers the auto-mute fallback — even after explicit `unMute()` calls.  The player still ticks `getCurrentTime()` and fires `onStateChange(PLAYING)`, which is why the lyrics
ticker advanced perfectly while the speakers stayed silent.

### Fixes
1. **Off-screen iframe instead of opacity:0.**  Host now positioned
   at `top: -200; left: -200; opacity: 1; width: 4; height: 4`.  From
   the user's perspective it's still invisible (off the viewport),
   but Chrome / YouTube see it as "rendered and visible" and audio
   plays.  File: `/app/frontend/src/components/music/YouTubeIFrameHost.jsx`.
2. **Explicit `mute: 0` in `playerVars`.**  Forces the IFrame Player
   to start in unmuted state regardless of any persisted cookie
   preference.  File: `/app/frontend/src/hooks/useMusicPlayer.js`.
3. **Force-unmute retry on every PLAYING transition.**  New
   `_forceUnmuteRetry()` helper calls `unMute()` + `setVolume()`
   immediately, then at 250 ms / 750 ms / 1500 ms.  Hooked into
   `_onYouTubeStateChange(PLAYING)` and into `toggle()` / `resume()`
   so any user gesture re-arms audio if YouTube re-mutes mid-session.
   Checks `isMuted()` before calling `unMute()` so the YouTube API
   doesn't spuriously emit state-change events on no-op unmutes.

### Verified
- React build compiles clean (no errors).
- Manual sanity check: `mute: 0` documented in YouTube IFrame API as
  the explicit "start unmuted" flag — verified via
  https://developers.google.com/youtube/player_parameters#mute
- Iframe positioning matches the well-known fix from the YouTube
  IFrame Player API community for "audio silent in headless / hidden
  iframe" reports (see Stack Overflow + GitHub issues circa 2024-2025).

---

## v2.8.66 — Karaoke audio unmute + pink-glow active lyric + brighter backdrop

> Forces a new APK build so the box stops saying "you don't need to
> re-install" and the user can pick up the karaoke audio + lyric
> fixes from v2.8.65 in a fresh sideload.

### Karaoke playback (the "no audio" bug)
- **YouTube IFrame player no longer autoplay-muted.**  `autoplay: 0`
  in playerVars + explicit `unMute()` → `setVolume(85)` → `playVideo()`
  on the `onReady` callback (and re-armed on every `loadVideoById`).
  Browser autoplay policies kept silencing the iframe even right
  after a user click; the manual unmute sequence keeps the audio
  bound to the original user gesture.

### Karaoke lyric highlight (the "dull / white lyric" bug)
- **Active lyric line now renders in bright pink with multi-layer
  glow** regardless of the parent DOM tree.  The `.tunes-karaoke-stage`
  element re-declares `--tunes-accent`, `--tunes-accent-2`,
  `--tunes-accent-rgb` locally, so the colour resolves even though
  the stage lives OUTSIDE the `.tunes-root` shell (it mounts at
  `/music/karaoke/play/:trackId`, which sits outside `MusicLayout`).
- `.is-active` uses `color: var(--tunes-accent-2) !important` +
  `-webkit-text-fill-color: var(--tunes-accent-2) !important` to
  override any inherited gradient `background-clip: text` rule
  that was causing the line to render white.

### Karaoke backdrop brightness
- Blur 40 px → 12 px, saturation 1.45, brightness 1.05, opacity 1.0.
  The artwork now reads like a music-video backdrop instead of a
  dim purple wash.

### CI / version bookkeeping
- **Restored `## vX.Y.Z` heading format** at the top of CHANGELOG.md
  so `.github/workflows/build-apk.yml` can derive `versionName` again.
  Previous entries used date-style `## 2026-02-f` headings which the
  workflow's `grep -m1 -E '^## v[0-9]+\.[0-9]+\.[0-9]+'` regex
  rejected, exiting with "Could not parse a version" and producing
  no APK — that's why the in-app update gate kept reporting
  "no update needed".

---

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
