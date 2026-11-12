package tv.rebornplay

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.composables.icons.lucide.*
import java.util.Calendar

/** Plain cover — no expand, no trailer. Used everywhere in My Library / actor pages / "not on your service yet". */
@Composable
fun CoverTile(item: Item, w: Dp = 132.dp, badge: ImageVector? = null, badgeTint: Color = Blue, requester: FocusRequester? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Focusable(onClick = onClick, requester = requester) { f ->
        Box(Modifier.width(w).aspectRatio(2f / 3f).focusScale(f, 1.06f).tileFocus(f, 10.dp).clip(shape).background(Bg2).border(1.dp, Color.White.copy(alpha = 0.06f), shape)) {
            if (item.poster != null) AsyncImage(item.poster, item.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text(item.name, color = Txt2, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).padding(8.dp))
            if (badge != null) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(24.dp).clip(CircleShape).background(Bg0.copy(alpha = 0.85f)).border(1.dp, badgeTint.copy(alpha = 0.6f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(badge, null, Modifier.size(12.dp), tint = badgeTint)
            }
        }
    }
}

@Composable
private fun LibSection(eyebrow: String, title: String, icon: ImageVector, content: @Composable () -> Unit) {
    val inset = safeInset()
    Column(Modifier.padding(bottom = 40.dp)) {
        Mono(eyebrow, 11.sp, Blue, 0.32f, Modifier.padding(start = 64.dp + inset + 36.dp, bottom = 8.dp))
        Row(Modifier.padding(start = 64.dp + inset, bottom = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(24.dp), tint = Blue); Spacer(Modifier.width(12.dp))
            Text(title, color = Txt, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 32.sp, letterSpacing = (-0.8).sp)
        }
        content()
    }
}

@Composable
private fun LibNote(text: String) {
    val inset = safeInset()
    Box(Modifier.padding(start = 64.dp + inset, end = 48.dp + inset).fillMaxWidth(0.6f).glass(RoundedCornerShape(12.dp)).padding(horizontal = 18.dp, vertical = 16.dp)) {
        Text(text, color = Txt2, fontFamily = Geist, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

/** Wrapping cover grid (Vesper FavouriteGrid) — rows of plain covers. */
@Composable
private fun CoverGrid(items: List<Item>, cols: Int, badge: ImageVector? = null, badgeTint: Color = Blue, onOpen: (Item) -> Unit) {
    val inset = safeInset()
    Column(Modifier.padding(start = 64.dp + inset, end = 48.dp + inset), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        items.chunked(cols).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) { row.forEach { CoverTile(it, badge = badge, badgeTint = badgeTint) { onOpen(it) } } } }
    }
}

/** Library.jsx — header (back · title · Calendar · Notifications), then Continue Watching, TV Shows, Movies, Actors, Waiting for HD. Covers only. */
@Composable
fun LibraryScreen(onBack: () -> Unit = {}, onResume: (CwEntry) -> Unit = {}, onCalendar: () -> Unit = {}, onActor: (CastMember) -> Unit = {}, onOpen: (Item) -> Unit) {
    val ctx = LocalContext.current
    val items = remember { Library.items(ctx).reversed() }
    val cw = remember { ContinueWatching.entries(ctx) }
    val actors = remember { SavedActors.items(ctx).reversed() }
    var waiting by remember { mutableStateOf(NotifyList.items(ctx).reversed()) }
    var panel by remember { mutableStateOf(false) }
    val series = items.filter { it.type == "series" }
    val movies = items.filter { it.type != "series" }
    val back = remember { FocusRequester() }
    FocusOnShow(back, Unit)
    val inset = safeInset()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cols = (((maxWidth - 112.dp - inset * 2 + 18.dp) / 150.dp).toInt()).coerceAtLeast(2)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 84.dp, bottom = 100.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 64.dp + inset, end = 48.dp + inset, bottom = 30.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircleBtn(Lucide.ArrowLeft, "Back", requester = back, onClick = onBack)
                    Spacer(Modifier.width(20.dp))
                    Column {
                        Mono("Your collection", 11.sp, BlueBright, 0.32f); Spacer(Modifier.height(4.dp))
                        Text("My Library", color = Txt, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 44.sp, letterSpacing = (-1.5).sp)
                    }
                    Spacer(Modifier.weight(1f))
                    Pill("Calendar", icon = Lucide.CalendarDays, height = 44.dp, fontSize = 14.sp, onClick = onCalendar)
                    Spacer(Modifier.width(12.dp))
                    Pill(if (waiting.isEmpty()) "Notifications" else "Notifications · ${waiting.size}", icon = Lucide.Bell, height = 44.dp, fontSize = 14.sp) { panel = true }
                }
            }
            item { LibSection("My library · Continue watching" + if (cw.isNotEmpty()) " · ${cw.size}" else "", "Continue Watching", Lucide.History) {
                if (cw.isEmpty()) LibNote("Nothing in progress — anything you start shows up here with a resume bar.")
                else LazyRow(contentPadding = PaddingValues(start = 64.dp + inset, end = 48.dp + inset), horizontalArrangement = Arrangement.spacedBy(20.dp)) { items(cw, key = { it.cwId }) { e -> CwCard(e, 140.dp) { onResume(e) } } }
            } }
            item { LibSection("My library · Series" + if (series.isNotEmpty()) " · ${series.size}" else "", "TV Shows", Lucide.Tv) {
                if (series.isEmpty()) LibNote("No shows saved yet. Press My List on any series to keep it here — new episodes then appear in your Calendar.") else CoverGrid(series, cols, onOpen = onOpen)
            } }
            item { LibSection("My library · Movies" + if (movies.isNotEmpty()) " · ${movies.size}" else "", "Movies", Lucide.Film) {
                if (movies.isEmpty()) LibNote("No movies saved yet. Press My List on any film to keep it here.") else CoverGrid(movies, cols, onOpen = onOpen)
            } }
            item { LibSection("My library · Actors" + if (actors.isNotEmpty()) " · ${actors.size}" else "", "Actors", Lucide.Users) {
                if (actors.isEmpty()) LibNote("No actors saved yet. Open any title, pick someone from the cast and press Save actor.")
                else LazyRow(contentPadding = PaddingValues(start = 64.dp + inset, end = 48.dp + inset, top = 6.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) { items(actors, key = { it.id }) { a -> CastCard(a) { onActor(a) } } }
            } }
            item { LibSection("My library · Notify me" + if (waiting.isNotEmpty()) " · ${waiting.size}" else "", "Waiting for HD", Lucide.Bell) {
                if (waiting.isEmpty()) LibNote("Nothing on your notify list. Press Notify me on a Coming Soon title and a pop-up tells you the moment it lands here in HD.")
                else CoverGrid(waiting, cols, badge = Lucide.BellRing, badgeTint = Amber, onOpen = onOpen)
            } }
        }
        if (panel) NotifyPanel(waiting, onClose = { panel = false }, onOpen = { panel = false; onOpen(it) }, onRemove = { NotifyList.remove(ctx, it.id); waiting = NotifyList.items(ctx).reversed() })
    }
}

