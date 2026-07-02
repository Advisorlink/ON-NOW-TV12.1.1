package tv.onnow.launcher.install

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import java.io.File

/**
 * v2.12.12 — Silent APK installer for DEVICE-OWNER boxes.
 *
 * When the launcher has been provisioned as the box's Device Owner
 * (one-time `adb dpm set-device-owner …` at setup — see
 * LAUNCHER_UPDATE_GUIDE.md), it can install and update APKs through
 * the platform [PackageInstaller] with ZERO user interaction:
 *   • no root / superuser prompt (never calls `su`)
 *   • no "install from unknown sources" dialog
 *   • no system install confirmation screen
 *
 * This is the cleanest fleet experience — the customer literally sees
 * nothing.  On Android 12+ (API 31) silent installs additionally
 * require `setRequireUserAction(USER_ACTION_NOT_REQUIRED)` plus the
 * `UPDATE_PACKAGES_WITHOUT_USER_ACTION` manifest permission (both
 * wired up); on older boxes device-owner installs are silent by
 * default.
 *
 * Self-update note: installing the launcher's own package this way is
 * an IN-PLACE update — all data/profiles are kept and the package is
 * never absent (box can't fall to the stock launcher).  After the
 * session succeeds, [InstallResultReceiver] relaunches the launcher so
 * the new code loads.
 */
object SilentInstaller {

    private const val TAG = "SilentInstaller"
    const val EXTRA_RELAUNCH = "onnow_relaunch"
    const val EXTRA_PKG = "onnow_pkg_name"

    /** True when this launcher is the box's Device Owner. */
    fun isDeviceOwner(ctx: Context): Boolean {
        return try {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.isDeviceOwnerApp(ctx.packageName)
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Silently install/update [apk] for package [packageName].  Only
     * works when this app is Device Owner; returns `false` otherwise
     * (or on any error) so the caller can fall back to root / intent
     * installs.  The install result arrives asynchronously at
     * [InstallResultReceiver].
     *
     * @param relaunch `true` when updating the launcher itself — the
     *   result receiver will relaunch it so the new code loads.
     */
    fun install(ctx: Context, apk: File, packageName: String, relaunch: Boolean): Boolean {
        if (!isDeviceOwner(ctx)) return false
        if (!apk.exists() || !apk.canRead()) {
            Log.w(TAG, "apk missing / unreadable: ${apk.absolutePath}")
            return false
        }
        return try {
            val installer = ctx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            )
            params.setAppPackageName(packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // API 31+: explicitly opt out of the user-action prompt.
                params.setRequireUserAction(
                    PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED,
                )
            }

            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("onnow_apk", 0, apk.length()).use { out ->
                        input.copyTo(out, bufferSize = 64 * 1024)
                        session.fsync(out)
                    }
                }

                val resultIntent = Intent(ctx, InstallResultReceiver::class.java).apply {
                    putExtra(EXTRA_RELAUNCH, relaunch)
                    putExtra(EXTRA_PKG, packageName)
                }
                var flags = PendingIntent.FLAG_UPDATE_CURRENT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    flags = flags or PendingIntent.FLAG_MUTABLE
                }
                val pending = PendingIntent.getBroadcast(ctx, sessionId, resultIntent, flags)
                session.commit(pending.intentSender)
            }
            Log.i(TAG, "silent install committed for $packageName (relaunch=$relaunch)")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "silent install failed for $packageName", t)
            false
        }
    }
}
