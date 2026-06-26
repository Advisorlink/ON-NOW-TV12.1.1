package tv.onnow.launcher.install.apkm

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * InstallApkmActivity
 * ───────────────────
 * Public entry-point that any other app on the box (Vesper, Live
 * TV, Music, Kids…) launches via the implicit intent action:
 *
 *     tv.onnow.launcher.ACTION_INSTALL_APKM
 *
 * Extras (one of):
 *   • EXTRA_URL   String  — http(s) URL of the .apkm bundle
 *   • EXTRA_PATH  String  — absolute path of the .apkm on disk
 *
 * Optional extras:
 *   • EXTRA_TITLE  String — friendly title shown above the
 *     progress bar (e.g. "WebView 138").  Falls back to a
 *     generic label.
 *
 * Lifecycle: download → unpack splits into PackageInstaller.Session
 * → commit → Android shows its standard install prompt → user
 * confirms → install completes → we toast success and finish().
 *
 * v2.10.53.
 */
class InstallApkmActivity : AppCompatActivity() {

    companion object {
        const val ACTION = "tv.onnow.launcher.ACTION_INSTALL_APKM"
        const val EXTRA_URL   = "tv.onnow.launcher.extra.URL"
        const val EXTRA_PATH  = "tv.onnow.launcher.extra.PATH"
        const val EXTRA_TITLE = "tv.onnow.launcher.extra.TITLE"

        private const val TAG = "InstallApkmActivity"
        private const val ACTION_INSTALL_RESULT = "tv.onnow.launcher.INSTALL_RESULT"
    }

    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView
    private lateinit var progressBar: ProgressBar
    private var job: Job? = null

    private val installResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
            val msg    = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            Log.i(TAG, "install status=$status msg=$msg")
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(confirm)
                    } else {
                        finishWith("Install prompt missing", success = false)
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> finishWith("Installed.", success = true)
                else -> finishWith("Install failed: $msg ($status)", success = false)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())

        LocalBroadcastManager.getInstance(this).registerReceiver(
            installResultReceiver, IntentFilter(ACTION_INSTALL_RESULT),
        )

        val url   = intent.getStringExtra(EXTRA_URL)
        val path  = intent.getStringExtra(EXTRA_PATH)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Update"

        titleView.text = title
        subtitleView.text = "Preparing…"

        val source = url ?: path
        if (source.isNullOrBlank()) {
            finishWith("No URL or PATH provided.", success = false)
            return
        }

        job = CoroutineScope(Dispatchers.Main).launch {
            val isUrl = url != null
            val sender = buildIntentSender()
            withContext(Dispatchers.IO) {
                val result = ApkmInstaller.downloadAndInstall(
                    ctx     = this@InstallApkmActivity,
                    source  = source,
                    statusReceiverIntentSender = sender,
                    onProgress = { pct ->
                        runOnUiThread {
                            subtitleView.text = if (isUrl) "Downloading… $pct%" else "Preparing…"
                            progressBar.progress = pct
                            progressBar.isIndeterminate = false
                        }
                    },
                )
                runOnUiThread {
                    when (result) {
                        is ApkmInstaller.Result.SessionCommitted -> {
                            subtitleView.text = "Confirm the system prompt to finish…"
                            progressBar.isIndeterminate = true
                        }
                        is ApkmInstaller.Result.Error -> finishWith(result.message, success = false)
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        job?.cancel()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(installResultReceiver)
    }

    /* ─── helpers ─── */

    private fun buildIntentSender(): android.content.IntentSender {
        val resultIntent = Intent(ACTION_INSTALL_RESULT).setPackage(packageName)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = PendingIntent.getBroadcast(this, 0xA17C0DE, resultIntent, flags)
        // LocalBroadcastManager doesn't accept PendingIntent senders;
        // we wrap with a global receiver that re-broadcasts locally.
        registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    LocalBroadcastManager.getInstance(c).sendBroadcast(
                        Intent(ACTION_INSTALL_RESULT).apply { putExtras(i) },
                    )
                }
            },
            IntentFilter(ACTION_INSTALL_RESULT),
            // Receiver flags required on API 33+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Context.RECEIVER_NOT_EXPORTED else 0,
        )
        return pi.intentSender
    }

    private fun finishWith(msg: String, success: Boolean) {
        Log.i(TAG, "finishWith success=$success msg=$msg")
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
        setResult(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED)
        finish()
    }

    /** Minimal, theme-free Compose-less UI so we don't pull extra deps. */
    private fun buildUi(): android.view.View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(96, 96, 96, 96)
            background = GradientDrawable().apply {
                colors = intArrayOf(0xFF06080F.toInt(), 0xFF0E1424.toInt())
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
            }
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        titleView = TextView(this).apply {
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        subtitleView = TextView(this).apply {
            textSize = 16f
            setTextColor(0xFF8C97B0.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 32)
        }
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(720, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        root.addView(titleView)
        root.addView(subtitleView)
        root.addView(progressBar)
        return root
    }
}
