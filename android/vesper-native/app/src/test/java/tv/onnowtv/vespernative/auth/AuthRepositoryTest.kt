package tv.onnowtv.vespernative.auth

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import tv.onnowtv.vespernative.BuildConfig
import tv.onnowtv.vespernative.data.ApiFailure
import tv.onnowtv.vespernative.data.VesperApi
import java.io.IOException
import java.time.Instant

// AuthRepository: login payload/response handling, restore invalidation, and logout local-clear behavior.
class AuthRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var sessions: InMemorySessionStore
    private lateinit var repository: AuthRepository

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        sessions = InMemorySessionStore()
        repository = AuthRepository(VesperApi(server.url("/").toString().trimEnd('/')), sessions)
    }

    @After
    fun teardown() {
        runCatching { server.shutdown() }
    }

    @Test
    fun sign_in_posts_exact_json_and_returns_account() = runBlocking {
        server.enqueue(json(200, """
            {
              "access_token":"jwt-123",
              "token_type":"bearer",
              "expires_in":3600,
              "account":{"id":"acc-1","username":"testuser","label":"Test User"}
            }
        """.trimIndent()))

        val account = repository.signIn(" testuser ", "testpass123")

        assertEquals("acc-1", account.id)
        assertEquals("testuser", account.username)
        assertEquals("jwt-123", repository.token)
        assertEquals("jwt-123", sessions.read())

        val request = server.takeRequest()
        assertEquals("/api/auth/login", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertEquals("testuser", body.getString("username"))
        assertEquals("testpass123", body.getString("password"))
        assertEquals(BuildConfig.APPLICATION_ID, body.getString("client_id"))
        assertEquals(3, body.length())
    }

    @Test
    fun sign_in_does_not_persist_token_on_malformed_response() = runBlocking {
        server.enqueue(json(200, """
            {"account":{"id":"acc-1","username":"testuser","label":"Test User"}}
        """.trimIndent()))

        val error = runCatching { repository.signIn("testuser", "testpass123") }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error is IllegalArgumentException)
        assertNull(repository.token)
        assertNull(sessions.read())
    }

    @Test
    fun restore_401_clears_saved_session() = runBlocking {
        sessions.save("saved-token")
        server.enqueue(json(401, "{" + "\"detail\":\"unauthorized\"}"))

        val restored = repository.restore()

        assertNull(restored)
        assertNull(repository.token)
        assertNull(sessions.read())
    }

    @Test
    fun restore_network_outage_preserves_saved_token() = runBlocking {
        sessions.save("saved-token")
        server.shutdown()

        val error = runCatching { repository.restore() }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(error is IOException)
        assertNull(repository.token)
        assertEquals("saved-token", sessions.read())
    }

    @Test
    fun disabled_or_expired_account_fails_as_403() = runBlocking {
        server.enqueue(json(200, """
            {
              "access_token":"jwt-disabled",
              "account":{"id":"acc-1","username":"testuser","label":"Test User","status":"disabled"}
            }
        """.trimIndent()))
        val disabled = runCatching { repository.signIn("testuser", "testpass123") }.exceptionOrNull()
        assertTrue(disabled is ApiFailure && disabled.status == 403)
        assertNull(sessions.read())

        server.enqueue(json(200, """
            {
              "access_token":"jwt-expired",
              "account":{
                "id":"acc-1",
                "username":"testuser",
                "label":"Test User",
                "expires_at":"${Instant.now().minusSeconds(3600)}"
              }
            }
        """.trimIndent()))
        val expired = runCatching { repository.signIn("testuser", "testpass123") }.exceptionOrNull()
        assertTrue(expired is ApiFailure && expired.status == 403)
        assertNull(sessions.read())
    }

    @Test
    fun sign_out_clears_locally_before_slow_logout_response() = runBlocking {
        server.enqueue(json(200, """
            {
              "access_token":"jwt-123",
              "account":{"id":"acc-1","username":"testuser","label":"Test User"}
            }
        """.trimIndent()))
        repository.signIn("testuser", "testpass123")

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{}")
                .setBodyDelay(2, java.util.concurrent.TimeUnit.SECONDS)
        )

        var callbackAtMs = -1L
        val started = System.nanoTime()
        repository.signOut {
            callbackAtMs = (System.nanoTime() - started) / 1_000_000
        }
        val totalMs = (System.nanoTime() - started) / 1_000_000

        assertTrue("onLocalCleared should fire promptly", callbackAtMs in 0..700)
        assertTrue("Remote logout was delayed, call should outlive callback", totalMs >= 1800)
        assertNull(repository.token)
        assertNull(sessions.read())
    }

    private fun json(code: Int, body: String): MockResponse =
        MockResponse()
            .setResponseCode(code)
            .setHeader("Content-Type", "application/json")
            .setBody(body)
}

private class InMemorySessionStore : SessionStore {
    private var token: String? = null
    override suspend fun read(): String? = token
    override suspend fun save(token: String) {
        this.token = token
    }
    override suspend fun clear() {
        token = null
    }
}
