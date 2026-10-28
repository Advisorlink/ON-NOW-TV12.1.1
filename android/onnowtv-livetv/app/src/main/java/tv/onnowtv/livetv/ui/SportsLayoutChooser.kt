package tv.onnowtv.livetv.ui

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import tv.onnowtv.livetv.data.SportsHubLayout
import tv.onnowtv.livetv.ui.SportsUi as UI

/** "Choose layout": five preview cards; picking one saves it and redraws the hub. */
object SportsLayoutChooser {
    fun show(act: AppCompatActivity, onPicked: (SportsHubLayout) -> Unit) {
        val dialog = Dialog(act)
        val current = SportsHubLayout.get(act)
        fun dp(v: Float) = UI.dp(act, v)
        val col = UI.col(act, dp(14f)).apply { setPadding(dp(32f), dp(26f), dp(32f), dp(26f)); background = UI.bg(0xF20A0F1A.toInt(), dp(24f).toFloat(), dp(1f), UI.BORDER) }
        col.addView(UI.eyebrow(act, "V2 Sports Hub", UI.ACCENT))
        col.addView(UI.text(act, "Choose layout", 30f, Color.WHITE, bold = true))
        col.addView(UI.text(act, "Same scores, teams and replays, five different looks.", 14f, UI.DIM))
        val row = UI.row(act, dp(18f)).apply { setPadding(dp(6f), dp(10f), dp(6f), dp(10f)) }
        SportsHubLayout.values().forEachIndexed { i, l ->
            val card = UI.identify(UI.col(act, dp(8f)), "hub-layout-${l.name.lowercase()}").apply { setPadding(dp(8f), dp(8f), dp(8f), dp(12f)) }
            UI.focusable(card, dp(16f).toFloat(), accent = l.accent)
            val frame = FrameLayout(act)
            frame.addView(ImageView(act).apply { setImageResource(l.preview); scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline = true; background = UI.bg(UI.PLOT, dp(12f).toFloat()) }, FrameLayout.LayoutParams(-1, -1))
            if (l == current) frame.addView(UI.text(act, "✓ IN USE", 10f, UI.BG, bold = true).apply { setPadding(dp(8f), dp(3f), dp(8f), dp(3f)); background = UI.bg(l.accent, dp(8f).toFloat()) },
                FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { setMargins(dp(8f), dp(8f), dp(8f), dp(8f)) })
            card.addView(frame, LinearLayout.LayoutParams(dp(240f), dp(135f)))
            card.addView(UI.text(act, "${'A' + i}  ·  ${l.title}", 16f, Color.WHITE, bold = true))
            card.addView(UI.text(act, l.blurb, 11f, UI.DIM).apply { maxLines = 2; maxWidth = dp(240f) })
            card.setOnClickListener { SportsHubLayout.set(act, l); dialog.dismiss(); onPicked(l) }
            row.addView(card)
        }
        col.addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; clipToPadding = false; clipChildren = false; addView(row) })
        dialog.setContentView(col)
        dialog.window?.apply { setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT)); setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT); setDimAmount(.8f) }
        dialog.setOnShowListener { row.findViewWithTag<android.view.View>("hub-layout-${current.name.lowercase()}")?.requestFocus() }
        dialog.show()
    }
}
