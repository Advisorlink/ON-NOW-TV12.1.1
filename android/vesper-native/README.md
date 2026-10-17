# Vesper Native TV — development migration

Separate pure-native Kotlin Android Views/RecyclerView/Media3 Android TV-box client, with native Compose for the original player UI. No WebView, JavaScript bridge or React runtime. The original React frontend and `android/vesper-tv` remain unchanged. Package `tv.onnowtv.vespernative` coexists with the current Vesper application.

**v0.3.0 is a PRIVATE, unfinished test build, NOT a pixel-perfect/full-parity release.** It contains the user's explicitly approved test-login prefill. Keep the APK and private download link private.

## Implemented
- Existing login, encrypted token-only Keystore/DataStore session, saved-profile picker/PIN gate and read-only cloud Library.
- Native icon navigation, fixed focus-follow billboard, network/studio rails, original artwork/Geist font, Movies/TV genre grids, Box Sets, Anime, title search and detail/season selection.
- Real stream selection and native Media3 play/pause/seek, source swap, embedded audio/subtitle tracks, aspect, timeout/retry, local account/profile-specific resume and decoder release on Back/background. Original wrapper's Compose overlay/dock/loading/track/source UI adapted into the standalone player; not full engine/feature parity.
- Master and TV initial-source autoplay preferences, navbar/settings controls, ranked ready-source launch once, manual selection and More Info bypass. Never automatically starts an uncached download; next-episode automation remains pending.
- Expanded V2 screen styling for login/profile/PIN/navigation/billboard/grids/Library/detail/search/settings/dialogs. Debounced title search, type filters and locally scoped recent queries; not full actor/history parity.
- Eight profile-local colour palettes, Home side menu, original wrapper exit dialog resources with Stay focused by default.
- V2 TV focus lift (tiles1.07/120ms ease-out, pills1.04), cached two-layer accent glow, no per-frame blur/layout, cleanup on focus reversal/detach/recycle, system reduced-motion support. Title/meta now overlay the poster artwork.
- Optional debug-only login prefill from native `.env`; password stays masked/editable and Sign in is manual. Release constants remain empty/disabled. No account credentials or PINs are changed.

## Still incomplete
Profile CRUD/custom avatars, library editing/reminders, actor search/cast, full search history, calendars, Watch Together, voice/AI, hover trailers, all layout skins/home arrangements and remaining settings/add-on management. Player visual acceptance and full buffer/speed/SDR controls, progressive source cascade, VLC/MPV engines, external subtitles and up-next remain incomplete. Cloud writes remain disabled.

Actual TV screenshots, physical remote/Keystore behaviour, playback/codec/DRM compatibility and low-end-box performance are **not verified in this container**. See `../../memory/VESPER_NATIVE_PARITY.md` for the release gates.

## Build
JDK 17, SDK 34, Gradle 8.7. Requires Android 8+, landscape-only, Leanback and generic box launchers. No phone/tablet-specific UI.

Set `VESPER_API_BASE_URL` in the environment or `vesper-native/.env`. `.env.example` records the requested production origin; use it only if no native `.env` exists. Missing native configuration fails the build; it never silently switches to the preview service. No credentials/server secrets belong in an APK.

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

Artifact: `app/build/outputs/apk/debug/app-debug.apk`, versionCode **7**, versionName **0.3.0-native-private**, production origin. Backend `GET /api/download/vesper-native-apk` serves `vesper-native-v0.3.0-private.apk` only with the correct `X-Download-Token` header. Anonymous/wrong/query-only requests404. This is the same development signing identity as earlier native builds, not the old wrapper's release identity.

Private browser download: `/api/download/vesper-native#<capability-from-backend-env>`. The fragment is cleared immediately and used only in a fetch header; it never reaches access logs as a URL query. No localStorage/cookie persistence, no-store/no-referrer, restrictive CSP. Do not copy a real capability into source, reports or logs. The old public APK link intentionally no longer works.

Debug prefill flags: `DEBUG_PREFILL_ENABLED`, `DEBUG_PREFILL_USERNAME`, `DEBUG_PREFILL_PASSWORD` in native `.env` or build environment. Omitted/disabled opt-in leaves debug fields empty; release always has false/empty constants. Values are recoverable from an enabled debug APK: private testing only. Existing credential values are documented privately in `memory/test_credentials.md`, not in source.

The explicit apksigner range exercises both signature schemes. With actual minSdk 26 it normally checks v2 only and reports v1=false even when the JAR signature is present/valid. Runtime minSdk remains **26**.

This pod's `/root/.gradle` points to `/tmp/vesper-gradle-cache` after the workspace filled. No SDK/native-tools files were deleted. `scripts/sync-reference-assets.mjs` copies original immutable network and exit-dialog assets into native; it never writes to the frontend or original wrapper.

## Data safety
Cloud pull is read-only and account/profile scoped; unknown keys are preserved. No native push/profile-save/library-write path exists. Local native preferences/playback positions do not overwrite or upload cloud data. Network failures do not bypass profile/PIN gates. Kids profiles remain excluded. No passwords persisted, backups disabled, API HTTPS-only. Stream media may use existing providers' HTTP URLs; no TLS validation bypass. Provider headers stay on the original stream origin and the account JWT is never sent to media/artwork hosts.

## Verification
Latest v0.3.0 (2026-10-17): **99 native tests pass**,1 pre-existing hardware Keystore skip,0 failures/errors (100 total; forced fresh test run);6 real private-download API checks pass including byte equality. Release BuildConfig prefill fields empty/disabled; v1/v2 signature/alignment/CRC verified. Anonymous download helper fits desktop/phone; this is delivery UI smoke, NOT native rendering evidence. All515 original frontend snapshot hashes match after exact lockfile restoration. Evidence: `../../test_reports/vesper_native_visual/final-verification.json`. SHA256 `1d745109e4061bc6c3b82e219e0900f6db7d8f0943837b79f5783344c3a4e3cf` (25,065,413 bytes). Real TV-box visual/player/remote acceptance remains pending.

Previous v0.2.1:88 native tests pass,1 hardware skip;6 download checks and actual browser-click artifact equality pass. Evidence `../../test_reports/vesper_native_focus/final-verification.json`; superseded for current artifact by the v0.3.0 report above.

Final v0.2.0: **80 native tests pass**, one existing hardware Keystore skip, no failures/errors, build succeeds; five real preview API contract checks pass. Signature/alignment/CRC/download-byte equality verified. Measured long-title hero controls fit standard/compact geometry. `final-verification.json` is authoritative over intermediate reports. APK SHA256: `593ab6f9344afc9f28b56e82ce361bc28be8bcb194d534eb6986d95c204cd6fc` (12,395,449 bytes).

Evidence: `../../test_reports/iteration_120.json`, `iteration_121.json`, and `../../test_reports/vesper_native_parity/`. Native JVM/Robolectric test fixtures are **MOCKED only in tests**; runtime APIs are real. Browser screenshots are reference smoke checks only, not native visual evidence. Physical-device acceptance remains required before any pixel-perfect/playback/performance claim.