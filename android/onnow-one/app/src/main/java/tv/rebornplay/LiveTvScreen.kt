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

private val CatW = 180.dp
private val GuideW = 250.dp
private val LocalK = compositionLocalOf { 1f }   // real dp → scaled dp
private val CatWCompact = 132.dp
private val RowShape = RoundedCornerShape(9.dp)
private val PanelShape = RoundedCornerShape(12.dp)

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
    // TV: lay the screen out on a fixed 1060-wide canvas so it looks the same on every box, then apply the user's Live TV size.
    val real = androidx.compose.ui.platform.LocalDensity.current
    val userScale by Prefs.liveScaleState(ctx)
    val d = if (compact) real.density else androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp * real.density / 1060f * userScale
    CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d, real.fontScale), LocalK provides real.density / d) {
    val inset = safeInset() * LocalK.current
    val side = 10.dp + inset / 2
    Column(Modifier.fillMaxSize().background(LvBg)) {
        LiveHero(current, preview, favs, fmt, tick, onFav = { current?.let { LiveStore.toggleFavourite(ctx, it.id) } }, onRefresh = onRefresh,
            onSettings = { LiveTvEntry.open(activity, settings = true) },
            onSportsHub = { activity.startActivity(android.content.Intent(activity, tv.onnowtv.livetv.SportsHubActivity::class.java)) }) { v -> playerView = v }
        Row(Modifier.weight(1f).fillMaxWidth().padding(start = side, end = side, bottom = 10.dp + inset / 2), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            CategoryColumn(cats, catId, locked, unlocked, favCount = favs.size, total = b.channels.size, onFocus = { focusedCat = it }, onSelect = { selectCategory(it) })
            Column(Modifier.weight(1f).fillMaxHeight().panel().padding(start = 10.dp, end = 10.dp, top = 8.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    LvMono(when (catId) { LiveStore.ALL -> "All channels"; LiveStore.FAVS -> "Favourites"; else -> cats.firstOrNull { it.id == catId }?.name ?: "Channels" }, 10.sp, LvAccent, 0.3f)
                    Spacer(Modifier.width(16.dp)); LvMono("${channels.size} channels", 9.sp, LvDim, 0.2f)
                    Spacer(Modifier.weight(1f))
                    SearchBox(query, searchOpen, onOpen = { searchOpen = true }, onClose = { query = ""; searchOpen = false }) { query = it }
                }
                ChannelColumn(channels, favs, current?.id, firstRow, fmt, tick, onFocus = { focused = it }, onOpen = { openChannel(it, channels) })
            }
            if (!compact) GuideColumn(current, fmt, tick)
        }
    }
    }
    gate?.let { c -> PinGate(c.name, onClose = { gate = null }) { unlocked += c.id; gate = null; selectCategory(c) } }
}

private val V2Sans = androidx.compose.ui.text.font.FontFamily.SansSerif
private val V2MonoF = androidx.compose.ui.text.font.FontFamily.Monospace
@Composable
private fun LvMono(text: String, size: androidx.compose.ui.unit.TextUnit = 11.sp, color: Color = LvDim, tracking: Float = 0.22f, modifier: Modifier = Modifier, upper: Boolean = true) =
    Text(if (upper) text.uppercase() else text, modifier, color = color, fontFamily = V2MonoF, fontWeight = FontWeight.Medium, fontSize = size, letterSpacing = (size.value * tracking).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)

private val LvBg = Color(0xFF09111F); private val LvPanel = Color(0xFF0C1627); private val LvCard = Color(0xFF101B2E); private val LvFocus = Color(0xFF132742)
private val LvDim = Color(0xFFB4BDCC); private val LvDimmer = Color(0xFF7D8799); private val LvRed = Color(0xFFFF2535)
private val LvAccent = Color(0xFF38BDF8); private val LvBorder = Color(0x14FFFFFF)
private val LvGreen = Color(0xFF5BD49A)

private fun Modifier.panel() = background(LvPanel.copy(alpha = 0.94f), PanelShape).border(1.dp, LvBorder, PanelShape)

@Composable
private fun LvText(text: String, size: androidx.compose.ui.unit.TextUnit = 11.sp, color: Color = LvDim, modifier: Modifier = Modifier, weight: FontWeight = FontWeight.Medium, maxLines: Int = 1) =
    Text(text, modifier, color = color, fontFamily = V2Sans, fontWeight = weight, fontSize = size, maxLines = maxLines, overflow = TextOverflow.Ellipsis)

