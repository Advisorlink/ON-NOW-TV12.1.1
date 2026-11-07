package tv.rebornplay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.RocketLaunch
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage

/** The glass pill menu centred along the top — icon-only buttons, label pops in under the focused one. */
@Composable
fun TopNav(activeId: String, autoplay: Boolean, rowNav: RowNav? = null, onItem: (NavItem) -> Unit, onAutoplay: () -> Unit, onSpeedUp: () -> Unit, onSpeedTest: () -> Unit, onProfile: () -> Unit) {
    val ctx = LocalContext.current
    val profile = remember { Profiles.active(ctx) }
    var hover by remember { mutableStateOf<Pair<String, Float>?>(null) }   // label + centre x (px)
    val hv: (String, Float, Boolean) -> Unit = { l, x, f -> if (f) hover = l to x else if (hover?.first == l) hover = null }
    val density = LocalDensity.current
    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 1000
    Box(Modifier.fillMaxWidth().padding(top = (if (wide) 14.dp else 8.dp) + safeInset() / 2, start = safeInset(), end = safeInset()).zIndex(10f)) {
        if (wide) BrandLogo(46.dp, Modifier.align(Alignment.TopStart).padding(start = 56.dp, top = 2.dp))
        Row(Modifier.align(Alignment.TopCenter).then(if (wide) Modifier else Modifier.padding(horizontal = 8.dp).horizontalScroll(rememberScrollState())).glass().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            NAV_ITEMS.forEach { item ->
                NavButton(item.icon, item.label, item.id == activeId, onHover = hv, rowNav = rowNav, isHome = item.id == "home") { onItem(item) }
            }
            Spacer(Modifier.width(6.dp)); Box(Modifier.width(1.dp).height(22.dp).background(Line)); Spacer(Modifier.width(6.dp))
            NavButton(Icons.Outlined.PlayCircle, if (autoplay) "Auto play · ON" else "Auto play · OFF", autoplay, accent = true, onHover = hv, rowNav = rowNav, onClick = onAutoplay)
            NavButton(Icons.Outlined.RocketLaunch, "Speed up", false, onHover = hv, rowNav = rowNav, onClick = onSpeedUp)
            NavButton(Icons.Outlined.Speed, "Speed test", false, onHover = hv, rowNav = rowNav, onClick = onSpeedTest)
            Focusable(Modifier.padding(start = 2.dp), onClick = onProfile, onFocus = { if (it) hover = "Switch profile" to -1f else if (hover?.first == "Switch profile") hover = null }) { f ->
                Box(Modifier.size(40.dp).clip(CircleShape).border(if (f) 2.dp else 0.dp, if (f) BlueBright else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
                    if (profile != null) AsyncImage(Avatars.url(profile.avatarId), profile.name, Modifier.size(28.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                    else Box(Modifier.size(28.dp).clip(CircleShape).background(Bg2))
                }
            }
        }
        hover?.let { (label, cx) ->
            var w by remember { mutableStateOf(0) }
            val x = if (cx < 0) null else with(density) { (cx - w / 2f).toDp() }
            Box(Modifier.padding(top = 58.dp).then(if (x != null) Modifier.offset(x = x) else Modifier.align(Alignment.TopCenter))
                .onGloballyPositioned { w = it.size.width }.glass().padding(horizontal = 12.dp, vertical = 4.dp)) {
                Text(label, color = Txt, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
private fun NavButton(icon: ImageVector, label: String, active: Boolean, accent: Boolean = false, onHover: (String, Float, Boolean) -> Unit, rowNav: RowNav? = null, isHome: Boolean = false, onClick: () -> Unit) {
    var cx by remember { mutableStateOf(0f) }
    val req = remember { FocusRequester() }
    if (isHome) rowNav?.register(-1, req)
    Focusable(Modifier.onGloballyPositioned { cx = it.positionInRoot().x + it.size.width / 2f }, onClick = onClick, requester = req,
        onFocus = { onHover(label, cx, it); if (it) rowNav?.focused(-1, req) }) { f ->
        val color = if (active) (if (accent) Amber else Blue) else Txt2
        Box(Modifier.size(40.dp).clip(CircleShape)
            .background(if (f) BlueBright else if (active) color.copy(alpha = 0.14f) else Color.Transparent), contentAlignment = Alignment.Center) {
            Icon(icon, label, Modifier.size(20.dp), tint = if (f) Bg0 else color)
        }
    }
}

/** Same menu, hosted inside the native Live TV guide (installed via RebornTopNav.hostBar). */
fun installLiveTvHostBar() {
    tv.onnowtv.livetv.RebornTopNav.hostBar = { activity, navigate ->
        val v = androidx.compose.ui.platform.ComposeView(activity).apply {
            setContent {
                androidx.compose.material3.MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(primary = Blue, background = Bg0, surface = Bg1)) {
                    var autoplay by remember { mutableStateOf(Prefs.autoplay(activity)) }
                    Box(Modifier.fillMaxWidth().height(72.dp).background(Bg0)) {
                        TopNav("live", autoplay, onItem = { item -> if (item.id != "live") navigate(routeFor(item)) },
                            onAutoplay = { autoplay = !autoplay; Prefs.setAutoplay(activity, autoplay) },
                            onSpeedUp = { tv.vesper.app.BoxBoost.show(activity, auto = false) }, onSpeedTest = {}, onProfile = { navigate("/profiles") })
                    }
                }
            }
        }
        v to 72
    }
}

fun routeFor(item: NavItem) = when (item.id) { "home" -> "/"; "tv" -> "/tv"; "movies" -> "/movies"; else -> "/" + item.id }
fun routeToNav(r: String): Route? = when {
    r.startsWith("/profiles") -> null
    r.startsWith("/movies") -> Route.Tab("movie"); r.startsWith("/tv") -> Route.Tab("series")
    r.startsWith("/search") -> Route.Search; r.startsWith("/boxsets") -> Route.BoxSets; r.startsWith("/anime") -> Route.Anime
    r.startsWith("/library") -> Route.Library; r.startsWith("/calendar") -> Route.Calendar; r.startsWith("/watch-together") -> Route.WatchTogether
    r.startsWith("/settings") -> Route.Settings; r.startsWith("/live") -> Route.Live; else -> Route.Home
}
