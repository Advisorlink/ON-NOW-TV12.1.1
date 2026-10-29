package tv.onnowtv.livetv.ui

import android.view.View
import android.view.ViewGroup
import androidx.core.view.doOnLayout

/** Grows the guide's video view in place until it covers [root], and shrinks it back.
 *  The view never leaves its parent, so its surface (and the stream) is never touched. */
class PreviewGrow(private val video: View, private val root: ViewGroup) {
    private class Saved(val group: ViewGroup, val clipChildren: Boolean, val clipToPadding: Boolean, val z: Float)
    private val saved = mutableListOf<Saved>()
    private var videoZ = 0f

    var grown = false
        private set

    fun grow() {
        if (grown) return
        grown = true
        var g = video.parent as? ViewGroup
        while (g != null) {
            saved += Saved(g, g.clipChildren, g.clipToPadding, g.translationZ)
            g.clipChildren = false
            g.clipToPadding = false
            if (g === root) break
            g.translationZ = LIFT
            g = g.parent as? ViewGroup
        }
        videoZ = video.translationZ
        video.translationZ = LIFT
        video.doOnLayout { if (grown) fit() }
    }

    private fun fit() {
        video.translationX = 0f
        video.translationY = 0f
        var dx = 0; var dy = 0
        var v: View = video
        while (v !== root) {
            dx += v.left - (v.parent as View).scrollX
            dy += v.top - (v.parent as View).scrollY
            v = v.parent as? View ?: break
        }
        video.layoutParams = video.layoutParams.apply { width = root.width; height = root.height }
        video.translationX = -dx.toFloat()
        video.translationY = -dy.toFloat()
    }

    fun shrink() {
        if (!grown) return
        grown = false
        saved.forEach {
            it.group.clipChildren = it.clipChildren
            it.group.clipToPadding = it.clipToPadding
            it.group.translationZ = it.z
        }
        saved.clear()
        video.translationZ = videoZ
        video.translationX = 0f
        video.translationY = 0f
        video.layoutParams = video.layoutParams.apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
    }

    private companion object { const val LIFT = 60f }
}
