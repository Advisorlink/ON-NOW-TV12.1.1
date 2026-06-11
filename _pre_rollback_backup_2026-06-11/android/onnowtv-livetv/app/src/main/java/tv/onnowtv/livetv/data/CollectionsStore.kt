package tv.onnowtv.livetv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent list of user-curated Collections for the Library screen.
 *
 * v2 data model (Feb 2026): a Collection is a NAMED CONTAINER of
 * channel IDs, not a single-category bookmark.  The user creates a
 * collection by name + cover, then long-presses OK on individual
 * channels in the EPG to add them to the collection.  Opening a
 * collection re-launches the EPG in collection-mode (sidebar
 * hidden, middle column = just those channels).
 *
 * Legacy v1 entries — created when a Collection wrapped exactly one
 * Xtream category — still load via the [LibraryCollection.categoryId]
 * field for back-compat.  All NEW collections leave `categoryId`
 * empty and use the `channelIds` array instead.
 *
 * Persistence: SharedPreferences as JSON so the file roundtrips
 * cleanly through `/api/backup/save` / `/api/backup/restore`.
 */
object CollectionsStore {

    private const val PREF_NAME = "v2_livetv_collections"
    private const val KEY = "collections_v1"

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun load(ctx: Context): MutableList<LibraryCollection> {
        val raw = prefs(ctx).getString(KEY, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<LibraryCollection>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val ids = mutableListOf<String>()
                obj.optJSONArray("channelIds")?.let { ja ->
                    for (j in 0 until ja.length()) {
                        val s = ja.optString(j)
                        if (s.isNotBlank()) ids.add(s)
                    }
                }
                out.add(
                    LibraryCollection(
                        id = obj.optString("id"),
                        name = obj.optString("name"),
                        coverHash = obj.optString("coverHash").ifBlank { null },
                        coverUrl = obj.optString("coverUrl").ifBlank { null },
                        addedAt = obj.optLong("addedAt", System.currentTimeMillis()),
                        channelIds = ids,
                        categoryId = obj.optString("categoryId"),
                    )
                )
            }
            out
        } catch (_: Throwable) {
            mutableListOf()
        }
    }

    fun save(ctx: Context, items: List<LibraryCollection>) {
        val arr = JSONArray()
        for (c in items) {
            val obj = JSONObject().apply {
                put("id", c.id)
                put("name", c.name)
                put("coverHash", c.coverHash ?: "")
                put("coverUrl", c.coverUrl ?: "")
                put("addedAt", c.addedAt)
                put("channelIds", JSONArray().apply { c.channelIds.forEach { put(it) } })
                put("categoryId", c.categoryId)
            }
            arr.put(obj)
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    /** Append a brand-new collection at the top of the row. */
    fun add(ctx: Context, c: LibraryCollection) {
        val list = load(ctx)
        list.removeAll { it.id == c.id }
        list.add(0, c)
        save(ctx, list)
    }

    fun remove(ctx: Context, id: String) {
        val list = load(ctx)
        if (list.removeAll { it.id == id }) save(ctx, list)
    }

    fun update(ctx: Context, c: LibraryCollection) {
        val list = load(ctx)
        val idx = list.indexOfFirst { it.id == c.id }
        if (idx >= 0) {
            list[idx] = c
            save(ctx, list)
        }
    }

    /**
     * Append [channelId] to the collection identified by [collectionId].
     * No-op if the channel is already in the collection or the
     * collection no longer exists.  Returns the new size on success,
     * or -1 if the collection wasn't found.
     */
    fun addChannel(ctx: Context, collectionId: String, channelId: String): Int {
        val list = load(ctx)
        val idx = list.indexOfFirst { it.id == collectionId }
        if (idx < 0) return -1
        val cur = list[idx]
        if (channelId in cur.channelIds) return cur.channelIds.size
        val updated = cur.copy(channelIds = cur.channelIds + channelId)
        list[idx] = updated
        save(ctx, list)
        return updated.channelIds.size
    }

    /**
     * Remove [channelId] from the collection identified by
     * [collectionId].  Returns the new size, or -1 if the
     * collection no longer exists.
     */
    fun removeChannel(ctx: Context, collectionId: String, channelId: String): Int {
        val list = load(ctx)
        val idx = list.indexOfFirst { it.id == collectionId }
        if (idx < 0) return -1
        val cur = list[idx]
        if (channelId !in cur.channelIds) return cur.channelIds.size
        val updated = cur.copy(channelIds = cur.channelIds.filterNot { it == channelId })
        list[idx] = updated
        save(ctx, list)
        return updated.channelIds.size
    }

    /** Quick lookup: does any collection contain this channel id? */
    fun containsChannel(ctx: Context, channelId: String): Boolean =
        load(ctx).any { channelId in it.channelIds }
}
