package tv.vesper.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
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

/** v2.16.48 — Aspect-ratio picker, triggered by the top-right cog. */
@Composable
fun AspectPickerSheet(
    currentToken: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val rows = listOf(
        AspectRow("fit",     "Fit",     "Show the whole frame (default) · no cropping · no distortion"),
        AspectRow("fill",    "Fill",    "Fill the screen · crops the edges to remove any black bars"),
        AspectRow("zoom",    "Zoom",    "Zoom in on the centre · discards the outer 10% of the frame"),
        AspectRow("stretch", "Stretch", "Force 16:9 · stretches 4:3 content to a widescreen panel"),
    )
    val focusers = remember { rows.map { FocusRequester() } }
    val initialIdx = rows.indexOfFirst { it.token == currentToken }.coerceAtLeast(0)
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
            Text("Aspect ratio", color = Color.White, fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.4).sp)
            Spacer(Modifier.height(6.dp))
            Text("Applies to the current stream only.  Choice resets between titles.",
                color = Color(0xCCC7CFDB), fontSize = 13.sp)
            Spacer(Modifier.height(22.dp))
            rows.forEachIndexed { idx, row ->
                AspectRowView(row, row.token == currentToken, focusers[idx]) { onPick(row.token) }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

private data class AspectRow(val token: String, val label: String, val subtitle: String)

@Composable
private fun AspectRowView(
    row: AspectRow, selected: Boolean, focus: FocusRequester, onPick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> Color(0xFF12283F); selected -> Color(0x2233CCFF); else -> Color(0x14FFFFFF)
    }
    val bd = when {
        focused -> Color(0xFF5DC8FF); selected -> Color(0x805DC8FF); else -> Color(0x22FFFFFF)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth().height(72.dp).clip(RoundedCornerShape(16.dp))
            .background(bg).border(1.5.dp, bd, RoundedCornerShape(16.dp))
            .focusRequester(focus).focusable().onFocusChanged { focused = it.isFocused }
            .clickable { onPick() }.padding(horizontal = 18.dp),
    ) {
        Box(
            modifier = Modifier.size(30.dp).clip(CircleShape)
                .background(if (selected) Color(0xFF5DC8FF) else Color(0x22FFFFFF)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Filled.Check, null,
                tint = Color(0xFF020610), modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(18.dp))
        Column {
            Text(row.label, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text(row.subtitle, color = Color(0xCCC7CFDB), fontSize = 12.sp)
        }
    }
}
