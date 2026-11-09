@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.tcrrry.diary

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.material3.Text as MaterialText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.*
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontSynthesis
import android.os.SystemClock
import java.time.*
import java.time.format.DateTimeFormatter
import kotlin.math.*


@Composable internal fun Text(text:String,modifier:Modifier=Modifier,color:Color=Color.Unspecified,fontSize:TextUnit=14.sp,fontWeight:FontWeight?=FontWeight.Medium,letterSpacing:TextUnit=TextUnit.Unspecified,maxLines:Int=Int.MAX_VALUE,overflow:TextOverflow=TextOverflow.Clip,textAlign:TextAlign?=null){
    val weight=FontWeight(((fontWeight?.weight?:500)+100).coerceIn(600,900))
    val base=LocalAMTypeface.current
    val family=remember(base,weight){base?.let{FontFamily(android.graphics.Typeface.create(it,weight.weight,false))}?:FontFamily.SansSerif}
    MaterialText(text=text,modifier=modifier,color=color,fontSize=fontSize,fontWeight=weight,fontFamily=family,style=LocalTextStyle.current.copy(fontSynthesis=FontSynthesis.All),letterSpacing=letterSpacing,maxLines=maxLines,overflow=overflow,textAlign=textAlign,lineHeight=fontSize*1.28f)
}

private val LocalAMTypeface=staticCompositionLocalOf<android.graphics.Typeface?>{null}
internal val LocalAlbumSurface=staticCompositionLocalOf<()->Color>{{Color.White}}
private val LocalDiaryFont=staticCompositionLocalOf<FontFamily>{FontFamily.SansSerif}
internal val Ink:Color @Composable get()=MaterialTheme.colorScheme.background
internal val Muted:Color @Composable get()=MaterialTheme.colorScheme.onSurfaceVariant
internal val Accent:Color @Composable get()=MaterialTheme.colorScheme.primary
private val Pink=Color(0xffff91bd)
private val Palette=listOf(Color(0xffef91b7),Color(0xffd1a5fa),Color(0xff8da5ff),Color(0xffefa16f))
internal val PeriodSeekFractionKey=SemanticsPropertyKey<Float>("PeriodSeekFraction")
internal val HistoryScrollIndexKey=SemanticsPropertyKey<Int>("HistoryScrollIndex")
internal val PreparedPeriodsKey=SemanticsPropertyKey<String>("PreparedPeriods")
internal data class PreparedPeriod(val report:DiaryReport,val computed:PeriodComputed?,val revision:Long,val metadataEpoch:Long=0,val provisional:Boolean=false)
private data class Selection(val play:Play,val key:String,val artist:String?=null,val album:String?=null)
internal class DetailActions(val shared:SharedTransitionScope,val visibility:AnimatedVisibilityScope,val artist:(String,String)->Unit,val album:(Play,String)->Unit,val track:(Play,String)->Unit)
internal val LocalDetailActions=staticCompositionLocalOf<DetailActions?>{null}
internal val HistoryPanelKey=SemanticsPropertyKey<String>("HistoryPanel")
internal val HistoryCoverKey=SemanticsPropertyKey<String>("HistoryCover")
internal class HistoryMotion(val shared:SharedTransitionScope,val visibility:AnimatedVisibilityScope,val prefix:String,val enabled:Boolean=true)
internal val LocalHistoryMotion=staticCompositionLocalOf<HistoryMotion?>{null}
@Composable internal fun Modifier.historyPanel():Modifier {
    val motion=LocalHistoryMotion.current?:return this
    if(!motion.enabled)return this
    return with(motion.shared){this@historyPanel.sharedBounds(rememberSharedContentState("history-panel:${motion.prefix}"),motion.visibility,enter=fadeIn(tween(200)),exit=fadeOut(tween(160)),boundsTransform={_,_->spring(.88f,190f)}).semantics{this[HistoryPanelKey]=motion.prefix}}
}
@Composable internal fun Modifier.historyCover(key:String):Modifier {
    val motion=LocalHistoryMotion.current?:return this
    if(!motion.enabled)return this
    return with(motion.shared){this@historyCover.sharedElement(rememberSharedContentState("history-cover:${motion.prefix}:$key"),motion.visibility,boundsTransform={_,_->spring(.88f,210f)}).semantics{this[HistoryCoverKey]="${motion.prefix}:$key"}}
}

/** Only participants may enter the overlay; keep them there until shared bounds finish too. */
@Composable internal fun ownedSharedOverlay(shared:SharedTransitionScope,visibility:AnimatedVisibilityScope,permitted:Boolean=true):Boolean {
    val moving=visibility.transition.currentState!=visibility.transition.targetState || visibility.transition.isRunning
    val sharedMoving=shared.isTransitionActive
    val history=LocalHistoryMotion.current?.visibility
    val historyMoving=history!=null && history!==visibility && (history.transition.currentState!=history.transition.targetState || history.transition.isRunning)
    val allowed=permitted&&!historyMoving
    var participated by remember(shared,visibility){mutableStateOf(false)}
    SideEffect {
        if(!allowed || (!moving&&!sharedMoving))participated=false
        else if(moving)participated=true
    }
    return allowed && (moving || (participated&&sharedMoving))
}
internal val DetailArtworkKey=SemanticsPropertyKey<String>("DetailArtwork")
internal val DetailArtworkMatchedKey=SemanticsPropertyKey<Boolean>("DetailArtworkMatched")
@Composable internal fun Modifier.detailSource(key:String,enabled:Boolean=true,shape:androidx.compose.ui.graphics.Shape=RoundedCornerShape(17.dp)):Modifier {
    val context=LocalDetailActions.current ?: return this
    if(!enabled)return this
    return with(context.shared){
        val content=rememberSharedContentState(key)
        this@detailSource.sharedElement(content,context.visibility,renderInOverlayDuringTransition=ownedSharedOverlay(context.shared,context.visibility),clipInOverlayDuringTransition=OverlayClip(shape),boundsTransform={_,_->tween(360,easing=FastOutSlowInEasing)}).clip(shape)
            .semantics{this[DetailArtworkKey]=key;this[DetailArtworkMatchedKey]=content.isMatchFound}
    }
}
internal val DetailCaptionAlphaKey=SemanticsPropertyKey<Float>("DetailCaptionAlpha")
/** Reveal labels gently after detail settles, without changing their measured size or position. */
@Composable internal fun Modifier.detailCaptionFade():Modifier {
    val transition=LocalDetailActions.current?.visibility?.transition
    val ready=transition==null || (transition.currentState==EnterExitState.Visible && transition.targetState==EnterExitState.Visible && !transition.isRunning)
    val opacity by animateFloatAsState(if(ready)1f else 0f,tween(if(ready)180 else 90,easing=LinearEasing),label="detail caption fade")
    return this.graphicsLayer { alpha=opacity }.semantics { this[DetailCaptionAlphaKey]=opacity }
}
internal fun minutes(ms:Long)="${ms/60000}"

private fun duration(ms:Long)=if(ms<60000)"${ms/1000} 秒" else if(ms<3600000)"${ms/60000} 分钟" else "${ms/3600000} 小时 ${(ms%3600000)/60000} 分钟"

/** A single press interaction owns both visual feedback and the click; springs can reverse mid-flight. */
@Composable internal fun Modifier.press(action:()->Unit):Modifier {
    val interaction=remember{MutableInteractionSource()};val down by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if(down).965f else 1f,spring(.8f,450f),label="press")
    val haptic=LocalHapticFeedback.current
    return graphicsLayer{scaleX=scale;scaleY=scale}.clickable(interactionSource=interaction,indication=null){haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove);action()}
}
@Composable private fun glass(source:Backdrop,radius:Dp=26.dp,outline:Shape=RoundedCornerShape(radius),wash:Float?=null):Modifier {
    val dark=MaterialTheme.colorScheme.background.luminance()<.5f
    val panel=MaterialTheme.colorScheme.surface
    val stroke=MaterialTheme.colorScheme.outlineVariant
    if(LocalInspectionMode.current||!LocalView.current.isHardwareAccelerated)return Modifier.background(panel.copy(alpha=wash?:.94f),outline).border(.7.dp,stroke,outline)
    return Modifier.drawBackdrop(
    backdrop=source,shape={outline},effects={vibrancy();blur(10.dp.toPx());lens(12.dp.toPx(),20.dp.toPx())},
    onDrawSurface={drawRect(panel.copy(alpha=wash?:if(dark).62f else .76f))}
).border(.7.dp,stroke,outline)
}

