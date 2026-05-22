package tv.vesper.app

/*
 * v2.7.74 — Native Live TV Guide overlay (Compose).
 *
 * Renders the slide-in guide that lives on top of `ExoPlayerActivity`'s
 * PlayerView.  Built to the user's locked-in spec:
 *   1) Push LEFT → opens with channel rail focused.
 *   2) Push LEFT again from the channel rail → categories column
 *      slides in to the left of the channels.
 *   3) Push RIGHT from channels jumps to the "UP NEXT" strip.
 *   4) Hover a channel for 1 s → auto-tune.
 *   5) OK on a channel → tune immediately.
 *   6) BACK / MENU → close (video keeps playing).
 *
 * Visual fidelity follows the mockup at
 *   /tmp/livetv_guide_mockup.png (user-provided):
 *   • Header: "LIVE TV GUIDE" + channel count (top-left), clock +
 *     date (top-right).
 *   • Channel row: rounded card 240 × 84 dp.  Number on the left
 *     (mono, dim), 56 × 56 logo plate, name in white bold.
 *   • Focused row: cyan border ring + brighter logo plate +
 *     chevron pointer ">" pulling toward the centre column.
 *   • Centre info column: LIVE pill, programme title (clamp 2),
 *     genre, synopsis (clamp 4), time range + "X min remaining",
 *     progress bar, optional HD / 5.1 / CC badges.
 *   • Bottom strip: "UP NEXT ON <CHANNEL>" with 4-5 cinematic
 *     thumbnail cards from TMDB lookups.
 */

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.KeyboardArrowRight
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

private val GuideCyan = Color(0xFF5DC8FF)

@Composable
fun LiveGuideOverlay(
    manager: LiveGuideManager,
    onTuneChannel: (LiveGuideManager.LiveChannel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val mode by manager.mode.collectAsState()
    val visible = mode != LiveGuideManager.MODE_CLOSED

    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(initialOffsetX = { -it / 2 }, animationSpec = tween(260)) + fadeIn(tween(260)),
        exit  = slideOutHorizontally(targetOffsetX = { -it / 2 }, animationSpec = tween(220)) + fadeOut(tween(220)),
        modifier = modifier,
    ) {
        GuideBody(manager = manager, onTuneChannel = onTuneChannel)
    }
}

