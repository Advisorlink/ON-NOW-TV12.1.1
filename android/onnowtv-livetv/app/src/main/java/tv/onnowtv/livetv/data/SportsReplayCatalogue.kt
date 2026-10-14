package tv.onnowtv.livetv.data

/** Archive programmes are not fabricated ESPN fixtures. The provider's title/date stay authoritative. */
object SportsReplayCatalogue {
    data class Entry(val channel: Channel, val programme: Programme, val bucket: String) {
        val key get() = "${channel.id}-${programme.startMs}"
    }
    fun channels(bundle: XtreamBundle): List<Channel> = bundle.channels.filter { channel ->
        channel.archiveEnabled && channel.archiveDays in 1..366 && SportsReplayPolicy.providerApi(channel) != null &&
            (LiveSportsClassifier.classify("", channel.name) != null ||
                bundle.categories.any { it.id == channel.categoryId && it.name.contains("sport", true) })
    }.sortedBy { it.lcn?.toIntOrNull() ?: Int.MAX_VALUE }.distinctBy { it.id }

    fun entries(channel: Channel, rows: List<Programme>, now: Long = System.currentTimeMillis()): List<Entry> = rows.mapNotNull { p ->
        if (p.archiveAvailable != true || !SportsReplayPolicy.checkProgramme(channel, p, now).eligible) return@mapNotNull null
        // Sports news/highlight packages are not full event replays. A recorded repeat can be included.
        val title = p.title.lowercase()
        if (listOf("highlights", "sports news", "sportscenter", "sports centre", "preview", "documentary", "teleshopping").any { title.contains(it) }) return@mapNotNull null
        val bucket = LiveSportsClassifier.programmeBucket(p.title) ?: LiveSportsClassifier.classify(p.title, channel.name)
            ?: return@mapNotNull null
        Entry(channel, p, bucket)
    }.distinctBy { it.key }.sortedByDescending { it.programme.startMs }
}