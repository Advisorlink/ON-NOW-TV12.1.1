package tv.onnow.launcher

import androidx.annotation.DrawableRes

/**
 * One row in the bottom dock.  6 of these render in MainActivity's
 * RecyclerView; their order + labels + sub-labels + target intents
 * will eventually come from the backend (Phase 2).
 */
data class DockItem(
    val key: String,                 // stable id: "movies" | "music" | "livetv" | "apps" | "browser" | "settings"
    val label: String,               // "Live TV"
    val sub: String,                 // "Watch live channels"
    @DrawableRes val iconRes: Int,
)
