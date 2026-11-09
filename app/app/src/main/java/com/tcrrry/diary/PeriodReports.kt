@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class,androidx.compose.material3.ExperimentalMaterial3Api::class)
package com.tcrrry.diary

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import com.kyant.backdrop.Backdrop
import kotlinx.coroutines.*
import java.time.*
import kotlin.math.*

internal data class PeriodComputed(val report:DiaryReport,val day:LocalDate,val range:DiaryRange,val days:Map<LocalDate,Long>,val albums:List<AlbumStat>,val ranking:List<Play>,val slices:List<PeriodHeroSlice>,val dailySongs:Map<LocalDate,List<Play>>,val monthlySongs:Map<LocalDate,List<Play>>,val monthlyTotals:Map<LocalDate,Long>,val favoriteAlbums:List<LibraryGroup>)
internal fun computePeriod(report:DiaryReport,day:LocalDate,range:DiaryRange):PeriodComputed {
    val days=reportDays(report);val daily=dailyPeriodSongs(report)
    val months=if(range==DiaryRange.YEAR)List(12){day.withMonth(it+1).withDayOfMonth(1)}else emptyList()
    val monthlySongs=months.associateWith{month->sortedPlays(daily.filterKeys{it.year==month.year&&it.monthValue==month.monthValue}.values.flatten(),SortOrder.COUNT)}
    val monthlyTotals=months.associateWith{month->days.filterKeys{it.year==month.year&&it.monthValue==month.monthValue}.values.sum()}
    val albums=reportAlbums(report)
    return PeriodComputed(report,day,range,days,albums,sortedPlays(report.history.filter{!it.imported||it.title!="未知歌曲"},SortOrder.COUNT),visiblePeriodHeroSlices(report,day,range),daily,monthlySongs,monthlyTotals,libraryAlbumGroups(albums))
}

/** Decode the destination's actual visible month, rather than the first few months of the year. */
internal fun periodWarmArtwork(computed:PeriodComputed,day:LocalDate):List<String>{
    val slice=computed.slices.getOrNull(heroSliceIndex(computed.slices,day))?:return emptyList()
    return (listOfNotNull(slice.lead?.id)+slice.albums.flatMap{it.songs}.sortedByDescending{it.ms}.take(6).map{it.id}+slice.artists.take(4).map{"artist:${it.name}"}).distinct().take(10)
}

internal fun visiblePeriodData(fresh:PeriodComputed?,previous:PeriodComputed?,day:LocalDate,range:DiaryRange):PeriodComputed? =
    fresh?.takeIf{it.day==day&&it.range==range}?:previous?.takeIf{it.day==day&&it.range==range}

internal fun heroLogicalIndex(page:Int,count:Int):Int = if(count<=1)0 else Math.floorMod(page-1,count)
internal fun heroRealPage(index:Int,count:Int):Int = if(count<=1)0 else index.coerceIn(0,count-1)+1
@Composable internal fun rememberPeriodHeroPager(day:LocalDate,range:DiaryRange,slices:List<PeriodHeroSlice>):PagerState {
    val pager=key(day,range){rememberPagerState(initialPage=heroRealPage(heroSliceIndex(slices,day),slices.size),pageCount={if(slices.size>1)slices.size+2 else slices.size})}
    var initialized by remember(day,range){mutableStateOf(slices.isNotEmpty())}
    LaunchedEffect(slices.isNotEmpty()){
        if(!initialized&&slices.isNotEmpty()){pager.scrollToPage(heroRealPage(heroSliceIndex(slices,day),slices.size));initialized=true}
    }
    return pager
}

