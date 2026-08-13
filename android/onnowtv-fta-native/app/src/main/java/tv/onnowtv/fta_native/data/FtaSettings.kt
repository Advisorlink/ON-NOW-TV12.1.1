package tv.onnowtv.fta_native.data

import android.content.Context

/** v2.18.9 — SharedPreferences-backed app settings for the FTA app.
 *  Backs the side-rail settings cog: default location + subtitles. */
object FtaSettings {
    private const val PREF = "fta_settings"
    private const val KEY_CITY = "default_city_v1"
    private const val KEY_SUBS = "subtitles_enabled_v1"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun city(ctx: Context): String =
        prefs(ctx).getString(KEY_CITY, null)?.takeIf { it.isNotBlank() }
            ?: FtaRepository.DEFAULT_CITY

    fun setCity(ctx: Context, city: String) {
        prefs(ctx).edit().putString(KEY_CITY, city).apply()
    }

    fun subtitlesEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SUBS, false)

    fun setSubtitlesEnabled(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_SUBS, enabled).apply()
    }
}
