package tv.rebornplay

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.input.key.*
import com.composables.icons.lucide.*
import tv.vesper.app.LiveTvEntry

private data class Sec(val id: String, val label: String, val hint: String, val icon: ImageVector)
private val SECTIONS = listOf(
    Sec("appearance", "Appearance", "Sizes, covers, trailers", Lucide.Palette),
    Sec("home", "Home screen", "Arrange your rows", Lucide.LayoutGrid),
    Sec("playback", "Playback", "Autoplay", Lucide.Play),
    Sec("livetv", "Live TV", "Player, guide, groups, PIN", Lucide.RadioTower),
    Sec("profiles", "Profiles", "Who's watching", Lucide.UsersRound),
    Sec("catalogue", "Movies & shows", "Refresh the list", Lucide.Database),
    Sec("about", "About", "Version & build", Lucide.Info),
    Sec("signout", "Sign out", "End this session", Lucide.LogOut),
)

/** Settings.jsx — Back pill, sticky glass section rail on the left, compact setting cards on the right. */
@Composable
fun SettingsScreen(activity: Activity, autoplay: Boolean, onAutoplay: () -> Unit, onProfiles: () -> Unit, onSignOut: () -> Unit, onRefreshLive: () -> Unit, onRefreshCatalogue: () -> Unit = {}, onHomeRows: () -> Unit = {}, onPickLiveCategory: () -> Unit = {}, onBack: () -> Unit = {}) {
    val ctx = LocalContext.current
    var section by rememberSaveable { mutableStateOf("appearance") }
    val first = remember { FocusRequester() }
    FocusOnShow(first, Unit)
    LocalRowNav.current?.fallback = first
    val inset = safeInset()
    Column(Modifier.fillMaxSize().padding(start = 64.dp + inset, end = 48.dp + inset, top = 84.dp)) {
        Pill("Back", icon = Lucide.ArrowLeft, mono = true, height = 34.dp, onClick = onBack)
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Column(Modifier.width(250.dp).glass(RoundedCornerShape(18.dp)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SECTIONS.forEachIndexed { i, s -> RailItem(s, section == s.id, if (i == 0) first else null) { section = s.id } }
            }
            LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(bottom = 80.dp)) {
                item {
                    when (section) {
                        "appearance" -> AppearanceSection(onPickLiveCategory)
                        "home" -> HomeSection(onHomeRows, onPickLiveCategory)
                        "playback" -> PlaybackSection(autoplay, onAutoplay)
                        "livetv" -> {
                            SectionHead("Settings · Live TV", "Live TV", Lucide.RadioTower)
                            ActionRow("Refresh Live TV", "Asks whether to refresh the channel list or the TV guide, then updates just that one. Movies and TV shows are not touched.", "Refresh", onClick = onRefreshLive)
                            LiveSettings()
                        }
                        "profiles" -> {
                            val user = remember { RebornSession.username(ctx) ?: "" }
                            val profile = remember { Profiles.active(ctx) }
                            SectionHead("Settings · Profiles", "Who's watching", Lucide.UsersRound)
                            ActionRow("Signed in as", user, null) {}
                            ActionRow("Profile", "Each profile keeps its own My List and Continue Watching.", profile?.name ?: "Switch", onClick = onProfiles)
                        }
                        "catalogue" -> {
                            SectionHead("Settings · Movies & shows", "Your movies and TV shows", Lucide.Database)
                            val c = Gorilla.catalog
                            ActionRow("Refresh movies & shows", if (c != null) "${"%,d".format(c.movies.size)} movies · ${"%,d".format(c.series.size)} TV shows saved on this device. Refresh downloads the list again from your service." else "Downloads the list again from your service.", "Refresh", onClick = onRefreshCatalogue)
                        }
                        "about" -> {
                            val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }
                            SectionHead("Settings · About", "Reborn Play", Lucide.Info)
                            ActionRow("Version", "Reborn Play $version", null) {}
                        }
                        "signout" -> {
                            SectionHead("Settings · Account", "Sign out", Lucide.LogOut)
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.04f)).border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp)).padding(14.dp)) {
                                Text("Signs this device out. Your profiles, lists and Continue Watching stay saved to your login and come back when you sign in again.", color = Txt2, fontFamily = Geist, fontSize = 12.sp, lineHeight = 17.sp)
                                Spacer(Modifier.height(14.dp))
                                Pill("Sign out", icon = Lucide.LogOut, height = 40.dp, fontSize = 14.sp, onClick = onSignOut)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeSection(onHomeRows: () -> Unit, onPickLiveCategory: () -> Unit) {
    SectionHead("Settings · Home screen", "Arrange your rows", Lucide.LayoutGrid)
    Text("Use the arrows to move a row up or down, and the eye to hide it. Changes apply the next time Home opens.", color = Txt2, fontFamily = Geist, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(bottom = 14.dp))
    HomeRowsList()
}

@Composable
private fun PlaybackSection(autoplay: Boolean, onAutoplay: () -> Unit) {
    SectionHead("Settings · Playback", "Autoplay", Lucide.Play)
    ToggleRow("Autoplay", "On: the featured title and Continue Watching start playing straight away. Off: you are asked first.", autoplay, onAutoplay)
}

@Composable
private fun RailItem(s: Sec, active: Boolean, requester: FocusRequester?, onPick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Focusable(onClick = onPick, requester = requester, onFocus = { if (it) onPick() }) { f ->
        Row(Modifier.fillMaxWidth().tileFocus(f, 12.dp).clip(shape).background(if (active) Blue.copy(alpha = 0.16f) else Color.Transparent)
            .border(1.dp, if (active) Blue.copy(alpha = 0.45f) else Color.Transparent, shape).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(if (active) Blue else Color.White.copy(alpha = 0.06f)), contentAlignment = Alignment.Center) {
                Icon(s.icon, null, Modifier.size(16.dp), tint = if (active) Bg0 else Txt)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(s.label, color = if (active) Txt else Txt2, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 16.sp)
                Text(s.hint, color = Txt3, fontFamily = Geist, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SectionHead(eyebrow: String, title: String, icon: ImageVector) {
    Mono(eyebrow, 10.sp, Blue, 0.32f, Modifier.padding(top = 4.dp, bottom = 6.dp))
    Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = Blue); Spacer(Modifier.width(8.dp))
        Text(title, color = Txt, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 21.sp, letterSpacing = (-0.4).sp)
    }
}

@Composable
private fun SubHead(title: String) = Text(title, color = Txt, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 18.sp, letterSpacing = (-0.3).sp, modifier = Modifier.padding(top = 24.dp, bottom = 10.dp))

@Composable
private fun Card(onClick: () -> Unit, content: @Composable RowScope.(Boolean) -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Focusable(Modifier.padding(bottom = 8.dp), onClick = onClick) { f ->
        Row(Modifier.fillMaxWidth().tileFocus(f, 10.dp).clip(shape).background(Color.White.copy(alpha = if (f) 0.07f else 0.04f)).border(1.dp, Color.White.copy(alpha = 0.08f), shape)
            .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) { content(f) }
    }
}

/** ToggleRow (Settings.jsx) — title + description + the 38×22 switch. */
@Composable
private fun ToggleRow(title: String, desc: String, value: Boolean, onToggle: () -> Unit) = Card(onToggle) { _ ->
    Column(Modifier.weight(1f)) {
        Text(title, color = Txt, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        Text(desc, color = Txt2, fontFamily = Geist, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 3.dp))
    }
    Spacer(Modifier.width(16.dp))
    Box(Modifier.size(38.dp, 22.dp).clip(CircleShape).background(if (value) Blue else Color.White.copy(alpha = 0.12f))) {
        Box(Modifier.padding(3.dp).size(16.dp).align(if (value) Alignment.CenterEnd else Alignment.CenterStart).clip(CircleShape).background(Color.White))
    }
}

@Composable
private fun ActionRow(title: String, desc: String, action: String?, onClick: () -> Unit) = Card(onClick) { f ->
    Column(Modifier.weight(1f)) {
        Text(title, color = Txt, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        if (desc.isNotBlank()) Text(desc, color = Txt2, fontFamily = Geist, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 3.dp))
    }
    if (action != null) {
        Spacer(Modifier.width(16.dp))
        Box(Modifier.clip(CircleShape).background(if (f) Blue else Color.White.copy(alpha = 0.08f)).border(1.dp, if (f) Color.Transparent else Color.White.copy(alpha = 0.12f), CircleShape).padding(horizontal = 12.dp, vertical = 6.dp)) {
            Mono(action, 10.sp, if (f) Bg0 else Txt, 0.16f)
        }
    }
}

/** Appearance — D-pad slider for cover size with a live preview of the cards underneath; Save writes it for Home / Box Sets / Library. */
@Composable
private fun AppearanceSection(onPickLiveCategory: () -> Unit) {
    val ctx = LocalContext.current
    var scale by Prefs.cardScaleState(ctx)
    SectionHead("Settings · Appearance", "Sizes", Lucide.Palette)
    Text("Move left or right on a slider to make things smaller or bigger. Changes apply straight away; the preview below shows the real cover size.", color = Txt2, fontFamily = Geist, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(bottom = 14.dp))
    val ui by Prefs.uiScaleState(ctx)
    SliderRow("Whole app size", ui, 0.75f, 1.3f, "Makes everything — text, menus, covers, every screen — smaller or bigger to suit your TV.") { Prefs.setUiScale(ctx, it) }
    SliderRow("Cover size", scale, 0.75f, 1.25f) { Prefs.setCardScale(ctx, it) }
    val live by Prefs.liveScaleState(ctx)
    SliderRow("Live TV size", live, 0.8f, 1.4f, "Bigger = larger preview window, text and rows on the Live TV screen (fewer channels per page).") { Prefs.setLiveScale(ctx, it) }
    val pip by Prefs.pipScaleState(ctx)
    SliderRow("Mini player size", pip, 0.6f, 1.6f, "The small live window that stays on the Movies & TV screen after you come back from a channel.") { Prefs.setPipScale(ctx, it) }
    SubHead("Preview")
    val sample = remember { (Cache.home?.heroes?.map { it.item } ?: Gorilla.catalog?.movies?.take(8) ?: emptyList()).take(6) }
    val base = 150.dp
    val h = base * scale
    if (sample.isEmpty()) Text("Covers appear here once the catalogue has loaded.", color = Txt3, fontFamily = Geist, fontSize = 12.sp)
    else Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Bg0).border(1.dp, Color.White.copy(alpha = 0.06f), RoundedCornerShape(14.dp)).padding(vertical = 18.dp)) {
        Mono("Trending now · live preview", 10.sp, Blue, 0.32f, Modifier.padding(start = 18.dp, bottom = 10.dp))
        Row(Modifier.padding(start = 18.dp).height(h + 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            sample.forEachIndexed { i, it ->
                Box(Modifier.width(if (i == 1) h * 16 / 9 else h * 2 / 3).height(h).then(if (i == 1) Modifier.tileFocus(true, 12.dp) else Modifier).clip(TileShape).background(Bg2).border(1.dp, Color.White.copy(alpha = 0.05f), TileShape)) {
                    coil.compose.AsyncImage(if (i == 1) (it.background ?: it.poster) else it.poster, it.name, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                    if (i == 1) Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(0.4f to Color.Transparent, 1f to Bg0.copy(alpha = 0.9f))))
                    if (i == 1) Text(it.name, color = Txt, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
                }
            }
        }
    }
    SubHead("Covers & trailers")
    var dvd by remember { mutableStateOf(Prefs.dvdMode(ctx)) }
    ActionRow("3D effect", "Covers on the Movies & TV screen become 3D DVD cases. Horizontal: a flat row. Distance: the row runs off into the distance and OK opens the case with the disc spinning.", Prefs.dvdModeLabel(dvd)) { dvd = Prefs.cycleDvdMode(ctx) }
    var dvdOff by remember { mutableStateOf(Prefs.dvdOffset(ctx)) }
    ActionRow("3D Distance: height", "Moves the heading, synopsis, buttons and covers down (+) or up (−) together. Press OK to step through; tell us the number you like.", (if (dvdOff > 0) "+" else "") + dvdOff) { dvdOff = Prefs.cycleDvdOffset(ctx) }
    var dvdSlot by remember { mutableStateOf(Prefs.dvdSlot(ctx)) }
    ActionRow("3D Distance: highlighted position", "Which cover in the row is the one that pops out, counted from the right.", listOf("1st", "2nd", "3rd", "4th", "5th")[dvdSlot - 1] + " from right") { dvdSlot = Prefs.cycleDvdSlot(ctx) }
    var dvdGap by remember { mutableStateOf(Prefs.dvdGap(ctx)) }
    ActionRow("3D Distance: spacing", "How far apart the cases stand. Lower = closer together.", "$dvdGap") { dvdGap = Prefs.cycleDvdGap(ctx) }
    var dvdDelay by remember { mutableStateOf(Prefs.dvdDelayMs(ctx)) }
    ActionRow("3D open delay", "How long you rest on a 3D case before it opens by itself.", Prefs.popDelayLabel(dvdDelay)) { dvdDelay = Prefs.cycleDvdDelay(ctx) }
    var covers by remember { mutableStateOf(Prefs.coversOnly(ctx)) }
    ToggleRow("Pop-out preview", "Resting on a cover slides it open into a wide card with its backdrop and trailer. Off = covers only, no wide card, no trailers.", !covers) { covers = !covers; Prefs.setCoversOnly(ctx, covers) }
    var delay by remember { mutableStateOf(Prefs.trailerDelayMs(ctx)) }
    var speed by remember { mutableStateOf(Prefs.expandMs(ctx)) }
    var pop by remember { mutableStateOf(Prefs.popDelayMs(ctx)) }
    ActionRow("Pop-out delay", "How long you rest on a cover before it slides open. Scroll past covers faster than this and they stay as plain covers.", Prefs.popDelayLabel(pop)) { pop = Prefs.cyclePopDelay(ctx) }
    ActionRow("Trailer delay", "How long you rest on a title before its trailer starts playing in the cover (never before the cover has opened).", Prefs.trailerDelayLabel(delay)) { delay = Prefs.cycleTrailerDelay(ctx) }
    ActionRow("Pop-out speed", "How quickly a focused cover slides open into its wide card.", Prefs.expandLabel(speed)) { speed = Prefs.cycleExpandMs(ctx) }
    SubHead("Live TV row on Home")
    var src by remember { mutableStateOf(LiveRowPrefs.source(ctx)) }
    var look by remember { mutableStateOf(LiveRowPrefs.style(ctx)) }
    val catName = LiveStore.categories(ctx).let { cs -> cs.firstOrNull { it.id == LiveRowPrefs.categoryId(ctx) } ?: cs.firstOrNull { it.name.contains("sky sports", true) } }?.name ?: "Not set"
    ActionRow("What the row shows", "Favourites · live sport right now · what's on now · or one category of your choice.", LiveRowPrefs.sourceLabel(src)) { src = LiveRowPrefs.cycleSource(ctx); Cache.home = null }
    ActionRow("Category for the row", "Used when the row is set to \"A category I pick\".", catName, onClick = onPickLiveCategory)
    ActionRow("How the row looks", "Channel cards, big programme cards or compact logos.", LiveRowPrefs.styleLabel(look)) { look = LiveRowPrefs.cycleStyle(ctx); Cache.home = null }
}

/** D-pad slider: Left / Right move it in 5 % steps, OK resets to 100 %. Applies instantly. */
@Composable
private fun SliderRow(title: String, value: Float, min: Float, max: Float, desc: String? = null, onChange: (Float) -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Focusable(Modifier.padding(bottom = 14.dp).onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (e.key) {
            Key.DirectionLeft -> { onChange(value - 0.05f); true }
            Key.DirectionRight -> { onChange(value + 0.05f); true }
            else -> false
        }
    }, onClick = { onChange(1f) }) { f ->
        Column(Modifier.fillMaxWidth().tileFocus(f, 10.dp).clip(shape).background(Color.White.copy(alpha = if (f) 0.07f else 0.04f)).border(1.dp, Color.White.copy(alpha = 0.08f), shape).padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = Txt, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Mono("${Math.round(value * 100)} %", 11.sp, if (f) BlueBright else Txt2, 0.1f)
            }
            if (desc != null) Text(desc, color = Txt2, fontFamily = Geist, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 3.dp))
            Spacer(Modifier.height(10.dp))
            val frac = ((value - min) / (max - min)).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(22.dp), contentAlignment = Alignment.CenterStart) {
                Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.12f)))
                Box(Modifier.fillMaxWidth(frac).height(4.dp).clip(CircleShape).background(Blue))
                BoxWithConstraints(Modifier.fillMaxWidth()) { Box(Modifier.padding(start = (maxWidth - 18.dp) * frac).size(18.dp).clip(CircleShape).background(if (f) BlueBright else Color.White).border(2.dp, Bg0, CircleShape)) }
            }
            Spacer(Modifier.height(8.dp))
            Row { Mono("Smaller", 9.sp, Txt3, 0.16f); Spacer(Modifier.weight(1f)); Mono("Applies instantly · OK resets to 100 %", 9.sp, Txt3, 0.16f); Spacer(Modifier.weight(1f)); Mono("Bigger", 9.sp, Txt3, 0.16f) }
        }
    }
}