@Composable internal fun PeriodReportPage(c:DiaryController,b:Backdrop,calendar:()->Unit,settings:()->Unit,open:(Play,String)->Unit,shared:SharedTransitionScope,visibility:AnimatedVisibilityScope,onTint:(List<Color>)->Unit,allowAuto:Boolean,period:DiaryRange=c.range,snapshot:DiaryReport=c.report,allSongs:(Boolean)->Unit={},allArtists:(String)->Unit={},periodVisibility:AnimatedVisibilityScope=visibility,summaryVisibility:AnimatedVisibilityScope=visibility,precomputed:PeriodComputed?=null,showPeriodHistory:(LocalDate?,PeriodComputed?)->Unit={_,_->}){
    val r=snapshot;val range=period;val day=c.day;val detailActions=LocalDetailActions.current
    val fresh=if(precomputed!=null&&precomputed.day==day&&precomputed.range==range)precomputed else if(c.demo)remember(r,day,range){computePeriod(r,day,range)}else {
        val result by produceState<PeriodComputed?>(null,r,day,range){value=withContext(Dispatchers.Default){computePeriod(r,day,range)}}
        result?.takeIf{it.day==day&&it.range==range}
    }
    var lastComputed by remember(day,range){mutableStateOf<PeriodComputed?>(null)}
    LaunchedEffect(fresh){if(fresh!=null)lastComputed=fresh}
    val computed=visiblePeriodData(fresh,lastComputed,day,range)
    val days=computed?.days.orEmpty();val ranking=computed?.ranking.orEmpty()
    val favoriteAlbums=computed?.favoriteAlbums.orEmpty()
    var albumMode by rememberSaveable{mutableStateOf(false)};var selected by remember(c.day,c.range){mutableStateOf(c.day)}
    val slices=computed?.slices.orEmpty()
    var lastVisibleSlices by remember(c.day,range){mutableStateOf(slices)}
    LaunchedEffect(computed){if(computed!=null)lastVisibleSlices=slices}
    val pagerSlices=if(computed==null)lastVisibleSlices else slices
    val heroPager=rememberPeriodHeroPager(c.day,range,pagerSlices)
    val scope=rememberCoroutineScope()
    LaunchedEffect(heroPager.settledPage,slices,range){
        if((range==DiaryRange.WEEK||range==DiaryRange.MONTH)&&slices.isNotEmpty()){val slice=slices[heroLogicalIndex(heroPager.settledPage,slices.size)];if(selected<slice.from||selected>slice.to)selected=slice.from}
    }
    val dailySongs=computed?.dailySongs.orEmpty()
    val list=rememberLazyListState()
    var discoveriesRequested by remember(r,c.day,range){mutableStateOf(false)}
    val discoveryVisible by remember(list,range){derivedStateOf{list.layoutInfo.visibleItemsInfo.any{it.key==when(range){DiaryRange.WEEK->"weekly-new";DiaryRange.MONTH->"monthly-new";DiaryRange.YEAR->"yearly-new";else->""}}}}
    LaunchedEffect(discoveryVisible){if(discoveryVisible)discoveriesRequested=true}
    val weeklyExtras by produceState<WeeklyExtras?>(null,r,computed,c.day,c.dayPolicy,c.demo,c.dataRevision,range,c.range,c.summary,discoveriesRequested){
        if(!discoveriesRequested||computed==null||range==DiaryRange.DAY||c.range!=range||c.summary)return@produceState
        value=null
        try{
            val (candidates,ids,artists)=withContext(Dispatchers.Default){
                val candidates=r.history.filter{!it.imported||it.title!="未知歌曲"}
                Triple(candidates,candidates.map{it.id}.toSet(),candidates.map{it.artist}.filter{it.isNotBlank()&&it!="未知歌手"}.toSet())
            }
            val discovered=if(c.demo)ids to artists else withContext(Dispatchers.IO){DiaryDatabase.get(c.activity).periodNewIdentities(c.day,range,c.dayPolicy,ids,artists)}
            value=withContext(Dispatchers.Default){
                val songs=sortedPlays(candidates.filter{it.id in discovered.first},SortOrder.COUNT)
                val groups=artistWeekGroups(r).filter{it.title in discovered.second}
                WeeklyExtras(songs,groups)
            }
        }catch(e:CancellationException){throw e}catch(_:Exception){value=null}
    }
    val first=LocalDate.parse(bounds(c.day,range).from);val last=LocalDate.parse(bounds(c.day,range).to)
    val dates=remember(first,last){generateSequence(first){it.plusDays(1)}.takeWhile{it<=last}.toList()}
    var selectedTrendMonth by remember(c.day,range){mutableIntStateOf(0)}
    LaunchedEffect(heroPager.settledPage){selectedTrendMonth=0}
    val selectionMonth=selectedTrendMonth.takeIf{it>0}?:slices.getOrNull(heroLogicalIndex(heroPager.settledPage,slices.size))?.from?.monthValue?:c.day.monthValue
    val heroVisible by remember{derivedStateOf{!list.isScrollInProgress&&list.layoutInfo.visibleItemsInfo.any{it.key=="hero"}}};val label=when(range){DiaryRange.WEEK->"本周";DiaryRange.MONTH->"本月";DiaryRange.YEAR->"年度";else->"今日"}
    val inheritedHistory=LocalHistoryMotion.current
    val historyEnabled=inheritedHistory?.enabled==true&&c.range==range
    val historyMotion=remember(shared,inheritedHistory,range,historyEnabled){inheritedHistory?.let{HistoryMotion(shared,it.visibility,range.name,historyEnabled)}}
    CompositionLocalProvider(LocalHistoryMotion provides historyMotion){
    LazyColumn(state=list,modifier=Modifier.fillMaxSize().testTag("period-report").statusBarsPadding(),contentPadding=PaddingValues(bottom=30.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
        item("header"){Column(Modifier.padding(horizontal=20.dp).padding(top=12.dp)){
            Text(periodHeader(c.day,range),Modifier.fillMaxWidth().press(calendar).testTag("period-date"),fontSize=14.sp,color=Muted)
            Row(Modifier.padding(top=13.dp,bottom=15.dp),verticalAlignment=Alignment.CenterVertically){Text("听歌日记",with(shared){Modifier.weight(1f).sharedElement(rememberSharedContentState("period-title"),periodVisibility,boundsTransform={_,_->tween(320,easing=LinearEasing)})},fontSize=34.sp,fontWeight=FontWeight.ExtraBold);Box(Modifier.size(38.dp).background(Muted.copy(alpha=.08f),CircleShape).press(settings),contentAlignment=Alignment.Center){DiaryGlyph("more",Modifier.size(22.dp),label="设置")}}
            Box(with(shared){Modifier.then(if(c.range==range)Modifier.sharedElement(rememberSharedContentState("summary-picker"),summaryVisibility,boundsTransform={_,_->tween(320,easing=LinearEasing)})else Modifier).sharedElement(rememberSharedContentState("period-picker"),periodVisibility,boundsTransform={_,_->tween(320,easing=LinearEasing)})}){AppPeriodPicker(c,b)}
        }}
        if(c.demo)item{Text("交互演示 · 示例记录",Modifier.padding(horizontal=22.dp),fontSize=10.sp,color=Muted)}
        item("hero"){Column(Modifier.animateContentSize(spring(.9f,260f))){
            val heroPending=pagerSlices.isEmpty()&&(r.from.isBlank()||computed==null||(c.range==range&&c.loading&&r.history.isEmpty()))
            AnimatedVisibility(pagerSlices.isNotEmpty()||heroPending,enter=expandVertically(tween(240))+fadeIn(tween(200)),exit=shrinkVertically(tween(240))+fadeOut(tween(160))){
                val shown=pagerSlices
                if(shown.isNotEmpty())PeriodHeroBrowser(c,shown,range,heroPager,onTint,open,allowAuto&&pagerSlices.isNotEmpty()&&heroVisible&&c.foreground,allArtists,shared,periodVisibility,summaryVisibility) else if(heroPending) PeriodHeroPlaceholder(range)
            }
            Spacer(Modifier.height(16.dp))
            ListeningStats(r,with(shared){Modifier.sharedBounds(rememberSharedContentState("period-stats"),periodVisibility,enter=fadeIn(tween(120)),exit=fadeOut(tween(120)),boundsTransform={_,_->tween(320,easing=LinearEasing)})}.padding(horizontal=16.dp)){c.notice=it}
            if(c.loading)Text("正在读取所选周期…",Modifier.padding(start=20.dp,top=4.dp),fontSize=11.sp,color=Muted)
        }}
        when(range){
            DiaryRange.WEEK->{
                item("week-history"){PeriodMotion(periodVisibility,0){PeriodDailyHistory(c,"一周历史",dates,dailySongs,days,selected,{selected=it;if(slices.isNotEmpty())scope.launch{heroPager.animateScrollToPage(heroRealPage(heroSliceIndex(slices,it),slices.size))}},{showPeriodHistory(it,computed)},r.segments)}}

                item("weekly-songs"){PeriodMotion(periodVisibility,2){Column(Modifier.padding(horizontal=18.dp)){
                    FavoriteHeading("本周循环",albumMode,{albumMode=it}){allSongs(albumMode)}
                    AnimatedContent(albumMode,transitionSpec={fadeIn(tween(250))+slideInHorizontally{it/10} togetherWith fadeOut(tween(160))},label="weekly favorites"){showAlbums->
                        if(showAlbums)FavoriteAlbumList(c,favoriteAlbums.take(5))else Column{ranking.take(5).forEachIndexed{i,p->val heard=dailySongs.count{(_,songs)->songs.any{it.id==p.id}};SongReportRow(c,p,i,if(heard>0)"${p.artist} · 听了${heard}天" else p.artist){open(p,"weekly:${p.id}")}}}
                    }
                }}}
                item("weekly-new"){PeriodMotion(periodVisibility,2){WeeklyNewSection(c,weeklyExtras,range,open)}}
            }
            DiaryRange.MONTH->{
                item("calendar"){PeriodMotion(periodVisibility,0){ReportPanel(Modifier.historyPanel()){
                    PeriodDailyStrip(c,"音乐月历",dates,dailySongs,days,selected,{selected=it;if(slices.isNotEmpty())scope.launch{heroPager.animateScrollToPage(heroRealPage(heroSliceIndex(slices,it),slices.size))}},{showPeriodHistory(it,computed)})
                    MonthCalendar(c.day,selected,days,r.firstRecorded){if(it==selected && (days[it]?:0L)>0L)showPeriodHistory(it,computed) else {selected=it;if(slices.isNotEmpty())scope.launch{heroPager.animateScrollToPage(heroRealPage(heroSliceIndex(slices,it),slices.size))}}}
                }}}

                item("monthly-favorites"){PeriodMotion(periodVisibility,2){Column(Modifier.padding(horizontal=18.dp)){FavoriteHeading("本月偏爱",albumMode,{albumMode=it}){allSongs(albumMode)}
                    AnimatedContent(albumMode,transitionSpec={fadeIn(tween(250))+slideInHorizontally{it/10} togetherWith fadeOut(tween(160))},label="monthly favorites"){showAlbums->if(showAlbums)FavoriteAlbumList(c,favoriteAlbums.take(5))else Column{ranking.take(5).forEachIndexed{i,p->SongReportRow(c,p,i,"${p.artist} · 听了${dailySongs.count{(_,songs)->songs.any{it.id==p.id}}}天"){open(p,"month:${p.id}")}}}}
                }}}
                item("monthly-new"){PeriodMotion(periodVisibility,2){WeeklyNewSection(c,weeklyExtras,range,open)}}
            }
            DiaryRange.YEAR->{

                item("year-chart"){PeriodMotion(periodVisibility,1){ReportPanel(Modifier.historyPanel()){val months=List(12){c.day.withMonth(it+1).withDayOfMonth(1)};val monthlyTotals=computed?.monthlyTotals.orEmpty();val monthlySongs=computed?.monthlySongs.orEmpty();val selectedMonth=c.day.withMonth(selectionMonth).withDayOfMonth(1);PeriodDailyStrip(c,"一年历史",months,monthlySongs,monthlyTotals,selectedMonth,{month->slices.indexOfFirst{it.from.monthValue==month.monthValue}.takeIf{it>=0}?.let{page->scope.launch{heroPager.animateScrollToPage(heroRealPage(page,slices.size),animationSpec=spring(.9f,240f))}}},{month->showPeriodHistory(month,computed)},monthly=true);YearWeeklyTrend(c.day,days){week->selectedTrendMonth=week.monthValue;slices.indexOfFirst{it.from.monthValue==week.monthValue}.takeIf{it>=0}?.let{page->scope.launch{heroPager.animateScrollToPage(heroRealPage(page,slices.size),animationSpec=spring(.9f,240f))}}};Row(Modifier.fillMaxWidth().padding(top=8.dp),horizontalArrangement=Arrangement.End){Text("查看月报 ›",Modifier.press{c.select(c.day.withMonth(selectionMonth).withDayOfMonth(1),DiaryRange.MONTH)},fontSize=13.sp,color=Accent)}}}}

            }
            else->{}
        }
        if(range==DiaryRange.YEAR){
                item("year-songs"){PeriodMotion(periodVisibility,2){Column(Modifier.padding(horizontal=18.dp)){
                    FavoriteHeading("年度循环",albumMode,{albumMode=it}){allSongs(albumMode)}
                    AnimatedContent(albumMode,transitionSpec={fadeIn(tween(250))+slideInHorizontally{it/10} togetherWith fadeOut(tween(160))},label="yearly favorites"){showAlbums->
                        if(showAlbums)FavoriteAlbumList(c,favoriteAlbums.take(5))else Column{ranking.take(5).forEachIndexed{i,p->SongReportRow(c,p,i,"${p.artist} · 听了${dailySongs.count{(_,songs)->songs.any{it.id==p.id}}}天"){open(p,"year-song:${p.id}")}}}
                    }
                }}}
        }
        if(range==DiaryRange.YEAR)item("yearly-new"){PeriodMotion(periodVisibility,2){WeeklyNewSection(c,weeklyExtras,range,open)}}
        item("artists"){PeriodMotion(periodVisibility,0){Column(Modifier.padding(horizontal=17.dp)){
            MemoryHeading("${label}常听歌手"){allArtists("")};Spacer(Modifier.height(15.dp))
            LazyRow(Modifier.testTag("period-artist-strip").graphicsLayer{compositingStrategy=CompositingStrategy.Offscreen}.drawWithContent{drawContent();val fade=14.dp.toPx();drawRect(Brush.horizontalGradient(listOf(Color.Transparent,Color.White),endX=fade),blendMode=BlendMode.DstIn);drawRect(Brush.horizontalGradient(listOf(Color.White,Color.Transparent),startX=size.width-fade,endX=size.width),blendMode=BlendMode.DstIn)},horizontalArrangement=Arrangement.spacedBy(4.dp)){
                items(r.topArtists.take(10),key={it.name}){a->
                    val artistDays=dailySongs.count{(_,songs)->songs.any{it.artist==a.name}}
                    Column(Modifier.width(92.dp).press{detailActions?.artist?.invoke(a.name,"strip-artist:${range.name}:${a.name}")},horizontalAlignment=Alignment.CenterHorizontally){Artwork(c,"artist:${a.name}",Modifier.size(68.dp).detailSource("strip-artist:${range.name}:${a.name}",shape=CircleShape).clip(CircleShape),edge=160);Text(a.name,Modifier.padding(top=8.dp),fontSize=14.sp,maxLines=1,overflow=TextOverflow.Ellipsis);Text("听了${artistDays}天",Modifier.padding(top=3.dp),fontSize=12.sp,color=Muted)}
                }
            }
        }}}
        item("period-facts"){PeriodMotion(periodVisibility,1){DailyListeningSummary(c,r.history,r.segments,Modifier.padding(horizontal=17.dp),range)}}
        if(range==DiaryRange.YEAR){
                item("moments"){PeriodMotion(periodVisibility,1){ReportPanel{Text("这一年的几个瞬间",fontSize=22.sp);days.maxByOrNull{it.value}?.let{Text("${it.key} · 听歌最多的一天 · ${it.value/60000}分钟",Modifier.padding(top=10.dp),fontSize=13.sp,color=Muted)}}}}
        }
    }
    }
}

internal data class WeeklyExtras(val newSongs:List<Play>,val newArtists:List<LibraryGroup>)

@Composable private fun WeeklyNewSection(c:DiaryController,data:WeeklyExtras?,range:DiaryRange,open:(Play,String)->Unit){
    val period=detailPeriodName(range)
    var artistTab by rememberSaveable(c.day,range){mutableStateOf(false)}
    var expanded by rememberSaveable(c.day,artistTab){mutableStateOf(false)}
    val detail=LocalDetailActions.current
    Column(Modifier.padding(horizontal=18.dp).testTag("weekly-new")){
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
            Text("${period}新增",Modifier.weight(1f),fontSize=23.sp,fontWeight=FontWeight.Bold)
            SmallTabs(listOf("歌曲","歌手"),if(artistTab)1 else 0){artistTab=it==1}
            val count=if(artistTab)data?.newArtists?.size?:0 else data?.newSongs?.size?:0
            if(count>5)Text("全部 ›",Modifier.padding(start=12.dp).press{expanded=true},fontSize=13.sp,color=Muted)
        }
        if(data==null)Text("正在整理${period}首次收听…",Modifier.padding(top=12.dp),fontSize=12.sp,color=Muted)
        else if(artistTab){
            val rows=data.newArtists.take(5)
            if(rows.isEmpty())Text("${period}没有首次收听的歌手",Modifier.padding(top=12.dp),fontSize=12.sp,color=Muted)
            rows.forEachIndexed{i,g->WeeklyNewArtistRow(c,g,i){detail?.artist?.invoke(g.title,"new-artist:${g.key}")}}
        }else{
            val rows=data.newSongs.take(5)
            if(rows.isEmpty())Text("${period}没有首次收听的歌曲",Modifier.padding(top=12.dp),fontSize=12.sp,color=Muted)
            rows.forEachIndexed{i,p->SongReportRow(c,p,i,p.artist){open(p,"new-song:${p.id}")}}
        }
    }
    if(expanded&&data!=null)Dialog(onDismissRequest={expanded=false}){
        Column(Modifier.fillMaxWidth().fillMaxHeight(.8f).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surface).padding(18.dp)){
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text(if(artistTab)"${period}新增歌手" else "${period}新增歌曲",Modifier.weight(1f),fontSize=20.sp,fontWeight=FontWeight.Bold);Text("关闭",Modifier.press{expanded=false},fontSize=13.sp,color=Accent)}
            LazyColumn(Modifier.weight(1f).padding(top=12.dp)){
                if(artistTab)itemsIndexed(data.newArtists,key={_,g->g.key}){i,g->WeeklyNewArtistRow(c,g,i){expanded=false;detail?.artist?.invoke(g.title,"new-artist:${g.key}")}}
                else itemsIndexed(data.newSongs,key={_,p->p.id}){i,p->SongReportRow(c,p,i,p.artist,beforeOpen={expanded=false}){open(p,"new-song:${p.id}")}}
            }
        }
    }
}
@Composable private fun WeeklyNewArtistRow(c:DiaryController,g:LibraryGroup,index:Int,click:()->Unit){
    Row(Modifier.fillMaxWidth().press(click).padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically){
        Text("%02d".format(index+1),Modifier.width(40.dp),fontSize=19.sp,color=Muted)
        Artwork(c,g.image,Modifier.size(49.dp).clip(CircleShape),edge=160)
        Column(Modifier.weight(1f).padding(horizontal=10.dp)){Text(g.title,fontSize=16.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis);Text("${g.songs.size}首 · ${shortDuration(g.ms)}",fontSize=12.sp,color=Muted)}
        Text("${g.plays}次",fontSize=19.sp)
    }
}

