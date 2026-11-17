package tv.rebornplay

import android.content.Context
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Wide (backdrop) artwork from TMDB. Wide cards never fall back to a cropped tall cover. */
object TmdbArt {
    private fun p(ctx: Context) = ctx.getSharedPreferences("tmdb_art", Context.MODE_PRIVATE)
    private val gate = Semaphore(3)
    private val missed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private fun isTmdb(u: String?) = u != null && u.contains("image.tmdb.org")
    private fun norm(s: String) = s.lowercase().replace(Regex("\\(\\d{4}\\)|\\b(4k|uhd|hd|fhd|multi|hdr)\\b"), " ").replace(Regex("[^a-z0-9]+"), " ").trim()

    /** Known straight away, without a lookup. */
    fun known(ctx: Context, item: Item): String? = item.background?.takeIf { isTmdb(it) } ?: Gorilla.cachedInfo(item)?.background?.takeIf { isTmdb(it) } ?: p(ctx).getString(item.id, null)

    suspend fun resolve(ctx: Context, item: Item): String? {
        known(ctx, item)?.let { return it }
        if (item.type != "movie" && item.type != "series") return item.background
        // 1) the service's own details usually carry the TMDB backdrop
        runCatching { Gorilla.enrich(ctx, item) }.getOrNull()?.background?.takeIf { isTmdb(it) }?.let { p(ctx).edit().putString(item.id, it).apply(); return it }
        // 2) otherwise find the title on TMDB by name, type and year
        if (item.id in missed) return item.background
        val found = gate.withPermit {
            val want = norm(item.name)
            val hits = runCatching { Tmdb.search(item.name) }.getOrDefault(emptyList()).filter { it.type == item.type && it.background != null && norm(it.name) == want }
            val y = item.year?.toIntOrNull()
            (hits.firstOrNull { h -> y != null && h.year?.toIntOrNull()?.let { kotlin.math.abs(it - y) <= 1 } == true } ?: hits.firstOrNull().takeIf { y == null || hits.size == 1 })?.background
        }
        if (found != null) { p(ctx).edit().putString(item.id, found).apply(); return found }
        missed += item.id
        return item.background   // a non-TMDB wide image from the service is still wide; a tall cover is never used
    }
}

/** Wide artwork for [item]; null until/unless a wide image exists (callers show a plain card, never the tall cover). */
@Composable
fun rememberWideArt(item: Item?): String? {
    val ctx = LocalContext.current
    var url by remember(item?.id) { mutableStateOf(item?.let { TmdbArt.known(ctx, it) }) }
    LaunchedEffect(item?.id, item?.background) { if (item != null && url == null) url = TmdbArt.resolve(ctx, item) }
    return url
}
