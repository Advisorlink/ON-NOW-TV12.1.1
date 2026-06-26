package tv.onnow.launcher.install

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * ApkInstaller
 * ────────────
 * Downloads an APK file from a URL (either a local backend path or
 * a remote https URL) to the app's cache directory, then fires the
 * standard Android `ACTION_VIEW` / `ACTION_INSTALL_PACKAGE` intent
 * which surfaces the system install prompt.
 *
 * Requires:
 *   • `android.permission.REQUEST_INSTALL_PACKAGES` (declared in
 *     AndroidManifest.xml).
 *   • A FileProvider entry that exposes the cache dir (so the
 *     URI passed to the installer is a `content://` URI on
 *     Android N+, NOT a `file://` URI which crashes since N).
 *
 * The user always sees the system install dialog — we never
 * silently install.  This is required by Android even with the
 * INSTALL_PACKAGES permission.
 */
object ApkInstaller {

    private const val TAG = "ApkInstaller"
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * @return null on success; an error message on failure.
     */
    suspend fun downloadAndInstall(
        ctx: Context,
        apkUrl: String,
        suggestedName: String? = null,
        onProgress: ((Int) -> Unit)? = null,
    ): String? = withContext(Dispatchers.IO) {
        try {
            val target = File(ctx.cacheDir, "downloads").apply { mkdirs() }
            val name = (suggestedName?.takeIf { it.endsWith(".apk", true) }
                ?: "install_${System.currentTimeMillis()}.apk").replace(Regex("[^A-Za-z0-9_.-]"), "_")
            val out = File(target, name)

            val req = Request.Builder().url(apkUrl).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext "HTTP ${resp.code}"
                val body = resp.body ?: return@withContext "empty body"
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
                            onProgress?.invoke(((read * 100L) / total).toInt())
                        }
                    }
                }
            }
            launchInstallPrompt(ctx, out)
            null
        } catch (e: IOException) {
            Log.e(TAG, "download failed", e)
            "Download failed: ${e.message}"
        } catch (t: Throwable) {
            Log.e(TAG, "install failed", t)
            "Install failed: ${t.message}"
        }
    }

    /**
     * Fires the standard install intent — the system shows the
     * "Allow installs from this source?" dialog (Android 8+) plus
     * the package-installer UI listing permissions, then the user
     * taps INSTALL.
     */
    private fun launchInstallPrompt(ctx: Context, apk: File) {
        val authority = "${ctx.packageName}.fileprovider"
        val uri: Uri = FileProvider.getUriForFile(ctx, authority, apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_ACTIVITY_NEW_TASK
        }
        ctx.startActivity(intent)
    }

    /**
     * Returns true if the app is currently authorised to install
     * APKs on Android 8+ (where this permission gate exists).  On
     * older devices this is always true.
     */
    fun canInstallNow(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) true
        else ctx.packageManager.canRequestPackageInstalls()

    /**
     * Open Settings → Apps → ON NOW TV V2 → Install unknown apps
     * so the user can grant the permission.
     */
    fun requestInstallPermission(ctx: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${ctx.packageName}"),
            ).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            ctx.startActivity(intent)
        }
    }
}
