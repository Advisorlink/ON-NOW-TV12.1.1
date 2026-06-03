package tv.vesper.native_app.data

/**
 * Shape of the items we render in poster rails.  Mirrors the
 * normalised Stremio catalog item the React Vesper UI consumes.
 *
 * Field choices follow `src/lib/api.js` + `lib/img.js` so the same
 * upstream catalog responses drop into either renderer unchanged.
 */
data class CatalogItem(
    val id: String,
    val type: String,           // "movie" | "series"
    val title: String,
    val poster: String?,        // raw URL (already absolute from upstream)
    val backdrop: String?,
    val year: String?,
    val genres: List<String>,
    val synopsis: String?,
    val imdbId: String?,
)

/**
 * One horizontal rail.  `items` arrive lazily — the page paints
 * the title row immediately and the posters stream in as the
 * addon endpoints respond.
 */
data class Shelf(
    val id: String,
    val title: String,
    val eyebrow: String? = null,
    val items: List<CatalogItem>,
)

/** Side-nav entry shown in the left rail. */
data class NavItem(
    val id: String,
    val label: String,
    @androidx.annotation.DrawableRes val iconRes: Int,
)
