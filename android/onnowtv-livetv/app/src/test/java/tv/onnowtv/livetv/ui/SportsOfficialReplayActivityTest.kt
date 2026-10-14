package tv.onnowtv.livetv.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tv.onnowtv.livetv.SportsOfficialReplayActivity

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
class SportsOfficialReplayActivityTest {

    @Test
    fun allowed_navigation_only_permits_main_backend_url_and_youtube_embed_subframes() {
        val pageUrl = "https://rebrand-app-5.preview.emergentagent.com/api/sportshub/official-replays/nrl-origin-2012-game-3/player"

        assertTrue(SportsOfficialReplayActivity.allowedNavigation("about:blank", pageUrl, mainFrame = true))
        assertTrue(SportsOfficialReplayActivity.allowedNavigation(pageUrl, pageUrl, mainFrame = true))
        assertFalse(SportsOfficialReplayActivity.allowedNavigation("https://www.youtube.com/watch?v=YXnYVCpGHf0", pageUrl, mainFrame = true))
        assertFalse(SportsOfficialReplayActivity.allowedNavigation("intent://watch?v=YXnYVCpGHf0", pageUrl, mainFrame = true))
        assertFalse(SportsOfficialReplayActivity.allowedNavigation("http://www.youtube.com/embed/YXnYVCpGHf0", pageUrl, mainFrame = false))

        assertTrue(SportsOfficialReplayActivity.allowedNavigation("https://www.youtube-nocookie.com/embed/YXnYVCpGHf0", pageUrl, mainFrame = false))
        assertTrue(SportsOfficialReplayActivity.allowedNavigation("https://www.youtube.com/embed/YXnYVCpGHf0", pageUrl, mainFrame = false))
        assertFalse(SportsOfficialReplayActivity.allowedNavigation("https://www.youtube.com/watch?v=YXnYVCpGHf0", pageUrl, mainFrame = false))
        assertFalse(SportsOfficialReplayActivity.allowedNavigation("https://evil.example/embed/YXnYVCpGHf0", pageUrl, mainFrame = false))
    }
}
