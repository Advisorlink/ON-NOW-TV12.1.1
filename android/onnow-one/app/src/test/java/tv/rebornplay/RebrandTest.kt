package tv.rebornplay

import org.junit.Assert.*
import org.junit.Test

class RebrandTest {
    @Test fun oldBrandBecomesReborn() {
        assertEquals("Reborn Play is loading your program", Rebrand.fix("ON NOW TV V2 is loading your program"))
        assertEquals("NOW PLAYING · REBORN PLAY", Rebrand.fix("NOW PLAYING · ON NOW TV V2"))
        assertEquals("PAUSED · REBORN PLAY", Rebrand.fix("PAUSED · ON NOW TV"))
        assertEquals("REBORN PLAY", Rebrand.fix("ON NOW TV  V2"))
        assertEquals("Still stuck?  Contact Reborn Play Support.", Rebrand.fix("Still stuck?  Contact ON NOW TV Support."))
    }
    @Test fun guideWordingIsLeftAlone() { assertNull(Rebrand.fix("ON NOW")); assertNull(Rebrand.fix("On Now")); assertNull(Rebrand.fix("What's on now")); assertNull(Rebrand.fix("Sky Sports Main Event")) }
}
