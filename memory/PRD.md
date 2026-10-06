# ON NOW TV V2 — Product requirements and current state

Updated: 2026-10-06. Historical implementation details previously in this 12,244-line file are preserved in [CHANGELOG.md](CHANGELOG.md). Priorities are in [ROADMAP.md](ROADMAP.md).

## Original product requirements
Continue development of the ON NOW TV V2 app suite:
1. Polish Vesper Movies UI, layouts, navigation and native player interactions.
2. Build ON NOW Trivia as a standalone Android TV interactive party game.
3. Fix backend bugs and maintain the self-hosted launcher environment.

Audience: TV viewers using a D-pad on Android TV boxes, families using the Kids app, phone companion/game participants, and the operator managing boxes through the launcher admin.

## Current user report (2026-10-06) — phone/tablet Play STILL unresponsive
User says TV box works, installed phone/tablet does nothing and shows neither Opening player nor error. User has **not confirmed the previous rebuilt APK was installed**. Prior simulated-bridge tests did not verify the actual Android player. Do NOT call the physical-device issue resolved without new APK/device evidence.

### Second-stage implementation: `touch-player-2`
- `useTouchActivate` + `PlaybackButton` activate on a stationary touch release, so a missing compatibility click cannot swallow Play. Mouse/keyboard keep click semantics. Duplicate compatibility clicks, drag>10px, cancellation, multitouch, disabled controls, long press and out-of-bounds release are guarded. Applied to Autoplay, source picker and source rows.
- Added native `playMedia(requestId,payload)` protocol1 JSON interface, avoiding positional-argument conversion differences and resolving bridge at call time. Existing legacy/party bridges and player routing retained. No assertion that positional conversion was the confirmed device root cause.
- `NativePlaybackSession.kt` validates requests, forwards metadata/resume/alternate streams to the existing player activities, reports received/launched/opened/returned/failed with correlation ID. MainActivity, Exo and VLC signal actual native lifecycle. No stream URLs/credentials in status events.
- `useNativeLaunchGuard` uses explicit native acknowledgements in addition to legacy focus/visibility. Native rejection now gives immediate feedback; opened cancels false watchdog failures even without WebView blur; returned unlocks replay. Exo startup fallback bounded to two retries rather than potentially looping Exo↔VLC.
- Playback details button on title page shows **Web build touch-player-2**, Android version/build, **Protocol1** and last native stage. Dialog portals to document.body to avoid transformed-hero clipping; focus trapped/restored, phone/desktop verified. This identifies whether the updated web/native pieces actually reached the user's device.
- No auth/server credentials/permissions changes. No physical Android playback/decoder verification or APK packaging performed in this environment.

### Verification
- Report iteration_102 found a real Kotlin Activity/MainActivity bridge mismatch. Fixed with guarded MainActivity cast and Boolean rejection; JS handles explicit false. Compiler check uses actual project Kotlin1.9.23 + Compose compiler1.5.13 (standalone compiler initially omitted Compose).
- **PASS: all23 Vesper Kotlin sources compiled against96 actual Android/dependency artifacts**, generated resource-ID placeholders only; not APK resource linking/signing.
- Final frontend tests: **40/40 across5 suites PASS** (touch/cancel/drag/duplicate suppression, modern/legacy/party bridges, late binding, acknowledgements/errors/replay). Final `CI=false yarn build` PASS23.24s, existing unrelated warnings only. Five changed Kotlin files pass brace checks; complete23-file native typecheck passed. Detailed evidence in iteration_102_followup.json.
- Main CDP test deliberately prevented compatibility clicks: trusted touch still produced one correct handoff. Explicit opened status prevented timeout beyond8s without blur. **Native bridge/source endpoints MOCKED in browser tests only**, not actual Android execution.
- Final desktop retest:5 open/close cycles, Tab trap, Escape/Close focus restoration passed. Native false return immediately showed error. Portal covers1920×800 and390×844 with no uncontained overflow. Runtime app integrations are not mocked.

