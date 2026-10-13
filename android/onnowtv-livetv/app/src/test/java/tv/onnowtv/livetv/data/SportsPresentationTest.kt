package tv.onnowtv.livetv.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SportsPresentationTest {

    private fun play(id: String): JSONObject = JSONObject().put("id", id)

    private fun wp(playId: String?, home: Any?): JSONObject {
        val o = JSONObject()
        if (playId != null) o.put("playId", playId)
        if (home != null) o.put("home", home)
        return o
    }

    @Test
    fun home_probability_returns_null_when_no_plays() {
        val result = SportsPresentation.homeProbability(emptyList(), listOf(wp("p1", 55.0)), 0)
        assertNull(result)
    }

    @Test
    fun home_probability_returns_null_for_invalid_index() {
        val result = SportsPresentation.homeProbability(listOf(play("p1")), listOf(wp("p1", 55.0)), 3)
        assertNull(result)
    }

    @Test
    fun home_probability_returns_null_when_points_invalid_or_missing() {
        val plays = listOf(play("p1"), play("p2"))
        val points = listOf(
            wp("p1", "bad"),
            wp("p2", -1),
        )
        val result = SportsPresentation.homeProbability(plays, points, 1)
        assertNull(result)
    }

    @Test
    fun home_probability_uses_closest_preceding_valid_point_only() {
        val plays = listOf(play("p1"), play("p2"), play("p3"))
        val points = listOf(
            wp("p1", 48.2),
            wp("p3", 66.1),
        )

        // Selected p2 has no own value. Must use preceding p1, not future p3.
        val result = SportsPresentation.homeProbability(plays, points, 1)
        assertEquals(48.2, result!!, 0.0001)
    }

    @Test
    fun home_probability_ignores_future_and_last_when_selected_earlier() {
        val plays = listOf(play("p1"), play("p2"), play("p3"), play("p4"))
        val points = listOf(
            wp("p4", 99.0),
            wp("p2", 41.0),
        )

        val result = SportsPresentation.homeProbability(plays, points, 1)
        assertEquals(41.0, result!!, 0.0001)
    }

    @Test
    fun value_suppresses_literal_null_and_blank() {
        val obj = JSONObject().put("a", "null").put("b", "")
        assertEquals("fallback", SportsPresentation.value(obj, "a", "fallback"))
        assertEquals("fallback", SportsPresentation.value(obj, "b", "fallback"))
    }
}
