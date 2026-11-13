package tv.rebornplay

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.*
import kotlinx.coroutines.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Vesper's SpeedTestModal: dial gauge, live Mbps, ping, 720p / 1080p / 4K verdicts. */
object SpeedTest {
    var open by mutableStateOf(false)
    var run by mutableStateOf(0)
    var sel by mutableStateOf(1)
    var finished by mutableStateOf(false)
    private var armed = false
    fun show() { sel = 1; armed = false; finished = false; run++; open = true }
    fun key(e: KeyEvent): Boolean {
        if (!open) return false
        when (e.key) {
            Key.DirectionLeft -> if (e.type == KeyEventType.KeyDown && finished) sel = 0
            Key.DirectionRight -> if (e.type == KeyEventType.KeyDown) sel = 1
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> if (e.type == KeyEventType.KeyDown) armed = true else if (armed) { armed = false; if (sel == 0 && finished) { finished = false; sel = 1; run++ } else open = false }
            Key.Back, Key.Escape -> return false
        }
        return true
    }
    private const val CF = "https://speed.cloudflare.com/__down"
    private fun conn(bytes: Long) = (URL("$CF?bytes=$bytes&r=${System.nanoTime()}").openConnection() as HttpURLConnection).apply { connectTimeout = 8000; readTimeout = 8000; useCaches = false }
    fun ping(): Int {
        val s = (0 until 6).map { val t = System.nanoTime(); val c = conn(0); try { c.inputStream.use { it.read() } } finally { c.disconnect() }; (System.nanoTime() - t) / 1_000_000 }.sorted()
        return s[s.size / 4].toInt()
    }
    /** 4 parallel downloads for `ms`; reports live Mbps (last second) and returns the average after a 1 s warm-up. */
    suspend fun download(ms: Long, onProgress: (Float, Float) -> Unit): Float = coroutineScope {
        val total = AtomicLong(0); val stop = AtomicBoolean(false); val failed = AtomicBoolean(false)
        val jobs = (0 until 4).map { launch(Dispatchers.IO) {
            val buf = ByteArray(64 * 1024)
            while (!stop.get()) { val c = conn(25_000_000); try { c.inputStream.use { i -> while (!stop.get()) { val n = i.read(buf); if (n < 0) break; total.addAndGet(n.toLong()) } } } catch (t: Throwable) { if (!stop.get()) { failed.set(true); stop.set(true) } } finally { c.disconnect() } }
        } }
        val start = System.nanoTime(); val samples = ArrayList<Pair<Long, Long>>(); var warm: Pair<Long, Long>? = null
        try {
            while (true) {
                delay(200)
                val t = (System.nanoTime() - start) / 1_000_000; val b = total.get()
                samples += t to b; if (warm == null && t >= 1000) warm = t to b
                val w = samples.firstOrNull { it.first >= t - 1000 } ?: samples.first()
                onProgress(if (t > w.first) (b - w.second) * 8f / ((t - w.first) / 1000f) / 1e6f else 0f, (t.toFloat() / ms).coerceAtMost(1f))
                if (t >= ms || failed.get()) break
            }
        } finally { stop.set(true); jobs.forEach { it.cancel() } }
        if (failed.get() && total.get() == 0L) throw java.io.IOException("no connection")
        val t = (System.nanoTime() - start) / 1_000_000; val w = warm ?: (0L to 0L)
        if (t > w.first) (total.get() - w.second) * 8f / ((t - w.first) / 1000f) / 1e6f else 0f
    }
}

