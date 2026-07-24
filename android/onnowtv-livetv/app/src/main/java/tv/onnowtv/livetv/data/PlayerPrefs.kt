package tv.onnowtv.livetv.data

import android.content.Context
import android.content.SharedPreferences

/**
 * v2.16.38 — Persistent player-backend preference.
 *
 * The Live TV app can play channels through either ExoPlayer (default)
 * or LibVLC.  The user picks via a settings cog on the player screen;
 * the choice writes into the app's `livetv_prefs` SharedPreferences
 * file on internal storage.
 *
 * Persistence guarantees:
 *  - Survives app exit, HOME, task-switcher swipe-away, device reboot.
 *  - Only wiped by uninstalling the APK or clearing storage in Android
 *    system settings — same as any local pref.
 *
 * Read once in `PlayerActivity.onCreate` (BEFORE any player instance
 * is built) so the correct backend is used from the very first frame.
 */
object PlayerPrefs {
    /** Selectable playback backends.  Serialized as short string tokens
     *  so we can extend the enum later without breaking existing prefs. */
    enum class Backend(val token: String, val label: String) {
        EXO("exo", "ExoPlayer (default)"),
        VLC("vlc", "LibVLC");

        companion object {
            fun fromToken(t: String?): Backend = values().firstOrNull { it.token == t } ?: EXO
        }
    }

    private const val FILE_NAME = "livetv_prefs"
    private const val KEY_BACKEND = "player.backend"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** Read the currently-selected backend.  Defaults to ExoPlayer so
     *  existing users don't notice a behavioural change until they opt
     *  into VLC themselves. */
    fun getBackend(ctx: Context): Backend =
        Backend.fromToken(prefs(ctx).getString(KEY_BACKEND, Backend.EXO.token))

    /** Write the new backend choice.  `apply()` (not `commit()`) so we
     *  don't block the UI thread — the disk write happens in the
     *  background on the SharedPreferences worker.  On the next
     *  `PlayerActivity.onCreate`, `getBackend` will see the new value. */
    fun setBackend(ctx: Context, backend: Backend) {
        prefs(ctx).edit().putString(KEY_BACKEND, backend.token).apply()
    }
}
