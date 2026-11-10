package tv.rebornplay

import com.composables.icons.lucide.*

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.onnowtv.livetv.LivePreviewSession
import tv.onnowtv.livetv.data.Category
import tv.onnowtv.livetv.data.Channel
import tv.onnowtv.livetv.data.EpgCache
import tv.onnowtv.livetv.data.ParentalLock
import tv.onnowtv.livetv.data.Programme
import tv.onnowtv.livetv.data.ReminderStore
import tv.onnowtv.livetv.data.Settings
import tv.vesper.app.LiveTvEntry
import java.text.SimpleDateFormat
import java.util.Date

private val CatW = 230.dp
private val GuideW = 320.dp
private val CatWCompact = 150.dp
private val RowShape = RoundedCornerShape(14.dp)

/** Live TV guide in the Reborn look — hero + preview window, categories | channels | programme guide. */
@Composable
fun LiveTvScreen(activity: Activity, onRefresh: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val bundle by LiveStore.bundle
    val favs by LiveStore.favourites
    val fmt = remember { Settings.timeFmt(ctx) }
    var catId by remember { mutableStateOf(Settings.prefs(ctx).getString("reborn_last_cat", LiveStore.ALL) ?: LiveStore.ALL) }
    var focusedCat by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var gate by remember { mutableStateOf<Category?>(null) }
    val unlocked = remember { mutableStateListOf<String>() }
    var focused by remember { mutableStateOf<Channel?>(null) }
    var preview by remember { mutableStateOf<Channel?>(null) }
    var playerView by remember { mutableStateOf<PlayerView?>(null) }
    val firstRow = remember { FocusRequester() }
    var tick by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); tick = System.currentTimeMillis() } }

    val b = bundle ?: run {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Eyebrow("Live TV"); Spacer(Modifier.height(10.dp))
                Text("Your channels aren't loaded yet", color = Txt, fontFamily = V2Sans, fontWeight = FontWeight.Black, fontSize = 26.sp)
                Spacer(Modifier.height(18.dp)); Pill("Load channels & guide", primary = true, onClick = onRefresh)
            }
        }
        return
    }
    val cats = remember(b, tick) { LiveStore.categories(ctx) }
    val locked = remember(cats) { ParentalLock.lockedIds(ctx) }
    val channels = remember(b, catId, favs, query) {
        val base = LiveStore.channelsIn(catId)
        if (query.isBlank()) base else base.filter { it.name.contains(query, ignoreCase = true) }
    }
    // Rows highlight instantly; the hero + guide (art, programme info) follow once the D-pad settles — keeps scrolling at V2 speed.
    var settled by remember { mutableStateOf<Channel?>(null) }
    LaunchedEffect(focused?.id) { if (focused == null) settled = null else { delay(120); settled = focused } }
    val current = settled ?: focused ?: channels.firstOrNull()

    // Nothing streams until the user asks: OK = preview in the window, OK again = full screen.
    LaunchedEffect(current?.id) { current?.let { LiveStore.programmes(ctx, it) } }
    fun openChannel(ch: Channel, list: List<Channel>) {
        if (preview?.id == ch.id && LivePreviewSession.isAlive()) LiveStore.openFullscreen(activity, ch, list) else { focused = ch; preview = ch }
    }
    LaunchedEffect(preview?.id, playerView) {
        val ch = preview ?: return@LaunchedEffect
        val v = playerView ?: return@LaunchedEffect
        LivePreviewSession.setChannel(ctx, ch)
        LivePreviewSession.attachTo(v)
        LiveStore.lastChannelId = ch.id
    }
    // Re-bind after full screen / background; nothing keeps playing once the tab is left.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, playerView) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> playerView?.let { v ->
                    LivePreviewSession.currentChannel?.let { ch -> if (ch.id != focused?.id) focused = ch; preview = ch }
                    if (LivePreviewSession.isAlive()) { LivePreviewSession.attachTo(v); LivePreviewSession.getOrCreate(ctx).playWhenReady = true }
                    else preview?.let { LivePreviewSession.setChannel(ctx, it); LivePreviewSession.attachTo(v) }
                }
                Lifecycle.Event.ON_STOP -> playerView?.let { if (LivePreviewSession.isAlive()) LivePreviewSession.detachWithoutRelease(it) }
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs); LivePreviewSession.release() }
    }
    if (channels.isNotEmpty()) FocusOnShow(firstRow, catId)

    fun selectCategory(c: Category?) {
        val id = c?.id ?: LiveStore.ALL
        if (c != null && id in locked && id !in unlocked) { gate = c; return }
        catId = id; focused = null; Settings.prefs(ctx).edit().putString("reborn_last_cat", id).apply()
    }

    val compact = isCompact()
    Column(Modifier.fillMaxSize().background(LvBg)) {
        LiveHero(current, preview != null && preview?.id == current?.id, favs, fmt, tick, onFav = { current?.let { LiveStore.toggleFavourite(ctx, it.id) } }, onRefresh = onRefresh,
            onSettings = { LiveTvEntry.open(activity, settings = true) },
            onSportsHub = { activity.startActivity(android.content.Intent(activity, tv.onnowtv.livetv.SportsHubActivity::class.java)) }) { v -> playerView = v }
        val inset = safeInset()
        Row(Modifier.weight(1f).fillMaxWidth().padding(start = (if (compact) 16.dp else 32.dp) + inset, end = (if (compact) 16.dp else 32.dp) + inset, bottom = 16.dp + inset), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            CategoryColumn(cats, catId, locked, unlocked, favCount = favs.size, total = b.channels.size, onFocus = { focusedCat = it }, onSelect = { selectCategory(it) })
            Column(Modifier.weight(1f)) {
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    LvMono(when (catId) { LiveStore.ALL -> "All channels"; LiveStore.FAVS -> "Favourites"; else -> cats.firstOrNull { it.id == catId }?.name ?: "Channels" }, 11.sp, LvAccent, 0.24f)
                    Spacer(Modifier.width(12.dp)); LvMono("${channels.size} channels", 10.sp, LvDim)
                    Spacer(Modifier.weight(1f))
                    SearchBox(query, searchOpen, onOpen = { searchOpen = true }, onClose = { query = ""; searchOpen = false }) { query = it }
                }
                ChannelColumn(channels, favs, current?.id, firstRow, fmt, tick, onFocus = { focused = it }, onOpen = { openChannel(it, channels) })
            }
            if (!isCompact()) GuideColumn(current, fmt, tick)
        }
    }
    gate?.let { c -> PinGate(c.name, onClose = { gate = null }) { unlocked += c.id; gate = null; selectCategory(c) } }
}

