package tv.rebornplay

import org.junit.Assert.*
import org.junit.Test

class LockdownTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    @Test fun acceptsTheRealSigningCertificate() = assertTrue(Lockdown.matches(hex("0e16e29766df3609602fa7c98fe6c2293eb009d2441611134681b7918582d103")))
    @Test fun rejectsAnyOtherCertificate() {
        val other = hex("0e16e29766df3609602fa7c98fe6c2293eb009d2441611134681b7918582d103"); other[31] = (other[31].toInt() xor 1).toByte()
        assertFalse(Lockdown.matches(other)); assertFalse(Lockdown.matches(ByteArray(32)))
    }
    @Test fun brandStrings() { assertEquals("Reborn Play", Lockdown.label()); assertEquals("tv.onnowtv.one", Lockdown.pkg()) }
}
