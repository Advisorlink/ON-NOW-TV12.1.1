# Vesper Native TV — development migration

Separate pure-native Kotlin Android Views/RecyclerView/Media3 Android TV-box client, with native Compose for the original player UI. No WebView, JavaScript bridge or React runtime. The original React frontend and `android/vesper-tv` remain unchanged. Package `tv.onnowtv.vespernative` coexists with the current Vesper application.

**v0.6.0 is a PRIVATE, unfinished test build, NOT a pixel-perfect/full-parity release.** It contains the user's explicitly approved test-login prefill. Keep the APK and private download link private.

## Implemented
- v0.6.0: bounded trailer URL/metadata warmup for current + next two titles, immediate retry after failed warmup, preview-only400ms start buffer. Consistent Geist headings, measured text/action separation, V2 accent labels/JetBrains captions; remembered initial season focus and episode glow/description. Poster readiness and actual-focus-only hero changes prevent loading rows stealing focus or replacing hero text. Real TV startup latency remains unmeasured.
- Screenshot pass: larger12-slotnav/Homehero/posters/fullbackdrop; essentialHomeorderandlocalreorder/hide/customrows; plainV2details+adjacentBackHome; namedepisodesafterseasonselection; actorfilmography/Downsimilar/Back; networkgradients and studio/boxset billboard+trailerrails.
- Trailerrepair:VISIBLETextureViewbeforeprepare,transparentshutter,device/backendrace,muxedorMP4/M4Apairfallback,firstframe10sec/buffer8secwatchdogs. Testsprovesurfacevisibility/race/cancellation, NOTactualTVdecoding.
- Nine-sectionsettings,colourthemes,Home/playback/preferences,help/tips/version,local64KiB-boundedSAFbackup/import(noauth/clouddata),signout/profile. Calendar/WatchTogether/fullskins/extraenginesexplicitlyunmigrated.
- V2 TV logical viewport across raw720p/1080p/4K box densities, six-column cell-filling grids and whole shelf paging below fixed hero; original player/global density unchanged.
- Native wide Home trailer slot,280msdwell, pinned original NewPipe0.26.3 device extraction with existing backend fallback, Media3 texture video/audio, same-player fullscreen/Back, focus/lifecycle cleanup and local auto-trailer preference. No web fallback. Actual device decoding/extraction acceptance pending.
- Movie detail plain V2 title/metadata/synopsis/actions, adjacent Back/Home, native cast/actor filmography/similar rows, explicit manual Choose stream. Add to Library visibly disabled under cloud-read-only constraint.
- Existing login, encrypted token-only Keystore/DataStore session, saved-profile picker/PIN gate and read-only cloud Library.
- Native icon navigation, fixed focus-follow billboard, network/studio rails, original artwork/Geist font, Movies/TV genre grids, Box Sets, Anime, title search and detail/season selection.
- Real stream selection and native Media3 play/pause/seek, source swap, embedded audio/subtitle tracks, aspect, timeout/retry, local account/profile-specific resume and decoder release on Back/background. Original wrapper's Compose overlay/dock/loading/track/source UI adapted into the standalone player; not full engine/feature parity.
- Master and TV initial-source autoplay preferences, navbar/settings controls, ranked ready-source launch once, manual selection and More Info bypass. Never automatically starts an uncached download; next-episode automation remains pending.
- Expanded V2 screen styling for login/profile/PIN/navigation/billboard/grids/Library/detail/search/settings/dialogs. Debounced title search, type filters and locally scoped recent queries; not full actor/history parity.
- Eight profile-local colour palettes, Home side menu, original wrapper exit dialog resources with Stay focused by default.
- V2 TV focus lift (tiles1.07/120ms ease-out, pills1.04), cached two-layer accent glow, no per-frame blur/layout, cleanup on focus reversal/detach/recycle, system reduced-motion support. Title/meta now overlay the poster artwork.
- Optional debug-only login prefill from native `.env`; password stays masked/editable and Sign in is manual. Release constants remain empty/disabled. No account credentials or PINs are changed.

## Still incomplete
Profile CRUD/custom avatars, library editing/reminders, actor search/full-personpages, full search history, calendars, Watch Together, voice/AI, full layout skins and remaining settings/add-on management. Filmography/similarrowsandHomearrangementsareimplemented. Device trailer/visual acceptance, full player buffer/speed/SDR controls, progressive source cascade, VLC/MPV engines, external subtitles and up-next remain incomplete. Cloud writes remain disabled.

Actual TV screenshots, physical remote/Keystore behaviour, playback/codec/DRM compatibility and low-end-box performance are **not verified in this container**. See `../../memory/VESPER_NATIVE_PARITY.md` for the release gates.

