# ON NOW TV V2 — Native Android TV Launcher

A pure-native Android launcher that replaces the device's home screen on Android TV / HK1 / generic Android boxes. Built with **RecyclerView + ConstraintLayout + Kotlin** — no Compose, no React-Native, no WebView.

## What's working
- ✅ Launcher manifest — HOME / LEANBACK_LAUNCHER intent filter so it can be set as the default home
- ✅ Top status bar — greeting, OnNow TV V2 wordmark, date, live clock
- ✅ Vertical paginator dots (4 segments, active one in accent colour)
- ✅ Featured content panel — kicker → title (with accent period) → tagline → description → CTA pill
- ✅ Right-side hero illustration — programmatically drawn per dock section
- ✅ 6-tile bottom dock as RecyclerView with horizontal LinearLayoutManager
- ✅ D-pad focus → focused tile glows in section accent colour + scales up 1.04×
- ✅ Featured panel updates live as focus moves
- ✅ Aurora glow background

### Phase 2 added (admin-backend integration)
- ✅ `LauncherRepository` polls `/api/launcher/config` every 5 minutes
- ✅ `NotificationPopup` polls `/api/launcher/notifications/pending` every 30 s and shows a modal
- ✅ Dock tile labels / subs / accent colours driven by backend
- ✅ Dock tile click routing: `target_package` (launch app) or `target_url` (open browser)
- ✅ Apps tile opens `AppsDrawerActivity` — a 4-column RecyclerView grid of admin-managed APKs
- ✅ `ApkInstaller` downloads + fires `ACTION_VIEW` install prompt (FileProvider + `REQUEST_INSTALL_PACKAGES`)
- ✅ Last-known config cached to SharedPreferences for offline cold starts

## Connecting to your backend

The launcher polls `https://onnowtv.duckdns.org/launcher` by default (Contabo VPS, Nginx-proxied to the launcher backend on `127.0.0.1:8002`). Change `LauncherRepository.DEFAULT_BASE_URL` to point at a different host before building, or add a settings screen later that persists the URL in SharedPreferences.

See `/app/launcher-backend/README.md` for backend deployment instructions.

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
│       │   ├── MainActivity.kt          Home screen activity (Phase 2)
│       │   ├── DockAdapter.kt           RecyclerView adapter for the dock
│       │   ├── DockItem.kt              POJO
│       │   ├── FeaturedRegistry.kt      Per-section copy + accent (admin overrideable)
│       │   ├── HeroIllustration.kt      Custom canvas view
│       │   ├── data/
│       │   │   ├── LauncherConfig.kt    Remote payload + JSON parser
│       │   │   └── LauncherRepository.kt OkHttp + StateFlow + SharedPreferences cache
│       │   ├── apps/
│       │   │   └── AppsDrawerActivity.kt Apps grid
│       │   ├── install/
│       │   │   └── ApkInstaller.kt      Download + install flow
│       │   └── notify/
│       │       └── NotificationPopup.kt AlertDialog overlay
│       └── res/
│           ├── layout/                  activity_main, item_dock
│           ├── drawable/                vectors + state lists
│           ├── values/                  colors, strings, styles
│           ├── xml/file_provider_paths.xml
│           └── mipmap-anydpi-v26/       adaptive launcher icon
└── .github/workflows/build-launcher.yml CI to produce a sideloadable APK
```

## How to install
1. Push this branch to GitHub.
2. GitHub Actions workflow `build-launcher.yml` produces `onnowtv-launcher-debug.apk` under the **launcher-latest** release tag.
3. Sideload onto your TV box: `adb install onnowtv-launcher-debug.apk` (or any file-explorer APK installer).
4. On the TV: Settings → Apps → Default apps → Home → select **OnNow TV V2**.

