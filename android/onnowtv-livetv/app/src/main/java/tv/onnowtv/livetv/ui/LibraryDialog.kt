package tv.onnowtv.livetv.ui

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import tv.onnowtv.livetv.R

/**
 * Vesper-style "Add to Library / Regenerate cover" dialog.
 *
 * Wraps a custom XML layout ([R.layout.dialog_add_to_library]) and
 * exposes three visual states the caller can flip through:
 *
 *   • [showIdle]      — first-paint state with two buttons.
 *   • [showBusy]      — primary button hides, progress strip + elapsed
 *                        timer appears.  The fill bar animates from 0 →
 *                        95 % across [ETA_MS] so the user always has a
 *                        believable "almost done" indicator.
 *   • [snapToComplete] — slams the bar to 100 % once the caller's
 *                        async work finished (just before dismissing).
 *
 * The progress timer increments once per second on the main thread.
 */
class LibraryDialog(private val activity: Activity) {

    companion object {
        /** Empirical Nano Banana 16:9 generation latency on the
         *  Emergent platform.  Used to drive the fake-but-honest
         *  progress curve so the bar fills at a believable rate. */
        private const val ETA_MS = 18_000L
    }

    private val dialog: Dialog
    private val root: View
    private val title: TextView
    private val body: TextView
    private val progressBlock: View
    private val progressFill: View
    private val progressTime: TextView
    private val btnPrimary: Button
    private val btnSecondary: Button
    private val btnTertiary: Button

    private val ui = Handler(Looper.getMainLooper())
    private var progressAnim: ValueAnimator? = null
    private var startedAt = 0L
    private var ticker: Runnable? = null

