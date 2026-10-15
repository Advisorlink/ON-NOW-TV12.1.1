package tv.onnowtv.vespernative.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

// CatalogRepository: addon shape parsing, essential shelves ordering, search/error behavior, metadata episodes, and cache/pagination.
class CatalogRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var api: VesperApi
    private lateinit var repository: CatalogRepository

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        api = VesperApi(server.url("/").toString().trimEnd('/'))
        repository = CatalogRepository(api) { "token-1" }
    }

    @After
    fun teardown() {
        runCatching { server.shutdown() }
    }

    @Test
    fun home_catalogs_returns_essential_rows_in_required_order() = runBlocking {
        server.enqueue(json(addonsPayload()))

        val rows = repository.homeCatalogs()

        assertEquals(listOf("New movies", "New series", "Popular movies", "Popular series"), rows.map { it.name })
        assertEquals(listOf("year", "year", "top", "top"), rows.map { it.id })
        assertEquals(listOf("movie", "series", "movie", "series"), rows.map { it.type })
    }

    @Test
    fun catalogs_marks_searchable_from_extra_metadata() = runBlocking {
        server.enqueue(json(addonsPayload()))

        val all = repository.catalogs()

        val searchable = all.filter { it.searchable }
        assertTrue(searchable.any { it.type == "movie" && it.id == "search" })
        assertTrue(searchable.any { it.type == "series" && it.id == "search" })
    }

    @Test
    fun search_de_duplicates_results_and_true_empty_is_not_error() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when {
                    request.path == "/api/addons" -> json(addonsPayload())
                    request.requestUrl?.queryParameter("search") == "xy" -> json("{\"data\":{\"metas\":[]}}")
                    request.path!!.contains("/catalog/movie/search") -> json("""
                        {"cached":false,"data":{"metas":[
                          {"id":"tt1","name":"Shared Title","poster":"p","background":"b"},
                          {"id":"tt2","name":"Movie Only","poster":"p2","background":"b2"}
                        ]}}
                    """.trimIndent())
                    request.path!!.contains("/catalog/series/search") -> json("""
                        {"cached":false,"data":{"metas":[
                          {"id":"tt1","name":"Shared Title","poster":"p","background":"b"}
                        ]}}
                    """.trimIndent())
                    else -> json("{" + "\"cached\":false,\"data\":{\"metas\":[]}}")
                }
            }
        }

        val results = repository.search("shared")
        val empty = repository.search("xy")

        assertEquals(setOf("movie:tt1", "movie:tt2"), results.map { it.key }.toSet())
        assertTrue(empty.isEmpty())
    }

    @Test
    fun search_propagates_401_and_throws_when_all_sources_fail() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when {
                    request.path == "/api/addons" -> json(addonsPayload())
                    request.path!!.contains("/catalog/movie/search") -> MockResponse().setResponseCode(401).setBody("{}")
                    request.path!!.contains("/catalog/series/search") -> MockResponse().setResponseCode(500).setBody("{}")
                    else -> MockResponse().setResponseCode(500).setBody("{}")
                }
            }
        }

        val unauthorized = runCatching { repository.search("matrix") }.exceptionOrNull()
        assertTrue(unauthorized is ApiFailure && unauthorized.status == 401)

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when {
                    request.path == "/api/addons" -> json(addonsPayload())
                    request.path!!.contains("/catalog/movie/search") -> MockResponse().setResponseCode(500).setBody("{}")
                    request.path!!.contains("/catalog/series/search") -> MockResponse().setResponseCode(500).setBody("{}")
                    else -> MockResponse().setResponseCode(500).setBody("{}")
                }
            }
        }

        val failed = runCatching { repository.search("matrix") }.exceptionOrNull()
        assertNotNull(failed)
    }

    @Test
    fun detail_parses_episodes_including_specials_sorted_by_season_episode() = runBlocking {
        server.enqueue(json("""
            {
              "cached":false,
              "data":{"meta":{
                "id":"tt-series",
                "name":"Series",
                "videos":[
                  {"id":"s1e2","season":1,"episode":2,"title":"Episode 2","released":"2026-01-02"},
                  {"id":"special-1","season":0,"episode":1,"title":"Special One","released":"2025-12-01"},
                  {"id":"s1e1","season":1,"episode":1,"title":"Episode 1","released":"2026-01-01"}
                ]
              }}
            }
        """.trimIndent()))

        val detail = repository.detail(Title(id = "tt-series", type = "series", name = "Series"))

        assertEquals(listOf("special-1", "s1e1", "s1e2"), detail.episodes.map { it.id })
        assertEquals(0, detail.episodes.first().season)
    }

    @Test
    fun catalog_cache_and_skip_pagination_behave_deterministically() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when {
                    request.path!!.startsWith("/api/addons/a1/catalog/movie/top?skip=18") -> json("""
                        {"cached":false,"data":{"metas":[{"id":"tt19","name":"Title 19"}]}}
                    """.trimIndent())
                    request.path!!.startsWith("/api/addons/a1/catalog/movie/top") -> json("""
                        {"cached":false,"data":{"metas":[
                          {"id":"tt1","name":"Title 1"},
                          {"id":"tt2","name":"Title 2"}
                        ]}}
                    """.trimIndent())
                    else -> json("{" + "\"cached\":false,\"data\":{\"metas\":[]}}")
                }
            }
        }

        val catalog = Catalog("a1", "movie", "top", "Popular movies", searchable = false)
        val first = repository.catalog(catalog)
        val cached = repository.catalog(catalog)
        val page2 = repository.catalog(catalog, skip = 18)

        assertEquals(2, first.size)
        assertEquals(first, cached)
        assertEquals("tt19", page2.single().id)
    }

    private fun addonsPayload(): String = """
      [
        {
          "id":"a1",
          "name":"Addon One",
          "catalogs":[
            {"type":"movie","id":"year","name":"Movie Year"},
            {"type":"series","id":"year","name":"Series Year"},
            {"type":"movie","id":"top","name":"Movie Top"},
            {"type":"series","id":"top","name":"Series Top"},
            {"type":"movie","id":"search","name":"Movie Search","extra":[{"name":"search"}]},
            {"type":"series","id":"search","name":"Series Search","extra":[{"name":"search"}]}
          ]
        }
      ]
    """.trimIndent()

    private fun json(body: String): MockResponse =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)
}
