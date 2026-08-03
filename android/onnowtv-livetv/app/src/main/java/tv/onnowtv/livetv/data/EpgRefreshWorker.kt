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
        val wantedNames = bundle.channels
            .map { XmlTvFetcher.normaliseChannelName(it.name) }
            .filter { it.isNotBlank() }
            .toHashSet()

        return try {
            // v2.10.15 — Stream programmes to disk via the per-channel
            // writer so the periodic refresh never accumulates more
            // than ~5 MB of programme data in memory.  Critical for
            // budget Android TV boxes where the WorkManager process
            // shares the same 256 MB heap as the foreground app.
            val writer = EpgCache.openStreamingWriter(ctx)
            val parsed = try {
                XmlTvFetcher.fetchEpgForChannels(
                    ctx,
                    wantedIds,
                    wantedNames,
                    writer = writer,
                ) { _, _ -> /* no UI to drive — silent worker */ }
            } catch (t: Throwable) {
                writer.abort()
                throw t
            }

            if (parsed.totalProgrammes == 0) {
                writer.abort()
                Log.w(TAG, "refresh returned 0 programmes — keeping previous cache")
                return Result.retry()
            }

            // Commit the new cache to disk.  EpgActivity will pick
            // it up via per-channel loadChannel() lookups on next
            // cold boot.
            val r = writer.finish(parsed.displayNameToEpgId)
            Log.i(
                TAG,
                "EPG refresh ok: ${r.channelsFlushed} channels persisted to cache " +
                    "(${parsed.displayNameToEpgId.size} xmltv display names seen, " +
                    "${parsed.totalProgrammes} programmes total)",
            )
            // v2.16.51 — Also refresh the near-term (8 h) EPG window
            // from the backend's epg-only endpoint.  This keeps the
            // channels that XMLTV does NOT cover (provider quality
            // variants, docu/entertainment channels whose guide only
            // arrives via the lazy per-channel fetch) warm on disk, so
            // the What's On hub and channel rows are fully populated
            // the instant the app opens — with no network wait.
            // mergeChannel() UNION-merges, so multi-day XMLTV guides
            // are never truncated by this window.
            try {
                val epgOnly = XtreamRepository.fetchEpgOnlyMap(
                    windowHours = 8,
                    keepIds = wantedIds,
                )
                var merged = 0
                for ((sid, progs) in epgOnly) {
                    if (progs.isEmpty()) continue
                    EpgCache.mergeChannel(ctx, sid, progs)
                    merged++
                }
                Log.i(TAG, "epg-only merge: refreshed $merged channels")
            } catch (t: Throwable) {
                Log.w(TAG, "epg-only merge failed (non-fatal): ${t.message}")
            }
            Result.success()
        } catch (t: Throwable) {
            Log.w(TAG, "refresh failed: ${t.message}")
            Result.retry()
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
