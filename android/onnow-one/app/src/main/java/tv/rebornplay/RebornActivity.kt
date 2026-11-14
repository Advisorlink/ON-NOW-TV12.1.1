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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.onPreviewKeyEvent
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
        Rebrand.install(application)
        Lockdown.enforce(this)
        // Same as V2: release builds block OS screenshots / screen recording / cast mirroring (HDMI output is unaffected).
        if (!tv.vesper.app.BuildConfig.DEBUG) window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        installLiveTvHostBar()
        tv.onnowtv.livetv.data.AuthStore.configure(Gorilla.SCHEME, Gorilla.HOST, Gorilla.PORT)
        pendingRoute = intent?.getStringExtra("reborn_route")
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Blue, background = Bg0, surface = Bg1)) {
                TvCanvas { RebornApp(this, { pendingRoute }, { pendingRoute = null }, resumeTick) }
            }
        }
        // Guide reminders (same banner + watcher as the V2 guide): tapping the banner jumps to the channel.
        tv.onnowtv.livetv.ReminderWatcher.attach(this, findViewById(android.R.id.content)) { r ->
            LiveStore.bundle.value?.channels?.firstOrNull { it.id == r.channelId }?.let { LiveStore.openFullscreen(this, it, emptyList()) }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); pendingRoute = intent.getStringExtra("reborn_route") }
    override fun onResume() { super.onResume(); Lockdown.enforce(this); resumeTick++ }
    override fun onPause() { super.onPause(); TrailerPlayer.stop(); Cloud.pushIfChanged(this) }
    override fun onDestroy() { super.onDestroy(); tv.onnowtv.livetv.ReminderWatcher.detach(this); TrailerPlayer.release() }
    private var softIdx = 15
    /** Fire TV / fixed-volume boxes ignore the system slider — turn the app's own sound up/down instead. */
    private fun softVolume(raise: Boolean): Int {
        softIdx = (softIdx + if (raise) 1 else -1).coerceIn(0, 15)
        TrailerPlayer.softVolume = softIdx / 15f
        runCatching { if (tv.onnowtv.livetv.LivePreviewSession.isAlive()) tv.onnowtv.livetv.LivePreviewSession.getOrCreate(this).volume = softIdx / 15f }
        return softIdx * 100 / 15
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = handleGlobalVolumeKey(this, event, ::softVolume) || super.dispatchKeyEvent(event)
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
        if (token == null) {
            // Fresh install on a box that has signed in before: its login comes back by itself.
            val saved = kotlinx.coroutines.withTimeoutOrNull(9000) { Cloud.recall(ctx) }
            if (saved == null) { stage = "login"; return@LaunchedEffect }
            RebornSession.save(ctx, "xtream", saved.first); tv.onnowtv.livetv.data.AuthStore.saveCredentials(ctx, saved.first, saved.second); token = "xtream"
        }
        loadError = null; homeError = null
        // Check the saved login against the service first (the service answers 404 for unknown accounts).
        val auth = withContext(Dispatchers.IO) { runCatching { Gorilla.login(Gorilla.user(ctx), Gorilla.pass(ctx)) } }
        auth.exceptionOrNull()?.let { e ->
            if (e.message?.startsWith("Wrong") == true || e.message?.startsWith("Account") == true) {
                signOutQuiet(ctx); token = null; toast = e.message; stage = "login"; return@LaunchedEffect
            }
        }
        if (auth.isSuccess) { launch { runCatching { Cloud.remember(ctx) } }; kotlinx.coroutines.withTimeoutOrNull(9000) { runCatching { Cloud.pull(ctx) } } }
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
        LiveLoader.keepFresh(ctx) { LiveStore.refreshFromHolder(ctx) }
        if (loadError != null) { signOutQuiet(ctx); token = null; stage = "login" }
        else if (homeError == null) stage = "profiles"   // else: stay here and show the reason + Try again
    }
    toast?.let { LaunchedEffect(it) { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show(); toast = null } }

    val signOut = { Cloud.pushIfChanged(ctx); kotlinx.coroutines.GlobalScope.launch { runCatching { Cloud.forget(ctx) } }; signOutQuiet(ctx); token = null; nav.go(Route.Home); stage = "login" }
    // Live TV refresh happens in place: pick channels or guide, nothing else is reloaded.
    var liveBusy by remember { mutableStateOf<String?>(null) }
    fun liveJob(label: String, job: suspend () -> Unit) {
        if (liveBusy != null) return
        liveBusy = label
        scope.launch {
            val r = runCatching { job() }
            if (r.isSuccess) { LiveStore.epg.clear(); LiveStore.refreshFromHolder(ctx) }
            liveBusy = null
            Toast.makeText(ctx, if (r.isSuccess) "$label — done" else "Couldn't refresh: ${r.exceptionOrNull()?.message ?: "try again"}", Toast.LENGTH_LONG).show()
        }
    }
    val askRefreshLive = {
        Confirm.choose("Refresh Live TV", "Choose what to refresh. Movies and TV shows are not touched.", listOf(
            "Live TV channels" to { liveJob("Refreshing Live TV channels") { LiveLoader.refreshChannels(ctx) } },
            "TV guide" to { liveJob("Refreshing the TV guide") { LiveLoader.refreshGuide(ctx) } }))
    }
    val askRefreshCatalogue = {
        Confirm.show("Refresh your movies and shows?", "The full list is downloaded again from your service. This can take a minute on a Fire Stick; Live TV is not touched.", "Refresh", false) {
            Cache.clear(); Gorilla.clearCache(ctx); BoxSets.reset(); nav.go(Route.Home); stage = "loading"
        }
    }
    val refreshLive = {
        tv.onnowtv.livetv.LivePreviewSession.release()
        tv.onnowtv.livetv.BundleHolder.current = null; LiveLoader.hydrated = false; LiveStore.bundle.value = null; LiveStore.epg.clear()
        tv.onnowtv.livetv.data.BundleCache.delete(ctx)
        liveReady = false; stage = "loading"
    }

    Box(Modifier.fillMaxSize().background(Bg0).onPreviewKeyEvent { Confirm.key(it) || SpeedTest.key(it) || TitleActions.key(it) }) {
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
                    HomePip.close()
                    val url = h.item.playUrl
                    if (url == null) nav.go(Route.Detail(h.item)) else launchPlayer(activity, h.item, h.item.name, url, h.item.id)
                }
                val cur = nav.current
                val rowNav = remember(cur) { RowNav { if (it == 0) 0 else it + 1 }.also { it.scope = scope } }
                BackHandler(enabled = nav.stack.size > 1 || cur !is Route.Home) { if (!nav.back()) nav.go(Route.Home) }
                when (cur) {
                    is Route.Detail -> key(cur.item.id) { DetailScreen(activity, cur.item, onBack = { nav.back() }, onHome = { nav.go(Route.Home) }, onActor = { nav.go(Route.Actor(it)) }) }
                    is Route.Actor -> key(cur.member.id) { ActorScreen(cur.member, onBack = { nav.back() }) { nav.go(Route.Detail(it)) } }
                    is Route.Calendar -> CalendarScreen(onBack = { nav.back() }) { nav.go(Route.Detail(it)) }
                    is Route.BoxSet -> BoxSetScreen(cur.id, onBack = { nav.back() }, onOpen = { nav.go(Route.Detail(it)) }, onPlay = { it.playUrl?.let { url -> launchPlayer(activity, it, it.name, url, it.id) } ?: nav.go(Route.Detail(it)) })
                    else -> CompositionLocalProvider(LocalToken provides "xtream") { CompositionLocalProvider(LocalRowNav provides rowNav) { Box(Modifier.fillMaxSize().rowNavKeys(rowNav, scope)) {
                        val inset = safeInset()
                        when (cur) {
                            is Route.Home -> { HomeScreen(nav, rowNav, playHero, onResume = { HomePip.close(); resumeEntry(activity, it) }, onChannel = { ch -> HomePip.open(activity, ch) }); HomePipWindow(activity) }
                            is Route.Tab -> Box(Modifier.padding(horizontal = inset)) { TabGridScreen(cur.type) { nav.go(Route.Detail(it)) } }
                            is Route.BoxSets -> BoxSetsScreen(onOpenSet = { nav.go(Route.BoxSet(it)) })
                            is Route.Search -> Box(Modifier.padding(horizontal = inset)) { SearchScreen(onOpen = { nav.go(Route.Detail(it)) }, onOpenChannel = { ch -> LiveStore.openFullscreen(activity, ch, LiveStore.bundle.value?.channels ?: listOf(ch)) }) }
                            is Route.Library -> LibraryScreen(onBack = { nav.go(Route.Home) }, onResume = { resumeEntry(activity, it) }, onCalendar = { nav.go(Route.Calendar) }, onActor = { nav.go(Route.Actor(it)) }, onSet = { nav.go(Route.BoxSet(it)) }) { nav.go(Route.Detail(it)) }
                            is Route.Live -> LiveTvScreen(activity, onLoad = refreshLive, onSettings = { nav.go(Route.Settings) }, onRefresh = askRefreshLive)
                            is Route.Settings -> Box(Modifier.padding(horizontal = inset)) { SettingsScreen(activity, autoplay, onAutoplay = { autoplay = !autoplay; Prefs.setAutoplay(ctx, autoplay) }, onProfiles = { stage = "profiles" }, onSignOut = signOut, onRefreshLive = askRefreshLive, onRefreshCatalogue = askRefreshCatalogue, onHomeRows = { nav.go(Route.HomeRows) }, onPickLiveCategory = { nav.go(Route.LiveCategoryPick) }, onBack = { nav.go(Route.Home) }) }
                            is Route.LiveCategoryPick -> Box(Modifier.padding(horizontal = inset)) { LiveCategoryPickScreen(onDone = { nav.go(Route.Settings) }) }
                            is Route.HomeRows -> Box(Modifier.padding(horizontal = inset)) { HomeRowsScreen(onBack = { nav.go(Route.Settings) }) }
                            else -> {}
                        }
                        val activeId = when (val top = nav.stack.first()) { is Route.Home -> "home"; is Route.Tab -> if (top.type == "movie") "movies" else "tv"; is Route.HomeRows, is Route.LiveCategoryPick -> "settings"; is Route.BoxSet -> "boxsets"; else -> NAV_ITEMS.firstOrNull { it.route == top }?.id ?: "" }
                        TopNav(activeId, autoplay, rowNav, onItem = { item -> item.route?.let { nav.go(it) } },
                            onAutoplay = { autoplay = !autoplay; Prefs.setAutoplay(ctx, autoplay); toast = if (autoplay) "Auto play ON" else "Auto play OFF" }, onSpeedUp = { BoxBoost.show(activity, auto = false) },
                            onSpeedTest = { HomePip.close(); TrailerPlayer.stop(); SpeedTest.show() }, onProfile = { stage = "profiles" })
                    } } }
                }
                TitleActionsSheet(activity) { nav.go(Route.Detail(it)) }
                TrailerFullscreen()
                NotifyHitToast { nav.go(Route.Detail(it)) }
                liveBusy?.let { b -> Box(Modifier.fillMaxSize()) { Row(Modifier.align(Alignment.TopCenter).padding(top = 84.dp).clip(androidx.compose.foundation.shape.CircleShape).background(androidx.compose.ui.graphics.Color(0xF011182A)).border(1.dp, Blue.copy(alpha = 0.5f), androidx.compose.foundation.shape.CircleShape).padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(14.dp), color = Blue, strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("$b…", color = Txt, fontFamily = Geist, fontSize = 13.sp) } } }
                SpeedTestDialog()
                ConfirmDialog()
                // Anything saved (My List, Continue Watching, reminders, actors, profiles) is uploaded within a minute, not only when the app closes.
                LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(45_000); Cloud.pushIfChanged(ctx) } }
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
    tv.onnowtv.livetv.BundleHolder.current = null; LiveLoader.hydrated = false; LiveStore.bundle.value = null; LiveStore.epg.clear()
    runCatching { tv.onnowtv.livetv.data.BundleCache.delete(ctx) }
    Cache.clear(); Gorilla.clearCache(ctx)
}
