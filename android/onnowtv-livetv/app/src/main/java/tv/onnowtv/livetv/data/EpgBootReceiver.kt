package tv.onnowtv.livetv.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * v2.16.50 — BOOT_COMPLETED receiver.
 *
 * Purpose: the moment the TV box finishes booting, kick the EPG
 * refresh worker so the guide is warm BEFORE the user opens the
 * app.  WorkManager will honour the constraints (needs network)
 * and run silently in the background.
 *
 * The receiver also re-establishes the periodic worker's schedule
 * — WorkManager persists across reboots, but on some Android TV
 * ROMs the schedule is dropped after a full power cycle.
 * `schedulePeriodic` uses `UPDATE` policy so re-enqueuing is safe.
 *
 * Runs only if the user is already signed in — otherwise the
 * worker's own `doWork()` no-ops on missing credentials.
 */
class EpgBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        Log.i(TAG, "boot received: action=$action — kicking EPG refresh")
        try {
            if (!AuthStore.isSignedIn(context)) {
                Log.i(TAG, "skip boot refresh: no saved credentials")
                return
            }
            // Re-establish the periodic schedule and fire a
            // one-shot right away so the guide is fresh well
            // before the operator opens the app.
            EpgRefreshWorker.schedulePeriodic(context.applicationContext)
            EpgRefreshWorker.refreshNow(context.applicationContext)
        } catch (t: Throwable) {
            Log.w(TAG, "boot enqueue failed: ${t.message}")
        }
    }

    companion object {
        private const val TAG = "EpgBootReceiver"
    }
}
