package tv.onnow.launcher.apps

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
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
 * AppsDrawerActivity — v1.9 ON NOW TV 2 App Store redesign.
 *
 * Goal: feel like a real curated App Store, not a settings list.
 *   - Brand hero header: "ON NOW TV" / red "2" / "App Store"
 *   - Subhead line + bulk-install CTA
 *   - 4-column grid of LARGE rounded-icon tiles (128 dp icons +
 *     name + version pill)
 *   - Focus → 1.12× overshoot scale + bright cyan border + glow
 *   - Click → install
 *
 * Pure programmatic Kotlin UI (no XML) so it can compose against
 * the same Vesper palette as the Onboarding screen and the dock.
 *
 * Note: the launcher backend resolves `icon_url` to a fully-
 * qualified URL via `_abs()` before sending the config, so we can
 * pass it to `ImageLoader.load()` verbatim.
 */
class AppsDrawerActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repo: LauncherRepository
    private lateinit var grid: RecyclerView
    private lateinit var emptyHint: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var installAllBtn: TextView
    private lateinit var statusLabel: TextView
    private var loadJob: Job? = null
    private var bulkInstallJob: Job? = null
    private var currentApks: List<ApkEntryRemote> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = LauncherRepository(applicationContext)

        /* ── Root: deep inky background + subtle radial glow ── */
        val root = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.onb_bg_glow)
        }

        val scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            // Don't let the ScrollView swallow D-pad focus from cards.
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(72), dp(60), dp(72), dp(48))
        }

        /* ── Hero header ── */
        val eyebrow = TextView(this).apply {
            text = "ON NOW TV V2 · CURATED APPS"
            textSize = 11f
            letterSpacing = 0.32f
            setTextColor(0xFF5DC8FF.toInt())
            typeface = Typeface.MONOSPACE
        }
        column.addView(eyebrow)
        column.addView(spacer(dp(10)))

        // Brand title — uses the same Montserrat font family as
        // the Onboarding display title for visual consistency.
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val font700 = makeFont(700)
        val font400 = makeFont(400)
        titleRow.addView(TextView(this).apply {
            text = "ON NOW TV"
            textSize = 44f
            typeface = font700
            letterSpacing = -0.01f
            setTextColor(0xFFF4F7FB.toInt())
            setShadowLayer(28f, 0f, 4f, 0xB02BB6FF.toInt())
        })
        titleRow.addView(TextView(this).apply {
            text = "2"
            textSize = 44f
            typeface = font700
            setTextColor(0xFF5DC8FF.toInt())
            setPadding(dp(10), 0, dp(10), 0)
            setShadowLayer(32f, 0f, 4f, 0xE05DC8FF.toInt())
        })
        titleRow.addView(TextView(this).apply {
            text = "App Store"
            textSize = 44f
            typeface = font400
            letterSpacing = -0.01f
            setTextColor(0xFFF4F7FB.toInt())
        })
        column.addView(titleRow)
        column.addView(spacer(dp(8)))

        val subtitle = TextView(this).apply {
            text = "Tap any app to install it on this box.  " +
                   "Use the Install All button to queue every app at once."
            textSize = 14f
            setTextColor(0xFF8EA0B7.toInt())
            typeface = font400
        }
        column.addView(subtitle)
        column.addView(spacer(dp(28)))

        /* ── Install-All CTA pill ── */
        installAllBtn = TextView(this).apply {
            text = "INSTALL ALL  \u2192"
            textSize = 13f
            isAllCaps = false
            letterSpacing = 0.22f
            typeface = font700
            setTextColor(0xFF5DC8FF.toInt())
            setBackgroundResource(R.drawable.onb_primary_selector)
            gravity = Gravity.CENTER
            setPadding(dp(36), dp(16), dp(36), dp(16))
            isFocusable = true
            isFocusableInTouchMode = true
            setOnFocusChangeListener { v, focused ->
                v as TextView
                v.setTextColor(if (focused) 0xFF04060B.toInt() else 0xFF5DC8FF.toInt())
                v.animate().cancel()
                v.animate()
                    .scaleX(if (focused) 1.06f else 1.0f)
                    .scaleY(if (focused) 1.06f else 1.0f)
                    .setDuration(180)
                    .setInterpolator(OvershootInterpolator(1.4f))
                    .start()
            }
            setOnClickListener { startBulkInstall() }
        }
        column.addView(installAllBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))
        column.addView(spacer(dp(16)))

        /* ── Bulk-install status line ── */
        statusLabel = TextView(this).apply {
            text = ""
            textSize = 13f
            setTextColor(0xFF2EEAC2.toInt())
            visibility = View.GONE
            letterSpacing = 0.04f
        }
        column.addView(statusLabel)

        progressBar = ProgressBar(this).apply { visibility = View.GONE }
        column.addView(progressBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.CENTER_HORIZONTAL })

        /* ── Empty state ── */
        emptyHint = TextView(this).apply {
            text = "No apps in the store yet.  Drop an APK into the admin and it'll show up here within 30 seconds."
            textSize = 16f
            setTextColor(0xFF5D6E85.toInt())
            visibility = View.GONE
            setPadding(0, dp(48), 0, 0)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        column.addView(emptyHint)

        /* ── Apps grid ── */
        column.addView(spacer(dp(12)))
        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@AppsDrawerActivity, 4)
            clipToPadding = false
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            // Grid is inside a ScrollView — disable nested scrolling
            // so the parent owns the scroll position.
            isNestedScrollingEnabled = false
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

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext[Job]?.cancel()
    }

    private fun loadAndRender() {
        val cached = repo.config.value ?: repo.loadCached()
        if (cached != null) renderApks(cached.apks)
        loadJob?.cancel()
        loadJob = scope.launch {
            val fresh = repo.refresh()
            if (fresh != null) renderApks(fresh.apks)
        }
    }

    private fun renderApks(apks: List<ApkEntryRemote>) {
        currentApks = apks
        installAllBtn.isEnabled = apks.isNotEmpty()
        installAllBtn.alpha     = if (apks.isNotEmpty()) 1f else 0.4f
        if (apks.isEmpty()) {
            emptyHint.visibility = View.VISIBLE
            grid.adapter = null
            return
        }
        emptyHint.visibility = View.GONE
        grid.adapter = AppsAdapter(apks) { entry -> installApk(entry) }
        // Auto-focus the first tile so the D-pad works immediately
        // after pressing back-from-Install-All etc.
        grid.post {
            (grid.findViewHolderForAdapterPosition(0)?.itemView)?.requestFocus()
        }
    }

    /** Sequentially install every APK in the manifest. */
    private fun startBulkInstall() {
        val apks = currentApks
        if (apks.isEmpty()) return
        if (!ApkInstaller.canInstallNow(this)) {
            ApkInstaller.requestInstallPermission(this); return
        }
        if (bulkInstallJob?.isActive == true) return

        statusLabel.visibility = View.VISIBLE
        statusLabel.setTextColor(0xFF2EEAC2.toInt())
        installAllBtn.isEnabled = false
        installAllBtn.alpha     = 0.5f

        bulkInstallJob = scope.launch {
            apks.forEachIndexed { i, apk ->
                statusLabel.text = "INSTALLING ${i + 1} / ${apks.size}  ·  ${apk.name.uppercase()}"
                val err = ApkInstaller.downloadAndInstall(
                    ctx = applicationContext,
                    apkUrl = apk.apkUrl,
                    suggestedName = "${apk.name}.apk",
                )
                if (err != null) {
                    statusLabel.setTextColor(0xFFFF5573.toInt())
                    statusLabel.text = "FAILED ON ${apk.name.uppercase()}: $err"
                    installAllBtn.isEnabled = true
                    installAllBtn.alpha     = 1f
                    return@launch
                }
                kotlinx.coroutines.delay(2500)
            }
            statusLabel.text =
                "DONE  ·  QUEUED ${apks.size} INSTALL${if (apks.size == 1) "" else "S"}.  " +
                "CONFIRM EACH PROMPT TO FINISH."
            installAllBtn.isEnabled = true
            installAllBtn.alpha     = 1f
        }
    }

    private fun installApk(entry: ApkEntryRemote) {
        if (!ApkInstaller.canInstallNow(this)) {
            ApkInstaller.requestInstallPermission(this)
            return
        }
        progressBar.visibility = View.VISIBLE
        scope.launch {
            val err = ApkInstaller.downloadAndInstall(
                ctx = applicationContext,
                apkUrl = entry.apkUrl,
                suggestedName = "${entry.name}.apk",
            )
            progressBar.visibility = View.GONE
            if (err != null) {
                statusLabel.visibility = View.VISIBLE
                statusLabel.setTextColor(0xFFFF5573.toInt())
                statusLabel.text = "INSTALL FAILED · $err"
            }
        }
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

/* ════════════════  RecyclerView adapter ════════════════ */
private class AppsAdapter(
    private val apks: List<ApkEntryRemote>,
    private val onClick: (ApkEntryRemote) -> Unit,
) : RecyclerView.Adapter<AppCardVH>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppCardVH {
        val ctx = parent.context
        val density = ctx.resources.displayMetrics.density
        fun px(v: Int) = (v * density).toInt()

        /* Card root: dark gradient, 22dp radius, subtle border. */
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(22), px(26), px(22), px(22))
            isFocusable = true
            isFocusableInTouchMode = true
            clipChildren = false
            // Programmatic resting background (matches the admin
            // .appstore-tile gradient + border).
            background = GradientDrawable().apply {
                cornerRadius = px(22).toFloat()
                setColor(Color.parseColor("#CC0F1B30"))
                setStroke(px(1), Color.parseColor("#1B2A45"))
            }
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                px(220),
            ).apply { setMargins(px(10), px(10), px(10), px(10)) }
        }

        /* Big rounded icon container. */
        val iconWrap = FrameLayout(ctx).apply {
            // Constructor form is safer across API levels than setting
            // .colors / .orientation properties post-hoc.
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.parseColor("#2E5DC8FF"),
                    Color.parseColor("#80101D33"),
                ),
            ).apply {
                cornerRadius = px(24).toFloat()
            }
            clipToOutline = true
        }
        val icon = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            // Round-corner clip is via the parent's clipToOutline+
            // shape, but for safety apply a programmatic outline too.
            clipToOutline = true
        }
        iconWrap.addView(icon, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        val initialBadge = TextView(ctx).apply {
            id = View.generateViewId()
            textSize = 38f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.parseColor("#FF5DC8FF"))
            gravity = Gravity.CENTER
        }
        iconWrap.addView(initialBadge, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ).apply { gravity = Gravity.CENTER })
        card.addView(iconWrap, LinearLayout.LayoutParams(px(108), px(108)).apply {
            setMargins(0, 0, 0, px(14))
        })

        /* Name. */
        val nameView = TextView(ctx).apply {
            id = View.generateViewId()
            setTextColor(Color.parseColor("#FFF4F7FB"))
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            ellipsize = TextUtils.TruncateAt.END
            maxLines = 1
            setPadding(0, 0, 0, 0)
        }
        card.addView(nameView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        /* Version pill. */
        val versionView = TextView(ctx).apply {
            id = View.generateViewId()
            setTextColor(Color.parseColor("#FF8EA0B7"))
            textSize = 11f
            letterSpacing = 0.22f
            gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
            setPadding(0, px(6), 0, 0)
        }
        card.addView(versionView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        /* Focus animation: 1.08× scale + bright cyan border. */
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
                .scaleX(if (hasFocus) 1.08f else 1f)
                .scaleY(if (hasFocus) 1.08f else 1f)
                .setDuration(180)
                .setInterpolator(OvershootInterpolator(1.4f))
                .start()
            // Soft elevation lift for the focused tile.
            v.translationZ = if (hasFocus) px(8).toFloat() else 0f
        }

        return AppCardVH(card, iconWrap, icon, initialBadge, nameView, versionView)
    }

    override fun onBindViewHolder(holder: AppCardVH, position: Int) {
        val apk = apks[position]
        holder.nameView.text = apk.name
        holder.versionView.text = listOfNotNull(apk.versionName?.let { "v$it" }, apk.packageId)
            .joinToString("  ·  ")
            .ifEmpty { "—" }

        // Load the icon — if URL missing, draw the initial letter.
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
        holder.card.setOnClickListener { onClick(apk) }
    }

    override fun getItemCount(): Int = apks.size
}

private class AppCardVH(
    val card:         LinearLayout,
    val iconWrap:     FrameLayout,
    val icon:         ImageView,
    val initialBadge: TextView,
    val nameView:     TextView,
    val versionView:  TextView,
) : RecyclerView.ViewHolder(card)
