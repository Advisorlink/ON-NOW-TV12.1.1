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
import coil.load

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
        error.apply {
            visibility=android.view.View.GONE; textSize=8f; maxLines=3; ellipsize=android.text.TextUtils.TruncateAt.END
            setTextColor(Color.rgb(255,118,134)); setPadding(TvUi.dp(c,8),TvUi.dp(c,6),TvUi.dp(c,8),TvUi.dp(c,6))
            background=TvUi.shape(Color.argb(28,255,75,100),TvUi.dp(c,6).toFloat(),Color.argb(56,255,75,100),1)
        }
        val image = ImageView(c).apply { load("file:///android_asset/login/vesper-bg.webp"); scaleType = ImageView.ScaleType.CENTER_CROP }
        root.addView(image, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(c).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.argb(230, 5, 8, 15), Color.argb(158, 5, 8, 15), Color.argb(140,5,8,15))) }, FrameLayout.LayoutParams(-1, -1))
        val row = TvUi.row(c).apply { setPadding(TvUi.dp(c, 16), TvUi.dp(c, 14), TvUi.dp(c, 16), TvUi.dp(c, 14)) }
        val brand = TvUi.column(c).apply {
            addView(V2Ui.eyebrow(c,"login-brand","ON NOW TV · V2").apply { setTextColor(V2Ui.loginAccent) })
            addView(TvUi.gap(c, 12))
            addView(TvUi.heading(c, "login-headline", "Movies, TV shows,\nand everything\nworth watching.", 26f))
            addView(TvUi.gap(c,12))
            addView(TvUi.text(c,"login-pitch","Sign in with the username and password you were given by ON NOW TV.",8f,TvUi.muted).apply { maxWidth=TvUi.dp(c,270); setLineSpacing(0f,1.4f) })
            addView(TvUi.gap(c,20))
            listOf("Movies & full TV series · streaming in HD","Trailers, collections & watchlists","Kids profiles · safe viewing for little ones").forEachIndexed { i,label ->
                val bullet=TvUi.row(c); bullet.addView(View(c).apply { setBackgroundColor(V2Ui.loginAccent) },LinearLayout.LayoutParams(TvUi.dp(c,3),TvUi.dp(c,3)).apply { marginEnd=TvUi.dp(c,7) })
                bullet.addView(TvUi.text(c,"login-bullet-$i",label,8f)); addView(bullet); addView(TvUi.gap(c,8))
            }
        }
        row.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        val form = TvUi.identify(TvUi.column(c),"login-panel").apply {
            background = V2Ui.panel(c)
            setPadding(TvUi.dp(c, 18), TvUi.dp(c, 20), TvUi.dp(c, 18), TvUi.dp(c, 16))
            addView(V2Ui.eyebrow(c,"login-step","STEP 01").apply { setTextColor(V2Ui.loginAccent) }); addView(TvUi.gap(c,8))
            addView(TvUi.heading(c, "login-title", "Sign in to your account", 15f)); addView(TvUi.gap(c, 6))
            addView(TvUi.text(c,"login-description","Sign in to your account to access movies, TV shows and your library.",7f,TvUi.muted))
            addView(TvUi.gap(c,18)); addView(V2Ui.eyebrow(c,"login-username-label","USERNAME")); addView(TvUi.gap(c,6))
            V2Ui.field(username); V2Ui.field(password)
            username.textSize=9f; password.textSize=9f
            addView(V2Ui.fieldShell(c,"login-username-shell",username,"user"), LinearLayout.LayoutParams(-1, TvUi.dp(c, 30)))
            addView(TvUi.gap(c, 12)); addView(V2Ui.eyebrow(c,"login-password-label","PASSWORD")); addView(TvUi.gap(c,6))
            // Password row: field + show/hide toggle
            var passwordVisible = false
            lateinit var showToggle: android.widget.TextView
            showToggle = TvUi.button(c, "login-show-password", "") {
                passwordVisible = !passwordVisible
                password.transformationMethod = if (passwordVisible) HideReturnsTransformationMethod.getInstance() else PasswordTransformationMethod.getInstance()
                showToggle.contentDescription = if (passwordVisible) "Hide password" else "Show password"
                showToggle.isSelected=passwordVisible
                password.setSelection(password.text.length)
            }
            V2Ui.stylePill(showToggle,"eye"); showToggle.setPadding(TvUi.dp(c,6),0,TvUi.dp(c,6),0); showToggle.contentDescription="Show password"
            addView(V2Ui.fieldShell(c,"login-password-shell",password,"lock",showToggle), LinearLayout.LayoutParams(-1,TvUi.dp(c,30)))
            V2Ui.stylePill(signIn,"logout",primary=true)
            addView(TvUi.gap(c, 12)); addView(signIn, LinearLayout.LayoutParams(-1, TvUi.dp(c, 28)))
            addView(error, LinearLayout.LayoutParams(-1, -2))
            addView(TvUi.gap(c,14)); addView(V2Ui.divider(c)); addView(TvUi.gap(c,10))
            addView(TvUi.text(c,"login-help","Need an account? Contact ON NOW TV Support.",8f,TvUi.muted))
            addView(TvUi.text(c, "login-server-hint", "server: ${BuildConfig.API_BASE_URL.removePrefix("https://")}", 8f, Color.argb(120, 200, 200, 200)))
        }
        row.addView(form, LinearLayout.LayoutParams(TvUi.dp(c,240), -2).apply { marginStart=TvUi.dp(c,36) })
        root.addView(row, FrameLayout.LayoutParams(minOf(TvUi.dp(c,580),c.resources.displayMetrics.widthPixels), -2, Gravity.CENTER))
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
        if (user.isBlank() || pass.isEmpty()) { error.text = "Enter your username and password."; error.visibility=android.view.View.VISIBLE; return }
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(root.windowToken, 0)
        submit(user, pass)
    }
    fun busy(value: Boolean) {
        submitting = value; signIn.isEnabled = !value; username.isEnabled = !value; password.isEnabled = !value
        signIn.text = if (value) "Signing in…" else "Sign in"
        error.text = ""
        error.visibility=android.view.View.GONE
    }
    fun failed(message: String) { busy(false); error.text = message; error.visibility=android.view.View.VISIBLE; password.text.clear(); password.requestFocus() }
    override fun initialFocus() = if (prefilled) signIn else username
    override fun dispose() { password.text.clear(); super.dispose() }
}