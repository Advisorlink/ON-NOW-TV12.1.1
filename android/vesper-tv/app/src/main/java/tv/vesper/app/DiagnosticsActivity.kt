package tv.vesper.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.util.DisplayMetrics
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebSettings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * v2.10.28 — System Diagnostics Activity.
 *
 * Why this exists: the user has two HK1 S905X3 boxes that are both
 * "Android 9", yet only one works.  We need an objective fingerprint
 * of EACH box's runtime environment so we can see exactly what's
 * different.  Build.VERSION.RELEASE is not enough — many cheap
 * Android-TV ROMs claim "9" while shipping a hacked WebView, a
 * forked Chromium, or a downgraded emoji font.
 *
 * The screen prints, in one scrollable column:
 *   • OS — version, SDK_INT, codename, incremental, security patch
 *   • Device — manufacturer, model, device, product, brand, board,
 *     hardware, bootloader, fingerprint, supported ABIs
 *   • System WebView — package name + versionName + versionCode
 *     (this is the real Chromium engine version that drives our
 *     React UI; mismatched WebView versions are THE most common
 *     cause of "works on one box, broken on another")
 *   • Default User-Agent — pulled from WebSettings; surfaces the
 *     actual Chromium build (e.g. Chrome/83.0 vs Chrome/120.0)
 *   • Display — width, height, densityDpi, density, scaledDensity,
 *     refresh rate, smallest dp
 *   • GPU — GL_VENDOR / GL_RENDERER / GL_VERSION via an offscreen
 *     EGL surface (waits up to ~600 ms; falls back to "unknown")
 *   • Compat flags — whether we forced software layer rendering,
 *     and which heuristic triggered (fragile-Amlogic vs forced)
 *
 * Activate via:
 *   1. `am start -n tv.onnowtv.app/.DiagnosticsActivity`
 *   2. Web bridge: `window.OnNowTV.openDiagnostics()`
 *   3. (User-facing) Settings page → 7-tap on the version label.
 *
 * Buttons: Copy (clipboard) + Share (ACTION_SEND text/plain) +
 * Close.  The Share/Copy payload is the exact same text shown on
 * screen so the user can ping us a screenshot OR a paste.
 */
