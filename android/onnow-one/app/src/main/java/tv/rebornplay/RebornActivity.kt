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
import androidx.compose.ui.graphics.Color as C
import androidx.compose.animation.core.animateFloat
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
        Rebrand.install(application); Crash.install(application)
        tv.vesper.app.PlayerDockSkin.dock = { RebornPlayerDock(it) }; tv.vesper.app.PlayerDockSkin.logo = { PlayerLogo.url }; tv.vesper.app.PlayerDockSkin.brand = "REBORN PLAY"
        PlayerLook.load(this)
        // Categories offered inside the full-screen Live TV channel list (PIN-locked ones are left out).
        tv.onnowtv.livetv.ui.StripGroups.provider = {
            val locked = tv.onnowtv.livetv.data.ParentalLock.lockedIds(applicationContext)
            val favs = LiveStore.channelsIn(LiveStore.FAVS)
            listOfNotNull(if (favs.isNotEmpty()) tv.onnowtv.livetv.ui.StripGroup(LiveStore.FAVS, "Favourites", favs.size) { LiveStore.channelsIn(LiveStore.FAVS) } else null) +
                LiveStore.categories(applicationContext).filter { it.id !in locked }.map { c -> tv.onnowtv.livetv.ui.StripGroup(c.id, c.name, c.channelCount) { LiveStore.channelsIn(c.id) } }
        }
        tv.vesper.app.PlayerDockSkin.extraLabel = "Player look"; tv.vesper.app.PlayerDockSkin.extraValue = { PlayerLook.names[PlayerLook.style] }
        tv.vesper.app.PlayerDockSkin.extraPick = { PlayerLook.next(applicationContext) }   // movies & shows get Reborn Play's own control bar
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
    override fun onResume() { super.onResume(); TrailerPlayer.suspended = false; Lockdown.enforce(this); resumeTick++ }
    override fun onPause() { super.onPause(); TrailerPlayer.halt(); Cloud.pushIfChanged(this) }
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
        // Already set up on this box: open straight away and do every check and refresh behind the scenes.
        // A newly installed version (fresh install or update) always does one full load from the service first.
        val build = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).let { if (android.os.Build.VERSION.SDK_INT >= 28) it.longVersionCode else it.versionCode.toLong() } }.getOrDefault(0L)
        val bootPrefs = ctx.getSharedPreferences("reborn_boot", android.content.Context.MODE_PRIVATE)
        val newBuild = bootPrefs.getLong("loaded_build", -1L) != build
        if (newBuild) { Gorilla.clearCache(ctx); Cache.clear() }
        if (!newBuild && Gorilla.hasCache(ctx) && tv.onnowtv.livetv.data.BundleCache.exists(ctx)) {
            scope.launch {
                launch {
                    if (LiveStore.ensureLoaded(ctx)) liveReady = true
                    else runCatching { LiveLoader.load(ctx) { }; LiveStore.refreshFromHolder(ctx) }.onFailure { if (it is LiveLoader.BadLogin) { signOutQuiet(ctx); token = null; toast = it.message; stage = "login" } }
                    liveReady = true
                    LiveLoader.keepFresh(ctx) { LiveStore.refreshFromHolder(ctx) }
                }
                launch { runCatching { loadHome(ctx) }.onFailure { android.util.Log.e("Reborn", "preload home failed", it) }; homeReady = true; launch(Dispatchers.IO) { runCatching { BoxSets.ensure(ctx) } } }
                val check = withContext(Dispatchers.IO) { runCatching { Gorilla.login(Gorilla.user(ctx), Gorilla.pass(ctx)) } }
                val msg = check.exceptionOrNull()?.message
                if (msg?.startsWith("Wrong") == true || msg?.startsWith("Account") == true) { signOutQuiet(ctx); token = null; toast = msg; stage = "login" }
                else if (check.isSuccess) { runCatching { Cloud.remember(ctx) }; runCatching { Cloud.pull(ctx) } }
            }
            stage = "profiles"; return@LaunchedEffect
        }
        Boot.reset()
        // Check the saved login against the service first (the service answers 404 for unknown accounts).
        val auth = withContext(Dispatchers.IO) { runCatching { Gorilla.login(Gorilla.user(ctx), Gorilla.pass(ctx)) } }
        auth.exceptionOrNull()?.let { e ->
            if (e.message?.startsWith("Wrong") == true || e.message?.startsWith("Account") == true) {
                signOutQuiet(ctx); token = null; toast = e.message; stage = "login"; return@LaunchedEffect
            }
        }
        if (auth.isSuccess) { launch { runCatching { Cloud.remember(ctx) } }; kotlinx.coroutines.withTimeoutOrNull(9000) { runCatching { Cloud.pull(ctx) } } }
        val live = launch {
            if (!newBuild && LiveStore.ensureLoaded(ctx)) liveReady = true
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
        else if (homeError == null) { if (liveReady) bootPrefs.edit().putLong("loaded_build", build).apply(); stage = "profiles" }   // else: stay here and show the reason + Try again
    }
    toast?.let { LaunchedEffect(it) { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show(); toast = null } }

    val signOut = { Cloud.pushIfChanged(ctx); kotlinx.coroutines.GlobalScope.launch { runCatching { Cloud.forget(ctx) } }; signOutQuiet(ctx); token = null; nav.go(Route.Home); stage = "login" }
    // Live TV refresh happens in place: pick channels or guide, nothing else is reloaded.
    var liveBusy by remember { mutableStateOf<String?>(null) }
    fun change(a: Int, b: Int) = "%,d  →  %,d".format(a, b) + when { b > a -> "   (+%,d)".format(b - a); b < a -> "   (−%,d)".format(a - b); else -> "   (no change)" }
    fun liveJob(label: String, done: String, stats: () -> List<Pair<String, Int>>, job: suspend () -> Unit) {
        if (liveBusy != null) return
        liveBusy = label
        val before = stats()
        scope.launch {
            val r = runCatching { job() }
            if (r.isSuccess) { LiveStore.epg.clear(); LiveStore.refreshFromHolder(ctx) }
            liveBusy = null
            if (r.isSuccess) Confirm.info(done, "Before  →  Now\n" + stats().mapIndexed { i, (name, now) -> "$name:  ${change(before.getOrNull(i)?.second ?: 0, now)}" }.joinToString("\n"))
            else Toast.makeText(ctx, "Couldn't refresh: ${r.exceptionOrNull()?.message ?: "try again"}", Toast.LENGTH_LONG).show()
        }
    }
    val channelStats = { tv.onnowtv.livetv.BundleHolder.current.let { b -> listOf("Channels" to (b?.channels?.size ?: 0), "Categories" to (b?.categories?.size ?: 0), "Catch-up channels" to (b?.channels?.count { it.archiveEnabled } ?: 0)) } }
    val guideStats = { tv.onnowtv.livetv.BundleHolder.current.let { b -> listOf("Channels with a guide" to (b?.epg?.size ?: 0), "Programmes" to (b?.epg?.values?.sumOf { it.size } ?: 0)) } }
    var catBefore by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var allBefore by remember { mutableStateOf<List<Pair<String, Int>>?>(null) }
    val askRefreshLive = {
        Confirm.choose("Refresh Live TV", "Choose what to refresh. Movies and TV shows are not touched.", listOf(
            "Live TV channels" to { liveJob("Refreshing Live TV channels", "Live TV channels refreshed", channelStats) { LiveLoader.refreshChannels(ctx) } },
            "TV guide" to { liveJob("Refreshing the TV guide", "TV guide refreshed", guideStats) { LiveLoader.refreshGuide(ctx) } }))
    }
    val askRefreshCatalogue = {
        Confirm.show("Refresh your movies and shows?", "The full list is downloaded again from your service. This can take a minute on a Fire Stick; Live TV is not touched.", "Refresh", false) {
            catBefore = Gorilla.catalog?.let { it.movies.size to it.series.size } ?: (0 to 0)
            Cache.clear(); Gorilla.clearCache(ctx); BoxSets.reset(); nav.go(Route.Home); stage = "loading"
        }
    }
    // Top-bar Refresh: one place to pick what to reload; every choice ends with a Before → Now card.
    val askRefreshAll = {
        Confirm.choose("Refresh", "Choose what to download again from your service.", listOf(
            "Movies & shows" to { catBefore = Gorilla.catalog?.let { it.movies.size to it.series.size } ?: (0 to 0); Cache.clear(); Gorilla.clearCache(ctx); BoxSets.reset(); nav.go(Route.Home); stage = "loading" },
            "TV guide" to { liveJob("Refreshing the TV guide", "TV guide refreshed", guideStats) { LiveLoader.refreshGuide(ctx) } },
            "Live TV" to { liveJob("Refreshing Live TV channels", "Live TV channels refreshed", channelStats) { LiveLoader.refreshChannels(ctx) } },
            "Everything" to {
                if (liveBusy == null) {
                    liveBusy = "Refreshing everything"
                    val before = channelStats() + guideStats()
                    scope.launch {
                        val r = runCatching { LiveLoader.refreshChannels(ctx); LiveLoader.refreshGuide(ctx) }
                        if (r.isSuccess) { LiveStore.epg.clear(); LiveStore.refreshFromHolder(ctx) }
                        liveBusy = null
                        if (r.isFailure) Toast.makeText(ctx, "Couldn't refresh Live TV: ${r.exceptionOrNull()?.message ?: "try again"}", Toast.LENGTH_LONG).show()
                        allBefore = before.takeIf { r.isSuccess }
                        catBefore = Gorilla.catalog?.let { it.movies.size to it.series.size } ?: (0 to 0)
                        Cache.clear(); Gorilla.clearCache(ctx); BoxSets.reset(); nav.go(Route.Home); stage = "loading"
                    }
                }
            }))
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
                            is Route.Home -> { HomeScreen(nav, rowNav, playHero, onResume = { HomePip.close(); resumeEntry(activity, it) }, onChannel = { ch -> HomePip.open(activity, ch) }) }
                            is Route.Tab -> Box(Modifier.padding(horizontal = inset)) { TabGridScreen(cur.type) { nav.go(Route.Detail(it)) } }
                            is Route.BoxSets -> BoxSetsScreen(onOpenSet = { nav.go(Route.BoxSet(it)) })
                            is Route.Search -> Box(Modifier.padding(horizontal = inset)) { SearchScreen(onOpen = { nav.go(Route.Detail(it)) }, onOpenChannel = { ch -> LiveStore.openFullscreen(activity, ch, LiveStore.bundle.value?.channels ?: listOf(ch)) }) }
                            is Route.Library -> LibraryScreen(onBack = { nav.go(Route.Home) }, onResume = { resumeEntry(activity, it) }, onCalendar = { nav.go(Route.Calendar) }, onActor = { nav.go(Route.Actor(it)) }, onSet = { nav.go(Route.BoxSet(it)) }) { nav.go(Route.Detail(it)) }
                            is Route.Live -> LiveTvScreen(activity, onLoad = refreshLive)
                            is Route.Settings -> Box(Modifier.padding(horizontal = inset)) { SettingsScreen(activity, autoplay, onAutoplay = { autoplay = !autoplay; Prefs.setAutoplay(ctx, autoplay) }, onProfiles = { stage = "profiles" }, onSignOut = signOut, onRefreshLive = askRefreshLive, onRefreshCatalogue = askRefreshCatalogue, onHomeRows = { nav.go(Route.HomeRows) }, onPickLiveCategory = { nav.go(Route.LiveCategoryPick) }, onBack = { nav.go(Route.Home) }) }
                            is Route.LiveCategoryPick -> Box(Modifier.padding(horizontal = inset)) { LiveCategoryPickScreen(onDone = { nav.go(Route.Settings) }) }
                            is Route.HomeRows -> Box(Modifier.padding(horizontal = inset)) { HomeRowsScreen(onBack = { nav.go(Route.Settings) }) }
                            else -> {}
                        }
                        val activeId = when (val top = nav.stack.first()) { is Route.Home -> "home"; is Route.Tab -> if (top.type == "movie") "movies" else "tv"; is Route.HomeRows, is Route.LiveCategoryPick -> "settings"; is Route.BoxSet -> "boxsets"; else -> NAV_ITEMS.firstOrNull { it.route == top }?.id ?: "" }
                        TopNav(activeId, autoplay, rowNav, onItem = { item -> item.route?.let { nav.go(it) } },
                            onAutoplay = { autoplay = !autoplay; Prefs.setAutoplay(ctx, autoplay); toast = if (autoplay) "Auto play ON" else "Auto play OFF" }, onSpeedUp = { BoxBoost.show(activity, auto = false) },
                            onSpeedTest = { HomePip.close(); TrailerPlayer.stop(); SpeedTest.show() }, onRefresh = askRefreshAll, onProfile = { stage = "profiles" })
                    } } }
                }
                // Mini player floats over every page (same spot as on Home) until something is played or Back closes it on Home.
                LaunchedEffect(cur) { if (cur is Route.Live) HomePip.close() }
                if (cur !is Route.Live) HomePipWindow(activity, onHome = cur is Route.Home && nav.stack.size == 1)
                TitleActionsSheet(activity) { nav.go(Route.Detail(it)) }
                TrailerFullscreen()
                NotifyHitToast { nav.go(Route.Detail(it)) }
                liveBusy?.let { b -> Box(Modifier.fillMaxSize()) { Row(Modifier.align(Alignment.TopCenter).padding(top = 84.dp).clip(androidx.compose.foundation.shape.CircleShape).background(androidx.compose.ui.graphics.Color(0xF011182A)).border(1.dp, Blue.copy(alpha = 0.5f), androidx.compose.foundation.shape.CircleShape).padding(horizontal = 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.CircularProgressIndicator(Modifier.size(14.dp), color = Blue, strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("$b…", color = Txt, fontFamily = Geist, fontSize = 13.sp) } } }
                SpeedTestDialog()
                // After "Refresh movies & shows": what it was and what it is now.
                LaunchedEffect(catBefore, Gorilla.catalog) {
                    val was = catBefore; val c = Gorilla.catalog
                    if (was != null && c != null) {
                        catBefore = null
                        val live = allBefore?.let { b -> val now = channelStats() + guideStats(); "\n" + now.mapIndexed { i, (name, n) -> "$name:  ${change(b.getOrNull(i)?.second ?: 0, n)}" }.joinToString("\n") }.orEmpty()
                        Confirm.info(if (allBefore != null) "Everything refreshed" else "Movies & TV shows refreshed", "Before  →  Now\nMovies:  ${change(was.first, c.movies.size)}\nTV shows:  ${change(was.second, c.series.size)}$live")
                        allBefore = null
                    }
                }
                ConfirmDialog()
                // Anything saved (My List, Continue Watching, reminders, actors, profiles) is uploaded within a minute, not only when the app closes.
                LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(45_000); Cloud.pushIfChanged(ctx) } }
            }
        }
    }
}

