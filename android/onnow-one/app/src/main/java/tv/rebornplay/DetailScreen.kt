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
    TrailerPlayer.halt(); HomePip.close()
    ContinueWatching.record(activity, cwId, meta, title.substringAfter(" · ", "").ifBlank { meta.runtime ?: "" }, url)
    val one = JSONArray().put(JSONObject().put("url", url).put("label", "Reborn").put("addonSource", "Reborn").put("quality", "").put("pmCached", true).put("isEnglish", true).put("size", "")).toString()
    WebAppInterface(activity).playInternalRichV2(url, title, null, meta.poster, meta.background, meta.description, meta.year, meta.rating, meta.runtime,
        meta.genres.joinToString(", "), meta.type, startAtMs, cwId, one, 0)
}

/** Relaunch a Continue Watching entry exactly where it was left. */
fun resumeEntry(activity: Activity, e: CwEntry) {
    if (e.item.type == "replay") {
        // A recording: free the line first (one connection), then reopen it at the saved place.
        val saved = tv.onnowtv.livetv.data.ReplayResume.get(activity, e.cwId.removePrefix("replay:")) ?: return
        TrailerPlayer.halt(); HomePip.close(); tv.onnowtv.livetv.LivePreviewSession.release()
        activity.startActivity(tv.onnowtv.livetv.data.ReplayResume.intent(activity, saved))
        return
    }
    val title = if (e.item.type == "series" && e.label.isNotBlank()) "${e.item.name} · ${e.label}" else e.item.name
    launchPlayer(activity, e.item, title, e.url, e.cwId, e.positionMs)
}

