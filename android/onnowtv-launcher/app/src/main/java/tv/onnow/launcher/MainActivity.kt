package tv.onnow.launcher

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import tv.onnow.launcher.apps.AppsDrawerActivity
import tv.onnow.launcher.data.DockTileRemote
import tv.onnow.launcher.data.LauncherConfig as RemoteLauncherConfig
import tv.onnow.launcher.data.LauncherRepository
import tv.onnow.launcher.data.NotificationRemote
import tv.onnow.launcher.databinding.ActivityMainBinding
import tv.onnow.launcher.notify.NotificationPopup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Home screen of the launcher.  Renders the featured panel (kicker /
 * title / tagline / desc / CTA / hero illustration), the 6-tile
 * bottom dock, the top status bar (greeting / logo / date / time)
 * and the vertical paginator.  D-pad navigation is handled by the
 * underlying View focus system; the only custom behaviour is the
 * live featured-panel + accent-colour swap whenever a dock tile
 * gains focus.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val handler = Handler(Looper.getMainLooper())
    private val argbEval = ArgbEvaluator()
    private lateinit var repo: LauncherRepository
    private val shownNotificationIds = mutableSetOf<String>()
    private var configPollJob: Job? = null
    private var notifyPollJob: Job? = null

    /* Local fallback dock items.  Used until the first network
     * /api/launcher/config fetch lands.  Once the admin backend
     * responds, dock items, accents, target intents and wallpaper
     * are all driven from the remote payload. */
    private var dockItems: MutableList<DockItem> = mutableListOf(
        DockItem("movies",   "Movies & TV Shows", "Stream and enjoy",     R.drawable.ic_dock_film),
        DockItem("music",    "Music",             "Listen and enjoy",     R.drawable.ic_dock_music),
        DockItem("livetv",   "Live TV",           "Watch live channels",  R.drawable.ic_dock_tv),
        DockItem("apps",     "Apps",              "All your apps",        R.drawable.ic_dock_grid),
        DockItem("browser",  "Browser",           "Surf the web",         R.drawable.ic_dock_globe),
        DockItem("settings", "Settings",          "System preferences",   R.drawable.ic_dock_gear),
    )

    /* Polling intervals for the live status-bar widgets. */
    private val clockTick = object : Runnable {
        override fun run() {
            paintClock()
            handler.postDelayed(this, 30_000)   // tick every 30 s
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repo = LauncherRepository(applicationContext)

        bindTopBar()
        bindDock()
        applyFeatured(dockItems[2])    // Default focus = Live TV (index 2) matches design

        // Hydrate from disk cache so the launcher renders the LAST
        // known remote config the moment the user opens it — even on
        // cold start with no network yet.
        repo.loadCached()?.let { onConfigUpdated(it) }

        // Make sure D-pad lands inside the dock on first frame so the
        // user sees an immediate focus state.
        binding.dock.post {
            (binding.dock.findViewHolderForAdapterPosition(2)?.itemView)?.requestFocus()
        }
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
        notifyPollJob?.cancel()
    }

    /* ──────────────────────  Backend polling  ──────────────────── */

    private fun startBackendPolling() {
        // Config every 5 minutes (admin layout / wallpaper / APK changes).
        configPollJob?.cancel()
        configPollJob = lifecycleScope.launch {
            while (true) {
                val fresh = repo.refresh()
                if (fresh != null) onConfigUpdated(fresh)
                delay(TimeUnit.MINUTES.toMillis(5))
            }
        }
        // Pending notifications every 30 seconds.
        notifyPollJob?.cancel()
        notifyPollJob = lifecycleScope.launch {
            while (true) {
                checkPendingNotifications()
                delay(TimeUnit.SECONDS.toMillis(30))
            }
        }
    }

    private suspend fun checkPendingNotifications() {
        val url = "${LauncherRepository.DEFAULT_BASE_URL}/api/launcher/notifications/pending?device_id=${repo.deviceId}"
        val pending = withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(8, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()
                val req = Request.Builder().url(url).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext emptyList<NotificationRemote>()
                    val body = resp.body?.string() ?: return@withContext emptyList()
                    val root = org.json.JSONObject(body)
                    val arr = root.optJSONArray("notifications") ?: return@withContext emptyList()
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.getJSONObject(i)
                        NotificationRemote(
                            id = o.optString("id"),
                            title = o.optString("title"),
                            body = o.optString("body"),
                            imageUrl = o.optString("image_url").ifBlank { null },
                            createdAt = o.optLong("created_at"),
                            expiresAt = o.optLong("expires_at"),
                        )
                    }
                }
            } catch (_: Throwable) { emptyList<NotificationRemote>() }
        }
        for (n in pending) {
            if (n.id in shownNotificationIds) continue
            shownNotificationIds.add(n.id)
            NotificationPopup.show(this, n) {
                lifecycleScope.launch { repo.ackNotification(n.id) }
            }
            // Only show one at a time; next will appear on the
            // next 30s poll tick.
            break
        }
    }

    /* ────────────────  Remote config applied  ─────────────────── */

    private fun onConfigUpdated(config: RemoteLauncherConfig) {
        if (config.dockTiles.size == 6) {
            // Swap the 6 dock items in place.  Map remote → local
            // model preserving the icon resource lookup so the
            // launcher's built-in vector icons are used unless the
            // admin supplied a custom icon_url (which we will plumb
            // through with Coil in a follow-up).
            val mapped = config.dockTiles.mapIndexed { idx, t ->
                DockItem(
                    key = t.key,
                    label = t.label,
                    sub = t.sub,
                    iconRes = iconResForKey(t.key),
                )
            }
            dockItems.clear()
            dockItems.addAll(mapped)
            binding.dock.adapter?.notifyDataSetChanged()
            // Update per-section accents from remote.
            for (t in config.dockTiles) {
                val argb = t.accent?.let { runCatching { Color.parseColor(it) }.getOrNull() }
                if (argb != null) FeaturedRegistry.overrideAccent(t.key, argb)
            }
            // Re-apply currently-focused tile so it picks up new accent.
            binding.dock.post {
                val pos = (binding.dock.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition() ?: 0
                applyFeatured(dockItems.getOrNull(pos) ?: dockItems[2])
            }
        }
        // Wallpaper handling — if admin set one, swap the
        // launcher background to a remote bitmap via Coil.  For
        // now we just record the URL; Coil integration to follow.
        config.activeWallpaperUrl?.let { wallpaperUrl ->
            android.util.Log.i("MainActivity", "active wallpaper: $wallpaperUrl")
        }
    }

    /** Map a remote dock-tile `key` back to one of our built-in
     *  vector icons.  Falls back to the TV icon for unknown keys. */
    private fun iconResForKey(key: String): Int = when (key) {
        "movies"   -> R.drawable.ic_dock_film
        "music"    -> R.drawable.ic_dock_music
        "livetv"   -> R.drawable.ic_dock_tv
        "apps"     -> R.drawable.ic_dock_grid
        "browser"  -> R.drawable.ic_dock_globe
        "settings" -> R.drawable.ic_dock_gear
        else       -> R.drawable.ic_dock_tv
    }

    /* ──────────────────────────  Top bar  ───────────────────────── */

    private fun bindTopBar() {
        // Greeting based on time of day.
        binding.greeting.text = greetingForHour()

        // Branded "OnNow TV V2" wordmark — last 2 chars rendered in
        // the accent colour with a soft glow to match the reference.
        binding.logoText.text = applyAccentTo("OnNow TV V2", "V2",
            color = Color.parseColor("#2BB6FF"))

        // Date + time.
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

    /* ────────────────────────────  Dock  ────────────────────────── */

    private fun bindDock() {
        binding.dock.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        binding.dock.adapter = DockAdapter(
            items = dockItems,
            onFocus = { item -> applyFeatured(item) },
            onSelect = { item -> handleSelect(item) },
        )
        // Disable the default item-change animation so the focus
        // state never visually "snaps" awkwardly when scrolling.
        binding.dock.itemAnimator = null
    }

    /* ──────────────────────  Featured panel  ────────────────────── */

    private var currentAccent = 0xFF2BB6FF.toInt()

    private fun applyFeatured(item: DockItem) {
        val state = FeaturedRegistry.forKey(item.key)

        // Highlight the period dot at the end of the title in the
        // accent colour — matches the reference design's signature.
        val titleSpan = SpannableString(state.title).apply {
            val periodIdx = state.title.lastIndexOf('.')
            if (periodIdx >= 0) {
                setSpan(
                    ForegroundColorSpan(state.accentArgb),
                    periodIdx, periodIdx + 1,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }
        binding.title.text       = titleSpan
        binding.tagline.text     = state.tagline
        binding.description.text = state.description

        // Accent-colour driven elements (kicker, paginator active
        // segment, CTA arrow) — animate between accents so the swap
        // feels alive instead of snappy.
        animateAccent(currentAccent, state.accentArgb)
        currentAccent = state.accentArgb

        // Swap the hero illustration to match the focused section.
        binding.heroIllustration.setIllustration(item.key)
    }

    private fun animateAccent(from: Int, to: Int) {
        if (from == to) return
        ValueAnimator.ofObject(argbEval, from, to).apply {
            duration = 240
            addUpdateListener { a ->
                val c = a.animatedValue as Int
                binding.kicker.setTextColor(c)
                binding.paginatorActive.setBackgroundColor(c)
            }
            start()
        }
    }

    /* ──────────────────────────  Routing  ────────────────────────── */

    private fun handleSelect(item: DockItem) {
        // Routing precedence:
        //   1. Apps tile → open AppsDrawerActivity.
        //   2. Remote target_package (admin set) → launch that app.
        //   3. Remote target_url (admin set) → open in browser.
        //   4. Fallback toast.
        if (item.key == "apps") {
            startActivity(Intent(this, AppsDrawerActivity::class.java))
            return
        }
        // Find the remote tile config (if any) to read target_*.
        val remote = repo.config.value?.dockTiles?.firstOrNull { it.key == item.key }
        val pkg = remote?.targetPackage
        if (!pkg.isNullOrBlank()) {
            val launch = packageManager.getLaunchIntentForPackage(pkg)
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launch)
                return
            }
            Toast.makeText(this, "${item.label} (app not installed: $pkg)", Toast.LENGTH_SHORT).show()
            return
        }
        val url = remote?.targetUrl
        if (!url.isNullOrBlank()) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Throwable) { /* no browser → fall through */ }
        }
        Toast.makeText(this,
            "${item.label} — set a target in the Launcher admin",
            Toast.LENGTH_SHORT).show()
    }

    /* ──────────────────────  Span helper  ─────────────────────── */

    private fun applyAccentTo(full: String, slice: String, color: Int): SpannableString {
        val span = SpannableString(full)
        val idx  = full.indexOf(slice)
        if (idx >= 0) {
            span.setSpan(
                ForegroundColorSpan(color),
                idx, idx + slice.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return span
    }

    /* ─────────────  HOME button = stay on launcher  ───────────── */

    override fun onBackPressed() {
        // Suppress BACK on the launcher's root — there's nothing to
        // go back to.  This is standard launcher behaviour.
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Re-show the dock when the user hits HOME from inside an
        // app (Android re-fires onNewIntent on the launcher).
        binding.dock.post {
            (binding.dock.findViewHolderForAdapterPosition(2)?.itemView)?.requestFocus()
        }
    }
}
