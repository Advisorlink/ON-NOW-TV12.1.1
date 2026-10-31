package tv.onnowtv.livetv.ui

import tv.onnowtv.livetv.data.Channel
import tv.onnowtv.livetv.data.Programme
import java.util.Locale

/** One row of the Live TV search screen. */
sealed class SearchRow {
    data class Header(val title: String, val count: Int) : SearchRow()
    data class Show(val channel: Channel, val programme: Programme, val live: Boolean) : SearchRow()
    data class ChannelHit(val channel: Channel, val now: Programme?) : SearchRow()
}

/** Grouped, ranked search over every channel name and every programme in the guide.
 *  Pure and thread-safe — run it off the main thread. */
object SearchEngine {
    const val MAX_NOW = 30
    const val MAX_UPCOMING = 50
    const val MAX_CHANNELS = 30

    fun search(
        query: String,
        channels: List<Channel>,
        epg: Map<String, List<Programme>>,
        now: Long = System.currentTimeMillis(),
    ): List<SearchRow> {
        val q = query.trim().lowercase(Locale.UK)
        if (q.length < 2) return emptyList()
        val bySid = HashMap<String, Channel>(channels.size)
        for (c in channels) c.epgChannelId?.takeIf { it.isNotBlank() }?.let { bySid.putIfAbsent(it, c) }

        val nowShows = ArrayList<Pair<Int, SearchRow.Show>>()
        val later = ArrayList<Pair<Int, SearchRow.Show>>()
        val seen = HashSet<String>()
        for ((sid, progs) in epg) {
            val ch = bySid[sid] ?: continue
            for (p in progs) {
                if (p.stopMs <= now) continue
                val score = score(p.title, q)
                if (score < 0) continue
                // Same show at the same time on HD/SD/+1 copies → keep one card.
                if (!seen.add(p.title.lowercase(Locale.UK) + "|" + p.startMs)) continue
                val live = p.startMs <= now
                (if (live) nowShows else later).add(score to SearchRow.Show(ch, p, live))
            }
        }
        val chHits = ArrayList<Pair<Int, Channel>>()
        for (c in channels) {
            val s = score(c.name, q)
            if (s >= 0) chHits.add(s to c)
        }

        val out = ArrayList<SearchRow>()
        val nowSorted = nowShows.sortedWith(compareBy({ it.first }, { it.second.programme.stopMs })).take(MAX_NOW)
        val laterSorted = later.sortedWith(compareBy({ it.second.programme.startMs }, { it.first })).take(MAX_UPCOMING)
        val chSorted = chHits.sortedWith(compareBy({ it.first }, { it.second.name.length })).take(MAX_CHANNELS)
        if (nowSorted.isNotEmpty()) {
            out += SearchRow.Header("ON NOW", nowSorted.size)
            nowSorted.forEach { out += it.second }
        }
        if (laterSorted.isNotEmpty()) {
            out += SearchRow.Header("COMING UP", laterSorted.size)
            laterSorted.forEach { out += it.second }
        }
        if (chSorted.isNotEmpty()) {
            out += SearchRow.Header("CHANNELS", chSorted.size)
            chSorted.forEach { (_, c) ->
                val nowProg = c.epgChannelId?.let { epg[it] }?.firstOrNull { it.startMs <= now && it.stopMs > now }
                out += SearchRow.ChannelHit(c, nowProg)
            }
        }
        return out
    }

    /** 0 = starts with the query, 1 = a word starts with it, 2 = contains it, -1 = no match. */
    fun score(text: String, q: String): Int {
        val t = text.lowercase(Locale.UK)
        val i = t.indexOf(q)
        if (i < 0) return -1
        if (i == 0) return 0
        return if (!t[i - 1].isLetterOrDigit()) 1 else 2
    }
}
