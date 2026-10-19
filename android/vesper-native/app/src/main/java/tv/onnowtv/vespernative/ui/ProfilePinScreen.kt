package tv.onnowtv.vespernative.ui

import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.*
import androidx.core.widget.doAfterTextChanged
import tv.onnowtv.vespernative.profile.*

class ProfilePinScreen(c: Context, profile: CloudProfile, private val snapshot: CloudSnapshot,
    private val unlocked: (ProfileData) -> Unit, cancel: () -> Unit) : NativeScreen(c) {
    private val profileId = profile.id
    private val error = TvUi.text(c, "profile-pin-error", size = 14f, color = android.graphics.Color.rgb(252, 165, 165))
    private var resetting = false
    private var completed = false
    private val digits = (0..3).map { index -> TvUi.identify(EditText(c), "profile-pin-digit-$index").apply {
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        filters = arrayOf(InputFilter.LengthFilter(1), android.text.method.DigitsKeyListener.getInstance("0123456789"))
        isSaveEnabled = false; importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        isLongClickable = false; setSingleLine(); gravity = Gravity.CENTER
        setTextColor(TvUi.text); textSize = 22.5f
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused),TvUi.shape(android.graphics.Color.argb(36,93,200,255),TvUi.dp(c,9).toFloat(),TvUi.bright,TvUi.dp(c,2)))
            addState(intArrayOf(),TvUi.shape(android.graphics.Color.argb(15,255,255,255),TvUi.dp(c,9).toFloat(),android.graphics.Color.argb(46,255,255,255),1)) }
        contentDescription = "PIN digit ${index + 1}"
    } }
    init {
        root.setBackgroundColor(android.graphics.Color.rgb(3,6,15))
        val content = TvUi.column(c).apply { gravity = Gravity.CENTER; setPadding(TvUi.dp(c,24),TvUi.dp(c,24),TvUi.dp(c,24),TvUi.dp(c,24)); background=V2Ui.panel(c,18) }
        content.addView(V2Ui.eyebrow(c, "profile-pin-label", "PROFILE LOCKED").apply { textSize=8.25f; letterSpacing=.32f })
        content.addView(TvUi.heading(c, "profile-pin-title", "Enter ${profile.name}'s PIN", 21f).apply { maxLines = 2; gravity = Gravity.CENTER })
        content.addView(TvUi.gap(c, 24))
        val row = TvUi.row(c)
        digits.forEachIndexed { index, field ->
            row.addView(field, LinearLayout.LayoutParams(TvUi.dp(c, 42), TvUi.dp(c, 52)).apply { setMargins(TvUi.dp(c,4), 0, TvUi.dp(c,4), 0) })
            field.doAfterTextChanged {
                if (!resetting && !completed) {
                    error.text = ""
                    if (field.text.isNotEmpty() && index < 3) digits[index + 1].requestFocus()
                    if (digits.all { it.text.length == 1 }) submit()
                }
            }
            field.setOnKeyListener { _, key, event ->
                if (key == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN && field.text.isEmpty() && index > 0) digits[index - 1].requestFocus() else false
            }
        }
        content.addView(row); content.addView(error); content.addView(TvUi.gap(c, 20))
        content.addView(V2Ui.pill(c, "profile-pin-cancel", "Cancel",click=cancel))
        root.addView(content, FrameLayout.LayoutParams(TvUi.dp(c,330),-2,Gravity.CENTER))
    }
    private fun submit() {
        val entered = digits.joinToString("") { it.text.toString() }
        try {
            val data = snapshot.openProfile(profileId, entered)
            completed = true
            clearDigits()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(root.windowToken, 0)
            unlocked(data)
        } catch (_: ProfileLocked) {
            clearDigits(); error.text = "Incorrect PIN. Try again."; digits.first().requestFocus()
        }
    }
    private fun clearDigits() { resetting = true; digits.forEach { it.text.clear() }; resetting = false }
    override fun initialFocus(): View = digits.first()
    override fun dispose() {
        completed = true; clearDigits()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
            .hideSoftInputFromWindow(root.windowToken, 0)
        super.dispose()
    }
}