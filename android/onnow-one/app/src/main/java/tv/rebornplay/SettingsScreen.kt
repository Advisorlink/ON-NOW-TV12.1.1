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
    Sec("appearance", "Appearance", "Cover, Live TV & mini player size", Lucide.Palette),
    Sec("home", "Home screen", "Row order, show / hide", Lucide.LayoutGrid),
    Sec("playback", "Playback", "Auto play, trailers", Lucide.Play),
    Sec("livetv", "Live TV", "Channels, guide, player", Lucide.RadioTower),
    Sec("profiles", "Profiles", "Who's watching", Lucide.UsersRound),
    Sec("catalogue", "Catalogue", "Movies & shows data", Lucide.Database),
    Sec("about", "About", "Version & build", Lucide.Info),
    Sec("signout", "Sign out", "End this session", Lucide.LogOut),
)

/** Settings.jsx — Back pill, sticky glass section rail on the left, compact setting cards on the right. */
@Composable
fun SettingsScreen(activity: Activity, autoplay: Boolean, onAutoplay: () -> Unit, onProfiles: () -> Unit, onSignOut: () -> Unit, onRefreshLive: () -> Unit, onHomeRows: () -> Unit = {}, onPickLiveCategory: () -> Unit = {}, onBack: () -> Unit = {}) {
    val ctx = LocalContext.current
    var section by rememberSaveable { mutableStateOf("appearance") }
    val first = remember { FocusRequester() }
    FocusOnShow(first, Unit)
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
                        "appearance" -> AppearanceSection()
                        "home" -> HomeSection(onHomeRows, onPickLiveCategory)
                        "playback" -> PlaybackSection(autoplay, onAutoplay)
                        "livetv" -> {
                            SectionHead("Settings · Live TV", "Channels, guide and player", Lucide.RadioTower)
                            ActionRow("Live TV settings", "Channel groups (reorder, hide, favourites), guide refresh and time offset, player engine, buffer and decoding, subtitles and audio language, interface size, parental PIN.", "Open") { LiveTvEntry.open(activity, settings = true) }
                            ActionRow("Refresh channels and guide", "Re-downloads your channel list and the programme guide from the service.", "Refresh", onClick = onRefreshLive)
                        }
                        "profiles" -> {
                            val user = remember { RebornSession.username(ctx) ?: "" }
                            val profile = remember { Profiles.active(ctx) }
                            SectionHead("Settings · Profiles", "Who's watching", Lucide.UsersRound)
                            ActionRow("Signed in as", user, null) {}
                            ActionRow("Profile", "Each profile keeps its own My List and Continue Watching.", profile?.name ?: "Switch", onClick = onProfiles)
                        }
                        "catalogue" -> {
                            SectionHead("Settings · Catalogue", "Movies & shows data", Lucide.Database)
                            val c = Gorilla.catalog
                            ActionRow("Refresh movies & shows", if (c != null) "${"%,d".format(c.movies.size)} movies · ${"%,d".format(c.series.size)} series cached on this device." else "Re-downloads the catalogue from the service.", "Refresh") { Cache.clear(); Gorilla.clearCache(ctx); BoxSets.reset() }
                        }
                        "about" -> {
                            val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }
                            SectionHead("Settings · About", "Reborn Play", Lucide.Info)
                            ActionRow("Version", "Reborn Play $version · native build", null) {}
                        }
                        "signout" -> {
                            SectionHead("Settings · Account", "Sign out", Lucide.LogOut)
                            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.04f)).border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp)).padding(14.dp)) {
                                Text("Clears this device's sign-in. Your profiles and lists stay backed up in the cloud and come straight back when you sign in again.", color = Txt2, fontFamily = Geist, fontSize = 12.sp, lineHeight = 17.sp)
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
    val ctx = LocalContext.current
    SectionHead("Settings · Home screen", "Arrange your For You page", Lucide.LayoutGrid)
    ActionRow("Home rows", "Move rows up or down and hide the ones you skip — the Live TV row can sit on any line.", "Customise", onClick = onHomeRows)
    SubHead("Live TV row")
    var src by remember { mutableStateOf(LiveRowPrefs.source(ctx)) }
    var look by remember { mutableStateOf(LiveRowPrefs.style(ctx)) }
    val catName = LiveStore.categories(ctx).firstOrNull { it.id == LiveRowPrefs.categoryId(ctx) }?.name ?: "Not set"
    ActionRow("What the row shows", "Favourites · live sport right now · what's on now · or one category of your choice.", LiveRowPrefs.sourceLabel(src)) { src = LiveRowPrefs.cycleSource(ctx); Cache.home = null }
    ActionRow("Category for the row", "Used when the row is set to \"A category I pick\".", catName, onClick = onPickLiveCategory)
    ActionRow("How the row looks", "Channel cards, big programme cards or compact logos.", LiveRowPrefs.styleLabel(look)) { look = LiveRowPrefs.cycleStyle(ctx); Cache.home = null }
}

@Composable
private fun PlaybackSection(autoplay: Boolean, onAutoplay: () -> Unit) {
    val ctx = LocalContext.current
    SectionHead("Settings · Playback", "Auto play & trailers", Lucide.Play)
    ToggleRow("Auto play", "Play straight away from the hero and Continue Watching; off asks first.", autoplay, onAutoplay)
    var covers by remember { mutableStateOf(Prefs.coversOnly(ctx)) }
    ToggleRow("Pop-out preview", "Resting on a cover slides it open into a wide card with its backdrop and trailer. Off = covers only, no wide card, no trailers.", !covers) { covers = !covers; Prefs.setCoversOnly(ctx, covers) }
    var delay by remember { mutableStateOf(Prefs.trailerDelayMs(ctx)) }
    var speed by remember { mutableStateOf(Prefs.expandMs(ctx)) }
    ActionRow("Trailer delay", "How long you rest on a title before its trailer starts playing in the cover.", Prefs.trailerDelayLabel(delay)) { delay = Prefs.cycleTrailerDelay(ctx) }
    ActionRow("Pop-out speed", "How quickly a focused cover slides open into its wide card.", Prefs.expandLabel(speed)) { speed = Prefs.cycleExpandMs(ctx) }
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
private fun AppearanceSection() {
    val ctx = LocalContext.current
    var scale by Prefs.cardScaleState(ctx)
    SectionHead("Settings · Appearance", "Sizes", Lucide.Palette)
    Text("Move left / right on the slider to shrink or grow every cover on Home and Box Sets — it applies straight away, no restart. The preview below is the real card.", color = Txt2, fontFamily = Geist, fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(bottom = 14.dp))
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
