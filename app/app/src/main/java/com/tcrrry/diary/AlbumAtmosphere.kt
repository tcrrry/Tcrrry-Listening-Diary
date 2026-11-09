package com.tcrrry.diary

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.lerp

internal data class AlbumAtmosphere(val top:State<Color>,val paper:State<Color>)
internal fun albumPaper(color:Color,soft:Boolean):Color{
    val hsv=FloatArray(3);android.graphics.Color.colorToHSV(color.toArgb(),hsv)
    // Convert the destination once; never normalize saturation/value on animated frames.
    hsv[1]=if(hsv[1]<.12f)if(soft).015f else .035f else if(soft).055f else .20f
    hsv[2]=if(soft).995f else .97f
    return Color(android.graphics.Color.HSVToColor(hsv))
}
internal fun darkAlbumColor(primary:Color,soft:Boolean):Color{
    val hsv=FloatArray(3);android.graphics.Color.colorToHSV(primary.toArgb(),hsv)
    hsv[1]=if(hsv[1]<.12f).06f else hsv[1].coerceIn(.35f,.72f);hsv[2]=if(soft).24f else .40f
    return Color(android.graphics.Color.HSVToColor(hsv))
}
@Composable internal fun rememberAlbumAtmosphere(primary:Color,dark:Boolean,base:Color):AlbumAtmosphere{
    val top=animateColorAsState(if(dark)darkAlbumColor(primary,false)else albumPaper(primary,false),tween(1100,easing=LinearOutSlowInEasing),label="album top gradient")
    val paper=animateColorAsState(if(dark)darkAlbumColor(primary,true)else albumPaper(primary,true),tween(1100,easing=LinearOutSlowInEasing),label="album paper gradient")
    return remember(top,paper){AlbumAtmosphere(top,paper)}
}
