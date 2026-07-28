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
        VLC("vlc", "LibVLC (default)"),
        EXO("exo", "ExoPlayer");

        companion object {
            fun fromToken(t: String?): Backend = values().firstOrNull { it.token == t } ?: VLC
        }
    }

    private const val FILE_NAME = "livetv_prefs"
    private const val KEY_BACKEND = "player.backend"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    /** Read the currently-selected backend.  Defaults to LibVLC — from
     *  v2.16.42 onward LibVLC is the default because ExoPlayer's
     *  strict audio-codec support left some Xtream MPEG-TS channels
     *  playing silent (AC-3/E-AC-3/DTS/MP2 dropped when the hardware
     *  didn't advertise decoder support).  LibVLC ships software
     *  decoders for all of these so audio just works.  Users who
     *  previously flipped the toggle to ExoPlayer keep ExoPlayer —
     *  only fresh installs (and users who never touched the toggle)
     *  see the new default. */
    fun getBackend(ctx: Context): Backend =
        Backend.fromToken(prefs(ctx).getString(KEY_BACKEND, Backend.VLC.token))

    /** Write the new backend choice.  `apply()` (not `commit()`) so we
     *  don't block the UI thread — the disk write happens in the
     *  background on the SharedPreferences worker.  On the next
     *  `PlayerActivity.onCreate`, `getBackend` will see the new value. */
    fun setBackend(ctx: Context, backend: Backend) {
        prefs(ctx).edit().putString(KEY_BACKEND, backend.token).apply()
    }
}
