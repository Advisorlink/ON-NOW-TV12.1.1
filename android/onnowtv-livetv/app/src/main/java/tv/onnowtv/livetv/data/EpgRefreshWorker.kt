package tv.onnowtv.livetv.data

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/**
 * v2.10.14 — Background EPG refresh worker.
 *
 * User asked for the EPG to "auto-update in the background" so
 * the on-disk cache never goes stale.  This worker fires every
 * 12 hours (WorkManager schedules within ±1 h flex window) on a
 * connected network, re-downloads the XMLTV from the user's
 * Xtream provider, parses it for every channel currently in the
 * bundle, runs the same name-fallback matching that MainActivity
 * does, then overwrites the [EpgCache] on disk.
 *
 * Idempotent at the enqueue site — [schedulePeriodic] uses the
 * KEEP policy so re-enqueuing on every cold boot of MainActivity
 * is a no-op once the periodic work is already running.
 *
 * If the user signs out, [AuthStore.signOut] cancels the work
 * (already wired through `cancelAllWorkByTag` in that path) so
 * we don't keep hitting the provider with stale creds.
 */
class EpgRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val session = AuthStore.sessionId(ctx)

        // No creds, no work — the next foreground launch will
        // re-enqueue once the user signs in again.
        if (!AuthStore.isSignedIn(ctx)) {
            Log.i(TAG, "skip: no saved credentials")
            return Result.success()
        }

        // We need the channel list from disk to know which ids
        // we're parsing programmes for.  If the bundle isn't on
        // disk yet (first install hasn't reached EpgActivity), we
        // skip and try again next cycle.
        val bundle = try {
            val text = BundleCache.loadJson(ctx) ?: run {
                Log.i(TAG, "skip: no BundleCache on disk yet")
                return Result.success()
            }
            XtreamRepository.parseBundleJson(text)
        } catch (t: Throwable) {
            Log.w(TAG, "bundle parse failed: ${t.message}")
            return Result.retry()
        }

        val wantedIds = bundle.channels
            .mapNotNull { it.epgChannelId?.takeIf { id -> id.isNotBlank() } }
            .toHashSet()
        return try {
            // Server guide only: small (~12 MB), pre-built, 3 days deep. The provider's 145 MB
            // XMLTV is no longer downloaded in the background — it bogged down budget boxes.
            val merged = GuideKeeper.mergeFromServer(ctx, session, wantedIds)
            Log.i(TAG, "server guide top-up: $merged channels")
            if (merged >= GuideKeeper.MIN_CHANNELS || merged == -1 || !GuideKeeper.needsRefresh(ctx)) Result.success() else Result.retry()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            Log.w(TAG, "refresh failed: ${t.message}")
            if (GuideKeeper.needsRefresh(ctx)) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val TAG = "EpgRefreshWorker"

        const val UNIQUE_NAME = "onnowtv.livetv.epg-refresh"
        const val UNIQUE_NAME_ONESHOT = "onnowtv.livetv.epg-refresh-now"
        const val TAG_WORK = "onnowtv-epg-refresh"

        /**
         * v2.16.50 — Reliable silent background refresh.
         *
         * Idempotently enqueue the periodic refresh.  Called from
         * MainActivity once the first foreground bundle hand-off
         * is complete AND from LiveTVApp.onCreate at every process
         * start.
         *
         * Cadence: every 6 h (was 12 h) with a 1 h flex window.
         * NO initial delay — a freshly-enqueued worker fires as
         * soon as network constraints are satisfied.  The old
         * 12 h schedule with a 12 h initial delay was the direct
         * cause of the operator's "guide disappears after a few
         * days" complaint: XMLTV covers 3-7 days, so missing a
         * couple of WorkManager cycles (Doze / battery-idle on
         * always-on TV boxes) quietly runs the cache off a cliff.
         * 6 h gives 4 windows/day of headroom.
         *
         * Backoff: linear 15 min on transient failure so a
         * temporarily-offline box keeps retrying without
         * hammering the provider.
         *
         * Policy: UPDATE (was KEEP) so cadence/constraint tweaks
         * from a new build actually replace the old schedule
         * instead of being ignored — the previous 12 h + 12 h
         * initial delay schedule was stuck on many boxes because
         * KEEP never let a new version's params take effect.
         */
        fun schedulePeriodic(ctx: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(false)
                .setRequiresDeviceIdle(false)
                .setRequiresCharging(false)
                .build()
            val request = PeriodicWorkRequestBuilder<EpgRefreshWorker>(
                6, TimeUnit.HOURS,
                1, TimeUnit.HOURS,
            )
                .setConstraints(constraints)
                .addTag(TAG_WORK)
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    15, TimeUnit.MINUTES,
                )
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        /**
         * Fire a one-time refresh immediately.  Silent: the
         * writer targets a staging dir and the live cache stays
         * intact until the atomic swap.  Safe to call on every
         * app open — KEEP policy de-duplicates concurrent kicks.
         *
         * This is the "always warm" safety net that MakesSure a
         * box which just came out of a long Doze / power-off
         * catches up right away instead of waiting up to 6 more
         * hours for the periodic worker's next window.
         */
        fun refreshNow(ctx: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(false)
                .setRequiresDeviceIdle(false)
                .setRequiresCharging(false)
                .build()
            val request = OneTimeWorkRequestBuilder<EpgRefreshWorker>()
                .setConstraints(constraints)
                .addTag(TAG_WORK)
                .setBackoffCriteria(
                    BackoffPolicy.LINEAR,
                    WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS,
                )
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(
                UNIQUE_NAME_ONESHOT,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        /** Wipe any pending refresh on sign-out so the next sign-in
         *  re-enqueues with the new credentials. */
        fun cancel(ctx: Context) {
            try {
                WorkManager.getInstance(ctx).cancelUniqueWork(UNIQUE_NAME)
                WorkManager.getInstance(ctx).cancelUniqueWork(UNIQUE_NAME_ONESHOT)
            } catch (_: Throwable) {}
        }
    }
}
