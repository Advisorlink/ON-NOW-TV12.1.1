# Vesper Native TV — development migration

Separate pure-native Kotlin Android Views/RecyclerView/Media3 Android TV-box client. No WebView, JavaScript bridge or React runtime. The original React frontend and `android/vesper-tv` remain unchanged. Package `tv.onnowtv.vespernative` coexists with the current Vesper application.

**v0.2.0 is an unfinished test build, NOT a pixel-perfect/full-parity release.**

## Implemented
- Existing login, encrypted token-only Keystore/DataStore session, saved-profile picker/PIN gate and read-only cloud Library.
- Native icon navigation, fixed focus-follow billboard, network/studio rails, original artwork/Geist font, Movies/TV genre grids, Box Sets, Anime, title search and detail/season selection.
- Real stream selection and native Media3 play/pause/seek, source swap, embedded audio/subtitle tracks, aspect/speed, timeout/retry, local account/profile-specific resume and decoder release on Back/background.
- Eight profile-local colour palettes, Home side menu, original wrapper exit dialog resources with Stay focused by default.

## Still incomplete
Profile CRUD/custom avatars, library editing/reminders, actor search/cast, calendars, Watch Together, voice/AI, hover trailers, all layout skins/home arrangements and remaining settings/add-on management. The player is **not** the full original overlay, automatic source cascade, VLC/MPV engines, external subtitles or up-next flow. Cloud writes remain disabled.

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

Artifact: `app/build/outputs/apk/debug/app-debug.apk`, versionCode **5**, versionName **0.2.0-native-dev**, production origin. Backend `GET /api/download/vesper-native-apk` serves it as `vesper-native-v0.2.0.apk`, without caching. This is the same development signing identity as v0.1.3, not the old wrapper's release identity.

The explicit apksigner range exercises both signature schemes. With actual minSdk 26 it normally checks v2 only and reports v1=false even when the JAR signature is present/valid. Runtime minSdk remains **26**.

This pod's `/root/.gradle` points to `/tmp/vesper-gradle-cache` after the workspace filled. No SDK/native-tools files were deleted. `scripts/sync-reference-assets.mjs` copies original immutable network and exit-dialog assets into native; it never writes to the frontend or original wrapper.

## Data safety
Cloud pull is read-only and account/profile scoped; unknown keys are preserved. No native push/profile-save/library-write path exists. Local native preferences/playback positions do not overwrite or upload cloud data. Network failures do not bypass profile/PIN gates. Kids profiles remain excluded. No passwords persisted, backups disabled, API HTTPS-only. Stream media may use existing providers' HTTP URLs; no TLS validation bypass. Provider headers stay on the original stream origin and the account JWT is never sent to media/artwork hosts.

## Verification
Final v0.2.0: **80 native tests pass**, one existing hardware Keystore skip, no failures/errors, build succeeds; five real preview API contract checks pass. Signature/alignment/CRC/download-byte equality verified. Measured long-title hero controls fit standard/compact geometry. `final-verification.json` is authoritative over intermediate reports. APK SHA256: `593ab6f9344afc9f28b56e82ce361bc28be8bcb194d534eb6986d95c204cd6fc` (12,395,449 bytes).

Evidence: `../../test_reports/iteration_120.json`, `iteration_121.json`, and `../../test_reports/vesper_native_parity/`. Native JVM/Robolectric test fixtures are **MOCKED only in tests**; runtime APIs are real. Browser screenshots are reference smoke checks only, not native visual evidence. Physical-device acceptance remains required before any pixel-perfect/playback/performance claim.