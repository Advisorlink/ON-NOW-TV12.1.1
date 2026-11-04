import os
os.chdir('/app/android')
p = 'onnowtv-livetv/app/src/main/java/tv/onnowtv/livetv/EpgActivity.kt'; s = open(p).read()
old = """        wireViews()
        buildCategories()
        setupAdapters()
        setupWhatsOnHub()"""
new = """        wireViews()
        installRebornTopNav()
        buildCategories()
        setupAdapters()
        setupWhatsOnHub()"""
assert old in s; s = s.replace(old, new, 1)
old = """    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_epg)"""
new = """    /** Reborn Play (embedded) — one-app top menu above the guide. */
    private fun installRebornTopNav() {
        if (!LiveTvRuntime.embedded) return
        val root = (findViewById<android.view.ViewGroup>(android.R.id.content)).getChildAt(0) as? android.widget.FrameLayout ?: return
        val content = root.getChildAt(0) ?: return
        RebornTopNav.install(this, root, content)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_epg)"""
assert old in s; s = s.replace(old, new, 1)
open(p, 'w').write(s)

p = 'vesper-tv/app/src/main/java/tv/vesper/app/MainActivity.kt'; s = open(p).read()
old = """    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        try {"""
new = """    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // Reborn Play: native Live TV top menu hands a route back to the web shell.
        intent.getStringExtra("reborn_route")?.takeIf { it.isNotBlank() }?.let { route ->
            if (webViewReady) {
                val safe = route.replace("'", "")
                webView.post { try { webView.evaluateJavascript("window.location.hash='#$safe'", null) } catch (_: Throwable) {} }
            }
            return
        }
        try {"""
assert old in s; s = s.replace(old, new, 1)
open(p, 'w').write(s)

p = 'onnow-one/app/src/main/AndroidManifest.xml'; s = open(p).read()
old = """            android:launchMode="singleTask"
            android:icon="@mipmap/reborn_icon\""""
new = """            android:launchMode="singleTask"
            android:icon="@mipmap/reborn_icon\""""
assert old in s
# add intent filter for HOST_NAVIGATE inside the MainActivity element: find the first </activity> after android:name=".MainActivity"
i = s.index('android:name=".MainActivity"')
j = s.index('</activity>', i)
s = s[:j] + """    <intent-filter>
                <action android:name="tv.onnowtv.one.HOST_NAVIGATE" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
        """ + s[j:]
open(p, 'w').write(s)
print('ok')
