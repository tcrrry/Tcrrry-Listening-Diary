package com.tcrrry.diary
import android.graphics.Bitmap
import android.graphics.Color
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35]) @GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverColorsTest {
    @Test fun greyCoverNeverCreatesABrightGreyWash(){val b=Bitmap.createBitmap(80,80,Bitmap.Config.ARGB_8888);b.eraseColor(Color.WHITE);coverColors(b).forEach{val hsv=FloatArray(3);Color.colorToHSV(it,hsv);assertTrue(hsv[2]<=.39f);assertTrue(hsv[1]<.12f)};b.recycle()}
    @Test fun vividCoverColorWinsOverMutedBackground(){val b=Bitmap.createBitmap(80,80,Bitmap.Config.ARGB_8888);b.eraseColor(Color.rgb(32,30,25));for(x in 0..23)for(y in 0..79)b.setPixel(x,y,Color.rgb(10,120,245));val color=coverColors(b)[0];assertTrue(Color.blue(color)>Color.red(color));b.recycle()}
    @Test fun dominantCoverHueIsPreserved(){val b=Bitmap.createBitmap(80,80,Bitmap.Config.ARGB_8888);b.eraseColor(Color.rgb(200,35,55));val colors=coverColors(b);assertTrue(Color.red(colors[0])>Color.green(colors[0]));assertTrue(Color.red(colors[0])>Color.blue(colors[0]));b.recycle()}
}
