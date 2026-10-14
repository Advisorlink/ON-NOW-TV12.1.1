package tv.onnowtv.livetv

import android.app.Activity
import android.content.Intent
import tv.onnowtv.livetv.data.Channel

/** All sports entry points use the same player and the same explicit return-to-sports policy. */
object SportsPlayback {
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