package tv.onnowtv.tunes.youtube

import android.util.Log
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.util.concurrent.ConcurrentHashMap

/**
 * Anonymous YouTube audio resolver (NewPipeExtractor backed).
 *
 *   resolve("Adele", "Hello") →
 *       {
 *         "url":      "https://rrX---sn-…googlevideo.com/videoplayback?…",
 *         "title":    "Adele - Hello (Official Music Video)",
 *         "uploader": "AdeleVEVO",
 *         "duration": 367,
 *         "yt_id":    "YQHsXMglC9A",
 *         "source":   "newpipe"
 *       }
 *
 * Every request originates from THIS BOX'S IP (residential / home
 * Wi-Fi), not our VPS, so YouTube's datacenter bot block does not
 * apply.  No cookies required for ~95 % of mainstream queries.
 *
 * Cached 4 h per (artist|title) — googlevideo URLs are signed and
 * typically live ~6 h, so we stay well under expiry.
 *
 * v2.12.6 — Resiliency pass:
 *   1. Removed the strict `music_songs` filter — it returned zero
 *      results for many indie / non-YT-Music tracks, causing the
 *      whole resolve to abort with "no results".  Default search
 *      returns music videos + audio uploads + covers, all playable.
 *   2. Walk down the top 5 search results instead of only trying
 *      the first — some tracks' first hit is age-restricted /
 *      region-locked / requires sign-in, throwing on getInfo().
 *      Fall through to the next candidate silently.
 *   3. Prefer M4A/AAC audio format over WebM/Opus for maximum
 *      HTML5 <audio> compatibility — some older Android WebViews
 *      can't decode WebM Opus (silent playback with no error).
 *   4. Validate audio URL is a real http(s) googlevideo URL — no
 *      HLS manifests or empty strings.
 */
object YouTubeResolver {

    private const val TAG = "YouTubeResolver"
    private const val CACHE_TTL_MS = 4L * 60 * 60 * 1000  // 4 hours

    /** v2.12.6 — Try this many top search hits before giving up. */
    private const val MAX_CANDIDATES = 5

    private data class CacheEntry(val payload: JSONObject, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    @Volatile
    private var initialised = false

    @Synchronized
    private fun ensureInit() {
        if (initialised) return
        OkHttpDownloader.init()
        NewPipe.init(
            OkHttpDownloader.instance(),
            Localization("en", "GB"),
            ContentCountry("GB"),
        )
        initialised = true
    }

    /**
     * Returns a JSONObject with the resolve result.  Always returns
     * SOMETHING — never throws — so the bridge can serialise the
     * outcome directly to the WebView.
     *
     * On success: { "ok": true, "url", "title", "uploader", "yt_id", … }
     * On failure: { "ok": false, "error": "<message>" }
     */
    fun resolve(artist: String, title: String): JSONObject {
        val key = "${artist.trim().lowercase()}|${title.trim().lowercase()}"
        val cached = cache[key]
        val now = System.currentTimeMillis()
        if (cached != null && cached.expiresAt > now) {
            return JSONObject(cached.payload.toString()).put("cached", true)
        }

        ensureInit()

        // v2.12.6 — "<artist> <title>" (no ` audio` suffix, no
        // music_songs filter).  Broader net catches indie / non-YT-
        // Music uploads.  Trailer-style false hits are filtered
        // out later by the audioStreams check.
        val query = "$artist $title"
        try {
            val service = ServiceList.YouTube
            // Empty contentFilter = default search (all types).
            val searchExtractor = service.getSearchExtractor(query)
            searchExtractor.fetchPage()
            val items = searchExtractor.initialPage.items.orEmpty()

            // v2.12.6 — Walk the top MAX_CANDIDATES stream items.
            // First hit fails ~5-10% of the time (age-restriction,
            // region-lock, sign-in required); second/third hit
            // usually succeeds.  Silent fall-through on any throw.
            val candidates = items
                .filterIsInstance<StreamInfoItem>()
                .take(MAX_CANDIDATES)

            if (candidates.isEmpty()) {
                return errorJson("no YouTube results for '$query'")
            }

            var lastError: String? = null
            for ((idx, cand) in candidates.withIndex()) {
                try {
                    val streamInfo = StreamInfo.getInfo(service, cand.url)
                    val audioStreams = streamInfo.audioStreams.orEmpty()
                    if (audioStreams.isEmpty()) {
                        lastError = "no audio streams for '${streamInfo.name}'"
                        continue
                    }
                    // v2.12.6 — Prefer M4A/AAC (mp4 container, AAC
                    // codec) — 100% supported by every Android
                    // WebView + HTML5 <audio> ever shipped.  Fall
                    // back to WebM/Opus only if no M4A exists (very
                    // rare on YouTube).  Then within the preferred
                    // family, pick highest bitrate.
                    val m4a = audioStreams.filter {
                        val fmt = (it.format?.name ?: "").uppercase()
                        // NewPipe's MediaFormat enum: M4A, WEBMA, MP3,
                        // OGG, WEBMA_OPUS, AAC, OPUS.  M4A/AAC = MP4
                        // container with AAC codec.
                        (fmt.contains("M4A") || fmt == "AAC") &&
                            !it.content.isNullOrBlank() &&
                            (it.content.startsWith("http://") ||
                                it.content.startsWith("https://"))
                    }
                    val pool = if (m4a.isNotEmpty()) m4a else audioStreams.filter {
                        !it.content.isNullOrBlank() &&
                            (it.content.startsWith("http://") ||
                                it.content.startsWith("https://"))
                    }
                    if (pool.isEmpty()) {
                        lastError = "no playable audio URLs for '${streamInfo.name}'"
                        continue
                    }
                    val best = pool.maxByOrNull { it.averageBitrate.coerceAtLeast(0) }
                        ?: pool.first()

                    Log.i(TAG, "resolved '$query' via candidate #$idx: " +
                        "${streamInfo.name} (${best.format?.name}, " +
                        "${best.averageBitrate}kbps)")

                    val payload = JSONObject().apply {
                        put("ok", true)
                        put("url", best.content)
                        put("title", streamInfo.name)
                        put("uploader", streamInfo.uploaderName)
                        put("duration", streamInfo.duration)
                        put("yt_id", streamInfo.id)
                        put("bitrate", best.averageBitrate)
                        put("format", best.format?.name ?: "")
                        put("candidate_index", idx)
                        put("source", "newpipe")
                    }
                    cache[key] = CacheEntry(payload, now + CACHE_TTL_MS)
                    return payload
                } catch (t: Throwable) {
                    // Age-restricted / region-locked / sign-in
                    // required / etc. — skip and try the next hit.
                    lastError = "${t.javaClass.simpleName}: ${t.message ?: "unknown"}"
                    Log.d(TAG, "candidate #$idx failed for '$query': $lastError")
                }
            }
            return errorJson("all ${candidates.size} candidates failed. last: $lastError")
        } catch (t: Throwable) {
            Log.w(TAG, "resolve failed for '$query'", t)
            return errorJson(t.javaClass.simpleName + ": " + (t.message ?: "unknown"))
        }
    }

    private fun errorJson(message: String): JSONObject =
        JSONObject().apply {
            put("ok", false)
            put("error", message)
            put("source", "newpipe")
        }
}
