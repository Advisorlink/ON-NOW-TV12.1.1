package tv.vesper.app.data

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * v2.16.31 — Reports Vesper native-player playback to the launcher
 * admin "Live" tab.  Mirrors [tv.onnowtv.livetv.data.PresenceReporter]
 * but for the movies / series / FTA video path.
 *
 * ── Why this file exists ─────────────────────────────────────────
 * Vesper movies + FTA episodes DO NOT play through React's
 * `Player.jsx`.  The JS bridge (`WebAppInterface.playVideo…`)
 * spawns a native `ExoPlayerActivity` or `VlcPlayerActivity`, and
 * that is the ONLY player the user ever sees on the TV.  Any
 * heartbeat wired in the React player therefore never fires on
 * the box — this class is what actually shows up in the Live tab.
 *
 * ── Auth ─────────────────────────────────────────────────────────
 * Native player has no direct access to the WebView's localStorage
 * where the Vesper JWT lives.  React pushes the current Vesper
 * username into SharedPreferences via `WebAppInterface.setPresenceUser`
 * once at login and after every profile switch; we read it back
 * here and post via the shared `X-Presence-Key` ingest path.
 */
object VesperPresenceReporter {

    private const val TAG = "VesperPresenceReporter"
    private const val HEARTBEAT_MS = 30_000L
    private const val INGEST_KEY = "onnow-presence-ingest-3f9c2a71b8de405e9047ac1d6f8b3e5c"

    // The Vesper backend URL.  We set this from WebAppInterface at
    // WebView boot so the reporter doesn't need to guess.  Falls
    // back to the production host if the JS side hasn't primed it
    // yet, which is very unlikely by the time the user hits play
    // but harmless as a safety net.
    @Volatile private var apiBase: String = "https://onnowhub.com"

    private const val PREFS = "vesper_presence"
    private const val KEY_USERNAME = "username"
    private const val KEY_API_BASE = "api_base"

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }
    private val io = Executors.newSingleThreadScheduledExecutor()
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    @Volatile private var sessionId: String? = null
    @Volatile private var contentTitle: String? = null
    @Volatile private var contentKind: String = "movie"
    @Volatile private var contentId: String = ""
    @Volatile private var future: ScheduledFuture<*>? = null

    /** Store the Vesper username + API base URL for later use by the
     *  native player.  Called from `WebAppInterface.setPresenceUser`. */
    fun setUser(ctx: Context, username: String?, apiBaseUrl: String?) {
        val prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (!username.isNullOrBlank()) prefs.putString(KEY_USERNAME, username.trim())
        else                          prefs.remove(KEY_USERNAME)
        if (!apiBaseUrl.isNullOrBlank()) {
            apiBase = apiBaseUrl.trimEnd('/')
            prefs.putString(KEY_API_BASE, apiBase)
        }
        prefs.apply()
    }

    private fun readUsername(ctx: Context): String {
        return try {
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_USERNAME, "").orEmpty().trim()
        } catch (_: Throwable) { "" }
    }

    private fun readApiBase(ctx: Context): String {
        return try {
            val stored = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_API_BASE, "").orEmpty().trim().trimEnd('/')
            if (stored.isNotBlank()) { apiBase = stored; stored } else apiBase
        } catch (_: Throwable) { apiBase }
    }

    /** Kick off heartbeats for the currently-playing title. */
    fun start(ctx: Context, title: String, kind: String = "movie", id: String = "") {
        val user = readUsername(ctx)
        if (user.isBlank() || title.isBlank()) return
        val base = readApiBase(ctx)
        // End any prior session before we replace it — the admin
        // gets a clean "movie A → movie B" transition instead of
        // two overlapping rows.
        val prev = sessionId
        val prevTitle = contentTitle
        if (prev != null && prevTitle != title) postEnd(base, prev)
        val ses = prev?.takeIf { prevTitle == title } ?: UUID.randomUUID().toString()
        sessionId = ses
        contentTitle = title
        contentKind = kind
        contentId = id
        future?.cancel(false)
        io.submit { postHeartbeat(base, user) }
        future = io.scheduleWithFixedDelay(
            { postHeartbeat(base, user) },
            HEARTBEAT_MS,
            HEARTBEAT_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    /** Explicit end — drops the row from the admin Live tab. */
    fun stop(ctx: Context) {
        future?.cancel(false)
        future = null
        val ses = sessionId ?: return
        val base = readApiBase(ctx)
        sessionId = null
        contentTitle = null
        contentId = ""
        postEnd(base, ses)
    }

    // ── HTTP helpers ─────────────────────────────────────────────
    private fun postHeartbeat(base: String, user: String) {
        val ses = sessionId ?: return
        val title = contentTitle ?: return
        val payload = JSONObject().apply {
            put("session_id", ses)
            put("app", "movies")
            put("content_kind", contentKind)
            put("content_id", contentId)
            put("content_title", title)
            put("client_key", user)
        }
        val req = Request.Builder()
            .url("$base/api/presence/heartbeat")
            .addHeader("X-Presence-Key", INGEST_KEY)
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()
        try {
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) Log.w(TAG, "hb http ${r.code}")
            }
        } catch (t: Throwable) {
            Log.d(TAG, "hb failed: ${t.message}")
        }
    }

    private fun postEnd(base: String, ses: String) {
        val payload = JSONObject().apply {
            put("session_id", ses)
            put("app", "movies")
        }
        val req = Request.Builder()
            .url("$base/api/presence/end")
            .addHeader("X-Presence-Key", INGEST_KEY)
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()
        io.submit {
            try { http.newCall(req).execute().close() }
            catch (t: Throwable) { Log.d(TAG, "end failed: ${t.message}") }
        }
    }
}
