package tv.vesper.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.webkit.JavascriptInterface
import android.widget.Toast

/**
 * JavaScript ↔ Android bridge.
 *
 * The web app calls window.OnNowTV.playVideo(url, title, mime) when
 * the user picks a stream.  We hand the URL off to the user's
 * preferred system video player (VLC, MX Player, Kodi, ExoPlayer-based
 * players, anything that handles ACTION_VIEW for video MIME types).
 *
 * Why?  The HK1 box's WebView is software-rendered for video — no
 * audio without gesture, awful frame rates on 1080p HLS.  System
 * players are hardware-accelerated, handle every codec under the sun,
 * have built-in subtitle pickers, and are what the user already knows.
 */
class WebAppInterface(private val activity: Activity) {

    /**
     * Return the SharedPreferences-backed progress map as JSON so
     * the web app can populate its Continue Watching shelf with
     * accurate positions.  Shape:
     *
     *   { "<cwId>": { "positionMs": 12345, "durationMs": 67890,
     *                  "updatedAt": 1700000000000 }, ... }
     */
    @JavascriptInterface
    fun getProgressMap(): String {
        val prefs = activity.getSharedPreferences(
            "onnowtv_progress", android.content.Context.MODE_PRIVATE
        )
        val out = org.json.JSONObject()
        for ((k, v) in prefs.all) {
            if (v is String) {
                try {
                    out.put(k, org.json.JSONObject(v))
                } catch (_: Exception) { /* ignore malformed */ }
            }
        }
        return out.toString()
    }

    /**
     * v2.10.63 — Host-package identity for the React app.
     *
     * Returned values:
     *   "tv.onnowtv.app"    Vesper TV (Movies / TV)
     *   "tv.onnowtv.kids"   Kids
     *   "tv.onnowtv.tunes"  Tunes
     *   "tv.onnowtv.fta"    FTA
     *
     * Lets the React frontend tell which APK shell it's running
     * inside.  Critical for two flows:
     *
     *   1. HARD GUARD against Vesper ever rendering KidsHome.  Vesper
     *      used to host a "Kids profile" inside the same React app
     *      (pre-v2.9.2).  Kids is now a standalone APK and Vesper
     *      must never enter Kids mode, no matter what stale
     *      localStorage value lingers from the old integration.
     *
     *   2. Per-app login lockout scoping.  The backend keys
     *      brute-force lockouts by `IP:username` today, which means
     *      a failed login in Kids blocks the same user in Vesper
     *      (same IP, same username).  Once the React app sends
     *      `app_id=<host_package>` with /auth/login, the backend
     *      can key lockouts by `IP:username:app_id` so the two
     *      apps don't punish each other.
     */
    @JavascriptInterface
    fun getHostPackage(): String = activity.packageName

    @JavascriptInterface
    fun playVideo(url: String, title: String?, mime: String?) {
        // Legacy bridge — kept for backwards compat with v1.1.x APKs.
        // Routes to the internal libVLC player.
        playInternal(url, title, null)
    }

    /**
     * v2.7.50 — JS pushes the current hash route here every time it
     * changes (HashRouter listener in App.jsx).  We persist the URL
     * to SharedPreferences("onnowtv_route") with a timestamp so
     * MainActivity can restore it on cold-start when Android killed
     * the activity during ExoPlayer playback.
     *
     * The url arg is just the hash path ("#/title/movie/tt123") —
     * not the full file URL — so we resolve it against the bundled
     * asset URL prefix before saving.
     */
    @JavascriptInterface
    fun saveRoute(hashPath: String) {
        if (hashPath.isBlank()) return
        try {
            val cleanHash = if (hashPath.startsWith("#")) hashPath else "#$hashPath"
            val full = "file:///android_asset/web/index.html$cleanHash"
            activity.getSharedPreferences("onnowtv_route", android.content.Context.MODE_PRIVATE)
                .edit()
                .putString("last_url", full)
                .putLong("last_ts", System.currentTimeMillis())
                .apply()
        } catch (_: Exception) { /* best effort */ }
    }



    // ──────────────────────────────────────────────────────────────
    // v2.7.39 — Video player backend toggle (LibVLC ⇄ ExoPlayer)
    // ──────────────────────────────────────────────────────────────
    //
    // Stremio uses ExoPlayer.  The user reports persistent buffering
    // on the libVLC backend and wants to A/B test ExoPlayer side by
    // side.  These two bridge methods let the React Settings page
    // flip the preference at runtime.  The pref is read inside
    // playInternalRichV2 and routes the launch to either VlcPlayerActivity
    // or ExoPlayerActivity — same intent contract, completely transparent
    // to the React layer.
    //
    // SharedPreferences (file "vesper_player", key "use_exoplayer_backend"):
    //   • true  → ExoPlayer (experimental).
    //   • false → LibVLC (default — stable, supports every codec).
    //
    // The active backend is shown as a giant glass badge top-left of
    // the player so the user always knows which one they're testing.

