package tv.onnowtv.livetv.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView
import tv.onnowtv.livetv.R

/**
 * Brand-styled action sheet used by every long-press menu in the
 * Live TV app — channel context, collection manage, category
 * context, "Add to Collection" sub-menu, etc.
 *
 * The card design (dark navy, rounded corners, neon-blue accents)
 * matches the EPG sidebar / Library tiles so popups no longer look
 * like stock Android.  All rows are focusable so the d-pad walks
 * through them naturally.
 *
 * Construct with a fluent API:
 *
 *     ActionSheetDialog(ctx)
 *         .title("Sky Sports F1")
 *         .subtitle("CHANNEL ACTIONS")
 *         .item("Add to Favourites", icon = "♥") { … }
 *         .item("Add to Collection…", icon = "+") { … }
 *         .show()
 */
class ActionSheetDialog(context: Context) {

    data class Action(
        val label: String,
        val icon: String?,
        val trailing: String?,
        val onClick: () -> Unit,
    )

    private val ctx = context
    private val actions = mutableListOf<Action>()
    private var titleText: String = ""
    private var subtitleText: String? = null

    fun title(text: String) = apply { titleText = text }
    fun subtitle(text: String?) = apply { subtitleText = text }

    fun item(label: String, icon: String? = null, trailing: String? = null, onClick: () -> Unit) =
        apply { actions.add(Action(label, icon, trailing, onClick)) }

    fun show(): Dialog {
        val dialog = Dialog(ctx, R.style.Theme_OnNowLiveTV_ActionSheet)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LayoutInflater.from(ctx).inflate(R.layout.dialog_action_sheet, null, false)
        val titleView = root.findViewById<TextView>(R.id.action_sheet_title)
        val subtitleView = root.findViewById<TextView>(R.id.action_sheet_subtitle)
        val items = root.findViewById<LinearLayout>(R.id.action_sheet_items)

        titleView.text = titleText
        subtitleText?.let {
            subtitleView.text = it.uppercase()
            subtitleView.visibility = View.VISIBLE
        }

        val inflater = LayoutInflater.from(ctx)
        actions.forEachIndexed { idx, action ->
            val row = inflater.inflate(R.layout.item_action_row, items, false) as LinearLayout
            row.findViewById<TextView>(R.id.action_row_icon).text = action.icon ?: ""
            row.findViewById<TextView>(R.id.action_row_label).text = action.label
            val trailing = row.findViewById<TextView>(R.id.action_row_trailing)
            if (!action.trailing.isNullOrBlank()) {
                trailing.text = action.trailing
                trailing.visibility = View.VISIBLE
            } else {
                trailing.visibility = View.GONE
            }
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                // v2.10.55 — Match the new item_action_row height
                // (was 56 dp → now 78 dp) so the dialog inflates at
                // the user-requested "bigger, nicer" size.
                (78 * ctx.resources.displayMetrics.density).toInt(),
            ).apply {
                topMargin = if (idx == 0) 0
                    else (6 * ctx.resources.displayMetrics.density).toInt()
            }
            row.layoutParams = lp
            row.setOnClickListener {
                dialog.dismiss()
                action.onClick()
            }
            items.addView(row)
        }

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.parseColor("#CC000308")))
            // The card itself defines its own width via the layout,
            // so just let the window wrap.
            setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.setCancelable(true)
        dialog.show()
        // Land focus on the first row so d-pad UP/DOWN/OK works
        // immediately.
        items.post { items.getChildAt(0)?.requestFocus() }
        return dialog
    }
}
