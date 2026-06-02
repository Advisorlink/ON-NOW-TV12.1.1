package tv.onnowtv.livetv

import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Full-screen diagnostic activity launched by `LiveTVApp` when the
 * app would otherwise crash.  Shows the exception class + message
 * + full stack trace so we can debug on a real TV box without adb.
 */
class CrashActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MESSAGE = "crash.message"
        const val EXTRA_STACK = "crash.stack"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val msg = intent.getStringExtra(EXTRA_MESSAGE) ?: "(no message)"
        val stack = intent.getStringExtra(EXTRA_STACK) ?: "(no stack)"

        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF0A0F1A.toInt())
            setPadding(48, 48, 48, 48)
        }
        val tv = TextView(this).apply {
            setTextColor(0xFFE6EAF2.toInt())
            textSize = 14f
            typeface = android.graphics.Typeface.MONOSPACE
            text = buildString {
                appendLine("V2 LIVE TV — CRASH DIAGNOSTIC")
                appendLine("───────────────────────────────")
                appendLine()
                appendLine(msg)
                appendLine()
                appendLine(stack)
            }
        }
        scroll.addView(tv)
        setContentView(scroll)
    }
}