@Composable
fun SpeedTestDialog() {
    if (!SpeedTest.open) return
    BackHandler { SpeedTest.open = false }
    var phase by remember { mutableStateOf("ping") }
    var ping by remember { mutableStateOf<Int?>(null) }
    var mbps by remember { mutableStateOf(0f) }
    var progress by remember { mutableStateOf(0f) }
    LaunchedEffect(SpeedTest.run) {
        phase = "ping"; ping = null; mbps = 0f; progress = 0f
        try {
            ping = withContext(Dispatchers.IO) { SpeedTest.ping() }
            phase = "download"
            mbps = SpeedTest.download(8000) { m, p -> mbps = m; progress = p }
            phase = "done"
        } catch (c: CancellationException) { throw c } catch (t: Throwable) { phase = "error" }
        SpeedTest.finished = true
    }
    val tone = when { phase == "error" -> Color(0xFFFF8E8E); phase != "done" -> Color(0xFF5DC8FF); mbps >= 100 -> Color(0xFF7CF1A1); mbps >= 20 -> Color(0xFF5DC8FF); mbps >= 10 -> Color(0xFFFFD36B); else -> Color(0xFFFF8E8E) }
    val status = when (phase) { "ping" -> "Checking your connection…"; "download" -> "Testing download speed…"; "error" -> "Couldn't reach the test server"
        else -> if (mbps >= 100) "Your internet is very healthy" else if (mbps >= 20) "Your internet is healthy" else if (mbps >= 10) "Your internet is a bit slow" else "Your internet is struggling" }
    // Dial: 0–200 Mbps on a square-root scale so everyday speeds use most of the arc.
    val arc by animateFloatAsState(if (phase == "ping" || phase == "error") 0f else kotlin.math.sqrt((mbps / 200f).coerceIn(0f, 1f)), tween(260), label = "arc")
    val shape = RoundedCornerShape(28.dp)
    Box(Modifier.fillMaxSize().background(Color(0xD106080F)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(640.dp).blueGlow(true, shape, 30.dp, 0.3f).clip(shape).background(Brush.linearGradient(listOf(Color(0xEB0F1626), Color(0xF006080F)))).border(1.dp, tone.copy(alpha = 0.33f), shape).padding(start = 40.dp, end = 40.dp, top = 36.dp, bottom = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) { Icon(Lucide.Gauge, null, Modifier.size(16.dp), tint = tone); Spacer(Modifier.width(10.dp)); Mono("Speed test", 11.sp, tone, 0.42f) }
            Box(Modifier.padding(top = 18.dp, bottom = 6.dp).size(240.dp, 200.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val r = 96.dp.toPx(); val c = Offset(size.width / 2, 120.dp.toPx()); val st = Stroke(14.dp.toPx(), cap = StrokeCap.Round)
                    val tl = Offset(c.x - r, c.y - r); val sz = Size(r * 2, r * 2)
                    drawArc(Color.White.copy(alpha = 0.08f), 135f, 270f, false, tl, sz, style = st)
                    if (arc > 0.005f) { drawArc(tone.copy(alpha = 0.25f), 135f, 270f * arc, false, tl, sz, style = Stroke(24.dp.toPx(), cap = StrokeCap.Round)); drawArc(tone, 135f, 270f * arc, false, tl, sz, style = st) }
                    // needle tip
                    val a = Math.toRadians((135f + 270f * arc).toDouble()); drawCircle(Color.White, 5.dp.toPx(), Offset(c.x + r * kotlin.math.cos(a).toFloat(), c.y + r * kotlin.math.sin(a).toFloat()))
                }
                Column(Modifier.padding(top = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (phase == "ping" || phase == "error") "—" else if (mbps >= 100) "%.0f".format(mbps) else "%.1f".format(mbps), color = Color.White, fontFamily = Geist, fontWeight = FontWeight.Black, fontSize = 56.sp, lineHeight = 56.sp, letterSpacing = (-1.6).sp)
                    Text("MBPS DOWNLOAD", color = Color(0xFF9DA5B5), fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 2.3.sp, modifier = Modifier.padding(top = 6.dp))
                }
            }
            Text(status, color = tone, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(ping?.let { "Ping $it ms · ${if (it <= 30) "Excellent" else if (it <= 70) "Good" else if (it <= 150) "Fair" else "High"}" } ?: "Ping —", color = Color(0xFF9DA5B5), fontFamily = Geist, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp))
            Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf("720p" to 10, "1080p" to 20, "4K" to 100).forEach { (label, min) ->
                    val known = phase == "done"; val ok = known && mbps >= min
                    val col = if (!known) Color(0xFF6B7384) else if (ok) Color(0xFF7CF1A1) else Color(0xFFFF8E8E)
                    Row(Modifier.clip(CircleShape).background(col.copy(alpha = 0.08f)).border(1.dp, col.copy(alpha = 0.33f), CircleShape).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(20.dp).clip(CircleShape).background(if (known) col else Color.White.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) { if (known) Icon(if (ok) Lucide.Check else Lucide.X, null, Modifier.size(13.dp), tint = Color(0xFF06080F)) }
                        Spacer(Modifier.width(8.dp)); Text(label, color = col, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(Modifier.width(8.dp)); Text("$min+", color = Color(0xFF6B7384), fontFamily = Geist, fontWeight = FontWeight.SemiBold, fontSize = 11.sp)
                    }
                }
            }
            Box(Modifier.padding(top = 22.dp).width(320.dp).height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(if (phase == "ping") 0.08f else if (phase == "download") 0.1f + progress * 0.9f else 1f).background(tone))
            }
            Row(Modifier.padding(top = 28.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (SpeedTest.finished) SpeedBtn("Test again", Lucide.RotateCw, SpeedTest.sel == 0, true)
                SpeedBtn(if (SpeedTest.finished) "Close" else "Cancel", Lucide.ArrowLeft, SpeedTest.sel == 1 || !SpeedTest.finished, false)
            }
            Text("Tested against Cloudflare's global speed-test network", color = Color(0xFF6B7384), fontFamily = Geist, fontSize = 12.sp, modifier = Modifier.padding(top = 20.dp))
        }
    }
}

@Composable
private fun SpeedBtn(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, on: Boolean, primary: Boolean) {
    Row(Modifier.height(48.dp).pillFocus(on).clip(CircleShape).background(if (primary && on) Blue else Color.White.copy(alpha = if (on) 0.14f else 0.06f)).border(if (on) 1.5.dp else 1.dp, if (on) BlueBright else Color.White.copy(alpha = 0.14f), CircleShape).padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        val c = if (primary && on) Bg0 else Txt
        Icon(icon, null, Modifier.size(18.dp), tint = c); Spacer(Modifier.width(9.dp)); Text(label, color = c, fontFamily = Geist, fontWeight = FontWeight.Bold, fontSize = 15.sp)
    }
}
