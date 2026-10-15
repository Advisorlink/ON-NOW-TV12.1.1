package tv.onnowtv.livetv

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.onnowtv.livetv.data.*
import tv.onnowtv.livetv.data.SportsHubApi.objects
import tv.onnowtv.livetv.ui.SportsLoadingBand
import tv.onnowtv.livetv.ui.SportsReplayAdapter
import tv.onnowtv.livetv.ui.SportsWindow
import tv.onnowtv.livetv.ui.SportsUi as UI
import tv.onnowtv.livetv.ui.SportsUi.dp

/** Categories appear only after a real recording is verified. Selection reuses one discovery index. */
class SportsReplaysActivity : AppCompatActivity() {
    private lateinit var heading: TextView
    private lateinit var status: TextView
    private lateinit var more: TextView
    private lateinit var refresh: TextView
    private lateinit var loading: SportsLoadingBand
    private lateinit var list: RecyclerView
    private lateinit var adapter: SportsReplayAdapter
    private var bucket: String? = null
    private var source: XtreamBundle? = null
    private var state: SportsReplayDiscovery.State? = null
    private var fetch: Job? = null
    private var officialFetch: Job? = null
    private var official: List<OfficialReplay> = emptyList()
    private var scanning = false
    private var opening = false
    private var stopped = false

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved); SportsWindow.apply(this); LivePreviewSession.release()
        bucket = saved?.getString("bucket")?.takeIf { it in LiveSportsClassifier.DISPLAY_ORDER }
        val root = UI.col(this, dp(this, 12f)).apply { setBackgroundColor(UI.BG); setPadding(dp(context, 16f), dp(context, 16f), dp(context, 16f), dp(context, 12f)) }
        val nav = UI.row(this, dp(this, 10f)); nav.addView(UI.wordmark(this, true), LinearLayout.LayoutParams(0, -2, 1f))
        nav.addView(UI.identify(UI.pill(this, "‹ Back") { goBack() }, "replays-back")); root.addView(nav)
        heading = UI.identify(UI.text(this, "REPLAYS", 34f, bold = true), "replays-heading"); root.addView(heading)
        loading = SportsLoadingBand(this, "replays"); loading.show(null); root.addView(loading)
        status = UI.identify(UI.text(this, "", 15f, UI.DIM), "replays-status"); root.addView(status)
        adapter = SportsReplayAdapter(this, { sport -> bucket = sport; render(); list.scrollToPosition(0) }, { entry ->
            if (!opening) { opening = true; SportsReplayActivity.openProgramme(this, entry) }
        }, { item -> if (!opening) { opening = true; SportsOfficialReplayActivity.open(this, item.id) } })
        list = UI.identify(RecyclerView(this), "replays-grid").apply {
            adapter = this@SportsReplaysActivity.adapter; itemAnimator = null
            setPadding(dp(context, 3f), dp(context, 3f), dp(context, 3f), dp(context, 8f))
        }; root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        val actions = UI.row(this, dp(this, 10f))
        more = UI.identify(UI.pill(this, "Find more replays") {
            if (scanning) { stopped = true; fetch?.cancel(); scanning = false; render() } else { stopped = false; begin() }
        }, "replays-load-more")
        refresh = UI.identify(UI.pill(this, "Refresh") { state = null; stopped = false; begin(true) }, "replays-retry")
        actions.addView(more); actions.addView(refresh); root.addView(actions); setContentView(root); render()
    }
    override fun onResume() {
        super.onResume(); opening = false
        if (source !== BundleHolder.current) { state = null; source = BundleHolder.current }
        if (!stopped) begin()
        officialFetch?.cancel(); officialFetch = lifecycleScope.launch {
            official = SportsHubApi.officialReplays()?.optJSONArray("replays").objects().mapNotNull { OfficialReplay.parse(it) }
            render()
        }
    }
    override fun onPause() { fetch?.cancel(); officialFetch?.cancel(); scanning = false; super.onPause() }
    override fun onSaveInstanceState(out: Bundle) { out.putString("bucket", bucket); super.onSaveInstanceState(out) }
    @Deprecated("TV remote compatibility") override fun onBackPressed() { goBack() }
    private fun goBack() { if (bucket == null) finish() else { bucket = null; render(); list.scrollToPosition(0) } }
    private fun begin(force: Boolean = false) {
        fetch?.cancel(); source = BundleHolder.current
        val bundle = source ?: run { scanning = false; render(); return }
        state?.let { SportsReplayDiscovery.prune(it) }
        scanning = true; render()
        fetch = lifecycleScope.launch {
            if (state == null || force) {
                val channels = withContext(Dispatchers.Default) { SportsReplayDiscovery.orderedChannels(bundle) { ch -> ch.copy(streamUrl = AuthStore.rewriteStreamUrl(this@SportsReplaysActivity, ch.streamUrl)) } }
                val next = SportsReplayDiscovery.acquire(channels, force)
                SportsReplayDiscovery.seed(next, SharedSportsGuide.rows(bundle))
                if (BundleHolder.current !== bundle) { scanning = false; render(); return@launch }
                state = next
            }
            val index = state ?: return@launch
            SportsReplayDiscovery.scan(index, { bucket }, { BundleHolder.current === bundle && !isFinishing }, force) { render() }
            scanning = false; render()
        }
    }
    private fun render() {
        if (!::list.isInitialized) return
        val index = state
        val available = LiveSportsClassifier.DISPLAY_ORDER.filter { sport -> index?.verified?.values?.any { it.bucket == sport } == true || official.any { it.sport == sport } }
        val rows = index?.verified?.values.orEmpty().filter { bucket == null || it.bucket == bucket }.sortedByDescending { it.programme.startMs }
        val external = official.filter { bucket == null || it.sport == bucket }
        val width = resources.configuration.screenWidthDp
        val columns = if (bucket == null) { if (width < 600) 2 else if (width < 1100) 4 else 6 } else { if (width < 600) 1 else if (width < 1100) 2 else 3 }
        if ((list.layoutManager as? GridLayoutManager)?.spanCount != columns) list.layoutManager = GridLayoutManager(this, columns)
        heading.text = bucket?.let { LiveSportsClassifier.labelOf(it) + " · REPLAYS" } ?: "REPLAYS"
        adapter.submitList(if (bucket == null) available.map { sport -> SportsReplayAdapter.Item(sport, count = index?.verified?.values?.count { it.bucket == sport }.orZero() + official.count { it.sport == sport }) }
            else rows.map { SportsReplayAdapter.Item(it.bucket, it) } + external.map { SportsReplayAdapter.Item(it.sport, official = it) })
        loading.show(if (scanning) "Finding available replays · ${index?.cursor ?: 0}/${index?.channels?.size ?: 0} channels" else null)
        status.text = when {
            rows.isNotEmpty() || external.isNotEmpty() -> if (bucket == null) "${available.size} sports with available replays · ${rows.size + external.size} recordings" else "${rows.size + external.size} available recordings"
            scanning -> "Only sports with verified recordings will appear."
            source == null -> "Open your TV guide to load your available recordings."
            stopped -> "Search paused. No recordings verified yet."
            index?.errors.orZero() > 0 -> "Some archive channels could not be checked. Refresh to retry."
            index?.hasMore() == true -> "No recordings verified in this batch. Continue searching below."
            else -> "No playable recent match recordings were found in your provider archive."
        }
        if (index?.errors.orZero() > 0 && rows.isNotEmpty()) status.append(" · Some channels could not be checked")
        more.text = if (scanning) "Pause search" else "Find more replays"
        more.visibility = if (scanning || index?.hasMore() == true) View.VISIBLE else View.GONE
        refresh.isEnabled = !scanning
    }
    private fun Int?.orZero() = this ?: 0
}