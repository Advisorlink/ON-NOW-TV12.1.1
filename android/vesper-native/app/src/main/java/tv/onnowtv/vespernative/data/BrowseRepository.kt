package tv.onnowtv.vespernative.data

import android.content.Context
import org.json.JSONObject

data class Brand(val slug: String, val name: String, val kind: String, val group: String,
    val region: String, val localArt: String, val moviesOnly: Boolean, val accent: String,
    val logo: String = "", val backdrop: String = "", val treatment: String = "",val background:String="",val wordmark:String="")
data class BrowsePage(val titles: List<Title>, val hasMore: Boolean)

/** Read-only routes already used by the reference app; no new service or cloud writer. */
class BrowseRepository(private val api: VesperApi, private val token: () -> String?) {
    private val artwork = mutableMapOf<String, JSONObject>()
    fun brands(c: Context, kind: String): List<Brand> {
        val source = c.assets.open("browse-registry.json").bufferedReader().use { JSONObject(it.readText()) }
        return source.getJSONArray(kind).objects().map { b ->
            Brand(b.text("slug"), b.text("name"), kind, b.text("group"), b.text("region").ifBlank { "US" },
                b.text("customLogo").takeIf(String::isNotBlank)?.let { "file:///android_asset${it}" }.orEmpty(),
                b.optBoolean("moviesOnly"), b.text("accent"),background=b.text("background"),wordmark=b.text("wordmark"))
        }
    }
    suspend fun art(brands: List<Brand>): List<Brand> {
        val kind = brands.firstOrNull()?.kind ?: return brands
        val data = artwork[kind] ?: (api.request(listOf(kind, "logos"), token()) as JSONObject)
            .getJSONObject("data").also { artwork[kind] = it }
        return brands.map { b -> data.optJSONObject(b.slug)?.let {
            b.copy(logo = it.text("logo"), backdrop = it.text("backdrop"), treatment = it.text("logo_treatment"))
        } ?: b }
    }
    suspend fun page(brand: Brand, type: String, page: Int): BrowsePage {
        val data = (api.request(listOf(brand.kind, brand.slug), token(), mapOf(
            "type" to if (type == "series") "tv" else "movie", "page" to page.toString(), "region" to brand.region
        )) as JSONObject).getJSONObject("data")
        return BrowsePage(data.optJSONArray("results")?.objects().orEmpty().mapNotNull { tmdbTitle(it, type) },
            page < data.optInt("total_pages", 1))
    }
    suspend fun genres(type: String): List<Pair<String, String>> {
        val data = api.request(listOf("tmdb", "genres", if (type == "series") "tv" else "movie"), token()) as JSONObject
        return data.optJSONArray("data")?.objects().orEmpty().map { it.text("id") to it.text("name") }
    }
    suspend fun genre(type: String, id: String, page: Int): BrowsePage {
        val data = api.request(listOf("tmdb", "by-genres", if (type == "series") "tv" else "movie"), token(),
            mapOf("genre_ids" to id, "page" to page.toString(), "limit" to "60")) as JSONObject
        val titles = data.optJSONArray("data")?.objects().orEmpty().mapNotNull { tmdbTitle(it, type) }
        return BrowsePage(titles, titles.size >= 60)
    }
    suspend fun anime(): List<Pair<String, List<Title>>> {
        val data = api.request(listOf("tmdb", "anime"), token(), mapOf("limit" to "40")) as JSONObject
        return data.optJSONArray("rails")?.objects().orEmpty().map { rail ->
            rail.text("title") to rail.optJSONArray("data")?.objects().orEmpty().mapNotNull { tmdbTitle(it, it.text("type")) }
        }
    }
    suspend fun upcoming():List<Title> {
        val result=api.request(listOf("tmdb","upcoming-movies"),token()) as JSONObject
        return result.optJSONArray("data")?.objects().orEmpty().mapNotNull { item->tmdbTitle(item,"movie")?.copy(description=item.text("synopsis")) }
    }
    companion object {
        fun tmdbTitle(item: JSONObject, type: String): Title? {
            val id = item.text("tmdb_id"); val name = item.text("title")
            if (id.isBlank() || name.isBlank()) return null
            return Title("tmdb:$id", type, name, item.text("poster"), item.text("backdrop"), item.text("overview"),
                item.text("year").take(4), item.text("rating").takeIf { it != "0" }.orEmpty())
        }
    }
}