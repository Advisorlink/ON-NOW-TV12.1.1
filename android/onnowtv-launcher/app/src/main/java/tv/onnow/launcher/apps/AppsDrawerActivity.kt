package tv.onnow.launcher.apps

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import tv.onnow.launcher.ImageLoader
import tv.onnow.launcher.MainActivity
import tv.onnow.launcher.R
import tv.onnow.launcher.data.ApkEntryRemote
import tv.onnow.launcher.data.AppStoreMeta
import tv.onnow.launcher.data.LauncherRepository
import tv.onnow.launcher.install.ApkInstaller

/**
 * AppsDrawerActivity — v2.0 ON NOW TV 2 App Store.
 *
 * Matches the user-supplied mockup:
 *   • Top hero image (admin-uploadable via /api/admin/appstore/hero).
 *   • 6-up tile grid below.
 *   • Each tile shows ONLY: rounded icon, app name, category.
 *     NO package id, NO version, NO star rating.
 *   • Status-aware action button at the bottom of each tile:
 *       – NOT installed → blue "Install" — tap to install.
 *       – Installed     → red "Uninstall" — single tap fires the
 *                          system uninstaller directly.  An
 *                          "INSTALLED" pill badge in the icon's
 *                          top-right corner shows the state at a
 *                          glance.  After the uninstall completes,
 *                          the tile flips straight back to "Install".
 *   • Install state refreshes on every `onResume()` so after a
 *     user installs/uninstalls and returns, the tile updates.
 *
 * Pure programmatic UI (no XML) so the palette stays in lockstep
 * with the rest of the launcher.
 */
class AppsDrawerActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repo: LauncherRepository
    private lateinit var grid: RecyclerView
    private lateinit var emptyHint: TextView
    private lateinit var backgroundImage: ImageView
    private var loadJob: Job? = null
    private var currentApks: List<ApkEntryRemote> = emptyList()
    private var adapter: AppsAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = LauncherRepository(applicationContext)

        /* ── Root: deep inky background + subtle radial glow ── */
        val root = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.onb_bg_glow)
        }

        /* v2.8.10 — Optional fullscreen wallpaper behind the apps
           grid (admin-uploadable via /api/admin/appstore/background).
           Sits AT the root level (lowest z-order) so the ScrollView
           content floats over it.  scaleType=CENTER_CROP because the
           backend already auto-fits to the exact 1920×1080 target
           — CENTER_CROP just hides any sub-pixel rounding on odd
           density boxes.
           v2.8.16 — Per direct user spec: NO scrim, NO overlay,
           full-brightness background.  The admin handles fade/
           tint inside the image they upload. */
        backgroundImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            visibility = View.GONE
        }
        root.addView(backgroundImage, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        /* v2.8.16 — Per direct user spec: the hero banner has been
           REMOVED entirely.  Only the fullscreen 1920×1080 background
           wallpaper remains as the admin's customisable image.  The
           apps grid sits in the same padded column it was always in. */
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(40), dp(48), dp(48))
        }

        /* ── 2. Section eyebrow + title ──
           v2.8.16 — Hero banner removed, but per direct user spec
           the eyebrow / title / app tiles must stay at their
           ORIGINAL absolute Y-positions on screen.  The hero used
           to occupy heroHeight=dp(260) + dp(28) bottom margin =
           dp(288) of vertical space.  We restore that as a top
           spacer so the rest of the layout doesn't shift up.  The
           user's 1920×1080 wallpaper now occupies that empty
           region directly behind the icons. */
        column.addView(spacer(dp(288)))

        val eyebrow = TextView(this).apply {
            text = "DISCOVER"
            textSize = 11f
            letterSpacing = 0.32f
            setTextColor(Color.parseColor("#FF5DC8FF"))
            typeface = Typeface.MONOSPACE
        }
        column.addView(eyebrow)
        column.addView(spacer(dp(8)))

        val title = TextView(this).apply {
            text = "ON NOW TV 2 · App Store"
            textSize = 28f
            typeface = makeFont(700)
            setTextColor(Color.parseColor("#FFF4F7FB"))
            letterSpacing = -0.005f
        }
        column.addView(title)
        column.addView(spacer(dp(20)))

        /* ── 3. Empty state ── */
        emptyHint = TextView(this).apply {
            text = "No apps in the store yet.  Drop an APK into the admin and it'll show up here within 30 seconds."
            textSize = 16f
            setTextColor(Color.parseColor("#FF5D6E85"))
            visibility = View.GONE
            setPadding(0, dp(24), 0, 0)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        column.addView(emptyHint)

        /* ── 4. Apps grid ── */
        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@AppsDrawerActivity, 6)
            clipToPadding = false
            clipChildren = false
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            isNestedScrollingEnabled = false
            // Big bottom inset so the elevation/scale-up halo on the
            // last row doesn't clip against the screen edge.
            setPadding(0, 0, 0, dp(32))
            // v2.8.17 — Disable the default item animator.  Its
            // 250ms fade on every notifyItemChanged() compounds
            // the install-progress flicker (a fresh fade fires
            // for every percent tick).  Combined with the new
            // PROGRESS_PAYLOAD partial-update path in the adapter
            // this gives a perfectly smooth installer UI.
            itemAnimator = null
        }
        column.addView(grid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        scroll.addView(column, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        root.addView(scroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        // v2.10.55 — HOME UPDATE pill, top-right corner.
        // Renders when the operator has pinned a launcher APK in
        // the admin UI's "Home Update" section.  Tap → confirm →
        // download the pinned APK via ApkInstaller → fires the
        // system install prompt → in-place upgrade (same keystore =
        // no data loss, no parsing error).
        homeUpdatePill = buildHomeUpdatePill().also {
            it.visibility = View.GONE
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.gravity = Gravity.TOP or Gravity.END
            lp.topMargin = dp(40)
            lp.rightMargin = dp(56)
            root.addView(it, lp)
        }

        setContentView(root)

        // v2.10.71 — Hidden 5-second long-press in the top-left
        // corner opens the BULK INSTALL surface (download + install
        // every pinned APK in one queued sweep — for fresh-box
        // setups).  Invisible until ~2s of holding, then a small
        // progress ring fades in so you know it's registering.  At
        // 5s: short audible click + launch BulkInstallActivity.
        installBulkInstallGesture(root)

        loadAndRender()
        refreshHomeUpdatePill()
    }

    /* v2.10.71 — Hidden BULK-INSTALL gesture.  See the comment at
     * the call site in onCreate.  Touch lifecycle:
     *
     *   t=0     finger goes down somewhere in the top-left 120×120dp
     *           zone.  We start the 5-second timer.
     *   t=2s    a small accent-coloured progress ring fades in at
     *           the corner, growing as the hold continues.  Gives
     *           the operator a visual cue that the gesture is
     *           registering (per direct user request).
     *   t=5s    quick MediaActionSound click + the ring snaps to
     *           full + launch BulkInstallActivity.  Resets state.
     *
     *   Finger lifted before 5s → cancel timer, hide the ring.
     *
     *   The zone view itself is invisible and NOT focusable, so the
     *   D-pad keeps falling through to the App-store grid behind it.
     *   The gesture only fires via a physical touch (TV remotes
     *   without a touch sensor never see it — exactly the kind of
     *   "developer-only" gate the user wanted).
     */
    private fun installBulkInstallGesture(root: FrameLayout) {
        val zone = View(this).apply {
            isClickable = false
            isFocusable = false
            isFocusableInTouchMode = false
            background = null
        }
        val lp = FrameLayout.LayoutParams(dp(120), dp(120)).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        root.addView(zone, lp)

        // The visual progress ring (hidden until ~2s into the hold).
        val ring = View(this).apply {
            visibility = View.GONE
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#33FFD24A"))
                setStroke(dp(2), Color.parseColor("#88FFD24A"))
            }
        }
        val ringLp = FrameLayout.LayoutParams(dp(36), dp(36)).apply {
            gravity = Gravity.TOP or Gravity.START
            topMargin = dp(20)
            leftMargin = dp(20)
        }
        root.addView(ring, ringLp)

        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var showRunnable: Runnable? = null
        var fireRunnable: Runnable? = null
        var growAnimator: android.animation.ValueAnimator? = null

        fun cancelAll() {
            showRunnable?.let { handler.removeCallbacks(it) }
            fireRunnable?.let { handler.removeCallbacks(it) }
            growAnimator?.cancel()
            ring.visibility = View.GONE
            ring.scaleX = 1f
            ring.scaleY = 1f
            ring.alpha = 1f
        }

        zone.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    cancelAll()
                    // After 2s of holding: reveal the ring + start
                    // a 3-second grow animation toward "full" so the
                    // user sees their progress.
                    showRunnable = Runnable {
                        ring.alpha = 0f
                        ring.scaleX = 0.6f
                        ring.scaleY = 0.6f
                        ring.visibility = View.VISIBLE
                        growAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                            duration = 3000L
                            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                            addUpdateListener { a ->
                                val t = a.animatedValue as Float
                                ring.alpha = 0.55f + 0.45f * t
                                val s = 0.6f + 0.7f * t
                                ring.scaleX = s
                                ring.scaleY = s
                            }
                            start()
                        }
                    }.also { handler.postDelayed(it, 2000L) }

                    // After 5s of holding: audible click + open
                    // BulkInstallActivity.
                    fireRunnable = Runnable {
                        try {
                            val click = android.media.MediaActionSound()
                            click.play(android.media.MediaActionSound.FOCUS_COMPLETE)
                        } catch (_: Throwable) { /* best-effort */ }
                        cancelAll()
                        startActivity(Intent(
                            this@AppsDrawerActivity,
                            tv.onnow.launcher.install.BulkInstallActivity::class.java,
                        ))
                    }.also { handler.postDelayed(it, 5000L) }
                    true
                }
                android.view.MotionEvent.ACTION_UP,
                android.view.MotionEvent.ACTION_CANCEL -> {
                    cancelAll()
                    true
                }
                else -> false
            }
        }
    }



    /** Top-right pill — visible only when an admin-pinned launcher
     *  update is available on the backend. */
    private var homeUpdatePill: View? = null
    private var homeUpdateLabel: TextView? = null
    private var homeUpdateInfo: JSONObject? = null

    private fun buildHomeUpdatePill(): View {
        val pill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(10), dp(22), dp(10))
            background = makeHomeUpdatePillBg()
            isClickable = true
            isFocusable = true
            elevation = dp(4).toFloat()
        }
        val icon = TextView(this).apply {
            text = "↻"
            textSize = 18f
            setTextColor(Color.WHITE)
            typeface = makeFont(700)
            setPadding(0, 0, dp(8), 0)
        }
        pill.addView(icon)

        val label = TextView(this).apply {
            text = "HOME UPDATE"
            textSize = 13f
            letterSpacing = 0.14f
            setTextColor(Color.WHITE)
            typeface = makeFont(700)
            setShadowLayer(4f, 0f, 2f, Color.parseColor("#A6000814"))
        }
        homeUpdateLabel = label
        pill.addView(label)

        pill.setOnClickListener { onHomeUpdatePillClicked() }
        return pill
    }

    private fun makeHomeUpdatePillBg(): android.graphics.drawable.Drawable {
        // Same cool cyan→blue→indigo gradient as the topbar Boost
        // pill — keeps the visual language consistent.
        val shape = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
            intArrayOf(
                Color.parseColor("#FF06B6D4"),
                Color.parseColor("#FF2563EB"),
                Color.parseColor("#FF4F46E5"),
            ),
        )
        shape.cornerRadius = dp(9999).toFloat()
        return shape
    }

    private fun refreshHomeUpdatePill() {
        lifecycleScope.launch {
            val info = fetchHomeUpdateInfo()
            homeUpdateInfo = info
            val show = info != null && info.optBoolean("has_update", false)
            homeUpdatePill?.visibility = if (show) View.VISIBLE else View.GONE
            if (show) {
                val ver = info?.optString("version_name").orEmpty()
                homeUpdateLabel?.text =
                    if (ver.isNotEmpty()) "HOME UPDATE · $ver" else "HOME UPDATE"
            }
        }
    }

    private suspend fun fetchHomeUpdateInfo(): JSONObject? =
        withContext(Dispatchers.IO) {
            try {
                val repo = LauncherRepository(this@AppsDrawerActivity)
                val cur = packageManager.getPackageInfo(packageName, 0)
                val vc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                    cur.longVersionCode else @Suppress("DEPRECATION") cur.versionCode.toLong()
                // v2.10.81 — Also send the build_id of the last
                // self-update we installed.  The backend compares
                // against the pinned `build_id` (UUID minted on every
                // admin re-upload) so re-pushing the same versionName
                // STILL fires has_update on this box.
                val cachedBuildId = MainActivity.readInstalledBuildId(
                    this@AppsDrawerActivity, packageName,
                )
                val buildQs = if (cachedBuildId.isNotEmpty())
                    "&current_build_id=$cachedBuildId" else ""
                val url = repo.baseUrlPublic().trimEnd('/') +
                    "/api/launcher/home-update/info?current_version_code=$vc$buildQs"
                val req = okhttp3.Request.Builder().url(url).get().build()
                tv.onnow.launcher.net.ResilientHttp.client.newCall(req).execute().use { r ->
                    val body = r.body?.string() ?: return@withContext null
                    JSONObject(body)
                }
            } catch (_: Throwable) {
                null
            }
        }

    private fun onHomeUpdatePillClicked() {
        val info = homeUpdateInfo ?: run {
            android.widget.Toast.makeText(
                this, "No home update info available yet — try again in a moment.",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            return
        }
        val ver = info.optString("version_name", "(unknown)")
        val sizeMb = (info.optLong("size", 0L) / (1024.0 * 1024.0))
        val msg = buildString {
            append("Install launcher update $ver?\n\n")
            append("• Size: ${"%.1f".format(sizeMb)} MB\n")
            append("• Installs in-place — your registration, dock and settings are preserved.\n")
            append("• The launcher will briefly close to complete installation.")
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Home Update")
            .setMessage(msg)
            .setPositiveButton("Install") { _, _ -> downloadAndInstallHomeUpdate(info) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun downloadAndInstallHomeUpdate(info: JSONObject) {
        if (!ApkInstaller.canInstallNow(this)) {
            android.widget.Toast.makeText(
                this,
                "Grant 'Install unknown apps' to ON NOW TV V2 in the Settings page that just opened.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
            ApkInstaller.requestInstallPermission(this)
            return
        }
        val apkUrl = info.optString("apk_url")
        if (apkUrl.isEmpty()) {
            android.widget.Toast.makeText(
                this, "No download URL — please re-upload in the admin.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
            return
        }
        val pinnedBuildId = info.optString("build_id").orEmpty()
        val pinnedVer = info.optString("version_name", "").trim()
        val titleVer = if (pinnedVer.isNotEmpty()) "v$pinnedVer" else "launcher"

        // v2.10.81 — Centred blue InstallProgressDialog, matching the
        // per-tile install UX (v2.10.75).  Stays up through download
        // and gracefully hands off to the system installer.  Replaces
        // the old Toast that vanished mid-download.
        val dialog = tv.onnow.launcher.install.InstallProgressDialog.show(
            this,
            "Updating $titleVer",
            "Downloading the latest launcher build from the server…",
        )
        lifecycleScope.launch {
            val err = ApkInstaller.downloadAndInstall(
                this@AppsDrawerActivity,
                apkUrl,
                suggestedName = "home-update.apk",
                onProgress = { pct ->
                    runOnUiThread { dialog.setProgress(pct) }
                },
            )
            if (err != null) {
                runOnUiThread {
                    dialog.dismiss()
                    android.widget.Toast.makeText(
                        this@AppsDrawerActivity,
                        "Home update failed: $err",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
                return@launch
            }
            // v2.10.81 — Save the freshly-installed build_id so the
            // next /api/launcher/home-update/info poll reports
            // has_update=false until the operator re-uploads.
            if (pinnedBuildId.isNotEmpty()) {
                MainActivity.writeInstalledBuildId(
                    this@AppsDrawerActivity, packageName, pinnedBuildId,
                )
            }
            // Hand off to the system installer UI.
            runOnUiThread {
                dialog.setTitle("Installing $titleVer")
                dialog.setMessage("Opening the system installer…")
                dialog.setProgress(100)
            }
            android.os.Handler(mainLooper).postDelayed({ dialog.dismiss() }, 1200L)
            // On success, ApkInstaller has fired the system install
            // prompt — the user will see the standard Android update
            // dialog.  No further action needed from this activity.
        }
    }

    private var uninstallReceiver: android.content.BroadcastReceiver? = null

    override fun onResume() {
        super.onResume()
        // After returning from Android's package-installer / uninstaller
        // we need to re-evaluate which tiles say "Install" vs
        // "Installed" — the user may have just toggled one.
        adapter?.notifyDataSetChanged()
    }

    override fun onStart() {
        super.onStart()
        // v2.8.5 — Listen for our `PackageInstaller.uninstall()`
        // callback so we can refresh tiles the instant the system
        // confirms the operation (instead of waiting for the user
        // to come back to this Activity, which can be slow on TV
        // remotes).
        uninstallReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, intent: Intent) {
                val status = intent.getIntExtra(
                    android.content.pm.PackageInstaller.EXTRA_STATUS, -1,
                )
                when (status) {
                    android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        // System needs the user to confirm via UI.
                        val confirm: Intent? =
                            intent.getParcelableExtra(Intent.EXTRA_INTENT)
                        if (confirm != null) {
                            try {
                                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                startActivity(confirm)
                            } catch (_: Throwable) {}
                        }
                    }
                    android.content.pm.PackageInstaller.STATUS_SUCCESS -> {
                        adapter?.notifyDataSetChanged()
                    }
                    else -> { /* failure → onResume will re-sync */ }
                }
            }
        }
        val filter = android.content.IntentFilter("tv.onnow.launcher.UNINSTALL_RESULT")
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(uninstallReceiver, filter,
                android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(uninstallReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        try { uninstallReceiver?.let { unregisterReceiver(it) } }
        catch (_: Throwable) {}
        uninstallReceiver = null
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext[Job]?.cancel()
    }

    private fun loadAndRender() {
        val cached = repo.config.value ?: repo.loadCached()
        if (cached != null) {
            renderApks(cached.apks, cached.appstore)
            renderBackground(cached.appstore.backgroundImageUrl)
        }
        loadJob?.cancel()
        loadJob = scope.launch {
            val fresh = repo.refresh()
            if (fresh != null) {
                renderApks(fresh.apks, fresh.appstore)
                renderBackground(fresh.appstore.backgroundImageUrl)
            }
        }
    }

    private fun renderBackground(url: String?) {
        if (url.isNullOrBlank()) {
            backgroundImage.visibility = View.GONE
            return
        }
        backgroundImage.visibility = View.VISIBLE
        ImageLoader.load(backgroundImage, url)
    }

    private fun renderApks(apks: List<ApkEntryRemote>, appstore: AppStoreMeta) {
        currentApks = apks
        if (apks.isEmpty()) {
            emptyHint.visibility = View.VISIBLE
            grid.adapter = null
            adapter = null
            return
        }
        emptyHint.visibility = View.GONE
        // v2.8.18 — Pass admin-editable tile colors (with sensible
        // defaults) into the adapter so the user can recolor the
        // App Store palette from the admin without rebuilding.
        val a = AppsAdapter(
            ctx = this,
            apks = apks,
            tileBgColor   = parseColorOrDefault(appstore.tileBgColor,   "#CC0F1B30"),
            tileTextColor = parseColorOrDefault(appstore.tileTextColor, "#FFF4F7FB"),
            isInstalled = { pkg -> pkg != null && isPackageInstalled(pkg) },
            onInstall   = { entry -> installApk(entry) },
            onUninstall = { entry -> uninstallApk(entry) },
        )
        adapter = a
        grid.adapter = a
        grid.post {
            (grid.findViewHolderForAdapterPosition(0)?.itemView)?.requestFocus()
        }
    }

    private fun parseColorOrDefault(hex: String?, fallback: String): Int =
        try { Color.parseColor(hex?.takeIf { it.isNotBlank() } ?: fallback) }
        catch (_: Throwable) { Color.parseColor(fallback) }

    /* ── Install / uninstall ── */

    /** True if the given package id is currently installed. */
    private fun isPackageInstalled(packageId: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageId, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun installApk(entry: ApkEntryRemote) {
        if (!ApkInstaller.canInstallNow(this)) {
            ApkInstaller.requestInstallPermission(this)
            adapter?.markInstallFinished(entry.id)
            return
        }
        scope.launch {
            // v2.8.3 — Throttle progress callbacks to main thread.
            // `ApkInstaller` calls onProgress on every 64 KB read
            // from a background dispatcher; for a 50 MB APK that's
            // ~800 callbacks.  We snap to integer % and post each
            // change exactly once to the main thread, so the
            // RecyclerView re-renders at most ~100 times.
            var lastPct = -1
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            val err = ApkInstaller.downloadAndInstall(
                ctx = applicationContext,
                apkUrl = entry.apkUrl,
                suggestedName = "${entry.name}.apk",
                onProgress = { pct ->
                    if (pct != lastPct) {
                        lastPct = pct
                        mainHandler.post {
                            adapter?.setInstallProgress(entry.id, pct)
                        }
                    }
                },
            )
            mainHandler.post {
                adapter?.markInstallFinished(entry.id)
                if (err != null) {
                    android.widget.Toast.makeText(
                        this@AppsDrawerActivity,
                        "Install failed: $err",
                        android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    /** Fire Android's uninstaller for the package.  v2.8.5 — Uses
     *  the modern `PackageInstaller.uninstall()` API as the primary
     *  path (works reliably on Android 11+), with three legacy
     *  intent fallbacks for older boxes:
     *
     *    1. `PackageInstaller.uninstall(pkg, sender)` — Android 5+
     *       (post-API-21 it just shows the system confirm sheet,
     *       SAME as ACTION_UNINSTALL_PACKAGE, but the call routes
     *       through the platform service instead of the Activity
     *       resolver, so package-visibility / launcher whitelist
     *       restrictions on the Activity side don't apply).
     *    2. `ACTION_UNINSTALL_PACKAGE` (deprecated but works on
     *       most 6-10 boxes).
     *    3. `ACTION_DELETE` (legacy alias).
     *    4. `ACTION_APPLICATION_DETAILS_SETTINGS` — opens the
     *       Apps detail page, user taps Uninstall from there.
     *
     *  Requires `REQUEST_DELETE_PACKAGES` in the manifest
     *  (added in v2.8.5).
     */
    private fun uninstallApk(entry: ApkEntryRemote) {
        val pkg = entry.packageId?.takeIf { it.isNotBlank() }
        if (pkg == null) {
            android.widget.Toast.makeText(
                this, "Package id missing — set it in the admin.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
            return
        }
        // 1) PackageInstaller.uninstall() — preferred path.
        try {
            val pi = packageManager.packageInstaller
            val callback = Intent("tv.onnow.launcher.UNINSTALL_RESULT").apply {
                setPackage(packageName)
            }
            val flags = if (android.os.Build.VERSION.SDK_INT >= 31) {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                android.app.PendingIntent.FLAG_MUTABLE
            } else {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
            }
            val sender = android.app.PendingIntent.getBroadcast(
                this, pkg.hashCode(), callback, flags,
            ).intentSender
            pi.uninstall(pkg, sender)
            return
        } catch (t: Throwable) {
            android.util.Log.w("AppsDrawer", "PackageInstaller.uninstall failed: ${t.message}")
        }

        // 2-4) Legacy intent fallbacks.
        val uri = Uri.parse("package:$pkg")
        val attempts = listOf(
            { Intent(Intent.ACTION_UNINSTALL_PACKAGE).apply { data = uri } },
            { Intent(Intent.ACTION_DELETE).apply { data = uri } },
            { Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply { data = uri } },
        )
        for (build in attempts) {
            try {
                val i = build().apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                if (i.resolveActivity(packageManager) != null) {
                    startActivity(i)
                    return
                }
            } catch (_: Throwable) { /* try next */ }
        }
        android.widget.Toast.makeText(
            this,
            "Couldn't open uninstaller for $pkg — Android refused.",
            android.widget.Toast.LENGTH_LONG,
        ).show()
    }

    /* ── Helpers ── */
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun spacer(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, h)
    }
    private fun makeFont(weight: Int): Typeface {
        val family = ResourcesCompat.getFont(this, R.font.montserrat)
            ?: return Typeface.DEFAULT
        return if (android.os.Build.VERSION.SDK_INT >= 28) {
            Typeface.create(family, weight, false)
        } else {
            Typeface.create(family, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
        }
    }
}

/* ════════════════  RecyclerView adapter  ════════════════ */

private class AppsAdapter(
    private val ctx: android.content.Context,
    private val apks: List<ApkEntryRemote>,
    private val tileBgColor:   Int,
    private val tileTextColor: Int,
    private val isInstalled: (String?) -> Boolean,
    private val onInstall:   (ApkEntryRemote) -> Unit,
    private val onUninstall: (ApkEntryRemote) -> Unit,
) : RecyclerView.Adapter<AppCardVH>() {

    /** Per-tile button mode.  We override the per-position default
     *  (Install vs Installed) when the user has tapped an installed
     *  tile once → shows red "Uninstall".  Cleared on rebind.
     *
     *  v2.8.3 — Keyed by `apk.id` (stable across reorders) instead
     *  of `position` (volatile) so the toggle state survives any
     *  data-set change.  Also used to mark download-in-progress
     *  tiles via the `downloading` map.
     */
    private enum class BtnMode { INSTALL, UNINSTALL, DOWNLOADING }
    private val downloading      = mutableMapOf<String, Int>() // id → 0..100

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppCardVH {
        val density = ctx.resources.displayMetrics.density
        fun px(v: Int) = (v * density).toInt()

        /* Card root — v2.8.18: admin-editable colors.  Defaults
           to deep-blue glass; admin can override via
           POST /api/admin/appstore/tile-colors.  No stroke (kept
           the "thin line above" fix), soft elevation shadow as
           the edge. */
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(16), px(20), px(16), px(16))
            isFocusable = true
            isFocusableInTouchMode = true
            clipChildren = false
            background = GradientDrawable().apply {
                cornerRadius = px(22).toFloat()
                setColor(tileBgColor)
            }
            elevation = px(2).toFloat()
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                px(248),
            ).apply { setMargins(px(8), px(8), px(8), px(8)) }
        }

        /* Big rounded icon container (90 dp). */
        val iconWrap = FrameLayout(ctx).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.parseColor("#2E5DC8FF"),
                    Color.parseColor("#80101D33"),
                ),
            ).apply { cornerRadius = px(22).toFloat() }
            clipToOutline = true
        }
        val icon = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
        }
        iconWrap.addView(icon, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        val initialBadge = TextView(ctx).apply {
            textSize = 32f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#FF5DC8FF"))
            gravity = Gravity.CENTER
        }
        iconWrap.addView(initialBadge, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ).apply { gravity = Gravity.CENTER })

        /* v2.8.6 — "Installed" status pill, anchored to the top-right
           corner of the icon.  Visible whenever the app is installed
           on the device so the user can SEE the state without
           reading the button text.  Toggled in onBindViewHolder. */
        val installedBadge = TextView(ctx).apply {
            text = "INSTALLED"
            textSize = 8.5f
            setTypeface(typeface, Typeface.BOLD)
            letterSpacing = 0.10f
            setTextColor(Color.parseColor("#FF04060B"))
            gravity = Gravity.CENTER
            setPadding(px(7), px(3), px(7), px(3))
            background = GradientDrawable().apply {
                cornerRadius = px(8).toFloat()
                setColor(Color.parseColor("#FF2EEAC2"))
            }
            visibility = View.GONE
        }
        iconWrap.addView(installedBadge, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            setMargins(0, px(6), px(6), 0)
        })

        card.addView(iconWrap, LinearLayout.LayoutParams(px(90), px(90)).apply {
            setMargins(0, 0, 0, px(12))
        })

        /* Name — v2.8.18 uses admin-editable text color. */
        val nameView = TextView(ctx).apply {
            setTextColor(tileTextColor)
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            ellipsize = TextUtils.TruncateAt.END
            maxLines = 1
        }
        card.addView(nameView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        /* Category sub-label — derived from tileTextColor at 60%
           opacity so it always reads as secondary regardless of
           which tile colour the admin picks. */
        val categoryView = TextView(ctx).apply {
            setTextColor((tileTextColor and 0x00FFFFFF) or 0x99000000.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
            ellipsize = TextUtils.TruncateAt.END
            maxLines = 1
            setPadding(0, px(4), 0, px(12))
        }
        card.addView(categoryView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        /* Action button (Install / Installed / Uninstall). */
        val actionBtn = TextView(ctx).apply {
            textSize = 13f
            isAllCaps = false
            letterSpacing = 0.04f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(px(22), px(10), px(22), px(10))
        }
        card.addView(actionBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        /* Card focus: 1.06× scale + elevation lift + subtle
           background brighten.  v2.8.18 — Focus colour is derived
           from the admin-chosen base by lightening toward white,
           so it stays sensible no matter which colour they pick. */
        val focusBg = lighten(tileBgColor, 0.12f)
        card.setOnFocusChangeListener { v, hasFocus ->
            v as LinearLayout
            (v.background as? GradientDrawable)?.setColor(
                if (hasFocus) focusBg else tileBgColor,
            )
            v.animate().cancel()
            v.animate()
                .scaleX(if (hasFocus) 1.06f else 1f)
                .scaleY(if (hasFocus) 1.06f else 1f)
                .setDuration(180)
                .setInterpolator(OvershootInterpolator(1.4f))
                .start()
            v.elevation = (if (hasFocus) px(10) else px(2)).toFloat()
            v.translationZ = if (hasFocus) px(8).toFloat() else 0f
        }

        return AppCardVH(card, iconWrap, icon, initialBadge, installedBadge, nameView, categoryView, actionBtn)
    }

    override fun onBindViewHolder(holder: AppCardVH, position: Int) {
        val density = ctx.resources.displayMetrics.density
        fun px(v: Int) = (v * density).toInt()
        val apk = apks[position]
        holder.nameView.text = apk.name
        // Category — falls back to a sensible default if unset.
        holder.categoryView.text = apk.category?.takeIf { it.isNotBlank() } ?: "Apps"

        if (apk.iconUrl.isNullOrBlank()) {
            holder.icon.visibility = View.GONE
            holder.initialBadge.visibility = View.VISIBLE
            holder.initialBadge.text =
                (apk.name.trim().firstOrNull()?.uppercase() ?: "?")
        } else {
            holder.initialBadge.visibility = View.GONE
            holder.icon.visibility = View.VISIBLE
            ImageLoader.load(holder.icon, apk.iconUrl)
        }

        // ── Pick button state ────────────────────────────────────
        // v2.8.6 — Per user spec: button under each tile is a single
        // tap.  Installed apps show UNINSTALL directly (no two-tap
        // toggle).  An "Installed" badge on the icon corner makes
        // the state legible at a glance — exactly the mockup the
        // user described.
        val installed = isInstalled(apk.packageId)
        val dl = downloading[apk.id]
        val mode: BtnMode = when {
            dl != null -> BtnMode.DOWNLOADING
            installed  -> BtnMode.UNINSTALL
            else       -> BtnMode.INSTALL
        }
        // Show the small "INSTALLED" pill in the icon corner only
        // when the app is installed AND not currently downloading.
        holder.installedBadge.visibility =
            if (installed && dl == null) View.VISIBLE else View.GONE
        applyButtonMode(holder.actionBtn, mode, px(22), dl)

        val clickHandler = clickHandler@{
            when (mode) {
                BtnMode.INSTALL -> {
                    // v2.8.3 — Flip the button to "Downloading…" IMMEDIATELY
                    // so the user gets instant feedback (was previously
                    // silent for the full ~30 s download on slow boxes).
                    downloading[apk.id] = 0
                    notifyItemChanged(holder.bindingAdapterPosition)
                    onInstall(apk)
                }
                BtnMode.UNINSTALL -> {
                    // Only fire if we actually have a package id —
                    // otherwise the system uninstaller has nothing
                    // to target and we'd silently do nothing.
                    if (apk.packageId.isNullOrBlank()) {
                        android.widget.Toast.makeText(
                            ctx, "Package id missing — set it in the admin.",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                        return@clickHandler
                    }
                    onUninstall(apk)
                }
                BtnMode.DOWNLOADING -> { /* no-op while downloading */ }
            }
        }
        holder.actionBtn.setOnClickListener { clickHandler() }
        // Card click goes to the action button too — for boxes
        // whose remotes focus the WHOLE tile, not just the button.
        holder.card.setOnClickListener { clickHandler() }
    }

    /** Called by the host activity when an install has either
     *  completed or failed — clears the "Downloading…" badge. */
    fun markInstallFinished(apkId: String) {
        if (downloading.remove(apkId) != null) {
            val idx = apks.indexOfFirst { it.id == apkId }
            if (idx >= 0) notifyItemChanged(idx)
        }
    }

    /** v2.8.17 — Per direct user spec: stop the flickering on
     *  install.  Root cause was `notifyItemChanged()` per
     *  progress tick — RecyclerView re-binds the whole ViewHolder
     *  (re-fetching the icon, recreating drawables, and sometimes
     *  recycling/re-binding neighbouring tiles → the
     *  "flicker, flicker, flicker, then flickers one of the
     *  other apps" effect).  Now we send a `PROGRESS_PAYLOAD`
     *  marker which is intercepted in `onBindViewHolder` so
     *  ONLY the action button label is updated — no re-fetch,
     *  no re-create, no neighbour-flicker. */
    fun setInstallProgress(apkId: String, percent: Int) {
        downloading[apkId] = percent.coerceIn(0, 100)
        val idx = apks.indexOfFirst { it.id == apkId }
        if (idx >= 0) notifyItemChanged(idx, PROGRESS_PAYLOAD)
    }

    /** Partial-update path — fires for every progress tick.
     *  Updates ONLY the button label so the icon / card / badges
     *  stay perfectly still. */
    override fun onBindViewHolder(
        holder: AppCardVH,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        if (payloads.contains(PROGRESS_PAYLOAD)) {
            val apk = apks[position]
            val dl = downloading[apk.id]
            val density = ctx.resources.displayMetrics.density
            val mode = when {
                dl != null -> BtnMode.DOWNLOADING
                isInstalled(apk.packageId) -> BtnMode.UNINSTALL
                else -> BtnMode.INSTALL
            }
            applyButtonMode(holder.actionBtn, mode, (22 * density).toInt(), dl)
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    companion object {
        private val PROGRESS_PAYLOAD = Any()
    }

    /** Lighten a color toward white by `t ∈ [0,1]`. */
    private fun lighten(color: Int, t: Float): Int {
        val a = (color shr 24) and 0xFF
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        fun mix(c: Int) = (c + ((255 - c) * t)).toInt().coerceIn(0, 255)
        return (a shl 24) or (mix(r) shl 16) or (mix(g) shl 8) or mix(b)
    }

    private fun applyButtonMode(btn: TextView, mode: BtnMode, radiusPx: Int, progress: Int? = null) {
        val (label, bg, fg) = when (mode) {
            BtnMode.INSTALL ->
                Triple("Install", "#FF2BB6FF", "#FF04060B")
            BtnMode.UNINSTALL ->
                Triple("Uninstall", "#FFFF5573", "#FFFFFFFF")
            BtnMode.DOWNLOADING ->
                Triple(
                    if ((progress ?: 0) > 0) "Downloading ${progress}%" else "Downloading…",
                    "#FF1B6BCF", "#FFF4F7FB",
                )
        }
        btn.text = label
        btn.setTextColor(Color.parseColor(fg))
        btn.background = GradientDrawable().apply {
            cornerRadius = radiusPx.toFloat()
            setColor(Color.parseColor(bg))
        }
    }

    override fun getItemCount(): Int = apks.size
}

private class AppCardVH(
    val card:           LinearLayout,
    val iconWrap:       FrameLayout,
    val icon:           ImageView,
    val initialBadge:   TextView,
    val installedBadge: TextView,
    val nameView:       TextView,
    val categoryView:   TextView,
    val actionBtn:      TextView,
) : RecyclerView.ViewHolder(card)
