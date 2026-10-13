package tv.onnowtv.livetv.data

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

class SportsReplayThreadingTest {
    @Test fun discovery_traverses_channels_off_the_calling_thread_without_network() = runBlocking {
        val caller = Thread.currentThread().name
        val traversals = ConcurrentLinkedQueue<String>()
        val channel = Channel("thread-check", "Example", null, null, null,
            "https://example.test/live/demo/demo/1.ts", epgChannelId = null, archiveEnabled = false)
        val channels = object : AbstractList<Channel>() {
            override val size = 1
            override fun get(index: Int): Channel { traversals.add(Thread.currentThread().name); return channel }
        }
        val bundle = XtreamBundle(Provider("p", "p", "example.test", "443", "https"), emptyList(), channels, emptyMap(), 0)
        val game = JSONObject().put("state", "post").put("startMs", System.currentTimeMillis() - 60_000)
        assertNull(SportsReplayRepository.discover(game, bundle))
        assertTrue(traversals.isNotEmpty())
        assertTrue("Repository owns its Default dispatcher, regardless of the caller", traversals.all { it != caller })
    }
}