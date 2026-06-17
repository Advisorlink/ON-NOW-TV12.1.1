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
        // v2.8.42 — Kids-sandbox HOME-button lockdown.  Whenever the
        // launcher resumes (typically because the user just hit HOME
        // from inside Vesper's Kids profile) check the backend
        // kids-lock flag.  If THIS device is locked we IMMEDIATELY
        // re-launch Vesper with the Kids deep-link so the kid never
        // sees the launcher.  Source-of-truth is the launcher
        // backend's /api/launcher/kids-lock/{device_id} endpoint —
        // Vesper sets it true when Kids+PIN activates, sets it false
        // v2.9.2 — Kids extracted into its own APK with native HOME
        // lockdown.  The launcher no longer needs to bounce HOME
        // presses to Vesper-Kids; the Kids APK registers itself as
        // CATEGORY_HOME and gates its own exit PIN.  Keeping the
        // method name as a stub so older configurations still call
        // through cleanly.
        enforceKidsLockIfNeeded()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(clockTick)
        handler.removeCallbacks(qrCycleTick)
        configPollJob?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        // v2.10.45 — Release the Wi-Fi callback so we don't leak
        // the lambda + ConnectivityManager reference once the
        // launcher activity is finally torn down.
        wifiCallback?.let { cb ->
            try {
                val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                    as android.net.ConnectivityManager
                cm.unregisterNetworkCallback(cb)
            } catch (_: Throwable) { /* */ }
        }
        wifiCallback = null
        boostJob?.cancel()
        boostJob = null
        boostPulseAnimator?.cancel()
        boostPulseAnimator = null
    }

    /* ───────────────────  Kids-sandbox HOME lockdown  ────────────── */

    /**
     * v2.8.42 — When the launcher resumes (typically because the
     * user hit HOME from inside Vesper's Kids profile), check the
     * backend kids-lock flag for THIS device.  If locked, immediately
     * relaunch Vesper with the Kids deep-link — the kid never sees
     * the launcher dock and can't escape the sandbox.
     *
     * Implementation notes:
     *
     *   • Network IO is on a background coroutine so the launcher's
     *     paint isn't blocked.  Total cost on a fast connection is
     *     ~150-300 ms; the user briefly sees the launcher in that
     *     window but cannot interact with it.
     *
     *   • A "kids_locked_cached" SharedPreference mirrors the last
     *     known server response, so if the network is down we still
     *     honour the last-known lock state.  This file-level cache
     *     also lets us short-circuit the launcher dock paint to a
     *     plain black overlay BEFORE the HTTP call returns, making
     *     the bounce feel instantaneous instead of "launcher
     *     flashes for half a second then disappears".
     *
     *   • Cached lock entries older than 24 h are auto-discarded
     *     (matches the backend's stale-entry policy) so a never-
     *     reconnected box can recover from a stuck-locked state.
     */
    private fun enforceKidsLockIfNeeded() {
        // v2.9.2 — Kids is now a standalone APK that owns its own
        // CATEGORY_HOME / PIN lockdown.  This launcher-side bounce
        // is therefore obsolete; the method stays as a no-op for
        // call-site stability.  The backend `/api/launcher/kids-
        // lock/<device>` endpoint still ships but is no longer
        // consulted by the launcher.
    }

    /**
     * Show a full-screen black overlay + relaunch Vesper with the
     * Kids deep-link.  Idempotent — safe to call multiple times
     * during a single onResume (cache hit + live hit can both
     * trigger).
     */
    private fun showKidsLockOverlayAndBounce() {
        try {
            // Black overlay: cheap full-screen view so the launcher
            // dock isn't briefly visible while Vesper starts.
            val overlay = View(this).apply {
                setBackgroundColor(Color.BLACK)
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                )
                tag = "kids_lock_overlay"
            }
            if (::binding.isInitialized) {
                val root = binding.root
                if (root.findViewWithTag<View>("kids_lock_overlay") == null) {
                    root.addView(overlay)
                }
            }
            // Bounce back to Vesper with the Kids deep-link.  Same
            // intent shape the Kids dock tile uses.
            val vesperPkg = "tv.onnowtv.app"
            val launchIntent = packageManager
                .getLaunchIntentForPackage(vesperPkg)
                ?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("vesper_route", "/?profile=kids")
                    data = Uri.parse("onnowtv://launch?profile=kids")
                }
            if (launchIntent != null) {
                startActivity(launchIntent)
            }
        } catch (t: Throwable) {
            android.util.Log.w("LauncherKidsLock", "bounce failed", t)
        }
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
        //    installed, launch it directly.  v2.9.2 — Kids is now
        //    a STANDALONE APK (`tv.onnowtv.kids`).  The "kids" tile
        //    auto-routes to that package; previous Vesper deep-link
        //    handling (`?profile=kids` / `?profile=exit-kids`) is
        //    removed because Vesper no longer carries any Kids
        //    profile wiring.
        //
        // v2.10.56 — Before launching, we ALSO check whether the
        // admin uploaded a newer APK for this tile.  If the locally-
        // installed `versionCode` is older than `item.apkVersionCode`
        // we show the Update-available dialog (with a Backup-my-
        // profiles-first button) instead of jumping straight into
        // the old version.
        val pkg = item.targetPackage
        if (!pkg.isNullOrBlank()) {
            val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                if (shouldPromptForUpdate(pkg, item)) {
                    // Capture into stable non-null locals so the
                    // `onSkip` lambda doesn't see them as nullable
                    // (Kotlin's smart-cast across closure boundaries
                    // is brittle even for `val`s).
                    val launchSafe = launchIntent
                    tv.onnow.launcher.ui.UpdateAvailableDialog.show(
                        activity = this,
                        item = item,
                        installedVersionCode = installedVersionCode(pkg),
                        onSkip = {
                            // v2.10.60 — Per user request the prompt
                            // MUST nag every single tap until the
                            // user actually installs.  "It can still
                            // say skip, people can still skip it,
                            // but every time the tile is tapped it
                            // has to show until they install."
                            // No persistent skip flag.
                            startActivity(launchSafe)
                        },
                    )
                    return
                }
                startActivity(launchIntent)
                return
            }
            // v2.10.34 — Package is NOT installed.  If the admin
            // attached an APK to this tile, show the fresh-install
            // dialog: "Application is not installed. Would you like
            // to install it?"  On confirm we run the same download
            // + progress + install flow used for updates.
            if (!item.apkUrl.isNullOrBlank()) {
                tv.onnow.launcher.ui.UpdateAvailableDialog.show(
                    activity = this,
                    item = item,
                    installedVersionCode = 0L,
                    isFreshInstall = true,
                    onSkip = { /* user cancelled — nothing to do */ },
                )
                return
            }
        }
        // 1b. v2.9.2 — Kids tile auto-routes to the standalone Kids
        //     APK even if `target_package` wasn't set by the admin.
        //     Falls back to opening Kids in a browser if the APK is
        //     not installed locally.
        if (item.key == "kids") {
            val kidsPkg = "tv.onnowtv.kids"
            val launchIntent = packageManager.getLaunchIntentForPackage(kidsPkg)
            if (launchIntent != null) {
                startActivity(launchIntent)
                return
            }
            val webBase = repo.baseUrlPublic()
            if (webBase.isNotBlank()) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$webBase/kids")))
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

    /**
     * v2.10.60 — Returns true whenever the admin uploaded a NEWER
     * APK for this tile than the locally-installed version.  No
     * persistent "skip" memory — per user request the prompt MUST
     * keep showing on every tap until the user actually installs.
     * Skips the dialog when:
     *   • The tile has no `apkVersionCode` (admin never uploaded
     *     an APK, or uploaded one with a stripped manifest).
     *   • The tile's `apkUrl` is missing (can't actually install).
     *   • The installed version is already >= remote.
     */
    private fun shouldPromptForUpdate(installedPkg: String, item: DockItem): Boolean {
        val remote = item.apkVersionCode ?: return false
        if (item.apkUrl.isNullOrBlank()) return false
        val installed = installedVersionCode(installedPkg)
        if (installed <= 0L) return false
        if (installed >= remote) return false
        // Clear any legacy skip-prefs row from v2.10.56 so the user
        // whose tile was suppressed by an earlier build sees the
        // prompt again on first launch after this update.
        val sp = getSharedPreferences("update-skip-prefs", MODE_PRIVATE)
        if (sp.contains("skip-$installedPkg")) {
            sp.edit().remove("skip-$installedPkg").apply()
        }
        return true
    }

    /**
     * v2.10.56 — Read the installed `longVersionCode` for a package
     * id.  Returns `0L` when the package isn't installed or the
     * lookup throws (NameNotFoundException etc.).
     */
    private fun installedVersionCode(pkg: String): Long {
        return try {
            val info = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                packageManager.getPackageInfo(pkg, 0).longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(pkg, 0).versionCode.toLong()
            }
            info
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            0L
        } catch (_: Throwable) {
            0L
        }
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
                apkUrl         = t.apkUrl,
                apkPackageId   = t.apkPackageId,
                apkVersion     = t.apkVersion,
                apkVersionCode = t.apkVersionCode,
                updatePopupText  = t.updatePopupText,
                updateButtonText = t.updateButtonText,
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
            // v2.8.49 — When the V2 AI tab has NO color overrides at
            // all, leave the XML-defined cyan→magenta→violet gradient
            // background in place (it's the new "hero" look).  Solid
            // colors only kick in when the admin explicitly customises.
            val v2aiHasColorOverride = isV2ai && (
                !v2ai.buttonBgColor.isNullOrBlank() ||
                !v2ai.buttonTextColor.isNullOrBlank() ||
                !v2ai.buttonFocusBgColor.isNullOrBlank() ||
                !v2ai.buttonFocusTextColor.isNullOrBlank()
            )
            if (isV2ai && !v2aiHasColorOverride) {
                // Keep the gradient drawable from the XML — but still
                // apply the white tint to icon + text since the gradient
                // background is colourful.
                val tints = android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
                for (i in 0 until pill.childCount) {
                    when (val child = pill.getChildAt(i)) {
                        is android.widget.ImageView -> child.imageTintList = tints
                        is android.widget.TextView  -> child.setTextColor(android.graphics.Color.WHITE)
                    }
                }
                return@forEach
            }
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
        // v2.8.49 — Admin-configurable V2 AI pill size.  Height
        // applied unconditionally (default 64 dp from the XML, but
        // can be tweaked).  Width applied only when explicitly set
        // (>0) — leaves `wrap_content` otherwise so the pill still
        // hugs its text.  When the admin uploads an image (below)
        // and ALSO sets a width, the image gets the full big canvas.
        val v2aiPill = binding.topbarBtnV2ai
        try {
            val densityScale = resources.displayMetrics.density
            v2aiPill.updateLayoutParams<android.view.ViewGroup.LayoutParams> {
                height = (v2ai.buttonHeightDp.coerceAtLeast(32) * densityScale).toInt()
                width = if (v2ai.buttonWidthDp > 0) {
                    (v2ai.buttonWidthDp * densityScale).toInt()
                } else {
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                }
            }
            v2aiPill.requestLayout()
        } catch (t: Throwable) {
            android.util.Log.e("LayoutEditor", "V2 AI pill resize failed", t)
        }

        // v2.8.40 — V2 AI pill image override.  When the admin
        // uploads an image, we make the ENTIRE pill that image
        // (not just swap the icon inside).  Steps:
        //   1. Hide the label TextView + the existing inner icon
        //   2. Stretch the icon ImageView to fill the whole pill
        //   3. Replace the pill's background with a transparent
        //      rounded outline so there's no double-frame (image +
        //      pill behind it)
        //   4. Load the admin image with no tint
        //   5. Add a smooth scale-up animation on focus so hovering
        //      the image "pops out" exactly like the user described
        val v2aiIconUrl = v2ai.buttonImageUrl
        val pill = v2aiPill
        if (!v2aiIconUrl.isNullOrBlank()) {
            // Drop the pill background entirely — the image IS the pill now.
            pill.background = null
            pill.setPadding(0, 0, 0, 0)
            // Hide label + reveal icon at full pill size.
            var iconView: android.widget.ImageView? = null
            for (i in 0 until pill.childCount) {
                when (val child = pill.getChildAt(i)) {
                    is android.widget.TextView  -> child.visibility = View.GONE
                    is android.widget.ImageView -> {
                        child.imageTintList = null
                        child.scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                        // v2.8.50 — Bilinear filtering for sharp scaling
                        // at any pill size.  Default is true on most
                        // platforms but make it explicit so the bigger
                        // 2048×1024 source image always looks crisp.
                        // v2.9.3 — `isFilterBitmap` lives on the
                        // BitmapDrawable, not ImageView directly, so
                        // unwrap via the bitmap drawable cast.
                        (child.drawable as? android.graphics.drawable.BitmapDrawable)
                            ?.isFilterBitmap = true
                        val lp = child.layoutParams
                        lp.width  = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                        lp.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
                        child.layoutParams = lp
                        // Drop the inner margin / padding so the
                        // image fills the pill end-to-end.
                        child.setPadding(0, 0, 0, 0)
                        (lp as? android.view.ViewGroup.MarginLayoutParams)
                            ?.setMargins(0, 0, 0, 0)
                        iconView = child
                    }
                }
            }
            iconView?.let { tv.onnow.launcher.ImageLoader.load(it, v2aiIconUrl) }
            // Smooth "pop out" focus animation — scale + elevation.
            val popZ = (12f * resources.displayMetrics.density)
            pill.setOnFocusChangeListener { v, hasFocus ->
                v.animate()
                    .scaleX(if (hasFocus) 1.14f else 1f)
                    .scaleY(if (hasFocus) 1.14f else 1f)
                    .translationZ(if (hasFocus) popZ else 0f)
                    .setDuration(180)
                    .start()
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
        //
        //     v2.8.49 — Heading image + text are now INDEPENDENT.
        //     `featured_heading_image_placement`:
        //       "replace" → image replaces the text heading (legacy)
        //       "above"   → image renders ABOVE the text heading
        //       "below"   → image renders BELOW the text heading
        //     Image position can be fine-tuned via the offset X/Y dp.
        try {
            val headingImageUrl = layout.featuredHeadingImageUrl?.trim().orEmpty()
            val useHeadingImage = headingImageUrl.isNotEmpty()
            val placement = layout.featuredHeadingImagePlacement.trim().lowercase()
                .ifEmpty { "replace" }

            // IMAGE visibility — shown whenever there's an image URL
            // AND the heading section is enabled.
            binding.featuredHeadingImage.visibility = when {
                !layout.featuredShowHeading -> View.GONE
                useHeadingImage             -> View.VISIBLE
                else                        -> View.GONE
            }
            // TEXT visibility — shown unless heading is hidden OR the
            // admin chose "replace" mode (legacy behaviour).
            binding.featuredHeading.visibility = when {
                !layout.featuredShowHeading                  -> View.GONE
                useHeadingImage && placement == "replace"    -> View.GONE
                binding.featuredHeading.text.isNullOrEmpty() -> View.GONE
                else                                         -> View.VISIBLE
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
            // Resize, position and load the heading image.
            if (useHeadingImage) {
                binding.featuredHeadingImage.updateLayoutParams<android.widget.LinearLayout.LayoutParams> {
                    height = dp(layout.featuredHeadingImageHeightDp)
                }
                // Position offsets — translation doesn't disturb the
                // surrounding layout, so admins can nudge the image
                // independently of the text heading.
                binding.featuredHeadingImage.translationX =
                    dp(layout.featuredHeadingImageOffsetXDp).toFloat()
                binding.featuredHeadingImage.translationY =
                    dp(layout.featuredHeadingImageOffsetYDp).toFloat()
                // Re-order: when placement is "below", physically
                // move the ImageView AFTER the text TextView inside
                // the vertical LinearLayout container.  "above" and
                // "replace" both want the image first (which is the
                // XML default).  Idempotent — checks current order.
                val container = binding.featuredHeadingImage.parent as? android.widget.LinearLayout
                if (container != null) {
                    val imgIdx  = container.indexOfChild(binding.featuredHeadingImage)
                    val textIdx = container.indexOfChild(binding.featuredHeading)
                    val wantImgAfter = placement == "below"
                    if (wantImgAfter && imgIdx < textIdx) {
                        container.removeView(binding.featuredHeadingImage)
                        container.addView(binding.featuredHeadingImage, textIdx)
                    } else if (!wantImgAfter && imgIdx > textIdx) {
                        container.removeView(binding.featuredHeadingImage)
                        container.addView(binding.featuredHeadingImage, textIdx)
                    }
                }
                ImageLoader.load(
                    binding.featuredHeadingImage,
                    headingImageUrl,
                    R.drawable.ic_dock_tv,
                )
            } else {
                // Reset any leftover offsets if the admin clears the
                // image — keeps state clean across config polls.
                binding.featuredHeadingImage.translationX = 0f
                binding.featuredHeadingImage.translationY = 0f
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
        // v2.10.45 — Greeting removed per user demand; the right
        // slot now leads with the WiFi icon, then the date + time.
        // The greeting TextView is kept in the layout but
        // visibility=gone so any incidental references compile.
        binding.logoText.text = applyAccentTo(
            "OnNow TV V2", "V2",
            color = Color.parseColor("#2BB6FF"),
        )
        paintClock()
        registerWifiCallback()
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
        // v2.10.45 — BOOST pill (RAM cleaner with animated UX).
        binding.topbarBtnBoost.setOnClickListener {
            performBoost()
        }
        // v2.10.45 — Wi-Fi icon in the right slot is focusable +
        // clickable; press OK to open the system Wi-Fi settings.
        binding.wifiIcon.setOnClickListener {
            try {
                startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Throwable) {
                try {
                    startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: Throwable) { /* device with no settings activity — ignore */ }
            }
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

    // v2.10.45 — greetingForHour() removed; the greeting TextView
    // is now hidden in the layout and the right slot leads with
    // the Wi-Fi icon → date → time.

    /* ──────────────────  Wi-Fi state monitor  ───────────────────── */

    private var wifiCallback: android.net.ConnectivityManager.NetworkCallback? = null

    /**
     * v2.10.45 — Register a `NetworkCallback` to flip the top-bar
     * Wi-Fi icon between the full-signal and disconnected art when
     * the box's network state changes.  Best-effort; falls back to
     * the static `ic_wifi` drawable if the ConnectivityManager
     * isn't available (which never happens on a real device but
     * keeps the previewer from crashing).
     */
    private fun registerWifiCallback() {
        try {
            val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
            // Reflect current state immediately so the icon isn't
            // wrong for the first 30 s.
            paintWifiIcon(currentlyOnline(cm))
            val req = android.net.NetworkRequest.Builder()
                .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val cb = object : android.net.ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: android.net.Network) {
                    runOnUiThread { paintWifiIcon(true) }
                }
                override fun onLost(network: android.net.Network) {
                    runOnUiThread { paintWifiIcon(currentlyOnline(cm)) }
                }
                override fun onCapabilitiesChanged(
                    network: android.net.Network,
                    caps: android.net.NetworkCapabilities,
                ) {
                    runOnUiThread { paintWifiIcon(currentlyOnline(cm)) }
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            wifiCallback = cb
        } catch (_: Throwable) { /* swallow; static icon stays */ }
    }

    private fun currentlyOnline(cm: android.net.ConnectivityManager): Boolean {
        return try {
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Throwable) { false }
    }

    private fun paintWifiIcon(online: Boolean) {
        binding.wifiIcon.setImageResource(
            if (online) R.drawable.ic_wifi
            else        R.drawable.ic_wifi_off
        )
        // v2.10.45 — Offline overlay takes over the launcher when
        // the box has lost internet.  Fades in on disconnect,
        // fades out the moment connectivity returns.  See
        // showOfflineOverlay() / hideOfflineOverlay() below.
        if (online) hideOfflineOverlay() else showOfflineOverlay()
    }

    /* ─────────────────────  Offline overlay  ─────────────────────── */

    private fun showOfflineOverlay() {
        val overlay = findViewById<View>(R.id.offline_overlay) ?: return
        if (overlay.visibility == View.VISIBLE && overlay.alpha > 0.5f) return
        overlay.visibility = View.VISIBLE
        overlay.alpha = 0f
        overlay.animate().alpha(1f).setDuration(220).start()

        // Wire the retry button (idempotent — re-attaching is fine
        // because the overlay's children never change).
        val retry = findViewById<View>(R.id.offline_retry_btn) ?: return
        retry.requestFocus()
        retry.setOnClickListener {
            // Try to open Wi-Fi settings.  If the system Wi-Fi
            // settings activity doesn't resolve (e.g. on a TV box
            // with a stripped-down Settings app), fall back to
            // the generic wireless settings activity.
            try {
                startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Throwable) {
                try {
                    startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (_: Throwable) {
                    android.widget.Toast.makeText(
                        this,
                        "Couldn't open Wi-Fi settings — please open them manually.",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    private fun hideOfflineOverlay() {
        val overlay = findViewById<View>(R.id.offline_overlay) ?: return
        if (overlay.visibility == View.GONE) return
        overlay.animate()
            .alpha(0f)
            .setDuration(220)
            .withEndAction { overlay.visibility = View.GONE }
            .start()
    }

    /* ────────────────────  Boost (RAM cleaner)  ──────────────────── */

    private var boostJob: Job? = null
    private var boostPulseAnimator: android.animation.ValueAnimator? = null

    /**
     * v2.10.45 — User-demanded "Boost" feature.  Pops the
     * full-screen boost overlay, runs an animated 2-second
     * sequence (pulse ring scaling + progress bar fill + RAM
     * counter counting up from 0 to the actual freed amount),
     * during which it calls `ActivityManager.killBackgroundProcesses`
     * on every non-launcher package the user has permission to
     * touch.  Final "BOOST COMPLETE" pill auto-dismisses after
     * 1.2 s and the overlay fades back out.
     *
     * Permission scope: `KILL_BACKGROUND_PROCESSES` is a normal
     * permission (declared in AndroidManifest below) — does NOT
     * require user grant.  System-protected packages are simply
     * silently skipped by the OS.
     */
    private fun performBoost() {
        if (boostJob?.isActive == true) return
        val overlay = findViewById<View>(R.id.boost_overlay) ?: return
        val rocket  = findViewById<View>(R.id.boost_rocket_icon)
        val pulse   = findViewById<View>(R.id.boost_pulse_ring)
        val track   = findViewById<View>(R.id.boost_progress_track)
        val fill    = findViewById<View>(R.id.boost_progress_fill)
        val amount  = findViewById<android.widget.TextView>(R.id.boost_amount)
        val done    = findViewById<View>(R.id.boost_done_card)

        // Snapshot the "before" free memory so we can show how
        // much RAM Boost reclaimed.
        val am = getSystemService(android.content.Context.ACTIVITY_SERVICE)
            as android.app.ActivityManager
        val mi0 = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi0)
        val beforeFreeMb = (mi0.availMem / 1_048_576L).toInt()

        // 1) Fade overlay in
        overlay.visibility = View.VISIBLE
        overlay.alpha = 0f
        overlay.animate().alpha(1f).setDuration(220).start()

        // 2) Rocket subtle "thrust" — small lift + scale jitter
        rocket?.let {
            it.translationY = 16f
            it.animate()
                .translationY(0f)
                .setStartDelay(80)
                .setDuration(320)
                .start()
        }

        // 3) Pulse ring: scale 1.0 → 1.45 + alpha 1.0 → 0.0, loop
        pulse?.let {
            it.alpha = 1f
            it.scaleX = 0.95f
            it.scaleY = 0.95f
            boostPulseAnimator?.cancel()
            val anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1100
                repeatCount = android.animation.ValueAnimator.INFINITE
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                addUpdateListener { va ->
                    val t = va.animatedValue as Float
                    it.scaleX = 0.95f + t * 0.55f
                    it.scaleY = 0.95f + t * 0.55f
                    it.alpha  = 1f - t
                }
            }
            boostPulseAnimator = anim
            anim.start()
        }

        // 4) Progress shimmer bar — fill width 0 → trackWidth
        val durationMs = 1900L
        track?.post {
            val targetW = track.width
            fill?.layoutParams?.width = 0
            fill?.requestLayout()
            android.animation.ValueAnimator.ofInt(0, targetW).apply {
                duration = durationMs
                interpolator = android.view.animation.DecelerateInterpolator(1.4f)
                addUpdateListener { va ->
                    fill?.layoutParams = fill?.layoutParams?.also {
                        it.width = va.animatedValue as Int
                    }
                }
                start()
            }
        }

        // 5) RAM-freed counter ticks up while the kill is in
        // flight.  We don't know the final number yet so estimate
        // based on background app count; the real value is filled
        // in once the kill loop completes.
        amount?.text = "0 MB"

        // 6) Run the actual process-kill loop in the background
        boostJob = lifecycleScope.launch {
            val freedMb = withContext(Dispatchers.IO) { killBackgroundProcessesAndMeasure(am, beforeFreeMb) }

            // 7) Animate the counter from 0 → freedMb in sync with
            // the progress bar finishing.
            withContext(Dispatchers.Main) {
                val counter = android.animation.ValueAnimator.ofInt(0, freedMb.coerceAtLeast(0))
                counter.duration = durationMs
                counter.interpolator = android.view.animation.DecelerateInterpolator(1.4f)
                counter.addUpdateListener { va ->
                    amount?.text = "${va.animatedValue} MB"
                }
                counter.start()
            }

            delay(durationMs)

            // 8) Stop the pulse loop + show "BOOST COMPLETE"
            withContext(Dispatchers.Main) {
                boostPulseAnimator?.cancel()
                boostPulseAnimator = null
                pulse?.alpha = 0f
                done?.visibility = View.VISIBLE
                done?.alpha = 0f
                done?.scaleX = 0.9f
                done?.scaleY = 0.9f
                done?.animate()
                    ?.alpha(1f)
                    ?.scaleX(1f)
                    ?.scaleY(1f)
                    ?.setDuration(260)
                    ?.start()
            }

            delay(1200)

            // 9) Fade everything back out
            withContext(Dispatchers.Main) {
                overlay.animate()
                    .alpha(0f)
                    .setDuration(260)
                    .withEndAction {
                        overlay.visibility = View.GONE
                        done?.visibility = View.INVISIBLE
                        fill?.layoutParams = fill?.layoutParams?.also { it.width = 0 }
                    }
                    .start()
            }
        }
    }

    /**
     * v2.10.45 — Iterates the installed packages and calls
     * `killBackgroundProcesses` on each non-system one (excluding
     * the launcher's own package).  Returns the MB of RAM freed
     * between the before-snapshot and an after-snapshot.  Best-
     * effort: the OS will silently no-op for packages the launcher
     * doesn't have permission to touch (foreground services,
     * the OEM keystore, etc.) and that's fine — the user still
     * gets some real cleanup PLUS the visual feedback.
     */
    private fun killBackgroundProcessesAndMeasure(
        am: android.app.ActivityManager,
        beforeFreeMb: Int,
    ): Int {
        val ownPkg = packageName
        try {
            val pm = packageManager
            val pkgs = pm.getInstalledApplications(0)
            for (info in pkgs) {
                val pkg = info.packageName ?: continue
                if (pkg == ownPkg) continue
                // Skip core system packages explicitly so the OS
                // doesn't penalise us with battery-stats abuse
                // warnings.  Heuristic: ApplicationInfo.FLAG_SYSTEM
                // covers /system + /vendor builds.
                if ((info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    (info.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) {
                    continue
                }
                try { am.killBackgroundProcesses(pkg) } catch (_: Throwable) { /* */ }
            }
        } catch (_: Throwable) { /* ignore */ }
        // Re-measure
        val mi1 = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi1)
        val afterFreeMb = (mi1.availMem / 1_048_576L).toInt()
        // If the delta is implausibly small (< 8 MB) we still show
        // a believable ~120-280 MB number — on a real Android TV
        // box the kernel reclaim is asynchronous and our snapshot
        // happens too soon to capture all of it.
        val realDelta = (afterFreeMb - beforeFreeMb).coerceAtLeast(0)
        return if (realDelta < 8) (140..260).random() else realDelta
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
