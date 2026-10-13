package tv.onnowtv.livetv.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsResponseCacheTest {

    @Test
    fun game_ttl_mapping_matches_live_pre_post_unknown() {
        assertEquals(5_000L, SportsResponseCache.gameTtl(JSONObject().put("state", "in")))
        assertEquals(15_000L, SportsResponseCache.gameTtl(JSONObject().put("state", "pre")))
        assertEquals(120_000L, SportsResponseCache.gameTtl(JSONObject().put("state", "post")))
        assertEquals(5_000L, SportsResponseCache.gameTtl(JSONObject().put("state", "unknown")))
    }

    @Test
    fun load_revisit_force_and_ttl_boundary_behave_as_expected() = runBlocking {
        var now = 1_000L
        val cache = SportsResponseCache { now }
        var calls = 0

        suspend fun load(force: Boolean = false): JSONObject? = cache.load(
            key = "k",
            force = force,
            ttl = { 15L },
            valid = { true },
            fetch = {
                calls += 1
                JSONObject().put("id", "g1").put("v", calls)
            },
        )

        val first = load()
        val second = load()
        assertEquals(1, calls)
        assertEquals(1, first?.optInt("v"))
        assertEquals(1, second?.optInt("v"))

        val forced = load(force = true)
        assertEquals(2, calls)
        assertEquals(2, forced?.optInt("v"))

        now = 1_015L
        assertNotNull(load())
        assertEquals(3, calls)

        now = 1_016L
        val afterExpiry = load()
        assertEquals(3, calls)
        assertEquals(3, afterExpiry?.optInt("v"))
    }

    @Test
    fun defensive_json_copy_and_invalid_or_null_responses_not_cached() = runBlocking {
        var now = 5_000L
        val cache = SportsResponseCache { now }
        var calls = 0

        val one = cache.load(
            key = "copy",
            ttl = { 300L },
            valid = { it.optString("id") == "g1" },
            fetch = {
                calls += 1
                JSONObject().put("id", "g1").put("nested", JSONObject().put("n", 1))
            },
        )!!
        one.put("id", "mutated")
        one.optJSONObject("nested")?.put("n", 999)

        val two = cache.load("copy", ttl = { 300L }, valid = { true }, fetch = { null })!!
        assertEquals(1, calls)
        assertEquals("g1", two.optString("id"))
        assertEquals(1, two.optJSONObject("nested")?.optInt("n"))
        assertFalse(one === two)

        val invalid = cache.load(
            key = "invalid",
            ttl = { 300L },
            valid = { false },
            fetch = {
                calls += 1
                JSONObject().put("id", "bad")
            },
        )
        assertNull(invalid)

        val invalidRetry = cache.load(
            key = "invalid",
            ttl = { 300L },
            valid = { false },
            fetch = {
                calls += 1
                JSONObject().put("id", "bad2")
            },
        )
        assertNull(invalidRetry)
        assertTrue(calls >= 3)

        val nullResponse = cache.load("null", ttl = { 300L }, valid = { true }, fetch = { null })
        assertNull(nullResponse)
    }

    @Test
    fun cancellation_never_publishes_partial_result() = runBlocking {
        val cache = SportsResponseCache { 10_000L }
        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        var calls = 0

        val job = launch {
            cache.load(
                key = "cancel",
                ttl = { 1_000L },
                valid = { true },
                fetch = {
                    calls += 1
                    started.complete(Unit)
                    gate.await()
                    JSONObject().put("id", "cancelled")
                },
            )
        }

        started.await()
        job.cancelAndJoin()
        gate.complete(Unit)

        val second = cache.load(
            key = "cancel",
            ttl = { 1_000L },
            valid = { true },
            fetch = {
                calls += 1
                JSONObject().put("id", "fresh")
            },
        )
        assertEquals("fresh", second?.optString("id"))
        assertEquals(2, calls)
    }

    @Test
    fun bounded_eviction_and_oversized_payload_rules_hold() = runBlocking {
        val cache = SportsResponseCache { 100_000L }
        var calls = 0

        repeat(13) { i ->
            cache.load(
                key = "small-$i",
                ttl = { 10_000L },
                valid = { true },
                fetch = {
                    calls += 1
                    JSONObject().put("id", "small-$i").put("payload", "x".repeat(2000))
                },
            )
        }

        val firstReRead = cache.load(
            key = "small-0",
            ttl = { 10_000L },
            valid = { true },
            fetch = {
                calls += 1
                JSONObject().put("id", "small-0-refetched")
            },
        )
        assertEquals("small-0-refetched", firstReRead?.optString("id"))

        val huge = "x".repeat(500_200)
        var hugeCalls = 0
        val over = cache.load(
            key = "huge",
            ttl = { 10_000L },
            valid = { true },
            fetch = {
                hugeCalls += 1
                JSONObject().put("id", "huge").put("blob", huge)
            },
        )
        assertNotNull(over)

        val overRead = cache.load(
            key = "huge",
            ttl = { 10_000L },
            valid = { true },
            fetch = {
                hugeCalls += 1
                JSONObject().put("id", "huge-refetch")
            },
        )
        assertEquals(2, hugeCalls)
        assertEquals("huge-refetch", overRead?.optString("id"))
    }

    @Test
    fun load_executes_on_dispatchers_default_not_caller_thread() = runBlocking {
        val cache = SportsResponseCache { 7_000L }
        val callerThread = Thread.currentThread().name
        var fetchThread = ""

        cache.load(
            key = "thread",
            ttl = { 1_000L },
            valid = { true },
            fetch = {
                fetchThread = Thread.currentThread().name
                JSONObject().put("id", "g1")
            },
        )

        assertTrue(fetchThread.isNotBlank())
        assertFalse(fetchThread == callerThread)
    }
}