@Composable
private fun GuideBody(
    manager: LiveGuideManager,
    onTuneChannel: (LiveGuideManager.LiveChannel) -> Unit,
) {
    val visibleChannels by manager.visibleChannels.collectAsState()
    val focusedId by manager.focusedChannelId.collectAsState()
    val playingId by manager.playingChannelId.collectAsState()
    val categories by manager.categories.collectAsState()
    val selectedCatId by manager.selectedCategoryId.collectAsState()
    val mode by manager.mode.collectAsState()
    // re-render trigger when TMDB art finishes resolving asynchronously
    val artTick by manager.artUpdateTick.collectAsState()

    val focusedChannel = remember(focusedId, visibleChannels) {
        visibleChannels.firstOrNull { it.streamId == focusedId } ?: visibleChannels.firstOrNull()
    }

    // Auto-tune: when the focused channel stays stable for 1 s and
    // is different from the currently-playing one, tune to it.
    LaunchedEffect(focusedChannel?.streamId) {
        val ch = focusedChannel ?: return@LaunchedEffect
        if (ch.streamId == playingId) return@LaunchedEffect
        delay(1000L)
        onTuneChannel(ch)
    }

    // Prefetch TMDB art for the focused channel's now-on programme
    // + the up-next list so the right-side strip is populated.
    LaunchedEffect(focusedChannel?.streamId, artTick) {
        val ch = focusedChannel ?: return@LaunchedEffect
        manager.nowProgramme(ch.streamId)?.let {
            if (it.title.isNotBlank()) manager.fetchArt(it.title, it.year)
        }
        manager.upNext(ch.streamId).forEach {
            if (it.title.isNotBlank()) manager.fetchArt(it.title, it.year)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Subtle full-screen scrim so the underlying video dims
            // a touch (but is still very much visible — we promised
            // video keeps playing on the right).
            .background(Color(0x99000000)),
    ) {
        // ── Top header ───────────────────────────────────────────
        GuideHeader(channelCount = visibleChannels.size)

        // ── Left column(s) ───────────────────────────────────────
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 92.dp, start = 24.dp)
                .fillMaxHeight()
                .padding(bottom = 220.dp),  // leave room for Up Next strip
            verticalAlignment = Alignment.Top,
        ) {
            // Categories column — only when mode == MODE_BOTH
            AnimatedVisibility(
                visible = mode == LiveGuideManager.MODE_BOTH,
                enter = slideInHorizontally(initialOffsetX = { -it }, animationSpec = tween(220)) + fadeIn(tween(220)),
                exit  = slideOutHorizontally(targetOffsetX = { -it }, animationSpec = tween(180)) + fadeOut(tween(180)),
            ) {
                CategoryRail(
                    categories = categories,
                    selectedId = selectedCatId,
                    onSelect = { id ->
                        manager.setSelectedCategory(id)
                        // After picking a category, move focus back
                        // to the channel rail (collapse cats column).
                        manager.open()  // sets mode = CHANNELS
                    },
                )
            }
            Spacer(Modifier.width(if (mode == LiveGuideManager.MODE_BOTH) 12.dp else 0.dp))

            // Channel rail
            ChannelRail(
                channels = visibleChannels,
                focusedId = focusedId,
                playingId = playingId,
                onFocus = { manager.setFocusedChannel(it.streamId) },
                onTune  = { ch ->
                    onTuneChannel(ch)
                },
                onOpenCategories = { manager.openCategories() },
            )

            Spacer(Modifier.width(28.dp))

            // Middle programme info column
            focusedChannel?.let { ch ->
                ProgrammeInfoColumn(
                    channel = ch,
                    nowProg = manager.nowProgramme(ch.streamId),
                )
            }
        }

        // ── Bottom Up Next strip ─────────────────────────────────
        focusedChannel?.let { ch ->
            UpNextStrip(
                channelName = ch.name,
                upNext = manager.upNext(ch.streamId),
                artLookup = { p -> manager.cachedArt(p.title, p.year) },
                modifier = Modifier.align(Alignment.BottomStart),
            )
        }
    }
}

@Composable
private fun GuideHeader(channelCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 36.dp, end = 36.dp, top = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "LIVE TV GUIDE",
            color = GuideCyan,
            fontSize = 16.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.4.sp,
        )
        Spacer(Modifier.width(18.dp))
        Text(
            text = "$channelCount CHANNELS",
            color = Color(0xFF94B8D6),
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.6.sp,
        )
        Spacer(Modifier.weight(1f))
        LiveClock()
    }
}

@Composable
private fun LiveClock() {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000L)
        }
    }
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val dateFmt = remember { SimpleDateFormat("EEE, MMM d", Locale.getDefault()) }
    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = timeFmt.format(Date(now)),
            color = Color.White,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = dateFmt.format(Date(now)),
            color = Color(0xFF94B8D6),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun CategoryRail(
    categories: List<LiveGuideManager.LiveCategory>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    LazyColumn(
        modifier = Modifier
            .width(190.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xE60B1322))
            .padding(vertical = 10.dp, horizontal = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            CategoryRow(
                label = "ALL",
                count = categories.sumOf { it.count }.coerceAtLeast(0),
                selected = selectedId == null,
                focusRequester = firstFocus,
                onClick = { onSelect(null) },
            )
        }
        items(categories, key = { it.id }) { cat ->
            CategoryRow(
                label = cat.name.uppercase(),
                count = cat.count,
                selected = cat.id == selectedId,
                onClick = { onSelect(cat.id) },
            )
        }
    }
    LaunchedEffect(Unit) {
        delay(220)
        try { firstFocus.requestFocus() } catch (_: Exception) {}
    }
}

