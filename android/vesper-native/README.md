# Vesper Native TV — development migration

Separate fully native Kotlin/RecyclerView Android TV-box client. Current Vesper (`../vesper-tv`) and its React frontend are unchanged. This project's package is `tv.onnowtv.vespernative`, so both can coexist.

**This is an unfinished migration, not full Vesper parity.** Current slice is real sign-in/session restore, essential catalogue shelves, submitted title search, read-only metadata/episodes and native Back/focus navigation. Playback, profiles, library/cloud migration, trailers and the remaining Vesper features are not connected yet. There are no runtime mock catalogues and no WebView fallbacks. See `../../memory/VESPER_NATIVE_PARITY.md` for the feature ledger and release gates.

## Build

JDK 17, SDK 34, Gradle wrapper 8.7. Set `ANDROID_HOME` / `ANDROID_SDK_ROOT` to your SDK. Set `VESPER_API_BASE_URL` to your HTTPS backend origin; local builds alternatively read the existing `frontend/.env` `REACT_APP_BACKEND_URL`. Configuration is required; there is no hardcoded production fallback.

```sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

Local output: `app/build/outputs/apk/debug/app-debug.apk`. Signing is standard local development signing, not the existing Vesper release identity. No publishing/update workflow modifies the existing launcher.

The app declares both Leanback and ordinary launcher categories for generic TV boxes, is landscape-only, and requires Android 8 or newer like current Vesper. No phone/tablet layout is included.

## Data safety

JWT only is encrypted with Android Keystore AES-GCM into private DataStore. No password is persisted. Backups are disabled. HTTPS required and bearer headers go only to the configured API, not artwork hosts. Requests are cancellable, bounded and run off the main thread. Invalid sessions return to sign-in; network outages preserve the saved token for Retry.

**No cloud sync/backup/library writes** are implemented in this first stage. A tested read-only `profile/` compatibility layer pulls the existing account-scoped snapshot, preserves unknown fields, applies existing PIN/key semantics and projects library/Continue Watching data. It is **not connected to the picker/home UI yet**. Android cannot automatically read another package's private WebView storage. Complete persistent migration and a safe merge must precede any push.

Artwork and launcher icon/banner are reused from current Vesper. Geist is bundled from Google Fonts under its SIL Open Font License (`app/src/main/assets/licenses/Geist-OFL.txt`).

## Verification

Latest checkpoint: 45 native tests (44 pass, one hardware Keystore test skipped), five REAL external API contract checks pass, debug build/signature/alignment/CRC/appId pass. See `../../test_reports/vesper_native/final_verification.json`. MockWebServer fixtures exist only in test source; runtime API calls are real.

Native tests and build evidence belong in `../../test_reports/vesper_native/`. Browser screenshots document the reference only. Actual TV visual parity, remote interaction, streaming/decoding and low-end-box performance require physical-device validation; successful compilation is not proof of those properties.