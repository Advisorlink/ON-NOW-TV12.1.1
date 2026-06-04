package tv.onnowtv.livetv.data

import android.content.Context

/**
 * Persistent set of channel IDs the user has favourited.
 *
 * Stored in SharedPreferences as a String set so it survives
 * process restarts AND the EPG bundle being re-fetched.  We
 * intentionally key on `Channel.id` (not `epgChannelId`) so a
 * provider stream-id change doesn't lose the favourite.
 *
 * EpgActivity reads this on boot to populate the Favourites
 * virtual category, and writes to it via [toggle] when the user
 * long-presses OK on a channel pill.
 */
object FavouritesStore {

    private const val PREF_NAME = "v2_livetv_favs"
    private const val KEY = "fav_channel_ids_v1"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun load(ctx: Context): MutableSet<String> =
        prefs(ctx).getStringSet(KEY, null)?.toMutableSet() ?: mutableSetOf()

    fun save(ctx: Context, items: Set<String>) {
        prefs(ctx).edit().putStringSet(KEY, items).apply()
    }

    /** Returns the NEW state — true = now favourited, false = removed. */
    fun toggle(ctx: Context, channelId: String): Boolean {
        val s = load(ctx)
        val nowFav = if (s.contains(channelId)) {
            s.remove(channelId); false
        } else {
            s.add(channelId); true
        }
        save(ctx, s)
        return nowFav
    }
}
