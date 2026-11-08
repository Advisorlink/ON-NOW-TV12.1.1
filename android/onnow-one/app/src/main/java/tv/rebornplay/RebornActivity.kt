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
import androidx.compose.ui.focus.FocusRequester
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
    // Only a service login (token "xtream" + saved credentials) counts; anything older (pre-3.0 builds) is wiped and sent to sign-in.
    var token by remember {
        val t = RebornSession.token(ctx)
        val valid = t == "xtream" && tv.onnowtv.livetv.data.AuthStore.isSignedIn(ctx)
        if (t != null && !valid) signOutQuiet(ctx)
        mutableStateOf(if (valid) t else null)
    }
    var stage by remember { mutableStateOf(if (token == null) "login" else "loading") }
    var homeReady by remember { mutableStateOf(Cache.home != null) }
    var liveReady by remember { mutableStateOf(LiveStore.bundle.value != null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var homeError by remember { mutableStateOf<String?>(null) }
    var bootTick by remember { mutableStateOf(0) }
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
    LaunchedEffect(stage, bootTick) {
        if (stage != "loading") return@LaunchedEffect
        if (token == null) { stage = "login"; return@LaunchedEffect }
        loadError = null; homeError = null
        // Check the saved login against the service first (the service answers 404 for unknown accounts).
        val auth = withContext(Dispatchers.IO) { runCatching { Gorilla.login(Gorilla.user(ctx), Gorilla.pass(ctx)) } }
        auth.exceptionOrNull()?.let { e ->
            if (e.message?.startsWith("Wrong") == true || e.message?.startsWith("Account") == true) {
                signOutQuiet(ctx); token = null; toast = e.message; stage = "login"; return@LaunchedEffect
            }
        }
        val live = launch {
            if (LiveStore.ensureLoaded(ctx)) liveReady = true
            else runCatching { LiveLoader.load(ctx) { }; LiveStore.refreshFromHolder(ctx) }.onSuccess { liveReady = true }
                .onFailure { if (it is LiveLoader.BadLogin) loadError = it.message else liveReady = true }
        }
        val home = launch {
            if (Cache.home == null) runCatching { loadHome(ctx) }.onSuccess { Cache.home = it }
                .onFailure { android.util.Log.e("Reborn", "preload home failed", it); homeError = it.message ?: "couldn't load" }
            homeReady = true
        }
        live.join(); home.join()
        if (loadError != null) { signOutQuiet(ctx); token = null; stage = "login" }
        else if (homeError == null) stage = "profiles"   // else: stay here and show the reason + Try again
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
                signOutQuiet(ctx)   // drop anything cached under a previous account (old channels, old catalogue)
                RebornSession.save(ctx, "xtream", user); token = "xtream"
                tv.onnowtv.livetv.data.AuthStore.saveCredentials(ctx, user, pass)
                homeReady = false; liveReady = false
                stage = "loading"
            }
            "loading" -> LoadingScreen(homeReady, liveReady, homeError,
                onRetry = { Gorilla.clearCache(ctx); Cache.home = null; homeReady = false; bootTick++ },
                onContinue = { stage = "profiles" })
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
                            is Route.Search -> Box(Modifier.padding(horizontal = inset)) { SearchScreen(onOpen = { nav.go(Route.Detail(it)) }, onOpenChannel = { ch -> LiveStore.openFullscreen(activity, ch, LiveStore.bundle.value?.channels ?: listOf(ch)) }) }
                            is Route.Library -> Box(Modifier.padding(horizontal = inset)) { LibraryScreen { nav.go(Route.Detail(it)) } }
                            is Route.Live -> LiveTvScreen(activity, onRefresh = refreshLive)
                            is Route.Settings -> Box(Modifier.padding(horizontal = inset)) { SettingsScreen(activity, autoplay, onAutoplay = { autoplay = !autoplay; Prefs.setAutoplay(ctx, autoplay) }, onProfiles = { stage = "profiles" }, onSignOut = signOut, onRefreshLive = refreshLive, onHomeRows = { nav.go(Route.HomeRows) }) }
                            is Route.HomeRows -> Box(Modifier.padding(horizontal = inset)) { HomeRowsScreen(onBack = { nav.go(Route.Settings) }) }
                            else -> {}
                        }
                        val activeId = when (val top = nav.stack.first()) { is Route.Home -> "home"; is Route.Tab -> if (top.type == "movie") "movies" else "tv"; is Route.HomeRows -> "settings"; else -> NAV_ITEMS.firstOrNull { it.route == top }?.id ?: "" }
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
fun LoadingScreen(homeReady: Boolean, liveReady: Boolean, homeError: String? = null, onRetry: () -> Unit = {}, onContinue: () -> Unit = {}) {
    val compact = isCompact()
    val ctx = LocalContext.current
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }
    val counts = Gorilla.catalog?.let { "${"%,d".format(it.movies.size)} movies · ${"%,d".format(it.series.size)} series" }
    val failed = homeError != null && homeReady
    Box(Modifier.fillMaxSize()) {
        ScreenBackdrop()
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                Box(Modifier.size(if (compact) 360.dp else 620.dp, if (compact) 160.dp else 260.dp).background(androidx.compose.ui.graphics.Brush.radialGradient(listOf(Blue.copy(alpha = 0.22f), androidx.compose.ui.graphics.Color.Transparent))))
                BrandLogo(if (compact) 84.dp else 140.dp)
            }
            Spacer(Modifier.height(if (compact) 18.dp else 30.dp))
            if (!failed) androidx.compose.material3.CircularProgressIndicator(color = Blue, strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(16.dp))
            Text(if (failed) "Couldn't load your movies & TV shows" else "Getting everything ready…", color = Txt, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Spacer(Modifier.height(14.dp))
            LoadStep(if (failed) "Movies & TV shows — $homeError" else if (homeReady && counts != null) "Movies & TV shows · $counts" else "Movies & TV shows", homeReady && !failed)
            LoadStep("Live TV channels", liveReady)
            LoadStep("TV guide", liveReady)
            if (failed) {
                Spacer(Modifier.height(24.dp))
                val retryReq = remember { FocusRequester() }; FocusOnShow(retryReq, homeError)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Pill("Try again", white = true, requester = retryReq, onClick = onRetry)
                    Pill("Continue to Live TV", onClick = onContinue)
                }
            }
        }
        Mono("Reborn Play v$version", 10.sp, Txt3, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 28.dp + safeInset(), bottom = 20.dp + safeInset()))
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
    runCatching { tv.onnowtv.livetv.data.BundleCache.delete(ctx) }
    Cache.clear(); Gorilla.clearCache(ctx)
}
