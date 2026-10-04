# ON NOW TV V2 — Product requirements and current state

Updated: 2026-10-04. Historical implementation details previously in this 12,244-line file are preserved in [CHANGELOG.md](CHANGELOG.md). Priorities are in [ROADMAP.md](ROADMAP.md).

## Original product requirements
Continue development of the ON NOW TV V2 app suite:
1. Polish Vesper Movies UI, layouts, navigation and native player interactions.
2. Build ON NOW Trivia as a standalone Android TV interactive party game.
3. Fix backend bugs and maintain the self-hosted launcher environment.

Audience: TV viewers using a D-pad on Android TV boxes, families using the Kids app, phone companion/game participants, and the operator managing boxes through the launcher admin.

## Latest user request (2026-10-04)
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