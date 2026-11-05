package tv.rebornplay

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.vesper.app.LiveTvEntry
import tv.vesper.app.R
import tv.vesper.app.WebAppInterface

val Navy = Color(0xFF070D1A)
val Panel = Color(0xFF0F182B)
val Blue = Color(0xFF1E88F0)
val BlueBright = Color(0xFF49B2FF)
val Silver = Color(0xFFD7DEE8)
val Muted = Color(0xFF8E9AAE)

enum class Tab(val label: String) { HOME("Home"), MOVIES("Movies"), TV("TV Shows"), LIVE("Live TV"), SETTINGS("Settings") }

/** Reborn Play — fully native shell (Compose). Live TV = native module; player = native ExoPlayer/VLC. */
class RebornActivity : ComponentActivity() {
    private var pendingRoute by mutableStateOf<String?>(null)
    private var resumeTick by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingRoute = intent?.getStringExtra("reborn_route")
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Blue, background = Navy, surface = Panel)) { RebornApp(this, { pendingRoute }, { pendingRoute = null }, resumeTick) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        pendingRoute = intent.getStringExtra("reborn_route")
    }

    override fun onResume() { super.onResume(); resumeTick++ }
}

@Composable
fun RebornApp(activity: Activity, route: () -> String?, consumeRoute: () -> Unit, resumeTick: Int) {
    val ctx = LocalContext.current
    var token by remember { mutableStateOf(RebornSession.token(ctx)) }
    var stage by remember { mutableStateOf(if (token == null) "login" else "main") }
    var tab by remember { mutableStateOf(Tab.HOME) }
    var detail by remember { mutableStateOf<Item?>(null) }

    LaunchedEffect(route()) {
        val r = route() ?: return@LaunchedEffect
        tab = when { r.startsWith("/movies") -> Tab.MOVIES; r.startsWith("/tv") -> Tab.TV; r.startsWith("/settings") -> Tab.SETTINGS; else -> Tab.HOME }
        detail = null; consumeRoute()
    }
    // Returning from the Live TV prefetch (loading) screen → main.
    LaunchedEffect(resumeTick) { if (stage == "loading" && resumeTick > 1) stage = "main" }

    Box(Modifier.fillMaxSize().background(Navy)) {
        when (stage) {
            "login" -> LoginScreen { tok, user, pass, iptv ->
                RebornSession.save(ctx, tok, user); token = tok
                LiveTvEntry.onLogin(ctx, user, pass, iptv)
                if (LiveTvEntry.available(ctx)) { stage = "loading"; LiveTvEntry.prefetch(activity) } else stage = "main"
            }
            "loading" -> LoadingScreen("Loading your channels and guide…")
            else -> {
                val d = detail
                if (d != null) DetailScreen(token!!, d, onBack = { detail = null }, activity = activity)
                else Column(Modifier.fillMaxSize()) {
                    TopBar(tab) { t ->
                        if (t == Tab.LIVE) LiveTvEntry.open(activity) else tab = t
                    }
                    when (tab) {
                        Tab.SETTINGS -> SettingsScreen(activity) { RebornSession.clear(ctx); LiveTvEntry.onLogout(activity); token = null; stage = "login" }
                        else -> Shelves(token!!, tab) { detail = it }
                    }
                }
            }
        }
    }
}