@Composable internal fun FavoriteHeading(title:String,album:Boolean,choose:(Boolean)->Unit,all:()->Unit){
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){
        Text(title,Modifier.weight(1f),fontSize=23.sp,fontWeight=FontWeight.Bold)
        Box(Modifier.testTag("favorite-type-tabs")){SmallTabs(listOf("歌曲","专辑"),if(album)1 else 0){choose(it==1)}}
        Text("全部 ›",Modifier.testTag("all-link:$title").padding(start=12.dp).press(all),fontSize=13.sp,color=Muted)
    }
}
@Composable internal fun SmallTabs(labels:List<String>,selected:Int,choose:(Int)->Unit){
    val cell=56.dp
    val position by animateFloatAsState(selected.toFloat(),spring(.85f,330f),label="secondary tab position")
    Box(Modifier.width(cell*labels.size).height(33.dp).background(Muted.copy(alpha=.08f),CircleShape)){
        Box(Modifier.offset{IntOffset((2.dp+cell*position).roundToPx(),2.dp.roundToPx())}.size(cell-4.dp,29.dp).shadow(3.dp,CircleShape).background(MaterialTheme.colorScheme.surface.copy(alpha=.92f),CircleShape))
        Row(Modifier.fillMaxSize()){labels.forEachIndexed{i,title->Box(Modifier.weight(1f).fillMaxHeight().semantics{role=Role.Tab;this.selected=selected==i}.press{choose(i)},contentAlignment=Alignment.Center){Text(title,fontSize=12.sp,color=if(i==selected)Accent else Muted)}}}
    }
}
@Composable internal fun ReportPanel(modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit){Column(Modifier.padding(horizontal=16.dp).fillMaxWidth().then(modifier).clip(RoundedCornerShape(23.dp)).background(Accent.copy(alpha=.045f)).padding(16.dp),content=content)}
@Composable private fun RowScope.ReportStat(value:String,label:String){Column(Modifier.weight(1f),horizontalAlignment=Alignment.CenterHorizontally){Text(value,fontSize=27.sp,fontWeight=FontWeight.Bold);Text(label,fontSize=12.sp,color=Muted)}}