## Build
JDK 17, SDK 34, Gradle 8.7. Requires Android 8+, landscape-only, Leanback and generic box launchers. No phone/tablet-specific UI.

Set `VESPER_API_BASE_URL` and `TMDB_IMAGE_BASE_URL` in the environment or `vesper-native/.env`. `.env.example` records the existing public origins; use it only if no native `.env` exists. Missing configuration fails the build; nohostfallbackorpreviewswitch. TheimagebaseisrequiredforrawTMDBfilmography/recommendationposterpaths. No server secrets belong in an APK; this PRIVATE debug artifact is the expressly approved test-login-prefill exception, with release prefill stripped.

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

ARM64 pod (preserve `/app/.cache/native-tools`):
```sh
ANDROID_HOME=/app/.cache/native-tools/sdk ANDROID_SDK_ROOT=/app/.cache/native-tools/sdk \
  ./gradlew :app:testDebugUnitTest :app:assembleDebug \
  -Pandroid.aapt2FromMavenOverride=/app/.cache/native-tools/aapt2
/usr/bin/apksigner verify --min-sdk-version 23 --max-sdk-version 34 --verbose app/build/outputs/apk/debug/app-debug.apk
/usr/bin/zipalign -c 4 app/build/outputs/apk/debug/app-debug.apk
```

Artifact: `app/build/outputs/apk/debug/app-debug.apk`, versionCode **10**, versionName **0.6.0-native-private**, configured production origin. Backend `GET /api/download/vesper-native-apk` serves `vesper-native-v0.6.0-private.apk` only with the correct `X-Download-Token` header. Anonymous/wrong/query-only requests404. This is the same development signing identity as earlier native builds, not the old wrapper's release identity. The separate release APK is unsigned build-verification output, not the private download artifact.

Private browser download: `/api/download/vesper-native#<capability-from-backend-env>`. The fragment is cleared immediately and sent only in a fetch header; it never reaches access logs as a URL query. Same-tab `sessionStorage` retains access for15min so reload/Back no longer greys out Download. Invalid/expired/rejected access is removed; a new fragment replaces it. Blocked storage still allows the initial page's download; transient network errors permit retry. No localStorage/cookie persistence, no-store/no-referrer, restrictive CSP. Do not copy a real capability into source, reports or logs. The old public APK link intentionally no longer works.

Download delivery follow-up (2026-10-19): the helper no longer fetches the full APK into a JS blob. Authenticated `POST /api/download/vesper-native-ticket` returns a small5min HMAC-signed APK-only URL; the browser streams `GET /api/download/vesper-native-file?ticket=<temporary-ticket>` directly, with HEAD/single-byte-range/If-Range support. A10s authorization timeout restores Retry; **Download directly** remains visible for a manual click. Ticket URLs are temporary bearer links and must stay private; original long-lived capability is never put in them. App access queries are redacted; upstream log redaction is unverified. Original header-only APK GET remains available.25 backend tests and9 browser scenarios pass, including REAL file downloads/hash checks; simulations only for failure/expiry cases. APK and515frontendbaseline unchanged. Evidence `test_reports/iteration_128.json` and final backend/browser results referenced in PRD.

Debug prefill flags: `DEBUG_PREFILL_ENABLED`, `DEBUG_PREFILL_USERNAME`, `DEBUG_PREFILL_PASSWORD` in native `.env` or build environment. Omitted/disabled opt-in leaves debug fields empty; release always has false/empty constants. Values are recoverable from an enabled debug APK: private testing only. Existing credential values are documented privately in `memory/test_credentials.md`, not in source.

The explicit apksigner range exercises both signature schemes. With actual minSdk 26 it normally checks v2 only and reports v1=false even when the JAR signature is present/valid. Runtime minSdk remains **26**.

This pod's `/root/.gradle` points to `/tmp/vesper-gradle-cache` after the workspace filled. No SDK/native-tools files were deleted. `scripts/sync-reference-assets.mjs` copies original immutable network and exit-dialog assets into native; it never writes to the frontend or original wrapper.

## Data safety
Cloud pull is read-only and account/profile scoped; unknown keys are preserved. No native push/profile-save/library-write path exists. Local native preferences/playback positions do not overwrite or upload cloud data. Network failures do not bypass profile/PIN gates. Kids profiles remain excluded. No passwords persisted, backups disabled, API HTTPS-only. Stream media may use existing providers' HTTP URLs; no TLS validation bypass. Provider headers stay on the original stream origin and the account JWT is never sent to media/artwork hosts.

