package tv.onnowtv.livetv.data

/** Widths are Android dp, not physical screenshot pixels. */
object SportsLayoutPolicy {
    fun columns(widthDp: Int) = when { widthDp < 600 -> 1; widthDp < 1100 -> 2; else -> 3 }
    fun stackedGame(widthDp: Int, heightDp: Int, television: Boolean) = !television && (widthDp < 840 || heightDp < 480)
    fun portrait(widthDp: Int, heightDp: Int) = widthDp < heightDp
}