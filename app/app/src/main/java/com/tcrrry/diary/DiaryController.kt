package com.tcrrry.diary
import android.app.Activity
import android.content.Context
import android.database.ContentObserver
import android.os.*
import androidx.compose.runtime.*
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.*
import java.time.LocalDate
import java.time.ZoneId

@Immutable
internal class RefKey<T>(val value: T) {
    override fun equals(other: Any?): Boolean = other is RefKey<*> && value === other.value
    override fun hashCode(): Int = System.identityHashCode(value)
}

@Immutable
data class Play @JvmOverloads constructor(val id:String,val visit:String,val title:String,val artist:String,val album:String,val start:Long,val end:Long,val ms:Long,val duration:Long,val zone:String,val occurrences:Int=1,val effective:Long=0,val imported:Boolean=false,val listeningDay:String="",val device:String="",val routeSource:String="",val volumePct:Int=-1):java.io.Serializable {
    internal fun interned(pool:MutableMap<String,String>):Play {
        fun intern(s:String)=pool.getOrPut(s){s}
        val a=intern(id);val b=intern(visit);val c=intern(title);val d=intern(artist);val e=intern(album);val f=intern(zone);val g=intern(listeningDay);val h=intern(device);val i=intern(routeSource)
        return if(a===id&&b===visit&&c===title&&d===artist&&e===album&&f===zone&&g===listeningDay&&h===device&&i===routeSource)this
        else copy(id=a,visit=b,title=c,artist=d,album=e,zone=f,listeningDay=g,device=h,routeSource=i)
    }
}
@Immutable
data class ArtistStat(val name:String,val ms:Long,val tracks:Int):java.io.Serializable
@Immutable
data class DiaryReport(val ms:Long=0,val plays:Int=0,val tracks:Int=0,val artists:Int=0,val history:List<Play> = emptyList(),val topArtists:List<ArtistStat> = emptyList(),val segments:List<Play> = emptyList(),val heat:Set<String> = emptySet(),val lastSync:Long=0,val nowTitle:String="",val recordState:String="等待采集插件",val from:String="",val to:String="",val dayTotals:Map<String,Long> = emptyMap(),val firstRecorded:String="",val artistFirst:Map<String,String> = emptyMap(),val nowTrackId:String="",val manualSkips:Long?=null,val manualSkipsSince:Long=0,val importedRows:Int=0,val missingTitles:Int=0,val skipsByDay:Map<String,Long> = emptyMap()):java.io.Serializable {
    override fun equals(other:Any?):Boolean = this === other
    override fun hashCode():Int = System.identityHashCode(this)
}
fun JSONObject.play()=Play(optString("id"),optString("visit",optString("id")),optString("title","未知歌曲"),optString("artist","未知歌手"),optString("album"),optLong("start"),optLong("end"),optLong("ms"),optLong("duration"),optString("zone",ZoneId.systemDefault().id),optInt("occurrences",1),optLong("plays",DailyRows.effective(optLong("ms"),optLong("duration"))),optInt("imported")==1)
fun JSONArray.objects()=(0 until length()).map{getJSONObject(it)}
fun JSONObject.toReport():DiaryReport {val s=getJSONObject("summary");return DiaryReport(s.optLong("ms"),optInt("plays"),s.optInt("tracks"),s.optInt("artists"),optJSONArray("daily")?.objects()?.map{it.play()}.orEmpty(),optJSONArray("artists")?.objects()?.map{ArtistStat(it.optString("artist"),it.optLong("ms"),it.optInt("tracks"))}.orEmpty(),optJSONArray("segments")?.objects()?.map{it.play()}.orEmpty(),optJSONArray("heat")?.objects()?.map{it.optString("day")}?.toSet().orEmpty(),optLong("last_sync"),optString("now_title"),optString("record_state"),optString("from"),optString("to"),optJSONArray("day_totals")?.objects()?.associate{it.optString("day") to it.optLong("ms")}.orEmpty(),optString("first_recorded"),optJSONArray("artist_first")?.objects()?.associate{it.optString("artist") to it.optString("day")}.orEmpty(),optString("now_track_id"),if(has("manual_skips"))optLong("manual_skips")else null,optLong("manual_skips_since"),optInt("imported_rows"))}
enum class DiaryThemeChoice(val label:String){SYSTEM("系统"),LIGHT("浅色"),DARK("深色")}

enum class SortOrder(val label:String,val mode:String){SEQUENCE("播放顺序","sequence"),LATEST("最近播放","latest"),COUNT("播放次数","count"),DURATION("聆听时长","duration")}
fun sortedPlays(list:List<Play>,sort:SortOrder):List<Play> {if(sort==SortOrder.SEQUENCE)return list.sortedBy{it.start};if(sort==SortOrder.LATEST)return list.sortedByDescending{it.start};val grouped=list.groupBy{it.id}.values.map{items->items.first().copy(ms=items.sumOf{it.ms},start=items.minOf{it.start},end=items.maxOf{it.end},occurrences=items.size,effective=items.sumOf{it.effective})};return if(sort==SortOrder.COUNT)grouped.sortedWith(compareByDescending<Play>{it.effective}.thenByDescending{it.ms})else grouped.sortedByDescending{it.ms}}

