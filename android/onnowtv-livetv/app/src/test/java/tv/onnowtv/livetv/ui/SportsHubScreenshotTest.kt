package tv.onnowtv.livetv.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import tv.onnowtv.livetv.SportsHubActivity
import tv.onnowtv.livetv.data.SportsHubLayout
import java.io.File

/** Renders each Sports Hub layout at 1920x1080 to build/hub-shots (run on demand: -Dhub.shots=true). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-television-xhdpi")
class SportsHubScreenshotTest {
    @Test
    fun renderLayouts() {
        if (System.getProperty("hub.shots") != "true") return
        val ctx = RuntimeEnvironment.getApplication() as Context
        val out = File(System.getProperty("hub.shots.dir") ?: "build/hub-shots").apply { mkdirs() }
        SportsHubLayout.values().forEach { layout ->
            SportsHubLayout.set(ctx, layout)
            val activity = Robolectric.buildActivity(SportsHubActivity::class.java).create().start().resume().visible().get()
            val root = activity.window.decorView
            val deadline = System.currentTimeMillis() + (System.getProperty("hub.wait")?.toLong() ?: 25_000L)
            while (System.currentTimeMillis() < deadline) { Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200)); Thread.sleep(200) }
            root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1920, 1080)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val bmp = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bmp))
            File(out, "hub_${layout.ordinal + 1}_${layout.name.lowercase()}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            activity.finish()
        }
        SportsHubLayout.set(ctx, SportsHubLayout.RAIL)
    }
}
