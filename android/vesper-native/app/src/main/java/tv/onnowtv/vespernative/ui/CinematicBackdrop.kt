package tv.onnowtv.vespernative.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView

class CinematicBackdrop(c:Context,id:String):FrameLayout(c) {
    val image=TvUi.identify(ImageView(c),id).apply { scaleType=ImageView.ScaleType.CENTER_CROP }
    init {
        isFocusable=false;addView(image,LayoutParams(-1,-1))
        addView(View(c).apply { background=GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,intArrayOf(Color.argb(220,3,6,15),Color.argb(120,3,6,15),Color.argb(22,3,6,15))) },LayoutParams(-1,-1))
        addView(View(c).apply { background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Color.argb(28,3,6,15),Color.argb(24,3,6,15),Color.argb(242,3,6,15))) },LayoutParams(-1,-1))
    }
}