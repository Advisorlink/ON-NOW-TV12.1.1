package tv.rebornplay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Settings → Home screen: move rows up/down, hide the ones you skip (Vesper's HomeRowsPanel). Applies next time Home opens. */
@Composable
fun HomeRowsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val cat = Gorilla.catalog
    var tick by remember { mutableStateOf(0) }
    val rows = remember(cat, tick) { cat?.let { HomeRowPrefs.apply(ctx, allHomeRows(it), includeHidden = true) } ?: emptyList() }
    val hidden = remember(tick) { HomeRowPrefs.hidden(ctx) }
    val first = remember { FocusRequester() }
    FocusOnShow(first, rows.isNotEmpty())
    fun move(i: Int, dir: Int) {
        val keys = rows.map { it.first }.toMutableList(); val j = i + dir
        if (j !in keys.indices) return
        keys[i] = keys[j].also { keys[j] = keys[i] }
        HomeRowPrefs.setOrder(ctx, keys); Cache.home = null; tick++
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 64.dp, end = 64.dp, top = 84.dp, bottom = 60.dp)) {
        item {
            Eyebrow("Settings · Home screen"); Spacer(Modifier.height(6.dp))
            Text("Build your own Home", color = Txt, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 36.sp, letterSpacing = (-1).sp)
            Spacer(Modifier.height(8.dp))
            Text("Put the rows you use most at the top and hide the ones you don't. Changes apply the next time Home opens.", color = Txt2, fontFamily = Geist, fontSize = 14.sp)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("Back to settings", icon = Icons.Outlined.ArrowBack, onClick = onBack)
                Pill("Reset to default order", icon = Icons.Outlined.RestartAlt) { HomeRowPrefs.reset(ctx); Cache.home = null; tick++ }
            }
            Spacer(Modifier.height(22.dp))
            if (rows.isEmpty()) Text("Your catalogue hasn't loaded yet.", color = Txt3, fontFamily = Geist, fontSize = 14.sp)
        }
        itemsIndexed(rows, key = { _, r -> r.first }) { i, (key, row) ->
            val isHidden = key in hidden
            Row(Modifier.fillMaxWidth(0.78f).padding(bottom = 8.dp).clip(RoundedCornerShape(14.dp)).background(Bg1.copy(alpha = if (isHidden) 0.35f else 0.7f)).border(1.dp, Line, RoundedCornerShape(14.dp)).padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Mono(String.format("%02d", i + 1), 11.sp, Txt3, 0.1f, modifier = Modifier.width(30.dp))
                Column(Modifier.weight(1f)) {
                    Text(row.second, color = if (isHidden) Txt3 else Txt, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, textDecoration = if (isHidden) TextDecoration.LineThrough else null)
                    Text(if (isHidden) "Hidden from Home" else "${row.first} · ${row.third.size} titles", color = Txt3, fontFamily = Geist, fontSize = 11.sp)
                }
                IconBtn(Icons.Outlined.KeyboardArrowUp, i > 0, if (i == 0) first else null) { move(i, -1) }
                IconBtn(Icons.Outlined.KeyboardArrowDown, i < rows.lastIndex) { move(i, 1) }
                IconBtn(if (isHidden) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, true) { HomeRowPrefs.toggleHidden(ctx, key); Cache.home = null; tick++ }
            }
        }
    }
}

@Composable
private fun IconBtn(icon: ImageVector, enabled: Boolean, requester: FocusRequester? = null, onClick: () -> Unit) {
    Focusable(onClick = onClick, requester = requester, enabled = enabled, modifier = Modifier.padding(start = 6.dp)) { f ->
        Box(Modifier.size(38.dp).clip(CircleShape).background(if (f) BlueBright else Color.White.copy(alpha = 0.07f)).border(1.dp, if (f) BlueBright else Line, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, Modifier.size(18.dp), tint = if (!enabled) Txt3.copy(alpha = 0.4f) else if (f) Bg0 else Txt)
        }
    }
}