@Composable fun DiaryApp(c:DiaryController,onImport:()->Unit,onExport:()->Unit,onAppleImport:()->Unit={}){
    val dark=when(c.theme){DiaryThemeChoice.SYSTEM->isSystemInDarkTheme();DiaryThemeChoice.LIGHT->false;DiaryThemeChoice.DARK->true}
    val scheme=if(dark)darkColorScheme(primary=Color(0xfffb6f8b),background=Color(0xff0a0b0e),surface=Color(0xff191a20),onSurface=Color(0xfff3f0fa),onSurfaceVariant=Color(0xffa8adb8),outlineVariant=Color.White.copy(alpha=.12f))else lightColorScheme(primary=Color(0xffcf315c),background=Color(0xfffffdfc),surface=Color.White,onSurface=Color(0xff161619),onSurfaceVariant=Color(0xff77787e),outlineVariant=Color.Black.copy(alpha=.08f))
    val font=remember(c.amTypeface){c.amTypeface?.let{FontFamily(it)}?:FontFamily.SansSerif}
    SideEffect{val appearance=android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;c.activity.window.insetsController?.setSystemBarsAppearance(if(dark)0 else appearance,appearance);c.activity.window.statusBarColor=android.graphics.Color.TRANSPARENT;c.activity.window.navigationBarColor=scheme.background.toArgb()}
    MaterialTheme(colorScheme=scheme){
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface,LocalDiaryFont provides font,LocalAMTypeface provides c.amTypeface){
        val backdrop=rememberLayerBackdrop();var settings by rememberSaveable{mutableStateOf(false)};var calendar by rememberSaveable{mutableStateOf(false)};var shareImages by rememberSaveable{mutableStateOf(false)}
        val timeline=remember{MusicTimelineState()}
        var selection by remember{mutableStateOf<Selection?>(null)};val selectionStack=remember{mutableStateListOf<Selection>()}
        fun pushSelection(value:Selection){selection?.let{selectionStack.add(it)};selection=value}
        fun popSelection(){selection=if(selectionStack.isNotEmpty())selectionStack.removeAt(selectionStack.lastIndex) else null}
        val saveable=rememberSaveableStateHolder()
        val snackbar=remember{SnackbarHostState()}
        var entered by remember{mutableStateOf(false)}
        LaunchedEffect(Unit){entered=true}
        val arrival by animateFloatAsState(if(entered)1f else 0f,spring(1f,170f),label="arrival")
        var coverTint by remember{mutableStateOf(listOf(Color(0xff322a48),Color(0xff20333e)))}
        val atmosphere=rememberAlbumAtmosphere(coverTint[0],dark,scheme.background)
        LaunchedEffect(c.notice){c.notice?.let{snackbar.showSnackbar(it);c.notice=null}}
        Box(Modifier.fillMaxSize().background(Ink)){
            // Only the ambient field is captured. The glass never samples its own output.
            Canvas(Modifier.fillMaxSize().layerBackdrop(backdrop)){
                drawRect(Brush.verticalGradient(listOf(atmosphere.top.value,atmosphere.paper.value),startY=0f,endY=size.height*.48f))
            }
            CompositionLocalProvider(LocalAlbumSurface provides {atmosphere.paper.value}){SharedTransitionLayout(Modifier.graphicsLayer{alpha=arrival;translationY=(1f-arrival)*16.dp.toPx()}) {
                AnimatedContent(selection,transitionSpec={(fadeIn(tween(220))+(if(targetState==null) EnterTransition.None else scaleIn(initialScale=.985f,animationSpec=tween(420)))) togetherWith fadeOut(tween(160))},label="report detail"){selected->
                    val detailVisibility=this
                    val detailActions=DetailActions(this@SharedTransitionLayout,detailVisibility,
                        {name,key->(if(c.summary)c.summaryFacts.report else c.report).history.firstOrNull{it.artist==name}?.let{pushSelection(Selection(it,key,artist=name))}},
                        {play,key->pushSelection(Selection(play,key,album=play.album))},{play,key->pushSelection(Selection(play,key))})
                    CompositionLocalProvider(LocalDetailActions provides detailActions){
                    if(selected==null)saveable.SaveableStateProvider("home"){
                        val summaryState=remember{SeekableTransitionState(c.summary)}
                        val summaryTransition=rememberTransition(summaryState,label="summary navigation")
                        LaunchedEffect(c.summary,c.range,c.periodSwipePosition,summaryTransition.totalDurationNanos){
                            val pos=c.periodSwipePosition
                            val target=when{!c.summary&&c.range==DiaryRange.YEAR&&pos!=null&&pos>3f->true;c.summary&&c.range==DiaryRange.YEAR&&c.retainedPeriodComputed?.let{it.report===c.report&&it.day==c.day&&it.range==DiaryRange.YEAR}==true&&pos!=null&&pos<4f->false;else->null}
                            if(target!=null)summaryState.seekTo(((if(target)(pos!!-3f) else (4f-pos!!)).coerceIn(0f,1f)*320_000_000f/summaryTransition.totalDurationNanos.coerceAtLeast(320_000_000L)).coerceIn(0f,1f),target)
                            else summaryState.animateTo(c.summary)
                        }
                        summaryTransition.AnimatedContent(transitionSpec={fadeIn(tween(150,delayMillis=170,easing=LinearEasing)) togetherWith fadeOut(tween(150,easing=LinearEasing))},contentKey={it}){showSummary->
                            val summaryVisibility=this
                            saveable.SaveableStateProvider(if(showSummary)"summary-hub"else"journal-home"){
                                if(showSummary)SummaryHub(c,backdrop,{settings=true},{p,key->pushSelection(Selection(p,key))},{coverTint=it},this@SharedTransitionLayout,summaryVisibility,timeline)
                                else Today(c,backdrop,{calendar=true},{settings=true},{p,key->pushSelection(Selection(p,key))},this@SharedTransitionLayout,detailVisibility,{coverTint=it},c.foreground&&!settings&&!calendar&&!shareImages&&!c.summary&&selection==null&&detailVisibility.transition.currentState==EnterExitState.Visible&&detailVisibility.transition.targetState==EnterExitState.Visible,summaryVisibility)
                            }
                        }
                    }else Detail(c,selected,backdrop,{popSelection()},this@SharedTransitionLayout,this@AnimatedContent,{coverTint=it})
                    }
                }
            }
            }
            SnackbarHost(snackbar,Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp))
        }
        BackHandler(selection!=null){popSelection()}
        LaunchedEffect(c.appleCompleted){if(c.appleCompleted>0)settings=false}
        AppleImportReview(c)
        if(shareImages)ShareImageScreen(c){shareImages=false}
        if(settings)ModalBottomSheet(onDismissRequest={settings=false},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=MaterialTheme.colorScheme.surface,dragHandle={BottomSheetDefaults.DragHandle()}){
            Column(Modifier.fillMaxWidth().weight(1f,false).verticalScroll(rememberScrollState()).padding(horizontal=26.dp).navigationBarsPadding().testTag("settings-scroll"),verticalArrangement=Arrangement.spacedBy(16.dp)){
                Text("让音乐留下痕迹",fontSize=26.sp,fontWeight=FontWeight.Bold)
                Button(onClick={c.connect()},enabled=!c.syncing,modifier=Modifier.fillMaxWidth()){Text(if(c.syncing)"正在同步…" else if(c.approved)"重新确认连接" else "连接 AM++ 采集器")}
                Text(if(c.approved)"已授权 AM++ · ${if(c.report.lastSync>0)"最近同步 ${diaryClock(c.report.lastSync,c.use12Hour)}" else "等待首次同步"}" else "先允许本机 AM++ 提供播放记录，再启用采集插件。",color=Muted)
                c.syncStatus?.let{Text(it,fontSize=14.sp,color=Muted)}
                if(c.approved)OutlinedButton(onClick={c.openHost()},modifier=Modifier.fillMaxWidth().testTag("open-am-sync")){Text("打开 AM++，恢复采集同步")}
                Text("插件负责本机记录；App 负责展示。即使 App 关闭，启用的插件仍会采集。安装后首次同步可能需要稍等，旧记录会从插件补传。",color=Muted,fontSize=13.sp)
                HorizontalDivider(color=Color.White.copy(alpha=.1f))
                Text("外观",fontWeight=FontWeight.Bold)
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){DiaryThemeChoice.entries.forEach{choice->FilterChip(selected=c.theme==choice,onClick={c.theme(choice)},label={Text(choice.label)})}}
                Text(if(c.amTypeface!=null)"已使用 AM 字体" else "字体随系统显示",fontSize=12.sp,color=Muted)
                Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("在线补全图片与音乐资料");Text("补全图片、专辑曲目和风格，缓存到本机",color=Muted,fontSize=12.sp)};Switch(c.imageNetwork,{c.network(it)})}
                Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("歌手图片",fontSize=16.sp);Text("自动获取 Apple Music 艺人头像",fontSize=12.sp,color=Muted)};Switch(modifier=Modifier.testTag("global-artist-photos"),checked=c.artistPhotosEnabled,onCheckedChange={c.artistPhotos(it)})}
                Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("12小时制");Text("以上午、下午显示时间",fontSize=12.sp,color=Muted)};Switch(modifier=Modifier.testTag("clock-12-hour"),checked=c.use12Hour,onCheckedChange={c.use12Hour(it)})}
                ListeningDaySettings(c)
                HorizontalDivider(color=Color.White.copy(alpha=.1f))
                Text("导入历史",fontWeight=FontWeight.Bold)
                AppleImportSettings(c,onAppleImport)
                HorizontalDivider(color=Color.White.copy(alpha=.1f))
                Text("导出与分享",fontWeight=FontWeight.Bold)
                Text("按日期或周期导出 CSV / TXT，方便保存或分享听歌记录。",fontSize=12.sp,color=Muted)
                ListeningDataExport(c)
                HorizontalDivider(color=Color.White.copy(alpha=.1f))
                Text("备份与迁移",fontWeight=FontWeight.Bold)
                Text("用于迁移或恢复日记：导入旧插件或日记 JSON 备份，或导出完整日记备份。",fontSize=12.sp,color=Muted)
                OutlinedButton(onClick=onImport,modifier=Modifier.fillMaxWidth()){Text("导入插件或日记 JSON 备份")}
                OutlinedButton(onClick=onExport,modifier=Modifier.fillMaxWidth()){Text("导出完整日记备份")}
                Text("记录不会自动过期。图片缓存上限 100 MB，可重新获取。有效播放：至少 30 秒，短歌为一半时长；分钟数只计算实际播放时间。",color=Muted,fontSize=12.sp)
                OutlinedButton(onClick={settings=false;shareImages=true},modifier=Modifier.fillMaxWidth().testTag("open-share-images")){Text("生成分享图片")}
                Spacer(Modifier.height(30.dp))
            }
        }
        DiaryCalendar(calendar,c){calendar=false}
    }
}

}

@Composable private fun PeriodLoadingPage(c:DiaryController,b:Backdrop,period:DiaryRange,calendar:()->Unit,settings:()->Unit){
    Column(Modifier.fillMaxSize().statusBarsPadding().testTag("period-loading-page").padding(horizontal=20.dp)){
        Row(Modifier.padding(top=13.dp),verticalAlignment=Alignment.CenterVertically){Text(periodHeader(c.day,period),Modifier.weight(1f).press(calendar),fontSize=14.sp,color=Muted)}
        Row(Modifier.padding(top=9.dp,bottom=16.dp),verticalAlignment=Alignment.CenterVertically){Text("听歌日记",Modifier.weight(1f),fontSize=34.sp,fontWeight=FontWeight.ExtraBold);Box(Modifier.size(40.dp).background(Muted.copy(alpha=.08f),CircleShape).press(settings),contentAlignment=Alignment.Center){DiaryGlyph("more",Modifier.size(23.dp),label="设置")}}
        AppPeriodPicker(c,b)
        Box(Modifier.fillMaxWidth().height(260.dp),contentAlignment=Alignment.Center){Text("正在读取所选周期…",fontSize=13.sp,color=Muted)}
    }
}