/** Library.jsx NotifyPopover — the notify list as a panel under the Notifications button. */
@Composable
private fun NotifyPanel(items: List<Item>, onClose: () -> Unit, onOpen: (Item) -> Unit, onRemove: (Item) -> Unit) {
    BackHandler(onBack = onClose)
    val first = remember { FocusRequester() }
    FocusOnShow(first, items.size)
    val shape = RoundedCornerShape(16.dp)
    Box(Modifier.fillMaxSize().background(Bg0.copy(alpha = 0.6f))) {
        Column(Modifier.align(Alignment.TopEnd).padding(top = 140.dp, end = 48.dp + safeInset()).width(420.dp).heightIn(max = 480.dp).clip(shape).background(Color(0xFF11182A)).border(1.dp, Blue.copy(alpha = 0.45f), shape).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Lucide.Bell, null, Modifier.size(14.dp), tint = Blue); Spacer(Modifier.width(8.dp)); Mono("Notify me · ${items.size}", 10.sp, Blue, 0.28f)
                Spacer(Modifier.weight(1f)); Pill("Close", icon = Lucide.X, height = 34.dp, fontSize = 12.sp, requester = if (items.isEmpty()) first else null, onClick = onClose)
            }
            Spacer(Modifier.height(10.dp))
            if (items.isEmpty()) Text("You'll get a pop-up in the top-right corner the moment a title you asked about arrives on your service in HD.", color = Txt2, fontFamily = Geist, fontSize = 13.sp, lineHeight = 19.sp)
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items.forEachIndexed { i, it ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.04f)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(44.dp).height(66.dp).clip(RoundedCornerShape(6.dp)).background(Bg2)) { if (it.poster != null) AsyncImage(it.poster, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(it.name, color = Txt, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Mono(it.category ?: it.year ?: "Waiting for HD", 9.sp, Txt3, 0.14f, Modifier.padding(top = 3.dp), upper = false)
                        }
                        Pill("Open", height = 34.dp, fontSize = 12.sp, requester = if (i == 0) first else null) { onOpen(it) }
                        Spacer(Modifier.width(6.dp))
                        CircleBtn(Lucide.Trash2, "Remove", size = 34.dp) { onRemove(it) }
                    }
                }
            }
        }
    }
}