### P0 user verification
Build/install this Vesper APK on the affected phone/tablet. Confirm Playback details shows `touch-player-2` and `Protocol1`, then try Play. If it still fails, the panel's native stage + exact Android version/build is required for further diagnosis. An absent panel/old marker means this code is not running; do not suggest clearing cache as a substitute for delivering the rebuilt APK.

## Previous user request (2026-10-06) — Installed phone/tablet Play taps
User says Play Movie / Autoplay on phones/tablets behaves as if not clicked. Clarified explicitly: **installed app**, not browser.

### Confirmed causes and fixes
- Reproduced with genuine CDP touch + Android WebView UA/OnNowTV marker: a valid fast partial stream was present, but Detail disabled Play until a deliberately slow final source finished. Early touch produced zero native calls; the same touch after completion dispatched playback. Buttons now accept intent immediately, use partial playable results, or queue one tap until the first playable stream/current metadata arrives. Pending state is visible; empty/external-only final results explain unavailability instead of silently doing nothing.
- Stream pick used `useCallback([])` capturing initial `playStream`/metadata/stream list: baseline native payload title was empty. Fresh callback now supplies current title, ID and alternatives, including same-component title changes. Metadata/streams scoped by `${type}:${id}` prevent previous-title handoffs; URL-autoplay/unavailable flags reset per title.
- Device detection treated `OnNowTV/version` in the installed app's UA as a TV signal. Narrow-touch fallback masked it on some portrait phones, but wide phones/tablets could stay TV-classified. `deviceInput.js` strips only the app marker and recognizes Android/iPad tablets regardless of width; genuine AndroidTV/SmartTV/GoogleTV/AFT remain remote mode. Detail's TV auto-focus timers no longer run on handhelds and respect modal traps on TVs.
- `useMoviePlayIntent.js` owns queued user intent; cancelling/changing title/rating block prevents stale launch. `useNativeLaunchGuard.js` shares a single native launch across manual, URL-autoplay and stream-picker paths. Returning from native (window/visibility lifecycle) re-enables explicit replay. If handoff never opens, an 8-second watchdog supplies an error and permits retry—no permanent dead button.

### Verification / limits
- 22/22 unit tests across deviceInput, movie intent and native launch guard PASS. `CI=false yarn build` PASS42.52s with existing unrelated warnings; changed-file lint no errors.
- Main follow-up uses actual `Input.dispatchTouchEvent` and asserts `touchstart.isTrusted`; no synthetic click fallback. Phone390×844 and wide tablet1920×800 APK UAs tested. Partial stream launches before slow source; queued delayed stream launches once; URL autoplay + repeated touches yield one handoff; return/replay works; Autoplay OFF picker works during loading; SPA A→B selection passes B's title/cwId; external-only results explain failure; TV Enter still launches once.
- Report `iteration_101.json` preserves first test-agent duplicate-handoff finding. Corrected/shared launch guard and complete follow-up evidence in `iteration_101_followup.json`. Test agent's unavailable `page.touchscreen.tap` did not prevent CDP touch—main explicitly enabled touch emulation and verified trusted events.
- **MOCKED in tests only:** native OnNowTV player bridge, selected stream/metadata/delay responses, cloud push to protect shared profile backups. No application API mocked and no real credentials changed. This proves tap→correct native handoff, NOT physical ExoPlayer decoding or real-provider availability.
- No Kotlin/server/auth changes in this task. **Rebuild/install Vesper APK** (bundled web assets) and verify on actual phone/tablet; existing installed APK will not contain this fix automatically.

## Previous user request (2026-10-04) — Live TV recovery + Music motion
User reports an unidentified Live TV crash (no photo/log yet); wants **Log in again** beside Retry to restart login without reinstalling; wants Music scrolling/navigation as smooth as updated Vesper.

