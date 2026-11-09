package com.tcrrry.diary

import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.unit.*
import kotlin.math.*

/** Mirror the full square cover (including its rounded mask and Fit letterbox), then crop the short reflection. */
internal fun DrawScope.drawCoverReflection(image:ImageBitmap,background:Brush,radius:Float,gap:Float,strength:Float,shade:Float){
    val edge=size.width
    if(edge<=0f||size.height<=gap)return
    val mask=Path().apply{addRoundRect(RoundRect(Rect(0f,0f,edge,edge),CornerRadius(radius.coerceIn(0f,edge/2))))}
    val factor=min(edge/image.width,edge/image.height)
    val width=(image.width*factor).roundToInt().coerceAtLeast(1);val height=(image.height*factor).roundToInt().coerceAtLeast(1)
    clipRect{
        translate(top=edge+gap){scale(scaleX=1f,scaleY=-1f,pivot=Offset.Zero){clipPath(mask){
            // Resolve the background brush against the full square, not the 20dp viewport.
            val previousSize=drawContext.size
            drawContext.size=Size(edge,edge)
            try{
            // The gallery uses ContentScale.Fit, not a square stretch or center crop.
            drawRect(background,size=Size(edge,edge))
            drawImage(image,dstOffset=IntOffset(((edge-width)/2).roundToInt(),((edge-height)/2).roundToInt()),dstSize=IntSize(width,height))
            if(shade>0f)drawRect(Color.Black.copy(alpha=shade.coerceIn(0f,1f)),size=Size(edge,edge))
            }finally{drawContext.size=previousSize}
        }}}
    }
    // Fade only the isolated reflection layer, never the ambient page beneath it.
    drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha=strength),.30f to Color.White.copy(alpha=strength*.38f),.72f to Color.White.copy(alpha=strength*.06f),1f to Color.Transparent,startY=gap,endY=size.height),blendMode=BlendMode.DstIn)
}
