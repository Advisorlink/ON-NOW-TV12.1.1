package tv.vesper.native_app.data

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Pulls real shelves from the same backend addon API the React
 * Vesper UI talks to (`/api/addons` + `/api/addons/{id}/catalog/
 * {type}/{catalogId}`).  Returns shelves shaped like the React
 * `useLiveShelves` hook so we get the SAME visual content the user
 * sees in their current Vesper app.
 *
 * Network is OkHttp — kept small + dep-light; we hop to a coroutine
 * dispatcher from the activity, no Retrofit/Moshi needed for v1.
 */
object CatalogRepository {

    /** Same prod backend the WebView Vesper hits. */
    const val BACKEND_BASE = "https://onnowtv.duckdns.org"

    /** TMDB-proxy backdrop endpoint used by the hero billboard. */
    const val EPG_ART_PATH = "/api/epg/art"

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Fetch the "Movies Popular / Series Popular / Movies New /
     * Series New" essential-4 shelves the user has locked in for
     * the React Home page (Home.jsx ~line 73).  We iterate every
     * installed addon, walk its catalog list, keep catalogs whose
     * combined `{type}-{id}` ends in `-movie-top`, `-series-top`,
     * `-movie-year`, or `-series-year` — same filter React uses.
     * Returns shelves PROGRESSIVELY via the [onShelf] callback so
     * the UI paints as data arrives — single slow addon can't
     * block the page.
     */
    fun fetchShelves(onShelf: (Shelf) -> Unit, onDone: () -> Unit) {
        // Match React's Home.jsx "wanted" list — suffix is on
        // `{addonId}-{catalogType}-{catalogId}`, but the
        // distinguishing tail is just `{type}-{id}`.
        val wantedTails = setOf(
            "movie-top",   // Popular movies
            "series-top",  // Popular series
            "movie-year",  // New movies
            "series-year", // New series
        )
        try {
            val addons = listAddons()
            for (addon in addons) {
                val addonId = addon.optString("id").takeIf { it.isNotBlank() }
                    ?: continue
                val addonName = addon.optString("name", "").trim()
                val catalogs = addon.optJSONArray("catalogs") ?: continue
                for (i in 0 until catalogs.length()) {
                    val cat = catalogs.optJSONObject(i) ?: continue
                    val cId = cat.optString("id")
                    val cType = cat.optString("type")
                    val cName = cat.optString("name")
                    if (cType != "movie" && cType != "series") continue
                    val tail = "$cType-$cId"
                    if (tail !in wantedTails) continue
                    val items = try {
                        fetchCatalog(addonId, cType, cId)
                    } catch (t: Throwable) {
                        Log.w("CatalogRepo", "catalog $addonId/$cType/$cId failed: ${t.message}")
                        continue
                    }
                    if (items.isNotEmpty()) {
                        val title = buildShelfTitle(cType, cId, cName, addonName)
                        onShelf(Shelf(id = "$addonId-$cType-$cId", title = title, items = items))
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e("CatalogRepo", "fetchShelves failed", t)
        } finally {
            onDone()
        }
    }

    /**
     * Match React's logic: prefer the catalog's "name" field but
     * strip a leading "{Addon Name} - " / ": " / etc. prefix and
     * any trailing brand suffix so we end up with a clean
     * "Popular" / "New" style label.  Fall back to a deterministic
     * "{Movies|TV Shows} · {Popular|New}" if name is empty.
     */
    private fun buildShelfTitle(
        type: String,
        catalogId: String,
        catalogName: String,
        addonName: String,
    ): String {
        val noun = if (type == "movie") "Movies" else "TV Shows"
        val kind = if (catalogId.endsWith("top")) "Popular" else "New"
        val fallback = "$noun · $kind"
        val raw = catalogName.trim()
        if (raw.isEmpty()) return fallback
        // Strip leading addon name + separator
        var t = raw
        if (addonName.isNotEmpty()) {
            val esc = Regex.escape(addonName)
            t = t.replace(Regex("^$esc\\s*[-–—:•|]\\s*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s*[-–—:•|]\\s*$esc$", RegexOption.IGNORE_CASE), "")
                .trim()
        }
        return if (t.isEmpty()) fallback else t
    }

    private fun listAddons(): List<JSONObject> {
        val url = "$BACKEND_BASE/api/addons"
        val body = httpGetText(url) ?: return emptyList()
        val arr = JSONArray(body)
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    private fun fetchCatalog(addonId: String, type: String, catalogId: String): List<CatalogItem> {
        val url = "$BACKEND_BASE/api/addons/$addonId/catalog/$type/$catalogId"
        val body = httpGetText(url) ?: return emptyList()
        val root = JSONObject(body)
        // Backend wraps the upstream addon payload in `{cached, data}`.
        // Older / non-cached responses may put `metas` at the top level
        // — handle both shapes.
        val payload = root.optJSONObject("data") ?: root
        val metas = payload.optJSONArray("metas") ?: return emptyList()
        val out = ArrayList<CatalogItem>(metas.length())
        for (i in 0 until metas.length()) {
            val m = metas.optJSONObject(i) ?: continue
            out.add(metaToItem(m, type))
        }
        return out
    }

    private fun metaToItem(m: JSONObject, type: String): CatalogItem {
        val genresArr = m.optJSONArray("genres")
        val genres = if (genresArr != null) {
            (0 until genresArr.length()).map { genresArr.optString(it) }.filter { it.isNotBlank() }
        } else emptyList()
        return CatalogItem(
            id = m.optString("id"),
            type = m.optString("type").ifBlank { type },
            title = m.optString("name").ifBlank { m.optString("title") },
            poster = m.optString("poster").ifBlank { null },
            backdrop = m.optString("background").ifBlank { null },
            year = m.optString("year").ifBlank { m.optString("releaseInfo").ifBlank { null } },
            genres = genres,
            synopsis = m.optString("description").ifBlank { null },
            imdbId = m.optString("imdb_id").ifBlank {
                m.optString("id").takeIf { it.startsWith("tt") }
            },
        )
    }

    private fun httpGetText(url: String): String? {
        return try {
            val req = Request.Builder().url(url).get().build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                resp.body?.string()
            }
        } catch (t: Throwable) {
            Log.w("CatalogRepo", "GET $url failed: ${t.message}")
            null
        }
    }
}
