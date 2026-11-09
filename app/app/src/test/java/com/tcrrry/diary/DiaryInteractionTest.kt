package com.tcrrry.diary
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

/** Tests user actions against the real composition, including animation interruption. */
@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],qualifiers="w412dp-h915dp-xxhdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiaryInteractionTest {
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Before fun setup(){ui.runOnUiThread{ui.activity.controller.preview(true);val instance=DiaryDatabase::class.java.getDeclaredField("instance");instance.isAccessible=true;instance.set(null,null);ui.activity.controller.network(false);seedPreviewArt(ui.activity);seedArtistArt(ui.activity);ui.activity.controller.preview(true);ui.activity.setContent{CompositionLocalProvider(LocalInspectionMode provides true){DiaryApp(ui.activity.controller,{},{})}}};ui.waitForIdle()}
    @Test fun genreDistributionRetainsSmallGenresInScrollableLegend(){
        val genres=(1..7).map{GenreStat("风格$it",if(it==1)940L else 10L,emptyList(),1)}+GenreStat("未匹配",1000L,emptyList(),1)
        ui.runOnUiThread{ui.activity.setContent{androidx.compose.material3.MaterialTheme{GenreDistribution(genres)}}};ui.waitForIdle()
        ui.onNodeWithTag("genre-distribution").assertExists()
        ui.onNodeWithTag("genre-major:风格1").assertExists()
        ui.onNodeWithTag("genre-minor-group").assertExists()
        ui.onNodeWithText("风格1",substring=true).assertExists()
        ui.onNodeWithText("风格7",substring=true).assertExists()
        ui.onNodeWithText("其他",substring=true).assertDoesNotExist()
        ui.onNodeWithText("未匹配",substring=true).assertDoesNotExist()
        ui.onNodeWithTag("genre-distribution-legend").performTouchInput{swipeLeft()};ui.waitForIdle()
        ui.onNodeWithText("风格7",substring=true).assertIsDisplayed()
    }
    @Test fun openingHistoryDoesNotLiftUnrelatedHeroArtworkIntoOverlay(){
        ui.runOnUiThread{ui.activity.controller.select(java.time.LocalDate.of(2026,10,23),DiaryRange.WEEK)}
        ui.waitForIdle()
        ui.onNodeWithTag("period-report").performScrollToNode(hasTestTag("period-history-link"))
        ui.mainClock.autoAdvance=false
        try {
            ui.onNodeWithTag("period-history-link").performClick()
            for(step in 0..6){
                ui.mainClock.advanceTimeBy(80);ui.waitForIdle()
                Assert.assertTrue("period artwork must not float over history",ui.onAllNodes(SemanticsMatcher.expectValue(PeriodArtworkOverlayKey,true),useUnmergedTree=true).fetchSemanticsNodes().isEmpty())
            }
        } finally {ui.mainClock.autoAdvance=true}
        ui.waitForIdle();ui.onNodeWithTag("period-history-page").assertExists()
    }

    @Test fun dailyArtistOffersSongAlbumAndTimeCountControls(){
        ui.onNodeWithTag("home-scroll").performScrollToNode(hasTestTag("artist-card:由薫"))
        ui.onNodeWithTag("artist-card:由薫").performClick();ui.waitForIdle()
        ui.onNodeWithTag("music-group-detail").performScrollToNode(hasTestTag("daily-artist-listening"))
        ui.onNodeWithText("今日收听").assertExists()
        ui.onNodeWithText("专辑").performClick();ui.waitForIdle()
        ui.onAllNodes(SemanticsMatcher("daily album"){it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.TestTag)&&it.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag].startsWith("daily-artist-album:")}).onFirst().assertExists()
        ui.onNodeWithText("时间").performClick();ui.waitForIdle()
        ui.onNodeWithTag("daily-sort-direction").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"正序"))
        ui.onNodeWithTag("daily-sort-direction").performClick();ui.waitForIdle()
        ui.onNodeWithTag("daily-sort-direction").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"倒序"))
        shot("preview77-artist-albums")
        ui.onNodeWithText("歌曲").performClick();ui.waitForIdle()
        ui.onNodeWithTag("music-group-detail").performScrollToIndex(3)
        ui.onNodeWithTag("daily-listening-summary").assertDoesNotExist()
        ui.onNodeWithText("这一天的声音").assertDoesNotExist();shot("preview89-artist-listening")
    }
    @Test fun dailyAlbumTimeSortShowsExactClockAndIdentifiedRecords(){
        val first=ui.activity.controller.report.history.filter{it.artist=="由薫"}.minBy{it.start}
        val clock=dailyRecordTime(first,ui.activity.controller.use12Hour)
        ui.onNodeWithTag("home-scroll").performScrollToNode(hasTestTag("artist-card:由薫"))
        ui.onNodeWithTag("artist-card:由薫").performClick();ui.waitForIdle()
        ui.onNodeWithTag("music-group-detail").performScrollToNode(hasTestTag("daily-artist-listening"))
        ui.onNodeWithText("专辑").performClick();ui.waitForIdle()
        ui.onAllNodes(SemanticsMatcher("daily albums"){it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.TestTag)&&it.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag].startsWith("daily-artist-album:")}).onFirst().performClick();ui.waitForIdle()
        ui.onNodeWithTag("music-group-detail").performScrollToNode(hasText("时间"))
        ui.onNodeWithText("时间").performClick();ui.waitForIdle()
        ui.onNodeWithTag("group-song-row:${first.id}",useUnmergedTree=true).assert(hasAnyDescendant(hasText(clock,substring=true)))
        ui.onNodeWithTag("music-group-detail").performScrollToNode(hasTestTag("detail-record:${first.visit}"))
        ui.onNodeWithTag("detail-record:${first.visit}",useUnmergedTree=true).assert(hasAnyDescendant(hasText(first.title)))
        shot("preview79-album-records")
    }
    @Test fun recordListSeparatesDifferentSongNamesAndClocks(){
        val at=java.time.Instant.parse("2026-10-30T10:05:00Z").toEpochMilli()
        val plays=listOf("Song A","Song B").mapIndexed{i,title->Play("song-$i","visit-$i",title,"Artist","Album",at+i*3600000,at+i*3600000+120000,120000,180000,"UTC")}
        val controller=ui.activity.controller
        ui.runOnUiThread{ui.activity.setContent{CompositionLocalProvider(LocalInspectionMode provides true){androidx.compose.material3.MaterialTheme{androidx.compose.foundation.lazy.LazyColumn{detailedRecordItems(controller,plays,true)}}}}}
        ui.waitForIdle()
        ui.onNodeWithText("Song A").assertExists();ui.onNodeWithText("Song B").assertExists()
        ui.onNodeWithText("10:05").assertExists();ui.onNodeWithText("11:05").assertExists()
        shot("preview79-song-identities")
    }
    @Test fun dailySongRecordsAreCompactAndShowClockOnly(){
        ui.onNodeWithTag("cover-caption").performClick();ui.waitForIdle()
        ui.onNodeWithText("详细记录").assertExists()
        ui.onAllNodes(SemanticsMatcher("record cells"){it.config.contains(androidx.compose.ui.semantics.SemanticsProperties.TestTag)&&it.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag].startsWith("detail-record:")}).onFirst().assertExists()
        shot("preview77-records")
    }

    @Test fun reopeningDayHistoryStartsAtTop(){
        openHistory();ui.waitForIdle()
        ui.onAllNodes(hasText("展开其余",substring=true)).onFirst().performClick();ui.waitForIdle()
        ui.onNodeWithTag("history-scroll").performScrollToIndex(4);ui.waitForIdle()
        Assert.assertTrue(ui.onNodeWithTag("history-scroll").fetchSemanticsNode().config[HistoryScrollIndexKey]>0)
        ui.runOnUiThread{ui.activity.onBackPressedDispatcher.onBackPressed()};ui.waitForIdle()
        ui.onNodeWithTag("home-scroll").performScrollToNode(hasTestTag("day-history-link"))
        ui.onNodeWithTag("day-history-link").performClick();ui.waitForIdle()
        ui.onNodeWithTag("history-scroll").assert(SemanticsMatcher.expectValue(HistoryScrollIndexKey,0))
    }
    @Test fun reopeningEachPeriodHistoryFromTheArrowStartsAtTop(){
        val controller=ui.activity.controller
        for(range in listOf(DiaryRange.WEEK,DiaryRange.MONTH,DiaryRange.YEAR)){
            ui.runOnUiThread{controller.select(java.time.LocalDate.of(2026,10,23),range)};ui.waitForIdle()
            ui.onNodeWithTag("period-report").performScrollToNode(hasTestTag("period-history-link"))
            ui.onNodeWithTag("period-history-link").performClick();ui.waitForIdle()
            ui.onNodeWithTag("period-history-page").assert(SemanticsMatcher.expectValue(HistoryScrollIndexKey,0))
            ui.onNodeWithTag("period-history-page").performScrollToIndex(3);ui.waitForIdle()
            Assert.assertTrue(ui.onNodeWithTag("period-history-page").fetchSemanticsNode().config[HistoryScrollIndexKey]>0)
            ui.runOnUiThread{ui.activity.onBackPressedDispatcher.onBackPressed()};ui.waitForIdle()
            ui.onNodeWithTag("period-report").performScrollToNode(hasTestTag("period-history-link"))
            ui.onNodeWithTag("period-history-link").performClick();ui.waitForIdle()
            ui.onNodeWithTag("period-history-page").assert(SemanticsMatcher.expectValue(HistoryScrollIndexKey,0))
            ui.runOnUiThread{ui.activity.onBackPressedDispatcher.onBackPressed()};ui.waitForIdle()
        }
    }
    @Test fun periodHistoryKeepsItsPlaceAfterOpeningAnArtist(){
        val day=java.time.LocalDate.of(2026,10,23)
        ui.runOnUiThread{ui.activity.controller.select(day,DiaryRange.WEEK)};ui.waitForIdle()
        ui.onNodeWithTag("period-report").performScrollToNode(hasTestTag("period-history-link"));ui.onNodeWithTag("period-history-link").performClick();ui.waitForIdle()
        ui.onNodeWithTag("period-history-page").performScrollToNode(hasTestTag("period-history:$day"));shot("preview55-history-final");val before=ui.onNodeWithTag("period-history-page").fetchSemanticsNode().config[HistoryScrollIndexKey];ui.onNodeWithTag("period-history-artist:$day").performClick();ui.waitForIdle()
        ui.onNodeWithTag("music-group-detail").assertExists();shot("preview54-artist");ui.onNodeWithContentDescription("返回日记").performClick();ui.waitUntil(10000){ui.onAllNodesWithTag("period-history-page").fetchSemanticsNodes().isNotEmpty()};ui.waitForIdle();ui.onNodeWithTag("period-history-page").assert(SemanticsMatcher.expectValue(HistoryScrollIndexKey,before))
    }
    @Test fun eachPeriodHistoryShowsOnlyRecordedBuckets(){
        val day=java.time.LocalDate.of(2026,10,23)
        for(range in listOf(DiaryRange.WEEK,DiaryRange.MONTH,DiaryRange.YEAR)){
            ui.runOnUiThread{ui.activity.controller.select(day,range)};ui.waitForIdle()
            val bounds=bounds(day,range);val first=java.time.LocalDate.parse(bounds.from);val last=java.time.LocalDate.parse(bounds.to)
            val dates=generateSequence(first){it.plusDays(1)}.takeWhile{it<=last}.toList();val recorded=periodHistoryDates(range,dates,reportDays(ui.activity.controller.report))
            ui.onNodeWithTag("period-report").performScrollToNode(hasTestTag("period-history-link"));ui.onNodeWithTag("period-history-link").performClick();ui.waitForIdle()
            if(recorded.isNotEmpty()){ui.onNodeWithTag("period-history-page").performScrollToNode(hasTestTag("period-history:${recorded.last()}"));ui.onNodeWithTag("period-history:${recorded.last()}").assertExists()}
            ui.onNodeWithText("暂无记录").assertDoesNotExist();ui.onNodeWithText("听歌日记").performClick();ui.waitForIdle()
        }
    }
    @Test fun hardwareBackReturnsFromArtistDetailToArtistRanking(){
        ui.onNodeWithTag("home-scroll").performScrollToNode(hasText("常听歌手"));ui.onNodeWithTag("all-link:常听歌手").performClick();ui.waitForIdle()
        ui.onNodeWithTag("library-group:artist:由薫").performClick();ui.waitForIdle();ui.onNodeWithTag("music-group-detail").assertExists()
        ui.runOnUiThread{ui.activity.onBackPressedDispatcher.onBackPressed()};ui.waitForIdle();ui.onNodeWithTag("artist-ranking").assertExists();ui.onNodeWithTag("music-group-detail").assertDoesNotExist()
        ui.runOnUiThread{ui.activity.onBackPressedDispatcher.onBackPressed()};ui.waitForIdle();ui.onNodeWithTag("home-scroll").assertExists()
    }
    @Test fun backDuringArtistEntranceReturnsToTheRanking(){
        ui.onNodeWithTag("home-scroll").performScrollToNode(hasText("常听歌手"));ui.onNodeWithTag("all-link:常听歌手").performClick();ui.waitForIdle()
        ui.mainClock.autoAdvance=false
        try{ui.onNodeWithTag("library-group:artist:由薫").performClick();ui.mainClock.advanceTimeByFrame();ui.mainClock.advanceTimeBy(80);ui.runOnUiThread{ui.activity.onBackPressedDispatcher.onBackPressed()};ui.mainClock.advanceTimeBy(1600)}finally{ui.mainClock.autoAdvance=true}
        ui.waitForIdle();ui.onNodeWithTag("artist-ranking").assertExists();ui.onNodeWithTag("music-group-detail").assertDoesNotExist()
    }
    @Test fun dayHistoryPanelExpandsFromItsCard(){
        ui.onNodeWithTag("home-scroll").performScrollToNode(hasTestTag("day-history-title"));ui.waitForIdle()
        val panel=SemanticsMatcher.expectValue(HistoryPanelKey,"DAY")
        val before=ui.onAllNodes(SemanticsMatcher.keyIsDefined(HistoryCoverKey),useUnmergedTree=true).fetchSemanticsNodes().map{it.config[HistoryCoverKey]}.toSet()
        ui.mainClock.autoAdvance=false
        try{ui.onNodeWithTag("day-history-title").performClick();ui.mainClock.advanceTimeByFrame();ui.mainClock.advanceTimeBy(90);ui.waitForIdle()
            Assert.assertTrue(ui.onAllNodes(panel,useUnmergedTree=true).fetchSemanticsNodes().size>=2)
            val after=ui.onAllNodes(SemanticsMatcher.keyIsDefined(HistoryCoverKey),useUnmergedTree=true).fetchSemanticsNodes().map{it.config[HistoryCoverKey]}
            Assert.assertTrue("day thumbnails must have a matching history entry",after.any{it in before});shot("preview55-day-transition")
        }finally{ui.mainClock.autoAdvance=true};ui.waitForIdle()
    }
    @Test fun periodHistoryDisplaysIndependentCountsForAllThreeItems(){
        val day=java.time.LocalDate.of(2024,5,10);val start=day.atTime(12,0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        fun song(id:String,artist:String,album:String,count:Long)=Play(id,id,id,artist,album,start,start+60000,60000,240000,"UTC",effective=count)
        val rows=listOf(song("Song A","Artist X","Album A",2),song("Song B","Artist X","Album B",2),song("Song C","Artist Y","Album C",3))
        ui.runOnUiThread{ui.activity.setContent{CompositionLocalProvider(LocalInspectionMode provides true){androidx.compose.material3.MaterialTheme{PeriodHistoryPage(ui.activity.controller,DiaryRange.WEEK,listOf(day),mapOf(day to rows),mapOf(day to 180000),null,{}, {_,_->})}}}};ui.waitForIdle()
        ui.onNodeWithTag("period-history-song:$day").assert(hasText("3次"))
        ui.onNodeWithTag("period-history-album:$day").assert(hasText("3次"))
        ui.onNodeWithTag("period-history-artist:$day").assert(hasText("4次"))
        ui.onNodeWithText("歌曲 ·",substring=true).assertDoesNotExist();ui.onNodeWithText("专辑 ·",substring=true).assertDoesNotExist();ui.onNodeWithText("歌手 ·",substring=true).assertDoesNotExist()
    }
    @Test fun dayHistoryTitleAndArrowOpenTheSamePage(){
        ui.onNodeWithTag("home-scroll").performScrollToNode(hasTestTag("day-history-title"));ui.onNodeWithTag("day-history-title").performClick();ui.waitForIdle();ui.onNodeWithTag("history-scroll").assertExists()
        ui.runOnUiThread{ui.activity.onBackPressedDispatcher.onBackPressed()};ui.waitForIdle();ui.onNodeWithTag("home-scroll").assertExists();ui.onNodeWithTag("day-history-link").performClick();ui.waitForIdle();ui.onNodeWithTag("history-scroll").assertExists()
    }
    @Test fun historyCardsAndCoversHaveMatchingTransitionPartners(){
        val day=java.time.LocalDate.of(2026,10,23)
        for(range in listOf(DiaryRange.WEEK,DiaryRange.MONTH,DiaryRange.YEAR)){
            ui.runOnUiThread{ui.activity.controller.select(day,range)};ui.waitForIdle()
            ui.onNodeWithTag("period-report").performScrollToNode(hasTestTag("period-history-link"));ui.waitForIdle()
            val panel=SemanticsMatcher.expectValue(HistoryPanelKey,range.name)
            val sources=ui.onAllNodes(SemanticsMatcher.keyIsDefined(HistoryCoverKey),useUnmergedTree=true).fetchSemanticsNodes().map{it.config[HistoryCoverKey]}.toSet()
            Assert.assertTrue("source panel",ui.onAllNodes(panel,useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty())
            ui.mainClock.autoAdvance=false
            try{
                ui.onNodeWithTag("period-history-link").performClick();ui.mainClock.advanceTimeByFrame();ui.mainClock.advanceTimeBy(90);ui.waitForIdle()
                val panels=ui.onAllNodes(panel,useUnmergedTree=true).fetchSemanticsNodes();Assert.assertTrue("source and destination should coexist during transition",panels.size>=2)
                val covers=ui.onAllNodes(SemanticsMatcher.keyIsDefined(HistoryCoverKey),useUnmergedTree=true).fetchSemanticsNodes().map{it.config[HistoryCoverKey]}
                Assert.assertTrue("visible covers must have a destination: $sources -> $covers",covers.any{it in sources})
                shot("preview55-history-transition-${range.name}")
            }finally{ui.mainClock.autoAdvance=true}
            ui.waitForIdle();ui.onNodeWithText("听歌日记").performClick();ui.waitForIdle()
        }
    }
    @Test fun timelineDragFollowsFingerAndDoesNotSwitchPeriod(){
        openSummary();ui.onNodeWithTag("summary-scroll").performScrollToNode(hasTestTag("favorite-timeline"));ui.waitForIdle();ui.mainClock.autoAdvance=false
        try{
            val timeline=ui.onNodeWithTag("favorite-timeline");val before=timeline.fetchSemanticsNode().config[MusicTimelinePhaseKey]
            timeline.performTouchInput{
                val measure=androidx.compose.ui.graphics.PathMeasure().apply{setPath(musicTimelinePath(width.toFloat(),height.toFloat()),false)}
                val distance=measure.length*.48f;val start=measure.getPosition(distance);val tangent=measure.getTangent(distance)
                down(start);moveTo(start+tangent*90f);up()
            };ui.waitForIdle();val after=timeline.fetchSemanticsNode().config[MusicTimelinePhaseKey]
            Assert.assertTrue("line drag must move timeline: $before -> $after",kotlin.math.abs(after-before)>.02f);Assert.assertTrue(ui.activity.controller.summary);shot("preview59-timeline")
        }finally{ui.mainClock.autoAdvance=true}
    }
    @Test fun verticalPageScrollAlsoMovesTheTimeline(){
        openSummary();ui.onNodeWithTag("summary-scroll").performScrollToNode(hasTestTag("favorite-timeline"));ui.waitForIdle();ui.mainClock.autoAdvance=false
        try{
            val timeline=ui.onNodeWithTag("favorite-timeline");val before=timeline.fetchSemanticsNode().config[MusicTimelinePhaseKey]
            timeline.performTouchInput{swipe(androidx.compose.ui.geometry.Offset(width*.92f,height*.08f),androidx.compose.ui.geometry.Offset(width*.92f,height*.18f),350)};ui.waitForIdle()
            val after=ui.onNodeWithTag("favorite-timeline").fetchSemanticsNode().config[MusicTimelinePhaseKey]
            Assert.assertEquals("drag away from the line must not move timeline",before,after,.001f)
        }finally{ui.mainClock.autoAdvance=true}
    }
    @OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
    @Test fun favoriteOrbitAdvancesAutomatically(){
        openSummary();val motion=MusicTimelineState();ui.mainClock.autoAdvance=false
        try{
            ui.runOnUiThread{ui.activity.setContent{CompositionLocalProvider(LocalInspectionMode provides true){androidx.compose.material3.MaterialTheme{
                androidx.compose.animation.SharedTransitionLayout{androidx.compose.animation.AnimatedVisibility(visible=true){MusicTimeline(ui.activity.controller,ui.activity.controller.summaryFacts,{},{_,_->},this@SharedTransitionLayout,this,motion)}}
            }}}};ui.waitForIdle();ui.mainClock.advanceTimeBy(100);ui.waitForIdle();ui.mainClock.advanceTimeBy(12000);ui.waitForIdle()
            Assert.assertTrue("timeline should advance continuously: ${motion.phase}, count=${ui.activity.controller.summaryFacts.monthlyFavorites.size}, summary=${ui.activity.controller.summary}, foreground=${ui.activity.controller.foreground}, demo=${ui.activity.controller.demo}, swipe=${ui.activity.controller.periodSwipePosition}",motion.phase>.5f)
        }finally{ui.mainClock.autoAdvance=true}
    }
    private fun timelineAlbums()=ui.onAllNodes(SemanticsMatcher("timeline album") {
        it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.TestTag){""}.startsWith("timeline-item:")
    },useUnmergedTree=true)

    @Test fun listeningDayDefaultFollowsCutoffWithoutOverwritingManualHistory(){
        val c=ui.activity.controller
        ui.runOnUiThread {
            c.stop();c.dayPolicy(ListeningDayPolicy(ListeningDayMode.FIXED,4))
            kotlinx.coroutines.runBlocking { c.alignListeningDay(java.time.ZonedDateTime.parse("2026-01-01T01:00:00+08:00")) }
            Assert.assertEquals(java.time.LocalDate.of(2025,12,31),c.day)
            kotlinx.coroutines.runBlocking { c.alignListeningDay(java.time.ZonedDateTime.parse("2026-01-01T04:00:00+08:00")) }
            Assert.assertEquals(java.time.LocalDate.of(2026,1,1),c.day)
            c.select(java.time.LocalDate.of(2020,1,1))
            kotlinx.coroutines.runBlocking { c.alignListeningDay(java.time.ZonedDateTime.parse("2026-01-02T08:00:00+08:00")) }
            Assert.assertEquals(java.time.LocalDate.of(2020,1,1),c.day)
        }
    }

    @Test fun timelineBottomScrubberKeepsItsSpaceAndSeeksHorizontally(){
        openSummary()
        ui.onNodeWithTag("summary-scroll").performScrollToNode(hasTestTag("timeline-controls-slot"));ui.waitForIdle()
        val slotBefore=ui.onNodeWithTag("timeline-controls-slot").fetchSemanticsNode().boundsInRoot
        val heroBefore=ui.onNodeWithTag("summary-profile-hero").fetchSemanticsNode().boundsInRoot
        val lineBounds=ui.onNodeWithTag("favorite-timeline").fetchSemanticsNode().boundsInRoot
        Assert.assertTrue("controls belong below the route",slotBefore.top>=lineBounds.bottom-1f)
        val covers=timelineAlbums().fetchSemanticsNodes()
        val album=covers.first { it.boundsInRoot.top>lineBounds.top+20f&&it.boundsInRoot.bottom<lineBounds.bottom-20f }
        ui.mainClock.autoAdvance=false
        ui.onNodeWithTag(album.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag]).performTouchInput{longClick()}
        ui.mainClock.advanceTimeBy(700);ui.waitForIdle()
        val slotAfter=ui.onNodeWithTag("timeline-controls-slot").fetchSemanticsNode().boundsInRoot
        val heroAfter=ui.onNodeWithTag("summary-profile-hero").fetchSemanticsNode().boundsInRoot
        Assert.assertEquals("opening controls must not move the next section",heroBefore.bottom,heroAfter.bottom,1f)
        Assert.assertEquals(slotBefore,slotAfter)
        val before=ui.onNodeWithTag("favorite-timeline").fetchSemanticsNode().config[MusicTimelinePhaseKey]
        ui.onNodeWithTag("timeline-quick-scrubber").performTouchInput{swipe(androidx.compose.ui.geometry.Offset(width*.15f,height*.5f),androidx.compose.ui.geometry.Offset(width*.85f,height*.5f),500)}
        ui.mainClock.advanceTimeBy(1000);ui.waitForIdle()
        val after=ui.onNodeWithTag("favorite-timeline").fetchSemanticsNode().config[MusicTimelinePhaseKey]
        Assert.assertTrue("horizontal scrub must change month: $before -> $after ",kotlin.math.abs(after-before)>.2f)
        Assert.assertTrue(ui.activity.controller.summary)
        shot("preview62-quick")
        ui.onNodeWithText("完成").performClick();ui.mainClock.advanceTimeBy(700);ui.waitForIdle()
        Assert.assertEquals(slotBefore,ui.onNodeWithTag("timeline-controls-slot").fetchSemanticsNode().boundsInRoot)
        shot("preview62-normal")
        ui.mainClock.autoAdvance=true
    }

    @Test fun topAlbumReturnSettlesOnceAtOriginalBounds(){
        ui.runOnUiThread{ui.activity.controller.select(java.time.LocalDate.of(2026,10,23),DiaryRange.WEEK)}
        ui.waitForIdle()
        ui.onNodeWithTag("period-hero-pager").performScrollToIndex(1);ui.waitForIdle()
        val slice=periodHeroSlices(ui.activity.controller.report,java.time.LocalDate.of(2026,10,23),DiaryRange.WEEK).first()
        val tag="hero-album:${slice.key}:${slice.lead!!.id}"
        val before=ui.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        ui.mainClock.autoAdvance=false
        try {
            ui.onNodeWithTag(tag).performClick();ui.waitForIdle();ui.mainClock.advanceTimeBy(1000);ui.waitForIdle()
            ui.onNodeWithContentDescription("返回日记").assertExists()
            ui.onNodeWithContentDescription("返回日记").performClick()
            ui.mainClock.advanceTimeByFrame();ui.mainClock.advanceTimeBy(80);ui.waitForIdle()
            ui.onNodeWithTag("period-hero").assertExists()
            var sawPartialCaption=false
            repeat(90){
                ui.mainClock.advanceTimeBy(16);ui.waitForIdle()
                val opacity=ui.onNodeWithTag("week-album-title:${slice.key}").fetchSemanticsNode().config[DetailCaptionAlphaKey]
                if(opacity>.01f&&opacity<.99f)sawPartialCaption=true
            }
            Assert.assertTrue("return caption must fade through intermediate opacity",sawPartialCaption)
            ui.onNodeWithTag("week-album-title:${slice.key}").assert(SemanticsMatcher.expectValue(DetailCaptionAlphaKey,1f))
            val settled=ui.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            ui.mainClock.advanceTimeBy(1000);ui.waitForIdle()
            val later=ui.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            Assert.assertEquals("cover must return to its original bounds",before,settled)
            Assert.assertEquals("no second adjustment after landing",settled,later)
        } finally {ui.mainClock.autoAdvance=true}
    }

    @Test fun timelineDetailSharesItsCoverAndReturnsToTheSamePosition(){
        openSummary();ui.onNodeWithTag("summary-scroll").performScrollToNode(hasTestTag("favorite-timeline"));ui.waitForIdle()
        ui.mainClock.autoAdvance=false
        try {
            ui.onNodeWithTag("favorite-timeline").performTouchInput {
                val path=androidx.compose.ui.graphics.PathMeasure().apply{setPath(musicTimelinePath(width.toFloat(),height.toFloat()),false)}
                val at=path.length*.48f;val start=path.getPosition(at)
                down(start);moveTo(start+path.getTangent(at)*90f);up()
            };ui.waitForIdle()
            val phase=ui.onNodeWithTag("favorite-timeline").fetchSemanticsNode().config[MusicTimelinePhaseKey]
            Assert.assertTrue("exercise a nonzero phase: ",kotlin.math.abs(phase)>.02f)
            val covers=timelineAlbums().fetchSemanticsNodes()
            val album=covers[covers.size/2]
            ui.onNodeWithTag(album.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag]).performClick()
            ui.mainClock.advanceTimeByFrame();ui.mainClock.advanceTimeBy(90);ui.waitForIdle()
            val matched=SemanticsMatcher.expectValue(DetailArtworkMatchedKey,true)
            Assert.assertTrue("source and destination must actually match",ui.onAllNodes(matched,useUnmergedTree=true).fetchSemanticsNodes().size>=2)
            shot("preview62-detail-enter")
            ui.mainClock.advanceTimeBy(1500);ui.waitForIdle()
            ui.onNodeWithTag("music-group-detail").assertExists()
            ui.onNodeWithContentDescription("返回日记").performClick()
            ui.mainClock.advanceTimeByFrame();ui.mainClock.advanceTimeBy(90);ui.waitForIdle()
            Assert.assertTrue("return cover must have its original partner",ui.onAllNodes(matched,useUnmergedTree=true).fetchSemanticsNodes().size>=2)
            shot("preview62-detail-return")
            ui.mainClock.advanceTimeBy(1500);ui.waitForIdle()
            val returned=ui.onNodeWithTag("favorite-timeline").fetchSemanticsNode().config[MusicTimelinePhaseKey]
            Assert.assertEquals("return must preserve the timeline phase",phase,returned,.001f)
        } finally {ui.mainClock.autoAdvance=true}
    }

    @OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
    @Test fun largerTimelineCoversStayInsideTheScreenAcrossTheWholeRoute(){
        openSummary()
        val controller=ui.activity.controller
        val originals=controller.summaryFacts.report.history
        val rows=(0 until 24).map{index->
            val date=java.time.LocalDate.of(2024,1,2).plusMonths(index.toLong())
            val start=date.atTime(12,0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            originals[index%originals.size].copy(visit="timeline-bound-$index",start=start,end=start+180000,ms=180000,listeningDay=date.toString())
        }
        val facts=SummaryFacts(controller.summaryFacts.report.copy(history=rows,segments=rows,from="2024-01-02",to="2025-12-02"))
        val motion=MusicTimelineState().apply{dragging=true}
        ui.mainClock.autoAdvance=false
        try {
            ui.runOnUiThread{ui.activity.setContent{CompositionLocalProvider(LocalInspectionMode provides true){androidx.compose.material3.MaterialTheme{
                androidx.compose.animation.SharedTransitionLayout{androidx.compose.animation.AnimatedVisibility(visible=true){MusicTimeline(controller,facts,{},{_,_->},this@SharedTransitionLayout,this,motion)}}
            }}}};ui.waitForIdle()
            for(phase in listOf(0f,.25f,.5f,.99f,6.5f)) {
                ui.runOnUiThread{motion.phase=phase};ui.mainClock.advanceTimeByFrame();ui.waitForIdle()
                val covers=timelineAlbums().fetchSemanticsNodes()
                Assert.assertTrue("fewer simultaneous covers",covers.size in 14..16)
                for(node in covers){
                    val bounds=ui.onNodeWithTag(node.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag]).getUnclippedBoundsInRoot()
                    Assert.assertTrue("rotated cover must stay inside left edge: $bounds",bounds.left.value>=0f)
                    Assert.assertTrue("rotated cover must stay inside right edge: $bounds",bounds.right.value<=412f)
                }
            }
            shot("preview62-sixteen-covers")
        } finally {ui.mainClock.autoAdvance=true}
    }

    @Test fun summaryPartnersAreRandomAcrossTheWholeRouteAndStableWithinASwipe(){
        val choices=(0 until 40).map{summaryTimelineSlots(it,16)}
        Assert.assertTrue(choices.distinct().size>10)
        Assert.assertTrue(choices.flatten().any{it>10})
        choices.forEach{Assert.assertEquals(5,it.distinct().size);Assert.assertTrue(it.all{slot->slot in 1..14})}
        ui.runOnUiThread{ui.activity.controller.select(range=DiaryRange.YEAR);ui.activity.controller.preloadSummary()};ui.waitForIdle()
        ui.onNodeWithTag("period-report").performTouchInput{down(androidx.compose.ui.geometry.Offset(width*.8f,height*.08f));moveTo(androidx.compose.ui.geometry.Offset(width*.5f,height*.08f))};ui.waitForIdle()
        val seed=ui.activity.controller.summaryMotionSeed
        ui.onNodeWithTag("period-report").performTouchInput{moveTo(androidx.compose.ui.geometry.Offset(width*.35f,height*.08f))};ui.waitForIdle()
        Assert.assertEquals(seed,ui.activity.controller.summaryMotionSeed)
        ui.onNodeWithTag("period-report").performTouchInput{cancel()};ui.waitForIdle()
    }

    @Test fun dayWeekMonthShareOneCoverWithSummaryInBothDirections(){
        val c=ui.activity.controller
        val day=java.time.LocalDate.of(2026,10,23)
        val matched=SemanticsMatcher.expectValue(SummaryArtworkMatchedKey,true)
        for(range in listOf(DiaryRange.DAY,DiaryRange.WEEK,DiaryRange.MONTH)){
            ui.runOnUiThread{c.select(day,range);c.preloadSummary()}
            ui.waitForIdle();ui.waitUntil(10000){
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                !c.loading&&c.report.from==bounds(day,range).from&&c.summaryFacts.report.history.isNotEmpty()
            }
            ui.waitForIdle()
            ui.waitUntil(10000){ui.onAllNodes(SemanticsMatcher.keyIsDefined(SummaryArtworkKey),useUnmergedTree=true).fetchSemanticsNodes().any{it.config[SummaryArtworkKey]=="summary-cover:0"}}
            ui.mainClock.autoAdvance=false
            try {
                ui.runOnUiThread{c.openSummary()}
                ui.mainClock.advanceTimeByFrame();ui.mainClock.advanceTimeBy(90);ui.waitForIdle()
                var entering=ui.onAllNodes(matched,useUnmergedTree=true).fetchSemanticsNodes()
                // Sample the live transition before 320ms; asynchronous first layout need not
                // register both shared nodes at exactly the first 90ms frame.
                repeat(10){if(entering.isEmpty()){ui.mainClock.advanceTimeByFrame();ui.waitForIdle();entering=ui.onAllNodes(matched,useUnmergedTree=true).fetchSemanticsNodes()}}
                if(entering.size!=2)println("SUMMARY_MOTION_DIAGNOSTIC summary=${c.summary} loading=${c.summaryLoading} history=${c.summaryFacts.report.history.size} favorites=${c.summaryFacts.monthlyFavorites.size} tree="+ui.onRoot(useUnmergedTree=true).printToString(maxDepth=5))
                Assert.assertEquals("$range must move exactly one matched pair; keys="+ui.onAllNodes(SemanticsMatcher.keyIsDefined(SummaryArtworkKey),useUnmergedTree=true).fetchSemanticsNodes().map{it.config[SummaryArtworkKey] to it.config[SummaryArtworkMatchedKey]},2,entering.size)
                shot("preview68-${range.name}-enter")
                ui.mainClock.advanceTimeBy(1000);ui.waitForIdle()
                ui.runOnUiThread{c.select(day,range)}
                ui.mainClock.advanceTimeByFrame();ui.mainClock.advanceTimeBy(90);ui.waitForIdle()
                Assert.assertEquals("$range return must match the same single cover slot",2,ui.onAllNodes(matched,useUnmergedTree=true).fetchSemanticsNodes().size)
                shot("preview68-${range.name}-return")
                ui.mainClock.advanceTimeBy(1000)
            } finally {ui.mainClock.autoAdvance=true}
            ui.waitForIdle()
        }
    }

    @Test fun summaryExitRespondsImmediatelyAndThenLoadsTheRealYear(){
        val c=ui.activity.controller
        val day=java.time.LocalDate.of(2026,10,23)
        ui.runOnUiThread {
            c.stop()
            val db=DiaryDatabase.get(ui.activity)
            val at=day.atTime(12,0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            val payload=org.json.JSONObject().put("sourceId","summary-exit-test")
                .put("tracks",org.json.JSONArray().put(org.json.JSONObject().put("id","exit-song").put("title","Song").put("artist","Artist").put("album","Album").put("duration",240000)))
                .put("spans",org.json.JSONArray().put(org.json.JSONObject().put("id",1).put("track","exit-song").put("visit","exit-visit").put("day",day.toString()).put("zone",java.time.ZoneId.systemDefault().id).put("start",at).put("end",at+180000).put("ms",180000)))
            db.ingest(payload)
            c.preview(false);c.select(day,DiaryRange.DAY)
        }
        openSummary()
        ui.runOnUiThread {
            c.select(day,DiaryRange.YEAR)
            Assert.assertFalse("selection must respond while the target loads",c.summary)
            Assert.assertEquals(DiaryRange.YEAR,c.range)
        }
        ui.waitForIdle()
        try{ui.waitUntil(15000){org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();!c.summary&&c.range==DiaryRange.YEAR&&!c.loading&&c.retainedPeriodComputed?.report===c.report}}catch(e:Throwable){
            val field=DiaryController::class.java.getDeclaredField("summaryExitJob").apply{isAccessible=true}
            val task=field.get(c) as? kotlinx.coroutines.Job
            throw AssertionError("summary=${c.summary} range=${c.range} notice=${c.notice} task=$task",e)
        }
        ui.runOnIdle {
            Assert.assertFalse("the real report eventually replaces the loading state",c.loading)
            Assert.assertEquals("2026-01-01",c.report.from)
            Assert.assertSame(c.report,c.retainedPeriodComputed?.report)
            Assert.assertTrue(c.retainedPeriodComputed!!.slices.isNotEmpty())
        }
    }

    @Test fun yearToAllUsesMultipleCoverPartners(){
        ui.runOnUiThread{ui.activity.controller.select(range=DiaryRange.YEAR);ui.activity.controller.preloadSummary()};ui.waitForIdle();ui.waitUntil(10000){ui.activity.controller.summaryFacts.report.history.isNotEmpty()}
        ui.onNodeWithTag("period-report").performTouchInput{down(androidx.compose.ui.geometry.Offset(width*.8f,height*.08f));moveTo(androidx.compose.ui.geometry.Offset(width*.4f,height*.08f))};ui.waitForIdle()
        val keys=ui.onAllNodes(SemanticsMatcher.keyIsDefined(SummaryArtworkKey),useUnmergedTree=true).fetchSemanticsNodes().map{it.config[SummaryArtworkKey]}.filter{it.startsWith("summary-cover:")}
        Assert.assertTrue("multiple independent cover pairs should participate: $keys",keys.distinct().size>=2&&keys.size>=4)
        ui.onNodeWithTag("period-report").performTouchInput{cancel()};ui.waitForIdle()
    }
    @Test fun summaryCoverPartnersRemainRegisteredWithoutAnIdleMatch(){
        openSummary();ui.waitForIdle()
        Assert.assertTrue("idle timeline must not retain a matched outgoing page",ui.onAllNodes(SemanticsMatcher.expectValue(SummaryArtworkMatchedKey,true),useUnmergedTree=true).fetchSemanticsNodes().isEmpty())
    }
    @Test fun adjacentPeriodIsPreparedBeforeTheDrag(){
        ui.waitUntil(10000){runCatching{ui.onAllNodesWithTag("period-navigation").fetchSemanticsNodes().firstOrNull()?.config?.get(PreparedPeriodsKey)?.contains("WEEK")==true}.getOrDefault(false)}
        ui.onNodeWithTag("home-scroll").performTouchInput{down(androidx.compose.ui.geometry.Offset(width*.8f,height*.08f));moveTo(androidx.compose.ui.geometry.Offset(width*.55f,height*.08f))};ui.waitForIdle()
        Assert.assertTrue(ui.onAllNodesWithTag("period-hero-layout-week").fetchSemanticsNodes().isNotEmpty())
        ui.onNodeWithTag("home-scroll").performTouchInput{cancel()};ui.waitForIdle()
        Assert.assertEquals(DiaryRange.DAY,ui.activity.controller.range)
    }
    @Test fun everyVisibleMonthAndYearArtworkGetsADistinctMotionPartner(){
        val day=java.time.LocalDate.of(2026,10,23)
        for(range in listOf(DiaryRange.MONTH,DiaryRange.YEAR)){
            ui.runOnUiThread{ui.activity.controller.select(day,range)};ui.waitForIdle()
            val cover=ui.onAllNodes(SemanticsMatcher.keyIsDefined(PeriodCoverMotionKey),useUnmergedTree=true).fetchSemanticsNodes().map{it.config[PeriodCoverMotionKey]}
            val artist=ui.onAllNodes(SemanticsMatcher.keyIsDefined(PeriodArtistMotionKey),useUnmergedTree=true).fetchSemanticsNodes().map{it.config[PeriodArtistMotionKey]}
            Assert.assertTrue("multiple covers should participate: $cover",cover.size>=2)
            Assert.assertEquals("cover keys must be unique",cover.size,cover.toSet().size)
            Assert.assertTrue("at least one artist should participate",artist.isNotEmpty())
            Assert.assertEquals("artist keys must be unique",artist.size,artist.toSet().size)
            Assert.assertEquals((0 until cover.size).toSet(),cover.toSet())
            Assert.assertEquals((0 until artist.size).toSet(),artist.toSet())
        }
    }
    @Test fun fastReturnRefreshesOnlyTheTargetLayoutSeed(){
        val c=ui.activity.controller
        ui.runOnUiThread{c.select(range=DiaryRange.MONTH)};ui.waitForIdle()
        val monthSeed=c.periodLayoutSeed(DiaryRange.MONTH)
        ui.runOnUiThread{c.select(range=DiaryRange.YEAR);c.select(range=DiaryRange.MONTH)};ui.waitForIdle()
        Assert.assertNotEquals("fast return should choose a new destination layout",monthSeed,c.periodLayoutSeed(DiaryRange.MONTH))
    }
    @Test fun swipeKeepsTheSameRandomChoiceUntilItCommits(){
        ui.onNodeWithTag("home-scroll").performTouchInput{down(androidx.compose.ui.geometry.Offset(width*.8f,height*.08f));moveTo(androidx.compose.ui.geometry.Offset(width*.55f,height*.08f))};ui.waitForIdle()
        val seed=ui.activity.controller.periodMotionSeed
        ui.onNodeWithTag("home-scroll").performTouchInput{moveTo(androidx.compose.ui.geometry.Offset(width*.2f,height*.08f));up()}
        ui.waitUntil(10000){ui.activity.controller.range==DiaryRange.WEEK};ui.waitForIdle()
        Assert.assertEquals("the matched artwork must not be reselected on release",seed,ui.activity.controller.periodMotionSeed)
    }
    @Test fun lookaheadSurvivesRapidNeighborSwitches(){
        ui.waitUntil(10000){runCatching{val keys=ui.onNodeWithTag("period-navigation").fetchSemanticsNode().config[PreparedPeriodsKey];keys.contains("WEEK")&&keys.contains("MONTH")}.getOrDefault(false)}
        ui.runOnUiThread{ui.activity.controller.select(range=DiaryRange.WEEK)};ui.waitForIdle()
        val keys=ui.onNodeWithTag("period-navigation").fetchSemanticsNode().config[PreparedPeriodsKey]
        Assert.assertTrue("previously prepared month should be retained: $keys",keys.contains("MONTH"))
        ui.onNodeWithTag("period-report").performTouchInput{swipe(androidx.compose.ui.geometry.Offset(width*.8f,height*.08f),androidx.compose.ui.geometry.Offset(width*.2f,height*.08f),160)}
        ui.waitUntil(10000){ui.activity.controller.range==DiaryRange.MONTH};ui.waitForIdle()
        ui.onAllNodesWithTag("period-hero-layout-month").onFirst().assertExists()
    }
    @Test fun previousHeroStaysMountedUntilTheNextPeriodHasTakenOver(){
        val c=ui.activity.controller
        // Keep both source and destination populated; month-boundary fixtures have only one slice.
        val recorded=java.time.LocalDate.of(2026,10,23)
        ui.runOnUiThread{c.select(day=recorded,range=DiaryRange.WEEK)};ui.waitForIdle()
        ui.waitUntil(10000){runCatching{ui.onAllNodesWithTag("period-navigation").fetchSemanticsNodes().firstOrNull()?.config?.get(PreparedPeriodsKey)?.contains("MONTH")==true}.getOrDefault(false)}
        ui.onAllNodesWithTag("period-hero-layout-week").onFirst().assertExists()
        ui.mainClock.autoAdvance=false
        try{
            ui.runOnUiThread{c.select(range=DiaryRange.MONTH)}
            ui.mainClock.advanceTimeByFrame();ui.waitForIdle();ui.mainClock.advanceTimeBy(220);ui.waitForIdle()
            ui.onAllNodesWithTag("period-hero-layout-week").onFirst().assertExists()
            ui.onAllNodesWithTag("period-hero-layout-month").onFirst().assertExists()
        }finally{ui.mainClock.autoAdvance=true}
        ui.waitForIdle()
    }
    @Test fun changingDateDiscardsPreparedPeriodsImmediately(){
        ui.waitUntil(10000){runCatching{ui.onNodeWithTag("period-navigation").fetchSemanticsNode().config[PreparedPeriodsKey].contains("WEEK")}.getOrDefault(false)}
        val oldFrom=bounds(ui.activity.controller.day,DiaryRange.WEEK).from
        ui.runOnIdle{ui.activity.controller.select(ui.activity.controller.day.plusDays(35),DiaryRange.DAY)}
        ui.waitForIdle()
        ui.onNodeWithTag("period-date").assertTextContains("${ui.activity.controller.day.monthValue}月${ui.activity.controller.day.dayOfMonth}日",substring=true)
        val prepared=ui.onNodeWithTag("period-navigation").fetchSemanticsNode().config[PreparedPeriodsKey]
        Assert.assertFalse("old-date preloads must not survive a date change: $prepared",prepared.contains("WEEK:$oldFrom"))
    }
    @Test fun revisedDatabaseInvalidatesPreparedReports(){
        ui.runOnUiThread{
            ui.activity.controller.preview(false)
            val prefs=ui.activity.getSharedPreferences("diary",0);val revision=prefs.getLong("data_revision",0)
            Assert.assertTrue(ui.activity.controller.isPreviewRevisionCurrent(revision))
            prefs.edit().putLong("data_revision",revision+1).commit()
            Assert.assertFalse(ui.activity.controller.isPreviewRevisionCurrent(revision))
            prefs.edit().putLong("data_revision",revision).commit()
        }
    }
    @Test fun slowDayToWeekDragKeepsProgressMonotonicUntilRelease(){
        ui.waitUntil(10000){runCatching{ui.onNodeWithTag("period-navigation").fetchSemanticsNode().config[PreparedPeriodsKey].contains("WEEK")}.getOrDefault(false)}
        val bounds=ui.onNodeWithTag("period-navigation").fetchSemanticsNode().boundsInRoot
        val w=bounds.width;val y=bounds.height*.08f
        ui.mainClock.autoAdvance=false
        try {
            ui.onNodeWithTag("period-navigation").performTouchInput{down(androidx.compose.ui.geometry.Offset(w*.9f,y))}
            var previous=0f
            for(progress in listOf(.1f,.25f,.45f,.65f,.8f,.87f)){
                ui.onNodeWithTag("period-navigation").performTouchInput{moveTo(androidx.compose.ui.geometry.Offset(w*(.9f-progress),y),150)}
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                ui.mainClock.advanceTimeBy(160);ui.waitForIdle()
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                val actual=ui.onNodeWithTag("period-navigation").fetchSemanticsNode().config[PeriodSeekFractionKey]
                Assert.assertTrue("slow drag must not rewind near landing: $previous -> $actual",actual>=previous-.01f)
                Assert.assertEquals("finger distance must directly map to timeline raw=${ui.activity.controller.periodSwipePosition} range=${ui.activity.controller.range} prepared=${ui.onNodeWithTag("period-navigation").fetchSemanticsNode().config[PreparedPeriodsKey]}",progress,actual,.04f)
                previous=actual
            }
            ui.onNodeWithTag("period-navigation").performTouchInput{up()}
            ui.mainClock.advanceTimeBy(48);ui.waitForIdle()
            val settling=ui.onNodeWithTag("period-navigation").fetchSemanticsNode().config[PeriodSeekFractionKey]
            Assert.assertTrue("release must retain intermediate animation frames: $settling",settling>.85f&&settling<.999f)
            Assert.assertEquals("selected page must not change until the seek has finished",DiaryRange.DAY,ui.activity.controller.range)
            Assert.assertNotNull("release must keep owning the same drag timeline",ui.activity.controller.periodSwipePosition)
            ui.mainClock.advanceTimeBy(96);ui.waitForIdle()
            Assert.assertEquals(DiaryRange.DAY,ui.activity.controller.range)
            val later=ui.onNodeWithTag("period-navigation").fetchSemanticsNode().config[PeriodSeekFractionKey]
            Assert.assertTrue("settling must advance continuously: $settling -> $later",later>settling&&later<1f)
            ui.mainClock.advanceTimeBy(1500);ui.waitForIdle()
            Assert.assertEquals(DiaryRange.WEEK,ui.activity.controller.range)
        } finally {ui.mainClock.autoAdvance=true}
    }

    @Test fun horizontalSwipeChangesPeriodButCarouselRemainsScrollable(){
        ui.onNodeWithTag("cover-carousel").performTouchInput{swipeLeft()};ui.waitForIdle()
        Assert.assertEquals(DiaryRange.DAY,ui.activity.controller.range)
        ui.onNodeWithTag("home-scroll").performTouchInput{down(androidx.compose.ui.geometry.Offset(width*.8f,height*.08f));moveTo(androidx.compose.ui.geometry.Offset(width*.55f,height*.08f))}
        ui.waitForIdle()
        val moving=ui.activity.controller.periodSwipePosition
        Assert.assertNotNull("unclaimed page drag should drive the glass",moving)
        Assert.assertTrue(moving!!>0f&&moving<1f)
        val glass=ui.onAllNodesWithTag("period-glass",useUnmergedTree=true).onFirst().fetchSemanticsNode().config[GlassPositionKey]
        Assert.assertTrue("glass thumb should follow the finger: $glass",glass>0f)
        val currentBounds=ui.onNodeWithTag("home-scroll").fetchSemanticsNode().boundsInRoot
        val nextBounds=ui.onNodeWithTag("period-report").fetchSemanticsNode().boundsInRoot
        Assert.assertTrue("current page should follow finger: $currentBounds",currentBounds.width > ui.activity.resources.displayMetrics.widthPixels*.65f && currentBounds.width < ui.activity.resources.displayMetrics.widthPixels*.85f)
        Assert.assertTrue("adjacent page should enter from right: $nextBounds",nextBounds.left > ui.activity.resources.displayMetrics.widthPixels*.65f && nextBounds.left < ui.activity.resources.displayMetrics.widthPixels*.85f)
        ui.onNodeWithTag("home-scroll").performTouchInput{moveTo(androidx.compose.ui.geometry.Offset(width*.2f,height*.08f));up()}
        ui.waitUntil(10000){ui.activity.controller.range==DiaryRange.WEEK};ui.waitForIdle()
        ui.onNodeWithTag("period-hero-pager").performTouchInput{swipeLeft()};ui.waitForIdle()
        Assert.assertEquals("hero swipe must not switch periods",DiaryRange.WEEK,ui.activity.controller.range)
        ui.onNodeWithTag("period-report").performTouchInput{swipe(androidx.compose.ui.geometry.Offset(width*.8f,height*.08f),androidx.compose.ui.geometry.Offset(width*.2f,height*.08f),400)}
        ui.waitUntil(10000){ui.activity.controller.range==DiaryRange.MONTH};ui.waitForIdle()
    }
    @Test fun shortDragCancelsAndFastFlingCommits(){
        ui.onNodeWithTag("home-scroll").performTouchInput{val a=androidx.compose.ui.geometry.Offset(width*.8f,height*.08f);down(a);moveTo(a.copy(x=width*.72f),300);up()};ui.waitForIdle()
        Assert.assertEquals(DiaryRange.DAY,ui.activity.controller.range)
        Assert.assertNull(ui.activity.controller.periodSwipePosition)
        ui.onNodeWithTag("home-scroll").performTouchInput{val a=androidx.compose.ui.geometry.Offset(width*.8f,height*.08f);down(a);moveTo(a.copy(x=width*.65f),35);up()}
        ui.waitUntil(10000){ui.activity.controller.range==DiaryRange.WEEK};ui.waitForIdle()
    }
    @Test fun yearAndSummaryFollowTheSameDrag(){
        ui.runOnUiThread{ui.activity.controller.select(range=DiaryRange.YEAR)};ui.waitForIdle()
        ui.onNodeWithTag("period-report").performTouchInput{down(androidx.compose.ui.geometry.Offset(width*.8f,height*.08f));moveTo(androidx.compose.ui.geometry.Offset(width*.55f,height*.08f))};ui.waitForIdle()
        Assert.assertFalse(ui.activity.controller.summary)
        val summary=ui.onNodeWithTag("summary-scroll").fetchSemanticsNode().boundsInRoot
        Assert.assertTrue("summary should stay in place during the drag: $summary",summary.left<5f&&summary.width>20f)
        ui.onNodeWithTag("period-report").performTouchInput{moveTo(androidx.compose.ui.geometry.Offset(width*.2f,height*.08f));up()}
        ui.waitUntil(10000){ui.activity.controller.summary};ui.waitUntil(10000){!ui.activity.controller.summaryLoading};ui.waitForIdle()
        shot("summary-settled")
        ui.onNodeWithTag("summary-scroll").performTouchInput{down(androidx.compose.ui.geometry.Offset(width*.2f,height*.08f));moveTo(androidx.compose.ui.geometry.Offset(width*.5f,height*.08f),200)};ui.waitForIdle()
        Assert.assertNotNull("summary should track the return swipe",ui.activity.controller.periodSwipePosition)
        ui.onNodeWithTag("summary-scroll").performTouchInput{moveTo(androidx.compose.ui.geometry.Offset(width*.8f,height*.08f),200);up()}
        shot("summary-after-swipe")
        ui.waitUntil(10000){!ui.activity.controller.summary};ui.waitForIdle();Assert.assertEquals(DiaryRange.YEAR,ui.activity.controller.range)
    }
    @Test fun oneContinuousSwipeReturnsFromSummaryToYear(){
        ui.runOnUiThread{ui.activity.controller.select(range=DiaryRange.YEAR);ui.activity.controller.preloadSummary()};ui.waitForIdle()
        ui.waitUntil(10000){ui.activity.controller.summaryFacts.report.history.isNotEmpty()}
        ui.runOnUiThread{ui.activity.controller.openSummary()};ui.waitForIdle();ui.waitUntil(10000){!ui.activity.controller.summaryLoading};ui.mainClock.advanceTimeBy(1000);ui.waitForIdle()
        ui.onNodeWithTag("summary-scroll").performTouchInput{swipe(androidx.compose.ui.geometry.Offset(width*.2f,height*.08f),androidx.compose.ui.geometry.Offset(width*.8f,height*.08f),400)}
        ui.waitUntil(10000){!ui.activity.controller.summary};ui.waitForIdle();Assert.assertEquals(DiaryRange.YEAR,ui.activity.controller.range)
    }
    @Test fun summaryIsPreparedBeforeDraggingFromYearAndMovesContinuously(){
        ui.runOnUiThread{ui.activity.controller.select(range=DiaryRange.YEAR);ui.activity.controller.preloadSummary()};ui.waitForIdle()
        ui.waitUntil(10000){ui.activity.controller.summaryFacts.report.history.isNotEmpty()}
        ui.onNodeWithTag("period-report").performTouchInput{down(androidx.compose.ui.geometry.Offset(width*.8f,height*.08f));moveTo(androidx.compose.ui.geometry.Offset(width*.65f,height*.08f))};ui.waitForIdle()
        val first=ui.activity.controller.periodSwipePosition!!
        val firstPage=ui.onNodeWithTag("summary-scroll").fetchSemanticsNode().boundsInRoot.left
        ui.onNodeWithTag("favorite-timeline").assertExists();shot("summary-drag-early")
        ui.onNodeWithTag("period-report").performTouchInput{moveTo(androidx.compose.ui.geometry.Offset(width*.4f,height*.08f))};ui.waitForIdle()
        val second=ui.activity.controller.periodSwipePosition!!
        val secondPage=ui.onNodeWithTag("summary-scroll").fetchSemanticsNode().boundsInRoot.left
        shot("summary-drag-later")
        Assert.assertTrue("transition progress should follow the finger: $first -> $second",second>first+.1f)
        Assert.assertTrue("the summary page itself must stay still: $firstPage -> $secondPage",kotlin.math.abs(secondPage-firstPage)<1f)
        ui.onNodeWithTag("period-report").performTouchInput{cancel()};ui.waitForIdle()
        Assert.assertFalse(ui.activity.controller.summary)
    }
    @Test fun everyPeriodHeroPublishesItsDisplayedCoverPalette(){
        val day=java.time.LocalDate.of(2024,5,10)
        for(range in listOf(DiaryRange.WEEK,DiaryRange.MONTH,DiaryRange.YEAR)){
            val id="period-tint-${range.name}";val bitmap=android.graphics.Bitmap.createBitmap(160,160,android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.rgb(30+range.ordinal*45,65,150))
            java.io.File(ui.activity.filesDir,"art/${ArtworkKeys.hash(id)}.jpg").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG,95,it)};bitmap.recycle()
            ui.runOnUiThread{ui.activity.controller.images.warmCached(listOf(id),720)}
            ui.waitUntil(10000){ui.activity.controller.images.peek(id,720)!=null}
            val start=day.atTime(10,0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            val play=Play(id,"visit-$id","测试歌曲","测试歌手","测试专辑",start,start+60000,60000,240000,java.time.ZoneId.systemDefault().id,effective=1)
            val album=AlbumStat("测试专辑","测试歌手",listOf(play),play.ms)
            val slice=PeriodHeroSlice(day,day,listOf(album),listOf(ArtistStat("测试歌手",play.ms,1)),play.ms)
            val received=java.util.concurrent.atomic.AtomicInteger()
            ui.runOnUiThread{ui.activity.controller.select(day,range);ui.activity.setContent{CompositionLocalProvider(LocalInspectionMode provides true){androidx.compose.material3.MaterialTheme{
                val pager=androidx.compose.foundation.pager.rememberPagerState(pageCount={1})
                PeriodHeroBrowser(ui.activity.controller,listOf(slice),range,pager,{received.incrementAndGet()},{_,_->})
            }}}};ui.waitForIdle()
            try{ui.waitUntil(10000){received.get()>0}}catch(e:Exception){throw AssertionError("palette not published for $range",e)}
        }
    }
    @Test fun cancelPointerReturnsToTheOriginalPeriod(){
        ui.onNodeWithTag("home-scroll").performTouchInput{down(androidx.compose.ui.geometry.Offset(width*.8f,height*.08f));moveTo(androidx.compose.ui.geometry.Offset(width*.5f,height*.08f));cancel()};ui.waitForIdle()
        Assert.assertEquals(DiaryRange.DAY,ui.activity.controller.range);Assert.assertNull(ui.activity.controller.periodSwipePosition)
    }
    @Test fun clockPreferenceFormatsNoonAndMidnight(){
        val zone=java.time.ZoneId.of("Asia/Shanghai");val midnight=java.time.LocalDate.of(2026,10,27).atStartOfDay(zone).toInstant().toEpochMilli();val noon=midnight+12*60*60*1000
        Assert.assertEquals("00:00",diaryClock(midnight,false,zone));Assert.assertEquals("上午 12:00",diaryClock(midnight,true,zone));Assert.assertEquals("下午 12:00",diaryClock(noon,true,zone))
        ui.runOnUiThread{ui.activity.controller.use12Hour(true)};Assert.assertTrue(ui.activity.controller.use12Hour);ui.runOnUiThread{ui.activity.controller.use12Hour(false)}
    }
    @Test fun shortPlaybackFadesVerticallyIntoItsBackground(){
        ui.runOnUiThread{ui.activity.setContent {
            Box(Modifier.size(100.dp).background(Color.White).testTag("fade-example")) {
                Box(Modifier.fillMaxSize().playbackFade(.4f).background(Color.Red))
            }
        }}
        ui.waitForIdle()
        val image=ui.onNodeWithTag("fade-example").captureToImage().asAndroidBitmap()
        val top=image.getPixel(image.width/2,image.height/10)
        val bottom=image.getPixel(image.width/2,image.height*9/10)
        Assert.assertTrue("top should preserve original color",android.graphics.Color.green(top)<30)
        Assert.assertTrue("bottom should show the background",android.graphics.Color.green(bottom)>140)
        System.getProperty("diary.preview.dir")?.let{dir->java.io.File(dir,"playback-fade-test.png").also{it.parentFile.mkdirs()}.outputStream().use{image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}
    }
    @Test fun coverOpensAndReturnsToReport(){shot("app-home");ui.onNodeWithTag("cover-caption").performClick();ui.onNodeWithText("详细记录").assertExists();shot("app-detail");ui.onNodeWithContentDescription("返回日记").performClick();ui.onNodeWithText("听歌日记").assertExists();ui.onNodeWithText("位歌手").assertExists()}
    @Test fun repeatedPeriodTapsDoNotCancelThePendingRead(){
        val activity=ui.activity;val c=activity.controller;val database=DiaryDatabase.get(activity)
        val locked=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val holder=Thread{synchronized(database){locked.countDown();release.await(20,java.util.concurrent.TimeUnit.SECONDS)}};holder.start()
        Assert.assertTrue(locked.await(5,java.util.concurrent.TimeUnit.SECONDS))
        try{
            ui.runOnUiThread{
                c.preview(false);c.select(java.time.LocalDate.of(2001,1,8),DiaryRange.YEAR)
                val field=DiaryController::class.java.getDeclaredField("selectionSerial").apply{isAccessible=true}
                val serial=field.getInt(c)
                repeat(5){c.select(range=DiaryRange.YEAR)}
                Assert.assertEquals("repeat taps must not restart the pending query",serial,field.getInt(c))
            }
        }finally{release.countDown();holder.join(5000)}
    }
    @Test fun allSummaryRevealsAValidReadWhileNewRecordsAdvanceTheRevision(){
        val activity=ui.activity;val c=activity.controller;val db=DiaryDatabase.get(activity)
        val day=java.time.LocalDate.of(2026,10,23)
        val at=day.atTime(12,0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        ui.runOnUiThread{
            c.stop()
            db.ingest(org.json.JSONObject().put("sourceId","summary-live-update")
                .put("tracks",org.json.JSONArray().put(org.json.JSONObject().put("id","live-song").put("title","Song").put("artist","Artist").put("album","Album").put("duration",240000)))
                .put("spans",org.json.JSONArray().put(org.json.JSONObject().put("id",1).put("track","live-song").put("visit","live-visit").put("day",day.toString()).put("zone",java.time.ZoneId.systemDefault().id).put("start",at).put("end",at+180000).put("ms",180000))))
            c.preview(false);c.select(day,DiaryRange.DAY);c.openSummary()
            // The read is asynchronous. Simulate another append after it started, before publication.
            val prefs=activity.getSharedPreferences("diary",0)
            prefs.edit().putLong("data_revision",prefs.getLong("data_revision",0)+1).commit()
        }
        ui.waitUntil(10000){org.robolectric.shadows.ShadowLooper.idleMainLooper();c.summaryFacts.report.history.isNotEmpty()&&!c.summaryLoading}
        Assert.assertEquals("live-song",c.summaryFacts.report.history.first().id)
    }
    @Test fun allSummarySnapshotIsVisibleWhileDatabaseIsLocked(){
        val activity=ui.activity;val c=activity.controller;val database=DiaryDatabase.get(activity)
        val facts=SummaryFacts(periodDemoReport(java.time.LocalDate.of(2026,10,23),DiaryRange.MONTH))
        val revision=activity.getSharedPreferences("diary",0).getLong("data_revision",0)
        Assert.assertTrue(SummarySnapshotCache.write(activity,c.dayPolicy,revision,facts))
        val locked=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val holder=Thread{synchronized(database){locked.countDown();release.await(20,java.util.concurrent.TimeUnit.SECONDS)}};holder.start()
        Assert.assertTrue(locked.await(5,java.util.concurrent.TimeUnit.SECONDS))
        try{
            ui.runOnUiThread{c.preview(false);c.openSummary()}
            ui.waitUntil(10000){org.robolectric.shadows.ShadowLooper.idleMainLooper();c.summaryFacts.report.history.isNotEmpty()&&!c.summaryLoading}
            Assert.assertEquals(facts.report.ms,c.summaryFacts.report.ms)
            Assert.assertEquals(facts.monthlyFavorites,c.summaryFacts.monthlyFavorites)
        }finally{release.countDown();holder.join(5000)}
    }
    @Test fun cachedRecordsAreVisibleWhileAuthoritativeDatabaseIsLocked(){
        val activity=ui.activity;val controller=activity.controller
        val database=DiaryDatabase.get(activity)
        val date=java.time.LocalDate.of(2025,1,12)
        val cached=demoReport(date).copy(lastSync=123456789L)
        val revision=activity.getSharedPreferences("diary",0).getLong("data_revision",0)
        DailyReportCache.write(activity,date,controller.dayPolicy,cached,revision)
        Assert.assertEquals(cached.plays,DailyReportCache.read(activity,date,controller.dayPolicy)?.plays)
        val locked=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val holder=Thread{synchronized(database){locked.countDown();release.await(20,java.util.concurrent.TimeUnit.SECONDS)}};holder.start()
        Assert.assertTrue(locked.await(5,java.util.concurrent.TimeUnit.SECONDS))
        try{
            ui.runOnUiThread{controller.preview(false);controller.select(date,DiaryRange.DAY)}
            ui.waitUntil(10000){org.robolectric.shadows.ShadowLooper.idleMainLooper();controller.showingCached}
            Assert.assertFalse(controller.loading)
            Assert.assertEquals(cached.plays,controller.report.plays)
            Assert.assertEquals(cached.lastSync,controller.report.lastSync)
        }finally{release.countDown();holder.join(5000)}
    }
    @Test fun startupCacheDoesNotWaitForTheDatabaseLock(){
        val day=java.time.LocalDate.of(2025,1,12);val policy=ListeningDayPolicy();val report=demoReport(day)
        val c=ui.activity;val prefs=c.getSharedPreferences("diary",0);val revision=prefs.getLong("data_revision",0)
        DailyReportCache.write(c,day,policy,report,revision)
        val database=DiaryDatabase.get(c);val locked=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val holder=Thread{ synchronized(database){locked.countDown();release.await(10,java.util.concurrent.TimeUnit.SECONDS)} };holder.start()
        Assert.assertTrue(locked.await(5,java.util.concurrent.TimeUnit.SECONDS))
        try{Assert.assertEquals(report.plays,DailyReportCache.read(c,day,policy)?.plays)}finally{release.countDown();holder.join(5000)}
    }
    @Test fun periodCalendarFollowsDarkTheme(){
        ui.runOnUiThread{ui.activity.controller.theme(DiaryThemeChoice.DARK);ui.activity.controller.select(java.time.LocalDate.of(2024,5,10),DiaryRange.MONTH)};ui.waitForIdle()
        ui.onNodeWithTag("period-date").performClick();ui.waitForIdle();ui.onNodeWithTag("period-calendar-months").assertExists();shot("app-picker-month-dark")
    }
    @Test fun eachPeriodUsesItsOwnDatePickerAndKeepsItsRange(){
        val day=java.time.LocalDate.of(2024,5,10)
        for(range in listOf(DiaryRange.WEEK,DiaryRange.MONTH,DiaryRange.YEAR)){
            ui.runOnUiThread{ui.activity.controller.select(day,range)};ui.waitForIdle()
            ui.onNodeWithTag("period-date").performClick();ui.waitForIdle()
            ui.onNodeWithTag("period-calendar").assertExists();shot("app-picker-${range.name.lowercase()}")
            when(range){
                DiaryRange.WEEK->{ui.onNodeWithTag("calendar-weeks").assertExists();ui.onNodeWithTag("calendar-week:2024-05-06").performClick()}
                DiaryRange.MONTH->{ui.onNodeWithTag("period-calendar-months").assertExists();ui.onNodeWithTag("period-calendar-month:6").performClick()}
                else->{ui.onNodeWithTag("period-calendar-years").assertExists();ui.onNodeWithTag("period-calendar-year:2025").performClick()}
            }
            ui.onNodeWithTag("period-calendar-confirm").performClick();ui.waitForIdle();Assert.assertEquals(range,ui.activity.controller.range)
            when(range){DiaryRange.WEEK->Assert.assertEquals(java.time.LocalDate.of(2024,5,6),ui.activity.controller.day);DiaryRange.MONTH->Assert.assertEquals(6,ui.activity.controller.day.monthValue);else->Assert.assertEquals(2025,ui.activity.controller.day.year)}
        }
    }
    @Test fun periodSwitchRetainsPreviousSnapshotWhileNextReportLoads(){
        ui.runOnUiThread{
            val instance=DiaryDatabase::class.java.getDeclaredField("instance");instance.isAccessible=true;instance.set(null,null)
            val now=System.currentTimeMillis();val zone=java.time.ZoneId.systemDefault();val day=java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val tracks=org.json.JSONArray().put(org.json.JSONObject().put("id","transition-a").put("title","A").put("artist","Artist").put("album","Album").put("duration",180000))
            val spans=org.json.JSONArray().put(org.json.JSONObject().put("id",1).put("visit","transition-visit").put("track","transition-a").put("day",day.toString()).put("zone",zone.id).put("start",now-60000).put("end",now).put("ms",60000))
            DiaryDatabase.get(ui.activity).ingest(org.json.JSONObject().put("sourceId","transition-test").put("tracks",tracks).put("spans",spans))
            ui.activity.controller.preview(false);ui.activity.controller.select(day,DiaryRange.DAY)
        }
        ui.waitUntil(10000){!ui.activity.controller.loading&&ui.activity.controller.report.history.isNotEmpty()};ui.waitForIdle()
        ui.runOnUiThread{
            ui.activity.controller.preparedPeriods.clear()
            val old=ui.activity.controller.report
            ui.activity.controller.select(range=DiaryRange.WEEK)
            Assert.assertTrue(ui.activity.controller.loading)
            Assert.assertEquals(old,ui.activity.controller.periodTransitionReport)
            ui.activity.controller.select(range=DiaryRange.MONTH)
            Assert.assertEquals(old,ui.activity.controller.periodTransitionReport)
        }
        ui.waitUntil(10000){!ui.activity.controller.loading};ui.waitForIdle()
        Assert.assertEquals(DiaryRange.MONTH,ui.activity.controller.range)
        Assert.assertEquals(bounds(ui.activity.controller.day,DiaryRange.MONTH).from,ui.activity.controller.report.from)
    }
    @Test fun periodAndSummaryTransitionsSurviveIntermediateFrames(){
        ui.mainClock.autoAdvance=false
        ui.runOnUiThread{ui.activity.controller.select(range=DiaryRange.WEEK)}
        ui.mainClock.advanceTimeBy(16);ui.waitForIdle();ui.mainClock.advanceTimeBy(200);ui.waitForIdle();shot("app-period-transition-mid")
        Assert.assertTrue(ui.onAllNodesWithTag("stat-plays").fetchSemanticsNodes().isNotEmpty())
        ui.mainClock.advanceTimeBy(1200);ui.waitForIdle()
        ui.runOnUiThread{ui.activity.controller.openSummary()}
        ui.mainClock.advanceTimeBy(160);ui.waitForIdle();shot("app-summary-transition-mid")
        ui.mainClock.advanceTimeBy(1200);ui.waitForIdle()
        ui.runOnUiThread{ui.activity.controller.select(range=DiaryRange.DAY)}
        ui.mainClock.advanceTimeBy(1200);ui.mainClock.autoAdvance=true;ui.waitForIdle()
        ui.onNodeWithTag("home-scroll").assertExists()
    }
    @Test fun periodSwitchCanReverseDuringAnimation(){ui.mainClock.autoAdvance=false;ui.onNode(hasText("月") and hasAnyAncestor(hasClickAction()),useUnmergedTree=true).performClick();ui.mainClock.advanceTimeBy(80);ui.onAllNodes(hasText("日") and hasAnyAncestor(hasClickAction()),useUnmergedTree=true).onFirst().assertIsDisplayed().performClick();ui.mainClock.autoAdvance=true;ui.waitForIdle();Assert.assertEquals(DiaryRange.DAY,ui.activity.controller.range)}
    @Test fun liquidPeriodDragCommitsNearestAndCancellationKeepsPeriod(){
        ui.onNodeWithTag("period-glass").performTouchInput{down(androidx.compose.ui.geometry.Offset(width*.12f,height/2f));moveTo(androidx.compose.ui.geometry.Offset(width*.50f,height/2f),300);up()}
        ui.waitForIdle();Assert.assertEquals(DiaryRange.MONTH,ui.activity.controller.range)
        ui.onNodeWithTag("period-glass").performTouchInput{down(androidx.compose.ui.geometry.Offset(width*.50f,height/2f));moveTo(androidx.compose.ui.geometry.Offset(width*.90f,height/2f),300);cancel()}
        ui.waitForIdle();Assert.assertEquals(DiaryRange.MONTH,ui.activity.controller.range)
    }
    @Test fun liquidPeriodDoesNotStealVerticalScroll(){
        ui.onNodeWithTag("period-glass").performTouchInput{down(center);moveBy(androidx.compose.ui.geometry.Offset(0f,-180f),400);up()}
        ui.waitForIdle();Assert.assertEquals(DiaryRange.DAY,ui.activity.controller.range)
        ui.onNodeWithTag("home-scroll").assert(SemanticsMatcher("vertical scroll progressed"){it.config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange].value()>0f})
    }
    @Test fun liquidTextSourceAndThumbHaveSameVerticalBounds(){
        val text=ui.onNodeWithTag("period-refraction-source",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        val thumb=ui.onNodeWithTag("period-thumb",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        Assert.assertEquals(text.center.y,thumb.center.y,.75f);Assert.assertEquals(text.height,thumb.height,.75f)
    }
    @Test fun settingsSeparatesExportsAndHidesDemo(){ui.onNodeWithContentDescription("设置").performClick();ui.onNodeWithText("让音乐留下痕迹").assertExists();ui.onNodeWithText("导入插件或日记 JSON 备份").assertExists();ui.onNodeWithText("交互演示").assertDoesNotExist()}
    @Test fun themeSwitchRetainsReportAndProducesBothPreviews(){
        ui.runOnUiThread{ui.activity.controller.theme(DiaryThemeChoice.LIGHT)};ui.waitForIdle();ui.onNodeWithText("听歌日记").assertIsDisplayed();shot("app-light-demo")
        ui.runOnUiThread{ui.activity.controller.theme(DiaryThemeChoice.DARK)};ui.waitForIdle();ui.onNodeWithText("听歌日记").assertIsDisplayed();shot("app-dark-demo")
        Assert.assertEquals(24,ui.activity.controller.report.history.size)
    }
    @Test fun sessionFootprintsCanCollapseAndExpand(){
        openHistory()
        val session=ListeningSessions.group(ui.activity.controller.report.segments).last()
        fun time(ms:Long)=java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
        val title="${time(session.start)} — ${time(session.end)}"
        val tag="song:row:${session.key}:${session.songs.last().visit}"
        ui.onNodeWithText(title).performScrollTo();ui.onNodeWithTag(tag).assertExists()
        ui.onNodeWithText(title).performClick();ui.onNodeWithTag(tag).assertDoesNotExist()
        ui.onNodeWithText(title).performClick();ui.onNodeWithTag(tag).assertExists()
    }
    @Test fun dayHistoryArrowUsesHistoryNavigationAndKeepsScrollOnReturn(){
        ui.onNodeWithTag("home-scroll").performScrollToNode(hasTestTag("day-history-link"));ui.waitForIdle()
        ui.onNodeWithContentDescription("听歌历史").assertExists();shot("app-day-history-entry")
        // Physical tap on the arrow end must select the nested history action, not its parent rhythm card.
        ui.onNodeWithTag("day-history-link").performTouchInput{click(androidx.compose.ui.geometry.Offset(width-3f,height/2f))};ui.waitForIdle()
        ui.onNodeWithTag("history-scroll").assertExists()
        ui.onNodeWithContentDescription("返回日记").performClick();ui.waitForIdle()
        ui.onNodeWithTag("day-history-link").assertIsDisplayed()
    }
    @Test fun recentCoversSwipeBothWaysAndOpenDetailWithoutOpeningParentCard(){
        ui.onNodeWithTag("home-scroll").performScrollToNode(hasTestTag("day-history-link"));ui.waitForIdle()
        fun position()=ui.onNodeWithTag("recent-listening-strip",useUnmergedTree=true).fetchSemanticsNode().config[RecentPositionKey]
        val before=position()
        ui.onNodeWithTag("recent-listening-strip",useUnmergedTree=true).performTouchInput{swipeLeft(durationMillis=450)};ui.waitForIdle()
        val moved=position();Assert.assertTrue(moved>before);shot("app-recent-scrolled")
        ui.onNodeWithTag("history-scroll").assertDoesNotExist();ui.onNodeWithText("聆听场次").assertDoesNotExist()
        ui.onNodeWithTag("recent-listening-strip",useUnmergedTree=true).performTouchInput{swipeRight(durationMillis=450)};ui.waitForIdle();Assert.assertTrue(position()<moved)
        ui.onNodeWithTag("recent-listening-strip",useUnmergedTree=true).performScrollToIndex(0)
        val first=CoverCarousel.stops(ui.activity.controller.report.history,ui.activity.controller.report.segments).first()
        ui.onNodeWithTag("recent-cover:${first.key}").performTouchInput{click(center)};ui.waitForIdle()
        ui.onNodeWithText("详细记录").assertExists();ui.onNodeWithContentDescription("返回日记").performClick();ui.waitForIdle()
        ui.onNodeWithTag("recent-listening-strip",useUnmergedTree=true).assertIsDisplayed();Assert.assertEquals(24,ui.activity.controller.report.history.size)
    }
    @Test fun recentPlayingBadgeUsesCollectorIdentityAndDisappearsOnPause(){
        ui.runOnUiThread{
            val instance=DiaryDatabase::class.java.getDeclaredField("instance");instance.isAccessible=true;instance.set(null,null)
            val now=System.currentTimeMillis();val zone=java.time.ZoneId.systemDefault();val day=java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val tracks=org.json.JSONArray().put(org.json.JSONObject().put("id","1781888430").put("title","Mermaid").put("artist","由薫").put("album","示例专辑").put("duration",240000))
            val spans=org.json.JSONArray().put(org.json.JSONObject().put("id",1).put("visit","live-recent").put("track","1781888430").put("day",day.toString()).put("zone",zone.id).put("start",now-60000).put("end",now).put("ms",60000))
            DiaryDatabase.get(ui.activity).ingest(org.json.JSONObject().put("sourceId","recent-test").put("tracks",tracks).put("spans",spans).put("nowTrackId","1781888430").put("nowTitle","Mermaid").put("status","正在记录"))
            ui.activity.controller.preview(false);ui.activity.controller.select(day,DiaryRange.DAY)
        }
        ui.waitUntil(10000){ui.activity.controller.report.nowTrackId=="1781888430"};ui.waitForIdle()
        ui.onNodeWithTag("home-scroll").performScrollToNode(hasTestTag("day-history-link"));ui.onNodeWithContentDescription("正在播放",useUnmergedTree=true).assertIsDisplayed();shot("app-recent-playing")
        val liveStop=CoverCarousel.stops(ui.activity.controller.report.history,ui.activity.controller.report.segments).first()
        ui.onNodeWithTag("recent-cover:${liveStop.key}").assert(SemanticsMatcher.expectValue(PlaybackOpacityKey,1f))
        val total=ui.activity.controller.report.ms
        ui.runOnUiThread{
            DiaryDatabase.get(ui.activity).ingest(org.json.JSONObject().put("sourceId","recent-test").put("nowTrackId","1781888430").put("status","已暂停 · 记录已保存"))
            ui.activity.contentResolver.notifyChange(BridgeIdentity.URI,null)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(700))
        }
        ui.waitUntil(10000){ui.activity.controller.report.recordState.startsWith("已暂停")};ui.waitForIdle()
        ui.onNodeWithContentDescription("正在播放",useUnmergedTree=true).assertDoesNotExist();Assert.assertEquals(total,ui.activity.controller.report.ms)
        val faded=ui.onNodeWithTag("recent-cover:${liveStop.key}").fetchSemanticsNode().config[PlaybackOpacityKey]
        Assert.assertTrue("paused short playback should be visibly faded: $faded",faded < .8f)
    }
    @Test fun artistPhotosUseOneGlobalSetting(){
        ui.onNodeWithContentDescription("设置").performClick();ui.onNodeWithTag("global-artist-photos").performScrollTo().performClick()
        Assert.assertFalse(ui.activity.controller.artistPhotosEnabled);Assert.assertEquals(24,ui.activity.controller.report.history.size)
    }
    @Test fun carouselAutomaticallyStopsThenAdvancesThroughHistory(){
        resetCarousel()
        shot("app-color-start")
        ui.onNodeWithTag("cover-carousel").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"demo-23"))
        ui.mainClock.advanceTimeBy(1500);ui.onNodeWithTag("cover-carousel").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"demo-23"))
        ui.mainClock.advanceTimeBy(2000);ui.onNodeWithTag("cover-carousel").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"demo-22"))
        ui.mainClock.advanceTimeBy(2400);ui.onNodeWithTag("cover-carousel").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"demo-21"));shot("app-color-next")
        ui.mainClock.autoAdvance=true
    }
    @Test fun carouselPausesWhileSettingsCoversTheReport(){
        resetCarousel()
        ui.onNodeWithContentDescription("设置").performClick();ui.mainClock.advanceTimeBy(10000)
        ui.onNodeWithTag("cover-carousel").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"demo-23"))
        ui.mainClock.autoAdvance=true
    }
    @Test fun carouselGivesTouchTimeBeforeResuming(){
        resetCarousel()
        ui.onNodeWithTag("cover-carousel").performTouchInput{down(androidx.compose.ui.geometry.Offset(1f,height-1f));advanceEventTime(100);up()}
        ui.mainClock.advanceTimeByFrame();ui.waitForIdle()
        ui.mainClock.advanceTimeBy(4000)
        ui.onNodeWithTag("cover-carousel").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"demo-23"))
        ui.mainClock.advanceTimeBy(4500)
        ui.onNodeWithTag("cover-carousel").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"demo-22"))
        ui.mainClock.autoAdvance=true
    }
    @Test fun scrollingSortingAndReturningKeepContext(){
        openHistory();shot("app-timeline")
        val session=ListeningSessions.group(ui.activity.controller.report.segments).last();val last=session.songs.last()
        ui.onNodeWithTag("song:row:${session.key}:${last.visit}").performScrollTo().performClick()
        ui.onNodeWithContentDescription("返回日记").performClick()
        ui.onNodeWithTag("song:row:${session.key}:${last.visit}").assertExists()
        ui.onNodeWithTag("history-scroll").performScrollToIndex(0);ui.onNodeWithTag("sort-menu").assertDoesNotExist();ui.onNodeWithContentDescription("前一天").assertDoesNotExist();shot("app-history-clean")
    }
    @Test fun historySearchFiltersSongsAndArtists(){openHistory();ui.onNodeWithTag("history-search").assertDoesNotExist();ui.onNodeWithTag("history-scroll").performScrollToIndex(0);ui.onNodeWithTag("history-scroll").performTouchInput{swipeDown(durationMillis=500)};ui.waitForIdle();ui.onNodeWithTag("history-search").performTextInput("Vaundy");ui.onAllNodesWithText("踊り子").onFirst().assertExists();ui.onNodeWithTag("history-search").performTextClearance();ui.onNodeWithTag("history-search").performTextInput("no-such-song");ui.onNodeWithText("没有找到歌曲或歌手").assertExists()}
    @Test fun weeklyMonthlyYearlyReportsAndIndependentSummaryAreNavigable(){
        val day=java.time.LocalDate.of(2026,10,23)
        for(range in listOf(DiaryRange.WEEK,DiaryRange.MONTH,DiaryRange.YEAR)){
            ui.runOnUiThread{ui.activity.controller.select(day,range)};ui.waitForIdle()
            ui.onNodeWithTag("period-report").performScrollToNode(hasTestTag("period-history-link"));ui.onNodeWithTag("period-history-link").performClick();ui.waitForIdle()
            ui.onNodeWithTag("period-history-page").assertExists();ui.onNodeWithText("听歌历史").assertExists()
            ui.onNodeWithText("听歌日记").performClick();ui.waitForIdle();ui.onNodeWithTag("period-report").assertExists()
        }
        openSummary();ui.onNodeWithTag("favorite-timeline").assertExists();shot("preview54-all");ui.onNodeWithText("音乐画像").assertDoesNotExist();ui.onNodeWithTag("summary-section-1").assertDoesNotExist()
    }
    @Test fun summaryProfileShowsLifetimeAndArtistAlbumDetailsReturnToContext(){
        openSummary();val facts=ui.activity.controller.summaryFacts;val name=facts.artists.first().name
        ui.onNodeWithTag("summary-scroll").performScrollToNode(hasTestTag("summary-link:$name"));ui.onNodeWithTag("summary-link:$name").performClick();ui.waitForIdle()
        ui.onNodeWithTag("music-group-detail").assertExists();ui.onNodeWithText(name).assertExists();ui.onNodeWithContentDescription("返回日记").performClick();ui.waitForIdle()
        ui.onNodeWithTag("summary-scroll").performScrollToNode(hasTestTag("summary-link:最常回听的专辑"));ui.onNodeWithTag("summary-link:最常回听的专辑").performClick();ui.waitForIdle();ui.onNodeWithTag("music-group-detail").assertExists();ui.onNodeWithContentDescription("返回日记").performClick();ui.waitForIdle();ui.onNodeWithTag("summary-scroll").assertExists()
    }
    @Test fun lifetimePageShowsRingsRankingsAndThreeTrailModes(){
        openSummary()
        ui.onNodeWithTag("summary-scroll").performScrollToNode(hasTestTag("all-history-trail"));ui.waitUntil(10000){org.robolectric.shadows.ShadowLooper.idleMainLooper();ui.onAllNodesWithTag("all-year-area",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()};shot("preview130-all-rings")
        ui.onNodeWithText("查看详情").performClick();ui.waitForIdle()
        ui.onNodeWithText("偏好轨迹").assertExists()
        ui.onNodeWithText("每个月听得最多的歌曲").assertExists()
        ui.onNodeWithText("专辑").performClick();ui.waitForIdle();ui.onNodeWithText("每个月听得最多的专辑").assertExists()
        ui.onNodeWithText("歌手").performClick();ui.waitForIdle();ui.onNodeWithText("每个月听得最多的歌手").assertExists()
        ui.onNodeWithText("听歌日记").performClick();ui.waitForIdle()
        ui.onNodeWithTag("summary-scroll").performScrollToNode(hasTestTag("all-favorites"));ui.onNodeWithTag("all-favorites").assertExists()
        ui.onNodeWithTag("summary-scroll").performScrollToNode(hasTestTag("all-artists"));ui.onNodeWithTag("all-artists").assertExists()
        ui.onNodeWithTag("summary-scroll").performScrollToNode(hasTestTag("all-returning-more"));ui.onNodeWithTag("all-returning-more").performClick();ui.waitForIdle();ui.onNodeWithText("按听过的月份和播放次数排序").assertExists()
    }
    @Test fun preferenceChartSwitchesDimensionsAndGranularity(){
        openSummary();ui.onNodeWithTag("summary-scroll").performScrollToNode(hasTestTag("all-history-trail"));ui.waitForIdle()
        ui.onNodeWithTag("all-history-trail").assertExists();ui.onNodeWithText("历年最爱歌手变迁").assertExists()
        ui.onNodeWithText("风格").performClick();ui.waitForIdle();ui.onNodeWithText("历年音乐风格变迁").assertExists()
        ui.onNodeWithText("探索").performClick();ui.waitForIdle();ui.onNodeWithText("音乐探索与重温").assertExists()
        ui.onNodeWithText("季").performClick();ui.waitForIdle();ui.onNodeWithText("季度",substring=true).assertExists();shot("preview134-preference-exploration")
    }
    @Test fun summaryIsFifthTabAndStagePeriodsHaveNoPlayer(){
        ui.onNodeWithTag("period-tab:4").performClick();ui.waitUntil(10000){!ui.activity.controller.summaryLoading&&ui.activity.controller.summaryFacts.report.history.isNotEmpty()};ui.waitForIdle()
        ui.onNodeWithTag("favorite-timeline").assertExists();ui.onNodeWithTag("summary-section-1").assertDoesNotExist();ui.onNodeWithTag("period-tab:0").performClick();ui.waitForIdle();Assert.assertFalse(ui.activity.controller.summary)
    }
    @Test fun summarySectionSwitchCanReverseWithoutLosingProfile(){
        openSummary();ui.onNodeWithTag("summary-scroll").performScrollToNode(hasTestTag("summary-profile-hero"));ui.onNodeWithTag("summary-profile-hero").assertExists();ui.onNodeWithTag("summary-section-1").assertDoesNotExist()
    }
    @Test fun primarySummaryNavigationCanReverseDuringItsEntrance(){
        ui.mainClock.autoAdvance=false
        ui.onNode(hasTestTag("period-tab:4"),useUnmergedTree=true).performClick();ui.mainClock.advanceTimeBy(80)
        ui.onAllNodes(hasText("日") and hasAnyAncestor(hasClickAction()),useUnmergedTree=true).onLast().performClick()
        ui.mainClock.autoAdvance=true;ui.waitForIdle();Assert.assertFalse(ui.activity.controller.summary);ui.onNodeWithTag("home-scroll").assertExists()
    }
    @Test fun knownAlbumCoverageAndSummaryRespectDarkTheme(){
        ui.runOnUiThread{
            val rows=org.json.JSONArray()
            ui.activity.controller.report.history.distinctBy{it.id}.forEachIndexed{i,p->rows.put(org.json.JSONObject().put("trackId",p.id).put("collectionId",700000+i).put("collectionName",p.album).put("artistName",p.artist).put("trackCount",12).put("primaryGenreName","Pop"))}
            CatalogMetadata.accept(java.io.File(ui.activity.filesDir,"art"),org.json.JSONObject().put("results",rows))
        }
        openSummary();ui.runOnUiThread{ui.activity.controller.theme(DiaryThemeChoice.DARK)};ui.waitForIdle();shot("app-summary-dark")
        ui.onNodeWithTag("summary-scroll").performScrollToNode(hasTestTag("summary-link:最常回听的专辑"));ui.onNodeWithTag("summary-link:最常回听的专辑").performClick();ui.waitForIdle();ui.waitUntil(10000){org.robolectric.shadows.ShadowLooper.idleMainLooper();ui.onAllNodesWithText("已听1/12首").fetchSemanticsNodes().isNotEmpty()};ui.onNodeWithText("已听1/12首").assertExists();shot("app-summary-coverage-dark")
    }
    private fun openSummary(){ui.runOnUiThread{ui.activity.controller.openSummary()};ui.waitUntil(10000){!ui.activity.controller.summaryLoading&&ui.activity.controller.summaryFacts.report.history.isNotEmpty()};ui.waitForIdle()}
    @Test fun periodAllLinksUseFullDayStyleRankings(){
        for(range in listOf(DiaryRange.WEEK,DiaryRange.MONTH,DiaryRange.YEAR)){
            ui.runOnUiThread{ui.activity.controller.select(range=range)};ui.waitForIdle()
            val heading=when(range){DiaryRange.WEEK->"本周循环";DiaryRange.MONTH->"本月偏爱";else->"年度循环"}
            ui.onNodeWithTag("period-report").performScrollToNode(hasText(heading));ui.waitForIdle()
            ui.onNodeWithTag("all-link:$heading").performClick()
            ui.onNodeWithTag("favorite-ranking").assertExists();ui.onNodeWithText("常听排行").assertExists();ui.onNodeWithText("专辑").assertExists();ui.onNodeWithTag("ranking-list:0").assertExists()
            ui.onNodeWithContentDescription("返回日记").performClick();ui.waitForIdle()
            ui.onNodeWithTag("period-report").performScrollToNode(hasText(if(range==DiaryRange.YEAR)"年度常听歌手" else if(range==DiaryRange.MONTH)"本月常听歌手" else "本周常听歌手"));ui.waitForIdle()
            ui.onNodeWithTag("all-link:${if(range==DiaryRange.YEAR)"年度常听歌手" else if(range==DiaryRange.MONTH)"本月常听歌手" else "本周常听歌手"}").performClick()
            ui.onNodeWithTag("artist-ranking").assertExists();ui.onNodeWithText("常听歌手").assertExists()
            ui.onNodeWithContentDescription("返回日记").performClick();ui.waitForIdle()
        }
    }
    @Test fun weeklyNewAndArtistDetailsExposeRequestedSections(){
        val c=ui.activity.controller;ui.runOnUiThread{c.select(range=DiaryRange.WEEK)};ui.waitForIdle()
        ui.onNodeWithTag("period-report").performScrollToNode(hasTestTag("weekly-new"));ui.onNodeWithTag("weekly-new").assertExists();shot("preview105-weekly-new")
        val song=c.report.history.first();val target=WeeklyTarget(song,artist=true);val stats=weeklyListeningStats(c.report,target,c.day)
        val state=WeeklyDetailUi(target,stats,WeekComparison(0,false,1,null,0.0),WeekCatalog(),{},newSongIds=stats.songs.map{it.id}.toSet())
        ui.runOnUiThread{ui.activity.setContent{androidx.compose.material3.MaterialTheme{androidx.compose.foundation.lazy.LazyColumn(Modifier.testTag("weekly-artist-test")){weeklyDetailItems(c,state)}}}}
        ui.waitForIdle();ui.onNodeWithTag("weekly-artist-test").performScrollToNode(hasTestTag("weekly-artist-ranking"));ui.onNodeWithTag("weekly-artist-ranking").assertExists();ui.onNodeWithText("本周新增歌曲").assertExists();shot("preview105-artist-ranking")
    }
    @Test fun weeklyArtistAllUsesTheExistingFullRankingAndReturnsToArtist(){
        val c=ui.activity.controller;ui.runOnUiThread{c.select(range=DiaryRange.WEEK)};ui.waitForIdle()
        val artist=c.report.topArtists.first().name
        ui.onNodeWithTag("period-report").performScrollToNode(hasTestTag("all-link:本周常听歌手"));ui.onNodeWithTag("all-link:本周常听歌手").performClick();ui.waitForIdle()
        ui.onNodeWithTag("ranking-list:2").performScrollToNode(hasTestTag("library-group:artist:$artist"));ui.onNodeWithTag("library-group:artist:$artist").performClick();ui.waitForIdle()
        ui.waitUntil(10000){ui.onAllNodesWithTag("weekly-detail").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("music-group-detail").performScrollToNode(hasTestTag("weekly-artist-all"));ui.onNodeWithTag("weekly-artist-all").performClick();ui.waitForIdle()
        ui.onNodeWithTag("favorite-ranking").assertExists();ui.onNodeWithTag("ranking-list:0").assertExists();ui.onNodeWithText("常听排行").assertExists()
        ui.onNodeWithContentDescription("返回日记").performClick();ui.waitForIdle();ui.onNodeWithTag("music-group-detail").assertExists()
    }
    @Test fun weeklyFullRankingsShowChangesAndDetailsUseOneComparisonTemplate(){
        val c=ui.activity.controller;ui.runOnUiThread{c.select(range=DiaryRange.WEEK)};ui.waitForIdle()
        ui.onNodeWithTag("period-report").performScrollToNode(hasTestTag("all-link:本周循环"));ui.onNodeWithTag("all-link:本周循环").performClick();ui.waitForIdle()
        ui.waitUntil(10000){org.robolectric.shadows.ShadowLooper.idleMainLooper();ui.onAllNodesWithTag("rank-change",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
        ui.onAllNodesWithTag("rank-change",useUnmergedTree=true).onFirst().assertExists()
        val song=c.report.history.first();val stats=weeklyListeningStats(c.report,WeeklyTarget(song),c.day).copy(plays=6)
        for(target in listOf(WeeklyTarget(song),WeeklyTarget(song,album=true),WeeklyTarget(song,artist=true))){
            val state=WeeklyDetailUi(target,stats,WeekComparison(4,false,2,4,0.1),WeekCatalog(),{})
            ui.runOnUiThread{ui.activity.setContent{androidx.compose.material3.MaterialTheme{androidx.compose.foundation.lazy.LazyColumn(Modifier.testTag("compare-test")){weeklyDetailItems(c,state)}}}}
            ui.waitForIdle();ui.onNodeWithTag("compare-test").performScrollToNode(hasTestTag("weekly-comparison"))
            ui.onNodeWithText("第2名").assertExists();ui.onNodeWithText("上周第4名").assertExists();ui.onNodeWithText("50%").assertExists();ui.onNodeWithTag("weekly-play-comparison",useUnmergedTree=true).assert(hasAnyDescendant(hasText("2次")));ui.onNodeWithText("本周 6次 · 上周 4次").assertExists();shot("preview107-comparison")
        }
    }
    @Test fun yearlyTrendShowsWeeklyCurveAndCanSelect(){
        val c=ui.activity.controller
        ui.runOnUiThread{c.select(java.time.LocalDate.of(2026,10,23),DiaryRange.YEAR)};ui.waitForIdle()
        ui.onNodeWithTag("period-report").performScrollToNode(hasTestTag("year-weekly-trend"));ui.waitForIdle();shot("preview122-year-weekly-trend")
        ui.onNodeWithTag("year-weekly-trend").assertExists()
        val chart=ui.onNodeWithTag("year-weekly-trend-chart")
        val before=chart.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription]
        chart.performTouchInput{swipeLeft()};ui.waitForIdle()
        val after=ui.onNodeWithTag("year-weekly-trend-chart").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.StateDescription]
        Assert.assertNotEquals(before,after)
    }
    @Test fun weeklyHistoryBarsOpenTheChosenDay(){
        val day=java.time.LocalDate.of(2026,10,23)
        ui.runOnUiThread{ui.activity.controller.select(day,DiaryRange.WEEK)};ui.waitForIdle()
        ui.onNodeWithTag("period-report").performScrollToNode(hasTestTag("week-history-chart"));ui.waitForIdle();shot("app-week-history-chart")
        ui.onNodeWithTag("period-bar:4").performClick();ui.waitForIdle()
        ui.onNodeWithTag("period-history-page").assertExists();ui.onNodeWithTag("period-history-page").performScrollToNode(hasTestTag("period-history:2026-10-23"));ui.onNodeWithTag("period-history:2026-10-23").assertExists()
    }
    @Test fun monthlyCalendarUsesRelativeCapacityAndNoLegend(){
        val day=java.time.LocalDate.of(2026,10,23)
        ui.runOnUiThread{ui.activity.controller.select(day,DiaryRange.MONTH)};ui.waitForIdle()
        val totals=reportDays(ui.activity.controller.report);val peak=totals.maxBy{it.value};val smaller=totals.entries.first{it.value>0&&it.value<peak.value}
        ui.onNodeWithTag("period-report").performScrollToNode(hasTestTag("month-day:${peak.key}"));ui.waitForIdle();shot("app-month-capacity")
        fun capacity(date:java.time.LocalDate)=ui.onNodeWithTag("month-day:$date").fetchSemanticsNode().config[MonthCapacityKey]
        Assert.assertEquals(1f,capacity(peak.key),.001f);Assert.assertEquals(calendarCapacity(smaller.value,peak.value),capacity(smaller.key),.001f)
        ui.onNodeWithText("听得少",substring=true).assertDoesNotExist();ui.onNodeWithText("听得多",substring=true).assertDoesNotExist()
    }
    @Test fun monthAndYearSongAlbumDetailsUseTheirOwnPeriods(){
        val activity=ui.activity;val c=activity.controller;val day=java.time.LocalDate.of(2026,10,23)
        for(range in listOf(DiaryRange.MONTH,DiaryRange.YEAR)){
            ui.runOnUiThread{c.select(day,range)};ui.waitForIdle()
            val selected=sortedPlays(c.report.history,SortOrder.COUNT).first()
            val known=periodDemoReport(day,DiaryRange.YEAR).history.filter{it.album==selected.album&&it.artist==selected.artist}.distinctBy{it.id}
            val rows=org.json.JSONArray().put(org.json.JSONObject().put("wrapperType","collection").put("collectionId","876543").put("collectionName",selected.album).put("artistName",selected.artist).put("trackCount",known.size+1))
            (known.map{it.title}+"未收听曲目").forEachIndexed{i,title->rows.put(org.json.JSONObject().put("kind","song").put("collectionId","876543").put("trackId",8000000+i).put("trackName",title).put("artistName",selected.artist).put("trackNumber",i+1).put("trackTimeMillis",known.getOrNull(i)?.duration?:180000))}
            AlbumCatalogueStore.load(java.io.File(activity.filesDir,"art"),WeeklyTarget(selected,true),org.json.JSONArray()){org.json.JSONObject().put("results",rows)}
            ui.onNodeWithTag("period-report").performScrollToNode(hasTestTag("period-ranked-song:${selected.id}"))
            ui.onAllNodesWithTag("period-ranked-song:${selected.id}").onFirst().performClick();ui.waitForIdle()
            ui.waitUntil(10000){org.robolectric.shadows.ShadowLooper.idleMainLooper();ui.onAllNodesWithTag("weekly-detail").fetchSemanticsNodes().isNotEmpty()}
            val period=detailPeriodName(range)
            ui.onNodeWithText("详细记录").assertDoesNotExist()
            ui.onNodeWithTag("weekly-detail").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,range.name))
            ui.onNodeWithTag("song-detail").performScrollToNode(hasTestTag("weekly-play-chart"));ui.mainClock.advanceTimeBy(700);ui.waitForIdle();shot("preview96-${range.name}-song-chart")
            ui.onNodeWithTag("song-detail").performScrollToNode(hasText("相比${detailPreviousName(range)}"))
            ui.onNodeWithText("相比${detailPreviousName(range)}").assertExists()
            ui.onNodeWithTag("song-detail").performScrollToNode(hasTestTag("weekly-open-album"));ui.onNodeWithTag("weekly-open-album").performClick();ui.waitForIdle()
            ui.waitUntil(10000){org.robolectric.shadows.ShadowLooper.idleMainLooper();ui.onAllNodesWithTag("weekly-detail").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("music-group-detail").performScrollToNode(hasTestTag("weekly-album-distribution"))
            ui.waitUntil(10000){ui.onAllNodesWithTag("weekly-album-coverage").fetchSemanticsNodes().isNotEmpty()}
            ui.onNodeWithTag("weekly-album-coverage").assertTextContains("${period}覆盖",substring=true)
            shot("preview96-${range.name}-album-distribution")
            ui.runOnUiThread{activity.onBackPressedDispatcher.onBackPressed()};ui.waitForIdle()
            ui.runOnUiThread{activity.onBackPressedDispatcher.onBackPressed()};ui.waitForIdle()
        }
    }
    @Test fun weeklySingleReleaseKeepsTheSongReportAndHidesAlbumOnlyPanels(){
        val c=ui.activity.controller
        val song=c.report.history.first().copy(album="Example - Single")
        val target=WeeklyTarget(song,true)
        val stats=weeklyListeningStats(DiaryReport(history=listOf(song),segments=listOf(song)),target,c.day)
        val album=AlbumCatalogue("987654","Example - Single",song.artist,1,listOf(AlbumCatalogueTrack(song.id,song.title,song.artist,1,1,song.duration)),0)
        val state=WeeklyDetailUi(target,stats,WeekComparison(0,false),WeekCatalog(album=album),{})
        ui.runOnUiThread{ui.activity.setContent{androidx.compose.material3.MaterialTheme{androidx.compose.foundation.lazy.LazyColumn{weeklyDetailItems(c,state)}}}}
        ui.waitForIdle()
        ui.onNodeWithTag("weekly-detail").assertExists()
        ui.onNodeWithTag("weekly-album-distribution").assertDoesNotExist()
        ui.onNodeWithText("专辑连播").assertDoesNotExist()
        ui.onNodeWithText("详细记录").assertDoesNotExist()
    }
    @Test fun weeklyAlbumCatalogShowsUnplayedTracksAndSongReportsReplaceRecordLists(){
        val c=ui.activity.controller;val day=java.time.LocalDate.of(2026,10,23)
        ui.runOnUiThread{c.select(day,DiaryRange.WEEK)};ui.waitForIdle()
        val slice=periodHeroSlices(c.report,day,DiaryRange.WEEK).first{it.from==day}
        val lead=slice.lead!!;val songs=c.report.history.filter{it.album==lead.album&&it.artist==lead.artist}.distinctBy{it.id}
        val rows=org.json.JSONArray().put(org.json.JSONObject().put("wrapperType","collection").put("collectionId","987654").put("collectionName",lead.album).put("artistName",lead.artist).put("trackCount",songs.size+1))
        (songs.map{it.title}+"本周未播放的曲目").forEachIndexed{i,title->rows.put(org.json.JSONObject().put("kind","song").put("collectionId","987654").put("trackId",9000000+i).put("trackName",title).put("artistName",lead.artist).put("trackNumber",i+1).put("trackTimeMillis",songs.getOrNull(i)?.duration?:180000))}
        AlbumCatalogueStore.load(java.io.File(ui.activity.filesDir,"art"),WeeklyTarget(lead,true),org.json.JSONArray()){org.json.JSONObject().put("results",rows)}
        ui.onNodeWithTag("hero-album:${slice.key}:${lead.id}").performClick()
        ui.waitUntil(10000){org.robolectric.shadows.ShadowLooper.idleMainLooper();ui.onAllNodesWithTag("weekly-detail").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText("详细记录").assertDoesNotExist()
        ui.onNodeWithTag("weekly-open-album").performClick();ui.waitForIdle()
        ui.waitUntil(10000){org.robolectric.shadows.ShadowLooper.idleMainLooper();ui.onAllNodesWithTag("music-group-detail").fetchSemanticsNodes().isNotEmpty()&&ui.onAllNodesWithTag("weekly-detail").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("music-group-detail").performScrollToNode(hasTestTag("weekly-play-chart"));shot("preview95-weekly-album-chart")
        ui.onNodeWithTag("music-group-detail").performScrollToNode(hasText("本周未播放的曲目"));ui.waitForIdle()
        ui.onNodeWithTag("weekly-album-coverage").assertExists();ui.onNodeWithText("本周未播放的曲目").assertExists();shot("preview95-weekly-album-distribution")
        ui.onNodeWithTag("weekly-track:9000000").performClick()
        ui.waitUntil(10000){org.robolectric.shadows.ShadowLooper.idleMainLooper();ui.onAllNodesWithTag("weekly-detail").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("song-detail").performScrollToNode(hasTestTag("weekly-play-chart"));shot("preview95-weekly-song-chart")
        ui.onNodeWithText("详细记录").assertDoesNotExist()
    }
    @Test fun weeklyHeroSwipesBetweenRealDayGroupsAndOpensSongDetails(){
        val day=java.time.LocalDate.of(2026,10,23)
        ui.runOnUiThread{ui.activity.controller.select(day,DiaryRange.WEEK)};ui.waitForIdle()
        ui.onNodeWithTag("period-hero").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"2026-10-23/2026-10-23"))
        shot("app-week-hero")
        ui.onNodeWithText("每天的专辑与歌手").assertDoesNotExist()
        ui.onAllNodesWithText("周五").onFirst().assertExists()
        ui.onNodeWithTag("period-hero-pager").performScrollToIndex(1);ui.waitForIdle()
        ui.onNodeWithTag("period-hero").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"2026-10-19/2026-10-19"))
        val slice=periodHeroSlices(ui.activity.controller.report,day,DiaryRange.WEEK).first()
        ui.onNodeWithTag("hero-album:${slice.key}:${slice.lead!!.id}").performClick()
        ui.waitUntil(10000){org.robolectric.shadows.ShadowLooper.idleMainLooper();ui.onAllNodesWithTag("weekly-detail").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText("详细记录").assertDoesNotExist()
        ui.onNodeWithContentDescription("返回日记").performClick();ui.waitForIdle()
        ui.onNodeWithTag("period-hero").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,slice.key))
    }
    @Test fun monthlyHeroBrowsesWeeksAndKeepsDecorativeCopyOut(){
        ui.runOnUiThread{ui.activity.controller.select(java.time.LocalDate.of(2026,10,25),DiaryRange.MONTH)};ui.waitForIdle()
        ui.onNodeWithTag("period-hero").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"2026-10-19/2026-10-25"))
        shot("app-month-hero")
        ui.onNodeWithTag("period-hero-pager").performTouchInput{swipe(androidx.compose.ui.geometry.Offset(width*.18f,height*.5f),androidx.compose.ui.geometry.Offset(width*.72f,height*.5f),650)};ui.waitForIdle()
        ui.onNodeWithTag("period-hero").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"2026-10-12/2026-10-18"))
        ui.onNodeWithText("首次记录",substring=true).assertDoesNotExist();ui.onNodeWithText("方形",substring=true).assertDoesNotExist()
    }
    @Test fun intervalDateTapHasIntermediateFramesAndCanReverse(){
        ui.runOnUiThread{ui.activity.controller.select(java.time.LocalDate.of(2026,10,25),DiaryRange.MONTH)};ui.waitForIdle();ui.mainClock.autoAdvance=false
        fun position()=ui.onNodeWithTag("period-hero-pager").fetchSemanticsNode().config[PeriodHeroPositionKey]
        ui.onNodeWithText("二周").performClick();ui.waitForIdle();ui.mainClock.advanceTimeByFrame();ui.waitForIdle();ui.mainClock.advanceTimeBy(96)
        val midway=position();Assert.assertTrue("Interval must slide through real intermediate frames: $midway",midway>2.01f&&midway<3.99f)
        ui.onNodeWithText("四周").performClick();ui.waitForIdle();ui.mainClock.advanceTimeBy(1800);Assert.assertEquals(4f,position(),.01f);ui.mainClock.autoAdvance=true
        ui.onNodeWithText("10月19日—25日").assertDoesNotExist()
    }
    @Test fun periodLayoutsUseSquareWeekRectangularMonthAndFreeformYear(){
        val day=java.time.LocalDate.of(2026,10,23)
        ui.runOnUiThread{ui.activity.controller.select(day,DiaryRange.WEEK)};ui.waitForIdle()
        ui.onAllNodesWithTag("period-hero-layout-week").onFirst().assertExists()
        val slice=periodHeroSlices(ui.activity.controller.report,day,DiaryRange.WEEK)[4]
        val cover=ui.onNodeWithTag("hero-album:${slice.key}:${slice.lead!!.id}").fetchSemanticsNode().boundsInRoot
        Assert.assertEquals(cover.width,cover.height,1f)
        ui.runOnUiThread{ui.activity.controller.select(day,DiaryRange.MONTH)};ui.waitForIdle()
        val monthKey=periodHeroSlices(ui.activity.controller.report,day,DiaryRange.MONTH)[3].key
        val portrait=ui.onNodeWithTag("month-portrait:$monthKey").fetchSemanticsNode().boundsInRoot
        val grid=ui.onNodeWithTag("month-album-grid:$monthKey").fetchSemanticsNode().boundsInRoot
        Assert.assertTrue(portrait.width>0f&&portrait.height>0f)
        Assert.assertTrue(grid.width>0f&&grid.height>0f)
        ui.runOnUiThread{ui.activity.controller.select(day,DiaryRange.YEAR)};ui.waitForIdle()
        ui.onAllNodesWithTag("period-hero-layout-year").onFirst().assertExists()
        val yearKey=periodHeroSlices(ui.activity.controller.report,day,DiaryRange.YEAR)[9].key
        val one=ui.onNodeWithTag("year-song-0:$yearKey").fetchSemanticsNode().boundsInRoot
        val two=ui.onNodeWithTag("year-song-1:$yearKey").fetchSemanticsNode().boundsInRoot
        Assert.assertTrue(one.width>0f&&two.width>0f)
        Assert.assertFalse("Square covers should not stack directly",one.overlaps(two))
        val portraitOverlap=ui.onNodeWithTag("year-artist-1:$yearKey").fetchSemanticsNode().boundsInRoot
        Assert.assertTrue("The artist portrait should remain visible",portraitOverlap.width>0f&&portraitOverlap.height>0f)
        val title=periodHeroSlices(ui.activity.controller.report,day,DiaryRange.YEAR)[9].albums.flatMap{it.songs}.maxBy{it.ms}.title
        ui.onAllNodesWithText(title).onFirst().assertExists()
    }
    @Test fun periodPagerSurvivesReplacingSeveralPagesWithOneOrNone(){
        val day=java.time.LocalDate.of(2024,5,10)
        val initial=periodHeroSlices(periodDemoReport(day,DiaryRange.WEEK),day,DiaryRange.WEEK)
        val slices=androidx.compose.runtime.mutableStateOf(initial)
        ui.runOnUiThread{ui.activity.setContent{CompositionLocalProvider(LocalInspectionMode provides true){androidx.compose.material3.MaterialTheme{
            val pager=androidx.compose.foundation.pager.rememberPagerState(initialPage=2,pageCount={slices.value.size})
            PeriodHeroBrowser(ui.activity.controller,slices.value,DiaryRange.WEEK,pager,{},{_,_->})
        }}}};ui.waitForIdle()
        ui.onNodeWithTag("period-hero-pager").performScrollToIndex(2);ui.waitForIdle()
        ui.runOnUiThread{slices.value=listOf(initial.last())};ui.waitForIdle()
        ui.onNodeWithTag("period-hero").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,initial.last().key))
        ui.runOnUiThread{slices.value=emptyList()};ui.waitForIdle();ui.onNodeWithTag("period-hero").assertDoesNotExist()
        ui.runOnUiThread{slices.value=initial};ui.waitForIdle();ui.onNodeWithTag("period-hero").assertExists()
    }
    @Test fun monthToYearKeepsTileContentDuringTransition(){
        val day=java.time.LocalDate.of(2026,10,23)
        ui.runOnUiThread{ui.activity.controller.select(day,DiaryRange.MONTH)};ui.waitForIdle()
        ui.mainClock.autoAdvance=false
        ui.runOnUiThread{ui.activity.controller.select(day,DiaryRange.YEAR)}
        ui.mainClock.advanceTimeByFrame();ui.waitForIdle();ui.mainClock.advanceTimeBy(160);ui.waitForIdle();shot("month-year-transition-mid")
        Assert.assertTrue(ui.onAllNodesWithTag("period-hero-layout-year").fetchSemanticsNodes().isNotEmpty())
        ui.mainClock.advanceTimeBy(1200);ui.mainClock.autoAdvance=true;ui.waitForIdle()
    }
    @Test fun heroSelectsTheRequestedDayWhenDataArrivesLater(){
        val day=java.time.LocalDate.of(2024,5,10)
        val all=visiblePeriodHeroSlices(periodDemoReport(day,DiaryRange.WEEK),day,DiaryRange.WEEK)
        val slices=androidx.compose.runtime.mutableStateOf(emptyList<PeriodHeroSlice>())
        ui.runOnUiThread{ui.activity.setContent{CompositionLocalProvider(LocalInspectionMode provides true){androidx.compose.material3.MaterialTheme{
            val pager=rememberPeriodHeroPager(day,DiaryRange.WEEK,slices.value)
            PeriodHeroBrowser(ui.activity.controller,slices.value,DiaryRange.WEEK,pager,{},{_,_->})
        }}}};ui.waitForIdle()
        ui.runOnUiThread{slices.value=all};ui.waitForIdle()
        val wanted=all[heroSliceIndex(all,day)].key
        ui.onNodeWithTag("period-hero").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,wanted))
    }
    @Test fun monthCoverAndItsTitleShareOneTransitionTile(){
        val day=java.time.LocalDate.of(2026,10,23)
        ui.runOnUiThread{ui.activity.controller.select(day,DiaryRange.MONTH)};ui.waitForIdle()
        val slices=visiblePeriodHeroSlices(ui.activity.controller.report,day,DiaryRange.MONTH)
        val slice=slices[heroSliceIndex(slices,day)]
        val main="month-main-tile:${slice.key}";val grid="month-grid-album-0:${slice.key}"
        val tile=if(ui.onAllNodesWithTag(main,useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty())main else grid
        ui.onAllNodes(hasText(slice.albums.first().title) and hasAnyAncestor(hasTestTag(tile)),useUnmergedTree=true).onFirst().assertExists()
        ui.onNodeWithTag(tile,useUnmergedTree=true).assert(SemanticsMatcher.keyIsDefined(PeriodCoverMotionKey))
    }
    @Test fun selectedHeroSurvivesAddingAnEarlierSlice(){
        val day=java.time.LocalDate.of(2024,5,10)
        val all=periodHeroSlices(periodDemoReport(day,DiaryRange.WEEK),day,DiaryRange.WEEK)
        val slices=androidx.compose.runtime.mutableStateOf(all.drop(1))
        ui.runOnUiThread{ui.activity.setContent{CompositionLocalProvider(LocalInspectionMode provides true){androidx.compose.material3.MaterialTheme{
            val pager=rememberPeriodHeroPager(day,DiaryRange.WEEK,slices.value)
            PeriodHeroBrowser(ui.activity.controller,slices.value,DiaryRange.WEEK,pager,{},{_,_->})
        }}}};ui.waitForIdle()
        ui.onNodeWithTag("period-hero-pager").performScrollToIndex(3);ui.waitForIdle()
        val selected=all[3].key
        ui.runOnUiThread{slices.value=all};ui.waitForIdle()
        ui.onNodeWithTag("period-hero").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,selected))
    }
    @Test fun holdingHeroPausesAutoScrollAndReleaseRestartsFullInterval(){
        val day=java.time.LocalDate.of(2024,5,10)
        val slices=visiblePeriodHeroSlices(periodDemoReport(day,DiaryRange.WEEK),day,DiaryRange.WEEK)
        ui.mainClock.autoAdvance=false
        try {
            ui.runOnUiThread{ui.activity.setContent{CompositionLocalProvider(LocalInspectionMode provides true){androidx.compose.material3.MaterialTheme{
                val pager=rememberPeriodHeroPager(day,DiaryRange.WEEK,slices)
                PeriodHeroBrowser(ui.activity.controller,slices,DiaryRange.WEEK,pager,{},{_,_->},true)
            }}}};ui.waitForIdle()
            ui.mainClock.advanceTimeBy(100);ui.waitForIdle()
            fun position()=ui.onNodeWithTag("period-hero-pager").fetchSemanticsNode().config[PeriodHeroPositionKey]
            val before=position()
            ui.mainClock.advanceTimeBy(4500);ui.waitForIdle()
            ui.onNodeWithTag("period-hero-pager").performTouchInput{down(center)}
            ui.mainClock.advanceTimeBy(7000);ui.waitForIdle()
            Assert.assertEquals("holding must pause autoplay",before,position(),.001f)
            ui.onNodeWithTag("period-hero-pager").performTouchInput{up()}
            ui.mainClock.advanceTimeBy(4500);ui.waitForIdle()
            Assert.assertEquals("release must restart the entire delay",before,position(),.001f)
            ui.mainClock.advanceTimeBy(3000);ui.waitForIdle()
            Assert.assertTrue("autoplay resumes after a full idle interval",kotlin.math.abs(position()-before)>.1f)
        } finally {ui.mainClock.autoAdvance=true}
    }

    @Test fun automaticHeroReversesAtTheEndWithoutJumpingAcrossPages(){
        val day=java.time.LocalDate.of(2024,5,10);val slices=visiblePeriodHeroSlices(periodDemoReport(day,DiaryRange.WEEK),day,DiaryRange.WEEK)
        ui.mainClock.autoAdvance=false
        ui.runOnUiThread{ui.activity.setContent{CompositionLocalProvider(LocalInspectionMode provides true){androidx.compose.material3.MaterialTheme{val pager=rememberPeriodHeroPager(day,DiaryRange.WEEK,slices);androidx.compose.runtime.LaunchedEffect(Unit){pager.scrollToPage(slices.size)};PeriodHeroBrowser(ui.activity.controller,slices,DiaryRange.WEEK,pager,{},{_,_->},true)}}}};ui.waitForIdle()
        ui.mainClock.advanceTimeBy(5650);ui.waitForIdle();val p=ui.onNodeWithTag("period-hero-pager").fetchSemanticsNode().config[PeriodHeroPositionKey];Assert.assertTrue("last page must advance forward: $p",p>slices.size);ui.mainClock.advanceTimeBy(2500);ui.waitForIdle();ui.onNodeWithTag("period-hero").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,slices.first().key));ui.mainClock.autoAdvance=true
    }
    @Test fun refreshedNumbersDoNotReplayTheirEntrance(){
        val report=androidx.compose.runtime.mutableStateOf(DiaryReport(plays=321))
        ui.runOnUiThread{ui.activity.setContent{androidx.compose.material3.MaterialTheme{ListeningStats(report.value)}}};ui.waitForIdle()
        ui.onNodeWithText("321",useUnmergedTree=true).assertExists()
        ui.runOnIdle{report.value=report.value.copy(plays=654)};ui.waitForIdle()
        ui.onNodeWithText("654",useUnmergedTree=true).assertExists();ui.onNodeWithText("321",useUnmergedTree=true).assertDoesNotExist()
    }
    @Test fun periodHeroAutomaticallyAdvancesAndStopsWhenDisabled(){
        val day=java.time.LocalDate.of(2024,5,10)
        val slices=periodHeroSlices(demoReport(day),day,DiaryRange.WEEK)
        val enabled=androidx.compose.runtime.mutableStateOf(true)
        ui.mainClock.autoAdvance=false
        ui.runOnUiThread{ui.activity.setContent{CompositionLocalProvider(LocalInspectionMode provides true){androidx.compose.material3.MaterialTheme{
            val pager=androidx.compose.foundation.pager.rememberPagerState(pageCount={slices.size})
            PeriodHeroBrowser(ui.activity.controller,slices,DiaryRange.WEEK,pager,{},{_,_->},enabled.value)
        }}}};ui.waitForIdle()
        ui.mainClock.advanceTimeBy(7500);ui.waitForIdle()
        val moved=ui.onNodeWithTag("period-hero-pager").fetchSemanticsNode().config[PeriodHeroPositionKey]
        Assert.assertTrue("Period covers should auto advance",moved>.9f)
        ui.runOnUiThread{enabled.value=false};ui.waitForIdle()
        val paused=ui.onNodeWithTag("period-hero-pager").fetchSemanticsNode().config[PeriodHeroPositionKey]
        ui.mainClock.advanceTimeBy(7500);ui.waitForIdle()
        Assert.assertEquals(paused,ui.onNodeWithTag("period-hero-pager").fetchSemanticsNode().config[PeriodHeroPositionKey],.01f)
        ui.mainClock.autoAdvance=true
    }
    @Test fun weeklyAlbumArtistAndDailyFavoriteArtistHaveIndependentCards(){
        val day=java.time.LocalDate.of(2026,10,25)
        val start=day.atTime(12,0).toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        fun p(id:String,artist:String,album:String,minutes:Long)=Play(id,id,id,artist,album,start,start+minutes*60000,minutes*60000,240000,"UTC")
        val report=DiaryReport(segments=listOf(p("a","专辑真正的歌手","专辑A",6),p("b","当天常听歌手","专辑B",4),p("c","当天常听歌手","专辑C",4)))
        val slice=periodHeroSlices(report,day,DiaryRange.WEEK).last()
        ui.runOnUiThread{ui.activity.setContent{CompositionLocalProvider(LocalInspectionMode provides true){androidx.compose.material3.MaterialTheme{
            val pager=androidx.compose.foundation.pager.rememberPagerState(pageCount={1})
            PeriodHeroBrowser(ui.activity.controller,listOf(slice),DiaryRange.WEEK,pager,{},{_,_->})
        }}}};ui.waitForIdle()
        val albumTag="week-album-card:${slice.key}";val artistTag="week-artist-card:${slice.key}"
        ui.onNode(hasText("专辑真正的歌手") and hasAnyAncestor(hasTestTag(albumTag))).assertExists()
        ui.onNode(hasText("当天常听歌手") and hasAnyAncestor(hasTestTag(artistTag)),useUnmergedTree=true).assertExists()
        ui.onNode(hasText("当天常听歌手") and hasAnyAncestor(hasTestTag(albumTag))).assertDoesNotExist()
        val album=ui.onNodeWithTag(albumTag).fetchSemanticsNode().boundsInRoot;val artist=ui.onNodeWithTag(artistTag).fetchSemanticsNode().boundsInRoot
        Assert.assertTrue(album.right<artist.left);Assert.assertEquals(album.top,artist.top,1f);Assert.assertEquals(album.height,artist.height,1f)
    }
    @Test fun emptyPeriodsHaveNoHeroOptionsAndRemainNavigable(){
        val day=java.time.LocalDate.of(2001,2,12)
        for(range in listOf(DiaryRange.WEEK,DiaryRange.MONTH,DiaryRange.YEAR)){
            ui.runOnUiThread{ui.activity.controller.preview(false);ui.activity.controller.select(day,range)}
            ui.waitUntil(10000){!ui.activity.controller.loading};ui.waitForIdle()
            ui.onNodeWithTag("period-report").assertExists();ui.onNodeWithTag("period-hero-dates").assertDoesNotExist()
        }
    }
    @Test fun periodHeroArtistsOpenTheirFullArtistRanking(){
        val day=java.time.LocalDate.of(2026,10,23)
        for(range in listOf(DiaryRange.WEEK,DiaryRange.MONTH,DiaryRange.YEAR)){
            ui.runOnUiThread{ui.activity.controller.select(day,range)};ui.waitForIdle()
            val slices=periodHeroSlices(ui.activity.controller.report,day,range);val slice=slices[heroSliceIndex(slices,day)]
            val tag=when(range){DiaryRange.WEEK->"week-artist-card:${slice.key}";DiaryRange.MONTH->"month-portrait:${slice.key}";else->"year-artist-0:${slice.key}"}
            val shownArtist=if(range==DiaryRange.YEAR||range==DiaryRange.MONTH)ui.onNodeWithTag(tag).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].first() else slice.artists.first().name
            ui.onNodeWithTag(tag).performClick();ui.waitForIdle();ui.onNodeWithTag("music-group-detail").assertExists();ui.onNodeWithText(shownArtist).assertExists()
            ui.onNodeWithContentDescription("返回日记").performClick();ui.waitForIdle()
        }
    }
    @Test fun yearArtistNameStaysInsideItsPortrait(){
        val day=java.time.LocalDate.of(2026,10,23)
        ui.runOnUiThread{ui.activity.controller.select(day,DiaryRange.YEAR)};ui.waitForIdle()
        val key=periodHeroSlices(ui.activity.controller.report,day,DiaryRange.YEAR)[9].key
        val tag="year-artist-0:$key"
        val portrait=ui.onNodeWithTag(tag,useUnmergedTree=true).fetchSemanticsNode()
        val name=portrait.config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].first()
        val text=ui.onNode(hasText(name) and hasAnyAncestor(hasTestTag(tag)),useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        val bounds=portrait.boundsInRoot
        Assert.assertTrue("name must stay inside the portrait: $text vs $bounds",text.left>=bounds.left-1f&&text.right<=bounds.right+1f&&text.top>=bounds.top-1f&&text.bottom<=bounds.bottom+1f)
    }
    @Test fun yearlyHeroBrowsesMonthAndEmptyMonthDoesNotReusePreviousArtwork(){
        ui.runOnUiThread{ui.activity.controller.select(java.time.LocalDate.of(2026,10,25),DiaryRange.YEAR)};ui.waitForIdle()
        ui.onNodeWithTag("period-hero-pager").performScrollToIndex(6);ui.waitForIdle()
        ui.onNodeWithTag("period-hero").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"2026-06-01/2026-06-30"))
        shot("app-year-hero")
        val heroHeight=ui.onNodeWithTag("period-hero-pager").fetchSemanticsNode().boundsInRoot.height
        ui.onNodeWithText("6月的偏爱").assertDoesNotExist()
        ui.runOnUiThread{ui.activity.controller.theme(DiaryThemeChoice.DARK)};ui.waitForIdle();shot("app-year-hero-dark")
        // Future months depend on today's month; November is valid once the clock reaches it.
        for(month in (java.time.LocalDate.now().monthValue+1)..12)ui.onNodeWithText("${month}月").assertDoesNotExist()
        Assert.assertEquals(heroHeight,ui.onNodeWithTag("period-hero-pager").fetchSemanticsNode().boundsInRoot.height,.5f)
    }
    @Test fun yearlyCutoutGutterDoesNotOpenTheUnderlyingSong(){
        val day=java.time.LocalDate.of(2026,10,23)
        ui.runOnUiThread{ui.activity.controller.select(day,DiaryRange.YEAR)};ui.waitForIdle()
        val key=periodHeroSlices(ui.activity.controller.report,day,DiaryRange.YEAR)[9].key
        val tag="year-song-0:$key";val rect=ui.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val layout=ui.onNode(hasTestTag("period-hero-layout-year") and hasAnyDescendant(hasTestTag(tag))).fetchSemanticsNode().config[YearArtLayoutKey]
        val song=layout.songs[0];val unit=rect.width/song.side;val density=ui.activity.resources.displayMetrics.density
        val gutter=layout.artists.asSequence().flatMap{portrait->(0 until 72).asSequence().map{index->
            val angle=index*2.0*Math.PI/72.0;val radius=unit*portrait.side/2+2f*density
            androidx.compose.ui.geometry.Offset(unit*(portrait.x+portrait.side/2-song.x)+(radius*kotlin.math.cos(angle)).toFloat(),unit*(portrait.y+portrait.side/2-song.y)+(radius*kotlin.math.sin(angle)).toFloat())
        }}.firstOrNull{it.x>2&&it.y>2&&it.x<rect.width-2&&it.y<rect.height-2}
        if(gutter!=null){ui.onNodeWithTag(tag).performTouchInput{click(gutter)};ui.waitForIdle();ui.onNodeWithTag("song-detail").assertDoesNotExist()}
        val safe=(1..9).asSequence().flatMap{x->(1..9).asSequence().map{y->androidx.compose.ui.geometry.Offset(rect.width*x/10f,rect.height*y/10f)}}.first{point->layout.artists.all{portrait->
            val cx=unit*(portrait.x+portrait.side/2-song.x);val cy=unit*(portrait.y+portrait.side/2-song.y);val dx=point.x-cx;val dy=point.y-cy
            dx*dx+dy*dy>(unit*portrait.side/2+7*density)*(unit*portrait.side/2+7*density)
        }}
        ui.onNodeWithTag(tag).performTouchInput{click(safe)};ui.waitForIdle()
        ui.waitUntil(10000){org.robolectric.shadows.ShadowLooper.idleMainLooper();ui.onAllNodesWithTag("weekly-detail").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithText("本年播放").assertExists();ui.onNodeWithText("详细记录").assertDoesNotExist()
    }
    @Test fun longPressCoverAddsInlineArcAndCanJumpToOldest(){
        ui.onNodeWithTag("cover-carousel").performTouchInput{longClick(center)};ui.waitForIdle();ui.onNodeWithTag("cover-arc-controls").assertExists();ui.onNodeWithTag("cover-arc-scrubber").assertIsDisplayed();ui.onNodeWithText("松手吸附到最近一首").assertDoesNotExist();ui.onNodeWithTag("cover-arc-scrubber").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress){it(12f)};ui.waitForIdle();shot("app-cover-arc");ui.onNodeWithTag("cover-arc-scrubber").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress){it(23f)};ui.waitForIdle();ui.onNodeWithTag("cover-carousel").performTouchInput{click(center)};ui.waitForIdle();ui.onNodeWithTag("cover-arc-controls").assertDoesNotExist();ui.onNodeWithTag("cover-carousel").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,"demo-0"))
    }
    @Test fun fastArcSeekHasRealIntermediatePagerFramesAndCanReverse(){
        ui.onNodeWithTag("cover-carousel").performTouchInput{longClick(center)};ui.waitForIdle();ui.mainClock.autoAdvance=false
        fun position()=ui.onNodeWithTag("cover-carousel").fetchSemanticsNode().config[CoverPositionKey]
        val before=position();ui.onNodeWithTag("cover-arc-scrubber").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress){it(18f)};ui.waitForIdle();ui.mainClock.advanceTimeByFrame();ui.waitForIdle()
        ui.mainClock.advanceTimeBy(80);val middle=position();Assert.assertTrue("Pager should move through intermediate positions: before=$before middle=$middle",middle>before&&middle<17.9f)
        ui.onNodeWithTag("cover-arc-scrubber").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress){it(0f)};ui.waitForIdle();val justRetargeted=position();Assert.assertEquals(middle,justRetargeted,.1f)
        ui.mainClock.advanceTimeBy(100);Assert.assertTrue(position()<middle);ui.mainClock.advanceTimeBy(2000);Assert.assertEquals(0f,position(),.01f);ui.mainClock.autoAdvance=true
    }
    @Test fun coverDragAndArcDragCanAlternateThenTapReturnsToNormal(){
        ui.onNodeWithTag("cover-carousel").performTouchInput{longClick(center)};ui.waitForIdle()
        ui.onNodeWithText("完成").assertDoesNotExist()
        val title=ui.onNodeWithTag("cover-arc-title").fetchSemanticsNode().boundsInRoot
        val controls=ui.onNodeWithTag("cover-arc-controls").fetchSemanticsNode().boundsInRoot
        Assert.assertEquals(controls.center.x,title.center.x,1f)
        fun position()=ui.onNodeWithTag("cover-carousel").fetchSemanticsNode().config[CoverPositionKey]
        ui.onNodeWithTag("cover-arc-scrubber").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress){it(4f)};ui.waitForIdle()
        ui.onNodeWithTag("cover-carousel").performTouchInput{swipe(androidx.compose.ui.geometry.Offset(width*.72f,height*.5f),androidx.compose.ui.geometry.Offset(width*.22f,height*.5f),500)};ui.waitForIdle();Assert.assertTrue("Cover drag must remain active in arc mode",position()>4.4f)
        ui.onNodeWithTag("cover-arc-scrubber").performTouchInput{swipe(androidx.compose.ui.geometry.Offset(width*.50f,height*.5f),androidx.compose.ui.geometry.Offset(width*.82f,height*.5f),350)};ui.waitForIdle();Assert.assertTrue("Arc remains draggable after cover interaction",position()>17f)
        ui.onNodeWithTag("cover-carousel").performTouchInput{click(center)};ui.waitForIdle();ui.onNodeWithTag("cover-arc-controls").assertDoesNotExist();ui.onNodeWithTag("cover-caption").assertExists();ui.onNodeWithText("详细记录").assertDoesNotExist()
        ui.onNodeWithTag("cover-carousel").performTouchInput{swipeRight()};ui.waitForIdle();ui.onNodeWithTag("cover-carousel").performTouchInput{longClick(center)};ui.waitForIdle();ui.onNodeWithTag("cover-arc-scrubber").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress){it(2f)};ui.waitForIdle();Assert.assertEquals(2f,position(),.02f)
    }
    @Test fun consecutiveRepeatsExpandWithoutChangingHistoryTotals(){
        ui.runOnUiThread{
            val day=ui.activity.controller.day;val start=day.atTime(12,0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            val json=org.json.JSONObject().put("sourceId","repeat-test").put("tracks",org.json.JSONArray().put(org.json.JSONObject().put("id","1781888430").put("title","连续测试").put("artist","由薫").put("album","测试专辑").put("duration",240000)))
            val spans=org.json.JSONArray();repeat(3){i->spans.put(org.json.JSONObject().put("id",i+1).put("visit","repeat-$i").put("track","1781888430").put("day",day.toString()).put("zone",java.time.ZoneId.systemDefault().id).put("start",start+i*240000).put("end",start+(i+1)*240000).put("ms",240000))};json.put("spans",spans)
            val instance=DiaryDatabase::class.java.getDeclaredField("instance");instance.isAccessible=true;instance.set(null,null);DiaryDatabase.get(ui.activity).ingest(json,true);ui.activity.controller.preview(false)
        }
        ui.waitUntil(5000){ui.activity.controller.report.history.any{it.visit.endsWith("repeat-0")}};openHistory();ui.onNodeWithText("连听3次").performScrollTo();shot("app-repeat-collapsed")
        val report=ui.activity.controller.report;val session=ListeningSessions.group(report.segments).first{it.songs.any{p->p.visit.endsWith("repeat-0")}};val visit=session.songs.first{it.visit.endsWith("repeat-0")}.visit;val tag="song:row:${session.key}:$visit"
        ui.onNodeWithTag(tag).assertDoesNotExist();ui.onNodeWithText("连听3次").performClick();ui.onNodeWithTag(tag).assertExists();shot("app-repeat-expanded");Assert.assertEquals(report.ms,ui.activity.controller.report.ms)
    }
    @Test fun topRepeatsFoldThenOpenIndividualVisitsAndReturnWithoutLosingTotals(){
        seedTopRepeats()
        ui.runOnUiThread{ui.activity.controller.stop()};ui.waitForIdle()
        ui.onNodeWithTag("cover-carousel").assert(SemanticsMatcher.expectValue(CoverRepeatCountKey,3))
        val total=ui.activity.controller.report.ms
        ui.onNodeWithTag("cover-caption").assertTextContains("12:00—12:12 · 连听3次",substring=true)
        shot("app-cover-folded")
        ui.onNodeWithTag("cover-carousel").performTouchInput{click(center)};ui.waitForIdle()
        ui.onNodeWithTag("cover-repeat-contents").assertExists();shot("app-cover-unfolded")
        for(i in 0..2){val p=ui.activity.controller.report.history.first{it.visit.endsWith("fold-$i")};ui.onNodeWithTag("cover-repeat-visit:${p.visit}").assertExists()}
        val visit=ui.activity.controller.report.history.first{it.visit.endsWith("fold-1")}.visit
        ui.onNodeWithTag("cover-repeat-visit:$visit").performClick();ui.onNodeWithText("详细记录").assertExists()
        ui.onNodeWithContentDescription("返回日记").performClick();ui.waitForIdle()
        ui.onNodeWithTag("cover-carousel").assert(SemanticsMatcher.expectValue(CoverRepeatCountKey,3))
        Assert.assertEquals(total,ui.activity.controller.report.ms)
        ui.runOnUiThread{ui.activity.controller.theme(DiaryThemeChoice.DARK)};ui.waitForIdle();shot("app-cover-folded-dark")
    }
    @Test fun foldedSheetPausesAutoAndQuickArcStillDragsAcrossFoldedStops(){
        seedTopRepeats();resetCarousel(CoverCarousel.stops(ui.activity.controller.report.history,ui.activity.controller.report.segments).size)
        ui.onNodeWithTag("cover-caption").performClick();ui.mainClock.advanceTimeBy(18000)
        ui.onNodeWithTag("cover-repeat-contents").assertExists()
        ui.onNodeWithTag("cover-carousel").assert(SemanticsMatcher.expectValue(CoverRepeatCountKey,3))
        ui.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.Dismiss),useUnmergedTree=true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.Dismiss){it()};ui.mainClock.advanceTimeBy(1000);ui.waitForIdle()
        ui.onNodeWithTag("cover-repeat-contents").assertDoesNotExist()
        ui.mainClock.autoAdvance=true;ui.waitForIdle()
        ui.onNodeWithTag("cover-carousel").performTouchInput{longClick(center)};ui.waitForIdle()
        ui.onNodeWithTag("cover-arc-scrubber").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress){it(2f)};ui.waitForIdle()
        ui.onNodeWithTag("cover-carousel").assert(SemanticsMatcher.expectValue(CoverRepeatCountKey,1))
        ui.onNodeWithTag("cover-arc-scrubber").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress){it(0f)};ui.waitForIdle()
        ui.onNodeWithTag("cover-carousel").performTouchInput{click(center)};ui.waitForIdle()
        ui.onNodeWithTag("cover-arc-controls").assertDoesNotExist()
        ui.onNodeWithTag("cover-carousel").assert(SemanticsMatcher.expectValue(CoverRepeatCountKey,3))
    }
    private fun seedTopRepeats(){
        ui.runOnUiThread{
            val day=ui.activity.controller.day;val zone=java.time.ZoneId.systemDefault();val start=day.atTime(12,0).atZone(zone).toInstant().toEpochMilli()
            val tracks=org.json.JSONArray().put(org.json.JSONObject().put("id","1781888430").put("title","Mermaid").put("artist","由薫").put("album","测试专辑").put("duration",240000))
            val spans=org.json.JSONArray()
            repeat(3){i->spans.put(org.json.JSONObject().put("id",i+1).put("visit","fold-$i").put("track","1781888430").put("day",day.toString()).put("zone",zone.id).put("start",start+i*240000).put("end",start+(i+1)*240000).put("ms",240000))}
            val demo=demoReport(day).history.distinctBy{it.id}.filter{it.id!="1781888430"}.take(3)
            demo.forEachIndexed{i,p->tracks.put(org.json.JSONObject().put("id",p.id).put("title",p.title).put("artist",p.artist).put("album",p.album).put("duration",240000));spans.put(org.json.JSONObject().put("id",10+i).put("visit","fold-other-$i").put("track",p.id).put("day",day.toString()).put("zone",zone.id).put("start",start-(i+1)*240000).put("end",start-i*240000).put("ms",240000))}
            val json=org.json.JSONObject().put("sourceId","cover-fold-test").put("tracks",tracks).put("spans",spans)
            val instance=DiaryDatabase::class.java.getDeclaredField("instance");instance.isAccessible=true;instance.set(null,null);DiaryDatabase.get(ui.activity).ingest(json,true);ui.activity.controller.preview(false)
        }
        ui.waitUntil(10000){ui.activity.controller.report.history.any{it.visit.endsWith("fold-2")}};ui.waitForIdle()
        ui.onNodeWithTag("cover-carousel").performScrollToIndex(CoverCarousel.firstPage(CoverCarousel.stops(ui.activity.controller.report.history,ui.activity.controller.report.segments).size));ui.waitForIdle()
    }
    @Test fun reconnectActuallyRequestsThenAcceptedBatchRefreshesVisibleSyncTime(){
        ui.runOnUiThread{
            val info=android.content.pm.PackageInfo().apply{packageName=BridgeIdentity.HOST;applicationInfo=android.content.pm.ApplicationInfo().apply{packageName=BridgeIdentity.HOST;uid=12345};signingInfo=android.content.pm.SigningInfo().also{org.robolectric.Shadows.shadowOf(it).setSignatures(arrayOf(android.content.pm.Signature("12345678")))}}
            org.robolectric.Shadows.shadowOf(ui.activity.packageManager).installPackage(info)
            val instance=DiaryDatabase::class.java.getDeclaredField("instance");instance.isAccessible=true;instance.set(null,null)
            ui.activity.controller.preview(false)
        }
        ui.waitForIdle();ui.onNodeWithContentDescription("设置").performClick()
        ui.onNodeWithText("连接 AM++ 采集器").performClick()
        ui.waitUntil(10000){ui.activity.controller.approved};ui.waitForIdle()
        ui.onNodeWithText("正在同步…").assertExists();shot("app-sync-requesting")
        ui.runOnUiThread{
            DiaryDatabase.get(ui.activity).ingest(org.json.JSONObject().put("sourceId","sync-ui-test").put("tracks",org.json.JSONArray()).put("spans",org.json.JSONArray()))
            ui.activity.contentResolver.notifyChange(BridgeIdentity.URI,null)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(700))
        }
        try{ui.waitUntil(10000){!ui.activity.controller.syncing&&ui.activity.controller.report.lastSync>0}}catch(e:Exception){throw AssertionError("foreground=${ui.activity.controller.foreground} syncing=${ui.activity.controller.syncing} status=${ui.activity.controller.syncStatus} lastSync=${ui.activity.controller.report.lastSync} error=${ui.activity.controller.error} serial=${ui.activity.getSharedPreferences("diary",0).getLong("sync_serial",0)}",e)};ui.waitForIdle()
        ui.onNodeWithText("已收到采集器更新").assertExists();ui.onNodeWithText("重新确认连接").assertExists();shot("app-sync-confirmed")
        ui.onNode(hasText("已授权 AM++ · 最近同步",substring=true)).assertExists()
    }
    @Test fun appleImportPreviewCommitManageAndRemoveKeepNativeData(){
        ui.runOnUiThread{val instance=DiaryDatabase::class.java.getDeclaredField("instance");instance.isAccessible=true;instance.set(null,null)}
        val headers=listOf("Event Type","Event ID","Song Name","Album Name","Play Duration Milliseconds","Media Duration In Milliseconds","Event Start Timestamp","Event End Timestamp","UTC Offset In Seconds","End Reason Type")
        val data=listOf("PLAY_END","ui-official-1","Mermaid","Apple 历史专辑","60000","240000","2025-12-01T12:00:00Z","2025-12-01T12:01:00Z","28800","TRACK_SKIPPED_FORWARDS")
        val file=java.io.File(ui.activity.cacheDir,"Apple Music Play Activity.csv").apply{writeText((headers+emptyList()).joinToString(",")+"\n"+data.joinToString(","))}
        val before=DiaryDatabase.get(ui.activity).backup().getJSONArray("spans").toString()
        ui.onNodeWithContentDescription("设置").performClick();ui.waitForIdle();ui.onNodeWithTag("apple-import-choose").performScrollTo().assertExists()
        ui.runOnUiThread{ui.activity.controller.stageApple(android.net.Uri.fromFile(file))}
        ui.waitUntil(10000){ui.activity.controller.applePlan!=null||ui.activity.controller.appleError!=null};Assert.assertNull(ui.activity.controller.appleError);ui.waitForIdle();ui.onNodeWithTag("apple-import-review").assertExists();shot("app-apple-preview");ui.runOnUiThread{ui.activity.controller.theme(DiaryThemeChoice.DARK)};ui.waitForIdle();shot("app-apple-preview-dark");ui.runOnUiThread{ui.activity.controller.theme(DiaryThemeChoice.LIGHT)};ui.waitForIdle()
        ui.onNodeWithTag("apple-import-confirm").performScrollTo().performClick();ui.waitUntil(10000){ui.activity.controller.appleBatches.isNotEmpty()&&!ui.activity.controller.appleBusy};ui.waitForIdle()
        Assert.assertEquals(java.time.LocalDate.of(2025,12,1),ui.activity.controller.day);ui.waitUntil(10000){ui.activity.controller.report.importedRows>0};ui.onNodeWithTag("home-scroll").assertExists();shot("app-apple-day")
        ui.onNodeWithTag("home-scroll").performScrollToIndex(0);ui.onNodeWithContentDescription("设置").performClick();ui.waitForIdle();val id=ui.activity.controller.appleBatches.first().id
        ui.onNodeWithTag("apple-remove:$id").performScrollTo();ui.waitForIdle();ui.onNodeWithTag("apple-remove:$id").assertIsDisplayed();shot("app-apple-manage");ui.onNodeWithTag("apple-remove:$id").performClick();ui.onNodeWithTag("apple-remove-confirm").performClick()
        ui.waitUntil(10000){ui.activity.controller.appleBatches.isEmpty()&&!ui.activity.controller.appleBusy};Assert.assertEquals(before,DiaryDatabase.get(ui.activity).backup().getJSONArray("spans").toString())
    }
    @Test fun rankingSearchIsHiddenUntilPullAndFiltersSongsAlbumsAndArtists(){
        ui.onNodeWithTag("home-scroll").performScrollToNode(hasText("今日最常听"));ui.onNodeWithTag("all-link:今日最常听").performClick();ui.waitForIdle()
        ui.onNodeWithTag("ranking-search").assertDoesNotExist();ui.onNodeWithTag("ranking-list:0").performTouchInput{swipeDown(durationMillis=500)};ui.waitForIdle();ui.onNodeWithTag("ranking-search").performTextInput("no-such-song");ui.onNodeWithText("没有找到匹配记录").assertExists()
        ui.onNodeWithContentDescription("返回日记").performClick();ui.waitForIdle();ui.onNodeWithText("专辑").performClick();ui.waitForIdle();ui.onNodeWithTag("all-link:今日最常听").performClick();ui.waitForIdle();ui.onNodeWithTag("ranking-list:1").performTouchInput{swipeDown(durationMillis=500)};ui.waitForIdle();ui.onNodeWithTag("ranking-search").performTextInput("Vaundy");ui.onNodeWithTag("library-group:album:示例专辑\u0000Vaundy").assertExists();shot("app-ranking-search")
        ui.onNodeWithContentDescription("返回日记").performClick();ui.waitForIdle();ui.onNodeWithTag("home-scroll").performScrollToNode(hasText("常听歌手"));ui.onNodeWithTag("all-link:常听歌手").performClick();ui.waitForIdle()
        ui.onNodeWithTag("ranking-search").assertDoesNotExist();ui.onNodeWithTag("ranking-list:2").performScrollToIndex(0);ui.onNodeWithTag("ranking-list:2").performTouchInput{swipeDown(durationMillis=500)};ui.waitForIdle();ui.onNodeWithTag("ranking-search").performTextInput("Vaundy");ui.onNodeWithTag("library-group:artist:Vaundy").assertExists();ui.onNodeWithTag("library-group:artist:由薫").assertDoesNotExist();shot("app-artist-search")
    }
    @Test fun fourStatsSwitchVerticallyWithoutHorizontalDrift(){
        ui.onNodeWithTag("home-scroll").performScrollToNode(hasTestTag("stat-plays"));ui.mainClock.autoAdvance=false
        for(tag in listOf("stat-plays","stat-duration","stat-tracks","stat-artists")){
            val x=ui.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.center.x;ui.onNodeWithTag(tag).performClick();ui.mainClock.advanceTimeBy(80)
            val texts=ui.onAllNodes(hasAnyAncestor(hasTestTag(tag)) and SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Text),useUnmergedTree=true).fetchSemanticsNodes()
            texts.forEach{Assert.assertEquals(x,it.boundsInRoot.center.x,1.5f)};ui.mainClock.advanceTimeBy(600)
        };ui.mainClock.autoAdvance=true
    }
    @Test fun realSkipCaptureShowsSeparateCountAndObservationStart(){
        ui.runOnUiThread{
            val instance=DiaryDatabase::class.java.getDeclaredField("instance");instance.isAccessible=true;instance.set(null,null)
            val now=System.currentTimeMillis();val zone=java.time.ZoneId.systemDefault();val day=java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            val event=org.json.JSONObject().put("event_id","ui-skip-real").put("day",day.toString()).put("zone",zone.id).put("at",now).put("kind","next").put("from_track","A").put("to_track","B").put("position",60000).put("duration",240000)
            DiaryDatabase.get(ui.activity).ingest(org.json.JSONObject().put("sourceId","ui-real").put("eventsSince",now-1000).put("events",org.json.JSONArray().put(event)))
            ui.activity.controller.preview(false);ui.activity.controller.select(day,DiaryRange.DAY)
        }
        ui.waitUntil(10000){ui.activity.controller.report.manualSkips==1L};ui.waitForIdle();ui.onNodeWithTag("home-scroll").performScrollToNode(hasTestTag("stat-plays"));ui.onNodeWithTag("stat-plays").performClick();ui.waitForIdle();ui.onNodeWithText("次切歌").assertExists();shot("app-skip-real")
    }
    @Test fun singleAndRepeatedCaptionKeepSongAndTimeAtTheSameSize(){
        fun verify(){
            val texts=ui.onAllNodes(hasAnyAncestor(hasTestTag("cover-caption")) and SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Text),useUnmergedTree=true)
            Assert.assertEquals(2,texts.fetchSemanticsNodes().size)
            repeat(2){i->texts[i].performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult){action->val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>();Assert.assertTrue(action(layouts));Assert.assertEquals(14f,layouts.single().layoutInput.style.fontSize.value,.01f)}}
        }
        resetCarousel();ui.waitForIdle();verify();val height=ui.onNodeWithTag("cover-caption").fetchSemanticsNode().boundsInRoot.height
        seedTopRepeats();resetCarousel(CoverCarousel.stops(ui.activity.controller.report.history,ui.activity.controller.report.segments).size);ui.waitForIdle();verify()
        Assert.assertEquals(height,ui.onNodeWithTag("cover-caption").fetchSemanticsNode().boundsInRoot.height,1f)
    }
    @Test fun allFourPeriodsUseTheSameSwitchableStats(){
        for(range in listOf(DiaryRange.DAY,DiaryRange.WEEK,DiaryRange.MONTH,DiaryRange.YEAR)){
            ui.runOnUiThread{ui.activity.controller.select(range=range)};ui.waitForIdle()
            val tag=if(range==DiaryRange.DAY)"home-scroll" else "period-report"
            ui.onNodeWithTag(tag).performScrollToNode(hasTestTag("stat-plays"))
            ui.onNodeWithTag("stat-plays").performClick();ui.onNodeWithText("次切歌·估算").assertExists()
            ui.onNodeWithTag("stat-duration").performClick();ui.onNodeWithText("小时").assertExists()
            ui.onNodeWithTag("stat-tracks").performClick();ui.onNodeWithText("张专辑").assertExists()
            ui.onNodeWithTag("stat-artists").performClick();ui.onNodeWithText("位歌手·多首").assertExists()
            shot("app-stats-${range.name.lowercase()}")
            ui.onNodeWithTag("stat-plays").performClick();ui.onNodeWithText("次播放").assertExists()
            ui.onNodeWithTag("stat-duration").performClick();ui.onNode(hasTestTag("stat-duration") and hasText("分钟")).assertExists()
        }
    }
    @Test fun artistRankingExpandsSongsAndReturnsToHome(){
        ui.onNodeWithTag("home-scroll").performScrollToNode(hasText("常听歌手"));ui.onNodeWithTag("artist-card:由薫").performClick();ui.waitForIdle()
        ui.onNodeWithTag("music-group-detail").assertExists();ui.onNodeWithText("由薫").assertExists();ui.onNodeWithText("今日收听").assertExists();ui.onNodeWithContentDescription("返回日记").performClick();ui.waitForIdle();ui.onNodeWithTag("home-scroll").assertExists()
    }
    @Test fun allPeriodsChooseSongsAndAlbumsOnHomeAndOpenRealAlbumDetails(){
        val activity=ui.activity;val c=activity.controller
        for(range in DiaryRange.entries){
            ui.runOnUiThread{c.select(java.time.LocalDate.of(2026,10,23),range)};ui.waitForIdle()
            val list=if(range==DiaryRange.DAY)"home-scroll"else"period-report"
            val heading=when(range){DiaryRange.DAY->"当日最常听";DiaryRange.WEEK->"本周循环";DiaryRange.MONTH->"本月偏爱";DiaryRange.YEAR->"年度循环"}
            ui.onNodeWithTag(list).performScrollToNode(hasTestTag("favorite-type-tabs"))
            ui.onNode(hasText("歌曲") and hasAnyAncestor(hasTestTag("favorite-type-tabs"))).performClick();ui.waitForIdle()
            ui.onNodeWithTag("all-link:$heading").performClick();ui.waitForIdle()
            ui.onNodeWithTag("ranking-list:0").assertExists();ui.onNodeWithText("专辑").assertExists()
            ui.onNodeWithText("专辑").performClick();ui.waitForIdle();ui.onNodeWithTag("ranking-list:1").assertExists()
            ui.onNodeWithText("歌曲").performClick();ui.waitForIdle();ui.onNodeWithTag("ranking-list:0").assertExists()
            ui.onNodeWithContentDescription("返回日记").performClick();ui.waitForIdle()
            ui.onNodeWithTag(list).performScrollToNode(hasTestTag("favorite-type-tabs"))
            ui.onNode(hasText("专辑") and hasAnyAncestor(hasTestTag("favorite-type-tabs"))).performClick();ui.waitForIdle()
            val album=libraryGroups(c.report,false).first()
            ui.onNodeWithTag(list).performScrollToNode(hasTestTag("favorite-album:${album.key.removePrefix("album:")}"))
            ui.onNodeWithTag("favorite-album:${album.key.removePrefix("album:")}").assert(hasText("${album.songs.size}首 · ${album.plays}次 · ${minutes(album.ms)}分钟",substring=true))
            val rowBounds=ui.onNodeWithTag("favorite-album:${album.key.removePrefix("album:")}").fetchSemanticsNode().boundsInRoot
            val listBounds=ui.onNodeWithTag("favorite-album-list").fetchSemanticsNode().boundsInRoot
            Assert.assertTrue("album preview uses full-width rows",rowBounds.width>=listBounds.width*.95f)
            ui.mainClock.advanceTimeBy(700);ui.waitForIdle();shot("preview98-${range.name}-home-albums")
            ui.onNodeWithTag("favorite-album:${album.key.removePrefix("album:")}").performClick();ui.waitForIdle()
            ui.onNodeWithTag("music-group-detail").assertExists()
            ui.onNodeWithText(album.title).assertExists()
            if(range!=DiaryRange.DAY){
                ui.waitUntil(10000){org.robolectric.shadows.ShadowLooper.idleMainLooper();ui.onAllNodesWithTag("weekly-detail").fetchSemanticsNodes().isNotEmpty()}
                ui.onNodeWithTag("weekly-detail").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription,range.name))
            }
            ui.onNodeWithContentDescription("返回日记").performClick();ui.waitForIdle()
            ui.onNodeWithTag(list).performScrollToNode(hasTestTag("favorite-type-tabs"))
            ui.onNodeWithText("专辑").assertIsSelected()
            ui.onNodeWithTag("all-link:$heading").performClick();ui.waitForIdle()
            ui.onNodeWithTag("ranking-list:1").assertExists();ui.onNodeWithText("歌曲").assertExists()
            val first=libraryGroups(c.report,false).first()
            ui.onNodeWithTag("library-group:${first.key}").performClick();ui.waitForIdle()
            ui.onNodeWithTag("music-group-detail").assertExists();ui.onNodeWithText(first.title).assertExists()
            ui.onNodeWithContentDescription("返回日记").performClick();ui.waitForIdle()
            ui.onNodeWithTag("ranking-list:1").assertExists()
            ui.onNodeWithContentDescription("返回日记").performClick();ui.waitForIdle()
        }
    }
    @Test fun periodDetailsGroupContinuityAndTwoTimeSharesWithoutExtraCaptions(){
        val activity=ui.activity;val c=activity.controller;val day=java.time.LocalDate.of(2026,10,23)
        for(range in listOf(DiaryRange.WEEK,DiaryRange.MONTH,DiaryRange.YEAR))for(album in listOf(false,true)){
            val report=periodDemoReport(day,range);val target=WeeklyTarget(report.history.first(),album)
            val stats=weeklyListeningStats(report,target,day,range)
            val state=WeeklyDetailUi(target,stats,WeekComparison(0,false),WeekCatalog(),{})
            ui.runOnUiThread{activity.setContent{androidx.compose.material3.MaterialTheme{androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize().testTag("report-test")){weeklyDetailItems(c,state)}}}}
            ui.waitForIdle()
            ui.onNodeWithTag("report-test").performScrollToNode(hasTestTag("weekly-continuous"))
            ui.onNodeWithTag("weekly-continuous").assert(hasAnyDescendant(hasText("最多连续播放")) and hasAnyDescendant(hasText("最长连续聆听")))
            ui.onNodeWithTag("report-test").performScrollToNode(hasTestTag("weekly-shares"))
            ui.onNodeWithTag("weekly-shares").assert(hasAnyDescendant(hasText("占歌手收听时长")) and hasAnyDescendant(hasText("占听歌时长")))
            ui.mainClock.advanceTimeBy(700);ui.waitForIdle();shot("preview97-${range.name}-${album}-metrics")
            ui.onNodeWithTag("report-test").performScrollToNode(hasTestTag("weekly-habits"))
            ui.onNodeWithText("此时段共听",substring=true).assertDoesNotExist();ui.onNodeWithText("专辑连播").assertDoesNotExist()
            ui.onNodeWithTag("weekly-habits").assert(hasAnyDescendant(hasText("${stats.peakMs/60000}分钟",substring=true)))
            ui.mainClock.advanceTimeBy(700);ui.waitForIdle();shot("preview97-${range.name}-${album}-habits")
        }
    }
    @Test fun themedCalendarChangesMonthPicksDateAndDismisses(){
        val selected=ui.activity.controller.day
        ui.onNodeWithTag("home-scroll").performScrollToIndex(0)
        ui.onNodeWithText(selected.format(java.time.format.DateTimeFormatter.ofPattern("yyyy年M月d日 · E",java.util.Locale.CHINESE))).performClick();ui.waitForIdle();ui.onNodeWithTag("diary-calendar").assertExists();shot("app-calendar")
        ui.onNodeWithTag("calendar-previous").performClick();ui.waitForIdle();val target=selected.withDayOfMonth(1).minusMonths(1).withDayOfMonth(12)
        ui.onNodeWithTag("calendar-day:$target").performClick();ui.onNodeWithTag("calendar-confirm").performClick();ui.waitForIdle();Assert.assertEquals(target,ui.activity.controller.day);ui.onNodeWithTag("diary-calendar").assertDoesNotExist()
    }

    @Test fun calendarJumpsDirectlyToYearMonthAndPreservesChosenDate(){
        ui.onNodeWithTag("home-scroll").performScrollToIndex(0)
        val selected=ui.activity.controller.day
        ui.onNodeWithText(selected.format(java.time.format.DateTimeFormatter.ofPattern("yyyy年M月d日 · E",java.util.Locale.CHINESE))).performClick()
        ui.onNodeWithTag("calendar-year").performClick();ui.onNodeWithTag("calendar-year:2021").performScrollTo().performClick();ui.waitForIdle();shot("app-calendar-years")
        ui.onNodeWithTag("calendar-month:2").performClick();ui.onNodeWithTag("calendar-day:2021-02-07").performClick();ui.onNodeWithTag("calendar-confirm").performClick()
        ui.waitUntil(10000){!ui.activity.controller.loading};Assert.assertEquals(java.time.LocalDate.of(2021,2,7),ui.activity.controller.day);Assert.assertEquals("2021-02-07",ui.activity.controller.report.from)
    }
    @Test fun settingsScrollReachesDayPolicyImportAndBackup(){
        ui.onNodeWithContentDescription("设置").performClick();ui.onNodeWithTag("day-mode:AUTO").performScrollTo().performClick();ui.waitForIdle()
        Assert.assertEquals(ListeningDayMode.AUTO,ui.activity.controller.dayPolicy.mode)
        ui.onNodeWithTag("day-mode:FIXED").performScrollTo().performClick();ui.onNodeWithTag("day-cutoff").performScrollTo().performClick();ui.onNodeWithTag("day-hour:3").performScrollTo().performClick();ui.waitForIdle()
        Assert.assertEquals(3,ui.activity.controller.dayPolicy.hour);shot("app-day-policy")
        ui.onNodeWithTag("apple-import-choose").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("导出完整日记备份").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("交互演示").assertDoesNotExist()
    }
    @Test fun collectorRefreshCannotStarveDateSelectionOrSummary(){
        val target=java.time.LocalDate.of(2025,12,19)
        ui.runOnUiThread{ui.activity.controller.select(target);repeat(50){ui.activity.controller.refresh(true)}}
        ui.waitUntil(10000){!ui.activity.controller.loading&&ui.activity.controller.report.from==target.toString()}
        ui.runOnUiThread{ui.activity.controller.openSummary();repeat(50){ui.activity.controller.refresh(true)}}
        ui.waitUntil(10000){!ui.activity.controller.summaryLoading&&ui.activity.controller.summaryFacts.report.history.isNotEmpty()}
        ui.runOnUiThread{ui.activity.controller.select(target.minusDays(1));ui.activity.controller.select(target.minusYears(1));ui.activity.controller.select(target.plusDays(1))}
        ui.waitUntil(10000){!ui.activity.controller.loading&&ui.activity.controller.report.from==target.plusDays(1).toString()}
    }

    @Test fun verifiedMetadataUpdatesAnOpenDetailAndArtistRanking(){
        val day=java.time.LocalDate.now();val zone=java.time.ZoneId.systemDefault().id;val start=day.atTime(12,0).atZone(java.time.ZoneId.of(zone)).toInstant().toEpochMilli()
        val track=AppleTrack("apple:ui-metadata29","资料补全测试歌","未知歌手","补全测试专辑",240000)
        ui.runOnUiThread{
            DiaryDatabase.get(ui.activity).importApple(ApplePlan("d".repeat(64),"UI metadata",listOf(AppleRow("ui-meta",track,start,60000,zone,"NATURAL_END_OF_TRACK",start+60000,0,60000,"device")),0,0))
            ui.activity.controller.preview(false);ui.activity.controller.select(day)
        }
        ui.waitUntil(10000){ui.activity.controller.report.history.any{it.id==track.id}};ui.onNodeWithTag("home-scroll").performScrollToNode(hasTestTag("day-history-link"));ui.onNodeWithTag("day-history-link").performClick();ui.onNodeWithText(track.title).performScrollTo().performClick();ui.onAllNodesWithText("未知歌手").assertCountEquals(1)
        ui.runOnUiThread{
            val row=org.json.JSONObject().put("trackId",1872948412L).put("artistId",1234567).put("trackName",track.title).put("artistName","旺仔小喬").put("collectionName",track.album).put("trackTimeMillis",track.duration).put("artworkUrl100","https://is1-ssl.mzstatic.com/test/100x100bb.jpg")
            Assert.assertTrue(TrackMetadata.shared(DiaryDatabase.get(ui.activity).images).put(track.id,AppleTrackImages.Hint(track.title,track.artist,track.album,track.duration),row));ui.activity.controller.refresh(true)
        }
        org.robolectric.shadows.ShadowLooper.idleMainLooper(500,java.util.concurrent.TimeUnit.MILLISECONDS)
        ui.waitUntil(10000){ui.activity.controller.report.history.any{it.id==track.id&&it.artist=="旺仔小乔"}};ui.waitForIdle();ui.onAllNodesWithText("旺仔小乔").assertCountEquals(1);ui.onNodeWithText("未知歌手").assertDoesNotExist();shot("app-metadata-detail")
        ui.onNodeWithContentDescription("返回日记").performClick();ui.onNodeWithContentDescription("返回日记").performClick();ui.onNodeWithTag("home-scroll").performScrollToNode(hasText("常听歌手"));ui.onNodeWithTag("artist-card:旺仔小乔").assertExists();shot("app-metadata-artist")
    }
    private fun openHistory(){ui.onNodeWithTag("home-scroll").performScrollToNode(hasText("常听歌手"));shot("app-artists");ui.onNodeWithTag("home-scroll").performScrollToNode(hasTestTag("day-history-link"));ui.onNodeWithTag("day-history-link").performClick();ui.waitForIdle()}
    private fun resetCarousel(count:Int=24){ui.runOnUiThread{ui.activity.controller.stop()};ui.mainClock.autoAdvance=false;ui.mainClock.advanceTimeByFrame();ui.onNodeWithTag("cover-carousel").performScrollToIndex(CoverCarousel.firstPage(count));ui.mainClock.advanceTimeByFrame();ui.runOnUiThread{ui.activity.controller.start()};ui.mainClock.advanceTimeByFrame();ui.waitForIdle()}
    private fun shot(name:String){System.getProperty("diary.preview.dir")?.let{dir->ui.waitForIdle();ui.runOnUiThread{val view=android.view.inspector.WindowInspector.getGlobalWindowViews().lastOrNull{it.isShown&&it.width>0&&it.height>0}?:ui.activity.window.decorView;val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888);view.draw(android.graphics.Canvas(bitmap));val file=java.io.File(dir,"$name.png");file.parentFile?.mkdirs();file.outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}}}
}

