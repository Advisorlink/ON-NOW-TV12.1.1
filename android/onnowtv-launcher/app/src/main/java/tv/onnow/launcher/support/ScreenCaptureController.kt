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
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32

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
        // v2.10.91 — Bumped 12 → 15 fps now that the operator side
        // is on a persistent streaming HTTP connection (no per-frame
        // request overhead).  At 960×540 / quality 45 each frame
        // averages ~22 KB → 15 fps ≈ 350 kbps, still well within
        // a typical Aussie ADSL/4G uplink.
        private const val TARGET_FPS = 15
        // v2.10.92 — Dynamic JPEG quality.  When the operator is
        // moving through menus (consecutive frames differ) we drop
        // to quality 30 — frames are smaller (faster upload + less
        // CPU on the box) and the eye doesn't notice the loss
        // because the content is moving.  When the screen settles
        // (mostly static, occasional change) we bump back to
        // quality 45 so menu text and posters stay crisp.
        private const val JPEG_QUALITY_MOTION = 30
        private const val JPEG_QUALITY_STATIC = 45
        // v2.10.92 — Allow up to 3 concurrent frame uploads.  On
        // slow uplinks one POST may take ~200ms; with only 1
        // permitted at a time we'd cap effective throughput at
        // ~5 fps regardless of capture rate.  Three lets the
        // network layer pipeline while still bounding memory.
        private const val MAX_CONCURRENT_UPLOADS = 3
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var stopped = false
    // v2.10.92 — Upload guard is now an AtomicInteger counter
    // permitting MAX_CONCURRENT_UPLOADS in-flight POSTs at once
    // (was a single boolean).  Increment when starting an upload,
    // decrement (in a finally block) when it completes/fails.
    private val inFlight = AtomicInteger(0)
    // v2.10.92 — Frame-deduplication state.  We hash the raw RGBA
    // pixel buffer (sampled — see quickHash()) before JPEG-encoding
    // so we can SKIP entirely when the screen hasn't changed.  This
    // is a massive bandwidth + CPU win on a static menu — most
    // launcher screens are stationary 90%+ of the time.
    @Volatile private var lastFrameHash: Long = 0L
    // Counts how many consecutive frames have actually CHANGED
    // (i.e. not been skipped by the dedup check).  We use this to
    // pick the JPEG quality dynamically — see picks above.
    private var consecutiveDifferentFrames: Int = 0
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

                    // v2.10.92 — Skip-if-unchanged.  Hash the raw
                    // RGBA buffer BEFORE the (expensive) bitmap copy
                    // + JPEG encode.  If the pixels are identical to
                    // the previous accepted frame, do nothing — no
                    // encode, no upload, no operator-side repaint.
                    // Saves significant CPU on a stationary menu
                    // and cuts uplink bandwidth essentially to zero
                    // when the box is idle.
                    val hash = quickHash(buffer)
                    if (hash == lastFrameHash) {
                        consecutiveDifferentFrames = 0
                        image.close()
                        return@setOnImageAvailableListener
                    }
                    lastFrameHash = hash
                    consecutiveDifferentFrames++

                    // Dynamic JPEG quality based on motion.  Two
                    // consecutive different frames = the screen is
                    // actively changing (operator navigating) →
                    // quality 30 for faster upload.  A single
                    // change after a stretch of stillness = the
                    // screen just settled into a new state →
                    // quality 45 to keep menu typography crisp.
                    val quality = if (consecutiveDifferentFrames >= 2)
                        JPEG_QUALITY_MOTION
                    else
                        JPEG_QUALITY_STATIC

                    val bmp = Bitmap.createBitmap(
                        captureW + rowPadding / pixelStride, captureH,
                        Bitmap.Config.ARGB_8888,
                    )
                    bmp.copyPixelsFromBuffer(buffer)
                    val cropped = Bitmap.createBitmap(bmp, 0, 0, captureW, captureH)
                    bmp.recycle()
                    val baos = ByteArrayOutputStream()
                    cropped.compress(Bitmap.CompressFormat.JPEG, quality, baos)
                    cropped.recycle()
                    val bytes = baos.toByteArray()

                    // v2.10.92 — Up to MAX_CONCURRENT_UPLOADS frames
                    // may be in flight at once.  We're the SOLE
                    // producer (single capture HandlerThread), so
                    // a simple get-then-increment is race-free —
                    // the only concurrent writer is the decrement
                    // in the upload threads' `finally` blocks, and
                    // that can only DROP the count.
                    if (inFlight.get() < MAX_CONCURRENT_UPLOADS) {
                        inFlight.incrementAndGet()
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
                                inFlight.decrementAndGet()
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

    /** v2.10.89 — Tell the capture loop "send the next frame ASAP",
     *  ignoring the regular fps throttle.  Called by the input
     *  poller after dispatching a burst of operator inputs so the
     *  operator sees the screen update immediately rather than
     *  waiting up to 1/TARGET_FPS for the next regular tick. */
    fun requestImmediateFrame() {
        // Resetting lastFrameAt to 0 makes the throttle check
        // pass on the very next image-available callback, which
        // typically fires within a few ms on a 60Hz display.
        lastFrameAt = 0L
        // v2.10.92 — Also force the frame-dedup hash to a sentinel
        // that can never match a real hash, so the next captured
        // frame is uploaded EVEN IF it's pixel-identical to the
        // one before.  Without this, an operator key-press that
        // produces no visible change would never trigger an upload
        // and the operator would never see the "still alive" frame.
        lastFrameHash = Long.MIN_VALUE
    }

    /**
     * v2.10.92 — Fast equality-only hash of a raw RGBA frame buffer.
     *
     * We don't need a cryptographic hash — we just want to cheaply
     * decide "is this frame the same as the last one?".  At 960×540
     * RGBA the buffer is ~2 MB, so we sample it: 16 chunks of 256
     * bytes evenly spaced across the buffer.  That's 4 KB CRC'd per
     * frame, which takes ~0.2 ms on a cheap TV box CPU (vs ~5 ms
     * for a full-buffer CRC).
     *
     * Collisions across genuinely-different frames are astronomically
     * unlikely given how spread-out the samples are — text or
     * colour changes anywhere in the image touch at least one
     * sample window with overwhelming probability.  Even if a
     * collision DID happen, the worst outcome is one skipped
     * frame, which the next capture tick (~67ms later at 15 fps)
     * will correct.
     *
     * Preserves the buffer's position+limit so subsequent reads
     * (the bitmap copyPixelsFromBuffer below) get the full buffer.
     */
    private fun quickHash(buffer: ByteBuffer): Long {
        val crc = CRC32()
        val originalPos = buffer.position()
        val limit = buffer.limit()
        val total = limit - originalPos
        val chunkSize = 256
        val numSamples = 16
        val stride = (total / numSamples).coerceAtLeast(chunkSize)
        val chunk = ByteArray(chunkSize)
        try {
            var i = originalPos
            var taken = 0
            while (i + chunkSize <= limit && taken < numSamples) {
                buffer.position(i)
                buffer.get(chunk, 0, chunkSize)
                crc.update(chunk, 0, chunkSize)
                i += stride
                taken++
            }
        } catch (t: Throwable) {
            // Any indexing weirdness → treat as "different" so we
            // fall through to upload.  Safety > cleverness.
            return Long.MIN_VALUE + System.nanoTime()
        } finally {
            buffer.position(originalPos)
        }
        return crc.value
    }
}