@Composable internal fun SongReportRow(c:DiaryController,p:Play,index:Int,subtitle:String,rankChange:RankChange?=null,beforeOpen:()->Unit={},click:()->Unit){val detail=LocalDetailActions.current;val source=remember{java.util.UUID.randomUUID().toString()};Row(Modifier.fillMaxWidth().testTag("period-ranked-song:${p.id}").press{beforeOpen();detail?.track?.invoke(p,source) ?: click()}.padding(vertical=11.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.width(40.dp)){Text("%02d".format(index+1),fontSize=19.sp,color=Muted);RankChangeLabel(rankChange)};Artwork(c,p.id,Modifier.size(49.dp).detailSource(source).clip(RoundedCornerShape(9.dp)),edge=160);Column(Modifier.weight(1f).padding(horizontal=10.dp)){Text(p.title,fontSize=16.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis);Text(subtitle,fontSize=12.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)};Text("${p.effective}次",fontSize=19.sp)}}

@Composable internal fun RankChangeLabel(change:RankChange?){
    if(change?.current==null)return
    val delta=change.delta
    val color=if(delta==null)Muted else if(delta>0)Color(0xffe34560) else if(delta<0)Color(0xff2c9f72) else Muted
    Row(Modifier.testTag("rank-change"),verticalAlignment=Alignment.CenterVertically){
        if(delta!=null&&delta!=0){
            RoundedChangeArrow(delta>0,color)
            Text(kotlin.math.abs(delta).toString(),fontSize=10.sp,color=color,maxLines=1)
        }else Text(if(change.previous==null)"新上榜" else "—",fontSize=10.sp,color=color,maxLines=1)
    }
}

