package tv.onnowtv.vespernative.ui

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.*
import coil.load
import coil.transform.CircleCropTransformation

class NavigationBar(c: Context, active: String, private val navigate: (String) -> Unit,
    avatar: String? = null, private val side: Boolean = false) : FrameLayout(c) {
    val activeButton: View
    val buttons = mutableListOf<View>()
    init {
        TvUi.identify(this, "navigation-bar"); clipChildren = false; clipToPadding = false
        val bar = if (side) TvUi.column(c) else TvUi.row(c)
        bar.background = TvUi.shape(Color.argb(240, 17, 24, 39), TvUi.dp(c, 28).toFloat(), Color.argb(18,255,255,255), 1)
        bar.setPadding(TvUi.dp(c, 6), TvUi.dp(c, 4), TvUi.dp(c, 6), TvUi.dp(c, 4))
        val label = TvUi.text(c, "nav-focus-label", size = 12f).apply {
            gravity = Gravity.CENTER; visibility = View.INVISIBLE
            background = TvUi.shape(TvUi.surface, TvUi.dp(c, 16).toFloat())
            setPadding(TvUi.dp(c,12), TvUi.dp(c,5), TvUi.dp(c,12), TvUi.dp(c,5))
        }
        val entries = listOf("search" to "Search", "home" to "Home", "series" to "TV Shows", "movie" to "Movies",
            "boxsets" to "Box Sets", "anime" to "Anime", "library" to "My Library", "settings" to "Settings", "account" to "Switch profile")
        var selected: View? = null
        entries.forEach { (route, name) ->
            val button = TvUi.identify(FrameLayout(c), "nav-$route").apply {
                isFocusable = true; isClickable = true; contentDescription = name; tooltipText = name
                background = TvUi.outline(c, 24)
            }
            if (route == "account" && avatar != null) {
                button.addView(ImageView(c).apply { load("file:///android_asset/avatars/$avatar.jpg") { transformations(CircleCropTransformation()) } },
                    LayoutParams(TvUi.dp(c, 26), TvUi.dp(c,26), Gravity.CENTER))
            } else button.addView(NavIcon(c, route).apply { tint = if (active == route) TvUi.accent else TvUi.muted }, LayoutParams(-1, -1))
            button.setOnClickListener { navigate(route) }
            FocusMotion.bind(button,tile=false,radius=24) { focused ->
                if (focused) { label.text = name; label.visibility = View.VISIBLE }
                else label.visibility = View.INVISIBLE
            }
            bar.addView(button, LinearLayout.LayoutParams(TvUi.dp(c, 36), TvUi.dp(c, 36)).apply {
                if (side) bottomMargin = TvUi.dp(c, 2) else marginEnd = TvUi.dp(c, 2)
            })
            buttons.add(button); if (active == route) selected = button
        }
        activeButton = selected ?: buttons[1]
        addView(bar, LayoutParams(-2,-2, if (side) Gravity.CENTER_VERTICAL or Gravity.START else Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        addView(label, LayoutParams(-2,-2, if (side) Gravity.BOTTOM or Gravity.START else Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            if (!side) topMargin = TvUi.dp(c,48) else bottomMargin = TvUi.dp(c,8)
        })
    }
}