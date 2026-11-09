package com.tcrrry.diary

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer

/** Preserve the top of the artwork and let its lower edge disappear into the actual background. */
internal fun playbackFadeBottomAlpha(opacity:Float):Float =
    (0.08f + 0.92f * ((opacity - 0.4f) / 0.6f).coerceIn(0f, 1f))

internal fun Modifier.playbackFade(opacity:Float):Modifier {
    if(opacity >= .999f)return this
    val bottom=playbackFadeBottomAlpha(opacity)
    return graphicsLayer { compositingStrategy=CompositingStrategy.Offscreen }.drawWithContent {
        drawContent()
        drawRect(
            Brush.verticalGradient(
                0f to Color.White,
                .16f to Color.White,
                .64f to Color.White.copy(alpha=(1f+bottom)*.5f),
                1f to Color.White.copy(alpha=bottom),
                startY=0f,endY=size.height
            ),
            blendMode=BlendMode.DstIn
        )
    }
}
