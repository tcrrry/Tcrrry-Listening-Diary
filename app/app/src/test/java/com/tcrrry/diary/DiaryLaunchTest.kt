package com.tcrrry.diary
import android.graphics.*
import android.os.Looper
import android.view.View
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import java.io.File
import java.time.Duration
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode

@RunWith(RobolectricTestRunner::class) @Config(sdk=[35],qualifiers="w412dp-h915dp-xxhdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiaryLaunchTest {
    @Test fun realActivityLaunchesAndRendersDemo(){
        val activity=Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        try {
            val a=activity.get();a.controller.network(false)
            seedPreviewArt(a);seedArtistArt(a)
            a.controller.preview(true)
            a.setContent{CompositionLocalProvider(LocalInspectionMode provides true){DiaryApp(a.controller,{},{})}}
            val root=a.window.decorView
            repeat(8){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));root.measure(View.MeasureSpec.makeMeasureSpec(1236,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2745,View.MeasureSpec.EXACTLY));root.layout(0,0,1236,2745)}
            assertEquals(24,a.controller.report.history.size)
            System.getProperty("diary.preview.dir")?.let{dir->val file=File(dir,"app-today-demo.png");file.parentFile.mkdirs();val bitmap=Bitmap.createBitmap(1236,2745,Bitmap.Config.ARGB_8888);root.draw(Canvas(bitmap));file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}
        }finally{activity.pause().stop().destroy()}
    }
}
internal fun seedPreviewArt(a:android.app.Activity){System.getProperty("diary.preview.art")?.let{dir->listOf("1781888430","1706828627","1736077483","1536126606","1711411579").forEachIndexed{i,id->File(dir,"test$i.jpg").takeIf{it.isFile}?.copyTo(File(a.filesDir,"art/${ArtworkKeys.hash(id)}.jpg"),true)}}}

internal fun seedArtistArt(a:android.app.Activity){System.getProperty("diary.preview.artist")?.let{dir->listOf("由薫","Vaundy","宇多田ヒカル","Aimer","緑黄色社会").forEachIndexed{i,name->File(dir,"artist$i.jpg").takeIf{it.isFile}?.copyTo(File(a.filesDir,"art/${ArtworkKeys.hash("apple-artist-v1:artist:"+name)}.jpg"),true)}}}
