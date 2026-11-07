package tv.rebornplay.setup

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Tiny bootstrap: downloads the full Reborn Play APK with automatic resume, then hands it to the installer. */
class SetupActivity : Activity() {
    private val exec = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var bar: ProgressBar
    private lateinit var action: Button
    private lateinit var apk: File
    @Volatile private var running = false
    private var complete = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_setup)
        status = findViewById(R.id.status); detail = findViewById(R.id.detail); bar = findViewById(R.id.progress); action = findViewById(R.id.action)
        apk = File(File(cacheDir, "apk").apply { mkdirs() }, "reborn-play.apk")
        action.setOnClickListener { if (complete) install() else startDownload() }
        startDownload()
    }

    private fun startDownload() {
        if (running) return
        running = true; complete = false
        action.visibility = View.GONE
        show("Connecting to Reborn…", "")
        exec.execute { download() }
    }

    private fun show(s: String, d: String? = null, pct: Int? = null) = ui.post {
        status.text = s
        if (d != null) detail.text = d
        if (pct != null) bar.progress = pct
    }

    private fun fail(msg: String) = ui.post {
        running = false
        status.text = msg
        action.text = "Try again"; action.visibility = View.VISIBLE; action.requestFocus()
    }

    private fun expectedTotal(): Long = getSharedPreferences("setup", MODE_PRIVATE).getLong("total", 0L)
    private fun rememberTotal(t: Long) = getSharedPreferences("setup", MODE_PRIVATE).edit().putLong("total", t).apply()

    private fun download() {
        var drops = 0
        while (running) {
            var c: HttpURLConnection? = null
            try {
                val have = if (apk.exists()) apk.length() else 0L
                c = (URL(BuildConfig.APK_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000; readTimeout = 30_000; instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "RebornSetup/${BuildConfig.VERSION_NAME}")
                    setRequestProperty("Accept-Encoding", "identity")
                    if (have > 0) setRequestProperty("Range", "bytes=$have-")
                }
                val code = c.responseCode
                if (code == 416) { apk.delete(); continue }
                if (code != 200 && code != 206) throw IOException("Server replied $code")
                val total = if (code == 206) c.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1L else c.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
                if (total <= 0) throw IOException("Unknown download size")
                val append = code == 206 && have > 0
                // A new build was published since the partial file was written — start clean.
                if ((append && expectedTotal() != 0L && expectedTotal() != total) || have > total) { c.disconnect(); apk.delete(); rememberTotal(0); continue }
                rememberTotal(total)
                var done = if (append) have else 0L
                var last = 0L
                FileOutputStream(apk, append).use { out ->
                    c.inputStream.use { inp ->
                        val buf = ByteArray(1 shl 16)
                        while (running) {
                            val n = inp.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            val now = System.currentTimeMillis()
                            if (now - last > 250) { last = now; show("Downloading Reborn Play…", "${mb(done)} of ${mb(total)} MB", (done * 1000 / total).toInt()) }
                        }
                    }
                }
                if (apk.length() >= total) {
                    show("Download complete", "${mb(total)} MB · ready to install", 1000)
                    complete = true; running = false
                    ui.post { install() }
                    return
                }
                // Stream ended early (the connection was cut) — loop to resume from where we are.
                drops++
                show("Connection dropped — resuming…", "${mb(apk.length())} of ${mb(total)} MB")
                Thread.sleep(800)
            } catch (e: IOException) {
                drops++
                if (drops > 80) { fail("Couldn't finish the download. Check the internet connection and try again."); return }
                show("Reconnecting… (${e.message ?: "network"})", null)
                Thread.sleep(1500)
            } catch (e: InterruptedException) { return
            } finally { c?.disconnect() }
        }
    }

    private fun mb(b: Long) = String.format("%.1f", b / 1048576.0)

    private fun install() {
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            show("Allow Reborn Setup to install apps, then come back here", "one-time permission")
            try { startActivityForResult(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")), REQ_PERMISSION) }
            catch (_: ActivityNotFoundException) {
                try { startActivityForResult(Intent(Settings.ACTION_SECURITY_SETTINGS), REQ_PERMISSION) }
                catch (_: ActivityNotFoundException) { showInstallButton("Turn on 'Install unknown apps' for Reborn Setup in your device settings, then press Install") }
            }
            return
        }
        val intent = if (Build.VERSION.SDK_INT >= 24) {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", apk)
            Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true).putExtra(Intent.EXTRA_RETURN_RESULT, true)
        } else {
            Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(apk), "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        show("Installing Reborn Play…", "follow the prompt on screen")
        versionBefore = installedVersion()
        try { startActivityForResult(intent, REQ_INSTALL) }
        catch (_: ActivityNotFoundException) { showInstallButton("No installer found on this device") }
    }

    private var versionBefore = -1L
    private fun installedVersion(): Long = try {
        val pi = packageManager.getPackageInfo(BuildConfig.TARGET_PACKAGE, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    } catch (_: Exception) { -1L }

    private fun showInstallButton(msg: String) {
        status.text = msg
        action.text = "Install Reborn Play"; action.visibility = View.VISIBLE; action.requestFocus()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_PERMISSION -> if (complete) install()
            REQ_INSTALL -> {
                val launch = packageManager.getLaunchIntentForPackage(BuildConfig.TARGET_PACKAGE)
                    ?: packageManager.getLeanbackLaunchIntentForPackage(BuildConfig.TARGET_PACKAGE)
                val installed = resultCode == RESULT_OK || installedVersion() > versionBefore
                if (installed && launch != null) {
                    show("Reborn Play installed", "opening…", 1000)
                    apk.delete()
                    startActivity(launch); finish()
                } else showInstallButton(if (resultCode == RESULT_CANCELED) "Install cancelled — press to try again" else "Install didn't finish — press to try again")
            }
        }
    }

    override fun onDestroy() { running = false; exec.shutdownNow(); super.onDestroy() }

    companion object { const val REQ_PERMISSION = 1; const val REQ_INSTALL = 2 }
}
