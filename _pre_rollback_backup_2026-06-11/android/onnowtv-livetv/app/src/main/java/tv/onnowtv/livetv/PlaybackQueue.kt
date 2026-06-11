package tv.onnowtv.livetv

import tv.onnowtv.livetv.data.Channel

/**
 * Process-scoped holder for the channel list the player should
 * navigate when the user presses D-pad UP / DOWN inside
 * PlayerActivity.  Set by EpgActivity right before
 * `startActivity(PlayerActivity)`; the player reads the index +
 * list from here so we don't have to serialise the whole bundle
 * through Intent extras.
 */
object PlaybackQueue {
    @Volatile var channels: List<Channel> = emptyList()
    @Volatile var index: Int = 0

    fun setQueue(list: List<Channel>, startId: String) {
        channels = list
        index = list.indexOfFirst { it.id == startId }.coerceAtLeast(0)
    }

    /** Move to the next channel (wraps around end → start). */
    fun next(): Channel? {
        val list = channels
        if (list.isEmpty()) return null
        index = (index + 1) % list.size
        return list[index]
    }

    /** Move to the previous channel (wraps around start → end). */
    fun prev(): Channel? {
        val list = channels
        if (list.isEmpty()) return null
        index = if (index <= 0) list.size - 1 else index - 1
        return list[index]
    }

    /** Resolve a typed channel number (LCN) to a channel.  Returns
     *  null when no exact LCN match exists. */
    fun byLcn(lcn: String): Channel? {
        if (lcn.isBlank()) return null
        val list = channels
        val idx = list.indexOfFirst { it.lcn == lcn }
        if (idx < 0) return null
        index = idx
        return list[idx]
    }
}
