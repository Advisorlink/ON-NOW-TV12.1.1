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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    /* Ticking clock for the top bar — updates every 30s. */
    private val clockTick = object : Runnable {
        override fun run() {
            paintClock()
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repo = LauncherRepository(applicationContext)

        bindTopBar()
        bindDock()

        // Hydrate from disk cache so the launcher renders the LAST
        // known remote config the moment the user opens it — even on
        // cold start with no network yet.
        repo.loadCached()?.let { onConfigUpdated(it) }
    }

    override fun onResume() {
        super.onResume()
        handler.post(clockTick)
        startBackendPolling()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(clockTick)
        configPollJob?.cancel()
    }

    /* ──────────────────────────  Dock  ───────────────────────── */

    private fun bindDock() {
        val lm = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        binding.dock.layoutManager = lm
        dockAdapter = DockAdapter(dockItems) { item -> onTileSelected(item) }
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

        binding.featuredHeading.text     = heading
        binding.featuredHeading.visibility =
            if (heading.isEmpty()) View.GONE else View.VISIBLE
        binding.featuredSubheading.text  = subh
        binding.featuredSubheading.visibility =
            if (subh.isEmpty()) View.GONE else View.VISIBLE
        binding.featuredDescription.text = desc
        binding.featuredDescription.visibility =
            if (desc.isEmpty()) View.GONE else View.VISIBLE
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
        //    installed, launch it directly.
        val pkg = item.targetPackage
        if (!pkg.isNullOrBlank()) {
            val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                startActivity(launchIntent)
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

    private fun startBackendPolling() {
        configPollJob?.cancel()
        configPollJob = lifecycleScope.launch {
            updateDebugStatus("polling…", false)
            while (true) {
                val ts = System.currentTimeMillis()
                val fresh = repo.refresh()
                if (fresh != null) {
                    onConfigUpdated(fresh)
                    val took = System.currentTimeMillis() - ts
                    // v1.2 — surface the live dock margin so we can
                    // tell at a glance whether layout updates are
                    // actually landing on the device.
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
    }

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

        // 3. Top bar visibility.
        try {
            binding.topbar.visibility =
                if (layout.topbarVisible) View.VISIBLE else View.GONE
        } catch (t: Throwable) {
            android.util.Log.e("LayoutEditor", "topbar visibility failed", t)
        }

        // 4. Per-element typography (each text style is independently
        //    wrapped so a single bad value can't break the others).
        applyTextStyleSafe("heading",
            binding.featuredHeading,
            layout.featuredHeadingFont,
            layout.featuredHeadingWeight,
            layout.featuredHeadingSizeSp,
            layout.featuredHeadingColor)
        applyTextStyleSafe("subheading",
            binding.featuredSubheading,
            layout.featuredSubheadingFont,
            layout.featuredSubheadingWeight,
            layout.featuredSubheadingSizeSp,
            layout.featuredSubheadingColor)
        applyTextStyleSafe("description",
            binding.featuredDescription,
            layout.featuredDescriptionFont,
            layout.featuredDescriptionWeight,
            layout.featuredDescriptionSizeSp,
            layout.featuredDescriptionColor)
        applyTextStyleSafe("button",
            binding.featuredCta,
            layout.featuredButtonFont,
            layout.featuredButtonWeight,
            layout.featuredButtonSizeSp,
            layout.featuredButtonTextColor)

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
     * v1.2 — Map an admin-edited font / weight / size / colour combo
     * onto a TextView.  Unknown values fall back to safe defaults so
     * the launcher never crashes on a typo in the admin form.
     *
     * IMPORTANT — we do NOT call `setTypeface(tf, BOLD)` because that
     * makes Android try to *find* a bold variant of the font and, if
     * none exists (Bebas Neue, Playfair Display Regular, etc.),
     * silently falls back to the SYSTEM default Sans Bold.  That
     * fallback was making Bebas Neue render as plain Sans on the TV
     * even though the admin saved `featured_heading_font=bebas_neue`.
     *
     * Instead we use the variable-font weight API on API 28+ which
     * lets Android render the actual font at the requested weight.
     * On older API levels we just apply the typeface as-is.
     */
    private fun applyTextStyle(
        view: android.widget.TextView,
        fontKey: String,
        weightKey: String,
        sizeSp: Int,
        colorHex: String,
    ) {
        val typeface = fontTypefaceFor(fontKey, weightKey)
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            // Typeface.create(family, weight, italic) — honours the
            // variable-font axis when the font supports it, and
            // renders at the closest available master weight when it
            // doesn't.  No silent system-font fallback.
            view.typeface = android.graphics.Typeface.create(typeface, weightToInt(weightKey), false)
        } else {
            // Pre-28: synthesise bold ONLY if we're staying on a font
            // that has a real bold variant (Montserrat).  For Bebas
            // and Playfair, leave style NORMAL so the original glyphs
            // shine through instead of system-fallback bold.
            val canBold = fontKey.equals("montserrat", true)
            val style = if (canBold && weightToInt(weightKey) >= 600) {
                android.graphics.Typeface.BOLD
            } else {
                android.graphics.Typeface.NORMAL
            }
            view.setTypeface(typeface, style)
        }
        view.textSize = sizeSp.toFloat()
        parseHexColorOrNull(colorHex)?.let { view.setTextColor(it) }
    }

    /** Returns the variable-font Typeface for [fontKey].  Falls back
     *  to Montserrat (default body) when [fontKey] is unrecognised. */
    private fun fontTypefaceFor(fontKey: String, weightKey: String): android.graphics.Typeface {
        val resId = when (fontKey.lowercase()) {
            "playfair", "playfair_display" -> R.font.playfair_display
            "bebas", "bebas_neue"          -> R.font.bebas_neue
            else                           -> R.font.montserrat
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
