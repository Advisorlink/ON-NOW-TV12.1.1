package tv.onnow.launcher.notify

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import tv.onnow.launcher.data.NotificationRemote

/**
 * NotificationPopup
 * ─────────────────
 * Modal dialog the launcher shows when the admin broadcasts a
 * notification through `/api/admin/notify`.  Built programmatically
 * (no XML) so it has zero asset dependencies and matches the
 * launcher's accent colour palette exactly.
 */
object NotificationPopup {

    fun show(
        activity: Activity,
        notif: NotificationRemote,
        onDismiss: () -> Unit,
    ) {
        val dp = activity.resources.displayMetrics.density
        val pad = (24 * dp).toInt()

        val title = TextView(activity).apply {
            text = notif.title
            setTextColor(Color.WHITE)
            textSize = 22f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val body = TextView(activity).apply {
            text = notif.body
            setTextColor(Color.parseColor("#BCC9DA"))
            textSize = 15f
            setPadding(0, (12 * dp).toInt(), 0, 0)
            ellipsize = TextUtils.TruncateAt.END
            maxLines = 12
        }
        val ok = Button(activity).apply {
            text = "OK"
            setTextColor(Color.parseColor("#04060B"))
            background = ColorDrawable(Color.parseColor("#2BB6FF"))
            isAllCaps = false
            textSize = 16f
            setPadding(pad, pad / 2, pad, pad / 2)
        }

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.parseColor("#0F1B30"))
            addView(title)
            addView(body)
            val btnSpacer = FrameLayout(activity).apply {
                setPadding(0, (24 * dp).toInt(), 0, 0)
                addView(ok, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { gravity = Gravity.END })
            }
            addView(btnSpacer)
        }

        val dialog = AlertDialog.Builder(activity)
            .setView(container)
            .setCancelable(false)
            .create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.parseColor("#CC04060B")))

        ok.setOnClickListener {
            dialog.dismiss()
            onDismiss()
        }
        dialog.setOnShowListener {
            ok.requestFocus()    // D-pad lands on OK immediately
        }
        dialog.show()
    }
}
