package tv.onnowtv.livetv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReplayVerificationCacheTest {

    @Test
    fun get_returns_value_before_expiry_then_null_after_expiry() {
        var now = 1_000L
        val cache = ReplayVerificationCache<String> { now }
        cache.put("k", "v", ttlMs = 500)

        assertEquals("v", cache.get("k"))
        now = 1_501L
        assertNull(cache.get("k"))
    }

    @Test
    fun put_enforces_capacity_128_and_evicts_oldest() {
        var now = 10_000L
        val cache = ReplayVerificationCache<Int> { now }

        repeat(128) { i ->
            cache.put("k-$i", i, ttlMs = 60_000)
            now += 1
        }
        cache.put("k-128", 128, ttlMs = 60_000)

        assertNull(cache.get("k-0"))
        assertEquals(128, cache.get("k-128"))
    }

    @Test
    fun put_prunes_expired_entries_before_capacity_check() {
        var now = 2_000L
        val cache = ReplayVerificationCache<String> { now }
        cache.put("old", "x", ttlMs = 10)
        now = 2_100L
        cache.put("fresh", "y", ttlMs = 1000)

        assertNull(cache.get("old"))
        assertEquals("y", cache.get("fresh"))
    }

    @Test
    fun remove_invalidates_entry_immediately() {
        val cache = ReplayVerificationCache<String> { 3_000L }
        cache.put("k", "v", ttlMs = 1_000)

        cache.remove("k")
        assertNull(cache.get("k"))
    }
}
