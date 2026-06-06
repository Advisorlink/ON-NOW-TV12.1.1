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
                }
            }
        )

        // ─────────────────────────────────────────────────────────
        // One-time post-Gemini cleanup.  v2.8.143 wiped all
        // GPT-Image-1's predecessor (Gemini Nano Banana) covers on
        // the server side, but every existing device still has
        // those bytes cached on disk via Coil AND still has the
        // stale `coverHash`/`coverUrl` pointers in its
        // CollectionsStore.  Without this purge the user re-opens
        // the app and sees the old Gemini imagery served straight
        // out of Coil's memory cache.
        //
        // We bump COVER_PURGE_VERSION whenever the server-side
        // generator changes; the bump triggers a one-shot wipe of:
        //   • Coil's image memory + disk caches
        //   • every Collection's `coverHash` and `coverUrl`
        // After the wipe each tile renders in the "no cover yet"
        // state until the user taps "Re-style ALL" (or any tile's
        // "Regenerate this" action), at which point GPT-Image-1
        // produces a fresh cover with the new URL — Coil treats it
        // as a brand-new key and fetches from the server.
        runCatching { applyCoverPurgeIfNeeded() }
            .onFailure { Log.e("LiveTVApp", "applyCoverPurgeIfNeeded failed", it) }
    }

    private fun applyCoverPurgeIfNeeded() {
        val prefs = getSharedPreferences("livetv-app", MODE_PRIVATE)
        val applied = prefs.getInt("cover_purge_applied", 0)
        if (applied >= COVER_PURGE_VERSION) return
        Log.i(
            "LiveTVApp",
            "Cover-cache purge: applying v$applied → v$COVER_PURGE_VERSION",
        )
        // 1. Drop Coil's memory + disk image caches so old bytes
        //    cannot survive even if the URL coincidentally collides.
        try {
            val loader = coil.Coil.imageLoader(this)
            loader.memoryCache?.clear()
            loader.diskCache?.clear()
            Log.i("LiveTVApp", "Coil memory + disk image cache cleared")
        } catch (t: Throwable) {
            Log.e("LiveTVApp", "Failed to clear Coil cache", t)
        }
        // 2. Wipe every persisted Collection's cover pointer AND
        //    kick off a background regeneration for each so the
        //    user doesn't have to tap "Re-style ALL" manually.
        //    Without this auto-regen the Library opens with blank
        //    tiles until the user goes hunting for the menu — which
        //    is exactly the friction the v2.8.143 manual purge had.
        try {
            val store = tv.onnowtv.livetv.data.CollectionsStore
            val all = store.load(this).toList()
            // Step A: clear pointers immediately so the UI repaints
            // with the "no cover yet" tiles right away.
            all.forEach { c ->
                if (c.coverHash != null || c.coverUrl != null) {
                    store.update(this, c.copy(coverHash = null, coverUrl = null))
                }
            }
            Log.i("LiveTVApp", "Cleared coverHash/coverUrl on ${all.size} collections")
            // Step B: fire a single background coroutine that loops
            // through every collection and regenerates fresh covers
            // serially (rate-limited, ~25 s each, won't hammer the
            // backend or burn through the OpenAI budget in parallel).
            if (all.isNotEmpty()) {
                kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    for (c in all) {
                        try {
                            val gen = tv.onnowtv.livetv.data.CoversApi.generate(
                                name = c.name,
                                forceSalt = tv.onnowtv.livetv.data.CoversApi.freshSalt(),
                            )
                            store.update(
                                this@LiveTVApp,
                                c.copy(coverHash = gen.hash, coverUrl = gen.url),
                            )
                            Log.i("LiveTVApp", "Auto-regen cover OK: ${c.name}")
                        } catch (t: Throwable) {
                            Log.e("LiveTVApp", "Auto-regen failed: ${c.name}", t)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e("LiveTVApp", "Failed to clear collection covers", t)
        }
        prefs.edit().putInt("cover_purge_applied", COVER_PURGE_VERSION).apply()
    }

    companion object {
        /** Bump this whenever the cover-generation provider or
         *  baseline prompt changes — on next app launch every
         *  device wipes its local cover cache AND auto-regenerates
         *  every Collection in the background, exactly once.
         *
         *  v2 — v2.8.143 (Gemini → GPT-Image-1 manual purge)
         *  v3 — v2.8.146 (auto-regen, kills the "still showing the
         *       old legal-imagery covers" complaint) */
        const val COVER_PURGE_VERSION = 3
    }
}
