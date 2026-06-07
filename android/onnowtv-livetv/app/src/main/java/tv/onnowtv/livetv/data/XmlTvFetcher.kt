package tv.onnowtv.livetv.data

import android.content.Context
import android.util.Log
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * v2.9.9 — Full XMLTV EPG downloader & stream-parser.
 *
 * The Xtream provider serves a `xmltv.php` endpoint that returns
 * GZIP-encoded XMLTV (Content-Encoding: gzip).  Raw size ~145 MB,
 * compressed transfer ~27 MB, downloads in ~5 s on a cable LAN.
 *
 * We CANNOT keep all 600k+ programmes in memory on a low-end TV
 * box.  Strategy:
 *   1. Caller passes the set of `epg_channel_id`s that belong to
 *      "priority" channels (UK + USA + AU Kayo + NZ Sports — the
 *      buckets the user wants populated before entering the EPG).
 *   2. We STREAM-parse the XMLTV with `XmlPullParser` — only
 *      programmes whose channel attribute matches a priority id
 *      are retained.  Everything else is dropped on the floor.
 *   3. For non-priority channels, EPG fills in on-demand via
 *      `DirectProviderFetcher.fetchShortEpg` when the user scrolls
 *      to that channel — same as the per-channel lazy-load path
 *      that already exists.
 *
 * Memory after parse for ~2,583 priority channels × ~72 h of guide:
 *   ~28 MB heap — well within a 1 GB Android TV box's headroom.
 */
object XmlTvFetcher {

    private const val TAG = "XmlTvFetcher"

    /**
     * Download + parse the provider's full XMLTV, returning a map
     * of `epg_channel_id → List<Programme>`.  Only programmes whose
     * channel id is in [priorityChannelIds] are retained.
     *
     * @param onProgress invoked from the parse loop with the running
     *    count of channels seen + programmes retained.  Used by the
     *    MainActivity loader to show animated counters.
     */
    suspend fun fetchPriorityEpg(
        ctx: Context,
        priorityChannelIds: Set<String>,
        onProgress: (channelsSeen: Int, programmesRetained: Int) -> Unit,
    ): Map<String, List<Programme>> = withContext(Dispatchers.IO) {
        val u = AuthStore.username(ctx).takeIf { it.isNotBlank() }
            ?: throw RuntimeException("No saved credentials")
        val p = AuthStore.password(ctx).takeIf { it.isNotBlank() }
            ?: throw RuntimeException("No saved credentials")
        val base = "${AuthStore.SCHEME}://${AuthStore.HOST}:${AuthStore.PORT}"
        val url = URL(
            "$base/xmltv.php?" +
                "username=${URLEncoder.encode(u, "UTF-8")}" +
                "&password=${URLEncoder.encode(p, "UTF-8")}",
        )

        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 90_000
            setRequestProperty("Accept-Encoding", "gzip")
            setRequestProperty("User-Agent", "ONNowTV/1.0")
            if (this is HttpsURLConnection) {
                try {
                    val trustAll = arrayOf<TrustManager>(
                        object : X509TrustManager {
                            override fun checkClientTrusted(
                                chain: Array<out java.security.cert.X509Certificate>?,
                                authType: String?,
                            ) {}
                            override fun checkServerTrusted(
                                chain: Array<out java.security.cert.X509Certificate>?,
                                authType: String?,
                            ) {}
                            override fun getAcceptedIssuers():
                                Array<java.security.cert.X509Certificate> = emptyArray()
                        },
                    )
                    val sslCtx = SSLContext.getInstance("TLS")
                    sslCtx.init(null, trustAll, SecureRandom())
                    sslSocketFactory = sslCtx.socketFactory
                    hostnameVerifier = HostnameVerifier { _, _ -> true }
                } catch (_: Throwable) {}
            }
        }

        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw RuntimeException("XMLTV HTTP $code")
            }
            val raw = conn.inputStream
            val stream = if ("gzip".equals(conn.contentEncoding, ignoreCase = true)) {
                GZIPInputStream(BufferedInputStream(raw, 64 * 1024))
            } else {
                BufferedInputStream(raw, 64 * 1024)
            }
            parseXmlTv(stream, priorityChannelIds, onProgress)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Stream-parse XMLTV.  Memory footprint is bounded by the
     * priority channel set — we never accumulate programmes for
     * non-priority channels.
     */
    private fun parseXmlTv(
        stream: java.io.InputStream,
        priorityChannelIds: Set<String>,
        onProgress: (channelsSeen: Int, programmesRetained: Int) -> Unit,
    ): Map<String, List<Programme>> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(stream, "UTF-8")

        val out = HashMap<String, MutableList<Programme>>(priorityChannelIds.size)
        val channelsSeen = HashSet<String>(8000)
        var retained = 0
        var lastProgressTick = 0L

        var inProgramme = false
        var curChannel: String? = null
        var curStartTs = 0L
        var curStopTs = 0L
        var curTitle: String? = null
        var curDesc: String? = null
        var currentTag: String? = null
        val titleBuf = StringBuilder()
        val descBuf = StringBuilder()

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "channel" -> {
                            val id = parser.getAttributeValue(null, "id")
                            if (!id.isNullOrBlank()) channelsSeen.add(id)
                        }
                        "programme" -> {
                            val ch = parser.getAttributeValue(null, "channel")
                            if (ch != null && priorityChannelIds.contains(ch)) {
                                inProgramme = true
                                curChannel = ch
                                curStartTs = parser.getAttributeValue(null, "start_timestamp")
                                    ?.toLongOrNull() ?: 0L
                                curStopTs = parser.getAttributeValue(null, "stop_timestamp")
                                    ?.toLongOrNull() ?: 0L
                                curTitle = null
                                curDesc = null
                            } else {
                                inProgramme = false
                            }
                        }
                        "title" -> if (inProgramme) {
                            currentTag = "title"
                            titleBuf.setLength(0)
                        }
                        "desc" -> if (inProgramme) {
                            currentTag = "desc"
                            descBuf.setLength(0)
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inProgramme) {
                        when (currentTag) {
                            "title" -> titleBuf.append(parser.text)
                            "desc" -> descBuf.append(parser.text)
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "title" -> {
                            if (inProgramme) {
                                curTitle = titleBuf.toString().trim()
                                currentTag = null
                            }
                        }
                        "desc" -> {
                            if (inProgramme) {
                                curDesc = descBuf.toString().trim().takeIf { it.isNotBlank() }
                                currentTag = null
                            }
                        }
                        "programme" -> {
                            if (inProgramme && curChannel != null && curStartTs > 0L) {
                                val list = out.getOrPut(curChannel!!) { ArrayList(64) }
                                list.add(
                                    Programme(
                                        title = curTitle?.takeIf { it.isNotBlank() } ?: "—",
                                        description = curDesc,
                                        startMs = curStartTs * 1000L,
                                        stopMs = curStopTs * 1000L,
                                    ),
                                )
                                retained++
                                // Throttle progress callbacks to ~10 Hz
                                val now = System.currentTimeMillis()
                                if (now - lastProgressTick > 100L) {
                                    onProgress(channelsSeen.size, retained)
                                    lastProgressTick = now
                                }
                            }
                            inProgramme = false
                            curChannel = null
                            curTitle = null
                            curDesc = null
                            currentTag = null
                        }
                    }
                }
            }
            event = parser.next()
        }
        // Final progress tick.
        onProgress(channelsSeen.size, retained)
        Log.i(
            TAG,
            "XMLTV parsed: ${channelsSeen.size} total channels seen, " +
                "${out.size} priority channels with EPG, $retained programmes retained",
        )
        return out
    }
}
