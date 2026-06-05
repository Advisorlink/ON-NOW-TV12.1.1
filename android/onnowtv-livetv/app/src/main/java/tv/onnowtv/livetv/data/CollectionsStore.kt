package tv.onnowtv.livetv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent list of user-curated saved categories (a.k.a.
 * Collections) for the Library screen.
 *
 * Serialised to SharedPreferences as a JSON array so it survives
 * process restarts AND survives the Xtream EPG bundle being
 * re-fetched.  The shape mirrors [LibraryCollection] exactly.
 *
 * `LibraryActivity` reads on boot and writes via [add] / [remove]
 * / [update].  `BackupClient` (separate workstream) will hand
 * the raw JSON to `/api/backup/save` so Collections roam across
 * boxes just like Vesper Movies/TV does.
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
                out.add(
                    LibraryCollection(
                        id = obj.optString("id"),
                        categoryId = obj.optString("categoryId"),
                        name = obj.optString("name"),
                        coverHash = obj.optString("coverHash").ifBlank { null },
                        coverUrl = obj.optString("coverUrl").ifBlank { null },
                        addedAt = obj.optLong("addedAt", System.currentTimeMillis()),
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
                put("categoryId", c.categoryId)
                put("name", c.name)
                put("coverHash", c.coverHash ?: "")
                put("coverUrl", c.coverUrl ?: "")
                put("addedAt", c.addedAt)
            }
            arr.put(obj)
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    fun add(ctx: Context, c: LibraryCollection) {
        val list = load(ctx)
        // Dedupe by categoryId — long-pressing the same category
        // twice should update the existing record, not create a
        // duplicate.
        list.removeAll { it.categoryId == c.categoryId }
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

    fun has(ctx: Context, categoryId: String): Boolean =
        load(ctx).any { it.categoryId == categoryId }
}
