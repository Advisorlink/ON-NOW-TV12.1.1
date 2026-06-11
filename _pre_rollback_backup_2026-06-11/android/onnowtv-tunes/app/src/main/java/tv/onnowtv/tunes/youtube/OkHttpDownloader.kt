package tv.onnowtv.tunes.youtube

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OkHttp-backed implementation of [Downloader] required by
 * NewPipeExtractor.  Lives on the HK1 box itself — every request to
 * YouTube originates from the user's residential IP, so the bot
 * detection that blocks our Contabo VPS doesn't apply.
 *
 * Cookies are stored as a single header string (per-domain).  Once
 * we add the Tier 2 sign-in flow they get populated from a logged-in
 * WebView session and the same Downloader transparently authenticates
 * every NewPipe request.
 */
class OkHttpDownloader private constructor(
    private val client: OkHttpClient,
) : Downloader() {

    @Volatile
    private var cookies: String = ""

    fun setCookies(value: String) {
        cookies = value
    }

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val method = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val body = dataToSend?.toRequestBody(null)
        val reqBuilder = okhttp3.Request.Builder()
            .method(method, body)
            .url(url)
            .addHeader("User-Agent", USER_AGENT)

        headers.forEach { (name, values) ->
            values.forEach { v -> reqBuilder.addHeader(name, v) }
        }
        // Inject cookies only if the upstream request didn't already
        // include one (NewPipe sets its own visitor cookies in some
        // paths).
        if (cookies.isNotEmpty() && headers["Cookie"].isNullOrEmpty()) {
            reqBuilder.addHeader("Cookie", cookies)
        }

        val resp = client.newCall(reqBuilder.build()).execute()
        val responseBody = resp.body?.string() ?: ""

        if (resp.code == 429) {
            throw ReCaptchaException("reCaptcha challenge requested", url)
        }
        return Response(
            resp.code,
            resp.message,
            resp.headers.toMultimap(),
            responseBody,
            resp.request.url.toString(),
        )
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; HK1 Box) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"

        @Volatile
        private var INSTANCE: OkHttpDownloader? = null

        fun init(): OkHttpDownloader {
            INSTANCE?.let { return it }
            synchronized(this) {
                INSTANCE?.let { return it }
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true)
                    .build()
                val downloader = OkHttpDownloader(client)
                INSTANCE = downloader
                return downloader
            }
        }

        fun instance(): OkHttpDownloader =
            INSTANCE ?: error("OkHttpDownloader.init() not called")
    }
}
