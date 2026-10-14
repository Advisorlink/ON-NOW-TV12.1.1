package tv.onnowtv.livetv.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsFixtureMetadataTest {

    private fun baseFixture(id: String = "401892502", sport: String = "hockey", league: String = "nhl") = JSONObject()
        .put("id", id)
        .put("sport", sport)
        .put("league", league)
        .put("state", "in")
        .put("startMs", 1791945000000L)
        .put("broadcasts", JSONArray().put("ESPN+"))
        .put("home", JSONObject().put("id", "8").put("name", "Los Angeles Kings").put("short", "Kings").put("abbr", "LAK").put("score", "1"))
        .put("away", JSONObject().put("id", "6").put("name", "Edmonton Oilers").put("short", "Oilers").put("abbr", "EDM").put("score", "3"))
        .put("plays", JSONArray().put(JSONObject().put("id", "p1")))

    @Test
    fun sparse_or_nullish_broadcasts_fall_back_to_previous_but_fresh_values_replace() {
        val previous = baseFixture()
        val sparseIncoming = baseFixture().put("broadcasts", JSONArray().put("null").put(""))
        val mergedSparse = SportsFixtureMetadata.merge(previous, sparseIncoming)
        assertEquals(listOf("ESPN+"), (0 until mergedSparse.optJSONArray("broadcasts").length()).map { mergedSparse.optJSONArray("broadcasts").optString(it) })

        val freshIncoming = baseFixture().put("broadcasts", JSONArray().put("ABTV").put("ESPN+"))
        val mergedFresh = SportsFixtureMetadata.merge(previous, freshIncoming)
        assertEquals(listOf("ABTV", "ESPN+"), (0 until mergedFresh.optJSONArray("broadcasts").length()).map { mergedFresh.optJSONArray("broadcasts").optString(it) })
    }

    @Test
    fun metadata_never_carries_to_other_fixture_identity_or_opponent() {
        val previous = baseFixture(id = "g-old")
        val incomingOtherId = baseFixture(id = "g-new").put("broadcasts", JSONArray())
        val mergedOtherId = SportsFixtureMetadata.merge(previous, incomingOtherId)
        assertEquals("g-new", mergedOtherId.optString("id"))
        assertEquals(0, mergedOtherId.optJSONArray("broadcasts").length())

        val incomingOtherOpponent = baseFixture(id = "g-old").put("away", JSONObject().put("id", "99").put("name", "Different Team"))
        val mergedOpponent = SportsFixtureMetadata.merge(previous, incomingOtherOpponent)
        assertEquals("99", mergedOpponent.optJSONObject("away").optString("id"))
        assertFalse(mergedOpponent.optString("away").contains("Oilers"))
    }

    @Test
    fun incoming_scores_state_plays_and_zero_values_are_preserved_and_start_falls_back_only_when_invalid() {
        val previous = baseFixture().put("startMs", 1791945000000L)
        val incoming = baseFixture()
            .put("state", "post")
            .put("startMs", 0)
            .put("home", baseFixture().optJSONObject("home").put("score", "0"))
            .put("away", baseFixture().optJSONObject("away").put("score", "0"))
            .put("plays", JSONArray())

        val merged = SportsFixtureMetadata.merge(previous, incoming)
        assertEquals("post", merged.optString("state"))
        assertEquals("0", merged.optJSONObject("home").optString("score"))
        assertEquals("0", merged.optJSONObject("away").optString("score"))
        assertEquals(0, merged.optJSONArray("plays").length())
        assertEquals(1791945000000L, merged.optLong("startMs"))
    }

    @Test
    fun literal_null_team_names_are_not_retained() {
        val previous = baseFixture()
        val incoming = baseFixture()
            .put("home", JSONObject().put("id", "8").put("name", "null").put("short", "null").put("abbr", "null"))
            .put("away", JSONObject().put("id", "6").put("name", "").put("short", "").put("abbr", ""))

        val merged = SportsFixtureMetadata.merge(previous, incoming)
        assertEquals("Los Angeles Kings", merged.optJSONObject("home").optString("name"))
        assertEquals("Kings", merged.optJSONObject("home").optString("short"))
        assertEquals("LAK", merged.optJSONObject("home").optString("abbr"))
        assertEquals("Edmonton Oilers", merged.optJSONObject("away").optString("name"))
    }
}
