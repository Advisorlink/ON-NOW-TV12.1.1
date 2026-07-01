/*
 * v2.11.8 — Native YouTube trailer extractor.
 *
 * WHY THIS EXISTS
 * ---------------
 * Every previous trailer strategy (iframe embed, backend yt-dlp
 * proxy, backend download-to-disk) hit YouTube's server-side embed
 * flag ("Error 153 — Video player configuration error") because
 * our backend's IP address is aggressively bot-flagged by Google.
 * From the operator's residential Australian IP the situation is
 * much better — YouTube treats the box as a regular home user —
 * but the extraction has to happen ON THE DEVICE for that to work.
 *
 * NewPipeExtractor is the same library the NewPipe app on F-Droid
 * uses to play any YouTube video without an account.  It scrapes
 * YouTube's public web pages the same way an anonymous browser
 * does — no OAuth, no cookies, no sign-in flow.  Because the HTTP
 * requests originate from the operator's own device, YouTube sees
 * a normal residential user and never fires the anti-scraping
 * heuristics that broke every other approach.
 *
 * PIPELINE
 * --------
 * WebAppInterface.playTrailer(videoId)        // JS bridge from React
 *   → YouTubeTrailerExtractor.extract(videoId)  // background thread
 *   → NewPipe.getService(YouTube).getStreamExtractor(url)
 *   → StreamExtractor.fetchPage()               // pulls YT's own JS
 *   → .videoStreams / .audioStreams             // signed CDN URLs
 *   → pick best muxed OR (bestVideoOnly + bestAudio) pair
 *   → hand back to WebAppInterface which launches ExoPlayerActivity
 *
 * The URLs are signed by Google for the DEVICE's IP so ExoPlayer
 * can hit googlevideo.com directly and stream — no proxy layer,
 * no re-signing, no Cloudflare in the middle.
 *
 * FAILURE MODES
 * -------------
 * NewPipeExtractor releases patches whenever YouTube changes its
 * frontend (~2-4 times a year).  If the operator's APK is
 * >6 months old and YT changes something, extraction can start
 * throwing `ContentNotAvailableException` or `ExtractionException`.
 * When that happens the frontend falls back to iframe cycling
 * (which will show the loading veil + auto-advance UX from v2.11.7)
 * so the modal never lands on the raw Error 153 card.
 */
