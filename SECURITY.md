# ON NOW TV V2 — Security & Anti-Tamper Policy

Everything in place to make rebranding / reverse-engineering / theft of this
APK as painful as possible. Nothing is hack-proof, but the cumulative cost of
breaking each of the layers below is high enough that nearly every casual
attacker will give up, and a determined one will leave very visible artifacts.

## TL;DR — what an attacker has to defeat

| # | Layer | What they have to do to bypass |
|---|---|---|
| 1 | **R8 + ProGuard obfuscation** | Manually rename ~thousands of obfuscated class / method / field references and re-derive the call graph |
| 2 | **Log stripping** | Find leaked Logcat info to anchor on — there is none in release builds |
| 3 | **Package-name pin** | Either preserve `tv.onnowtv.app` (kills side-by-side installs) OR patch the IntegrityGuard binary |
| 4 | **Signing certificate pin** | Patch the IntegrityGuard SHA-256 constant — but R8 packed it inside an obfuscated class, you have to find it first |
| 5 | **Debugger detection** | Statically remove the `Debug.isDebuggerConnected()` check from obfuscated code — and we have multiple checkpoints |
| 6 | **Frida detection** | Hide the `gum-js-loop` thread and patch `/proc/self/maps` — requires kernel-level access |
| 7 | **Xposed detection** | Hide `de.robv.android.xposed.XposedBridge` from `Class.forName` |
| 8 | **TLS cert pinning** | Strip the pin from `network_security_config.xml` AND re-sign the APK (kills layer 4 again) |
| 9 | **No backups** | Can't extract cached state via `adb backup` |
| 10 | **No WebView file:// access** | Can't pivot from JS XSS to disk reads |
| 11 | **Cleartext forbidden** | Can't downgrade TLS to plaintext for sniffing |
| 12 | **Native libs not extracted** | Slightly harder to swap `libvlcjni.so` |
| 13 | **Periodic re-checks** (v2.7.82) | Survive a randomised 4-12 min re-audit — every check fires AGAIN mid-session.  Attaching Frida after start gets caught |
| 14 | **FLAG_SECURE on player + main** (v2.7.82) | Can't screenshot / record / cast the running app.  Recents shows a black thumbnail |
| 15 | **Process-UID integrity** (v2.7.82) | Catches UID-remapping attacks via Magisk delegated UIDs |
| 16 | **Magisk Hide-resistant detection** (v2.7.82) | Catches Magisk even when MagiskHide is on, via mount-table fingerprints |
| 17 | **Emulator detection** (v2.7.82) | Soft warning today; one-line edit promotes to hard kill if you ever need to block emulator farming |
| 18 | **Build watermark** (v2.7.82) | Every CI build carries an immutable git SHA + build timestamp baked into `BuildConfig.GIT_SHA` / `BuildConfig.BUILD_TS`.  A leaked APK can be forensically traced back to the exact CI run.  Use it for DMCA / legal action |

The attacker has to break **all** of these and produce an APK that runs cleanly.
Breaking any single one is non-trivial; breaking the whole stack consistently is
weeks of dedicated work.

---

## What was changed (v2.7.80 security pass)

### 1. R8 + ProGuard — enabled in release builds
**File:** `android/vesper-tv/app/build.gradle.kts`

```kotlin
buildTypes {
    release {
        isMinifyEnabled   = true   // ← R8 obfuscation ON
        isShrinkResources = true   // ← drop unreferenced resources
        ...
    }
}
```

Every class / method / field that is NOT explicitly preserved becomes
`a.b.c.d` in the shipping APK.  An attacker pulling the APK with `apktool`
will see:

```
class a extends android.app.Application {
    public void a();
    public void b(Context c);
}
```

…instead of `OnNowApplication` / `attachBaseContext` / `onCreate`.

**File:** `android/vesper-tv/app/proguard-rules.pro` — full rewrite. Strips
`Log.d`, `Log.v`, `Log.i`, `Log.w` calls.  Keeps only Android entry points,
`@JavascriptInterface` methods (the WebView bridge), libVLC JNI surface,
ExoPlayer, and Coil.  Aggressive flags enabled:

