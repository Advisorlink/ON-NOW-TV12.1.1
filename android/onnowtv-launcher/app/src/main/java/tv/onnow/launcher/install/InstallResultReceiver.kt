package tv.onnow.launcher.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * v2.12.12 — Receives the async result of a [SilentInstaller]
 * PackageInstaller session.
 *
 * Declared in the manifest (not context-registered) so the system can
 * still deliver the result even after a launcher self-update kills the
 * old process — Android re-instantiates the (new) app to deliver it.
 */
class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(ctx: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        val relaunch = intent.getBooleanExtra(SilentInstaller.EXTRA_RELAUNCH, false)
        val pkg = intent.getStringExtra(SilentInstaller.EXTRA_PKG) ?: ctx.packageName

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Should not happen for a device owner, but if the OS
                // still wants confirmation (e.g. a firmware that ignores
                // USER_ACTION_NOT_REQUIRED), surface the system dialog
                // as a graceful fallback rather than silently failing.
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { ctx.startActivity(confirm) } catch (_: Throwable) {}
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "silent install success for $pkg (relaunch=$relaunch)")
                if (relaunch) {
                    // Self-update: cold-start the launcher so the NEW
                    // code loads.  (As the HOME app the OS would also
                    // relaunch us, but doing it explicitly is snappier.)
                    try {
                        val launch = ctx.packageManager.getLaunchIntentForPackage(pkg)
                        if (launch != null) {
                            launch.addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_CLEAR_TASK,
                            )
                            ctx.startActivity(launch)
                        }
                    } catch (t: Throwable) {
                        Log.w(TAG, "relaunch after self-update failed", t)
                    }
                }
            }

            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w(TAG, "silent install failed for $pkg — status=$status msg=$msg")
            }
        }
    }

    companion object {
        private const val TAG = "InstallResultReceiver"
    }
}
