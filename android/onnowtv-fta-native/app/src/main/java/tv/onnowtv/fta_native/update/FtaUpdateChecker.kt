package tv.onnowtv.fta_native.update

import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import org.json.JSONObject

/**
 * In-app update gate for the native FTA app.
 *
 * Flow:
 *   1. On EpgActivity startup, fetch
 *      `${REACT_APP_BACKEND_URL}/api/app/latest-version-fta-native`
 *      off the main thread.
 *   2. Compare the returned `version` against `BuildConfig.VERSION_NAME`
 *      using semver semantics.  If newer, show a non-blocking
 *      "Update available" dialog with title + notes + "Update now"
 *      + "Later" buttons.
 *   3. On "Update now": use Android's DownloadManager to fetch the
 *      APK into the app's external cache.  Watch the download via
 *      a polling Handler so we can show a Toast every 25 % of
 *      progress (TV remotes can't see notifications, so a Toast
 *      is the right UX here).
 *   4. On download complete: launch the system package installer
 *      via FileProvider + ACTION_VIEW.  If the device blocks
 *      installs from this source, funnel the user to the
 *      MANAGE_UNKNOWN_APP_SOURCES settings screen.
 *
 * The backend already knows the repo (set via `APK_GITHUB_REPO`
 * env var on the Contabo VPS — currently
 * `Advisorlink/ON-NOW-TV12.1.1`), so the APK never hardcodes a
 * GitHub URL.  This means we can move the project to a new repo
 * later by flipping one env var, without re-shipping the APK.
 */
class FtaUpdateChecker(
    private val activity: AppCompatActivity,
    private val backendBase: String,
    private val currentVersion: String,
) {

    private val tag = "FtaUpdateChecker"
    private val handler = Handler(Looper.getMainLooper())

    fun checkAndPrompt() {
        Thread {
            try {
                val url = "$backendBase/api/app/latest-version-fta-native"
                val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 6_000
                    readTimeout = 8_000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "fta-native/$currentVersion")
                }
                val code = conn.responseCode
                if (code !in 200..299) {
                    Log.w(tag, "Update check returned HTTP $code — skipping prompt")
                    return@Thread
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val latestVersion = json.optString("version").takeIf { it.isNotBlank() } ?: return@Thread
                val apkUrl = json.optString("apk_url").takeIf { it.isNotBlank() } ?: return@Thread
                val name = json.optString("name")
                val notes = json.optString("notes")

                if (isNewer(latestVersion, currentVersion)) {
                    handler.post { promptUpdate(latestVersion, apkUrl, name, notes) }
                } else {
                    Log.i(tag, "Already on latest version ($currentVersion ≥ $latestVersion)")
                }
            } catch (t: Throwable) {
                Log.w(tag, "Update check failed", t)
            }
        }.start()
    }

    /** Naive semver "is a > b" comparator.  Treats X.Y.Z as
     *  3-tuple of ints; anything non-numeric counts as 0. */
    private fun isNewer(latest: String, current: String): Boolean {
        val a = latest.split(".").mapNotNull { it.toIntOrNull() }
        val b = current.split(".").mapNotNull { it.toIntOrNull() }
        val n = maxOf(a.size, b.size).coerceAtLeast(1)
        for (i in 0 until n) {
            val ai = a.getOrNull(i) ?: 0
            val bi = b.getOrNull(i) ?: 0
            if (ai > bi) return true
            if (ai < bi) return false
        }
        return false
    }

    private fun promptUpdate(latest: String, apkUrl: String, name: String, notes: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        val message = buildString {
            appendLine("A new build of ON NOW FTA is available.")
            appendLine()
            append("Current: $currentVersion\nLatest:  $latest")
            if (notes.isNotBlank()) {
                appendLine()
                appendLine()
                // Trim notes to ~600 chars so the dialog stays readable.
                append(notes.trim().take(600))
                if (notes.length > 600) append("…")
            }
        }
        AlertDialog.Builder(activity)
            .setTitle(if (name.isNotBlank()) name else "FTA $latest available")
            .setMessage(message)
            .setPositiveButton("Update now") { dialog, _ ->
                dialog.dismiss()
                startDownload(apkUrl)
            }
            .setNegativeButton("Later", null)
            .setCancelable(true)
            .show()
    }

    private fun startDownload(apkUrl: String) {
        try {
            val ctx = activity.applicationContext
            val cacheDir = File(ctx.externalCacheDir, "updates").apply { mkdirs() }
            val apkFile = File(cacheDir, "fta-native-update.apk").apply { if (exists()) delete() }
            Toast.makeText(activity, "Downloading FTA update…", Toast.LENGTH_SHORT).show()

            val dm = ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val req = DownloadManager.Request(Uri.parse(apkUrl)).apply {
                setTitle("ON NOW FTA update")
                setDescription("Downloading the new version…")
                setMimeType("application/vnd.android.package-archive")
                setDestinationUri(Uri.fromFile(apkFile))
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }
            val id = dm.enqueue(req)
            pollDownload(dm, id, apkFile)
        } catch (t: Throwable) {
            Log.e(tag, "startDownload failed", t)
            Toast.makeText(activity, "Update download failed: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun pollDownload(dm: DownloadManager, id: Long, apkFile: File) {
        var lastShownPct = -1
        val poll = object : Runnable {
            override fun run() {
                val q = DownloadManager.Query().setFilterById(id)
                val cur = dm.query(q) ?: run {
                    handler.postDelayed(this, 700); return
                }
                if (!cur.moveToFirst()) {
                    cur.close()
                    handler.postDelayed(this, 700); return
                }
                val status = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val downloaded = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                cur.close()
                when (status) {
                    DownloadManager.STATUS_RUNNING,
                    DownloadManager.STATUS_PAUSED,
                    DownloadManager.STATUS_PENDING -> {
                        if (total > 0) {
                            val pct = (downloaded * 100 / total).toInt()
                            // Show a Toast every 25 % so it doesn't
                            // spam the screen.
                            if (pct / 25 != lastShownPct / 25) {
                                lastShownPct = pct
                                Toast.makeText(activity, "Updating FTA… $pct%", Toast.LENGTH_SHORT).show()
                            }
                        }
                        handler.postDelayed(this, 700)
                    }
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        Toast.makeText(activity, "Download complete — installing", Toast.LENGTH_SHORT).show()
                        launchInstaller(apkFile)
                    }
                    DownloadManager.STATUS_FAILED -> {
                        Toast.makeText(activity, "Update download failed", Toast.LENGTH_LONG).show()
                    }
                    else -> handler.postDelayed(this, 700)
                }
            }
        }
        handler.post(poll)
    }

    private fun launchInstaller(apkFile: File) {
        try {
            val ctx = activity.applicationContext
            val uri = FileProvider.getUriForFile(
                ctx,
                ctx.packageName + ".fileprovider",
                apkFile,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            activity.startActivity(intent)
        } catch (e: SecurityException) {
            // Android 8+ blocks installs from sources that haven't
            // been granted REQUEST_INSTALL_PACKAGES yet — punt the
            // user to the system settings page where they can grant
            // it for this app.
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val settings = Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.packageName),
                    )
                    activity.startActivity(settings)
                    Toast.makeText(
                        activity,
                        "Tap 'Allow' so ON NOW FTA can install updates, then tap Update again.",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } catch (_: Throwable) {
                Toast.makeText(activity, "Install blocked: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } catch (t: Throwable) {
            Log.e(tag, "launchInstaller failed", t)
            Toast.makeText(activity, "Install failed: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }
}
