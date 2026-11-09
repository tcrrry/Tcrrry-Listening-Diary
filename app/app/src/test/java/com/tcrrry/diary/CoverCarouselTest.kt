package com.tcrrry.diary
import org.junit.Test
import org.junit.Assert.*
import java.time.LocalDate

class CoverCarouselTest {
    private val plays=demoReport(LocalDate.of(2026,10,25)).history
    @Test fun newestFirstPreservesEveryRepeat(){val result=CoverCarousel.history(plays.reversed());assertEquals(24,result.size);assertEquals(plays.asReversed().map{it.visit},result.map{it.visit});assertEquals(result[0].id,result[5].id);assertNotEquals(result[0].visit,result[5].visit)}
    @Test fun sentinelPagesCloseTheLoopOneStepAtATime(){val n=plays.size;val g=CoverCarousel.GUARD_PAGES;assertEquals(n+2*g,CoverCarousel.pageCount(n));assertEquals(n-1,CoverCarousel.sourceIndex(g-1,n));assertEquals(0,CoverCarousel.sourceIndex(g,n));assertEquals(0,CoverCarousel.sourceIndex(n+g,n));assertEquals(g,CoverCarousel.normalize(n+g,n));assertEquals(n+g-1,CoverCarousel.normalize(g-1,n))}
    @Test fun duplicateSongsAndSentinelsHaveUniqueKeys(){val keys=(0 until CoverCarousel.pageCount(plays.size)).map{CoverCarousel.key(it,plays)};assertEquals(keys.size,keys.toSet().size)}
    @Test fun nearCardsAlwaysPaintAboveFarCardsOnBothSides(){for(step in -50..50){val fraction=step/100f;for(side in listOf(-1,1)){assertTrue(CoverCarousel.depth(side+fraction)>CoverCarousel.depth(2*side+fraction));assertTrue(CoverCarousel.depth(2*side+fraction)>CoverCarousel.depth(3*side+fraction))}}}
    @Test fun visibleCoverSizesDecreaseWithoutPerspectiveCrossing(){assertTrue(CoverCarousel.scale(0f)>CoverCarousel.scale(1f));assertTrue(CoverCarousel.scale(1f)>CoverCarousel.scale(2f));assertTrue(CoverCarousel.scale(2f)>CoverCarousel.scale(3f));val maxPerspective=1f/(1f-.5f/CoverCarousel.CAMERA_WIDTHS);assertTrue(CoverCarousel.scale(2f)*maxPerspective<CoverCarousel.scale(1f)/maxPerspective)}
    @Test fun singleCoverHasNoSyntheticPages(){assertEquals(1,CoverCarousel.pageCount(1));assertEquals(0,CoverCarousel.firstPage(1));assertEquals(0,CoverCarousel.sourceIndex(0,1))}
}