```
-allowaccessmodification
-overloadaggressively
-mergeinterfacesaggressively
-repackageclasses ''
```

### 2. IntegrityGuard — tamper detection
**File:** `android/vesper-tv/app/src/main/java/tv/vesper/app/security/IntegrityGuard.kt` (NEW)

Runs ONCE on cold start (via `OnNowApplication.onCreate`) and HARD-KILLS the
process on any of:

- **Wrong package name** — `getPackageName()` ≠ `tv.onnowtv.app`. Catches
  attackers who rename the package to install side-by-side with the original.
- **Wrong signing certificate** — SHA-256 of the X.509 signing cert ≠
  `0E:16:E2:97:66:DF:36:09:60:2F:A7:C9:8F:E6:C2:29:3E:B0:09:D2:44:16:11:13:46:81:B7:91:85:82:D1:03`.
  Catches anyone who re-signs the APK with their own keystore (which is what
  every repackaging attack has to do).
- **Debugger attached** — `Debug.isDebuggerConnected()` or
  `Debug.waitingForDebugger()`. Catches `jdb` / `lldb` / IDE attaches.
- **Frida** — checks for `gum-js-loop` / `gmain` / `linjector` threads AND
  `frida-agent` / `frida-gadget` in `/proc/self/maps`.
- **Xposed** — checks if `de.robv.android.xposed.XposedBridge` is loadable.
- **Root detection** — soft warning only (most cheap TV boxes ship rooted,
  killing here would punish legitimate users).

Debug builds skip every check so local development isn't disrupted.

### 3. Manifest hardening
**File:** `android/vesper-tv/app/src/main/AndroidManifest.xml`

- `android:allowBackup="false"` (already)
- `android:fullBackupContent="false"` (NEW)
- `android:dataExtractionRules="@xml/data_extraction_rules"` (NEW)
- `android:extractNativeLibs="false"` (NEW — faster install + harder to swap `libvlcjni.so`)
- `android:usesCleartextTraffic="false"` (CHANGED — was `true`)

**File:** `android/vesper-tv/app/src/main/res/xml/data_extraction_rules.xml` (NEW)

Blocks `adb backup`, Google Drive backups, and Android 12+ device-transfer
restores from copying the app's data.

### 4. Network Security — TLS cert pinning
**File:** `android/vesper-tv/app/src/main/res/xml/network_security_config.xml`

```xml
<domain-config>
    <domain includeSubdomains="true">onnowtv.duckdns.org</domain>
    <pin-set>
        <pin digest="SHA-256">pWSzCFKSFRvIHePnUFhCxm8izwaWUGFnW2Obl7tTbo4=</pin>
        <pin digest="SHA-256">AAAA…</pin>   <!-- placeholder backup slot -->
    </pin-set>
    ...
</domain-config>
```

The pin is the SubjectPublicKeyInfo SHA-256 of the current Let's Encrypt
certificate's public key. Pinning the **public key** instead of the cert means
the pin stays valid across LE's 90-day renewals — most ACME clients reuse the
same key.

A MITM attempt with a rogue cert (e.g. a corporate proxy CA or a phishing CA)
fails the pin check and OkHttp / Android's TLS engine closes the connection
before any data leaves the device.

### 5. WebView hardening
**File:** `android/vesper-tv/app/src/main/java/tv/vesper/app/MainActivity.kt`

```kotlin
allowFileAccess                = false   // was: true
allowContentAccess             = false   // was: false
allowFileAccessFromFileURLs    = false   // NEW
allowUniversalAccessFromFileURLs = false // NEW
setDownloadListener { _ -> /* block */ } // NEW
```

`file:///android_asset/` URLs (used to load the bundled React app) still work
— Android always allows asset/res URLs regardless of `allowFileAccess`.

Result: an XSS in the WebView CANNOT escalate to reading `/data/data/tv.onnowtv.app/`
files, opening `content://` URIs from other apps, or chaining cross-origin
JS into asset/res reads.

