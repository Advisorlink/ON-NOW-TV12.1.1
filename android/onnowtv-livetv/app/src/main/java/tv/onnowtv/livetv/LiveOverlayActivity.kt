package tv.onnowtv.livetv

/** Full-screen Live TV drawn as a see-through layer over the guide, whose own video
 *  window has grown to fill the screen — no surface hand-over, no stream restart. */
class LiveOverlayActivity : PlayerActivity() {
    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
