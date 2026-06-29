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
import okhttp3.WebSocket
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayOutputStream

/**
 * v2.10.84 — Capture the TV box's screen at ~6 fps, encode each
 * frame as a JPEG (~quality 60), and ship over WebSocket to the
 * paired operator.
 *
 * Implementation notes
 * --------------------
 *  - Uses MediaProjection + ImageReader.  Caller supplies the result
 *    Intent from the system consent dialog.
 *  - Captures at HALF the device resolution to keep bandwidth under
 *    ~200 kB/s on cheap HK1-class boxes — 1080p / 2 = 960×540, still
 *    legible enough to D-pad around menus.
 *  - JPEG quality 60 strikes a good balance between bandwidth and
 *    text legibility (Vesper menu typography stays readable).
 *  - Frame-throttle to 6 fps via Handler.postDelayed.
 *  - Capture happens on a dedicated HandlerThread so it doesn't
 *    block the UI thread or the WebSocket I/O.
 */
class ScreenCaptureController(
    private val context: Context,
    private val resultCode: Int,
    private val data: Intent,
    private val ws: WebSocket,
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
    private var stopped = false
    private var lastFrameAt = 0L
    private val frameIntervalMs = 1000L / TARGET_FPS

    fun start() {
        if (stopped) return
        val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = try {
            mpm.getMediaProjection(resultCode, data)
        } catch (t: Throwable) {
            Log.e(TAG, "MediaProjection get failed", t)
            null
        } ?: return

        val metrics = context.resources.displayMetrics
        // Capture at half-resolution to halve bandwidth without
        // significantly degrading menu legibility.
        val captureW = metrics.widthPixels / 2
        val captureH = metrics.heightPixels / 2
        val dpi = metrics.densityDpi

        handlerThread = HandlerThread("ScreenCaptureWorker").also { it.start() }
        handler = Handler(handlerThread!!.looper)

        imageReader = ImageReader.newInstance(
            captureW, captureH, PixelFormat.RGBA_8888, 2,
        ).also { reader ->
            reader.setOnImageAvailableListener({ r ->
                if (stopped) return@setOnImageAvailableListener
                val now = System.currentTimeMillis()
                if (now - lastFrameAt < frameIntervalMs) {
                    // Throttle — drop this frame but consume the
                    // image so the reader doesn't back up.
                    val img = r.acquireLatestImage()
                    img?.close()
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
                    // Build a Bitmap that accounts for row stride padding.
                    val bmp = Bitmap.createBitmap(
                        captureW + rowPadding / pixelStride, captureH,
                        Bitmap.Config.ARGB_8888,
                    )
                    bmp.copyPixelsFromBuffer(buffer)
                    // Crop off the padding column.
                    val cropped = Bitmap.createBitmap(bmp, 0, 0, captureW, captureH)
                    bmp.recycle()
                    val baos = ByteArrayOutputStream()
                    cropped.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, baos)
                    cropped.recycle()
                    val bytes = baos.toByteArray()
                    try {
                        ws.send(bytes.toByteString())
                    } catch (t: Throwable) {
                        Log.w(TAG, "ws.send failed", t)
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
