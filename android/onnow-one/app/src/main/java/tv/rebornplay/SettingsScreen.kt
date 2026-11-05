package tv.rebornplay

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import tv.vesper.app.LiveTvEntry

@Composable
fun SettingsScreen(activity: Activity, autoplay: Boolean, onAutoplay: () -> Unit, onProfiles: () -> Unit, onSignOut: () -> Unit) {
    val ctx = LocalContext.current
    val user = remember { RebornSession.username(ctx) ?: "" }
    val profile = remember { Profiles.active(ctx) }
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }
    val first = remember { FocusRequester() }
    FocusOnShow(first, Unit)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 64.dp, end = 64.dp, top = 84.dp, bottom = 60.dp)) {
        item {
            Eyebrow("Settings"); Spacer(Modifier.height(6.dp))
            Text("Make it yours", color = Txt, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 36.sp, letterSpacing = (-1).sp)
            Spacer(Modifier.height(28.dp))
        }
        item { Section("Account") {
            SettingRow(Icons.Outlined.Person, "Signed in as", user, requester = first) {}
            SettingRow(Icons.Outlined.Groups, "Profile", profile?.name ?: "—", action = "Switch", onClick = onProfiles)
            SettingRow(Icons.Outlined.Logout, "Sign out", "Clears this device's sign-in. Profiles stay backed up in the cloud.", action = "Sign out", onClick = onSignOut)
        } }
        item { Section("Playback") {
            SettingRow(Icons.Outlined.PlayCircle, "Autoplay best stream", if (autoplay) "Press Play and the best link starts straight away" else "Press Play to pick a link first", action = if (autoplay) "ON" else "OFF", accent = autoplay, onClick = onAutoplay)
        } }
        item { Section("Live TV") {
            SettingRow(Icons.Outlined.CellTower, "Live TV settings", "Player engine, subtitles, aspect ratio, buffer, parental PIN, hidden groups", action = "Open") { LiveTvEntry.open(activity, settings = true) }
            SettingRow(Icons.Outlined.Refresh, "Refresh channels & guide", "Re-downloads your channel list and the programme guide", action = "Refresh") { LiveTvEntry.prefetch(activity) }
        } }
        item { Section("Home") {
            SettingRow(Icons.Outlined.Autorenew, "Refresh home content", "Clears cached shelves so the latest titles show up", action = "Refresh") { Cache.clear() }
        } }
        item { Section("About") { SettingRow(Icons.Outlined.Info, "Reborn Play", "Version $version · native build") {} } }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(bottom = 26.dp)) {
        Mono(title, 10.sp, Txt3, modifier = Modifier.padding(bottom = 10.dp))
        Column(Modifier.fillMaxWidth(0.72f).clip(RoundedCornerShape(16.dp)).background(Bg1.copy(alpha = 0.7f)).border(1.dp, Line, RoundedCornerShape(16.dp)), content = content)
    }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, sub: String, action: String? = null, accent: Boolean = false, requester: FocusRequester? = null, onClick: () -> Unit) {
    Focusable(onClick = onClick, requester = requester) { f ->
        Row(Modifier.fillMaxWidth().background(if (f) Blue.copy(alpha = 0.12f) else Color.Transparent).padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(20.dp), tint = if (f) Blue else Txt2); Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Txt, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(sub, color = Txt2, fontFamily = Geist, fontSize = 12.sp, maxLines = 2)
            }
            if (action != null) Box(Modifier.clip(CircleShape).background(if (accent) Amber.copy(alpha = 0.16f) else if (f) BlueBright else Color.White.copy(alpha = 0.08f)).padding(horizontal = 14.dp, vertical = 6.dp)) {
                Mono(action, 10.sp, if (accent) Amber else if (f) Bg0 else Txt, 0.16f)
            }
        }
    }
}

/** Search — results grid from TMDB search, typing debounced like the web. */
@Composable
fun SearchScreen(onOpen: (Item) -> Unit) {
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Item>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    val req = remember { FocusRequester() }
    FocusOnShow(req, Unit)
    LaunchedEffect(q) {
        if (q.trim().length < 2) { results = emptyList(); return@LaunchedEffect }
        delay(350); busy = true
        results = withContext(Dispatchers.IO) { runCatching { RebornApi.search(q.trim()) }.getOrDefault(emptyList()) }
        busy = false
    }
    Column(Modifier.fillMaxSize().padding(top = 80.dp)) {
        Row(Modifier.padding(start = 64.dp, end = 48.dp, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column { Eyebrow("Search"); Text("Find anything", color = Txt, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 26.sp) }
            Spacer(Modifier.width(30.dp))
            Box(Modifier.width(520.dp)) { Field(q, { q = it }, "Movies, shows, people…", Icons.Outlined.Search, requester = req) }
            Spacer(Modifier.width(18.dp))
            Mono(if (busy) "Searching…" else if (results.isNotEmpty()) "${results.size} results" else "", 10.sp, Txt3)
        }
        if (q.trim().length < 2) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Start typing to search movies and TV shows", color = Txt3, fontFamily = Geist, fontSize = 15.sp) }
        else TitleGrid(results, null, onOpen)
    }
}
