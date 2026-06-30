package tv.onnow.launcher.support

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import tv.onnow.launcher.net.ResilientHttp

/**
 * v2.10.89 — Remote support runs in a foreground service so it
 * survives the user pressing HOME or backgrounding the support
 * activity.  Previously the activity-hosted MediaProjection froze
 * as soon as the user navigated away — the operator's screen would
 * stop updating even though D-pad inputs were still being dispatched.
 *
 * Lifecycle:
 *   1. SupportSessionActivity collects the MediaProjection result.
 *   2. Activity calls startForegroundService(this) with the result
 *      code + Intent + session_id + base URL + device_id.
 *   3. Service starts in the foreground with an ongoing notification
 *      ("Remote support session active — tap to end").
 *   4. Service opens the persistent root shell, posts the hello,
 *      starts the screen capture, and runs the input long-poller.
 *   5. Activity finishes — the customer's launcher home returns to
 *      the foreground (and therefore to the captured screen).
 *   6. Stopping the service (notification tap, or session cancel)
 *      tears everything down + POSTs /host/cancel.
 *
 * No WebSockets anywhere — pure HTTPS POST + long-poll.
 */
class SupportForegroundService : Service() {

    companion object {
        private const val TAG = "SupportFG"
        private const val CH_ID = "onnow-support"
        private const val NOTI_ID = 4791
        const val ACTION_START = "tv.onnow.launcher.support.START"
        const val ACTION_STOP  = "tv.onnow.launcher.support.STOP"
        const val EX_RESULT_CODE = "ex_result_code"
        const val EX_RESULT_DATA = "ex_result_data"
        const val EX_SESSION_ID  = "ex_session_id"
        const val EX_BASE_URL    = "ex_base_url"
        const val EX_DEVICE_ID   = "ex_device_id"
    }

