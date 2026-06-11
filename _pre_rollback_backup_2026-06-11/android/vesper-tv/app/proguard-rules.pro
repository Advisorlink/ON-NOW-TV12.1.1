# ============================================================
#  ON NOW TV V2 — R8 / ProGuard rules
#  ────────────────────────────────────────────────────────────
#  This file is applied ONLY to release builds (see build.gradle.kts
#  `release { isMinifyEnabled = true }`).  Debug builds are
#  unchanged so local development stays frictionless.
#
#  Goals:
#    1. Obfuscate every class / method / field name we don't need
#       to keep — makes static reverse-engineering massively harder.
#    2. Strip BuildConfig debug strings and ALL `Log.d/v/i` calls
#       so the shipping APK leaks no helpful Logcat info.
#    3. Keep ONLY the entry points Android / WebView JS / ExoPlayer
#       reflect on by name — every other symbol becomes `a.b.c.d`.
#
#  TEST THIS LOCALLY before pushing: `./gradlew assembleRelease`.
#  If the app crashes on first launch, the missing keep rule is
#  reported in `app/build/outputs/mapping/release/missing_rules.txt`
#  — read it, add the rule, rebuild.
# ============================================================

# --- Source attribution: keep just enough to debug genuine crash
# --- reports.  Source file names rewritten to `SourceFile` so the
# --- attacker can't see which .kt the obfuscated class came from.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- Strip ALL log calls in release.  Debug calls reveal API
# --- endpoints, JS bridge names and internal state to anyone
# --- running `adb logcat` on a stolen / sideloaded APK.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
}

# --- Aggressive: turn on R8 full mode (already default in AGP 8+,
# --- but kept explicit so future AGP versions don't regress).
-allowaccessmodification
-mergeinterfacesaggressively
-overloadaggressively
-repackageclasses ''

# ====================================================================
#  KEEP RULES — every annotation / API surface Android calls by name.
# ====================================================================

# Application + Activities are referenced by string name in the
# manifest; their names MUST stay stable.
-keep public class tv.vesper.app.OnNowApplication
-keep public class tv.vesper.app.MainActivity
-keep public class tv.vesper.app.VlcPlayerActivity
-keep public class tv.vesper.app.ExoPlayerActivity

# FileProvider authority is `${applicationId}.fileprovider` — keep
# the class name AndroidX expects.
-keep class androidx.core.content.FileProvider { *; }

# JS bridge — every @JavascriptInterface method is called by NAME
# from the bundled React JS.  Obfuscating these would silently break
# every WebView↔native handoff (e.g. `bridge.playInternal(...)`,
# `bridge.setLiveGuide(...)`).  This single rule covers ALL such
# methods, present and future.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class tv.vesper.app.WebAppInterface { *; }

# Media3 / ExoPlayer — uses reflection internally to discover codecs
# and renderers.  Keep the whole tree.
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# libVLC — JNI bridge from native code looks up Java methods by name.
-keep class org.videolan.libvlc.** { *; }
-dontwarn org.videolan.libvlc.**

# OkHttp — uses reflection for certificate pinning + DNS overrides.
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# Jetpack Compose — its compiler plugin already keeps what it needs;
# we just have to silence dontwarn noise here.
-dontwarn androidx.compose.**
-keep class androidx.compose.runtime.** { *; }

# Coil image loader — uses reflection-driven image source dispatch.
-keep class coil.** { *; }
-dontwarn coil.**

# Kotlin stdlib + coroutines — reflection-heavy.
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlin.**
-dontwarn kotlinx.coroutines.**

# Keep all annotation classes (R8 sometimes strips them in full mode
# which breaks `@JavascriptInterface` lookup at runtime).
-keepattributes *Annotation*

# Parcelable contracts — Android creates them via reflection.
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}

# v2.7.82 red-team finding: IntegrityGuard is called DIRECTLY from
# OnNowApplication.onCreate() — NOT via reflection.  The old keep
# rule was an over-cautious carry-over that defeated the obfuscation
# pass on the security class itself.  Removed in v2.7.82 so R8 will
# rename `IntegrityGuard` to `a.b.c` like every other class.  An
# attacker can no longer locate the security code by grepping for
# "IntegrityGuard" or "signingCertMatches" in the obfuscated DEX.
#-keep class tv.vesper.app.security.IntegrityGuard { *; }
