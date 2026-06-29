package tv.onnow.launcher.install

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import tv.onnow.launcher.R
import tv.onnow.launcher.data.LauncherRepository
import tv.onnow.launcher.net.ResilientHttp
import java.io.File

/**
 * v2.10.71 — Bulk-install activity for fresh-box setups.
 *
 * Reached via a hidden 5-second long-press in the top-left corner of
 * the Apps drawer (see AppsDrawerActivity.installBulkInstallGesture).
 * Fetches `/api/bulk/manifest` from the launcher backend, downloads
 * every pinned APK to cache FIRST (so we never wait on a slow
 * download with the user staring at a system dialog), then fires the
 * system install prompt for each one in sequence.
 *
 * Per user spec:
 *   • Reinstall everything every time — no "skip already installed"
 *     check.  The user said *"install all of them like a backup"*,
 *     so the policy is "always lay every APK down".
 *   • Download-all-first, then install — the install dialogs appear
 *     back-to-back with no download wait between them.
 *   • One D-pad OK click per APK on the system install dialog (an
 *     unavoidable Android requirement for non-system launchers).
 *
 * Pure programmatic UI for palette consistency with the rest of the
 * launcher (matches AppsDrawerActivity's pattern).
 */
class BulkInstallActivity : AppCompatActivity() {

    private lateinit var repo: LauncherRepository
    private lateinit var rowsContainer: LinearLayout
    private lateinit var primaryButton: TextView
    private lateinit var headerSubtitle: TextView
    private lateinit var statusBanner: TextView

    /** Per-app row state.  Drives the status pill rendering. */
    private enum class Phase { PENDING, DOWNLOADING, DOWNLOADED, INSTALLING, INSTALLED, FAILED }

    private data class Item(
        val key: String,
        val label: String,
        val packageId: String,
        val version: String,
        val apkUrl: String,
        val apkFilename: String,
        val sizeBytes: Long,
        var phase: Phase = Phase.PENDING,
        var statusText: String = "Waiting…",
        var downloadedFile: File? = null,
        var pillView: TextView? = null,
    )

