package tv.onnowtv.livetv.data

/** Short-lived provider EPG, isolated by actual channel URL/account; contains no recording claims. */
object SportsGuideOverlay {
    private data class Entry(val expires: Long, val programmes: List<Programme>)
    private val entries = mutableMapOf<String, Entry>()
    @Synchronized fun get(channel: Channel): List<Programme> = entries[channel.streamUrl]?.takeIf { it.expires > System.currentTimeMillis() }?.programmes.orEmpty()
    @Synchronized fun fresh(channel: Channel) = (entries[channel.streamUrl]?.expires ?: 0) > System.currentTimeMillis()
    @Synchronized fun put(channel: Channel, programmes: List<Programme>) { entries.entries.removeAll { it.value.expires <= System.currentTimeMillis() }; entries[channel.streamUrl] = Entry(System.currentTimeMillis() + 600_000, programmes) }
}