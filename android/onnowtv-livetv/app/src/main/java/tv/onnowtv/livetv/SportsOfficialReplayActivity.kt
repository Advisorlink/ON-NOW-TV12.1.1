package tv.onnowtv.livetv

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.*
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import tv.onnowtv.livetv.data.XtreamRepository
import tv.onnowtv.livetv.ui.SportsWindow
import tv.onnowtv.livetv.ui.SportsUi as UI

/** Supported provider iframe in our fullscreen shell. Never hands off to another app. */
class SportsOfficialReplayActivity : AppCompatActivity() {
    companion object {
        fun open(ctx: Context, sourceId: String) = ctx.startActivity(Intent(ctx, SportsOfficialReplayActivity::class.java).putExtra("source-id", sourceId))
        fun allowedNavigation(url: String, pageUrl: String, mainFrame: Boolean): Boolean {
            if (url == "about:blank") return true
            val uri = Uri.parse(url)
            if (uri.scheme != "https" || uri.userInfo != null) return false
            if (mainFrame) return url == pageUrl
            return uri.host in setOf("www.youtube.com", "www.youtube-nocookie.com") && uri.path.orEmpty().startsWith("/embed/")
        }
    }
    private lateinit var root: FrameLayout
    private lateinit var content: LinearLayout
    private lateinit var holder: FrameLayout
    private lateinit var status: TextView
    private var web: WebView? = null
    private var fullView: View? = null
    private var fullCallback: WebChromeClient.CustomViewCallback? = null
    private var pageUrl = ""
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); SportsWindow.apply(this); LivePreviewSession.release()
        root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        content = UI.col(this, 0); root.addView(content, FrameLayout.LayoutParams(-1, -1))
        val bar = UI.row(this, UI.dp(this, 10f)).apply { setPadding(UI.dp(context, 12f), UI.dp(context, 10f), UI.dp(context, 12f), UI.dp(context, 10f)) }
        bar.addView(UI.identify(UI.pill(this, "‹ Back") { finish() }, "official-player-back"))
        status = UI.identify(UI.text(this, "Official replay · loading…", 14f, UI.DIM), "official-player-status")
        bar.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(UI.identify(UI.pill(this, "Retry") { dispose(); load() }, "official-player-retry"))
        content.addView(bar); holder = FrameLayout(this); content.addView(holder, LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(root)
        val id = intent.getStringExtra("source-id").orEmpty()
        if (Regex("[a-z0-9-]{1,70}").matches(id)) pageUrl = XtreamRepository.BACKEND_BASE.trimEnd('/') + "/api/sportshub/official-replays/$id/player"
        else status.text = "This official replay is unavailable."
    }
    override fun onResume() { super.onResume(); if (web == null && pageUrl.isNotEmpty()) load() }
    private fun load() {
        if (pageUrl.isEmpty()) return
        try {
            val browser = UI.identify(WebView(this), "official-replay-webview"); web = browser
            browser.settings.apply {
                javaScriptEnabled = true; domStorageEnabled = true; mediaPlaybackRequiresUserGesture = true
                allowFileAccess = false; allowContentAccess = false; mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                setSupportMultipleWindows(false); javaScriptCanOpenWindowsAutomatically = false
            }
            browser.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val permitted = allowedNavigation(request.url.toString(), pageUrl, request.isForMainFrame)
                    if (!permitted) status.text = "This action is unavailable inside ON NOW. No external app will open."
                    return !permitted
                }
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: android.net.http.SslError) { handler.cancel(); status.text = "The official player connection could not be verified." }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) status.text = "Official replay could not load. Please retry."
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                    if (request.isForMainFrame) status.text = "This official replay is currently unavailable inside ON NOW."
                }
            }
            browser.webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView, title: String?) { status.text = when (title) { "ONNOW:READY" -> "Official replay · ready"; "ONNOW:PLAYING" -> "Official replay"; "ONNOW:ERROR" -> "Official replay unavailable · see player message"; else -> "Official replay" } }
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    if (fullView != null) { callback.onCustomViewHidden(); return }
                    fullView = view; fullCallback = callback; content.visibility = View.GONE
                    root.addView(view, FrameLayout.LayoutParams(-1, -1)); view.requestFocus()
                }
                override fun onHideCustomView() { exitFullscreen() }
                override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message): Boolean = false
            }
            holder.addView(browser, FrameLayout.LayoutParams(-1, -1)); browser.loadUrl(pageUrl)
        } catch (_: RuntimeException) { status.text = "The official player is unavailable on this device."; dispose() }
    }
    private fun exitFullscreen() {
        (fullView?.parent as? ViewGroup)?.removeView(fullView); fullView = null
        val callback = fullCallback; fullCallback = null; callback?.onCustomViewHidden(); content.visibility = View.VISIBLE
    }
    private fun dispose() {
        if (::content.isInitialized) exitFullscreen()
        web?.let { it.stopLoading(); it.loadUrl("about:blank"); it.onPause(); holder.removeView(it); it.removeAllViews(); it.destroy() }
        web = null
    }
    @Deprecated("TV remote support") override fun onBackPressed() { if (fullView != null) exitFullscreen() else finish() }
    override fun onPause() { dispose(); super.onPause() }
    override fun finish() { dispose(); super.finish() }
    override fun onDestroy() { dispose(); super.onDestroy() }
}