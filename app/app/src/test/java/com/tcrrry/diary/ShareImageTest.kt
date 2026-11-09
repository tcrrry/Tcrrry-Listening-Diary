package com.tcrrry.diary

import android.content.*
import android.database.Cursor
import android.graphics.*
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class) @Config(sdk=[34,35]) @GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShareImageTest{
    private val day=LocalDate.of(2026,10,23)
    @Test fun differentAlbumColorsChangeTheExportedPaperWithTheSameBundledDecor(){
        val context=RuntimeEnvironment.getApplication()
        val document=shareDocument(demoReport(day),ShareImageOptions(day,DiaryRange.DAY))
        val decor=context.assets.open("share/poster-decor-light.png").use{BitmapFactory.decodeStream(it)}
        val colors=listOf(Color.rgb(210,45,72),Color.rgb(35,90,200),Color.rgb(30,155,100)).map{color->
            val cover=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888).apply{eraseColor(color)}
            val palette=SharePalette.create(false,cover)
            val painter=ShareImagePainter(document,palette,null,{_,_->cover.copy(Bitmap.Config.ARGB_8888,false)},decor)
            try{val poster=painter.draw(sharePages(document).first(),0,1);try{poster.getPixel(6,1370)}finally{poster.recycle()}}finally{painter.close();cover.recycle()}
        }
        decor?.recycle();assertEquals(3,colors.distinct().size)
    }
    @Test fun fixedTemplateAssetsHaveRealTransparencyAndDefaultReportIsOnePoster(){
        val context=RuntimeEnvironment.getApplication()
        for(theme in listOf("light","dark")){
            val decor=context.assets.open("share/poster-decor-$theme.png").use{checkNotNull(BitmapFactory.decodeStream(it))}
            assertTrue(decor.hasAlpha());assertEquals(0,Color.alpha(decor.getPixel(decor.width/2,decor.height/2)))
            assertEquals(0,Color.alpha(decor.getPixel(decor.width/2,decor.height-10)));decor.recycle()
        }
        val doc=shareDocument(demoReport(day),ShareImageOptions(day,DiaryRange.DAY))
        assertEquals(1,sharePages(doc).size);assertEquals(3,sharePages(doc).single().sections.single().rows.size)
        assertEquals(480,sharePages(doc).single().height);assertTrue(doc.heroArtist.isNotBlank());assertNotNull(doc.heroAlbum)
        assertEquals(3,doc.features.size)
    }
    @Test fun referencePosterCompositionRendersAllPeriodsAndBothThemes()=runBlocking{
        val context=RuntimeEnvironment.getApplication()
        val reference=System.getProperty("diary.preview.template")?.let{BitmapFactory.decodeFile(it)}
        val names=listOf("MIREI","Yuika","AYANE","Uru","MIREI")
        val albums=listOf("Answer","Suikidakara","#Twenty","プロローグ","Memories")
        val ids=listOf("1781888430","1706828627","1736077483","1536126606","1711411579")
        val rows=demoReport(day).history.mapIndexed{i,p->p.copy(artist=names[i%5],album=albums[i%5],title=albums[i%5])}
        val report=demoReport(day).copy(history=rows,segments=rows)
        fun artwork(id:String):Bitmap?{
            val source=reference?:return null
            val box=if(id.startsWith("artist:"))Rect(620,35,1050,489)else when(id){ids[0]->Rect(582,506,715,641);ids[1]->Rect(510,683,763,935);ids[2]->Rect(66,666,476,965);ids[3]->Rect(806,704,982,881);else->Rect(582,506,715,641)}
            return Bitmap.createBitmap(source,box.left,box.top,box.width(),box.height())
        }
        try{for(range in DiaryRange.entries)for(dark in listOf(false,true)){
            val document=shareDocument(report,ShareImageOptions(day,range),demo=true)
            val decor=context.assets.open("share/poster-decor-${if(dark)"dark"else"light"}.png").use{BitmapFactory.decodeStream(it)}
            val files=try{renderShareImages(context.cacheDir,document,dark,decoration=decor){id,_->artwork(id)}}finally{decor?.recycle()}
            assertEquals(1,files.size);assertEquals(1080,files[0].width);assertEquals(1440,files[0].height)
            System.getProperty("diary.preview.dir")?.let{dir->File(dir).mkdirs();files[0].file.copyTo(File(dir,"template-${range.name}-${if(dark)"dark"else"light"}.png"),true)}
        }}finally{reference?.recycle()}
    }
    @Test fun selectedModulesAndSortingControlWhatIsPrinted(){
        val rows=demoReport(day).history.take(3).mapIndexed{i,p->p.copy(title="Song $i",album="Album $i",effective=(3-i).toLong(),ms=(i+1)*60000L)}
        val report=DiaryReport(history=rows,segments=rows)
        val options=ShareImageOptions(day,DiaryRange.DAY,setOf(ShareModule.SONGS),ranking=ShareRanking.TIME)
        val document=shareDocument(report,options)
        assertEquals(listOf(ShareModule.SONGS),document.sections.map{it.module})
        assertEquals("Song 2",document.sections.single().rows.first().title)
        assertEquals("Song 0",shareDocument(report,options.copy(ranking=ShareRanking.PLAYS)).sections.single().rows.first().title)
        assertFalse(document.sections.any{it.module==ShareModule.ARTISTS})
    }
    @Test fun trendAggregatesUseWeekMonthAndYearBoundaries(){
        for(range in DiaryRange.entries){
            val report=periodDemoReport(day,range)
            val section=shareDocument(report,ShareImageOptions(day,range,setOf(ShareModule.TREND))).sections.single()
            assertEquals(report.history.sumOf{it.ms},section.values.sum())
            assertEquals(section.values.size,section.labels.size)
            if(range==DiaryRange.YEAR)assertEquals(12,section.values.size)
            if(range==DiaryRange.WEEK)assertEquals(7,section.values.size)
        }
    }
    @Test fun monthlyTrendHonorsDatabaseListeningDayAssignments(){
        val p=demoReport(day).history.first().copy(listeningDay="2026-10-22")
        val section=shareDocument(DiaryReport(history=listOf(p),segments=listOf(p),dayTotals=mapOf("2026-10-22" to p.ms)),ShareImageOptions(day,DiaryRange.MONTH,setOf(ShareModule.TREND))).sections.single()
        assertEquals(p.ms,section.values.sum());assertEquals(p.ms,section.values[3])
    }
    @Test fun largeRankingsPaginateAtCardBoundariesAndKeepAllRanks(){
        val base=demoReport(day).history.first()
        val rows=(0..29).map{base.copy(id="id$it",visit="v$it",title="Track $it",artist="Artist $it",album="Album $it")}
        val document=shareDocument(DiaryReport(history=rows,segments=rows),ShareImageOptions(day,DiaryRange.DAY,ShareModule.entries.toSet(),20))
        val pages=sharePages(document)
        assertTrue(pages.size>1);assertTrue(pages.all{it.height==480})
        for(module in listOf(ShareModule.SONGS,ShareModule.ALBUMS,ShareModule.ARTISTS)){
            assertEquals(20,pages.flatMap{it.sections}.filter{it.module==module}.sumOf{it.rows.size})
            assertEquals(listOf(1,4,7,10,13,16,19),document.sections.filter{it.module==module}.map{it.startRank})
        }
    }
    @Test fun exportedPngFilesAreCompleteBoundedAndReadableInBothThemes()=runBlocking{
        val context=RuntimeEnvironment.getApplication();val fixture=File(context.cacheDir,"share-art").apply{mkdirs()}
        val bitmap=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.rgb(75,103,178))}
        val document=shareDocument(periodDemoReport(day,DiaryRange.MONTH),ShareImageOptions(day,DiaryRange.MONTH,ShareModule.entries.toSet()),demo=true)
        try{for(dark in listOf(false,true)){
            val decor=context.assets.open("share/poster-decor-${if(dark)"dark"else"light"}.png").use{BitmapFactory.decodeStream(it)}
            val files=renderShareImages(context.cacheDir,document,dark,decoration=decor){id,_->
                val index=listOf("1781888430","1706828627","1736077483","1536126606","1711411579").indexOf(id).coerceAtLeast(0)
                System.getProperty("diary.preview.art")?.let{BitmapFactory.decodeFile(File(it,"test$index.jpg").path)}?:bitmap.copy(Bitmap.Config.ARGB_8888,false)
            }
            decor?.recycle()
            assertEquals(sharePages(document).size,files.size)
            files.forEachIndexed{i,file->
                val read=checkNotNull(BitmapFactory.decodeFile(file.file.path));assertEquals(1080,read.width);assertTrue(read.height==1440);assertEquals(file.height,read.height)
                assertEquals(255,Color.alpha(read.getPixel(0,0)));assertTrue(file.file.length()>10000);read.recycle()
                System.getProperty("diary.preview.dir")?.let{dir->File(dir).mkdirs();file.file.copyTo(File(dir,"share-poster-${if(dark)"dark"else"light"}-${i+1}.png"),true)}
            }
        }}finally{bitmap.recycle();fixture.delete()}
    }
    @Test fun localExportArtworkNeverFetchesAndReturnsAnOwnedBitmap(){
        val context=RuntimeEnvironment.getApplication();val dir=File(context.cacheDir,"share-owned-art").apply{mkdirs()}
        val image=Bitmap.createBitmap(160,160,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.MAGENTA)}
        File(dir,"${ArtworkKeys.hash("a")}.jpg").outputStream().use{image.compress(Bitmap.CompressFormat.JPEG,90,it)};image.recycle()
        val artwork=DiaryArtwork(dir,{_,_->error("export must not fetch")},0)
        try{val first=checkNotNull(artwork.exportCached("a",160));val second=checkNotNull(artwork.exportCached("a",160));first.recycle();assertFalse(second.isRecycled);assertNull(artwork.exportCached("missing",160));second.recycle()}finally{artwork.close()}
    }
    @Test fun systemSharingIncludesAllImagesAndTemporaryReadGrants(){
        val uris=arrayListOf(Uri.parse("content://com.tcrrry.listeningdiary.app.share-images/share_images/one.png"),Uri.parse("content://com.tcrrry.listeningdiary.app.share-images/share_images/two.png"))
        val single=shareImageIntent(ArrayList(uris.take(1)));assertEquals(Intent.ACTION_SEND,single.action);assertEquals("image/png",single.type)
        assertEquals(uris.first(),single.getParcelableExtra(Intent.EXTRA_STREAM,Uri::class.java));assertTrue(single.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0)
        assertEquals(0,single.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val multiple=shareImageIntent(uris);assertEquals(Intent.ACTION_SEND_MULTIPLE,multiple.action);assertEquals(2,multiple.clipData!!.itemCount)
        assertEquals(uris,multiple.getParcelableArrayListExtra(Intent.EXTRA_STREAM,Uri::class.java))
        assertThrows(IllegalArgumentException::class.java){shareImageIntent(arrayListOf(Uri.parse("file:///private-ledger.json")))}
    }
    @Test fun fileProviderIsPrivateAndOnlyExposesTheImageCache(){
        val context=RuntimeEnvironment.getApplication()
        val info=context.packageManager.resolveContentProvider("${context.packageName}.share-images",android.content.pm.PackageManager.GET_META_DATA)!!
        assertFalse(info.exported);assertTrue(info.grantUriPermissions)
        val parser=context.resources.getXml(info.metaData.getInt("android.support.FILE_PROVIDER_PATHS"))
        val paths=mutableListOf<Pair<String,String>>()
        parser.use{while(it.next()!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT)if(it.eventType==org.xmlpull.v1.XmlPullParser.START_TAG&&it.name!="paths")paths+=it.name to it.getAttributeValue(null,"path")}
        assertEquals(listOf("cache-path" to "share-images/"),paths)
    }
    private class Gallery(val dir:File,val failAt:Int=0):ContentProvider(){
        val files=linkedMapOf<Uri,File>();val pending=linkedMapOf<Uri,Int>();var inserted=0;var deleted=0
        override fun onCreate()=true
        override fun getType(uri:Uri)="image/png"
        override fun query(uri:Uri,p:Array<out String>?,s:String?,a:Array<out String>?,o:String?):Cursor?=null
        override fun insert(uri:Uri,values:ContentValues?):Uri{val next=Uri.withAppendedPath(uri,"${++inserted}");files[next]=File(dir,"gallery$inserted.png");pending[next]=values!!.getAsInteger(MediaStore.Images.Media.IS_PENDING);return next}
        override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor{if(inserted==failAt)error("simulated full storage");return ParcelFileDescriptor.open(files.getValue(uri),ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE)}
        override fun update(uri:Uri,v:ContentValues?,s:String?,a:Array<out String>?):Int{pending[uri]=v!!.getAsInteger(MediaStore.Images.Media.IS_PENDING);return 1}
        override fun delete(uri:Uri,s:String?,a:Array<out String>?):Int{files.remove(uri)?.delete();pending.remove(uri);deleted++;return 1}
    }
    @Test fun galleryPublishesCompletedBatchAndRollsBackFailedBatch()=runBlocking{
        val context=RuntimeEnvironment.getApplication();val dir=File(context.cacheDir,"gallery-test").apply{mkdirs()}
        val source=File(dir,"source.png").apply{writeBytes(byteArrayOf(5,6,7))};val images=List(2){ShareImageFile(source,100,100)}
        val success=Gallery(dir);success.attachInfo(context,android.content.pm.ProviderInfo().apply{authority="media"});ShadowContentResolver.registerProviderInternal("media",success)
        val uris=saveShareImages(context,images);assertEquals(2,uris.size);assertTrue(success.pending.values.all{it==0});success.files.values.forEach{assertArrayEquals(source.readBytes(),it.readBytes())}
        val failure=Gallery(dir,2);failure.attachInfo(context,android.content.pm.ProviderInfo().apply{authority="media"});ShadowContentResolver.registerProviderInternal("media",failure)
        try{saveShareImages(context,images);fail("must fail")}catch(_:IllegalStateException){}
        assertTrue(failure.files.isEmpty());assertEquals(2,failure.deleted)
    }
}