@Composable internal fun RoundedChangeArrow(up:Boolean,color:Color,modifier:Modifier=Modifier){
    Canvas(modifier.size(11.dp).semantics{contentDescription=if(up)"上升" else "下降"}){val w=size.width;val h=size.height;val top=if(up)h*.16f else h*.84f;val bottom=if(up)h*.82f else h*.18f;val shoulder=if(up)h*.42f else h*.58f
        val path=Path().apply{moveTo(w*.5f,bottom);lineTo(w*.5f,top);moveTo(w*.2f,shoulder);lineTo(w*.5f,top);lineTo(w*.8f,shoulder)}
        drawPath(path,color,style=Stroke(width=1.8.dp.toPx(),cap=StrokeCap.Round,join=StrokeJoin.Round))
    }
}

internal val MonthCapacityKey=SemanticsPropertyKey<Float>("MonthCapacity")
@Composable private fun MonthCalendar(month:LocalDate,selected:LocalDate,days:Map<LocalDate,Long>,firstRecorded:String,select:(LocalDate)->Unit){
    val accent=Accent;val muted=Muted;val max=(days.values.maxOrNull()?:0L).coerceAtLeast(1)
    Column(Modifier.padding(top=12.dp,bottom=10.dp)){
        Row{listOf("一","二","三","四","五","六","日").forEach{Text(it,Modifier.weight(1f),fontSize=12.sp,color=Muted,textAlign=TextAlign.Center)}}
        monthCells(month).chunked(7).forEach{week->Row(Modifier.padding(top=5.dp),horizontalArrangement=Arrangement.spacedBy(5.dp)){
            week.forEach{date->
                val ms=days[date]?:0L;val future=date?.isAfter(LocalDate.now())==true
                val amount=calendarCapacity(ms,max);val shape=RoundedCornerShape(8.dp)
                Box(Modifier.weight(1f).height(27.dp).clip(shape)
                    .background(if(date==null)Color.Transparent else muted.copy(alpha=if(future).035f else .075f))
                    .then(if(date==selected)Modifier.border(1.5.dp,accent,shape)else Modifier)
                    .then(if(date!=null&&!future)Modifier.testTag("month-day:$date").semantics{this[MonthCapacityKey]=amount;contentDescription="${date.monthValue}月${date.dayOfMonth}日，${shortDuration(ms)}"}.press{select(date)} else Modifier),
                    contentAlignment=Alignment.Center){
                    if(amount>0f&&!future)Box(Modifier.fillMaxWidth().fillMaxHeight(amount.coerceAtLeast(.08f)).align(Alignment.BottomCenter).clip(RoundedCornerShape(topStart=7.dp,topEnd=7.dp,bottomStart=3.dp,bottomEnd=3.dp)).background(Brush.verticalGradient(listOf(accent.copy(alpha=.38f),accent.copy(alpha=.68f)))))
                    if(date!=null)Text("${date.dayOfMonth}",fontSize=12.sp,color=if(future)muted else MaterialTheme.colorScheme.onSurface,fontWeight=if(date==selected)FontWeight.Bold else FontWeight.Normal)
                }
            }
            repeat(7-week.size){Spacer(Modifier.weight(1f))}
        }}
    }
}

