package com.tcrrry.diary
import android.graphics.Bitmap
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class) @Config(sdk=[35]) @GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverReflectionTest {
    private fun render(w:Int=164,h:Int=164):Bitmap{
        val source=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        source.eraseColor(android.graphics.Color.RED)
        for(x in 0 until w)for(y in h/2 until h)source.setPixel(x,y,android.graphics.Color.BLUE)
        val output=Bitmap.createBitmap(164,24,Bitmap.Config.ARGB_8888)
        CanvasDrawScope().draw(Density(1f),LayoutDirection.Ltr,Canvas(output.asImageBitmap()),Size(164f,24f)){
            drawCoverReflection(source.asImageBitmap(),Brush.linearGradient(listOf(Color.Green,Color.Green)),17f,1f,1f,0f)
        }
        source.recycle();return output
    }
    @Test fun reflectionMirrorsBottomEdgeAndHasRoundedContactCorners(){val b=render();val c=b.getPixel(82,2);assertTrue(android.graphics.Color.blue(c)>240);assertTrue(android.graphics.Color.red(c)<10);assertEquals(0,android.graphics.Color.alpha(b.getPixel(1,2)));assertTrue(android.graphics.Color.alpha(b.getPixel(16,2))>0);b.recycle()}
    @Test fun contactGapIsTransparentAndFadeHasNoHardBottomEdge(){val b=render();assertEquals(0,android.graphics.Color.alpha(b.getPixel(82,0)));val near=android.graphics.Color.alpha(b.getPixel(82,2));val middle=android.graphics.Color.alpha(b.getPixel(82,12));val end=android.graphics.Color.alpha(b.getPixel(82,23));assertTrue(near>middle);assertTrue(middle>end);assertTrue(end<=2);b.recycle()}
    @Test fun wideArtworkPreservesTheSameLetterboxAsTheCover(){val b=render(200,100);val c=b.getPixel(82,2);assertTrue(android.graphics.Color.green(c)>240);assertTrue(android.graphics.Color.blue(c)<10);b.recycle()}
}
