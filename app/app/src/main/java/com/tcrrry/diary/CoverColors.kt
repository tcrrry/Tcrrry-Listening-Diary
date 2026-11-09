package com.tcrrry.diary
import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.*

/** Small quantized histogram: avoid a white/grey cover wash drowning out the dark dashboard. */
internal fun coverColors(bitmap:Bitmap):IntArray {
    val histogram=HashMap<Int,Int>()
    for(x in 1..24)for(y in 1..24){val color=bitmap.getPixel(bitmap.width*x/25,bitmap.height*y/25);val r=Color.red(color);val g=Color.green(color);val b=Color.blue(color);val key=((r/24) shl 8)or((g/24)shl 4)or(b/24);histogram[key]=(histogram[key]?:0)+1}
    fun rgb(key:Int)=Color.rgb(((key shr 8)and 15)*24+11,((key shr 4)and 15)*24+11,(key and 15)*24+11)
    val sorted=histogram.entries.sortedByDescending{entry->val hsv=FloatArray(3);Color.colorToHSV(rgb(entry.key),hsv);entry.value.toDouble().pow(.55)*(.01+hsv[1]*hsv[1])*(.1+hsv[2]*hsv[2])*(if(hsv[2]<.12f).08 else 1.0)}
    val first=sorted.firstOrNull()?.let{rgb(it.key)}?:Color.rgb(68,54,94)
    val hsv=FloatArray(3);Color.colorToHSV(first,hsv)
    val second=sorted.firstOrNull{entry->val other=FloatArray(3);Color.colorToHSV(rgb(entry.key),other);min(abs(other[0]-hsv[0]),360-abs(other[0]-hsv[0]))>35}?.let{rgb(it.key)}?:first
    fun dark(color:Int):Int {val value=FloatArray(3);Color.colorToHSV(color,value);value[1]=if(value[1]<.12f).06f else value[1].coerceIn(.25f,.68f);value[2]=value[2].coerceIn(.2f,.38f);return Color.HSVToColor(value)}
    return intArrayOf(dark(first),dark(second))
}