class DiaryController(val activity:Activity,private val scope:LifecycleCoroutineScope,saved:Bundle?) {
    var day by mutableStateOf(runCatching{LocalDate.parse(saved?.getString("day"))}.getOrDefault(LocalDate.now()));private set
    private var periodSettleJob:Job?=null
    internal fun cancelPeriodSettlement(){periodSettleJob?.cancel();periodSettleJob=null;periodSwipePosition=null}
    internal fun settlePeriodSwipe(animationScope:CoroutineScope,current:Int,target:Int,commit:Boolean,releaseVelocity:Float=0f){
        periodSettleJob?.cancel()
        val start=periodSwipePosition?:current.toFloat()
        val end=(if(commit)target else current).toFloat()
        val motion=PeriodReleaseMotion(end-start,releaseVelocity)
        periodSettleJob=animationScope.launch{
            try{
                // Finish the same seek timeline before changing the selected page or its layout.
                androidx.compose.animation.core.animate(start,end,animationSpec=androidx.compose.animation.core.tween(motion.durationMillis,easing=motion.easing)){value,_->periodSwipePosition=value}
                androidx.compose.runtime.withFrameNanos{}
                androidx.compose.runtime.withFrameNanos{}
                if(commit){if(target==4)openSummary(keepSummaryMotion=true)else select(range=DiaryRange.entries[target],keepPeriodMotion=true)}
            }finally{periodSwipePosition=null}
        }
    }
    private var lastNavigationAt=android.os.SystemClock.elapsedRealtime()
    internal var navigationSettling by mutableStateOf(false);private set
    private var navigationSettleTimer:Job?=null
    private var navigationSettleWallTimer:Job?=null
    private var navigationSettleToken=0L
    internal fun markNavigationTransition(durationMs:Long=350L){
        lastNavigationAt=android.os.SystemClock.elapsedRealtime()
        val token=++navigationSettleToken
        navigationSettling=true
        navigationSettleTimer?.cancel()
        navigationSettleWallTimer?.cancel()
        navigationSettleTimer=scope.launch{delay(durationMs);if(navigationSettleToken==token){navigationSettling=false;navigationSettleWallTimer?.cancel();androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()}}
        navigationSettleWallTimer=scope.launch(Dispatchers.Default){delay(durationMs);withContext(Dispatchers.Main.immediate){if(navigationSettleToken==token){navigationSettling=false;navigationSettleTimer?.cancel();androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()}}}
    }
    private val verticalScrollSources=mutableSetOf<Any>()
    internal var verticalScrolling by mutableStateOf(false);private set
    internal fun setVerticalScrolling(source:Any,active:Boolean){
        if(active)verticalScrollSources.add(source) else verticalScrollSources.remove(source)
        val next=verticalScrollSources.isNotEmpty()
        if(verticalScrolling!=next)verticalScrolling=next
    }
    private var lastHeavyPeriodRefreshAt=0L
    internal suspend fun awaitNavigationSettled(){
        snapshotFlow{periodSwipePosition==null&&!navigationSettling&&!verticalScrolling}.first{it}
    }
    internal suspend fun raceDelay(ms:Long){
        if(ms<=0L)return
        val gate=kotlinx.coroutines.CompletableDeferred<Unit>()
        val wall=scope.launch(Dispatchers.Default){delay(ms);gate.complete(Unit)}
        val main=scope.launch{delay(ms);gate.complete(Unit)}
        try{gate.await()}finally{wall.cancel();main.cancel()}
    }
    private var swipePositionState by mutableStateOf<Float?>(null)
    internal var periodSwipePosition:Float?
        get()=swipePositionState
        set(value){if(value!=null)lastNavigationAt=android.os.SystemClock.elapsedRealtime();swipePositionState=value}
    internal var summaryMotionSeed by mutableIntStateOf(kotlin.random.Random.nextInt());private set
    internal fun chooseSummaryMotion(){var next=kotlin.random.Random.nextInt();while(next==summaryMotionSeed)next=kotlin.random.Random.nextInt();summaryMotionSeed=next}
    internal var periodMotionSeed by mutableIntStateOf(0);private set
    private val periodLayoutSeeds=androidx.compose.runtime.mutableStateListOf(kotlin.random.Random.nextInt(),kotlin.random.Random.nextInt(),kotlin.random.Random.nextInt(),kotlin.random.Random.nextInt())
    internal fun periodLayoutSeed(range:DiaryRange):Int=periodLayoutSeeds[range.ordinal]
    internal fun choosePeriodLayout(range:DiaryRange){val old=periodLayoutSeeds[range.ordinal];var next=kotlin.random.Random.nextInt();while(next==old)next=kotlin.random.Random.nextInt();periodLayoutSeeds[range.ordinal]=next}
    internal fun choosePeriodMotion(){periodMotionSeed=kotlin.random.Random.nextInt()}
    internal var lastPeriodTintId:String?=null
    var range by mutableStateOf(DiaryRange.entries.getOrElse(saved?.getInt("range")?:0){DiaryRange.DAY});private set
    var report by mutableStateOf(DiaryReport(),referentialEqualityPolicy());private set
    internal var retainedPeriodComputed:PeriodComputed?=null
    internal var periodTransitionReport by mutableStateOf<DiaryReport?>(null,referentialEqualityPolicy());private set
    var summary by mutableStateOf(saved?.getBoolean("summary")?:false);private set
    internal var summaryFacts by mutableStateOf(SummaryFacts(DiaryReport()));private set
    internal var catalogFacts by mutableStateOf<Map<String,CatalogFact>>(emptyMap());private set
    var summaryLoading by mutableStateOf(false);private set
    var summaryError by mutableStateOf<String?>(null);private set
    private data class SummaryStamp(val revision:Long,val metadata:Long,val policy:ListeningDayPolicy,val demo:Boolean)
    private var summaryStamp:SummaryStamp?=null
    private var summaryPreloadJob:Job?=null
    private var lastSummaryPreloadAt=0L
    private fun summaryKey()=SummaryStamp(dataRevision,previewMetadataEpoch,dayPolicy,demo)
    private var summaryJob:Job?=null
    private var summaryMetadataJob:Job?=null
    private var summaryReadAt=0L
    private suspend fun awaitSummaryRevealStable(){awaitNavigationSettled()}
    var loading by mutableStateOf(true);private set
    internal var showingCached by mutableStateOf(false);private set
    var error by mutableStateOf<String?>(null);private set
    var demo by mutableStateOf(false);private set
    var approved by mutableStateOf(BridgeIdentity.approved(activity));private set
    var notice by mutableStateOf<String?>(null)
    var imageEpoch by mutableIntStateOf(0);private set
    private val prefs=activity.getSharedPreferences("diary",Context.MODE_PRIVATE)
    var artEpoch by mutableIntStateOf(prefs.getInt("art_epoch",0));private set
    var foreground by mutableStateOf(false);private set
    var amTypeface by mutableStateOf<android.graphics.Typeface?>(null);private set
    private var fontJob:Job?=null
    var effectiveSeconds by mutableIntStateOf(prefs.getInt("effective_seconds",30).coerceIn(0,60));private set
    init { DailyRows.threshold(effectiveSeconds) }
    fun effectiveSeconds(value:Int){
        val seconds=value.coerceIn(0,60)
        if(seconds==effectiveSeconds)return
        effectiveSeconds=seconds
        job?.cancel();periodCacheWriteJobs.values.forEach{it.cancel()};periodCacheWriteJobs.clear();summaryPreloadJob?.cancel();summaryJob?.cancel();summaryMetadataJob?.cancel();summaryDiskTask?.cancel()
        scope.launch {
            withContext(Dispatchers.IO){DiaryDatabase.get(activity).setEffectiveThreshold(seconds)}
            dataRevision=prefs.getLong("data_revision",dataRevision)
            preparedPeriods.clear();cachedDiscoveries.clear();cachedListeningSummaries.clear();summaryStamp=null;summaryDiskTask=null;summaryDiskKey=null;lastSummaryPreloadAt=0L
            cachedSummaryGenres=null;cachedSummaryPreferences=null
            summaryFacts=SummaryFacts(DiaryReport());report=DiaryReport();retainedPeriodComputed=null
            refresh(false)
        }
    }
    internal fun correctDailyTrackPlayback(
        day:LocalDate,
        trackId:String,
        trackTitle:String,
        visitsToRemove:Set<String>,
        keptPlays:Int,
        removedEffective:Int,
        removedMs:Long,
        onComplete:()->Unit={}
    ){
        if(demo||visitsToRemove.isEmpty()){onComplete();return}
        job?.cancel();dayPreviewJob?.cancel();periodCacheWriteJobs.values.forEach{it.cancel()};periodCacheWriteJobs.clear();summaryPreloadJob?.cancel();summaryJob?.cancel();summaryMetadataJob?.cancel();summaryDiskTask?.cancel()
        scope.launch {
            try {
                val removed=withContext(Dispatchers.IO){
                    val count=DiaryDatabase.get(activity).removeTrackVisits(trackId,visitsToRemove,day,keptPlays,removedEffective,removedMs)
                    DiaryDatabase.projection(activity).releaseProjectionMemory()
                    count
                }
                if(removed>0){
                    dataRevision=prefs.getLong("data_revision",dataRevision)
                    preparedPeriods.clear();cachedDiscoveries.clear();cachedListeningSummaries.clear()
                    summaryStamp=null;summaryDiskTask=null;summaryDiskKey=null;lastSummaryPreloadAt=0L;lastHeavyPeriodRefreshAt=0L
                    cachedSummaryGenres=null;cachedSummaryPreferences=null
                    summaryFacts=SummaryFacts(DiaryReport());retainedPeriodComputed=null
                    loadAppleBatches()
                    refresh(false)
                    notice=if(keptPlays==0)"已清除《$trackTitle》当日异常记录，可在此处撤销" else "已将《$trackTitle》当日记录修正为 $keptPlays 次播放，可在此处撤销"
                }else{
                    notice="未找到需要修正的记录"
                }
            }catch(e:CancellationException){throw e}
            catch(_:Exception){notice="修正未完成，请重试"}
            finally{onComplete()}
        }
    }
    internal fun undoDailyTrackCorrection(day:LocalDate,trackId:String,trackTitle:String,onComplete:()->Unit={}){
        if(demo){onComplete();return}
        job?.cancel();dayPreviewJob?.cancel();periodCacheWriteJobs.values.forEach{it.cancel()};periodCacheWriteJobs.clear();summaryPreloadJob?.cancel();summaryJob?.cancel();summaryMetadataJob?.cancel();summaryDiskTask?.cancel()
        scope.launch {
            try {
                val restored=withContext(Dispatchers.IO){
                    val count=DiaryDatabase.get(activity).undoTrackCorrection(day,trackId)
                    DiaryDatabase.projection(activity).releaseProjectionMemory()
                    count
                }
                if(restored>0){
                    dataRevision=prefs.getLong("data_revision",dataRevision)
                    preparedPeriods.clear();cachedDiscoveries.clear();cachedListeningSummaries.clear()
                    summaryStamp=null;summaryDiskTask=null;summaryDiskKey=null;lastSummaryPreloadAt=0L;lastHeavyPeriodRefreshAt=0L
                    cachedSummaryGenres=null;cachedSummaryPreferences=null
                    summaryFacts=SummaryFacts(DiaryReport());retainedPeriodComputed=null
                    loadAppleBatches()
                    refresh(false)
                    notice="已撤销《$trackTitle》的当日播放修正"
                }else{
                    notice="没有可撤销的修正记录"
                }
            }catch(e:CancellationException){throw e}
            catch(_:Exception){notice="撤销未完成，请重试"}
            finally{onComplete()}
        }
    }
    internal var dataRevision by mutableLongStateOf(prefs.getLong("data_revision",0L));private set
    internal var previewMetadataEpoch by mutableLongStateOf(0L);private set
    internal fun isPreviewRevisionCurrent(revision:Long)=demo||prefs.getLong("data_revision",0L)==revision

