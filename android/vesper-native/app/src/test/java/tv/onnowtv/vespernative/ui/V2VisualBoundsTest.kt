package tv.onnowtv.vespernative.ui

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tv.onnowtv.vespernative.R

@RunWith(RobolectricTestRunner::class)
class V2VisualBoundsTest {
    @Test @Config(sdk=[34],qualifiers="w960dp-h540dp-land-mdpi")
    fun login_fields_and_signin_fit_standard_frame()=checkLogin(960,540)
    @Test @Config(sdk=[34],qualifiers="w800dp-h450dp-land-mdpi")
    fun login_fields_and_signin_fit_compact_frame()=checkLogin(800,450)
    private fun checkLogin(width:Int,height:Int) {
        val screen=LoginScreen(RuntimeEnvironment.getApplication().applicationContext) { _,_->fail("Auto submit is forbidden") }
        for(error in listOf("","Unable to sign in. Check your username and password, then try again.")) {
            if(error.isNotEmpty())screen.failed(error)
            screen.root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY))
            screen.root.layout(0,0,width,height)
            for(tag in listOf("login-panel","login-username","login-password","login-submit")) {
                val view=find(screen.root,tag)!!; val rect=Rect(0,0,view.width,view.height)
                screen.root.offsetDescendantRectToMyCoords(view,rect)
                assertTrue("$tag exceeds viewport: $rect",rect.left>=0 && rect.right<=width && rect.top>=0 && rect.bottom<=height)
            }
        }
        screen.dispose()
    }
    private fun find(v:View,id:String):View? {
        if(v.getTag(R.id.test_id)==id)return v
        if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i),id)?.let { return it }
        return null
    }
}