### 6. Crash-log hardening (existing)
Already in place from earlier work: `OnNowApplication.attachBaseContext` installs
an uncaught-exception handler that writes to internal storage. With backups
disabled, even these logs can no longer be pulled via `adb backup`.

---

## What this does NOT protect against

Be honest about the limits.

- **The bundled React JS** (`assets/web/static/js/*.js`) is **minified** by
  Webpack but **not** encrypted.  An attacker can pretty-print the JS bundle
  and read your React component names, API endpoint paths, etc.  Mitigation:
  the JS bundle never contains secrets — everything sensitive is server-side.
- **Stream URLs from Stremio addons** travel over the network and can be
  intercepted at the upstream provider.  This is a property of how Stremio
  works, not something the app can fix.
- **Brand/logo assets** are inside the APK at `res/drawable/`.  An attacker
  CAN extract the PNGs.  Mitigation: copyright + DMCA takedown is your legal
  recourse, not a code-level one.

---

## How to enable the hardened build

The release build type already includes everything above.  CI is currently
building **debug** APKs (no R8, IntegrityGuard skipped) for development speed.
To ship the hardened version, change ONE line in `.github/workflows/build-apk.yml`:

```diff
-      - run: ./gradlew assembleDebug --no-daemon ...
+      - run: ./gradlew assembleRelease --no-daemon ...
```

…and update the `find … -name "*-debug.apk"` line below it to look for
`-release.apk`.  That's the only change needed.

Local test: `cd android/vesper-tv && ./gradlew assembleRelease`.  The signed
release APK lands at `app/build/outputs/apk/release/app-release.apk`.

---

## How to verify the protections after building

After installing the release APK on a TV box:

1. **Logcat is silent** — `adb logcat -s OnNowTV` shows nothing useful.
2. **`adb backup tv.onnowtv.app` produces an empty archive.**
3. **`apktool d onnowtv-v2-release.apk` reveals obfuscated class names** (`a.b.c.d`).
4. **Re-signing the APK with your own keystore and reinstalling** → the app
   immediately exits on launch (signing-cert mismatch).
5. **Frida-trace -U tv.onnowtv.app** → app exits before Frida can attach.
6. **TLS proxy (mitmproxy / Charles)** → the app refuses to connect to the
   backend; everything else (TMDB, streams) still works because they aren't
   pinned.

---

## Rotating the signing certificate

If you ever need to switch keystores (e.g. publishing to Play Store with a new
key):

1. Generate the new keystore.
2. Sign a one-off APK with the new key.
3. Extract the new cert's SHA-256:
   ```
   keytool -exportcert -alias <new-alias> -keystore <new-keystore> -file new.der
   sha256sum new.der
   ```
4. Update the `EXPECTED_SIGNING_CERT_SHA256` byte array in `IntegrityGuard.kt`.
5. Update the SPKI pin in `network_security_config.xml` (only if the BACKEND
   cert is changing too — the signing cert and backend cert are independent).
6. Build + ship.  Existing installs continue to work; the new installs
   trust both keys until you remove the old one.

---

## Quick reference — files touched in this pass

```
android/vesper-tv/app/build.gradle.kts                      (R8 enabled)
android/vesper-tv/app/proguard-rules.pro                    (full rewrite)
android/vesper-tv/app/src/main/AndroidManifest.xml          (cleartext off, extractNativeLibs off, dataExtractionRules)
android/vesper-tv/app/src/main/res/xml/network_security_config.xml  (cert pinning)
android/vesper-tv/app/src/main/res/xml/data_extraction_rules.xml    (NEW — backup deny)
android/vesper-tv/app/src/main/java/tv/vesper/app/security/IntegrityGuard.kt  (NEW)
android/vesper-tv/app/src/main/java/tv/vesper/app/OnNowApplication.kt        (wire IntegrityGuard)
android/vesper-tv/app/src/main/java/tv/vesper/app/MainActivity.kt            (WebView hardening)
SECURITY.md                                                 (this file)
```
