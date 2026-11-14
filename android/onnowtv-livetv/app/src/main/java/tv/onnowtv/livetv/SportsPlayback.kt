package tv.onnowtv.livetv

import android.app.Activity
import android.content.Intent
import tv.onnowtv.livetv.data.Channel

/** All sports entry points use the same player and the same explicit return-to-sports policy. */
object SportsPlayback {
    /** Sports the live stats screen can serve (same list as the guide's What's On). */
    val statsSports = setOf("soccer", "afl", "nba", "nhl", "mlb", "rugby", "nrl", "nfl", "f1", "mma", "cricket", "tennis")

    /** Live sport with stats available: ask "Watch with Live Stats" or "Watch Fullscreen". Anything else just plays. */
    fun offer(activity: Activity, channel: Channel, programmeTitle: String, bucket: String, subtitle: String = programmeTitle) {
        if (bucket !in statsSports) { open(activity, channel, subtitle = subtitle); return }
        val clean = programmeTitle.replace("\u1D38\u1DA6\u1D5B\u1D49", "").trim()
        tv.onnowtv.livetv.ui.ActionSheetDialog(activity)
            .title(clean)
            .subtitle("Live now · " + tv.onnowtv.livetv.data.LiveSportsClassifier.labelOf(bucket) + " · " + channel.name)
            .item("Watch with Live Stats", icon = "▦") { openStats(activity, channel, clean, bucket) }
            .item("Watch Fullscreen", icon = "▶") { open(activity, channel, subtitle = subtitle) }
            .show()
    }

    fun openStats(activity: Activity, channel: Channel, programmeTitle: String, bucket: String) {
        if (LivePreviewSession.currentChannel?.id != channel.id || !LivePreviewSession.isAlive()) LivePreviewSession.setChannel(activity, channel)
        PlaybackQueue.setQueue(listOf(channel), channel.id)
        activity.startActivity(Intent(activity, StatsPlayerActivity::class.java).apply {
            putExtra(StatsPlayerActivity.EXTRA_CHANNEL_ID, channel.id)
            putExtra(StatsPlayerActivity.EXTRA_URL, channel.streamUrl)
            putExtra(StatsPlayerActivity.EXTRA_CHANNEL_NAME, channel.name)
            putExtra(StatsPlayerActivity.EXTRA_PROGRAMME_TITLE, programmeTitle)
            putExtra(StatsPlayerActivity.EXTRA_SPORT, bucket)
            putExtra(StatsPlayerActivity.EXTRA_STOP_ON_EXIT, true)
        })
    }
    fun open(activity: Activity, channel: Channel, queue: List<Channel> = listOf(channel), subtitle: String = "", failover: Boolean = false) {
        PlaybackQueue.setQueue(queue.distinctBy { it.id }, channel.id)
        activity.startActivity(Intent(activity, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_URL, channel.streamUrl)
            putExtra(PlayerActivity.EXTRA_TITLE, channel.name)
            putExtra(PlayerActivity.EXTRA_SUBTITLE, subtitle)
            putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channel.id)
            putExtra(PlayerActivity.EXTRA_USE_SHARED_PLAYER, true)
            putExtra(PlayerActivity.EXTRA_STOP_ON_EXIT, true)
            putExtra(PlayerActivity.EXTRA_FAILOVER_QUEUE, failover && queue.size > 1)
        })
    }
}