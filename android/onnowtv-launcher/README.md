# ON NOW TV V2 — Native Android TV Launcher

A pure-native Android launcher that replaces the device's home screen on Android TV / HK1 / generic Android boxes. Built with **RecyclerView + ConstraintLayout + Kotlin** — no Compose, no React-Native, no WebView.

## Project layout
```
android/onnowtv-launcher/
├── build.gradle.kts         project-level config
├── settings.gradle.kts
├── gradle.properties
├── app/
│   ├── build.gradle.kts     app module
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/tv/onnow/launcher/
│       │   ├── OnNowApp.kt              Application class
│       │   ├── MainActivity.kt          Home screen activity
│       │   ├── DockAdapter.kt           RecyclerView adapter for the dock
│       │   ├── DockItem.kt              POJO
│       │   ├── FeaturedRegistry.kt      Per-section copy + accent
│       │   └── HeroIllustration.kt      Custom canvas view (right-side art)
│       └── res/
│           ├── layout/                  activity_main, item_dock
│           ├── drawable/                vectors + state lists
│           ├── values/                  colors, strings, styles
│           └── mipmap-anydpi-v26/       adaptive launcher icon
└── .github/workflows/build-launcher.yml CI to produce a sideloadable APK
```

## What's working in this scaffold
- ✅ Launcher manifest — HOME / LEANBACK_LAUNCHER intent filter so it can be set as the default home
- ✅ Top status bar — greeting, OnNow TV V2 wordmark, date, live clock
- ✅ Vertical paginator dots (4 segments, active one in accent colour)
- ✅ Featured content panel — kicker → title (with accent period) → tagline → description → CTA pill
- ✅ Right-side hero illustration — programmatically drawn per dock section (Live TV stadium scene, browser globe, apps grid, etc.)
- ✅ 6-tile bottom dock as RecyclerView with horizontal LinearLayoutManager
- ✅ D-pad focus → focused tile glows in section accent colour + scales up 1.04×
- ✅ Featured panel updates live as focus moves (title swap, accent animation)
- ✅ Aurora glow background (Variation 1 from the design exploration)

## What's not wired yet (Phase 2)
- ⏳ Backend integration — admin-curated dock items, icons, wallpapers (separate FastAPI project at `/app/launcher-backend/`)
- ⏳ APK installer flow — admin pushes APK URL → launcher downloads → Android install prompt
- ⏳ Popup notifications — backend trigger → launcher shows fullscreen modal
- ⏳ Custom wallpaper support — admin uploads background image, launcher swaps the aurora glow for it

## How to install
1. Push this branch to GitHub.
2. GitHub Actions workflow `build-launcher.yml` produces `onnowtv-launcher-debug.apk` under the **launcher-latest** release tag.
3. Sideload onto your TV box: `adb install onnowtv-launcher-debug.apk` (or any file-explorer APK installer).
4. On the TV: Settings → Apps → Default apps → Home → select **OnNow TV V2**.

## Phase 2 plan (after user confirms the visual is correct on the box)
1. New FastAPI service at `/app/launcher-backend/` with admin endpoints:
   - `GET  /api/launcher/config` — current dock items, wallpaper URL, APK manifest
   - `POST /api/admin/icons` — upload/replace tile icon
   - `POST /api/admin/wallpaper` — upload/replace background
   - `POST /api/admin/apks` — add/remove sideloadable app entry
   - `POST /api/admin/notify` — broadcast a popup to all launchers
2. Small admin dashboard (could be React or plain HTML) hosted by the same backend.
3. Launcher pulls `/api/launcher/config` every 10 min + on resume; pulls `/api/launcher/notify` every 60 s.
4. APK installer flow uses `Intent.ACTION_INSTALL_PACKAGE` with `REQUEST_INSTALL_PACKAGES` permission already declared in the manifest.