/** Title page — same structure as Detail.jsx: backdrop, Back/Home, title, meta, synopsis, CTA row, seasons + episodes. */
@Composable
fun DetailScreen(activity: Activity, initial: Item, onBack: () -> Unit, onHome: () -> Unit, onActor: (CastMember) -> Unit = {}) {
    val ctx = LocalContext.current
    // A Coming Soon / notify title that the service now carries opens as the real, playable title.
    val external = initial.id.startsWith("tmdb:")
    if (external) { val real = remember(initial.id) { Match.find(initial) }; if (real != null) { DetailScreen(activity, real, onBack, onHome, onActor); return } }
    var notify by remember { mutableStateOf(NotifyList.has(ctx, initial.id)) }
    val scope = rememberCoroutineScope()
    var meta by remember { mutableStateOf(initial) }
    var episodes by remember { mutableStateOf<List<Episode>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var ready by remember { mutableStateOf(false) }
    var season by remember { mutableStateOf(1) }
    // Vesper: seasons are pills; the episode list only opens once a season is clicked (clicking it again closes it and brings the cast back).
    var shown by remember { mutableStateOf(false) }
    var inList by remember { mutableStateOf(Library.has(ctx, initial.id)) }
    var trailerBusy by remember { mutableStateOf(false) }
    val primary = remember { FocusRequester() }
    val isSeries = initial.type == "series"

    LaunchedEffect(initial.id) {
        if (external) {
            if (meta.trailerYt == null) withContext(Dispatchers.IO) { runCatching { RebornApi.trailerKey(initial.type, initial.tmdbId ?: 0L) }.getOrNull() }?.let { meta = meta.copy(trailerYt = it) }
            ready = true; return@LaunchedEffect
        }
        runCatching { if (isSeries) Gorilla.seriesInfo(ctx, initial).also { (m, eps) -> meta = m; episodes = eps; season = eps.firstOrNull { it.season > 0 }?.season ?: 1 } else meta = Gorilla.movieInfo(ctx, initial) }
            .onFailure { error = it.message }
        ready = true
    }
    FocusOnShow(primary, Unit)
    LaunchedEffect(initial.id) { TrailerPlayer.stop() }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    BackHandler(onBack = onBack)
    BackHandler(enabled = shown) { shown = false }

    fun playMovie() { val url = meta.playUrl ?: run { error = "This title has no playable file yet"; return }; launchPlayer(activity, meta, meta.name, url, meta.id) }
    fun playEpisode(e: Episode) { val url = e.url ?: run { error = "This episode has no playable file yet"; return }; launchPlayer(activity, meta, "${meta.name} · S${e.season} E${e.episode} · ${e.title}", url, e.id) }

    fun playTrailer() {
        if (trailerBusy) return
        trailerBusy = true
        scope.launch {
            val key = meta.trailerYt
            val ts = key?.let { k -> withContext(Dispatchers.IO) { runCatching { YouTubeTrailerExtractor.extract(k) ?: YouTubeTrailerExtractor.extractMuxed(k) }.getOrNull() } }
            trailerBusy = false
            if (ts == null) error = "No trailer available" else { TrailerPlayer.halt(); HomePip.mute(true); WebAppInterface(activity).playTrailerFullscreen(ts.videoUrl, ts.audioUrl ?: "", "${meta.name} — Trailer") }
        }
    }

    Box(Modifier.fillMaxSize().background(Bg0)) {
        val wideArt = rememberWideArt(meta)
        AsyncImage(wideArt, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.85f)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Bg0.copy(alpha = 0.45f), 0.3f to Bg0.copy(alpha = 0.2f), 0.7f to Bg0.copy(alpha = 0.35f), 1f to Bg0.copy(alpha = 0.65f))))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Bg0.copy(alpha = 0.92f), 0.3f to Bg0.copy(alpha = 0.7f), 0.55f to Bg0.copy(alpha = 0.2f), 0.75f to Color.Transparent)))

        val compact = isCompact()
        val pad = if (compact) 28.dp else 80.dp
        val seasons = episodes.map { it.season }.distinct().sorted()
        // Vesper sizes: title clamp(44, 4.6vw, 72), buttons clamp(50, 4vw, 60); movies keep the cast lane pinned to the bottom of the screen.
        val cfg = androidx.compose.ui.platform.LocalConfiguration.current
        val titleSp = (cfg.screenWidthDp * 0.046f).coerceIn(44f, 72f)
        val btnH = (cfg.screenWidthDp * 0.04f).dp.coerceIn(50.dp, 60.dp)
        val pinned = !compact && !(isSeries && shown)
        val progress = remember(shown) { ContinueWatching.entries(ctx).associate { it.cwId to it.fraction } }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = pad, end = pad, top = if (compact) 20.dp else 40.dp, bottom = if (pinned) 0.dp else 60.dp)) {
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
                    Eyebrow(listOfNotNull(if (external) "Coming soon" else if (isSeries) "Series" else "Movie", meta.category).joinToString(" · "))
                    Spacer(Modifier.height(8.dp))
                    Text(meta.name, color = Txt, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = if (compact) 32.sp else titleSp.sp, lineHeight = if (compact) 34.sp else (titleSp * 1.05f).sp, letterSpacing = (-titleSp * 0.035f).sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(12.dp))
                    MetaLine(meta, if (compact) 14.sp else 18.sp)
                    Spacer(Modifier.height(12.dp))
                    Text(meta.description.orEmpty(), color = Txt2, fontFamily = Geist, fontSize = if (compact) 13.sp else 16.sp, lineHeight = if (compact) 18.sp else 25.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 560.dp))
                    Spacer(Modifier.height(if (compact) 8.dp else 18.dp))
                    Row(Modifier.offset(x = (-22).dp, y = (-10).dp).horizontalScroll(rememberScrollState()).padding(vertical = 18.dp, horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        val h = if (compact) 46.dp else btnH
                        if (external) Pill(if (notify) "We'll notify you" else "Notify me in HD", icon = if (notify) Lucide.BellRing else Lucide.Bell, primary = !notify, height = h, fontSize = 16.sp, requester = primary) { notify = NotifyList.toggle(ctx, meta) }
                        else if (!isSeries) Pill(if (!ready) "Loading…" else "Play movie", icon = Lucide.Play, primary = true, height = h, fontSize = 16.sp, requester = primary, enabled = ready && meta.playUrl != null) { playMovie() }
                        Pill(if (trailerBusy) "Loading…" else if (isSeries) "Play Trailer" else "Trailer", icon = Lucide.Clapperboard, height = h, fontSize = 15.sp, requester = if (isSeries && !external) primary else null) { playTrailer() }
                        if (!external) Pill(if (inList) "In My List" else "My List", icon = if (inList) Lucide.Check else Lucide.Plus, height = h, fontSize = 15.sp) { inList = Library.toggle(ctx, meta) }
                        if (!external && !Match.isHd(meta)) Pill(if (notify) "We'll notify you" else "Notify me in HD", icon = if (notify) Lucide.BellRing else Lucide.Bell, height = h, fontSize = 15.sp) { notify = NotifyList.toggle(ctx, meta) }
                        // Vesper: the seasons start on the same line as the buttons.
                        if (isSeries && episodes.isNotEmpty()) {
                            Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(28.dp).background(Color.White.copy(alpha = 0.14f)))
                            seasons.forEach { s ->
                                val active = s == season && shown
                                Focusable(onClick = { if (shown && s == season) shown = false else { season = s; shown = true } }) { f ->
                                    Box(Modifier.height(40.dp).focusScale(f, 1.08f).pillFocus(f).clip(CircleShape).background(if (active) Blue else if (f) Color.White.copy(alpha = 0.12f) else Color.White.copy(alpha = 0.04f))
                                        .border(if (f) 1.5.dp else 1.dp, if (f) BlueBright else if (active) Color.Transparent else Color.White.copy(alpha = 0.08f), CircleShape).padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                                        Text(if (s == 0) "Specials" else "Season $s", color = if (active) Bg0 else if (f) Txt else Txt2, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    }
                                }
                            }
                        }
                    }
                    if (error != null) { Spacer(Modifier.height(12.dp)); Text(error!!, color = Color(0xFFFF8A8A), fontFamily = Geist, fontSize = 13.sp) }
                    if (external) { Spacer(Modifier.height(12.dp)); Text(if (notify) "On your notify list — a pop-up appears in the top-right corner the moment this arrives on your service in HD." else "Not on your service yet. Press Notify me and we'll tell you the moment it arrives in HD.", color = Txt2, fontFamily = Geist, fontSize = 13.sp, lineHeight = 19.sp) }
                }
            }
            if (!pinned && !(isSeries && shown)) item { Spacer(Modifier.height(26.dp)); CastRow(meta, onActor = onActor) }
            if (isSeries && episodes.isNotEmpty()) {
                if (shown) item { Mono("Season $season · ${episodes.count { it.season == season }} episodes", 10.sp, Txt3, 0.24f, Modifier.padding(top = 20.dp, bottom = 12.dp)) }
                if (shown) items(episodes.filter { it.season == season }, key = { it.id }) { e ->
                    Box(Modifier.fillMaxWidth().padding(bottom = 14.dp)) { EpisodeRow(e, wideArt, progress[e.id] ?: 0f) { playEpisode(e) } }
                }
            }
        }
        if (pinned) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Brush.verticalGradient(0f to Color.Transparent, 0.7f to Bg0.copy(alpha = 0.7f), 1f to Bg0.copy(alpha = 0.9f))).padding(top = 40.dp, bottom = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.05f).dp - 14.dp)) {
            TitleLanes(meta, 80.dp, withSimilar = !external, onActor = onActor, onUp = { if (runCatching { primary.requestFocus() }.isFailure) focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Up) }) { TitleActions.details(it) }
        }
    }
}