/** Small red rounded rectangle — NOW while on air, LIVE for live events / the preview window. */
@Composable
private fun LiveBadge(live: Boolean, big: Boolean = false) {
    Box(Modifier.height(if (big) 17.dp else 14.dp).clip(RoundedCornerShape(3.dp)).background(LvRed).padding(horizontal = if (big) 7.dp else 6.dp), contentAlignment = Alignment.Center) {
        Text(if (live) "LIVE" else "NOW", color = Color.White, fontFamily = V2Sans, fontWeight = FontWeight.Bold, fontSize = if (big) 9.sp else 8.sp, lineHeight = 9.sp, letterSpacing = 0.8.sp)
    }
}

private fun clock(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%02d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) }

@Composable
private fun LiveHero(ch: Channel?, previewCh: Channel?, favs: Set<String>, fmt: SimpleDateFormat, tick: Long, onFav: () -> Unit, onRefresh: () -> Unit, onSettings: () -> Unit, onSportsHub: () -> Unit, onPlayerView: (PlayerView) -> Unit) {
    val ctx = LocalContext.current
    val compact = isCompact()
    val k = LocalK.current
    val inset = safeInset() * k
    val side = 10.dp + inset / 2
    val previewing = previewCh != null
    // The preview window keeps showing (and timing) the channel that is actually playing while the hero follows the highlighted one.
    val playingNow = remember(previewCh?.id, LiveStore.epg[previewCh?.let { LiveStore.key(it) } ?: ""], tick) { previewCh?.let { LiveStore.nowNext(it).first } }
    val (now, next) = remember(ch?.id, LiveStore.epg[ch?.let { LiveStore.key(it) } ?: ""], tick) { ch?.let { LiveStore.nowNext(it) } ?: (null to null) }
    var art by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(now?.title) {
        val t = now?.title?.takeIf { it.isNotBlank() } ?: run { art = null; return@LaunchedEffect }
        if (LiveStore.art.containsKey(t)) { art = LiveStore.art[t]; return@LaunchedEffect }
        delay(250); art = LiveStore.fetchArt(t)
    }
    val wideBar = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 1200
    val navTop = (if (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 1000) 14.dp else 8.dp) * k + inset / 2
    val topPad = navTop + 58.dp * k + 6.dp
    val prevW = if (compact) 178.dp else 300.dp
    val prevH = if (compact) 100.dp else 169.dp
    Box(Modifier.fillMaxWidth().height(topPad + prevH + 12.dp)) {
        if (art != null) {
            Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.72f)) {
                AsyncImage(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to LvBg, 0.28f to LvBg.copy(alpha = 0.72f), 0.55f to LvBg.copy(alpha = 0.12f), 1f to Color.Transparent)))
            }
        } else if (ch?.logoUrl != null) {
            AsyncImage(ch.logoUrl, null, Modifier.align(Alignment.CenterEnd).padding(end = 120.dp).size(170.dp), contentScale = ContentScale.Fit, alpha = 0.07f)
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to LvBg.copy(alpha = 0.55f), 0.3f to Color.Transparent, 0.72f to Color.Transparent, 1f to LvBg)))
        Row(Modifier.align(Alignment.BottomStart).padding(start = side, bottom = 12.dp, end = side).fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            val pShape = RoundedCornerShape(10.dp)
            Box(Modifier.width(prevW).height(prevH).clip(pShape).background(Color.Black).border(1.5.dp, Color.White.copy(alpha = 0.16f), pShape)) {
                AndroidView(factory = { c -> PlayerView(c).apply { useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM; setShutterBackgroundColor(android.graphics.Color.BLACK); onPlayerView(this) } }, modifier = Modifier.fillMaxSize())
                if (!previewing) {
                    if (art != null) AsyncImage(art, now?.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else { Box(Modifier.fillMaxSize().background(LvCard)); if (ch?.logoUrl != null) AsyncImage(ch.logoUrl, null, Modifier.align(Alignment.Center).fillMaxSize(0.45f), contentScale = ContentScale.Fit, alpha = 0.6f) }
                }
                Box(Modifier.align(Alignment.TopStart).padding(8.dp)) { LiveBadge(true, big = true) }
                PreviewBar(if (previewing) playingNow else now, previewing, Modifier.align(Alignment.BottomCenter))
            }
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                LvMono("Live TV", 10.sp, LvAccent, 0.38f)
                Spacer(Modifier.height(5.dp))
                Text(ch?.let { Settings.cleanName(ctx, it.name) } ?: "Live TV", color = Color.White, fontFamily = V2Sans, fontWeight = FontWeight.Bold, fontSize = if (compact) 12.sp else 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (now != null) {
                    Text(now.title, color = Color.White, fontFamily = V2Sans, fontWeight = FontWeight.Black, fontSize = if (compact) 20.sp else 30.sp, lineHeight = if (compact) 24.sp else 34.sp, letterSpacing = (-0.6).sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(0.6f))
                    if (!compact && !now.description.isNullOrBlank()) { Spacer(Modifier.height(5.dp)); Text(now.description!!, color = Color.White.copy(alpha = 0.88f), fontFamily = V2Sans, fontSize = 10.5.sp, lineHeight = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(300.dp)) }
                    Spacer(Modifier.height(9.dp))
                    ProgressBar(now, Modifier.width(if (compact) 200.dp else 260.dp), 3.dp)
                    Spacer(Modifier.height(8.dp))
                    Row { LvText("Up next:", 10.sp, LvDim); Spacer(Modifier.width(8.dp)); if (next != null) { LvText(fmt.format(Date(next.startMs)), 10.sp, LvDim); Spacer(Modifier.width(10.dp)); LvText(next.title, 10.sp, Color.White.copy(alpha = 0.9f)) } else LvText("—", 10.sp, LvDim) }
                } else { Spacer(Modifier.height(8.dp)); LvText("No programme information", 12.sp, LvDimmer) }
            }
        }
        Row(Modifier.align(Alignment.TopEnd).padding(top = if (wideBar) navTop + 9.dp * k else topPad, end = side), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            val fav = ch != null && ch.id in favs
            HeroPill(Lucide.Trophy, "Sports Hub", onSportsHub)
            HeroIcon(Lucide.Star, if (fav) "Unfavourite" else "Favourite", if (fav) Amber else null, onFav)
            HeroIcon(Lucide.RefreshCw, "Refresh", null, onRefresh)
            HeroIcon(Lucide.Settings, "Live TV settings", null, onSettings)
        }
    }
}

