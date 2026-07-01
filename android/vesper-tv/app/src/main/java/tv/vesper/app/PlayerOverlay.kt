package tv.vesper.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// ─────────────────────────────────────────────────────────────────────────────
// Palette
// ─────────────────────────────────────────────────────────────────────────────
private val NavyBg      = Color(0xFF06080F)
private val NavyDeep    = Color(0xFF020610)
private val CyanPrimary = Color(0xFF5DC8FF)
private val TextPrim    = Color(0xFFFFFFFF)
private val TextSub     = Color(0xCCC7CFDB)
private val TextMuted   = Color(0xFFA8B5C7)

data class PlayerInfo(
    val title: String,
    val synopsis: String,
    val year: String,
    val runtime: String,
    val rating: String,
    val backdrop: String,
    val addonSource: String,
    val quality: String,
    val isEnglish: Boolean,
    val sizeGb: Float,
    val poster: String = "",
)

data class TrackOption(val id: String, val label: String, val selected: Boolean)
data class StreamOption(
    val idx: Int,
    val label: String,
    val selected: Boolean,
    val addonSource: String = "",
    val quality: String = "",
    val pmCached: Boolean = false,
    val isEnglish: Boolean = false,
)

/**
 * Root overlay rendered ABOVE ExoPlayer's PlayerView.
 *
 * v2.7.44 changes:
 *   • Status pill border removed (plain cyan text).
 *   • Loading dots animate at 2.4 s/cycle (was 1.2 s).
 *   • Mid-playback rebuffering shows a small spinner, NOT the full
 *     loading splash (tracked via `hasEverPlayed`).
 *   • All dock buttons are focusable + D-pad-navigable.
 *   • Audio / Subtitle / Stream picker sheets wired to callbacks.
 */
@Composable
fun PlayerOverlay(
    // v2.10.40 — `info` is now a reactive StateFlow so mid-playback
    // episode swaps (skip-next-episode) propagate to the dock title.
    // Previously this was a one-shot snapshot — the title stayed
    // stuck at S1E5 even after the activity had swapped to S1E6,
    // making the user think the swap had "replayed the same episode".
    infoFlow: StateFlow<PlayerInfo>,
    // v2.10.40 — Forces the full LoadingScreen (not the tiny corner
    // spinner) during an in-place episode swap, regardless of
    // `hasEverPlayed`.
    isSwappingEpisode: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow(),
    isPlaying: StateFlow<Boolean>,
    positionMs: StateFlow<Long>,
    durationMs: StateFlow<Long>,
    bufferedPercent: StateFlow<Int>,
    bufferAheadMs: StateFlow<Long>,
    bitrateKbps: StateFlow<Long>,
    isLoading: StateFlow<Boolean>,
    errorMessage: StateFlow<String?>,
    audioTracks: StateFlow<List<TrackOption>>,
    subtitleTracks: StateFlow<List<TrackOption>>,
    streams: StateFlow<List<StreamOption>>,
    userActivity: StateFlow<Long>,
    // v2.7.60 — Native Watch Together voice dock.  Null when not in a
    // party (or when party_code wasn't supplied via intent extras).
    partyVoice: PartyVoiceManager? = null,
    // v2.7.73 — Watch Together left-side host menu (slide-in drawer
    // toggled by the MENU button on the remote).  Owner of this flag
    // is ExoPlayerActivity; PlayerOverlay reads it and renders the
    // drawer accordingly.
    partyDrawerOpen: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow(),
    partyRole: String = "guest",
    onPlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeekTo: (Long) -> Unit,
    onPickAudio: (String) -> Unit,
    onPickSubtitle: (String) -> Unit,
    onPickStream: (Int) -> Unit,
    // v2.10.24 — Skip-Next-Episode dock button (TV shows only).
    // `hasNextEpisode` flips true ~60s before the credits when we
    // know there IS a next episode to jump to.  `onNextEpisode`
    // saves the intent + finishes the activity; MainActivity then
    // either auto-plays the next ep or opens its episode picker.
    hasNextEpisode: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow(),
    nextEpisodeThumbnailUrl: StateFlow<String> = MutableStateFlow("").asStateFlow(),
    logoUrl: StateFlow<String> = MutableStateFlow("").asStateFlow(),
    onNextEpisode: () -> Unit = {},
    onClose: () -> Unit,
) {
    // v2.10.40 — Reactive PlayerInfo so the title / poster / logo
    // update when the in-place episode swap mutates the underlying
    // state in ExoPlayerActivity.
    val info by collectAsStateSafe(
        infoFlow,
        PlayerInfo(
            title = "", synopsis = "", year = "", runtime = "", rating = "",
            backdrop = "", addonSource = "", quality = "", isEnglish = false,
            sizeGb = 0f, poster = "",
        )
    )
    val swappingEpisode by collectAsStateSafe(isSwappingEpisode, false)
    val playing by collectAsStateSafe(isPlaying, false)
    val pos by collectAsStateSafe(positionMs, 0L)
    val dur by collectAsStateSafe(durationMs, 0L)
    val bufAhead by collectAsStateSafe(bufferAheadMs, 0L)
    // v2.10.80 — Surface buffer-percent + bitrate for the Info sheet
    // (buffering diagnostics shown when the user taps the new (i)
    // button in the LEFT dock cluster).
    val bufferedPercentValue by collectAsStateSafe(bufferedPercent, 0)
    val bitrate by collectAsStateSafe(bitrateKbps, 0L)
    val loading by collectAsStateSafe(isLoading, true)
    val error by collectAsStateSafe(errorMessage, null)
    val audios by collectAsStateSafe(audioTracks, emptyList())
    val subs by collectAsStateSafe(subtitleTracks, emptyList())
    val streamList by collectAsStateSafe(streams, emptyList())
    val hasNext by collectAsStateSafe(hasNextEpisode, false)
    val nextEpThumb by collectAsStateSafe(nextEpisodeThumbnailUrl, "")
    val logoUrlValue by collectAsStateSafe(logoUrl, "")
    // v2.7.54 — Activity dispatchKeyEvent pumps every D-pad press
    // here, so the dock auto-hide timer always sees fresh activity.
    val userActivityTs by collectAsStateSafe(userActivity, System.currentTimeMillis())

    // Track whether we've EVER seen playback running.  Used to switch
    // mid-playback rebuffer from "full loading screen" → "small spinner".
    var hasEverPlayed by remember { mutableStateOf(false) }
    LaunchedEffect(playing) { if (playing) hasEverPlayed = true }

    // v2.10.40 — `swappingEpisode` overrides the hasEverPlayed gate
    // so the full LoadingScreen (with episode title + show logo)
    // re-appears for the duration of the next-episode swap.  This
    // is the unmissable visual confirmation the user demanded — no
    // more "tiny corner spinner with the OLD frozen frame" UX that
    // looked like the same episode was being replayed.
    val showFullLoader = swappingEpisode || (loading && !hasEverPlayed)
    val showRebufferSpinner = loading && hasEverPlayed && !swappingEpisode

    // Auto-hide the bottom dock after 5 s without user activity.
    // v2.7.54 — Driven by userActivityTs (pumped by
    // Activity.dispatchKeyEvent on every key press) so the dock
    // re-appears even after auto-hide.
    var dockVisible by remember { mutableStateOf(true) }
    var lastActivity by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(userActivityTs) {
        lastActivity = userActivityTs
        dockVisible = true
        delay(if (!hasEverPlayed) 10_000L else 5_000L)
        if (System.currentTimeMillis() - lastActivity >= 5000) dockVisible = false
    }
    val bump: () -> Unit = { lastActivity = System.currentTimeMillis() }

    // Track picker sheet state
    var sheet by remember { mutableStateOf<SheetKind>(SheetKind.None) }

    Box(modifier = Modifier
        .fillMaxSize()
        .onKeyEvent { ev ->
            // v2.7.51 — Any remote key press re-shows the dock so
            // the user can navigate again after auto-hide.  We only
            // intercept on KeyDown to avoid bumping twice per press.
            // Returning false lets the press continue to focused
            // children (DockButton handles its own onKeyEvent).
            if (ev.type == KeyEventType.KeyDown) {
                bump()
            }
            false
        }
    ) {
        // ── Full loading screen (first play only) ──────────────────
        AnimatedVisibility(
            visible = showFullLoader,
            enter = fadeIn(tween(200)),
            exit  = fadeOut(tween(400)),
        ) { LoadingScreen(info, error, logoUrlValue) }

        // ── Mid-playback rebuffer: tiny corner spinner ─────────────
        AnimatedVisibility(
            visible = showRebufferSpinner,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            RebufferSpinner(
                modifier = Modifier.padding(top = 28.dp, start = 36.dp),
            )
        }

        // ── Top status pill (BUF · ExoPlayer) ──────────────────────
        if (!showFullLoader) {
            TopStatusBadge(
                bufferAheadSec = (bufAhead / 1000L).toInt(),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 28.dp, end = 36.dp),
            )
        }

        // ── Bottom control dock (auto-hide) ────────────────────────
        // v2.7.73 — In party mode the bottom dock is entirely
        // suppressed; the new left-side PartyHostDrawer replaces it.
        // Solo playback keeps the original auto-hide behaviour.
        val inParty = partyVoice != null
        AnimatedVisibility(
            visible  = !inParty && !showFullLoader && dockVisible && sheet == SheetKind.None,
            enter    = fadeIn(tween(220)),
            exit     = fadeOut(tween(280)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ControlDock(
                info        = info,
                isPlaying   = playing,
                positionMs  = pos,
                durationMs  = dur,
                bufferedMs  = pos + bufAhead,
                hasAudio    = audios.size > 1,
                hasSubs     = subs.isNotEmpty(),
                hasStreams  = streamList.size > 1,
                streamCount = streamList.size,
                hasNextEp   = hasNext,
                nextEpThumbnailUrl = nextEpThumb,
                logoUrl     = logoUrlValue,
                onPlayPause = { bump(); onPlayPause() },
                onSeekBy    = { dt -> bump(); onSeekBy(dt) },
                onSeekTo    = { p -> bump(); onSeekTo(p) },
                onPickAudio = { bump(); sheet = SheetKind.Audio },
                onPickSubs  = { bump(); sheet = SheetKind.Subs },
                onPickStream= { bump(); sheet = SheetKind.Stream },
                onPickInfo  = { bump(); sheet = SheetKind.Info },
                onNextEp    = { bump(); onNextEpisode() },
                onClose     = { bump(); onClose() },
            )
        }

        // ── Picker sheet (Audio / Subs / Stream / Info) ───────────
        AnimatedVisibility(
            visible = sheet != SheetKind.None,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
        ) {
            when (sheet) {
                SheetKind.Audio -> TrackPickerSheet(
                    title = "Audio track",
                    options = audios,
                    onPick = { id ->
                        onPickAudio(id)
                        sheet = SheetKind.None
                        bump()
                    },
                    onDismiss = { sheet = SheetKind.None; bump() },
                )
                SheetKind.Subs -> TrackPickerSheet(
                    title = "Subtitles",
                    options = if (subs.any { it.id == "off" }) subs
                              else listOf(TrackOption("off", "Off", subs.none { it.selected })) + subs,
                    onPick = { id ->
                        onPickSubtitle(id)
                        sheet = SheetKind.None
                        bump()
                    },
                    onDismiss = { sheet = SheetKind.None; bump() },
                )
                SheetKind.Stream -> StreamPickerSheet(
                    streams = streamList,
                    onPick = { idx ->
                        onPickStream(idx)
                        sheet = SheetKind.None
                        bump()
                    },
                    onDismiss = { sheet = SheetKind.None; bump() },
                )
                SheetKind.Info -> BufferingInfoSheet(
                    bufferAheadMs = bufAhead,
                    bufferedPercent = bufferedPercentValue,
                    bitrateKbps = bitrate,
                    onDismiss = { sheet = SheetKind.None; bump() },
                )
                SheetKind.None -> Unit
            }
        }

        // v2.7.60 — Watch Together voice dock + voice bubbles overlay.
        // Renders only when partyVoice != null (i.e. an active party).
        if (partyVoice != null) {
            PartyVoiceLayer(
                manager = partyVoice,
                onActivity = bump,
                modifier = Modifier.fillMaxSize(),
            )
            // v2.7.73 — Left-side slide-in drawer with party-specific
            // controls (Play/Pause, Catch Up, Subtitles, Audio).
            // Toggled by the remote's MENU button (see ExoPlayerActivity).
            PartyHostDrawer(
                manager      = partyVoice,
                openFlow     = partyDrawerOpen,
                role         = partyRole,
                isPlaying    = playing,
                onPlayPause  = onPlayPause,
                onCatchUp    = { hostMs ->
                    if (hostMs > 0L) onSeekTo(hostMs)
                },
                onOpenSubs   = { sheet = SheetKind.Subs },
                onOpenAudio  = { sheet = SheetKind.Audio },
                modifier     = Modifier.fillMaxSize(),
            )
        }
    }
}