// V2 Live TV type: system sans-serif (Black for titles, like `sans-serif-black`) and monospace labels — same as the V2 guide.
private val V2Sans = androidx.compose.ui.text.font.FontFamily.SansSerif
private val V2MonoF = androidx.compose.ui.text.font.FontFamily.Monospace
@Composable
private fun LvMono(text: String, size: androidx.compose.ui.unit.TextUnit = 11.sp, color: Color = LvDim, tracking: Float = 0.22f, modifier: Modifier = Modifier, upper: Boolean = true) =
    Text(if (upper) text.uppercase() else text, modifier, color = color, fontFamily = V2MonoF, fontWeight = FontWeight.Medium, fontSize = size, letterSpacing = (size.value * tracking).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)

// V2 Live TV palette (livetv colors.xml) so the Reborn guide reads exactly like the V2 app.
private val LvBg = Color(0xFF0A0F1A); private val LvCard = Color(0xFF141C2A); private val LvFocus = Color(0xFF1A2438)
private val LvDim = Color(0xFF9DA5B5); private val LvDimmer = Color(0xFF7D8493); private val LvRed = Color(0xFFFF2535)
private val LvAccent = Color(0xFF5DC8FF); private val LvChip = Color(0xFF3B82F6); private val LvBorder = Color(0x1AFFFFFF)

/** V2 body text: sans-serif-medium (programme lines, times). */
@Composable
private fun LvText(text: String, size: androidx.compose.ui.unit.TextUnit = 11.sp, color: Color = LvDim, modifier: Modifier = Modifier, weight: FontWeight = FontWeight.Medium, maxLines: Int = 1) =
    Text(text, modifier, color = color, fontFamily = V2Sans, fontWeight = weight, fontSize = size, maxLines = maxLines, overflow = TextOverflow.Ellipsis)

