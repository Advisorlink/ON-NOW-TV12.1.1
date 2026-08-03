package tv.onnow.launcher.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import tv.onnow.launcher.data.LauncherRepository
import tv.onnow.launcher.net.ResilientHttp
import tv.onnow.launcher.support.RootInputDispatcher
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID

/**
 * Always-on phone-remote host (v3 — permanent QR, zero-PIN pairing).
 *
 * Started by MainActivity at launcher boot.  Registers this box with a
 * PERSISTENT device-bound session: device_id + a secret token kept in
 * SharedPreferences.  The backend answers with a per-box QR that
 * encodes the auto-connect URL — scanning it (or opening a saved
 * home-screen shortcut) connects the phone instantly with no code.
 *
 * Three transports, fastest first:
 *  1. LAN direct  — [LocalRemoteServer] on the box itself (~5-20 ms).
 *  2. Cloud WS    — persistent socket to /api/remote/ws/host/{sid}.
 *  3. Long-poll   — v1 fallback while the cloud socket is down.
 *
 * Extras beyond raw key events: "seek" → broadcast to the active
 * player; keyboard detection via root dumpsys; Vesper NOW_PLAYING
 * forwarding for the phone's media card.
 */
class RemoteControlService : Service() {

    companion object {
        private const val TAG = "RemoteFG"
        private const val CH_ID = "onnow-remote"
        private const val NOTI_ID = 4823
        const val ACTION_START = "tv.onnow.launcher.remote.START"
        const val ACTION_STOP  = "tv.onnow.launcher.remote.STOP"

        const val ACTION_NOW_PLAYING = "tv.onnow.remote.NOW_PLAYING"
        const val ACTION_PLAYER_CMD  = "tv.onnow.remote.CMD"
        const val ACTION_KEYBOARD    = "tv.onnow.remote.KEYBOARD"

        private val LOCAL_PORTS = intArrayOf(8765, 8766, 8767, 8768)

        data class RegInfo(
            val sessionId: String,
            val code: String,
            val shortCode: String?,
            val qrImageUrl: String?,
            val qrTarget: String?,
        )

        /** Latest successful registration — observed by MainActivity
         *  (top-bar QR chip) and RemoteControlActivity (big QR). */
        val regFlow = MutableStateFlow<RegInfo?>(null)

        @Volatile var isRunning: Boolean = false
            private set

        /** Stable per-box secret; minted once, survives updates. */
        fun deviceToken(ctx: Context): String {
            val p = ctx.getSharedPreferences("remote_prefs", Context.MODE_PRIVATE)
            var t = p.getString("remote_token", null)
            if (t.isNullOrBlank()) {
                t = UUID.randomUUID().toString().replace("-", "")
                p.edit().putString("remote_token", t).apply()
            }
            return t
        }
    }

    @Volatile private var active = false
    private var inputPollerThread: Thread? = null
    private var keyboardThread: Thread? = null
    private var cloudPingThread: Thread? = null
    @Volatile private var sessionId: String? = null
    private var baseUrl: String? = null
    @Volatile private var code: String? = null
    private val jsonType = "application/json".toMediaTypeOrNull()

    private var localServer: LocalRemoteServer? = null
    @Volatile private var localIp: String? = null
    @Volatile private var cloudWs: WebSocket? = null
    @Volatile private var cloudWsOpen = false
    @Volatile private var subsystemsStarted = false

    @Volatile private var curKeyboard = false
    @Volatile private var dumpsysKb = false
    @Volatile private var jsKb = false
    @Volatile private var curNowPlaying: JSONObject? = null
    @Volatile private var nowPlayingReceiverRegistered = false

    /** Combine app-reported (JS bridge) + dumpsys IME detection. */
    private fun updateKeyboard() {
        val combined = dumpsysKb || jsKb
        if (combined != curKeyboard) {
            curKeyboard = combined
            pushState(includeNowPlaying = false, includeKeyboard = true)
        }
    }

