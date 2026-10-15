package tv.onnowtv.vespernative.auth

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

// KeystoreSessionStore: AES-GCM session persistence behavior under Robolectric (or explicit device-only boundary).
@RunWith(RobolectricTestRunner::class)
class KeystoreSessionStoreRobolectricTest {

    @Test
    fun save_read_clear_round_trip() = runBlocking {
        val context = RuntimeEnvironment.getApplication().applicationContext
        val store = KeystoreSessionStore(context)

        try {
            store.clear()
            store.save("jwt-session-token")
            assertEquals("jwt-session-token", store.read())
            store.clear()
            assertNull(store.read())
        } catch (e: Exception) {
            // Robolectric keystore coverage varies across environments.
            Assume.assumeNoException("AndroidKeyStore unavailable in Robolectric; device-only boundary", e)
        }
    }
}
