package com.tcrrry.diary

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],qualifiers="w412dp-h915dp-xxhdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShareImageInteractionTest{
    @get:Rule val ui=createAndroidComposeRule<MainActivity>()
    @Test fun editorGeneratesPreviewWithoutChangingDiarySelectionOrSavingAutomatically(){
        val activity=ui.activity;val c=activity.controller
        ui.runOnUiThread{c.preview(true);c.network(false);activity.setContent{CompositionLocalProvider(LocalInspectionMode provides true){DiaryApp(c,{},{})}}}
        ui.waitForIdle();val day=c.day;val range=c.range
        ui.onNodeWithContentDescription("设置").performClick();ui.waitForIdle()
        ui.onNodeWithTag("open-share-images").performScrollTo().performClick();ui.waitForIdle()
        ui.onNodeWithTag("share-image-screen").assertExists()
        for(module in listOf(ShareModule.OVERVIEW,ShareModule.SONGS))ui.onNodeWithTag("share-module:${module.name}").performClick()
        ui.onNodeWithTag("share-generate").assertIsNotEnabled()
        ui.onNodeWithTag("share-module:OVERVIEW").performClick()
        ui.onNode(hasText("周") and hasAnyAncestor(hasTestTag("share-range")),useUnmergedTree=true).performClick()
        ui.onNodeWithTag("share-generate").performClick()
        ui.waitUntil(15000){org.robolectric.shadows.ShadowLooper.idleMainLooper();ui.onAllNodesWithTag("share-preview-image").fetchSemanticsNodes().isNotEmpty()}
        ui.onNodeWithTag("share-save").assertIsEnabled();ui.onNodeWithTag("share-send").assertIsEnabled()
        Assert.assertEquals(day,c.day);Assert.assertEquals(range,c.range)
        ui.onNodeWithText("返回设置").performClick();ui.waitForIdle();ui.onNodeWithTag("share-generate").assertExists()
        ui.onNodeWithText("返回",useUnmergedTree=true).performClick();ui.waitForIdle();ui.onNodeWithTag("share-image-screen").assertDoesNotExist();ui.onNodeWithTag("home-scroll").assertExists()
    }
}