    private val keyboardReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            jsKb = intent?.getBooleanExtra("needed", false) == true
            updateKeyboard()
        }
    }

    private val nowPlayingReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent == null) return
            curNowPlaying = if (intent.getBooleanExtra("cleared", false)) null
            else JSONObject().apply {
                put("title", intent.getStringExtra("title") ?: "")
                put("synopsis", intent.getStringExtra("synopsis") ?: "")
                put("poster", intent.getStringExtra("poster") ?: "")
                put("backdrop", intent.getStringExtra("backdrop") ?: "")
                put("year", intent.getStringExtra("year") ?: "")
                put("runtime", intent.getStringExtra("runtime") ?: "")
                put("rating", intent.getStringExtra("rating") ?: "")
                put("position_ms", intent.getLongExtra("position_ms", 0L))
                put("duration_ms", intent.getLongExtra("duration_ms", 0L))
                put("playing", intent.getBooleanExtra("playing", true))
                put("has_next", intent.getBooleanExtra("has_next", false))
                // v2.18.0 — Companion context (music / live TV).
                put("source", intent.getStringExtra("source") ?: "vesper")
                put("artist", intent.getStringExtra("artist") ?: "")
                put("channel", intent.getStringExtra("channel") ?: "")
                put("live", intent.getBooleanExtra("live", false))
            }
            pushState(includeNowPlaying = true, includeKeyboard = false)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) return START_NOT_STICKY
        if (active) return START_STICKY  // already hosting

        baseUrl = LauncherRepository(applicationContext).baseUrlPublic().trimEnd('/')
        code = deviceToken(this)
        active = true

        ensureChannel()
        startForeground(NOTI_ID, buildNotification())
        isRunning = true

        // Pre-open the root shell so any Magisk prompt fires once at boot.
        Thread {
            if (!RootInputDispatcher.ensureShell())
                Log.w(TAG, "no root shell — remote inputs will be dropped")
        }.also { it.isDaemon = true }.start()

        registerNowPlayingReceiver()
        startLocalServer()
        startRegistrationLoop()

        return START_STICKY
    }

    // ─────────────────────── registration ─────────────────────────

    private fun registerOnce(): Boolean {
        return try {
            val body = JSONObject().apply {
                put("device_id", deviceId())
                put("token", code ?: "")
                put("apps", installedAppsJson())
                localIp?.let {
                    put("local_ip", it)
                    put("local_port", localServer?.listeningPort ?: 0)
                }
            }.toString().toRequestBody(jsonType)
            val url = "${baseUrl}/api/remote/host/register"
            ResilientHttp.client.newCall(Request.Builder().url(url).post(body).build())
                .execute().use { r ->
                    if (!r.isSuccessful) return false
                    val resp = JSONObject(r.body?.string().orEmpty())
                    sessionId = resp.optString("session_id")
                    regFlow.value = RegInfo(
                        sessionId = resp.optString("session_id"),
                        code = resp.optString("code"),
                        shortCode = resp.optString("short_code").ifBlank { null },
                        qrImageUrl = resp.optString("qr_image_url").ifBlank { null },
                        qrTarget = resp.optString("qr_target").ifBlank { null },
                    )
                    true
                }
        } catch (t: Throwable) {
            Log.w(TAG, "host/register failed", t)
            false
        }
    }

    private fun startRegistrationLoop() {
        Thread {
            var delayMs = 3000L
            while (active) {
                if (registerOnce()) {
                    Log.i(TAG, "registered as ${sessionId}")
                    startSubsystems()
                    return@Thread
                }
                try { Thread.sleep(delayMs) } catch (_: InterruptedException) { return@Thread }
                delayMs = (delayMs * 2).coerceAtMost(60_000L)
            }
        }.also { it.isDaemon = true; it.start() }
    }

    @Synchronized private fun startSubsystems() {
        if (subsystemsStarted || !active) return
        subsystemsStarted = true
        connectCloudWs()
        startLongPollFallback()
        startCloudPinger()
        startKeyboardWatcher()
    }

    // ─────────────────────── input dispatch ───────────────────────

    private fun dispatch(payload: JSONObject) {
        when (payload.optString("action").lowercase()) {
            "seek" -> {
                val pos = payload.optLong("position_ms", -1L)
                if (pos >= 0) {
                    try {
                        sendBroadcast(Intent(ACTION_PLAYER_CMD).apply {
                            putExtra("cmd", "seek")
                            putExtra("position_ms", pos)
                        })
                    } catch (t: Throwable) {
                        Log.w(TAG, "seek broadcast failed", t)
                    }
                }
            }
            "next_episode" -> {
                try {
                    sendBroadcast(Intent(ACTION_PLAYER_CMD).apply {
                        putExtra("cmd", "next_episode")
                    })
                } catch (t: Throwable) {
                    Log.w(TAG, "next-episode broadcast failed", t)
                }
            }
            // v2.18.0 — Companion app: play / open content on the box.
            "companion_play", "companion_open" -> handleCompanion(payload)
            // v2.18.3 — Player extras from the phone's now-playing
            // sheet: cycle stream source / kill subtitles.
            "swap_stream", "subtitles_off" -> {
                try {
                    sendBroadcast(Intent(ACTION_PLAYER_CMD).apply {
                        putExtra("cmd", payload.optString("action"))
                    })
                } catch (t: Throwable) {
                    Log.w(TAG, "player-cmd broadcast failed", t)
                }
            }
            else -> RootInputDispatcher.handle(this, payload)
        }
    }

    /** v2.18.0 — Launch the right app with a deep-link built from a
     *  Companion phone command.  Mirrors the V2 AI voice assistant's
     *  battle-tested `vesper_route` contract for Vesper; Tunes and
     *  Live TV get simple extras their MainActivities understand. */
    private fun handleCompanion(payload: JSONObject) {
        val target = payload.optString("target")
        val action = payload.optString("action")
        try {
            val pkg = when (target) {
                "vesper" -> tv.onnow.launcher.AppPackages.VESPER
                "tunes"  -> tv.onnow.launcher.AppPackages.TUNES
                "livetv" -> tv.onnow.launcher.AppPackages.LIVETV
                "kids"   -> tv.onnow.launcher.AppPackages.KIDS
                "fta"    -> "tv.onnowtv.fta.recycler"
                else -> return
            }
            val launch = packageManager.getLaunchIntentForPackage(pkg) ?: run {
                Log.w(TAG, "companion: $pkg not installed")
                pushToast("The ${companionLabel(target)} app isn't installed on this box yet — install it on the TV first.")
                return
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (action == "companion_play") {
                when (target) {
                    "vesper" -> {
                        val title = payload.optString("title")
                        if (title.isBlank()) return
                        val enc = java.net.URLEncoder.encode(title, "UTF-8")
                        val type = if (payload.optString("media_type") == "series") "series" else "movie"
                        var route = "/?v2ai=$enc&type=$type&autoplay=1"
                        val imdb = payload.optString("imdb")
                        if (imdb.isNotBlank()) {
                            route += "&imdb=" + java.net.URLEncoder.encode(imdb, "UTF-8")
                        }
                        val profile = payload.optString("profile")
                        if (profile.isNotBlank()) {
                            route += "&companionProfile=" + java.net.URLEncoder.encode(profile, "UTF-8")
                        }
                        // v2.18.3 — Exact-episode deep-link from the
                        // phone's series page.
                        val season = payload.optInt("season", 0)
                        val episode = payload.optInt("episode", 0)
                        if (season > 0 && episode > 0) {
                            route += "&season=$season&episode=$episode"
                        }
                        launch.putExtra("vesper_route", route)
                        launch.data = android.net.Uri.parse("onnowtv://launch$route")
                    }
                    "tunes" -> {
                        val trackId = payload.optString("track_id")
                        val route = if (trackId.isNotBlank()) {
                            "/music?companionPlayTrack=$trackId"
                        } else {
                            payload.optString("route").ifBlank { return }
                        }
                        launch.putExtra("tunes_route", route)
                    }
                    "livetv" -> {
                        val sid = payload.optString("stream_id")
                        if (sid.isBlank()) return
                        launch.putExtra("companion_stream_id", sid)
                        launch.putExtra("companion_stream_name", payload.optString("name"))
                    }
                }
            }
            startActivity(launch)
            Log.i(TAG, "companion: launched $target ($action)")
        } catch (t: Throwable) {
            Log.w(TAG, "companion launch failed", t)
        }
    }

    private fun companionLabel(target: String): String = when (target) {
        "vesper" -> "Movies"
        "tunes"  -> "Music"
        "livetv" -> "Live TV"
        "kids"   -> "Kids"
        "fta"    -> "Free-to-Air"
        else     -> target
    }

    /** v2.18.2 — Which suite apps are installed on this box; the phone
     *  Companion greys out / guards tiles for the missing ones. */
    private fun installedAppsJson(): JSONObject = JSONObject().apply {
        val map = mapOf(
            "vesper" to tv.onnow.launcher.AppPackages.VESPER,
            "tunes"  to tv.onnow.launcher.AppPackages.TUNES,
            "livetv" to tv.onnow.launcher.AppPackages.LIVETV,
            "kids"   to tv.onnow.launcher.AppPackages.KIDS,
            "fta"    to "tv.onnowtv.fta.recycler",
        )
        for ((k, pkg) in map) put(k, packageManager.getLaunchIntentForPackage(pkg) != null)
    }

    /** v2.18.2 — One-shot toast on the phone (e.g. "app not installed").
     *  Fastest transport first: LAN socket, cloud WS, then HTTP state. */
    private fun pushToast(text: String) {
        val json = JSONObject().put("type", "toast").put("text", text).toString()
        try { localServer?.broadcastTransient(json) } catch (_: Throwable) {}
        if (cloudWsOpen) {
            try { cloudWs?.send(json); return } catch (_: Throwable) {}
        }
        val sid = sessionId ?: return
        Thread {
            try {
                val body = JSONObject().put("toast", text).toString().toRequestBody(jsonType)
                ResilientHttp.client.newCall(
                    Request.Builder().url("${baseUrl}/api/remote/host/state/$sid").post(body).build()
                ).execute().close()
            } catch (_: Throwable) {}
        }.also { it.isDaemon = true }.start()
    }

    // ─────────────────────── LAN direct server ────────────────────

    private fun startLocalServer() {
        Thread {
            val pageFile = File(filesDir, "remote_page.html")
            refreshCachedPage(pageFile)
            var server: LocalRemoteServer? = null
            for (port in LOCAL_PORTS) {
                try {
                    val s = LocalRemoteServer(
                        port, code ?: "", pageFile, baseUrl ?: "",
                    ) { payload -> dispatch(payload) }
                    s.start(30_000, true)
                    server = s
                    break
                } catch (t: Throwable) {
                    Log.w(TAG, "local server port $port busy", t)
                }
            }
            localServer = server
            if (server == null) {
                Log.w(TAG, "local server failed on all ports — cloud only")
                return@Thread
            }
            // v2.18.5 — Keep the LAN-served page fresh without waiting
            // for a reboot: re-download it every 6 h.
            Thread {
                while (true) {
                    try { Thread.sleep(6 * 60 * 60 * 1000L) } catch (_: InterruptedException) { return@Thread }
                    refreshCachedPage(pageFile)
                }
            }.also { it.isDaemon = true }.start()
            val ip = localIpv4()
            localIp = ip
            Log.i(TAG, "local remote server on $ip:${server.listeningPort}")
            if (ip != null && sessionId != null) reportLocalEndpoint(ip, server.listeningPort)
        }.also { it.isDaemon = true }.start()
    }

    /** Cache the latest remote page from the cloud so the LAN server
     *  always serves the newest design without an APK update. */
    private fun refreshCachedPage(pageFile: File) {
        try {
            val req = Request.Builder().url("${baseUrl}/remote").get().build()
            ResilientHttp.client.newCall(req).execute().use { r ->
                if (r.isSuccessful) {
                    val html = r.body?.string().orEmpty()
                    if (html.contains("</html>")) {
                        val tmp = File(pageFile.parentFile, pageFile.name + ".tmp")
                        tmp.writeText(html)
                        tmp.renameTo(pageFile)
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "remote page refresh failed (will use cached copy)", t)
        }
    }

    private fun reportLocalEndpoint(ip: String, port: Int) {
        try {
            val body = JSONObject().apply {
                put("local_ip", ip)
                put("local_port", port)
            }.toString().toRequestBody(jsonType)
            val url = "${baseUrl}/api/remote/host/local/${sessionId}"
            ResilientHttp.client.newCall(
                Request.Builder().url(url).post(body).build()
            ).execute().close()
        } catch (t: Throwable) {
            Log.w(TAG, "host/local report failed", t)
        }
    }

    private fun localIpv4(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    } catch (_: Throwable) { null }

    // ─────────────────────── cloud WebSocket ──────────────────────

    private fun connectCloudWs() {
        if (!active) return
        val sid = sessionId ?: return
        val base = baseUrl ?: return
        val wsUrl = base.replaceFirst("http", "ws") + "/api/remote/ws/host/$sid"
        try {
            cloudWs = ResilientHttp.client.newWebSocket(
                Request.Builder().url(wsUrl).build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        cloudWsOpen = true
                        Log.i(TAG, "cloud WS connected")
                        pushState(includeNowPlaying = true, includeKeyboard = true)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        try {
                            val msg = JSONObject(text)
                            if (msg.optString("type") == "input") {
                                msg.optJSONObject("payload")?.let { dispatch(it) }
                            }
                        } catch (_: Throwable) {}
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        cloudWsOpen = false
                        scheduleCloudReconnect()
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        cloudWsOpen = false
                        scheduleCloudReconnect()
                    }
                },
            )
        } catch (t: Throwable) {
            Log.w(TAG, "cloud WS connect failed", t)
            scheduleCloudReconnect()
        }
    }

    private fun scheduleCloudReconnect() {
        if (!active) return
        Thread {
            try { Thread.sleep(2500) } catch (_: InterruptedException) { return@Thread }
            if (!active || cloudWsOpen) return@Thread
            // Re-register first: keeps the device session alive across
            // backend restarts (in-memory store) and refreshes LAN info.
            registerOnce()
            connectCloudWs()
        }.also { it.isDaemon = true }.start()
    }

    private fun startCloudPinger() {
        cloudPingThread = Thread {
            while (active) {
                try { Thread.sleep(20_000) } catch (_: InterruptedException) { break }
                if (!active) break
                if (cloudWsOpen) {
                    try { cloudWs?.send("""{"type":"ping"}""") } catch (_: Throwable) {}
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    // ─────────────────────── long-poll fallback ───────────────────

    private fun startLongPollFallback() {
        inputPollerThread = Thread {
            var since = 0L
            while (active) {
                try {
                    // When the cloud WS is up the backend never queues,
                    // so back off instead of hammering the endpoint.
                    if (cloudWsOpen) {
                        Thread.sleep(3000)
                        continue
                    }
                    val sid = sessionId ?: break
                    val url = "${baseUrl}/api/remote/host/poll/$sid?since=$since&wait=20"
                    val req = Request.Builder().url(url).get().build()
                    val body = ResilientHttp.longPollClient.newCall(req).execute()
                        .use { it.body?.string().orEmpty() }
                    if (body.isEmpty()) continue
                    val parsed = JSONObject(body)
                    val maxSeq = parsed.optLong("seq", since)
                    if (maxSeq > since) since = maxSeq
                    val arr = parsed.optJSONArray("inputs") ?: continue
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val payload = item.optJSONObject("payload") ?: continue
                        dispatch(payload)
                    }
                } catch (t: Throwable) {
                    if (!active) break
                    try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    // ─────────────────────── keyboard detection ───────────────────

    private fun startKeyboardWatcher() {
        keyboardThread = Thread {
            while (active) {
                try {
                    val phoneListening = cloudWsOpen || (localServer?.hasClients() == true)
                    if (!phoneListening) {
                        Thread.sleep(3000)
                        continue
                    }
                    val out = RootQueryShell.query(
                        "dumpsys input_method | grep -E 'mInputShown|mIsInputViewShown'",
                        timeoutMs = 2000,
                    )
                    if (out != null) {
                        val shown = out.contains("mInputShown=true") ||
                            out.contains("mIsInputViewShown=true")
                        if (shown != curKeyboard) {
                            curKeyboard = shown
                            pushState(includeNowPlaying = false, includeKeyboard = true)
                        }
                    }
                    Thread.sleep(1200)
                } catch (_: InterruptedException) {
                    break
                } catch (t: Throwable) {
                    try { Thread.sleep(3000) } catch (_: InterruptedException) { break }
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    // ─────────────────────── state push ───────────────────────────

    private fun pushState(includeNowPlaying: Boolean, includeKeyboard: Boolean) {
        val msg = JSONObject().put("type", "state")
        try { msg.put("apps", installedAppsJson()) } catch (_: Throwable) {}
        if (includeNowPlaying) msg.put("now_playing", curNowPlaying ?: JSONObject.NULL)
        if (includeKeyboard || includeNowPlaying) msg.put("keyboard", curKeyboard)
        val json = msg.toString()
        try { localServer?.broadcastState(json) } catch (_: Throwable) {}
        if (cloudWsOpen) {
            try {
                cloudWs?.send(json)
                return
            } catch (_: Throwable) {}
        }
        // Cloud WS down → best-effort HTTP so a cloud-mode phone still
        // gets state via its polling fallback.
        val sid = sessionId ?: return
        Thread {
            try {
                val body = json.toRequestBody(jsonType)
                val url = "${baseUrl}/api/remote/host/state/$sid"
                ResilientHttp.client.newCall(
                    Request.Builder().url(url).post(body).build()
                ).execute().close()
            } catch (_: Throwable) {}
        }.also { it.isDaemon = true }.start()
    }

    // ─────────────────────── lifecycle ────────────────────────────

    private fun registerNowPlayingReceiver() {
        if (nowPlayingReceiverRegistered) return
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(nowPlayingReceiver, IntentFilter(ACTION_NOW_PLAYING), Context.RECEIVER_EXPORTED)
                registerReceiver(keyboardReceiver, IntentFilter(ACTION_KEYBOARD), Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(nowPlayingReceiver, IntentFilter(ACTION_NOW_PLAYING))
                registerReceiver(keyboardReceiver, IntentFilter(ACTION_KEYBOARD))
            }
            nowPlayingReceiverRegistered = true
        } catch (t: Throwable) {
            Log.w(TAG, "remote receivers registration failed", t)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        active = false
        subsystemsStarted = false
        cloudWsOpen = false
        try { cloudWs?.close(1000, "stop") } catch (_: Throwable) {}
        cloudWs = null
        try { localServer?.stop() } catch (_: Throwable) {}
        localServer = null
        inputPollerThread?.interrupt(); inputPollerThread = null
        keyboardThread?.interrupt(); keyboardThread = null
        cloudPingThread?.interrupt(); cloudPingThread = null
        curKeyboard = false
        curNowPlaying = null
        if (nowPlayingReceiverRegistered) {
            try { unregisterReceiver(nowPlayingReceiver) } catch (_: Throwable) {}
            try { unregisterReceiver(keyboardReceiver) } catch (_: Throwable) {}
            nowPlayingReceiverRegistered = false
        }
        try { RootInputDispatcher.shutdown() } catch (_: Throwable) {}
        try { RootQueryShell.shutdown() } catch (_: Throwable) {}
        // NOTE: no /host/cancel — the session is persistent so a saved
        // phone shortcut reconnects the moment the service is back.
    }

    private fun deviceId(): String =
        tv.onnow.launcher.onboarding.OnboardingActivity.deviceId(this)

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CH_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CH_ID, "Phone Remote", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Phone remote hosting for this box"
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, RemoteControlService::class.java).setAction(ACTION_STOP)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else PendingIntent.FLAG_UPDATE_CURRENT
        val stopPi = PendingIntent.getService(this, 1, stopIntent, flags)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CH_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setContentTitle("Phone remote ready")
            .setContentText("Scan the QR on the launcher to control this box from your phone.")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(stopPi)
            .build()
    }
}
