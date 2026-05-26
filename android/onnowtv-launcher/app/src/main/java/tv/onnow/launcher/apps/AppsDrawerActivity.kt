package tv.onnow.launcher.apps

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.onnow.launcher.data.ApkEntryRemote
import tv.onnow.launcher.data.LauncherRepository
import tv.onnow.launcher.install.ApkInstaller

/**
 * AppsDrawerActivity
 * ──────────────────
 * Shows the admin-managed APK manifest in a TV-friendly card grid.
 * D-pad navigation + OK launches install for each card.  Pure
 * programmatic UI — no XML, RecyclerView only.
 */
class AppsDrawerActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var repo: LauncherRepository
    private lateinit var grid: RecyclerView
    private lateinit var emptyHint: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var installAllBtn: Button
    private lateinit var statusLabel: TextView
    private var loadJob: Job? = null
    private var bulkInstallJob: Job? = null
    /** Latest APK list rendered — used by the "Install all" button. */
    private var currentApks: List<ApkEntryRemote> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = LauncherRepository(applicationContext)

        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFF04060B.toInt())
            setPadding(64, 64, 64, 64)
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        column.addView(TextView(this).apply {
            text = "Downloads"
            textSize = 36f
            setTextColor(0xFFF4F7FB.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        column.addView(TextView(this).apply {
            text = "Tap an app to install it.  Or tap \"Install all apps\" to download every app onto this box in one go."
            textSize = 14f
            setTextColor(0xFF8EA0B7.toInt())
            setPadding(0, 8, 0, 18)
        })

        // v1.0 — Bulk install button.  Sequentially fires the system
        // installer for every APK in the admin-managed manifest.
        installAllBtn = Button(this).apply {
            text = "Install all apps"
            setTextColor(0xFF04060B.toInt())
            setBackgroundColor(0xFF2BB6FF.toInt())
            isAllCaps = false
            textSize = 16f
            setPadding(36, 18, 36, 18)
            isFocusable = true
            isFocusableInTouchMode = true
        }
        column.addView(installAllBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(0, 0, 0, 14) })
        installAllBtn.setOnClickListener { startBulkInstall() }

        // v1.0 — Live status line for the bulk-install progress.
        statusLabel = TextView(this).apply {
            text = ""
            textSize = 13f
            setTextColor(0xFF2EEAC2.toInt())
            setPadding(0, 0, 0, 18)
            visibility = View.GONE
        }
        column.addView(statusLabel)

        progressBar = ProgressBar(this).apply { visibility = View.GONE }
        column.addView(progressBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { gravity = Gravity.CENTER_HORIZONTAL })

        emptyHint = TextView(this).apply {
            text = "No apps in the manifest yet."
            textSize = 16f
            setTextColor(0xFF5D6E85.toInt())
            visibility = View.GONE
        }
        column.addView(emptyHint)

        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@AppsDrawerActivity, 4)
            clipToPadding = false
            isFocusable = false
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }
        column.addView(grid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ))

        root.addView(column, FrameLayout.LayoutParams(
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
        grid.adapter = AppsAdapter(apks) { entry ->
            installApk(entry)
        }
    }

    /** v1.0 — Sequentially install every APK in the manifest.  Android
     *  doesn't allow truly silent installs without root or device-owner
     *  privileges, so we kick off the system installer for each APK in
     *  turn with a short delay so the user can confirm each prompt. */
    private fun startBulkInstall() {
        val apks = currentApks
        if (apks.isEmpty()) return
        if (!ApkInstaller.canInstallNow(this)) {
            ApkInstaller.requestInstallPermission(this); return
        }
        if (bulkInstallJob?.isActive == true) return  // already running

        statusLabel.visibility = View.VISIBLE
        statusLabel.setTextColor(0xFF2EEAC2.toInt())
        installAllBtn.isEnabled = false
        installAllBtn.alpha     = 0.5f

        bulkInstallJob = scope.launch {
            apks.forEachIndexed { i, apk ->
                statusLabel.text = "Installing ${i + 1} / ${apks.size}: ${apk.name}"
                val err = ApkInstaller.downloadAndInstall(
                    ctx = applicationContext,
                    apkUrl = apk.apkUrl,
                    suggestedName = "${apk.name}.apk",
                )
                if (err != null) {
                    statusLabel.setTextColor(0xFFFF5573.toInt())
                    statusLabel.text = "Failed on ${apk.name}: $err"
                    installAllBtn.isEnabled = true
                    installAllBtn.alpha     = 1f
                    return@launch
                }
                // Give the user a moment to confirm each system
                // installer prompt before queueing the next one.
                kotlinx.coroutines.delay(2500)
            }
            statusLabel.text = "Done — queued ${apks.size} installs.  Confirm each prompt to finish."
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
                // Surface error inline.
                emptyHint.text = err
                emptyHint.setTextColor(0xFFFF5573.toInt())
                emptyHint.visibility = View.VISIBLE
            }
        }
    }
}

/* ── RecyclerView adapter ── */
private class AppsAdapter(
    private val apks: List<ApkEntryRemote>,
    private val onClick: (ApkEntryRemote) -> Unit,
) : RecyclerView.Adapter<AppCard>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppCard {
        val card = LinearLayout(parent.context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(0x800F1B30.toInt())
            isFocusable = true
            isFocusableInTouchMode = true
            clipChildren = false
            layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                280,
            ).apply { setMargins(12, 12, 12, 12) }
        }
        return AppCard(card)
    }

    override fun onBindViewHolder(holder: AppCard, position: Int) {
        val apk = apks[position]
        val ctx = holder.itemView.context
        val card = holder.itemView as LinearLayout
        card.removeAllViews()

        val nameView = TextView(ctx).apply {
            text = apk.name
            setTextColor(0xFFF4F7FB.toInt())
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            ellipsize = TextUtils.TruncateAt.END
            maxLines = 2
        }
        val versionView = TextView(ctx).apply {
            text = listOfNotNull(apk.versionName, apk.packageId).joinToString(" · ")
            setTextColor(0xFF8EA0B7.toInt())
            textSize = 12f
            setPadding(0, 8, 0, 0)
        }
        val descView = TextView(ctx).apply {
            text = apk.description ?: ""
            setTextColor(0xFF8EA0B7.toInt())
            textSize = 13f
            setPadding(0, 16, 0, 16)
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
        }
        val installBtn = Button(ctx).apply {
            text = "Install"
            setTextColor(0xFF04060B.toInt())
            setBackgroundColor(0xFF2BB6FF.toInt())
            isAllCaps = false
            textSize = 14f
        }

        card.addView(nameView)
        card.addView(versionView)
        card.addView(descView)
        card.addView(installBtn)

        card.setOnFocusChangeListener { v, hasFocus ->
            v.setBackgroundColor(if (hasFocus) 0xFF14385E.toInt() else 0x800F1B30.toInt())
            v.animate().scaleX(if (hasFocus) 1.04f else 1.0f)
                       .scaleY(if (hasFocus) 1.04f else 1.0f)
                       .setDuration(140).start()
        }
        card.setOnClickListener { onClick(apk) }
        installBtn.setOnClickListener { onClick(apk) }
    }

    override fun getItemCount(): Int = apks.size
}

private class AppCard(view: View) : RecyclerView.ViewHolder(view)
