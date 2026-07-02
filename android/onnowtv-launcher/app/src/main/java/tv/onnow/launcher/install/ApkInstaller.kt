package tv.onnow.launcher.install

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
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
 * a remote https URL) to the app's cache directory, then fires the
 * standard Android `ACTION_VIEW` / `ACTION_INSTALL_PACKAGE` intent
 * which surfaces the system install prompt.
 *
 * Requires:
 *   • `android.permission.REQUEST_INSTALL_PACKAGES` (declared in
 *     AndroidManifest.xml).
 *   • A FileProvider entry that exposes the cache dir (so the
 *     URI passed to the installer is a `content://` URI on
 *     Android N+, NOT a `file://` URI which crashes since N).
 *
 * The user always sees the system install dialog — we never
 * silently install.  This is required by Android even with the
 * INSTALL_PACKAGES permission.
 */
object ApkInstaller {

    private const val TAG = "ApkInstaller"
    private val http = tv.onnow.launcher.net.ResilientHttp.client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * @return null on success; an error message on failure.
     *
     * @param onConflict  v2.10.75 — Optional callback fired when
     *   the downloaded APK targets a package already installed
     *   with a DIFFERENT signing certificate.  When provided, this
     *   helper does NOT auto-fire the uninstall prompt — it just
     *   reports the conflict (with the conflicting package name +
     *   the downloaded APK file) and returns `null`.  The caller
     *   is then responsible for driving the uninstall flow and
     *   calling [launchInstallPrompt] with the cached APK file
     *   once the user confirms the uninstall.  When `onConflict`
     *   is `null` (the legacy default) the previous behaviour
     *   stands: a Toast + uninstall intent fires automatically,
     *   and the user must re-tap the tile to install the new
     *   version.
     */
    suspend fun downloadAndInstall(
        ctx: Context,
        apkUrl: String,
        suggestedName: String? = null,
        onProgress: ((Int) -> Unit)? = null,
        onConflict: ((conflictPkg: String, apkFile: File) -> Unit)? = null,
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
            // v2.10.66 — Proactive signature-conflict guard.  Android
            // refuses to install an APK over a package with a
            // different signing certificate ("App not installed —
            // package conflicts" — opaque, dead-end for the user).
            // Before firing the install prompt we read the APK's
            // signature, compare to the currently-installed
            // package's signature, and if they differ we hand off
            // to either the caller's `onConflict` (v2.10.75 path —
            // auto-resume install after uninstall) OR the legacy
            // launchUninstallPrompt fallback.
            val conflict = detectSignatureConflict(ctx, out)

            // v2.10.93 — On rooted boxes (the customer's case), bypass
            // the whole intent-based install dance + signature-
            // conflict prompt.  A detached root shell does
            // `pm install -r -d` first, falls back to
            // `pm uninstall && pm install` on signature mismatch,
            // and survives the launcher being SIGKILLed mid-update.
            //
            // v2.10.98 — Expanded scope: ALSO route side-app
            // UPDATES through the root installer.  Previously
            // (v2.10.95 spec) we only used root for signature-
            // conflict + launcher self-update, leaving Vesper /
            // Tunes / Live TV / FTA updates on the intent flow.
            // The intent flow won't auto-uninstall the existing
            // copy, so every time the operator pushed a new Vesper
            // build the update dialog either silently no-op'd or
            // surfaced "Application not installed" with no way
            // forward.  By using root for *any* re-install of an
            // already-installed package, `pm install -r -d` handles
            // the happy path and the `|| pm uninstall && pm install`
            // fallback handles signature drift — both auto-uninstall
            // the existing copy as a side-effect.  Fresh first
            // installs of side-apps still use the intent flow so the
            // user doesn't see a Magisk prompt for their very first
            // download of an app from the App Store.
            val apkPkg = conflict
                ?: ctx.packageManager.getPackageArchiveInfo(out.absolutePath, 0)?.packageName
                ?: ctx.packageName
            val isUpdate = try {
                ctx.packageManager.getPackageInfo(apkPkg, 0)
                true
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                false
            }

            // v2.12.12 — PREFERRED PATH: if the launcher has been
            // provisioned as the box's DEVICE OWNER, install SILENTLY
            // via PackageInstaller — no root, no superuser prompt, no
            // "unknown sources" dialog, no confirmation screen.  The
            // customer sees nothing.  Covers fresh installs, side-app
            // updates AND the launcher self-update (in-place, keeps
            // data; InstallResultReceiver relaunches it).  Falls
            // through to the root path (then the intent path) on any
            // failure — e.g. a side app whose signature drifted, which
            // PackageInstaller refuses just like `pm`.
            if (SilentInstaller.isDeviceOwner(ctx)) {
                val relaunch = (apkPkg == ctx.packageName)
                if (SilentInstaller.install(ctx, out, apkPkg, relaunch)) {
                    Log.i(TAG, "silent device-owner install committed for $apkPkg (relaunch=$relaunch)")
                    return@withContext null
                }
                Log.w(TAG, "device-owner silent install failed for $apkPkg; falling back to root/intent")
            }

            val rootNeeded =
                (conflict != null) ||
                (apkPkg == ctx.packageName) ||
                isUpdate
            if (rootNeeded && RootApkInstaller.isRootAvailable()) {
                val relaunch = (apkPkg == ctx.packageName)
                // v2.12.2 — Force clean uninstall+install whenever the
                // package is already on-device.  Fixes the operator's
                // "installer says installed but nothing changed" bug
                // where `pm install -r -d` alone silently no-ops.
                val ok = RootApkInstaller.install(
                    ctx,
                    out,
                    apkPkg,
                    relaunch = relaunch,
                    forceCleanInstall = isUpdate,
                )
                if (ok) {
                    Log.i(TAG, "root install launched for $apkPkg (isUpdate=$isUpdate, relaunch=$relaunch, forceClean=$isUpdate)")
                    return@withContext null
                }
                Log.w(TAG, "root install launch failed; falling back to intent flow")
                // Falls through to the legacy intent-based path.
            }

            if (conflict != null) {
                if (onConflict != null) {
                    // v2.10.75 — Hand the conflict to the caller so
                    // they can drive an ActivityResultLauncher-based
                    // auto-resume flow.  Do NOT show a Toast or
                    // launch the uninstall ourselves — the caller
                    // will orchestrate everything.
                    withContext(Dispatchers.Main) {
                        onConflict(conflict, out)
                    }
                    return@withContext null
                }
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(
                        ctx,
                        "Old version of '${conflict}' is signed differently. " +
                                "Uninstall it, then tap the tile again to install the new build.",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
                launchUninstallPrompt(ctx, conflict)
                return@withContext null   // not an error, just deferred
            }
            launchInstallPrompt(ctx, out)
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
     * v2.10.66 — Returns the package name of the installed app that
     * has a DIFFERENT signing certificate than the freshly-downloaded
     * APK, or `null` if there's no conflict (new install, or same
     * signing cert as already installed).  Used to decide whether
     * we need to uninstall first.
     */
    @Suppress("DEPRECATION")
    private fun detectSignatureConflict(ctx: Context, apk: File): String? {
        return try {
            val pm = ctx.packageManager
            val apkInfo = pm.getPackageArchiveInfo(
                apk.absolutePath,
                android.content.pm.PackageManager.GET_SIGNATURES,
            ) ?: return null
            val apkPkg = apkInfo.packageName ?: return null
            // Walk to the installed app's signatures.
            val installed = try {
                pm.getPackageInfo(
                    apkPkg,
                    android.content.pm.PackageManager.GET_SIGNATURES,
                )
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                return null    // not installed → no conflict
            }
            val apkSigs = apkInfo.signatures?.map { it.toCharsString() }?.toSet().orEmpty()
            val instSigs = installed.signatures?.map { it.toCharsString() }?.toSet().orEmpty()
            if (apkSigs.isEmpty() || instSigs.isEmpty()) return null
            if (apkSigs == instSigs) null else apkPkg
        } catch (t: Throwable) {
            Log.w(TAG, "signature check failed", t)
            null   // be permissive — let Android's own error UI handle it
        }
    }

    /**
     * v2.10.66 — Fire the system uninstall confirmation for
     * [packageName].  After the user accepts, MainActivity.onResume
     * is called and DockAdapter re-binds the pill — which will now
     * compute INSTALL state (because PackageInfo lookup returns
     * null) and the operator's next tile click triggers the
     * download + install cleanly.
     */
    private fun launchUninstallPrompt(ctx: Context, packageName: String) {
        val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE).apply {
            data = Uri.parse("package:$packageName")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        try {
            ctx.startActivity(intent)
        } catch (t: Throwable) {
            Log.e(TAG, "uninstall intent failed", t)
        }
    }

    /**
     * Fires the standard install intent — the system shows the
     * "Allow installs from this source?" dialog (Android 8+) plus
     * the package-installer UI listing permissions, then the user
     * taps INSTALL.
     *
     * v2.10.75 — Made `internal` so callers driving an
     * `ActivityResultLauncher`-based auto-resume after uninstall
     * (see `MainActivity.onTileInstallRequested`) can re-fire the
     * install for the already-downloaded APK without going through
     * `downloadAndInstall` again.
     */
    internal fun launchInstallPrompt(ctx: Context, apk: File) {
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
