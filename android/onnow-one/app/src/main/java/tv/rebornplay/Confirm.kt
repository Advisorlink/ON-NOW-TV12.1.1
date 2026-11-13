package tv.rebornplay

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.*

/** Vesper's ConfirmModal: press-and-hold actions always ask first. Cancel on the left, the action on the right (focused). */
object Confirm {
    class Ask(val title: String, val body: String, val options: List<Pair<String, () -> Unit>>, val danger: Boolean)
    var ask: Ask? by mutableStateOf(null)
    var sel by mutableStateOf(1)
    private var armed = false
    fun show(title: String, body: String, confirm: String, danger: Boolean, run: () -> Unit) = choose(title, body, listOf(confirm to run), danger)
    /** Cancel plus one or more actions. */
    fun choose(title: String, body: String, options: List<Pair<String, () -> Unit>>, danger: Boolean = false) { sel = 1; armed = false; ask = Ask(title, body, options, danger) }
    /** Owns the remote while open, so focus stays on the two buttons. The held OK that opened it is ignored. */
    fun key(e: KeyEvent): Boolean {
        val a = ask ?: return false
        when (e.key) {
            Key.DirectionLeft -> if (e.type == KeyEventType.KeyDown) sel = (sel - 1).coerceAtLeast(0)
            Key.DirectionRight -> if (e.type == KeyEventType.KeyDown) sel = (sel + 1).coerceAtMost(a.options.size)
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter ->
                if (e.type == KeyEventType.KeyDown) { if (e.nativeKeyEvent.repeatCount == 0) armed = true }
                else if (armed) { armed = false; ask = null; a.options.getOrNull(sel - 1)?.second?.invoke() }
            Key.Back, Key.Escape -> return false
        }
        return true
    }
}

@Composable
fun ConfirmDialog() {
    val a = Confirm.ask ?: return
    BackHandler { Confirm.ask = null }
    val accent = if (a.danger) Color(0xFFFF6B7A) else Color(0xFF5DC8FF)
    val shape = RoundedCornerShape(16.dp); val btn = RoundedCornerShape(10.dp)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.65f)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(if (a.options.size > 1) 520.dp else 420.dp).clip(shape).background(Color(0xFF11182A)).border(1.dp, Color.White.copy(alpha = 0.10f), shape).padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 20.dp)) {
            Mono(if (a.danger) "Confirm removal" else "Confirm action", 10.sp, accent, 0.28f)
            Text(a.title, color = Color.White, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 23.sp, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
            Text(a.body, color = Color(0xFF9DA5B5), fontFamily = Geist, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(bottom = 18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                val c = Confirm.sel == 0
                Row(Modifier.height(40.dp).pillFocus(c).clip(btn).background(Color.White.copy(alpha = if (c) 0.14f else 0.06f)).border(if (c) 1.5.dp else 1.dp, if (c) BlueBright else Color.White.copy(alpha = 0.12f), btn).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Lucide.X, null, Modifier.size(13.dp), tint = Txt); Spacer(Modifier.width(6.dp))
                    Text("Cancel", color = Txt, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                }
                a.options.forEachIndexed { i, o ->
                    val k = Confirm.sel == i + 1
                    Box(Modifier.height(40.dp).pillFocus(k).clip(btn).background(accent.copy(alpha = if (k) 1f else 0.55f)).border(1.5.dp, if (k) Color.White else Color.Transparent, btn).padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
                        Text(o.first, color = Color(0xFF0A0F1A), fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}
