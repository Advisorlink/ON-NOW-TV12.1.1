package tv.onnowtv.livetv.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Public sports snapshots only. Never persists credentials or provider playback URLs. */
internal class SportsResponseCache(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Entry(val json: String, val expires: Long)
    private val entries = linkedMapOf<String, Entry>()
    private var characters = 0
    @Synchronized private fun read(key: String): String? {
        val entry = entries[key] ?: return null
        if (entry.expires <= clock()) { entries.remove(key); characters -= entry.json.length; return null }
        return entry.json
    }
    @Synchronized private fun write(key: String, json: String, ttl: Long) {
        if (json.length > 500_000 || ttl <= 0) return
        entries.remove(key)?.let { characters -= it.json.length }
        while (entries.isNotEmpty() && (entries.size >= 12 || characters + json.length > 1_000_000)) {
            entries.remove(entries.keys.first())?.let { characters -= it.json.length }
        }
        entries[key] = Entry(json, clock() + ttl); characters += json.length
    }
    suspend fun load(key: String, force: Boolean = false, ttl: (JSONObject) -> Long,
                     valid: (JSONObject) -> Boolean, fetch: suspend () -> JSONObject?): JSONObject? = withContext(Dispatchers.Default) {
        if (!force) read(key)?.let { return@withContext JSONObject(it) }
        val response = fetch() ?: return@withContext null
        if (!valid(response)) return@withContext null
        val json = response.toString()
        currentCoroutineContext().ensureActive()
        write(key, json, ttl(response))
        JSONObject(json) // Callers cannot mutate another screen's cached snapshot.
    }
    companion object {
        fun gameTtl(data: JSONObject) = when (data.optString("state")) { "post" -> 120_000L; "pre" -> 15_000L; else -> 5_000L }
    }
}