@Composable private fun Today(c:DiaryController,b:Backdrop,calendar:()->Unit,settings:()->Unit,open:(Play,String)->Unit,shared:SharedTransitionScope,visibility:AnimatedVisibilityScope,onTint:(List<Color>)->Unit,allowAuto:Boolean,summaryVisibility:AnimatedVisibilityScope){
    val list=rememberLazyListState();val r=c.report
    LaunchedEffect(c.range,c.summary,c.loading,c.foreground,c.demo,c.dataRevision,c.previewMetadataEpoch){
        if(!c.summary&&!c.loading&&(c.foreground||c.demo)){
            delay(if(c.demo)600 else 30000)
            if(!c.demo)kotlinx.coroutines.withTimeoutOrNull(5000){snapshotFlow{listOf(DiaryRange.WEEK,DiaryRange.MONTH,DiaryRange.YEAR).all{c.preparedPeriods.containsKey(it)}}.first{it}}
            c.preloadSummary()
        }
    }
    var galleryArc by remember{mutableStateOf(false)}
    val galleryHeight by animateDpAsState(if(galleryArc)294.dp else 224.dp,spring(.9f,260f),label="gallery expansion")
    var secondaryEntry by rememberSaveable{mutableIntStateOf(0)}
    var historyOpen by rememberSaveable{mutableStateOf(false)}
    var periodHistoryDate by rememberSaveable{mutableStateOf<String?>(null)}
    var periodHistorySnapshot by remember{mutableStateOf<PeriodComputed?>(null)}
    var favoritesOpen by rememberSaveable{mutableStateOf(false)};var favoritesAlbum by rememberSaveable{mutableStateOf(false)}
    val detailActions=LocalDetailActions.current
    var artistSheet by rememberSaveable{mutableStateOf<String?>(null)};var rhythmSheet by remember{mutableStateOf(false)}
    val galleryVisible by remember{derivedStateOf{!list.isScrollInProgress&&list.layoutInfo.visibleItemsInfo.any{it.key=="covers"}}}
    val label=when(c.range){DiaryRange.DAY->if(c.day==LocalDate.now())"今日" else "当日";DiaryRange.WEEK->"本周";DiaryRange.MONTH->"本月";DiaryRange.YEAR->"本年"}
    var headerHeight by remember{mutableIntStateOf(0)}
    var pullTarget by remember{mutableFloatStateOf(0f)}
    val pull by animateFloatAsState(pullTarget,spring(.88f,230f),label="pull return")
    val nested=remember(list){object:NestedScrollConnection{
        override fun onPreScroll(available:Offset,source:NestedScrollSource):Offset{if(source==NestedScrollSource.UserInput){if(list.firstVisibleItemIndex==0&&list.firstVisibleItemScrollOffset==0&&available.y>0)pullTarget=(pullTarget+available.y*.22f).coerceAtMost(130f)else if(available.y<0)pullTarget=(pullTarget+available.y*.3f).coerceAtLeast(0f)};return Offset.Zero}
        override suspend fun onPreFling(available:Velocity):Velocity{pullTarget=0f;return Velocity.Zero}
    }}
    val scroll by remember{derivedStateOf{when(list.firstVisibleItemIndex){0->list.firstVisibleItemScrollOffset.toFloat();1->headerHeight+list.firstVisibleItemScrollOffset.toFloat();else->headerHeight+700f}}}
    AnimatedContent(when{historyOpen->"history";periodHistoryDate!=null->"period-history";artistSheet!=null->"artists";favoritesOpen->"favorites";else->"home"},transitionSpec={
        if(targetState.contains("history")||initialState.contains("history")) (fadeIn(tween(220))+scaleIn(initialScale=.96f,animationSpec=tween(350))) togetherWith fadeOut(tween(180))
        else (fadeIn(tween(220))+slideInHorizontally{if(targetState!="home")it/5 else -it/5}) togetherWith fadeOut(tween(150))
    },label="history navigation"){showHistory->
        val historyScope=this
        val historyPrefix=if(showHistory=="period-history")c.range.name else "DAY"
        val historyEnabled=c.periodSwipePosition==null
        val historyMotion=remember(shared,historyScope,historyPrefix,historyEnabled){HistoryMotion(shared,historyScope,historyPrefix,historyEnabled)}
        CompositionLocalProvider(LocalHistoryMotion provides historyMotion){
        if(showHistory=="history")key(secondaryEntry,c.day,c.range){HistoryPage(c,calendar,settings,{historyOpen=false},open,shared,visibility)}
        else if(showHistory=="period-history")key(secondaryEntry,c.day,c.range){
            val snapshot by produceState<PeriodComputed?>(periodHistorySnapshot,c.report,c.day,c.range){if(value==null||value?.report!==c.report)value=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default){computePeriod(c.report,c.day,c.range)}}
            val computed=snapshot
            if(computed!=null){val first=LocalDate.parse(bounds(c.day,c.range).from);val last=LocalDate.parse(bounds(c.day,c.range).to);val dates=remember(first,last){generateSequence(first){it.plusDays(1)}.takeWhile{it<=last}.toList()};PeriodHistoryPage(c,c.range,dates,computed.dailySongs,computed.days,periodHistoryDate?.takeIf{it.isNotEmpty()}?.let{LocalDate.parse(it)},{periodHistoryDate=null;periodHistorySnapshot=null},open)}
            else Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){CircularProgressIndicator(Modifier.size(25.dp))}
        }
        else if(showHistory=="artists")key(secondaryEntry,c.day,c.range){LibraryRanking(c,true,artistSheet.orEmpty(),{artistSheet=null},open,shared,visibility)}
        else if(showHistory=="favorites")key(secondaryEntry,c.day,c.range){LibraryRanking(c,false,"",{favoritesOpen=false},open,shared,visibility,favoritesAlbum)}
        else {
        val periodState=remember{SeekableTransitionState(c.range)}
        val periodTransition=rememberTransition(periodState,label="period navigation")
        val dragIndex=c.periodSwipePosition?.let{position->if(position>c.range.ordinal+.001f)c.range.ordinal+1 else if(position<c.range.ordinal-.001f)c.range.ordinal-1 else c.range.ordinal}?.takeIf{it in 0..3}
        val prepared=remember(c.day,c.dayPolicy,c.demo,c.dataRevision,c.previewMetadataEpoch){c.activePreparedPeriods()}
        val canWarm=c.periodSwipePosition==null
        LaunchedEffect(c.day,c.range,c.dayPolicy,c.demo,c.dataRevision,c.previewMetadataEpoch,c.loading,c.foreground,canWarm){
            if(!c.loading&&canWarm&&(c.foreground||c.demo)){
                delay(150)
                val range=c.range;val day=c.day;val revision=c.dataRevision;val policy=c.dayPolicy;val metadataEpoch=c.previewMetadataEpoch
                // Keep already prepared periods and look ahead beyond the immediate neighbor.
                val neighbors=DiaryRange.entries.filter{it!=range}.sortedWith(compareBy<DiaryRange>{kotlin.math.abs(it.ordinal-range.ordinal)}.thenByDescending{it.ordinal})
                for(next in neighbors){
                    if(prepared[next]?.let{it.revision==revision&&it.metadataEpoch==metadataEpoch}==true)continue
                    if(!c.demo){
                        val disk=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){DailyReportCache.read(c.activity,day,policy,next,requireCurrent=true)}
                        if(disk!=null){
                            val computed=if(next==DiaryRange.DAY)null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default){computePeriod(disk,day,next)}
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            if(c.isPreviewRevisionCurrent(revision))prepared[next]=PreparedPeriod(disk,computed,revision,metadataEpoch,true)
                            c.images.warmCached(if(computed!=null)periodWarmArtwork(computed,day)else disk.history.asReversed().take(3).map{it.id},720)
                            continue
                        }
                    }
                    val loaded=if(c.demo)periodDemoReport(day,next) to revision else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){DiaryDatabase.get(c.activity).readReportVersioned(bounds(day,next),policy)}
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if(loaded.second!=revision||!c.isPreviewRevisionCurrent(revision)||loaded.first.segments.size>80000)continue
                    val distance=kotlin.math.abs(next.ordinal-range.ordinal)
                    if(distance==1){
                        val victims=prepared.keys.filter{it!=range&&kotlin.math.abs(it.ordinal-range.ordinal)>distance}.sortedByDescending{kotlin.math.abs(it.ordinal-range.ordinal)}
                        for(victim in victims){if(loaded.first.segments.size+prepared.entries.filter{it.key!=range}.sumOf{it.value.report.segments.size}<=96000)break;prepared.remove(victim)}
                    }
                    if(loaded.first.segments.size+prepared.entries.filter{it.key!=range}.sumOf{it.value.report.segments.size}>96000)continue
                    val calculated=if(next==DiaryRange.DAY)null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default){computePeriod(loaded.first,day,next)}
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if(!c.isPreviewRevisionCurrent(revision))continue
                    prepared[next]=PreparedPeriod(loaded.first,calculated,revision,metadataEpoch)
                    val covers=if(calculated!=null)periodWarmArtwork(calculated,day)else loaded.first.history.asReversed().take(3).map{it.id}
                    c.images.warmCached(covers.distinct().take(10),720)
                }
                if(range !in prepared && c.isPreviewRevisionCurrent(revision) && c.report.from==bounds(day,range).from && c.report.segments.size<=80000){
                    val currentCalc=if(range==DiaryRange.DAY)null else c.retainedPeriodComputed?.takeIf{it.report===c.report&&it.day==day&&it.range==range}?:kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default){computePeriod(c.report,day,range)}
                    if(c.isPreviewRevisionCurrent(revision))prepared[range]=PreparedPeriod(c.report,currentCalc,revision,metadataEpoch)
                }
            }
        }
        var previewReport by remember(c.day,c.dayPolicy,c.demo,c.dataRevision,c.previewMetadataEpoch){mutableStateOf<DiaryReport?>(null)}
        var previewRange by remember(c.day,c.dayPolicy,c.demo,c.dataRevision,c.previewMetadataEpoch){mutableStateOf<DiaryRange?>(null)}
        var previewRevision by remember(c.day,c.dayPolicy,c.demo,c.dataRevision,c.previewMetadataEpoch){mutableLongStateOf(-1L)}
        var previewComputed by remember(c.day,c.dayPolicy,c.demo,c.dataRevision,c.previewMetadataEpoch){mutableStateOf<PeriodComputed?>(null)}
        LaunchedEffect(c.day,dragIndex,c.dayPolicy,c.demo,c.dataRevision,c.previewMetadataEpoch){
            if(dragIndex!=null&&dragIndex!=c.range.ordinal){
                val next=DiaryRange.entries[dragIndex]
                if(prepared[next]?.let{c.isPreviewRevisionCurrent(it.revision)}!=true){
                    previewReport=null;previewComputed=null;previewRange=next
                    val loaded=if(c.demo)periodDemoReport(c.day,next) to c.dataRevision else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO){DiaryDatabase.get(c.activity).readReportVersioned(bounds(c.day,next),c.dayPolicy)}
                    val calculated=if(next==DiaryRange.DAY)null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default){computePeriod(loaded.first,c.day,next)}
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if(c.isPreviewRevisionCurrent(loaded.second)){previewRevision=loaded.second;previewComputed=calculated;previewReport=loaded.first}
                }
            }
        }
        // A selected period must have its hero laid out before its shared cover can move.
        val selectedCached=prepared[c.range]?.takeIf{c.isPreviewRevisionCurrent(it.revision)}
        val committedComputed=if(c.demo&&c.range!=DiaryRange.DAY&&!c.loading)remember(c.report,c.day,c.range){selectedCached?.computed?.takeIf{it.report===c.report}?:computePeriod(c.report,c.day,c.range)} else {
            val retained=c.retainedPeriodComputed?.takeIf{it.report===c.report&&it.day==c.day&&it.range==c.range}
            val result by produceState<PeriodComputed?>(retained,c.report,c.range,c.day,c.loading,selectedCached){
                value=retained
                if(c.range!=DiaryRange.DAY&&!c.loading){
                    val report=c.report;val day=c.day;val range=c.range
                    value=retained?:selectedCached?.computed?.copy(report=c.report)?:kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default){computePeriod(report,day,range)}
                    c.retainedPeriodComputed=value
                }
            }
            result
        }
        val selectedReady=if(c.range==DiaryRange.DAY)selectedCached!=null||!c.loading else selectedCached?.computed!=null||(!c.loading&&committedComputed?.report===c.report)
        val dragRange=dragIndex?.let{DiaryRange.entries[it]}
        val dragCached=dragRange?.let{prepared[it]}?.takeIf{c.isPreviewRevisionCurrent(it.revision)}
        val dragReady=dragRange==null||dragCached!=null||(previewRange==dragRange&&previewReport!=null&&c.isPreviewRevisionCurrent(previewRevision)&&(dragRange==DiaryRange.DAY||previewComputed?.report===previewReport))
        val periodDragPosition=c.periodSwipePosition?.takeIf{dragIndex!=null&&dragIndex!=c.range.ordinal}
        LaunchedEffect(c.range,periodDragPosition,selectedReady,dragReady){
            val pos=periodDragPosition
            if(pos!=null&&dragIndex!=null&&dragIndex!=c.range.ordinal){
                periodState.seekTo(kotlin.math.abs(pos-c.range.ordinal).coerceIn(0f,1f),DiaryRange.entries[dragIndex])
            }else periodState.animateTo(c.range)
        }
        periodTransition.AnimatedContent(modifier=Modifier.periodSwipe(c).testTag("period-navigation").semantics{this[PeriodSeekFractionKey]=periodState.fraction;this[PreparedPeriodsKey]=prepared.entries.sortedBy{it.key.ordinal}.joinToString(","){it.key.name+":"+it.value.report.from}},transitionSpec={
            val forward=targetState.ordinal>initialState.ordinal
            slideInHorizontally(tween(320,easing=LinearEasing)){if(forward)it else -it} togetherWith slideOutHorizontally(tween(320,easing=LinearEasing)){if(forward)-it else it}
        },contentKey={it}){period->
        val periodVisibility=this
        val cached=prepared[period]?.takeIf{c.isPreviewRevisionCurrent(it.revision)}
        val cachedReport=remember(cached,period){if(period==DiaryRange.DAY)cached?.report?.copy(nowTrackId="") else cached?.report}
        val fallback=previewReport?.takeIf{c.isPreviewRevisionCurrent(previewRevision)}
        var pageReport by remember(period){mutableStateOf(cachedReport?:fallback?.takeIf{previewRange==period}?:if(c.range==period)c.report else DiaryReport())}
        if(c.range==period&&(!periodTransition.isRunning||pageReport.history.isEmpty()))pageReport=if(c.loading)cachedReport?:if(previewRange==period)fallback?:c.report else c.report else c.report
        else if(dragIndex==period.ordinal)pageReport=cachedReport?:if(previewRange==period)fallback?:pageReport else pageReport
        // Keep the exiting page mounted with its own data until the transition disposes it.
        val r=pageReport
        if(r.history.isEmpty()&&((c.range==period&&c.loading)||(c.range!=period&&!dragReady))) PeriodLoadingPage(c,b,period,calendar,settings)
        else if(period!=DiaryRange.DAY)PeriodReportPage(c,b,calendar,settings,open,shared,visibility,onTint,allowAuto&&c.range==period&&c.periodSwipePosition==null,period,r,{album->secondaryEntry++;favoritesAlbum=album;favoritesOpen=true},{name->if(name.isBlank()){secondaryEntry++;artistSheet=""} else detailActions?.artist?.invoke(name,"period-artist:${period.name}:$name")},periodVisibility,summaryVisibility,cached?.computed?.takeIf{cached.report===r||(c.range==period&&!c.loading&&cached.revision==c.dataRevision)}?:previewComputed?.takeIf{it.report===r}?:committedComputed?.takeIf{it.report===r},{date,computed->secondaryEntry++;periodHistorySnapshot=computed;periodHistoryDate=date?.toString()?:""})
        else {
        val covers=remember(r.history){CoverCarousel.history(r.history)}
        val ranked=remember(r.history){sortedPlays(r.history.filter{!it.imported||it.title!="未知歌曲"},SortOrder.COUNT).take(5)}
        val artistPlays=remember(r.history){r.history.groupBy{it.artist}.mapValues{(_,songs)->songs.sumOf{it.effective}}}
        LazyColumn(state=list,modifier=Modifier.fillMaxSize().testTag("home-scroll").statusBarsPadding().nestedScroll(nested),contentPadding=PaddingValues(bottom=28.dp)){
            item("header"){
                Column(Modifier.fillMaxWidth().onSizeChanged{headerHeight=it.height}.padding(horizontal=20.dp).padding(top=13.dp,bottom=13.dp)){
                    Row(verticalAlignment=Alignment.CenterVertically){Text(periodHeader(c.day,DiaryRange.DAY),Modifier.weight(1f).press(calendar).testTag("period-date"),fontSize=14.sp,color=Muted)}
                    Row(Modifier.padding(top=9.dp,bottom=16.dp),verticalAlignment=Alignment.CenterVertically){Text("听歌日记",with(shared){Modifier.weight(1f).sharedElement(rememberSharedContentState("period-title"),periodVisibility,boundsTransform={_,_->tween(320,easing=LinearEasing)})},fontSize=34.sp,fontWeight=FontWeight.ExtraBold);Box(Modifier.size(40.dp).background(Muted.copy(alpha=.08f),CircleShape).press(settings),contentAlignment=Alignment.Center){DiaryGlyph("more",Modifier.size(23.dp),label="设置")}}
                    Box(with(shared){Modifier.then(if(c.range==period)Modifier.sharedElement(rememberSharedContentState("summary-picker"),summaryVisibility,boundsTransform={_,_->tween(320,easing=LinearEasing)})else Modifier).sharedElement(rememberSharedContentState("period-picker"),periodVisibility,boundsTransform={_,_->tween(320,easing=LinearEasing)})}){AppPeriodPicker(c,b)}
                }
            }
            item("covers"){Box(Modifier.fillMaxWidth().height(galleryHeight).graphicsLayer{translationY=scroll*.45f+pull*.5f;scaleX=1f+pull/1800f;scaleY=scaleX}){
                if(covers.isEmpty())Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){Text("下一段音乐记忆，正在路上",fontSize=16.sp,color=Muted)}
                else key(c.day,period){CoverGallery(c,covers,open,shared,visibility,onTint,allowAuto&&c.periodSwipePosition==null&&galleryVisible&&!historyOpen&&artistSheet==null&&!rhythmSheet,{galleryArc=it},segments=r.segments,periodVisibility=periodVisibility,summaryVisibility=summaryVisibility)}
            }}
            sheetItem("summary",top=true){
                ListeningStats(r,with(shared){Modifier.sharedBounds(rememberSharedContentState("period-stats"),periodVisibility,enter=fadeIn(tween(120)),exit=fadeOut(tween(120)),boundsTransform={_,_->tween(320,easing=LinearEasing)})}.padding(horizontal=20.dp).padding(bottom=12.dp)){c.notice=it}
                if(c.showingCached||c.loading&&r.history.isEmpty())Text(if(c.showingCached)"正在更新记录…" else "正在读取所选日期…",Modifier.padding(horizontal=20.dp).padding(bottom=8.dp),fontSize=12.sp,color=Muted)
                if(r.importedRows>0)Text(if(r.missingTitles>0)"含 Apple 导入记录 · ${r.missingTitles}次未提供曲名（保留时长与历史）"else "含 Apple 导入记录",Modifier.padding(horizontal=20.dp).padding(bottom=8.dp).press(settings),fontSize=11.sp,color=Muted)
                if(!c.loading&&(c.demo||c.error!=null||(r.lastSync==0L&&r.history.isEmpty())))Text(when{c.demo->"交互演示 · 示例记录";c.error!=null->c.error!!;else->"等待采集器同步 · 点击连接"},Modifier.padding(horizontal=20.dp).padding(bottom=10.dp).press(settings),fontSize=11.sp,color=Muted)
            }
            sheetItem("hours"){
                Column(Modifier.padding(horizontal=16.dp).padding(bottom=12.dp).historyPanel().clip(RoundedCornerShape(19.dp)).background(Accent.copy(alpha=.045f)).padding(horizontal=14.dp,vertical=12.dp)){
                    key(c.day){RecentListeningHeader(c,r,{secondaryEntry++;historyOpen=true},open,shared,visibility)}
                    HourlyPlot(r.segments,Modifier.fillMaxWidth().padding(top=2.dp).height(76.dp).press{rhythmSheet=true},c.use12Hour,c.dayPolicy)
                }
            }
            if(ranked.isNotEmpty())sheetItem("favorites"){
                Column(Modifier.padding(horizontal=17.dp)){
                    FavoriteHeading("${label}最常听",favoritesAlbum,{favoritesAlbum=it}){secondaryEntry++;favoritesOpen=true};Spacer(Modifier.height(12.dp))
                    AnimatedContent(favoritesAlbum,transitionSpec={fadeIn(tween(250))+slideInHorizontally{it/10} togetherWith fadeOut(tween(160))},label="daily favorites"){showAlbums->
                    if(showAlbums)FavoriteAlbumList(c,remember(r){libraryGroups(r,false)}.take(5))else Column{
                    ranked.forEachIndexed{i,p->val key="rank:${p.id}";Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.Transparent).press{open(p,key)}.padding(horizontal=12.dp,vertical=11.dp),verticalAlignment=Alignment.CenterVertically){
                        Text("0${i+1}",Modifier.width(36.dp),fontSize=19.sp,color=Muted)
                        with(shared){Artwork(c,p.id,Modifier.size(49.dp).sharedElement(rememberSharedContentState(key),visibility,renderInOverlayDuringTransition=ownedSharedOverlay(shared,visibility),clipInOverlayDuringTransition=OverlayClip(RoundedCornerShape(17.dp)),boundsTransform={_,_->tween(360,easing=FastOutSlowInEasing)}).clip(RoundedCornerShape(9.dp)),edge=160)}
                        Column(Modifier.weight(1f).padding(horizontal=12.dp)){Text(p.title,fontSize=16.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis);Text(p.artist,Modifier.padding(top=3.dp),fontSize=13.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)}
                        Row(verticalAlignment=Alignment.Bottom){Text("${p.effective}",fontSize=23.sp,fontWeight=FontWeight.Bold);Text(" 次",fontSize=12.sp,color=Muted)}
                    };if(i<ranked.lastIndex)HorizontalDivider(Modifier.padding(start=60.dp),color=Muted.copy(alpha=.1f),thickness=.5.dp)}
                    }
                    }
                    Spacer(Modifier.height(18.dp))
                }
            }
            if(r.topArtists.isNotEmpty())sheetItem("artists"){
                Column(Modifier.padding(horizontal=17.dp)){
                    MemoryHeading("常听歌手",toAll={secondaryEntry++;artistSheet=""});Spacer(Modifier.height(15.dp))
                    Box(Modifier.fillMaxWidth()){val cell=96.dp;LazyRow(Modifier.testTag("artist-strip").graphicsLayer{compositingStrategy=CompositingStrategy.Offscreen}.drawWithContent{drawContent();val fade=14.dp.toPx();drawRect(Brush.horizontalGradient(listOf(Color.Transparent,Color.White),endX=fade),blendMode=BlendMode.DstIn);drawRect(Brush.horizontalGradient(listOf(Color.White,Color.Transparent),startX=size.width-fade,endX=size.width),blendMode=BlendMode.DstIn)}){items(r.topArtists.take(10),key={it.name}){a->Column(Modifier.width(cell).testTag("artist-card:${a.name}").press{detailActions?.artist?.invoke(a.name,"day-artist:${a.name}")},horizontalAlignment=Alignment.CenterHorizontally){Artwork(c,"artist:${a.name}",Modifier.size(68.dp).detailSource("day-artist:${a.name}",shape=CircleShape).clip(CircleShape),edge=160);Text(a.name,Modifier.padding(top=8.dp),fontSize=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis);Text("${artistPlays[a.name]?:0} 次",Modifier.padding(top=3.dp),fontSize=12.sp,color=Muted)}}}}
                    Spacer(Modifier.height(22.dp))
                }
            }
            if(r.history.isNotEmpty())sheetItem("daily-summary"){
                DailyListeningSummary(c,r.history,r.segments,Modifier.padding(horizontal=17.dp).padding(bottom=20.dp))
            }
        }
        }
        }
        }
    }
    }
    if(rhythmSheet)SessionSheet(c,r){rhythmSheet=false}
}