private enum class SheetKind { None, Audio, Subs, Stream, Info }

// ─────────────────────────────────────────────────────────────────────────────
// Full loading screen (first play only)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun LoadingScreen(info: PlayerInfo, error: String?, logoUrl: String = "") {
    Box(modifier = Modifier.fillMaxSize().background(NavyBg)) {
        if (info.backdrop.isNotBlank()) {
            AsyncImage(
                model = info.backdrop,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(0.55f),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(Color.Transparent, NavyBg),
                        radius = 1600f,
                    ),
                ),
        )

        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 80.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (info.poster.isNotBlank() || info.backdrop.isNotBlank()) {
                AsyncImage(
                    model = info.poster.ifBlank { info.backdrop },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .width(220.dp)
                        .height(330.dp)
                        .shadow(24.dp, RoundedCornerShape(14.dp))
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF101723)),
                )
                Spacer(Modifier.width(48.dp))
            }
            Column(modifier = Modifier.fillMaxWidth()) {
                // v2.10.37 — TMDB title logo above the hero text on
                // the loading screen — matches what the in-dock
                // overlay does so the user sees the same wordmark
                // on both screens.  Renders only when the logo
                // fetch succeeded; until then the giant 44 sp
                // "Michael" / "Breaking Bad" text below carries
                // the load.
                if (logoUrl.isNotBlank()) {
                    AsyncImage(
                        model = logoUrl,
                        contentDescription = info.title,
                        contentScale = ContentScale.Fit,
                        alignment = Alignment.CenterStart,
                        modifier = Modifier
                            .heightIn(min = 80.dp, max = 120.dp)
                            .widthIn(max = 480.dp),
                    )
                    Spacer(Modifier.height(20.dp))
                }
                Text(
                    text = "NOW PLAYING · ON NOW TV V2",
                    color = CyanPrimary,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.6.sp,
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = info.title.ifBlank { "Loading…" },
                    color = TextPrim,
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(14.dp))
                val metaParts = buildList {
                    if (info.year.isNotBlank()) add(info.year)
                    if (info.runtime.isNotBlank()) add(info.runtime)
                    if (info.rating.isNotBlank()) add(info.rating)
                    add(info.quality)
                    if (info.isEnglish) add("🇬🇧 ENG")
                }
                Text(
                    text = metaParts.joinToString("  ·  "),
                    color = TextMuted,
                    fontSize = 15.sp,
                    maxLines = 1,
                )
                if (info.synopsis.isNotBlank()) {
                    Spacer(Modifier.height(18.dp))
                    Text(
                        text = info.synopsis,
                        color = TextSub,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(28.dp))
                StatusLabel(error)
                Spacer(Modifier.height(14.dp))
                LoadingDots()
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(2.dp)
                .background(CyanPrimary),
        )
    }
}

