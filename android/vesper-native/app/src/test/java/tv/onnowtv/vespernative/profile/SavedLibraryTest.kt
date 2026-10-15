package tv.onnowtv.vespernative.profile

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// SavedLibrary: read-only projections for favourites/watch-later/continue-watching with filtering + dedupe.
class SavedLibraryTest {
    @Test
    fun favourites_filters_by_type_and_sorts_most_recent_first() {
        val favourites = JSONObject()
            .put("tt-older", JSONObject().put("type", "movie").put("addedAt", "2026-01-01T00:00:00Z")
                .put("meta", JSONObject().put("name", "Older").put("poster", "p1")))
            .put("tt-newer", JSONObject().put("type", "movie").put("addedAt", "2026-01-03T00:00:00Z")
                .put("meta", JSONObject().put("name", "Newer").put("poster", "p2")))
            .put("ss-1", JSONObject().put("type", "series").put("addedAt", "2026-01-04T00:00:00Z")
                .put("meta", JSONObject().put("name", "Series").put("poster", "p3")))

        val state = JSONObject().put("favorites", favourites)
        val saved = SavedLibrary(profileData(state, JSONArray()))

        val movies = saved.favourites("movie")
        assertEquals(listOf("tt-newer", "tt-older"), movies.map { it.id })
        assertEquals(listOf("Newer", "Older"), movies.map { it.name })
    }

    @Test
    fun watch_later_accepts_movie_or_series_shapes_and_dedupes_by_title_key() {
        val watchLater = JSONArray()
            .put(JSONObject().put("id", "show-1").put("type", "series").put("addedAt", "2026-01-03")
                .put("show", JSONObject().put("name", "Show One").put("poster", "sp")))
            .put(JSONObject().put("id", "movie-1").put("type", "movie").put("addedAt", "2026-01-02")
                .put("movie", JSONObject().put("name", "Movie One").put("poster", "mp")))
            .put(JSONObject().put("id", "movie-1").put("type", "movie").put("addedAt", "2026-01-01")
                .put("movie", JSONObject().put("name", "Movie One").put("poster", "mp")))

        val state = JSONObject().put("watchLater", watchLater)
        val saved = SavedLibrary(profileData(state, JSONArray()))

        val projected = saved.watchLater()
        assertEquals(2, projected.size)
        assertEquals(listOf("series:show-1", "movie:movie-1"), projected.map { it.key })
    }

    @Test
    fun continue_watching_reduces_episode_ids_to_show_ids_and_keeps_recent_order() {
        val cw = JSONArray()
            .put(JSONObject().put("id", "show-1:1:1").put("type", "series").put("title", "Pilot")
                .put("poster", "p1").put("updatedAt", 1000L))
            .put(JSONObject().put("id", "show-1:1:2").put("type", "series").put("title", "Ep 2")
                .put("poster", "p2").put("updatedAt", 2000L))
            .put(JSONObject().put("id", "movie-1").put("type", "movie").put("title", "Movie")
                .put("poster", "pm").put("updatedAt", 1500L))

        val saved = SavedLibrary(profileData(JSONObject(), cw))
        val projected = saved.continueWatching()

        assertEquals(listOf("series:show-1", "movie:movie-1"), projected.map { it.key })
        assertEquals("Ep 2", projected.first().name)
    }

    @Test
    fun malformed_entries_are_ignored_without_throwing() {
        val favourites = JSONObject().put("broken", JSONObject().put("type", "movie"))
        val watchLater = JSONArray().put(JSONObject().put("id", "x").put("type", "series"))
        val cw = JSONArray().put(JSONObject().put("id", "").put("type", "movie").put("title", ""))
        val state = JSONObject().put("favorites", favourites).put("watchLater", watchLater)

        val saved = SavedLibrary(profileData(state, cw))

        assertTrue(saved.favourites("movie").isEmpty())
        assertTrue(saved.watchLater().isEmpty())
        assertTrue(saved.continueWatching().isEmpty())
    }

    private fun profileData(library: JSONObject, continueWatching: JSONArray): ProfileData {
        val values = mapOf(
            ProfileKeys.LIBRARY to library,
            ProfileKeys.CONTINUE_WATCHING to continueWatching,
        )
        return ProfileData("p-test") { key -> values[key] }
    }
}
