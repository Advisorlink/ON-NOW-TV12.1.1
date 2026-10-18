package tv.onnowtv.vespernative.data

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject

/** Instance is scoped to one signed-in session; no account's cache survives sign-out. */
class CatalogRepository(private val api: VesperApi, private val token: () -> String?) {
    val browse = BrowseRepository(api, token)
    val trailers = tv.onnowtv.vespernative.playback.TrailerRepository(api, token)
    val extras = TitleExtrasRepository(api, token)
    private var catalogCache: List<Catalog>? = null
    private val rows = linkedMapOf<String, Pair<Long, List<Title>>>()
    private val networkSlots = Semaphore(3)

    suspend fun catalogs(): List<Catalog> {
        catalogCache?.let { return it }
        val addons = api.request(listOf("addons"), token()) as JSONArray
        return addons.objects().flatMap { addon ->
            addon.optJSONArray("catalogs")?.objects()?.mapNotNull { cat ->
                val type = cat.text("type")
                if (type !in setOf("movie", "series")) null else {
                    val extra = cat.optJSONArray("extra")?.objects().orEmpty()
                    Catalog(addon.text("id"), type, cat.text("id"), cat.text("name"),
                        extra.any { it.text("name") == "search" })
                }
            }.orEmpty()
        }.filter { it.addon.isNotBlank() && it.id.isNotBlank() }.also { catalogCache = it }
    }

    suspend fun homeCatalogs(type: String? = null): List<Catalog> {
        val all = catalogs()
        val desired = listOf("movie" to "year", "series" to "year", "movie" to "top", "series" to "top")
        return desired.mapNotNull { (media, suffix) ->
            if (type != null && media != type) null else all.firstOrNull { it.type == media && it.id == suffix }
                ?.copy(name = when (suffix) { "year" -> "New "; else -> "Popular " } + if (media == "movie") "movies" else "series")
        }
    }

    suspend fun catalog(cat: Catalog, skip: Int = 0, search: String? = null): List<Title> {
        val key = "${cat.key}:$skip:${search.orEmpty()}"
        rows[key]?.takeIf { System.currentTimeMillis() - it.first < 600_000 }?.let { return it.second }
        val params = buildMap { if (skip > 0) put("skip", skip.toString()); if (search != null) put("search", search) }
        val result = networkSlots.withPermit {
            api.request(listOf("addons", cat.addon, "catalog", cat.type, cat.id), token(), params) as JSONObject
        }
        return result.getJSONObject("data").optJSONArray("metas")?.objects().orEmpty()
            .mapNotNull { Title.parse(it, cat.type) }.distinctBy(Title::key).also {
                if (rows.size >= 24) rows.remove(rows.keys.first())
                rows[key] = System.currentTimeMillis() to it
            }
    }

    suspend fun search(query: String): List<Title> = coroutineScope {
        if (query.trim().length < 2) return@coroutineScope emptyList()
        val sources = catalogs().filter(Catalog::searchable)
        if (sources.isEmpty()) throw IllegalStateException("No searchable catalogues are available.")
        val results = sources.map { cat -> async {
            try { Result.success(catalog(cat, search = query.trim()))
            } catch (e: CancellationException) { throw e
            } catch (e: ApiFailure) { if (e.status == 401 || e.status == 403) throw e else Result.failure(e)
            } catch (e: Exception) { Result.failure(e) }
        } }.awaitAll()
        if (results.all { it.isFailure }) throw results.first().exceptionOrNull()!!
        // IMDb identifies a work globally, even when an addon misfiles it under
        // two media types. Opaque provider IDs may collide across types instead.
        results.flatMap { it.getOrDefault(emptyList()) }.distinctBy {
            if (it.id.matches(Regex("tt[0-9]+"))) it.id else it.key
        }
    }

    suspend fun detail(title: Title): Title {
        val id = if (title.id.startsWith("tmdb:")) {
            val resolved = api.request(listOf("tmdb", "imdb", if (title.type == "series") "tv" else "movie", title.id.substringAfter(':')), token()) as JSONObject
            resolved.text("imdb_id").ifBlank { throw IllegalStateException("Title details are unavailable.") }
        } else title.id
        val result = api.request(listOf("meta", title.type, id), token()) as JSONObject
        return Title.parse(result.getJSONObject("data").getJSONObject("meta"), title.type)
            ?: throw IllegalStateException("Title details are unavailable.")
    }
}