## Verification
Latest v0.6.0 (2026-10-19): **132 cases,131PASS/0 failures/errors/1 existing hardware Keystore skip**; final changes passed twice including forced full rerun. Debug/release assembly,20 backend checks,7 private-helper browser scenarios pass. Real browser downloads before/after reload, replacement fragment and blocked storage match local APK bytes; expiry/network/storage error scenarios are **MOCKED only in tests**. Main repaired agent127's persisted test harness and reran all7; `artifacts_iter127_download_browser/result.json` supersedes its earlier `tool_results.json`. Signature v1/v2/alignment/CRC pass; release prefill false/empty, actual approved credential bytes absent from release DEX. APK26,783,596bytes, SHA256 `2a6685032694bef78ba381af50ed38145154d8300d2c0375d460e4b2db43d4e4`. All515 original frontend files, wrapper and full movie player unchanged. Cloud remains read-only. Evidence `../../test_reports/vesper_native_polish/final-verification.json`. **Physical trailer latency/decoder/TV pixels/focus acceptance remains pending.** Older results below are historical.

Latestv0.5.0:125nativecases **124PASS/0failures/1hardwareKeystoreskip**, debug/releaseassemblepass,20realAPIchecksPASS. Actualactor→filmography→Downsimilar→Backtestcaught/fixedrawTMDBparser;6herooverflowsfixedbeforemeasure;visiblefirst-frameTextureView,slowdevice/fastbackendrace,fallback/cancel,episodes,Homeprefs,atomicboundedbackupchecked. Releaseprefillfalse/emptyandsecretbytesabsentfromDEX;v1/v2signature/alignment/CRC/protectedbytesverified. APK26,994,236bytes SHA256`33cf6df663b1affa20f44b28edd6008a3e7464b816abad4e262fd6ee800b0372`. All515frontendoriginalhashesmatch;wrapperunchanged. Evidence`../../test_reports/vesper_native_screenshots/final-verification.json`;agent125initialfailuressuperseded. **NativeGPU/TVdecoder/pixels/SAFpickeracceptancePENDING.** Oldresultsbelowhistorical.

Latest v0.4.0(2026-10-18):115nativecases **114PASS/1hardwareKeystoreskip/0failures**, debug/releaseassembly pass;13APIchecks viareport124, finaldownload6re-run after final APK. Additional tests prove physicalpixel-preserving density normalization, exactsixcolumn cellgeometry, paddedpage snap, longdetails/cast bounds and manualstreambypass. v1/v2signatures/CRC/alignment pass; prefill debug-only/releasefalse-empty. All515originalfrontend filesmatch, wrapperunchanged. FinaldebugAPK26,836,919bytes SHA256`ef91faf5d51e7d3d56f39a91c610c6d9394b3b1d296b0a59aa6c5a5352207abd`. Evidence`../../test_reports/vesper_native_video/final-verification.json`; uservideo notes`../../memory/VESPER_TV_VIDEO_COMPARISON.md`. RealTVpixels/trailers/decoder/remote acceptance still PENDING, not fullparity.

Latest v0.3.0 (2026-10-17): **99 native tests pass**,1 pre-existing hardware Keystore skip,0 failures/errors (100 total; forced fresh test run);6 real private-download API checks pass including byte equality. Release BuildConfig prefill fields empty/disabled; v1/v2 signature/alignment/CRC verified. Anonymous download helper fits desktop/phone; this is delivery UI smoke, NOT native rendering evidence. All515 original frontend snapshot hashes match after exact lockfile restoration. Evidence: `../../test_reports/vesper_native_visual/final-verification.json`. SHA256 `1d745109e4061bc6c3b82e219e0900f6db7d8f0943837b79f5783344c3a4e3cf` (25,065,413 bytes). Real TV-box visual/player/remote acceptance remains pending.

Previous v0.2.1:88 native tests pass,1 hardware skip;6 download checks and actual browser-click artifact equality pass. Evidence `../../test_reports/vesper_native_focus/final-verification.json`; superseded for current artifact by the v0.3.0 report above.

Final v0.2.0: **80 native tests pass**, one existing hardware Keystore skip, no failures/errors, build succeeds; five real preview API contract checks pass. Signature/alignment/CRC/download-byte equality verified. Measured long-title hero controls fit standard/compact geometry. `final-verification.json` is authoritative over intermediate reports. APK SHA256: `593ab6f9344afc9f28b56e82ce361bc28be8bcb194d534eb6986d95c204cd6fc` (12,395,449 bytes).

Evidence: `../../test_reports/iteration_120.json`, `iteration_121.json`, and `../../test_reports/vesper_native_parity/`. Native JVM/Robolectric test fixtures are **MOCKED only in tests**; runtime APIs are real. Browser screenshots are reference smoke checks only, not native visual evidence. Physical-device acceptance remains required before any pixel-perfect/playback/performance claim.