    init {
        dialog = Dialog(activity)
        // Transparent system background so the rounded card pops.
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.parseColor("#CC000814")))
        // Soft dim behind the dialog so the EPG behind it recedes
        // visually without going pitch-black.
        dialog.window?.setDimAmount(0.55f)
        val inflater = LayoutInflater.from(activity)
        root = inflater.inflate(R.layout.dialog_add_to_library, null)
        dialog.setContentView(root)
        // The root LinearLayout's `android:layout_width="640dp"` is
        // ignored by Dialog's default window WRAP_CONTENT sizing
        // (visible in the screenshot — the dialog shrank to a thin
        // vertical strip).  Force the window to the desired width
        // in pixels here, and pin the root view's LayoutParams to
        // match so children wrap correctly.
        val widthPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            720f,
            activity.resources.displayMetrics,
        ).toInt()
        dialog.window?.setLayout(widthPx, WindowManager.LayoutParams.WRAP_CONTENT)
        // The dialog's DecorView is a `FrameLayout` and its
        // `onMeasure` casts every child's LayoutParams directly to
        // `FrameLayout.LayoutParams` (line 186 of FrameLayout.java).
        // - `ViewGroup.LayoutParams`          → crashed at line 185
        //   (`measureChildWithMargins` needs MarginLayoutParams).
        // - `ViewGroup.MarginLayoutParams`    → crashed at line 186
        //   (FrameLayout.onMeasure needs FrameLayout.LayoutParams).
        // Only `FrameLayout.LayoutParams` survives both casts.  It
        // extends `MarginLayoutParams`, takes the same `(w, h)` ctor
        // and zeroes margins by default.
        root.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        title = root.findViewById(R.id.dlg_title)
        body = root.findViewById(R.id.dlg_body)
        progressBlock = root.findViewById(R.id.dlg_progress_block)
        progressFill = root.findViewById(R.id.dlg_progress_fill)
        progressTime = root.findViewById(R.id.dlg_progress_time)
        btnPrimary = root.findViewById(R.id.dlg_btn_primary)
        btnSecondary = root.findViewById(R.id.dlg_btn_secondary)
        btnTertiary = root.findViewById(R.id.dlg_btn_tertiary)
    }

    /**
     * Configure first-paint state.
     *
     * @param onPrimary    runs when the primary CTA is pressed.
     * @param onSecondary  runs when Cancel/Close is pressed.
     * @param onTertiary   runs when the optional 3rd button is
     *                     pressed.  Pass `null` (the default) to
     *                     hide the tertiary button entirely.
     */
    fun showIdle(
        titleText: String,
        bodyText: String,
        primaryLabel: String = "Add + Generate",
        secondaryLabel: String = "Cancel",
        tertiaryLabel: String? = null,
        onPrimary: () -> Unit,
        onSecondary: () -> Unit = { dismiss() },
        onTertiary: (() -> Unit)? = null,
    ) {
        title.text = titleText
        body.text = bodyText
        progressBlock.visibility = View.GONE
        btnPrimary.visibility = View.VISIBLE
        btnPrimary.text = primaryLabel
        btnSecondary.text = secondaryLabel
        btnPrimary.setOnClickListener { onPrimary() }
        btnSecondary.setOnClickListener { onSecondary() }
        if (tertiaryLabel != null && onTertiary != null) {
            btnTertiary.visibility = View.VISIBLE
            btnTertiary.text = tertiaryLabel
            btnTertiary.setOnClickListener { onTertiary() }
        } else {
            btnTertiary.visibility = View.GONE
        }
        if (!dialog.isShowing) dialog.show()
        // Give focus to the primary CTA so a single OK confirms.
        btnPrimary.post { btnPrimary.requestFocus() }
    }

    /**
     * Switch to busy state.  The progress bar animates to ~95 %
     * across [ETA_MS] and the elapsed timer ticks once per second.
     * Caller must call [snapToComplete] once the network work
     * finishes so the bar slams to 100 % before dismissal.
     */
    fun showBusy(bodyText: String = "Generating your cover — usually 10–20 seconds.") {
        body.text = bodyText
        progressBlock.visibility = View.VISIBLE
        btnPrimary.visibility = View.GONE
        btnTertiary.visibility = View.GONE
        btnSecondary.text = "Hide"
        btnSecondary.setOnClickListener { dismiss() }

        startedAt = System.currentTimeMillis()
        // Start the elapsed-time tick.
        ticker?.let { ui.removeCallbacks(it) }
        ticker = object : Runnable {
            override fun run() {
                val secs = ((System.currentTimeMillis() - startedAt) / 1000L).toInt()
                progressTime.text = "${secs}s"
                ui.postDelayed(this, 500)
            }
        }.also { ui.post(it) }

        // Kick off the fake-but-honest progress curve once the
        // dialog has measured itself (defer one frame if not yet).
        progressFill.post { startProgressAnim() }
    }

    private fun startProgressAnim() {
        val parent = progressFill.parent as? View ?: return
        val parentWidth = parent.width
        if (parentWidth <= 0) {
            // Still not measured — try once more on the next frame.
            progressFill.post { startProgressAnim() }
            return
        }
        val target = (parentWidth * 0.95f).toInt()
        progressAnim?.cancel()
        progressAnim = ValueAnimator.ofInt(0, target).apply {
            duration = ETA_MS
            addUpdateListener { v ->
                val lp = progressFill.layoutParams
                lp.width = v.animatedValue as Int
                progressFill.layoutParams = lp
            }
            start()
        }
    }

    /** Slams the bar to 100 % then auto-dismisses after [holdMs]. */
    fun snapToComplete(holdMs: Long = 350L) {
        progressAnim?.cancel()
        val parent = progressFill.parent as? View
        val full = parent?.width ?: progressFill.width
        val lp = progressFill.layoutParams
        lp.width = full
        progressFill.layoutParams = lp
        ticker?.let { ui.removeCallbacks(it); ticker = null }
        ui.postDelayed({ dismiss() }, holdMs)
    }

    /** Switch to failure state — keeps the dialog open so the
     *  user can read the message + tap Close. */
    fun showError(message: String) {
        progressAnim?.cancel()
        ticker?.let { ui.removeCallbacks(it); ticker = null }
        progressBlock.visibility = View.GONE
        body.text = "Something went wrong:\n\n$message"
        btnPrimary.visibility = View.GONE
        btnTertiary.visibility = View.GONE
        btnSecondary.text = "Close"
        btnSecondary.setOnClickListener { dismiss() }
    }

    fun dismiss() {
        progressAnim?.cancel()
        ticker?.let { ui.removeCallbacks(it); ticker = null }
        try { dialog.dismiss() } catch (_: Throwable) {}
    }

    val isShowing: Boolean get() = dialog.isShowing
}
