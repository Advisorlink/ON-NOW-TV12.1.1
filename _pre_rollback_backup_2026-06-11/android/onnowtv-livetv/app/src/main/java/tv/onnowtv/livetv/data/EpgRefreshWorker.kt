package tv.onnowtv.livetv.data

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
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
            Result.success()
        } catch (t: Throwable) {
            Log.w(TAG, "refresh failed: ${t.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "EpgRefreshWorker"
        const val UNIQUE_NAME = "onnowtv.livetv.epg-refresh"
        const val TAG_WORK = "onnowtv-epg-refresh"

        /**
         * Idempotently enqueue the periodic refresh.  Called from
         * MainActivity once the first foreground bundle hand-off
         * is complete.  WorkManager's KEEP policy ensures
         * re-enqueuing on each cold boot is a no-op.
         */
        fun schedulePeriodic(ctx: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<EpgRefreshWorker>(
                12, TimeUnit.HOURS,
                // 1-hour flex window — WorkManager will fire any
                // time within the last hour of each 12-hour cycle.
                1, TimeUnit.HOURS,
            )
                .setConstraints(constraints)
                .addTag(TAG_WORK)
                .setInitialDelay(12, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /** Wipe any pending refresh on sign-out so the next sign-in
         *  re-enqueues with the new credentials. */
        fun cancel(ctx: Context) {
            try {
                WorkManager.getInstance(ctx).cancelUniqueWork(UNIQUE_NAME)
            } catch (_: Throwable) {}
        }
    }
}
