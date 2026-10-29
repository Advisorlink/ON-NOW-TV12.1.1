package tv.onnowtv.livetv.data

import android.content.Context
import org.robolectric.RuntimeEnvironment
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/** The "guide gone after 3 days" regression: an expired cache must be topped up from the server guide. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class GuideKeeperTest {
    private lateinit var ctx: Context
    private lateinit var server: MockWebServer
    private val hour = 3_600_000L

    @Before fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        AuthStore.saveCredentials(ctx, "user", "pass")
        server = MockWebServer().apply { start() }
    }

    @After fun tearDown() { server.shutdown(); EpgCache.delete(ctx) }

    private fun serverGuide(channels: Int, nowSec: Long): MockResponse {
        val epg = JSONObject()
        for (c in 0 until channels) {
            val arr = JSONArray()
            for (h in -1 until 71) arr.put(JSONObject().put("title", "Show $c/$h").put("description", if (h % 2 == 0) "desc" else JSONObject.NULL)
                .put("start", nowSec + h * 3600).put("stop", nowSec + (h + 1) * 3600).put("live", h == 0))
            epg.put("${1000 + c}", arr)
        }
        val body = JSONObject().put("epg", epg).put("epg_fetched_at", nowSec).put("generated_at", nowSec).toString()
        val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(body.toByteArray()) } }.toByteArray()
        return MockResponse().setHeader("Content-Encoding", "gzip").setBody(Buffer().write(gz))
    }

    @Test fun expired_cache_is_refilled_with_three_days_from_server() = runBlocking {
        val now = System.currentTimeMillis()
        // Disk state on day 4 after sign-in: every programme already ended, stamp ancient.
        EpgCache.mergeChannel(ctx, "1000", listOf(Programme("Old", null, now - 2 * hour, now - hour)))
        assertTrue(GuideKeeper.needsRefresh(ctx))

        server.enqueue(serverGuide(150, now / 1000))
        val merged = GuideKeeper.mergeFromServer(ctx, AuthStore.sessionId(ctx), keepIds = null, backendBase = server.url("/").toString())

        assertEquals(150, merged)
        val ch = EpgCache.loadChannel(ctx, "1000")!!
        assertTrue("something is on now", ch.any { it.startMs <= now && it.stopMs > now })
        assertTrue("guide reaches ~3 days ahead", ch.maxOf { it.stopMs } > now + 69 * hour)
        assertTrue(EpgCache.horizonMs(ctx) > now + 69 * hour)
        assertFalse("fresh guide must not trigger another refresh", GuideKeeper.needsRefresh(ctx))
    }

    @Test fun keep_ids_filter_and_failed_server_leave_cache_untouched() = runBlocking {
        val now = System.currentTimeMillis()
        server.enqueue(serverGuide(150, now / 1000))
        val merged = GuideKeeper.mergeFromServer(ctx, AuthStore.sessionId(ctx), keepIds = setOf("1001", "1002"), backendBase = server.url("/").toString())
        assertEquals(2, merged)
        assertTrue("below threshold: not stamped fresh", GuideKeeper.needsRefresh(ctx))

        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(0, GuideKeeper.mergeFromServer(ctx, AuthStore.sessionId(ctx), keepIds = null, backendBase = server.url("/").toString()))
        assertTrue(EpgCache.loadChannel(ctx, "1001")!!.isNotEmpty())
    }
}
