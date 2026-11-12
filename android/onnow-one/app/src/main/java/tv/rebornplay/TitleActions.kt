package tv.rebornplay

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.composables.icons.lucide.*

/** Vesper's cover menu: OK on a cover offers Expand Trailer · Play · My List · Details. */
object TitleActions { var item: Item? by mutableStateOf(null) }

@Composable
fun TitleActionsSheet(activity: Activity, onDetails: (Item) -> Unit) {
    val item = TitleActions.item ?: return
    val ctx = LocalContext.current
    val series = item.type == "series"
    var inList by remember(item.id) { mutableStateOf(Library.has(ctx, item.id)) }
    val first = remember(item.id) { FocusRequester() }
    FocusOnShow(first, item.id)
    fun close() { TitleActions.item = null }
    BackHandler { close() }
    val rich = Gorilla.cachedInfo(item) ?: item
    val shape = RoundedCornerShape(18.dp)
    Box(Modifier.fillMaxSize().background(Bg0.copy(alpha = 0.72f)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(460.dp).blueGlow(true, shape, 18.dp, 0.5f).clip(shape).background(Color(0xFF11182A)).border(1.dp, Blue.copy(alpha = 0.4f), shape)) {
            Box(Modifier.fillMaxWidth().height(190.dp).background(Bg2)) {
                AsyncImage(rich.background ?: item.poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color(0xFF11182A))))
                Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 20.dp, vertical = 10.dp)) {
                    Text(item.name, color = Txt, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 26.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (item.sub.isNotBlank()) Mono(item.sub, 9.sp, Txt2, 0.14f, Modifier.padding(top = 3.dp), upper = false)
                }
            }
            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 20.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill(if (series) "Play Show" else "Play Movie", Modifier.fillMaxWidth(), icon = Lucide.Play, primary = true, height = 46.dp, fontSize = 15.sp, requester = first) {
                    close(); HomePip.close()
                    val url = item.playUrl
                    if (series || url == null) onDetails(item) else launchPlayer(activity, item, item.name, url, item.id)
                }
                Pill("Expand Trailer", Modifier.fillMaxWidth(), icon = Lucide.Maximize, height = 46.dp, fontSize = 15.sp) { close(); HomePip.close(); TrailerPlayer.fullscreen = item }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill(if (inList) "In My List" else "Add to List", Modifier.weight(1f), icon = if (inList) Lucide.Check else Lucide.Plus, height = 46.dp, fontSize = 15.sp) { inList = Library.toggle(ctx, item) }
                    Pill(if (series) "Show Details" else "Movie Details", Modifier.weight(1f), icon = Lucide.Info, height = 46.dp, fontSize = 15.sp) { close(); onDetails(item) }
                }
            }
        }
    }
}
