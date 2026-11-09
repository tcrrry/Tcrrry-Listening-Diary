package com.tcrrry.diary
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],qualifiers="w412dp-h915dp-xxhdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
class AlbumAtmosphereTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun actualPaintedBackgroundPassesThroughMultipleColorsInsteadOfJumping(){
        val source=mutableStateOf(Color.Blue)
        ui.runOnUiThread{ui.activity.controller.network(false);ui.activity.controller.stop();ui.activity.setContent{
            val atmosphere=rememberAlbumAtmosphere(source.value,false,Color.White)
            Canvas(Modifier.fillMaxSize()){drawRect(Brush.verticalGradient(listOf(atmosphere.top.value,atmosphere.paper.value),endY=size.height*.48f))}
        }}
        ui.waitForIdle();ui.mainClock.autoAdvance=false
        fun pixel():Int{var result=0;ui.runOnUiThread{val view=ui.activity.window.decorView;val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888);view.draw(android.graphics.Canvas(bitmap));result=bitmap.getPixel(12,12);bitmap.recycle()};return result}
        val blue=pixel();ui.runOnUiThread{source.value=Color.Red};ui.mainClock.advanceTimeByFrame();val first=pixel()
        ui.mainClock.advanceTimeBy(260);val middle=pixel();ui.mainClock.advanceTimeBy(300);val later=pixel();ui.mainClock.advanceTimeBy(900);val red=pixel()
        assertNotEquals(blue,red);assertNotEquals(middle,red);assertNotEquals(middle,blue);assertNotEquals(middle,later)
        assertTrue(kotlin.math.abs(android.graphics.Color.red(first)-android.graphics.Color.red(blue))<8)
        assertTrue(android.graphics.Color.red(middle)<android.graphics.Color.red(red));ui.mainClock.autoAdvance=true
    }
}
