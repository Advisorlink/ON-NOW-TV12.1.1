package tv.onnowtv.fta_native.data

import android.content.Context

/** SharedPreferences-backed set of favourited channel IDs. */
object FtaFavouritesStore {
    private const val PREF = "fta_favs"
    private const val KEY = "fav_ids_v1"

    fun load(ctx: Context): MutableSet<String> =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getStringSet(KEY, null)?.toMutableSet() ?: mutableSetOf()

    fun toggle(ctx: Context, id: String): Boolean {
        val s = load(ctx)
        val on = if (s.contains(id)) { s.remove(id); false } else { s.add(id); true }
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY, s).apply()
        return on
    }
}
