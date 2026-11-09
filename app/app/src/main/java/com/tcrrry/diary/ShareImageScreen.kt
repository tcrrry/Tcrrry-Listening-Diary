@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.tcrrry.diary

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import java.io.File
import java.time.*
import kotlinx.coroutines.*

@Composable internal fun ShareImageScreen(c:DiaryController,close:()->Unit){
    var range by rememberSaveable{mutableStateOf(c.range)}
    var dayText by rememberSaveable{mutableStateOf(c.day.toString())}
    val day=LocalDate.parse(dayText)
    var modules by remember{mutableStateOf(ShareImageOptions(day,range).modules)}
    var limit by rememberSaveable{mutableIntStateOf(3)}
    var ranking by rememberSaveable{mutableStateOf(ShareRanking.PLAYS)}
    var signature by rememberSaveable{mutableStateOf(true)}
    var calendar by remember{mutableStateOf(false)}
    var result by remember{mutableStateOf<List<ShareImageFile>>(emptyList())}
    var busy by remember{mutableStateOf(false)};var saving by remember{mutableStateOf(false)}
    var saved by remember{mutableStateOf(false)};var message by remember{mutableStateOf<String?>(null)}
    val scope=rememberCoroutineScope();val dark=MaterialTheme.colorScheme.background.luminance()<.5f
    val typeface=c.amTypeface
    fun generate(){
        if(busy||modules.isEmpty())return
        val options=ShareImageOptions(day,range,modules.toSet(),limit,ranking,signature)
        // Freeze the request; changing period in this editor never navigates the diary behind it.
        val demo=c.demo;val policy=c.dayPolicy;val use12Hour=c.use12Hour
        val current=c.report.takeIf{!c.loading&&c.day==day&&c.range==range}
        val metadata=c.catalogFacts.toMap();val artistContext=c.summaryFacts.songs
        busy=true;message=null
        scope.launch{
            try{
                val report=current?:withContext(Dispatchers.IO){if(demo)periodDemoReport(options.day,options.range)else DiaryDatabase.get(c.activity).readReport(bounds(options.day,options.range),policy)}
                if(report.history.isEmpty()){message="所选周期还没有听歌记录";return@launch}
                val cached=if(ShareModule.STYLES in options.modules)withContext(Dispatchers.IO){CatalogMetadata.cached(File(c.activity.filesDir,"art"),(report.history+artistContext).map{it.id}.distinct()).objects().map{CatalogFact.parse(it)}.associateBy{it.id}}else emptyMap()
                val document=withContext(Dispatchers.Default){shareDocument(report,options,cached+metadata,demo,use12Hour,artistContext+report.history)}
                val images=withContext(Dispatchers.IO){
                    val decor=c.activity.assets.open("share/poster-decor-${if(dark)"dark"else"light"}.png").use{BitmapFactory.decodeStream(it)}
                    try{renderShareImages(c.activity.cacheDir,document,dark,typeface,decor){id,edge->c.images.exportCached(id,edge)}}finally{decor?.recycle()}
                }
                result=images;saved=false
            }catch(e:CancellationException){throw e}catch(_:Exception){message="生成失败，请重试；请确认手机仍有可用空间"}finally{busy=false}
        }
    }
    Dialog(onDismissRequest={if(!saving){if(result.isNotEmpty())result=emptyList()else close()}},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)){
        BackHandler{if(!saving){if(result.isNotEmpty())result=emptyList()else close()}}
        Surface(Modifier.fillMaxSize().testTag("share-image-screen"),color=MaterialTheme.colorScheme.background){
            Column(Modifier.fillMaxSize().systemBarsPadding()){
                Row(Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically){
                    TextButton(onClick={if(result.isNotEmpty())result=emptyList()else close()},enabled=!saving){Text(if(result.isNotEmpty())"返回设置"else"返回")}
                    Text(if(result.isNotEmpty())"分享预览"else"生成分享图片",Modifier.weight(1f),fontSize=22.sp,fontWeight=FontWeight.Bold)
                    if(result.isNotEmpty())Text("${result.size}张",fontSize=12.sp,color=Muted)
                }
                if(result.isEmpty()){
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal=24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){
                        Text("把自己的音乐记忆，留成一张海报。",fontSize=14.sp,color=Muted)
                        Row(Modifier.testTag("share-range")){SmallTabs(DiaryRange.entries.map{it.label},range.ordinal){if(!busy)range=DiaryRange.entries[it]}}
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                            TextButton(onClick={dayText=periodShift(day,range,-1).toString()},enabled=!busy){Text("‹")}
                            TextButton(onClick={calendar=true},enabled=!busy,modifier=Modifier.weight(1f).testTag("share-date")){Text(periodCaption(day,range))}
                            TextButton(onClick={dayText=periodShift(day,range,1).toString()},enabled=!busy){Text("›")}
                        }
                        Text("选择图片内容",fontSize=18.sp,fontWeight=FontWeight.SemiBold)
                        FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
                            ShareModule.entries.forEach{module->FilterChip(selected=module in modules,onClick={modules=if(module in modules)modules-module else modules+module},enabled=!busy,label={Text(module.title)},modifier=Modifier.testTag("share-module:${module.name}"))}
                        }
                        Text("排行设置",fontSize=18.sp,fontWeight=FontWeight.SemiBold)
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf(3,5,10,20).forEach{n->FilterChip(selected=limit==n,onClick={limit=n},enabled=!busy,label={Text("前${n}名")})}}
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){ShareRanking.entries.forEach{r->FilterChip(selected=ranking==r,onClick={ranking=r},enabled=!busy,label={Text(r.label)})}}
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("保留「听歌日记」署名",Modifier.weight(1f),fontSize=14.sp);Switch(signature,{signature=it},enabled=!busy)}
                        Text("3:4 海报，每张排行最多3名，更多内容组成系列海报。装饰已内置，照片与底色在本地合成。预览不会写入相册。",fontSize=12.sp,color=Muted)
                        if(c.demo)Text("当前为交互演示，图片会标注示例数据。",fontSize=12.sp,color=Accent)
                        Spacer(Modifier.height(8.dp))
                    }
                }else{
                    LazyColumn(Modifier.weight(1f).testTag("share-preview-list"),contentPadding=PaddingValues(horizontal=18.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
                        items(result,key={it.file.name}){file->
                            val bitmap by produceState<android.graphics.Bitmap?>(null,file.file){value=withContext(Dispatchers.IO){BitmapFactory.decodeFile(file.file.path,BitmapFactory.Options().apply{inSampleSize=2})}}
                            val current=bitmap
                            if(current!=null)Image(current.asImageBitmap(),"分享图片预览",Modifier.fillMaxWidth().aspectRatio(file.width.toFloat()/file.height).clip(RoundedCornerShape(16.dp)).testTag("share-preview-image"))
                            else Box(Modifier.fillMaxWidth().height(220.dp),contentAlignment=Alignment.Center){CircularProgressIndicator()}
                        }
                    }
                }
                message?.let{Text(it,Modifier.padding(horizontal=24.dp,vertical=10.dp).testTag("share-message"),color=Accent,fontSize=13.sp)}
                if(result.isEmpty())Button(onClick={generate()},enabled=!busy&&modules.isNotEmpty(),modifier=Modifier.fillMaxWidth().padding(horizontal=24.dp,vertical=16.dp).testTag("share-generate")){
                    if(busy){CircularProgressIndicator(Modifier.size(16.dp),strokeWidth=2.dp);Spacer(Modifier.width(10.dp))};Text(if(busy)"正在生成海报…"else"生成预览")
                }else Row(Modifier.fillMaxWidth().padding(horizontal=18.dp,vertical=14.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)){
                    OutlinedButton(onClick={
                        saving=true;message=null;val images=result
                        scope.launch{try{withContext(Dispatchers.IO){saveShareImages(c.activity,images)};saved=true;message="已保存${images.size}张到相册 · 听歌日记"}catch(e:CancellationException){throw e}catch(_:Exception){message="保存失败，请检查剩余空间后重试"}finally{saving=false}}
                    },enabled=!saving&&!saved,modifier=Modifier.weight(1f).testTag("share-save")){Text(if(saving)"正在保存…"else if(saved)"已保存"else"保存图片")}
                    Button(onClick={runCatching{c.activity.startActivity(Intent.createChooser(shareImageIntent(c.activity,result),"分享听歌日记"))}.onFailure{message="未能打开系统分享，请先保存图片"}},enabled=!saving,modifier=Modifier.weight(1f).testTag("share-send")){Text("系统分享")}
                }
            }
        }
        if(calendar){
            val picker=rememberDatePickerState(initialSelectedDateMillis=day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            DatePickerDialog(onDismissRequest={calendar=false},confirmButton={TextButton(onClick={picker.selectedDateMillis?.let{dayText=Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()};calendar=false}){Text("确定")}},dismissButton={TextButton(onClick={calendar=false}){Text("取消")}}){DatePicker(picker)}
        }
    }
}
