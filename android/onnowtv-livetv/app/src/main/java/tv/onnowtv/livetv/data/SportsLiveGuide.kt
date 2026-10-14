package tv.onnowtv.livetv.data

/** Real current programmes; deliberately NOT synthetic ESPN fixtures, scores or replays. */
object SportsLiveGuide {
    data class Entry(val channel: Channel, val programme: Programme, val bucket: String) {
        val key: String get() = "${channel.id}-${programme.startMs}"
    }
    fun current(bundle: XtreamBundle, bucket: String? = null, now: Long = System.currentTimeMillis()): List<Entry> {
        val guide = SharedSportsGuide.rows(bundle)
        return bundle.channels.mapNotNull { channel ->
            val programme = guide[channel.epgChannelId ?: channel.id].orEmpty().firstOrNull { it.isLiveAt(now) } ?: return@mapNotNull null
            val classified = LiveSportsClassifier.liveBucket(programme, channel.name, now) ?: return@mapNotNull null
            if (bucket != null && classified != bucket) null else Entry(channel, programme, classified)
        }.sortedWith(compareBy<Entry> { LiveSportsClassifier.DISPLAY_ORDER.indexOf(it.bucket) }
            .thenBy { it.channel.lcn?.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it.channel.name })
    }
}