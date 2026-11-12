package tv.rebornplay

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.ui.input.key.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.composables.icons.lucide.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One franchise — the same title-treatment art Vesper shows (`/api/collections/logos`). */
data class BoxSet(val slug: String, val name: String, val logo: String?, val backdrop: String?, val treatment: String, val accent: Color)

/**
 * Box Sets = Vesper's franchise list + art, filled with the films YOUR service actually carries
 * (matched by title/year against the catalogue, kept in franchise order).
 */
object BoxSets {
    private val ACCENTS = listOf(0xFFE23636, 0xFFFFE81F, 0xFFC9A24B, 0xFFD4AF37, 0xFFFF3B3B, 0xFFFFB100, 0xFFFF5E3A, 0xFF9B5CFF, 0xFF4CC9F0, 0xFFF72585, 0xFF06D6A0, 0xFFFFD166, 0xFF8ECAE6)
    var all by mutableStateOf<List<BoxSet>>(emptyList()); private set
    val films = mutableStateMapOf<String, List<Item>>()      // slug → service films, franchise order
    var ready by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    private val lock = Mutex()

    fun visible(): List<BoxSet> = all.filter { (films[it.slug]?.size ?: 0) >= 1 }
    fun get(slug: String): BoxSet? = all.firstOrNull { it.slug == slug }

    suspend fun ensure(ctx: Context) = lock.withLock {
        if (ready) return@withLock
        error = null
        runCatching {
            val cat = Gorilla.loadCatalog(ctx)
            val sets = withContext(Dispatchers.IO) { fetchSets() }
            all = sets
            val index = buildIndex(cat.movies)
            withContext(Dispatchers.IO) {
                sets.chunked(10).forEach { chunk -> coroutineScope { chunk.map { s -> async { runCatching { films[s.slug] = match(fetchFilms(s.slug), index) } } }.awaitAll() } }
            }
            ready = true
        }.onFailure { error = it.message ?: "Couldn't load box sets" }
    }

    fun reset() { ready = false; films.clear(); all = emptyList() }

    private fun fetchSets(): List<BoxSet> {
        val d = RebornApi.obj("/api/collections/logos").let { it.optJSONObject("data") ?: it }
        return d.keys().asSequence().mapIndexedNotNull { i, k ->
            val o = d.getJSONObject(k)
            if (o.optString("group") != "boxset" || o.optString("logo", "").isBlank()) null
            else BoxSet(k, o.optString("name", k), o.optString("logo"), o.optString("backdrop", null), o.optString("logo_treatment", "none"), Color(ACCENTS[i % ACCENTS.size]))
        }.toList()
    }

    /** Franchise order straight from the backend (page 1 covers every box set we ship). */
    private fun fetchFilms(slug: String): List<Item> {
        val d = RebornApi.obj("/api/collections/$slug?type=movie&page=1&region=US").let { it.optJSONObject("data") ?: it }
        val arr = d.optJSONArray("results") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val r = arr.getJSONObject(i)
            Item(id = "tmdb:${r.optLong("tmdb_id")}", type = "movie", name = r.optString("title"), poster = r.optString("poster", null), background = r.optString("backdrop", null),
                description = r.optString("overview", null), year = r.optString("year", null), rating = r.optDouble("rating", 0.0).takeIf { it > 0 }?.let { "%.1f".format(it) }, tmdbId = r.optLong("tmdb_id"))
        }
    }

    private val junk = Regex("\\b(4k|uhd|hd|hdr|dv|1080p|720p|remastered|extended|unrated|directors? cut|imax)\\b")
    fun norm(s: String): String = s.lowercase().replace(Regex("\\(.*?\\)|\\[.*?\\]"), " ").replace("&", " and ").replace(junk, " ")
        .replace(Regex("[^a-z0-9]+"), " ").trim().removePrefix("the ").trim()

    private fun buildIndex(movies: List<Item>): Map<String, List<Item>> = movies.groupBy { norm(it.name) }

    private fun match(order: List<Item>, index: Map<String, List<Item>>): List<Item> = order.mapNotNull { t ->
        val cands = index[norm(t.name)] ?: return@mapNotNull null
        val hit = cands.firstOrNull { it.yearInt != 0 && t.yearInt != 0 && kotlin.math.abs(it.yearInt - t.yearInt) <= 1 } ?: cands.firstOrNull { it.yearInt == 0 || t.yearInt == 0 } ?: return@mapNotNull null
        hit.copy(poster = hit.poster ?: t.poster, background = hit.background ?: t.background, description = hit.description ?: t.description, year = hit.year ?: t.year, rating = hit.rating ?: t.rating, tmdbId = hit.tmdbId ?: t.tmdbId)
    }.distinctBy { it.id }
}

