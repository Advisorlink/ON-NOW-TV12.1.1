package tv.onnow.launcher.install.apkm

import android.content.Context
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * ApkmInstaller
 * ─────────────
 * In-house equivalent of APK Mirror Installer.  Accepts either a
 * remote URL or a local file path pointing at an `.apkm` /
 * `.xapk` / `.apks` bundle (or a plain `.apk`), unpacks every
 * `*.apk` entry, and streams them into Android's
 * `PackageInstaller.Session` — the same path the platform itself
 * uses for split-APK installs.  The user still sees the standard
 * system install prompt (Android requires it; there is no silent
 * install for non-system apps).
 *
 * Permissions:
 *   • `REQUEST_INSTALL_PACKAGES` — declared in the Launcher's
 *     AndroidManifest.  Pre-granted on most AOSP TV boxes.
 *
 * v2.10.53 — Built so every Vesper / Live TV / Music / Kids
 * client can prompt for a missing dependency (e.g. WebView 138)
 * and install it WITHOUT requiring the user to first sideload a
 * 3rd-party APK Mirror Installer.
 */
object ApkmInstaller {

    private const val TAG = "ApkmInstaller"
    private const val BUFFER = 256 * 1024  // 256 KB streaming buffer

    private val http = tv.onnow.launcher.net.ResilientHttp.client.newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    sealed class Result {
        object SessionCommitted : Result()
        data class Error(val message: String, val cause: Throwable? = null) : Result()
    }

    /**
     * Download (if a remote URL) and install a bundle.
     *
     * @param ctx       Application or Activity context.
     * @param source    Either an `https://…` / `http://…` URL or
     *                  an absolute local path (e.g.
     *                  `/storage/emulated/0/Download/x.apkm`).
     * @param statusReceiverIntentSender  IntentSender returned by
     *                  `PackageInstaller.Session.commit()` — caller
     *                  must build this with a `PendingIntent` so
     *                  Android can surface the install-prompt UI
     *                  back to the user.
     * @param onProgress  Download progress in 0..100.
     */
    suspend fun downloadAndInstall(
        ctx: Context,
        source: String,
        statusReceiverIntentSender: android.content.IntentSender,
        onProgress: ((Int) -> Unit)? = null,
    ): Result = withContext(Dispatchers.IO) {
        val localFile: File = try {
            if (source.startsWith("http", ignoreCase = true)) {
                download(ctx, source, onProgress)
            } else {
                File(source).also {
                    if (!it.exists()) return@withContext Result.Error("File not found: $source")
                }
            }
        } catch (t: Throwable) {
            return@withContext Result.Error("Download failed: ${t.message}", t)
        }
        installFromFile(ctx, localFile, statusReceiverIntentSender)
    }