private fun LazyListScope.sheetItem(key:Any,top:Boolean=false,content:@Composable LazyItemScope.()->Unit){
    item(key){val lazyScope=this;val surface=LocalAlbumSurface.current;Column(Modifier.fillMaxWidth().drawBehind{if(top)drawRect(Brush.verticalGradient(listOf(Color.Transparent,surface()),startY=0f,endY=26.dp.toPx()))else drawRect(surface())}){content(lazyScope)}}
}

@Composable private fun HistoryPage(c:DiaryController,calendar:()->Unit,settings:()->Unit,back:()->Unit,open:(Play,String)->Unit,shared:SharedTransitionScope,visibility:AnimatedVisibilityScope){
    BackHandler{back()};val r=c.report;val list=rememberLazyListState();var query by rememberSaveable{mutableStateOf("")};var closed by rememberSaveable{mutableStateOf(listOf<String>())};var all by rememberSaveable{mutableStateOf(listOf<String>())};var repeatsOpen by rememberSaveable{mutableStateOf(listOf<String>())};var sortIndex by rememberSaveable{mutableIntStateOf(1)};var searchVisible by rememberSaveable{mutableStateOf(false)}
    var pull by remember{mutableFloatStateOf(0f)}
    val threshold=with(androidx.compose.ui.platform.LocalDensity.current){48.dp.toPx()}
    val reveal=remember(list,threshold){object:NestedScrollConnection{
        override fun onPreScroll(available:Offset,source:NestedScrollSource):Offset {
            if(source==NestedScrollSource.UserInput&&list.firstVisibleItemIndex==0&&list.firstVisibleItemScrollOffset==0&&available.y>0){pull+=available.y;if(pull>threshold)searchVisible=true}
            else if(available.y<0){pull=0f;if(query.isBlank()&&available.y < -12f)searchVisible=false}
            return Offset.Zero
        }
        override suspend fun onPreFling(available:androidx.compose.ui.unit.Velocity):androidx.compose.ui.unit.Velocity{pull=0f;return androidx.compose.ui.unit.Velocity.Zero}
    }}
    val resumedVisits=remember(r.segments){ListeningSessions.resumedVisits(r.segments)}
    val liveStops=remember(r.history,r.segments){CoverCarousel.stops(r.history,r.segments)}
    val liveKey=rememberRecentPlayingKey(r,liveStops,c.foreground)
    val liveVisit=liveStops.firstOrNull{it.key==liveKey}?.play?.visit
    val sessions=remember(r.segments,r.history,sortIndex){val natural=ListeningSessions.group(r.segments);when(sortIndex){0->natural;1->natural.asReversed();else->{val songs=sortedPlays(r.history,SortOrder.entries[sortIndex]);if(songs.isEmpty())emptyList()else listOf(ListeningSession("grouped",songs.minOf{it.start},songs.maxOf{it.end},songs.sumOf{it.ms},songs))}}}
    val panel=LocalAlbumSurface.current
    LazyColumn(state=list,modifier=Modifier.fillMaxSize().historyPanel().testTag("history-scroll").semantics{this[HistoryScrollIndexKey]=list.firstVisibleItemIndex}.nestedScroll(reveal).drawBehind{drawRect(panel())}.statusBarsPadding(),contentPadding=PaddingValues(start=17.dp,end=17.dp,bottom=25.dp)){
        item("header"){
            Row(Modifier.press(back).semantics{contentDescription="返回日记"}.padding(top=14.dp,bottom=13.dp),verticalAlignment=Alignment.CenterVertically){DiaryGlyph("back",Modifier.size(22.dp),color=Accent);Text("听歌日记",fontSize=16.sp,color=Accent)}
            Text("听歌历史",Modifier.padding(bottom=12.dp),fontSize=34.sp,fontWeight=FontWeight.ExtraBold)
            Text("${r.plays}次有效播放 · ${r.history.size}段记录 · ${minutes(r.ms)}分钟",Modifier.fillMaxWidth().padding(vertical=13.dp),fontSize=13.sp,color=Muted,textAlign=TextAlign.Center)
            AnimatedVisibility(searchVisible,enter=expandVertically(spring(.9f,300f))+fadeIn(tween(220)),exit=shrinkVertically(tween(180))+fadeOut(tween(130))){OutlinedTextField(value=query,onValueChange={query=it},modifier=Modifier.fillMaxWidth().padding(bottom=15.dp).testTag("history-search"),singleLine=true,shape=RoundedCornerShape(16.dp),placeholder={Text("搜索歌曲或歌手",fontSize=15.sp,color=Muted)})}
        }
        var matched=0
        sessions.forEachIndexed{index,session->
            val ordered=if(sortIndex==1)session.songs.asReversed()else session.songs
            val runs=(if(sortIndex>=2)ordered.map{RepeatPlays(listOf(it))}else repeatPlays(ordered)).filter{run->query.isBlank()||run.songs.any{it.title.contains(query,true)||it.artist.contains(query,true)}}
            val songs=runs.flatMap{it.songs}
            if(songs.isNotEmpty()){
                matched+=songs.size;val opened=query.isNotBlank()||session.key !in closed;val limit=if(index==0)5 else 3;val shown=if(query.isNotBlank()||session.key in all)runs else runs.take(limit)
                item("head:${session.key}"){
                    val turn by animateFloatAsState(if(opened)0f else -90f,spring(.88f,330f),label="session arrow")
                    Column(Modifier.animateItem(fadeInSpec=tween(220),placementSpec=spring(.9f,300f),fadeOutSpec=tween(180)).fillMaxWidth().clip(if(opened)RoundedCornerShape(topStart=21.dp,topEnd=21.dp)else RoundedCornerShape(21.dp)).background(Accent.copy(alpha=.05f)).press{closed=if(session.key in closed)closed-session.key else closed+session.key}.padding(14.dp)){
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(8.dp).background(Pink,CircleShape));Text(if(sortIndex>=2)SortOrder.entries[sortIndex].label else "${diaryClock(session.start,c.use12Hour)} — ${diaryClock(session.end,c.use12Hour)}",Modifier.weight(1f).padding(start=12.dp),fontSize=19.sp,fontWeight=FontWeight.Bold);DiaryGlyph("down",Modifier.size(18.dp).graphicsLayer{rotationZ=turn},color=Muted)}
                        Text("${session.songs.size}次播放 · ${duration(session.ms)}",Modifier.padding(start=20.dp,top=5.dp),fontSize=13.sp,color=Muted)
                    }
                }
                if(opened){items(shown,key={"song:${session.key}:${it.key}"}){run->
                    val key="${session.key}:${run.key}"
                    Column(Modifier.animateItem(fadeInSpec=tween(220),placementSpec=spring(.9f,300f),fadeOutSpec=tween(180))){
                    if(run.songs.size==1)MemorySong(c,run.songs.first(),"row:${session.key}:${run.songs.first().visit}",shared,visibility,open,sortIndex>=2,liveVisit==run.songs.first().visit,run.songs.first().visit in resumedVisits)
                    else Column(Modifier.animateContentSize(spring(.9f,300f))){
                        Row(Modifier.fillMaxWidth().testTag("repeat:$key").clip(RoundedCornerShape(15.dp)).background(Accent.copy(alpha=.055f)).press{repeatsOpen=if(key in repeatsOpen)repeatsOpen-key else repeatsOpen+key}.padding(horizontal=12.dp,vertical=11.dp),verticalAlignment=Alignment.CenterVertically){
                            Column(Modifier.width(if(c.use12Hour)72.dp else 48.dp)){Text(diaryClock(run.start,c.use12Hour),fontSize=12.sp);Text(diaryClock(run.end,c.use12Hour),fontSize=10.sp,color=Muted)}
                            Box(Modifier.size(width=51.dp,height=43.dp)){for(layer in minOf(2,run.songs.lastIndex) downTo 0)Artwork(c,run.songs[layer].id,Modifier.offset(x=(layer*3).dp,y=(-layer*2).dp).size(41.dp).then(if(layer==0)Modifier.historyCover(run.songs.first().visit) else Modifier).clip(RoundedCornerShape(8.dp)),edge=160)}
                            Column(Modifier.weight(1f).padding(start=10.dp)){Text(run.songs.first().title,fontSize=15.sp,maxLines=1,overflow=TextOverflow.Ellipsis);Text(run.songs.first().artist,fontSize=12.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)}
                            Text("连听${run.songs.size}次",fontSize=12.sp,color=Muted);DiaryGlyph(if(key in repeatsOpen)"up" else "down",Modifier.padding(start=7.dp).size(17.dp),color=Muted)
                        }
                        AnimatedVisibility(key in repeatsOpen,enter=expandVertically(tween(260))+fadeIn(tween(220)),exit=shrinkVertically(tween(200))+fadeOut(tween(160))){Column{run.songs.forEach{p->MemorySong(c,p,"row:${session.key}:${p.visit}",shared,visibility,open,false,liveVisit==p.visit,p.visit in resumedVisits,matchHistory=false)}}}
                    }
                    }
                }
                    if(runs.size>limit&&query.isBlank())item("more:${session.key}"){Row(Modifier.animateItem(fadeInSpec=tween(200),placementSpec=spring(.9f,300f),fadeOutSpec=tween(170)).fillMaxWidth().press{all=if(session.key in all)all-session.key else all+session.key}.padding(vertical=13.dp),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){Text(if(session.key in all)"收起" else "展开其余${songs.size-shown.sumOf{it.songs.size}}次播放",fontSize=13.sp,color=Accent);DiaryGlyph("down",Modifier.size(17.dp),color=Accent)}}
                }

                item("gap:${session.key}"){Spacer(Modifier.animateItem(placementSpec=spring(.9f,300f)).height(13.dp))}
            }
        }
        if(matched==0)item{Text(if(query.isBlank())"这一天还没有记录" else "没有找到歌曲或歌手",Modifier.padding(22.dp),color=Muted)}
    }
}