/* ───────────────────────────── Cast ───────────────────────────── */

private val Grey = androidx.compose.ui.graphics.ColorFilter.colorMatrix(androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(0f) })

/** CastRow.jsx ActorCard — 2:3 portrait, black & white until focused, name + "as Character" on a bottom fade. */
@Composable
fun CastCard(m: CastMember, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Focusable(onClick = onClick) { f ->
        Box(Modifier.width(124.dp).aspectRatio(2f / 3f).focusScale(f, 1.06f).tileFocus(f, 12.dp).clip(shape).background(Brush.verticalGradient(listOf(Bg2, Bg1))).border(1.dp, Color.White.copy(alpha = 0.05f), shape)) {
            if (m.photo != null) AsyncImage(m.photo, m.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter, colorFilter = if (f) null else Grey, alpha = if (f) 1f else 0.92f)
            else Text(m.name.take(1).uppercase(), color = Blue.copy(alpha = 0.18f), fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 56.sp, modifier = Modifier.align(Alignment.Center))
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.42f).background(Brush.verticalGradient(0f to Color.Transparent, 0.78f to Color(0xED06080F), 1f to Color(0xFF06080F))))
            Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                Text(m.name, color = Txt, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (m.character.isNotBlank()) Mono("as ${m.character}", 8.sp, Txt2, 0.16f, Modifier.padding(top = 3.dp))
            }
        }
    }
}

/** Cast strip on the title page (CastRow.jsx). */
@Composable
fun CastRow(item: Item, onActor: (CastMember) -> Unit) {
    val ctx = LocalContext.current
    var cast by remember(item.id) { mutableStateOf<List<CastMember>>(emptyList()) }
    LaunchedEffect(item.id) { val t = Tmdb.resolve(ctx, item) ?: return@LaunchedEffect; cast = Tmdb.cast(item.type, t).take(16) }
    if (cast.isEmpty()) return
    Column(Modifier.padding(top = 26.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Lucide.Users, null, Modifier.size(14.dp), tint = Blue); Spacer(Modifier.width(8.dp)); Mono("Cast", 11.sp, Blue, 0.32f) }
        Spacer(Modifier.height(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 8.dp, horizontal = 4.dp)) { items(cast, key = { it.id }) { m -> CastCard(m) { onActor(m) } } }
    }
}

