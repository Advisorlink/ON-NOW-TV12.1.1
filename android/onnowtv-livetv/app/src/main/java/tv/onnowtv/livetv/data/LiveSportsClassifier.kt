package tv.onnowtv.livetv.data

import androidx.annotation.DrawableRes
import tv.onnowtv.livetv.R

/**
 * v2.14.15 — Keyword-based classifier for the "What's On Live" hub.
 *
 * Given an EPG show title (and optionally the channel name), returns
 * a sport bucket ID.  Rules are ordered by SPECIFICITY — more
 * specific patterns win over generic ones so "Formula E" doesn't
 * accidentally fall into F1 and "Rugby League" doesn't land in
 * Rugby Union.  The classifier is intentionally over-inclusive:
 * anything with a `LIVE` tag and no bucket match still ends up in
 * `OTHER_SPORT` so it's visible in the hub — the operator can
 * refine the rules as false-positives surface.
 *
 * Rules use plain lowercase-substring matches (no regex) because
 * the entire EPG for a full day is ~5 000 titles and this runs on
 * a slow TV box — string.contains is O(n·m) but pool-cheap.
 */
object LiveSportsClassifier {

    // Sport bucket IDs.  Kept as constants (not enum) so we can add
    // new buckets from JSON later without breaking existing pins.
    const val SOCCER      = "soccer"
    const val F1          = "f1"
    const val MOTORSPORT  = "motorsport"
    const val GOLF        = "golf"
    const val CRICKET     = "cricket"
    const val TENNIS      = "tennis"
    const val RUGBY_UNION = "rugby"
    const val RUGBY_LEAGUE= "nrl"
    const val AFL         = "afl"
    const val NFL         = "nfl"
    const val NBA         = "nba"
    const val NHL         = "nhl"
    const val MLB         = "mlb"
    const val MMA_BOXING  = "mma"
    const val CYCLING     = "cycling"
    const val ATHLETICS   = "athletics"
    const val WWE         = "wwe"
    const val OTHER_SPORT = "other"

    /** Human-friendly display label for a bucket ID. */
    fun labelOf(id: String): String = when (id) {
        SOCCER       -> "Football"
        F1           -> "Formula 1"
        MOTORSPORT   -> "Motorsport"
        GOLF         -> "Golf"
        CRICKET      -> "Cricket"
        TENNIS       -> "Tennis"
        RUGBY_UNION  -> "Rugby Union"
        RUGBY_LEAGUE -> "Rugby League"
        AFL          -> "AFL"
        NFL          -> "NFL"
        NBA          -> "Basketball"
        NHL          -> "Ice Hockey"
        MLB          -> "Baseball"
        MMA_BOXING   -> "Combat Sports"
        CYCLING      -> "Cycling"
        ATHLETICS    -> "Athletics"
        WWE          -> "Wrestling"
        else         -> "Other Sport"
    }

    /** Rendering order for the sport chip row — most popular first. */
    val DISPLAY_ORDER: List<String> = listOf(
        SOCCER, F1, MOTORSPORT, GOLF, CRICKET, TENNIS,
        RUGBY_UNION, RUGBY_LEAGUE, AFL, NFL, NBA, NHL, MLB,
        MMA_BOXING, CYCLING, ATHLETICS, WWE, OTHER_SPORT,
    )

    /** Vector-drawable resource painted inside the sport chip's
     *  coloured disc.  Icons are white-filled so [ImageView]'s
     *  colour filter can tint them with [colorOf] at bind time. */
    @DrawableRes
    fun iconOf(id: String): Int = when (id) {
        SOCCER       -> R.drawable.img_sport_football
        F1           -> R.drawable.img_sport_f1
        MOTORSPORT   -> R.drawable.img_sport_motorsport
        GOLF         -> R.drawable.img_sport_golf
        CRICKET      -> R.drawable.img_sport_cricket
        TENNIS       -> R.drawable.img_sport_tennis
        RUGBY_UNION  -> R.drawable.img_sport_rugby
        RUGBY_LEAGUE -> R.drawable.img_sport_rugby_league
        AFL          -> R.drawable.img_sport_afl
        NFL          -> R.drawable.img_sport_nfl
        NBA          -> R.drawable.img_sport_basketball
        NHL          -> R.drawable.img_sport_hockey
        MLB          -> R.drawable.img_sport_baseball
        MMA_BOXING   -> R.drawable.img_sport_boxing
        CYCLING      -> R.drawable.img_sport_cycling
        ATHLETICS    -> R.drawable.img_sport_athletics
        WWE          -> R.drawable.img_sport_wwe
        else         -> R.drawable.img_sport_trophy
    }