@Composable private fun FootprintStat(modifier:Modifier,value:String,label:String){Column(modifier,horizontalAlignment=Alignment.CenterHorizontally){Text(value,fontSize=28.sp,fontWeight=FontWeight.SemiBold);Text(label,fontSize=13.sp,color=Muted)}}
@Composable private fun MemoryDivider(){HorizontalDivider(Modifier.padding(horizontal=20.dp),thickness=.5.dp,color=Muted.copy(alpha=.1f))}
@Composable internal fun MemoryHeading(title:String,toAll:()->Unit){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(title,Modifier.weight(1f),fontSize=22.sp,fontWeight=FontWeight.Bold);Row(Modifier.testTag("all-link:$title").press(toAll),verticalAlignment=Alignment.CenterVertically){Text("全部",fontSize=13.sp,color=Muted);DiaryGlyph("next",Modifier.size(13.dp),color=Muted)}}}
@Composable private fun MemorySong(c:DiaryController,p:Play,key:String,shared:SharedTransitionScope,visibility:AnimatedVisibilityScope,open:(Play,String)->Unit,grouped:Boolean=false,isPlaying:Boolean=false,resumed:Boolean=false,matchHistory:Boolean=true){
    val line=Muted.copy(alpha=.12f);val opacity by animateFloatAsState(if(grouped)1f else playbackOpacity(listOf(p),isPlaying),tween(280),label="history playback opacity")
    Row(Modifier.testTag("song:$key").fillMaxWidth().background(LocalAlbumSurface.current()).press{open(p,key)}.padding(start=20.dp,end=8.dp,top=9.dp,bottom=9.dp),verticalAlignment=Alignment.CenterVertically){
        Text(if(grouped)"${p.effective}次" else diaryClock(p.start,c.use12Hour),Modifier.width(if(c.use12Hour)72.dp else 48.dp),fontSize=12.sp,color=Muted)
        with(shared){Artwork(c,p.id,Modifier.size(43.dp).then(if(matchHistory)Modifier.historyCover(p.visit)else Modifier).sharedElement(rememberSharedContentState(key),visibility,renderInOverlayDuringTransition=ownedSharedOverlay(shared,visibility),clipInOverlayDuringTransition=OverlayClip(RoundedCornerShape(17.dp)),boundsTransform={_,_->tween(360,easing=FastOutSlowInEasing)}).clip(RoundedCornerShape(8.dp)).playbackFade(opacity),edge=160)}
        Column(Modifier.weight(1f).padding(start=12.dp)){Text(if(p.imported&&p.title=="未知歌曲")"Apple 未提供曲名"else p.title,fontSize=16.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis);Text(if(p.imported&&p.title=="未知歌曲")"保留播放时间与时长"else p.artist+(if(resumed)" · 中断后继续" else ""),Modifier.padding(top=2.dp),fontSize=13.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)}
    }
    HorizontalDivider(Modifier.padding(start=68.dp),thickness=.5.dp,color=line)
}

@Composable private fun HourlyPlot(spans:List<Play>,modifier:Modifier,twelve:Boolean=false,policy:ListeningDayPolicy=ListeningDayPolicy()){
    val start=HourlyListening.startHour(policy)
    val bins=remember(spans,start){HourlyListening.orderedHours(spans,start)};val ink=Accent
    val capacity=(bins.maxOrNull()?:0L).coerceAtLeast(1L)
    var appeared by remember{mutableStateOf(false)};LaunchedEffect(Unit){appeared=true}
    val levels=bins.mapIndexed{i,value->animateFloatAsState(if(appeared)(value.toFloat()/capacity).coerceIn(0f,1f) else 0f,tween(460,delayMillis=i*5,easing=FastOutSlowInEasing),label="hour $i").value}
    Column(modifier){Canvas(Modifier.fillMaxWidth().weight(1f)){
        val cell=size.width/bins.size;val baseline=size.height-1.dp.toPx()
        val ceiling=(baseline-1.dp.toPx()).coerceAtLeast(1f)
        drawLine(ink.copy(alpha=.08f),Offset(0f,baseline),Offset(size.width,baseline),.5.dp.toPx())
        bins.forEachIndexed{i,value->
            val width=cell*.64f;val level=levels[i]
            val height=if(value>0)(level*ceiling).coerceAtLeast(2.dp.toPx())else 1.5.dp.toPx()
            val color=lerp(Color(0xffffd5e2),Color(0xffeb326e),level)
            drawRoundRect(color,Offset(i*cell+(cell-width)/2,baseline-height),Size(width,height),cornerRadius=androidx.compose.ui.geometry.CornerRadius(width/2),alpha=if(value>0)1f else .3f)
        }
    }
        Row(Modifier.fillMaxWidth().padding(top=4.dp),horizontalArrangement=Arrangement.SpaceBetween){HourlyListening.axisLabels(start,twelve).forEach{Text(it,fontSize=9.sp,color=Muted)}}
    }
}

internal fun shortDuration(ms:Long)=if(ms<60000)"${ms/1000} 秒" else if(ms<3600000)"${ms/60000} 分钟" else "${"%.1f".format(java.util.Locale.ROOT,ms/3600000f)} 小时"
@Composable private fun StatDivider(){Box(Modifier.width(.7.dp).height(28.dp).background(Muted.copy(alpha=.15f)))}
@Composable private fun DashboardStat(modifier:Modifier,value:String,label:String,color:Color,icon:Int){
    Column(modifier,horizontalAlignment=Alignment.CenterHorizontally){
        val iconInk=Ink
        Canvas(Modifier.size(15.dp)){
            val w=size.width;val h=size.height;val stroke=1.4.dp.toPx()
            when(icon){
                0->{drawCircle(color);val path=Path().apply{moveTo(w*.42f,h*.29f);lineTo(w*.72f,h*.5f);lineTo(w*.42f,h*.71f);close()};drawPath(path,iconInk)}
                1->{drawCircle(color);drawLine(iconInk,center,Offset(w*.5f,h*.22f),stroke);drawLine(iconInk,center,Offset(w*.72f,h*.62f),stroke)}
                2->{drawLine(color,Offset(w*.63f,h*.13f),Offset(w*.63f,h*.75f),stroke);drawLine(color,Offset(w*.63f,h*.13f),Offset(w*.9f,h*.27f),stroke*1.5f);drawOval(color,Offset(w*.19f,h*.59f),Size(w*.48f,h*.34f))}
                else->{drawCircle(color,radius=w*.21f,center=Offset(w*.5f,h*.25f));val shoulders=Path().apply{moveTo(w*.12f,h*.93f);cubicTo(w*.10f,h*.45f,w*.90f,h*.45f,w*.88f,h*.93f);close()};drawPath(shoulders,color)}
            }
        }
        Spacer(Modifier.height(5.dp));Text(value,fontSize=25.sp,fontWeight=FontWeight.SemiBold,letterSpacing=(-.7).sp);Text(label,fontSize=10.sp,color=Muted)
    }
}
@Composable private fun Metric(value:String,label:String,color:Color){Column{Text(value,fontSize=26.sp,color=color);Text(label,fontSize=12.sp,color=Muted)}}

