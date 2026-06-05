package tv.onnowtv.livetv.data

/** One sport bucket pulled from `/api/sportsdb/fixtures` → `sportsMeta`. */
data class SportMeta(
    val key: String,
    val name: String,
    val count: Int,
)

/** One fixture / event the user can choose to watch. */
data class Fixture(
    val id: String,
    val sport: String,
    val league: String,
    val home: String,
    val away: String,
    val title: String,
    val date: String,
    val timeUtc: String,
    val timestamp: Long,
    val venue: String,
    val country: String,
    val status: String,
    val poster: String,
    val live: Boolean,
    val broadcasts: List<String>,
)
