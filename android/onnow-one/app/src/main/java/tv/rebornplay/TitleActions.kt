package tv.rebornplay

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.composables.icons.lucide.*

/** Cover choices (Vesper's trailer actions): Play · Expand Trailer · Library | Details. On the trailer itself, or as a card when no trailer is playing. */
object TitleActions {
    var item: Item? by mutableStateOf(null)
    var sel by mutableStateOf(0)
    /** Cover whose playing trailer is showing the choices. */
    var inlineId: String? by mutableStateOf(null)
    /** Two stacked choices (Coming Soon) instead of the usual four. */
    private var two = false
    fun openInline(id: String, twoChoices: Boolean = false) { sel = 0; armed = false; two = twoChoices; inlineId = id }
    var play: (Item) -> Unit = {}
    var details: (Item) -> Unit = {}
    internal var activate: () -> Unit = {}
    private var armed = false
    fun open(it: Item) { sel = 0; armed = false; item = it }
    fun expand(it: Item) { HomePip.close(); TrailerPlayer.fullscreen = it }
    /** Layout is [0] / [1] / [2 | 3]. */
    fun move(from: Int, key: Key): Int = when (key) {
        Key.DirectionUp -> when (from) { 0 -> 0; 1 -> 0; else -> 1 }
        Key.DirectionDown -> when (from) { 0 -> 1; 1 -> 2; else -> from }
        Key.DirectionLeft -> if (from == 3) 2 else from
        Key.DirectionRight -> if (from == 2) 3 else from
        else -> from
    }
    /** While the card is open every remote press is handled here, so focus can never wander to the screen behind it. */
    fun key(e: KeyEvent): Boolean {
        if (item == null && inlineId == null) return false
        when (e.key) {
            Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight -> if (e.type == KeyEventType.KeyDown) sel = if (two && item == null) (when (e.key) { Key.DirectionUp -> 0; Key.DirectionDown -> 1; else -> sel }) else move(sel, e.key)
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> if (e.type == KeyEventType.KeyDown) armed = true else if (armed) { armed = false; activate() }
            Key.Back, Key.Escape -> return false
        }
        return true
    }
}

@Composable
private fun VPill(label: String, icon: ImageVector, on: Boolean, compact: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.height(if (compact) 30.dp else 44.dp).pillFocus(on).clip(CircleShape).background(if (on) Color(0xE60B1830) else Color.Black.copy(alpha = 0.62f))
        .border(if (on) 1.5.dp else 1.dp, if (on) BlueBright else Color.White.copy(alpha = 0.28f), CircleShape).padding(horizontal = if (compact) 10.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Icon(icon, null, Modifier.size(if (compact) 12.dp else 16.dp), tint = if (on) BlueBright else Color.White); Spacer(Modifier.width(if (compact) 6.dp else 9.dp))
        Text(label, color = Color.White, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = if (compact) 11.sp else 14.sp, maxLines = 1)
    }
}

/** Coming Soon: the two choices on the playing trailer. */
@Composable
fun SoonPills(sel: Int, remind: Boolean, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
        VPill("Play Trailer Full Screen", Lucide.Maximize, sel == 0, true, Modifier.width(196.dp))
        VPill(if (remind) "Reminder Set" else "Remind Me", if (remind) Lucide.BellRing else Lucide.Bell, sel == 1, true, Modifier.width(196.dp))
    }
}

/** The four choices, centred, as glowing pills. */
@Composable
fun ActionPills(sel: Int, series: Boolean, inList: Boolean, compact: Boolean, modifier: Modifier = Modifier) {
    val w = if (compact) 196.dp else 280.dp; val gap = if (compact) 7.dp else 12.dp
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(gap)) {
        VPill(if (series) "Play Show" else "Play Movie", Lucide.Play, sel == 0, compact, Modifier.width(w))
        VPill("Expand Trailer", Lucide.Maximize, sel == 1, compact, Modifier.width(w))
        Row(Modifier.width(w), horizontalArrangement = Arrangement.spacedBy(gap)) {
            VPill(if (inList) "In Library" else "Library", if (inList) Lucide.Check else Lucide.Plus, sel == 2, compact, Modifier.weight(1f))
            VPill("Details", Lucide.Info, sel == 3, compact, Modifier.weight(1f))
        }
    }
}

@Composable
fun TitleActionsSheet(activity: Activity, onDetails: (Item) -> Unit) {
    val ctx = LocalContext.current
    SideEffect {
        TitleActions.details = onDetails
        TitleActions.play = { it -> HomePip.close(); val url = it.playUrl; if (it.type == "series" || url == null) onDetails(it) else launchPlayer(activity, it, it.name, url, it.id) }
    }
    val item = TitleActions.item ?: return
    val series = item.type == "series"
    var inList by remember(item.id) { mutableStateOf(Library.has(ctx, item.id)) }
    fun close() { TitleActions.item = null }
    SideEffect {
        TitleActions.activate = {
            when (TitleActions.sel) {
                0 -> { close(); TitleActions.play(item) }
                1 -> { close(); TitleActions.expand(item) }
                2 -> inList = Library.toggle(ctx, item)
                else -> { close(); onDetails(item) }
            }
        }
    }
    BackHandler { close() }   // focus never left the cover, so Back lands exactly where you were
    val rich = Gorilla.cachedInfo(item) ?: item
    val shape = RoundedCornerShape(22.dp)
    Box(Modifier.fillMaxSize().background(Bg0.copy(alpha = 0.72f)), contentAlignment = Alignment.Center) {
        Box(Modifier.width(520.dp).height(400.dp).blueGlow(true, shape, 26.dp, 0.55f).clip(shape).background(Color(0xFF0B1220)).border(1.5.dp, BlueBright.copy(alpha = 0.55f), shape)) {
            AsyncImage(rich.background ?: item.poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x4005070D), 0.4f to Color(0x8C05070D), 1f to Color(0xF205070D))))
            Column(Modifier.align(Alignment.TopStart).padding(horizontal = 24.dp, vertical = 20.dp)) {
                Mono(if (series) "Series" else "Movie", 10.sp, BlueBright, 0.32f)
                Text(item.name, color = Txt, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = (-0.6).sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                if (item.sub.isNotBlank()) Mono(item.sub, 9.sp, Txt2, 0.14f, Modifier.padding(top = 5.dp), upper = false)
            }
            ActionPills(TitleActions.sel, series, inList, compact = false, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 26.dp))
        }
    }
}