/** CollectionTile (CollectionsShelf.jsx): 16:9 card, backdrop at 55 %, dark fade, title-treatment logo, mono name. */
@Composable
fun CollectionTile(set: BoxSet, count: Int, modifier: Modifier = Modifier, requester: FocusRequester? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    val ctx = LocalContext.current
    // Press and hold OK adds / removes the box set from My Library.
    var saved by remember(set.slug) { mutableStateOf(SavedSets.has(ctx, set.slug)) }
    var held by remember { mutableStateOf(false) }
    val hold = Modifier.onPreviewKeyEvent { ev ->
        if (ev.key != Key.DirectionCenter && ev.key != Key.Enter && ev.key != Key.NumPadEnter) return@onPreviewKeyEvent false
        if (ev.type == KeyEventType.KeyDown && ev.nativeKeyEvent.repeatCount == 0) held = false
        if (ev.type == KeyEventType.KeyDown && !held && ev.nativeKeyEvent.repeatCount >= 6) {
            held = true
            if (saved) Confirm.show("Remove ${set.name} from My Library?", "The box set stays in Box Sets — it just leaves your library.", "Remove", true) { saved = SavedSets.toggle(ctx, set) }
            else Confirm.show("Add ${set.name} to My Library?", "It will appear under Box Sets in My Library on every device signed in to this account.", "Add to Library", false) { saved = SavedSets.toggle(ctx, set) }
        }
        if (held) { if (ev.type == KeyEventType.KeyUp) held = false; true } else false
    }
    Focusable(modifier.then(hold), onClick = onClick, requester = requester) { f ->
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(16f / 9f).focusScale(f, 1.06f).tileFocus(f, 14.dp).clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF0B0F1A), Color(0xFF111A2C), set.accent.copy(alpha = 0.2f))))
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape)) {
            val w = maxWidth; val h = maxHeight
            if (set.backdrop != null) AsyncImage(set.backdrop, null, Modifier.fillMaxSize().alpha(0.55f), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0xFF05080F).copy(alpha = 0.15f), 0.6f to Color(0xFF05080F).copy(alpha = 0.45f), 1f to Color(0xFF05080F).copy(alpha = 0.85f))))
            if (set.logo != null) AsyncImage(set.logo, set.name, Modifier.padding(start = w * 0.14f, top = h * 0.18f, end = w * 0.14f, bottom = h * 0.24f).fillMaxSize(), contentScale = ContentScale.Fit,
                colorFilter = if (set.treatment == "invert") ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(-1f, 0f, 0f, 0f, 255f, 0f, -1f, 0f, 0f, 255f, 0f, 0f, -1f, 0f, 255f, 0f, 0f, 0f, 1f, 0f))) else null)
            else Text(set.name, color = Color.White, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 20.sp, letterSpacing = (-0.4).sp, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 16.dp))
            Mono(set.name, 9.sp, Color.White.copy(alpha = 0.75f), 0.2f, Modifier.align(Alignment.BottomStart).padding(start = 12.dp, bottom = 10.dp))
            if (saved) Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(22.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Blue), contentAlignment = Alignment.Center) { androidx.compose.material3.Icon(Lucide.Check, null, Modifier.size(13.dp), tint = Bg0) }
            if (count >= 0) Mono("$count films", 9.sp, Color.White.copy(alpha = 0.55f), 0.16f, Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 10.dp))
        }
    }
}

