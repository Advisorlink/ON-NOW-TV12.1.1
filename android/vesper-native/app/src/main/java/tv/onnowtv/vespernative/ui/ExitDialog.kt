package tv.onnowtv.vespernative.ui

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import tv.onnowtv.vespernative.R

object ExitDialog {
    fun show(activity:AppCompatActivity) {
        val view=activity.layoutInflater.inflate(R.layout.dialog_exit_confirm,null)
        val dialog=AlertDialog.Builder(activity,androidx.appcompat.R.style.Theme_AppCompat_Dialog).setView(view).setCancelable(true).create()
        val stay=view.findViewById<Button>(R.id.exit_btn_cancel); val close=view.findViewById<Button>(R.id.exit_btn_close)
        TvUi.identify(stay,"exit-cancel"); TvUi.identify(close,"exit-confirm")
        stay.setOnClickListener { dialog.dismiss() }; close.setOnClickListener { dialog.dismiss(); activity.finish() }
        var label=0
        fun identifyLabels(v:View) {
            if(v is android.widget.TextView && v !is Button)TvUi.identify(v,"exit-label-${label++}")
            if(v is ViewGroup)for(i in 0 until v.childCount)identifyLabels(v.getChildAt(i))
        }
        identifyLabels(view)
        dialog.setOnShowListener {
            TvUi.identify(dialog.window!!.decorView,"exit-dialog")
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT)
            stay.requestFocus()
        }; dialog.show()
    }
}