@Composable internal fun CoverGallery(c:DiaryController,plays:List<Play>,open:(Play,String)->Unit,shared:SharedTransitionScope,visibility:AnimatedVisibilityScope,onTint:(List<Color>)->Unit,allowAuto:Boolean,onFastChange:(Boolean)->Unit={},segments:List<Play> = plays,periodVisibility:AnimatedVisibilityScope?=null,summaryVisibility:AnimatedVisibilityScope?=null){
    val stops=remember(plays,segments){CoverCarousel.stops(plays,segments)}
    val playing=rememberRecentPlayingKey(c.report,stops,c.foreground)
    val count=stops.size
    if(count==0)return
    var expandedGroup by rememberSaveable{mutableStateOf<String?>(null)}
    val pager=rememberPagerState(initialPage=CoverCarousel.firstPage(count),pageCount={CoverCarousel.pageCount(count)})
    val scope=rememberCoroutineScope()
    var quick by remember{mutableStateOf(false)}
    var quickGoal by remember{mutableFloatStateOf(CoverCarousel.firstPage(count).toFloat())}
    var quickHeld by remember{mutableStateOf(false)}
    val motionOwner=remember{arrayOfNulls<kotlinx.coroutines.Job>(1)}
    var lastTouch by remember{mutableLongStateOf(SystemClock.uptimeMillis()-CoverCarousel.TOUCH_REST_MS)}
    var touchEpoch by remember{mutableIntStateOf(0)}
    fun touched(){lastTouch=SystemClock.uptimeMillis();touchEpoch++}
    fun leaveQuick(target:Int=pager.currentPage){quick=false;quickHeld=false;touched();scope.launch{motionOwner[0]?.cancelAndJoin();pager.animateScrollToPage(target,animationSpec=spring(.9f,300f))}}
    val quickState by rememberUpdatedState(quick)
    val fastChange by rememberUpdatedState(onFastChange)
    LaunchedEffect(quick){fastChange(quick)}
    DisposableEffect(Unit){onDispose{fastChange(false)}}
    val arcProgress by animateFloatAsState(if(quick)1f else 0f,spring(.86f,260f),label="gallery arc")
    val gallerySpacing by animateDpAsState(if(quick)CoverArcSpacingDp.dp else (-58).dp,spring(.86f,260f),label="gallery density")
    val visits=remember(stops){stops.map{it.key}}
    LaunchedEffect(expandedGroup,visits){if(expandedGroup!=null&&stops.none{it.key==expandedGroup})expandedGroup=null}
    val palettes=remember{mutableStateMapOf<String,List<Color>>()}
    val tintSink by rememberUpdatedState(onTint)
    LaunchedEffect(pager,visits,c.range,c.summary){snapshotFlow{
        val location=(pager.currentPage+pager.currentPageOffsetFraction).coerceIn(0f,(CoverCarousel.pageCount(count)-1).toFloat())
        val left=floor(location).toInt();val right=(left+1).coerceAtMost(CoverCarousel.pageCount(count)-1)
        val a=palettes[stops[CoverCarousel.sourceIndex(left,count)].play.id]
        val b=palettes[stops[CoverCarousel.sourceIndex(right,count)].play.id]
        if(a!=null&&b!=null)List(2){lerp(a[it],b[it],location-left)} else a
    }.collect{colors->if(colors!=null&&c.range==DiaryRange.DAY&&!c.summary)tintSink(colors)}}
    LaunchedEffect(quick,count){
        if(!quick)return@LaunchedEffect
        val owner=kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job];motionOwner[0]=owner
        try{pager.scroll(androidx.compose.foundation.MutatePriority.UserInput){
            var previousFrame=0L
            while(isActive){
                val current=pager.currentPage+pager.currentPageOffsetFraction
                val target=quickGoal.coerceIn(CoverCarousel.firstPage(count).toFloat(),(CoverCarousel.firstPage(count)+count-1).toFloat())
                if(abs(target-current)<.0005f){snapshotFlow{quickGoal.coerceIn(CoverCarousel.firstPage(count).toFloat(),(CoverCarousel.firstPage(count)+count-1).toFloat())}.first{abs(it-current)>=.0005f};previousFrame=0L;continue}
                val frame=withFrameNanos{it};val dt=if(previousFrame==0L)1f/60f else (frame-previousFrame)/1_000_000_000f;previousFrame=frame
                val next=if((coroutineContext[MotionDurationScale]?.scaleFactor?:1f)<=0f)target else coverArcStep(current,target,dt,quickHeld)
                val step=(pager.layoutInfo.pageSize+pager.layoutInfo.pageSpacing).coerceAtLeast(1)
                scrollBy((next-current)*step)
            }
        }}finally{if(motionOwner[0]===owner)motionOwner[0]=null}
    }
    // Normalize only after settling: no long rewind through the entire day's history.
    LaunchedEffect(pager,visits){snapshotFlow{pager.settledPage to pager.isScrollInProgress}.collect{(page,moving)->if(!moving){val target=CoverCarousel.normalize(page,count);if(target!=page)pager.scrollToPage(target)}}}
    val carouselView=LocalView.current
    val carouselInspection=LocalInspectionMode.current
    // Keep the display request alive across the short idle pauses between automatic pages.
    LaunchedEffect(allowAuto,carouselInspection,carouselView){
        if(allowAuto&&!carouselInspection)while(isActive){boostViewFrameRate(carouselView);delay(500)}
    }
    LaunchedEffect(pager.isScrollInProgress,carouselInspection){
        if(pager.isScrollInProgress&&!carouselInspection){
            while(isActive&&pager.isScrollInProgress){
                boostViewFrameRate(carouselView)
                withFrameNanos{}
            }
        }
    }
    LaunchedEffect(allowAuto,visits,touchEpoch,quick,expandedGroup){
        if(quick||expandedGroup!=null||!allowAuto||count<2)return@LaunchedEffect
        var hold=maxOf(CoverCarousel.HOLD_MS,lastTouch+CoverCarousel.TOUCH_REST_MS-SystemClock.uptimeMillis())
        while(isActive){
            snapshotFlow{!pager.isScrollInProgress&&pager.settledPage==CoverCarousel.normalize(pager.settledPage,count)}.first{it}
            delay(hold);hold=CoverCarousel.HOLD_MS
            if(pager.isScrollInProgress)continue
            if((coroutineContext[MotionDurationScale]?.scaleFactor?:1f)<=0f)continue
            if(!carouselInspection){boostViewFrameRate(carouselView);withFrameNanos{};withFrameNanos{}}
            pager.animateScrollToPage((pager.settledPage+1).coerceAtMost(CoverCarousel.pageCount(count)-1),animationSpec=tween(520,easing=FastOutSlowInEasing))
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()){
        HorizontalPager(pager,userScrollEnabled=!quick,key={CoverCarousel.stopKey(it,stops)},contentPadding=PaddingValues(horizontal=((maxWidth-164.dp)/2).coerceAtLeast(0.dp)),pageSize=PageSize.Fixed(164.dp),pageSpacing=gallerySpacing,beyondViewportPageCount=if(quick)5 else 3,modifier=Modifier.fillMaxWidth().height(194.dp).testTag("cover-carousel").semantics{stateDescription=stops[CoverCarousel.sourceIndex(pager.currentPage,count)].play.visit;this[CoverRepeatCountKey]=stops[CoverCarousel.sourceIndex(pager.currentPage,count)].count;this[CoverPositionKey]=pager.currentPage+pager.currentPageOffsetFraction-CoverCarousel.firstPage(count)}.pointerInput(Unit){
            awaitPointerEventScope{while(true){val event=awaitPointerEvent(PointerEventPass.Initial);if(event.changes.any{it.pressed||it.previousPressed})touched()}}
        }.pointerInput(pager){awaitEachGesture{
            val down=awaitFirstDown(requireUnconsumed=false,pass=PointerEventPass.Initial);var previous=down.position;var active=false;var vertical=false
            do{val event=awaitPointerEvent(PointerEventPass.Initial);val change=event.changes.firstOrNull{it.id==down.id}?:break
                if(!change.pressed){if(active){quickHeld=false;quickGoal=quickGoal.roundToInt().toFloat()};break}
                val total=change.position-down.position
                if(quickState&&!active&&!vertical&&max(abs(total.x),abs(total.y))>viewConfiguration.touchSlop){if(abs(total.x)>abs(total.y))active=true else vertical=true}
                if(active&&quickState){val delta=change.position.x-previous.x;change.consume();quickHeld=true
                    val step=(pager.layoutInfo.pageSize+pager.layoutInfo.pageSpacing).coerceAtLeast(1).toFloat()
                    quickGoal=(quickGoal-delta*CoverArcDragGain/step).coerceIn(CoverCarousel.firstPage(count).toFloat(),(CoverCarousel.firstPage(count)+count-1).toFloat())
                }
                previous=change.position
            }while(true)
        }}) {index->
            val stop=stops[CoverCarousel.sourceIndex(index,count)];val p=stop.play;val offset=(pager.currentPage-index)+pager.currentPageOffsetFraction;val focus=(1f-abs(offset)).coerceIn(0f,1f);val key="gallery:${CoverCarousel.stopKey(index,stops)}"
            val touchSource=remember(stop.key){MutableInteractionSource()}
            val held by touchSource.collectIsPressedAsState()
            val pressScale by animateFloatAsState(if(held&&!quick).965f else 1f,spring(.8f,450f),label="cover press")
            var reflected by remember(p.id){mutableStateOf(c.images.peek(p.id,720))}
            val fadeSurface=LocalAlbumSurface.current();val coverOpacity by animateFloatAsState(playbackOpacity(stop.songs,stop.key==playing),tween(280),label="gallery playback opacity")
            Box(Modifier.zIndex(CoverCarousel.depth(offset)).graphicsLayer{scaleX=CoverCarousel.scale(offset)*(1f-.22f*arcProgress)*pressScale;scaleY=scaleX;rotationY=offset.coerceIn(-2f,2f)*(-4f);translationY=abs(offset).coerceAtMost(5f).pow(2)*3.dp.toPx()*arcProgress;rotationZ=-offset.coerceIn(-5f,5f)*6.5f*arcProgress;cameraDistance=size.width*CoverCarousel.CAMERA_WIDTHS;alpha=if(index in CoverCarousel.firstPage(count) until CoverCarousel.firstPage(count)+count)1f else 1f-arcProgress}.combinedClickable(interactionSource=touchSource,indication=null,onLongClick={touched();quickGoal=pager.currentPage+pager.currentPageOffsetFraction;quick=true},onClick={touched();if(quick){leaveQuick(index)}else if(pager.currentPage!=index)scope.launch{pager.animateScrollToPage(index,animationSpec=spring(.9f,260f))}else if(stop.count>1)expandedGroup=stop.key else open(p,key)})){
                // Opaque cards with a dark overlay, rather than translucent images exposing the card below.
                val foldInset by animateDpAsState(if(stop.count>1)8.dp else 0.dp,spring(.86f,280f),label="repeat front inset")
                FoldedCoverLayers(reflected,stop.count,focus)
                Column(Modifier.offset(y=foldInset)){Box(Modifier.size(164.dp).periodCoverMotion(shared,periodVisibility,index==pager.currentPage,clipOverlay=false).shadow(14.dp,RoundedCornerShape(17.dp))){with(shared){Artwork(c,p.id,Modifier.size(164.dp).summaryArtworkMotion(shared,summaryVisibility,index==pager.currentPage,if(c.range==DiaryRange.DAY)"summary-cover:0"else "inactive-day-summary-cover:0",RoundedCornerShape(17.dp)).sharedElement(rememberSharedContentState(key),visibility,renderInOverlayDuringTransition=ownedSharedOverlay(shared,visibility),clipInOverlayDuringTransition=OverlayClip(RoundedCornerShape(17.dp)),boundsTransform={_,_->tween(360,easing=FastOutSlowInEasing)}).clip(RoundedCornerShape(17.dp)).background(fadeSurface).drawWithContent{drawContent();if(coverOpacity<.999f)drawRect(Brush.verticalGradient(listOf(Color.Transparent,fadeSurface.copy(alpha=1f-coverOpacity)),startY=size.height*.18f,endY=size.height));drawRect(Color.Black.copy(alpha=(1f-focus)*.22f))},{colors->palettes[p.id]=colors},edge=720,onBitmap={reflected=it})};FoldedCoverBadge(stop.count,Modifier.align(Alignment.TopEnd).padding(8.dp))};CoverReflection(reflected,focus,Modifier.width(164.dp).height(20.dp).graphicsLayer{alpha=coverOpacity})}
            }
        }
        val page=pager.currentPage;val current=stops[CoverCarousel.sourceIndex(page,count)];val p=current.play
        if(!quick)AnimatedContent(current.key to (current.count>1),modifier=Modifier.align(Alignment.BottomCenter).detailCaptionFade().fillMaxWidth().padding(start=18.dp,end=18.dp,bottom=1.dp).testTag("cover-caption").press{touched();if(current.count>1)expandedGroup=current.key else open(p,"gallery:${CoverCarousel.stopKey(page,stops)}")},transitionSpec={fadeIn(tween(180))+slideInVertically{it/4} togetherWith fadeOut(tween(120))},label="cover caption"){target->
            val item=stops.firstOrNull{it.key==target.first}
            Box(Modifier.fillMaxWidth().height(44.dp),contentAlignment=Alignment.Center){Column(horizontalAlignment=Alignment.CenterHorizontally){
                if(item!=null&&item.count>1){Text(item.play.title,fontSize=14.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis);Text(coverStopTime(item,c.use12Hour),Modifier.padding(top=2.dp),fontSize=14.sp,color=Muted,maxLines=1)}
                else if(item!=null)Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.CenterVertically){Text(item.play.title,Modifier.weight(1f,fill=false),color=Muted,fontSize=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis);Text(" · "+coverStopTime(item,c.use12Hour),color=Muted,fontSize=14.sp,maxLines=1)}
            }}
        }
        if(quick)Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal=24.dp).testTag("cover-arc-controls"),horizontalAlignment=Alignment.CenterHorizontally){
            CoverArcScrubber(count,(pager.currentPage+pager.currentPageOffsetFraction-CoverCarousel.firstPage(count)).coerceIn(0f,(count-1).toFloat()),{value->touched();quickHeld=true;quickGoal=CoverCarousel.firstPage(count)+value},{quickHeld=false;quickGoal=quickGoal.roundToInt().toFloat()})
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("1",Modifier.press{quickHeld=false;quickGoal=CoverCarousel.firstPage(count).toFloat()},fontSize=12.sp,color=Muted);Text("${CoverCarousel.sourceIndex(pager.currentPage,count)+1} / $count",Modifier.weight(1f),fontSize=16.sp,fontWeight=FontWeight.Bold,color=MaterialTheme.colorScheme.onSurface,textAlign=TextAlign.Center);Text("$count",Modifier.press{quickHeld=false;quickGoal=(CoverCarousel.firstPage(count)+count-1).toFloat()},fontSize=12.sp,color=Muted)}
            Text("${p.title} · ${coverStopTime(current,c.use12Hour)}",Modifier.fillMaxWidth().padding(top=10.dp,bottom=7.dp).testTag("cover-arc-title"),fontSize=13.sp,fontWeight=FontWeight.SemiBold,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis,textAlign=TextAlign.Center)
        }
    }
    expandedGroup?.let{key->stops.firstOrNull{it.key==key}?.let{entry->
        ModalBottomSheet(onDismissRequest={expandedGroup=null;touched()},sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true),containerColor=LocalAlbumSurface.current()){
            FoldedCoverContents(c,entry){play->expandedGroup=null;touched();open(play,"gallery:${CoverCarousel.stopKey(pager.currentPage,stops)}")}
        }
    }}

}
@Composable private fun CoverReflection(bitmap:android.graphics.Bitmap?,focus:Float,modifier:Modifier){
    if(focus<=.02f||bitmap==null){Spacer(modifier);return}
    val placeholder=MaterialTheme.colorScheme.surfaceVariant
    val background=Brush.linearGradient(listOf(placeholder,lerp(placeholder,Accent,.1f)))
    val dark=MaterialTheme.colorScheme.background.luminance()<.5f
    val reveal by animateFloatAsState(1f,tween(220),label="reflection arrival")
    Canvas(modifier.graphicsLayer{compositingStrategy=CompositingStrategy.Offscreen}){
        drawCoverReflection(bitmap.asImageBitmap(),background,17.dp.toPx(),1.dp.toPx(),(if(dark).20f else .15f)*reveal,(1f-focus)*.22f)
    }
}