/** Player strip on the preview window: play/pause, elapsed / total of what's on, progress, full-screen hint. */
@Composable
private fun PreviewBar(now: Programme?, previewing: Boolean, modifier: Modifier) {
    val t by produceState(System.currentTimeMillis()) { while (true) { delay(1000); value = System.currentTimeMillis() } }
    Column(modifier.fillMaxWidth().background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))).padding(start = 10.dp, end = 10.dp, top = 14.dp, bottom = 7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (previewing) Lucide.Pause else Lucide.Play, null, Modifier.size(12.dp), tint = Color.White); Spacer(Modifier.width(10.dp))
            Text(if (now != null) "${clock(t - now.startMs)} / ${clock(now.stopMs - now.startMs)}" else if (previewing) "LIVE" else "OK · Preview", color = Color.White, fontFamily = V2MonoF, fontSize = 8.sp, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Icon(Lucide.Maximize, null, Modifier.size(12.dp), tint = Color.White)
        }
        if (now != null) { Spacer(Modifier.height(5.dp)); ProgressBar(now, Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp), 2.dp) }
    }
}

@Composable
private fun HeroPill(icon: ImageVector, label: String, onClick: () -> Unit) {
    Focusable(onClick = onClick) { f ->
        Row(Modifier.height(34.dp).focusScale(f, 1.04f).pillFocus(f).clip(CircleShape).background(LvPanel.copy(alpha = 0.9f)).border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape).padding(horizontal = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, label, Modifier.size(15.dp), tint = LvAccent); Spacer(Modifier.width(8.dp))
            Text(label.uppercase(), color = Color.White, fontFamily = V2Sans, fontWeight = FontWeight.Bold, fontSize = 10.5.sp, letterSpacing = 0.3.sp)
        }
    }
}