/** V2 `now_pill_bg`: solid red, 3dp radius, 8sp bold white — NOW while on air, LIVE for live events. */
@Composable
private fun LiveBadge(live: Boolean) {
    Box(Modifier.height(14.dp).clip(RoundedCornerShape(3.dp)).background(LvRed).padding(horizontal = 7.dp), contentAlignment = Alignment.Center) {
        Text(if (live) "LIVE" else "NOW", color = Color.White, fontFamily = V2Sans, fontWeight = FontWeight.Bold, fontSize = 8.sp, lineHeight = 8.sp, letterSpacing = 1.sp)
    }
}

@Composable
private fun LiveHero(ch: Channel?, previewing: Boolean, favs: Set<String>, fmt: SimpleDateFormat, tick: Long, onFav: () -> Unit, onRefresh: () -> Unit, onSettings: () -> Unit, onSportsHub: () -> Unit, onPlayerView: (PlayerView) -> Unit) {
    val ctx = LocalContext.current
    val compact = isCompact()
    val inset = safeInset()
    val (now, next) = remember(ch?.id, LiveStore.epg[ch?.let { LiveStore.key(it) } ?: ""], tick) { ch?.let { LiveStore.nowNext(it) } ?: (null to null) }
    // TMDB programme art (debounced, cached by title) — same as the web guide's hero backdrop.
    var art by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(now?.title) {
        val t = now?.title?.takeIf { it.isNotBlank() } ?: run { art = null; return@LaunchedEffect }
        art = LiveStore.art[t]
        if (art == null) { delay(350); val a = LiveStore.fetchArt(t); if (now?.title == t) art = a }
    }
    val heroH = if (compact) 210.dp else 300.dp
    Box(Modifier.fillMaxWidth().height(heroH)) {
        if (art != null) {
            AsyncImage(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to LvBg, 0.4f to LvBg.copy(alpha = 0.8f), 1f to LvBg.copy(alpha = 0.15f))))
        } else if (ch?.logoUrl != null) {
            AsyncImage(ch.logoUrl, null, Modifier.align(Alignment.CenterEnd).padding(end = 120.dp).size(220.dp), contentScale = ContentScale.Fit, alpha = 0.06f)
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to LvBg.copy(alpha = 0.5f), 0.3f to Color.Transparent, 0.7f to Color.Transparent, 1f to LvBg)))
        Row(Modifier.align(Alignment.BottomStart).padding(start = 32.dp + inset, bottom = 16.dp, end = 32.dp + inset).fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            // Preview window — LEFT, like the web guide.
            Box(Modifier.width(if (compact) 200.dp else 320.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Color.Black).border(1.dp, Line, RoundedCornerShape(12.dp))) {
                AndroidView(factory = { c -> PlayerView(c).apply { useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; setShutterBackgroundColor(android.graphics.Color.BLACK); onPlayerView(this) } }, modifier = Modifier.fillMaxSize())
                if (!previewing) {
                    // Cover art of what's on now (same TMDB lookup as the hero) until the user presses OK to preview.
                    if (art != null) AsyncImage(art, now?.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else if (ch?.logoUrl != null) AsyncImage(ch.logoUrl, null, Modifier.align(Alignment.Center).fillMaxSize(0.45f), contentScale = ContentScale.Fit, alpha = 0.5f)
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color(0xFF0A0F1A).copy(alpha = 0.9f))))
                    now?.let { Box(Modifier.align(Alignment.TopStart).padding(8.dp)) { LiveBadge(it.live) } }
                    LvMono("OK · Preview", 8.sp, Color.White.copy(alpha = 0.8f), 0.18f, Modifier.align(Alignment.BottomStart).padding(8.dp))
                }
                if (previewing) Box(Modifier.align(Alignment.TopStart).padding(8.dp)) { LiveBadge(now?.live == true) }
                LvMono(if (previewing) "OK again · Full screen" else "", 8.sp, Txt2, 0.18f, Modifier.align(Alignment.BottomEnd).padding(8.dp))
            }
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                LvMono("Live TV${ch?.lcn?.let { " · CH $it" } ?: ""}", 11.sp, LvAccent, 0.32f)
                Spacer(Modifier.height(6.dp))
                Text(ch?.let { Settings.cleanName(ctx, it.name) } ?: "Live TV", color = Color.White, fontFamily = V2Sans, fontWeight = FontWeight.Black, fontSize = if (compact) 24.sp else 38.sp, letterSpacing = (-1.2).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                if (now != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LiveBadge(now.live); Spacer(Modifier.width(8.dp))
                        LvText(fmt.format(Date(now.startMs)), 13.sp, LvAccent); Spacer(Modifier.width(12.dp))
                        Text(now.title, color = Txt, fontFamily = V2Sans, fontWeight = FontWeight.Black, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (!compact && !now.description.isNullOrBlank()) { Spacer(Modifier.height(6.dp)); Text(now.description!!, color = Txt2, fontFamily = V2Sans, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(0.8f)) }
                    Spacer(Modifier.height(10.dp))
                    ProgressBar(now, Modifier.width(if (compact) 220.dp else 380.dp), 3.dp)
                    if (next != null) { Spacer(Modifier.height(8.dp)); LvText("Up next  ·  ${fmt.format(Date(next.startMs))}  ·  ${next.title}", 12.sp, LvDim) }
                } else LvText("No programme information", 12.sp, LvDimmer)
            }
        }
        Row(Modifier.align(Alignment.TopEnd).padding(top = if (compact) 58.dp else 70.dp, end = 32.dp + inset), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val fav = ch != null && ch.id in favs
            HeroPill(Lucide.Trophy, "Sports Hub", onSportsHub)
            HeroIcon(if (fav) Lucide.Star else Lucide.Star, if (fav) "Unfavourite" else "Favourite", if (fav) Amber else null, onFav)
            HeroIcon(Lucide.RefreshCw, "Refresh", null, onRefresh)
            HeroIcon(Lucide.Settings, "Live TV settings", null, onSettings)
        }
    }
}

