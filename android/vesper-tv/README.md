# Vesper TV — Android WebView Wrapper

A tiny Kotlin Android app that hosts the Vesper web client in a
fullscreen, immersive WebView. It launches like a native TV app on your
HK1 box, hides the system bars, forces landscape, and forwards the
remote BACK button to in-app history navigation.

The whole thing is **~200 lines of Kotlin** plus icon / theme XML.

---

## What's in here

```
vesper-tv/
├── settings.gradle.kts
├── build.gradle.kts                # root project
├── gradle.properties
├── gradle/wrapper/gradle-wrapper.properties
├── README.md                       # this file
└── app/
    ├── build.gradle.kts
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/tv/vesper/app/
        │   ├── MainActivity.kt
        │   └── VesperWebViewClient.kt
        └── res/
            ├── drawable/
            │   ├── ic_launcher_background.xml   # adaptive icon back
            │   ├── ic_launcher_foreground.xml   # neon-blue "V" mark
            │   └── tv_banner.xml                # 320x180 TV banner
            ├── mipmap-anydpi-v26/
            │   ├── ic_launcher.xml
            │   └── ic_launcher_round.xml
            ├── values/
            │   ├── colors.xml
            │   ├── strings.xml                  # ← edit app_url here
            │   └── themes.xml
            └── xml/network_security_config.xml
```

---

## 1 · Configure the URL it loads

Open **`app/src/main/res/values/strings.xml`** and edit `app_url`:

```xml
<string name="app_url">https://your-vesper-host.example.com/</string>
```

By default it points at the preview deployment. If you deploy your own
copy of Vesper somewhere with HTTPS, change this string and rebuild.

> If you ever need plain `http://` (e.g. local LAN testing), set
> `cleartextTrafficPermitted="true"` in
> `res/xml/network_security_config.xml` *and*
> `usesCleartextTraffic="true"` on `<application>` in the manifest.

---

## 2 · Build the APK

### Option A — Android Studio (easiest)

1. Open Android Studio → *File → Open* → select the **`vesper-tv`**
   folder.
2. Wait for Gradle sync (it'll fetch the Android Gradle Plugin and any
   missing SDK platforms automatically).
3. Plug your HK1 in via USB **or** create a TV emulator.
4. Press the **green Run ▶** button — the APK is built, installed and
   launched.
5. To produce a stand-alone APK file, *Build → Build Bundle(s)/APK(s)
   → Build APK(s)*. The signed-debug APK appears in
   `app/build/outputs/apk/debug/app-debug.apk`.

### Option B — Command line

You'll need:
- **JDK 17** (`java -version`)
- **Android SDK** with `platform-tools` and a build-tools 34 install
  (the easiest way to get it is one Android-Studio install with the SDK)
- `ANDROID_HOME` pointed at the SDK root

```bash
cd vesper-tv

# First time only — generate the gradle-wrapper.jar
gradle wrapper --gradle-version 8.7

# Build a debug APK (sideload-ready, signed with the auto-generated debug key)
./gradlew assembleDebug

# Output:
#   app/build/outputs/apk/debug/app-debug.apk
```

For a release build with your **own** key, drop a keystore in `app/`
and add a `signingConfigs.release { … }` block in
`app/build.gradle.kts`, then `./gradlew assembleRelease`.

---

## 3 · Sideload onto the HK1

### Easiest — Wireless ADB

On the HK1:
1. *Settings → About → click Build* 7 times → developer mode unlocked.
2. *Settings → Developer Options → USB Debugging* **and** *ADB over
   Network* both ON.
3. Note the box's IP (Settings → About → Network).

On your laptop:

```bash
adb connect 192.168.x.x:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n tv.vesper.app/.MainActivity
```

### Alternative — USB cable

Plug the box into your computer with a USB-A → USB-A cable (HK1 boxes
typically expose a USB OTG port). Run the same `adb install` command.

### Alternative — Drop the APK on a USB stick

Copy `app-debug.apk` to a USB key, plug it into the HK1, open the
file with the box's File Manager, *Install*. Allow "install from
unknown sources" if prompted.

---

## 4 · Behaviour on the HK1

- App appears under both **Apps** and the **Leanback / TV row** on
  most HK1 launchers (the manifest declares both intent-filters).
- Launches **landscape, fullscreen, no system bars**.
- **D-pad** works natively (Vesper's React app handles it via
  `useSpatialFocus` so every focusable tile / button is reachable).
- **BACK** on the remote → in-app web history (only exits the app
  when you're already on the home screen).
- **Screen-on** is enforced while the app is foregrounded.

---

## 5 · Versions & SDK

- `compileSdk` 34 (Android 14)
- `targetSdk` 34
- `minSdk` 24 (Android 7.0+) — covers virtually every HK1 box ever
  shipped. Lower it to 21 if you have an unusually old box, but
  some adaptive-icon features will degrade.
- Kotlin 1.9.23, Android Gradle Plugin 8.4.0, Gradle 8.7, JDK 17.

---

## 6 · Roadmap

- [ ] Replace placeholder vector banner & icon with proper artwork once
      the brand finalises.
- [ ] Optional: add Cast support (mediarouter / cast-framework) so the
      box can act as a Chromecast target.
- [ ] Optional: native splash screen so the loading flash before the
      web app paints feels intentional.

---

## License

Personal-use sample wrapper for Vesper. Do whatever you want with it.