@Composable
private fun StatusLabel(error: String?) {
    // v2.7.44 — border + background removed per user request.  Just text.
    val text = if (error.isNullOrBlank()) {
        "ON NOW TV V2 is loading your program"
    } else {
        "Could not start: $error"
    }
    Text(
        text = text,
        color = if (error.isNullOrBlank()) CyanPrimary else Color(0xFFFF8B7C),
        fontSize = 13.sp,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 1.6.sp,
    )
}

@Composable
private fun LoadingDots() {
    // v2.7.44 — slower (2.4 s / cycle) per user request.
    val infinite = rememberInfiniteTransition(label = "dots")
    val phase by infinite.animateFloat(
        initialValue = 0f, targetValue = 3f,
        animationSpec = infiniteRepeatable(
            tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "phase",
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (i in 0..2) {
            val active = (phase.toInt() % 3) == i
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (active) CyanPrimary else Color(0x335DC8FF)),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Mid-playback rebuffer spinner (NO full overlay)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun RebufferSpinner(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(Color(0xCC020610), RoundedCornerShape(20.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            color = CyanPrimary,
            strokeWidth = 2.5.dp,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = "Buffering",
            color = TextSub,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.2.sp,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Top status badge (BUF · ExoPlayer)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun TopStatusBadge(bufferAheadSec: Int, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Color(0xCC020610), RoundedCornerShape(6.dp))
            .border(1.dp, Color(0x4D5DC8FF), RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = "BUF ${bufferAheadSec}s  ·  ExoPlayer",
            color = CyanPrimary,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.2.sp,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// C01 — Classic Bottom Dock (D-pad navigable)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun ControlDock(
    info: PlayerInfo,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    bufferedMs: Long,
    hasAudio: Boolean,
    hasSubs: Boolean,
    hasStreams: Boolean,
    streamCount: Int = 0,
    hasNextEp: Boolean = false,
    nextEpThumbnailUrl: String = "",
    logoUrl: String = "",
    onPlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeekTo: (Long) -> Unit = {},
    onPickAudio: () -> Unit,
    onPickSubs: () -> Unit,
    onPickStream: () -> Unit,
    // v2.10.80 — Buffering Info sheet (left-dock, after Stream).
    onPickInfo: () -> Unit = {},
    onNextEp: () -> Unit = {},
    onClose: () -> Unit,
) {
    // Default focus → Play/Pause (center button) so D-pad center
    // immediately toggles playback when the dock appears.
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try { playFocus.requestFocus() } catch (_: Exception) {}
    }

    // v2.10.34 — Scrub debounce.
    //
    // The old "left/right press = immediate player.seekTo" loop made
    // every rapid scrub feel sluggish: each press triggered a buffer
    // flush + re-buffer, so 5 quick lefts cost ~5×~400 ms (≈ 2 s) of
    // visible stalling.  The user complaint was "it's taking too
    // long for it to re-pick where it's up to."
    //
    // New flow: while the user is holding/repeating LEFT or RIGHT on
    // the scrub bar, we mutate a local `pendingScrubMs` long.  The
    // playback bar paints from this pending value, so the user sees
    // instant visual feedback.  220 ms after the LAST keypress the
    // LaunchedEffect coroutine commits a SINGLE `onSeekTo(pending)`
    // call — one buffer flush, one re-buffer, regardless of how
    // many times the user pressed the key.
    //
    // v2.10.45 — Idle window tightened 500 → 220 ms.  User feedback:
    // "scrubbing isn't working as quick as it used to" — half a
    // second of dead air between releasing the key and the seek
    // firing read as lag.  220 ms still batches a held-key burst
    // (auto-repeat fires every ~50-100 ms) but commits almost
    // immediately after the user lets go.
    var pendingScrubMs by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(pendingScrubMs) {
        val target = pendingScrubMs ?: return@LaunchedEffect
        // Idle window — bumped by every fresh keypress because each
        // press re-fires this LaunchedEffect via the changed state.
        delay(220)
        onSeekTo(target)
        pendingScrubMs = null
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(360.dp)
            .background(
                Brush.verticalGradient(
                    listOf(
                        Color.Transparent,
                        Color(0x99020610),
                        Color(0xEB020610),
                    ),
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                // v2.10.37 — Bumped bottom padding from 40 → 64 dp so
                // the dock buttons clear projector / TV overscan
                // safe-zones.  User reported the pause button "getting
                // cut off a little bit at the bottom" — happens on
                // sets where the bottom 3-4 % of the screen is
                // physically masked.
                .padding(start = 64.dp, end = 64.dp, top = 40.dp, bottom = 64.dp),
        ) {
            // v2.10.35 — TMDB title logo above the heading.  Surface
            // it ONLY when the network resolution succeeded; until
            // then (typical first-launch cold cache is ~400 ms) we
            // just show the heading text so the user never sees an
            // empty placeholder.  Once loaded the logo fades in via
            // Coil's default crossfade.  Capped at 320×80 dp so even
            // wide wordmarks (like Star Wars or Game of Thrones)
            // don't dominate the dock or push the scrub bar down.
            if (logoUrl.isNotBlank()) {
                AsyncImage(
                    model = logoUrl,
                    contentDescription = info.title,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                    modifier = Modifier
                        .heightIn(min = 56.dp, max = 80.dp)
                        .widthIn(max = 360.dp),
                )
                Spacer(Modifier.height(10.dp))
            }
            // v2.10.35 — Heading bumped from 22 sp / SemiBold to
            // 30 sp / ExtraBold so it actually reads like a heading
            // when the user invokes the dock — old size looked
            // identical to the body line below.  Letter-spacing
            // tightened slightly so the wider weight stays elegant.
            Text(
                text = info.title,
                color = TextPrim,
                fontSize = 30.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.5).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(6.dp))
            val metaLine = buildList {
                if (info.year.isNotBlank()) add(info.year)
                if (info.runtime.isNotBlank()) add(info.runtime)
                if (info.rating.isNotBlank()) add(info.rating)
                add(info.quality)
                if (info.isEnglish) add("ENG")
                if (info.sizeGb > 0.5f) add("%.1f GB".format(info.sizeGb))
            }.joinToString("  ·  ")
            Text(
                text = metaLine,
                color = TextMuted,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.1.sp,
            )

            Spacer(Modifier.height(22.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // v2.10.34 — Reflect the pending scrub target
                    // here too so the timecode jumps in lock-step
                    // with the playhead the user is dragging.
                    text = formatMs(pendingScrubMs ?: positionMs),
                    color = if (pendingScrubMs != null) Color(0xFFFFC350) else CyanPrimary,
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    modifier = Modifier.width(96.dp),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 18.dp)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color(0x33FFFFFF))
                        // v2.10.27 — Make the scrub bar focusable so
                        // pressing D-pad UP from any dock button
                        // lands here and LEFT / RIGHT scrubs the
                        // playhead by 10 s.  The Box swells to 16 dp
                        // tall + a cyan ring when focused so the
                        // user gets a clear "I'm now controlling the
                        // bar" affordance.  DOWN automatically
                        // returns to the dock via Compose's spatial
                        // focus.
                        .focusable()
                        .onKeyEvent { ev ->
                            if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                            when (ev.key) {
                                Key.DirectionLeft -> {
                                    // v2.10.34 — Update the pending
                                    // scrub buffer instead of seeking
                                    // immediately.  Visual feedback
                                    // is instant via `playFrac` below;
                                    // the actual `onSeekTo` commits
                                    // 500 ms after the LAST keypress
                                    // (see the LaunchedEffect at the
                                    // top of ControlDock).
                                    val cur = pendingScrubMs ?: positionMs
                                    pendingScrubMs = (cur - 10_000L).coerceIn(0L, durationMs)
                                    true
                                }
                                Key.DirectionRight -> {
                                    val cur = pendingScrubMs ?: positionMs
                                    pendingScrubMs = (cur + 10_000L).coerceIn(0L, durationMs)
                                    true
                                }
                                Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                                    // OK while scrubbing commits the
                                    // current pending position
                                    // immediately, then toggles play.
                                    pendingScrubMs?.let {
                                        onSeekTo(it)
                                        pendingScrubMs = null
                                    }
                                    onPlayPause(); true
                                }
                                else -> false
                            }
                        },
                ) {
                    val total = (durationMs.coerceAtLeast(1L)).toFloat()
                    val bufFrac =
                        (bufferedMs.coerceAtLeast(0L).toFloat() / total).coerceIn(0f, 1f)
                    // v2.10.34 — Paint from the pending scrub buffer
                    // when the user is actively pressing left/right,
                    // so the bar follows their input frame-perfect.
                    val displayPos = pendingScrubMs ?: positionMs
                    val playFrac =
                        (displayPos.coerceAtLeast(0L).toFloat() / total).coerceIn(0f, 1f)
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(bufFrac)
                            .background(Color(0x665DC8FF)),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(playFrac)
                            .background(CyanPrimary),
                    )
                }
                Text(
                    text = formatMs(durationMs),
                    color = Color(0xFF94A3B8),
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.2.sp,
                    modifier = Modifier.width(96.dp),
                )
            }

            Spacer(Modifier.height(26.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // LEFT cluster: Audio / Subs / Stream picker / Info
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    DockButton(
                        Icons.Default.Audiotrack,
                        "Audio",
                        enabled = hasAudio,
                        onClick = onPickAudio,
                    )
                    DockButton(
                        Icons.Default.Subtitles,
                        "Subtitles",
                        onClick = onPickSubs,
                    )
                    /* v2.11.2 — CHOOSE LINKS emphasized.
                     *
                     * Operator's #1 recurring pain: "the EXO Player
                     * isn't showing the section so I can choose what
                     * link to watch."  In v2.10.96 we added the
                     * button but rendered it as a plain grey circle
                     * with a generic icon — indistinguishable at 3 m
                     * from the surrounding Audio / Subtitles /
                     * Info circles.  Users literally couldn't spot
                     * it in the row of five icons.
                     *
                     * The fix: wrap it in a cyan-bordered pill with
                     * the stream count front-and-centre.  Renders as
                     * a proper "chip" instead of an icon among icons.
                     * Also fires a brief 3-second glow-pulse when
                     * the player first appears with >1 stream, so
                     * the user's eye is drawn to it BEFORE they hunt
                     * for it.  When there's only 1 stream the chip
                     * is hidden entirely (not just disabled). */
                    if (hasStreams) {
                        StreamPickerChip(
                            count = streamCount,
                            onClick = onPickStream,
                        )
                    }
                    // v2.10.80 — (i) Buffering Diagnostics button.
                    // Always enabled — even when streams=1 the user
                    // wants to see the buffer-ahead seconds so they
                    // can judge whether the current link is healthy.
                    DockButton(
                        Icons.Default.Info,
                        "Info",
                        onClick = onPickInfo,
                    )
                }
                // CENTER cluster: Back10 / PLAY-PAUSE (focused) / Fwd10
                Row(
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DockButton(
                        Icons.Default.Replay10,
                        "Back 10s",
                        onClick = { onSeekBy(-10_000) },
                    )
                    DockButton(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        if (isPlaying) "Pause" else "Play",
                        large  = true,
                        // v2.10.37 — User feedback: "I don't want
                        // the play button to be fully highlighted
                        // all the time."  Dropped `active = true`
                        // so the play/pause button matches the
                        // other dock buttons (glassy translucent
                        // default, bright cyan ring only on focus).
                        focusRequester = playFocus,
                        onClick = onPlayPause,
                    )
                    DockButton(
                        Icons.Default.Forward10,
                        "Forward 10s",
                        onClick = { onSeekBy(10_000) },
                    )
                }
                // RIGHT cluster: optional next-episode thumbnail +
                // "PLAY NEXT EPISODE" pill (series only, ≤120 s from
                // credits).  v2.10.34 — Added the thumbnail strip
                // just before the pill so the user knows exactly
                // which episode is about to play before clicking.
                // The thumbnail URL comes from metahub's deterministic
                // episode-image CDN and is wired through from the
                // activity (see `nextEpThumbnailFlow`).
                //
                // The previous trio of CC / NextEp(small) / Fullscreen
                // was removed in v2.10.30 — Subtitles already live in
                // the LEFT cluster, Cast was never implemented, and
                // the player is always fullscreen.
                //
                // The widthIn(min) ensures the right slot reserves
                // visual width even when the pill isn't showing, so
                // the centre play/pause cluster stays anchored.
                Box(
                    modifier = Modifier.widthIn(min = 380.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    if (hasNextEp) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            if (nextEpThumbnailUrl.isNotBlank()) {
                                NextEpisodeThumbnail(url = nextEpThumbnailUrl)
                            }
                            NextEpisodePill(onClick = onNextEp)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Big "PLAY NEXT EPISODE" call-to-action pill that lives inside the
 * right slot of the bottom dock during the ≤60 s pre-credits window.
 *
 * Design intent (per user direction Feb 10 2026):
 *   • Inside the dock (not floating above), so it shares the same
 *     focus row as the other controls — D-pad Right from the
 *     play/pause centre lands on it directly.
 *   • Auto-grabs focus when it appears so the user can hit OK
 *     immediately without navigating to it.
 *   • Highlight must be unmistakable when focused — bright cyan
 *     fill, 4 dp glowing border, monospaced uppercase label, and
 *     a subtle scale animation so it pops against the smaller
 *     circular dock buttons.  The user explicitly called out that
 *     the previous small-icon variant looked the same focused or
 *     not, which is why we made the whole pill swap colours.
 */
@Composable
private fun NextEpisodePill(onClick: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }

    // Auto-focus removed in v2.10.33 per user feedback — they didn't
    // want the pill to steal focus from whatever they were on (e.g.
    // mid-scrub).  D-pad Right from the centre Play/Pause now lands
    // on the pill exactly when the user asks for it, not before.

    // Pulsing glow halo when focused.  Even at rest the pill is
    // visually loud (cyan fill) — the pulse just confirms "yes,
    // this is what's selected".  Period chosen long enough to feel
    // calm but short enough to be perceptible without distracting
    // from the credits playing behind.
    val pulseTransition = rememberInfiniteTransition(label = "next-ep-pulse")
    val pulse by pulseTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "next-ep-pulse-frac",
    )

    val bg = if (focused) CyanPrimary else Color(0xFF0B2A3D)
    val fg = if (focused) NavyDeep else CyanPrimary
    val borderColor = if (focused) {
        // Bright cyan, slightly oscillating opacity for the glow
        // effect.  We deliberately keep it ON the brand cyan rather
        // than going white — sticks to the established palette.
        Color(0xFF5DC8FF).copy(alpha = 0.55f + 0.45f * pulse)
    } else {
        Color(0x665DC8FF)
    }
    val borderWidth = if (focused) 4.dp else 2.dp
    val scale = if (focused) 1.06f else 1.0f

    Row(
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .focusable(enabled = true)
            .onKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown
                    && (ev.key == Key.Enter
                        || ev.key == Key.DirectionCenter
                        || ev.key == Key.NumPadEnter)
                ) { onClick(); true } else false
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .clip(RoundedCornerShape(28.dp))
            .background(bg)
            .border(borderWidth, borderColor, RoundedCornerShape(28.dp))
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Default.SkipNext,
            contentDescription = "Play next episode",
            tint = fg,
            modifier = Modifier.size(26.dp),
        )
        Text(
            text = "PLAY NEXT EPISODE",
            color = fg,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
        )
    }
}

/**
 * v2.10.34 — Small still image (≈16:9 thumbnail) of the upcoming
 * episode rendered just before the PLAY NEXT EPISODE pill so the
 * user can visually verify which episode is about to play before
 * clicking.
 *
 * URL is the deterministic metahub episode CDN
 * (`https://episodes.metahub.space/{imdb}/{S}/{E}/w780.jpg`) — same
 * data source the React layer already uses, so an episode that
 * appears in SeriesEpisodes.jsx is guaranteed to resolve here too.
 *
 * Sized roughly 64×96 (16:9 at 96 px wide) to match the pill's
 * vertical footprint without dominating the dock.  A thin cyan
 * border + rounded corners visually tether it to the pill.
 */
@Composable
private fun NextEpisodeThumbnail(url: String) {
    Box(
        modifier = Modifier
            .height(54.dp)
            .width(96.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, Color(0x665DC8FF), RoundedCornerShape(6.dp))
            .background(Color(0xFF06080F)),
    ) {
        AsyncImage(
            model = url,
            contentDescription = "Next episode thumbnail",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
}

/**
 * v2.11.2 — StreamPickerChip.  Cyan-bordered pill that surfaces
 * how many alternate streams are available and opens the picker
 * sheet on tap.  Made visually distinct from the surrounding
 * icon-only DockButtons so the user can spot it at 3 m from the TV.
 *
 * Fires a 3-second glow pulse on first appearance so the user's
 * eye is drawn to it before they hunt for it — after that the pulse
 * stops and the chip just holds a subtle cyan border.
 */
@Composable
private fun StreamPickerChip(count: Int, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    // 3-second attention pulse on first mount only.
    var pulseOn by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(3_000)
        pulseOn = false
    }
    val infinite = androidx.compose.animation.core.rememberInfiniteTransition(label = "streamPulse")
    val pulseAlpha by infinite.animateFloat(
        initialValue = 0.55f,
        targetValue  = 1.0f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            animation = androidx.compose.animation.core.tween(
                durationMillis = 900,
                easing = androidx.compose.animation.core.FastOutSlowInEasing,
            ),
            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "streamPulseAlpha",
    )
    val borderAlpha = if (pulseOn) pulseAlpha else 0.55f

    val bg = when {
        focused -> CyanPrimary
        else    -> Color(0x1A5DC8FF)  // subtle cyan tint
    }
    val fg = when {
        focused -> NavyDeep
        else    -> Color(0xFF8de0ff)
    }
    val border = if (focused)
        CyanPrimary
    else
        CyanPrimary.copy(alpha = borderAlpha)

    Row(
        modifier = Modifier
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(bg)
            .border(
                width = if (focused) 3.dp else 2.dp,
                color = border,
                shape = RoundedCornerShape(28.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown
                    && (ev.key == Key.Enter ||
                        ev.key == Key.DirectionCenter ||
                        ev.key == Key.NumPadEnter)
                ) { onClick(); true } else false
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Default.PlaylistPlay,
            contentDescription = "Choose Links",
            tint = fg,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = "CHOOSE LINKS",
            color = fg,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.6.sp,
        )
        if (count > 1) {
            Box(
                modifier = Modifier
                    .height(22.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (focused) NavyDeep.copy(alpha = 0.35f) else Color(0x335DC8FF))
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = count.toString(),
                    color = fg,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}


 * border + rounded corners visually tether it to the pill.
 */
@Composable
private fun NextEpisodeThumbnail(url: String) {
    Box(
        modifier = Modifier
            .height(54.dp)
            .width(96.dp)
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, Color(0x665DC8FF), RoundedCornerShape(6.dp))
            .background(Color(0xFF06080F)),
    ) {
        AsyncImage(
            model = url,
            contentDescription = "Next episode thumbnail",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
private fun DockButton(
    icon: ImageVector,
    contentDescription: String,
    large: Boolean = false,
    active: Boolean = false,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val sz = if (large) 80.dp else 56.dp
    val iconSz = if (large) 40.dp else 26.dp
    val bg = when {
        !enabled -> Color(0x0DFFFFFF)
        focused  -> CyanPrimary
        active   -> CyanPrimary
        else     -> Color(0x1AFFFFFF)
    }
    val fg = when {
        !enabled -> Color(0x66FFFFFF)
        focused || active -> NavyDeep
        else -> TextPrim
    }
    val borderColor =
        if (focused) CyanPrimary
        else if (active) Color.Transparent
        else Color(0x33FFFFFF)

    var mod: Modifier = Modifier
        .size(sz)
        .clip(CircleShape)
        .background(bg)
        .border(
            width = if (focused) 3.dp else 1.dp,
            color = borderColor,
            shape = CircleShape,
        )
    if (focusRequester != null) {
        mod = mod.focusRequester(focusRequester)
    }
    mod = mod
        .onFocusChanged { focused = it.isFocused }
        .focusable(enabled = enabled)
        .onKeyEvent { ev ->
            if (ev.type == KeyEventType.KeyDown
                && (ev.key == Key.Enter ||
                    ev.key == Key.DirectionCenter ||
                    ev.key == Key.NumPadEnter)
                && enabled
            ) { onClick(); true } else false
        }
        .clickable(
            enabled = enabled,
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        )

    Box(modifier = mod, contentAlignment = Alignment.Center) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = fg,
            modifier = Modifier.size(iconSz),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Track picker sheet (Audio / Subtitles)
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun TrackPickerSheet(
    title: String,
    options: List<TrackOption>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try { firstFocus.requestFocus() } catch (_: Exception) {}
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE6020610))
            .onKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown
                    && (ev.key == Key.Back || ev.key == Key.Escape)
                ) { onDismiss(); true } else false
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.width(520.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = title.uppercase(),
                color = CyanPrimary,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 3.sp,
            )
            Spacer(Modifier.height(28.dp))
            if (options.isEmpty()) {
                Text(
                    "No tracks available",
                    color = TextMuted,
                    fontSize = 14.sp,
                )
            } else {
                options.forEachIndexed { i, opt ->
                    TrackRow(
                        label = opt.label,
                        selected = opt.selected,
                        focusRequester = if (i == 0) firstFocus else null,
                        onClick = { onPick(opt.id) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "Press BACK to close",
                color = TextMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.4.sp,
            )
        }
    }
}

@Composable
private fun TrackRow(
    label: String,
    selected: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused  -> Color(0x335DC8FF)
        selected -> Color(0x1A5DC8FF)
        else     -> Color(0x14FFFFFF)
    }
    val border = if (focused) CyanPrimary else if (selected) Color(0x665DC8FF) else Color(0x22FFFFFF)
    var mod: Modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(10.dp))
        .background(bg)
        .border(if (focused) 2.dp else 1.dp, border, RoundedCornerShape(10.dp))
    if (focusRequester != null) {
        mod = mod.focusRequester(focusRequester)
    }
    mod = mod
        .onFocusChanged { focused = it.isFocused }
        .focusable()
        .onKeyEvent { ev ->
            if (ev.type == KeyEventType.KeyDown
                && (ev.key == Key.Enter ||
                    ev.key == Key.DirectionCenter ||
                    ev.key == Key.NumPadEnter)
            ) { onClick(); true } else false
        }
        .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        )
        .padding(horizontal = 18.dp, vertical = 14.dp)

    Row(modifier = mod, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            color = TextPrim,
            fontSize = 16.sp,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Text(
                text = "● ACTIVE",
                color = CyanPrimary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.4.sp,
            )
        }
    }
}

/**
 * v2.10.80 — Buffering Info bottom-sheet.
 *
 * Tap (i) in the dock to surface this card.  Shows the live
 * buffer-ahead (in seconds), buffered-percent of the loaded
 * portion, and current bitrate — the three numbers that tell the
 * user at a glance whether the current stream is healthy.
 */
@Composable
private fun BufferingInfoSheet(
    bufferAheadMs: Long,
    bufferedPercent: Int,
    bitrateKbps: Long,
    onDismiss: () -> Unit,
) {
    val dismissFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try { dismissFocus.requestFocus() } catch (_: Exception) {}
    }
    val bufferSec = (bufferAheadMs / 1000L).toInt()
    val healthyThreshold = 30
    val (bufferColor, bufferAdvice) = when {
        bufferSec >= 60 -> Pair(
            Color(0xFF7AEB8A),
            "Excellent — this stream is comfortable, sit back.",
        )
        bufferSec >= healthyThreshold -> Pair(
            Color(0xFF7AEB8A),
            "Healthy — playback should be smooth.",
        )
        bufferSec >= 15 -> Pair(
            Color(0xFFFFD54F),
            "Borderline — watch for stalls; consider another link.",
        )
        else -> Pair(
            Color(0xFFFF6B6B),
            "Low — playback may stall.  Open Stream picker → pick another link.",
        )
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE6020610))
            .onKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown
                    && (ev.key == Key.Back || ev.key == Key.Escape)
                ) { onDismiss(); true } else false
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(620.dp)
                .background(
                    Color(0xFF0B1220),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                )
                .padding(28.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Text(
                "Buffering diagnostics",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Live numbers for the stream currently playing.",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(22.dp))

            // ── Big number: buffer ahead in seconds ──
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    bufferSec.toString(),
                    color = bufferColor,
                    fontSize = 64.sp,
                    fontWeight = FontWeight.Black,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "s ahead",
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 14.dp),
                )
            }
            Spacer(Modifier.height(2.dp))
            Text(
                bufferAdvice,
                color = bufferColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )

            Spacer(Modifier.height(22.dp))

            // ── Secondary numbers row ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                BufferingStat(label = "Buffered", value = "${bufferedPercent}%")
                BufferingStat(
                    label = "Bitrate",
                    value = if (bitrateKbps > 0) "${bitrateKbps} kbps" else "—",
                )
                BufferingStat(label = "Target", value = "${healthyThreshold}s+")
            }

            Spacer(Modifier.height(22.dp))

            // ── Plain-English explainer ──
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Color(0xFF11192B),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                    )
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    "What does this mean?",
                    color = Color(0xFF5DC8FF),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.4.sp,
                )
                Text(
                    "The big number is how many seconds of video have been downloaded " +
                        "AHEAD of where you're watching.  The higher, the safer.",
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
                Text(
                    "Above ${healthyThreshold}s = comfortable — no stutters likely.\n" +
                        "Below ${healthyThreshold}s = borderline — the stream may stall.  " +
                        "Tap the Stream button next to me, pick a different link " +
                        "(EasyNews++ direct streams or Torrentio debrid-cached ones " +
                        "usually buffer fastest).",
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                )
            }

            Spacer(Modifier.height(20.dp))

            // ── Dismiss pill ──
            Box(
                modifier = Modifier
                    .focusRequester(dismissFocus)
                    .focusable()
                    .clickable { onDismiss() }
                    .background(
                        Color(0xFF5DC8FF),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(999.dp),
                    )
                    .padding(horizontal = 24.dp, vertical = 10.dp),
            ) {
                Text(
                    "Got it",
                    color = Color(0xFF020610),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                )
            }
        }
    }
}

