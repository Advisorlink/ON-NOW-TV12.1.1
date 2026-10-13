package tv.onnowtv.livetv.data

import org.json.JSONObject

/** Provider-optional values must not become literal "null", invented scores or future probabilities. */
object SportsPresentation {
    fun value(o: JSONObject?, key: String, fallback: String = ""): String =
        o?.optString(key)?.takeUnless { it.isBlank() || it == "null" } ?: fallback
    fun key(g: JSONObject) = "${g.optString("sport")}|${g.optString("league")}|${g.optString("id")}"
    fun teamKey(g: JSONObject, side: String) = "${g.optString("sport")}|${g.optString("league")}|${value(g.optJSONObject(side), "id")}"
    fun abbreviation(t: JSONObject?) = value(t, "abbr", value(t, "short", "—"))
    fun homeProbability(plays: List<JSONObject>, points: List<JSONObject>, index: Int): Double? {
        if (index !in plays.indices) return null
        val byPlay = points.filter { value(it, "playId").isNotEmpty() }.associateBy { it.optString("playId") }
        for (i in index downTo 0) {
            val point = byPlay[plays[i].optString("id")] ?: continue
            val number = point.optDouble("home", Double.NaN)
            if (number.isFinite() && number in 0.0..100.0) return number
        }
        return null
    }
}