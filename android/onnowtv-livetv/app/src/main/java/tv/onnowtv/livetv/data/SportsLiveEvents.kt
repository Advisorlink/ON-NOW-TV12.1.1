package tv.onnowtv.livetv.data

import org.json.JSONObject

/** One presentation item per fixture/programme; every original channel remains selectable. */
object SportsLiveEvents {
    data class Event(val key: String, val title: String, val bucket: String, val sources: List<SportsLiveGuide.Entry>, val fixtureJson: String?) {
        val representative get() = sources.first()
    }
    fun fixtureKey(entry: SportsLiveGuide.Entry): String? = entry.fixtureJson?.let { SportsPresentation.key(JSONObject(it)) }
    fun titleKey(title: String): String = GameChannelMatcher.normalize(title.replace(Regex("\\s+@\\s+"), " v "))
        .replace(Regex("\\b(live|hd|uhd|fhd|4k)\\b"), " ")
        .replace(Regex("\\b(vs|versus|at)\\b"), " v ")
        .replace(Regex("\\s+"), " ").trim()
    fun group(entries: List<SportsLiveGuide.Entry>): List<Event> = entries.groupBy { entry ->
        fixtureKey(entry)?.let { "fixture-$it" } ?: "guide-${entry.bucket}-${titleKey(entry.programme.title)}"
    }.map { (key, entries) ->
        val sources = entries.distinctBy { it.channel.id }.sortedWith(compareBy<SportsLiveGuide.Entry> { it.channel.lcn?.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it.channel.name })
        val first = sources.first()
        Event(key, first.fixtureJson?.let { JSONObject(it).optString("name") }?.takeIf { it.isNotBlank() } ?: first.programme.title, first.bucket, sources, first.fixtureJson)
    }
}