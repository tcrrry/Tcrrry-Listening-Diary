package com.tcrrry.diary

import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.unit.*

/** A real transparent cutout, so the existing animated atmosphere shows through the gutter. */
internal data class ArtworkNotch(val x: Float, val y: Float, val radius: Float)

internal class NotchedArtworkShape(
    private val notches: List<ArtworkNotch>,
    private val gap: Dp = 4.dp,
    private val corner: Dp = 14.dp
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val round = RoundRect(Rect(Offset.Zero, size), CornerRadius(with(density) { corner.toPx() }))
        if (notches.isEmpty()) return Outline.Rounded(round)
        var path = Path().apply { addRoundRect(round) }
        val gutter = with(density) { gap.toPx() }
        notches.forEach { notch ->
            val radius = notch.radius * size.width + gutter
            val center = Offset(notch.x * size.width, notch.y * size.width)
            val hole = Path().apply { addOval(Rect(center - Offset(radius, radius), center + Offset(radius, radius))) }
            path = Path.combine(PathOperation.Difference, path, hole)
        }
        return Outline.Generic(path)
    }
}

internal data class YearArtSlot(val x: Float, val y: Float, val side: Float)
internal val YearSongSlots = listOf(
    YearArtSlot(0f, .02f, .42f), YearArtSlot(.44f, .005f, .21f),
    YearArtSlot(.015f, .46f, .34f), YearArtSlot(.66f, .48f, .34f),
    YearArtSlot(.56f, .84f, .16f)
)
internal val YearArtistSlots = listOf(
    YearArtSlot(.64f, 0f, .36f), YearArtSlot(.36f, .30f, .30f), YearArtSlot(.27f, .67f, .25f)
)

internal data class YearArtLayout(val songs:List<YearArtSlot>,val artists:List<YearArtSlot>)

/** Keep every cover inside its original safe region while varying size and location. */
internal fun randomizedYearArtLayout(seed:Int,songAvailable:Int=6,artistAvailable:Int=4):YearArtLayout {
    val random=kotlin.random.Random(seed)
    val mirror=random.nextBoolean()
    fun vary(slot:YearArtSlot):YearArtSlot {
        val size=slot.side*(.92f+random.nextFloat()*.08f);val spare=slot.side-size
        val x=slot.x+spare*random.nextFloat();val y=slot.y+spare*random.nextFloat()
        return YearArtSlot(if(mirror)1f-x-size else x,y,size)
    }
    // These freeform templates intentionally place portraits across cover edges;
    // square covers never intersect one another and the portraits cut real gutters.
    val firstSongs=listOf(
        YearArtSlot(.00f,.02f,.42f),YearArtSlot(.44f,.005f,.21f),
        YearArtSlot(.015f,.46f,.34f),YearArtSlot(.66f,.48f,.34f),
        YearArtSlot(.56f,.84f,.16f),YearArtSlot(.05f,.84f,.13f))
    val firstArtists=listOf(
        YearArtSlot(.55f,.03f,.34f),YearArtSlot(.30f,.28f,.30f),
        YearArtSlot(.22f,.67f,.26f),YearArtSlot(.50f,.62f,.23f))
    // Four corner covers come first so sparse data still has a balanced footprint.
    val secondSongs=listOf(
        YearArtSlot(.00f,.00f,.34f),YearArtSlot(.66f,.01f,.34f),
        YearArtSlot(.01f,.55f,.34f),YearArtSlot(.65f,.54f,.34f),
        YearArtSlot(.38f,.69f,.24f),YearArtSlot(.36f,.02f,.27f))
    val secondArtists=listOf(
        YearArtSlot(.20f,.22f,.32f),YearArtSlot(.60f,.25f,.31f),
        YearArtSlot(.40f,.50f,.27f))
    val (templateSongs,artists)=if(random.nextBoolean())firstSongs to firstArtists else secondSongs to secondArtists
    val songCount=minOf(songAvailable.coerceAtLeast(0),if(songAvailable>=6)random.nextInt(5,7)else 5)
    val artistCount=minOf(artistAvailable.coerceAtLeast(0),artists.size,if(artistAvailable>=4)random.nextInt(3,5)else 3)
    // A handful of records should use larger artwork instead of an empty six-slot mosaic.
    val songs=when(songCount){
        1->listOf(YearArtSlot(.17f,.18f,.66f))
        2->listOf(YearArtSlot(.02f,.06f,.45f),YearArtSlot(.53f,.50f,.45f))
        3->listOf(YearArtSlot(.02f,.03f,.45f),YearArtSlot(.53f,.03f,.45f),YearArtSlot(.27f,.53f,.45f))
        else->templateSongs.take(songCount)
    }
    val placedSongs=songs.map(::vary)
    val placedArtists=artists.take(artistCount).map(::vary)
    return YearArtLayout(placedSongs,if(songCount in 1..3)fitSparsePortraits(placedSongs,placedArtists)else placedArtists)
}

