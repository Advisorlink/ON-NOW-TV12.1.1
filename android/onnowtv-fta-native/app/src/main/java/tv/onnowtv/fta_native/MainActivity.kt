package tv.onnowtv.fta_native

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity

/**
 * Splash screen — held for ~900 ms so the warm gradient + V2 / Free-to-Air
 * wordmark is on screen long enough to register, then hands off to
 * [EpgActivity] for the real EPG.  No data fetch happens here; the EPG
 * shows its own loader while channels + programmes are loading.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        Handler(Looper.getMainLooper()).postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            startActivity(Intent(this, EpgActivity::class.java))
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }, SPLASH_HOLD_MS)
    }

    companion object {
        private const val SPLASH_HOLD_MS = 900L
    }
}
