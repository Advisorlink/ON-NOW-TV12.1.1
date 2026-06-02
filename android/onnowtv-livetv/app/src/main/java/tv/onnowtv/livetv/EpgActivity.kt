package tv.onnowtv.livetv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tv.onnowtv.livetv.data.Category
import tv.onnowtv.livetv.data.Channel
import tv.onnowtv.livetv.data.Programme
import tv.onnowtv.livetv.data.XtreamBundle
import tv.onnowtv.livetv.data.XtreamRepository
import tv.onnowtv.livetv.ui.CategoryPillAdapter
import tv.onnowtv.livetv.ui.ChannelPillAdapter
import tv.onnowtv.livetv.ui.GuideRowAdapter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * V2 Live TV — Vesper-style EPG.
 *
 *   ── Layout ──
 *     • HERO (top, 240 dp): TMDB backdrop + LIVE TV eyebrow + big
 *       channel name + NOW programme info + cyan progress bar +
 *       UP NEXT line + top-right icon cluster (★ / ⟳ / ↪).
 *     • BODY (3 columns):
 *         CATEGORIES (left, 220 dp) → CHANNELS (middle, 360 dp) →
 *         GUIDE (right, fills remainder) grouped by TODAY / TOMORROW.
 *
 *   ── Navigation ──
 *     Native D-pad routes ↑/↓ within a column and ←/→ between
 *     columns automatically thanks to `nextFocusLeft/Right`
 *     attributes on each RecyclerView.  No custom keydown
 *     interceptors are needed for the column-jump case.
 *
 *   ── Data flow ──
 *     • Categories list submitted once from the bundle.
 *     • When a category gains focus → refilter channel list.
 *     • When a channel gains focus → update hero + load guide (lazy
 *       fetch /api/xtream/epg/{stream_id} if bundle EPG was empty).
 *     • Pressing OK on a channel → launch PlayerActivity.
 */
class EpgActivity : AppCompatActivity() {

    private lateinit var bundle: XtreamBundle

    // Hero refs
    private lateinit var hero: FrameLayout
    private lateinit var heroBackdrop: ImageView
    private lateinit var heroChannelName: TextView
    private lateinit var heroEyebrow: TextView
    private lateinit var heroNowTitle: TextView
    private lateinit var heroSynopsis: TextView
    private lateinit var heroProgress: View
    private lateinit var heroUpNext: TextView
    private lateinit var clock: TextView
    private lateinit var btnFavourite: ImageButton
    private lateinit var btnRefresh: ImageButton
    private lateinit var btnLogout: ImageButton

    // Body refs
    private lateinit var categoriesList: RecyclerView
    private lateinit var channelsList: RecyclerView
    private lateinit var channelsHeader: TextView
    private lateinit var guideList: RecyclerView

    private lateinit var categoryAdapter: CategoryPillAdapter
    private lateinit var channelAdapter: ChannelPillAdapter
    private lateinit var guideAdapter: GuideRowAdapter

