package tv.onnowtv.livetv.data

/** In-memory only; bounded entries and an injectable clock keep expiry deterministic. */
internal class ReplayVerificationCache<T>(private val now: () -> Long = System::currentTimeMillis) {
    private data class Entry<T>(val value: T, val expires: Long)
    private val entries = linkedMapOf<String, Entry<T>>()
    @Synchronized fun get(key: String): T? {
        val entry = entries[key] ?: return null
        if (entry.expires <= now()) { entries.remove(key); return null }
        return entry.value
    }
    @Synchronized fun put(key: String, value: T, ttlMs: Long) {
        entries.entries.removeAll { it.value.expires <= now() }
        entries.remove(key)
        while (entries.size >= 128) entries.remove(entries.keys.first())
        entries[key] = Entry(value, now() + ttlMs)
    }
    @Synchronized fun remove(key: String) { entries.remove(key) }
}