### Delivered changes and evidence
- Native loader has Retry + Log in again, with explicit D-pad neighbour links. Login reset is available during loading as well as on failure. It cancels retained loader/child jobs, invalidates the attempt, clears the provider session/guide caches and clears the activity task before LoginActivity. Favourites/collections/preferences are preserved.
- CrashActivity has the same recovery actions and retains diagnostic text for a photo. It already runs in `:crash`; its reset action sends an explicit extra to MAIN process before cache fast-path/auth processing. LiveTVApp now avoids normal EPG/player initialization in the crash process; diagnostic Intent payload bounded. No claim that the existing 800 ms main-process kill caused the unidentified device crash.
- Retained loader ownership, cancellation propagation, stale progress/navigation checks, session-generation guarded cache publication. EPG workers/streaming writer cannot publish a previous session after reset. CacheDirectorySwap preserves the previous guide when either rename fails and keeps a backup if rollback also fails.
- Music has scoped 180 ms interruptible scrolling with one owner and 120 ms focus transforms enabled in Android/low-end mode. Removed conflicting shelf scroll-snap; row navigation retains X bookmarks. Pointer/wheel/resize/route changes cancel pending motion. Vesper scrolling paths unchanged.
- Stable Music command hooks do not subscribe catalogue/track/artist/search/library/radio/karaoke controls to playback progress. Browsing components subscribe only to track/play-state changes; mini/fullscreen player keep live progress subscriptions via useSyncExternalStore.
- Browser follow-up: real catalogue, TV CSS classes present, horizontal offsets 0 → 302 → 488 demonstrate intermediate motion; reversals settled with no queued focus; vertical target fully visible; reduced-motion instant; controlled local media clock + mini-player pause/resume passed with four engine subscriptions. Desktop1920×800 and mobile390×844 checked; no uncontained overflow (intentional offscreen carousel children remain clipped).
- Test report iteration_100 plus main follow-up. Four scoped API tests passed; all six pure JVM CacheDirectorySwap carry-over/failure/rollback tests passed; all seven modified Kotlin files pass brace checks. Web production build passed (30.70 s, existing warnings). **Final SDK-backed Kotlin typecheck PASS: all 42 Live TV Kotlin files against 79 real dependencies.** Compiler caught the coroutine's `Intent(this, ...)` receiver; corrected to `this@MainActivity`, rerun passed. Not an APK packaging or device-runtime test.
- Full APK packaging initially blocked by missing SDK, then ARM64 aapt2 mismatch. Main installed official SDK34/dependencies and ran a direct SDK-backed Kotlin typecheck using generated resource-ID placeholders; this is not a signed/linked APK or device test. Do not change project Gradle settings for this machine-specific verification workaround.
- No application APIs mocked. Browser playback timing verified using **controlled local test media**, not real external stream availability. Some external Googlevideo sources returned 403/decode errors in the browser; those service failures remain separate from navigation work.
- Testing agent accidentally ran unrelated backend security/admin tests: original results retained in iteration_100. Existing lockout/proxy-origin observations are not claimed fixed; user previously deferred security work. Out-of-scope tests now explicit skips. Disposable TEST_iter100_dd8d6a03 account removed and credentials handoff updated; real accounts unchanged.

### Still needed
- **Photo/stack trace or reproducible trigger for the actual Live TV crash.** Recovery hardening is not proof that the unidentified crash itself is eliminated.
- Build/install updated Live TV and Music APKs; physical remote, crash/reset and playback validation pending.

## Previous user request (2026-10-04)
Remove the duplicate startup/login update popups, fit every update on a single non-scrolling dialog, and smooth the top navigation bar. User resumed after a credit interruption; work and testing continued.