    /** True when ExoPlayer is the active backend, false for LibVLC. */
    @JavascriptInterface
    fun getPlayerBackend(): String {
        // v2.7.87 — Mirror the new shouldUseExoPlayer() logic so the
        // Settings UI shows the correct active backend after the
        // bulletproof default rolled out.
        return if (ExoPlayerActivity.shouldUseExoPlayer(activity)) {
            "exoplayer"
        } else {
            "libvlc"
        }
    }

    /** Set the backend.  Pass "exoplayer" or "libvlc". */
    @JavascriptInterface
    fun setPlayerBackend(backend: String) {
        val useExo = backend.equals("exoplayer", ignoreCase = true)
                  || backend.equals("exo", ignoreCase = true)
        activity.getSharedPreferences(
            "vesper_player", android.content.Context.MODE_PRIVATE
        ).edit()
            .putBoolean(ExoPlayerActivity.PREF_KEY_USE_EXO, useExo)
            // v2.7.87 — Mark the user's choice as explicit so the
            // new bulletproof default honours it.  Picking ExoPlayer
            // clears the flag (so a future user can re-pick LibVLC
            // freely); picking LibVLC SETS the flag (so it sticks).
            .putBoolean(ExoPlayerActivity.PREF_KEY_EXPLICIT_LIBVLC, !useExo)
            .apply()
        // Visible confirmation so the user knows the switch landed.
        activity.runOnUiThread {
            Toast.makeText(
                activity,
                if (useExo) {
                    "✓ Now using ExoPlayer · play any title to test"
                } else {
                    "✓ Now using LibVLC · play any title to test"
                },
                Toast.LENGTH_LONG
            ).show()
        }
    }

