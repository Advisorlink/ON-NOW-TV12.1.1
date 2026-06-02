package tv.onnowtv.livetv

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import tv.onnowtv.livetv.data.XtreamRepository

/**
 * Splash screen + initial bundle fetch.  As soon as the gzipped
 * EPG bundle is in memory we hand off to EpgActivity, passing the
 * bundle through `BundleHolder` (a process-scoped singleton so we
 * don't have to re-serialise ~3 MB of channels through an Intent).
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // Swap the splash theme out for the regular theme so the
        // post-splash window doesn't keep the splash drawable.
        setTheme(R.style.Theme_OnNowLiveTV_NoActionBar)

        val status: TextView = findViewById(R.id.loading_status)

        lifecycleScope.launch {
            try {
                status.text = "Loading guide…"
                val bundle = XtreamRepository.fetchBundle()
                BundleHolder.current = bundle
                Log.i("MainActivity", "bundle: ${bundle.channels.size} channels, ${bundle.epg.size} epg buckets")
                startActivity(Intent(this@MainActivity, EpgActivity::class.java))
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                finish()
            } catch (t: Throwable) {
                Log.e("MainActivity", "fetchBundle failed", t)
                status.text = "Couldn't load guide.\n${t.message ?: t::class.java.simpleName}\nTap to retry."
                status.setOnClickListener {
                    recreate()
                }
            }
        }
    }
}

/** Process-scoped holder for the freshly-fetched bundle.  Avoids
 *  serialising channels + EPG through an Intent extra (which would
 *  blow the Binder 1 MB transaction limit). */
object BundleHolder {
    @Volatile var current: tv.onnowtv.livetv.data.XtreamBundle? = null
}