### Implemented and verified
- Reproduced actual duplicate with a delayed rejected cached session: old notice appeared before `/me` completed, disappeared at login, appeared again after login. Independent automatic welcome tour and auth-remounted boot splash could add further interruptions.
- `AuthContext.jsx` exposes `sessionVerified` for UI readiness, without changing server auth, credentials, token storage or providers. Notice waits for real `/me` validation, or successful login plus completion of cloud restore lookup. Optimistic cached authentication alone cannot trigger it.
- `lib/releaseNotice.js` provides a synchronous, versioned session/in-memory claim on actual presentation, persistent version acknowledgement on dismissal. `WhatsNewModal.jsx` waits for boot/other focus traps and eligible Vesper routes. Explicit restore choice remains intact; update notes do not stack over it.
- `OnboardingGate` is manual-only via existing Settings → Help → Replay. `BootSplash` mounts once outside `LoginGate`, not again on sign-in.
- Release version `1.5.1`, 14 concise entries: all 12 preceding release topics plus smooth browsing and the single-popup change. `ReleaseNotesPanel.jsx` + `releaseNotes.css`: responsive 3-column TV / 2-column phone grid, every title/detail visible, no scroll pane or clipping, D-pad/Tab focus trap and native Back dismissal.
- `useTopNavMotion.js` + `topNavMotion.css`: one 160 ms translate3d focus indicator; fixed-size buttons; 120 ms icon transforms; persistent fade labels. Local left/right sibling navigation avoids whole-page geometric scanning. TV/low-end CSS no longer forces the menu's movement to snap. Reduced-motion preference respected. Spatial engine marks actual redirected focus synchronously.
- Main report `test_reports/iteration_99.json`; follow-up `iteration_99_followup.json`. Real auth smoke 3/3 pass. Stale-token, once-only notice, reload/relogin, excluded products, layout and top-nav movement checks passed. Main follow-up proved manual tour works both with click and D-pad after selecting Help; no Settings fix needed.
- Restore-ordering test used a **MOCKED cloud snapshot and push response in browser automation only** to avoid mutating real profile backups. Verified pending lookup → restore dialog alone → restore/reload → one update notice → no duplicate after dismissal/reload. No application API is mocked.
- Required screenshots: 1920×800 panel 1120×453 and 390×844 panel 366×577; all 14 entries with no text or horizontal overflow. No physical TV test performed; rebuild/install APK remains required.
- Modified-file lint: zero errors; two existing App.js warnings. Final `CI=false yarn build` passed in 24.12 seconds with existing suite hook/bundle-size warnings, no compilation errors (`/tmp/vesper-popup-topnav-build.log`). No new/changed credentials.

## Previous user request (2026-10-02)
Deeply investigate why horizontal navigation in Vesper feels chunky compared with Kids in the supplied recording; fix underlying causes without removing trailers, and explain what was wrong.
Recording: https://customer-assets-jt897jd0.emergentagent.net/job_rebrand-app-5/artifacts/26fu3mgh_az_recorder_20261002_163651.mp4

## Architecture
- React frontend in `frontend/`, FastAPI in `backend/`, MongoDB via existing environment configuration.
- Vesper React bundle is packaged in `android/vesper-tv/`; native Kotlin shell provides ExoPlayer, trailer extraction and remote controls. Browser preview does not update installed APK assets.
- `android/onnowtv-livetv/` and `android/onnowtv-launcher/` provide dedicated Live TV and launcher clients.
- `launcher-backend/`: self-hosted FastAPI launcher proxy/admin. Do not conflate its VPS status with preview health.
- Shared spatial focus engine: `frontend/src/hooks/useSpatialFocus.js`; held-key pacing: `lib/dpadPacer.js`.
- Content: TMDB metadata and synthetic category APIs, Xtream IPTV/VOD matching, Stremio-style addons. Existing keys/credentials are in environment files; testing setup in `test_credentials.md`.
- Main implemented experiences: Home, Movies/TV grids, Box Sets/Studios, Anime hub, Watch Together, profile-scoped settings/library/continue-watching, Kids, Music and Trivia. The fork handoff's “Trivia not started” conflicts with historical changelog and existing code: verify before recreating anything.

