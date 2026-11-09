package com.tcrrry.diary
import kotlin.math.abs
import androidx.compose.ui.semantics.SemanticsPropertyKey

internal data class CoverStop(val songs:List<Play>) {
    val first get()=songs.minBy{it.start}
    val play get()=songs.maxBy{it.start}
    val key get()="${first.visit}:${first.start}"
    val start get()=songs.minOf{it.start}
    val end get()=songs.maxOf{it.end}
    val ms get()=songs.sumOf{it.ms}
    val count get()=songs.size
}
internal val PlaybackOpacityKey=SemanticsPropertyKey<Float>("PlaybackOpacity")
internal val CoverRepeatCountKey=SemanticsPropertyKey<Int>("CoverRepeatCount")

/** Unknown duration is not evidence of a skip. Repeat groups use the average completion. */
internal fun playbackOpacity(songs:List<Play>,isPlaying:Boolean=false):Float {
    if(isPlaying)return 1f
    val known=songs.filter{it.duration>0}
    if(known.isEmpty())return 1f
    val completion=known.map{(it.ms.toDouble()/it.duration).coerceIn(0.0,1.0)}.average()
    return (0.40+0.60*(completion/.9).coerceIn(0.0,1.0)).toFloat()
}

/** Consecutive repeats share a stop; sentinels keep the last → first step one page wide. */
internal object CoverCarousel {
    const val HOLD_MS=1700L
    const val TOUCH_REST_MS=6500L
    const val GUARD_PAGES=3
    fun history(plays:List<Play>)=plays.sortedByDescending{it.start}
    fun stops(plays:List<Play>,segments:List<Play> = plays):List<CoverStop>{
        val identities=plays.map{it.visit to it.id}.toSet()
        val spans=segments.filter{(it.visit to it.id) in identities}.ifEmpty{plays}
        return ListeningSessions.group(spans).flatMap{session->repeatPlays(session.songs).map{CoverStop(it.songs.sortedByDescending{p->p.start})}}.sortedByDescending{it.play.start}
    }
    fun stopKey(page:Int,stops:List<CoverStop>):String {
        val key=stops[sourceIndex(page,stops.size)].key
        return when{stops.size<=1->key;page<GUARD_PAGES->"before:$page:$key";page>=stops.size+GUARD_PAGES->"after:$page:$key";else->key}
    }
    fun pageCount(count:Int)=if(count>1)count+2*GUARD_PAGES else count
    fun firstPage(count:Int)=if(count>1)GUARD_PAGES else 0
    fun sourceIndex(page:Int,count:Int)=if(count<=1)0 else (page-GUARD_PAGES).mod(count)
    fun normalize(page:Int,count:Int)=if(count<=1)0 else GUARD_PAGES+sourceIndex(page,count)
    // Never clamp the depth: clamping gave the second and third cards the same draw order.
    fun depth(offset:Float)=100f-abs(offset)
    fun scale(offset:Float)=(1f-abs(offset)*.18f).coerceIn(.52f,1f)
    const val CAMERA_WIDTHS=24f
    fun key(page:Int,plays:List<Play>):String {
        val visit=plays[sourceIndex(page,plays.size)].visit
        return when{plays.size<=1->visit;page<GUARD_PAGES->"before:$page:$visit";page>=plays.size+GUARD_PAGES->"after:$page:$visit";else->visit}
    }
}
