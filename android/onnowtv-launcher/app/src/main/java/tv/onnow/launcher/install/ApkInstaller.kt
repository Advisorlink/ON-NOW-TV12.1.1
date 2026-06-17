package tv.onnow.launcher.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * ApkInstaller
 * ────────────
 * Downloads an APK file from a URL (either a local backend path or
 * a remote https URL) to the app's cache directory, then prompts
 * the user to install it.
 *
 * v2.10.32 — UNINSTALL-FIRST behaviour.
 *
 *   Why: Android refuses to upgrade a package across signing-key
 *   changes — the user sees "App not installed because another one
 *   is installed already".  This happens any time the previously
 *   installed APK was signed with a different keystore than the new
 *   one (sideload → CI build, dev key → release key, two different
 *   CI workflows, etc.).
 *
 *   What changed:
 *     1. After download we parse the APK to extract its package name.
 *     2. We check whether that package is already installed on the
 *        device.
 *     3. If it IS installed AND it's not the launcher itself:
 *        → fire the system uninstall prompt (`ACTION_DELETE`)
 *        → register a one-shot PACKAGE_REMOVED receiver
 *        → when removal completes, automatically fire the install
 *          prompt for the freshly-downloaded APK
 *     4. If it's NOT installed: fire the install prompt directly.
 *     5. If it IS the launcher updating itself: skip the uninstall
 *        (we'd kill ourselves before the install fires) and rely
 *        on the system's signature-matching upgrade path.
 *
 *   The user always sees the system dialogs — this is required by
 *   Android security policy.  We're only chaining them.
 *
 * Requires:
 *   • `android.permission.REQUEST_INSTALL_PACKAGES` (declared in
 *     AndroidManifest.xml).
 *   • A FileProvider entry that exposes the cache dir (so the
 *     URI passed to the installer is a `content://` URI on
 *     Android N+, NOT a `file://` URI which crashes since N).
 */
object ApkInstaller {

    private const val TAG = "ApkInstaller"
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * @return null on success; an error message on failure.
     */
    suspend fun downloadAndInstall(
        ctx: Context,
        apkUrl: String,
        suggestedName: String? = null,
        onProgress: ((Int) -> Unit)? = null,
    ): String? = withContext(Dispatchers.IO) {
        try {
            val target = File(ctx.cacheDir, "downloads").apply { mkdirs() }
            val name = (suggestedName?.takeIf { it.endsWith(".apk", true) }
                ?: "install_${System.currentTimeMillis()}.apk").replace(Regex("[^A-Za-z0-9_.-]"), "_")
            val out = File(target, name)

            val req = Request.Builder().url(apkUrl).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext "HTTP ${resp.code}"
                val body = resp.body ?: return@withContext "empty body"
                val total = body.contentLength().coerceAtLeast(1L)
                var read = 0L
                body.byteStream().use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            read += n
                            onProgress?.invoke(((read * 100L) / total).toInt())
                        }
                    }
                }
            }
            // v2.10.32 — decide whether to uninstall first.
            handleInstall(ctx, out)
            null
        } catch (e: IOException) {
            Log.e(TAG, "download failed", e)
            "Download failed: ${e.message}"
        } catch (t: Throwable) {
            Log.e(TAG, "install failed", t)
            "Install failed: ${t.message}"
        }
    }

    /**
     * Decide between three install paths based on what is currently
     * on the device.  Always runs on the UI thread implicitly (it
     * fires Activity intents); safe to call from any thread because
     * `startActivity` is documented to be thread-safe.
     */
    private fun handleInstall(ctx: Context, apk: File) {
        val pkgName = readApkPackageName(ctx, apk)
        val isSelf = pkgName != null && pkgName == ctx.packageName
        val installed = pkgName != null && isPackageInstalled(ctx, pkgName)

        Log.i(
            TAG,
            "handleInstall pkg=$pkgName installed=$installed isSelf=$isSelf apk=${apk.absolutePath}",
        )

        if (pkgName == null || !installed || isSelf) {
            // Brand-new install OR the launcher updating itself —
            // skip uninstall and rely on the standard install flow.
            // For self-update, uninstalling the launcher first would
            // kill our process before we could fire the install.
            launchInstallPrompt(ctx, apk)
            return
        }

        // Existing third-party app being updated — uninstall first,
        // then auto-fire the install when PACKAGE_REMOVED arrives.
        uninstallThenInstall(ctx, pkgName, apk)
    }

    /** Read the package name from a downloaded APK file via
     *  PackageManager.getPackageArchiveInfo.  Returns null if the
     *  file isn't a valid APK or the parser fails. */
    private fun readApkPackageName(ctx: Context, apk: File): String? {
        return try {
            val info = ctx.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
            info?.packageName
        } catch (t: Throwable) {
            Log.w(TAG, "getPackageArchiveInfo failed for ${apk.absolutePath}", t)
            null
        }
    }

    /** Is the given package currently installed (any state)? */
    private fun isPackageInstalled(ctx: Context, pkg: String): Boolean {
        return try {
            ctx.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        } catch (_: Throwable) {
            false
        }
    }

    /** Fire the system uninstall prompt for `pkg`, register a
     *  one-shot PACKAGE_REMOVED receiver, and when removal lands
     *  auto-fire the install prompt for `newApk`. */
    private fun uninstallThenInstall(ctx: Context, pkg: String, newApk: File) {
        // One-shot receiver — unregisters itself after the first
        // matching PACKAGE_REMOVED broadcast.  Uses applicationContext
        // so the receiver outlives the calling Activity (which gets
        // backgrounded while the uninstaller UI is on screen).
        val appCtx = ctx.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val removedPkg = intent?.data?.schemeSpecificPart
                if (removedPkg == pkg) {
                    Log.i(TAG, "Package $pkg removed → firing install for ${newApk.name}")
                    try { appCtx.unregisterReceiver(this) } catch (_: Throwable) { /* idempotent */ }
                    try {
                        launchInstallPrompt(appCtx, newApk)
                    } catch (t: Throwable) {
                        Log.e(TAG, "post-uninstall install failed", t)
                    }
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_PACKAGE_REMOVED).apply {
            addDataScheme("package")
        }
        // Android 14 (TIRAMISU+) requires the explicit export flag.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appCtx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appCtx.registerReceiver(receiver, filter)
        }

        // Fire the system uninstall prompt.  ACTION_DELETE is the
        // user-facing equivalent of ACTION_UNINSTALL_PACKAGE and
        // works without requiring DELETE_PACKAGES permission.
        try {
            val uninstall = Intent(Intent.ACTION_DELETE).apply {
                data = Uri.parse("package:$pkg")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            appCtx.startActivity(uninstall)
            Log.i(TAG, "Uninstall prompt fired for $pkg")
        } catch (t: Throwable) {
            // Uninstall prompt couldn't be shown — fall back to a
            // direct install and let the system handle whatever
            // error message Android prefers.
            Log.e(TAG, "uninstall prompt failed; falling back to direct install", t)
            try { appCtx.unregisterReceiver(receiver) } catch (_: Throwable) { /* idempotent */ }
            launchInstallPrompt(appCtx, newApk)
        }
    }

    /**
     * Fires the standard install intent — the system shows the
     * "Allow installs from this source?" dialog (Android 8+) plus
     * the package-installer UI listing permissions, then the user
     * taps INSTALL.
     */
    private fun launchInstallPrompt(ctx: Context, apk: File) {
        val authority = "${ctx.packageName}.fileprovider"
        val uri: Uri = FileProvider.getUriForFile(ctx, authority, apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_ACTIVITY_NEW_TASK
        }
        ctx.startActivity(intent)
    }

    /**
     * Returns true if the app is currently authorised to install
     * APKs on Android 8+ (where this permission gate exists).  On
     * older devices this is always true.
     */
    fun canInstallNow(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) true
        else ctx.packageManager.canRequestPackageInstalls()

    /**
     * Open Settings → Apps → ON NOW TV V2 → Install unknown apps
     * so the user can grant the permission.
     */
    fun requestInstallPermission(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${ctx.packageName}"),
            ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            ctx.startActivity(intent)
        }
    }
}
