# ============================================================
#  ON NOW TV Live TV — R8 / ProGuard rules (release only)
#  Obfuscates app code; keeps the few API surfaces that are
#  reached by name via reflection / JNI / the manifest.
# ============================================================

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes *Annotation*

# Strip log calls in release.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
}

-allowaccessmodification
-repackageclasses ''

# Manifest-declared components (activities, receiver, application) are
# auto-kept by the AGP-generated rules.  IntegrityGuard is called
# directly (not by name), so let R8 rename it like everything else.

# WorkManager instantiates workers by class name via reflection.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Media3 / ExoPlayer — reflective codec + renderer discovery.
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# libVLC — JNI bridge looks up Java methods by name.
-keep class org.videolan.libvlc.** { *; }
-dontwarn org.videolan.libvlc.**

# OkHttp / Okio.
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# Coil image loader — reflection-driven source dispatch.
-keep class coil.** { *; }
-dontwarn coil.**

# Kotlin stdlib + coroutines.
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlin.**
-dontwarn kotlinx.coroutines.**

# Parcelable contracts.
-keepclassmembers class * implements android.os.Parcelable {
    public static final ** CREATOR;
}