/** BoxSets.jsx — every franchise your service carries as a 16:9 title-treatment tile. */
@Composable
fun BoxSetsScreen(onOpenSet: (String) -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { BoxSets.ensure(ctx) }
    val sets = BoxSets.visible()
    val first = remember { FocusRequester() }
    FocusOnShow(first, BoxSets.ready)
    val inset = safeInset()
    Column(Modifier.fillMaxSize().padding(start = 64.dp + inset, end = 48.dp + inset, top = 84.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 22.dp), verticalAlignment = Alignment.Bottom) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) { androidx.compose.material3.Icon(Lucide.Boxes, null, Modifier.size(13.dp), tint = Blue); Spacer(Modifier.width(6.dp)); Mono("Browse · Box Sets", 11.sp, Blue, 0.32f) }
                Spacer(Modifier.height(2.dp))
                Text("Box Sets", color = Txt, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 38.sp, lineHeight = 40.sp, letterSpacing = (-1.2).sp)
            }
            Spacer(Modifier.weight(1f))
            Mono(if (BoxSets.ready) "${sets.size} sets · hold OK to add to My Library" else "Matching box sets to your service…", 10.sp, Txt3, 0.22f, Modifier.padding(bottom = 8.dp))
        }
        when {
            !BoxSets.ready && sets.isEmpty() -> if (BoxSets.error != null) Column { Text(BoxSets.error!!, color = Txt2, fontFamily = Geist, fontSize = 14.sp); Spacer(Modifier.height(14.dp)); Pill("Try again", requester = first) { BoxSets.reset() } } else Spinner("Building your box sets")
            sets.isEmpty() -> Text("None of the box sets match films on your service yet.", color = Txt2, fontFamily = Geist, fontSize = 14.sp)
            else -> LazyVerticalGrid(GridCells.Adaptive(236.dp), contentPadding = PaddingValues(top = 22.dp, bottom = 80.dp, start = 10.dp, end = 10.dp), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                itemsIndexed(sets, key = { _, s -> s.slug }) { i, s -> CollectionTile(s, BoxSets.films[s.slug]?.size ?: 0, requester = if (i == 0) first else null) { onOpenSet(s.slug) } }
            }
        }
    }
}

/** Collection.jsx — billboard follows the focused cover, one shelf "Films in order", Back pill + eyebrow top-left. */
@Composable
fun BoxSetScreen(slug: String, onBack: () -> Unit, onOpen: (Item) -> Unit, onPlay: (Item) -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { BoxSets.ensure(ctx) }
    val set = BoxSets.get(slug)
    val films = BoxSets.films[slug].orEmpty()
    val first = remember { FocusRequester() }
    var preview by remember { mutableStateOf<Item?>(null) }
    val inset = safeInset()
    if (set == null || films.isEmpty()) { if (!BoxSets.ready) Spinner("Loading box set") else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("Nothing in this box set on your service yet.", color = Txt2, fontFamily = Geist, fontSize = 15.sp); Spacer(Modifier.height(16.dp)); Pill("Back", icon = Lucide.ArrowLeft, requester = first, onClick = onBack); FocusOnShow(first, Unit) } }; return }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val total = maxHeight
        val bottomPad = total * 0.05f
        val scale by Prefs.cardScaleState(ctx)
        val tileH = ((total - total * 0.52f - bottomPad - 56.dp) * scale).coerceIn(120.dp, total * 0.58f)
        val heroH = total - bottomPad - 56.dp - tileH
        var bg by remember { mutableStateOf<Item?>(null) }
        FullBleedBackdrop(bg)
        Column(Modifier.fillMaxSize()) {
            HeroBillboard(listOf(Hero(films.first(), films.first().tmdbId ?: 0L)), "Box set · ${set.name}", null, height = heroH, preview = preview, onPlay = { onPlay(it.item) }, onInfo = { onOpen(it.item) }, onHero = { bg = it })
            Box(Modifier.fillMaxWidth().weight(1f).padding(bottom = bottomPad), contentAlignment = Alignment.BottomStart) {
                PosterShelf("Box set", "${set.name} · Films in order", films, firstRequester = first, onFocusItem = { preview = it }, tileH = tileH, bottomPad = 0.dp, onOpen = onOpen)
            }
        }
        FocusOnShow(first, films.size)
        Row(Modifier.padding(top = 14.dp + inset / 2, start = 64.dp + inset), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Pill("Back", icon = Lucide.ArrowLeft, mono = true, height = 40.dp, onClick = onBack)
            Mono("Box set · ${set.name}", 11.sp, set.accent, 0.26f)
        }
    }
}
