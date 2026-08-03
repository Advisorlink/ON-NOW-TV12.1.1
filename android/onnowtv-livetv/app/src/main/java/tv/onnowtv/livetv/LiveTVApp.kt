package tv.onnowtv.livetv

import android.app.Application
import android.content.Intent
import android.os.Process
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlin.system.exitProcess

/**
 * App-level entry point.  Installs a global uncaught-exception
 * handler so unexpected crashes display the stack trace in a
 * full-screen "diagnostic" activity instead of the generic
 * "OnNow V2 Live keeps stopping" Android dialog.  This is
 * invaluable while we shake out the new layout on real TV boxes
 * where adb logcat is awkward.
 */
class LiveTVApp : Application() {

    override fun onCreate() {
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Log.e("LiveTVApp", "Uncaught exception in thread ${thread.name}", throwable)
                val intent = Intent(applicationContext, CrashActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra(CrashActivity.EXTRA_MESSAGE, throwable.toString())
                    putExtra(CrashActivity.EXTRA_STACK, Log.getStackTraceString(throwable))
                }
                startActivity(intent)
            } catch (chain: Throwable) {
                Log.e("LiveTVApp", "Crash handler itself failed", chain)
                defaultHandler?.uncaughtException(thread, throwable)
            }
            // Give the crash activity a moment to render, then exit
            // so the process is in a clean state.
            Thread.sleep(800)
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }

        // ─────────────────────────────────────────────────────────
        // Whole-app background detector.  When the user EXITS or
        // HOMES out of the app the upstream IPTV stream MUST stop
        // immediately — the provider only allows ONE concurrent
        // stream per account, and the user has explicitly demanded
        // "stream stops the moment we exit".
        // ─────────────────────────────────────────────────────────
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    // Process moved to background — release the
                    // shared live player and free the upstream
                    // socket pool.  ExoPlayer fully tears down,
                    // OkHttp connection pool is evicted.
                    Log.i("LiveTVApp", "App backgrounded — releasing LivePreviewSession")
                    LivePreviewSession.release()
                    // v2.16.14 — Force-flush any pending cloud
                    // backup push NOW so favourites / collections /
                    // reminders that were added seconds before the
                    // user exited actually reach the profile.  The
                    // 2.5 s debounce means most edits push before
                    // this ever fires, but this is the belt-and-
                    // braces safety net for a fast Home-key exit.
                    try {
                        tv.onnowtv.livetv.data.SyncManager.flushNow(applicationContext)
                    } catch (t: Throwable) {
                        Log.w("LiveTVApp", "flushNow onStop failed", t)
                    }
                }
            }
        )

        // ─────────────────────────────────────────────────────────
        // v2.16.50 — Always-warm EPG.  Kick the periodic refresh
        // worker (UPDATE policy) and, if the cache is older than
        // 6 h, fire a one-shot refresh right away.  Both calls
        // are silent WorkManager enqueues — the UI is never
        // blocked.  The writer targets a staging dir and only
        // atomically swaps into place on success, so a foreground
        // fast-path load ALWAYS shows the previously-persisted
        // guide instantly and the new guide slides in behind the
        // scenes.
        //
        // This is the operator's #1 complaint fix: "the guide
        // stops showing after a few days".  Running this at
        // every process start means any box that comes out of a
        // long Doze / power-off catches up in the background
        // BEFORE the user has finished navigating to a channel.
        // ─────────────────────────────────────────────────────────
        try {
            if (tv.onnowtv.livetv.data.AuthStore.isSignedIn(this)) {
                tv.onnowtv.livetv.data.EpgRefreshWorker
                    .schedulePeriodic(applicationContext)
                val ageMs = tv.onnowtv.livetv.data.EpgCache.ageMs(applicationContext)
                // Kick a one-shot when the cache is empty (ageMs
                // returns Long.MAX_VALUE) OR older than 6 h.
                if (ageMs > 6L * 60L * 60L * 1000L) {
                    Log.i(
                        "LiveTVApp",
                        "EPG age=${ageMs / 3_600_000}h — silent refreshNow on app start",
                    )
                    tv.onnowtv.livetv.data.EpgRefreshWorker
                        .refreshNow(applicationContext)
                }
            }
        } catch (t: Throwable) {
            Log.w("LiveTVApp", "EPG warmup on app start failed", t)
        }
    }
}