class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var bodyText: TextView
    private var glVendor: String = "unknown"
    private var glRenderer: String = "unknown"
    private var glVersion: String = "unknown"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#06080F"))
            isFillViewport = true
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 64)
        }

        val title = TextView(this).apply {
            text = "ON NOW TV · System Diagnostics"
            setTextColor(Color.WHITE)
            textSize = 22f
            setPadding(0, 0, 0, 8)
        }
        val sub = TextView(this).apply {
            text = "Send this to support. Hidden text matters too — " +
                "different boxes that 'look the same' often differ in " +
                "the WebView / GPU / compat lines below."
            setTextColor(Color.parseColor("#A8B5C7"))
            textSize = 13f
            setPadding(0, 0, 0, 24)
        }

        bodyText = TextView(this).apply {
            text = buildReport(includeGpu = false)
            setTextColor(Color.parseColor("#D8E3F0"))
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.parseColor("#0D1422"))
            setPadding(28, 24, 28, 24)
            setTextIsSelectable(true)
        }

        // ── Action buttons row ──────────────────────────────────
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 24, 0, 0)
        }
        val copyBtn = Button(this).apply {
            text = "Copy"
            setOnClickListener {
                try {
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("ON NOW TV diag", bodyText.text))
                    Toast.makeText(this@DiagnosticsActivity, "Copied", Toast.LENGTH_SHORT).show()
                } catch (_: Throwable) { /* swallow */ }
            }
        }
        val shareBtn = Button(this).apply {
            text = "Share"
            setOnClickListener {
                try {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "ON NOW TV diagnostics")
                        putExtra(Intent.EXTRA_TEXT, bodyText.text.toString())
                    }
                    startActivity(Intent.createChooser(send, "Share diagnostics"))
                } catch (_: Throwable) { /* no chooser */ }
            }
        }
        val closeBtn = Button(this).apply {
            text = "Close"
            setOnClickListener { finish() }
        }
        btnRow.addView(copyBtn)
        btnRow.addView(shareBtn)
        btnRow.addView(closeBtn)

        col.addView(title)
        col.addView(sub)
        col.addView(bodyText)
        col.addView(btnRow)
        root.addView(col)
        setContentView(root)

        // Kick off the GPU info fetch — it runs on an offscreen
        // GLSurfaceView so we don't block onCreate.  The report is
        // re-rendered once GL_VENDOR / RENDERER come back.
        captureGpuInfo()
    }

    private fun refreshReport() {
        bodyText.text = buildReport(includeGpu = true)
    }

    private fun buildReport(includeGpu: Boolean): String {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(dm)
        val refresh = try {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.refreshRate
        } catch (_: Throwable) { 0f }

        val webViewLine = describeSystemWebView()
        val defaultUa = try { WebSettings.getDefaultUserAgent(this) } catch (_: Throwable) { "unknown" }

        // Compat heuristic — keep in lock-step with MainActivity's logic.
        val needle = "${Build.HARDWARE} ${Build.MODEL} ${Build.DEVICE} ${Build.PRODUCT}".lowercase()
        val isFragileAmlogic =
            (("amlogic" in needle || "rockchip" in needle) &&
                Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) ||
                "s905x3" in needle
        val useSoftware = isFragileAmlogic

        val sb = StringBuilder(2048)
        sb.appendLine("══ OS ══")
        sb.appendLine("Android version : ${Build.VERSION.RELEASE}")
        sb.appendLine("SDK_INT         : ${Build.VERSION.SDK_INT}")
        sb.appendLine("Codename        : ${Build.VERSION.CODENAME}")
        sb.appendLine("Incremental     : ${Build.VERSION.INCREMENTAL}")
        sb.appendLine("Security patch  : ${safeGet { Build.VERSION.SECURITY_PATCH }}")
        sb.appendLine()
        sb.appendLine("══ Device ══")
        sb.appendLine("Manufacturer    : ${Build.MANUFACTURER}")
        sb.appendLine("Brand           : ${Build.BRAND}")
        sb.appendLine("Model           : ${Build.MODEL}")
        sb.appendLine("Device          : ${Build.DEVICE}")
        sb.appendLine("Product         : ${Build.PRODUCT}")
        sb.appendLine("Hardware        : ${Build.HARDWARE}")
        sb.appendLine("Board           : ${Build.BOARD}")
        sb.appendLine("Bootloader      : ${Build.BOOTLOADER}")
        sb.appendLine("Supported ABIs  : ${Build.SUPPORTED_ABIS.joinToString(",")}")
        sb.appendLine("Fingerprint     : ${Build.FINGERPRINT}")
        sb.appendLine()
        sb.appendLine("══ System WebView ══")
        sb.appendLine(webViewLine)
        sb.appendLine("Default UA      : $defaultUa")
        sb.appendLine()
        sb.appendLine("══ Display ══")
        sb.appendLine("Resolution      : ${dm.widthPixels} × ${dm.heightPixels}")
        sb.appendLine("Density DPI     : ${dm.densityDpi} (density=${dm.density}, scaled=${dm.scaledDensity})")
        sb.appendLine("xdpi/ydpi       : ${dm.xdpi} / ${dm.ydpi}")
        sb.appendLine("Refresh rate    : ${"%.1f".format(refresh)} Hz")
        sb.appendLine("Smallest width  : ${resources.configuration.smallestScreenWidthDp} dp")
        sb.appendLine("Screen layout   : 0x${Integer.toHexString(resources.configuration.screenLayout)}")
        sb.appendLine("UI mode         : 0x${Integer.toHexString(resources.configuration.uiMode)}")
        sb.appendLine()
        sb.appendLine("══ GPU ══")
        if (includeGpu) {
            sb.appendLine("GL_VENDOR       : $glVendor")
            sb.appendLine("GL_RENDERER     : $glRenderer")
            sb.appendLine("GL_VERSION      : $glVersion")
        } else {
            sb.appendLine("(querying… reopen to see)")
        }
        sb.appendLine()
        sb.appendLine("══ Compat ══")
        sb.appendLine("Fragile Amlogic : $isFragileAmlogic")
        sb.appendLine("Force software  : $forceSoftware (override)")
        sb.appendLine("Will use SW lyr : $useSoftware")
        sb.appendLine()
        sb.appendLine("══ App ══")
        sb.appendLine("Package         : $packageName")
        sb.appendLine("Version name    : ${BuildConfig.VERSION_NAME}")
        sb.appendLine("Version code    : ${BuildConfig.VERSION_CODE}")
        sb.appendLine("Git SHA         : ${BuildConfig.GIT_SHA}")
        sb.appendLine("Build ts        : ${BuildConfig.BUILD_TS}")
        return sb.toString()
    }

    /** Pull System-WebView (Chromium) details via PackageManager.
     *  WebView.getCurrentWebViewPackage() is the modern API
     *  (Android 8+); we fall back to scanning common candidate
     *  package names for older boxes (e.g. com.android.webview /
     *  com.google.android.webview / com.android.chrome).  Boxes
     *  with a forked / hacked WebView often surface a non-Google
     *  package name here, which is the clue we need. */
    private fun describeSystemWebView(): String {
        val pm = packageManager
        // Modern path — Android 8 (API 26)+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val info = android.webkit.WebView.getCurrentWebViewPackage()
                if (info != null) {
                    return "Package         : ${info.packageName}\n" +
                        "Version name    : ${info.versionName}\n" +
                        "Version code    : ${info.longVersionCode}"
                }
            } catch (_: Throwable) { /* fall through */ }
        }
        // Legacy path — try the well-known candidate package names.
        val candidates = listOf(
            "com.google.android.webview",
            "com.android.webview",
            "com.android.chrome",
        )
        for (pkg in candidates) {
            try {
                val pi = pm.getPackageInfo(pkg, 0)
                @Suppress("DEPRECATION")
                val code = pi.versionCode
                return "Package         : ${pi.packageName}  (heuristic)\n" +
                    "Version name    : ${pi.versionName}\n" +
                    "Version code    : $code"
            } catch (_: Throwable) { /* try next */ }
        }
        return "Package         : <none found>\n" +
            "Version name    : -\n" +
            "Version code    : -"
    }

    /** Spin up a 1×1 GLSurfaceView and grab GL_VENDOR / GL_RENDERER
     *  / GL_VERSION on the first draw, then tear it down.  This is
     *  the standard pattern; the OpenGL strings reveal the actual
     *  GPU driver vendor + version, which is the second most
     *  common source of "works on one box, broken on another"
     *  (different Mali drivers ship in different AOSP forks). */
    private fun captureGpuInfo() {
        try {
            val gl = GLSurfaceView(this)
            gl.setEGLContextClientVersion(2)
            gl.setRenderer(object : GLSurfaceView.Renderer {
                override fun onSurfaceCreated(gl10: GL10?, config: EGLConfig?) {
                    try {
                        glVendor = gl10?.glGetString(GL10.GL_VENDOR) ?: "?"
                        glRenderer = gl10?.glGetString(GL10.GL_RENDERER) ?: "?"
                        glVersion = gl10?.glGetString(GL10.GL_VERSION) ?: "?"
                    } catch (_: Throwable) { /* swallow */ }
                    runOnUiThread {
                        refreshReport()
                        try {
                            // Tear down the dummy surface — we only
                            // needed the first frame.
                            (gl.parent as? ViewGroup)?.removeView(gl)
                        } catch (_: Throwable) { /* swallow */ }
                    }
                }
                override fun onSurfaceChanged(gl10: GL10?, w: Int, h: Int) {}
                override fun onDrawFrame(gl10: GL10?) {}
            })
            gl.layoutParams = ViewGroup.LayoutParams(1, 1)
            gl.visibility = View.INVISIBLE
            (window.decorView as ViewGroup).addView(gl)
        } catch (_: Throwable) {
            // GL surface couldn't be created (extremely cheap boxes
            // without GLES2).  Leave the GPU lines as "unknown".
            runOnUiThread { refreshReport() }
        }
    }

    private inline fun safeGet(block: () -> String?): String =
        try { block() ?: "unknown" } catch (_: Throwable) { "unknown" }
}
 /* swallow */ }
                    runOnUiThread {
                        refreshReport()
                        try {
                            // Tear down the dummy surface — we only
                            // needed the first frame.
                            (gl.parent as? ViewGroup)?.removeView(gl)
                        } catch (_: Throwable) { /* swallow */ }
                    }
                }
                override fun onSurfaceChanged(gl10: GL10?, w: Int, h: Int) {}
                override fun onDrawFrame(gl10: GL10?) {}
            })
            gl.layoutParams = ViewGroup.LayoutParams(1, 1)
            gl.visibility = View.INVISIBLE
            (window.decorView as ViewGroup).addView(gl)
        } catch (_: Throwable) {
            // GL surface couldn't be created (extremely cheap boxes
            // without GLES2).  Leave the GPU lines as "unknown".
            runOnUiThread { refreshReport() }
        }
    }

    private inline fun safeGet(block: () -> String?): String =
        try { block() ?: "unknown" } catch (_: Throwable) { "unknown" }
}
