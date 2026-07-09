package tv.onnow.launcher.remote

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
import tv.onnow.launcher.support.RootInputDispatcher

/**
 * Phone-remote host service.
 *
 * Runs in the foreground so the phone keeps controlling the box after
 * the user dismisses the QR popup and starts browsing.  It long-polls
 * `/api/remote/host/poll/{sid}` and dispatches each queued phone input
 * as a system-wide `input keyevent` / `input text` via the shared
 * RootInputDispatcher (same root shell the support feature uses).
 *
 * No screen capture — the phone renders its own remote UI, so we only
 * ever push inputs one way (phone → box).
 */
class RemoteControlService : Service() {

    companion object {
        private const val TAG = "RemoteFG"
        private const val CH_ID = "onnow-remote"
        private const val NOTI_ID = 4823
        const val ACTION_START = "tv.onnow.launcher.remote.START"
        const val ACTION_STOP  = "tv.onnow.launcher.remote.STOP"
        const val EX_SESSION_ID = "ex_session_id"
        const val EX_BASE_URL   = "ex_base_url"

        @Volatile var isRunning: Boolean = false
            private set
    }

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

        val sid = intent.getStringExtra(EX_SESSION_ID)
        val base = intent.getStringExtra(EX_BASE_URL)
        if (sid == null || base == null) {
            Log.w(TAG, "missing extras; refusing to start")
            stopSelf()
            return START_NOT_STICKY
        }
        // A second START replaces any previous session cleanly.
        pollingActive = false
        inputPollerThread?.interrupt()
        sessionId = sid
        baseUrl = base.trimEnd('/')

        ensureChannel()
        startForeground(NOTI_ID, buildNotification())
        isRunning = true

        // Pre-open the root shell so the Magisk prompt (if any) fires
        // once now while the QR popup is still up.
        Thread {
            if (!RootInputDispatcher.ensureShell())
                Log.w(TAG, "no root shell — remote inputs will be dropped")
        }.also { it.isDaemon = true }.start()

        pollingActive = true
        inputPollerThread = Thread {
            var since = 0L
            while (pollingActive) {
                try {
                    val url = "${baseUrl}/api/remote/host/poll/$sid?since=$since&wait=20"
                    val req = Request.Builder().url(url).get().build()
                    val body = ResilientHttp.longPollClient.newCall(req).execute()
                        .use { it.body?.string().orEmpty() }
                    if (body.isEmpty()) continue
                    val parsed = JSONObject(body)
                    val maxSeq = parsed.optLong("seq", since)
                    if (maxSeq > since) since = maxSeq
                    val arr = parsed.optJSONArray("inputs") ?: continue
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val payload = item.optJSONObject("payload") ?: continue
                        RootInputDispatcher.handle(this, payload)
                    }
                } catch (t: Throwable) {
                    if (!pollingActive) break
                    Log.w(TAG, "remote input long-poll error", t)
                    try { Thread.sleep(50) } catch (_: InterruptedException) { break }
                }
            }
        }.also { it.isDaemon = true; it.start() }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        pollingActive = false
        inputPollerThread?.interrupt()
        inputPollerThread = null
        try { RootInputDispatcher.shutdown() } catch (_: Throwable) {}
        sessionId?.let { sid ->
            Thread {
                try {
                    val payload = JSONObject().apply { put("session_id", sid) }.toString()
                        .toRequestBody(jsonType)
                    val url = "${baseUrl}/api/remote/host/cancel"
                    ResilientHttp.client.newCall(Request.Builder().url(url).post(payload).build())
                        .execute().close()
                } catch (_: Throwable) {}
            }.start()
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CH_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CH_ID, "Phone Remote", NotificationManager.IMPORTANCE_LOW).apply {
                description = "A phone is paired as a remote for this box"
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, RemoteControlService::class.java).setAction(ACTION_STOP)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else PendingIntent.FLAG_UPDATE_CURRENT
        val stopPi = PendingIntent.getService(this, 1, stopIntent, flags)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CH_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setContentTitle("Phone remote active")
            .setContentText("Your phone is controlling this box. Tap to disconnect.")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(stopPi)
            .build()
    }
}