@Composable internal fun Artwork(c:DiaryController,id:String,modifier:Modifier,onTint:((List<Color>)->Unit)?=null,edge:Int=720,onBitmap:((android.graphics.Bitmap)->Unit)?=null,contentScale:ContentScale=ContentScale.Fit){
    val hidden=id.startsWith("artist:")&&!c.artistPhotosEnabled
    var bitmap by remember(id,edge){mutableStateOf(c.images.peek(id,edge))}
    DisposableEffect(id,edge,hidden,c.imageNetwork,c.imageEpoch,c.report.lastSync){val subscription=if(hidden)null else c.images.load(id,edge){image->if(image!=null)bitmap=image};onDispose{subscription?.close()}}
    val image=if(hidden)null else bitmap
    var phase by remember(id){mutableStateOf("")}
    LaunchedEffect(id,image==null,c.foreground,c.imageNetwork){if(edge>=720&&image==null&&c.foreground)while(true){phase=c.images.phase(id);delay(1000)}}
    val currentBitmap by rememberUpdatedState(onBitmap)
    LaunchedEffect(image){image?.let{currentBitmap?.invoke(it)}}
    val currentTint by rememberUpdatedState(onTint)
    LaunchedEffect(image,onTint!=null){image?.let{if(currentTint!=null){val colors=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default){coverColors(it).map{color->Color(color)}};currentTint?.invoke(colors)}}}
    val placeholder=MaterialTheme.colorScheme.surfaceVariant
    Box(modifier.background(Brush.linearGradient(listOf(placeholder,lerp(placeholder,Accent,.1f)))) ,contentAlignment=Alignment.Center){
        if(image==null){if(id.startsWith("artist:"))DiaryGlyph("person",Modifier.fillMaxSize(.5f),color=Muted.copy(alpha=.7f))else Text("♫",fontSize=if(edge<=160)15.sp else 25.sp,color=Accent.copy(alpha=.6f));if(edge>=720&&!hidden)Text(phase,Modifier.align(Alignment.BottomCenter).padding(bottom=18.dp),fontSize=10.sp,color=Muted,maxLines=1)}
        val alpha by animateFloatAsState(if(image==null)0f else 1f,tween(220),label="image arrival")
        if(image!=null)Image(image.asImageBitmap(),contentDescription=null,modifier=Modifier.fillMaxSize().graphicsLayer{this.alpha=alpha},contentScale=contentScale)
    }
}

@Composable private fun Detail(c:DiaryController,s:Selection,b:Backdrop,back:()->Unit,shared:SharedTransitionScope,visibility:AnimatedVisibilityScope,onTint:(List<Color>)->Unit){
    BackHandler(enabled=visibility.transition.targetState==EnterExitState.Visible) { back() }
    if(s.artist!=null || s.album!=null){MusicGroupDetail(c,s,back,shared,visibility,onTint);return}
    val detailReport=if(c.summary)c.summaryFacts.report else c.report
    val current=detailReport.history
    val (p,all,totals)=remember(current,s.play){val updated=current.firstOrNull{it.id==s.play.id};val merged=s.play.copy(artist=updated?.artist?:s.play.artist,album=updated?.album?:s.play.album);val matched=current.filter{it.id==merged.id};Triple(merged,matched,matched.sumOf{it.ms} to matched.sumOf{it.effective})}
    val dayOnly=!c.summary&&c.range==DiaryRange.DAY
    val weekly=if(c.summary||c.range!=DiaryRange.DAY)rememberWeeklyDetail(c,WeeklyTarget(p))else null
    val actions=LocalDetailActions.current
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().testTag("song-detail"),contentPadding=PaddingValues(25.dp),verticalArrangement=Arrangement.spacedBy(if(dayOnly)8.dp else 24.dp)){
        item{Row(Modifier.press(back).semantics{contentDescription="返回日记"}.padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically){DiaryGlyph("back",Modifier.size(24.dp),color=Accent);Text("返回日记",color=Accent,fontSize=17.sp)}}
        item{with(shared){Artwork(c,p.id,Modifier.fillMaxWidth().aspectRatio(1f).detailSource(s.key,shape=RoundedCornerShape(30.dp)).clip(RoundedCornerShape(30.dp)),onTint=onTint,edge=1200)}}
        item{Column(verticalArrangement=Arrangement.spacedBy(10.dp)){Text(p.title,fontSize=30.sp,fontWeight=FontWeight.Bold);Text(p.artist,color=Accent,fontSize=18.sp);Text(if(!c.summary&&p.album.isNotBlank())"${p.album} ›"else p.album,Modifier.then(if(!c.summary&&p.album.isNotBlank()&&actions!=null)Modifier.testTag("weekly-open-album").press{actions.album(p,"weekly-album-link:${p.id}")}else Modifier),color=Muted,fontSize=14.sp)}}
        item{Row(Modifier.fillMaxWidth().then(glass(b)).padding(23.dp),horizontalArrangement=Arrangement.SpaceBetween){Metric(duration(totals.first),if(c.summary)"全部实际聆听" else if(weekly!=null)"${detailPeriodName(c.range)}实际聆听"else "本期实际聆听",Accent);Metric("${totals.second}","有效播放",Pink)}}
        if(!dayOnly){
            val label=latestDeviceLabel(detailReport.segments.filter{it.id==p.id})
            if(label.isNotEmpty())item("song-devices"){Column{Text("\u6700\u8fd1\u4e00\u6b21\u8bb0\u5f55",fontSize=18.sp,fontWeight=FontWeight.SemiBold);Text(label,Modifier.padding(top=5.dp),fontSize=13.sp,color=Muted)}}
        }
        if(weekly!=null)weeklyDetailItems(c,weekly)
        if(weekly==null||c.summary)detailedRecordItems(c,all,dayOnly,showSongNames=!dayOnly,deviceParts=detailReport.segments.filter{it.id==p.id})

    }
}