    @JavascriptInterface
    fun playInternal(url: String, title: String?, subtitleUrl: String?) {
        if (url.isBlank()) return
        activity.runOnUiThread {
            try {
                // v2.7.61 — Honour user's player preference here too.
                val useExo = ExoPlayerActivity.shouldUseExoPlayer(activity)
                val targetClass = if (useExo) {
                    ExoPlayerActivity::class.java
                } else {
                    VlcPlayerActivity::class.java
                }
                val intent = android.content.Intent(activity, targetClass).apply {
                    putExtra(VlcPlayerActivity.EXTRA_URL, url)
                    putExtra(VlcPlayerActivity.EXTRA_TITLE, title)
                    putExtra(VlcPlayerActivity.EXTRA_SUB_URL, subtitleUrl)
                    // v2.7.28 — no FLAG_ACTIVITY_NEW_TASK so back
                    // returns to wherever the player was launched
                    // from (Detail page in WebView) — not Home.
                }
                activity.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(
                    activity,
                    "Could not start player: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Rich variant — used by the web layer to pass the full cinematic
     * preview meta (poster / backdrop / synopsis / year / rating /
     * runtime / genres) so the native player can render a Stremio-
     * style loading screen instead of a bare spinner.
     */
    @JavascriptInterface
    fun playInternalRich(
        url: String,
        title: String?,
        subtitleUrl: String?,
        poster: String?,
        backdrop: String?,
        synopsis: String?,
        year: String?,
        rating: String?,
        runtime: String?,
        genres: String?,
        type: String?,
        startAtMs: Long,
        cwId: String?
    ) {
        // Delegate to the V2 method with empty streams payload — keeps
        // backward-compat for any caller that hasn't been updated to
        // pass `streamsJson`.  Default Kotlin params don't survive the
        // JavascriptInterface reflection lookup so we must declare both
        // overloads as actual @JavascriptInterface methods.
        playInternalRichV2(
            url, title, subtitleUrl, poster, backdrop, synopsis,
            year, rating, runtime, genres, type, startAtMs, cwId,
            null, -1
        )
    }

    /**
     * v2.7.27 — V2 with alt-streams payload for the in-player
     * stream picker.  Web layer should call this when available;
     * `playInternalRich` falls back to this with empty streams.
     */
    @JavascriptInterface
    fun playInternalRichV2(
        url: String,
        title: String?,
        subtitleUrl: String?,
        poster: String?,
        backdrop: String?,
        synopsis: String?,
        year: String?,
        rating: String?,
        runtime: String?,
        genres: String?,
        type: String?,
        startAtMs: Long,
        cwId: String?,
        streamsJson: String?,
        currentStreamIdx: Int
    ) {
        if (url.isBlank()) return
        activity.runOnUiThread {
            try {
                // v2.7.39 — Player backend switch.  When the user
                // has toggled ExoPlayer ON in Settings, route the
                // launch to ExoPlayerActivity instead.  Both
                // activities accept the same key extras (`stream_url`,
                // `title`, `start_at_ms`) so the bridge is identical.
                val useExo = ExoPlayerActivity.shouldUseExoPlayer(activity)
                val targetClass = if (useExo) {
                    ExoPlayerActivity::class.java
                } else {
                    VlcPlayerActivity::class.java
                }
                val intent = android.content.Intent(activity, targetClass).apply {
                    // v2.7.40 — both VlcPlayerActivity and
                    // ExoPlayerActivity read the same VlcPlayerActivity.EXTRA_*
                    // keys, so a single putExtra block works for either.
                    putExtra(VlcPlayerActivity.EXTRA_URL, url)
                    putExtra(VlcPlayerActivity.EXTRA_TITLE, title)
                    putExtra(VlcPlayerActivity.EXTRA_SUB_URL, subtitleUrl)
                    putExtra(VlcPlayerActivity.EXTRA_POSTER, poster)
                    putExtra(VlcPlayerActivity.EXTRA_BACKDROP, backdrop)
                    putExtra(VlcPlayerActivity.EXTRA_SYNOPSIS, synopsis)
                    putExtra(VlcPlayerActivity.EXTRA_YEAR, year)
                    putExtra(VlcPlayerActivity.EXTRA_RATING, rating)
                    putExtra(VlcPlayerActivity.EXTRA_RUNTIME, runtime)
                    putExtra(VlcPlayerActivity.EXTRA_GENRES, genres)
                    putExtra(VlcPlayerActivity.EXTRA_TYPE, type)
                    putExtra(VlcPlayerActivity.EXTRA_START_AT_MS, startAtMs)
                    putExtra(VlcPlayerActivity.EXTRA_CW_ID, cwId)
                    putExtra(VlcPlayerActivity.EXTRA_STREAMS_JSON, streamsJson)
                    putExtra(VlcPlayerActivity.EXTRA_CURRENT_STREAM_IDX, currentStreamIdx)
                    // v2.7.28 — no NEW_TASK: BACK returns to detail.
                }
                activity.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(
                    activity,
                    "Could not start player: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Watch-Together variant — same payload as playInternalRich but
     * also passes the party code + role + member id + ws url so the
     * VlcPlayerActivity can open a sync WebSocket and emit/apply
     * play/pause/seek events.
     */
    @JavascriptInterface
    fun playInternalParty(
        url: String,
        title: String?,
        subtitleUrl: String?,
        poster: String?,
        backdrop: String?,
        synopsis: String?,
        year: String?,
        rating: String?,
        runtime: String?,
        genres: String?,
        type: String?,
        startAtMs: Long,
        cwId: String?,
        partyCode: String,
        partyRole: String,
        partyMemberId: String?,
        partyWsUrl: String,
        // v2.6.69: avatar emoji + display name so the native player
        // can render avatar bubbles next to reactions and identify
        // who in the party is reacting.  Optional — if blank, the
        // player falls back to "🎬" + role-suffixed labels.
        partyAvatarEmoji: String?,
        partyDisplayName: String?
    ) {
        if (url.isBlank()) return
        activity.runOnUiThread {
            try {
                // v2.7.61 — Respect the user's player preference even
                // for party playback.  Previously this path was
                // hard-coded to VlcPlayerActivity so toggling
                // Settings → Video Player → ExoPlayer had no effect
                // when joining/hosting Watch Together.  Now we
                // honour `shouldUseExoPlayer` everywhere, and
                // ExoPlayerActivity carries the new native voice dock
                // (v2.7.60 / PartyVoiceManager).
                val useExo = ExoPlayerActivity.shouldUseExoPlayer(activity)
                val targetClass = if (useExo) {
                    ExoPlayerActivity::class.java
                } else {
                    VlcPlayerActivity::class.java
                }
                val intent = android.content.Intent(activity, targetClass).apply {
                    putExtra(VlcPlayerActivity.EXTRA_URL, url)
                    putExtra(VlcPlayerActivity.EXTRA_TITLE, title)
                    putExtra(VlcPlayerActivity.EXTRA_SUB_URL, subtitleUrl)
                    putExtra(VlcPlayerActivity.EXTRA_POSTER, poster)
                    putExtra(VlcPlayerActivity.EXTRA_BACKDROP, backdrop)
                    putExtra(VlcPlayerActivity.EXTRA_SYNOPSIS, synopsis)
                    putExtra(VlcPlayerActivity.EXTRA_YEAR, year)
                    putExtra(VlcPlayerActivity.EXTRA_RATING, rating)
                    putExtra(VlcPlayerActivity.EXTRA_RUNTIME, runtime)
                    putExtra(VlcPlayerActivity.EXTRA_GENRES, genres)
                    putExtra(VlcPlayerActivity.EXTRA_TYPE, type)
                    putExtra(VlcPlayerActivity.EXTRA_START_AT_MS, startAtMs)
                    putExtra(VlcPlayerActivity.EXTRA_CW_ID, cwId)
                    putExtra(VlcPlayerActivity.EXTRA_PARTY_CODE, partyCode)
                    putExtra(VlcPlayerActivity.EXTRA_PARTY_ROLE, partyRole)
                    putExtra(VlcPlayerActivity.EXTRA_PARTY_MEMBER_ID, partyMemberId)
                    putExtra(VlcPlayerActivity.EXTRA_PARTY_WS_URL, partyWsUrl)
                    putExtra(VlcPlayerActivity.EXTRA_PARTY_AVATAR_EMOJI, partyAvatarEmoji ?: "")
                    putExtra(VlcPlayerActivity.EXTRA_PARTY_DISPLAY_NAME, partyDisplayName ?: "")
                    // v2.7.28 — no NEW_TASK: BACK returns to detail.
                }
                activity.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(
                    activity,
                    "Could not start party player: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Trailer-specific variant.  Accepts an OPTIONAL `audioUrl`
     * which gets attached to the player as an audio slave so we can
     * play YouTube's HD video-only stream and the matching audio
     * stream together as a single playback session.  YouTube only
     * serves combined audio+video MP4 up to 360p; HD is DASH (split
     * streams).  Without the slave, the trailer is silent.
     */
    @JavascriptInterface
    fun playTrailer(
        url: String,
        audioUrl: String?,
        title: String?,
        poster: String?,
        backdrop: String?
    ) {
        if (url.isBlank()) return
        activity.runOnUiThread {
            try {
                val intent = android.content.Intent(activity, VlcPlayerActivity::class.java).apply {
                    putExtra(VlcPlayerActivity.EXTRA_URL, url)
                    putExtra(VlcPlayerActivity.EXTRA_AUDIO_URL, audioUrl ?: "")
                    putExtra(VlcPlayerActivity.EXTRA_TITLE, title ?: "Trailer")
                    putExtra(VlcPlayerActivity.EXTRA_POSTER, poster ?: "")
                    putExtra(VlcPlayerActivity.EXTRA_BACKDROP, backdrop ?: "")
                    putExtra(VlcPlayerActivity.EXTRA_TYPE, "trailer")
                    // v2.7.28 — no NEW_TASK: BACK returns to detail.
                }
                activity.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(
                    activity,
                    "Could not start trailer: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    @JavascriptInterface
    fun playExternal(url: String, title: String?, mime: String?) {
        // Opt-in path: hand to system video player (VLC stand-alone,
        // MX Player, Kodi, etc.).  Used by power users from a button
        // inside our own player.
        if (url.isBlank()) return
        activity.runOnUiThread {
            try {
                val uri = Uri.parse(url)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mime ?: guessMime(url))
                    if (!title.isNullOrBlank()) {
                        putExtra("title", title)
                        putExtra("itemTitle", title)
                        putExtra("video_title", title)
                        putExtra("decode_mode", 1)
                    }
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                val chooser = Intent.createChooser(intent, "Play with…")
                activity.startActivity(chooser)
            } catch (e: Exception) {
                Toast.makeText(
                    activity,
                    "No external video player installed.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    @JavascriptInterface
    fun isAndroidHost(): Boolean = true

    /**
     * Dev-mode network override.  Persists a URL in
     * SharedPreferences("onnowtv-dev") that MainActivity.onCreate
     * uses instead of the bundled file:// URL on every launch.
     * Pass `null` or "" to clear and return to the bundled SPA.
     * After setting, we reload the WebView so the change takes
     * effect immediately without a force-close.
     */
    @JavascriptInterface
    fun setDevUrl(url: String?) {
        val prefs = activity.getSharedPreferences("onnowtv-dev", android.content.Context.MODE_PRIVATE)
        if (url.isNullOrBlank()) {
            prefs.edit().remove("dev_url").apply()
        } else {
            prefs.edit().putString("dev_url", url).apply()
        }
        // Reload the WebView (must run on UI thread).
        activity.runOnUiThread {
            val target = if (url.isNullOrBlank()) "file:///android_asset/web/index.html" else url
            try {
                val mainAct = activity as? MainActivity
                mainAct?.findViewById<android.webkit.WebView>(android.R.id.content)
                // Simpler: pull the webview field directly via the
                // bridge — but the WebAppInterface only has access
                // through the activity.  We trigger a reload via JS.
            } catch (_: Exception) {}
            activity.recreate()
        }
    }

    /** Current dev URL, or "" if unset. */
    @JavascriptInterface
    fun getDevUrl(): String {
        val prefs = activity.getSharedPreferences("onnowtv-dev", android.content.Context.MODE_PRIVATE)
        return prefs.getString("dev_url", "") ?: ""
    }

    /**
     * Force-SDR playback flag.  When TRUE, the libVLC player uses
     * full software decoding (`:codec=avcodec`) which guarantees
     * BT.709 SDR output regardless of stream HDR side data.  Useful
     * for non-HDR projectors / TVs that wash out colour when fed an
     * HDR signal.  Costs ~30 % CPU on the HK1.  Defaults to OFF.
     */
    @JavascriptInterface
    fun setForceSdr(enabled: Boolean) {
        activity.getSharedPreferences("onnowtv_player", android.content.Context.MODE_PRIVATE)
            .edit()
            .putBoolean("force_sdr_playback", enabled)
            .apply()
    }

    @JavascriptInterface
    fun getForceSdr(): Boolean {
        return activity.getSharedPreferences("onnowtv_player", android.content.Context.MODE_PRIVATE)
            .getBoolean("force_sdr_playback", false)
    }



    /**
     * Launch the system speech recognizer (Google Voice / OEM STT)
     * and route the recognized text back to the React side.  React
     * stashes a Promise resolver in `window.__voiceSearch[callbackId]`
     * before calling this; the Activity's onActivityResult fires
     * `window.__voiceSearchResult(id, text, error)` once done.
     */
    @JavascriptInterface
    fun startVoiceSearch(callbackId: String) {
        activity.runOnUiThread {
            if (activity is MainActivity) {
                (activity as MainActivity).startVoiceRecognition(callbackId)
            } else {
                activity.runOnUiThread {
                    activity.window?.decorView?.post {
                        // Should never happen, but emit a graceful error.
                        val esc = callbackId.replace("\\", "\\\\").replace("'", "\\'")
                        val js = "window.__voiceSearchResult && " +
                            "window.__voiceSearchResult('$esc','','no-host')"
                        // No webView handle here — best-effort no-op.
                    }
                }
            }
        }
    }

    @JavascriptInterface
    fun deviceClass(): String {
        // Crude low-end heuristic — most cheap HK1/RK boxes have <=2 GB RAM
        // and weak GPUs.  The web app uses this to disable expensive
        // backdrop-blurs, ken-burns, and grain overlays.
        val rt = Runtime.getRuntime()
        val maxMb = rt.maxMemory() / (1024 * 1024)
        return if (maxMb < 256) "low" else "normal"
    }

    private fun guessMime(url: String): String {
        val lower = url.lowercase()
        return when {
            lower.contains(".m3u8") -> "application/x-mpegurl"
            lower.contains(".mpd") -> "application/dash+xml"
            lower.contains(".mp4") -> "video/mp4"
            lower.contains(".mkv") -> "video/x-matroska"
            lower.contains(".webm") -> "video/webm"
            lower.contains(".ts") -> "video/mp2t"
            else -> "video/*"
        }
    }

    /**
     * Native HTTP GET that returns the JSON response body as a
     * string.  Used by the React side to call stream addons
     * (notably Torrentio) which block calls from datacenter IPs —
     * the HK1 box has a residential IP, so calls fired from Kotlin
     * succeed where the backend proxy fails.
     *
     * Returns a JSON object like:
     *   {"ok": true, "status": 200, "body": "{...}"}
     *   {"ok": false, "status": 0,   "error": "timeout"}
     *
     * Blocking call from Kotlin's perspective; JS treats it as
     * synchronous because @JavascriptInterface methods are invoked
     * on a private binder thread.  Hard cap timeout of 20 s.
     */
    @JavascriptInterface
    fun fetchUrl(url: String, timeoutMs: Int): String {
        return try {
            val u = java.net.URL(url)
            val conn = u.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = timeoutMs.coerceIn(1000, 30000)
            conn.readTimeout = timeoutMs.coerceIn(1000, 30000)
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json,text/plain,*/*")
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 11; HK1) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/119.0 Mobile Safari/537.36 OnNowTV/1.0"
            )
            conn.instanceFollowRedirects = true
            val code = conn.responseCode
            val stream =
                if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            org.json.JSONObject().apply {
                put("ok", code in 200..299)
                put("status", code)
                put("body", body)
            }.toString()
        } catch (e: Exception) {
            org.json.JSONObject().apply {
                put("ok", false)
                put("status", 0)
                put("error", e.message ?: e.javaClass.simpleName)
            }.toString()
        }
    }

    /**
     * In-player Live Guide bridge.
     *
     * Persists the current Live TV channel list (and the EPG that
     * goes with it) to SharedPreferences so VlcPlayerActivity can
     * read it on launch and render an in-player channel browser
     * overlay.
     *
     * Stored under `live_guide` prefs:
     *   - "categories" : JSON array  [{id, name, count}, ...]
     *   - "channels"   : JSON array  [{stream_id, name, logo,
     *                                  category_id, epg_channel_id,
     *                                  stream_url}, ...]
     *   - "epg"        : JSON object {stream_id_str: [{title,
     *                                  startTimestamp, stopTimestamp,
     *                                  description}, ...], ...}
     *   - "provider_id": String  (so we can invalidate when the user
     *                              switches providers)
     *   - "updated_at" : Long unix ms
     *
     * Called by LiveTV.jsx each time the channel list is freshly
     * loaded.  Idempotent — overwrites on every call.
     */
    @JavascriptInterface
    fun setLiveGuide(
        providerId: String?,
        categoriesJson: String?,
        channelsJson: String?,
        epgJson: String?,
        favoritesJson: String? = null
    ) {
        try {
            val prefs = activity.getSharedPreferences("live_guide", android.content.Context.MODE_PRIVATE)
            prefs.edit()
                .putString("provider_id", providerId ?: "")
                .putString("categories", categoriesJson ?: "[]")
                .putString("channels",   channelsJson   ?: "[]")
                .putString("epg",        epgJson        ?: "{}")
                .putString("favorites",  favoritesJson  ?: "[]")
                .putLong  ("updated_at", System.currentTimeMillis())
                .apply()
        } catch (e: Throwable) {
            // Never let a bridge call crash the WebView.  The Live
            // Guide overlay will just be empty until the next call.
        }
    }

    /**
     * v2.7.78 — File-backed EPG bridge.
     *
     * SharedPreferences's XML serialiser was bottlenecking us — anything
     * beyond ~2 MB of EPG JSON either silently truncated through the
     * JS↔Java bridge or made `apply()` block the main thread for
     * seconds while it rewrote the entire prefs file.  Result: the
     * in-player Live Guide showed "No programme information available"
     * because the EPG payload was either missing or corrupted.
     *
     * This method writes the full EPG (potentially 30+ MB raw) straight
     * to `filesDir/live_guide/epg.json`.  We write SYNCHRONOUSLY (the
     * JS-bridge call doesn't return until the file is durably on disk)
     * so the caller can be 100% sure the file is ready before
     * dismissing the loading splash.  The bridge call runs on the
     * WebView's JS thread, NOT the UI thread, so a 200–400 ms write
     * never causes an ANR.
     *
     * Pairs with the existing `setLiveGuide(...)` call: that still
     * carries the small categories + channels + favourites in
     * SharedPreferences, while THIS call carries the heavy EPG.
     */
    @JavascriptInterface
    fun setLiveGuideEpg(epgJson: String?): String {
        val body = epgJson ?: "{}"
        val started = System.currentTimeMillis()
        return try {
            val dir = java.io.File(activity.filesDir, "live_guide")
            if (!dir.exists()) dir.mkdirs()
            val tmp = java.io.File(dir, "epg.json.tmp")
            tmp.writeText(body, Charsets.UTF_8)
            val target = java.io.File(dir, "epg.json")
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                // Atomic rename failed (some filesystems) — fall
                // back to a direct write so we never leave a
                // half-written EPG behind.
                target.writeText(body, Charsets.UTF_8)
                try { tmp.delete() } catch (_: Throwable) {}
            }
            // Persist a tiny pointer so LiveGuideManager can verify
            // the file is fresh (and how big it should be).
            val prefs = activity.getSharedPreferences(
                "live_guide", android.content.Context.MODE_PRIVATE
            )
            prefs.edit()
                .putLong("epg_file_updated_at", System.currentTimeMillis())
                .putInt ("epg_file_size_bytes", target.length().toInt())
                .commit()  // commit() not apply() — block until written
            org.json.JSONObject().apply {
                put("ok", true)
                put("size_bytes", target.length())
                put("write_ms", System.currentTimeMillis() - started)
            }.toString()
        } catch (e: Throwable) {
            // Best-effort failure — caller can decide whether to
            // retry or warn the user.  We don't crash the WebView.
            org.json.JSONObject().apply {
                put("ok", false)
                put("error", e.message ?: e.javaClass.simpleName)
                put("write_ms", System.currentTimeMillis() - started)
            }.toString()
        }
    }

    /**
     * v2.7.78 — Tiny diagnostic for the React side to confirm the
     * native EPG file actually landed (and how big it is).  Used by
     * the boot splash to display "12,540 channels cached on device".
     */
    @JavascriptInterface
    fun getLiveGuideEpgMeta(): String {
        return try {
            val f = java.io.File(java.io.File(activity.filesDir, "live_guide"), "epg.json")
            val prefs = activity.getSharedPreferences(
                "live_guide", android.content.Context.MODE_PRIVATE
            )
            org.json.JSONObject().apply {
                put("exists", f.exists())
                put("size_bytes", if (f.exists()) f.length() else 0L)
                put("updated_at", prefs.getLong("epg_file_updated_at", 0L))
            }.toString()
        } catch (e: Throwable) {
            "{\"exists\":false,\"size_bytes\":0,\"updated_at\":0,\"error\":\"" +
                (e.message ?: e.javaClass.simpleName) + "\"}"
        }
    }

    /**
     * v2.7.74 — Persist the React app's REACT_APP_BACKEND_URL so the
     * native ExoPlayer overlay (Live Guide TMDB lookups, Watch
     * Together STT) can talk to the same backend the WebView is
     * using.  Called once on app boot by `lib/host.js`.
     */
    @JavascriptInterface
    fun setBackendBase(url: String?) {
        try {
            val base = (url ?: "").trim().trimEnd('/')
            if (base.isBlank()) return
            val prefs = activity.getSharedPreferences("app_meta", android.content.Context.MODE_PRIVATE)
            prefs.edit().putString("backend_base", base).apply()
        } catch (_: Throwable) {}
    }

    /**
     * Download the new APK and hand it to the system installer.
     *
     * Called by `UpdateGate.jsx` when the user clicks "Download and
     * install" while running an outdated version.  Uses Android's
     * `DownloadManager` (which shows the standard system download
     * notification + handles retries / WebView-independent resume),
     * then fires an ACTION_VIEW intent with the APK mime type so the
     * platform PackageInstaller takes over.
     *
     * On Android 8+ the user is prompted to allow ON NOW TV to
     * install unknown apps; once they do (and tap "OK") the install
     * proceeds and Android automatically reopens our app afterwards.
     *
     * Progress callbacks are routed back to the WebView so the JS
     * gate can show "Downloading … 47 %" instead of an indefinite
     * spinner.
     */
    @JavascriptInterface
    fun installApk(apkUrl: String) {
        if (apkUrl.isBlank()) {
            postUpdateEvent("error", "URL is empty")
            return
        }
        activity.runOnUiThread {
            try {
                val ctx = activity.applicationContext
                val cacheDir = java.io.File(ctx.externalCacheDir, "updates")
                if (!cacheDir.exists()) cacheDir.mkdirs()
                // Always overwrite — we never want a stale half-download
                // sitting around eating disk + tricking us.
                val apkFile = java.io.File(cacheDir, "onnowtv-update.apk")
                if (apkFile.exists()) apkFile.delete()

                postUpdateEvent("started", apkUrl)

                val dm = ctx.getSystemService(android.content.Context.DOWNLOAD_SERVICE)
                        as android.app.DownloadManager
                val req = android.app.DownloadManager.Request(Uri.parse(apkUrl)).apply {
                    setTitle("ON NOW TV update")
                    setDescription("Downloading the new version…")
                    setMimeType("application/vnd.android.package-archive")
                    setDestinationUri(Uri.fromFile(apkFile))
                    setNotificationVisibility(
                        android.app.DownloadManager.Request
                            .VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    )
                    setAllowedOverMetered(true)
                    setAllowedOverRoaming(true)
                }
                val downloadId = dm.enqueue(req)

                // Poll the DownloadManager every 600 ms so the gate
                // shows real progress.  Stops when the download
                // succeeds, fails, or the app is destroyed.
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                val poll = object : Runnable {
                    override fun run() {
                        val q = android.app.DownloadManager.Query().setFilterById(downloadId)
                        val cur = dm.query(q)
                        if (cur != null && cur.moveToFirst()) {
                            val status = cur.getInt(
                                cur.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_STATUS)
                            )
                            val downloaded = cur.getLong(
                                cur.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                            )
                            val total = cur.getLong(
                                cur.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                            )
                            cur.close()
                            when (status) {
                                android.app.DownloadManager.STATUS_RUNNING,
                                android.app.DownloadManager.STATUS_PAUSED,
                                android.app.DownloadManager.STATUS_PENDING -> {
                                    val pct = if (total > 0) (downloaded * 100 / total).toInt() else -1
                                    postUpdateEvent("progress", pct.toString())
                                    handler.postDelayed(this, 600)
                                }
                                android.app.DownloadManager.STATUS_SUCCESSFUL -> {
                                    postUpdateEvent("downloaded", apkFile.absolutePath)
                                    launchInstaller(apkFile)
                                }
                                android.app.DownloadManager.STATUS_FAILED -> {
                                    postUpdateEvent("error", "Download failed (status=$status)")
                                }
                                else -> {
                                    handler.postDelayed(this, 600)
                                }
                            }
                        } else {
                            // No row yet — keep polling briefly.
                            handler.postDelayed(this, 600)
                        }
                    }
                }
                handler.post(poll)
            } catch (e: Throwable) {
                postUpdateEvent("error", e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** Launch the system package installer for a freshly-downloaded
     *  APK.  Routed through a FileProvider content:// URI because
     *  file:// URIs have been forbidden on Android 7+ since 2017. */
    private fun launchInstaller(apkFile: java.io.File) {
        try {
            val ctx = activity.applicationContext
            val uri = androidx.core.content.FileProvider.getUriForFile(
                ctx,
                ctx.packageName + ".fileprovider",
                apkFile,
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            activity.startActivity(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            // Pre-Oreo or stripped TV ROMs without a PackageInstaller
            // — fall back to a chooser.
            postUpdateEvent("error", "No installer found on this device. Sideload manually.")
        } catch (e: SecurityException) {
            // Android 8+ blocks installs from apps that don't have
            // REQUEST_INSTALL_PACKAGES granted yet — funnel the user
            // to the settings page so they can enable it once.
            try {
                val settings = android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.packageName)
                )
                activity.startActivity(settings)
                postUpdateEvent(
                    "error",
                    "Please tap 'Allow' so ON NOW TV can install updates."
                )
            } catch (_: Throwable) {
                postUpdateEvent("error", "Install blocked: " + (e.message ?: "SecurityException"))
            }
        } catch (e: Throwable) {
            postUpdateEvent("error", e.message ?: e.javaClass.simpleName)
        }
    }

    /** Post an update-lifecycle event back to the WebView so the JS
     *  gate can render progress.  Routed via the global hook
     *  `window.__onUpdateEvent(stage, info)`. */
    private fun postUpdateEvent(stage: String, info: String) {
        try {
            val main = activity as? MainActivity ?: return
            val webView = main.webViewOrNull() ?: return
            val safeInfo = info.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ")
            val js = "if(window.__onUpdateEvent)window.__onUpdateEvent('" + stage + "','" + safeInfo + "');"
            webView.post { webView.evaluateJavascript(js, null) }
        } catch (_: Throwable) {
            /* WebView gone — silent. */
        }
    }

    /** Generic "open this URL in the system browser / Downloader app"
     *  helper.  Used by UpdateGate as a fallback when the native
     *  installer fails for any reason, and could be used elsewhere
     *  for external links (e.g., support pages). */
    @JavascriptInterface
    fun openExternal(url: String) {
        if (url.isBlank()) return
        activity.runOnUiThread {
            try {
                val intent = android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    Uri.parse(url),
                )
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                activity.startActivity(intent)
            } catch (e: Throwable) {
                Toast.makeText(
                    activity,
                    "Could not open browser: " + (e.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * v2.8.13 — Called from KidsExitPin.jsx the moment the parent
     * enters the correct PIN.  Stops Lock Task Mode if we entered
     * it, then finishes the Vesper Activity → Android returns to
     * the previous app (the Launcher).  No more "press Back five
     * times to get out of Kids".
     */
    @JavascriptInterface
    fun exitVesperToLauncher() {
        activity.runOnUiThread {
            try {
                // If we're in Lock Task Mode (screen pinning), exit
                // first.  stopLockTask() is a no-op if not pinned.
                @Suppress("DEPRECATION")
                activity.stopLockTask()
            } catch (_: Throwable) { /* not pinned — ignore */ }
            activity.finish()
        }
    }

    /**
     * v2.8.13 — Called from KidsHome the moment Kids mode becomes
     * active.  Pins the Vesper Activity so the system HOME / RECENTS
     * keys can NOT navigate away until exitVesperToLauncher() (i.e.
     * correct PIN) is called.  Android shows a one-time system
     * prompt on the first activation; users tap "Got it" once and
     * it never appears again.  Silent no-op on devices that don't
     * support pinning.
     */
    /**
     * v2.8.42 — Kids-sandbox lock-state bridge.  Called by the React
     * frontend whenever Kids mode is activated WITH A PIN configured
     * (locked=true) and when the PIN is successfully entered to exit
     * (locked=false).
     *
     * The launcher polls the matching GET endpoint
     * (/api/launcher/kids-lock/{device_id}) on every onResume — if
     * locked it instantly bounces the user back to Vesper with the
     * Kids deep-link, so the HOME button can NEVER let a kid escape
     * the sandbox.
     *
     * Network failures are swallowed silently — the lockdown is a
     * best-effort enhancement, not a security boundary.  The
     * in-WebView PIN gate (`useKidsBackGuard` + `KidsExitPin`) is
     * still the source of truth for the actual lock.
     */
    @JavascriptInterface
    fun setKidsLock(locked: Boolean) {
        Thread {
            try {
                val backendBase = activity.getSharedPreferences(
                    "app_meta", android.content.Context.MODE_PRIVATE
                ).getString("backend_base", "")
                    ?.trim()
                    ?.trimEnd('/')
                    ?.takeIf { it.isNotBlank() }
                    ?: "https://onnowhub.com"  // v2.10.58 — Cloudflare-fronted default
                // Talk to the LAUNCHER backend, not Vesper's backend —
                // the kids-lock endpoint lives under /launcher/* on
                // the VPS Nginx proxy.
                val launcherBase = "$backendBase/launcher"
                val deviceId = android.provider.Settings.Secure.getString(
                    activity.contentResolver,
                    android.provider.Settings.Secure.ANDROID_ID,
                ) ?: "unknown"
                val url = java.net.URL("$launcherBase/api/launcher/kids-lock")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.connectTimeout = 5_000
                conn.readTimeout = 5_000
                conn.setRequestProperty("Content-Type", "application/json")
                val body = """{"device_id":"$deviceId","locked":$locked}"""
                conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                android.util.Log.i(
                    "VesperKids",
                    "setKidsLock locked=$locked deviceId=$deviceId → HTTP $code"
                )
                conn.disconnect()
            } catch (t: Throwable) {
                android.util.Log.w("VesperKids", "setKidsLock failed", t)
            }
        }.start()
    }
}