@Composable
fun TopBar(active: Tab, onPick: (Tab) -> Unit) {
    Row(Modifier.fillMaxWidth().background(Panel).padding(horizontal = 28.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painter = painterResource(R.drawable.reborn_banner), contentDescription = "Reborn Play", modifier = Modifier.height(40.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.FillHeight)
        Spacer(Modifier.width(26.dp))
        Tab.values().forEach { t ->
            var focused by remember { mutableStateOf(false) }
            val isActive = t == active
            Text(
                t.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                color = if (focused) Color(0xFF04101E) else if (isActive) BlueBright else Silver,
                modifier = Modifier.padding(end = 6.dp).clip(CircleShape)
                    .background(if (focused) BlueBright else if (isActive) Blue.copy(alpha = 0.2f) else Color.Transparent)
                    .onFocusChanged { focused = it.isFocused }.focusable().clickable { onPick(t) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
fun Image(painter: androidx.compose.ui.graphics.painter.Painter, contentDescription: String?, modifier: Modifier, contentScale: ContentScale) =
    androidx.compose.foundation.Image(painter, contentDescription, modifier, contentScale = contentScale)

@Composable
fun LoadingScreen(text: String) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Image(painterResource(R.drawable.reborn_banner), "Reborn Play", Modifier.height(140.dp), ContentScale.FillHeight)
        Spacer(Modifier.height(28.dp)); CircularProgressIndicator(color = BlueBright)
        Spacer(Modifier.height(16.dp)); Text(text, color = Muted, fontSize = 14.sp)
    }
}

@Composable
fun LoginScreen(onDone: (String, String, String, String) -> Unit) {
    var user by remember { mutableStateOf("") }; var pass by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }; var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val submit = {
        if (!busy && user.isNotBlank() && pass.isNotBlank()) {
            busy = true; err = null
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { RebornApi.login(user.trim(), pass) } }
                    .onSuccess { (tok, iptv) -> onDone(tok, user.trim(), pass, iptv.toString()) }
                    .onFailure { err = it.message ?: "Sign-in failed" }
                busy = false
            }
        }
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0B1A33), Navy))), contentAlignment = Alignment.Center) {
        Column(Modifier.width(420.dp).clip(RoundedCornerShape(20.dp)).background(Panel.copy(alpha = 0.92f)).border(1.dp, Blue.copy(alpha = 0.35f), RoundedCornerShape(20.dp)).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Image(painterResource(R.drawable.reborn_banner), "Reborn Play", Modifier.height(96.dp), ContentScale.FillHeight)
            Spacer(Modifier.height(10.dp))
            Text("Movies, TV shows and Live TV. One sign-in.", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(22.dp))
            OutlinedTextField(user, { user = it }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
            if (err != null) { Spacer(Modifier.height(10.dp)); Text(err!!, color = Color(0xFFFF7A7A), fontSize = 13.sp) }
            Spacer(Modifier.height(18.dp))
            Button(onClick = { submit() }, enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = BlueBright, contentColor = Color(0xFF04101E)), modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Text(if (busy) "Signing in…" else "Sign in", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun Shelves(token: String, tab: Tab, onOpen: (Item) -> Unit) {
    var rows by remember(tab) { mutableStateOf<List<Pair<Catalog, List<Item>>>>(emptyList()) }
    var loading by remember(tab) { mutableStateOf(true) }; var err by remember(tab) { mutableStateOf<String?>(null) }
    LaunchedEffect(tab) {
        loading = true
        runCatching {
            withContext(Dispatchers.IO) {
                val cats = RebornApi.catalogs(token).filter { tab == Tab.HOME || (tab == Tab.MOVIES && it.type == "movie") || (tab == Tab.TV && it.type == "series") }
                cats.mapNotNull { c -> runCatching { RebornApi.catalog(token, c) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { c to it } }
            }
        }.onSuccess { rows = it }.onFailure { err = it.message }
        loading = false
    }
    if (loading && rows.isEmpty()) LoadingScreen("Loading ${tab.label.lowercase()}…")
    else if (rows.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(err ?: "Nothing here yet", color = Muted) }
    else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 18.dp)) {
        items(rows) { (c, list) ->
            Text("${c.name} · ${c.addonName}".uppercase(), color = Muted, fontSize = 11.sp, letterSpacing = 2.sp, modifier = Modifier.padding(start = 32.dp, top = 10.dp, bottom = 8.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = 28.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(list) { item -> Poster(item) { onOpen(item) } }
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
fun Poster(item: Item, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Column(Modifier.width(150.dp).scale(if (focused) 1.08f else 1f).onFocusChanged { focused = it.isFocused }.focusable().clickable(onClick = onClick)) {
        AsyncImage(item.poster, item.name, Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)).background(Panel).border(if (focused) 3.dp else 0.dp, if (focused) BlueBright else Color.Transparent, RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
        Text(item.name, color = if (focused) Silver else Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
fun DetailScreen(token: String, item: Item, onBack: () -> Unit, activity: Activity) {
    var full by remember { mutableStateOf(item) }; var eps by remember { mutableStateOf<List<Episode>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(item.id) { runCatching { withContext(Dispatchers.IO) { RebornApi.meta(token, item.type, item.id) } }.onSuccess { (m, e) -> full = m; eps = e } }
    val play: (String) -> Unit = { vid ->
        status = "Finding the best stream…"
        scope.launch {
            val list = runCatching { withContext(Dispatchers.IO) { RebornApi.streams(token, item.type, vid) } }.getOrDefault(emptyList())
            val best = list.sortedByDescending { q -> when (q.quality.uppercase()) { "4K", "2160P" -> 4; "1080P" -> 3; "720P" -> 2; else -> 1 } }.firstOrNull()
            if (best == null) status = "No playable stream found" else { status = null; WebAppInterface(activity).playInternal(best.url, full.name, null) }
        }
    }
    androidx.activity.compose.BackHandler { onBack() }
    Box(Modifier.fillMaxSize()) {
        AsyncImage(full.background ?: full.poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.35f)
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Navy, Navy.copy(alpha = 0.75f), Color.Transparent))))
        Column(Modifier.fillMaxSize().padding(40.dp)) {
            if (full.logo != null) AsyncImage(full.logo, full.name, Modifier.height(90.dp), contentScale = ContentScale.Fit)
            else Text(full.name, color = Silver, fontSize = 40.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(listOfNotNull(full.year, full.rating?.let { "IMDb $it" }, if (item.type == "series") "Series" else "Movie").joinToString("  ·  "), color = BlueBright, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            Text(full.description.orEmpty(), color = Silver, fontSize = 15.sp, maxLines = 5, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(620.dp))
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (item.type == "movie") Pill("▶  Play", primary = true) { play(item.id) }
                Spacer(Modifier.width(10.dp)); Pill("Back") { onBack() }
                if (status != null) { Spacer(Modifier.width(16.dp)); Text(status!!, color = Muted, fontSize = 13.sp) }
            }
            if (eps.isNotEmpty()) {
                Spacer(Modifier.height(22.dp)); Text("EPISODES", color = Muted, fontSize = 11.sp, letterSpacing = 2.sp)
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.weight(1f)) {
                    items(eps) { e ->
                        var f by remember { mutableStateOf(false) }
                        Row(Modifier.fillMaxWidth(0.6f).clip(RoundedCornerShape(10.dp)).background(if (f) Blue.copy(alpha = 0.25f) else Color.Transparent).onFocusChanged { f = it.isFocused }.focusable().clickable { play(e.id) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("S${e.season} E${e.episode}", color = BlueBright, fontSize = 12.sp, modifier = Modifier.width(72.dp))
                            Text(e.title, color = Silver, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Pill(label: String, primary: Boolean = false, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Text(label, color = if (f || primary) Color(0xFF04101E) else Silver, fontWeight = FontWeight.Bold, fontSize = 15.sp,
        modifier = Modifier.clip(CircleShape).background(if (f) BlueBright else if (primary) Blue else Color.White.copy(alpha = 0.12f)).onFocusChanged { f = it.isFocused }.focusable().clickable(onClick = onClick).padding(horizontal = 26.dp, vertical = 12.dp))
}

@Composable
fun SettingsScreen(activity: Activity, onSignOut: () -> Unit) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().padding(40.dp)) {
        Text("SETTINGS", color = Muted, fontSize = 11.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(16.dp))
        Text("Signed in as ${RebornSession.username(ctx) ?: ""}", color = Silver, fontSize = 16.sp)
        Spacer(Modifier.height(20.dp))
        Row { Pill("Live TV settings") { LiveTvEntry.open(activity, settings = true) }; Spacer(Modifier.width(10.dp)); Pill("Refresh channels") { LiveTvEntry.prefetch(activity) }; Spacer(Modifier.width(10.dp)); Pill("Sign out") { onSignOut() } }
    }
}
