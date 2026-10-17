package tv.onnowtv.vespernative.playback

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import tv.onnowtv.vespernative.data.text
import tv.onnowtv.vespernative.playback.v2.*

class PlayerVisualState(payload:JSONObject?,fallback:String) {
    val loading=MutableStateFlow(true); val playing=MutableStateFlow(false)
    val position=MutableStateFlow(0L); val duration=MutableStateFlow(0L); val buffered=MutableStateFlow(0L)
    val percent=MutableStateFlow(0); val bitrate=MutableStateFlow(0L); val activity=MutableStateFlow(System.currentTimeMillis())
    val hide=MutableStateFlow(0L); val error=MutableStateFlow<String?>(null)
    val audio=MutableStateFlow<List<TrackOption>>(emptyList()); val subtitles=MutableStateFlow<List<TrackOption>>(emptyList())
    val streams=MutableStateFlow<List<StreamOption>>(emptyList()); val aspect=MutableStateFlow("fit")
    val logo=MutableStateFlow(payload?.text("logo").orEmpty())
    var controlsVisible=true
    val info=PlayerInfo(title=payload?.text("title").orEmpty().ifBlank { fallback },
        year=payload?.text("year").orEmpty(),poster=payload?.text("poster").orEmpty(),backdrop=payload?.text("backdrop").orEmpty(),
        rating=payload?.text("rating").orEmpty(),synopsis=payload?.text("overview").orEmpty(),runtime=payload?.text("runtime").orEmpty(),
        addonSource="",quality="",isEnglish=false,sizeGb=0f)
    val liveInfo=MutableStateFlow(info)
    fun touch() { activity.value=maxOf(System.currentTimeMillis(),activity.value+1) }
    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    fun view(c:Context,playPause:()->Unit,seek:(Long)->Unit,audioPick:(String)->Unit,subtitlePick:(String)->Unit,
        streamPick:(Int)->Unit,aspectPick:(String)->Unit,close:()->Unit):ComposeView=ComposeView(c).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent { MaterialTheme(colorScheme=darkColorScheme()) { Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId=true }) {
            val selectedAspect by aspect.collectAsState()
            PlayerOverlay(infoFlow=liveInfo,isLoading=loading,isPlaying=playing,positionMs=position,durationMs=duration,
                bufferAheadMs=buffered,audioTracks=audio,subtitleTracks=subtitles,streams=streams,errorMessage=error,
                currentEngineToken="exo",currentAspect=selectedAspect,bufferedPercent=percent,bitrateKbps=bitrate,
                userActivity=activity,hideControlsSignal=hide,logoUrl=logo,
                onControlsVisibilityChanged={ controlsVisible=it },onPlayPause=playPause,
                onSeekBy={ delta->seek((position.value+delta).coerceAtLeast(0)) },onSeekTo=seek,
                onPickAudio=audioPick,onPickSubtitle=subtitlePick,onPickStream=streamPick,
                onPickAspect=aspectPick,onClose=close)
        } } }
    }
}