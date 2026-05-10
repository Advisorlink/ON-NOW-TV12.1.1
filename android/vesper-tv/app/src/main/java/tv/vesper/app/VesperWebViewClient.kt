package tv.vesper.app

import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/** Pin navigation to the Vesper origin and let the WebView handle the rest. */
class VesperWebViewClient : WebViewClient() {

    override fun shouldOverrideUrlLoading(
        view: WebView?,
        request: WebResourceRequest?
    ): Boolean {
        val target = request?.url?.host ?: return false
        val current = view?.url
            ?.let { android.net.Uri.parse(it).host }
            ?: return false
        // If the requested host differs from the loaded host, block it
        // (defence-in-depth — Vesper itself doesn't link off-origin).
        return target != current
    }
}
