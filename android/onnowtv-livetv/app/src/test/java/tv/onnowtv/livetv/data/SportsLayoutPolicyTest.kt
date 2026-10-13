package tv.onnowtv.livetv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SportsLayoutPolicyTest {

    // Sports hub/grid policy across requested width buckets.
    @Test
    fun columns_match_expected_breakpoints() {
        assertEquals(1, SportsLayoutPolicy.columns(320))
        assertEquals(1, SportsLayoutPolicy.columns(390))
        assertEquals(2, SportsLayoutPolicy.columns(600))
        assertEquals(2, SportsLayoutPolicy.columns(768))
        assertEquals(2, SportsLayoutPolicy.columns(840))
        assertEquals(2, SportsLayoutPolicy.columns(960))
        assertEquals(2, SportsLayoutPolicy.columns(1024))
        assertEquals(3, SportsLayoutPolicy.columns(1440))
    }

    // Game centre stacking policy for phone/tablet vs TV and short landscape.
    @Test
    fun stacked_game_policy_respects_phone_tablet_tv_and_short_landscape() {
        assertTrue(SportsLayoutPolicy.stackedGame(widthDp = 390, heightDp = 844, television = false))
        assertTrue(SportsLayoutPolicy.stackedGame(widthDp = 600, heightDp = 1024, television = false))
        assertFalse(SportsLayoutPolicy.stackedGame(widthDp = 960, heightDp = 540, television = false))
        assertTrue(SportsLayoutPolicy.stackedGame(widthDp = 900, heightDp = 460, television = false))
        assertFalse(SportsLayoutPolicy.stackedGame(widthDp = 768, heightDp = 1024, television = true))
    }

    @Test
    fun portrait_helper_detects_orientation() {
        assertTrue(SportsLayoutPolicy.portrait(390, 844))
        assertFalse(SportsLayoutPolicy.portrait(844, 390))
    }
}