    /** Accent hex colour used for the sport chip's disc + focus
     *  glow.  Kept dark-friendly (single accent per bucket) so the
     *  row reads as a cohesive palette. */
    fun colorOf(id: String): Int = when (id) {
        SOCCER       -> 0xFF3EB44A.toInt()  // pitch green
        F1           -> 0xFFE10600.toInt()  // Ferrari red
        MOTORSPORT   -> 0xFFFF6A00.toInt()  // paddock orange
        GOLF         -> 0xFF7FC57F.toInt()  // fairway
        CRICKET      -> 0xFFCC1F1F.toInt()  // ball red
        TENNIS       -> 0xFFDCFF3F.toInt()  // tennis-ball yellow
        RUGBY_UNION  -> 0xFF1F3E7A.toInt()  // Six Nations navy
        RUGBY_LEAGUE -> 0xFF6E37FF.toInt()  // NRL purple
        AFL          -> 0xFFE30E2E.toInt()  // AFL red
        NFL          -> 0xFF875A2B.toInt()  // pigskin
        NBA          -> 0xFFF57C1F.toInt()  // basketball orange
        NHL          -> 0xFF6FDCFF.toInt()  // ice
        MLB          -> 0xFF1257A6.toInt()  // stadium blue
        MMA_BOXING   -> 0xFFB80020.toInt()  // glove crimson
        CYCLING      -> 0xFFFFCB05.toInt()  // maillot jaune
        ATHLETICS    -> 0xFFFF3B7A.toInt()  // track magenta
        WWE          -> 0xFFC8A027.toInt()  // WWE gold
        else         -> 0xFF8FA1BF.toInt()  // slate
    }

    // Priority-ordered rules.  Evaluated top-to-bottom; first hit
    // wins.  More specific patterns MUST come before broader ones.
    private data class Rule(val bucket: String, val needles: List<String>)

    private val RULES: List<Rule> = listOf(
        // ── Very specific series first ──────────────────────────
        Rule(F1, listOf(
            "formula 1", "formula one", " f1 ", "grand prix", "gp weekend",
            "monza", "silverstone", "spa francorchamps", "singapore gp",
        )),
        Rule(MOTORSPORT, listOf(
            "motogp", "moto gp", "moto2", "moto3", "formula e",
            "nascar", "indycar", "supercars", "wrc", "world rally",
            "goodwood festival", "festival of speed", "le mans",
            "world endurance", "world superbike", "wsbk", "extreme e",
        )),
        Rule(GOLF, listOf(
            "golf", "pga tour", "dp world tour", "liv golf", "ryder cup",
            "solheim cup", "presidents cup", "the masters", "u.s. open golf",
            "the open", "scottish open", "irish open", " lpga",
        )),
        Rule(CRICKET, listOf(
            "cricket", "test match", "the ashes", " odi ", " t20 ",
            "ipl ", "big bash", "bbl", "world test", "county championship",
            "one day international",
        )),
        Rule(RUGBY_LEAGUE, listOf(
            "nrl ", "rugby league", "state of origin", "super league",
            "grand final", "kangaroos", "kiwis",
        )),
        Rule(RUGBY_UNION, listOf(
            "rugby", "six nations", "rugby championship", "rugby world cup",
            "super rugby", "wallabies", "all blacks", "premiership rugby",
            "top 14", "united rugby",
        )),
        Rule(AFL, listOf(
            " afl ", "aussie rules", "australian football", "afl live",
            "afl round", "afl finals", "aflw",
        )),
        Rule(NFL, listOf(
            " nfl ", "super bowl", "monday night football",
            "thursday night football", "sunday night football",
            "college football", "ncaa football",
        )),
        Rule(NBA, listOf(
            " nba ", "basketball", "wnba", "euroleague basketball", "nba finals",
            "ncaa basketball", "march madness",
        )),
        Rule(NHL, listOf(
            " nhl ", "ice hockey", "hockey night", "stanley cup",
        )),
        Rule(MLB, listOf(
            " mlb ", "baseball", "world series baseball", "yankees",
            "red sox", "dodgers vs",
        )),
        Rule(TENNIS, listOf(
            "tennis", "wimbledon", "us open tennis", "australian open",
            "french open", "roland garros", " atp ", " wta ", "davis cup",
            "billie jean king cup",
        )),
        Rule(MMA_BOXING, listOf(
            " ufc ", "ufc ", "boxing", "heavyweight", "mma ", "bellator",
            "one championship", "professional fighters league",
            "world boxing", "sky sports boxing", "top rank boxing",
        )),
        Rule(WWE, listOf(
            "wwe ", "wwe raw", "smackdown", "wrestlemania", "aew ",
            "all elite wrestling", "impact wrestling",
        )),
        Rule(CYCLING, listOf(
            "cycling", "tour de france", "giro d'italia", "vuelta",
            "world tour cycling", "uci ",
        )),
        Rule(ATHLETICS, listOf(
            "athletics", "diamond league", "world athletics", "olympics live",
            "marathon", "track and field",
        )),
        // ── Soccer / football (kept LAST because 'football' is the
        //    ambiguous word — many other sports include it) ───────
        Rule(SOCCER, listOf(
            "premier league", "champions league", "europa league",
            "world cup", "euro 20", "euro 21", "euro 22", "euro 24",
            "euro 26", "copa america", "copa libertadores",
            "la liga", "serie a", "bundesliga", "ligue 1", " mls ",
            "fa cup", "carabao cup", "efl", "womens super league",
            "wsl ", "afc cup", "nations league", "concacaf",
            "football live", "soccer", "fifa ", "uefa ",
            // Generic "football" LAST — most likely soccer but only
            // if no other sport rule matched already.
            "football",
        )),
    )