/** Place sparse portraits near cover edges while respecting the same overlap limit. */
private fun fitSparsePortraits(songs:List<YearArtSlot>,wanted:List<YearArtSlot>):List<YearArtSlot>{
    val placed=mutableListOf<YearArtSlot>();val occupied=DoubleArray(songs.size)
    fun intersection(song:YearArtSlot,artist:YearArtSlot):Double {
        val r=artist.side/2.0;val cx=artist.x+r;val cy=artist.y+r;val step=song.side/32.0
        var result=0.0
        repeat(32){i->val dx=song.x+(i+.5)*step-cx
            if(kotlin.math.abs(dx)<r){val half=kotlin.math.sqrt(r*r-dx*dx);result+=kotlin.math.max(0.0,kotlin.math.min(song.y+song.side.toDouble(),cy+half)-kotlin.math.max(song.y.toDouble(),cy-half))*step}
        }
        return result
    }
    for(target in wanted){
        var best:YearArtSlot?=null;var bestAreas=DoubleArray(songs.size);var bestScore=Double.POSITIVE_INFINITY
        val size=target.side.coerceAtMost(.28f);val maxX=1f-size;val maxY=.98f-size
        for(ix in 0..16)for(iy in 0..16){
            val candidate=YearArtSlot(maxX*ix/16f,maxY*iy/16f,size)
            val cx=candidate.x+size/2;val cy=candidate.y+size/2
            if(placed.any{p->val dx=cx-(p.x+p.side/2);val dy=cy-(p.y+p.side/2);val limit=(size+p.side)/2+.01f;dx*dx+dy*dy<limit*limit})continue
            val areas=DoubleArray(songs.size){intersection(songs[it],candidate)}
            val total=areas.sum();val circle=Math.PI*size*size/4
            if(total>circle*.35||songs.indices.any{i->occupied[i]+areas[i]>songs[i].side*songs[i].side*.35})continue
            val dx=(candidate.x-target.x).toDouble();val dy=(candidate.y-target.y).toDouble()
            val score=dx*dx+dy*dy+if(total<circle*.025).12 else 0.0
            if(score<bestScore){best=candidate;bestScore=score;bestAreas=areas}
        }
        best?.let{placed+=it;for(i in songs.indices)occupied[i]+=bestAreas[i]}
    }
    return placed
}

/** Reserve space beside any portrait cutting into the lower caption area. */
internal fun artworkCaptionInsets(slot:YearArtSlot,artists:List<YearArtSlot>,gap:Float):Pair<Float,Float>{
    var start=0f;var end=0f
    for(artist in artists){
        val cx=artist.x+artist.side/2;val cy=artist.y+artist.side/2;val radius=artist.side/2+gap
        for(level in listOf(.80f,.90f,.98f)){
            val dy=kotlin.math.abs(slot.y+slot.side*level-cy)
            if(dy>=radius)continue
            val dx=kotlin.math.sqrt(radius*radius-dy*dy)
            val left=cx-dx;val right=cx+dx
            if(right<=slot.x||left>=slot.x+slot.side)continue
            if(cx<slot.x+slot.side/2)start=maxOf(start,(right-slot.x).coerceIn(0f,slot.side*.45f))
            else end=maxOf(end,(slot.x+slot.side-left).coerceIn(0f,slot.side*.45f))
        }
    }
    return start to end
}

internal fun artworkNotches(slot: YearArtSlot, artists: List<YearArtSlot>) = artists.map { artist ->
    ArtworkNotch((artist.x + artist.side / 2 - slot.x) / slot.side,
        (artist.y + artist.side / 2 - slot.y) / slot.side, artist.side / 2 / slot.side)
}
