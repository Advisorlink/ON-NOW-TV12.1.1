package tv.onnow.launcher.support

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tv.onnow.launcher.net.ResilientHttp
import java.io.ByteArrayOutputStream

/**
 * v2.10.88 — Capture the TV box's screen at ~6 fps, encode each
 * frame as a JPEG (~quality 55), and POST it to the backend over
 * plain HTTPS.  Previously this used a WebSocket but the connection
 * kept getting killed by Cloudflare on the customer's free zone
 * (Connection lost — restart to retry).  HTTP POST per frame works
 * through every CDN / firewall and the few-hundred-ms overhead per
 * request is fine for a 6 fps support session.
 *
 *  - Uses MediaProjection + ImageReader.  Caller supplies the result
 *    Intent from the system consent dialog.
 *  - Captures at HALF the device resolution to keep bandwidth under
 *    ~200 kB/s on cheap HK1-class boxes — 1080p / 2 = 960×540, still
 *    legible enough to D-pad around menus.
 *  - JPEG quality 55 strikes a good balance between bandwidth and
 *    text legibility (Vesper menu typography stays readable).
 *  - Frame-throttle to 6 fps via Handler.postDelayed.
 *  - Capture happens on a dedicated HandlerThread so it doesn't
 *    block the UI thread or the network calls.
 */
class ScreenCaptureController(
    private val context: Context,
    private val resultCode: Int,
    private val data: Intent,
    private val frameUploadUrl: String,
) {
    companion object {
        private const val TAG = "ScreenCapture"
        private const val TARGET_FPS = 6
        private const val JPEG_QUALITY = 55
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var stopped = false
    @Volatile private var inFlight = false
    private var lastFrameAt = 0L
    private val frameInterval = 1000L / TARGET_FPS
    private val jpegMediaType = "image/jpeg".toMediaTypeOrNull()

    private var captureW = 0
    private var captureH = 0

    fun start() {
        val metrics = context.resources.displayMetrics
        val srcW = metrics.widthPixels
        val srcH = metrics.heightPixels
        captureW = srcW / 2
        captureH = srcH / 2
        val dpi = metrics.densityDpi

        handlerThread = HandlerThread("onnow-screencap").apply { start() }
        handler = Handler(handlerThread!!.looper)

        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(resultCode, data)

        imageReader = ImageReader.newInstance(captureW, captureH, PixelFormat.RGBA_8888, 2).apply {
            setOnImageAvailableListener({ r ->
                if (stopped) return@setOnImageAvailableListener
                val now = System.currentTimeMillis()
                if (now - lastFrameAt < frameInterval) {
                    r.acquireLatestImage()?.close()
                    return@setOnImageAvailableListener
                }
                lastFrameAt = now
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride
                    val rowPadding = rowStride - pixelStride * captureW
                    val bmp = Bitmap.createBitmap(
                        captureW + rowPadding / pixelStride, captureH,
                        Bitmap.Config.ARGB_8888,
                    )
                    bmp.copyPixelsFromBuffer(buffer)
                    val cropped = Bitmap.createBitmap(bmp, 0, 0, captureW, captureH)
                    bmp.recycle()
                    val baos = ByteArrayOutputStream()
                    cropped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
                    cropped.recycle()
                    val bytes = baos.toByteArray()
                    // Drop frames if a previous upload is still in flight
                    // (slow Wi-Fi / busy backend) — better to keep up
                    // with the live screen than queue stale frames.
                    if (!inFlight) {
                        inFlight = true
                        Thread {
                            try {
                                val req = Request.Builder()
                                    .url(frameUploadUrl)
                                    .post(bytes.toRequestBody(jpegMediaType))
                                    .build()
                                ResilientHttp.client.newCall(req).execute().use { /* discard */ }
                            } catch (t: Throwable) {
                                Log.w(TAG, "frame POST failed", t)
                            } finally {
                                inFlight = false
                            }
                        }.start()
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "frame encode failed", t)
                } finally {
                    image.close()
                }
            }, handler)
        }

        virtualDisplay = projection?.createVirtualDisplay(
            "onnow-support",
            captureW, captureH, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, handler,
        )
    }

    fun stop() {
        stopped = true
        try { virtualDisplay?.release() } catch (_: Throwable) {}
        try { imageReader?.close() } catch (_: Throwable) {}
        try { projection?.stop() } catch (_: Throwable) {}
        try { handlerThread?.quitSafely() } catch (_: Throwable) {}
        virtualDisplay = null
        imageReader = null
        projection = null
        handler = null
        handlerThread = null
    }
}
