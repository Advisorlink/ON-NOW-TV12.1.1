package tv.onnowtv.livetv.data

/** Sports Hub menu style: the full rail on TV, an icon-only dock on narrow phone screens. */
enum class SportsHubLayout(val accent: Int) {
    RAIL(0xFF5DC8FF.toInt()), DOCK(0xFF5DC8FF.toInt());

    companion object {
        fun forWidth(widthDp: Int) = if (widthDp < 600) DOCK else RAIL
    }
}
