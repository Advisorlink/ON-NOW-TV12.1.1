package tv.onnowtv.livetv.ui

import tv.onnowtv.livetv.R

/**
 * v2.10.61 — Maps a Highfly genre string (e.g. "basketball",
 * "american football", "ufc") onto a vibrant per-sport gradient
 * drawable used as the fallback "poster" on event cards + the
 * hero whenever the addon doesn't ship a real background URL.
 *
 * The Highfly addon's `genres[0]` is the canonical source.  We
 * fall back to a default cyan/navy gradient for unknown values.
 */
internal object SportFallback {

    /** Resolve a fallback poster drawable for an event's genres or
     *  title.  Always returns a valid drawable resource id. */
    fun drawableFor(genres: List<String>, title: String = ""): Int {
        val haystack = (genres.joinToString(" ") + " " + title).lowercase()
        return when {
            // Order matters — match the more-specific tokens first.
            "american football" in haystack || "nfl" in haystack ->
                R.drawable.highfly_sport_nfl
            "afl" in haystack || "aussie rules" in haystack ->
                R.drawable.highfly_sport_afl
            "rugby" in haystack ->
                R.drawable.highfly_sport_rugby
            "basketball" in haystack || "nba" in haystack ->
                R.drawable.highfly_sport_basketball
            "baseball" in haystack || "mlb" in haystack ->
                R.drawable.highfly_sport_baseball
            "hockey" in haystack || "nhl" in haystack ->
                R.drawable.highfly_sport_hockey
            "tennis" in haystack || "atp" in haystack || "wta" in haystack ->
                R.drawable.highfly_sport_tennis
            "ufc" in haystack || "boxing" in haystack || "mma" in haystack || "fight" in haystack ->
                R.drawable.highfly_sport_fight
            "motor" in haystack || "f1" in haystack || "formula" in haystack || "nascar" in haystack || "motogp" in haystack ->
                R.drawable.highfly_sport_motor
            "cricket" in haystack ->
                R.drawable.highfly_sport_cricket
            "golf" in haystack || "pga" in haystack ->
                R.drawable.highfly_sport_golf
            "snooker" in haystack || "billiards" in haystack || "pool" in haystack ->
                R.drawable.highfly_sport_snooker
            "darts" in haystack || "pdc" in haystack ->
                R.drawable.highfly_sport_darts
            "football" in haystack || "soccer" in haystack || "premier league" in haystack || "uefa" in haystack || "champions league" in haystack ->
                R.drawable.highfly_sport_football
            else ->
                R.drawable.highfly_sport_other
        }
    }
}