/** Live numbers for the first-time set-up screen. */
object Boot {
    val bytes = mutableStateOf(0L)
    val channels = mutableStateOf(0)
    val guideChannels = mutableStateOf(0)
    val guideProgrammes = mutableStateOf(0)
    private val counter = java.util.concurrent.atomic.AtomicLong()
    fun addBytes(n: Int) { val t = counter.addAndGet(n.toLong()); if (t - bytes.value > 200_000) bytes.value = t }
    fun reset() { counter.set(0); bytes.value = 0; channels.value = 0; guideChannels.value = 0; guideProgrammes.value = 0 }
}

@Composable
fun LoadingScreen(homeReady: Boolean, liveReady: Boolean, homeError: String? = null, onRetry: () -> Unit = {}, onContinue: () -> Unit = {}) {
    val compact = isCompact()
    val ctx = LocalContext.current
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }
    val cat = Gorilla.catalog
    val failed = homeError != null && homeReady
    val bytes = Boot.bytes.value
    val chans = maxOf(Boot.channels.value, LiveStore.bundle.value?.channels?.size ?: 0)
    val gCh = Boot.guideChannels.value; val gPr = Boot.guideProgrammes.value
    val channelsDone = liveReady || chans > 0
    // Real counts drive each bar; totals aren't known ahead of time, so a bar approaches full and snaps to 100 % when its step finishes.
    val catF = if (homeReady && !failed) 1f else (bytes / (bytes + 6_000_000f)) * 0.92f
    val chF = if (channelsDone) 1f else 0.08f
    val gF = if (liveReady) 1f else if (!channelsDone) 0f else maxOf(gPr / (gPr + 25_000f), gCh / (gCh + 2_500f)) * 0.95f
    val overall by androidx.compose.animation.core.animateFloatAsState(0.35f * catF + 0.15f * chF + 0.5f * gF, androidx.compose.animation.core.tween(500), label = "p")
    val spin = androidx.compose.animation.core.rememberInfiniteTransition(label = "spin")
    val angle by spin.animateFloat(0f, 360f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(2400, easing = androidx.compose.animation.core.LinearEasing)), label = "a")
    val pulse by spin.animateFloat(0.55f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(1300), androidx.compose.animation.core.RepeatMode.Reverse), label = "g")
    val headline = when {
        failed -> "Couldn't load your movies & TV shows"
        !channelsDone -> "Finding your channels"
        !liveReady -> "Building your TV guide"
        !homeReady -> "Loading your movies & TV shows"
        else -> "All set"
    }
    Box(Modifier.fillMaxSize()) {
        ScreenBackdrop()
        Box(Modifier.align(Alignment.Center).size(if (compact) 420.dp else 760.dp).background(androidx.compose.ui.graphics.Brush.radialGradient(listOf(Blue.copy(alpha = 0.16f * pulse), C.Transparent))))
        BrandLogo(if (compact) 34.dp else 46.dp, Modifier.align(Alignment.TopStart).padding(start = 48.dp + safeInset(), top = 34.dp + safeInset()))
        Column(Modifier.align(Alignment.Center).padding(horizontal = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val ring = if (compact) 120.dp else 190.dp
            Box(Modifier.size(ring), contentAlignment = Alignment.Center) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val w = size.minDimension * 0.062f
                    val inset = w * 1.6f
                    val arc = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2)
                    val tl = androidx.compose.ui.geometry.Offset(inset, inset)
                    drawArc(C.White.copy(alpha = 0.07f), 0f, 360f, false, tl, arc, style = androidx.compose.ui.graphics.drawscope.Stroke(w))
                    // soft glow, then the crisp arc on top
                    drawArc(Blue.copy(alpha = 0.22f * pulse), -90f, 360f * overall, false, tl, arc, style = androidx.compose.ui.graphics.drawscope.Stroke(w * 2.6f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                    drawArc(androidx.compose.ui.graphics.Brush.sweepGradient(listOf(Blue, BlueBright, C.White, Blue)), -90f, 360f * overall, false, tl, arc, style = androidx.compose.ui.graphics.drawscope.Stroke(w, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                    // orbiting marker shows work is under way even between count updates
                    if (!failed && overall < 0.999f) drawArc(BlueBright.copy(alpha = 0.55f), angle, 26f, false, androidx.compose.ui.geometry.Offset(w * 0.3f, w * 0.3f), androidx.compose.ui.geometry.Size(size.width - w * 0.6f, size.height - w * 0.6f), style = androidx.compose.ui.graphics.drawscope.Stroke(w * 0.28f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${(overall * 100).toInt()}", color = Txt, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = if (compact) 34.sp else 56.sp, letterSpacing = (-2).sp)
                    Text("%", color = BlueBright, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = if (compact) 14.sp else 20.sp, modifier = Modifier.padding(bottom = if (compact) 6.dp else 11.dp, start = 2.dp))
                }
            }
            Spacer(Modifier.height(if (compact) 10.dp else 22.dp))
            Mono(if (failed) "Something went wrong" else "First-time set-up · only happens once", 10.sp, if (failed) Amber else BlueBright, 0.22f)
            Spacer(Modifier.height(6.dp))
            Text(headline, color = Txt, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = if (compact) 18.sp else 26.sp, letterSpacing = (-0.4).sp)
            Spacer(Modifier.height(if (compact) 12.dp else 26.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val cw = if (compact) 190.dp else 268.dp
                LoadStep(cw, Lucide.Tv, "Live TV channels", if (channelsDone) "%,d".format(chans) else "…", if (channelsDone) "channels found" else "asking your service", chF, channelsDone, false)
                LoadStep(cw, Lucide.CalendarDays, "TV guide", if (liveReady && gPr == 0) "Ready" else "%,d".format(gPr), when { liveReady && gPr == 0 -> "loaded from this box"; liveReady -> "programmes saved"; !channelsDone -> "waiting for channels"; else -> "programmes · ${"%,d".format(gCh)} channels read" }, gF, liveReady, false)
                LoadStep(cw, Lucide.Film, "Movies & TV shows", when { failed -> "!"; homeReady && cat != null -> "%,d".format(cat.movies.size + cat.series.size); bytes > 0 -> "%.1f MB".format(bytes / 1_000_000f); else -> "…" },
                    when { failed -> homeError.orEmpty(); homeReady && cat != null -> "${"%,d".format(cat.movies.size)} movies · ${"%,d".format(cat.series.size)} shows"; bytes > 0 -> "downloaded so far"; else -> "starting download" }, catF, homeReady && !failed, failed)
            }
            if (failed) {
                Spacer(Modifier.height(22.dp))
                val retryReq = remember { FocusRequester() }; FocusOnShow(retryReq, homeError)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Pill("Try again", white = true, requester = retryReq, onClick = onRetry)
                    Pill("Continue to Live TV", onClick = onContinue)
                }
            } else {
                Spacer(Modifier.height(if (compact) 10.dp else 22.dp))
                Text("Next time Reborn Play opens straight away and keeps itself up to date in the background.", color = Txt3, fontFamily = Geist, fontSize = 12.5.sp)
            }
        }
        Mono("Reborn Play v$version", 10.sp, Txt3, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 28.dp + safeInset(), bottom = 20.dp + safeInset()))
    }
}

@Composable
private fun LoadStep(width: androidx.compose.ui.unit.Dp, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, big: String, detail: String, fraction: Float, done: Boolean, failed: Boolean) {
    val f by androidx.compose.animation.core.animateFloatAsState(fraction.coerceIn(0f, 1f), androidx.compose.animation.core.tween(500), label = "s")
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.width(width).clip(shape).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(C(0xFF16233F), C(0xFF0D1629)))).border(1.dp, if (done) Blue.copy(alpha = 0.55f) else if (failed) Amber.copy(alpha = 0.6f) else C.White.copy(alpha = 0.08f), shape).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(if (done) Blue.copy(alpha = 0.2f) else C.White.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Icon(icon, null, Modifier.size(16.dp), tint = if (done) BlueBright else if (failed) Amber else Txt2)
            }
            Spacer(Modifier.width(10.dp))
            Text(label, color = if (done) Txt else Txt2, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1)
            if (done) androidx.compose.material3.Icon(Lucide.CircleCheck, null, Modifier.size(17.dp), tint = BlueBright)
            else if (failed) androidx.compose.material3.Icon(Lucide.CircleAlert, null, Modifier.size(17.dp), tint = Amber)
            else Mono("${(f * 100).toInt()}%", 10.sp, BlueBright)
        }
        Spacer(Modifier.height(12.dp))
        Text(big, color = Txt, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 26.sp, letterSpacing = (-0.8).sp, maxLines = 1)
        Text(detail, color = if (failed) Amber else Txt3, fontFamily = Geist, fontSize = 11.5.sp, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(C.White.copy(alpha = 0.08f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(f.coerceAtLeast(0.03f)).clip(CircleShape).background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Blue, BlueBright))))
        }
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