@Composable
private fun BufferingStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            label.uppercase(),
            color = Color.White.copy(alpha = 0.5f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}


@Composable
private fun StreamPickerSheet(
    streams: List<StreamOption>,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try { firstFocus.requestFocus() } catch (_: Exception) {}
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xE6020610))
            .onKeyEvent { ev ->
                if (ev.type == KeyEventType.KeyDown
                    && (ev.key == Key.Back || ev.key == Key.Escape)
                ) { onDismiss(); true } else false
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.width(720.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "CHOOSE LINKS",
                color = CyanPrimary,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 3.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (streams.isEmpty())
                    "No alternate streams"
                else
                    "${streams.size} sources · D-pad UP/DOWN · OK to switch",
                color = TextMuted,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(28.dp))
            if (streams.isEmpty()) {
                Text(
                    "No alternate streams",
                    color = TextMuted,
                    fontSize = 14.sp,
                )
            } else {
                streams.forEachIndexed { i, s ->
                    StreamRow(
                        stream = s,
                        focusRequester = if (i == 0) firstFocus else null,
                        onClick = { onPick(s.idx) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                "Press BACK to close",
                color = TextMuted,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.4.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Stream picker row — title + addon-source chip + cached chip + ENG chip
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun StreamRow(
    stream: StreamOption,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused         -> Color(0x335DC8FF)
        stream.selected -> Color(0x1A5DC8FF)
        else            -> Color(0x14FFFFFF)
    }
    val border =
        if (focused) CyanPrimary
        else if (stream.selected) Color(0x665DC8FF)
        else Color(0x22FFFFFF)
    var mod: Modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(10.dp))
        .background(bg)
        .border(if (focused) 2.dp else 1.dp, border, RoundedCornerShape(10.dp))
    if (focusRequester != null) {
        mod = mod.focusRequester(focusRequester)
    }
    mod = mod
        .onFocusChanged { focused = it.isFocused }
        .focusable()
        .onKeyEvent { ev ->
            if (ev.type == KeyEventType.KeyDown
                && (ev.key == Key.Enter ||
                    ev.key == Key.DirectionCenter ||
                    ev.key == Key.NumPadEnter)
            ) { onClick(); true } else false
        }
        .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        )
        .padding(horizontal = 18.dp, vertical = 14.dp)

    Column(modifier = mod) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stream.label.lineSequence().firstOrNull() ?: stream.label,
                color = TextPrim,
                fontSize = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (stream.selected) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "● CURRENT",
                    color = CyanPrimary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.4.sp,
                )
            }
        }
        // Chip row — only render when at least one chip is non-empty.
        val hasAnyChip = stream.addonSource.isNotBlank() ||
                         stream.quality.isNotBlank() ||
                         stream.pmCached ||
                         stream.isEnglish
        if (hasAnyChip) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (stream.addonSource.isNotBlank()) {
                    Chip(
                        text = stream.addonSource,
                        bg = Color(0x245DC8FF),
                        border = Color(0x4D5DC8FF),
                        fg = CyanPrimary,
                    )
                }
                if (stream.quality.isNotBlank()) {
                    Chip(
                        text = stream.quality,
                        bg = Color(0x1AFFFFFF),
                        border = Color(0x33FFFFFF),
                        fg = TextPrim,
                    )
                }
                if (stream.pmCached) {
                    Chip(
                        text = "⚡ CACHED",
                        bg = Color(0x297AEB8A),
                        border = Color(0x597AEB8A),
                        fg = Color(0xFF7AEB8A),
                    )
                }
                if (stream.isEnglish) {
                    Chip(
                        text = "🇬🇧 ENG",
                        bg = Color(0x14FFFFFF),
                        border = Color(0x26FFFFFF),
                        fg = TextSub,
                    )
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, bg: Color, border: Color, fg: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(4.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            color = fg,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Utilities
// ─────────────────────────────────────────────────────────────────────────────

private fun formatMs(ms: Long): String {
    if (ms <= 0) return "00:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%02d:%02d:%02d".format(h, m, s)
           else "%02d:%02d".format(m, s)
}

@Composable
private fun <T> collectAsStateSafe(
    flow: StateFlow<T>,
    initial: T,
): androidx.compose.runtime.State<T> {
    val state = remember { androidx.compose.runtime.mutableStateOf(initial) }
    LaunchedEffect(flow) { flow.collect { state.value = it } }
    return state
}

// ─────────────────────────────────────────────────────────────────────────────
// v2.7.60 — Native Watch Together voice dock + bubbles
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun PartyVoiceLayer(
    manager: PartyVoiceManager,
    onActivity: () -> Unit,           // explicit "show player chrome"
    modifier: Modifier = Modifier,
) {
    val members by collectAsStateSafe(manager.members, emptyList())
    val bubbles by collectAsStateSafe(manager.bubbles, emptyList())
    val reactions by collectAsStateSafe(manager.reactions, emptyList())
    val recState by collectAsStateSafe(manager.recState, PartyVoiceManager.RecState.Idle)
    val lastError by collectAsStateSafe(manager.lastError, "")

    Box(modifier = modifier) {
        bubbles.forEachIndexed { i, b -> VoiceBubbleCard(b, index = i) }

        // v2.7.67 — Floating emoji reactions (right edge → drift up).
        reactions.forEach { r -> FloatingReaction(reaction = r) }

        PartyVoiceDockRow(
            manager = manager,
            members = members,
            recState = recState,
            onOpenChrome = onActivity,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 36.dp, bottom = 56.dp),
        )

        if (recState != PartyVoiceManager.RecState.Idle) {
            StatusPill(
                state = recState,
                errorText = lastError,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 36.dp, bottom = 116.dp),
            )
        }
    }
}

