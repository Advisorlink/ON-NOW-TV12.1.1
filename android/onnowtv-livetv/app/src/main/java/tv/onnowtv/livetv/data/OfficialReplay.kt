package tv.onnowtv.livetv.data

import org.json.JSONObject

data class OfficialReplay(val id: String, val sport: String, val title: String, val channel: String, val eventDate: String?) {
    companion object {
        fun parse(json: JSONObject): OfficialReplay? {
            val id = json.optString("id"); val sport = json.optString("sport")
            if (!Regex("[a-z0-9-]{1,70}").matches(id) || sport !in LiveSportsClassifier.DISPLAY_ORDER || json.optString("kind") != "official_embed" || !json.optBoolean("verified_full_match")) return null
            return OfficialReplay(id, sport, json.optString("title"), json.optString("channel"), json.optString("event_date").takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) })
        }
    }
    fun dateLabel(): String = eventDate?.let { "Event date · $it · Kickoff time unavailable" } ?: "Original event date/time unavailable"
}