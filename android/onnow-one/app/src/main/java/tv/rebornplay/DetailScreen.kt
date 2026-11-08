package tv.rebornplay

import com.composables.icons.lucide.*

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import tv.vesper.app.WebAppInterface
import tv.vesper.app.YouTubeTrailerExtractor

fun launchPlayer(activity: Activity, meta: Item, title: String, url: String, cwId: String, startAtMs: Long = 0L) {
    ContinueWatching.record(activity, cwId, meta, title.substringAfter(" · ", "").ifBlank { meta.runtime ?: "" }, url)
    val one = JSONArray().put(JSONObject().put("url", url).put("label", "Reborn").put("addonSource", "Reborn").put("quality", "").put("pmCached", true).put("isEnglish", true).put("size", "")).toString()
    WebAppInterface(activity).playInternalRichV2(url, title, null, meta.poster, meta.background, meta.description, meta.year, meta.rating, meta.runtime,
        meta.genres.joinToString(", "), meta.type, startAtMs, cwId, one, 0)
}

/** Relaunch a Continue Watching entry exactly where it was left. */
fun resumeEntry(activity: Activity, e: CwEntry) {
    val title = if (e.item.type == "series" && e.label.isNotBlank()) "${e.item.name} · ${e.label}" else e.item.name
    launchPlayer(activity, e.item, title, e.url, e.cwId, e.positionMs)
}

