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
     * Production launcher-backend URL.  MUST match the value used
     * by `LauncherRepository.DEFAULT_BASE_URL` so Vesper hits the
     * same Nginx host that already proxies `/launcher/` → the
     * uvicorn backend on 127.0.0.1:8002.
     *
     * v2.10.53-b — Earlier this fell back to a non-existent
     * placeholder host which caused `UnknownHostException`
     * ("unable to resolve host") on every client.  Production
     * runs on the Contabo VPS at `onnowtv.duckdns.org` behind
     * nginx with a `/launcher` prefix.
     */
    private const val PROD_LAUNCHER_BASE = "https://onnowtv.duckdns.org/launcher"

    /** SharedPreferences key the Launcher uses to override the
     *  production base URL at runtime.  We mirror it so that
     *  if the user ever points their Launcher at a different
     *  backend (e.g. preview pod), Vesper follows automatically. */
    private const val LAUNCHER_PREFS    = "launcher_repo"
    private const val LAUNCHER_BASE_KEY = "launcher.base_url"

    /**
     * Where to fetch the WebView 138 APKM bundle.
     *
     * Resolution order (first hit wins):
     *   1. BuildConfig.LAUNCHER_BACKEND_URL (if the Vesper build
     *      script ever wires one — currently it doesn't, kept here
     *      for future build-variant overrides).
     *   2. The Launcher's own SharedPreferences `launcher.base_url`
     *      key — so a single override flips both apps in sync.
     *   3. The production default `https://onnowtv.duckdns.org/launcher`.
     */
    private fun bundleUrl(ctx: Context): String {
        val buildConfigUrl = runCatching {
            val clazz = Class.forName("${ctx.packageName}.BuildConfig")
            val f = clazz.getField("LAUNCHER_BACKEND_URL")
            (f.get(null) as? String)?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        }.getOrNull()

        val launcherOverride = runCatching {
            // Cross-app SharedPreferences read.  Same `MODE_PRIVATE`
            // store but accessed by name; works because the Launcher
            // and Vesper run in their own UIDs — we are reading a
            // file on a path Android won't share.  Falls through to
            // null when the Launcher isn't installed or the user
            // never opened it, which is what we want.
            ctx.createPackageContext(
                "tv.onnow.launcher",
                Context.CONTEXT_IGNORE_SECURITY,
            ).getSharedPreferences(LAUNCHER_PREFS, Context.MODE_PRIVATE)
                .getString(LAUNCHER_BASE_KEY, null)
                ?.trim()
                ?.trimEnd('/')
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull()

        val base = buildConfigUrl ?: launcherOverride ?: PROD_LAUNCHER_BASE
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
