package tv.onnowtv.vespernative.playback.v2

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import java.util.concurrent.atomic.AtomicLong

private val nextId = AtomicLong()
@Composable private fun tag(prefix:String,value:String=""):String {
    val id=remember { nextId.incrementAndGet() }
    return "player-$prefix-${value.lowercase().replace(Regex("[^a-z0-9]+"),"-").take(36)}-$id"
}
fun Modifier.nativeFocusTag():Modifier=composed { this.testTag(tag("control")) }
fun Modifier.nativeClickTag():Modifier=composed { this.testTag(tag("action")) }
fun Modifier.nativeGestureTag():Modifier=composed { this.testTag(tag("gesture")) }
@Composable fun TaggedText(text:String,modifier:Modifier=Modifier,color:Color=Color.Unspecified,
    fontSize:TextUnit=TextUnit.Unspecified,fontWeight:FontWeight?=null,fontFamily:FontFamily?=null,
    letterSpacing:TextUnit=TextUnit.Unspecified,lineHeight:TextUnit=TextUnit.Unspecified,
    textAlign:TextAlign?=null,maxLines:Int=Int.MAX_VALUE,overflow:TextOverflow=TextOverflow.Clip) {
    androidx.compose.material3.Text(text=text,modifier=modifier.testTag(tag("text",text)),color=color,fontSize=fontSize,
        fontWeight=fontWeight,fontFamily=fontFamily,letterSpacing=letterSpacing,lineHeight=lineHeight,
        textAlign=textAlign,maxLines=maxLines,overflow=overflow)
}
@Composable fun TaggedIcon(imageVector:ImageVector,contentDescription:String?,modifier:Modifier=Modifier,tint:Color=Color.Unspecified) {
    androidx.compose.material3.Icon(imageVector,contentDescription,modifier.testTag(tag("icon",contentDescription.orEmpty())),tint)
}
@Composable fun TaggedImage(model:Any?,contentDescription:String?,modifier:Modifier=Modifier,contentScale:ContentScale=ContentScale.Fit,alpha:Float=1f,alignment:androidx.compose.ui.Alignment=androidx.compose.ui.Alignment.Center) {
    coil.compose.AsyncImage(model,contentDescription,modifier.testTag(tag("art",contentDescription.orEmpty())),contentScale=contentScale,alpha=alpha,alignment=alignment)
}