@Composable
private fun HeroPill(icon: ImageVector, label: String, onClick: () -> Unit) {
    Focusable(onClick = onClick) { f ->
        Row(Modifier.height(36.dp).focusScale(f, 1.04f).pillFocus(f).clip(CircleShape).background(LvCard.copy(alpha = 0.9f)).border(1.dp, LvBorder, CircleShape).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, label, Modifier.size(15.dp), tint = LvAccent); Spacer(Modifier.width(8.dp))
            LvMono(label, 10.sp, Txt, 0.16f)
        }
    }
}

@Composable
private fun HeroIcon(icon: ImageVector, label: String, tint: Color?, onClick: () -> Unit) {
    Focusable(onClick = onClick) { f ->
        Box(Modifier.size(36.dp).focusScale(f, 1.04f).pillFocus(f).clip(CircleShape).background(LvCard.copy(alpha = 0.9f)).border(1.dp, LvBorder, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, label, Modifier.size(16.dp), tint = tint ?: LvDim)
        }
    }
}

@Composable
private fun ProgressBar(p: Programme, modifier: Modifier, height: androidx.compose.ui.unit.Dp) {
    val now = System.currentTimeMillis()
    val frac = ((now - p.startMs).toFloat() / (p.stopMs - p.startMs).coerceAtLeast(1)).coerceIn(0f, 1f)
    Box(modifier.height(height).clip(CircleShape).background(Color.White.copy(alpha = 0.10f))) { Box(Modifier.fillMaxHeight().fillMaxWidth(frac).clip(CircleShape).background(if (p.live) LvRed else LvAccent)) }
}

