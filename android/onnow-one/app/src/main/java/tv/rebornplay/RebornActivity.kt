package tv.rebornplay

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.vesper.app.BoxBoost
import tv.vesper.app.LiveTvEntry
import tv.vesper.app.handleGlobalVolumeKey

/** Reborn Play — fully native (Compose). Live TV = the native Live TV module; VOD player = the native ExoPlayer. */
class RebornActivity : ComponentActivity() {
    private var pendingRoute by mutableStateOf<String?>(null)
    private var resumeTick by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installLiveTvHostBar()
        tv.onnowtv.livetv.data.AuthStore.configure(Gorilla.SCHEME, Gorilla.HOST, Gorilla.PORT)
        pendingRoute = intent?.getStringExtra("reborn_route")
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Blue, background = Bg0, surface = Bg1)) {
                RebornApp(this, { pendingRoute }, { pendingRoute = null }, resumeTick)
            }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); pendingRoute = intent.getStringExtra("reborn_route") }
    override fun onResume() { super.onResume(); resumeTick++ }
    override fun onPause() { super.onPause(); TrailerPlayer.stop() }
    override fun onDestroy() { super.onDestroy(); TrailerPlayer.release() }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = handleGlobalVolumeKey(this, event) || super.dispatchKeyEvent(event)
}