    var dayPolicy by mutableStateOf(ListeningDayPolicy(runCatching{ListeningDayMode.valueOf(prefs.getString("day_mode","CALENDAR")!!)}.getOrDefault(ListeningDayMode.CALENDAR),prefs.getInt("day_hour",4).coerceIn(0,12)));private set
    private var followsListeningToday = saved?.getBoolean("follow_today", saved.getString("day")==null || saved.getString("day")==LocalDate.now().toString()) ?: true
    private var latestListeningDay = day
    private var listeningClockJob:Job? = null
    init { if(saved==null){day=listeningToday(java.time.ZonedDateTime.now(),dayPolicy);latestListeningDay=day} }
    internal suspend fun currentListeningDay(now:java.time.ZonedDateTime=java.time.ZonedDateTime.now()):LocalDate {
        val policy=dayPolicy
        return if(demo||policy.mode!=ListeningDayMode.AUTO||now.hour>=6)listeningToday(now,policy)
        else withContext(Dispatchers.IO){DiaryDatabase.get(activity).currentListeningDay(now,policy)}
    }
    internal suspend fun alignListeningDay(now:java.time.ZonedDateTime=java.time.ZonedDateTime.now()) {
        val policy=dayPolicy
        val actual=currentListeningDay(now)
        if(policy!=dayPolicy)return
        latestListeningDay=actual
        if(followsListeningToday && day!=actual){day=actual;refresh(false)}
    }
    private fun startListeningClock(){
        listeningClockJob?.cancel()
        listeningClockJob=scope.launch { while(isActive){ alignListeningDay(); delay(60000) } }
    }
    fun dayPolicy(value:ListeningDayPolicy){dayPolicy=value;if(foreground)startListeningClock();prefs.edit().putString("day_mode",value.mode.name).putInt("day_hour",value.hour).apply();preparedPeriods.clear();cachedDiscoveries.clear();cachedListeningSummaries.clear();cachedSummaryGenres=null;cachedSummaryPreferences=null;summaryPreloadJob?.cancel();summaryStamp=null;lastSummaryPreloadAt=0L;summaryJob?.cancel();summaryMetadataJob?.cancel();summaryFacts=SummaryFacts(DiaryReport());refresh(false)}
    var use12Hour by mutableStateOf(prefs.getBoolean("clock_12_hour",false));private set
    fun use12Hour(enabled:Boolean){use12Hour=enabled;prefs.edit().putBoolean("clock_12_hour",enabled).apply()}
    var imageNetwork by mutableStateOf(prefs.getBoolean("image_network",true));private set
    var theme by mutableStateOf(runCatching{DiaryThemeChoice.valueOf(prefs.getString("theme","SYSTEM")!!)}.getOrDefault(DiaryThemeChoice.SYSTEM));private set
    var artistPhotosEnabled by mutableStateOf(prefs.getBoolean("artist_photos_enabled",true));private set
    fun theme(choice:DiaryThemeChoice){theme=choice;prefs.edit().putString("theme",choice.name).apply()}
    fun artistPhotos(show:Boolean){artistPhotosEnabled=show;prefs.edit().putBoolean("artist_photos_enabled",show).apply();images.artistNetwork(show)}
    private var metadataRefreshJob:Job?=null
    internal val images=DiaryArtwork(java.io.File(activity.filesDir,"art")).apply{network(imageNetwork);artistNetwork(artistPhotosEnabled);onMetadataChanged{if(foreground){previewMetadataEpoch++;metadataRefreshJob?.cancel();metadataRefreshJob=scope.launch{raceDelay(1200);snapshotFlow{periodSwipePosition==null&&!loading}.first{it};refresh(true);if(summary){summaryJob?.join();if(summary&&foreground)refreshSummary()}}}}}
    private var job:Job?=null
    internal val collectorLink=CollectorLink(activity,scope){refresh(true)}
    internal var lanStatus by mutableStateOf("尚未配对");private set
    internal var lanPaired by mutableStateOf(false);private set
    internal var lanCode by mutableStateOf<String?>(null);private set
    internal var lanName by mutableStateOf("");private set
    internal var lanManualAddress by mutableStateOf("");private set
    internal var lanDevices by mutableStateOf<List<LanKnownDevice>>(emptyList());private set
    internal var browserDevices by mutableStateOf<List<BrowserSyncDevice>>(emptyList());private set
    private val lan=LanForegroundSync.shared(activity)
    private val browserSync:BrowserSyncServer by lazy {BrowserSyncServer(activity,{lan.browserGroupKey()},{lan.localAddresses()+lan.peers().flatMap{it.addresses}},{lan.ownName()}){browserDevices=browserSync.devices();if(foreground)refresh(true)}}
    internal fun lanRename(name:String){runCatching{lan.rename(name);lanName=lan.ownName()}.onFailure{lanStatus=it.message?:"设备名称无效"}}
    internal fun lanManualAddress(value:String):Boolean=runCatching{lan.manualAddress(value);lanManualAddress=lan.manualAddress();true}.getOrElse{lanStatus=it.message?:"地址无效";false}
    internal fun lanPermissionDenied(){lanStatus="需要允许局域网权限才能发现并连接设备"}
    internal fun lanCreateCode(){(activity as MainActivity).withLanPermission{runCatching{val code=lan.createCode{lanCode=null;browserSync.cancelGroupPairing()};lanCode=code;lanPaired=lan.paired;browserSync.beginGroupPairing(code)}.onFailure{lanStatus="无法生成配对码"}}}
    internal fun lanJoin(code:String,address:String){(activity as MainActivity).withLanPermission{runCatching{lan.join(code,address){error->if(error==null){lanPaired=lan.paired;lanManualAddress=lan.manualAddress();lanCode=null;if(foreground)browserSync.start()}else lanStatus=error}}.onFailure{lanStatus=it.message?:"配对码无效"}}}
    internal fun lanAddresses()=lan.localAddresses()
    internal fun lanEnable(){(activity as MainActivity).withLanPermission{lan.start()}}
    internal fun lanHideCode(){lan.hideCode();browserSync.cancelGroupPairing();lanCode=null}
    internal fun lanUnpair(){browserSync.cancelGroupPairing();browserSync.revoke();browserDevices=emptyList();lan.unpair();lanCode=null;lanPaired=false;lanStatus="尚未配对"}
    val syncing get()=collectorLink.syncing
    val syncStatus get()=collectorLink.status
    private val observer=object:ContentObserver(Handler(Looper.getMainLooper())){override fun onChange(selfChange:Boolean){if(foreground){artEpoch=prefs.getInt("art_epoch",artEpoch);collectorLink.received();refresh(true)}}}
    fun start(){if(foreground)return;foreground=true;lan.bind({lanStatus=it},{if(foreground)refresh(true)},{lanDevices=it});lanPaired=lan.paired;lanName=lan.ownName();lanManualAddress=lan.manualAddress();lanDevices=lan.peers();browserDevices=browserSync.devices();lan.start();browserSync.start();startListeningClock();if(amTypeface==null&&fontJob?.isActive!=true)fontJob=scope.launch{amTypeface=withContext(Dispatchers.IO){runCatching{val host=activity.createPackageContext(BridgeIdentity.HOST,0);val rounded=host.resources.getIdentifier("sf_pro_rounded_android_ui","font",BridgeIdentity.HOST);val id=if(rounded!=0)rounded else host.resources.getIdentifier("sf_pro_android_ui","font",BridgeIdentity.HOST);if(id!=0)host.resources.getFont(id)else null}.getOrNull()}};images.active(true);imageEpoch++;activity.contentResolver.registerContentObserver(BridgeIdentity.URI,true,observer);approved=BridgeIdentity.approved(activity);refresh(false);if(!demo)collectorLink.start(approved)}
    fun stop(){if(!foreground)return;verticalScrollSources.clear();verticalScrolling=false;browserSync.stop();lan.stop();lan.unbind();lanCode=null;navigationSettleTimer?.cancel();navigationSettleWallTimer?.cancel();navigationSettling=false;periodCacheWriteJobs.values.forEach{it.cancel()};periodCacheWriteJobs.clear();summaryExitTicket++;summaryExitJob?.cancel();listeningClockJob?.cancel();summaryPreloadJob?.cancel();metadataRefreshJob?.cancel();collectorLink.stop();summaryJob?.cancel();summaryMetadataJob?.cancel();foreground=false;activity.contentResolver.unregisterContentObserver(observer);images.active(false)}
    internal fun trimMemory(aggressive:Boolean){
        images.trimMemory(aggressive)
        if(aggressive){
            preparedPeriods.clear();cachedDiscoveries.clear();cachedListeningSummaries.clear();retainedPeriodComputed=null
            if(!demo)scope.launch(Dispatchers.IO){DiaryDatabase.projection(activity).releaseProjectionMemory()}
        }
    }
    fun close(){navigationSettleTimer?.cancel();navigationSettleWallTimer?.cancel();periodCacheWriteJobs.values.forEach{it.cancel()};periodCacheWriteJobs.clear();summaryExitJob?.cancel();listeningClockJob?.cancel();summaryPreloadJob?.cancel();metadataRefreshJob?.cancel();appleListJob?.cancel();appleJob?.cancel();collectorLink.stop();summaryJob?.cancel();summaryMetadataJob?.cancel();job?.cancel();dayPreviewJob?.cancel();fontJob?.cancel();images.close()}
    private var summaryExitJob:Job?=null
    private var summaryExitTicket=0L
    private val periodCacheWriteJobs=mutableMapOf<DiaryRange,Job>()
    internal data class DiscoveryCacheEntry(val revision:Long,val metadataEpoch:Long,val reportRef:DiaryReport,val extras:WeeklyExtras)
    internal val cachedDiscoveries=mutableMapOf<DiaryRange,DiscoveryCacheEntry>()
    internal val cachedListeningSummaries=mutableMapOf<String,Any>()
    internal val preparedPeriods=mutableStateMapOf<DiaryRange,PreparedPeriod>()
    private var preparedStamp:Any?=null
    private var cachedSummaryGenres:Pair<Pair<SummaryFacts,Int>,List<GenreStat>>?=null
    private var cachedSummaryPreferences:Pair<Pair<SummaryFacts,Int>,List<PreferencePoint>>?=null
    internal fun getCachedSummaryGenres(facts:SummaryFacts,catalogSize:Int):List<GenreStat>? =
        cachedSummaryGenres?.takeIf{it.first.first===facts&&it.first.second==catalogSize}?.second
    internal fun putCachedSummaryGenres(facts:SummaryFacts,catalogSize:Int,value:List<GenreStat>){
        cachedSummaryGenres=(facts to catalogSize) to value
    }
    internal fun getCachedPreferencePoints(facts:SummaryFacts,catalogSize:Int):List<PreferencePoint>? =
        cachedSummaryPreferences?.takeIf{it.first.first===facts&&it.first.second==catalogSize}?.second
    internal fun putCachedPreferencePoints(facts:SummaryFacts,catalogSize:Int,value:List<PreferencePoint>){
        cachedSummaryPreferences=(facts to catalogSize) to value
    }
    internal fun activePreparedPeriods():androidx.compose.runtime.snapshots.SnapshotStateMap<DiaryRange,PreparedPeriod>{
        val stamp=listOf(day,dayPolicy,demo,prefs.getLong("cache_epoch",0L))
        if(preparedStamp!=stamp){preparedPeriods.clear();cachedDiscoveries.clear();cachedListeningSummaries.clear();preparedStamp=stamp}
        return preparedPeriods
    }
    internal fun isPreparedUsable(entry:PreparedPeriod):Boolean = demo || entry.revision <= prefs.getLong("data_revision",0L)
    private fun schedulePeriodCacheWrite(day:LocalDate,policy:ListeningDayPolicy,report:DiaryReport,revision:Long,range:DiaryRange){
        if(demo||report.segments.size>200000)return
        periodCacheWriteJobs.remove(range)?.cancel()
        periodCacheWriteJobs[range]=scope.launch{
            awaitNavigationSettled()
            raceDelay(600L)
            awaitNavigationSettled()
            withContext(Dispatchers.IO){DailyReportCache.write(activity,day,policy,report,revision,range)}
        }
    }
    fun select(day:LocalDate=this.day,range:DiaryRange=this.range,keepPeriodMotion:Boolean=false){
        lastNavigationAt=android.os.SystemClock.elapsedRealtime()
        if(!summary&&day==this.day&&range==this.range&&(loading||job?.isActive==true))return
        if(summary||day!=this.day||range!=this.range)markNavigationTransition(if(keepPeriodMotion)120L else 350L)
        val ticket=++summaryExitTicket
        summaryExitJob?.cancel()
        val preparedMap=activePreparedPeriods()
        val cached=if(day==this.day)preparedMap[range]?.takeIf{isPreparedUsable(it)&&(range==DiaryRange.DAY||it.computed!=null)} else null
        if(cached!=null){
            commitSelection(day,range,keepPeriodMotion,cached.computed,cached.revision,cached.report)
            val recentHeavyRefresh=(range==DiaryRange.MONTH||range==DiaryRange.YEAR)&&!cached.provisional&&cached.metadataEpoch==previewMetadataEpoch&&lastHeavyPeriodRefreshAt>0&&android.os.SystemClock.elapsedRealtime()-lastHeavyPeriodRefreshAt<60000L
            if(!recentHeavyRefresh&&(!isPreviewRevisionCurrent(cached.revision)||cached.provisional||cached.metadataEpoch!=previewMetadataEpoch)){
                scope.launch{
                    raceDelay(500)
                    awaitNavigationSettled()
                    if(this@DiaryController.day==day&&this@DiaryController.range==range&&!summary)refresh(true)
                }
            }
            return
        }
        commitSelection(day,range,keepPeriodMotion)
    }
    private fun commitSelection(day:LocalDate,range:DiaryRange,keepPeriodMotion:Boolean,prepared:PeriodComputed?=null,revision:Long=dataRevision,preparedReport:DiaryReport?=prepared?.report){
        if(day!=this.day){followsListeningToday=day==latestListeningDay;preparedPeriods.clear();cachedDiscoveries.clear();cachedListeningSummaries.clear()}
        if(summary&&!keepPeriodMotion)chooseSummaryMotion()
        if(!keepPeriodMotion&&(range!=this.range||summary)){choosePeriodMotion();choosePeriodLayout(range)}
        periodTransitionReport=if(day==this.day&&range!=this.range)if(loading)periodTransitionReport?:report.takeIf{showingCached} else report else null
        summaryQuietRefreshJob?.cancel();summaryJob?.cancel();summaryMetadataJob?.cancel();this.day=day;this.range=range
        if(preparedReport!=null){
            job?.cancel();selectionSerial++;refreshPending=false
            dataRevision=revision;report=preparedReport;retainedPeriodComputed=prepared
            loading=false;showingCached=false;error=null;periodTransitionReport=null
            summary=false
            scope.launch{
                raceDelay(340)
                awaitNavigationSettled()
                if(this@DiaryController.day==day&&this@DiaryController.range==range&&!summary&&!demo){
                    schedulePeriodCacheWrite(day,dayPolicy,preparedReport,revision,range)
                    val focus=withContext(Dispatchers.Default){sortedPlays(preparedReport.history,SortOrder.COUNT).take(80)}
                    val wanted=preparedReport.topArtists.map{it.name}.toSet()
                    val bindings=withContext(Dispatchers.Default){preparedReport.history.asSequence().filter{it.artist in wanted}.groupBy{it.artist}.mapValues{(_,songs)->songs.map{it.id}.distinct().take(3)}}
                    withContext(Dispatchers.IO){images.trackHints(preparedReport.history);if(!summary)images.focusTracks(focus);images.artistTracks(bindings)}
                    if(!summary)withContext(Dispatchers.IO){images.prefetchArtists(preparedReport.topArtists.map{it.name});images.prefetch(preparedReport.history.map{it.id}.distinct().takeLast(80))}
                }
            }
        }else{summary=false;refresh(false)}
    }
    private var summaryDiskTask:Deferred<Pair<SummaryFacts,Long>?>?=null
    private var summaryDiskKey:Pair<ListeningDayPolicy,Long>?=null
    private var summaryCacheWriteJob:Job?=null
    private var summaryCacheWriteAt=0L
    private var summaryCacheWriteEpoch=-1L
    private var summaryCacheWritePolicy:ListeningDayPolicy?=null
    private suspend fun restoreSummarySnapshot(){
        if(demo||summaryFacts.report.history.isNotEmpty())return
        val key=dayPolicy to prefs.getLong("cache_epoch",0)
        val task=summaryDiskTask?.takeIf{summaryDiskKey==key&&!it.isCancelled}?:scope.async(Dispatchers.IO){SummarySnapshotCache.readVersioned(activity,key.first)}.also{summaryDiskKey=key;summaryDiskTask=it}
        val cached=task.await()?:return
        if(!demo&&key==(dayPolicy to prefs.getLong("cache_epoch",0))&&summaryFacts.report.history.isEmpty()){
            summaryFacts=cached.first;summaryLoading=false
            if(cached.second==dataRevision)summaryStamp=summaryKey()
        }
        if(summaryDiskTask===task){summaryDiskTask=null;summaryDiskKey=null}
    }
    private fun saveSummarySnapshot(facts:SummaryFacts,revision:Long){
        if(demo)return
        val epoch=prefs.getLong("cache_epoch",0)
        if(summaryCacheWriteJob?.isActive==true&&summaryCacheWriteEpoch==epoch&&summaryCacheWritePolicy==dayPolicy)return
        val now=android.os.SystemClock.elapsedRealtime()
        if(summaryCacheWriteEpoch==epoch&&summaryCacheWritePolicy==dayPolicy&&summaryCacheWriteAt>0&&now-summaryCacheWriteAt<120000)return
        summaryCacheWriteAt=now;summaryCacheWriteEpoch=epoch
        val policy=dayPolicy;summaryCacheWritePolicy=policy
        summaryCacheWriteJob=scope.launch{
            // Wait for the interaction to settle before allocating a large serialized snapshot.
            // Serialization of a large history competes with gestures even on IO threads.
            // Start only after navigation has been quiet for a sustained interval.
            while(true){
                awaitSummaryRevealStable()
                val wait=2500L-(android.os.SystemClock.elapsedRealtime()-lastNavigationAt)
                if(wait<=0&&periodSwipePosition==null&&!navigationSettling)break
                delay(wait.coerceAtLeast(250L))
            }
            val written=withContext(Dispatchers.IO){SummarySnapshotCache.write(activity,policy,revision,facts,epoch)}
            if(!written&&summaryCacheWriteEpoch==epoch&&summaryCacheWritePolicy==policy)summaryCacheWriteAt=0
        }
    }
    // A completed read may lag normal appended records. Only a changed policy/demo mode or
    // cache epoch (checked by the caller for imports/deletions) makes it unsafe to reveal.
    private fun isSummaryReadUsable(key:SummaryStamp,revision:Long=key.revision):Boolean{
        val current=summaryKey()
        return current.policy==key.policy&&current.demo==key.demo&&(demo||revision<=prefs.getLong("data_revision",0))
    }
    internal fun preloadSummary(){
        val key=summaryKey()
        val epoch=prefs.getLong("cache_epoch",0)
        if(summary||summaryPreloadJob?.isActive==true||summaryJob?.isActive==true||(summaryStamp==key&&isPreviewRevisionCurrent(key.revision)&&summaryFacts.report.history.isNotEmpty()))return
        val now=android.os.SystemClock.elapsedRealtime()
        if(!demo&&summaryFacts.report.history.isNotEmpty()&&lastSummaryPreloadAt>0&&now-lastSummaryPreloadAt<60000)return
        summaryPreloadJob=scope.launch{
            try{
                restoreSummarySnapshot()
                if(summaryFacts.report.history.isNotEmpty()){
                    val restoredFacts=summaryFacts
                    if(getCachedPreferencePoints(restoredFacts,catalogFacts.size)==null){
                        val cachedCatalog=withContext(Dispatchers.IO){CatalogMetadata.cached(java.io.File(activity.filesDir,"art"),restoredFacts.report.history.map{it.id}.distinct()).objects().associate{row->val value=CatalogFact.parse(row);value.id to value}}
                        withContext(Dispatchers.Default){
                            images.warmCached(restoredFacts.monthlyFavorites.take(18).map{it.second.id}.distinct(),320)
                            images.warmCached((restoredFacts.artists.take(6).map{"artist:${it.name}"}+restoredFacts.albums.take(2).map{it.cover.id}+restoredFacts.returningSongs.take(3).map{it.id}).distinct(),160)
                        }
                        awaitSummaryRevealStable()
                        ensureActive();if(!isSummaryReadUsable(key)||epoch!=prefs.getLong("cache_epoch",0))return@launch
                        if(catalogFacts.isEmpty()||cachedCatalog.size>=catalogFacts.size)catalogFacts=cachedCatalog
                        val currentCatalog=catalogFacts
                        val (preGenres,prePrefs)=withContext(Dispatchers.Default){mainstreamGenreStats(restoredFacts,currentCatalog) to preferencePoints(restoredFacts,currentCatalog)}
                        putCachedSummaryGenres(restoredFacts,currentCatalog.size,preGenres)
                        putCachedPreferencePoints(restoredFacts,currentCatalog.size,prePrefs)
                    }
                    if(summaryStamp==key&&isPreviewRevisionCurrent(key.revision)){
                        lastSummaryPreloadAt=android.os.SystemClock.elapsedRealtime()
                        return@launch
                    }
                }
                if(!key.demo&&!summary&&foreground){
                    val gate=kotlinx.coroutines.CompletableDeferred<Unit>()
                    val watcher=scope.launch{snapshotFlow{summary||!foreground||listOf(DiaryRange.WEEK,DiaryRange.MONTH,DiaryRange.YEAR).all{preparedPeriods.containsKey(it)}}.first{it};gate.complete(Unit)}
                    val timer=scope.launch{raceDelay(1500L);gate.complete(Unit)}
                    try{gate.await()}finally{watcher.cancel();timer.cancel()}
                    if(!summary)awaitNavigationSettled()
                }
                val loaded=if(key.demo)withContext(Dispatchers.Default){summaryDemoReport() to key.revision}else withContext(Dispatchers.IO){DiaryDatabase.projection(activity).readAllReportVersioned(key.policy)}
                ensureActive();if(!isSummaryReadUsable(key,loaded.second)||epoch!=prefs.getLong("cache_epoch",0))return@launch
                val facts=withContext(Dispatchers.Default){SummaryFacts(loaded.first)}
                if(summaryFacts.report.history.isNotEmpty())awaitSummaryRevealStable() else snapshotFlow{periodSwipePosition==null}.first{it}
                ensureActive();if(!isSummaryReadUsable(key,loaded.second)||epoch!=prefs.getLong("cache_epoch",0))return@launch
                summaryFacts=facts;summaryStamp=key.copy(revision=loaded.second);summaryReadAt=android.os.SystemClock.elapsedRealtime();lastSummaryPreloadAt=summaryReadAt
                summaryDiskTask=null;summaryDiskKey=null
                if(summary)summaryLoading=false
                saveSummarySnapshot(facts,loaded.second)
                if(!key.demo)withContext(Dispatchers.IO){DiaryDatabase.projection(activity).releaseProjectionMemory()}
                val cachedCatalog=withContext(Dispatchers.IO){CatalogMetadata.cached(java.io.File(activity.filesDir,"art"),facts.report.history.map{it.id}.distinct()).objects().associate{row->val value=CatalogFact.parse(row);value.id to value}}
                withContext(Dispatchers.Default){
                    images.warmCached(facts.monthlyFavorites.take(18).map{it.second.id}.distinct(),320)
                    images.warmCached((facts.artists.take(6).map{"artist:${it.name}"}+facts.albums.take(2).map{it.cover.id}+facts.returningSongs.take(3).map{it.id}).distinct(),160)
                    val warm720=facts.albums.take(2).map{it.cover.id}+facts.artists.take(1).map{"artist:${it.name}"}
                    images.warmCached(warm720,720)
                }
                awaitSummaryRevealStable()
                ensureActive();if(!isSummaryReadUsable(key,loaded.second)||epoch!=prefs.getLong("cache_epoch",0))return@launch
                if(catalogFacts.isEmpty()||cachedCatalog.size>=catalogFacts.size)catalogFacts=cachedCatalog
                val currentCatalog=catalogFacts
                val (preGenres,prePrefs)=withContext(Dispatchers.Default){mainstreamGenreStats(facts,currentCatalog) to preferencePoints(facts,currentCatalog)}
                putCachedSummaryGenres(facts,currentCatalog.size,preGenres)
                putCachedPreferencePoints(facts,currentCatalog.size,prePrefs)
            }catch(e:CancellationException){throw e}catch(e:Exception){android.util.Log.w("DiarySummary","Preload failed",e)}
        }
    }
    private var summaryQuietRefreshJob:Job?=null
    private fun scheduleSummaryRefresh(){
        if(summaryQuietRefreshJob?.isActive==true)return
        summaryQuietRefreshJob=scope.launch{
            while(summary){
                val wait=12000L-(android.os.SystemClock.elapsedRealtime()-lastNavigationAt)
                if(periodSwipePosition==null&&wait<=0){refreshSummary(false);return@launch}
                delay(wait.coerceAtLeast(250L))
            }
        }
    }
    fun openSummary(keepSummaryMotion:Boolean=false){
        lastNavigationAt=android.os.SystemClock.elapsedRealtime()
        summaryExitTicket++;summaryExitJob?.cancel()
        if(!summary){
            markNavigationTransition(if(keepSummaryMotion)120L else 350L)
            if(!keepSummaryMotion)chooseSummaryMotion()
        }
        summary=true
        android.util.Log.i("DiarySummary","Open: rows=${summaryFacts.report.segments.size} preloading=${summaryPreloadJob?.isActive==true}")
        val warming=summaryPreloadJob
        if(warming?.isActive==true){summaryLoading=summaryFacts.report.history.isEmpty();scope.launch{warming.join();if(summary){if(summaryFacts.report.history.isEmpty())refreshSummary() else scheduleSummaryRefresh()}}}
        else if(summaryFacts.report.history.isEmpty())refreshSummary()
        else scheduleSummaryRefresh()
    }
    fun refreshSummary(force:Boolean=true){
        if(summaryJob?.isActive==true||(!force&&android.os.SystemClock.elapsedRealtime()-summaryReadAt<15000))return
        summaryMetadataJob?.cancel()
        summaryJob=scope.launch{
            summaryLoading=summaryFacts.report.history.isEmpty();summaryError=null
            try{
                restoreSummarySnapshot()
                val key=summaryKey()
                val epoch=prefs.getLong("cache_epoch",0)
                var revision=key.revision
                val alreadyWarm=summaryStamp==key&&isPreviewRevisionCurrent(key.revision)&&summaryFacts.report.history.isNotEmpty()
                val facts=if(alreadyWarm)summaryFacts else {
                    val loaded=if(key.demo)withContext(Dispatchers.Default){summaryDemoReport() to key.revision}else withContext(Dispatchers.IO){DiaryDatabase.projection(activity).readAllReportVersioned(key.policy)}
                    if(!isSummaryReadUsable(key,loaded.second)||epoch!=prefs.getLong("cache_epoch",0))return@launch
                    revision=loaded.second
                    val next=withContext(Dispatchers.Default){SummaryFacts(loaded.first)}
                    next
                }
                if(!isSummaryReadUsable(key,revision)||epoch!=prefs.getLong("cache_epoch",0))return@launch
                if(summaryFacts!==facts){
                    if(summaryFacts.report.history.isNotEmpty())awaitSummaryRevealStable() else snapshotFlow{periodSwipePosition==null}.first{it}
                    ensureActive()
                    if(!isSummaryReadUsable(key,revision)||epoch!=prefs.getLong("cache_epoch",0))return@launch
                    summaryFacts=facts
                    summaryDiskTask=null;summaryDiskKey=null
                    saveSummarySnapshot(facts,revision)
                    if(!key.demo)withContext(Dispatchers.IO){DiaryDatabase.projection(activity).releaseProjectionMemory()}
                }
                summaryStamp=key.copy(revision=revision)
                summaryLoading=false;summaryReadAt=android.os.SystemClock.elapsedRealtime()
                // Wait until the 320ms entrance transition settles before running background metadata / disk / network I/O
                awaitSummaryRevealStable()
                ensureActive()
                withContext(Dispatchers.Default){
                    images.warmCached(facts.monthlyFavorites.take(18).map{it.second.id}.distinct(),320)
                    images.warmCached((facts.artists.take(6).map{"artist:${it.name}"}+facts.albums.take(2).map{it.cover.id}+facts.returningSongs.take(3).map{it.id}).distinct(),160)
                }
                val bindings=withContext(Dispatchers.Default){val wanted=facts.artists.take(20).map{it.name}.toSet();facts.report.history.asSequence().filter{it.artist in wanted}.groupBy{it.artist}.mapValues{(_,songs)->songs.map{it.id}.distinct().take(3)}}
                withContext(Dispatchers.IO){images.trackHints(facts.report.history);images.focusTracks(facts.songs.take(40));images.artistTracks(bindings)}
                withContext(Dispatchers.IO){images.prefetchArtists(facts.artists.take(12).map{it.name})}
                val ids=facts.albums.take(12).flatMap{it.songs}.map{it.id}.distinct()
                val cached=withContext(Dispatchers.IO){CatalogMetadata.cached(java.io.File(activity.filesDir,"art"),facts.report.history.map{it.id}.distinct()).objects().associate{row->val value=CatalogFact.parse(row);value.id to value}}
                if(cached!=catalogFacts)catalogFacts=cached
                val currentCatalog=catalogFacts
                val (preGenres,prePrefs)=withContext(Dispatchers.Default){mainstreamGenreStats(facts,currentCatalog) to preferencePoints(facts,currentCatalog)}
                putCachedSummaryGenres(facts,currentCatalog.size,preGenres)
                putCachedPreferencePoints(facts,currentCatalog.size,prePrefs)
                summaryLoading=false
                summaryMetadataJob=scope.launch{for(batch in ids.take(80).chunked(20)){
                    ensureActive();if(!summary||!foreground||!imageNetwork)break
                    val found=withContext(Dispatchers.IO){images.summaryMetadata(batch){summary&&foreground&&imageNetwork}.objects().associate{row->val value=CatalogFact.parse(row);value.id to value}}
                    if(found.isNotEmpty())catalogFacts=catalogFacts+found
                }}
            }catch(e:CancellationException){throw e}catch(e:Exception){summaryError="读取总结失败，已有记录已保留"}finally{summaryLoading=false}
        }
    }
    fun network(on:Boolean){imageNetwork=on;prefs.edit().putBoolean("image_network",on).apply();images.network(on)}
    fun openHost(){try{val intent=activity.packageManager.getLaunchIntentForPackage(BridgeIdentity.HOST)?:throw IllegalStateException();activity.startActivity(intent)}catch(e:Exception){notice="无法打开 AM++，请确认已安装"}}
    fun connect(){collectorLink.request{withContext(Dispatchers.IO){BridgeIdentity.approve(activity)};approved=true}}
    fun preview(on:Boolean){if(demo!=on){preparedPeriods.clear();cachedDiscoveries.clear();cachedListeningSummaries.clear();cachedSummaryGenres=null;cachedSummaryPreferences=null;summaryPreloadJob?.cancel();summaryStamp=null;lastSummaryPreloadAt=0L;summaryFacts=SummaryFacts(DiaryReport());report=DiaryReport()};demo=on;if(on)collectorLink.stop()else if(foreground)collectorLink.start(approved);refresh(false)}
    private var refreshPending=false
    private var selectionSerial=0
    private var dayPreviewJob:Job?=null
    private var completedProjectionSerial=-1
    private fun refreshDayPreviewWhileLoading(){
        if(range!=DiaryRange.DAY || dayPolicy.mode==ListeningDayMode.AUTO || dayPreviewJob?.isActive==true || completedProjectionSerial==selectionSerial)return
        val ticket=selectionSerial;val selectedDay=day;val policy=dayPolicy
        dayPreviewJob=scope.launch {
            raceDelay(180)
            try {
                val quick=withContext(Dispatchers.IO){DiaryDatabase.get(activity).readDayPreview(selectedDay,policy)}
                ensureActive()
                if(quick!=null && ticket==selectionSerial && completedProjectionSerial!=ticket && !summary && range==DiaryRange.DAY && day==selectedDay && quick.second>=dataRevision){
                    dataRevision=quick.second;report=quick.first;showingCached=true;loading=false
                    android.util.Log.i("DiaryTiming","refresh quick-sync-ready range=DAY")
                }
            } catch(e:CancellationException){throw e} catch(e:Exception){android.util.Log.w("DiaryTiming","Day preview refresh failed: ${e.javaClass.simpleName}")}
        }
    }
    fun refresh(debounce:Boolean){
        if(summary){
            dataRevision=prefs.getLong("data_revision",dataRevision)
            if(summaryFacts.report.history.isEmpty())refreshSummary(!debounce) else scheduleSummaryRefresh()
            return
        }
        // A collector acknowledgement can change status/time without changing any listening rows.
        // Keep in-flight discovery work and prepared period data intact in that case.
        if(debounce&&!demo&&!showingCached&&!loading&&error==null){
            val prepared=activePreparedPeriods()[range]
            val selected=bounds(day,range)
            val metaVersion=TrackMetadata.shared(java.io.File(activity.filesDir,"art")).version()
            val recentHeavyRefresh=(range==DiaryRange.MONTH||range==DiaryRange.YEAR)&&lastHeavyPeriodRefreshAt>0&&android.os.SystemClock.elapsedRealtime()-lastHeavyPeriodRefreshAt<60000L&&report.history.isNotEmpty()&&report.from==selected.from&&report.to==selected.to
            if(recentHeavyRefresh||(prepared!=null&&!prepared.provisional&&prepared.revision==prefs.getLong("data_revision",0L)&&prepared.metadataEpoch==previewMetadataEpoch&&prepared.trackMetaVersion==metaVersion&&report.from==selected.from&&report.to==selected.to)){
                val nextLastSync=prefs.getLong("last_sync",0)
                val nextNowTitle=prefs.getString("now_title","").orEmpty()
                val nextNowTrackId=prefs.getString("now_track_id","").orEmpty()
                val nextRecordState=prefs.getString("record_state","等待采集插件").orEmpty()
                if(nextNowTitle!=report.nowTitle||nextNowTrackId!=report.nowTrackId||nextRecordState!=report.recordState||nextRecordState=="正在记录"||(report.lastSync==0L&&nextLastSync!=0L)){
                    report=report.copy(lastSync=nextLastSync,nowTitle=nextNowTitle,nowTrackId=nextNowTrackId,recordState=nextRecordState)
                }
                android.util.Log.i("DiaryTiming","refresh status-only range=$range")
                return
            }
        }
        if(debounce&&job?.isActive==true){refreshPending=true;refreshDayPreviewWhileLoading();return}
        if(!debounce){job?.cancel();dayPreviewJob?.cancel();selectionSerial++;showingCached=false;val selected=bounds(day,range);if(report.from!=selected.from||report.to!=selected.to)report=DiaryReport(from=selected.from,to=selected.to);loading=report.history.isEmpty()}
        if(demo){
            report=periodDemoReport(day,range);loading=false;periodTransitionReport=null;error=null;refreshPending=false
            images.artistTracks(report.history.groupBy{it.artist}.mapValues{(_,songs)->songs.map{it.id}.distinct().take(3)});images.prefetchArtists(report.topArtists.map{it.name});return
        }
        val ticket=selectionSerial;val requestedDay=day;val requestedRange=range;val policy=dayPolicy
        completedProjectionSerial=-1
        job=scope.launch{
            val timingStart=android.os.SystemClock.elapsedRealtime()
            fun timing(stage:String){android.util.Log.i("DiaryTiming", "refresh $stage range=${requestedRange.name} elapsed=${android.os.SystemClock.elapsedRealtime()-timingStart}ms")}
            if(debounce){raceDelay(180);if(requestedRange==DiaryRange.MONTH||requestedRange==DiaryRange.YEAR)awaitNavigationSettled()}
            try{
                if(!debounce&&!demo&&report.history.isEmpty()){
                    val cached=withContext(Dispatchers.IO){DailyReportCache.read(activity,requestedDay,policy,requestedRange)}
                    timing(if(cached==null)"cache-miss" else "cache-hit")
                    ensureActive();if(ticket!=selectionSerial)return@launch
                    if(cached!=null){
                        val computed=if(requestedRange==DiaryRange.DAY)null else withContext(Dispatchers.Default){computePeriod(cached,requestedDay,requestedRange)}
                        ensureActive();if(ticket!=selectionSerial)return@launch
                        if(report.history.isNotEmpty()&&(periodSwipePosition!=null||navigationSettling)){
                            awaitNavigationSettled()
                            ensureActive();if(ticket!=selectionSerial)return@launch
                        }
                        retainedPeriodComputed=computed;report=cached;showingCached=true;loading=cached.history.isEmpty()
                    }
                }
                if(!debounce&&requestedRange==DiaryRange.DAY&&(report.history.isEmpty()||showingCached)){
                    val quick=withContext(Dispatchers.IO){DiaryDatabase.get(activity).readDayPreview(requestedDay,policy)}
                    timing(if(quick==null)"quick-empty" else "quick-ready")
                    ensureActive();if(ticket!=selectionSerial)return@launch
                    if(quick!=null){dataRevision=quick.second;report=quick.first;showingCached=true;loading=false}
                }
                val preMetaVersion=withContext(Dispatchers.IO){TrackMetadata.shared(java.io.File(activity.filesDir,"art")).version()}
                val local=withContext(Dispatchers.IO){if(requestedRange==DiaryRange.DAY)DiaryDatabase.get(activity).readDayScreen(requestedDay,policy) else DiaryDatabase.get(activity).readPeriodScreen(bounds(requestedDay,requestedRange),policy)}
                // Only the lifetime projection waits for the initial collector handshake.
                if(local==null&&!debounce&&collectorLink.syncing){
                    kotlinx.coroutines.withTimeoutOrNull(2500){snapshotFlow{collectorLink.syncing}.first{!it}}
                    timing("collector-priority")
                    refreshDayPreviewWhileLoading()
                }
                val versioned=local?:withContext(Dispatchers.IO){DiaryDatabase.projection(activity).readReportVersioned(bounds(requestedDay,requestedRange),policy)}
                timing(if(local!=null)"local-ready" else "full-ready")
                val provisional=!isPreviewRevisionCurrent(versioned.second)
                val result=versioned.first
                val computed=if(requestedRange==DiaryRange.DAY)null else withContext(Dispatchers.Default){computePeriod(result,requestedDay,requestedRange)}
                ensureActive();if(ticket!=selectionSerial)return@launch
                if(requestedRange==DiaryRange.DAY&&versioned.second<dataRevision&&showingCached){
                    refreshPending=true
                    return@launch
                }
                if(!showingCached&&report.history.isNotEmpty()&&(periodSwipePosition!=null||navigationSettling)){
                    awaitNavigationSettled()
                    ensureActive();if(ticket!=selectionSerial)return@launch
                }
                retainedPeriodComputed=computed
                if(requestedRange==DiaryRange.MONTH||requestedRange==DiaryRange.YEAR)lastHeavyPeriodRefreshAt=android.os.SystemClock.elapsedRealtime()
                completedProjectionSerial=ticket;dataRevision=versioned.second;artEpoch=prefs.getInt("art_epoch",artEpoch);report=result;error=null;loading=false;showingCached=provisional;periodTransitionReport=null
                if(result.segments.size<=120000)activePreparedPeriods()[requestedRange]=PreparedPeriod(result,computed,versioned.second,previewMetadataEpoch,provisional,preMetaVersion)
                if(!demo&&ticket==selectionSerial)schedulePeriodCacheWrite(requestedDay,policy,result,versioned.second,requestedRange)
                val preparation=withContext(Dispatchers.Default){
                    val focus=sortedPlays(result.history,SortOrder.COUNT).take(80)
                    val wanted=result.topArtists.map{it.name}.toSet()
                    val bindings=result.history.asSequence().filter{it.artist in wanted}.groupBy{it.artist}.mapValues{(_,songs)->songs.map{it.id}.distinct().take(3)}
                    focus to bindings
                }
                ensureActive();if(ticket!=selectionSerial)return@launch
                withContext(Dispatchers.IO){images.trackHints(result.history);if(!summary)images.focusTracks(preparation.first);images.artistTracks(preparation.second)}
                ensureActive();if(ticket!=selectionSerial)return@launch
                if(!summary)withContext(Dispatchers.IO){images.prefetchArtists(result.topArtists.map{it.name});images.prefetch(result.history.map{it.id}.distinct().takeLast(80))}
            }catch(e:CancellationException){throw e}catch(e:Exception){timing("failed-${e.javaClass.simpleName}");periodTransitionReport=null;error="读取日记失败：${e.javaClass.simpleName}，请保留数据"}finally{
                if(ticket==selectionSerial){loading=false;if(refreshPending){refreshPending=false;job=null;refresh(true)}}
            }
        }
    }
    internal var applePlan by mutableStateOf<ApplePlan?>(null);private set
    internal var appleBatches by mutableStateOf<List<AppleBatch>>(emptyList());private set
    internal var manualImports by mutableStateOf<List<AppleBatch>>(emptyList());private set
    internal var webImport by mutableStateOf<AppleBatch?>(null);private set
    var appleBusy by mutableStateOf(false);private set
    var appleError by mutableStateOf<String?>(null);private set
    var appleCompleted by mutableIntStateOf(0);private set
    private var appleJob:Job?=null
    private var appleListJob:Job?=null
    fun loadAppleBatches(){appleListJob?.cancel();appleListJob=scope.launch{try{val lists=withContext(Dispatchers.IO){val db=DiaryDatabase.get(activity);Triple(db.appleBatches(),db.manualBatches(),db.webImportSummary())};appleBatches=lists.first;manualImports=lists.second;webImport=lists.third}catch(e:CancellationException){throw e}catch(e:Exception){appleError="读取导入列表失败，请保留数据后重试"}}}
    fun stageApple(uri:android.net.Uri){if(appleBusy)return;appleBusy=true;appleError=null;appleJob=scope.launch{
        try{val current=kotlinx.coroutines.currentCoroutineContext();applePlan=withContext(Dispatchers.IO){
            val label=runCatching{activity.contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())it.getString(0)else null}}.getOrNull()?:"Apple Music 导入"
            val temp=java.io.File.createTempFile("apple-import-",".data",activity.cacheDir)
            try{activity.contentResolver.openInputStream(uri)!!.use{input->temp.outputStream().use{out->val buffer=ByteArray(65536);var size=0L;while(true){current.ensureActive();val n=input.read(buffer);if(n<0)break;size+=n;require(size<=512L*1024*1024){"文件超过512MB，请分批导入"};out.write(buffer,0,n)}}};AppleActivityImport.read(temp,label,check={current.ensureActive()})}finally{temp.delete()}
        }}catch(e:CancellationException){throw e}catch(e:Exception){appleError="读取失败：${e.message?:e.javaClass.simpleName}"}finally{appleBusy=false}
    }}
    fun cancelApple(){if(!appleBusy){applePlan=null;appleError=null}}
    fun commitApple(){val plan=applePlan?:return;if(appleBusy)return;appleBusy=true;appleError=null;appleJob=scope.launch{try{
        val current=kotlinx.coroutines.currentCoroutineContext();val added=withContext(Dispatchers.IO){DiaryDatabase.get(activity).importApple(plan){current.ensureActive()}}
        applePlan=null;appleBatches=withContext(Dispatchers.IO){DiaryDatabase.get(activity).appleBatches()};demo=false;if(foreground)collectorLink.start(approved);select(java.time.LocalDate.parse(plan.to),DiaryRange.DAY);appleCompleted++;notice=if(added)"Apple 历史已导入，可在设置中单独移除"else "这批记录已存在，没有重复导入"
    }catch(e:CancellationException){throw e}catch(e:Exception){appleError="导入未完成：${e.message?:e.javaClass.simpleName}"}finally{appleBusy=false}}}
    internal fun viewApple(batch:AppleBatch){val date=runCatching{java.time.LocalDate.parse(batch.to)}.getOrNull()?:return;demo=false;if(foreground)collectorLink.start(approved);select(date,DiaryRange.DAY);appleCompleted++}
    fun removeApple(id:String){if(appleBusy)return;appleBusy=true;appleJob=scope.launch{try{
        withContext(Dispatchers.IO){DiaryDatabase.get(activity).removeApple(id)};appleBatches=withContext(Dispatchers.IO){DiaryDatabase.get(activity).appleBatches()};refresh(false);notice="已移除此批导入，采集记录保持不变"
    }catch(e:CancellationException){throw e}catch(e:Exception){appleError="移除未完成：${e.javaClass.simpleName}"}finally{appleBusy=false}}}
    fun importJson(uri:android.net.Uri){scope.launch{loading=true;try{val count=withContext(Dispatchers.IO){val bytes=activity.contentResolver.openInputStream(uri)!!.use{input->val out=java.io.ByteArrayOutputStream();val buf=ByteArray(8192);var n=input.read(buf);while(n!=-1){require(out.size()+n<=128*1024*1024){"备份超过 128 MB"};out.write(buf,0,n);n=input.read(buf)};out.toByteArray()};val data=JSONObject(bytes.toString(Charsets.UTF_8));val db=DiaryDatabase.get(activity);val result=db.ingest(data,true);val label=runCatching{activity.contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use{if(it.moveToFirst())it.getString(0)else null}}.getOrNull()?:"导入文件";result to runCatching{db.rememberManualImport(bytes,data,label)}.isSuccess};notice="已导入 ${count.first} 段记录；重复记录不会累加"+if(count.second)"" else "；导入列表未保存，请保留原文件";demo=false;loadAppleBatches();refresh(false)}catch(e:Exception){notice="导入失败：${e.message?:e.javaClass.simpleName}；已有记录未清空";loading=false}}}
    fun exportJson(uri:android.net.Uri){scope.launch{try{withContext(Dispatchers.IO){activity.contentResolver.openOutputStream(uri)!!.bufferedWriter().use{DiaryDatabase.get(activity).writeBackup(it)}};notice="完整备份已保存"}catch(e:Exception){notice="导出失败，请检查存储空间"}}}
    fun save(out:Bundle){out.putBoolean("follow_today",followsListeningToday);out.putBoolean("summary",summary);out.putString("day",day.toString());out.putInt("range",range.ordinal)}
}

