package com.tcrrry.diary

import android.graphics.*
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.unit.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class) @Config(sdk=[35]) @GraphicsMode(GraphicsMode.Mode.NATIVE)
class NotchedArtworkShapeTest {
    @Test fun circularGutterExposesUnderlyingBackgroundRatherThanPaintingAFlatBorder() {
        val shape=NotchedArtworkShape(listOf(ArtworkNotch(.9f,.9f,.25f)),gap=6.dp)
        val outline=shape.createOutline(Size(200f,200f),LayoutDirection.Ltr,Density(1f)) as Outline.Generic
        val bitmap=Bitmap.createBitmap(200,200,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);canvas.drawColor(Color.BLUE)
        canvas.save();canvas.clipPath(outline.path.asAndroidPath());canvas.drawColor(Color.RED);canvas.restore()
        canvas.drawCircle(180f,180f,50f,Paint().apply{color=Color.GREEN})
        assertEquals(Color.BLUE,bitmap.getPixel(142,142))
        assertEquals(Color.GREEN,bitmap.getPixel(146,146))
        assertEquals(Color.RED,bitmap.getPixel(136,140))
        assertEquals(Color.RED,bitmap.getPixel(100,100))
    }
    @Test fun gutterAndRoundingScaleWithDensityAndKeepUnaffectedCoverRegions() {
        val outline=NotchedArtworkShape(listOf(ArtworkNotch(.9f,.9f,.25f)),gap=6.dp)
            .createOutline(Size(400f,400f),LayoutDirection.Ltr,Density(2f)) as Outline.Generic
        val region=Region().apply{setPath(outline.path.asAndroidPath(),Region(0,0,400,400))}
        assertFalse(region.contains(284,284));assertTrue(region.contains(272,280));assertTrue(region.contains(200,200));assertFalse(region.contains(0,0))
    }
    @Test fun squareCoverSlotsRemainSeparateAndTheirPortraitCutoutsShareTheSameCoordinates() {
        YearSongSlots.forEachIndexed { i,a -> YearSongSlots.drop(i+1).forEach { b ->
            assertFalse(a.x < b.x+b.side && a.x+a.side > b.x && a.y < b.y+b.side && a.y+a.side > b.y)
        } }
        val cover=YearSongSlots[2];val artist=YearArtistSlots[2]
        val notch=artworkNotches(cover,YearArtistSlots)[2]
        assertEquals(artist.x+artist.side/2,cover.x+notch.x*cover.side,.0001f)
        assertEquals(artist.y+artist.side/2,cover.y+notch.y*cover.side,.0001f)
        assertEquals(artist.side/2,notch.radius*cover.side,.0001f)
    }
}
