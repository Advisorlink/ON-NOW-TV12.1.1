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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.IOException
import java.util.concurrent.TimeUnit

// BrowseRepository/CatalogRepository detail: slug routes, tmdb mapping, genres/anime payloads, and strict failure behavior.
@RunWith(RobolectricTestRunner::class)
class BrowseRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var api: VesperApi
    private lateinit var browse: BrowseRepository
    private lateinit var catalog: CatalogRepository

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
        api = VesperApi(server.url("/").toString().trimEnd('/'))
        browse = BrowseRepository(api) { "token-1" }
        catalog = CatalogRepository(api) { "token-1" }
    }

    @After
    fun teardown() {
        runCatching { server.shutdown() }
    }

    @Test
    fun brands_reads_network_and_collection_slugs_with_au_region_and_asset_logo_prefix() {
        val context = RuntimeEnvironment.getApplication().applicationContext

        val networks = browse.brands(context, "networks")
        val collections = browse.brands(context, "collections")

        assertTrue(networks.any { it.slug == "netflix" && it.region == "US" })
        assertTrue(networks.any { it.slug == "paramount-plus" && it.region == "AU" })
        assertTrue(networks.any { it.slug == "netflix" && it.localArt.startsWith("file:///android_asset/") })
        assertTrue(collections.any { it.slug == "star-wars" && it.group == "boxset" })
    }

    @Test
    fun page_uses_slug_type_page_region_and_maps_tmdb_title_shape() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.requestUrl!!.encodedPath) {
                    "/api/networks/paramount-plus" -> {
                        assertEquals("tv", request.requestUrl!!.queryParameter("type"))
                        assertEquals("2", request.requestUrl!!.queryParameter("page"))
                        assertEquals("AU", request.requestUrl!!.queryParameter("region"))
                        json(
                            """
                            {
                              "data": {
                                "results": [
                                  {
                                    "tmdb_id":"9001",
                                    "title":"AU Show",
                                    "poster":"https://img/p.jpg",
                                    "backdrop":"https://img/b.jpg",
                                    "overview":"overview",
                                    "year":"2026-09-01",
                                    "rating":"7.5"
                                  }
                                ],
                                "total_pages": 3
                              }
                            }
                            """.trimIndent()
                        )
                    }
                    else -> MockResponse().setResponseCode(404).setBody("{}")
                }
            }
        }

        val context = RuntimeEnvironment.getApplication().applicationContext
        val brand = browse.brands(context, "networks").first { it.slug == "paramount-plus" }
        val page = browse.page(brand, "series", 2)

        assertEquals(1, page.titles.size)
        assertEquals("tmdb:9001", page.titles.single().id)
        assertEquals("series", page.titles.single().type)
        assertEquals("2026", page.titles.single().year)
        assertTrue(page.hasMore)
    }

    @Test
    fun genres_and_anime_parse_data_arrays_and_rails_without_fabrication() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.requestUrl!!.encodedPath) {
                    "/api/tmdb/genres/tv" -> json("""{"data":[{"id":16,"name":"Animation"},{"id":"35","name":"Comedy"}]}""")
                    "/api/tmdb/anime" -> json(
                        """
                        {
                          "rails":[
                            {
                              "title":"Trending Anime",
                              "data":[
                                {"tmdb_id":"1","title":"Anime One","type":"series","poster":"p","backdrop":"b","overview":"o","year":"2026","rating":"8"}
                              ]
                            }
                          ]
                        }
                        """.trimIndent()
                    )
                    else -> MockResponse().setResponseCode(404).setBody("{}")
                }
            }
        }

        val genres = browse.genres("series")
        val rails = browse.anime()

        assertEquals(listOf("16" to "Animation", "35" to "Comedy"), genres)
        assertEquals(1, rails.size)
        assertEquals("Trending Anime", rails.first().first)
        assertEquals("tmdb:1", rails.first().second.single().id)
    }

    @Test
    fun browse_calls_throw_on_500_or_invalid_json_instead_of_returning_fake_data() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("{}"))
        val failedGenre = runCatching { browse.genre("movie", "28", 1) }.exceptionOrNull()
        assertTrue(failedGenre is ApiFailure && (failedGenre as ApiFailure).status == 500)

        server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody("{"))
        val invalidAnime = runCatching { browse.anime() }.exceptionOrNull()
        assertNotNull(invalidAnime)
        assertTrue(invalidAnime is IOException)
    }

    @Test
    fun detail_tmdb_series_resolves_imdb_then_meta_and_missing_imdb_returns_explicit_error() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return when (request.requestUrl!!.encodedPath) {
                    "/api/tmdb/imdb/tv/555" -> json("""{"imdb_id":"tt0555"}""")
                    "/api/meta/series/tt0555" -> json("""{"data":{"meta":{"id":"tt0555","name":"Resolved Series"}}}""")
                    "/api/tmdb/imdb/tv/556" -> json("""{"imdb_id":""}""")
                    else -> MockResponse().setResponseCode(404).setBody("{}")
                }
            }
        }

        val resolved = catalog.detail(Title(id = "tmdb:555", type = "series", name = "Seed"))
        val first = server.takeRequest(2, TimeUnit.SECONDS)!!
        val second = server.takeRequest(2, TimeUnit.SECONDS)!!

        assertEquals("/api/tmdb/imdb/tv/555", first.path)
        assertEquals("/api/meta/series/tt0555", second.path)
        assertEquals("tt0555", resolved.id)
        assertEquals("Resolved Series", resolved.name)

        val missing = runCatching { catalog.detail(Title(id = "tmdb:556", type = "series", name = "Seed")) }.exceptionOrNull()
        assertTrue(missing is IllegalStateException)
        assertEquals("Title details are unavailable.", missing!!.message)
    }

    private fun json(body: String): MockResponse =
        MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(body)
}