@Composable
private fun HeroIcon(icon: ImageVector, label: String, tint: Color?, onClick: () -> Unit) {
    Focusable(onClick = onClick) { f ->
        Box(Modifier.size(34.dp).focusScale(f, 1.04f).pillFocus(f).clip(CircleShape).background(LvPanel.copy(alpha = 0.9f)).border(1.dp, Color.White.copy(alpha = 0.22f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, label, Modifier.size(15.dp), tint = tint ?: Color.White)
        }
    }
}

@Composable
private fun ProgressBar(p: Programme, modifier: Modifier, height: androidx.compose.ui.unit.Dp) {
    val now = System.currentTimeMillis()
    val frac = ((now - p.startMs).toFloat() / (p.stopMs - p.startMs).coerceAtLeast(1)).coerceIn(0f, 1f)
    Box(modifier.height(height).clip(CircleShape).background(Color.White.copy(alpha = 0.12f))) { Box(Modifier.fillMaxHeight().fillMaxWidth(frac).clip(CircleShape).background(LvAccent)) }
}

/** Collapsed to a plain "Search" button so passing over it with the D-pad never pops the keyboard; OK expands it and focuses the field. */
@Composable
private fun SearchBox(q: String, open: Boolean, onOpen: () -> Unit, onClose: () -> Unit, onChange: (String) -> Unit) {
    if (!open) {
        Focusable(onClick = onOpen) { f ->
            Row(Modifier.height(26.dp).pillFocus(f).clip(CircleShape).background(LvCard).border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape).padding(horizontal = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Lucide.Search, null, Modifier.size(12.dp), tint = Color.White); Spacer(Modifier.width(8.dp))
                LvMono("Search", 8.5.sp, Color.White, 0.1f)
            }
        }
        return
    }
    val req = remember { FocusRequester() }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { req.requestFocus() }; keyboard?.show() }
    Row(Modifier.width(260.dp).height(26.dp).clip(CircleShape).background(LvCard).border(1.dp, LvAccent, CircleShape).padding(start = 11.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Lucide.Search, null, Modifier.size(12.dp), tint = LvAccent); Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (q.isEmpty()) LvMono("Search channels", 9.sp, LvDimmer, 0.1f, upper = false)
            androidx.compose.foundation.text.BasicTextField(q, onChange, Modifier.fillMaxWidth().focusRequester(req), singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = Txt, fontFamily = V2Sans, fontSize = 12.sp), cursorBrush = androidx.compose.ui.graphics.SolidColor(LvAccent))
        }
        Focusable(onClick = onClose) { f ->
            Box(Modifier.size(20.dp).clip(CircleShape).background(if (f) LvAccent else Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                Icon(Lucide.X, null, Modifier.size(11.dp), tint = if (f) LvBg else LvDim)
            }
        }
    }
}

@Composable
private fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(LvBorder))

@Composable
private fun CategoryColumn(cats: List<Category>, selected: String, locked: Set<String>, unlocked: List<String>, favCount: Int, total: Int, onFocus: (String?) -> Unit, onSelect: (Category?) -> Unit) {
    val state = rememberLazyListState()
    val selIndex = when (selected) { LiveStore.FAVS -> 0; LiveStore.ALL -> 1; else -> cats.indexOfFirst { it.id == selected } + 2 }
    LaunchedEffect(cats.size) { if (selIndex > 2) runCatching { state.scrollToItem(selIndex - 2) } }
    Column(Modifier.width(if (isCompact()) CatWCompact else CatW).fillMaxHeight().panel().padding(horizontal = 6.dp)) {
        LvMono("Groups", 9.5.sp, LvDim, 0.32f, modifier = Modifier.padding(start = 8.dp, bottom = 9.dp, top = 12.dp))
        Box(Modifier.padding(horizontal = 6.dp)) { Divider() }
        LazyColumn(state = state, contentPadding = PaddingValues(bottom = 8.dp)) {
            item { CategoryRow("Favourites", favCount, selected == LiveStore.FAVS, false, onFocus = { onFocus(LiveStore.FAVS) }) { onSelect(Category(LiveStore.FAVS, "Favourites")) } }
            item { CategoryRow("All channels", total, selected == LiveStore.ALL, false, onFocus = { onFocus(LiveStore.ALL) }) { onSelect(null) } }
            items(cats, key = { it.id }) { c -> CategoryRow(c.name, c.channelCount, selected == c.id, c.id in locked && c.id !in unlocked, onFocus = { onFocus(c.id) }) { onSelect(c) } }
        }
    }
}

