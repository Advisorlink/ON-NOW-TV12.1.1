package tv.onnowtv.vespernative.ui

import android.content.Context
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import tv.onnowtv.vespernative.data.*

/** V2 CastRow's two levels, including filmography drill-in and actor restoration. */
class DetailLane(c:Context,private val scope:CoroutineScope,private val repo:TitleExtrasRepository,
    private val actorFocus:(CastMember)->Unit,private val titleFocus:(Title)->Unit,
    private val open:(Title)->Unit,private val up:()->Unit):FrameLayout(c) {
    private var mode="cast";private var actor:CastMember?=null;private var similarRequest:Deferred<List<Title>>?=null;private var request:Job?=null
    private var parentTitle:Title?=null
    private val heading=TvUi.heading(c,"detail-lane-heading","",17f)
    private val status=StatusBand(c,"detail-lane-status")
    private val cast=CastLane(c,::films,actorFocus) { code->
        when(code) { KeyEvent.KEYCODE_DPAD_DOWN->{ showSimilar();true };KeyEvent.KEYCODE_DPAD_UP->{up();true};else->false }
    }
    private val titles=TvUi.identify(RecyclerView(c),"detail-lane-titles").apply {
        layoutManager=LinearLayoutManager(c,RecyclerView.HORIZONTAL,false);itemAnimator=null
        FocusMotion.prepareRail(this);setPadding(TvUi.dp(c,34),TvUi.dp(c,18),TvUi.dp(c,34),TvUi.dp(c,12))
    }
    private val panel=TvUi.column(c).apply {
        addView(heading,LinearLayout.LayoutParams(-1,TvUi.dp(c,20)).apply { leftMargin=TvUi.dp(c,34) })
        addView(status);addView(titles,LinearLayout.LayoutParams(-1,TvUi.dp(c,140)));visibility=View.GONE
    }
    init {
        TvUi.identify(this,"detail-cast-lane");TvUi.identify(cast,"detail-cast-content");addView(cast,LayoutParams(-1,-1));addView(panel,LayoutParams(-1,-1))
        val switch=TvUi.row(c)
        switch.addView(V2Ui.pill(c,"detail-show-cast","CAST ↑"){showCast()},LinearLayout.LayoutParams(-2,TvUi.dp(c,20)))
        switch.addView(V2Ui.pill(c,"detail-show-similar","JUST LIKE THIS ↓"){showSimilar()},LinearLayout.LayoutParams(-2,TvUi.dp(c,20)).apply{marginStart=TvUi.dp(c,6)})
        addView(switch,LayoutParams(-2,TvUi.dp(c,20),android.view.Gravity.TOP or android.view.Gravity.END).apply{rightMargin=TvUi.dp(c,34)})
    }
    fun load(title:Title) {
        parentTitle=title
        scope.launch { try { cast.submit(repo.cast(title)) }catch(e:CancellationException){throw e}catch(_:Exception){status.show("Cast unavailable.",true);status.onRetry={load(title)}} }
        similarRequest=scope.async { repo.similar(title) }
    }
    fun focusFirst() { if(mode=="cast")cast.focusActor() else titles.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
    fun back():Boolean { if(mode=="cast")return false;showCast();return true }
    private fun showCast() { request?.cancel();mode="cast";panel.visibility=View.GONE;cast.visibility=View.VISIBLE;cast.focusActor(actor?.id) }
    private fun films(person:CastMember) {
        actor=person;mode="filmography";heading.text="${person.name} · Films & shows";showPanel()
        request?.cancel();request=scope.launch {
            status.show("Loading filmography…")
            try { display(repo.films(person.id)) }catch(e:CancellationException){throw e}catch(_:Exception){status.show("Couldn't load filmography.",true);status.onRetry={films(person)}}
        }
    }
    private fun showSimilar() {
        request?.cancel();mode="similar";heading.text="Just like this";showPanel();status.show("Loading similar titles…")
        request=scope.launch { try{display(similarRequest?.await().orEmpty())}catch(e:CancellationException){throw e}catch(_:Exception){status.show("Couldn't load similar titles.",true);status.onRetry={parentTitle?.let{title->similarRequest=scope.async{repo.similar(title)}};showSimilar()}} }
    }
    private fun showPanel(){ cast.visibility=View.GONE;panel.visibility=View.VISIBLE;titles.adapter=null }
    private fun display(items:List<Title>) {
        if(items.isEmpty()) { status.show("No titles available.");status.isFocusable=true;status.requestFocus()
            status.setOnKeyListener { _,key,event->if(event.action==KeyEvent.ACTION_DOWN && key==KeyEvent.KEYCODE_DPAD_UP){showCast();true}else false };return }
        status.clear()
        titles.adapter=TitleAdapter("cast-$mode",110,open,titleFocus,keyAction={key ->when(key) {
            KeyEvent.KEYCODE_DPAD_UP->{showCast();true}
            KeyEvent.KEYCODE_DPAD_DOWN->{if(mode=="filmography")showSimilar();true}
            else->false
        }}).apply { replace(items) }
        titles.scrollToPosition(0);titles.post { titles.findViewHolderForAdapterPosition(0)?.itemView?.requestFocus() }
    }
}