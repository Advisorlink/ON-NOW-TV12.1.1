package tv.onnowtv.livetv.data

/** Archive programmes are not fabricated ESPN fixtures. The provider's title/date stay authoritative. */
object SportsReplayCatalogue {
    data class Entry(val channel: Channel, val programme: Programme, val bucket: String, val fixtureJson: String? = null) {
        val key get() = "${channel.id}-${programme.startMs}"
    }
    fun channels(bundle: XtreamBundle): List<Channel> = bundle.channels.filter { channel ->
        channel.archiveEnabled && channel.archiveDays in 1..366 && SportsReplayPolicy.providerApi(channel) != null
    }.sortedBy { it.lcn?.toIntOrNull() ?: Int.MAX_VALUE }.distinctBy { it.id }

    fun entries(channel: Channel, rows: List<Programme>, now: Long = System.currentTimeMillis()): List<Entry> = rows.mapNotNull { p ->
        if (p.archiveAvailable != true || !SportsReplayPolicy.checkProgramme(channel, p, now).eligible) return@mapNotNull null
        if (SportsEventPolicy.isProgramme(p.title)) return@mapNotNull null
        val fixture = SportsEventMetadata.match(p)
        val bucket = fixture?.let { SportsCategories.bucket(it) } ?: SportsEventPolicy.bucket(p, channel) ?: return@mapNotNull null
        Entry(channel, p, bucket, fixture?.toString())
    }.distinctBy { it.key }.sortedByDescending { it.programme.startMs }
}