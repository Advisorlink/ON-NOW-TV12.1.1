package tv.rebornplay

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.vector.ImageVector
import com.composables.icons.lucide.*

sealed class Route {
    object Home : Route()
    data class Tab(val type: String) : Route()            // "movie" | "series"
    object Search : Route()
    object Library : Route()
    object Settings : Route()
    object HomeRows : Route()
    data class BoxSet(val id: String) : Route()
    object LiveCategoryPick : Route()
    object BoxSets : Route()
    object Live : Route()
    data class Detail(val item: Item) : Route()
}

data class NavItem(val id: String, val label: String, val icon: ImageVector, val route: Route?)

/** Same order + lucide glyphs as SideNav.jsx / TopNav.jsx. */
val NAV_ITEMS = listOf(
    NavItem("search", "Search", Lucide.Search, Route.Search),
    NavItem("home", "Home", Lucide.House, Route.Home),
    NavItem("tv", "TV Shows", Lucide.Tv, Route.Tab("series")),
    NavItem("movies", "Movies", Lucide.Film, Route.Tab("movie")),
    NavItem("live", "Live TV", Lucide.RadioTower, Route.Live),
    NavItem("boxsets", "Box Sets", Lucide.Boxes, Route.BoxSets),
    NavItem("library", "My Library", Lucide.Library, Route.Library),
    NavItem("settings", "Settings", Lucide.Settings, Route.Settings),
)

/** Tiny back stack — the top is what's on screen. */
class Nav {
    val stack = mutableStateListOf<Route>(Route.Home)
    val current get() = stack.last()
    var focusHomeNav = mutableStateOf(false)
    fun go(r: Route) { if (r is Route.Detail || r is Route.BoxSet) stack.add(r) else { stack.clear(); stack.add(r) } }
    fun back(): Boolean { if (stack.size <= 1) return false; stack.removeAt(stack.lastIndex); return true }
    fun isTop(r: Route) = current == r
}
