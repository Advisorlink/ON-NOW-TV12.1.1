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
import com.composables.icons.lucide.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
        // Guide reminders (same banner + watcher as the V2 guide): tapping the banner jumps to the channel.
        tv.onnowtv.livetv.ReminderWatcher.attach(this, findViewById(android.R.id.content)) { r ->
            LiveStore.bundle.value?.channels?.firstOrNull { it.id == r.channelId }?.let { LiveStore.openFullscreen(this, it, emptyList()) }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); pendingRoute = intent.getStringExtra("reborn_route") }
    override fun onResume() { super.onResume(); resumeTick++ }
    override fun onPause() { super.onPause(); TrailerPlayer.stop() }
    override fun onDestroy() { super.onDestroy(); tv.onnowtv.livetv.ReminderWatcher.detach(this); TrailerPlayer.release() }
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
            if (homeError == null) launch(Dispatchers.IO) { runCatching { BoxSets.ensure(ctx) } }
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
                    if (url == null) nav.open(h.item) else launchPlayer(activity, h.item, h.item.name, url, h.item.id)
                }
                val cur = nav.current
                val rowNav = remember(cur) { RowNav { if (it == 0) 0 else it + 1 }.also { it.scope = scope } }
                BackHandler(enabled = nav.stack.size > 1 || cur !is Route.Home) { if (!nav.back()) nav.go(Route.Home) }
                when (cur) {
                    is Route.Detail -> DetailScreen(activity, cur.item, onBack = { nav.back() }, onHome = { nav.go(Route.Home) })
                    is Route.BoxSet -> BoxSetScreen(cur.id, onBack = { nav.back() }, onOpen = { nav.go(Route.Detail(it)) }, onPlay = { it.playUrl?.let { url -> launchPlayer(activity, it, it.name, url, it.id) } ?: nav.go(Route.Detail(it)) })
                    else -> CompositionLocalProvider(LocalToken provides "xtream") { Box(Modifier.fillMaxSize().rowNavKeys(rowNav, scope)) {
                        val inset = safeInset()
                        when (cur) {
                            is Route.Home -> HomeScreen(nav, rowNav, playHero, onResume = { resumeEntry(activity, it) }, onChannel = { ch -> LiveStore.openFullscreen(activity, ch, LiveStore.bundle.value?.channels ?: listOf(ch)) })
                            is Route.Tab -> Box(Modifier.padding(horizontal = inset)) { TabGridScreen(cur.type) { nav.go(Route.Detail(it)) } }
                            is Route.BoxSets -> BoxSetsScreen(onOpenSet = { nav.go(Route.BoxSet(it)) })
                            is Route.Search -> Box(Modifier.padding(horizontal = inset)) { SearchScreen(onOpen = { nav.go(Route.Detail(it)) }, onOpenChannel = { ch -> LiveStore.openFullscreen(activity, ch, LiveStore.bundle.value?.channels ?: listOf(ch)) }) }
                            is Route.Library -> LibraryScreen(onBack = { nav.go(Route.Home) }, onResume = { resumeEntry(activity, it) }) { nav.go(Route.Detail(it)) }
                            is Route.Live -> LiveTvScreen(activity, onRefresh = refreshLive)
                            is Route.Settings -> Box(Modifier.padding(horizontal = inset)) { SettingsScreen(activity, autoplay, onAutoplay = { autoplay = !autoplay; Prefs.setAutoplay(ctx, autoplay) }, onProfiles = { stage = "profiles" }, onSignOut = signOut, onRefreshLive = refreshLive, onHomeRows = { nav.go(Route.HomeRows) }, onPickLiveCategory = { nav.go(Route.LiveCategoryPick) }, onBack = { nav.go(Route.Home) }) }
                            is Route.LiveCategoryPick -> Box(Modifier.padding(horizontal = inset)) { LiveCategoryPickScreen(onDone = { nav.go(Route.Settings) }) }
                            is Route.HomeRows -> Box(Modifier.padding(horizontal = inset)) { HomeRowsScreen(onBack = { nav.go(Route.Settings) }) }
                            else -> {}
                        }
                        val activeId = when (val top = nav.stack.first()) { is Route.Home -> "home"; is Route.Tab -> if (top.type == "movie") "movies" else "tv"; is Route.HomeRows, is Route.LiveCategoryPick -> "settings"; is Route.BoxSet -> "boxsets"; else -> NAV_ITEMS.firstOrNull { it.route == top }?.id ?: "" }
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
    val cat = Gorilla.catalog
    val channels = LiveStore.bundle.value?.channels?.size ?: 0
    val failed = homeError != null && homeReady
    val done = (if (homeReady && !failed) 1 else 0) + (if (liveReady) 2 else 0)
    val progress by androidx.compose.animation.core.animateFloatAsState(done / 3f, androidx.compose.animation.core.tween(600), label = "p")
    Box(Modifier.fillMaxSize()) {
        ScreenBackdrop()
        Column(Modifier.align(Alignment.Center).width(if (compact) 340.dp else 520.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                Box(Modifier.size(if (compact) 360.dp else 620.dp, if (compact) 150.dp else 220.dp).background(androidx.compose.ui.graphics.Brush.radialGradient(listOf(Blue.copy(alpha = 0.24f), androidx.compose.ui.graphics.Color.Transparent))))
                BrandLogo(if (compact) 76.dp else 116.dp)
            }
            Spacer(Modifier.height(if (compact) 10.dp else 18.dp))
            Text(if (failed) "Couldn't load your movies & TV shows" else "Getting everything ready…", color = Txt, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Spacer(Modifier.height(16.dp))
            Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(18.dp)).padding(horizontal = 18.dp, vertical = 12.dp)) {
                LoadStep(Lucide.Film, "Movies & TV shows", when {
                    failed -> homeError.orEmpty()
                    homeReady && cat != null -> "${"%,d".format(cat.movies.size)} movies · ${"%,d".format(cat.series.size)} series"
                    else -> "Downloading your catalogue"
                }, homeReady && !failed, failed)
                LoadStep(Lucide.Tv, "Live TV channels", if (liveReady) "${"%,d".format(channels)} channels ready" else "Fetching channels", liveReady, false)
                LoadStep(Lucide.CalendarDays, "TV guide", if (liveReady) "Programme guide ready" else "Loading today's schedule", liveReady, false)
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.10f))) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceAtLeast(0.04f)).background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Blue, BlueBright))))
                }
            }
            if (failed) {
                Spacer(Modifier.height(22.dp))
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
private fun LoadStep(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, detail: String, done: Boolean, failed: Boolean) {
    androidx.compose.foundation.layout.Row(Modifier.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(if (done) Blue.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.White.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
            androidx.compose.material3.Icon(icon, null, Modifier.size(17.dp), tint = if (done) BlueBright else if (failed) Amber else Txt2)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = if (done) Txt else Txt2, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Mono(detail, 9.sp, if (failed) Amber else Txt3, 0.08f, upper = false)
        }
        if (done) androidx.compose.material3.Icon(Lucide.CircleCheck, null, Modifier.size(18.dp), tint = BlueBright)
        else if (failed) androidx.compose.material3.Icon(Lucide.CircleAlert, null, Modifier.size(18.dp), tint = Amber)
        else androidx.compose.material3.CircularProgressIndicator(color = Blue, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
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