    /**
     * Classify a show title + optional channel name into a sport
     * bucket.  Returns `null` when neither the title nor the channel
     * matches any pattern (the show is NOT sports at all).
     */
    fun classify(title: String, channelName: String? = null): String? {
        val hay = " " + title.lowercase() + " " +
                  (channelName?.lowercase().orEmpty()) + " "
        for (r in RULES) {
            for (n in r.needles) {
                if (hay.contains(n)) return r.bucket
            }
        }
        // ── Last-resort: obvious sports channel name → OTHER_SPORT.
        //    We already returned early for known sports above.
        val ch = channelName?.lowercase().orEmpty()
        val isSportsChannel =
            ch.contains("sport") || ch.contains("espn") ||
            ch.contains("bein") || ch.contains("dazn") ||
            ch.contains("tsn ") || ch.contains("sky sports") ||
            ch.contains("fox sports") || ch.contains("nbc sports") ||
            ch.contains("eurosport")
        return if (isSportsChannel) OTHER_SPORT else null
    }

    /**
     * v2.14.17 — TITLE-ONLY variant used by the "What's On Live"
     * hub.  Skips the channel-name fallback so a rugby channel
     * running a talk show / studio wrap doesn't fake a live match.
     * Returns `null` unless the title itself matches a sport rule.
     */
    fun classifyTitle(title: String): String? {
        val hay = " " + title.lowercase() + " "
        for (r in RULES) {
            for (n in r.needles) {
                if (hay.contains(n)) return r.bucket
            }
        }
        return null
    }

    /**
     * v2.14.17 — Substrings that mark a programme as a re-broadcast,
     * highlights package, review, preview, or documentary rather
     * than a live event.  The "What's On Live" hub uses this to
     * suppress items like "European Rugby Final — Extended
     * Highlights" that the classifier would otherwise pin to
     * Rugby Union.  Kept case-insensitive & surrounded-by-spaces
     * to avoid false matches ("clive" ≠ "live"). */
    private val NON_LIVE_MARKERS = listOf(
        " highlights", "highlights ", "extended highlights",
        "match highlights", "goals & highlights", "goals and highlights",
        " replay ", " replay:", "replayed", " rerun", " re-run",
        " encore ", "encore:", " review ", "review:", " recap ",
        "recap:", "post-match", "post match", " reaction ",
        "reaction:", "build-up", "build up", " preview ", "preview:",
        "best of ", "top 10", "top ten", " classic ", "classic:",
        "throwback", "greatest", "documentary", "the story of",
        " special ",
    )

    /**
     * True when the programme title suggests it is NOT actually
     * airing live right now — replay, highlights, review, etc.
     * Called by the hub before adding a channel to a sport bucket. */
    fun isNonLive(title: String): Boolean {
        val hay = " " + title.lowercase() + " "
        return NON_LIVE_MARKERS.any { hay.contains(it) }
    }
}