    /**
     * Install an on-disk bundle (skipping the download step).
     * Public so callers that already have the file (e.g. picked
     * via SAF) can call this directly.
     */
    suspend fun installFromFile(
        ctx: Context,
        file: File,
        statusReceiverIntentSender: android.content.IntentSender,
    ): Result = withContext(Dispatchers.IO) {
        try {
            val pm = ctx.packageManager
            val installer = pm.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            ).apply {
                /* Hint the OS that this is a big install so it can
                 * pre-allocate storage and skip mid-write GC. */
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setSize(file.length())
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    /* When available, ask Android to ALLOW updates
                     * even if the new app signature differs from a
                     * pre-installed system app's signature.  This
                     * is essential for shipping a newer WebView. */
                    setRequestUpdateOwnership(false)
                }
            }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                if (isBundle(file)) {
                    streamBundleIntoSession(file, session)
                } else {
                    streamApkIntoSession(file, session, name = file.name)
                }
                session.commit(statusReceiverIntentSender)
            }
            Log.i(TAG, "Session $sessionId committed for ${file.name}.")
            Result.SessionCommitted
        } catch (t: Throwable) {
            Log.e(TAG, "installFromFile failed", t)
            Result.Error("Install failed: ${t.message}", t)
        }
    }

    /* ───────── internals ───────── */

    /**
     * Decide whether `file` is a SPLIT-APK BUNDLE (.apkm / .xapk /
     * .apks containing multiple .apk entries) or a SINGLE plain
     * .apk (which is also a ZIP, but contains AndroidManifest.xml /
     * classes.dex — no nested .apk files).
     *
     * Why not just look at the filename?  v2.10.53-b shipped with
     * `endsWith(".apkm")` etc, but production renamed the file to
     * `.apk` during upload — so a real bundle was being treated as
     * a single APK.  Looking INSIDE the ZIP is bulletproof: if
     * there are nested `.apk` entries, it's a bundle.
     */
    private fun isBundle(file: File): Boolean {
        if (file.length() < 4) return false
        return try {
            ZipFile(file).use { zf ->
                zf.entries().asSequence().any {
                    !it.isDirectory && it.name.lowercase().endsWith(".apk")
                }
            }
        } catch (_: Throwable) {
            // Not a ZIP at all → treat as plain APK and let
            // PackageInstaller reject it with a meaningful error.
            false
        }
    }

    /**
     * Iterate every `*.apk` entry inside the ZIP bundle and stream
     * each one directly into a uniquely-named session slot.
     * PackageInstaller treats the whole set of splits as one app.
     *
     * v2.10.53-f — SPLIT-CONFIG FILTERING.  We must NOT blindly
     * ship every `.apk` inside the bundle to PackageInstaller —
     * doing that on an ARM TV box with a multi-arch APKM puts
     * x86 native libs into the session and Android then rejects
     * the install with `INSTALL_FAILED_NO_MATCHING_ABIS` or
     * `INSTALL_FAILED_INVALID_APK`.  APK Mirror Installer's
     * special sauce is doing this same filtering.
     *
     * Filter rules:
     *   • `base.apk`                           → always include.
     *   • Feature splits (`split_<feature>.apk` that DON'T match
     *     a `split_config.*.apk` pattern)      → always include.
     *   • `split_config.<abi>.apk`             → include only if
     *     the device's `Build.SUPPORTED_ABIS` lists that ABI.
     *   • `split_config.<lang>.apk`            → include the
     *     device's primary language + English fallback.
     *   • `split_config.<dpi>dpi.apk`          → include the
     *     device's matching DPI bucket + nearest neighbour.
     *
     * Anything that doesn't match keeps Android happy (no
     * conflicting native libs) and dramatically shrinks the
     * install size.
     */
    private fun streamBundleIntoSession(
        bundle: File,
        session: PackageInstaller.Session,
    ) {
        var count = 0
        ZipFile(bundle).use { zf ->
            val all = zf.entries().toList()
                .filter { !it.isDirectory && it.name.lowercase().endsWith(".apk") }
                .sortedBy { it.name }
            require(all.isNotEmpty()) { "Bundle ${bundle.name} contains no .apk entries." }

            val keep = selectSplitsForDevice(all.map { it.name })
            val kept = all.filter { keep.contains(it.name) }
            Log.i(
                TAG,
                "Selected ${kept.size}/${all.size} splits for device " +
                    "(ABIs=${Build.SUPPORTED_ABIS.toList()}): " +
                    kept.joinToString { it.name },
            )

            for (entry in kept) {
                val safeName = "split_${count}_" +
                    entry.name.replace(Regex("[^A-Za-z0-9_.-]"), "_")
                zf.getInputStream(entry).use { input ->
                    session.openWrite(safeName, 0L, entry.size).use { out ->
                        copy(input, out)
                        session.fsync(out)
                    }
                }
                count += 1
            }
        }
        Log.i(TAG, "Streamed $count splits from ${bundle.name} into session.")
    }

    /** ABI / language / density split-selector — same logic APK
     *  Mirror Installer uses. */
    private fun selectSplitsForDevice(names: List<String>): Set<String> {
        val abiTokens = Build.SUPPORTED_ABIS.map { it.replace('-', '_') }
            .toSet()  // e.g. {armeabi_v7a, armeabi, arm64_v8a}
        val deviceLang = java.util.Locale.getDefault().language.lowercase()  // e.g. "en"
        val keep = LinkedHashSet<String>()

        // ── Pass 1: always include base + non-config splits ──
        for (n in names) {
            val lower = n.lowercase()
            if (lower == "base.apk" || lower.endsWith("/base.apk")) {
                keep.add(n); continue
            }
            // Feature splits look like `split_<feature>.apk` but NOT
            // `split_config.<abi>.apk`.  Anything that doesn't start
            // with the literal token `split_config.` is a feature.
            val isConfigSplit = lower.contains("split_config.") ||
                                lower.contains("config.")
            if (!isConfigSplit && lower.startsWith("split_")) {
                keep.add(n)
            }
        }

        // ── Pass 2: ABI config splits ──
        for (n in names) {
            val lower = n.lowercase()
            for (abi in abiTokens) {
                // Matches "split_config.armeabi_v7a.apk", "config.arm64_v8a.apk", etc.
                if (lower.contains("config.$abi.apk") ||
                    lower.contains(".$abi.apk")) {
                    keep.add(n); break
                }
            }
        }

        // ── Pass 3: language config splits ── (device lang + en fallback)
        val wantedLangs = setOf(deviceLang, "en")
        for (n in names) {
            val lower = n.lowercase()
            for (lang in wantedLangs) {
                if (lower.contains("config.$lang.apk")) {
                    keep.add(n); break
                }
            }
        }

        // ── Pass 4: DPI config splits ──  (best-match, fallback xxhdpi)
        val targetDpi = when {
            android.content.res.Resources.getSystem().displayMetrics.densityDpi >= 560 -> "xxxhdpi"
            android.content.res.Resources.getSystem().displayMetrics.densityDpi >= 400 -> "xxhdpi"
            android.content.res.Resources.getSystem().displayMetrics.densityDpi >= 280 -> "xhdpi"
            android.content.res.Resources.getSystem().displayMetrics.densityDpi >= 200 -> "hdpi"
            else -> "mdpi"
        }
        val dpiFallback = setOf(targetDpi, "xxhdpi", "tvdpi", "nodpi")
        for (n in names) {
            val lower = n.lowercase()
            for (dpi in dpiFallback) {
                if (lower.contains("config.${dpi}.apk")) {
                    keep.add(n); break
                }
            }
        }

        // ── Safety net ── if filtering kept NOTHING (e.g.
        // single-APK bundle with no base.apk naming), fall back
        // to keeping everything — better an over-broad install
        // than no install.
        return if (keep.isEmpty()) names.toSet() else keep
    }

    private fun streamApkIntoSession(
        apk: File,
        session: PackageInstaller.Session,
        name: String,
    ) {
        apk.inputStream().use { input ->
            session.openWrite(name, 0L, apk.length()).use { out ->
                copy(input, out)
                session.fsync(out)
            }
        }
    }

    private fun copy(input: InputStream, out: java.io.OutputStream) {
        val buf = ByteArray(BUFFER)
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            out.write(buf, 0, n)
        }
    }

    private fun download(
        ctx: Context,
        url: String,
        onProgress: ((Int) -> Unit)?,
    ): File {
        val target = File(ctx.cacheDir, "apkm-downloads").apply { mkdirs() }
        val name = url.substringAfterLast('/').takeIf { it.isNotBlank() } ?: "download.apkm"
        val safeName = name.replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val out = File(target, safeName)
        if (out.exists()) out.delete()

        val req = Request.Builder().url(url).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body ?: error("Empty body")
            val total = body.contentLength()
            body.byteStream().use { input ->
                FileOutputStream(out).use { fos ->
                    val buf = ByteArray(BUFFER)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        fos.write(buf, 0, n)
                        read += n
                        if (total > 0 && onProgress != null) {
                            val pct = ((read * 100) / total).toInt().coerceIn(0, 100)
                            onProgress(pct)
                        }
                    }
                }
            }
        }
        Log.i(TAG, "Downloaded ${out.length()} bytes → ${out.absolutePath}")
        return out
    }
}
