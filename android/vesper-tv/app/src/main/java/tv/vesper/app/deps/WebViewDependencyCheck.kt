package tv.vesper.app.deps

import android.app.AlertDialog
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.widget.Toast

/**
 * WebViewDependencyCheck
 * ──────────────────────
 * Runs once on Vesper TV launch.  Inspects the currently active
 * Android System WebView provider — if its `versionCode` (major
 * version, e.g. *138* for Chrome 138 family) is below the
 * required minimum, raises a friendly install prompt:
 *
 *     "WebView 138 is required to render Vesper correctly.
 *      Install now?"
 *
 * Tapping "Install" fires the implicit Intent
 * `tv.onnow.launcher.ACTION_INSTALL_APKM` with EXTRA_URL pointing
 * at the Launcher backend's distribution endpoint — the
 * Launcher's in-house APKM installer (v2.10.53) takes over from
 * there.  No third-party APK Mirror Installer is required.
 *
 * Designed to be a single function call from MainActivity.onCreate().
 */
object WebViewDependencyCheck {

    private const val TAG = "WebViewDependencyCheck"
    private const val PREFS = "vesper-webview-dep-v1"
    private const val KEY_DISMISSED_FOR_VERSION = "dismissed_for_minor_version"

    /** Required major version (e.g. 138 → Chrome 138 / WebView 138). */
    const val REQUIRED_MAJOR = 138

    /** Implicit action handled by the Launcher's InstallApkmActivity. */
    private const val ACTION_INSTALL_APKM = "tv.onnow.launcher.ACTION_INSTALL_APKM"
    private const val EXTRA_URL   = "tv.onnow.launcher.extra.URL"
    private const val EXTRA_TITLE = "tv.onnow.launcher.extra.TITLE"

    /**
     * Where to fetch the WebView 138 APKM bundle.  The Launcher
     * Admin hosts it under a stable path; we resolve the host at
     * runtime from BuildConfig if available, otherwise fall back
     * to the production launcher domain.
     */
    private fun bundleUrl(ctx: Context): String {
        val host = runCatching {
            val clazz = Class.forName("${ctx.packageName}.BuildConfig")
            val f = clazz.getField("LAUNCHER_BACKEND_URL")
            (f.get(null) as? String)?.trimEnd('/')
        }.getOrNull()
        val base = host ?: "https://launcher.onnowtv.tv"
        return "$base/api/system-deps/webview-138.apkm"
    }

    /**
     * @return true when the prompt was shown (and so the caller
     *         should skip its own startup UI until the user
     *         interacts), false when the device is fine.
     */
    fun checkAndPromptIfNeeded(activity: android.app.Activity): Boolean {
        val current = readWebViewMajor(activity)
        Log.i(TAG, "WebView major detected: $current  (required ≥ $REQUIRED_MAJOR)")
        if (current == null) {
            // WebView provider missing entirely — definitely prompt.
            showPrompt(activity, currentMajor = null)
            return true
        }
        if (current >= REQUIRED_MAJOR) return false

        // Don't nag the user if they already dismissed for this
        // exact installed version in this session install.
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val dismissedFor = prefs.getInt(KEY_DISMISSED_FOR_VERSION, -1)
        if (dismissedFor == current) {
            Log.i(TAG, "User already dismissed for installed major $current — skipping prompt.")
            return false
        }
        showPrompt(activity, currentMajor = current)
        return true
    }

    /* ───────── internals ───────── */

    private fun showPrompt(activity: android.app.Activity, currentMajor: Int?) {
        val title = "WebView $REQUIRED_MAJOR required"
        val msg = buildString {
            if (currentMajor == null) {
                append("Android System WebView is missing on this device.  ")
            } else {
                append("Your current WebView is version $currentMajor.  ")
            }
            append("Vesper needs version $REQUIRED_MAJOR or newer to render videos correctly.  ")
            append("Install now?")
        }
        AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(msg)
            .setCancelable(true)
            .setPositiveButton("Install") { _, _ ->
                launchInstaller(activity)
            }
            .setNegativeButton("Not now") { _, _ ->
                activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putInt(KEY_DISMISSED_FOR_VERSION, currentMajor ?: 0)
                    .apply()
            }
            .show()
    }

    private fun launchInstaller(activity: android.app.Activity) {
        val intent = Intent(ACTION_INSTALL_APKM).apply {
            putExtra(EXTRA_URL,   bundleUrl(activity))
            putExtra(EXTRA_TITLE, "Android System WebView $REQUIRED_MAJOR")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Log.w(TAG, "Launcher's APKM installer not found — is the Launcher installed?")
            Toast.makeText(
                activity,
                "ON NOW TV Launcher v2.10.53+ is required to install the WebView update.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    /**
     * Resolve the *major* version (first segment of versionName)
     * for the active WebView provider package.  We look up the
     * package returned by `WebView.getCurrentWebViewPackage()` on
     * Android 8+, falling back to scanning the canonical providers
     * (`com.google.android.webview`, `com.android.webview`,
     * `com.android.chrome`) on older devices.
     */
    private fun readWebViewMajor(ctx: Context): Int? {
        val pm = ctx.packageManager
        val pkg = currentProviderPackage(ctx)
        val candidates = listOfNotNull(
            pkg,
            "com.google.android.webview",
            "com.android.webview",
            "com.android.chrome",
        ).distinct()
        for (name in candidates) {
            try {
                val info = pm.getPackageInfo(name, 0)
                val major = parseMajor(info.versionName)
                if (major != null) return major
            } catch (_: PackageManager.NameNotFoundException) {
                // Try next candidate.
            }
        }
        return null
    }

    private fun currentProviderPackage(ctx: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return runCatching {
            android.webkit.WebView.getCurrentWebViewPackage()?.packageName
        }.getOrNull()
    }

    private fun parseMajor(versionName: String?): Int? {
        if (versionName.isNullOrBlank()) return null
        val firstSegment = versionName.substringBefore('.')
        return firstSegment.toIntOrNull()
    }
}
