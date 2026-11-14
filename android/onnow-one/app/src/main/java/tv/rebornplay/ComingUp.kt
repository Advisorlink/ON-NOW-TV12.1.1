package tv.rebornplay

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import com.composables.icons.lucide.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/** Home "Coming up": next episodes of the shows saved in My Library (same source as the Library calendar). */
object ComingUp {
    val eps = mutableStateOf<List<CalEpisode>>(emptyList())
    var byImdb: Map<String, Item> = emptyMap()
    private var sig = ""; private var at = 0L
    suspend fun refresh(ctx: android.content.Context) {
        val series = Library.items(ctx).filter { it.type == "series" }
        val s = (Profiles.active(ctx)?.id ?: "") + "|" + series.joinToString(",") { it.id }
        if (s == sig && System.currentTimeMillis() - at < 30 * 60_000L) return
        if (series.isEmpty()) { eps.value = emptyList(); sig = s; at = System.currentTimeMillis(); return }
        runCatching { Tmdb.calendar(ctx, series) }.onSuccess { (e, m) ->
            val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            byImdb = m; eps.value = e.filter { it.date >= today }.take(24); sig = s; at = System.currentTimeMillis()
        }
    }
    /** "Today" / "Tomorrow" / "In 4 days" + parsed date. */
    fun whenLabel(iso: String): Pair<String, java.util.Calendar?> {
        val d = runCatching { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(iso) }.getOrNull() ?: return "Coming up" to null
        val day = java.util.Calendar.getInstance().apply { time = d }
        val t = java.util.Calendar.getInstance().apply { set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }
        val n = Math.round((day.timeInMillis - t.timeInMillis) / 86_400_000.0).toInt()
        return (when { n <= 0 -> "Today"; n == 1 -> "Tomorrow"; n < 7 -> "In $n days"; n < 14 -> "Next week"; else -> "In ${n / 7} weeks" }) to day
    }
}

@Composable
fun ComingUpShelf(eps: List<CalEpisode>, row: Int, rowNav: RowNav?, tileH: Dp, onOpen: (Item) -> Unit) {
    if (eps.isEmpty()) return
    val inset = safeInset()
    if (rowNav != null) DisposableEffect(row) { onDispose { rowNav.forget(row) } }
    val h = (tileH * 0.84f).coerceAtMost(190.dp)
    val w = h * 2.5f
    Column {
        SectionHeader("From your calendar", "Coming Up", "${eps.size} new episodes on the way", Modifier.padding(horizontal = inset))
        AnchoredRow(64.dp + inset + w + 22.dp) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(22.dp), contentPadding = PaddingValues(start = 64.dp + inset, end = 64.dp + inset)) {
                itemsIndexed(eps, key = { i, e -> "${e.imdbId}-${e.season}-${e.episode}-$i" }) { i, e -> ComingUpCard(e, w, h, i == 0 && row >= 0, row, rowNav) { ComingUp.byImdb[e.imdbId]?.let(onOpen) } }
            }
        }
    }
}

@Composable
private fun ComingUpCard(e: CalEpisode, w: Dp, h: Dp, first: Boolean, row: Int, rowNav: RowNav?, onClick: () -> Unit) {
    val req = remember { FocusRequester() }
    if (first) rowNav?.register(row, req)
    var top by remember { mutableStateOf(false) }
    val accent = Color(e.colour)
    val (label, day) = remember(e.date) { ComingUp.whenLabel(e.date) }
    val art = remember(e) { (e.backdrop ?: e.still)?.replace("/t/p/w300/", "/t/p/w780/") ?: e.poster }
    Focusable(Modifier.zIndex(if (top) 1f else 0f), requester = req, onFocus = { top = it; if (it) rowNav?.focused(row, req) }, onClick = onClick) { f ->
        Box(Modifier.width(w).height(h).focusScale(f, 1.04f).tileFocus(f, 12.dp).clip(TileShape).background(Bg2).border(1.dp, Color.White.copy(alpha = 0.06f), TileShape)) {
            if (art != null) AsyncImage(art, e.showName, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Bg0.copy(alpha = 0.96f), 0.34f to Bg0.copy(alpha = 0.78f), 0.72f to Color.Transparent)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Bg0.copy(alpha = 0.85f))))
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(4.dp).fillMaxHeight().background(accent))
                // Date block
                Column(Modifier.fillMaxHeight().width(h * 0.62f).padding(start = 14.dp), verticalArrangement = Arrangement.Center) {
                    Mono(day?.let { java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(it.time) } ?: "", 10.sp, accent, 0.2f)
                    Text(day?.get(java.util.Calendar.DAY_OF_MONTH)?.toString() ?: "–", color = Txt, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = (h.value * 0.3f).sp, lineHeight = (h.value * 0.3f).sp, letterSpacing = (-1.5).sp)
                    Mono(day?.let { java.text.SimpleDateFormat("MMM", java.util.Locale.getDefault()).format(it.time) } ?: "", 10.sp, Txt2, 0.2f)
                }
                Box(Modifier.padding(vertical = 18.dp).width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.12f)))
                Column(Modifier.weight(1f).fillMaxHeight().padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.Bottom) {
                    Text(e.showName, color = Txt, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text("Season ${e.season} · Episode ${e.episode}" + if (e.name.isNotBlank()) "  —  ${e.name}" else "", color = Txt2, fontFamily = Geist, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (e.network != null) Mono(e.network, 8.5.sp, Txt3, 0.14f, Modifier.padding(top = 6.dp))
                }
            }
            Row(Modifier.align(Alignment.TopEnd).padding(10.dp).height(24.dp).clip(CircleShape).background(if (label == "Today") accent else Bg0.copy(alpha = 0.82f)).border(1.dp, accent.copy(alpha = 0.7f), CircleShape).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Lucide.CalendarDays, null, Modifier.size(11.dp), tint = if (label == "Today") Bg0 else accent); Spacer(Modifier.width(6.dp))
                Text(label, color = if (label == "Today") Bg0 else Txt, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 10.sp)
            }
        }
    }
}