@Composable
private fun PartyVoiceDockRow(
    manager: PartyVoiceManager,
    members: List<PartyVoiceManager.Member>,
    recState: PartyVoiceManager.RecState,
    onOpenChrome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = members.take(4)
    val firstFocus = remember { FocusRequester() }
    var pressing by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(40.dp))
            .background(Color(0xB3080E1A))
            .border(1.dp, Color(0x4D5DC8FF), RoundedCornerShape(40.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        visible.forEachIndexed { i, m ->
            val isSelf = m.id == manager.selfMemberIdValue
            DockAvatar(
                member = m,
                isSelf = isSelf,
                isRecording = isSelf && recState == PartyVoiceManager.RecState.Recording,
                pressing = isSelf && pressing,
                focusRequester = if (i == 0) firstFocus else null,
                // v2.7.63 — Holding OK on the avatar ONLY records.
                // It does NOT bump the player-chrome auto-hide timer
                // any more, so the Play/Pause/Audio control deck no
                // longer pops up when you mean to talk.
                onHoldStart = if (isSelf) {{
                        pressing = true; manager.startRecording()
                }} else null,
                onHoldEnd = if (isSelf) {{
                        pressing = false; manager.stopRecording()
                }} else null,
            )
        }
        DockMenuButton(onOpenChrome = onOpenChrome)
    }

    LaunchedEffect(visible.isNotEmpty()) {
        if (visible.isNotEmpty()) {
            kotlinx.coroutines.delay(280)
            try { firstFocus.requestFocus() } catch (_: Exception) {}
        }
    }
}

