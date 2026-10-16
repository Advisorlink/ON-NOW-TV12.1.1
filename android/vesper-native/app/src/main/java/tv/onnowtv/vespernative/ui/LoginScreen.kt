package tv.onnowtv.vespernative.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import tv.onnowtv.vespernative.BuildConfig
import tv.onnowtv.vespernative.R
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod

class LoginScreen(c: Context, private val submit: (String, String) -> Unit) : NativeScreen(c) {
    private val username = input("login-username", "Username", false)
    private val password = input("login-password", "Password", true)
    private val signIn = TvUi.button(c, "login-submit", "Sign in") { send() }
    private val error = TvUi.text(c, "login-error", size = 13f, color = Color.rgb(255, 150, 150))
    private var submitting = false
    private val prefilled = BuildConfig.DEBUG && BuildConfig.DEBUG_PREFILL_ENABLED &&
        BuildConfig.DEBUG_PREFILL_USERNAME.isNotBlank() && BuildConfig.DEBUG_PREFILL_PASSWORD.isNotEmpty()
    init {
        if (prefilled) {
            username.setText(BuildConfig.DEBUG_PREFILL_USERNAME)
            password.setText(BuildConfig.DEBUG_PREFILL_PASSWORD)
        }
        val image = ImageView(c).apply { setImageResource(R.drawable.login_backdrop); scaleType = ImageView.ScaleType.CENTER_CROP }
        root.addView(image, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(c).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.argb(170, 3, 6, 15), Color.argb(230, 3, 6, 15))) }, FrameLayout.LayoutParams(-1, -1))
        val row = TvUi.row(c).apply { setPadding(TvUi.dp(c, 48), TvUi.dp(c, 24), TvUi.dp(c, 48), TvUi.dp(c, 24)) }
        val brand = TvUi.column(c).apply {
            addView(TvUi.text(c, "login-brand", "ON NOW TV · V2", 15f, TvUi.accent))
            addView(TvUi.gap(c, 20))
            addView(TvUi.heading(c, "login-headline", "Movies, TV shows,\nand everything\nworth watching.", 32f))
        }
        row.addView(brand, LinearLayout.LayoutParams(0, -2, 1.2f))
        val form = TvUi.column(c).apply {
            background = TvUi.shape(Color.argb(205, 13, 20, 30), TvUi.dp(c, 12).toFloat())
            setPadding(TvUi.dp(c, 24), TvUi.dp(c, 24), TvUi.dp(c, 24), TvUi.dp(c, 24))
            addView(TvUi.heading(c, "login-title", "Welcome back", 26f)); addView(TvUi.gap(c, 14))
            addView(username, LinearLayout.LayoutParams(-1, TvUi.dp(c, 48)))
            addView(TvUi.gap(c, 12))
            // Password row: field + show/hide toggle
            val passRow = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
            var passwordVisible = false
            lateinit var showToggle: android.widget.TextView
            showToggle = TvUi.button(c, "login-show-password", "Show") {
                passwordVisible = !passwordVisible
                password.transformationMethod = if (passwordVisible) HideReturnsTransformationMethod.getInstance() else PasswordTransformationMethod.getInstance()
                showToggle.text = if (passwordVisible) "Hide" else "Show"
                password.setSelection(password.text.length)
            }
            showToggle.textSize = 11f
            passRow.addView(password, LinearLayout.LayoutParams(0, TvUi.dp(c, 48), 1f))
            passRow.addView(TvUi.gap(c, 8))
            passRow.addView(showToggle, LinearLayout.LayoutParams(-2, TvUi.dp(c, 48)))
            addView(passRow, LinearLayout.LayoutParams(-1, -2))
            addView(TvUi.gap(c, 14)); addView(signIn, LinearLayout.LayoutParams(-1, TvUi.dp(c, 44)))
            addView(error, LinearLayout.LayoutParams(-1, -2))
            addView(TvUi.gap(c, 8))
            addView(TvUi.text(c, "login-server-hint", "server: ${BuildConfig.API_BASE_URL.removePrefix("https://")}", 10f, Color.argb(120, 200, 200, 200)))
        }
        row.addView(form, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        username.nextFocusDownId = password.id; password.nextFocusUpId = username.id
        password.nextFocusDownId = signIn.id; signIn.nextFocusUpId = password.id
        password.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { send(); true } else false
        }
    }
    private fun input(key: String, hint: String, secret: Boolean) = TvUi.identify(EditText(context), key).apply {
        this.hint = hint; contentDescription = hint; setTextColor(TvUi.text); setHintTextColor(TvUi.muted)
        textSize = 16f; setSingleLine(); background = TvUi.focus(context, false)
        setPadding(TvUi.dp(context, 12), 0, TvUi.dp(context, 12), 0)
        inputType = InputType.TYPE_CLASS_TEXT or if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        filters = arrayOf(InputFilter.LengthFilter(if (secret) 256 else 128))
        imeOptions = if (secret) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        isSaveEnabled = false
    }
    private fun send() {
        if (submitting) return
        val user = username.text.toString().trim(); val pass = password.text.toString()
        if (user.isBlank() || pass.isEmpty()) { error.text = "Enter your username and password."; return }
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(root.windowToken, 0)
        submit(user, pass)
    }
    fun busy(value: Boolean) {
        submitting = value; signIn.isEnabled = !value; username.isEnabled = !value; password.isEnabled = !value
        signIn.text = if (value) "Signing in…" else "Sign in"
        error.text = ""
    }
    fun failed(message: String) { busy(false); error.text = message; password.text.clear(); password.requestFocus() }
    override fun initialFocus() = if (prefilled) signIn else username
    override fun dispose() { password.text.clear(); super.dispose() }
}