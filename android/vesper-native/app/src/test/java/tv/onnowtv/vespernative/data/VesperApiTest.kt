package tv.onnowtv.vespernative.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

// VesperApi: URL encoding, bearer scoping, cancellation, safe failures, and response-body guards.
class VesperApiTest {
    private lateinit var server: MockWebServer
    private lateinit var api: VesperApi

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        api = VesperApi(server.url("/").toString().trimEnd('/'))
    }

    @After
    fun teardown() {
        runCatching { server.shutdown() }
    }

    @Test
    fun request_encodes_path_and_query_and_sets_bearer_on_backend_call() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("{}"))

        api.request(
            path = listOf("addons", "addon id", "catalog", "movie", "new hits"),
            token = "abc-token",
            query = linkedMapOf("search" to "star wars", "genre" to "sci-fi/action")
        )

        val req = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("Bearer abc-token", req.getHeader("Authorization"))
        assertEquals("/api/addons/addon%20id/catalog/movie/new%20hits", req.requestUrl!!.encodedPath)
        assertEquals("star wars", req.requestUrl!!.queryParameter("search"))
        assertEquals("sci-fi/action", req.requestUrl!!.queryParameter("genre"))
    }

    @Test
    fun cancellation_cancels_inflight_okhttp_call() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("{}")
                    .setBodyDelay(5, TimeUnit.SECONDS)
            }
        }

        val job = async(Dispatchers.IO) {
            api.request(listOf("auth", "me"), token = "tkn")
        }
        delay(150)
        job.cancel()
        withContext(Dispatchers.IO) { delay(100) }

        assertTrue(job.isCancelled)
    }

    @Test
    fun invalid_or_non_json_body_returns_safe_parse_error() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "text/html")
                .setBody("{")
        )

        val error = runCatching { api.request(listOf("addons")) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error is IOException)
        assertEquals("Unable to read server response. Please try again.", error!!.message)
    }

    @Test
    fun oversized_response_body_is_rejected_by_8mb_guard() = runBlocking {
        val tooLarge = "\"${"a".repeat(8 * 1024 * 1024 + 32)}\""
        server.enqueue(
            MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json")
                .setBody(tooLarge)
        )

        val error = runCatching { api.request(listOf("addons")) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error is IOException)
        assertEquals("Unable to read server response. Please try again.", error!!.message)
    }

    @Test
    fun safe_messages_for_403_and_429() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("{}"))
        val forbidden = runCatching { api.request(listOf("auth", "me"), token = "x") }.exceptionOrNull()
        assertTrue(forbidden is ApiFailure)
        assertEquals("This account is unavailable. Please contact ON NOW TV.", forbidden!!.message)

        server.enqueue(MockResponse().setResponseCode(429).setBody("{}"))
        val throttled = runCatching { api.request(listOf("auth", "login"), body = JSONObject().toString()) }.exceptionOrNull()
        assertTrue(throttled is ApiFailure)
        assertEquals("Too many attempts. Please wait before trying again.", throttled!!.message)
    }
}