    private var currentCategoryId: String? = null
    private var focusedChannel: Channel? = null
    private var allCategoriesWithCounts: List<Category> = emptyList()
    private val epgCache = mutableMapOf<String, List<Programme>>()

    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockFmt = SimpleDateFormat("h:mma", Locale.UK)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_epg)
        setTheme(R.style.Theme_OnNowLiveTV_NoActionBar)

        val held = BundleHolder.current
        if (held == null) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        bundle = held
        // Seed the EPG cache from the bundle.
        epgCache.putAll(bundle.epg)

        // Wire views
        hero = findViewById<FrameLayout>(R.id.hero)
        heroBackdrop = findViewById<ImageView>(R.id.hero_backdrop)
        heroChannelName = findViewById<TextView>(R.id.hero_channel_name)
        heroEyebrow = findViewById<TextView>(R.id.hero_eyebrow)
        heroNowTitle = findViewById<TextView>(R.id.hero_now_title)
        heroSynopsis = findViewById<TextView>(R.id.hero_synopsis)
        heroProgress = findViewById<View>(R.id.hero_progress)
        heroUpNext = findViewById<TextView>(R.id.hero_up_next)
        clock = findViewById<TextView>(R.id.clock)
        btnFavourite = findViewById<ImageButton>(R.id.btn_favourite)
        btnRefresh = findViewById<ImageButton>(R.id.btn_refresh)
        btnLogout = findViewById<ImageButton>(R.id.btn_logout)
        categoriesList = findViewById<RecyclerView>(R.id.categories_list)
        channelsList = findViewById<RecyclerView>(R.id.channels_list)
        channelsHeader = findViewById<TextView>(R.id.channels_header)
        guideList = findViewById<RecyclerView>(R.id.guide_list)

        // Build category metadata with real channel counts.
        val countsByCat: Map<String, Int> = bundle.channels
            .groupingBy { it.categoryId ?: "" }
            .eachCount()
        val virtualAll = Category(id = "__all__", name = "All channels", channelCount = bundle.channels.size)
        allCategoriesWithCounts = listOf(virtualAll) + bundle.categories
            .map { it.copy(channelCount = countsByCat[it.id] ?: 0) }
            .filter { it.channelCount > 0 && !it.name.contains("#####") }

        // Smart default: highest EPG coverage ratio.
        val epgCoverageByCat: Map<String, Double> = bundle.categories.associate { cat ->
            val cs = bundle.channels.filter { it.categoryId == cat.id }
            val ratio = if (cs.isEmpty()) 0.0
            else cs.count { ch ->
                val eid = ch.epgChannelId
                !eid.isNullOrBlank() && (epgCache[eid]?.isNotEmpty() == true)
            }.toDouble() / cs.size
            cat.id to ratio
        }
        val bestCat = bundle.categories
            .filter { !it.name.contains("#####") && (countsByCat[it.id] ?: 0) >= 5 }
            .maxByOrNull { (epgCoverageByCat[it.id] ?: 0.0) }
        currentCategoryId = if (bestCat != null && (epgCoverageByCat[bestCat.id] ?: 0.0) > 0.1) {
            bestCat.id
        } else {
            "__all__"
        }

        setupCategoriesList()
        setupChannelsList()
        setupGuideList()
        applyCategory()

        // Wire the hero icon cluster.
        btnRefresh.setOnClickListener {
            // Soft-refresh: re-apply current category to rebuild the list.
            applyCategory()
        }
        btnFavourite.setOnClickListener {
            // Future: persist favourite to SharedPreferences.
        }
        btnLogout.setOnClickListener {
            // Exit the app cleanly.
            finishAffinity()
        }

        // Refresh hero idle state every 30 s.
        startClock()

        // Focus the first category on boot.
        categoriesList.post {
            categoriesList.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus()
        }
    }

    /* ───────── set-up ─────────── */

    private fun setupCategoriesList() {
        categoryAdapter = CategoryPillAdapter(
            onPick = { c ->
                currentCategoryId = c.id
                applyCategory()
                // Move focus into the channels column on pick.
                channelsList.post {
                    channelsList.findViewHolderForAdapterPosition(0)
                        ?.itemView?.requestFocus()
                }
            },
            onFocus = { c ->
                // Live-filter as the user scrolls the categories list
                // — feels instant on a TV remote.
                if (c.id != currentCategoryId) {
                    currentCategoryId = c.id
                    applyCategory()
                }
            },
        )
        categoriesList.layoutManager = LinearLayoutManager(this)
        categoriesList.adapter = categoryAdapter
        categoriesList.itemAnimator = null
        categoryAdapter.submit(allCategoriesWithCounts, currentCategoryId)
    }

    private fun setupChannelsList() {
        channelAdapter = ChannelPillAdapter(
            nowResolver = { ch -> liveProgrammeOf(ch) },
            onFocus = { ch ->
                focusedChannel = ch
                updateHero(ch)
                loadGuideForChannel(ch)
            },
            onActivate = { ch -> launchPlayer(ch) },
        )
        channelsList.layoutManager = LinearLayoutManager(this)
        channelsList.adapter = channelAdapter
        channelsList.itemAnimator = null
    }

    private fun setupGuideList() {
        guideAdapter = GuideRowAdapter(
            onActivate = { /* future: toggle reminder */ },
        )
        guideList.layoutManager = LinearLayoutManager(this)
        guideList.adapter = guideAdapter
        guideList.itemAnimator = null
    }

    /* ───────── helpers ─────────── */

    private fun applyCategory() {
        val sel = currentCategoryId
        val channels = if (sel == null || sel == "__all__") {
            bundle.channels
        } else {
            bundle.channels.filter { it.categoryId == sel }
        }
        val visible = channels.take(500)
        channelAdapter.submit(visible)
        val label = allCategoriesWithCounts.firstOrNull { it.id == sel }?.name ?: "ALL"
        channelsHeader.text = "CHANNELS · $label  ·  ${visible.size}"
        categoryAdapter.setSelected(sel)
    }

    private fun liveProgrammeOf(ch: Channel): Programme? {
        val list = epgCache[ch.epgChannelId] ?: return null
        val n = System.currentTimeMillis()
        return list.firstOrNull { it.isLiveAt(n) }
    }

    private fun updateHero(ch: Channel) {
        heroChannelName.text = ch.name
        heroEyebrow.text = ch.lcn?.let { "LIVE TV · CH $it" } ?: "LIVE TV"
        val now = liveProgrammeOf(ch)
        if (now != null) {
            heroNowTitle.text = "${formatTime(now.startMs)}  ${now.title}"
            heroSynopsis.text = now.description ?: ""
            // Progress
            val pct = computeProgress(now)
            heroProgress.post {
                val parent = heroProgress.parent as? View ?: return@post
                val lp = heroProgress.layoutParams
                lp.width = (parent.width * pct).toInt().coerceAtLeast(0)
                heroProgress.layoutParams = lp
            }
            // UP NEXT
            val next = upcomingProgrammeOf(ch, now)
            heroUpNext.text = next?.let {
                "UP NEXT · ${formatTime(it.startMs)} · ${it.title}"
            } ?: ""
        } else {
            heroNowTitle.text = "Loading guide…"
            heroSynopsis.text = ""
            heroProgress.post {
                val lp = heroProgress.layoutParams
                lp.width = 0
                heroProgress.layoutParams = lp
            }
            heroUpNext.text = ""
        }
        // For now we use the channel logo as the backdrop fallback —
        // wire TMDB once focused-channel art lookup is added.
        if (!ch.logoUrl.isNullOrBlank()) {
            heroBackdrop.load(ch.logoUrl)
        }
    }

    private fun upcomingProgrammeOf(ch: Channel, now: Programme): Programme? {
        val list = epgCache[ch.epgChannelId] ?: return null
        return list.firstOrNull { it.startMs > now.startMs }
    }

    private fun loadGuideForChannel(ch: Channel) {
        val sid = ch.epgChannelId ?: return
        val cached = epgCache[sid]
        if (!cached.isNullOrEmpty()) {
            guideAdapter.submit(cached)
        } else {
            guideAdapter.submit(emptyList())
            // Lazy fetch from backend.
            lifecycleScope.launch(Dispatchers.IO) {
                val fetched = XtreamRepository.fetchEpgForChannel(sid)
                if (fetched.isNotEmpty()) {
                    epgCache[sid] = fetched
                    guideList.post {
                        if (focusedChannel?.epgChannelId == sid) {
                            guideAdapter.submit(fetched)
                            // Hero might still be on this channel; refresh it.
                            focusedChannel?.let { updateHero(it) }
                            channelAdapter.notifyDataSetChanged()
                        }
                    }
                }
            }
        }
    }

    private fun launchPlayer(ch: Channel) {
        val intent = Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_URL, ch.streamUrl)
            putExtra(PlayerActivity.EXTRA_TITLE, ch.name)
            val live = liveProgrammeOf(ch)
            putExtra(PlayerActivity.EXTRA_SUBTITLE, live?.title ?: "")
        }
        startActivity(intent)
    }

    private fun computeProgress(p: Programme): Float {
        val now = System.currentTimeMillis()
        if (now <= p.startMs) return 0f
        if (now >= p.stopMs) return 1f
        val span = (p.stopMs - p.startMs).coerceAtLeast(1L)
        return ((now - p.startMs).toFloat() / span.toFloat()).coerceIn(0f, 1f)
    }

    private fun formatTime(ms: Long): String =
        clockFmt.format(Date(ms)).lowercase(Locale.UK)

    private fun startClock() {
        val tick = object : Runnable {
            override fun run() {
                clock.text = clockFmt.format(Date()).lowercase(Locale.UK)
                focusedChannel?.let { updateHero(it) }
                clockHandler.postDelayed(this, 30_000L)
            }
        }
        clockHandler.post(tick)
    }

    override fun onDestroy() {
        clockHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
