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
            visibility=android.view.View.GONE; textSize=9f; maxLines=3; ellipsize=android.text.TextUtils.TruncateAt.END
            setTextColor(Color.rgb(255,118,134)); setPadding(TvUi.dp(c,8),TvUi.dp(c,6),TvUi.dp(c,8),TvUi.dp(c,6))
            background=TvUi.shape(Color.argb(28,255,75,100),TvUi.dp(c,6).toFloat(),Color.argb(56,255,75,100),1)
        }
        val image = ImageView(c).apply { load("file:///android_asset/login/vesper-bg.webp"); scaleType = ImageView.ScaleType.CENTER_CROP }
        root.addView(image, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(c).apply { background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(Color.argb(230, 5, 8, 15), Color.argb(158, 5, 8, 15), Color.argb(140,5,8,15))) }, FrameLayout.LayoutParams(-1, -1))
        // V2 LoginScreen metrics at the TV's 1280px CSS viewport (1 CSS px = 0.75 native dp).
        val accent=V2Ui.loginAccent
        fun mono(id:String,label:String,size:Float,color:Int,spacing:Float)=TvUi.text(c,id,label,size,color).apply {
            typeface=androidx.core.content.res.ResourcesCompat.getFont(c,R.font.jetbrains_mono);fontVariationSettings="'wght' 700";letterSpacing=spacing }
        val row = TvUi.row(c).apply { setPadding(TvUi.dp(c, 48), 0, TvUi.dp(c, 48), 0) }
        val brand = TvUi.column(c).apply {
            addView(TvUi.row(c).apply {
                background=TvUi.shape(Color.argb(184,12,20,34),TvUi.dp(c,9).toFloat(),Color.argb(26,255,255,255),1)
                setPadding(TvUi.dp(c,10),TvUi.dp(c,5),TvUi.dp(c,10),TvUi.dp(c,5))
                addView(View(c).apply { setBackgroundColor(accent) },LinearLayout.LayoutParams(TvUi.dp(c,4),TvUi.dp(c,4)).apply { marginEnd=TvUi.dp(c,7) })
                addView(mono("login-brand","ON NOW TV · V2",8.25f,accent,.28f))
            },LinearLayout.LayoutParams(-2,-2))
            addView(TvUi.gap(c, 18))
            addView(TvUi.heading(c, "login-headline", "Movies, TV shows,\nand everything\nworth watching.", 34.5f).apply {
                fontVariationSettings="'wght' 900";if(android.os.Build.VERSION.SDK_INT>=28)typeface=android.graphics.Typeface.create(typeface,900,false)
                letterSpacing=-.025f;setLineSpacing(0f,1.06f);setShadowLayer(TvUi.dp(c,16).toFloat(),0f,TvUi.dp(c,4).toFloat(),Color.argb(191,0,0,0)) })
            addView(TvUi.gap(c,13))
            addView(TvUi.text(c,"login-pitch","Sign in with the username and password you were given by ON NOW TV.",11.6f,Color.argb(209,214,226,244)).apply {
                maxWidth=TvUi.dp(c,300); setLineSpacing(0f,1.55f);setShadowLayer(TvUi.dp(c,6).toFloat(),0f,TvUi.dp(c,1).toFloat(),Color.argb(179,0,0,0)) })
            addView(TvUi.gap(c,24))
            listOf("Movies & full TV series · streaming in HD","Trailers, collections & watchlists","Kids profiles · safe viewing for little ones").forEachIndexed { i,label ->
                val bullet=TvUi.row(c); bullet.addView(View(c).apply { setBackgroundColor(accent);glow(this,3,accent,accent) },LinearLayout.LayoutParams(TvUi.dp(c,4),TvUi.dp(c,4)).apply { marginEnd=TvUi.dp(c,10) })
                bullet.addView(TvUi.text(c,"login-bullet-$i",label,10.5f,Color.argb(235,232,240,252))); addView(bullet); addView(TvUi.gap(c,9))
            }
        }
        row.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        val form = TvUi.identify(TvUi.column(c),"login-panel").apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(Color.argb(199,14,22,42),Color.argb(230,6,10,22))).apply {
                cornerRadius=TvUi.dp(c,18).toFloat();setStroke(1,Color.argb(56,93,200,255)) }
            glow(this,18,Color.argb(160,93,200,255),Color.BLACK)
            setPadding(TvUi.dp(c, 24), TvUi.dp(c, 18), TvUi.dp(c, 24), TvUi.dp(c, 18))
            addView(mono("login-step","STEP 01",7.5f,accent,.32f)); addView(TvUi.gap(c,6))
            addView(TvUi.heading(c, "login-title", "Sign in to your account", 21f).apply { letterSpacing=-.02f }); addView(TvUi.gap(c, 5))
            addView(TvUi.text(c,"login-description","Sign in to your account to access movies, TV shows and your library.",9.75f,Color.argb(168,214,226,244)).apply { setLineSpacing(0f,1.5f) })
            addView(TvUi.gap(c,24)); addView(mono("login-username-label","USERNAME",7.5f,accent,.28f)); addView(TvUi.gap(c,6))
            V2Ui.field(username); V2Ui.field(password)
            username.textSize=11.6f; password.textSize=11.6f
            addView(V2Ui.fieldShell(c,"login-username-shell",username,"user"), LinearLayout.LayoutParams(-1, TvUi.dp(c, 36)))
            addView(TvUi.gap(c, 12)); addView(mono("login-password-label","PASSWORD",7.5f,accent,.28f)); addView(TvUi.gap(c,6))
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
            addView(V2Ui.fieldShell(c,"login-password-shell",password,"lock",showToggle), LinearLayout.LayoutParams(-1,TvUi.dp(c,36)))
            V2Ui.stylePill(signIn,"logout",primary=true)
            signIn.textSize=12f; signIn.setTextColor(Color.rgb(4,16,30)); signIn.setTypeface(signIn.typeface,android.graphics.Typeface.BOLD)
            signIn.background=android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(-android.R.attr.state_enabled),TvUi.shape(Color.argb(115,93,200,255),TvUi.dp(c,24).toFloat()))
                addState(intArrayOf(),GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(Color.rgb(93,200,255),Color.rgb(46,168,232))).apply { cornerRadius=TvUi.dp(c,24).toFloat() })
            }
            glow(signIn,8,Color.argb(140,93,200,255),Color.argb(90,93,200,255))
            addView(TvUi.gap(c, 16)); addView(signIn, LinearLayout.LayoutParams(-1, TvUi.dp(c, 40)))
            addView(error, LinearLayout.LayoutParams(-1, -2))
            addView(TvUi.gap(c,16)); addView(V2Ui.divider(c)); addView(TvUi.gap(c,12))
            addView(TvUi.row(c).apply {
                addView(TvUi.text(c,"login-help","Need an account? Contact ON NOW TV Support.",8.6f,Color.argb(115,255,255,255)),LinearLayout.LayoutParams(0,-2,1f))
                addView(mono("login-secure","SECURE",7.5f,accent,.28f))
            })
            addView(TvUi.text(c, "login-server-hint", "server: ${BuildConfig.API_BASE_URL.removePrefix("https://")}", 6.5f, Color.argb(90, 200, 200, 200)).apply { setPadding(0,TvUi.dp(c,6),0,0) })
        }
        row.addView(form, LinearLayout.LayoutParams(TvUi.dp(c,360), -2).apply { marginStart=TvUi.dp(c,38) })
        root.addView(row, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL))
        root.addView(mono("login-brandline","ON NOW TV · V2",8.25f,Color.argb(191,120,140,175),.32f).apply { fontVariationSettings="'wght' 500" },
            FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM or Gravity.START).apply { leftMargin=TvUi.dp(c,48);bottomMargin=TvUi.dp(c,24) })
        username.nextFocusDownId = password.id; password.nextFocusUpId = username.id
        password.nextFocusDownId = signIn.id; signIn.nextFocusUpId = password.id
        password.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { send(); true } else false
        }
    }
    /** Coloured elevation glow; the colour API exists from Android 9, older boxes keep a neutral shadow. */
    private fun glow(v:View,dp:Int,spot:Int,ambient:Int) { v.elevation=TvUi.dp(context,dp).toFloat()
        if(android.os.Build.VERSION.SDK_INT>=28){v.outlineSpotShadowColor=spot;v.outlineAmbientShadowColor=ambient} }
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