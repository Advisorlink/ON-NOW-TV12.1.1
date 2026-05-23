package tv.onnow.launcher

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import tv.onnow.launcher.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    /* The 6 dock items.  Order + labels will eventually be supplied
     * by the admin backend (Phase 2); for now they are hard-coded so
     * the launcher boots without any network. */
    private val dockItems by lazy {
        listOf(
            DockItem("movies",   "Movies & TV Shows", "Stream and enjoy",     R.drawable.ic_dock_film),
            DockItem("music",    "Music",             "Listen and enjoy",     R.drawable.ic_dock_music),
            DockItem("livetv",   "Live TV",           "Watch live channels",  R.drawable.ic_dock_tv),
            DockItem("apps",     "Apps",              "All your apps",        R.drawable.ic_dock_grid),
            DockItem("browser",  "Browser",           "Surf the web",         R.drawable.ic_dock_globe),
            DockItem("settings", "Settings",          "System preferences",   R.drawable.ic_dock_gear),
        )
    }

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

        bindTopBar()
        bindDock()
        applyFeatured(dockItems[2])    // Default focus = Live TV (index 2) matches design

        // Make sure D-pad lands inside the dock on first frame so the
        // user sees an immediate focus state.
        binding.dock.post {
            (binding.dock.findViewHolderForAdapterPosition(2)?.itemView)?.requestFocus()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(clockTick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(clockTick)
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
        // Phase 1 placeholder — show a toast.  Phase 2 will route to
        // Live TV / Browser / Apps / Settings deep-link intents that
        // the admin backend declares.
        Toast.makeText(this,
            "${item.label} — coming soon",
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

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Re-show the dock when the user hits HOME from inside an
        // app (Android re-fires onNewIntent on the launcher).
        binding.dock.post {
            (binding.dock.findViewHolderForAdapterPosition(2)?.itemView)?.requestFocus()
        }
    }
}