private fun <T> next(list: List<T>, cur: T): T = list[(list.indexOf(cur) + 1).mod(list.size)]

/** Everything the old Live TV settings screen had that still applies, as Reborn settings. OK on a row steps to its next option. */
@Composable
private fun LiveSettings() {
    val ctx = LocalContext.current
    val S = tv.onnowtv.livetv.data.Settings
    val Lock = tv.onnowtv.livetv.data.ParentalLock
    var t by remember { mutableStateOf(0) }
    fun on(b: Boolean) = if (b) "On" else "Off"
    key(t) {
        SubHead("Player")
        val eng = tv.onnowtv.livetv.data.PlayerPrefs.getBackend(ctx)
        ActionRow("Video engine", "ExoPlayer is fastest; LibVLC plays unusual streams.", if (eng.token == "exo") "ExoPlayer" else "LibVLC") { tv.onnowtv.livetv.data.PlayerPrefs.setBackend(ctx, next(tv.onnowtv.livetv.data.PlayerPrefs.Backend.values().toList(), eng)); t++ }
        ActionRow("Buffer size", "Larger buffers survive weak Wi-Fi but take longer to start.", S.bufferSize) { S.bufferSize = next(listOf("small", "normal", "large"), S.bufferSize); t++ }
        ActionRow("Decoding", "Try Software if the picture is green, black or stuttering.", if (S.softwareDecoding) "Software" else "Hardware") { S.softwareDecoding = !S.softwareDecoding; t++ }
        val langs = S.audioLanguages
        ActionRow("Preferred audio language", "", langs.firstOrNull { it.first == S.audioLanguage }?.second ?: "English") { S.audioLanguage = next(langs.map { it.first }, S.audioLanguage); t++ }
        ToggleRow("Subtitles", "Turn subtitles on by default when a channel has them.", S.subtitlesOn) { S.subtitlesOn = !S.subtitlesOn; t++ }
        val subs = langs.filter { it.first != "any" && it.first != "off" }
        ActionRow("Subtitle language", "", subs.firstOrNull { it.first == S.subtitleLanguage }?.second ?: "English") { S.subtitleLanguage = next(subs.map { it.first }, S.subtitleLanguage); t++ }
        val aspects = listOf("Fit", "Zoom", "Fill", "16:9")
        ActionRow("Default picture shape", "How the picture fills the screen when a channel starts.", aspects[S.defaultAspect.coerceIn(0, 3)]) { S.defaultAspect = (S.defaultAspect + 1) % 4; t++ }
        ActionRow("Up / Down while watching", "", if (S.playerUpDown == "channels") "Channel up / down" else "Up = channel, Down = controls") { S.playerUpDown = if (S.playerUpDown == "channels") "mixed" else "channels"; t++ }

        SubHead("Guide")
        ActionRow("Guide auto-update", "How often the TV guide refreshes on its own.", "Every ${S.epgIntervalHours} h") { S.epgIntervalHours = next(listOf(3, 6, 12, 24), S.epgIntervalHours); t++ }
        val offs = listOf(-120, -90, -60, -30, 0, 30, 60, 90, 120)
        ActionRow("Guide time offset", "Shift guide times if programmes show at the wrong hour. Applies after the guide is next refreshed.", S.epgOffsetMinutes.let { if (it == 0) "None" else "${if (it > 0) "+" else ""}$it min" }) { S.epgOffsetMinutes = next(offs, S.epgOffsetMinutes); t++ }
        ActionRow("Clock format", "", if (S.clock24) "24-hour" else "12-hour") { S.clock24 = !S.clock24; t++ }

        SubHead("Channel names")
        ActionRow("Channel order inside a group", "", if (S.channelSort == "az") "A – Z" else "Playlist order") { S.channelSort = if (S.channelSort == "az") "playlist" else "az"; t++ }
        ToggleRow("Clean up channel names", "Removes prefixes such as \"UK|\" or \"HD:\" from every channel. Example: \"UK| BBC One HD\" becomes \"${S.cleanName(ctx, "UK| BBC One HD")}\".", S.nameCleanup) { S.nameCleanup = !S.nameCleanup; t++ }

        SubHead("Parental control")
        var pinDialog by remember { mutableStateOf(false) }
        ActionRow(if (Lock.hasPin(ctx)) "Change parental PIN" else "Set parental PIN", "A 4-digit PIN that opens locked groups.", if (Lock.hasPin(ctx)) "Change" else "Set") { pinDialog = true }
        if (pinDialog) PinSetDialog(onClose = { pinDialog = false }) { Lock.setPin(ctx, it); pinDialog = false; t++ }

        SubHead("Channel groups")
        Text("Move a group up or down, hide it from Live TV, or lock it behind the parental PIN. Changes show the next time Live TV opens.", color = Txt2, fontFamily = Geist, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(bottom = 12.dp))
        val b = LiveStore.bundle.value
        if (b == null) Text("Live TV hasn't loaded yet.", color = Txt3, fontFamily = Geist, fontSize = 13.sp)
        else {
            val hidden = S.hiddenGroups(ctx); val locked = Lock.lockedIds(ctx); val order = S.groupOrder(ctx)
            val cats = b.categories.let { c -> if (order.isEmpty()) c else c.sortedBy { order.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } } }
            fun move(i: Int, d: Int) { val ids = cats.map { it.id }.toMutableList(); val j = i + d; if (j !in ids.indices) return; ids[i] = ids[j].also { ids[j] = ids[i] }; S.setGroupOrder(ctx, ids); t++ }
            cats.forEachIndexed { i, c ->
                val hid = c.id in hidden; val lk = c.id in locked; val shape = RoundedCornerShape(10.dp)
                Row(Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(shape).background(Color.White.copy(alpha = 0.04f)).border(1.dp, Color.White.copy(alpha = 0.08f), shape).padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(c.name, color = if (hid) Txt3 else Txt, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f))
                    if (hid) Mono("Hidden", 9.sp, Txt3, 0.16f, Modifier.padding(end = 10.dp))
                    if (lk) Mono("Locked", 9.sp, Amber, 0.16f, Modifier.padding(end = 10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CircleBtn(Lucide.ChevronUp, "Move up", size = 32.dp) { move(i, -1) }
                        CircleBtn(Lucide.ChevronDown, "Move down", size = 32.dp) { move(i, 1) }
                        CircleBtn(if (hid) Lucide.EyeOff else Lucide.Eye, if (hid) "Show" else "Hide", size = 32.dp) { S.setHiddenGroups(ctx, if (hid) hidden - c.id else hidden + c.id); t++ }
                        CircleBtn(if (lk) Lucide.Lock else Lucide.LockOpen, if (lk) "Unlock" else "Lock", size = 32.dp) {
                            if (!Lock.hasPin(ctx)) android.widget.Toast.makeText(ctx, "Set a parental PIN first", android.widget.Toast.LENGTH_SHORT).show()
                            else { if (lk) Lock.unlock(ctx, c.id) else Lock.lock(ctx, c.id); t++ }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PinSetDialog(onClose: () -> Unit, onSet: (String) -> Unit) {
    var a by remember { mutableStateOf("") }; var b by remember { mutableStateOf("") }; var err by remember { mutableStateOf<String?>(null) }
    val req = remember { FocusRequester() }
    FocusOnShow(req, Unit)
    fun save() { if (!a.matches(Regex("\\d{4}"))) err = "The PIN must be 4 digits" else if (a != b) err = "The two PINs don't match" else onSet(a) }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Column(Modifier.width(420.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF11182A)).border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(16.dp)).padding(24.dp)) {
            Mono("Parental control", 10.sp, Blue, 0.28f)
            Text("Set a 4-digit PIN", color = Txt, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.padding(top = 8.dp, bottom = 14.dp))
            Field(a, { if (it.length <= 4 && it.all { c -> c.isDigit() }) { a = it; err = null } }, "New PIN", Lucide.Lock, password = true, requester = req)
            Spacer(Modifier.height(10.dp))
            Field(b, { if (it.length <= 4 && it.all { c -> c.isDigit() }) { b = it; err = null } }, "Enter it again", Lucide.Lock, password = true, onDone = { save() })
            err?.let { Text(it, color = Color(0xFFFF8A8A), fontFamily = Geist, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("Save PIN", primary = true, height = 42.dp, fontSize = 14.sp) { save() }
                Pill("Cancel", height = 42.dp, fontSize = 14.sp, onClick = onClose)
            }
        }
    }
}
