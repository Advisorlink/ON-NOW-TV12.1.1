package tv.rebornplay

/** Box Sets built purely from the service's own movie list: films sharing a franchise title ("Scary Movie", "Scary Movie 2", …). */
object BoxSets {
    private val cut = listOf(
        Regex("\\s*[:\\-–|]\\s.*$"),                                              // "Star Wars: A New Hope" → "Star Wars"
        Regex("\\s+(part|chapter|vol\\.?|volume)\\s+[\\divxlc]+.*$", RegexOption.IGNORE_CASE),
        Regex("\\s+\\d{1,2}$"),                                                   // "Scary Movie 4"
        Regex("\\s+(ii|iii|iv|v|vi|vii|viii|ix|x)$", RegexOption.IGNORE_CASE),   // "Rocky II"
        Regex("\\s+(returns|reloaded|revolutions|resurrection|forever|begins|rises)$", RegexOption.IGNORE_CASE),
    )
    private fun base(name: String): String { var n = name.trim(); cut.forEach { n = n.replace(it, "") }; return n.trim() }
    private fun key(name: String) = base(name).lowercase().removePrefix("the ").replace(Regex("[^a-z0-9 ]"), "").trim()

    @Volatile var sets: Map<String, Pair<String, List<Item>>> = emptyMap()   // id → (title, films)

    fun build(movies: List<Item>): List<Item> {
        val groups = movies.groupBy { key(it.name) }.filter { (k, l) -> k.length >= 4 && l.distinctBy { it.name.lowercase() }.size >= 3 }
        val out = HashMap<String, Pair<String, List<Item>>>()
        val tiles = groups.map { (k, l) ->
            val films = l.distinctBy { it.name.lowercase() }.sortedWith(compareBy({ it.year ?: "9999" }, { it.name }))
            val title = base(films.first().name).ifBlank { k }
            val id = "boxset:$k"
            out[id] = title to films
            Item(id = id, type = "boxset", name = "$title Collection", poster = films.first().poster, background = films.firstNotNullOfOrNull { it.background },
                description = "${films.size} films · ${films.first().year ?: ""}–${films.last().year ?: ""}", year = "${films.size} films",
                genres = films.first().genres, added = films.maxOf { it.added })
        }.sortedByDescending { it.added }
        sets = out
        return tiles
    }
}
