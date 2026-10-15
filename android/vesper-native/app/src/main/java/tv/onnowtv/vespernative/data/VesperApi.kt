package tv.onnowtv.vespernative.data

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONTokener
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiFailure(val status: Int) : IOException(when (status) {
    401 -> "Please sign in again."
    403 -> "This account is unavailable. Please contact ON NOW TV."
    404 -> "This title is currently unavailable."
    429 -> "Too many attempts. Please wait before trying again."
    else -> "Unable to connect. Please try again."
})

/** Backend only: bearer tokens are never sent to artwork/addon origins. */
class VesperApi(baseUrl: String, private val client: OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
    .callTimeout(30, TimeUnit.SECONDS).followRedirects(false).build()) {
    private val base = baseUrl.trimEnd('/').toHttpUrl()

    suspend fun request(path: List<String>, token: String? = null,
        query: Map<String, String> = emptyMap(), body: String? = null): Any {
        val url = base.newBuilder().addPathSegment("api").apply {
            path.forEach(::addPathSegment)
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val request = Request.Builder().url(url).header("Accept", "application/json").apply {
            if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
            if (body != null) post(body.toRequestBody("application/json".toMediaType()))
        }.build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("Unable to connect. Please try again."))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val parsed = response.use {
                            if (!it.isSuccessful) throw ApiFailure(it.code)
                            val source = it.body?.source() ?: throw IOException("Empty server response")
                            if (source.request(8L * 1024 * 1024 + 1)) throw IOException("Server response is too large")
                            JSONTokener(source.readUtf8()).nextValue()
                        }
                        if (continuation.isActive) continuation.resume(parsed)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(
                            if (e is ApiFailure) e else IOException("Unable to read server response. Please try again."))
                    }
                }
            })
        }
    }
}