    private var screenCapture: ScreenCaptureController? = null
    @Volatile private var pollingActive = false
    private var inputPollerThread: Thread? = null
    private var sessionId: String? = null
    private var baseUrl: String? = null
    private val jsonType = "application/json".toMediaTypeOrNull()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) return START_NOT_STICKY

        val resultCode = intent.getIntExtra(EX_RESULT_CODE, 0)
        val data = intent.getParcelableExtra<Intent>(EX_RESULT_DATA)
        val sid = intent.getStringExtra(EX_SESSION_ID)
        val base = intent.getStringExtra(EX_BASE_URL)
        val deviceId = intent.getStringExtra(EX_DEVICE_ID).orEmpty()
        if (resultCode == 0 || data == null || sid == null || base == null) {
            Log.w(TAG, "missing required extras; refusing to start")
            stopSelf()
            return START_NOT_STICKY
        }
        sessionId = sid
        baseUrl = base.trimEnd('/')

        // Foreground notification — required by Android 8+ for any
        // service that runs after the activity backgrounds.  On 14+
        // it MUST also declare foregroundServiceType="mediaProjection"
        // in the manifest (we do).
        ensureChannel()
        startForeground(NOTI_ID, buildNotification())

        // 1) Pre-open the root shell so the Magisk prompt fires once
        //    at session start, while the customer is still looking
        //    at the support activity (immediately before we
        //    background ourselves).
        Thread {
            val ok = RootInputDispatcher.ensureShell()
            if (!ok) Log.w(TAG, "could not get root shell — inputs will be dropped")
        }.also { it.isDaemon = true }.start()

        // 2) POST hello so the operator's panel sees device-id +
        //    screen geometry immediately.
        Thread {
            try {
                val hello = JSONObject().apply {
                    put("device_id", deviceId)
                    put("build", Build.MODEL ?: "unknown")
                    put("screen_w", resources.displayMetrics.widthPixels)
                    put("screen_h", resources.displayMetrics.heightPixels)
                }.toString().toRequestBody(jsonType)
                val req = Request.Builder()
                    .url("${baseUrl}/api/support/host/hello/$sid").post(hello).build()
                ResilientHttp.client.newCall(req).execute().close()
            } catch (t: Throwable) { Log.w(TAG, "host/hello POST failed", t) }
        }.start()

        // 3) Start streaming JPEGs.  Capture controller POSTs each
        //    frame to /host/frame/{sid}.
        screenCapture = ScreenCaptureController(
            this, resultCode, data,
            "${baseUrl}/api/support/host/frame/$sid",
        )
        screenCapture?.start()

        // 4) Long-poll for operator inputs and dispatch via the
        //    persistent root shell.  No artificial sleeps between
        //    polls — the backend's `wait` parameter is the
        //    throttle, and inputs return immediately when one
        //    arrives.
        //    v2.10.94 — Use `longPollClient` (30s readTimeout) so
        //    okhttp doesn't time out before the backend's 20s hold
        //    window completes.  Eliminates the 1.5-3s input-lag
        //    pulses caused by the previous 15s timeout + 1500ms
        //    error sleep cycle.
        pollingActive = true
        inputPollerThread = Thread {
            var since = 0L
            while (pollingActive) {
                try {
                    val url = "${baseUrl}/api/support/host/inputs/$sid?since=$since&wait=20"
                    val req = Request.Builder().url(url).get().build()
                    val body = ResilientHttp.longPollClient.newCall(req).execute()
                        .use { it.body?.string().orEmpty() }
                    if (body.isEmpty()) continue
                    val parsed = JSONObject(body)
                    val maxSeq = parsed.optLong("max_seq", since)
                    if (maxSeq > since) since = maxSeq
                    val arr = parsed.optJSONArray("inputs") ?: continue
                    if (arr.length() == 0) continue
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val payload = item.optJSONObject("payload") ?: continue
                        RootInputDispatcher.handle(this, payload)
                    }
                    // Force-trigger an immediate frame after a burst
                    // of inputs so the operator's screen updates
                    // without waiting for the next regular capture
                    // tick.  This shaves ~80-150 ms off perceived
                    // input latency.
                    screenCapture?.requestImmediateFrame()
                } catch (t: Throwable) {
                    if (!pollingActive) break
                    Log.w(TAG, "input long-poll error", t)
                    // v2.10.94 — Was Thread.sleep(1500) which created
                    // a 1.5-3s "deaf" window where any operator input
                    // arrived too late.  Now: tiny jitter to avoid a
                    // thundering-herd reconnect on transient network
                    // hiccups, then retry.
                    try { Thread.sleep(50) } catch (_: InterruptedException) { break }
                }
            }
        }.also { it.isDaemon = true; it.start() }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        pollingActive = false
        inputPollerThread?.interrupt()
        inputPollerThread = null
        screenCapture?.stop()
        screenCapture = null
        try { RootInputDispatcher.shutdown() } catch (_: Throwable) {}
        // Fire-and-forget cancel so the backend reaps the session.
        sessionId?.let { sid ->
            Thread {
                try {
                    val body = JSONObject().apply { put("session_id", sid) }.toString()
                        .toRequestBody(jsonType)
                    val url = "${baseUrl}/api/support/host/cancel"
                    ResilientHttp.client.newCall(Request.Builder().url(url).post(body).build())
                        .execute().close()
                } catch (_: Throwable) {}
            }.start()
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CH_ID) != null) return
        val ch = NotificationChannel(
            CH_ID,
            "Remote Support",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Active remote-support session — screen is being shared"
            setShowBadge(false)
        }
        nm.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, SupportForegroundService::class.java)
            .setAction(ACTION_STOP)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else
            PendingIntent.FLAG_UPDATE_CURRENT
        val stopPi = PendingIntent.getService(this, 1, stopIntent, flags)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CH_ID)
        else
            @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setContentTitle("Remote support session active")
            .setContentText("Your screen is being shared with support. Tap to end.")
            .setSmallIcon(android.R.drawable.stat_sys_speakerphone)
            .setOngoing(true)
            .setContentIntent(stopPi)
            .build()
    }
}
