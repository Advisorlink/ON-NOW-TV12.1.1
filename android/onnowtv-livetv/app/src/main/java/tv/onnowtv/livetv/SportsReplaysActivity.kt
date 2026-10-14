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
import tv.onnowtv.livetv.ui.SportsLoadingBand
import tv.onnowtv.livetv.ui.SportsReplayAdapter
import tv.onnowtv.livetv.ui.SportsWindow
import tv.onnowtv.livetv.ui.SportsUi as UI
import tv.onnowtv.livetv.ui.SportsUi.dp

/** Immediate artwork categories, then bounded/cancellable real provider discovery. No ESPN dependency. */
class SportsReplaysActivity : AppCompatActivity() {
    private lateinit var heading: TextView
    private lateinit var status: TextView
    private lateinit var more: TextView
    private lateinit var retry: TextView
    private lateinit var loading: SportsLoadingBand
    private lateinit var list: RecyclerView
    private lateinit var adapter: SportsReplayAdapter
    private var bucket: String? = null
    private var source: XtreamBundle? = null
    private var channels: List<Channel> = emptyList()
    private var cursor = 0
    private var pending = ArrayDeque<SportsReplayCatalogue.Entry>()
    private val verified = linkedMapOf<String, SportsReplayCatalogue.Entry>()
    private var failedChannels = 0
    private var rejected = 0
    private var fetch: Job? = null
    private var loadingInterrupted = false
    private var opening = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); SportsWindow.apply(this); LivePreviewSession.release()
        bucket = savedInstanceState?.getString("bucket")?.takeIf { it in LiveSportsClassifier.DISPLAY_ORDER }
        val root = UI.col(this, dp(this, 12f)).apply {
            setBackgroundColor(UI.BG); setPadding(dp(context, 16f), dp(context, 16f), dp(context, 16f), dp(context, 12f))
        }
        val nav = UI.row(this, dp(this, 10f))
        nav.addView(UI.wordmark(this, true), LinearLayout.LayoutParams(0, -2, 1f))
        nav.addView(UI.identify(UI.pill(this, "‹ Back") { goBack() }, "replays-back")); root.addView(nav)
        heading = UI.identify(UI.text(this, "REPLAYS", 34f, bold = true), "replays-heading"); root.addView(heading)
        loading = SportsLoadingBand(this, "replays"); loading.show(null); root.addView(loading)
        status = UI.identify(UI.text(this, "", 15f, UI.DIM), "replays-status"); root.addView(status)
        adapter = SportsReplayAdapter(this, { select(it) }, { entry ->
            if (!opening) {
                if (SportsReplayPolicy.checkProgramme(entry.channel, entry.programme).eligible) {
                    opening = true; SportsReplayActivity.openProgramme(this, entry)
                } else { verified.remove(entry.key); render(); status.text = "This replay has expired from the provider's archive." }
            }
        })
        list = UI.identify(RecyclerView(this), "replays-grid").apply {
            adapter = this@SportsReplaysActivity.adapter; itemAnimator = null
            clipToPadding = true; setPadding(dp(context, 3f), dp(context, 3f), dp(context, 3f), dp(context, 8f))
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        val actions = UI.row(this, dp(this, 10f))
        more = UI.identify(UI.pill(this, "Load more recordings") { loadBatch() }, "replays-load-more")
        retry = UI.identify(UI.pill(this, "Retry") { select(bucket, true) }, "replays-retry")
        actions.addView(more); actions.addView(retry); root.addView(actions)
        setContentView(root); render()
    }
    override fun onResume() {
        super.onResume(); opening = false
        if (bucket != null) {
            if (source !== BundleHolder.current || source == null) select(bucket)
            else {
                verified.entries.removeAll { !SportsReplayPolicy.checkProgramme(it.value.channel, it.value.programme).eligible }
                render()
                if (loadingInterrupted) loadBatch()
            }
        }
    }
    override fun onPause() { loadingInterrupted = fetch?.isActive == true; fetch?.cancel(); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("bucket", bucket); super.onSaveInstanceState(outState) }
    @Deprecated("Android remote compatibility") override fun onBackPressed() { goBack() }
    private fun goBack() { if (bucket == null) finish() else select(null) }
    private fun select(value: String?, force: Boolean = false) {
        fetch?.cancel(); bucket = value; source = BundleHolder.current; channels = emptyList(); cursor = 0
        pending.clear(); verified.clear(); failedChannels = 0; rejected = 0; loadingInterrupted = false
        loading.show(null); render(); list.scrollToPosition(0)
        if (value != null) loadBatch(force)
    }
    private fun render() {
        val width = resources.configuration.screenWidthDp
        val columns = if (bucket == null) { if (width < 600) 2 else if (width < 1100) 4 else 6 } else { if (width < 600) 1 else if (width < 1100) 2 else 3 }
        if ((list.layoutManager as? GridLayoutManager)?.spanCount != columns) list.layoutManager = GridLayoutManager(this, columns)
        heading.text = if (bucket == null) "REPLAYS" else LiveSportsClassifier.labelOf(bucket!!) + " · REPLAYS"
        adapter.submitList(if (bucket == null) LiveSportsClassifier.DISPLAY_ORDER.map { SportsReplayAdapter.Item(it) }
            else verified.values.sortedByDescending { it.programme.startMs }.map { SportsReplayAdapter.Item(it.bucket, it) })
        val hasMore = bucket != null && (pending.isNotEmpty() || cursor < channels.size)
        more.visibility = if (hasMore) View.VISIBLE else View.GONE
        more.isEnabled = fetch?.isActive != true
        retry.visibility = if (bucket != null) View.VISIBLE else View.GONE
        retry.text = if (failedChannels > 0 || rejected > 0) "Retry" else "Refresh"
        status.text = when {
            bucket == null -> "SPORTS ARCHIVE · 18 CATEGORIES"
            source == null -> "Open your TV guide first to connect your replay library."
            verified.isNotEmpty() -> "${verified.size} recordings available" + if (failedChannels > 0) " · Some channels could not be checked" else ""
            loading.visibility == View.VISIBLE -> "Recordings appear as they are verified."
            failedChannels > 0 -> "Unable to check some archive channels. Please retry."
            hasMore -> "No playable recordings in this batch. More archive listings are available."
            else -> "No replays currently available for this sport."
        }
    }
    private fun loadBatch(force: Boolean = false) {
        val selected = bucket ?: return
        val bundle = source ?: BundleHolder.current ?: run { render(); return }
        if (fetch?.isActive == true) return
        source = bundle; loadingInterrupted = false; loading.show("Finding ${LiveSportsClassifier.labelOf(selected)} recordings…")
        fetch = lifecycleScope.launch {
            if (channels.isEmpty() && cursor == 0) channels = withContext(Dispatchers.Default) {
                SportsReplayCatalogue.channels(bundle).map { it.copy(streamUrl = AuthStore.rewriteStreamUrl(this@SportsReplaysActivity, it.streamUrl)) }
                    .sortedBy { if (LiveSportsClassifier.classify("", it.name) == selected) 0 else 1 }
            }
            var checked = 0; var scanned = 0
            // A page performs at most 12 media-prefix checks and 6 metadata calls, one at a time.
            while (checked < 12 && (pending.isNotEmpty() || (cursor < channels.size && scanned < 6))) {
                if (BundleHolder.current !== bundle || bucket != selected) return@launch
                if (pending.isEmpty()) {
                    val channel = channels[cursor]
                    loading.show("Reading archive · ${cursor + 1}/${channels.size} channels")
                    val rows = SportsReplayRepository.archiveProgrammes(channel, force)
                    if (rows == null) failedChannels++
                    val entries = withContext(Dispatchers.Default) { SportsReplayCatalogue.entries(channel, rows.orEmpty()).filter { it.bucket == selected } }
                    pending.addAll(entries); cursor++; scanned++
                } else {
                    val entry = pending.first()
                    loading.show("Checking recordings · ${checked + 1}/12 this batch")
                    if (force) SportsReplayRepository.invalidate(entry.channel, entry.programme)
                    val available = SportsReplayRepository.verifyProgramme(entry.channel, entry.programme).verified != null
                    if (BundleHolder.current !== bundle || bucket != selected) return@launch
                    pending.removeFirst(); checked++
                    if (available) verified[entry.key] = entry else rejected++
                }
                render()
            }
            loading.show(null); fetch = null; render()
        }
        render()
    }
}