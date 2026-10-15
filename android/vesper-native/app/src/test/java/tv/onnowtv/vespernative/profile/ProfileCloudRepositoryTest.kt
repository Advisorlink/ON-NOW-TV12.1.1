package tv.onnowtv.vespernative.profile

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import tv.onnowtv.vespernative.data.Account
import tv.onnowtv.vespernative.data.ApiFailure
import tv.onnowtv.vespernative.data.VesperApi
import java.io.IOException
import java.util.concurrent.TimeUnit

// ProfileCloudRepository: bound-token pull semantics, auth/error propagation, and delayed-response session switching.
class ProfileCloudRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var api: VesperApi
    private lateinit var account: Account
    private var activeToken: String? = "token-a"

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        api = VesperApi(server.url("/").toString().trimEnd('/'))
        account = Account("acc-1", "test user", "Test")
        activeToken = "token-a"
    }

    @After
    fun teardown() {
        runCatching { server.shutdown() }
    }

    @Test
    fun pull_uses_get_with_bound_bearer_and_parses_cloud_snapshot() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "found": true,
                      "updated_at": 1700001,
                      "data": {
                        "onnowtv-profiles-v1:test_user": [{"id":"p-1","name":"Alpha","avatarId":"a"}]
                      }
                    }
                    """.trimIndent()
                )
        )

        val repository = ProfileCloudRepository(api, account, "token-a") { activeToken }
        val snapshot = repository.pull()

        val req = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("GET", req.method)
        assertEquals("/api/vesper/sync/pull", req.path)
        assertEquals("Bearer token-a", req.getHeader("Authorization"))
        assertNotNull(snapshot)
        assertEquals(listOf("p-1"), snapshot!!.profiles.map { it.id })
    }

    @Test
    fun no_request_is_made_when_active_session_missing_or_changed() = runBlocking {
        val repository = ProfileCloudRepository(api, account, "token-a") { activeToken }

        activeToken = null
        val missing = runCatching { repository.pull() }.exceptionOrNull()
        assertTrue(missing is ApiFailure && (missing as ApiFailure).status == 401)
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))

        activeToken = "token-b"
        val switched = runCatching { repository.pull() }.exceptionOrNull()
        assertTrue(switched is CancellationException)
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun delayed_response_is_discarded_if_token_switches_midflight() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                    .setBody("{" + "\"found\":true,\"data\":{}}")
                    .setBodyDelay(800, TimeUnit.MILLISECONDS)
            }
        }

        val repository = ProfileCloudRepository(api, account, "token-a") { activeToken }
        val job = async(Dispatchers.IO) { runCatching { repository.pull() }.exceptionOrNull() }
        delay(120)
        activeToken = "token-b"
        val err = job.await()

        assertTrue(err is CancellationException)
        val req = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull(req)
        assertEquals("Bearer token-a", req!!.getHeader("Authorization"))
    }

    @Test
    fun old_repository_cannot_request_using_new_login_token() = runBlocking {
        val oldRepository = ProfileCloudRepository(api, account, "token-a") { activeToken }
        activeToken = "token-new"
        val err = runCatching { oldRepository.pull() }.exceptionOrNull()
        assertTrue(err is CancellationException)
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun pull_propagates_401_403_and_offline_failures() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(403).setBody("{}"))

        val repository = ProfileCloudRepository(api, account, "token-a") { activeToken }

        val unauthorized = runCatching { repository.pull() }.exceptionOrNull()
        assertTrue(unauthorized is ApiFailure && (unauthorized as ApiFailure).status == 401)

        val forbidden = runCatching { repository.pull() }.exceptionOrNull()
        assertTrue(forbidden is ApiFailure && (forbidden as ApiFailure).status == 403)

        server.shutdown()
        val offline = runCatching { repository.pull() }.exceptionOrNull()
        assertTrue(offline is IOException)
    }
}