@Composable
private fun CategoryRow(name: String, count: Int, active: Boolean, isLocked: Boolean, onFocus: () -> Unit, onClick: () -> Unit) {
    Focusable(Modifier.fillMaxWidth(), onClick = onClick, onFocus = { if (it) onFocus() }) { f ->
        val shape = RoundedCornerShape(7.dp); val on = f || active
        Column {
            Row(Modifier.fillMaxWidth().height(32.dp).clip(shape).background(if (on) LvFocus else Color.Transparent)
                .border(if (f) 1.5.dp else 1.dp, if (f) LvAccent else if (active) LvAccent.copy(alpha = 0.7f) else Color.Transparent, shape).padding(start = if (on) 8.dp else 10.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (on) { Box(Modifier.width(3.dp).height(17.dp).clip(CircleShape).background(LvAccent)); Spacer(Modifier.width(9.dp)) }
                Text(name, color = if (on) Color.White else Color(0xFFE6EAF2), fontFamily = V2Sans, fontWeight = FontWeight.Bold, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (isLocked) Icon(Lucide.Lock, "Locked", Modifier.size(11.dp), tint = Amber) else if (count > 0) Text("$count", color = LvDim, fontFamily = V2MonoF, fontSize = 9.5.sp)
            }
            Box(Modifier.padding(horizontal = 6.dp)) { if (on) Spacer(Modifier.height(1.dp)) else Divider() }
        }
    }
}

@Composable
private fun ChannelColumn(channels: List<Channel>, favs: Set<String>, currentId: String?, first: FocusRequester, fmt: SimpleDateFormat, tick: Long, onFocus: (Channel) -> Unit, onOpen: (Channel) -> Unit) {
    val ctx = LocalContext.current
    if (channels.isEmpty()) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LvMono("No channels in this group", 11.sp, LvDimmer) }; return }
    val state: LazyListState = rememberLazyListState()
    // Whole group's guide loads up front (V2 behaviour) — no need to rest on a channel.
    LaunchedEffect(channels) { channels.take(300).forEach { ch -> launch { runCatching { LiveStore.loadRow(ctx, ch) } } } }
    LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(5.dp), contentPadding = PaddingValues(start = 2.dp, end = 2.dp, top = 2.dp, bottom = 12.dp)) {
        itemsIndexed(channels, key = { _, c -> c.id }) { i, ch ->
            LaunchedEffect(ch.id) { runCatching { LiveStore.loadRow(ctx, ch) } }
            ChannelRow(ch, ch.id in favs, ch.id == currentId, if (i == 0) first else null, fmt, tick, onFocus = { onFocus(ch) }) { onOpen(ch) }
        }
    }
}

