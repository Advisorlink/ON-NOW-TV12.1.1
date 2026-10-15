package tv.onnowtv.vespernative.profile

import org.json.JSONObject
import tv.onnowtv.vespernative.data.*

/** Read projections only. The original snapshot is never rewritten by this view. */
class SavedLibrary(private val data: ProfileData) {
    fun favourites(type: String): List<Title> {
        val favourites = data.library().optJSONObject("favorites") ?: return emptyList()
        return favourites.keys().asSequence().mapNotNull { id ->
            favourites.optJSONObject(id)?.takeIf { it.text("type") == type }?.let { value ->
                val meta = value.optJSONObject("meta") ?: return@let null
                title(id, type, meta)?.let { it to value.text("addedAt") }
            }
        }.toList().sortedByDescending { it.second }.map { it.first }
    }
    fun watchLater(): List<Title> = data.library().optJSONArray("watchLater")?.objects().orEmpty()
        .sortedByDescending { it.text("addedAt") }.mapNotNull { item ->
            val type = item.text("type")
            if (type !in setOf("movie", "series")) return@mapNotNull null
            val meta = item.optJSONObject(if (type == "series") "show" else "movie") ?: return@mapNotNull null
            title(item.text("id"), type, meta)
        }.distinctBy(Title::key)

    fun continueWatching(): List<Title> = data.continueWatching().objects()
        .sortedByDescending { it.optLong("updatedAt", 0) }.mapNotNull { item ->
            val type = item.text("type")
            if (type !in setOf("movie", "series")) return@mapNotNull null
            val id = if (type == "series") item.text("id").substringBefore(':') else item.text("id")
            title(id, type, item)
        }.distinctBy(Title::key)

    private fun title(id: String, type: String, meta: JSONObject): Title? {
        val name = meta.text("name").ifBlank { meta.text("title") }
        if (id.isBlank() || name.isBlank()) return null
        return Title(id, type, name, meta.text("poster"), meta.text("background").ifBlank { meta.text("backdrop") },
            meta.text("description").ifBlank { meta.text("synopsis") },
            meta.text("releaseInfo").ifBlank { meta.text("year") }, meta.text("imdbRating").ifBlank { meta.text("rating") })
    }
}