@Composable private fun MusicGroupDetail(c:DiaryController,s:Selection,back:()->Unit,shared:SharedTransitionScope,visibility:AnimatedVisibilityScope,onTint:(List<Color>)->Unit){
    val records=(if(c.summary)c.summaryFacts.report else c.report).history
    val dayOnly=!c.summary&&c.range==DiaryRange.DAY
    val artist=s.artist;val actions=LocalDetailActions.current
    var artistRankTab by rememberSaveable(artist,c.range){mutableStateOf<Int?>(null)}
    if(artist!=null&&artistRankTab!=null){
        val sourceReport=if(c.summary)c.summaryFacts.report else c.report
        val artistReport=remember(sourceReport,artist){sourceReport.copy(history=sourceReport.history.filter{it.artist==artist},segments=sourceReport.segments.filter{it.artist==artist})}
        LibraryRanking(c,false,"",{artistRankTab=null},{p,key->actions?.track?.invoke(p,key)},shared,visibility,initialAlbum=artistRankTab==1,reportOverride=artistReport,artistFilter=artist)
        return
    }
    val weekly=if(c.summary||c.range!=DiaryRange.DAY)rememberWeeklyDetail(c,if(artist!=null)WeeklyTarget(s.play.copy(artist=artist),artist=true)else weeklySelectionTarget(records,s.play.copy(album=s.album?:s.play.album),true))else null
    val albumTitle=weekly?.target?.play?.album?:s.album
    val albumArtist=weekly?.target?.play?.artist?:s.play.artist
    val baseSongs=remember(records,artist,albumTitle,albumArtist){sortedPlays(records.filter{if(artist!=null)it.artist==artist else it.album==albumTitle&&it.artist==albumArtist},SortOrder.COUNT)}
    val firstPlayMap=remember(records,artist,s.album,s.play.artist,c.summary){
        if(c.summary){
            baseSongs.associate{p->p.id to (c.summaryFacts.trackFirstPlay(p.id)?:p)}
        }else{
            records.filter{if(artist!=null)it.artist==artist else it.album==s.album&&it.artist==s.play.artist}
                .groupBy{it.id}
                .mapValues{(_,list)->list.minByOrNull{it.start}!!}
        }
    }
    var songSortMode by rememberSaveable(artist,s.album){mutableIntStateOf(0)} // 0 = 常听, 1 = 时间
    var songDescending by rememberSaveable(artist,s.album){mutableStateOf(true)} // 常听默认倒序(最多在前)，切到时间默认正序(最早初遇在前)
    val orderedSongs=remember(baseSongs,firstPlayMap,songSortMode,songDescending){
        when(songSortMode){
            0->if(songDescending)baseSongs else baseSongs.asReversed()
            else->{
                val byFirst=baseSongs.sortedBy{firstPlayMap[it.id]?.start?:it.start}
                if(songDescending)byFirst.asReversed() else byFirst
            }
        }
    }
    val albumCount=remember(baseSongs){baseSongs.map{it.album}.filter{it.isNotBlank()}.distinct().size}
    val songSummary=remember(baseSongs,weekly!=null,c.range,artist,albumCount){(if(c.summary)"累计收听${baseSongs.size}首" else if(weekly!=null)"${detailPeriodName(c.range)}收听${baseSongs.size}首"else "${baseSongs.size}首歌曲")+(if(artist!=null&&(c.summary||c.range!=DiaryRange.DAY))" · ${albumCount}张专辑"else "")+" · ${baseSongs.sumOf{it.effective}}次播放 · ${shortDuration(baseSongs.sumOf{it.ms})}"}
    val artistAlbums=remember(records,artist,c.summary){if(artist==null)emptyList() else if(c.summary)c.summaryFacts.albums.filter{it.artist==artist} else reportAlbums(c.report).filter{it.artist==artist}}
    LazyColumn(Modifier.fillMaxSize().statusBarsPadding().testTag("music-group-detail"),contentPadding=PaddingValues(25.dp),verticalArrangement=Arrangement.spacedBy(if(dayOnly&&artist==null)8.dp else if(dayOnly)14.dp else 20.dp)){
        item{Row(Modifier.press(back).semantics{contentDescription="返回日记"}.padding(vertical=12.dp),verticalAlignment=Alignment.CenterVertically){DiaryGlyph("back",Modifier.size(24.dp),color=Accent);Text("返回日记",color=Accent,fontSize=17.sp)}}
        item{with(shared){Artwork(c,if(artist!=null)"artist:$artist" else s.play.id,Modifier.fillMaxWidth().aspectRatio(1f).detailSource(s.key,shape=RoundedCornerShape(30.dp)).clip(RoundedCornerShape(30.dp)),onTint=onTint,edge=1200)}}
        item{Text(artist?:albumTitle.orEmpty(),fontSize=30.sp,fontWeight=FontWeight.Bold);if(artist==null)Text(albumArtist,color=Muted,fontSize=16.sp);Text(songSummary,Modifier.padding(top=8.dp),fontSize=14.sp,color=Muted);if(c.summary&&artist==null){val album=c.summaryFacts.albums.find{it.title==s.album&&it.artist==s.play.artist};album?.let{albumCoverage(it,c.catalogFacts)}?.let{(heard,total)->Text("已听${heard}/${total}首",Modifier.padding(top=8.dp),fontSize=14.sp,color=Muted)}}}
        if(artist!=null&&c.summary){
            item { ArtistPreferenceTrajectoryCard(c, c.summaryFacts, artist) }
        }
        if(weekly!=null){
            weeklyDetailItems(c,weekly){albums->artistRankTab=if(albums)1 else 0}
        }
        if(weekly!=null&&!c.summary){
            // Period details already include the compact rankings above.
        }else if(artist!=null&&dayOnly){
            val today=records.filter{it.artist==artist}
            item("today-listening"){DailyArtistListening(c,today)}
        }else{
        if(artist!=null){
            val albums=artistAlbums
            if(albums.isNotEmpty())item{Text("常听专辑",fontSize=22.sp,fontWeight=FontWeight.Bold)}
            items(albums,key={it.key}){album->val key="artist-detail-album:${album.key}";Row(Modifier.fillMaxWidth().press{actions?.album?.invoke(album.cover,key)}.padding(vertical=7.dp),verticalAlignment=Alignment.CenterVertically){Artwork(c,album.cover.id,Modifier.size(44.dp).detailSource(key).clip(RoundedCornerShape(9.dp)),edge=160);Column(Modifier.padding(start=12.dp)){Text(album.title,fontSize=16.sp,maxLines=1,overflow=TextOverflow.Ellipsis);Text(shortDuration(album.ms),fontSize=12.sp,color=Muted)}}}
        }
        item{
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
                Text(if(dayOnly){if(songSortMode==0)"收听歌曲" else "收听顺序"}else if(songSortMode==0)"常听歌曲" else "相遇时间线",Modifier.weight(1f),fontSize=22.sp,fontWeight=FontWeight.Bold)
                SmallTabs(listOf("常听","时间"),songSortMode){mode->
                    if(songSortMode!=mode){
                        songSortMode=mode
                        songDescending = (mode == 0) // 常听默认从多到少，时间默认正序(最早相遇在前)
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    if(songDescending)"倒序 ⇅" else "正序 ⇅",
                    Modifier.clip(CircleShape).background(Accent.copy(alpha=.10f)).press{songDescending=!songDescending}.padding(horizontal=10.dp,vertical=5.dp),
                    fontSize=11.sp,
                    fontWeight=FontWeight.SemiBold,
                    color=Accent
                )
            }
        }
        items(orderedSongs,key={it.id}){p->
            val key="group-song:${p.id}"
            val firstPlay=firstPlayMap[p.id]?:p
            val firstZone=runCatching{ZoneId.of(firstPlay.zone)}.getOrDefault(ZoneId.systemDefault())
            val firstTimeText=Instant.ofEpochMilli(firstPlay.start).atZone(firstZone).format(
                DateTimeFormatter.ofPattern(
                    if(songSortMode==1){if(c.use12Hour)"yyyy年M月d日 a h:mm" else "yyyy年M月d日 HH:mm"}
                    else "yyyy.MM.dd",
                    java.util.Locale.CHINA
                )
            )
            val subtitle=if(dayOnly){(if(songSortMode==1)dailyRecordTime(firstPlay,c.use12Hour)+" · "else "")+"${p.effective}次 · ${shortDuration(p.ms)}"}else if(songSortMode==1){
                "${firstTimeText} 初遇 · ${p.effective}次 · ${shortDuration(p.ms)}"
            }else{
                "${p.effective}次 · ${shortDuration(p.ms)} · 初遇 $firstTimeText"
            }
            Row(Modifier.fillMaxWidth().testTag("group-song-row:${p.id}").press{actions?.track?.invoke(p,key)}.padding(vertical=7.dp),verticalAlignment=Alignment.CenterVertically){
                Artwork(c,p.id,Modifier.size(44.dp).detailSource(key).clip(RoundedCornerShape(9.dp)),edge=160)
                Column(Modifier.weight(1f).padding(start=12.dp)){
                    Text(p.title,fontSize=16.sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                    Text(subtitle,fontSize=12.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
                }
            }
        }
        }
        if(dayOnly&&artist==null)detailedRecordItems(c,records.filter{it.album==s.album&&it.artist==s.play.artist},true)
    }
}

@Composable private fun RhythmPlot(r:DiaryReport,modifier:Modifier,twelve:Boolean=false){
    val sessions=remember(r.segments){ListeningSessions.group(r.segments)}
    Column(modifier,horizontalAlignment=Alignment.CenterHorizontally){
        Box(Modifier.weight(1f).aspectRatio(1f),contentAlignment=Alignment.Center){
            Canvas(Modifier.fillMaxSize().padding(8.dp)){var start=-90f;val total=sessions.sumOf{it.ms};sessions.forEachIndexed{i,session->val sweep=if(total>0)session.ms.toFloat()/total*360 else 0f;if(sweep>0)drawArc(Palette[i%Palette.size],start+.8f,(sweep-1.6f).coerceAtLeast(.1f),false,style=Stroke(10.dp.toPx(),cap=StrokeCap.Round));start+=sweep}}
            Column(horizontalAlignment=Alignment.CenterHorizontally){Text("${sessions.size}",fontSize=24.sp,fontWeight=FontWeight.Bold);Text("段聆听",color=Muted,fontSize=10.sp)}
        }
        sessions.takeLast(2).reversed().forEach{session->Row(Modifier.padding(top=3.dp),verticalAlignment=Alignment.CenterVertically){Box(Modifier.size(5.dp).background(Palette[sessions.indexOf(session)%Palette.size],CircleShape));Text(" ${diaryClock(session.start,twelve)}–${diaryClock(session.end,twelve)}",fontSize=9.sp,color=Muted)}}
    }
}

@Composable private fun SessionSheet(c:DiaryController,r:DiaryReport,dismiss:()->Unit){
    val sessions=remember(r.segments){ListeningSessions.group(r.segments)}
    var expanded by remember{mutableStateOf(sessions.lastOrNull()?.key)}
    val panel=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.45f)
    ModalBottomSheet(onDismissRequest=dismiss,containerColor=MaterialTheme.colorScheme.surface){
        LazyColumn(Modifier.fillMaxWidth().heightIn(max=640.dp),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(4.dp)){
            item{Text("聆听场次",fontSize=26.sp,fontWeight=FontWeight.ExtraBold)}
            item{Text("连续播放归为一段，停止超过 15 分钟再开新段。暂停时间不计入聆听时长，跨日场次只展示当前范围内的部分。",Modifier.padding(vertical=12.dp),color=Muted,fontSize=12.sp)}
            sessions.asReversed().forEach{session->
                item("header:${session.key}"){Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(panel).press{expanded=if(expanded==session.key)null else session.key}.padding(16.dp)){
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("${diaryClock(session.start,c.use12Hour)}–${diaryClock(session.end,c.use12Hour)}",fontSize=18.sp,fontWeight=FontWeight.Bold,modifier=Modifier.weight(1f));DiaryGlyph(if(expanded==session.key)"down" else "next",Modifier.size(18.dp),color=Muted)}
                    Text("${duration(session.ms)} · ${session.songs.map{it.id}.distinct().size} 首歌曲",Modifier.padding(top=7.dp),fontSize=12.sp,color=Muted)
                }}
                if(expanded==session.key)items(session.songs,key={"song:${session.key}:${it.visit}"}){song->Row(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically){Text(diaryClock(song.start,c.use12Hour),fontSize=10.sp,color=Muted,modifier=Modifier.width(42.dp));Artwork(c,song.id,Modifier.size(32.dp).clip(RoundedCornerShape(7.dp)),edge=160);Column(Modifier.weight(1f).padding(horizontal=10.dp)){Text(song.title,fontSize=13.sp,maxLines=1,overflow=TextOverflow.Ellipsis);Text(song.artist,fontSize=11.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)};Text(shortDuration(song.ms),fontSize=10.sp,color=Muted)}}
            }
        }
    }
}

@Composable internal fun DiaryGlyph(kind:String,modifier:Modifier=Modifier,color:Color=LocalContentColor.current,label:String?=null){
    Canvas(modifier.then(if(label!=null)Modifier.semantics{contentDescription=label}else Modifier)){
        val w=size.width;val h=size.height
        when(kind){
            "more"->listOf(.22f,.5f,.78f).forEach{x->drawCircle(color,radius=w*.075f,center=Offset(w*x,h*.5f))}
            "person"->{drawCircle(color,radius=w*.19f,center=Offset(w*.5f,h*.25f));val shoulders=Path().apply{moveTo(w*.12f,h*.91f);cubicTo(w*.1f,h*.46f,w*.9f,h*.46f,w*.88f,h*.91f);close()};drawPath(shoulders,color)}
            else->{val path=Path();when(kind){"back"->{path.moveTo(w*.65f,h*.2f);path.lineTo(w*.35f,h*.5f);path.lineTo(w*.65f,h*.8f)};"up"->{path.moveTo(w*.2f,h*.65f);path.lineTo(w*.5f,h*.35f);path.lineTo(w*.8f,h*.65f)};"down"->{path.moveTo(w*.2f,h*.35f);path.lineTo(w*.5f,h*.65f);path.lineTo(w*.8f,h*.35f)};else->{path.moveTo(w*.35f,h*.2f);path.lineTo(w*.65f,h*.5f);path.lineTo(w*.35f,h*.8f)}};drawPath(path,color,style=Stroke(width=2.3.dp.toPx(),cap=StrokeCap.Round,join=StrokeJoin.Round))}
        }
    }
}