@Composable internal fun PeriodBars(values:List<Long>,labels:List<String>,modifier:Modifier,selected:Int,future:(Int)->Boolean={false},format:(Long)->String=::reportHours,select:(Int)->Unit){val max=(values.maxOrNull()?:0L).coerceAtLeast(1);var shown by remember{mutableStateOf(false)};LaunchedEffect(Unit){shown=true};val levels=values.mapIndexed{i,v->animateFloatAsState(if(shown)v.toFloat()/max else 0f,tween(500,i*8),label="period bar $i").value};val accent=Accent;val muted=Muted
    Column(modifier.padding(top=14.dp)){Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(if(values.size>7)6.dp else 13.dp)){values.forEachIndexed{i,value->Column(Modifier.weight(1f).fillMaxHeight().testTag("period-bar:$i").press{if(!future(i))select(i)},horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Bottom){Text(if(future(i))"--"else format(value),fontSize=if(values.size>7)8.sp else 10.sp,color=Muted);Spacer(Modifier.height(4.dp));Box(Modifier.fillMaxWidth(.77f).fillMaxHeight(levels[i].coerceIn(.025f,1f)).clip(RoundedCornerShape(topStart=5.dp,topEnd=5.dp)).background(if(future(i))muted.copy(alpha=.08f)else accent.copy(alpha=if(i==selected).90f else .26f+.45f*levels[i])))}}}
        Row(Modifier.fillMaxWidth().padding(top=6.dp)){labels.forEach{Text(it,Modifier.weight(1f),fontSize=if(values.size>7)9.sp else 12.sp,color=Muted,textAlign=TextAlign.Center)}}
    }
}


