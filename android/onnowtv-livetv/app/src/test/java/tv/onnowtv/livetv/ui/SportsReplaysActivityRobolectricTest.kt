package tv.onnowtv.livetv.ui

import android.view.View
import android.view.View.MeasureSpec
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import tv.onnowtv.livetv.BundleHolder
import tv.onnowtv.livetv.R
import tv.onnowtv.livetv.SportsReplayActivity
import tv.onnowtv.livetv.SportsReplaysActivity
import tv.onnowtv.livetv.data.Category
import tv.onnowtv.livetv.data.Channel
import tv.onnowtv.livetv.data.LiveSportsClassifier
import tv.onnowtv.livetv.data.Programme
import tv.onnowtv.livetv.data.Provider
import tv.onnowtv.livetv.data.SportsReplayDiscovery
import tv.onnowtv.livetv.data.SportsReplayCatalogue
import tv.onnowtv.livetv.data.XtreamBundle
import tv.onnowtv.livetv.ui.SportsUi.dp
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.LEGACY)
@Config(sdk = [28], application = android.app.Application::class)
class SportsReplaysActivityRobolectricTest {

    @Test
    @Config(qualifiers = "w390dp-h844dp-port")
    fun empty_guide_shows_zero_available_category_tiles_and_no_spinner_loop() {
        val controller = Robolectric.buildActivity(SportsReplaysActivity::class.java).create().start().resume()
        val activity = controller.get()
        try {
            val list = activity.window.decorView.findViewWithTag<RecyclerView>("replays-grid")
            assertEquals(0, list.adapter!!.itemCount)
            assertEquals(2, (list.layoutManager as GridLayoutManager).spanCount)

            val heading = activity.window.decorView.findViewWithTag<TextView>("replays-heading")
            val status = activity.window.decorView.findViewWithTag<TextView>("replays-status")
            val retry = activity.window.decorView.findViewWithTag<TextView>("replays-retry")
            val loading = activity.window.decorView.findViewWithTag<View>("replays-loading-progress-band")
            assertEquals("REPLAYS", heading.text.toString())
            assertTrue(status.text.toString().contains("guide", ignoreCase = true))
            assertEquals(View.VISIBLE, retry.visibility)
            assertEquals("Refresh", retry.text.toString())
            // No guide/account means no request is running; do not show an endless spinner.
            assertEquals(View.GONE, loading.visibility)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    @Config(qualifiers = "w1920dp-h1080dp-land")
    fun tv_layout_uses_wide_category_grid_and_back_steps_category_then_finishes() {
        val controller = Robolectric.buildActivity(SportsReplaysActivity::class.java).create().start().resume()
        val activity = controller.get()
        try {
            val list = activity.window.decorView.findViewWithTag<RecyclerView>("replays-grid")
            assertEquals(6, (list.layoutManager as GridLayoutManager).spanCount)

            setBucket(activity, LiveSportsClassifier.MLB)
            activity.onBackPressed()
            val heading = activity.window.decorView.findViewWithTag<TextView>("replays-heading")
            assertEquals("REPLAYS", heading.text.toString())
            activity.onBackPressed()
            assertTrue(activity.isFinishing)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-port")
    fun replay_adapter_long_title_fits_mobile_and_focus_frame_uses_3dp_inset() {
        val context = RuntimeEnvironment.getApplication()
        val adapter = SportsReplayAdapter(context, {}, {})
        val now = System.currentTimeMillis()
        val entry = SportsReplayCatalogue.Entry(
            channel = Channel("44", "MLB Network", "44", null, null, "https://provider/live/user/pass/44.ts", "44", true, 7),
            programme = Programme(
                "Los Angeles Dodgers at Milwaukee Brewers Championship Replay With Extra Innings And Extended Play By Play",
                null,
                now - 4_000_000L,
                now - 3_000_000L,
                archiveAvailable = true,
            ),
            bucket = LiveSportsClassifier.MLB,
        )
        adapter.submitList(listOf(SportsReplayAdapter.Item(LiveSportsClassifier.MLB, entry)))

        val parent = RecyclerView(context)
        val holder = adapter.onCreateViewHolder(parent, 0)
        adapter.onBindViewHolder(holder, 0)

        val width = dp(context, 390f)
        holder.frame.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        holder.frame.layout(0, 0, width, holder.frame.measuredHeight)

        val title = holder.frame.findViewWithTag<TextView>("replay-name-${entry.key}")
        assertNotNull(title)
        assertEquals(4, title.maxLines)
        assertEquals(dp(context, 3f), holder.frame.paddingLeft)
        assertEquals(dp(context, 3f), holder.frame.paddingTop)
    }

    @Test
    fun open_programme_intent_contains_only_channel_and_start_without_credentials_or_game_blob() {
        val activity = Robolectric.buildActivity(SportsReplaysActivity::class.java).create().get()
        val now = System.currentTimeMillis()
        val entry = SportsReplayCatalogue.Entry(
            channel = Channel("55", "MLB Network", "55", null, null, "https://provider/live/user/pass/55.ts", "55", true, 7),
            programme = Programme("Yankees vs Red Sox", null, now - 5_000_000L, now - 4_000_000L, archiveAvailable = true),
            bucket = LiveSportsClassifier.MLB,
        )

        SportsReplayActivity.openProgramme(activity, entry)
        val started = Shadows.shadowOf(activity).nextStartedActivity
        assertEquals(SportsReplayActivity::class.java.name, started.component?.className)
        assertEquals("55", started.getStringExtra("channel"))
        assertEquals(entry.programme.startMs, started.getLongExtra("programme-start", -1))
        assertNull(started.getStringExtra("game"))
        assertNull(started.getStringExtra("url"))
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-port")
    fun controlled_archive_fixture_loads_verified_row_then_click_opens_replay_and_pause_cancels_fetch() {
        val now = System.currentTimeMillis()
        val start = now - 4 * 3_600_000L
        val stop = now - 3 * 3_600_000L
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when {
                    request.path?.contains("action=get_simple_data_table") == true -> MockResponse().setResponseCode(200).setBody(
                        """
                        {"epg_listings":[
                          {"title":"Yankees vs Red Sox","start_timestamp":${start / 1000},"stop_timestamp":${stop / 1000},"has_archive":1}
                        ]}
                        """.trimIndent(),
                    )

                    request.path?.contains("player_api.php") == true -> MockResponse().setResponseCode(200)
                        .setBody("{\"server_info\":{\"timezone\":\"America/New_York\"}}")

                    request.path?.contains("/timeshift/") == true -> MockResponse().setResponseCode(200)
                        .setBody("#EXTM3U\n#EXTINF:5,\nsegment-0.ts\n")

                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()

        val old = BundleHolder.current
        BundleHolder.current = XtreamBundle(
            provider = Provider("p", "p", "h", "443", "https"),
            categories = listOf(Category("sport", "Sports")),
            channels = listOf(
                Channel(
                    id = "777",
                    name = "MLB Network",
                    lcn = "777",
                    logoUrl = null,
                    categoryId = "sport",
                    streamUrl = server.url("/provider/live/user/pass/777.ts").toString(),
                    epgChannelId = "777",
                    archiveEnabled = true,
                    archiveDays = 7,
                ),
            ),
            epg = emptyMap(),
            generatedAt = now,
        )

        val controller = Robolectric.buildActivity(SportsReplaysActivity::class.java).create().start().resume()
        val activity = controller.get()
        try {
            waitUntil(3_500) {
                val recycler = activity.window.decorView.findViewWithTag<RecyclerView>("replays-grid")
                recycler.adapter?.itemCount == 1
            }

            // One available sport category should appear first, then selecting it shows matching replay rows.
            val categories = activity.window.decorView.findViewWithTag<RecyclerView>("replays-grid")
            val categoryAdapter = categories.adapter as SportsReplayAdapter
            val categoryHolder = categoryAdapter.onCreateViewHolder(categories, 0)
            categoryAdapter.onBindViewHolder(categoryHolder, 0)
            assertEquals("sport-category-tile-${LiveSportsClassifier.MLB}", categoryHolder.frame.tag)
            setBucket(activity, LiveSportsClassifier.MLB)

            waitUntil(3_000) {
                val recycler = activity.window.decorView.findViewWithTag<RecyclerView>("replays-grid")
                val adapter = recycler.adapter as SportsReplayAdapter
                if (adapter.itemCount == 0) return@waitUntil false
                val probe = adapter.onCreateViewHolder(recycler, 0)
                adapter.onBindViewHolder(probe, 0)
                (probe.frame.tag as? String)?.startsWith("replay-match-") == true
            }

            val recycler = activity.window.decorView.findViewWithTag<RecyclerView>("replays-grid")
            val adapter = recycler.adapter as SportsReplayAdapter
            val holder = adapter.onCreateViewHolder(recycler, 0)
            adapter.onBindViewHolder(holder, 0)
            assertTrue((holder.frame.tag as? String)?.startsWith("replay-match-") == true)
            holder.frame.performClick()

            val started = Shadows.shadowOf(activity).nextStartedActivity
            assertNotNull(started)
            assertEquals(SportsReplayActivity::class.java.name, started.component?.className)
            assertEquals("777", started.getStringExtra("channel"))
            assertTrue(started.hasExtra("programme-start"))
            assertNull(started.getStringExtra("game"))

            controller.pause()
            val fetch = readField<kotlinx.coroutines.Job?>(activity, "fetch")
            assertTrue(fetch == null || !fetch.isActive)
        } finally {
            controller.stop().destroy()
            BundleHolder.current = old
            server.shutdown()
        }
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-port")
    fun startup_with_blocked_archive_metadata_publishes_non_empty_channel_state_before_probe_completion() {
        val now = System.currentTimeMillis()
        val start = now - 2 * 3_600_000L
        val stop = now - 90 * 60_000L
        val tableRequested = CountDownLatch(1)
        val releaseTable = CountDownLatch(1)
        val server = server(object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when {
                    request.path?.contains("action=get_simple_data_table") == true -> {
                        tableRequested.countDown()
                        releaseTable.await(5, TimeUnit.SECONDS)
                        MockResponse().setResponseCode(200).setBody(row("Yankees vs Red Sox", start, stop))
                    }

                    request.path?.contains("player_api.php") == true -> MockResponse().setResponseCode(200)
                        .setBody("{\"server_info\":{\"timezone\":\"America/New_York\"}}")

                    request.path?.contains("/timeshift/") == true -> {
                        val bytes = ByteArray(4096)
                        bytes[0] = 0x47
                        bytes[188] = 0x47
                        bytes[376] = 0x47
                        MockResponse().setResponseCode(200).setBody(Buffer().write(bytes))
                    }

                    else -> MockResponse().setResponseCode(404)
                }
            }
        })
        val previous = BundleHolder.current
        BundleHolder.current = XtreamBundle(
            provider = Provider("p", "p", "h", "443", "https"),
            categories = listOf(Category("sport", "Sports")),
            channels = listOf(
                Channel(
                    id = "901",
                    name = "MLB Network",
                    lcn = "901",
                    logoUrl = null,
                    categoryId = "sport",
                    streamUrl = server.url("/provider/live/user/pass/901.ts").toString(),
                    epgChannelId = "901",
                    archiveEnabled = true,
                    archiveDays = 7,
                ),
            ),
            epg = emptyMap(),
            generatedAt = now,
        )

        val controller = Robolectric.buildActivity(SportsReplaysActivity::class.java).create().start().resume()
        val activity = controller.get()
        try {
            waitFor(2_000) { tableRequested.count == 0L }
            assertEquals(0L, tableRequested.count)
            waitFor(2_000) {
                val state = readField<SportsReplayDiscovery.State?>(activity, "state")
                val label = activity.window.decorView.findViewWithTag<TextView>("replays-loading-label")
                state != null && state.channels.size == 1 && label.text.contains("0/1") && !label.text.contains("0/0")
            }

            val blockingState = readField<SportsReplayDiscovery.State?>(activity, "state")
            val label = activity.window.decorView.findViewWithTag<TextView>("replays-loading-label")
            assertNotNull(blockingState)
            assertEquals(1, blockingState!!.channels.size)
            assertTrue(label.text.contains("0/1"))
            assertTrue(!label.text.contains("0/0"))

            releaseTable.countDown()

            waitFor(4_000) {
                val recycler = activity.window.decorView.findViewWithTag<RecyclerView>("replays-grid")
                recycler.adapter?.itemCount == 1
            }
            val categories = activity.window.decorView.findViewWithTag<RecyclerView>("replays-grid")
            val categoryAdapter = categories.adapter as SportsReplayAdapter
            val categoryHolder = categoryAdapter.onCreateViewHolder(categories, 0)
            categoryAdapter.onBindViewHolder(categoryHolder, 0)
            assertEquals("sport-category-tile-${LiveSportsClassifier.MLB}", categoryHolder.frame.tag)

            setBucket(activity, LiveSportsClassifier.MLB)
            waitFor(4_000) {
                val recycler = activity.window.decorView.findViewWithTag<RecyclerView>("replays-grid")
                val adapter = recycler.adapter as SportsReplayAdapter
                if (adapter.itemCount == 0) return@waitFor false
                val holder = adapter.onCreateViewHolder(recycler, 0)
                adapter.onBindViewHolder(holder, 0)
                (holder.frame.tag as? String)?.startsWith("replay-match-") == true
            }
        } finally {
            releaseTable.countDown()
            controller.pause().stop().destroy()
            BundleHolder.current = previous
            server.shutdown()
        }
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-port")
    fun channel_source_enumeration_failure_exits_loading_with_safe_retry_message() {
        val previous = BundleHolder.current
        val unstableChannels = object : AbstractList<Channel>() {
            private val seed = Channel(
                id = "c-1",
                name = "MLB Network",
                lcn = "101",
                logoUrl = null,
                categoryId = "sport",
                streamUrl = "https://provider/live/user/pass/101.ts",
                epgChannelId = "c-1",
                archiveEnabled = true,
                archiveDays = 7,
            )
            override val size: Int = 1
            override fun get(index: Int): Channel = seed
            override fun iterator(): Iterator<Channel> = throw IllegalStateException("Enumeration failed")
        }
        BundleHolder.current = XtreamBundle(
            provider = Provider("p", "p", "h", "443", "https"),
            categories = listOf(Category("sport", "Sports")),
            channels = unstableChannels,
            epg = emptyMap(),
            generatedAt = System.currentTimeMillis(),
        )

        val controller = Robolectric.buildActivity(SportsReplaysActivity::class.java).create().start().resume()
        val activity = controller.get()
        try {
            waitFor(2_500) {
                val status = activity.window.decorView.findViewWithTag<TextView>("replays-status")
                status.text.contains("could not finish", ignoreCase = true)
            }
            val status = activity.window.decorView.findViewWithTag<TextView>("replays-status")
            val retry = activity.window.decorView.findViewWithTag<TextView>("replays-retry")
            val loading = activity.window.decorView.findViewWithTag<View>("replays-loading-progress-band")
            assertTrue(status.text.contains("could not finish", ignoreCase = true))
            assertEquals(View.GONE, loading.visibility)
            assertTrue(retry.isEnabled)
        } finally {
            controller.pause().stop().destroy()
            BundleHolder.current = previous
        }
    }

    @Test
    @Config(qualifiers = "w390dp-h844dp-port")
    fun zero_eligible_replay_channels_finishes_scan_without_indefinite_initial_loading() {
        val previous = BundleHolder.current
        BundleHolder.current = XtreamBundle(
            provider = Provider("p", "p", "h", "443", "https"),
            categories = listOf(Category("sport", "Sports")),
            channels = listOf(
                Channel(
                    id = "10",
                    name = "News 24",
                    lcn = "10",
                    logoUrl = null,
                    categoryId = "sport",
                    streamUrl = "https://provider/live/user/pass/10.ts",
                    epgChannelId = "10",
                    archiveEnabled = false,
                    archiveDays = null,
                ),
            ),
            epg = emptyMap(),
            generatedAt = System.currentTimeMillis(),
        )

        val controller = Robolectric.buildActivity(SportsReplaysActivity::class.java).create().start().resume()
        val activity = controller.get()
        try {
            waitFor(2_500) {
                val status = activity.window.decorView.findViewWithTag<TextView>("replays-status")
                status.text.contains("not reporting any supported catch-up channels", ignoreCase = true)
            }
            val status = activity.window.decorView.findViewWithTag<TextView>("replays-status")
            val loading = activity.window.decorView.findViewWithTag<View>("replays-loading-progress-band")
            val retry = activity.window.decorView.findViewWithTag<TextView>("replays-retry")
            assertTrue(status.text.contains("not reporting any supported catch-up channels", ignoreCase = true))
            assertEquals(View.GONE, loading.visibility)
            assertTrue(retry.isEnabled)
        } finally {
            controller.pause().stop().destroy()
            BundleHolder.current = previous
        }
    }

    private fun setBucket(activity: SportsReplaysActivity, bucket: String?) {
        val field = SportsReplaysActivity::class.java.getDeclaredField("bucket")
        field.isAccessible = true
        field.set(activity, bucket)
        val render = SportsReplaysActivity::class.java.getDeclaredMethod("render")
        render.isAccessible = true
        render.invoke(activity)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(30, TimeUnit.MILLISECONDS)
            if (condition()) return
            Thread.sleep(20)
        }
        throw AssertionError("Condition not met within ${timeoutMs}ms")
    }

    private fun waitFor(timeoutMs: Long, condition: () -> Boolean) = waitUntil(timeoutMs, condition)

    private fun server(dispatcher: Dispatcher): MockWebServer = MockWebServer().apply {
        this.dispatcher = dispatcher
        start()
    }

    private fun row(title: String, startMs: Long, stopMs: Long): String =
        """
        {"epg_listings":[
          {"title":"$title","start_timestamp":${startMs / 1000},"stop_timestamp":${stopMs / 1000},"has_archive":1}
        ]}
        """.trimIndent()

    @Suppress("UNCHECKED_CAST")
    private fun <T> readField(target: Any, name: String): T {
        val field = target::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(target) as T
    }
}