/** Title page — same structure as Detail.jsx: backdrop, Back/Home, title, meta, synopsis, CTA row, seasons + episodes. */
@Composable
fun DetailScreen(activity: Activity, initial: Item, onBack: () -> Unit, onHome: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var meta by remember { mutableStateOf(initial) }
    var episodes by remember { mutableStateOf<List<Episode>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var ready by remember { mutableStateOf(false) }
    var season by remember { mutableStateOf(1) }
    var inList by remember { mutableStateOf(Library.has(ctx, initial.id)) }
    var trailerBusy by remember { mutableStateOf(false) }
    val primary = remember { FocusRequester() }
    val isSeries = initial.type == "series"

    LaunchedEffect(initial.id) {
        runCatching { if (isSeries) Gorilla.seriesInfo(ctx, initial).also { (m, eps) -> meta = m; episodes = eps; season = eps.firstOrNull { it.season > 0 }?.season ?: 1 } else meta = Gorilla.movieInfo(ctx, initial) }
            .onFailure { error = it.message }
        ready = true
    }
    FocusOnShow(primary, Unit)
    BackHandler(onBack = onBack)

    fun playMovie() { val url = meta.playUrl ?: run { error = "This title has no playable file yet"; return }; launchPlayer(activity, meta, meta.name, url, meta.id) }
    fun playEpisode(e: Episode) { val url = e.url ?: run { error = "This episode has no playable file yet"; return }; launchPlayer(activity, meta, "${meta.name} · S${e.season} E${e.episode} · ${e.title}", url, e.id) }

    fun playTrailer() {
        if (trailerBusy) return
        trailerBusy = true
        scope.launch {
            val key = meta.trailerYt
            val ts = key?.let { k -> withContext(Dispatchers.IO) { runCatching { YouTubeTrailerExtractor.extract(k) ?: YouTubeTrailerExtractor.extractMuxed(k) }.getOrNull() } }
            trailerBusy = false
            if (ts == null) error = "No trailer available" else WebAppInterface(activity).playTrailerFullscreen(ts.videoUrl, ts.audioUrl ?: "", "${meta.name} — Trailer")
        }
    }

    Box(Modifier.fillMaxSize().background(Bg0)) {
        AsyncImage(meta.background ?: meta.poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.85f)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Bg0.copy(alpha = 0.45f), 0.3f to Bg0.copy(alpha = 0.2f), 0.7f to Bg0.copy(alpha = 0.35f), 1f to Bg0.copy(alpha = 0.65f))))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Bg0.copy(alpha = 0.92f), 0.3f to Bg0.copy(alpha = 0.7f), 0.55f to Bg0.copy(alpha = 0.2f), 0.75f to Color.Transparent)))

        val compact = isCompact()
        val pad = if (compact) 28.dp else 80.dp
        val seasons = episodes.map { it.season }.distinct().sorted()
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = pad, end = pad, top = if (compact) 20.dp else 40.dp, bottom = 40.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Pill("Back", icon = Lucide.ArrowLeft, mono = true, height = 44.dp, onClick = onBack)
                    Pill("Home", icon = Lucide.House, mono = true, height = 44.dp, onClick = onHome)
                    if (inList) Row(Modifier.height(44.dp).clip(CircleShape).background(Blue.copy(alpha = 0.12f)).border(1.dp, Blue.copy(alpha = 0.3f), CircleShape).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Lucide.Check, null, Modifier.size(14.dp), tint = Blue); Spacer(Modifier.width(8.dp)); Mono("In my list", 11.sp, Blue, 0.18f)
                    }
                }
                Spacer(Modifier.height(if (compact) 14.dp else 22.dp))
                Column(Modifier.fillMaxWidth(if (compact) 0.85f else 0.68f)) {
                    Eyebrow(listOfNotNull(if (isSeries) "Series" else "Movie", meta.category).joinToString(" · "))
                    Spacer(Modifier.height(8.dp))
                    Text(meta.name, color = Txt, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = if (compact) 32.sp else 56.sp, lineHeight = if (compact) 34.sp else 58.sp, letterSpacing = (-1.5).sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(12.dp))
                    MetaLine(meta, if (compact) 14.sp else 18.sp)
                    Spacer(Modifier.height(12.dp))
                    Text(meta.description.orEmpty(), color = Txt2, fontFamily = Geist, fontSize = if (compact) 13.sp else 16.sp, lineHeight = if (compact) 18.sp else 24.sp, maxLines = if (isSeries || compact) 3 else 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(0.9f))
                    Spacer(Modifier.height(if (compact) 16.dp else 26.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        val h = if (compact) 46.dp else 54.dp
                        if (!isSeries) Pill(if (!ready) "Loading…" else "Play movie", icon = Lucide.Play, primary = true, height = h, fontSize = 16.sp, requester = primary, enabled = ready && meta.playUrl != null) { playMovie() }
                        else Pill(if (!ready) "Loading…" else "Play S$season E${episodes.firstOrNull { it.season == season }?.episode ?: 1}", icon = Lucide.Play, primary = true, height = h, fontSize = 16.sp, requester = primary, enabled = ready && episodes.isNotEmpty()) {
                            episodes.firstOrNull { it.season == season }?.let { playEpisode(it) }
                        }
                        Pill(if (trailerBusy) "Loading…" else "Trailer", icon = Lucide.Clapperboard, height = h, fontSize = 15.sp) { playTrailer() }
                        Pill(if (inList) "In My List" else "My List", icon = if (inList) Lucide.Check else Lucide.Plus, height = h, fontSize = 15.sp) { inList = Library.toggle(ctx, meta) }
                    }
                    if (error != null) { Spacer(Modifier.height(12.dp)); Text(error!!, color = Color(0xFFFF8A8A), fontFamily = Geist, fontSize = 13.sp) }
                }
            }
            if (isSeries && episodes.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(if (compact) 16.dp else 26.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(seasons) { s ->
                            Focusable(onClick = { season = s }) { f ->
                                val active = s == season
                                Text(if (s == 0) "Specials" else "Season $s", color = if (f || active) Bg0 else Txt2, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                                    modifier = Modifier.clip(CircleShape).background(if (f) BlueBright else if (active) Blue else Color.White.copy(alpha = 0.08f)).padding(horizontal = 16.dp, vertical = 9.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }
                items(episodes.filter { it.season == season }, key = { it.id }) { e ->
                    Box(Modifier.fillMaxWidth(if (compact) 1f else 0.68f).padding(bottom = 8.dp)) { EpisodeRow(e) { playEpisode(e) } }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(e: Episode, onClick: () -> Unit) {
    Focusable(onClick = onClick) { f ->
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (f) Blue.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.04f))
            .border(1.dp, if (f) Blue else Color.White.copy(alpha = 0.06f), RoundedCornerShape(12.dp)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(168.dp).height(94.dp).clip(RoundedCornerShape(8.dp)).background(Bg2)) {
                if (e.thumb != null) AsyncImage(e.thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Icon(Lucide.Play, null, Modifier.align(Alignment.Center).size(30.dp), tint = Color.White.copy(alpha = if (f) 1f else 0.7f))
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Mono("S${e.season} · E${e.episode}${e.released?.take(10)?.let { "  ·  $it" } ?: ""}", 10.sp, Blue, 0.18f)
                Spacer(Modifier.height(4.dp))
                Text(e.title, color = Txt, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!e.overview.isNullOrBlank()) { Spacer(Modifier.height(3.dp)); Text(e.overview.trim(), color = Txt2, fontFamily = Geist, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}
