package tv.onnowtv.livetv

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Separate-process recovery. Restart in the main process so its existing
 * sign-out routine owns all state; never clear shared preferences here. */
class CrashActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_MESSAGE = "crash.message"
        const val EXTRA_STACK = "crash.stack"
        const val EXTRA_RESET_LOGIN = "recovery.login"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(28), dp(20), dp(28), dp(20))
            setBackgroundColor(0xFF0A0F1A.toInt())
        }
        root.addView(TextView(this).apply {
            text = "Live TV stopped unexpectedly"
            textSize = 23f
            setTextColor(0xFFE6EAF2.toInt())
        })
        root.addView(TextView(this).apply {
            text = "Retry, or log in again to reload the guide. Your favourites will stay saved."
            textSize = 15f
            setTextColor(0xFFACBDD0.toInt())
            setPadding(0, dp(8), 0, dp(12))
        })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun recoveryButton(label: String, tag: String, reset: Boolean) = Button(this).apply {
            text = label
            contentDescription = label
            this.tag = tag
            isFocusable = true
            setOnClickListener {
                isEnabled = false
                startActivity(Intent(this@CrashActivity, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    putExtra(EXTRA_RESET_LOGIN, reset)
                })
                finish()
            }
        }
        val retry = recoveryButton("Retry", "crash-retry", false)
        actions.addView(retry)
        actions.addView(recoveryButton("Log in again", "crash-login-again", true))
        root.addView(actions)
        val details = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFFACBDD0.toInt())
            typeface = android.graphics.Typeface.MONOSPACE
            text = "Diagnostic details — include these in your photo\n\n" +
                (intent.getStringExtra(EXTRA_MESSAGE) ?: "Unknown error") + "\n\n" +
                (intent.getStringExtra(EXTRA_STACK) ?: "No stack trace available")
            tag = "crash-diagnostic-details"
            setPadding(0, dp(16), 0, 0)
        }
        val scroll = ScrollView(this).apply { addView(details) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        retry.requestFocus()
    }
}