    private val items = mutableListOf<Item>()
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = LauncherRepository(applicationContext)
        setContentView(buildUi())
        loadManifest()
    }

    /* ─────────────────────────── UI ─────────────────────────── */

    private fun buildUi(): View {
        val root = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.onb_bg_glow)
        }

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(64), dp(56), dp(64), dp(64))
        }

        /* Eyebrow */
        column.addView(TextView(this).apply {
            text = "ON NOW · BULK INSTALL"
            textSize = 11f
            letterSpacing = 0.32f
            setTextColor(Color.parseColor("#FF5DC8FF"))
            typeface = Typeface.MONOSPACE
        })
        column.addView(spacer(dp(8)))

        /* Title */
        column.addView(TextView(this).apply {
            text = "Install every app, all at once"
            textSize = 28f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setTextColor(Color.parseColor("#FFF4F7FB"))
            letterSpacing = -0.005f
        })
        column.addView(spacer(dp(10)))

        headerSubtitle = TextView(this).apply {
            text = "Loading the app manifest from the backend…"
            textSize = 14f
            setTextColor(Color.parseColor("#FFAAB6C5"))
            // v2.10.83 — TextView.setLineHeight is API 28+.  Use
            // setLineSpacing() (since API 1) on older boxes so we
            // don't NoSuchMethodError-crash on Android 6/7/8.1 TVs.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lineHeight = dp(20)
            } else {
                setLineSpacing(dp(6).toFloat(), 1.0f)
            }
        }
        column.addView(headerSubtitle)
        column.addView(spacer(dp(20)))

        /* Status banner — only shown while the queue is running. */
        statusBanner = TextView(this).apply {
            visibility = View.GONE
            textSize = 13f
            setTextColor(Color.parseColor("#FFF8F3B0"))
            typeface = Typeface.MONOSPACE
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor("#33FFD24A"))
                setStroke(dp(1), Color.parseColor("#66FFD24A"))
            }
            setPadding(dp(20), dp(14), dp(20), dp(14))
        }
        column.addView(statusBanner)
        column.addView(spacer(dp(12)))

        rowsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        column.addView(rowsContainer)
        column.addView(spacer(dp(24)))

        /* Primary CTA */
        primaryButton = TextView(this).apply {
            text = "Install all apps"
            textSize = 16f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = primaryButtonBackground(focused = false)
            setPadding(dp(28), dp(14), dp(28), dp(14))
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            isFocusableInTouchMode = false
            setOnFocusChangeListener { _, hasFocus ->
                background = primaryButtonBackground(focused = hasFocus)
            }
            setOnClickListener { onPrimaryClick() }
        }
        column.addView(primaryButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        column.addView(spacer(dp(12)))
        column.addView(TextView(this).apply {
            text = "Android will pop a confirmation dialog for each app.  Press OK on the remote " +
                    "for every dialog — the queue advances automatically as each install finishes."
            textSize = 12f
            setTextColor(Color.parseColor("#FF6F7E92"))
            // v2.10.83 — API 28+ guard, see headerSubtitle comment.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lineHeight = dp(18)
            } else {
                setLineSpacing(dp(5).toFloat(), 1.0f)
            }
        })

        scroll.addView(column, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(scroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        return root
    }

    private fun primaryButtonBackground(focused: Boolean): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = dp(999).toFloat()
            colors = if (focused) {
                intArrayOf(Color.parseColor("#FF5DC8FF"), Color.parseColor("#FF2BA9F0"))
            } else {
                intArrayOf(Color.parseColor("#FF2BA9F0"), Color.parseColor("#FF1B7BBF"))
            }
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            if (focused) setStroke(dp(2), Color.parseColor("#FFCFEBFF"))
        }

    /* ───────────────────── Manifest loading ───────────────────── */

    private fun loadManifest() {
        lifecycleScope.launch {
            val base = repo.baseUrlPublic()
            val list = withContext(Dispatchers.IO) {
                try {
                    val url = "$base/api/bulk/manifest"
                    val req = Request.Builder().url(url).build()
                    ResilientHttp.client.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@withContext null
                        val body = resp.body?.string() ?: return@withContext null
                        parseManifest(body)
                    }
                } catch (t: Throwable) {
                    null
                }
            }
            if (list == null) {
                headerSubtitle.text =
                    "Couldn't reach the launcher backend.  Check your connection and try again."
                primaryButton.text = "Retry"
                primaryButton.setOnClickListener { loadManifest() }
                return@launch
            }
            items.clear()
            items.addAll(list)
            renderRows()
            headerSubtitle.text = if (items.isEmpty())
                "No APKs pinned in the admin yet.  Upload one in the dock-tile editor and try again."
            else
                "${items.size} app${if (items.size == 1) "" else "s"} pinned in the backend.  " +
                        "Tap below to download + install every one in sequence."
            if (items.isEmpty()) {
                primaryButton.visibility = View.GONE
            }
        }
    }

    private fun parseManifest(json: String): List<Item> {
        val obj = JSONObject(json)
        val arr: JSONArray = obj.optJSONArray("apks") ?: return emptyList()
        val out = mutableListOf<Item>()
        for (i in 0 until arr.length()) {
            val n = arr.optJSONObject(i) ?: continue
            val apkUrl = n.optString("apk_url", "")
            if (apkUrl.isBlank()) continue
            out += Item(
                key         = n.optString("key", "tile-$i"),
                label       = n.optString("label", "Untitled app"),
                packageId   = n.optString("package_id", ""),
                version     = n.optString("version", ""),
                apkUrl      = apkUrl,
                apkFilename = n.optString("apk_filename", ""),
                sizeBytes   = n.optLong("size_bytes", 0L),
            )
        }
        return out
    }

    /* ───────────────────── Row rendering ───────────────────── */

    private fun renderRows() {
        rowsContainer.removeAllViews()
        items.forEach { item ->
            rowsContainer.addView(buildRow(item))
            rowsContainer.addView(spacer(dp(10)))
        }
    }

    private fun buildRow(item: Item): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(Color.parseColor("#FF0E1320"))
                setStroke(dp(1), Color.parseColor("#FF1F2A3D"))
            }
            setPadding(dp(20), dp(14), dp(20), dp(14))
        }

        /* Left — app name + meta */
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        left.addView(TextView(this).apply {
            text = item.label
            textSize = 16f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            setTextColor(Color.parseColor("#FFF4F7FB"))
        })
        val metaParts = mutableListOf<String>()
        if (item.packageId.isNotBlank()) metaParts += item.packageId
        if (item.version.isNotBlank()) metaParts += "v${item.version}"
        if (item.sizeBytes > 0) metaParts += humanBytes(item.sizeBytes)
        left.addView(TextView(this).apply {
            text = metaParts.joinToString(" · ")
            textSize = 12f
            setTextColor(Color.parseColor("#FF6F7E92"))
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(4), 0, 0)
        })

        row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        /* Right — status pill */
        val pill = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.18f
            setPadding(dp(14), dp(7), dp(14), dp(7))
        }
        item.pillView = pill
        row.addView(pill)
        applyPill(item)
        return row
    }

    private fun applyPill(item: Item) {
        val pill = item.pillView ?: return
        val (bg, fg, label) = when (item.phase) {
            Phase.PENDING     -> Triple("#1A8FA0B5", "#FFAAB6C5", "PENDING")
            Phase.DOWNLOADING -> Triple("#2A5DC8FF", "#FF8EE0FF", item.statusText.ifBlank { "DOWNLOADING" })
            Phase.DOWNLOADED  -> Triple("#1F2BB66B", "#FF5DDC9B", "DOWNLOADED")
            Phase.INSTALLING  -> Triple("#33FFD24A", "#FFFFE08A", "INSTALLING")
            Phase.INSTALLED   -> Triple("#1F2BB66B", "#FF5DDC9B", "INSTALLED")
            Phase.FAILED      -> Triple("#33FF6B6B", "#FFFFA8A8", item.statusText.ifBlank { "FAILED" })
        }
        pill.text = label
        pill.setTextColor(Color.parseColor(fg))
        pill.background = GradientDrawable().apply {
            cornerRadius = dp(999).toFloat()
            setColor(Color.parseColor(bg))
        }
    }

    /* ───────────────────── Queue ───────────────────── */

    private fun onPrimaryClick() {
        if (running) return
        if (items.isEmpty()) return
        running = true
        primaryButton.isEnabled = false
        primaryButton.alpha = 0.55f
        primaryButton.text = "Working…"
        statusBanner.visibility = View.VISIBLE

        lifecycleScope.launch {
            /* ── Phase 1: download all APKs to cache ── */
            statusBanner.text = "Downloading every APK to cache before installing…"
            for ((idx, item) in items.withIndex()) {
                item.phase = Phase.DOWNLOADING
                item.statusText = "0%"
                applyPill(item)
                statusBanner.text = "Downloading ${idx + 1} of ${items.size}: ${item.label}…"

                val outFile = withContext(Dispatchers.IO) {
                    downloadToCache(item) { pct ->
                        runOnUiThread {
                            item.statusText = "DL $pct%"
                            applyPill(item)
                        }
                    }
                }
                if (outFile == null) {
                    item.phase = Phase.FAILED
                    item.statusText = "DOWNLOAD FAILED"
                    applyPill(item)
                    continue
                }
                item.downloadedFile = outFile
                item.phase = Phase.DOWNLOADED
                applyPill(item)
            }

            /* ── Phase 2: install in sequence ── */
            statusBanner.text = "Downloads done.  Confirming each install — press OK on the remote for every dialog."
            for ((idx, item) in items.withIndex()) {
                val file = item.downloadedFile ?: continue
                item.phase = Phase.INSTALLING
                applyPill(item)
                statusBanner.text = "Installing ${idx + 1} of ${items.size}: ${item.label}…"

                try {
                    launchInstallPrompt(file)
                } catch (t: Throwable) {
                    item.phase = Phase.FAILED
                    item.statusText = "INTENT FAILED"
                    applyPill(item)
                    continue
                }

                /* Wait briefly so the system dialog has time to come up
                 * and the user has time to confirm before we fire the
                 * next one.  Without a gap, multiple install intents
                 * stack and the dialogs end up out of order. */
                delay(1200L)
                item.phase = Phase.INSTALLED
                applyPill(item)
            }

            statusBanner.text = "All apps queued.  Close this screen when you're done confirming dialogs."
            primaryButton.text = "Done"
            primaryButton.isEnabled = true
            primaryButton.alpha = 1.0f
            primaryButton.setOnClickListener { finish() }
            running = false
        }
    }

    private fun downloadToCache(item: Item, onPct: (Int) -> Unit): File? {
        return try {
            val dir = File(cacheDir, "bulk_apks").apply { mkdirs() }
            val safeName = (item.apkFilename.takeIf { it.endsWith(".apk", true) }
                ?: "bulk-${item.key}-${System.currentTimeMillis()}.apk")
                .replace(Regex("[^A-Za-z0-9_.-]"), "_")
            val out = File(dir, safeName)
            val req = Request.Builder().url(item.apkUrl).build()
            ResilientHttp.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val body = resp.body ?: return null
                val total = body.contentLength().coerceAtLeast(1L)
                var read = 0L
                body.byteStream().use { input ->
                    out.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            read += n
                            onPct(((read * 100L) / total).toInt())
                        }
                    }
                }
            }
            out
        } catch (t: Throwable) {
            null
        }
    }

    private fun launchInstallPrompt(apk: File) {
        val authority = "${packageName}.fileprovider"
        val uri = androidx.core.content.FileProvider.getUriForFile(this, authority, apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
    }

    /* ───────────────────── Helpers ───────────────────── */

    private fun spacer(h: Int) = View(this).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun humanBytes(b: Long): String {
        if (b < 1024) return "${b}B"
        if (b < 1024 * 1024) return "${b / 1024}KB"
        val mb = b.toDouble() / (1024.0 * 1024.0)
        return String.format("%.1fMB", mb)
    }
}
