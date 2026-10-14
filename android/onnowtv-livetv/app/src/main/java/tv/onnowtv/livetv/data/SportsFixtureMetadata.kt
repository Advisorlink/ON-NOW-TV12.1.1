package tv.onnowtv.livetv.data

import org.json.JSONArray
import org.json.JSONObject

/** Preserve only matching metadata from the SAME fixture, never stale scores, state, plays or stats. */
object SportsFixtureMetadata {
    fun merge(previous: JSONObject, incoming: JSONObject): JSONObject {
        val merged = JSONObject(incoming.toString())
        if (listOf("id", "sport", "league").any { previous.optString(it).isBlank() || previous.optString(it) != incoming.optString(it) }) return merged
        for (side in listOf("home", "away")) {
            val oldId = previous.optJSONObject(side)?.optString("id").orEmpty()
            val newId = incoming.optJSONObject(side)?.optString("id").orEmpty()
            if (oldId.isNotBlank() && newId.isNotBlank() && oldId != newId) return merged
        }
        val fresh = incoming.optJSONArray("broadcasts")
        val hasNames = fresh != null && (0 until fresh.length()).any { index ->
            val value = fresh.opt(index); value is String && value.isNotBlank() && !value.equals("null", true)
        }
        if (!hasNames) previous.optJSONArray("broadcasts")?.let { merged.put("broadcasts", JSONArray(it.toString())) }
        if (incoming.optLong("startMs") <= 0 && previous.optLong("startMs") > 0) merged.put("startMs", previous.optLong("startMs"))
        for (side in listOf("home", "away")) {
            val old = previous.optJSONObject(side) ?: continue
            val team = merged.optJSONObject(side) ?: continue
            for (key in listOf("name", "short", "abbr")) {
                if (SportsPresentation.value(team, key).isBlank()) {
                    SportsPresentation.value(old, key).takeIf { it.isNotBlank() }?.let { team.put(key, it) }
                }
            }
        }
        return merged
    }
}