/** Collapsed to a plain "Search" button so passing over it with the D-pad never pops the keyboard; OK expands it and focuses the field. */
@Composable
private fun SearchBox(q: String, open: Boolean, onOpen: () -> Unit, onClose: () -> Unit, onChange: (String) -> Unit) {
    if (!open) {
        Focusable(onClick = onOpen) { f ->
            Row(Modifier.height(36.dp).focusScale(f, 1.04f).pillFocus(f).clip(CircleShape).background(LvCard.copy(alpha = 0.9f)).border(1.dp, LvBorder, CircleShape).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Lucide.Search, null, Modifier.size(14.dp), tint = LvDim); Spacer(Modifier.width(8.dp))
                LvMono("Search", 10.sp, LvDim, 0.12f)
            }
        }
        return
    }
    val req = remember { FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { req.requestFocus() }; keyboard?.show() }
    Row(Modifier.width(300.dp).height(36.dp).clip(CircleShape).background(Bg1.copy(alpha = 0.7f)).border(1.dp, Blue, CircleShape).padding(start = 12.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Lucide.Search, null, Modifier.size(14.dp), tint = Blue); Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (q.isEmpty()) LvMono("Search channels", 10.sp, Txt3, 0.12f, upper = false)
            androidx.compose.foundation.text.BasicTextField(q, onChange, Modifier.fillMaxWidth().focusRequester(req), singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = Txt, fontFamily = V2Sans, fontSize = 13.sp), cursorBrush = androidx.compose.ui.graphics.SolidColor(Blue))
        }
        Focusable(onClick = onClose) { f ->
            Box(Modifier.size(26.dp).clip(CircleShape).background(if (f) BlueBright else Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                Icon(Lucide.X, null, Modifier.size(14.dp), tint = if (f) Bg0 else Txt2)
            }
        }
    }
}

@Composable
private fun CategoryColumn(cats: List<Category>, selected: String, locked: Set<String>, unlocked: List<String>, favCount: Int, total: Int, onFocus: (String?) -> Unit, onSelect: (Category?) -> Unit) {
    Column(Modifier.width(if (isCompact()) CatWCompact else CatW)) {
        LvMono("Groups", 10.sp, LvDim, 0.22f, modifier = Modifier.padding(start = 14.dp, bottom = 10.dp, top = 8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { CategoryRow("Favourites", favCount, selected == LiveStore.FAVS, false, onFocus = { onFocus(LiveStore.FAVS) }) { onSelect(Category(LiveStore.FAVS, "Favourites")) } }
            item { CategoryRow("All channels", total, selected == LiveStore.ALL, false, onFocus = { onFocus(LiveStore.ALL) }) { onSelect(null) } }
            items(cats, key = { it.id }) { c -> CategoryRow(c.name, c.channelCount, selected == c.id, c.id in locked && c.id !in unlocked, onFocus = { onFocus(c.id) }) { onSelect(c) } }
        }
    }
}

@Composable
private fun CategoryRow(name: String, count: Int, active: Boolean, isLocked: Boolean, onFocus: () -> Unit, onClick: () -> Unit) {
    Focusable(Modifier.fillMaxWidth(), onClick = onClick, onFocus = { if (it) onFocus() }) { f ->
        Row(Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(10.dp))
            .background(if (f) LvFocus else if (active) LvCard else LvCard.copy(alpha = 0.45f))
            .border(1.dp, if (f) LvAccent else Color.Transparent, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (active) { Box(Modifier.width(3.dp).height(16.dp).clip(CircleShape).background(LvAccent)); Spacer(Modifier.width(8.dp)) }
            Text(name, color = if (f || active) Color.White else LvDim, fontFamily = V2Sans, fontWeight = FontWeight.Black, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (isLocked) Icon(Lucide.Lock, "Locked", Modifier.size(12.dp), tint = Amber) else if (count > 0) LvText("$count", 11.sp, if (f) LvAccent else LvDimmer)
        }
    }
}

@Composable
private fun ChannelColumn(channels: List<Channel>, favs: Set<String>, currentId: String?, first: FocusRequester, fmt: SimpleDateFormat, tick: Long, onFocus: (Channel) -> Unit, onOpen: (Channel) -> Unit) {
    val ctx = LocalContext.current
    if (channels.isEmpty()) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LvMono("No channels in this group", 11.sp, Txt3) }; return }
    val state: LazyListState = rememberLazyListState()
    LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 20.dp)) {
        itemsIndexed(channels, key = { _, c -> c.id }) { i, ch ->
            LaunchedEffect(ch.id) {
                val k = LiveStore.key(ch)
                if (LiveStore.epg[k] == null) withContext(Dispatchers.IO) { runCatching { EpgCache.loadChannel(ctx, k) }.getOrNull() }?.let { LiveStore.epg[k] = it }
            }
            ChannelRow(ch, ch.id in favs, ch.id == currentId, if (i == 0) first else null, fmt, tick, onFocus = { onFocus(ch) }) { onOpen(ch) }
        }
    }
}

