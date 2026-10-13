package tv.onnowtv.livetv.ui

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.core.view.OneShotPreDrawListener
import androidx.core.view.doOnPreDraw
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

/** One sandboxed, bundled Three.js scene. No JS bridge, remote scripts, network, or idle animation. */
@SuppressLint("SetJavaScriptEnabled")
class SportsSceneView(context: Context) : FrameLayout(context) {
    var aspectRatio: Float? = null
    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val height = aspectRatio?.let { MeasureSpec.makeMeasureSpec((MeasureSpec.getSize(widthSpec) / it).toInt(), MeasureSpec.EXACTLY) } ?: heightSpec
        super.onMeasure(widthSpec, height)
    }
    private val fallback = GamePlotView(context)
    private var browser: WebView? = null
    private var loaded = false
    private var payload = JSONObject()
    private var plays: List<JSONObject> = emptyList()
    private var index = -1
    private var top = false
    private var cameraControl: View? = null
    private var lastScene = ""
    private var resumed = false
    private var destroyed = false
    private var firstFrame: OneShotPreDrawListener? = null
    private val startBrowser = Runnable {
        if (resumed && !destroyed && isAttachedToWindow && !lowMemory && browser == null) createBrowser()
    }
    private val drawScene = Runnable { draw() }
    private val lowMemory = (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true
    private val host = WebViewAssetLoader.DEFAULT_DOMAIN
    init {
        SportsUi.identify(this, "sports-dimensional-field"); setBackgroundColor(SportsUi.PLOT)
        addView(fallback, LayoutParams(-1, -1))
        val label = SportsUi.eyebrow(context, "SCHEMATIC FIELD · FIGURES ARE ILLUSTRATIVE").apply { textSize = 8f; setPadding(10, 7, 10, 7); background = SportsUi.bg(0xBB0C1520.toInt(), 12f) }
        addView(label, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(12, 0, 12, 10) })
        if (!lowMemory) {
            cameraControl = SportsUi.identify(SportsUi.pill(context, "3D / Top") { top = !top; render() }, "field-camera-toggle").apply { visibility = View.GONE }
            addView(cameraControl, LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(0, 8, 8, 0) })
        }
    }
    private fun scheduleBrowser() {
        if (lowMemory || destroyed || !resumed || browser != null) return
        firstFrame?.removeListener()
        firstFrame = doOnPreDraw { firstFrame = null; post(startBrowser) }
        invalidate()
    }
    private fun createBrowser() {
        val loader = WebViewAssetLoader.Builder().addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context)).build()
        try {
            browser = WebView(context).apply {
                isFocusable = false; isFocusableInTouchMode = false; setBackgroundColor(Color.TRANSPARENT); visibility = View.INVISIBLE
                settings.javaScriptEnabled = true; settings.domStorageEnabled = false; settings.allowFileAccess = false; settings.allowContentAccess = false
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.setSupportZoom(false); setOnTouchListener { _, _ -> true }
                webViewClient = object : WebViewClientCompat() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                        return if (request.url.scheme == "https" && request.url.host == host) loader.shouldInterceptRequest(request.url)
                            ?: blocked() else blocked()
                    }
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                    override fun onPageFinished(view: WebView, url: String) { if (Uri.parse(url).host == host) { loaded = true; render() } }
                }
            }
            addView(browser, 1, LayoutParams(-1, -1))
            browser?.loadUrl(Uri.Builder().scheme("https").authority(host).path("/assets/sports-scene/index.html").build().toString())
        } catch (_: Exception) { browser?.destroy(); browser = null }
    }
    private fun blocked() = WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
    fun setGame(sport: String, accent: Int, homeColor: Int, awayColor: Int, plays: List<JSONObject>) {
        this.plays = plays; fallback.setGame(sport, accent, homeColor, awayColor, plays)
        payload.put("sport", sport).put("homeColor", String.format("#%06X", homeColor and 0xffffff)).put("awayColor", String.format("#%06X", awayColor and 0xffffff))
        render()
    }
    fun setTeams(game: JSONObject) {
        listOf("home", "away").forEach { side -> game.optJSONObject(side)?.optString("color")?.removePrefix("#")?.takeIf { it.matches(Regex("[0-9a-fA-F]{6}")) }?.let { payload.put("${side}Color", "#$it") } }
        payload.put("homeAbbr", game.optJSONObject("home")?.optString("abbr").orEmpty())
        payload.put("awayAbbr", game.optJSONObject("away")?.optString("abbr").orEmpty())
        val sit = game.optJSONObject("situation"); val bases = JSONArray()
        listOf("onFirst", "onSecond", "onThird").forEachIndexed { i, key -> if (sit?.optBoolean(key) == true) bases.put(i + 1) }
        payload.put("bases", bases); render()
    }
    fun showUpTo(value: Int) { index = value; fallback.showUpTo(value); render() }
    private fun render() {
        removeCallbacks(drawScene); postDelayed(drawScene, 16)
    }
    private fun draw() {
        if (!resumed || destroyed || !loaded || browser == null || payload.optString("sport").isBlank()) return
        payload.put("plays", JSONArray(plays.take((index + 1).coerceAtLeast(0)).takeLast(60))).put("view", if (top) "top" else "angle")
        val json = payload.toString(); if (json == lastScene) return; lastScene = json
        browser?.evaluateJavascript("window.SportsScene ? SportsScene.render($json) : false") { result -> browser?.visibility = if (result == "true") View.VISIBLE else View.INVISIBLE; cameraControl?.visibility = if (result == "true") View.VISIBLE else View.GONE }
    }
    fun pauseScene() { resumed = false; firstFrame?.removeListener(); firstFrame = null; removeCallbacks(startBrowser); removeCallbacks(drawScene); browser?.evaluateJavascript("window.SportsScene && SportsScene.pause()", null); browser?.onPause() }
    fun resumeScene() { resumed = true; lastScene = ""; browser?.onResume(); browser?.evaluateJavascript("window.SportsScene && SportsScene.resume()", null); scheduleBrowser(); render() }
    fun destroyScene() { destroyed = true; pauseScene(); browser?.evaluateJavascript("window.SportsScene && SportsScene.dispose()", null); browser?.stopLoading(); browser?.let { removeView(it); it.destroy() }; browser = null; loaded = false }
}