## Current navigation fix — implemented, browser verified
- Added `hooks/useShelfMotion.js`: single shelf-owned position update after width reconciliation; 180 ms compositor-only FLIP translations on visible poster wrappers. Preview remains in slot 2 (slot 1 at first tile/narrow widths), no per-frame width animation, no motion queue. New input retargets from current visual positions. Reduced motion and touch cancellation respected.
- `Shelf.jsx`: wrapper cells, explicit `data-focus-scroll="shelf"`; `useSpatialFocus.js` and preview reveal no longer issue competing horizontal corrections. `PosterTile.jsx` memoized so unchanged cards don't render on each selection.
- `FocusHeroBillboard.jsx` isolates hero-follow state from Home/Collection/Anime. `useFocusHero.js` waits 200 ms before hero/image work, rather than rerendering the whole page every 90 ms.
- `TrailerHoverPreview.jsx`: 280 ms cancellable dwell BEFORE any candidate lookup/extraction; no next-tile extraction, HD prefetch only after a second of actual playback, immediate old-video pause, stale-result/unmount guards. Existing fullscreen, resume and nonplaying OK-to-title behaviour retained.
- TV-specific CSS allows inexpensive poster transform transitions while expensive blur/shadow transitions remain disabled.
- Regression finding repaired: compact phone TopNav (More menu with remaining destinations/autoplay), mobile clipping removed; fullscreen button fixed-position rule corrected. TV menu unchanged. Home row walker respects menu focus traps.

## Verification and limits
- Testing agent: `/app/test_reports/iteration_98.json`; main-agent follow-up: `/app/test_reports/iteration_98_followup.json`.
- Real populated Home: eight right presses at ~100 ms spacing generated **0 trailer-related requests while moving**, 2 after settling; baseline generated 12 across navigation/settle. Preview rAF samples improved but are NOT a controlled TV hardware benchmark.
- Reversals, single-focus/one-wide-card invariants, no queued focus movement after release, reduced motion, Collection/Anime/Kids smoke covered.
- TV/low-end CSS verified in browser: 180 ms row animations and 120 ms focus transforms run, finish with zero live row animations. 4× CPU throttling held-key test stayed on the same tile 800 ms after release.
- Desktop 1920×800 and phone 390×844 checked. Phone controls fit; no uncontained/page-level horizontal overflow. Offscreen children inside intentional horizontal rails are clipped, as designed.
- Fullscreen/resume was verified with a **MOCKED native bridge/candidate response and local video in tests only**. Playback resumed advancing from the carried timestamp. No production APIs or application integrations were replaced with mocks.
- Real browser YouTube playback can be embed-blocked; external addon CORS/502/cloud-sync warnings remain environment/provider limitations, not proven causes of the local row jumps. Nonplaying preview Enter correctly opens the title.
- Modified-file ESLint passes. Final `CI=false yarn build` passed (23.71 s, 2026-10-02), matching the existing APK workflow. Existing hook-dependency warnings remain; strict `CI=true` treats those warnings as errors. No compilation errors in the production bundle.
- Final compact-menu check passed: nine Down presses remain trapped, Escape restores More focus, selecting Anime navigates correctly after back-trap cleanup.
- **Still required:** rebuild/install Vesper APK and verify on the actual TV box. No Kotlin code changed in this task; real decoder/GPU/remote performance cannot be certified from desktop automation.

## Previous delivered work to preserve
- Anime removed from automatic default Home injection; hub stays in top/side navigation and is available through Add a category. Existing Movies/TV chip order retained.
- Settings left-pane navigation and Home row arrangement; custom categories including Hallmark/Christmas/Anime; static Tips. What's New is now once per release, never twice across startup/login; manual welcome tour remains available.
- Studios sizing, Box Set hero/trailer behaviour, Coming Soon hover trailers, trailers-off without preview rectangle; native keyboard in Host a Party search.
- Native player improvements from earlier sessions still require rebuilt APKs to reach boxes.

## Working constraints
- Preserve current visual language and deterministic D-pad navigation; do not “fix” performance by silently removing trailers.
- Do not modify auth/keys, production services or deferred security findings as part of navigation work.
- Native changes require local brace/syntax checks and on-device verification. This task changes only the web bundle.
- Read ROADMAP before picking new work. Test credentials were not created or changed in this session.