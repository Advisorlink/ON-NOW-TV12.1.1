package tv.onnowtv.vespernative.playback

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as NpeRequest
import org.schabi.newpipe.extractor.downloader.Response as NpeResponse
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.Localization
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/** Extractor/version and muxed <=720p selection from the original V2 app.
 * One worker, per-extraction cancellation, no JS bridge, no signed-URL logging.
 */
object DeviceTrailerExtractor {
    private class Ticket {
        val cancelled=AtomicBoolean(false)
        val call=AtomicReference<Call?>()
        fun check() { if(cancelled.get() || Thread.currentThread().isInterrupted)throw IOException("Cancelled") }
    }
    private val worker=Executors.newSingleThreadExecutor { r->Thread(r,"vesper-trailer").apply { isDaemon=true } }
    private val current=ThreadLocal<Ticket>()
    private var initialized=false
    private val http=OkHttpClient.Builder().connectTimeout(8,TimeUnit.SECONDS).readTimeout(10,TimeUnit.SECONDS)
        .callTimeout(12,TimeUnit.SECONDS).followRedirects(true).build()
    private class NativeDownloader:Downloader() {
        override fun execute(request:NpeRequest):NpeResponse {
            val ticket=current.get() ?: throw IOException("No extraction scope")
            ticket.check()
            val builder=Request.Builder().url(request.url())
            request.headers().forEach { (key,values)->values.forEach { builder.addHeader(key,it) } }
            if(request.headers().keys.none { it.equals("User-Agent",true) })builder.header("User-Agent",
                "Mozilla/5.0 (Linux; Android 12; SM-G998B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
            builder.method(request.httpMethod(),request.dataToSend()?.toRequestBody())
            val call=http.newCall(builder.build()); ticket.call.set(call)
            try {
                ticket.check()
                return call.execute().use { response ->
                    if(response.code==429)throw ReCaptchaException("Verification required",request.url())
                    val source=response.body?.source() ?: throw IOException("Empty response")
                    if(source.request(8L*1024*1024+1))throw IOException("Response too large")
                    NpeResponse(response.code,response.message,response.headers.toMultimap(),source.readUtf8(),response.request.url.toString())
                }
            } finally { ticket.call.compareAndSet(call,null) }
        }
    }
    suspend fun extract(key:String):TrailerMedia?=suspendCancellableCoroutine { continuation ->
        val ticket=Ticket()
        val future=worker.submit {
            current.set(ticket)
            val result=try {
                ticket.check()
                if(!initialized) { NewPipe.init(NativeDownloader(),Localization("en","US")); initialized=true }
                val extractor=ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=$key")
                extractor.fetchPage(); ticket.check()
                val muxed=extractor.videoStreams?.filter { !it.url.isNullOrBlank() && it.height in 1..720 }?.maxByOrNull { it.height }
                muxed?.url?.takeIf(TrailerMedia::valid)?.let { TrailerMedia(it,key=key) }
            } catch(_:Exception) { null } finally { current.remove() }
            if(continuation.isActive)continuation.resume(result)
        }
        continuation.invokeOnCancellation { ticket.cancelled.set(true); ticket.call.get()?.cancel(); future.cancel(true) }
    }
}