@Composable internal fun PeriodFacts(days:Map<LocalDate,Long>,range:DiaryRange,report:DiaryReport,daily:Map<LocalDate,List<Play>>){
    val recorded=days.filterValues{it>0};val peak=recorded.maxByOrNull{it.value};val n=recorded.size
    if(n>0)ReportPanel{
        val label=when(range){DiaryRange.WEEK->"这一周";DiaryRange.MONTH->"这个月";else->"这一年"}
        Text(label, fontSize=18.sp,fontWeight=FontWeight.Bold)
        Text("听歌 ${n} 天 · 最多 ${peak!!.key.monthValue}月${peak.key.dayOfMonth}日 ${shortDuration(peak.value)}",Modifier.padding(top=7.dp),fontSize=13.sp,color=Muted)
        if(range==DiaryRange.MONTH&&n>1)Text("最长连续听歌 ${periodDayStreak(recorded.keys)} 天",Modifier.padding(top=4.dp),fontSize=12.sp,color=Muted)
        val topSong=remember(report.history){sortedPlays(report.history.filter{!it.imported||it.title!="未知歌曲"},SortOrder.COUNT).firstOrNull()}
        val topArtist=report.topArtists.firstOrNull()
        if(topSong!=null)Text("最常循环：${topSong.title} · ${topSong.effective}次",Modifier.padding(top=4.dp),fontSize=12.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
        if(topArtist!=null)Text("常听歌手：${topArtist.name} · 听了${daily.count{(_,v)->v.any{it.artist==topArtist.name}}}天",Modifier.padding(top=4.dp),fontSize=12.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
        if(range==DiaryRange.WEEK)Text("本周共听了 ${report.tracks} 首歌",Modifier.padding(top=4.dp),fontSize=12.sp,color=Muted)
        if(range==DiaryRange.YEAR){val topMonth=recorded.entries.groupBy{it.key.monthValue}.mapValues{(_,v)->v.sumOf{it.value}}.maxByOrNull{it.value};topMonth?.let{Text("听歌最多的月份：${it.key}月 · ${shortDuration(it.value)}",Modifier.padding(top=4.dp),fontSize=12.sp,color=Muted)}}
    }
}

/** The largest day fills its cell; all other days retain their true relative duration. */
internal fun calendarCapacity(ms:Long,max:Long):Float =
    if(ms<=0||max<=0)0f else (ms.toDouble()/max).coerceIn(0.0,1.0).toFloat()

/** Keep PeriodMotion <= 320ms so periodTransition.totalDurationNanos equals 320_000_000L and manual swipe keeps both halves of the animation. */
@Composable private fun PeriodMotion(scope:AnimatedVisibilityScope,order:Int,content:@Composable ()->Unit){
    val motion=with(scope){Modifier.animateEnterExit(
        enter=fadeIn(tween(210,delayMillis=order*25))+slideInHorizontally(tween(240,delayMillis=order*25,easing=FastOutSlowInEasing)){it/14},
        exit=fadeOut(tween(140))+slideOutHorizontally(tween(220)){-it/20}
    )}
    Box(motion){content()}
}
