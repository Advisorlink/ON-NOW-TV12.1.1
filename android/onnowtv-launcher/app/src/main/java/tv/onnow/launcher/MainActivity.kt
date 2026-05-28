package tv.onnow.launcher

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.onnow.launcher.apps.AppsDrawerActivity
import tv.onnow.launcher.data.LauncherConfig as RemoteLauncherConfig
import tv.onnow.launcher.data.LauncherRepository
import tv.onnow.launcher.data.LayoutSettings
import tv.onnow.launcher.databinding.ActivityMainBinding
import tv.onnow.launcher.notify.NotificationPopup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Home screen of the launcher — v0.5 stripped-down rebuild.
 *
 * The screen is now intentionally minimal:
 *   - Per-tile wallpaper painted edge-to-edge
 *   - Top bar with greeting / OnNow TV V2 wordmark / date+time
 *   - Bottom dock with 1..N tiles (count comes from admin backend)
 *
 * No hero illustration.  No featured-panel text (title / tagline /
 * description / CTA).  No paginator.  The wallpaper is the entire
 * visual statement; the dock is the entire interaction surface.
 *
 * Tile data is fetched from `LauncherRepository` every 30 seconds.
 * Admin can add / remove / reorder tiles freely; the launcher
 * renders whatever it gets.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var repo: LauncherRepository
    private lateinit var dockAdapter: DockAdapter
    private val shownNotificationIds = mutableSetOf<String>()
    private var configPollJob: Job? = null
    private var hasReceivedFirstConfig = false
    private var currentWallpaperUrl: String? = null
    private var currentLayout: LayoutSettings = LayoutSettings()

    /* Mutable list of tiles rendered in the dock.  Empty on cold
     * launch — populated as soon as the first config arrives (or
     * the cached config is loaded from disk). */
    private val dockItems: MutableList<DockItem> = mutableListOf()

    /* v2.8.24 — QR videos rendered in the on-home overlay panel.
     * Cycles every 8 s when more than one is visible.  Empty list
     * hides the panel entirely. */
    private val qrVideos: MutableList<tv.onnow.launcher.data.QrVideoRemote> = mutableListOf()
    private var qrCycleIndex = 0
    private val qrCycleTick = object : Runnable {
        override fun run() {
            if (qrVideos.size > 1) {
                qrCycleIndex = (qrCycleIndex + 1) % qrVideos.size
                paintQrPanel()
            }
            handler.postDelayed(this, 8_000)
        }
    }

    /* Ticking clock for the top bar — updates every 30s. */
    private val clockTick = object : Runnable {
        override fun run() {
            paintClock()
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // v1.7 — Activation gate.  If this box hasn't been approved
        // by the admin yet, route to the Onboarding flow (Wi-Fi →
        // Register → Pending/Blocked) BEFORE we render the dock.
        // OnboardingActivity will return us to MainActivity once it
        // observes status="active" coming back from the backend.
        val activation = tv.onnow.launcher.onboarding.OnboardingActivity
            .currentStatus(this)
        if (activation != "active") {
            startActivity(android.content.Intent(this,
                tv.onnow.launcher.onboarding.OnboardingActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repo = LauncherRepository(applicationContext)

        bindTopBar()
        bindTopBarActions()
        bindDock()

        // Hydrate from disk cache so the launcher renders the LAST
        // known remote config the moment the user opens it — even on
        // cold start with no network yet.
        repo.loadCached()?.let { onConfigUpdated(it) }
    }

    override fun onResume() {
        super.onResume()
        handler.post(clockTick)
        handler.postDelayed(qrCycleTick, 8_000)
        startBackendPolling()
        // v2.8.19 — Refresh the top-bar VPN status dot whenever the
        // launcher gains focus, so a freshly-connected VPN client
        // (or a disconnect) shows up the moment the user returns.
        if (::binding.isInitialized) refreshVpnDot()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(clockTick)
        handler.removeCallbacks(qrCycleTick)
        configPollJob?.cancel()
    }

    /* ──────────────────────────  Dock  ───────────────────────── */

    private fun bindDock() {
        // v2.8.22 — Default LinearLayoutManager.  The previous
        // onInterceptFocusSearch override was too aggressive — it
        // swallowed LEFT/RIGHT inside the dock too (the user's
        // "I can't move left or right on the tiles" complaint).
        // Horizontal navigation is now handled MANUALLY in
        // `dispatchKeyEvent` below, which moves focus + scrolls
        // explicitly when the user is in the dock — bulletproof
        // both at edges (no escape) and mid-list (no leaks).
        val lm = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        binding.dock.layoutManager = lm
        dockAdapter = DockAdapter(dockItems) { item -> onTileSelected(item) }
        // v2.8.20 — Per-item UP arrow climbs to the VPN pill.
        dockAdapter.nextFocusUpResId = binding.topbarBtnVpn.id
        binding.dock.adapter = dockAdapter

        // Listen for focus changes inside the dock so we can swap
        // the wallpaper as the user navigates between tiles.
        binding.dock.viewTreeObserver.addOnGlobalFocusChangeListener { _, newFocus ->
            if (newFocus == null) return@addOnGlobalFocusChangeListener
            val rv = binding.dock
            // RecyclerView has a built-in findContainingItemView that
            // walks the view tree up to a direct child of this RV.
            val itemView = rv.findContainingItemView(newFocus) ?: return@addOnGlobalFocusChangeListener
            val pos = rv.getChildAdapterPosition(itemView)
            if (pos in dockItems.indices) {
                val tile = dockItems[pos]
                applyWallpaperForTile(tile)
                applyFeaturedPanel(tile)
            }
        }
    }

    /* ────────────────────  Featured panel  ─────────────────── */

    /**
     * Paints the heading / description / CTA pill panel that floats
     * over the wallpaper for the currently-focused tile.
     *
     *   • If the tile has no heading AND no description, the entire
     *     panel fades out (we don't show an empty card).
     *   • CTA pill fill colour follows the tile's accent so the
     *     button reads as the same "brand" as the glow halo.
     */
    private fun applyFeaturedPanel(item: DockItem) {
        val panel   = binding.featuredPanel
        val heading = item.heading?.trim().orEmpty()
        val subh    = item.subheading?.trim().orEmpty()
        val desc    = item.description?.trim().orEmpty()
        val cta     = item.ctaLabel?.trim().orEmpty().ifEmpty { "ENTER" }

        // Panel is shown if ANY of heading / subheading / description
        // are set.  Otherwise hide the whole thing.
        val hasContent = heading.isNotEmpty() || subh.isNotEmpty() || desc.isNotEmpty()
        if (!hasContent) {
            panel.animate().alpha(0f).setDuration(180).start()
            return
        }

        // Per-tile content.  Visibility for each block is gated by
        // BOTH the global Layout Editor toggle AND a non-empty value.
        // Heading-as-image case (Layout Editor sets a URL) is also
        // honoured here — we keep the text heading hidden when the
        // image is in play.
        val useHeadingImage =
            !currentLayout.featuredHeadingImageUrl.isNullOrBlank()

        if (currentLayout.featuredShowHeading && !useHeadingImage) {
            binding.featuredHeading.text = heading
            binding.featuredHeading.visibility =
                if (heading.isEmpty()) View.GONE else View.VISIBLE
        } else if (!useHeadingImage) {
            binding.featuredHeading.visibility = View.GONE
        }

        if (currentLayout.featuredShowSubheading) {
            binding.featuredSubheading.text  = subh
            binding.featuredSubheading.visibility =
                if (subh.isEmpty()) View.GONE else View.VISIBLE
        } else {
            binding.featuredSubheading.visibility = View.GONE
        }

        if (currentLayout.featuredShowDescription) {
            binding.featuredDescription.text = desc
            binding.featuredDescription.visibility =
                if (desc.isEmpty()) View.GONE else View.VISIBLE
        } else {
            binding.featuredDescription.visibility = View.GONE
        }
        binding.featuredCta.text         = cta.uppercase()

        // CTA pill visibility honours BOTH the global "show button"
        // toggle from the Layout Editor AND the per-tile cta_label.
        val showCta = currentLayout.featuredShowButton
        binding.featuredCtaWrap.visibility = if (showCta) View.VISIBLE else View.GONE

        // Tint the CTA pill to the tile's accent.  Pill TEXT colour
        // comes from the Layout Editor; default is dark on bright
        // accent (auto-picked via YIQ) so legacy admins still get
        // readable buttons.
        val accent = parseTileAccent(item.accent)
        val pillBg = binding.featuredCta.background?.mutate()
        if (pillBg is android.graphics.drawable.GradientDrawable) {
            pillBg.setColor(accent)
        }
        val pillTextColor = parseHexColorOrNull(currentLayout.featuredButtonTextColor)
            ?: contrastingForeground(accent)
        binding.featuredCta.setTextColor(pillTextColor)

        if (panel.alpha < 1f) {
            panel.animate().alpha(1f).setDuration(280).start()
        }
    }

    private fun parseTileAccent(hex: String?): Int {
        if (hex.isNullOrBlank()) return 0xFF2BB6FF.toInt()
        return try { Color.parseColor(hex.trim()) }
        catch (_: Throwable) { 0xFF2BB6FF.toInt() }
    }

    private fun parseHexColorOrNull(hex: String?): Int? {
        if (hex.isNullOrBlank()) return null
        return try { Color.parseColor(hex.trim()) }
        catch (_: Throwable) { null }
    }

    /** Returns black on bright accents, white on dark ones — keeps
     *  the CTA label always legible against the pill fill. */
    private fun contrastingForeground(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val yiq = (r * 299 + g * 587 + b * 114) / 1000
        return if (yiq >= 150) 0xFF04060B.toInt() else 0xFFF4F7FB.toInt()
    }

    private fun onTileSelected(item: DockItem) {
        // 1. If the tile points at a target package + the package is
        //    installed, launch it directly.  v1.6 — When the tile is
        //    the Kids tile AND it targets Vesper, we ALSO pass the
        //    `?profile=kids` deep-link so the Vesper WebView boots
        //    straight into the sandboxed Kids profile.
        //    v1.8 — Any OTHER tile that targets Vesper passes
        //    `profile=exit-kids` so re-entering from Movies/TV /
        //    Music / etc. drops the user OUT of Kids mode (instead
        //    of inheriting the previous Kids session via Vesper's
        //    saved last_url).
        val pkg = item.targetPackage
        val targetsVesper = pkg == "tv.onnowtv.app"
        if (!pkg.isNullOrBlank()) {
            val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                if (item.key == "kids") {
                    launchIntent.putExtra("vesper_route", "/?profile=kids")
                    launchIntent.data = Uri.parse("onnowtv://launch?profile=kids")
                } else if (targetsVesper) {
                    launchIntent.putExtra("vesper_route", "/?profile=exit-kids")
                    launchIntent.data =
                        Uri.parse("onnowtv://launch?profile=exit-kids")
                }
                startActivity(launchIntent)
                return
            }
        }
        // 1b. v1.6 — Kids tile auto-routes to Vesper even if
        //     `target_package` wasn't set by the admin.  Falls back
        //     to a Play-Store / browser deep-link if Vesper isn't
        //     installed locally.
        if (item.key == "kids") {
            val vesperPkg = "tv.onnowtv.app"
            val launchIntent = packageManager.getLaunchIntentForPackage(vesperPkg)
            if (launchIntent != null) {
                launchIntent.putExtra("vesper_route", "/?profile=kids")
                launchIntent.data = Uri.parse("onnowtv://launch?profile=kids")
                startActivity(launchIntent)
                return
            }
            // Vesper not installed — open Kids in a browser instead.
            val webBase = repo.baseUrlPublic()
            if (webBase.isNotBlank()) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$webBase/?profile=kids")))
                return
            }
        }
        // 2. If the tile has a target URL, open it in a browser.
        val url = item.targetUrl
        if (!url.isNullOrBlank()) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            return
        }
        // 3. Built-in shortcuts.
        if (item.key == "apps" || item.key == "downloads") {
            startActivity(Intent(this, AppsDrawerActivity::class.java))
            return
        }
        if (item.key == "settings") {
            startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
            return
        }
        // 4. Fallback toast.
        Toast.makeText(
            this,
            "Set a target in the Launcher admin for \"${item.label}\".",
            Toast.LENGTH_SHORT,
        ).show()
    }

    /* ──────────────────────────  Wallpaper  ───────────────────────── */

    private fun applyWallpaperForTile(item: DockItem) {
        val url = item.wallpaperUrl
        if (url == currentWallpaperUrl) return
        currentWallpaperUrl = url
        val wallpaperView = binding.tileWallpaper
        val scrimView = binding.tileWallpaperScrim
        if (url.isNullOrBlank()) {
            wallpaperView.animate().alpha(0f).setDuration(280).withEndAction {
                wallpaperView.visibility = View.GONE
            }.start()
            scrimView.animate().alpha(0f).setDuration(280).withEndAction {
                scrimView.visibility = View.GONE
            }.start()
            return
        }
        ImageLoader.loadBitmap(this, url) { bmp ->
            if (bmp == null) return@loadBitmap
            if (currentWallpaperUrl != url) return@loadBitmap
            wallpaperView.setImageBitmap(bmp)
            wallpaperView.alpha = 0f
            wallpaperView.visibility = View.VISIBLE
            scrimView.alpha = 0f
            scrimView.visibility = View.VISIBLE
            wallpaperView.animate().alpha(1f).setDuration(360).start()
            scrimView.animate().alpha(1f).setDuration(360).start()
        }
    }

    /* ─────────────────  Network polling / config  ───────────────── */

    /**
     * v1.7 — Verify activation status against the backend.  If admin
     * has blocked the device, route back to OnboardingActivity (which
     * shows the "ON NOW TV is blocked" popup).  Returns false when
     * the caller should stop polling.
     */
    private suspend fun checkActivationGate(): Boolean = withContext(Dispatchers.IO) {
        try {
            val deviceId = tv.onnow.launcher.onboarding.OnboardingActivity
                .deviceId(this@MainActivity)
            val url = repo.baseUrlPublic().trimEnd('/') +
                      "/api/launcher/activation?device_id=$deviceId"
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 6_000
            conn.readTimeout    = 6_000
            val txt = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val status = org.json.JSONObject(txt).optString("status", "active")
            if (status != "active") {
                getSharedPreferences(
                    tv.onnow.launcher.onboarding.OnboardingActivity.PREFS,
                    MODE_PRIVATE,
                ).edit()
                    .putString(
                        tv.onnow.launcher.onboarding.OnboardingActivity.KEY_STATUS,
                        status,
                    )
                    .apply()
                withContext(Dispatchers.Main) {
                    startActivity(
                        Intent(this@MainActivity,
                            tv.onnow.launcher.onboarding.OnboardingActivity::class.java)
                            .apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_CLEAR_TASK
                            }
                    )
                    finish()
                }
                return@withContext false
            }
            true
        } catch (_: Throwable) {
            // Backend unreachable — don't kick the user out.  We err
            // toward letting them keep using a previously-approved
            // box during transient network failures.
            true
        }
    }

    private fun startBackendPolling() {
        configPollJob?.cancel()
        configPollJob = lifecycleScope.launch {
            updateDebugStatus("polling…", false)
            while (true) {
                val ts = System.currentTimeMillis()

                // v1.7 — Re-check activation status every cycle so an
                // admin-side BLOCK takes effect within ~30 s even on
                // an already-running box.  If our status has changed
                // away from "active", bounce back to Onboarding.
                val gateOk = checkActivationGate()
                if (!gateOk) return@launch

                val fresh = repo.refresh()
                if (fresh != null) {
                    onConfigUpdated(fresh)
                    val took = System.currentTimeMillis() - ts
                    val dockM = currentLayout.dockMarginBottomDp
                    updateDebugStatus(
                        "OK · gen ${fresh.generation} · dockBot=${dockM}dp · ${took}ms",
                        true,
                    )
                } else {
                    updateDebugStatus("NO REACH · ${repo.baseUrlPublic()}", false)
                }
                delay(TimeUnit.SECONDS.toMillis(30))
            }
        }
    }

    /** Surface any un-shown notifications from the latest config.
     *  The launcher backend embeds active notifications inside the
     *  config payload, so we don't need a separate poll endpoint. */
    private fun surfaceNotifications(config: RemoteLauncherConfig) {
        for (n in config.notifications) {
            if (n.id in shownNotificationIds) continue
            shownNotificationIds.add(n.id)
            NotificationPopup.show(this, n) {
                lifecycleScope.launch { repo.ackNotification(n.id) }
            }
        }
    }

    /** Paint the on-screen debug pill.  Auto-hides 5s after the first
     *  successful poll so it doesn't permanently clutter the UI. */
    private fun updateDebugStatus(msg: String, ok: Boolean) {
        // Prefix with the launcher BUILD version so the user can
        // visually confirm at a glance WHICH APK is installed.
        val v = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Throwable) { "?" }
        binding.debugStatus.text = "v$v · $msg"
        val color = if (ok) 0xFF2EEAC2.toInt() else 0xFFFFB454.toInt()
        binding.debugStatus.setTextColor(color)
        if (ok && !hasReceivedFirstConfig) {
            hasReceivedFirstConfig = true
            binding.debugStatus.postDelayed({
                binding.debugStatus.animate()
                    .alpha(0f)
                    .setDuration(600)
                    .withEndAction {
                        binding.debugStatus.visibility = View.GONE
                    }
                    .start()
            }, 15_000)
        }
    }

    private fun onConfigUpdated(config: RemoteLauncherConfig) {
        // v0.5 — Variable tile count (1..N) instead of the old fixed 6.
        // Build dock items from whatever the backend sends.
        val mapped = config.dockTiles.map { t ->
            DockItem(
                key            = t.key,
                label          = t.label,
                sub            = t.sub,
                iconRes        = iconResForKey(t.key),
                imageUrl       = t.imageUrl,
                wallpaperUrl   = t.wallpaperUrl,
                targetPackage  = t.targetPackage,
                targetUrl      = t.targetUrl,
                accent         = t.accent,
                heading        = t.heading,
                subheading     = t.subheading,
                description    = t.description,
                ctaLabel       = t.ctaLabel,
            )
        }
        dockItems.clear()
        dockItems.addAll(mapped)
        // v1.0 — Apply admin-edited Layout Editor values BEFORE
        // notifying the adapter so the new tile dimensions land on
        // the first render (no flash of old size).
        applyLayoutSettings(config.layout)
        binding.dock.adapter?.notifyDataSetChanged()
        // Wallpaper + featured panel for the currently focused
        // (or first) tile.
        binding.dock.post {
            val pos = (binding.dock.layoutManager as? LinearLayoutManager)
                ?.findFirstVisibleItemPosition() ?: 0
            val tile = dockItems.getOrNull(pos) ?: dockItems.firstOrNull()
            tile?.let {
                applyWallpaperForTile(it)
                applyFeaturedPanel(it)
            }
            // Auto-focus the first tile so D-pad works immediately.
            binding.dock.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
        // Surface any new admin-broadcast notifications.
        surfaceNotifications(config)
        // v2.8.20 — Apply admin-uploadable logo + top-bar pill colors.
        applyTopBarBranding(config.appstore, config.v2ai)
        // v2.8.24 — Refresh the on-home QR overlay panel.
        applyQrVideos(config.qrVideos)
    }

    /** v2.8.24 — Update the QR video list + repaint the overlay
     *  panel.  Hides the panel entirely if no visible QRs exist. */
    private fun applyQrVideos(list: List<tv.onnow.launcher.data.QrVideoRemote>) {
        qrVideos.clear()
        qrVideos.addAll(list.filter { !it.qrImageUrl.isNullOrBlank() })
        qrCycleIndex = 0
        paintQrPanel()
    }

    private fun paintQrPanel() {
        if (!::binding.isInitialized) return
        val panel = binding.qrVideoPanel
        if (qrVideos.isEmpty()) {
            panel.visibility = View.GONE
            return
        }
        val v = qrVideos[qrCycleIndex.coerceIn(0, qrVideos.size - 1)]
        panel.visibility = View.VISIBLE
        binding.qrVideoTitle.text = v.name
        if (v.caption.isNullOrBlank()) {
            binding.qrVideoCaption.visibility = View.GONE
        } else {
            binding.qrVideoCaption.visibility = View.VISIBLE
            binding.qrVideoCaption.text = v.caption
        }
        v.qrImageUrl?.let {
            tv.onnow.launcher.ImageLoader.load(binding.qrVideoImage, it)
        }
    }

    /** v2.8.20 — Recolour the top-bar action pills and swap the
     *  bundled logo for an admin-uploaded image, if set.
     *  v2.8.26 — Also swaps the V2 AI pill's lightning-bolt icon
     *  for an admin-uploaded image if `cfg.v2ai.buttonImageUrl` is
     *  set.  Tint is dropped on the override so colour PNGs render
     *  as uploaded. */
    private fun applyTopBarBranding(
        appstore: tv.onnow.launcher.data.AppStoreMeta,
        v2ai: tv.onnow.launcher.data.V2AIConfig = tv.onnow.launcher.data.V2AIConfig(),
    ) {
        // Logo: prefer the admin upload, fall back to the bundled
        // play-tile + wordmark layout that's already in the XML.
        val logo = appstore.logoImageUrl
        if (!logo.isNullOrBlank()) {
            // Use the wordmark TextView as the host: replace its
            // siblings + show an ImageView in its place.  Cheaper
            // than restructuring the layout — we just hide the text
            // + play tile and inject an ImageView once.
            val parent = binding.logoText.parent as? android.view.ViewGroup ?: return
            // Hide siblings inside the logo block.
            for (i in 0 until parent.childCount) {
                parent.getChildAt(i).visibility = android.view.View.GONE
            }
            // Inject (or reuse) the logo ImageView.
            val tag = "topbar-logo-img"
            var logoView = parent.findViewWithTag<android.widget.ImageView>(tag)
            if (logoView == null) {
                logoView = android.widget.ImageView(this).apply {
                    this.tag = tag
                    adjustViewBounds = true
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        (resources.displayMetrics.density * 40).toInt(),
                    )
                }
                parent.addView(logoView)
            }
            logoView.visibility = android.view.View.VISIBLE
            tv.onnow.launcher.ImageLoader.load(logoView, logo)
        }

        // Pill background + text colors.
        val bgHex      = appstore.topbarBtnBgColor        ?: "#33203A5C"
        val textHex    = appstore.topbarBtnTextColor      ?: "#FFFFFFFF"
        val focusBgHex = appstore.topbarBtnFocusBgColor   ?: "#FF2BB6FF"
        val focusFgHex = appstore.topbarBtnFocusTextColor ?: "#FF04060B"
        val bgColor        = parseHexSafely(bgHex,      Color.parseColor("#33203A5C"))
        val textColor      = parseHexSafely(textHex,    Color.parseColor("#FFFFFFFF"))
        val focusBgColor   = parseHexSafely(focusBgHex, Color.parseColor("#FF2BB6FF"))
        val focusTextColor = parseHexSafely(focusFgHex, Color.parseColor("#FF04060B"))
        listOf(binding.topbarBtnVpn, binding.topbarBtnSpeed, binding.topbarBtnV2ai).forEach { pill ->
            // v2.8.38 — V2 AI pill can override the shared top-bar
            // palette with its own colors set on the V2 AI admin
            // tab.  Any null override falls back to the shared
            // value above.
            val isV2ai = pill === binding.topbarBtnV2ai
            val pillBg = if (isV2ai) parseHexSafely(v2ai.buttonBgColor       ?: bgHex,      bgColor)        else bgColor
            val pillTx = if (isV2ai) parseHexSafely(v2ai.buttonTextColor     ?: textHex,    textColor)      else textColor
            val pillFB = if (isV2ai) parseHexSafely(v2ai.buttonFocusBgColor  ?: focusBgHex, focusBgColor)   else focusBgColor
            val pillFT = if (isV2ai) parseHexSafely(v2ai.buttonFocusTextColor?: focusFgHex, focusTextColor) else focusTextColor
            // Replace the pill's background with a state selector
            // honouring the admin-chosen resting + focused colors.
            val resting = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 9999f
                setColor(pillBg)
            }
            val focused = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 9999f
                setColor(pillFB)
            }
            val sel = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), focused)
                addState(intArrayOf(), resting)
            }
            pill.background = sel
            // v2.8.22 — Focus-state text tint via ColorStateList so the
            // label + icon flip color in lockstep with the background.
            val tints = android.content.res.ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_focused),
                    intArrayOf(),
                ),
                intArrayOf(pillFT, pillTx),
            )
            for (i in 0 until pill.childCount) {
                when (val child = pill.getChildAt(i)) {
                    is android.widget.ImageView -> child.imageTintList = tints
                    is android.widget.TextView  -> child.setTextColor(tints)
                }
            }
        }
        // v2.8.26 — V2 AI pill button icon override.  If the admin
        // uploaded a custom button image, swap the lightning-bolt
        // ImageView's src for it AND drop the tint so colour PNGs
        // render exactly as uploaded.
        val v2aiIconUrl = v2ai.buttonImageUrl
        if (!v2aiIconUrl.isNullOrBlank()) {
            val pill = binding.topbarBtnV2ai
            for (i in 0 until pill.childCount) {
                val child = pill.getChildAt(i)
                if (child is android.widget.ImageView) {
                    child.imageTintList = null
                    tv.onnow.launcher.ImageLoader.load(child, v2aiIconUrl)
                    break
                }
            }
        }
    }

    private fun parseHexSafely(hex: String, fallback: Int): Int = try {
        Color.parseColor(hex)
    } catch (_: Throwable) { fallback }

    /* ────────────────────  Layout Editor  ──────────────────── */

    /**
     * v1.2 — Apply admin-controlled Layout Editor values.  Each step
     * is independently wrapped so a malformed value in one section
     * (e.g. an unparseable colour hex) can't kill the others.  The
     * dock + featured-panel margins are applied via the Kotlin
     * `updateLayoutParams` extension which calls `requestLayout`
     * deterministically.  Called on every config update — including
     * the initial cached load — so layout changes from the dashboard
     * land within one poll cycle.
     */
    private fun applyLayoutSettings(layout: LayoutSettings) {
        currentLayout = layout
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        // 1. Dock margins.
        try {
            binding.dock.updateLayoutParams<androidx.constraintlayout.widget.ConstraintLayout.LayoutParams> {
                bottomMargin = dp(layout.dockMarginBottomDp)
                leftMargin   = dp(layout.dockMarginHorizontalDp)
                rightMargin  = dp(layout.dockMarginHorizontalDp)
            }
            binding.dock.requestLayout()
        } catch (t: Throwable) {
            android.util.Log.e("LayoutEditor", "dock margins failed", t)
        }

        // 2. Featured panel margins.
        try {
            binding.featuredPanel.updateLayoutParams<androidx.constraintlayout.widget.ConstraintLayout.LayoutParams> {
                leftMargin   = dp(layout.featuredMarginStartDp)
                bottomMargin = dp(layout.featuredMarginBottomDp)
            }
            binding.featuredPanel.requestLayout()
        } catch (t: Throwable) {
            android.util.Log.e("LayoutEditor", "featured panel margins failed", t)
        }

        // 2b. v1.8 — Group nudge.  Translate the WHOLE panel as a
        //     single block via View.translationX / Y.  Lets the admin
        //     fine-tune the position of the heading + subheading +
        //     description + CTA as ONE unit, without having to keep
        //     the per-element gaps in sync.  Translation does NOT
        //     change the layout measurement, so adjacent views
        //     (dock, topbar) keep their positions.
        try {
            binding.featuredPanel.translationX = dp(layout.featuredGroupOffsetXDp).toFloat()
            binding.featuredPanel.translationY = dp(layout.featuredGroupOffsetYDp).toFloat()
        } catch (t: Throwable) {
            android.util.Log.e("LayoutEditor", "group offset failed", t)
        }

        // 3. Top bar visibility.
        try {
            binding.topbar.visibility =
                if (layout.topbarVisible) View.VISIBLE else View.GONE
        } catch (t: Throwable) {
            android.util.Log.e("LayoutEditor", "topbar visibility failed", t)
        }

        // 3b. v1.6 — Per-element show/hide toggles for the featured
        //     panel.  Honour the admin's global "show" flag AND any
        //     content-driven hides (empty heading still hides itself).
        //     Heading-as-image overrides the text heading when set.
        try {
            val headingImageUrl = layout.featuredHeadingImageUrl?.trim().orEmpty()
            val useHeadingImage = headingImageUrl.isNotEmpty()
            binding.featuredHeadingImage.visibility = when {
                !layout.featuredShowHeading -> View.GONE
                useHeadingImage             -> View.VISIBLE
                else                        -> View.GONE
            }
            binding.featuredHeading.visibility = when {
                !layout.featuredShowHeading -> View.GONE
                useHeadingImage             -> View.GONE
                else                        -> View.VISIBLE
            }
            binding.featuredSubheading.visibility = when {
                !layout.featuredShowSubheading -> View.GONE
                binding.featuredSubheading.text.isNullOrEmpty() -> View.GONE
                else                           -> View.VISIBLE
            }
            binding.featuredDescription.visibility = when {
                !layout.featuredShowDescription -> View.GONE
                binding.featuredDescription.text.isNullOrEmpty() -> View.GONE
                else                            -> View.VISIBLE
            }
            // Resize and load the heading image.
            if (useHeadingImage) {
                binding.featuredHeadingImage.updateLayoutParams<android.widget.LinearLayout.LayoutParams> {
                    height = dp(layout.featuredHeadingImageHeightDp)
                }
                ImageLoader.load(
                    binding.featuredHeadingImage,
                    headingImageUrl,
                    R.drawable.ic_dock_tv,
                )
            }
        } catch (t: Throwable) {
            android.util.Log.e("LayoutEditor", "visibility toggles failed", t)
        }

        // 4. Per-element typography (font / size / weight / color +
        //    letter-spacing on every element + line-height on the
        //    multi-line description).
        applyTextStyleSafe("heading",
            binding.featuredHeading,
            layout.featuredHeadingFont,
            layout.featuredHeadingWeight,
            layout.featuredHeadingSizeSp,
            layout.featuredHeadingColor)
        binding.featuredHeading.letterSpacing = layout.featuredHeadingLetterSpacing / 100f

        applyTextStyleSafe("subheading",
            binding.featuredSubheading,
            layout.featuredSubheadingFont,
            layout.featuredSubheadingWeight,
            layout.featuredSubheadingSizeSp,
            layout.featuredSubheadingColor)
        binding.featuredSubheading.letterSpacing = layout.featuredSubheadingLetterSpacing / 100f

        applyTextStyleSafe("description",
            binding.featuredDescription,
            layout.featuredDescriptionFont,
            layout.featuredDescriptionWeight,
            layout.featuredDescriptionSizeSp,
            layout.featuredDescriptionColor)
        binding.featuredDescription.letterSpacing = layout.featuredDescriptionLetterSpacing / 100f
        // Line-height multiplier (100 = single line spacing).
        binding.featuredDescription.setLineSpacing(0f, layout.featuredDescriptionLineHeightPct / 100f)

        applyTextStyleSafe("button",
            binding.featuredCta,
            layout.featuredButtonFont,
            layout.featuredButtonWeight,
            layout.featuredButtonSizeSp,
            layout.featuredButtonTextColor)
        binding.featuredCta.letterSpacing = layout.featuredButtonLetterSpacing / 100f

        // 4b. Vertical gaps between featured-panel elements.  Each
        //     value controls the top margin of the next element so
        //     the admin can dial in the exact rhythm of the panel.
        listOf(
            binding.featuredSubheading to layout.featuredGapAfterHeadingDp,
            binding.featuredDescription to layout.featuredGapAfterSubheadingDp,
            binding.featuredCtaWrap to layout.featuredGapAfterDescriptionDp,
        ).forEach { (view, gapDp) ->
            try {
                view.updateLayoutParams<android.widget.LinearLayout.LayoutParams> {
                    topMargin = dp(gapDp)
                }
            } catch (t: Throwable) {
                android.util.Log.e("LayoutEditor", "gap update failed", t)
            }
        }

        // 5. Horizontal alignment.
        try {
            val gravity = when (layout.featuredAlign.lowercase()) {
                "center", "centre" -> android.view.Gravity.CENTER_HORIZONTAL
                "end", "right"     -> android.view.Gravity.END
                else               -> android.view.Gravity.START
            }
            val textAlign = when (layout.featuredAlign.lowercase()) {
                "center", "centre" -> View.TEXT_ALIGNMENT_CENTER
                "end", "right"     -> View.TEXT_ALIGNMENT_VIEW_END
                else               -> View.TEXT_ALIGNMENT_VIEW_START
            }
            binding.featuredPanel.gravity = gravity
            listOf(
                binding.featuredHeading,
                binding.featuredSubheading,
                binding.featuredDescription,
            ).forEach { it.textAlignment = textAlign }
            (binding.featuredCtaWrap.layoutParams as? android.widget.LinearLayout.LayoutParams)?.also { lp ->
                lp.gravity = gravity
                binding.featuredCtaWrap.layoutParams = lp
            }
        } catch (t: Throwable) {
            android.util.Log.e("LayoutEditor", "alignment failed", t)
        }

        // 6. Tile dimensions.
        try {
            if (::dockAdapter.isInitialized) {
                dockAdapter.tileWidthDp  = layout.tileWidthDp
                dockAdapter.tileHeightDp = layout.tileHeightDp
            }
        } catch (t: Throwable) {
            android.util.Log.e("LayoutEditor", "tile dimensions failed", t)
        }
    }

    /** Wrapper that logs any exception so a single bad value doesn't
     *  silently take out the rest of the layout. */
    private fun applyTextStyleSafe(
        which: String,
        view: android.widget.TextView,
        fontKey: String, weightKey: String,
        sizeSp: Int, colorHex: String,
    ) {
        try {
            applyTextStyle(view, fontKey, weightKey, sizeSp, colorHex)
        } catch (t: Throwable) {
            android.util.Log.e("LayoutEditor", "text style $which failed", t)
        }
    }

    /**
     * v1.4 — Map an admin-edited font / weight / size / colour combo
     * onto a TextView.  Now that every multi-weight family ships as a
     * `<font-family>` XML resource pointing at static TTFs, we can use
     * the canonical `Typeface.create(family, weight, italic)` overload
     * (API 28+) which honours the requested weight by selecting the
     * matching static file — no silent fallback to system Sans Bold.
     *
     * On API < 28 we fall back to `setTypeface(family, BOLD)` for
     * weights ≥ 600.  Android's font-family resolver handles the
     * weight match on those devices too, so this is safe.
     */
    private fun applyTextStyle(
        view: android.widget.TextView,
        fontKey: String,
        weightKey: String,
        sizeSp: Int,
        colorHex: String,
    ) {
        val typeface = fontTypefaceFor(fontKey, weightKey)
        val weight   = weightToInt(weightKey)
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            view.typeface = android.graphics.Typeface.create(typeface, weight, false)
        } else {
            val style = if (weight >= 600) android.graphics.Typeface.BOLD
                        else android.graphics.Typeface.NORMAL
            view.setTypeface(typeface, style)
        }
        view.textSize = sizeSp.toFloat()
        parseHexColorOrNull(colorHex)?.let { view.setTextColor(it) }
        // v1.7 — Defensively disable any truncation that might have
        // been inherited or set elsewhere.  Featured-panel text must
        // grow to fit ALL the admin's copy, no matter how long.
        view.maxLines = Int.MAX_VALUE
        view.ellipsize = null
        view.isSingleLine = false
    }

    /** v1.4 — Returns the static-weight font family for [fontKey].
     *  Multi-weight families use a `<font-family>` XML resource so
     *  Android picks the right TTF for the requested weight (no
     *  silent system Sans fallback).  Single-weight display fonts
     *  use the raw .ttf directly.
     *
     *  Unknown fontKey falls back to Montserrat. */
    private fun fontTypefaceFor(fontKey: String, weightKey: String): android.graphics.Typeface {
        val resId = when (fontKey.lowercase().replace("-", "_")) {
            // Multi-weight sans-serif body fonts
            "montserrat"        -> R.font.montserrat
            "inter"             -> R.font.inter
            "poppins"           -> R.font.poppins
            "roboto"            -> R.font.roboto
            "nunito"            -> R.font.nunito
            // Multi-weight serif / cinematic
            "playfair_display",
            "playfair"          -> R.font.playfair_display
            "merriweather"      -> R.font.merriweather
            // Single-weight display fonts
            "oswald"            -> R.font.oswald
            "bebas_neue", "bebas" -> R.font.bebas_neue
            "anton"             -> R.font.anton
            "dm_serif_display", "dm_serif" -> R.font.dm_serif_display
            "russo_one", "russo" -> R.font.russo_one
            "lobster"           -> R.font.lobster
            "pacifico"          -> R.font.pacifico
            else                -> R.font.montserrat
        }
        return androidx.core.content.res.ResourcesCompat.getFont(this, resId)
            ?: android.graphics.Typeface.DEFAULT
    }

    /** Map a Montserrat-style weight label to a numeric weight (the
     *  CSS-style 100..900 axis that variable fonts use). */
    private fun weightToInt(weightKey: String): Int =
        when (weightKey.lowercase()) {
            "thin"       -> 100
            "extralight" -> 200
            "light"      -> 300
            "regular"    -> 400
            "medium"     -> 500
            "semibold"   -> 600
            "bold"       -> 700
            "extrabold"  -> 800
            "black"      -> 900
            else         -> 400
        }

    /** Map a remote dock-tile `key` back to one of our built-in
     *  vector icons.  Falls back to the grid icon for unknown keys. */
    private fun iconResForKey(key: String): Int = when (key) {
        "movies"   -> R.drawable.ic_dock_film
        "music"    -> R.drawable.ic_dock_music
        "livetv"   -> R.drawable.ic_dock_tv
        "apps"     -> R.drawable.ic_dock_grid
        "browser"  -> R.drawable.ic_dock_globe
        "settings" -> R.drawable.ic_dock_gear
        else       -> R.drawable.ic_dock_grid
    }

    /* ──────────────────────────  Top bar  ───────────────────────── */

    private fun bindTopBar() {
        binding.greeting.text = greetingForHour()
        binding.logoText.text = applyAccentTo(
            "OnNow TV V2", "V2",
            color = Color.parseColor("#2BB6FF"),
        )
        paintClock()
    }

    /**
     * v2.8.19 — Wire the new top-bar action pills (VPN + Speed Test)
     * to their respective full-screen activities.  Both pills are
     * focusable XML views; `nextFocusDown` on each pill already
     * points to `R.id.dock`, and the dock's UP arrow is wired here
     * so the focus chain is bidirectional.
     */
    private fun bindTopBarActions() {
        binding.topbarBtnVpn.setOnClickListener {
            startActivity(android.content.Intent(this,
                tv.onnow.launcher.vpn.VpnControlActivity::class.java))
        }
        binding.topbarBtnSpeed.setOnClickListener {
            launchSpeedTestApp()
        }
        // v2.8.23 — V2 AI push-and-hold voice assistant.
        binding.topbarBtnV2ai.setOnClickListener {
            startActivity(android.content.Intent(this,
                tv.onnow.launcher.v2ai.VoiceAssistantActivity::class.java))
        }
        // Reflect live VPN state on the pill's status dot.
        refreshVpnDot()
    }

    private fun launchSpeedTestApp() {
        val cfg = repo.config.value ?: repo.loadCached()
        val pkg = cfg?.appstore?.speedTestPackage?.trim().orEmpty()
        if (pkg.isEmpty()) {
            android.widget.Toast.makeText(
                this,
                "No Speed Test app configured yet — set the package name in the admin App Store tab.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
            return
        }
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            android.widget.Toast.makeText(
                this,
                "$pkg isn't installed.  Install it from the App Store first.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
            return
        }
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    /**
     * v2.8.22 — Manual LEFT/RIGHT focus advancement inside the dock.
     * Replaces the v2.8.21 LayoutManager override which broke
     * mid-list horizontal nav, AND the v2.8.20 dispatch trap which
     * leaked on the 4-5th rapid press.
     *
     * Strategy: while focus is in the dock, we OWN the LEFT/RIGHT
     * keys end-to-end.  Compute the target adapter position,
     * scrollToPosition if needed (cheaper + more predictable than
     * smoothScrollToPosition for ~12 items), then requestFocus on
     * the resulting itemView on the next layout pass.  No path
     * through Android's geometry focus search → no leaks possible.
     * UP / DOWN arrows fall through to default behaviour, so UP
     * still climbs to the top-bar VPN pill.
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN && ::binding.isInitialized) {
            val focused = currentFocus
            val dock = binding.dock
            val itemView = focused?.let { dock.findContainingItemView(it) }
            if (itemView != null) {
                val pos = dock.getChildAdapterPosition(itemView)
                val count = dock.adapter?.itemCount ?: 0
                val target = when (event.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (pos in 0 until count - 1) pos + 1 else null
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (pos > 0) pos - 1 else null
                    }
                    else -> Int.MIN_VALUE  // not a horizontal key — fall through
                }
                if (target == null) {
                    // Edge — swallow so focus can't escape sideways.
                    return true
                }
                if (target != Int.MIN_VALUE) {
                    val lm = dock.layoutManager as? LinearLayoutManager
                    val nextView = lm?.findViewByPosition(target)
                    if (nextView != null) {
                        nextView.requestFocus()
                    } else {
                        dock.scrollToPosition(target)
                        dock.post {
                            (dock.layoutManager as? LinearLayoutManager)
                                ?.findViewByPosition(target)
                                ?.requestFocus()
                        }
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Toggle the green/red status dot on the VPN pill. */
    private fun refreshVpnDot() {
        try {
            val cm = getSystemService(CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
            val active = cm.allNetworks.any { net ->
                cm.getNetworkCapabilities(net)?.hasTransport(
                    android.net.NetworkCapabilities.TRANSPORT_VPN
                ) == true
            }
            binding.topbarVpnDot.setBackgroundResource(
                if (active) R.drawable.bg_status_dot_on
                else        R.drawable.bg_status_dot_off
            )
        } catch (_: Throwable) { /* ignore — best-effort UI */ }
    }

    private fun paintClock() {
        val now = Date()
        binding.dateLabel.text = SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(now)
        binding.timeLabel.text = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(now)
    }

    private fun greetingForHour(): String {
        val h = SimpleDateFormat("HH", Locale.getDefault()).format(Date()).toInt()
        return when {
            h < 5  -> "Good night"
            h < 12 -> "Good morning"
            h < 17 -> "Good afternoon"
            else   -> "Good evening"
        }
    }

    /** Apply an accent colour to a substring of the given text. */
    private fun applyAccentTo(text: String, suffix: String, color: Int): SpannableString {
        val span = SpannableString(text)
        val start = text.lastIndexOf(suffix)
        if (start >= 0) {
            span.setSpan(
                ForegroundColorSpan(color),
                start,
                start + suffix.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return span
    }

    /* ──────────────────────────  Back / Home  ───────────────────── */

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // Launcher: BACK should never finish the activity (HOME-screen
        // semantic).  We swallow it.  Don't call super.
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        binding.dock.post {
            binding.dock.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }
}
