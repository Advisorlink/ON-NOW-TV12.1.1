package tv.rebornplay

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** "Who's ready to watch?" — Reborn-only profiles (never Vesper's), with Reborn-only cloud backup. */
@Composable
fun ProfilesScreen(token: String, onPick: (Profile) -> Unit, onSignOut: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var profiles by remember { mutableStateOf(Profiles.all(ctx)) }
    var cloud by remember { mutableStateOf<JSONObject?>(null) }
    var checkedCloud by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Profile?>(null) }   // id "" = new
    var manage by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    val first = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (profiles.isEmpty()) cloud = withContext(Dispatchers.IO) { runCatching { RebornApi.cloudPull(token) }.getOrNull() }?.takeIf { Profiles.parse(it.optJSONArray("reborn-profiles")?.toString()).isNotEmpty() }
        checkedCloud = true
    }
    if (checkedCloud && cloud == null && editing == null) FocusOnShow(first, profiles.size, manage)
    fun persist(list: List<Profile>) { profiles = list; Profiles.save(ctx, list) }
    fun backup(msg: String = "Profiles backed up") = scope.launch {
        toast = withContext(Dispatchers.IO) { runCatching { RebornApi.cloudPush(token, Profiles.snapshot(ctx)) }.fold({ msg }, { "Backup failed: ${it.message}" }) }
    }

    Box(Modifier.fillMaxSize()) {
        ScreenBackdrop()
        if (!checkedCloud) { Spinner(); return }
        cloud?.let { data ->
            CloudPrompt(Profiles.parse(data.optJSONArray("reborn-profiles")?.toString()), onRestore = { Profiles.restore(ctx, data); profiles = Profiles.all(ctx); cloud = null }, onFresh = { cloud = null })
            return
        }
        editing?.let { p ->
            ProfileEditor(p, onCancel = { editing = null }, onSave = { saved ->
                persist(if (p.id.isEmpty()) profiles + saved else profiles.map { if (it.id == saved.id) saved else it }); editing = null; backup()
            }, onDelete = if (p.id.isEmpty()) null else ({ persist(profiles.filter { it.id != p.id }); editing = null; backup("Profile removed") }))
            return
        }
        val compact = isCompact()
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(if (compact) 18.dp else 36.dp)); BrandLogo(if (compact) 36.dp else 56.dp)
            Spacer(Modifier.height(if (compact) 18.dp else 90.dp))
            Text("Who's ready to watch?", color = Txt, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = if (compact) 30.sp else 54.sp, letterSpacing = (-1.5).sp)
            Spacer(Modifier.height(6.dp))
            Text(if (manage) "Pick a profile to rename or remove it" else "Choose a profile to continue", color = Txt2, fontFamily = Geist, fontSize = 14.sp)
            Spacer(Modifier.height(if (compact) 18.dp else 40.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(44.dp), contentPadding = PaddingValues(horizontal = 80.dp)) {
                items(profiles, key = { it.id }) { p ->
                    AvatarTile(p.name, Avatars.url(p.avatarId), requester = if (p == profiles.firstOrNull()) first else null, badge = if (manage) Icons.Outlined.Edit else null) {
                        if (manage) editing = p else { Profiles.setActive(ctx, p.id); onPick(p) }
                    }
                }
                item { AvatarTile("Add Profile", null, requester = if (profiles.isEmpty()) first else null) { editing = Profile("", "", Avatars.ALL.first()) } }
            }
            Spacer(Modifier.height(if (compact) 22.dp else 52.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(if (manage) "Done" else "Manage profiles", icon = if (manage) Icons.Outlined.Check else Icons.Outlined.Edit, height = 40.dp, fontSize = 13.sp) { manage = !manage }
                Pill("Back up profiles", icon = Icons.Outlined.CloudUpload, height = 40.dp, fontSize = 13.sp) { backup() }
                Pill("Load from backup", icon = Icons.Outlined.CloudDownload, height = 40.dp, fontSize = 13.sp) {
                    scope.launch { val d = withContext(Dispatchers.IO) { runCatching { RebornApi.cloudPull(token) }.getOrNull() }; if (d == null) toast = "No backup found for this account" else { Profiles.restore(ctx, d); profiles = Profiles.all(ctx); toast = "Profiles restored" } }
                }
                Pill("Sign out", icon = Icons.Outlined.Logout, height = 40.dp, fontSize = 13.sp, onClick = onSignOut)
            }
        }
        toast?.let { Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 34.dp).glass().padding(horizontal = 18.dp, vertical = 10.dp)) { Text(it, color = Txt, fontFamily = Geist, fontSize = 13.sp) }; LaunchedEffect(it) { kotlinx.coroutines.delay(2600); toast = null } }
    }
}

