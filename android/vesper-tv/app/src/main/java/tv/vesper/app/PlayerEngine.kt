package tv.vesper.app

import android.content.Context

/**
 * v2.16.42 — Available playback engines.  Persisted in the
 * `vesper_player` SharedPreferences store under [PREF_KEY].
 *
 * Ordering here is the canonical order in the in-player settings-cog
 * picker.  MPV is the default from this build forward (operator
 * preference — best pan-smoothness / frame pacing).
 */
enum class PlayerEngine(val token: String, val label: String) {
    MPV("mpv", "MPV (recommended)"),
    VLC("vlc", "LibVLC"),
    EXO("exo", "ExoPlayer"),
    EXO_FFMPEG("exo_ffmpeg", "ExoPlayer + FFmpeg audio");

    companion object {
        const val PREF_KEY = "player_engine_v2_16_42"
        const val PREFS_NAME = "vesper_player"
        val DEFAULT = MPV

        fun fromToken(tok: String?): PlayerEngine =
            values().firstOrNull { it.token == tok } ?: DEFAULT

        fun read(ctx: Context): PlayerEngine {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return fromToken(prefs.getString(PREF_KEY, null))
        }

        fun write(ctx: Context, engine: PlayerEngine) {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(PREF_KEY, engine.token)
                .apply()
        }
    }
}
