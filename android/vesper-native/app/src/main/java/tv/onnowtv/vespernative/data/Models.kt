package tv.onnowtv.vespernative.data

import org.json.JSONArray
import org.json.JSONObject

fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONObject.text(key: String): String = if (isNull(key)) "" else optString(key, "")

data class Account(val id: String, val username: String, val label: String) {
    companion object {
        fun parse(json: JSONObject): Account {
            val id = json.text("id")
            val username = json.text("username")
            require(id.isNotBlank() && username.isNotBlank()) { "Invalid account response" }
            return Account(id, username, json.text("label").ifBlank { username })
        }
    }
}

data class Title(
    val id: String, val type: String, val name: String, val poster: String = "",
    val backdrop: String = "", val description: String = "", val year: String = "",
    val rating: String = "", val genres: List<String> = emptyList(),
    val episodes: List<Episode> = emptyList(), val runtime: String = "", val logo: String = ""
) {
    val key get() = "$type:$id"
    val subtitle get() = listOf(year, rating.takeIf { it.isNotBlank() }?.let { "★ $it" })
        .filterNotNull().filter(String::isNotBlank).joinToString(" · ")
    companion object {
        fun parse(json: JSONObject, type: String): Title? {
            val id = json.text("id")
            val name = json.text("name")
            if (id.isBlank() || name.isBlank()) return null
            val genres = json.optJSONArray("genres") ?: JSONArray()
            return Title(id, type, name, json.text("poster"), json.text("background"),
                json.text("description"), json.text("releaseInfo"), json.text("imdbRating"),
                (0 until genres.length()).map { genres.optString(it) }.filter(String::isNotBlank),
                json.optJSONArray("videos")?.objects()?.mapNotNull(Episode::parse)
                    ?.sortedWith(compareBy({ it.season }, { it.number })) ?: emptyList(), json.text("runtime"), json.text("logo"))
        }
    }
}

data class Episode(val id: String, val season: Int, val number: Int, val name: String, val released: String,
    val overview: String = "", val thumbnail: String = "",val runtime:String="",val rating:String="") {
    companion object {
        fun parse(json: JSONObject): Episode? {
            val id = json.text("id")
            val season = json.optInt("season", -1)
            val number = json.optInt("episode", -1)
            if (id.isBlank() || season < 0 || number < 0) return null
            return Episode(id, season, number, json.text("name").ifBlank { json.text("title") }.ifBlank { "Episode $number" },
                json.text("released"), json.text("overview").ifBlank { json.text("description") }, json.text("thumbnail"),json.text("runtime"),json.text("rating"))
        }
    }
}

data class Catalog(val addon: String, val type: String, val id: String, val name: String, val searchable: Boolean) {
    val key get() = "$addon-$type-$id"
}