/** Actor page — their titles that are actually on the user's service. */
@Composable
fun ActorScreen(member: CastMember, onBack: () -> Unit, onOpen: (Item) -> Unit) {
    val ctx = LocalContext.current
    var person by remember { mutableStateOf<Person?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var titles by remember { mutableStateOf<List<Item>>(emptyList()) }
    var saved by remember { mutableStateOf(SavedActors.has(ctx, member.id)) }
    val back = remember { FocusRequester() }
    FocusOnShow(back, Unit)
    BackHandler(onBack = onBack)
    LaunchedEffect(member.id) {
        val p = Tmdb.person(member.id); person = p
        titles = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { p?.credits.orEmpty().mapNotNull { c -> Match.find(c.type, c.title, c.year?.toIntOrNull() ?: 0) }.distinctBy { it.id } }
        loaded = true
    }
    val inset = safeInset()
    val photo = person?.photo ?: member.photo
    BoxWithConstraints(Modifier.fillMaxSize().background(Bg0)) {
        val cols = (((maxWidth - 112.dp - inset * 2 + 18.dp) / 150.dp).toInt()).coerceAtLeast(2)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 40.dp, bottom = 80.dp)) {
            item {
                Row(Modifier.padding(start = 64.dp + inset, end = 48.dp + inset, bottom = 34.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.width(150.dp).height(200.dp).clip(RoundedCornerShape(14.dp)).background(Bg2).border(1.dp, Blue.copy(alpha = 0.25f), RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                        if (photo != null) AsyncImage(photo, member.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter) else Icon(Lucide.User, null, Modifier.size(48.dp), tint = Txt3)
                    }
                    Spacer(Modifier.width(28.dp))
                    Column(Modifier.weight(1f)) {
                        Mono("Actor", 11.sp, BlueBright, 0.32f); Spacer(Modifier.height(4.dp))
                        Text(person?.name?.ifBlank { member.name } ?: member.name, color = Txt, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 46.sp, letterSpacing = (-1.5).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val facts = listOfNotNull(person?.age?.let { "Age $it" }, person?.born).joinToString("  ·  ")
                        if (facts.isNotBlank()) Mono(facts, 10.sp, Txt3, 0.14f, Modifier.padding(top = 6.dp), upper = false)
                        if (!person?.bio.isNullOrBlank()) Text(person!!.bio, color = Txt2, fontFamily = Geist, fontSize = 14.sp, lineHeight = 21.sp, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp).fillMaxWidth(0.85f))
                        Spacer(Modifier.height(18.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Pill("Back", icon = Lucide.ArrowLeft, mono = true, height = 44.dp, requester = back, onClick = onBack)
                            Pill(if (saved) "Saved to My Library" else "Save actor", icon = if (saved) Lucide.Check else Lucide.Plus, primary = !saved, height = 44.dp, fontSize = 14.sp) { saved = SavedActors.toggle(ctx, CastMember(member.id, person?.name?.ifBlank { null } ?: member.name, "", photo)) }
                        }
                    }
                }
            }
            item {
                Mono(if (!loaded) "Finding their titles on your service…" else "On your service · ${titles.size} title${if (titles.size == 1) "" else "s"}", 11.sp, Blue, 0.32f, Modifier.padding(start = 64.dp + inset, bottom = 16.dp))
                if (loaded && titles.isEmpty()) LibNote("None of ${member.name}'s movies or shows are on your service right now.")
                else CoverGrid(titles, cols, onOpen = onOpen)
            }
        }
    }
}

/* ───────────────────────────── Notify pop-up ───────────────────────────── */

/** NotifyHitWatcher.jsx — top-right card when a title from the notify list has arrived in HD. One at a time. */
@Composable
fun NotifyHitToast(onWatch: (Item) -> Unit) {
    val ctx = LocalContext.current
    val hit = NotifyScan.hits.firstOrNull() ?: return
    val watch = remember(hit.wanted.id) { FocusRequester() }
    FocusOnShow(watch, hit.wanted.id)
    fun dismiss() { NotifyScan.hits.remove(hit) }
    BackHandler { dismiss() }
    val shape = RoundedCornerShape(16.dp)
    val art = hit.wanted.background ?: hit.found.background ?: hit.found.poster ?: hit.wanted.poster
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.align(Alignment.TopEnd).padding(top = 24.dp + safeInset() / 2, end = 24.dp + safeInset() / 2).width(400.dp).blueGlow(true, shape, 16.dp, 0.6f).clip(shape).background(Color(0xFF11182A)).border(1.dp, Blue.copy(alpha = 0.45f), shape)) {
            Box(Modifier.fillMaxWidth().height(150.dp).background(Bg2)) {
                if (art != null) AsyncImage(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color(0xE011182A))))
                Row(Modifier.align(Alignment.TopStart).padding(12.dp).clip(CircleShape).background(Bg0.copy(alpha = 0.8f)).border(1.dp, Blue.copy(alpha = 0.5f), CircleShape).padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Lucide.BellRing, null, Modifier.size(11.dp), tint = Blue); Spacer(Modifier.width(6.dp)); Mono("Now in HD", 9.sp, Blue, 0.24f)
                }
            }
            Column(Modifier.padding(start = 18.dp, end = 18.dp, bottom = 16.dp, top = 4.dp)) {
                Text(hit.found.name, color = Txt, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 24.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("The title you asked about has just arrived on your service.", color = Txt2, fontFamily = Geist, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill("Watch now", icon = Lucide.Play, primary = true, height = 42.dp, fontSize = 14.sp, requester = watch) { dismiss(); onWatch(hit.found) }
                    Pill("Save for later", icon = Lucide.BookmarkPlus, height = 42.dp, fontSize = 14.sp) { if (!Library.has(ctx, hit.found.id)) Library.toggle(ctx, hit.found); dismiss() }
                }
            }
        }
    }
}