@Composable
private fun CategoryRow(
    label: String,
    count: Int,
    selected: Boolean,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor = when {
        focused  -> GuideCyan
        selected -> GuideCyan.copy(alpha = 0.55f)
        else     -> Color.Transparent
    }
    val bg = when {
        focused  -> Color(0xFF173052)
        selected -> Color(0xFF0F2240)
        else     -> Color(0xCC0F1A30)
    }
    var mod: Modifier = Modifier
        .fillMaxWidth()
        .height(46.dp)
        .clip(RoundedCornerShape(10.dp))
        .background(bg)
        .border(if (focused) 2.dp else 1.dp, borderColor, RoundedCornerShape(10.dp))
    if (focusRequester != null) mod = mod.focusRequester(focusRequester)
    mod = mod.onFocusChanged { focused = it.isFocused }.focusable()
    Row(
        modifier = mod.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = if (focused || selected) Color.White else Color(0xFFB8D9F2),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = if (count > 0) count.toString() else "",
            color = Color(0xFF6B7587),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun ChannelRail(
    channels: List<LiveGuideManager.LiveChannel>,
    focusedId: String?,
    playingId: String?,
    onFocus: (LiveGuideManager.LiveChannel) -> Unit,
    onTune: (LiveGuideManager.LiveChannel) -> Unit,
    onOpenCategories: () -> Unit,
) {
    val listState = rememberLazyListState()
    val firstFocus = remember { FocusRequester() }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .width(340.dp)
            .fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(channels, key = { it.streamId }) { ch ->
            val isFirstFocusable = ch.streamId == (focusedId ?: channels.firstOrNull()?.streamId)
            ChannelRow(
                channel = ch,
                isFocused = ch.streamId == focusedId,
                isPlaying = ch.streamId == playingId,
                focusRequester = if (isFirstFocusable) firstFocus else null,
                onGotFocus = { onFocus(ch) },
                onClick = { onTune(ch) },
                onLeft = onOpenCategories,
            )
        }
    }
    LaunchedEffect(Unit) {
        delay(300)
        try { firstFocus.requestFocus() } catch (_: Exception) {}
    }
    // Keep the focused row centred-ish as the user D-pads.
    LaunchedEffect(focusedId) {
        val idx = channels.indexOfFirst { it.streamId == focusedId }
        if (idx >= 0) {
            // Scroll so the focused row sits about 4 rows from the top.
            val target = max(0, idx - 4)
            try { listState.animateScrollToItem(target) } catch (_: Exception) {}
        }
    }
}

@Composable
private fun ChannelRow(
    channel: LiveGuideManager.LiveChannel,
    isFocused: Boolean,
    isPlaying: Boolean,
    focusRequester: FocusRequester?,
    onGotFocus: () -> Unit,
    onClick: () -> Unit,
    onLeft: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val ringColor = when {
        focused   -> GuideCyan
        isFocused -> GuideCyan.copy(alpha = 0.4f)
        else      -> Color.Transparent
    }
    val bg = if (focused) Color(0xFF173052) else Color(0xCC0F1A30)
    var mod: Modifier = Modifier
        .fillMaxWidth()
        .height(84.dp)
        .clip(RoundedCornerShape(14.dp))
        .background(bg)
        .border(if (focused) 2.dp else 1.dp, ringColor, RoundedCornerShape(14.dp))
    if (focusRequester != null) mod = mod.focusRequester(focusRequester)
    mod = mod
        .onFocusChanged {
            focused = it.isFocused
            if (it.isFocused) onGotFocus()
        }
        .focusable()
        .onKeyEvent { ev ->
            if (ev.type == KeyEventType.KeyDown) {
                when (ev.nativeKeyEvent.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                    android.view.KeyEvent.KEYCODE_ENTER -> { onClick(); true }
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { onLeft(); true }
                    else -> false
                }
            } else false
        }
    Row(
        modifier = mod.padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = channel.number.toString().padStart(3, '0'),
            color = Color(0xFF6B7587),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
            modifier = Modifier.width(38.dp),
        )
        ChannelLogoPlate(logoUrl = channel.logo, name = channel.name)
        Spacer(Modifier.width(12.dp))
        Text(
            text = channel.name,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (isPlaying) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(GuideCyan),
            )
        }
        if (focused) {
            Spacer(Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = GuideCyan,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun ChannelLogoPlate(logoUrl: String, name: String) {
    Box(
        modifier = Modifier
            .size(56.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFE9EEF5)),
        contentAlignment = Alignment.Center,
    ) {
        if (logoUrl.isNotBlank()) {
            AsyncImage(
                model = logoUrl,
                contentDescription = name,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(6.dp),
            )
        } else {
            Text(
                text = name.take(4).uppercase(),
                color = Color(0xFF0F1A30),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ProgrammeInfoColumn(
    channel: LiveGuideManager.LiveChannel,
    nowProg: LiveGuideManager.LiveProgramme?,
) {
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val now = System.currentTimeMillis()
    Column(
        modifier = Modifier
            .width(420.dp)
            .padding(top = 14.dp, end = 24.dp),
    ) {
        // LIVE pill
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color(0x331E90FF))
                    .border(1.dp, GuideCyan.copy(alpha = 0.6f), RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(GuideCyan),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "LIVE",
                        color = GuideCyan,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.6.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = (nowProg?.title?.takeIf { it.isNotBlank() } ?: channel.name),
            color = Color.White,
            fontSize = 36.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 40.sp,
        )
        if (nowProg != null && nowProg.episodeTitle.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = buildString {
                    if (nowProg.season.isNotBlank() && nowProg.episode.isNotBlank()) {
                        append("S").append(nowProg.season).append(" · E").append(nowProg.episode).append(" — ")
                    }
                    append(nowProg.episodeTitle)
                },
                color = Color(0xFFB8D9F2),
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(14.dp))
        if (nowProg != null && nowProg.desc.isNotBlank()) {
            Text(
                text = nowProg.desc,
                color = Color(0xFFD2DEEF),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(
                text = "No programme information available.",
                color = Color(0xFF6B7587),
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(22.dp))
        if (nowProg != null) {
            // Time range + remaining
            val total = (nowProg.stopMs - nowProg.startMs).coerceAtLeast(1L)
            val elapsed = (now - nowProg.startMs).coerceAtLeast(0L)
            val remainingMin = ((nowProg.stopMs - now) / 60_000L).coerceAtLeast(0L)
            val progress = min(1f, elapsed / total.toFloat())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${timeFmt.format(Date(nowProg.startMs))} – ${timeFmt.format(Date(nowProg.stopMs))}",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "$remainingMin min remaining",
                    color = Color(0xFF94B8D6),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.height(8.dp))
            // Progress bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0x33FFFFFF)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(progress)
                        .background(
                            Brush.horizontalGradient(
                                listOf(GuideCyan, Color(0xFF8FE0FF)),
                            ),
                        ),
                )
            }
        }
    }
}

@Composable
private fun UpNextStrip(
    channelName: String,
    upNext: List<LiveGuideManager.LiveProgramme>,
    artLookup: (LiveGuideManager.LiveProgramme) -> LiveGuideManager.EpgArt?,
    modifier: Modifier = Modifier,
) {
    if (upNext.isEmpty()) return
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, bottom = 22.dp),
    ) {
        Text(
            text = "UP NEXT ON ${channelName.uppercase()}",
            color = Color(0xFF94B8D6),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.8.sp,
        )
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(upNext) { p ->
                UpNextCard(programme = p, timeFmt = timeFmt, art = artLookup(p))
            }
        }
    }
}

@Composable
private fun UpNextCard(
    programme: LiveGuideManager.LiveProgramme,
    timeFmt: SimpleDateFormat,
    art: LiveGuideManager.EpgArt?,
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor = if (focused) GuideCyan else Color(0x33FFFFFF)
    Column(
        modifier = Modifier
            .width(260.dp)
            .height(150.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xCC0F1A30))
            .border(if (focused) 2.dp else 1.dp, borderColor, RoundedCornerShape(12.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(90.dp)
                .background(Color(0xFF142846)),
        ) {
            if (!art?.backdropUrl.isNullOrBlank()) {
                AsyncImage(
                    model = art?.backdropUrl,
                    contentDescription = programme.title,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Text(
                text = timeFmt.format(Date(programme.startMs)),
                color = Color.White,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xCC000000))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(
                text = programme.title.ifBlank { "Untitled" },
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    if (programme.season.isNotBlank() && programme.episode.isNotBlank()) {
                        append("S").append(programme.season).append(" E").append(programme.episode)
                    } else if (programme.year.isNotBlank()) {
                        append(programme.year)
                    }
                },
                color = Color(0xFF94B8D6),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
    }
}
