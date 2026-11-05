import os, re
os.chdir('/app/android/onnow-one')

# 1. source dir for native Reborn code
p = 'app/build.gradle.kts'; s = open(p).read()
s = s.replace('java.setSrcDirs(listOf("../../vesper-tv/app/src/main/java"))', 'java.setSrcDirs(listOf("../../vesper-tv/app/src/main/java", "src/main/java"))', 1)
if 'kotlinx-coroutines-android' not in s:
    s = s.replace('    implementation("io.coil-kt:coil-compose:2.6.0")', '    implementation("io.coil-kt:coil-compose:2.6.0")\n    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")', 1)
open(p, 'w').write(s)

# 2. API fixes: bare-array addons list; openSettings helper
p = 'app/src/main/java/tv/rebornplay/RebornApi.kt'; s = open(p).read()
old = s[s.index('    fun catalogs(token: String): List<Catalog> {'):s.index('        val out = ArrayList<Catalog>()')]
new = '''    fun catalogs(token: String): List<Catalog> {
        val raw = getRaw("/api/addons", token).trim()
        val list = if (raw.startsWith("[")) JSONArray(raw) else JSONObject(raw).optJSONArray("addons") ?: JSONArray()
'''
s = s.replace(old, new, 1)
s = s.replace('''    private fun get(path: String, token: String?): JSONObject {''', '''    private fun getRaw(path: String, token: String?): String {
        val b = Request.Builder().url(BASE + path).header("User-Agent", "RebornPlay/1.0 Android")
        if (!token.isNullOrBlank()) b.header("Authorization", "Bearer $token")
        http.newCall(b.build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}: ${body.take(160)}")
            return body
        }
    }

    private fun get(path: String, token: String?): JSONObject {''', 1)
open(p, 'w').write(s)

p = 'app/src/main/java/tv/rebornplay/RebornActivity.kt'; s = open(p).read()
s = s.replace('LiveTvEntry.openSettings(activity)', 'LiveTvEntry.open(activity, settings = true)')
open(p, 'w').write(s)

# 3. Manifest: RebornActivity becomes the launcher + HOST_NAVIGATE target; WebView MainActivity stays non-launcher
p = 'app/src/main/AndroidManifest.xml'; s = open(p).read()
i = s.index('android:name=".MainActivity"'); j = s.index('</activity>', i)
block = s[i:j]
# strip launcher + host-navigate filters out of MainActivity
block2 = re.sub(r'\s*<intent-filter>\s*<action android:name="android.intent.action.MAIN" />.*?</intent-filter>', '', block, flags=re.S)
block2 = re.sub(r'\s*<intent-filter>\s*<action android:name="tv.onnowtv.one.HOST_NAVIGATE" />.*?</intent-filter>', '', block2, flags=re.S)
s = s[:i] + block2 + s[j:]
reborn = '''        <activity
            android:name="tv.rebornplay.RebornActivity"
            android:exported="true"
            android:launchMode="singleTask"
            android:icon="@mipmap/reborn_icon"
            android:roundIcon="@mipmap/reborn_icon_round"
            android:label="Reborn Play"
            android:banner="@drawable/reborn_banner"
            android:screenOrientation="sensorLandscape"
            android:configChanges="orientation|keyboardHidden|keyboard|screenSize|screenLayout|smallestScreenSize|navigation|uiMode|locale|fontScale|density"
            android:theme="@style/Theme.OnNowTV">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
                <category android:name="android.intent.category.LEANBACK_LAUNCHER" />
            </intent-filter>
            <intent-filter>
                <action android:name="tv.onnowtv.one.HOST_NAVIGATE" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
        </activity>
'''
k = s.index('<activity', s.index('<application'))
s = s[:k] + reborn.lstrip() + '        ' + s[k:]
open(p, 'w').write(s)
print('ok', 'LAUNCHER' in block, 'MainActivity launcher removed:', 'LAUNCHER' not in block2)
