package tv.rebornplay

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL

/**
 * Remote kill switch. The app asks the server (every start and every few minutes) whether it has been
 * disabled. When it has, the whole app is covered by a lock screen until it is turned back on from the
 * server. No answer (no internet) never locks it: the last good answer stands, and a fresh install is open.
 */
object KillSwitch {
    var killed by mutableStateOf(false)
        private set
    var message by mutableStateOf("")
        private set

    fun load(ctx: Context) {
        val p = ctx.getSharedPreferences("reborn_kill", Context.MODE_PRIVATE)
        killed = p.getBoolean("killed", false)
        message = p.getString("message", "").orEmpty()
    }

    private fun save(ctx: Context) {
        ctx.getSharedPreferences("reborn_kill", Context.MODE_PRIVATE).edit()
            .putBoolean("killed", killed).putString("message", message).apply()
    }

    // The kill switch lives on the Emergent backend (the content server onnowhub.com does not carry it).
    private const val KILL_URL = "https://rebrand-app-5.emergent.host/api/reborn/status"

    suspend fun check(ctx: Context) {
        val o: JSONObject = withContext(Dispatchers.IO) {
            runCatching {
                val c = URL(KILL_URL).openConnection() as java.net.HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 15000; c.setRequestProperty("User-Agent", "RebornPlay/2.0 Android")
                c.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            }.getOrNull()
        } ?: return
        killed = o.optBoolean("killed", false)
        o.optString("message").takeIf { it.isNotBlank() }?.let { message = it }
        save(ctx)
    }
}

@Composable
fun KillGate(ctx: Context) {
    LaunchedEffect(Unit) {
        KillSwitch.load(ctx)
        while (isActive) { KillSwitch.check(ctx); delay(4 * 60_000L) }
    }
    AnimatedVisibility(visible = KillSwitch.killed, enter = fadeIn()) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF12060A), Color(0xFF0A1222)))), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 560.dp).padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("REBORN PLAY", color = Blue, fontFamily = Geist, fontWeight = FontWeight.Black, fontSize = 20.sp, letterSpacing = 3.sp)
                Spacer(Modifier.height(28.dp))
                Text("Unavailable", color = Color.White, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 40.sp)
                Spacer(Modifier.height(18.dp))
                Text(
                    KillSwitch.message.ifBlank { "This app has been disabled. Please contact the developer to restore access." },
                    color = Color(0xFFC9CDD4), fontFamily = Geist, fontSize = 19.sp, lineHeight = 29.sp, textAlign = TextAlign.Center,
                )
            }
        }
    }
}
