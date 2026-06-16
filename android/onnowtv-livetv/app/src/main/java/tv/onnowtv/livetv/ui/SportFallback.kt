package tv.onnowtv.livetv.ui

import tv.onnowtv.livetv.R

/**
 * v2.10.62 — Sport visual resolver.
 *
 * Two helpers:
 *   • [drawableFor]  → per-sport vibrant GRADIENT used as the
 *                       fallback "poster" background.
 *   • [iconFor]      → per-sport Material-style ICON used inside
 *                       sport-filter chips + card top-right badges.
 *
 * Both resolve from Highfly's `genres[0]` + the event title so we
 * cover the cases where the addon doesn't tag a genre (rare but
 * happens for 24/7 channels).  More-specific tokens are matched
 * before generic ones (e.g. "american football" before "football").
 */
internal object SportFallback {

    fun drawableFor(genres: List<String>, title: String = ""): Int =
        when (classify(genres, title)) {
            Sport.NFL          -> R.drawable.highfly_sport_nfl
            Sport.AFL          -> R.drawable.highfly_sport_afl
            Sport.RUGBY        -> R.drawable.highfly_sport_rugby
            Sport.BASKETBALL   -> R.drawable.highfly_sport_basketball
            Sport.BASEBALL     -> R.drawable.highfly_sport_baseball
            Sport.HOCKEY       -> R.drawable.highfly_sport_hockey
            Sport.TENNIS       -> R.drawable.highfly_sport_tennis
            Sport.FIGHT        -> R.drawable.highfly_sport_fight
            Sport.MOTOR        -> R.drawable.highfly_sport_motor
            Sport.CRICKET      -> R.drawable.highfly_sport_cricket
            Sport.GOLF         -> R.drawable.highfly_sport_golf
            Sport.SNOOKER      -> R.drawable.highfly_sport_snooker
            Sport.DARTS        -> R.drawable.highfly_sport_darts
            Sport.FOOTBALL     -> R.drawable.highfly_sport_football
            Sport.OTHER        -> R.drawable.highfly_sport_other
        }

    fun iconFor(genres: List<String>, title: String = ""): Int =
        when (classify(genres, title)) {
            Sport.NFL          -> R.drawable.ic_sport_nfl
            Sport.AFL          -> R.drawable.ic_sport_afl
            Sport.RUGBY        -> R.drawable.ic_sport_rugby
            Sport.BASKETBALL   -> R.drawable.ic_sport_basketball
            Sport.BASEBALL     -> R.drawable.ic_sport_baseball
            Sport.HOCKEY       -> R.drawable.ic_sport_hockey
            Sport.TENNIS       -> R.drawable.ic_sport_tennis
            Sport.FIGHT        -> R.drawable.ic_sport_fight
            Sport.MOTOR        -> R.drawable.ic_sport_motor
            Sport.CRICKET      -> R.drawable.ic_sport_cricket
            Sport.GOLF         -> R.drawable.ic_sport_golf
            Sport.SNOOKER      -> R.drawable.ic_sport_snooker
            Sport.DARTS        -> R.drawable.ic_sport_darts
            Sport.FOOTBALL     -> R.drawable.ic_sport_football
            Sport.OTHER        -> R.drawable.ic_sport_all
        }

    private enum class Sport {
        NFL, AFL, RUGBY, BASKETBALL, BASEBALL, HOCKEY, TENNIS, FIGHT,
        MOTOR, CRICKET, GOLF, SNOOKER, DARTS, FOOTBALL, OTHER
    }

    private fun classify(genres: List<String>, title: String): Sport {
        val haystack = (genres.joinToString(" ") + " " + title).lowercase()
        return when {
            "american football" in haystack || "nfl" in haystack -> Sport.NFL
            "afl" in haystack || "aussie rules" in haystack -> Sport.AFL
            "rugby" in haystack -> Sport.RUGBY
            "basketball" in haystack || "nba" in haystack -> Sport.BASKETBALL
            "baseball" in haystack || "mlb" in haystack -> Sport.BASEBALL
            "hockey" in haystack || "nhl" in haystack -> Sport.HOCKEY
            "tennis" in haystack || "atp" in haystack || "wta" in haystack -> Sport.TENNIS
            "ufc" in haystack || "boxing" in haystack || "mma" in haystack || "fight" in haystack -> Sport.FIGHT
            "motor" in haystack || " f1 " in " $haystack " || "formula" in haystack || "nascar" in haystack || "motogp" in haystack -> Sport.MOTOR
            "cricket" in haystack -> Sport.CRICKET
            "golf" in haystack || "pga" in haystack -> Sport.GOLF
            "snooker" in haystack || "billiards" in haystack || "pool" in haystack -> Sport.SNOOKER
            "darts" in haystack || "pdc" in haystack -> Sport.DARTS
            "football" in haystack || "soccer" in haystack || "premier league" in haystack ||
                "uefa" in haystack || "champions league" in haystack || "la liga" in haystack -> Sport.FOOTBALL
            else -> Sport.OTHER
        }
    }
}
