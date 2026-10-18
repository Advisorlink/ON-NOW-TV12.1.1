package tv.onnowtv.vespernative.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.FrameLayout
import tv.onnowtv.vespernative.data.Title
import tv.onnowtv.vespernative.playback.TrailerPreview

object TrailerDialog {
    fun show(c:Context,title:Title,preview:TrailerPreview):Dialog {
        val dialog=Dialog(c,android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root=TvUi.identify(FrameLayout(c),"detail-trailer-dialog").apply { setBackgroundColor(Color.BLACK) }
        val status=TvUi.text(c,"detail-trailer-status","Loading trailer…",16f).apply { gravity=Gravity.CENTER }
        root.addView(status,FrameLayout.LayoutParams(-1,-1))
        dialog.setContentView(root); dialog.setOnDismissListener { preview.stop() }
        dialog.show(); dialog.window?.setLayout(-1,-1)
        preview.focus(title,root,true) { status.text=it }
        return dialog
    }
}