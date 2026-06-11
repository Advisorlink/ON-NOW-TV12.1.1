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
 * apply.  No cookies required for ~95 % of mainstream queries; if
 * the box's IP itself ever gets rate-limited the Tier 2 sign-in
 * flow will populate cookies on the downloader.
 *
 * Cached 4 h per (artist|title) — googlevideo URLs are signed and
 * typically live ~6 h, so we stay well under expiry.
 */
object YouTubeResolver {

    private const val TAG = "YouTubeResolver"
    private const val CACHE_TTL_MS = 4L * 60 * 60 * 1000  // 4 hours

    private data class CacheEntry(val payload: JSONObject, val expiresAt: Long)

    private val cache = ConcurrentHashMap<String, CacheEntry>()

    @Volatile
    private var initialised = false

    @Synchronized
    private fun ensureInit() {
        if (initialised) return
        // Initialise NewPipe with our OkHttp downloader and a UK
        // English locale (matches the user's spoken English).
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
     * On success: keys above (url / title / uploader / yt_id / …).
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

        val query = "$artist $title audio"
        try {
            val service = ServiceList.YouTube
            val searchExtractor = service.getSearchExtractor(
                query,
                listOf("music_songs"),  // YouTube Music filter — biases to
                                        // official-audio uploads, no MVs
                "",
            )
            searchExtractor.fetchPage()
            val page = searchExtractor.initialPage
            val items = page.items.orEmpty()

            // Prefer the first StreamInfoItem (filters out channels +
            // playlists which can show up before the first track).
            val firstStream = items.firstOrNull { it is StreamInfoItem } as? StreamInfoItem
                ?: return errorJson("no YouTube results for '$query'")

            val streamInfo = StreamInfo.getInfo(service, firstStream.url)
            val audioStreams = streamInfo.audioStreams.orEmpty()
            if (audioStreams.isEmpty()) {
                return errorJson("no audio streams for '${streamInfo.name}'")
            }
            // Pick the highest-bitrate stream — typically m4a 128/256.
            val best = audioStreams.maxByOrNull { it.averageBitrate.coerceAtLeast(0) }
                ?: audioStreams.first()

            val payload = JSONObject().apply {
                put("ok", true)
                put("url", best.content)
                put("title", streamInfo.name)
                put("uploader", streamInfo.uploaderName)
                put("duration", streamInfo.duration)
                put("yt_id", streamInfo.id)
                put("bitrate", best.averageBitrate)
                put("format", best.format?.name ?: "")
                put("source", "newpipe")
            }
            cache[key] = CacheEntry(payload, now + CACHE_TTL_MS)
            return payload
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
