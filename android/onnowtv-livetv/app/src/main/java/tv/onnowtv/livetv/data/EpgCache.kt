package tv.onnowtv.livetv.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * v2.9.9 — Disk cache for the parsed XMLTV priority-channel EPG.
 *
 * v2.9.12 — Cache is now PERMANENT.  User explicitly asked: once
 * the EPG is loaded, it should be cached forever — no expiry, no
 * forced re-fetch — until the user actively deletes the app or
 * signs out.  Refreshes happen ONLY when the user taps the rail
 * refresh button.
 *
 * Kept SEPARATE from `BundleCache` because the channel list and
 * the EPG are merged at hydration time but have different
 * persistence requirements (the bundle is rebuilt on every
 * cold boot; the EPG is sticky).
 *
 * File: `filesDir/epg_priority.json.gz` — gzipped JSON object of
 * shape `{ "<epg_channel_id>": [ { t, d?, s, e }, … ] }`.
 */
object EpgCache {
    private const val TAG = "EpgCache"
    private const val FILE_NAME = "epg_priority.json.gz"
    private const val TS_FILE_NAME = "epg_priority.timestamp"

    /** v2.9.12 — Cache is permanent.  Kept as a sentinel constant
     *  so any caller that previously branched on `ageMs() < FRESH_MS`
     *  always takes the "fresh" branch. */
    const val FRESH_MS = Long.MAX_VALUE

    private fun fileFor(ctx: Context): File = File(ctx.filesDir, FILE_NAME)
    private fun tsFile(ctx: Context): File = File(ctx.filesDir, TS_FILE_NAME)

    fun exists(ctx: Context): Boolean = fileFor(ctx).exists()

    fun ageMs(ctx: Context): Long {
        val ts = tsFile(ctx)
        if (!ts.exists()) return Long.MAX_VALUE
        val w = ts.readText().trim().toLongOrNull() ?: return Long.MAX_VALUE
        return System.currentTimeMillis() - w
    }

    /** Serialise the in-memory EPG map and write it as gzipped JSON. */
    fun save(ctx: Context, epg: Map<String, List<Programme>>) {
        try {
            val obj = JSONObject()
            for ((channelId, programmes) in epg) {
                val arr = JSONArray()
                for (p in programmes) {
                    val o = JSONObject()
                    o.put("t", p.title)
                    if (!p.description.isNullOrBlank()) o.put("d", p.description)
                    o.put("s", p.startMs / 1000L) // seconds — saves bytes
                    o.put("e", p.stopMs / 1000L)
                    arr.put(o)
                }
                obj.put(channelId, arr)
            }
            val json = obj.toString()
            val tmp = File(ctx.filesDir, "$FILE_NAME.tmp")
            GZIPOutputStream(tmp.outputStream()).use { gz ->
                gz.write(json.toByteArray(Charsets.UTF_8))
            }
            tmp.renameTo(fileFor(ctx))
            tsFile(ctx).writeText(System.currentTimeMillis().toString())
            Log.i(TAG, "saved ${epg.size} channels, ${epg.values.sumOf { it.size }} programmes (${json.length} chars uncompressed)")
        } catch (t: Throwable) {
            Log.w(TAG, "save failed: ${t.message}")
        }
    }

    /** Read the cached EPG back into memory. */
    fun load(ctx: Context): Map<String, List<Programme>>? {
        val f = fileFor(ctx)
        if (!f.exists()) return null
        return try {
            val text = GZIPInputStream(f.inputStream())
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            val obj = JSONObject(text)
            val out = HashMap<String, List<Programme>>(obj.length())
            val keys = obj.keys()
            while (keys.hasNext()) {
                val cid = keys.next()
                val arr = obj.optJSONArray(cid) ?: continue
                val list = ArrayList<Programme>(arr.length())
                for (i in 0 until arr.length()) {
                    val p = arr.getJSONObject(i)
                    list.add(
                        Programme(
                            title = p.optString("t").ifBlank { "—" },
                            description = p.optString("d").takeIf { it.isNotBlank() },
                            startMs = p.optLong("s", 0L) * 1000L,
                            stopMs = p.optLong("e", 0L) * 1000L,
                        ),
                    )
                }
                out[cid] = list
            }
            Log.i(TAG, "loaded ${out.size} channels, ${out.values.sumOf { it.size }} programmes (age=${ageMs(ctx)/1000}s)")
            out
        } catch (t: Throwable) {
            Log.w(TAG, "load failed: ${t.message}")
            null
        }
    }

    /** v2.9.11 — Wipe the cache (used on sign-out). */
    fun delete(ctx: Context) {
        try { fileFor(ctx).delete() } catch (_: Throwable) {}
        try { tsFile(ctx).delete() } catch (_: Throwable) {}
    }

    /**
     * v2.9.12 — Merge a single channel's freshly-fetched programmes
     * into the on-disk cache.  Called by `EpgActivity` whenever
     * the per-channel lazy-load path hits the provider directly
     * (channels outside the priority UK/USA/AU/NZ set).  Result:
     * once you've clicked on a channel and seen its EPG, you NEVER
     * have to wait for it again — it's persisted with the rest.
     */
    @Synchronized
    fun mergeChannel(ctx: Context, channelId: String, programmes: List<Programme>) {
        if (channelId.isBlank() || programmes.isEmpty()) return
        val current = load(ctx)?.toMutableMap() ?: HashMap(64)
        current[channelId] = programmes
        save(ctx, current)
    }
}
