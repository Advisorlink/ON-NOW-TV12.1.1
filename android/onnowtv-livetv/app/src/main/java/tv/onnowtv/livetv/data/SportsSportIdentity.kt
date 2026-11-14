package tv.onnowtv.livetv.data

/** Strong event-title evidence before ambiguous TV strand names such as Monday Night Football. */
object SportsSportIdentity {
    private val aflTeams = listOf("adelaide crows", "brisbane lions", "carlton", "collingwood", "essendon", "fremantle", "geelong", "gold coast suns", "gws giants", "hawthorn", "melbourne demons", "north melbourne", "port adelaide", "richmond tigers", "st kilda", "sydney swans", "west coast eagles", "western bulldogs")
    private val nrlTeams = listOf("brisbane broncos", "canberra raiders", "canterbury bulldogs", "cronulla sharks", "gold coast titans", "manly sea eagles", "melbourne storm", "newcastle knights", "new zealand warriors", "north queensland cowboys", "parramatta eels", "penrith panthers", "south sydney rabbitohs", "st george illawarra", "sydney roosters", "wests tigers", "redcliffe dolphins")
    private val cricketTest = Regex(" (1st|2nd|3rd|4th|5th) test ")
    private fun pair(title: String, names: List<String>) = names.count { title.contains(" $it ") } >= 2
    fun resolve(raw: String): String? {
        val title = " ${GameChannelMatcher.normalize(raw)} "
        return when {
            title.contains(" afl ") || title.contains(" aflw ") || title.contains(" australian football ") || pair(title, aflTeams) -> "afl"
            title.contains(" nrl ") || title.contains(" nrlw ") || title.contains(" rugby league ") || title.contains(" state of origin ") || pair(title, nrlTeams) -> "nrl"
            title.contains(" nfl ") || title.contains(" american football ") || title.contains(" ncaa football ") || title.contains(" college football ") || title.contains(" super bowl ") -> "nfl"
            title.contains(" a league ") || title.contains(" alw ") -> "soccer"
            title.contains(" sheffield shield ") || title.contains(" big bash ") || title.contains(" wbbl ") || title.contains(" bbl ") || cricketTest.containsMatchIn(title) -> "cricket"
            else -> null
        }
    }
}