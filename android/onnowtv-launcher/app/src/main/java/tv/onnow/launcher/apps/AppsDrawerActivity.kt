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
 *       – Installed     → green "Installed" — tap to switch to red
 *                          "Uninstall" mode → tap again to fire
 *                          Android's uninstall intent.
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
    private lateinit var heroImage: ImageView
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

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(40), dp(48), dp(48))
        }

        /* ── 1. Hero image (admin-uploaded) ── */
        heroImage = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            // Cyan-tinted placeholder while the real hero loads.
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.parseColor("#2B6BCF"),
                    Color.parseColor("#0A1B33"),
                ),
            ).apply { cornerRadius = dp(28).toFloat() }
        }
        // 21:9 cinematic aspect (matches the mockup banner).
        val heroHeight = dp(260)
        column.addView(heroImage, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            heroHeight,
        ).apply { setMargins(0, 0, 0, dp(28)) })

        /* ── 2. Section eyebrow + title ── */
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

    override fun onResume() {
        super.onResume()
        // After returning from Android's package-installer / uninstaller
        // we need to re-evaluate which tiles say "Install" vs
        // "Installed" — the user may have just toggled one.
        adapter?.notifyDataSetChanged()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext[Job]?.cancel()
    }

    private fun loadAndRender() {
        val cached = repo.config.value ?: repo.loadCached()
        if (cached != null) {
            renderApks(cached.apks)
            renderHero(cached.appstore.heroImageUrl)
        }
        loadJob?.cancel()
        loadJob = scope.launch {
            val fresh = repo.refresh()
            if (fresh != null) {
                renderApks(fresh.apks)
                renderHero(fresh.appstore.heroImageUrl)
            }
        }
    }

    private fun renderHero(url: String?) {
        if (url.isNullOrBlank()) return
        ImageLoader.load(heroImage, url)
    }

    private fun renderApks(apks: List<ApkEntryRemote>) {
        currentApks = apks
        if (apks.isEmpty()) {
            emptyHint.visibility = View.VISIBLE
            grid.adapter = null
            adapter = null
            return
        }
        emptyHint.visibility = View.GONE
        val a = AppsAdapter(
            ctx = this,
            apks = apks,
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

    /** Fire Android's standard ACTION_DELETE intent for the package.
     *  The system shows its own confirm dialog and handles the
     *  result; we just refresh tile state in `onResume()`.
     *
     *  v2.8.3 — Multiple fallbacks because `ACTION_DELETE` is
     *  inconsistent across vendors:
     *    1. `ACTION_UNINSTALL_PACKAGE` (Android 5+)
     *    2. `ACTION_DELETE`            (older + many AOSP forks)
     *    3. Settings → Apps detail page (always works)
     *  If a Toast surfaces "Couldn't open uninstaller", the user
     *  knows it's a launcher whitelist issue and not a missing pkg. */
    private fun uninstallApk(entry: ApkEntryRemote) {
        val pkg = entry.packageId?.takeIf { it.isNotBlank() }
        if (pkg == null) {
            android.widget.Toast.makeText(
                this, "Package id missing — set it in the admin.",
                android.widget.Toast.LENGTH_LONG,
            ).show()
            return
        }
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
            "Couldn't open uninstaller for $pkg",
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
    private enum class BtnMode { INSTALL, INSTALLED, UNINSTALL, DOWNLOADING }
    private val pendingUninstall = mutableSetOf<String>()
    private val downloading      = mutableMapOf<String, Int>() // id → 0..100

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppCardVH {
        val density = ctx.resources.displayMetrics.density
        fun px(v: Int) = (v * density).toInt()

        /* Card root — dark glass tile. */
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(16), px(20), px(16), px(16))
            isFocusable = true
            isFocusableInTouchMode = true
            clipChildren = false
            background = GradientDrawable().apply {
                cornerRadius = px(22).toFloat()
                setColor(Color.parseColor("#CC0F1B30"))
                setStroke(px(1), Color.parseColor("#1B2A45"))
            }
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
        card.addView(iconWrap, LinearLayout.LayoutParams(px(90), px(90)).apply {
            setMargins(0, 0, 0, px(12))
        })

        /* Name. */
        val nameView = TextView(ctx).apply {
            setTextColor(Color.parseColor("#FFF4F7FB"))
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

        /* Category sub-label (e.g. "Entertainment"). */
        val categoryView = TextView(ctx).apply {
            setTextColor(Color.parseColor("#FF8EA0B7"))
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

        /* Card focus: 1.06× scale + bright cyan border. */
        card.setOnFocusChangeListener { v, hasFocus ->
            v as LinearLayout
            (v.background as? GradientDrawable)?.apply {
                if (hasFocus) {
                    setStroke(px(2), Color.parseColor("#FF5DC8FF"))
                    setColor(Color.parseColor("#FF14385E"))
                } else {
                    setStroke(px(1), Color.parseColor("#1B2A45"))
                    setColor(Color.parseColor("#CC0F1B30"))
                }
            }
            v.animate().cancel()
            v.animate()
                .scaleX(if (hasFocus) 1.06f else 1f)
                .scaleY(if (hasFocus) 1.06f else 1f)
                .setDuration(180)
                .setInterpolator(OvershootInterpolator(1.4f))
                .start()
            v.translationZ = if (hasFocus) px(8).toFloat() else 0f
        }

        return AppCardVH(card, iconWrap, icon, initialBadge, nameView, categoryView, actionBtn)
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
        // v2.8.3 — `pendingUninstall` keyed by apk.id so the toggle
        // state survives any list reorder.  `downloading` shows
        // immediate progress feedback while the APK is fetching.
        val installed = isInstalled(apk.packageId)
        val dl = downloading[apk.id]
        val mode: BtnMode = when {
            dl != null                                       -> BtnMode.DOWNLOADING
            !installed                                       -> BtnMode.INSTALL
            installed && pendingUninstall.contains(apk.id)   -> BtnMode.UNINSTALL
            else                                             -> BtnMode.INSTALLED
        }
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
                BtnMode.INSTALLED -> {
                    // Only allow the uninstall toggle if we actually
                    // know the package id — otherwise pressing
                    // "Uninstall" would do nothing and confuse the user.
                    if (apk.packageId.isNullOrBlank()) {
                        android.widget.Toast.makeText(
                            ctx, "Package id missing — set it in the admin.",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                        return@clickHandler
                    }
                    pendingUninstall.add(apk.id)
                    notifyItemChanged(holder.bindingAdapterPosition)
                }
                BtnMode.UNINSTALL -> {
                    pendingUninstall.remove(apk.id)
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

    /** Called by the host activity each time `downloadAndInstall`
     *  reports new progress for an in-flight install. */
    fun setInstallProgress(apkId: String, percent: Int) {
        downloading[apkId] = percent.coerceIn(0, 100)
        val idx = apks.indexOfFirst { it.id == apkId }
        if (idx >= 0) notifyItemChanged(idx)
    }

    private fun applyButtonMode(btn: TextView, mode: BtnMode, radiusPx: Int, progress: Int? = null) {
        val (label, bg, fg) = when (mode) {
            BtnMode.INSTALL ->
                Triple("Install", "#FF2BB6FF", "#FF04060B")
            BtnMode.INSTALLED ->
                Triple("✓ Installed", "#FF2EEAC2", "#FF04060B")
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
    val card:         LinearLayout,
    val iconWrap:     FrameLayout,
    val icon:         ImageView,
    val initialBadge: TextView,
    val nameView:     TextView,
    val categoryView: TextView,
    val actionBtn:    TextView,
) : RecyclerView.ViewHolder(card)