@Composable
private fun ChannelRow(ch: Channel, fav: Boolean, active: Boolean, requester: FocusRequester?, fmt: SimpleDateFormat, tick: Long, onFocus: () -> Unit, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val now = remember(ch.id, LiveStore.epg[LiveStore.key(ch)], tick) { LiveStore.nowNext(ch).first }
    Focusable(Modifier.fillMaxWidth(), onClick = onClick, requester = requester, onFocus = { if (it) onFocus() }) { f ->
        Column(Modifier.fillMaxWidth().focusScale(f, 1.01f).tileFocus(f, 12.dp).clip(RowShape).background(if (f) LvFocus else LvCard)
            .border(1.dp, if (!f && active) LvAccent.copy(alpha = 0.35f) else Color.Transparent, RowShape)) {
            Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 12.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                if (f || active) Box(Modifier.width(3.dp).height(30.dp).clip(CircleShape).background(LvAccent)) else Spacer(Modifier.width(3.dp))
                Spacer(Modifier.width(10.dp))
                LvText(ch.lcn ?: "", 15.sp, if (f) LvAccent else LvDimmer, Modifier.width(40.dp), weight = FontWeight.Medium)
                Box(Modifier.width(52.dp).height(34.dp).clip(RoundedCornerShape(6.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                    if (ch.logoUrl != null) AsyncImage(ch.logoUrl, null, Modifier.fillMaxSize().padding(3.dp), contentScale = ContentScale.Fit)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(Settings.cleanName(ctx, ch.name), color = Color.White, fontFamily = V2Sans, fontWeight = FontWeight.Black, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (now != null) { LiveBadge(now.live); Spacer(Modifier.width(8.dp)) }
                        LvText(now?.let { "${fmt.format(Date(it.startMs))}  ·  ${it.title}" } ?: "No guide data", 11.sp, if (now == null) LvDimmer else LvDim)
                    }
                }
                if (fav) Icon(Lucide.Star, null, Modifier.size(13.dp), tint = Amber)
            }
            if (now != null) ProgressBar(now, Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(bottom = 8.dp), 3.dp)
        }
    }
}

private val LvGreen = Color(0xFF5BD49A)   // livetv_reminder_yellow (V2 reminder colour)

/** Programme guide (V2 item_guide_row): focusable rows, red NOW + progress on the live one, OK on a future programme sets/clears a reminder (green ring). */
@Composable
private fun GuideColumn(ch: Channel?, fmt: SimpleDateFormat, tick: Long) {
    val ctx = LocalContext.current
    val list = ch?.let { LiveStore.epg[LiveStore.key(it)] } ?: emptyList()
    val nowMs = System.currentTimeMillis() + (tick * 0)
    val dayFmt = remember { SimpleDateFormat("EEEE d MMM", java.util.Locale.UK) }
    val shown = remember(list, tick) { list.filter { it.stopMs > nowMs - 3 * 3_600_000L }.take(80) }
    val today = remember(tick) { dayFmt.format(Date()) }
    val rows = remember(shown) { buildList<Any> { var last = ""; shown.forEach { p -> val d = dayFmt.format(Date(p.startMs)); if (d != last) { last = d; add(d) }; add(p) } } }
    var reminders by remember { mutableStateOf(ReminderStore.load(ctx).keys.toSet()) }
    fun key(p: Programme) = "${ch?.epgChannelId ?: ""}:${p.startMs}"
    fun toggle(p: Programme) {
        val c = ch ?: return
        val map = ReminderStore.load(ctx); val k = key(p)
        if (map.remove(k) == null) map[k] = ReminderStore.Reminder(k, c.id, c.name, c.logoUrl, c.lcn, p.title, p.startMs, p.stopMs)
        ReminderStore.save(ctx, map); reminders = map.keys.toSet()
    }
    Column(Modifier.width(GuideW).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(LvCard.copy(alpha = 0.7f)).border(1.dp, LvBorder, RoundedCornerShape(16.dp)).padding(14.dp)) {
        LvMono("Guide", 10.sp, LvAccent, 0.24f)
        Text(ch?.let { Settings.cleanName(ctx, it.name) } ?: "—", color = Color.White, fontFamily = V2Sans, fontWeight = FontWeight.Black, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
        if (shown.isEmpty()) LvMono(if (ch == null) "" else "Loading guide…", 10.sp, Txt3)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(rows.size, key = { i -> (rows[i] as? Programme)?.startMs ?: rows[i] }) { i ->
                val row = rows[i]
                if (row is String) { LvMono(if (row == today) "Today" else row, 9.sp, Txt3, 0.2f, Modifier.padding(top = if (i == 0) 0.dp else 10.dp, bottom = 4.dp)); return@items }
                val p = row as Programme
                val live = p.isLiveAt(nowMs); val past = p.stopMs <= nowMs; val future = p.startMs > nowMs
                val set = key(p) in reminders
                Focusable(Modifier.fillMaxWidth(), onClick = { if (future) toggle(p) }) { f ->
                    val shape = RoundedCornerShape(8.dp)
                    Column(Modifier.fillMaxWidth().clip(shape).background(if (f) LvFocus else if (live) LvCard else Color.Transparent)
                        .border(if (set) 2.dp else 1.dp, if (set) LvGreen else if (f) LvAccent else if (live) LvAccent.copy(alpha = 0.35f) else Color.Transparent, shape)
                        .padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.Top) {
                            LvText(fmt.format(Date(p.startMs)), 11.sp, if (live || f) LvAccent else if (past) LvDimmer else LvDim, Modifier.width(64.dp))
                            Column(Modifier.weight(1f)) {
                                if (live) { Box(Modifier.padding(bottom = 4.dp)) { LiveBadge(p.live) } }
                                Text(p.title, color = if (live || f) Color.White else if (past) LvDimmer else LvDim, fontFamily = V2Sans, fontWeight = if (live || f) FontWeight.Bold else FontWeight.Normal, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (live) { Spacer(Modifier.height(5.dp)); ProgressBar(p, Modifier.fillMaxWidth(), 2.dp) }
                            }
                            if (set) Icon(Lucide.BellRing, "Reminder set", Modifier.size(13.dp), tint = LvGreen)
                        }
                        if (future && (f || set)) Row(Modifier.padding(top = 6.dp, start = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (set) Lucide.BellRing else Lucide.Bell, null, Modifier.size(11.dp), tint = if (set) LvGreen else LvDim); Spacer(Modifier.width(6.dp))
                            LvMono(if (set) "Reminder set · OK to clear" else "Push OK to set reminder", 8.sp, if (set) LvGreen else LvDim, 0.14f)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PinGate(groupName: String, onClose: () -> Unit, onUnlock: () -> Unit) {
    val ctx = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var err by remember { mutableStateOf(false) }
    val req = remember { FocusRequester() }
    FocusOnShow(req, Unit)
    if (!ParentalLock.hasPin(ctx)) { LaunchedEffect(Unit) { onUnlock() }; return }
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.width(420.dp).glass(RoundedCornerShape(20.dp)).background(Bg1.copy(alpha = 0.97f)).padding(26.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Lucide.Lock, null, Modifier.size(18.dp), tint = Amber); Spacer(Modifier.width(10.dp)); Eyebrow("Locked group") }
            Spacer(Modifier.height(8.dp))
            Text(groupName, color = Txt, fontFamily = V2Sans, fontWeight = FontWeight.Black, fontSize = 22.sp)
            Spacer(Modifier.height(6.dp)); Text("Enter your Live TV PIN to open this group.", color = Txt2, fontFamily = V2Sans, fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            Field(pin, { if (it.length <= 6 && it.all { c -> c.isDigit() }) { pin = it; err = false } }, "PIN", Lucide.Lock, password = true, requester = req, onDone = {
                if (ParentalLock.checkPin(ctx, pin)) onUnlock() else { err = true; pin = "" }
            })
            if (err) { Spacer(Modifier.height(8.dp)); Text("Wrong PIN — try again", color = Color(0xFFFF8A8A), fontFamily = V2Sans, fontSize = 12.sp) }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("Unlock", primary = true, height = 42.dp, fontSize = 14.sp) { if (ParentalLock.checkPin(ctx, pin)) onUnlock() else { err = true; pin = "" } }
                Pill("Cancel", height = 42.dp, fontSize = 14.sp, onClick = onClose)
            }
        }
    }
}