fun demoReport(day:LocalDate):DiaryReport {val base=day.atTime(8,12).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();val titles=listOf("Mermaid","踊り子","One Last Kiss","星の消えた夜に","花になって");val names=listOf("由薫","Vaundy","宇多田ヒカル","Aimer","緑黄色社会");val ids=listOf("1781888430","1706828627","1736077483","1536126606","1711411579");val plays=(0 until 24).map{i->Play(ids[i%5],"demo-$i",titles[i%5],names[i%5],"示例专辑",base+i*300000+(if(i>=12)10800000 else 0),base+i*300000+(if(i>=12)10800000 else 0)+220000,220000,240000,ZoneId.systemDefault().id,effective=1)};return DiaryReport(plays.sumOf{it.ms},24,5,5,plays,names.map{name->ArtistStat(name,plays.filter{it.artist==name}.sumOf{it.ms},1)},plays,setOf(day.toString()),nowTitle="交互演示 · 不写入真实历史",from=day.toString(),to=day.toString())}

internal fun periodDemoReport(day:LocalDate,range:DiaryRange):DiaryReport{
    if(range==DiaryRange.DAY)return demoReport(day)
    val bound=bounds(day,range);val end=minOf(LocalDate.parse(bound.to),LocalDate.now());var date=LocalDate.parse(bound.from);val songs=mutableListOf<Play>()
    while(!date.isAfter(end)){if(date.dayOfMonth%4!=0){songs+=demoReport(date).history.take(4+date.dayOfMonth%17).map{it.copy(visit="${date}:${it.visit}")}};date=date.plusDays(1)}
    val totals=songs.groupBy{java.time.Instant.ofEpochMilli(it.start).atZone(ZoneId.systemDefault()).toLocalDate().toString()}.mapValues{(_,v)->v.sumOf{it.ms}}
    return DiaryReport(ms=songs.sumOf{it.ms},plays=songs.size,tracks=songs.map{it.id}.distinct().size,artists=songs.map{it.artist}.distinct().size,history=songs,segments=songs,topArtists=songs.groupBy{it.artist}.map{(name,v)->ArtistStat(name,v.sumOf{it.ms},v.map{it.id}.distinct().size)}.sortedByDescending{it.ms},heat=totals.keys,from=bound.from,to=bound.to,dayTotals=totals,firstRecorded=bound.from,artistFirst=songs.groupBy{it.artist}.mapValues{(_,v)->java.time.Instant.ofEpochMilli(v.minOf{it.start}).atZone(ZoneId.systemDefault()).toLocalDate().toString()})
}

internal fun summaryDemoReport():DiaryReport {
    val report=periodDemoReport(LocalDate.now(),DiaryRange.YEAR);val days=reportDays(report).keys
    return report.copy(from=days.minOrNull()?.toString()?:LocalDate.now().toString(),to=days.maxOrNull()?.toString()?:LocalDate.now().toString())
}