/* ───────────────────────────── Calendar ───────────────────────────── */

private fun iso(y: Int, m: Int, d: Int) = "%04d-%02d-%02d".format(y, m + 1, d)
private fun todayIso(): String { val c = Calendar.getInstance(); return iso(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)) }
private fun plusDaysIso(n: Int): String { val c = Calendar.getInstance(); c.add(Calendar.DAY_OF_YEAR, n); return iso(c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)) }
private fun pretty(isoDate: String, pattern: String): String = runCatching {
    java.text.SimpleDateFormat(pattern, java.util.Locale.UK).format(java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.UK).parse(isoDate)!!)
}.getOrDefault(isoDate)
/** 6 × 7 Monday-first cells; 0 = padding. */
private fun monthCells(y: Int, m: Int): List<Int> {
    val c = Calendar.getInstance().apply { clear(); set(y, m, 1) }
    val lead = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7
    val total = c.getActualMaximum(Calendar.DAY_OF_MONTH)
    return List(42) { i -> val d = i - lead + 1; if (d in 1..total) d else 0 }
}

/** LibraryCalendar.jsx — "Coming up / Your calendar": month grid, selected-day panel, This week rail. Never scrolls. */
@Composable
fun CalendarScreen(onBack: () -> Unit, onOpen: (Item) -> Unit) {
    val ctx = LocalContext.current
    val series = remember { Library.items(ctx).filter { it.type == "series" } }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    var eps by remember { mutableStateOf<List<CalEpisode>>(emptyList()) }
    var byImdb by remember { mutableStateOf<Map<String, Item>>(emptyMap()) }
    val now = remember { Calendar.getInstance() }
    var year by remember { mutableStateOf(now.get(Calendar.YEAR)) }
    var month by remember { mutableStateOf(now.get(Calendar.MONTH)) }
    val today = remember { todayIso() }
    var selected by remember { mutableStateOf(today) }
    val back = remember { FocusRequester() }
    FocusOnShow(back, Unit)
    BackHandler(onBack = onBack)
    LaunchedEffect(Unit) {
        runCatching { Tmdb.calendar(ctx, series) }.onSuccess { (e, m) ->
            eps = e; byImdb = m
            e.firstOrNull()?.date?.let { d -> selected = d; val y = d.take(4).toInt(); val mo = d.substring(5, 7).toInt() - 1; if (y > year || (y == year && mo > month)) { year = y; month = mo } }
        }.onFailure { error = true }
        loading = false
    }
    val byDate = remember(eps) { eps.groupBy { it.date } }
    val week = remember(eps) { val end = plusDaysIso(7); eps.filter { it.date >= today && it.date <= end }.take(12) }
    fun step(d: Int) { var m = month + d; var y = year; if (m < 0) { m = 11; y-- }; if (m > 11) { m = 0; y++ }; month = m; year = y }
    val inset = safeInset()
    Column(Modifier.fillMaxSize().background(Color(0xFF06080F)).padding(start = 64.dp + inset, end = 48.dp + inset, top = 20.dp + inset / 2, bottom = 20.dp + inset / 2)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircleBtn(Lucide.ArrowLeft, "Close calendar", requester = back, onClick = onBack)
            Spacer(Modifier.width(20.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Lucide.CalendarDays, null, Modifier.size(13.dp), tint = BlueBright); Spacer(Modifier.width(8.dp)); Mono("Coming up" + if (eps.isNotEmpty()) " · ${eps.size} episodes" else "", 11.sp, BlueBright, 0.32f) }
                Spacer(Modifier.height(4.dp))
                Text("Your calendar", color = Txt, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 40.sp, letterSpacing = (-1.2).sp)
            }
            Spacer(Modifier.weight(1f))
            CircleBtn(Lucide.ChevronLeft, "Previous month", size = 44.dp) { step(-1) }
            Text(pretty(iso(year, month, 1), "MMMM yyyy"), color = Txt, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, textAlign = TextAlign.Center, modifier = Modifier.width(230.dp))
            CircleBtn(Lucide.ChevronRight, "Next month", size = 44.dp) { step(1) }
        }
        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner("Loading your calendar") }
            error -> Text("Could not load upcoming episodes. Check the connection and open the calendar again.", color = Txt2, fontFamily = Geist, fontSize = 15.sp, modifier = Modifier.padding(top = 40.dp))
            eps.isEmpty() -> CalEmpty(series.isEmpty())
            else -> {
                Row(Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    MonthGrid(Modifier.weight(1.6f).fillMaxHeight(), year, month, byDate, selected, today) { selected = it }
                    DayPanel(Modifier.weight(1f).fillMaxHeight(), selected, byDate[selected].orEmpty())
                }
                if (week.isNotEmpty()) {
                    Mono("This week", 10.sp, BlueBright, 0.32f, Modifier.padding(top = 14.dp, bottom = 8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)) {
                        items(week, key = { "${it.imdbId}-${it.season}-${it.episode}" }) { e -> WeekTile(e) { byImdb[e.imdbId]?.let(onOpen) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthGrid(modifier: Modifier, year: Int, month: Int, byDate: Map<String, List<CalEpisode>>, selected: String, today: String, onSelect: (String) -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val cells = remember(year, month) { monthCells(year, month) }
    Column(modifier.clip(shape).background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.03f), Color.White.copy(alpha = 0.01f)))).border(1.dp, Blue.copy(alpha = 0.18f), shape).padding(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEach { Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Mono(it, 9.sp, Txt3, 0.28f) } }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (r in 0 until 6) Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (c in 0 until 7) {
                    val day = cells[r * 7 + c]
                    if (day == 0) { Spacer(Modifier.weight(1f)); continue }
                    val d = iso(year, month, day); val list = byDate[d].orEmpty(); val isToday = d == today; val sel = d == selected
                    val cs = RoundedCornerShape(9.dp)
                    Focusable(Modifier.weight(1f).fillMaxHeight(), onClick = { onSelect(d) }, onFocus = { if (it) onSelect(d) }) { f ->
                        Column(Modifier.fillMaxSize().tileFocus(f, 9.dp).clip(cs)
                            .background(if (sel) Blue.copy(alpha = 0.22f) else if (list.isNotEmpty()) Blue.copy(alpha = 0.07f) else Color.White.copy(alpha = 0.025f))
                            .border(1.dp, if (sel) Blue.copy(alpha = 0.85f) else if (isToday) Blue.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.05f), cs).padding(horizontal = 6.dp, vertical = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("$day", color = if (isToday) BlueBright else Txt, fontFamily = Geist, fontWeight = if (isToday) FontWeight.Bold else FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 15.sp)
                                if (isToday) { Spacer(Modifier.width(5.dp)); Mono("Today", 7.sp, BlueBright, 0.2f) }
                                if (list.size > 1) { Spacer(Modifier.weight(1f)); Mono("+${list.size - 1}", 7.sp, Txt3, 0.1f) }
                            }
                            list.firstOrNull()?.let { e ->
                                Spacer(Modifier.height(3.dp))
                                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(RoundedCornerShape(3.dp)).background(Color(e.colour).copy(alpha = 0.2f))) {
                                    Box(Modifier.width(3.dp).fillMaxHeight().background(Color(e.colour)))
                                    Text(e.showName, color = Color.White, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 8.5.sp, lineHeight = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayPanel(modifier: Modifier, date: String, list: List<CalEpisode>) {
    val shape = RoundedCornerShape(18.dp)
    Column(modifier.clip(shape).background(Color.White.copy(alpha = 0.02f)).border(1.dp, Color.White.copy(alpha = 0.08f), shape).padding(horizontal = 18.dp, vertical = 16.dp)) {
        Mono(if (list.isEmpty()) "Nothing scheduled" else "${list.size} episode${if (list.size == 1) "" else "s"}", 10.sp, BlueBright, 0.32f)
        Text(pretty(date, "EEEE, d MMMM"), color = Txt, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 26.sp, modifier = Modifier.padding(top = 6.dp, bottom = 14.dp))
        if (list.isEmpty()) Text("No episodes from your library on this day. Pick a highlighted day from the calendar to see what's airing.", color = Txt2, fontFamily = Geist, fontSize = 13.sp, lineHeight = 20.sp)
        else Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            list.forEach { e ->
                val col = Color(e.colour); val cs = RoundedCornerShape(12.dp)
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clip(cs).background(Color(0x8C0B1322)).border(1.dp, col.copy(alpha = 0.33f), cs)) {
                    Box(Modifier.width(4.dp).fillMaxHeight().background(col))
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        val thumb = e.still ?: e.poster
                        if (thumb != null) { AsyncImage(thumb, null, Modifier.width(100.dp).height(56.dp).clip(RoundedCornerShape(7.dp)).background(Bg2), contentScale = ContentScale.Crop); Spacer(Modifier.width(12.dp)) }
                        Column {
                            Mono((e.network?.let { "$it · " } ?: "") + "S${e.season} · E${e.episode}", 8.5.sp, col, 0.24f)
                            Text(e.showName, color = Txt, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 14.sp, lineHeight = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(e.name.ifBlank { e.overview }, color = Txt2, fontFamily = Geist, fontSize = 11.5.sp, lineHeight = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekTile(e: CalEpisode, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp); val col = Color(e.colour)
    Focusable(onClick = onClick) { f ->
        Box(Modifier.width(200.dp).aspectRatio(16f / 9f).focusScale(f, 1.04f).tileFocus(f, 12.dp).clip(shape).background(Color(0xFF0B1322)).border(1.dp, Color.White.copy(alpha = 0.06f), shape)) {
            (e.still ?: e.backdrop)?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.3f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.9f))))
            Box(Modifier.padding(10.dp).size(9.dp).clip(CircleShape).background(col))
            Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 10.dp, vertical = 8.dp)) {
                Mono("${pretty(e.date, "d MMM")} · S${e.season} · E${e.episode}", 8.sp, col, 0.2f)
                Text(e.showName, color = Color.White, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 13.sp, lineHeight = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun CalEmpty(noShows: Boolean) {
    val shape = RoundedCornerShape(20.dp)
    Column(Modifier.padding(top = 36.dp).width(680.dp).clip(shape).background(Color.White.copy(alpha = 0.02f)).border(1.dp, Blue.copy(alpha = 0.3f), shape).padding(horizontal = 32.dp, vertical = 32.dp)) {
        Mono("Nothing on the horizon", 11.sp, BlueBright, 0.28f)
        Row(Modifier.padding(top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Lucide.Tv, null, Modifier.size(22.dp), tint = Blue); Spacer(Modifier.width(10.dp))
            Text("No upcoming episodes", color = Txt, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 26.sp)
        }
        Text(if (noShows) "Your calendar fills itself from the TV shows in My Library. Press My List on any series and its next episodes will appear here."
        else "We didn't find any scheduled episodes for the shows in your library. They may be between seasons or finished. Add a current series and check back — the calendar refreshes every time you open it.",
            color = Txt2, fontFamily = Geist, fontSize = 14.sp, lineHeight = 21.sp)
    }
}