@Composable
fun RebornApp(activity: Activity, route: () -> String?, consumeRoute: () -> Unit, resumeTick: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var token by remember { mutableStateOf(RebornSession.token(ctx)) }
    var stage by remember { mutableStateOf(if (token == null) "login" else "loading") }
    var homeReady by remember { mutableStateOf(Cache.home != null) }
    var liveReady by remember { mutableStateOf(LiveStore.bundle.value != null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var autoplay by remember { mutableStateOf(Prefs.autoplay(ctx)) }
    var profileKey by remember { mutableStateOf(0) }
    var toast by remember { mutableStateOf<String?>(null) }
    val nav = remember { Nav() }

    // Live TV's embedded top menu hands navigation back to us via "reborn_route".
    LaunchedEffect(route()) {
        val r = route() ?: return@LaunchedEffect
        val target = routeToNav(r)
        if (target == null) stage = "profiles" else { nav.go(target); if (stage == "profiles") stage = "main" }
        consumeRoute()
    }
    // Boot / sign-in: channels + guide (disk fast path, else the full Live TV loader) and the Home shelves, then profiles.
    LaunchedEffect(stage) {
        if (stage != "loading") return@LaunchedEffect
        if (token == null) { stage = "login"; return@LaunchedEffect }
        loadError = null
        val live = launch {
            if (LiveStore.ensureLoaded(ctx)) liveReady = true
            else runCatching { LiveLoader.load(ctx) { }; LiveStore.refreshFromHolder(ctx) }.onSuccess { liveReady = true }
                .onFailure { if (it is LiveLoader.BadLogin) loadError = it.message else liveReady = true }
        }
        val home = launch { if (Cache.home == null) Cache.home = runCatching { loadHome(ctx) }.getOrNull(); homeReady = true }
        live.join(); home.join()
        if (loadError != null) { signOutQuiet(ctx); token = null; stage = "login" } else stage = "profiles"
    }
    toast?.let { LaunchedEffect(it) { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show(); toast = null } }

    val signOut = { signOutQuiet(ctx); token = null; nav.go(Route.Home); stage = "login" }
    val refreshLive = {
        tv.onnowtv.livetv.LivePreviewSession.release()
        tv.onnowtv.livetv.BundleHolder.current = null; LiveStore.bundle.value = null; LiveStore.epg.clear()
        tv.onnowtv.livetv.data.BundleCache.delete(ctx)
        liveReady = false; stage = "loading"
    }

    Box(Modifier.fillMaxSize().background(Bg0)) {
        when (stage) {
            "login" -> LoginScreen { user, pass ->
                RebornSession.save(ctx, "xtream", user); token = "xtream"
                tv.onnowtv.livetv.data.AuthStore.saveCredentials(ctx, user, pass)
                Cache.clear(); Gorilla.clearCache(ctx)
                stage = "loading"
            }
            "loading" -> LoadingScreen(homeReady, liveReady)
            "profiles" -> ProfilesScreen(onPick = { profileKey++; nav.go(Route.Home); stage = "main" }, onSignOut = signOut)
            else -> key(profileKey) {
                val playHero: (Hero) -> Unit = { h ->
                    val url = h.item.playUrl
                    if (url == null) nav.go(Route.Detail(h.item)) else launchPlayer(activity, h.item, h.item.name, url, h.item.id)
                }
                val cur = nav.current
                val rowNav = remember(cur) { RowNav { if (it == 0) 0 else it + 1 }.also { it.scope = scope } }
                BackHandler(enabled = nav.stack.size > 1 || cur !is Route.Home) { if (!nav.back()) nav.go(Route.Home) }
                when (cur) {
                    is Route.Detail -> DetailScreen(activity, cur.item, onBack = { nav.back() }, onHome = { nav.go(Route.Home) })
                    else -> CompositionLocalProvider(LocalToken provides "xtream") { Box(Modifier.fillMaxSize().rowNavKeys(rowNav, scope)) {
                        val inset = safeInset()
                        when (cur) {
                            is Route.Home -> HomeScreen(nav, rowNav, playHero)
                            is Route.Tab -> Box(Modifier.padding(horizontal = inset)) { TabGridScreen(cur.type) { nav.go(Route.Detail(it)) } }
                            is Route.Search -> Box(Modifier.padding(horizontal = inset)) { SearchScreen { nav.go(Route.Detail(it)) } }
                            is Route.Library -> Box(Modifier.padding(horizontal = inset)) { LibraryScreen { nav.go(Route.Detail(it)) } }
                            is Route.Live -> LiveTvScreen(activity, onRefresh = refreshLive)
                            is Route.Settings -> Box(Modifier.padding(horizontal = inset)) { SettingsScreen(activity, autoplay, onAutoplay = { autoplay = !autoplay; Prefs.setAutoplay(ctx, autoplay) }, onProfiles = { stage = "profiles" }, onSignOut = signOut, onRefreshLive = refreshLive) }
                            else -> {}
                        }
                        val activeId = when (val top = nav.stack.first()) { is Route.Home -> "home"; is Route.Tab -> if (top.type == "movie") "movies" else "tv"; else -> NAV_ITEMS.firstOrNull { it.route == top }?.id ?: "" }
                        TopNav(activeId, autoplay, rowNav, onItem = { item -> item.route?.let { nav.go(it) } },
                            onAutoplay = { autoplay = !autoplay; Prefs.setAutoplay(ctx, autoplay); toast = if (autoplay) "Auto play ON" else "Auto play OFF" }, onSpeedUp = { BoxBoost.show(activity, auto = false) },
                            onSpeedTest = { toast = "Speed test lives in Live TV → Settings for now" }, onProfile = { stage = "profiles" })
                    } }
                }
            }
        }
    }
}

@Composable
fun LoadingScreen(homeReady: Boolean, liveReady: Boolean) {
    val compact = isCompact()
    Box(Modifier.fillMaxSize()) {
        ScreenBackdrop()
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                Box(Modifier.size(if (compact) 360.dp else 620.dp, if (compact) 160.dp else 260.dp).background(androidx.compose.ui.graphics.Brush.radialGradient(listOf(Blue.copy(alpha = 0.22f), androidx.compose.ui.graphics.Color.Transparent))))
                BrandLogo(if (compact) 84.dp else 140.dp)
            }
            Spacer(Modifier.height(if (compact) 18.dp else 30.dp))
            androidx.compose.material3.CircularProgressIndicator(color = Blue, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(16.dp))
            Text("Getting everything ready…", color = Txt, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Spacer(Modifier.height(14.dp))
            LoadStep("Movies & TV shows", homeReady)
            LoadStep("Live TV channels", liveReady)
            LoadStep("TV guide", liveReady)
        }
    }
}

@Composable
private fun LoadStep(label: String, done: Boolean) {
    androidx.compose.foundation.layout.Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Icon(if (done) Icons.Outlined.CheckCircle else Icons.Outlined.Schedule, null, Modifier.size(15.dp), tint = if (done) Blue else Txt3)
        Spacer(Modifier.width(10.dp))
        Mono(label, 11.sp, if (done) Txt else Txt3, 0.14f, upper = false)
    }
}

fun signOutQuiet(ctx: android.content.Context) {
    tv.onnowtv.livetv.LivePreviewSession.release()
    RebornSession.clear(ctx); Profiles.setActive(ctx, null)
    tv.onnowtv.livetv.data.AuthStore.signOut(ctx)
    tv.onnowtv.livetv.BundleHolder.current = null; LiveStore.bundle.value = null; LiveStore.epg.clear()
    Cache.clear(); Gorilla.clearCache(ctx)
}