@Composable
private fun DockAvatar(
    member: PartyVoiceManager.Member,
    isSelf: Boolean,
    isRecording: Boolean,
    pressing: Boolean,
    focusRequester: FocusRequester?,
    onHoldStart: (() -> Unit)?,
    onHoldEnd: (() -> Unit)?,
) {
    var focused by remember { mutableStateOf(false) }
    val scale = when {
        pressing    -> 0.94f
        focused     -> 1.08f
        isRecording -> 1.05f
        else        -> 1f
    }
    val borderColor = when {
        isRecording -> Color(0xFFFF5050)
        focused     -> CyanPrimary
        else        -> Color(0x55FFFFFF)
    }
    val interactive = onHoldStart != null

    var mod: Modifier = Modifier
        .size(52.dp)
        .clip(CircleShape)
        .background(parseAvatarBg(member.avatar))
        .border(if (focused || isRecording) 2.dp else 1.dp, borderColor, CircleShape)
    if (focusRequester != null) {
        mod = mod.focusRequester(focusRequester)
    }
    if (interactive) {
        mod = mod
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onKeyEvent { ev ->
                val isOkKey = (ev.key == Key.Enter ||
                               ev.key == Key.DirectionCenter ||
                               ev.key == Key.NumPadEnter)
                if (isOkKey) {
                    when (ev.type) {
                        KeyEventType.KeyDown -> { onHoldStart?.invoke(); true }
                        KeyEventType.KeyUp   -> { onHoldEnd?.invoke();   true }
                        else                 -> false
                    }
                } else false
            }
    }

    Box(
        modifier = mod.then(
            Modifier.graphicsLayer {
                scaleX = scale; scaleY = scale
            }
        ),
        contentAlignment = Alignment.Center,
    ) {
        val glyph = member.avatarEmoji.ifBlank {
            member.name.firstOrNull()?.toString() ?: "?"
        }
        Text(
            text = glyph,
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        if (isSelf) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(if (isRecording) Color(0xFFFF5050) else Color(0xCC0B1322))
                    .border(1.5.dp, CyanPrimary, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "🎤", fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun DockMenuButton(onOpenChrome: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale = if (focused) 1.08f else 1f
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(Color(0xD90B1322))
            .border(
                width = if (focused) 2.dp else 1.dp,
                color = if (focused) CyanPrimary else Color(0x885DC8FF),
                shape = CircleShape,
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onKeyEvent { ev ->
                val isOkKey = (ev.key == Key.Enter ||
                               ev.key == Key.DirectionCenter ||
                               ev.key == Key.NumPadEnter)
                if (isOkKey && ev.type == KeyEventType.KeyDown) {
                    // v2.7.63 — Menu button NOW actually shows the
                    // player chrome (Play/Pause/Audio/Subs/Stream
                    // control deck).  Previously it just bumped the
                    // activity timer with no other effect, so
                    // pressing it appeared to do nothing.
                    onOpenChrome()
                    true
                } else false
            }
            .then(
                Modifier.graphicsLayer {
                    scaleX = scale; scaleY = scale
                }
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "☰", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StatusPill(
    state: PartyVoiceManager.RecState,
    errorText: String = "",
    modifier: Modifier = Modifier,
) {
    // v2.7.65 — error variant is now a big multi-line panel so the
    // diagnostic text (HTTP code / exception class) is readable from
    // across the room and survives a phone-camera capture.  Non-error
    // states keep the original compact pill look.
    if (state == PartyVoiceManager.RecState.Error ||
        state == PartyVoiceManager.RecState.Blocked) {
        val isBlocked = state == PartyVoiceManager.RecState.Blocked
        val detail = errorText.trim().ifBlank {
            if (isBlocked) "MIC BLOCKED" else "TRY AGAIN"
        }
        val headerLabel = if (isBlocked) "MICROPHONE BLOCKED" else "VOICE ERROR"
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xF2B91C1C))
                .border(2.dp, Color.White, RoundedCornerShape(14.dp))
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) {
            Column {
                Text(
                    text = headerLabel,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.6.sp,
                )
                androidx.compose.foundation.layout.Spacer(Modifier.height(4.dp))
                Text(
                    text = detail,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        return
    }
    val (label, bg) = when (state) {
        PartyVoiceManager.RecState.Recording     -> "● LISTENING…"    to Color(0xE6FF5050)
        PartyVoiceManager.RecState.Transcribing  -> "⟳ TRANSCRIBING…" to Color(0xE60B1322)
        PartyVoiceManager.RecState.Blocked,
        PartyVoiceManager.RecState.Idle,
        PartyVoiceManager.RecState.Error         -> "" to Color.Transparent
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .border(1.dp, Color(0x665DC8FF), RoundedCornerShape(20.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
        )
    }
}

@Composable
private fun VoiceBubbleCard(bubble: PartyVoiceManager.VoiceBubble, index: Int) {
    // v2.7.68 — Anchor bubbles to the BOTTOM-RIGHT, stacked just
    // above the avatar voice dock so it's obvious who's talking.
    // Stagger upward as more bubbles arrive (each one ~96 dp higher).
    val laneOffset = (130 + (index % 4) * 90).dp
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 36.dp, bottom = laneOffset)
                .widthIn(min = 200.dp, max = 360.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(Color(0xEB0B1322), Color(0xEB142846)),
                    ),
                )
                .border(1.dp, Color(0x735DC8FF), RoundedCornerShape(18.dp))
                .padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.End,
        ) {
            val avatarPrefix = if (bubble.senderAvatarEmoji.isNotBlank())
                "${bubble.senderAvatarEmoji}  " else ""
            val label = if (bubble.mine) "You" else bubble.senderName.ifBlank { "Voice" }
            Text(
                text = "$avatarPrefix$label",
                color = CyanPrimary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.6.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = bubble.text,
                color = Color.White,
                fontSize = 16.sp,
                lineHeight = 22.sp,
            )
        }
    }
}

// v2.7.69 — Floating emoji reaction.  Anchors to the right edge and
// drifts up slowly so reactions linger and stack instead of vanishing.
// Lane (0..6) staggers horizontally so back-to-back taps don't pile on
// the same pixel column.
@Composable
private fun FloatingReaction(reaction: PartyVoiceManager.Reaction) {
    val anim = remember(reaction.id) { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(reaction.id) {
        anim.animateTo(
            targetValue = 1f,
            animationSpec = androidx.compose.animation.core.tween(
                // v2.7.69 — was 3000 ms.  User asked the emojis to
                // "slowly float up the screen" so we stretch the
                // animation to 7 s, hold full opacity for the first
                // 75 %, then fade out across the last 25 %.
                durationMillis = 7000,
                easing = androidx.compose.animation.core.LinearEasing,
            ),
        )
    }
    val progress = anim.value
    val laneOffset = (reaction.lane % 7) * 28
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = (80 + laneOffset).dp)
                .offset(
                    // Travel ~720 dp upwards (roughly 80 % of a 1080p
                    // screen on TV) over the full 7 s.
                    y = (-220 - (progress * 720f)).dp,
                )
                .graphicsLayer {
                    // Hold full opacity for 0.0–0.75, then fade out
                    // smoothly to 0 across 0.75–1.0.
                    val fadeStart = 0.75f
                    val alphaCalc = if (progress < fadeStart) 1f
                                    else 1f - ((progress - fadeStart) / (1f - fadeStart))
                    alpha = alphaCalc.coerceIn(0f, 1f)
                    val scale = 0.92f + progress * 0.18f
                    scaleX = scale
                    scaleY = scale
                },
        ) {
            Text(
                text = reaction.emoji,
                fontSize = 56.sp,
            )
        }
    }
}



private fun parseAvatarBg(avatarId: String): Color {
    val hash = avatarId.hashCode()
    val palette = listOf(
        Color(0xFF1B3A6E),
        Color(0xFF5D2C8A),
        Color(0xFF2A8060),
        Color(0xFF8A492C),
        Color(0xFF6E1B40),
        Color(0xFF1B6E68),
        Color(0xFF8A6C2C),
        Color(0xFF2C508A),
    )
    return palette[((hash % palette.size) + palette.size) % palette.size]
}



// ─────────────────────────────────────────────────────────────────────────────
// v2.7.73 — Watch Together left-side host menu (slide-in drawer)
//
// Toggled by KEYCODE_MENU (handled in ExoPlayerActivity.dispatchKeyEvent).
// Closed by BACK or another MENU press.  When closed, the drawer is
// fully out-of-tree so spatial focus can't trap into it.
// ─────────────────────────────────────────────────────────────────────────────
@Composable
private fun PartyHostDrawer(
    manager: PartyVoiceManager,
    openFlow: StateFlow<Boolean>,
    role: String,                                  // "host" or "guest"
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onCatchUp: (Long) -> Unit,
    onOpenSubs: () -> Unit,
    onOpenAudio: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val open by collectAsStateSafe(openFlow, false)
    val hostPos by collectAsStateSafe(manager.hostPositionMs, 0L)
    val firstBtnFocus = remember { FocusRequester() }

    AnimatedVisibility(
        visible  = open,
        enter    = slideInHorizontally(
            initialOffsetX = { -it },
            animationSpec  = tween(220),
        ) + fadeIn(tween(220)),
        exit     = slideOutHorizontally(
            targetOffsetX = { -it },
            animationSpec = tween(200),
        ) + fadeOut(tween(200)),
        modifier = modifier,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Semi-transparent backdrop so the player dims behind it
            // without going fully black (so the user can still see
            // what's playing).
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0x66000000)),
            )
            // The drawer itself — a slim vertical strip on the LEFT.
            Column(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(124.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color(0xF20B1322), Color(0xCC0B1322)),
                        ),
                    )
                    .border(
                        width = 1.dp,
                        brush = Brush.verticalGradient(
                            listOf(Color(0x445DC8FF), Color(0x115DC8FF)),
                        ),
                        shape = RoundedCornerShape(0.dp),
                    )
                    .padding(vertical = 28.dp, horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = if (role == "host") "HOST" else "GUEST",
                    color = CyanPrimary,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.8.sp,
                )
                Spacer(Modifier.height(4.dp))

                // 1) Play / Pause — both roles.
                DrawerButton(
                    icon  = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    label = if (isPlaying) "PAUSE" else "PLAY",
                    onClick = onPlayPause,
                    focusRequester = firstBtnFocus,
                )

                // 2) Catch up — guest only.  Hidden when host.
                if (role != "host") {
                    DrawerButton(
                        icon  = Icons.Filled.Forward10,
                        label = "CATCH UP",
                        onClick = { onCatchUp(hostPos) },
                    )
                }

                // 3) Subtitles — both roles.
                DrawerButton(
                    icon  = Icons.Filled.ClosedCaption,
                    label = "SUBS",
                    onClick = onOpenSubs,
                )

                // 4) Audio track — both roles.
                DrawerButton(
                    icon  = Icons.Filled.Audiotrack,
                    label = "AUDIO",
                    onClick = onOpenAudio,
                )
            }
        }
        // Auto-focus the first button so D-pad navigation engages
        // the moment the drawer slides in.
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(240)
            try { firstBtnFocus.requestFocus() } catch (_: Exception) {}
        }
    }
}

@Composable
private fun DrawerButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = if (focused) Color(0xFF1A3357) else Color(0xCC0F1A30)
    val borderColor = if (focused) CyanPrimary else Color(0x445DC8FF)
    var mod: Modifier = Modifier
        .width(100.dp)
        .height(82.dp)
        .clip(RoundedCornerShape(12.dp))
        .background(bg)
        .border(if (focused) 2.dp else 1.dp, borderColor, RoundedCornerShape(12.dp))
    if (focusRequester != null) mod = mod.focusRequester(focusRequester)
    mod = mod
        .onFocusChanged { focused = it.isFocused }
        .focusable()
        .onKeyEvent { ev ->
            if (ev.type == KeyEventType.KeyDown &&
                (ev.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                 ev.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_ENTER)) {
                onClick(); true
            } else false
        }
    Column(
        modifier = mod,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = if (focused) Color.White else Color(0xFFB8D9F2),
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            color = if (focused) Color.White else Color(0xFF94B8D6),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.4.sp,
        )
    }
}
