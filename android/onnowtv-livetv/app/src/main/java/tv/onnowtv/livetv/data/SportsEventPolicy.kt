package tv.onnowtv.livetv.data

/** An archive flag means a TV programme was recorded, NOT that it was a match. */
object SportsEventPolicy {
    private val nonEvent = Regex("\\b(highlights?|news|sportscent(er|re)|studio|analysis|analyst|preview|review|recap|roundup|round up|magazine|podcast|documentary|documentaries|interview|press conference|countdown|pregame|pre game|postgame|post game|post match|pre match|build up|reaction|tonight|daily|today|morning|breakfast|talk|debate|insiders?|360|360o|teleshopping|draft|trade|best of|top plays|top ten|top 10|goals show|football focus|match of the day|footy classified|on the couch|front bar|sunday footy show|nfl live|nfl total access|game day live|gameday live)\\b")
    private val versus = Regex("\\b[\\p{L}\\d][\\p{L}\\d .&'’/-]*\\s+(?:v\\.?|vs\\.?|versus|at|@)\\s+[\\p{L}\\d][\\p{L}\\d .&'’/-]*", RegexOption.IGNORE_CASE)
    private val session = Regex("\\b(race|qualifying|practice|grand prix|grand final|semi final|quarter final|finals?|round \\d+|day \\d+|stage \\d+|session|heats?|full match|full game|full event|full replay|main card|undercard)\\b")
    private val shows = listOf("final score", "final word", "soccer saturday", "soccer am", "game changers", "rivalries", "behind the scenes", "the story of", "the history of", "classic", "classics", "throwback", "retro", "greatest games", "from the vault", "rewind")
    fun isProgramme(title: String): Boolean {
        val normalized = GameChannelMatcher.normalize(title)
        return nonEvent.containsMatchIn(normalized) || shows.any { " $normalized ".contains(" $it ") }
    }
    fun bucket(programme: Programme, channel: Channel): String? {
        if (isProgramme(programme.title)) return null
        val title = programme.title
        val namedMatch = versus.containsMatchIn(title)
        if (!namedMatch && !session.containsMatchIn(GameChannelMatcher.normalize(title))) return null
        val explicit = LiveSportsClassifier.programmeBucket(title)
        if (explicit != null) return explicit
        val channelSport = LiveSportsClassifier.programmeBucket(channel.name)
        // A channel branded NFL is not evidence that this event is American football.
        // Unknown names on such a channel are excluded rather than misfiled.
        if (channelSport == LiveSportsClassifier.NFL) return null
        return channelSport?.takeIf { namedMatch || session.containsMatchIn(GameChannelMatcher.normalize(title)) }
            ?: LiveSportsClassifier.classify("", channel.name)?.takeIf { it == LiveSportsClassifier.OTHER_SPORT && namedMatch }
    }
}