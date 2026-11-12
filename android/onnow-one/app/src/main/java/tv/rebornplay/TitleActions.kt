package tv.rebornplay

import android.app.Activity
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.composables.icons.lucide.*

/** Cover choices: Play · Expand Trailer · Add to List · Details. Shown on the trailer itself, or as this card when no trailer is playing. */
object TitleActions {
    var item: Item? by mutableStateOf(null)
    var play: (Item) -> Unit = {}
    var details: (Item) -> Unit = {}
    fun expand(it: Item) { HomePip.close(); TrailerPlayer.fullscreen = it }
}

/** Full-width choice: the highlight fills the button, so focus always stays inside the card. */
@Composable
private fun Choice(label: String, icon: ImageVector, requester: FocusRequester? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Focusable(Modifier.fillMaxWidth(), onClick = onClick, requester = requester) { f ->
        Row(Modifier.fillMaxWidth().height(48.dp).clip(shape).background(if (f) Blue else Color.White.copy(alpha = 0.06f)).border(1.dp, if (f) Color.White.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.10f), shape).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(18.dp), tint = if (f) Bg0 else BlueBright); Spacer(Modifier.width(14.dp))
            Text(label, color = if (f) Bg0 else Txt, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
            if (f) Icon(Lucide.ChevronRight, null, Modifier.size(18.dp), tint = Bg0)
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
    val first = remember(item.id) { FocusRequester() }
    FocusOnShow(first, item.id)
    fun close() { TitleActions.item = null }
    BackHandler { close() }
    val rich = Gorilla.cachedInfo(item) ?: item
    val shape = RoundedCornerShape(20.dp)
    Box(Modifier.fillMaxSize().background(Bg0.copy(alpha = 0.78f)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(440.dp).clip(shape).background(Color(0xFF0E1526)).border(1.dp, Blue.copy(alpha = 0.35f), shape)) {
            Box(Modifier.fillMaxWidth().height(210.dp).background(Bg2)) {
                AsyncImage(rich.background ?: item.poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to Color(0xFF0E1526))))
                Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 22.dp, vertical = 8.dp)) {
                    Text(item.name, color = Txt, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 28.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (item.sub.isNotBlank()) Mono(item.sub, 9.sp, Txt2, 0.14f, Modifier.padding(top = 4.dp), upper = false)
                }
            }
            Column(Modifier.padding(start = 22.dp, end = 22.dp, bottom = 22.dp, top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice(if (series) "Play Show" else "Play Movie", Lucide.Play, first) { close(); TitleActions.play(item) }
                Choice("Expand Trailer", Lucide.Maximize) { close(); TitleActions.expand(item) }
                Choice(if (inList) "In My List" else "Add to List", if (inList) Lucide.Check else Lucide.Plus) { inList = Library.toggle(ctx, item) }
                Choice(if (series) "Show Details" else "Movie Details", Lucide.Info) { close(); onDetails(item) }
            }
        }
    }
}
