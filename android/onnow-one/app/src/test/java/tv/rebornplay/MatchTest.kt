package tv.rebornplay

import org.junit.Assert.*
import org.junit.Test

class MatchTest {
    private fun it(name: String, cat: String? = null) = Item("movie:1", "movie", name, null, null, category = cat)
    @Test fun sameTitleDifferentDressing() {
        assertEquals(Match.norm("The Hunger Games: Sunrise on the Reaping"), Match.norm("Hunger Games - Sunrise On The Reaping (2026) [4K]"))
        assertEquals(Match.norm("Fast & Furious"), Match.norm("Fast and Furious"))
        assertNotEquals(Match.norm("Avatar"), Match.norm("Avatar 2"))
    }
    @Test fun cameraCopiesAreNotHd() {
        assertFalse(Match.isHd(it("Wicked CAM"))); assertFalse(Match.isHd(it("Wicked", "Movies | HDCAM"))); assertFalse(Match.isHd(it("Wicked (TS)")))
        assertTrue(Match.isHd(it("Wicked"))); assertTrue(Match.isHd(it("Camp Rock", "Family"))); assertTrue(Match.isHd(it("Its Always Sunny")))
    }
    @Test fun yearWindow() { assertTrue(Match.yearOk(2025, 2026)); assertTrue(Match.yearOk(0, 2026)); assertFalse(Match.yearOk(2010, 2026)) }
}
