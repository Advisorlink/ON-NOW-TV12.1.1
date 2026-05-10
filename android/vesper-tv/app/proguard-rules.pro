# Keep WebView JavaScript-bridged classes if any are added later.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
