package tv.onnowtv.livetv.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent storage for the user's EPG reminders.
 *
 * Reminders live in `SharedPreferences` so they survive process
 * restarts, app updates, and EPG re-fetches.  Each entry stores
 * enough info to fire on its own — channel id, channel name,
 * channel logo URL, channel LCN, programme title, start ms,
 * stop ms — so the watcher can pop a banner with full context
 * without re-resolving anything from the EPG bundle.
 *
 * Storage key is `channelId + ":" + startMs`.
 */
object ReminderStore {

    private const val PREF_NAME = "v2_livetv_reminders"
    private const val KEY = "reminders_v1"
    private const val TAG = "ReminderStore"

    data class Reminder(
        val key: String,
        val channelId: String,
        val channelName: String,
        val channelLogo: String?,
        val channelLcn: String?,
        val title: String,
        val startMs: Long,
        val stopMs: Long,
        /** Wall-clock time we last raised a banner for this
         *  reminder.  Used to suppress repeat banners within the
         *  same firing window. */
        var firedAt: Long = 0L,
    )

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun load(ctx: Context): MutableMap<String, Reminder> {
        val raw = prefs(ctx).getString(KEY, null) ?: return mutableMapOf()
        return try {
            val arr = JSONArray(raw)
            val out = mutableMapOf<String, Reminder>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val r = Reminder(
                    key = o.optString("key"),
                    channelId = o.optString("channel_id"),
                    channelName = o.optString("channel_name"),
                    channelLogo = o.optString("channel_logo").ifBlank { null },
                    channelLcn = o.optString("channel_lcn").ifBlank { null },
                    title = o.optString("title"),
                    startMs = o.optLong("start_ms", 0L),
                    stopMs = o.optLong("stop_ms", 0L),
                    firedAt = o.optLong("fired_at", 0L),
                )
                if (r.key.isNotBlank()) out[r.key] = r
            }
            out
        } catch (t: Throwable) {
            Log.w(TAG, "load failed: ${t.message}")
            mutableMapOf()
        }
    }

    fun save(ctx: Context, items: Map<String, Reminder>) {
        val arr = JSONArray()
        for (r in items.values) {
            arr.put(JSONObject().apply {
                put("key", r.key)
                put("channel_id", r.channelId)
                put("channel_name", r.channelName)
                put("channel_logo", r.channelLogo ?: "")
                put("channel_lcn", r.channelLcn ?: "")
                put("title", r.title)
                put("start_ms", r.startMs)
                put("stop_ms", r.stopMs)
                put("fired_at", r.firedAt)
            })
        }
        prefs(ctx).edit().putString(KEY, arr.toString()).apply()
    }

    /** Drop reminders whose stop time has already passed. */
    fun pruneExpired(ctx: Context, items: MutableMap<String, Reminder>) {
        val now = System.currentTimeMillis()
        val removed = items.values.filter { it.stopMs > 0 && it.stopMs < now }
        if (removed.isEmpty()) return
        for (r in removed) items.remove(r.key)
        save(ctx, items)
    }
}
