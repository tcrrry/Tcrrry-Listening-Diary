package com.tcrrry.diary

import org.junit.Assert.*
import org.junit.Test

class PeriodMotionSlotTest {
    @Test fun layoutShuffleOnlyReordersVisibleItemsAndIsStable(){
        val items=(0..9).toList()
        val seen=(0 until 100).map{seed->
            val layout=periodLayoutItems(items,5,periodLayoutSeed(seed,"2026-10"))
            assertEquals(layout,periodLayoutItems(items,5,periodLayoutSeed(seed,"2026-10")))
            assertEquals(items.take(5).toSet(),layout.toSet())
            layout
        }.toSet()
        assertTrue(seen.size>1)
        assertTrue(periodLayoutItems(items,0,0).isEmpty())
    }
    @Test fun monthGeometryVariesWithoutLeavingUsableBounds(){
        val layouts=(0 until 200).map{randomizedMonthArtGeometry(it,"2026-10")}
        assertEquals(setOf(true,false),layouts.map{it.portraitFirst}.toSet())
        assertEquals(setOf(true,false),layouts.map{it.smallFirst}.toSet())
        assertTrue(layouts.all{it.albumWidthFraction in .50f.. .59f})
        assertTrue(layouts.map{it.albumWidthFraction}.toSet().size>10)
        assertEquals(randomizedMonthArtGeometry(42,"2026-10"),randomizedMonthArtGeometry(42,"2026-10"))
    }
    @Test fun captionAvoidanceFollowsPortraitOnEitherSide(){
        val cover=YearArtSlot(.2f,.1f,.4f)
        val left=artworkCaptionInsets(cover,listOf(YearArtSlot(.12f,.30f,.3f)),.01f)
        val right=artworkCaptionInsets(cover,listOf(YearArtSlot(.40f,.30f,.3f)),.01f)
        assertTrue(left.first>0f);assertEquals(0f,left.second,0f)
        assertTrue(right.second>0f);assertEquals(0f,right.first,0f)
        assertEquals(0f,artworkCaptionInsets(cover,emptyList(),.01f).first,0f)
    }
    @Test fun yearGeometryVariesButKeepsCoversInsideSafeNonoverlappingRegions(){
        val layouts=(0 until 500).map(::randomizedYearArtLayout)
        assertTrue(layouts.map{it.songs.first()}.toSet().size>10)
        for(layout in layouts){
            for(slot in layout.songs+layout.artists){
                assertTrue(slot.x>=-0.0001f&&slot.x+slot.side<=1.0001f)
                assertTrue(slot.y>=0f&&slot.y+slot.side<=1.0351f)
                assertTrue(slot.side>0f)
            }
            layout.songs.forEachIndexed{i,a->layout.songs.drop(i+1).forEach{b->
                assertFalse(a.x<b.x+b.side&&a.x+a.side>b.x&&a.y<b.y+b.side&&a.y+a.side>b.y)
            }}
            val interlocking=layout.artists.count{artist->layout.songs.any{song->
                val cx=artist.x+artist.side/2;val cy=artist.y+artist.side/2
                val x=cx.coerceIn(song.x,song.x+song.side);val y=cy.coerceIn(song.y,song.y+song.side)
                val dx=x-cx;val dy=y-cy;dx*dx+dy*dy<(artist.side*.47f)*(artist.side*.47f)
            }}
            assertTrue("Freeform layouts should retain several interlocking portraits: $layout",interlocking>=2)
            assertTrue("The lower part should be occupied",layout.songs.any{it.y+it.side>.80f})
            assertTrue("The left and right sides should both be occupied",layout.songs.any{it.x<.20f}&&layout.songs.any{it.x+it.side>.80f})
            val weighted=layout.songs.map{it to it.side*it.side}+layout.artists.map{it to it.side*it.side*.785f}
            val total=weighted.sumOf{it.second.toDouble()}
            val cx=weighted.sumOf{(slot,area)->(slot.x+slot.side/2)*area.toDouble()}/total
            val cy=weighted.sumOf{(slot,area)->(slot.y+slot.side/2)*area.toDouble()}/total
            assertTrue("Mosaic should retain enough visual density: $layout",total>.42)
            assertTrue("Mosaic center should not drift to an edge: $cx,$cy",cx in .32.. .68&&cy in .35.. .66)
        }
    }
    private fun intersection(song:YearArtSlot,artist:YearArtSlot):Double {
        val radius=artist.side/2.0;val cx=artist.x+radius;val cy=artist.y+radius
        val step=song.side/120.0
        return (0 until 120).sumOf{i->
            val x=song.x+(i+.5)*step;val dx=x-cx
            if(kotlin.math.abs(dx)>=radius)0.0 else{
                val h=kotlin.math.sqrt(radius*radius-dx*dx)
                kotlin.math.max(0.0,kotlin.math.min(song.y+song.side.toDouble(),cy+h)-kotlin.math.max(song.y.toDouble(),cy-h))*step
            }
        }
    }
    @Test fun yearPortraitIntrusionRemainsBelowTwoFifths(){
        for(seed in 0 until 500){
            val layout=randomizedYearArtLayout(seed)
            for(artist in layout.artists){
                val area=layout.songs.sumOf{intersection(it,artist)}
                val fraction=area/(Math.PI*artist.side*artist.side/4)
                assertTrue("portrait intrusion $fraction: $layout",fraction<=.40)
            }
            for(song in layout.songs){
                val fraction=layout.artists.sumOf{intersection(song,it)}/(song.side*song.side)
                assertTrue("cover intrusion $fraction: $layout",fraction<=.40)
            }
        }
    }
    @Test fun sparseYearIntrusionsAlsoRespectTwoFifths(){
        for(seed in 0 until 100)for(count in 1..3){
            val layout=randomizedYearArtLayout(seed,count,4)
            assertTrue("available portraits should remain visible",layout.artists.isNotEmpty())
            for(artist in layout.artists){
                val fraction=layout.songs.sumOf{intersection(it,artist)}/(Math.PI*artist.side*artist.side/4)
                assertTrue("sparse portrait intrusion $fraction: $layout",fraction<=.40)
            }
            for(song in layout.songs){
                val fraction=layout.artists.sumOf{intersection(song,it)}/(song.side*song.side)
                assertTrue("sparse cover intrusion $fraction: $layout",fraction<=.40)
            }
        }
    }
    @Test fun sparseYearDataUsesLargerArtwork(){
        for(count in 1..3){
            val layout=randomizedYearArtLayout(45,count,0)
            assertEquals(count,layout.songs.size)
            assertTrue(layout.songs.all{it.side>.40f})
            assertTrue(layout.artists.isEmpty())
        }
        assertTrue(randomizedYearArtLayout(45,20,3).songs.size>=5)
        assertEquals(3,randomizedYearArtLayout(45,20,3).artists.size)
    }
    @Test fun allAvailableItemsHaveUniqueStablePartners(){
        for(seed in 0 until 100)for(artist in listOf(false,true))for(count in 0..6){
            val month=periodMotionKeys(seed,DiaryRange.MONTH,artist,count)
            assertEquals((0 until count).toSet(),month.toSet())
            assertEquals(month,periodMotionKeys(seed,DiaryRange.MONTH,artist,count))
            val year=periodMotionKeys(seed,DiaryRange.YEAR,artist,6)
            assertEquals(count,month.toSet().intersect(year.toSet()).size)
        }
        assertTrue((0 until 100).map{periodMotionKeys(it,DiaryRange.YEAR,false,6)}.toSet().size>1)
    }
    @Test fun monthCanUseOneTwoOrThreeArtistsAndFallsBackWhenDataIsMissing(){
        val day=java.time.LocalDate.of(2026,10,23)
        val slice=visiblePeriodHeroSlices(periodDemoReport(day,DiaryRange.MONTH),day,DiaryRange.MONTH).first{it.albums.size>=3&&it.artists.size>=3}
        val styles=(0 until 100).map{monthMosaicStyle(it,slice)}.toSet()
        assertEquals(setOf(1,2,3),styles)
        assertTrue((0 until 100).all{monthMosaicStyle(it,slice.copy(artists=slice.artists.take(1)))==1})
    }
    @Test fun choicesStayStableAndCoverAllVisibleSlots(){
        for(range in listOf(DiaryRange.WEEK,DiaryRange.MONTH,DiaryRange.YEAR)){
            for(artist in listOf(false,true)){
                assertEquals(-1,periodMotionSlot(23,range,artist,0))
                for(count in 1..5){
                    val choices=(0 until 200).map{seed->
                        val slot=periodMotionSlot(seed,range,artist,count)
                        assertEquals(slot,periodMotionSlot(seed,range,artist,count))
                        assertTrue(slot in 0 until count)
                        slot
                    }.toSet()
                    assertEquals((0 until count).toSet(),choices)
                }
            }
        }
    }
}
