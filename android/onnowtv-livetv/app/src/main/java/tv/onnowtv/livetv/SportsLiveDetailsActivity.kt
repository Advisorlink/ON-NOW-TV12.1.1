package tv.onnowtv.livetv

import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import tv.onnowtv.livetv.data.*
import tv.onnowtv.livetv.ui.SportsArtwork
import tv.onnowtv.livetv.ui.SportsBroadcastPanel
import tv.onnowtv.livetv.ui.SportsLoadingBand
import tv.onnowtv.livetv.ui.SportsSportBadge
import tv.onnowtv.livetv.ui.SportsWindow
import tv.onnowtv.livetv.ui.SportsUi as UI

/** Full details-page fallback for guide-only events, never a channel-picker popup or auto-play. */
class SportsLiveDetailsActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var channels: LinearLayout
    private lateinit var loading: SportsLoadingBand
    private var fetch: Job? = null
    private var opening = false
    private var bucket = "other"
    private var eventTitle = "Live event"
    private var bundleAtOpen: XtreamBundle? = null
    private var generation = 0
    override fun onCreate(state: Bundle?) {
        super.onCreate(state); SportsWindow.apply(this)
        bucket = intent.getStringExtra("event-sport")?.takeIf { it in LiveSportsClassifier.DISPLAY_ORDER } ?: "other"
        eventTitle = intent.getStringExtra("event-title") ?: "Live event"
        bundleAtOpen = BundleHolder.current
        val body = UI.col(this, UI.dp(this, 16f)).apply { setBackgroundColor(UI.BG); setPadding(UI.dp(context, 16f), UI.dp(context, 16f), UI.dp(context, 16f), UI.dp(context, 16f)) }
        UI.identify(body, "live-details-page")
        body.addView(UI.row(this, UI.dp(this, 12f)).apply {
            addView(UI.wordmark(context, true), LinearLayout.LayoutParams(0, -2, 1f))
            addView(UI.identify(UI.pill(context, "‹ Scores") { finish() }, "live-details-back"))
        })
        val surface = FrameLayout(this)
        surface.addView(SportsArtwork.eventLayer(this, null, bucket, "live-details"), FrameLayout.LayoutParams(-1, -1))
        surface.addView(UI.col(this, UI.dp(this, 12f)).apply {
            setPadding(UI.dp(context, 18f), UI.dp(context, 22f), UI.dp(context, 18f), UI.dp(context, 22f))
            addView(SportsSportBadge.create(context, bucket, "live-details"))
            addView(UI.identify(UI.text(context, eventTitle, 30f, bold = true), "live-details-title"))
        }, FrameLayout.LayoutParams(-1, -2)); body.addView(surface)
        loading = SportsLoadingBand(this, "live-details"); body.addView(loading)
        status = UI.identify(UI.text(this, "Updating match details…", 15f, UI.DIM), "live-details-status"); body.addView(status)
        channels = UI.col(this, UI.dp(this, 10f)); UI.identify(channels, "live-details-channels"); body.addView(channels)
        setContentView(ScrollView(this).apply { isFillViewport = true; setBackgroundColor(UI.BG); addView(body) })
    }
    override fun onResume() { super.onResume(); opening = false; load() }
    override fun onPause() { generation++; fetch?.cancel(); loading.show(null); super.onPause() }
    private fun load() {
        val request = ++generation
        fetch?.cancel()
        val bundle = bundleAtOpen
        if (bundle == null || BundleHolder.current !== bundle) { loading.show(null); status.text = "The guide changed. Reopen this event from Sports Hub."; channels.removeAllViews(); return }
        val ids = intent.getStringArrayListExtra("event-channel-ids").orEmpty().toSet()
        val available = bundle.channels.filter { it.id in ids }
        if (SportsLiveNavigation.scope(available) != intent.getStringExtra("event-account-scope")) { loading.show(null); status.text = "These channel choices changed. Reopen the event."; return }
        loading.show("Updating team information…")
        fetch = lifecycleScope.launch {
            try {
                val scoped = bundle.copy(channels = available, epg = SharedSportsGuide.rows(bundle))
                var entries = withContext(Dispatchers.Default) { SportsLiveGuide.current(scoped, bucket) }
                if (BundleHolder.current !== bundle) return@launch
                renderChannels(bundle, entries)
                withTimeoutOrNull(15_000) { SportsEventMetadata.refresh() }
                if (BundleHolder.current !== bundle) return@launch
                entries = withContext(Dispatchers.Default) { SportsLiveGuide.current(scoped, bucket) }
                val fixtures = entries.mapNotNull { it.fixtureJson?.let(::JSONObject) }.distinctBy { SportsPresentation.key(it) }
                val fixture = fixtures.singleOrNull()?.takeIf { SportsLiveNavigation.hasFixture(it) && entries.all { row -> row.fixtureJson != null } }
                if (fixture != null && !opening) { opening = true; startActivity(SportsLiveNavigation.matchIntent(this@SportsLiveDetailsActivity, fixture)); finish() }
                else { status.text = "Team profiles and statistics are not supplied for this guide event yet."; renderChannels(bundle, entries) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (request == generation) status.text = "Match details could not update. Available guide channels are shown below." }
            finally { if (request == generation) loading.show(null) }
        }
    }
    private fun renderChannels(bundle: XtreamBundle, entries: List<SportsLiveGuide.Entry>) {
        channels.removeAllViews()
        val matches = entries.map { GameChannelMatcher.Match(it.channel, 45, "Live guide programme", it.programme, liveCandidate = true) }
        channels.addView(SportsBroadcastPanel.build(this, this, JSONObject().put("state", "in"), bundle, matches, { _, selected ->
            val entry = entries.firstOrNull { it.channel.id == selected.id }
            if (!opening && BundleHolder.current === bundle && entry?.programme?.isLiveAt(System.currentTimeMillis()) == true) {
                opening = true; SportsPlayback.open(this, selected, subtitle = eventTitle)
            } else status.text = "This broadcast ended or the guide changed. Reopen the event."
        }, {}))
    }
}