package tv.onnowtv.livetv.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Backend client for the AI cover-art endpoints (see /app/backend/library.py).
 *
 *   • [generate] hits POST /api/library/generate-cover and returns a
 *     [GeneratedCover] holding the deterministic hash + a fully-
 *     qualified image URL the [LibraryActivity] (and Coil) can load.
 *
 *   • [coverUrl] resolves a stored hash to its fully-qualified URL
 *     so already-generated covers survive reinstalls.
 *
 * Network access is on [Dispatchers.IO] inside [withContext].  The
 * caller is responsible for `lifecycleScope.launch`.
 */
object CoversApi {

    private const val TAG = "CoversApi"
    private const val PATH_GENERATE = "/api/library/generate-cover"
    private const val PATH_COVER = "/api/library/cover/"

    data class GeneratedCover(
        val hash: String,
        val url: String,
        val mime: String,
    )

    /**
     * Generate (or fetch-from-cache) a 16:9 cover for [name].
     * Pass a fresh [forceSalt] (e.g. UUID) to force a brand-new
     * variant — used by the "Regenerate cover" long-press flow.
     */
    suspend fun generate(
        name: String,
        forceSalt: String? = null,
        backendBase: String = XtreamRepository.BACKEND_BASE,
    ): GeneratedCover = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("name", name)
            if (!forceSalt.isNullOrBlank()) put("salt", forceSalt)
        }.toString().toByteArray(StandardCharsets.UTF_8)

        val url = URL(backendBase.trimEnd('/') + PATH_GENERATE)
        val con = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 90_000  // GPT-Image-1 can take 30-60s
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }
        try {
            con.outputStream.use { it.write(body, 0, body.size) }
            val status = con.responseCode
            val stream = if (status in 200..299) con.inputStream else con.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() } ?: ""
            if (status !in 200..299) {
                Log.w(TAG, "generate-cover HTTP $status — $text")
                throw RuntimeException("Cover generation failed (HTTP $status)")
            }
            val obj = JSONObject(text)
            val hash = obj.getString("hash")
            return@withContext GeneratedCover(
                hash = hash,
                url = backendBase.trimEnd('/') + PATH_COVER + "$hash.png",
                mime = obj.optString("mime", "image/png"),
            )
        } finally {
            con.disconnect()
        }
    }

    /** Resolve a previously-generated hash to its full URL. */
    fun coverUrl(hash: String, backendBase: String = XtreamRepository.BACKEND_BASE): String =
        backendBase.trimEnd('/') + PATH_COVER + "$hash.png"

    /** Generate a fresh salt for "Regenerate cover" requests. */
    fun freshSalt(): String = UUID.randomUUID().toString().take(8)
}
