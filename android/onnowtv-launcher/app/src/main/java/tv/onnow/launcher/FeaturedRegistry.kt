package tv.onnow.launcher

/**
 * Per-tile copy + accent colour for the featured panel.  Looked up
 * by DockItem.key whenever focus moves so the headline / tagline /
 * description / accent colour update live.
 */
data class FeaturedState(
    val title: String,
    val tagline: String,
    val description: String,
    val accentArgb: Int,
)

object FeaturedRegistry {
    private val byKey: Map<String, FeaturedState> = mapOf(
        "movies"   to FeaturedState(
            title       = "Movies & TV Shows.",
            tagline     = "Endless stories. Unforgettable moments.",
            description = "Stream the latest movies, binge-worthy series, and timeless classics. Discover new favourites and watch your way.",
            accentArgb  = 0xFF38B8FF.toInt(),
        ),
        "music"    to FeaturedState(
            title       = "Music.",
            tagline     = "Lossless sound, every track.",
            description = "Listen to your favourite albums, playlists, and live radio in stunning quality, straight on the big screen.",
            accentArgb  = 0xFF38B8FF.toInt(),
        ),
        "livetv"   to FeaturedState(
            title       = "Live TV.",
            tagline     = "Real-time channels. Real moments.",
            description = "Watch live news, sports, and entertainment as it happens. Stay connected to the world in real time.",
            accentArgb  = 0xFF2BB6FF.toInt(),
        ),
        "apps"     to FeaturedState(
            title       = "Apps.",
            tagline     = "Discover apps, games, and tools all in one place.",
            description = "Explore a world of possibilities. Find new apps, enhance your entertainment, and customise your experience.",
            accentArgb  = 0xFF2EEAC2.toInt(),
        ),
        "browser"  to FeaturedState(
            title       = "Browser.",
            tagline     = "Surf the web on the big screen.",
            description = "Search, explore, and discover a world of content from your favourite websites. Fast. Secure. Effortless.",
            accentArgb  = 0xFF38C2FF.toInt(),
        ),
        "settings" to FeaturedState(
            title       = "Settings.",
            tagline     = "Tune everything to your liking.",
            description = "Wallpaper, layout, sound, network, accounts, parental controls — your box, your rules.",
            accentArgb  = 0xFF5BC5FF.toInt(),
        ),
    )

    fun forKey(key: String): FeaturedState = byKey[key] ?: byKey.getValue("livetv")
}
