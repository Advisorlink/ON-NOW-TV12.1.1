package tv.vesper.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

/**
 * v2.16.42 — Full-screen engine picker sheet triggered by the
 * in-player settings cog.  Mirrors the visual language of
 * TrackPickerSheet — dark scrim, single column of pill-shaped rows,
 * initial D-pad focus on the currently-active engine, BACK returns
 * to the player without changing anything.
 */
@Composable
fun EnginePickerSheet(
    currentToken: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val engines = listOf(
        EngineRow("mpv",        "MPV",                 "Best pan-smoothness · motion interpolation · widest codec support"),
        EngineRow("vlc",        "LibVLC",              "Battle-tested · every codec · deep buffer for slow lines"),
        EngineRow("exo",        "ExoPlayer",           "Google Media3 · hardware-first · adaptive HLS/DASH"),
        EngineRow("exo_ffmpeg", "ExoPlayer + FFmpeg",  "ExoPlayer with software audio for DTS · TrueHD · EAC3-JOC"),
    )
    val focusers = remember { engines.map { FocusRequester() } }
    val initialIdx = engines.indexOfFirst { it.token == currentToken }.coerceAtLeast(0)
    LaunchedEffect(Unit) {
        try { focusers[initialIdx].requestFocus() } catch (_: Throwable) {}
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF0020610))
            .onKeyEvent {
                if (it.type == KeyEventType.KeyDown && it.key == Key.Back) {
                    onDismiss(); true
                } else false
            }
            .zIndex(4f),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(min = 560.dp, max = 720.dp)
                .padding(24.dp),
        ) {
            Text(
                text = "Player engine",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.4).sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Change persists on this box until you pick again.",
                color = Color(0xCCC7CFDB),
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(22.dp))
            engines.forEachIndexed { idx, row ->
                EngineRowView(
                    row = row,
                    selected = row.token == currentToken,
                    focus = focusers[idx],
                    onPick = { onPick(row.token) },
                )
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

private data class EngineRow(val token: String, val label: String, val subtitle: String)

@Composable
private fun EngineRowView(
    row: EngineRow,
    selected: Boolean,
    focus: FocusRequester,
    onPick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> Color(0xFF12283F)
        selected -> Color(0x2233CCFF)
        else -> Color(0x14FFFFFF)
    }
    val border = when {
        focused -> Color(0xFF5DC8FF)
        selected -> Color(0x805DC8FF)
        else -> Color(0x22FFFFFF)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .border(1.5.dp, border, RoundedCornerShape(16.dp))
            .focusRequester(focus)
            .focusable()
            .onFocusChanged { focused = it.isFocused }
            .clickable { onPick() }
            .padding(horizontal = 18.dp),
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(if (selected) Color(0xFF5DC8FF) else Color(0x22FFFFFF)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = Color(0xFF020610),
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Spacer(Modifier.width(18.dp))
        Column {
            Text(
                text = row.label,
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = row.subtitle,
                color = Color(0xCCC7CFDB),
                fontSize = 12.sp,
            )
        }
    }
}