/** SeriesEpisodes.jsx EpisodeCard — 16:9 still on the left (S·E badge, play disc, resume bar), title / date / overview on the right. */
@Composable
private fun EpisodeRow(e: Episode, fallbackArt: String?, fraction: Float, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp); val ts = RoundedCornerShape(12.dp)
    val thumbW = (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp * 0.18f).dp.coerceIn(180.dp, 280.dp)
    Focusable(onClick = onClick) { f ->
        Row(Modifier.fillMaxWidth().tileFocus(f, 16.dp).clip(shape).background(Color(0xFF111827).copy(alpha = if (f) 0.78f else 0.42f))
            .border(1.dp, if (f) Blue.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.05f), shape).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(thumbW).aspectRatio(16f / 9f).clip(ts).background(Color.Black.copy(alpha = 0.5f)).border(1.dp, Color.White.copy(alpha = 0.05f), ts)) {
                AsyncImage(e.thumb ?: fallbackArt, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = if (e.thumb == null) 0.55f else 1f)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0xB306080F))))
                Box(Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xC706080F)).padding(horizontal = 8.dp, vertical = 4.dp)) { Mono("S${e.season} · E${e.episode}", 9.sp, Txt, 0.18f) }
                if (fraction >= 0.95f) Box(Modifier.align(Alignment.TopEnd).padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xF022C55E)).padding(horizontal = 8.dp, vertical = 4.dp)) { Mono("Watched", 9.sp, Color(0xFF06260F), 0.14f) }
                else if (fraction > 0.02f) Box(Modifier.align(Alignment.BottomCenter).padding(start = 8.dp, end = 8.dp, bottom = 4.dp).fillMaxWidth().height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.16f))) { Box(Modifier.fillMaxHeight().fillMaxWidth(fraction).background(Blue)) }
                if (f) Box(Modifier.align(Alignment.Center).size(52.dp).clip(CircleShape).background(Blue.copy(alpha = 0.92f)), contentAlignment = Alignment.Center) { Icon(Lucide.Play, null, Modifier.size(22.dp), tint = Bg0) }
            }
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Text(e.title, color = Txt, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 24.sp, letterSpacing = (-0.3).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.padding(top = 5.dp, bottom = 9.dp)) {
                    Text("Episode ${e.episode}", color = Blue, fontFamily = Geist, fontSize = 13.sp)
                    e.released?.take(10)?.takeIf { it.isNotBlank() }?.let { Text("   ·   ${Tmdb.niceDate(it)}", color = Txt3, fontFamily = Geist, fontSize = 13.sp) }
                }
                Text(e.overview?.trim().orEmpty().ifBlank { "No description for this episode." }, color = Txt2, fontFamily = Geist, fontSize = 14.sp, lineHeight = 20.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
