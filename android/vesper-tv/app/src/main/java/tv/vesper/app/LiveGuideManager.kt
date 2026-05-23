package tv.vesper.app

/*
 * v2.7.74 — Native Live TV Guide for ExoPlayer.
 *
 * Replaces the old VLC `LiveGuideController` (XML View Activity) with
 * a Jetpack Compose overlay that lives on top of ExoPlayer's
 * `PlayerView`.  Video keeps playing in the background while the
 * guide is open — push LEFT to slide it in, BACK / MENU to dismiss.
 *
 * Design follows the user's mockup (see /app/CHANGELOG.md v2.7.74):
 *   - Left channel rail with number + logo + name (cyan selection
 *     ring + chevron pointer).
 *   - Middle programme info column (LIVE pill, title, synopsis,
 *     time range, progress bar, HD/5.1/CC pills).
 *   - Right side: video keeps playing (no fancy backdrop image —
 *     user explicitly picked option (b) in the locking question).
 *   - Bottom "UP NEXT" LazyRow with TMDB-resolved thumbnails.
 *   - Push LEFT a second time from the channel rail → reveals a
 *     categories column inset to the left.
 *   - Hover a channel for 1 s → auto-tune (no OK required).
 *
 * Data source: SharedPreferences key "live_guide" populated by
 *   WebAppInterface.setLiveGuide (already wired in v2.6.x).
 */

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class LiveGuideManager(
    private val ctx: Context,
    private val backendBase: String,
    private val initialChannelStreamId: String,
    private val initialChannelStreamUrl: String = "",
) {
    companion object {
        private const val TAG = "LiveGuide"
        const val MODE_CLOSED = 0
        const val MODE_CHANNELS = 1     // channel rail visible
        const val MODE_BOTH     = 2     // category rail + channel rail visible
    }

    /* ───────────────── Data models ─────────────────── */
    data class LiveChannel(
        val streamId: String,
        val number: Int,
        val name: String,
        val logo: String,
        val categoryId: String,
        val streamUrl: String,
        val epgChannelId: String,
    )
    data class LiveCategory(
        val id: String,
        val name: String,
        val count: Int,
    )
    data class LiveProgramme(
        val title: String,
        val desc: String,
        val startMs: Long,
        val stopMs: Long,
        val season: String,
        val episode: String,
        val episodeTitle: String,
        val year: String,
    )
    data class EpgArt(
        val backdropUrl: String,
        val posterUrl: String,
        val tmdbTitle: String,
    )

    /* ───────────────── State flows ─────────────────── */
    private val _mode = MutableStateFlow(MODE_CLOSED)
    val mode: StateFlow<Int> = _mode.asStateFlow()

    private val _categories = MutableStateFlow<List<LiveCategory>>(emptyList())
    val categories: StateFlow<List<LiveCategory>> = _categories.asStateFlow()

    private val _channels = MutableStateFlow<List<LiveChannel>>(emptyList())
    val channels: StateFlow<List<LiveChannel>> = _channels.asStateFlow()

    private val _epg = MutableStateFlow<Map<String, List<LiveProgramme>>>(emptyMap())
    val epg: StateFlow<Map<String, List<LiveProgramme>>> = _epg.asStateFlow()

    // Currently selected category id, or null = "All channels".
    private val _selectedCategoryId = MutableStateFlow<String?>(null)
    val selectedCategoryId: StateFlow<String?> = _selectedCategoryId.asStateFlow()

    // Channel currently FOCUSED in the rail (drives the middle info
    // column + the up-next strip).
    private val _focusedChannelId = MutableStateFlow<String?>(null)
    val focusedChannelId: StateFlow<String?> = _focusedChannelId.asStateFlow()

    // Channel currently PLAYING (after a tune, or initial one).
    private val _playingChannelId = MutableStateFlow<String?>(initialChannelStreamId)
    val playingChannelId: StateFlow<String?> = _playingChannelId.asStateFlow()

    // Visible channels = filtered by selected category.  Recomputed
    // when either list or selectedCategoryId changes.
    val visibleChannels: StateFlow<List<LiveChannel>> = combine(_channels, _selectedCategoryId) { all, cat ->
        if (cat == null) all else all.filter { it.categoryId == cat }
    }.stateIn(
        scope = CoroutineScope(Dispatchers.Default),
        started = SharingStarted.Eagerly,
        initialValue = emptyList(),
    )

    /* ───────────────── TMDB EPG art ─────────────────── */
    private val artCache = ConcurrentHashMap<String, EpgArt>()
    private val artInFlight = ConcurrentHashMap<String, Boolean>()
    private val artClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
    private val artScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun cachedArt(title: String, year: String): EpgArt? {
        if (title.isBlank()) return null
        return artCache[artKey(title, year)]
    }

    /** Returns cached art if present, otherwise kicks off a lookup
     * and returns null.  Callers should subscribe to `artUpdates` so
     * they re-render when the lookup completes. */
    fun fetchArt(title: String, year: String) {
        if (title.isBlank()) return
        val k = artKey(title, year)
        if (artCache.containsKey(k) || artInFlight[k] == true) return
        artInFlight[k] = true
        artScope.launch {
            try {
                val url = "$backendBase/api/epg/art" +
                    "?title=" + java.net.URLEncoder.encode(title, "UTF-8") +
                    if (year.isNotBlank()) "&year=" + java.net.URLEncoder.encode(year, "UTF-8") else ""
                val req = Request.Builder().url(url).get().build()
                val resp = artClient.newCall(req).execute()
                val raw = try { resp.body?.string().orEmpty() } catch (_: Exception) { "" }
                resp.close()
                val json = JSONObject(raw.ifBlank { "{}" })
                val art = EpgArt(
                    backdropUrl = json.optString("backdrop", ""),
                    posterUrl   = json.optString("poster", ""),
                    tmdbTitle   = json.optString("tmdb_title", ""),
                )
                artCache[k] = art
                _artUpdateTick.value = _artUpdateTick.value + 1
            } catch (e: Exception) {
                Log.w(TAG, "epg art fetch failed for '$title': ${e.message}")
            } finally {
                artInFlight.remove(k)
            }
        }
    }
    private val _artUpdateTick = MutableStateFlow(0)
    val artUpdateTick: StateFlow<Int> = _artUpdateTick.asStateFlow()

    // v2.7.78 — Dedicated IO scope for EPG file hydration so we
    // never block the WebView / UI thread when parsing 30+ MB of
    // JSON.  Cancelled in `release()`.
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun artKey(title: String, year: String): String =
        title.trim().lowercase() + "|" + year.trim()

    /* ───────────────── Public API ─────────────────── */
    fun loadFromPreferences() {
        try {
            val prefs = ctx.getSharedPreferences("live_guide", Context.MODE_PRIVATE)
            val cats = parseCategories(prefs.getString("categories", "[]") ?: "[]")
            val chs = parseChannels(prefs.getString("channels", "[]") ?: "[]")
            _categories.value = cats
            _channels.value = chs
            // Default focused channel = the currently playing one.
            // We try TWO matching strategies before giving up:
            //   1. Match by streamId (fast, works when the player's
            //      URL parses cleanly into digits).
            //   2. Match by full streamUrl (fallback for URL shapes
            //      where the streamId extraction fails — e.g.
            //      `play.m3u8?stream_id=xxx`).
            // If BOTH fail, we leave focused null so the auto-tune
            // LaunchedEffect never fires.  Previously we defaulted
            // to chs.firstOrNull() (channel #1) which caused the
            // 1-second-after-guide-open ERROR_CODE_IO_NETWORK_
            // CONNECTION_FAILED bug — the player got auto-tuned to
            // a completely different channel the moment the guide
            // animated in.
            val playingId = _playingChannelId.value
            val byId = chs.firstOrNull { it.streamId == playingId }
            val byUrl = if (byId == null && initialChannelStreamUrl.isNotBlank())
                chs.firstOrNull { it.streamUrl == initialChannelStreamUrl } else null
            val initial = byId ?: byUrl
            if (initial != null) {
                _focusedChannelId.value = initial.streamId
                _playingChannelId.value = initial.streamId  // canonicalise
                _selectedCategoryId.value = null   // "All"
            } else {
                Log.w(
                    TAG,
                    "playing channel not found in cache (id='$playingId', url='$initialChannelStreamUrl') — leaving focus empty so auto-tune doesn't fire",
                )
            }
            // v2.7.78 — EPG is now stored in a file (see
            // WebAppInterface.setLiveGuideEpg).  Read from the file
            // on a background coroutine so the rail can paint
            // instantly while the (potentially 30+ MB) JSON parses.
            // SharedPreferences "epg" key is kept as a fallback for
            // older APKs.
            val epgFile = java.io.File(java.io.File(ctx.filesDir, "live_guide"), "epg.json")
            if (epgFile.exists() && epgFile.length() > 2L) {
                ioScope.launch {
                    try {
                        val raw = epgFile.readText(Charsets.UTF_8)
                        val parsed = parseEpg(raw)
                        _epg.value = parsed
                        Log.i(TAG, "EPG hydrated from file: ${parsed.size} channels, ${epgFile.length() / 1024}KB")
                    } catch (t: Throwable) {
                        Log.w(TAG, "EPG file parse failed", t)
                    }
                }
            } else {
                // Legacy fallback — older APK still pushes EPG via
                // SharedPreferences directly (small trim).
                val ep = parseEpg(prefs.getString("epg", "{}") ?: "{}")
                _epg.value = ep
            }
        } catch (t: Throwable) {
            Log.w(TAG, "loadFromPreferences failed", t)
        }
    }

    fun open() {
        if (_channels.value.isEmpty()) loadFromPreferences()
        if (_mode.value == MODE_CLOSED) _mode.value = MODE_CHANNELS
    }
    fun openCategories() {
        if (_channels.value.isEmpty()) loadFromPreferences()
        _mode.value = MODE_BOTH
    }
    fun close() { _mode.value = MODE_CLOSED }
    fun toggle() {
        if (_mode.value == MODE_CLOSED) open() else close()
    }

    fun setFocusedChannel(id: String) {
        if (_focusedChannelId.value == id) return
        _focusedChannelId.value = id
    }
    fun setSelectedCategory(id: String?) {
        if (_selectedCategoryId.value == id) return
        _selectedCategoryId.value = id
    }
    fun markPlaying(channelId: String) {
        _playingChannelId.value = channelId
    }

    fun nowProgramme(channelId: String): LiveProgramme? {
        val list = _epg.value[channelId] ?: return null
        val now = System.currentTimeMillis()
        return list.firstOrNull { it.startMs <= now && it.stopMs > now }
    }
    fun upNext(channelId: String, count: Int = 6): List<LiveProgramme> {
        val list = _epg.value[channelId] ?: return emptyList()
        val now = System.currentTimeMillis()
        return list.filter { it.startMs > now }.take(count)
    }

    fun release() {
        try { artScope.cancel() } catch (_: Exception) {}
        try { ioScope.cancel() } catch (_: Exception) {}
    }

    /* ───────────────── Parsing helpers ─────────────────── */
    private fun parseCategories(raw: String): List<LiveCategory> = try {
        val arr = JSONArray(raw)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            LiveCategory(
                id = o.optString("id", ""),
                name = o.optString("name", ""),
                count = o.optInt("count", 0),
            )
        }.filter { it.id.isNotBlank() }
    } catch (_: Throwable) { emptyList() }

    private fun parseChannels(raw: String): List<LiveChannel> = try {
        val arr = JSONArray(raw)
        val countByCat = HashMap<String, Int>()
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val sid = o.optString("stream_id", "")
            val url = o.optString("stream_url", "")
            val catId = o.optString("category_id", "")
            if (sid.isBlank() || url.isBlank()) return@mapNotNull null
            val n = (countByCat[catId] ?: 0) + 1
            countByCat[catId] = n
            LiveChannel(
                streamId = sid,
                number = n,
                name = o.optString("name", "Channel $sid"),
                logo = o.optString("logo", ""),
                categoryId = catId,
                epgChannelId = o.optString("epg_channel_id", ""),
                streamUrl = url,
            )
        }
    } catch (_: Throwable) { emptyList() }

    private fun parseEpg(raw: String): Map<String, List<LiveProgramme>> = try {
        val obj = JSONObject(raw)
        val out = HashMap<String, List<LiveProgramme>>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val sid = keys.next()
            val arr = obj.optJSONArray(sid) ?: continue
            val list = ArrayList<LiveProgramme>(arr.length())
            for (i in 0 until arr.length()) {
                val p = arr.getJSONObject(i)
                list.add(
                    LiveProgramme(
                        title = p.optString("title", ""),
                        desc  = p.optString("desc", ""),
                        startMs = p.optLong("startTimestamp", 0L),
                        stopMs  = p.optLong("stopTimestamp", 0L),
                        season = p.optString("season", ""),
                        episode = p.optString("episode", ""),
                        episodeTitle = p.optString("episodeTitle", ""),
                        year = p.optString("year", ""),
                    )
                )
            }
            if (list.isNotEmpty()) out[sid] = list
        }
        out
    } catch (_: Throwable) { emptyMap() }
}
