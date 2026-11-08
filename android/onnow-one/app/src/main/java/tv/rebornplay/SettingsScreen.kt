package tv.rebornplay

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import tv.vesper.app.LiveTvEntry

@Composable
fun SettingsScreen(activity: Activity, autoplay: Boolean, onAutoplay: () -> Unit, onProfiles: () -> Unit, onSignOut: () -> Unit, onRefreshLive: () -> Unit, onHomeRows: () -> Unit = {}) {
    val ctx = LocalContext.current
    val user = remember { RebornSession.username(ctx) ?: "" }
    val profile = remember { Profiles.active(ctx) }
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "" }
    val first = remember { FocusRequester() }
    FocusOnShow(first, Unit)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 64.dp, end = 64.dp, top = 84.dp, bottom = 60.dp)) {
        item {
            Eyebrow("Settings"); Spacer(Modifier.height(6.dp))
            Text("Make it yours", color = Txt, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 36.sp, letterSpacing = (-1).sp)
            Spacer(Modifier.height(28.dp))
        }
        item { Section("Account") {
            SettingRow(Icons.Outlined.Person, "Signed in as", user, requester = first) {}
            SettingRow(Icons.Outlined.Groups, "Profile", profile?.name ?: "—", action = "Switch", onClick = onProfiles)
            SettingRow(Icons.Outlined.Logout, "Sign out", "Clears this device's sign-in. Profiles stay backed up in the cloud.", action = "Sign out", onClick = onSignOut)
        } }
        item { Section("Live TV") {
            SettingRow(Icons.Outlined.CellTower, "Live TV settings", "Player engine, subtitles, aspect ratio, buffer, parental PIN, hidden groups", action = "Open") { LiveTvEntry.open(activity, settings = true) }
            SettingRow(Icons.Outlined.Refresh, "Refresh channels & guide", "Re-downloads your channel list and the programme guide", action = "Refresh", onClick = onRefreshLive)
        } }
        item { Section("Home screen") {
            SettingRow(Icons.Outlined.ViewAgenda, "Home rows", "Move rows up or down and hide the ones you skip — just like Vesper", action = "Customise", onClick = onHomeRows)
            SettingRow(Icons.Outlined.Autorenew, "Refresh movies & shows", "Re-downloads the catalogue from the service", action = "Refresh") { Cache.clear(); Gorilla.clearCache(ctx) }
        } }
        item { Section("About") { SettingRow(Icons.Outlined.Info, "Reborn Play", "Version $version · native build") {} } }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(bottom = 26.dp)) {
        Mono(title, 10.sp, Txt3, modifier = Modifier.padding(bottom = 10.dp))
        Column(Modifier.fillMaxWidth(0.72f).clip(RoundedCornerShape(16.dp)).background(Bg1.copy(alpha = 0.7f)).border(1.dp, Line, RoundedCornerShape(16.dp)), content = content)
    }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, sub: String, action: String? = null, accent: Boolean = false, requester: FocusRequester? = null, onClick: () -> Unit) {
    Focusable(onClick = onClick, requester = requester) { f ->
        Row(Modifier.fillMaxWidth().background(if (f) Blue.copy(alpha = 0.12f) else Color.Transparent).padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(20.dp), tint = if (f) Blue else Txt2); Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Txt, fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                Text(sub, color = Txt2, fontFamily = Geist, fontSize = 12.sp, maxLines = 2)
            }
            if (action != null) Box(Modifier.clip(CircleShape).background(if (accent) Amber.copy(alpha = 0.16f) else if (f) BlueBright else Color.White.copy(alpha = 0.08f)).padding(horizontal = 14.dp, vertical = 6.dp)) {
                Mono(action, 10.sp, if (accent) Amber else if (f) Bg0 else Txt, 0.16f)
            }
        }
    }
}