package tv.vesper.app

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as NpeRequest
import org.schabi.newpipe.extractor.downloader.Response as NpeResponse
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.Localization
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object YouTubeTrailerExtractor {
    private const val TAG = "YTTrailerExtractor"
    private val initialised = AtomicBoolean(false)

    /**
     * Combined result — either a single muxed URL that plays as-is,
     * or a video-only + audio-only pair that ExoPlayer's DASH
     * pipeline can merge on the fly.
     */
    data class TrailerStreams(
        val videoUrl: String,
        val audioUrl: String?,   // null when the video URL is muxed
        val title: String,
        val heightPx: Int,       // 0 when unknown
    )

    /**
     * Lightweight OkHttp-backed Downloader impl.  NewPipeExtractor
     * calls into this for every HTTP request it needs (page HTML,
     * player JS, signature cipher lookup, etc.).  Sharing our
     * project's existing OkHttp instance would introduce circular
     * init issues, so we build a dedicated 8 MB-cache client here.
     */
    private class NpeDownloader : Downloader() {
        private val http: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        @Throws(IOException::class, ReCaptchaException::class)
        override fun execute(request: NpeRequest): NpeResponse {
            val builder = Request.Builder().url(request.url())
            // Copy headers in.
            for ((k, values) in request.headers()) {
                for (v in values) builder.addHeader(k, v)
            }
            // NewPipeExtractor sometimes omits User-Agent — YouTube
            // 429s empty-UA requests.  Force a stable one that
            // matches what a real Android WebView would send.
            if (request.headers()["User-Agent"] == null) {
                builder.header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 12; SM-G998B) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/120.0.0.0 Mobile Safari/537.36"
                )
            }
            // Method + body.
            val data = request.dataToSend()
            val body = if (data != null) {
                okhttp3.RequestBody.create(null, data)
            } else null
            builder.method(request.httpMethod(), body)
            val resp = http.newCall(builder.build()).execute()

            if (resp.code == 429) {
                resp.close()
                throw ReCaptchaException("reCAPTCHA required", request.url())
            }

            val respBody = resp.body?.string() ?: ""
            val respHeaders = mutableMapOf<String, List<String>>()
            for (name in resp.headers.names()) {
                respHeaders[name] = resp.headers.values(name)
            }
            return NpeResponse(
                resp.code,
                resp.message,
                respHeaders,
                respBody,
                resp.request.url.toString()
            )
        }
    }

    /**
     * One-time init.  Safe to call repeatedly — the AtomicBoolean
     * short-circuits after the first pass.
     */
    fun ensureInit() {
        if (initialised.compareAndSet(false, true)) {
            try {
                NewPipe.init(NpeDownloader(), Localization("en", "US"))
                Log.i(TAG, "NewPipeExtractor initialised")
            } catch (t: Throwable) {
                Log.w(TAG, "init failed", t)
                initialised.set(false)
                throw t
            }
        }
    }

    /**
     * Extract the best available stream for [videoId].
     *
     * v2.12.1 — HD PRIORITY: DASH pair (video-only + audio-only,
     * up to 4K) is tried FIRST because YouTube only serves muxed
     * MP4 up to 720p — and often only 360p on older uploads.
     * Fall back to muxed only when DASH extraction fails (rare —
     * usually only happens for embed-restricted uploads that are
     * about to fall out to the iframe path anyway).
     *
     * Runs synchronously — caller MUST invoke from a background
     * thread (network I/O + JavaScript execution inside Rhino).
     *
     * Returns null on any extraction failure so callers can fall
     * through to the iframe fallback.
     */
    fun extract(videoId: String): TrailerStreams? {
        val safe = videoId.filter { it.isLetterOrDigit() || it == '_' || it == '-' }
        if (safe.isEmpty()) return null
        try {
            ensureInit()
            val service = ServiceList.YouTube
            val url = "https://www.youtube.com/watch?v=$safe"
            val extractor = service.getStreamExtractor(url)
            extractor.fetchPage()

            // Prefer the DASH pair — HIGHEST quality path.  YouTube
            // serves 1080p / 1440p / 2160p (4K) ONLY as separate
            // video-only + audio-only streams (progressive muxed
            // maxes out at 720p, often 360p on older uploads).  The
            // Vesper ExoPlayerActivity knows how to merge them via
            // `trailerAudioUrl` extra + MergingMediaSource.
            //
            // Note: NewPipeExtractor's Stream.getUrl() is annotated
            // as nullable Java (`@Nullable String`) — Kotlin sees
            // `String?`.  Filter out null/blank before dereferencing.
            val bestVideo = extractor.videoOnlyStreams
                ?.filter { !it.url.isNullOrBlank() }
                ?.maxByOrNull { it.height.takeIf { h -> h > 0 } ?: 0 }
            val bestAudio = extractor.audioStreams
                ?.filter { !it.url.isNullOrBlank() }
                ?.maxByOrNull { it.averageBitrate.takeIf { b -> b > 0 } ?: 0 }
            if (bestVideo != null && bestAudio != null) {
                Log.i(TAG, "DASH pair chosen: ${bestVideo.height}p video + " +
                    "${bestAudio.averageBitrate}kbps audio")
                return TrailerStreams(
                    videoUrl = bestVideo.url ?: "",
                    audioUrl = bestAudio.url ?: "",
                    title = extractor.name ?: "Trailer",
                    heightPx = bestVideo.height.takeIf { it > 0 } ?: 0,
                )
            }

            // Fallback: muxed progressive stream — plain HTTP MP4 that
            // an HTML5 <video> can play with zero DASH plumbing.  YT
            // caps this path at 720p (usually 360p on older uploads)
            // so it's a last resort when DASH extraction returns nothing.
            val muxed = extractor.videoStreams
                ?.filter { !it.url.isNullOrBlank() }
                ?.maxByOrNull { it.height.takeIf { h -> h > 0 } ?: 0 }
            if (muxed != null) {
                Log.i(TAG, "muxed fallback chosen: ${muxed.height}p")
                return TrailerStreams(
                    videoUrl = muxed.url ?: "",
                    audioUrl = null,
                    title = extractor.name ?: "Trailer",
                    heightPx = muxed.height.takeIf { it > 0 } ?: 0,
                )
            }
            return null
        } catch (t: Throwable) {
            Log.w(TAG, "extract($safe) failed: ${t.javaClass.simpleName}: ${t.message}")
            return null
        }
    }
}
