package tv.onnow.launcher.install

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * v2.10.75 — Centered modal progress UI for the launcher's
 * tile install / update flow.
 *
 * User report: *"When it's doing the install or when it's doing
 * the update, it only shows the loading for like a few seconds
 * and then the percentage thing disappears.  Can we have that
 * loading bar, that nice blue loading bar that we used to have
 * in the middle, and make sure it lasts all the way until the
 * actual install page pops up."*
 *
 * The previous flow used a `Toast.LENGTH_LONG` to display the
 * "Updating X… N%" message.  Android auto-dismisses LENGTH_LONG
 * toasts after ~3.5 seconds regardless of activity, so any
 * download longer than that left the user staring at the
 * launcher with no feedback until the system install dialog
 * finally surfaced.
 *
 * This dialog stays up for the FULL lifecycle of the install:
 *   • While the APK is downloading — progress 0 → 100 %.
 *   • While the old version is being uninstalled (signature
 *     conflict path).
 *   • Until the system install dialog is about to overlay it.
 *
 * Pure programmatic UI to match the rest of the launcher (no
 * extra layout XML).  Cyan/blue accent matches the V2 brand.
 */
class InstallProgressDialog private constructor(
    private val dialog: AlertDialog,
    private val titleView: TextView,
    private val progressBar: ProgressBar,
    private val messageView: TextView,
    private val percentView: TextView,
) {

    fun setTitle(text: String) {
        titleView.text = text
    }

    fun setMessage(text: String) {
        messageView.text = text
    }

    /**
     * Update the % indicator.  Values < 0 switch the bar to
     * indeterminate mode (used while we wait for the first byte
     * of the download to arrive — the user sees a moving
     * indicator instead of a flat empty bar).
     */
    fun setProgress(pct: Int) {
        if (pct < 0) {
            progressBar.isIndeterminate = true
            percentView.text = "PREPARING…"
        } else {
            val clamped = pct.coerceIn(0, 100)
            progressBar.isIndeterminate = false
            progressBar.progress = clamped
            percentView.text = "$clamped%"
        }
    }

    fun dismiss() {
        try { dialog.dismiss() } catch (_: Throwable) { /* already gone */ }
    }

    companion object {
        fun show(ctx: Context, title: String, message: String): InstallProgressDialog {
            val density = ctx.resources.displayMetrics.density
            fun dp(v: Int): Int = (v * density).toInt()

            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(36), dp(32), dp(36), dp(28))
                background = GradientDrawable().apply {
                    cornerRadius = dp(20).toFloat()
                    colors = intArrayOf(
                        Color.parseColor("#FF0F1830"),
                        Color.parseColor("#FF07101F"),
                    )
                    orientation = GradientDrawable.Orientation.TOP_BOTTOM
                    setStroke(dp(1), Color.parseColor("#475DC8FF"))
                }
                gravity = Gravity.CENTER_HORIZONTAL
            }

            /* Title */
            val titleView = TextView(ctx).apply {
                text = title
                textSize = 20f
                setTextColor(Color.parseColor("#FFF4F7FB"))
                typeface = android.graphics.Typeface.create(
                    android.graphics.Typeface.SANS_SERIF,
                    android.graphics.Typeface.BOLD,
                )
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(14))
            }
            root.addView(titleView)

            /* Progress bar — bright cyan over a translucent track. */
            val progressBar = ProgressBar(
                ctx, null, android.R.attr.progressBarStyleHorizontal,
            ).apply {
                max = 100
                progress = 0
                isIndeterminate = true
                progressDrawable.setColorFilter(
                    Color.parseColor("#FF5DC8FF"), PorterDuff.Mode.SRC_IN,
                )
                indeterminateDrawable.setColorFilter(
                    Color.parseColor("#FF5DC8FF"), PorterDuff.Mode.SRC_IN,
                )
                layoutParams = LinearLayout.LayoutParams(
                    dp(320), dp(8),
                ).apply {
                    topMargin = dp(6)
                    bottomMargin = dp(10)
                }
            }
            root.addView(progressBar)

            /* "42%" / "PREPARING…" */
            val percentView = TextView(ctx).apply {
                text = "PREPARING…"
                textSize = 13f
                setTextColor(Color.parseColor("#FF5DC8FF"))
                typeface = android.graphics.Typeface.MONOSPACE
                letterSpacing = 0.18f
                gravity = Gravity.CENTER
                setPadding(0, 0, 0, dp(12))
            }
            root.addView(percentView)

            /* Sub-status message */
            val messageView = TextView(ctx).apply {
                text = message
                textSize = 13f
                setTextColor(Color.parseColor("#FFAAB6C5"))
                gravity = Gravity.CENTER
                // v2.10.83 — Kotlin can't synthesize a setter for
                // `lineSpacingExtra` (TextView only exposes a getter
                // for it; the setter is `setLineSpacing(add, mult)`
                // for both fields together).  Call the Java setter
                // directly with multiplier=1.0 to preserve default
                // line height while adding 2 dp of breathing room
                // between wrapped lines.
                setLineSpacing(dp(2).toFloat(), 1.0f)
            }
            root.addView(messageView)

            val dialog = AlertDialog.Builder(ctx)
                .setView(root)
                .setCancelable(false)
                .create()
            dialog.window?.apply {
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
                setLayout(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            dialog.show()
            return InstallProgressDialog(
                dialog, titleView, progressBar, messageView, percentView,
            )
        }
    }
}
