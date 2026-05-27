package tv.onnow.launcher.apps

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
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
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import tv.onnow.launcher.ImageLoader
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
        setContentView(root)

        loadAndRender()
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