@Composable
private fun AvatarTile(label: String, url: String?, requester: FocusRequester? = null, badge: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Focusable(onClick = onClick, requester = requester, onFocus = { focused = it }) { _ ->
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(if (isCompact()) 92.dp else 126.dp).focusScale(focused, 1.08f).then(if (focused) Modifier.shadow(30.dp, CircleShape, ambientColor = Blue, spotColor = Blue) else Modifier)
                .clip(CircleShape).background(Bg2).border(if (focused) 3.dp else 0.dp, if (focused) Blue else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
                if (url != null) AsyncImage(url, label, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else {
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) { drawCircle(Txt3, style = Stroke(3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 12f)))) }
                    Icon(Icons.Outlined.Add, null, Modifier.size(40.dp), tint = Txt2)
                }
                if (badge != null) Box(Modifier.fillMaxSize().background(Bg0.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) { Icon(badge, null, Modifier.size(34.dp), tint = Txt) }
            }
            Spacer(Modifier.height(14.dp))
            Text(label, color = if (focused) Txt else Txt2, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        }
    }
}

@Composable
private fun CloudPrompt(found: List<Profile>, onRestore: () -> Unit, onFresh: () -> Unit) {
    val req = remember { FocusRequester() }
    FocusOnShow(req, Unit)
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
        Column(Modifier.width(560.dp).padding(vertical = 20.dp).clip(RoundedCornerShape(22.dp)).background(Bg1.copy(alpha = 0.92f)).border(1.dp, Line, RoundedCornerShape(22.dp)).padding(32.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(Blue.copy(alpha = 0.12f)).border(1.dp, Blue.copy(alpha = 0.3f), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.CloudSync, null, Modifier.size(24.dp), tint = Blue) }
                Spacer(Modifier.width(14.dp))
                Column { Eyebrow("Welcome back"); Text("We found your profiles in the cloud", color = Txt, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 22.sp) }
            }
            Spacer(Modifier.height(18.dp))
            Text("Everyone signed in to this account is already set up and waiting — avatars and lists included.", color = Txt2, fontFamily = Geist, fontSize = 14.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Bg0.copy(alpha = 0.6f)).border(1.dp, Line, RoundedCornerShape(12.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Groups, null, Modifier.size(22.dp), tint = Blue); Spacer(Modifier.width(14.dp))
                Column { Text("${found.size} profile${if (found.size == 1) "" else "s"}", color = Txt, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 14.sp); Text(found.joinToString(", ") { it.name }, color = Txt2, fontFamily = Geist, fontSize = 12.sp) }
            }
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("Restore my profiles", icon = Icons.Outlined.Check, primary = true, height = 48.dp, requester = req, onClick = onRestore)
                Pill("Start fresh", icon = Icons.Outlined.Close, height = 48.dp, onClick = onFresh)
            }
        }
    }
}

@Composable
private fun ProfileEditor(p: Profile, onCancel: () -> Unit, onSave: (Profile) -> Unit, onDelete: (() -> Unit)?) {
    var name by remember { mutableStateOf(p.name) }
    var avatar by remember { mutableStateOf(p.avatarId) }
    val nameReq = remember { FocusRequester() }
    FocusOnShow(nameReq, Unit)
    BackHandler(onBack = onCancel)
    val compact = isCompact()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = if (compact) 28.dp else 80.dp, vertical = if (compact) 20.dp else 40.dp)) {
        Eyebrow(if (p.id.isEmpty()) "New profile" else "Edit profile"); Spacer(Modifier.height(6.dp))
        Text(if (p.id.isEmpty()) "Who's watching?" else "Edit ${p.name}", color = Txt, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = if (compact) 26.sp else 36.sp, letterSpacing = (-1).sp)
        Spacer(Modifier.height(if (compact) 14.dp else 26.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(Avatars.url(avatar), null, Modifier.size(if (compact) 64.dp else 96.dp).clip(CircleShape).border(2.dp, Blue, CircleShape), contentScale = ContentScale.Crop)
            Spacer(Modifier.width(20.dp))
            Column(Modifier.width(320.dp)) { Mono("Name", 10.sp, Txt2); Spacer(Modifier.height(6.dp)); Field(name, { if (it.length <= 20) name = it }, "Profile name", Icons.Outlined.Person, requester = nameReq) }
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("Save profile", icon = Icons.Outlined.Check, primary = true, enabled = name.isNotBlank()) { onSave(Profile(p.id.ifEmpty { Profiles.newId() }, name.trim(), avatar, p.kids)) }
            Pill("Cancel", onClick = onCancel)
            if (onDelete != null) Pill("Remove profile", icon = Icons.Outlined.Delete, onClick = onDelete)
        }
        Spacer(Modifier.height(22.dp))
        Mono("Pick an avatar", 10.sp, Blue, modifier = Modifier.padding(bottom = 10.dp))
        Avatars.CATEGORIES.forEach { (cat, ids) ->
            Mono(cat, 10.sp, Txt3, modifier = Modifier.padding(bottom = 8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                items(ids) { id ->
                    Focusable(onClick = { avatar = id }) { f ->
                        AsyncImage(Avatars.url(id), id, Modifier.size(if (f) 64.dp else 58.dp).clip(CircleShape).border(if (f || id == avatar) 3.dp else 0.dp, if (f) BlueBright else if (id == avatar) Blue else Color.Transparent, CircleShape), contentScale = ContentScale.Crop)
                    }
                }
            }
        }
    }
}