@Composable
private fun ChannelRow(ch: Channel, fav: Boolean, active: Boolean, requester: FocusRequester?, fmt: SimpleDateFormat, tick: Long, onFocus: () -> Unit, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val now = remember(ch.id, LiveStore.epg[LiveStore.key(ch)], tick) { LiveStore.nowNext(ch).first }
    Focusable(Modifier.fillMaxWidth(), onClick = onClick, requester = requester, onFocus = { if (it) onFocus() }) { f ->
        // One focus treatment only: a single cyan border with a soft glow.
        Column(Modifier.fillMaxWidth().blueGlow(f, RowShape, 9.dp, 0.55f).clip(RowShape).background(if (f) LvFocus else LvCard)
            .border(if (f) 1.5.dp else 1.dp, if (f) LvAccent else if (active) LvAccent.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.10f), RowShape)) {
            Row(Modifier.fillMaxWidth().height(41.dp).padding(start = 28.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(38.dp).height(28.dp).clip(RoundedCornerShape(5.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                    if (ch.logoUrl != null) AsyncImage(ch.logoUrl, null, Modifier.fillMaxSize().padding(3.dp), contentScale = ContentScale.Fit)
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(Settings.cleanName(ctx, ch.name), color = Color.White, fontFamily = V2Sans, fontWeight = FontWeight.Bold, fontSize = 12.5.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (now != null) { LiveBadge(now.live); Spacer(Modifier.width(8.dp)) }
                        Text(now?.let { "${fmt.format(Date(it.startMs))} · ${it.title}" } ?: "No guide data", color = if (now == null) LvDimmer else LvDim, fontFamily = V2MonoF, fontSize = 9.5.sp, lineHeight = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (fav) Icon(Lucide.Star, null, Modifier.size(12.dp), tint = Amber)
            }
            if (now != null) ProgressBar(now, Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 5.dp), 2.dp) else Spacer(Modifier.height(7.dp))
        }
    }
}

/** Today's schedule: previous show, what's on now (boxed, with progress) and what's coming. OK on an upcoming show sets/clears a reminder (bell appears once set). */
@Composable
private fun GuideColumn(ch: Channel?, fmt: SimpleDateFormat, tick: Long) {
    val ctx = LocalContext.current
    val list = ch?.let { LiveStore.epg[LiveStore.key(it)] } ?: emptyList()
    val nowMs = System.currentTimeMillis() + (tick * 0)
    val dayFmt = remember { SimpleDateFormat("EEEE d MMM", java.util.Locale.UK) }
    val shown = remember(list, tick) { val i = list.indexOfFirst { it.stopMs > nowMs }; if (i < 0) emptyList() else list.drop(maxOf(0, i - 1)).take(60) }
    val today = remember(tick) { dayFmt.format(Date()) }
    val rows = remember(shown) { buildList<Any> { var last = today; shown.forEach { p -> val d = dayFmt.format(Date(p.startMs)); if (d != last && p.startMs > nowMs) { last = d; add(d) }; add(p) } } }
    var reminders by remember { mutableStateOf(ReminderStore.load(ctx).keys.toSet()) }
    fun key(p: Programme) = "${ch?.epgChannelId ?: ""}:${p.startMs}"
    fun toggle(p: Programme) {
        val c = ch ?: return
        val map = ReminderStore.load(ctx); val k = key(p)
        if (map.remove(k) == null) map[k] = ReminderStore.Reminder(k, c.id, c.name, c.logoUrl, c.lcn, p.title, p.startMs, p.stopMs)
        ReminderStore.save(ctx, map); reminders = map.keys.toSet()
    }
    Column(Modifier.width(GuideW).fillMaxHeight().panel().padding(horizontal = 8.dp)) {
        LvMono("Today's schedule", 9.5.sp, LvAccent, 0.3f, Modifier.padding(start = 8.dp, top = 10.dp))
        Text(ch?.let { Settings.cleanName(ctx, it.name) } ?: "—", color = Color.White, fontFamily = V2Sans, fontWeight = FontWeight.Bold, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp, top = 5.dp, bottom = 7.dp))
        Box(Modifier.padding(horizontal = 6.dp)) { Divider() }
        if (shown.isEmpty()) LvMono(if (ch == null) "" else "No guide data", 9.sp, LvDimmer, modifier = Modifier.padding(8.dp))
        LazyColumn(contentPadding = PaddingValues(top = 2.dp, bottom = 8.dp)) {
            items(rows.size, key = { i -> (rows[i] as? Programme)?.startMs ?: rows[i] }) { i ->
                val row = rows[i]
                if (row is String) { LvMono(row, 8.5.sp, LvDimmer, 0.2f, Modifier.padding(start = 8.dp, top = 10.dp, bottom = 4.dp)); return@items }
                val p = row as Programme
                val live = p.isLiveAt(nowMs); val past = p.stopMs <= nowMs; val future = p.startMs > nowMs
                val set = key(p) in reminders
                Focusable(Modifier.fillMaxWidth(), onClick = { if (future) toggle(p) }) { f ->
                    val shape = RoundedCornerShape(8.dp); val boxed = live || f || set
                    Column {
                        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).clip(shape).background(if (live || f) LvFocus else Color.Transparent)
                            .border(if (f) 1.5.dp else 1.dp, if (f) LvAccent else if (live) LvAccent.copy(alpha = 0.85f) else if (set) LvGreen else Color.Transparent, shape)
                            .padding(horizontal = 8.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(fmt.format(Date(p.startMs)), color = if (live) LvAccent else if (past) LvDimmer else LvDim, fontFamily = V2MonoF, fontWeight = if (live) FontWeight.Bold else FontWeight.Normal, fontSize = 9.5.sp, maxLines = 1, modifier = Modifier.width(62.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.title, color = if (live || f) Color.White else if (past) LvDimmer else Color(0xFFE6EAF2), fontFamily = V2Sans, fontWeight = if (live) FontWeight.Bold else FontWeight.Medium, fontSize = if (live) 12.sp else 11.sp, lineHeight = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (live) { Spacer(Modifier.height(6.dp)); ProgressBar(p, Modifier.fillMaxWidth(), 2.5.dp) }
                            }
                            if (set) { Spacer(Modifier.width(6.dp)); Icon(Lucide.BellRing, "Reminder set", Modifier.size(12.dp), tint = LvGreen) }
                        }
                        Box(Modifier.padding(horizontal = 6.dp)) { if (boxed) Spacer(Modifier.height(1.dp)) else Divider() }
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
