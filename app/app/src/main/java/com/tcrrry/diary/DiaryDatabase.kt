package com.tcrrry.diary

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters
import java.util.UUID

enum class DiaryRange(val label:String) { DAY("日"),WEEK("周"),MONTH("月"),YEAR("年") }
data class DayBounds(val from:String,val to:String)
fun bounds(day:LocalDate,range:DiaryRange):DayBounds=when(range){
    DiaryRange.DAY->DayBounds(day.toString(),day.toString())
    DiaryRange.WEEK->DayBounds(day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString(),day.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).toString())
    DiaryRange.MONTH->DayBounds(day.withDayOfMonth(1).toString(),day.with(TemporalAdjusters.lastDayOfMonth()).toString())
    DiaryRange.YEAR->DayBounds(day.withDayOfYear(1).toString(),day.with(TemporalAdjusters.lastDayOfYear()).toString())
}

/** Local mirror. Remote row keys are stable, so retries UPDATE snapshots instead of double counting. */
class DiaryDatabase private constructor(private val context:Context,private val projectionOnly:Boolean=false) {
    val images=java.io.File(context.filesDir,"art").apply{mkdirs()}
    private val preferences=context.getSharedPreferences("diary",Context.MODE_PRIVATE)
    init { DailyRows.threshold(preferences.getInt("effective_seconds",30)) }
    val generation:String=preferences.getString("generation",null)?:UUID.randomUUID().toString().also{preferences.edit().putString("generation",it).commit()}
    private val db=SQLiteDatabase.openOrCreateDatabase(java.io.File(context.filesDir,"diary.db"),null).apply {
        enableWriteAheadLogging()
        if(!projectionOnly){
        execSQL("CREATE TABLE IF NOT EXISTS tracks(id TEXT PRIMARY KEY,title TEXT NOT NULL,artist TEXT NOT NULL,album TEXT NOT NULL,duration INTEGER NOT NULL)")
        execSQL("CREATE TABLE IF NOT EXISTS spans(id INTEGER PRIMARY KEY,remote_id TEXT UNIQUE NOT NULL,visit TEXT NOT NULL,track TEXT NOT NULL,day TEXT NOT NULL,zone TEXT NOT NULL,start INTEGER NOT NULL,end INTEGER NOT NULL,ms INTEGER NOT NULL)")
        val columns=mutableSetOf<String>();rawQuery("PRAGMA table_info(spans)",null).use{while(it.moveToNext())columns.add(it.getString(1))}
        if("volume_pct" !in columns)execSQL("ALTER TABLE spans ADD COLUMN volume_pct INTEGER NOT NULL DEFAULT -1")
        if("device" !in columns)execSQL("ALTER TABLE spans ADD COLUMN device TEXT NOT NULL DEFAULT ''")
        if("route_source" !in columns)execSQL("ALTER TABLE spans ADD COLUMN route_source TEXT NOT NULL DEFAULT ''")
        execSQL("DROP VIEW IF EXISTS diary_rows")
        execSQL("CREATE INDEX IF NOT EXISTS spans_day ON spans(day)");execSQL("CREATE INDEX IF NOT EXISTS spans_visit ON spans(visit)")
        execSQL("CREATE INDEX IF NOT EXISTS spans_first_track ON spans(track,zone,start)")
        execSQL("CREATE TABLE IF NOT EXISTS events(remote_id TEXT PRIMARY KEY,event_id TEXT NOT NULL,source TEXT NOT NULL,day TEXT NOT NULL,zone TEXT NOT NULL,at INTEGER NOT NULL,kind TEXT NOT NULL,from_track TEXT NOT NULL,to_track TEXT NOT NULL,position INTEGER NOT NULL,duration INTEGER NOT NULL)")
        execSQL("CREATE INDEX IF NOT EXISTS events_day ON events(day)")
        execSQL("CREATE TABLE IF NOT EXISTS event_sources(source TEXT PRIMARY KEY,since INTEGER NOT NULL)")
        execSQL("CREATE TABLE IF NOT EXISTS import_batches(id TEXT PRIMARY KEY,label TEXT NOT NULL,rows INTEGER NOT NULL,ms INTEGER NOT NULL,from_day TEXT NOT NULL,to_day TEXT NOT NULL,imported_at INTEGER NOT NULL)")
        execSQL("CREATE TABLE IF NOT EXISTS manual_imports(id TEXT PRIMARY KEY,label TEXT NOT NULL,kind TEXT NOT NULL,rows INTEGER NOT NULL,ms INTEGER NOT NULL,from_day TEXT NOT NULL,to_day TEXT NOT NULL,imported_at INTEGER NOT NULL)")
        execSQL("CREATE TABLE IF NOT EXISTS import_tracks(batch TEXT NOT NULL,id TEXT NOT NULL,title TEXT NOT NULL,artist TEXT NOT NULL,album TEXT NOT NULL,duration INTEGER NOT NULL,PRIMARY KEY(batch,id))")
        execSQL("CREATE TABLE IF NOT EXISTS import_spans(batch TEXT NOT NULL,row_key TEXT NOT NULL,part INTEGER NOT NULL,visit TEXT NOT NULL,track TEXT NOT NULL,day TEXT NOT NULL,zone TEXT NOT NULL,start INTEGER NOT NULL,end INTEGER NOT NULL,ms INTEGER NOT NULL,reason TEXT NOT NULL,original_end INTEGER NOT NULL,start_position INTEGER NOT NULL,end_position INTEGER NOT NULL,inferred INTEGER NOT NULL,PRIMARY KEY(batch,row_key,part))")
        execSQL("CREATE INDEX IF NOT EXISTS import_spans_day ON import_spans(day)")
        execSQL("CREATE INDEX IF NOT EXISTS import_spans_first_track ON import_spans(batch,track,zone,start)")
        execSQL("CREATE INDEX IF NOT EXISTS import_spans_key ON import_spans(row_key,batch)")
        execSQL("CREATE VIEW IF NOT EXISTS diary_rows AS SELECT s.visit,s.track,s.day,s.zone,s.start,s.end,s.ms,t.title,t.artist,t.album,t.duration,0 imported,'' batch,'' row_key,'' reason,s.device,s.route_source,s.volume_pct FROM spans s JOIN tracks t ON t.id=s.track UNION ALL SELECT s.visit,s.track,s.day,s.zone,s.start,s.end,s.ms,t.title,t.artist,t.album,t.duration,1 imported,s.batch,s.row_key,s.reason,'' device,'' route_source,-1 volume_pct FROM import_spans s JOIN import_tracks t ON t.batch=s.batch AND t.id=s.track WHERE NOT EXISTS(SELECT 1 FROM import_spans earlier WHERE earlier.row_key=s.row_key AND earlier.batch<s.batch)")
        version=1
        }
    }
    private var revision=preferences.getLong("data_revision",0L)
    private var externalEpoch=preferences.getLong("cache_epoch",0L)
    private fun updateProjectionRevision(){
        if(!projectionOnly)return
        val next=preferences.getLong("data_revision",0L);val epoch=preferences.getLong("cache_epoch",0L)
        if(epoch!=externalEpoch){importedCache=null;importedProjection=null;externalEpoch=epoch}
        if(next!=revision)revision=next
        nativeCatalogVersion=preferences.getLong("catalog_revision",0L)
    }
    private fun bumpRevision(invalidateCache:Boolean=false){revision++;val edit=preferences.edit().putLong("data_revision",revision);if(invalidateCache)edit.putLong("cache_epoch",preferences.getLong("cache_epoch",0L)+1);edit.commit()}
    @Synchronized internal fun setEffectiveThreshold(seconds:Int) {
        check(!projectionOnly)
        val value=seconds.coerceIn(0,60)
        DailyRows.threshold(value)
        preferences.edit().putInt("effective_seconds",value).commit()
        bumpRevision(true)
    }
    private data class LedgerRow(val play:Play,val reason:String,val key:String):java.io.Serializable
    private var importedCache:List<LedgerRow>?=null
    private data class ImportedProjection(val rows:List<LedgerRow>,val policy:ListeningDayPolicy,val metadataVersion:Long,val catalogVersion:Long,val parts:List<DatedPlay>)
    private var importedProjection:ImportedProjection?=null
    private var nativeCatalogVersion=preferences.getLong("catalog_revision",0L)
    private val metadata=TrackMetadata.shared(images)
    private var projectionKey:Triple<Long,ListeningDayPolicy,Long>?=null
    private var projected:List<DatedPlay> = emptyList()
    private var projectedRows:List<LedgerRow> = emptyList()
    private var projectedDays:Map<LocalDate,List<DatedPlay>> = emptyMap()
    private var importedSkipDays:Map<String,Long> = emptyMap()
    private var firstArtists:Map<String,String> = emptyMap()
    @Synchronized internal fun periodNewIdentities(day:LocalDate,range:DiaryRange,policy:ListeningDayPolicy,songIds:Set<String>,artistNames:Set<String>):Pair<Set<String>,Set<String>> {
        updateProjectionRevision()
        val started=android.os.SystemClock.elapsedRealtime()
        val validation="${if(policy.mode==ListeningDayMode.AUTO)revision else preferences.getLong("first_span_revision",0)}:$policy:${metadata.version()}:${preferences.getLong("catalog_revision",0)}:${preferences.getLong("cache_epoch",0)}"
        val cacheKey="first-listening-index-v3:$policy"
        val memory=discoveryMemory
        val index=if(memory?.validation==validation)memory.index else {
            val stamp=discoveryIndexStamp(policy)
            android.util.Log.i("DiaryTiming","discovery validation first=${preferences.getLong("first_span_revision",0)} catalog=${preferences.getLong("catalog_revision",0)} epoch=${preferences.getLong("cache_epoch",0)} elapsed=${android.os.SystemClock.elapsedRealtime()-started}ms")
            val cached=memory?.takeIf{it.stamp==stamp}?.index
                ?:DerivedStatisticsCache.read(context,cacheKey,stamp) as? DiscoveryIndex
            val built=cached?:buildDiscoveryIndex(day,range,policy)
            if(cached==null&&revision==preferences.getLong("data_revision",0L))DerivedStatisticsCache.write(context,cacheKey,stamp,built)
            discoveryMemory=DiscoveryMemory(validation,stamp,built)
            android.util.Log.i("DiaryTiming","discovery index ${if(cached==null)"built" else "restored"} rows=${built.identities.size} elapsed=${android.os.SystemClock.elapsedRealtime()-started}ms")
            built
        }
        val bound=bounds(day,range);val start=LocalDate.parse(bound.from);val end=LocalDate.parse(bound.to)
        val first=HashMap(index.first)
        val identities=index.identities
        // Import and browser IDs can differ from the native catalog ID for the same recording.
        // Match source identities once, keeping different artists and editions separate.
        val byTitle=index.byTitle
        val recordedFirst=first.toMap()
        for(id in songIds){
            val play=identities[id]?:continue
            if(!AppleTrackImages.knownArtist(play.artist)||play.title=="未知歌曲")continue
            val hint=AppleTrackImages.hint(play)
            for(other in byTitle[AppleTrackImages.normalized(play.title)].orEmpty()){
                if(other.id==id||AppleTrackImages.normalized(play.artist)!=AppleTrackImages.normalized(other.artist))continue
                if(play.album.isBlank()||other.album.isBlank()||play.duration<=0||other.duration<=0)continue
                if(AppleTrackImages.edition(play.title)!=AppleTrackImages.edition(other.title))continue
                if(!AppleTrackImages.localMatch(hint,AppleTrackImages.hint(other)))continue
                val earlier=recordedFirst.getValue(other.id)
                if(earlier<first.getValue(id))first[id]=earlier
            }
        }
        val songs=songIds.filter{first[it]?.let{date->date>=start&&date<=end}==true}.toSet()
        // Only recognize separately recorded artist names. Do not infer that every
        // ampersand in a band's name denotes individual artists.
        val artistDates=index.artists.entries.groupBy{AppleTrackImages.normalized(it.key)}
            .mapValues{(_,entries)->entries.minOf{LocalDate.parse(it.value)}}
        val artists=artistNames.filter{name->
            val exact=artistDates[AppleTrackImages.normalized(name)]
            val date=discoveryArtistMembers(name).mapNotNull{artistDates[AppleTrackImages.normalized(it)]}
                .plus(listOfNotNull(exact)).minOrNull()
            date!=null&&date>=start&&date<=end
        }.toSet()
        android.util.Log.i("DiaryTiming","discovery ready range=$range elapsed=${android.os.SystemClock.elapsedRealtime()-started}ms")
        return songs to artists
    }

    private data class DiscoveryImports(val rows:ArrayList<LedgerRow>):java.io.Serializable
    private var discoveryImported:Pair<String,List<LedgerRow>>?=null

    private data class DiscoveryMemory(val validation:String,val stamp:String,val index:DiscoveryIndex)
    private var discoveryMemory:DiscoveryMemory?=null

    private data class DiscoveryIndex(
        val first:HashMap<String,LocalDate>,
        val identities:HashMap<String,Play>,
        val artists:HashMap<String,String>,
        val byTitle:Map<String,List<Play>>
    ):java.io.Serializable

    private fun buildDiscoveryIndex(day:LocalDate,range:DiaryRange,policy:ListeningDayPolicy):DiscoveryIndex {
        val first=HashMap<String,LocalDate>();val identities=HashMap<String,Play>();val artists=HashMap<String,String>()
        fun accept(play:Play,date:LocalDate){
            if(first[play.id]==null||date<first.getValue(play.id)){first[play.id]=date;identities[play.id]=play}
            if(AppleTrackImages.knownArtist(play.artist)&&(artists[play.artist]==null||date.toString()<artists.getValue(play.artist)))artists[play.artist]=date.toString()
        }
        if(policy.mode==ListeningDayMode.AUTO){
            if(projectionKey!=Triple(revision,policy,metadata.version()))readReport(bounds(day,range),policy)
            for(part in projected)accept(part.play,part.day)
        }else{
            // SQLite's single MIN aggregate selects its source row. A first day depends
            // only on that first timestamp, not on every later span of the recording.
            val started=android.os.SystemClock.elapsedRealtime()
            val importStamp="$generation:${preferences.getLong("cache_epoch",0)}"
            val importKey="discovery-import-first-rows-v1"
            val imports=discoveryImported?.takeIf{it.first==importStamp}?.second
                ?: (DerivedStatisticsCache.read(context,importKey,importStamp) as? DiscoveryImports)?.rows
                ?: ArrayList(ledger("SELECT s.visit,s.track,s.day,s.zone,MIN(s.start) start,s.end,s.ms,t.title,t.artist,t.album,t.duration,1 imported,s.batch,s.row_key,s.reason,'' device,'' route_source,-1 volume_pct FROM import_spans s JOIN import_tracks t ON t.batch=s.batch AND t.id=s.track WHERE NOT EXISTS(SELECT 1 FROM import_spans earlier WHERE earlier.row_key=s.row_key AND earlier.batch<s.batch) GROUP BY s.batch,s.track,s.zone ORDER BY start")).also{
                    DerivedStatisticsCache.write(context,importKey,importStamp,DiscoveryImports(it))
                }
            discoveryImported=importStamp to imports
            val native=ledger("SELECT s.visit,s.track,s.day,s.zone,MIN(s.start) start,s.end,s.ms,t.title,t.artist,t.album,t.duration,0 imported,'' batch,'' row_key,'' reason,s.device,s.route_source,s.volume_pct FROM spans s JOIN tracks t ON t.id=s.track GROUP BY s.track,s.zone ORDER BY start")
            android.util.Log.i("DiaryTiming","discovery source-first imported=${imports.size} native=${native.size} elapsed=${android.os.SystemClock.elapsedRealtime()-started}ms")
            val records=(imports+native).sortedBy{it.play.start}
            val enrich=ledgerEnricher(records)
            for(row in records){
                val play=enrich(row)
                val local=java.time.Instant.ofEpochMilli(play.start).atZone(java.time.ZoneId.of(play.zone))
                val date=if(policy.mode==ListeningDayMode.FIXED)local.minusHours(policy.hour.toLong()).toLocalDate()else local.toLocalDate()
                accept(play,date)
            }
        }
        val titles=identities.values.filter{AppleTrackImages.knownArtist(it.artist)&&it.title!="未知歌曲"}.groupBy{AppleTrackImages.normalized(it.title)}
        return DiscoveryIndex(first,identities,artists,titles)
    }

    private fun ledgerEnricher(records:List<LedgerRow>):(LedgerRow)->Play {
        val names=records.asSequence().map{it.play.artist}.filter{AppleTrackImages.knownArtist(it)}.distinct().associateBy{AppleTrackImages.normalized(it)}
        // One verified metadata lookup per distinct source identity, rather than once per play segment.
        data class Identity(val id:String,val title:String,val artist:String,val album:String,val duration:Long)
        val resolved=HashMap<Identity,Pair<String,String>>()
        val nativeByTitle=records.asSequence().map{it.play}.filter{!it.imported&&AppleTrackImages.knownArtist(it.artist)}.distinctBy{it.id}.groupBy{AppleTrackImages.normalized(it.title)}
        fun enrich(r:LedgerRow):Play {val p=r.play
            val values=resolved.getOrPut(Identity(p.id,p.title,p.artist,p.album,p.duration)){
                val m=metadata.lookup(p)
                if(m==null){
                    val candidates=if(p.imported)nativeByTitle[AppleTrackImages.normalized(p.title)].orEmpty().filter{AppleTrackImages.localMatch(AppleTrackImages.hint(p),AppleTrackImages.hint(it))}else emptyList()
                    val source=candidates.takeIf{it.map{v->AppleTrackImages.normalized(v.artist)}.distinct().size==1}?.firstOrNull()
                    (if(!AppleTrackImages.knownArtist(p.artist))source?.artist?:p.artist else p.artist) to p.album.ifBlank{source?.album.orEmpty()}
                }else {
                    val found=m.optString("artistName");val artist=if(!AppleTrackImages.knownArtist(p.artist)&&AppleTrackImages.knownArtist(found))names[AppleTrackImages.normalized(found)]?:AppleTrackImages.displayName(found) else p.artist
                    artist to p.album.ifBlank{AppleTrackImages.displayName(m.optString("collectionName"))}
                }
            }
            return if(values.first==p.artist&&values.second==p.album)p else p.copy(artist=values.first,album=values.second)
        }
        return ::enrich
    }

    private fun metadataCacheStamp():String {
        val folder=java.io.File(images,"track-matches-v1")
        val files=folder.listFiles{file->file.name.endsWith(".json")}.orEmpty().sortedBy{it.name}
        return files.joinToString("|"){it.name+":"+it.length()+":"+it.lastModified()}
    }
    private fun digestQuery(sql:String):String {
        val digest=java.security.MessageDigest.getInstance("SHA-256")
        db.rawQuery(sql,null).use{cursor->
            while(cursor.moveToNext())for(i in 0 until cursor.columnCount){
                val bytes=cursor.getString(i).orEmpty().toByteArray(Charsets.UTF_8)
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
                digest.update(bytes)
            }
        }
        return digest.digest().joinToString(""){"%02x".format(it)}
    }
    private fun discoveryIndexStamp(policy:ListeningDayPolicy):String {
        // These revisions are maintained by ledger writes. Checking a persisted index
        // must not scan the entire ledger merely to discover whether it changed.
        val automatic=if(policy.mode==ListeningDayMode.AUTO)revision else 0L
        return "$generation:$policy:$automatic:"+preferences.getLong("cache_epoch",0)+":"+
            preferences.getLong("catalog_revision",0)+":"+preferences.getLong("first_span_revision",0)+":"+ArtworkKeys.hash(metadataCacheStamp())
    }
    @Synchronized internal fun rankingCacheStamp(bound:DayBounds,policy:ListeningDayPolicy):String {
        updateProjectionRevision()
        val first=LocalDate.parse(bound.from).minusDays(3)
        val last=LocalDate.parse(bound.to).plusDays(3)
        val rows=digestQuery("SELECT track,visit,title,artist,album,duration,start,end,ms,zone,imported FROM diary_rows WHERE day>='$first' AND day<='$last' ORDER BY start,visit,track,imported,batch,row_key")
        return "$generation:$policy:"+preferences.getLong("cache_epoch",0)+":"+preferences.getInt("effective_seconds",30)+":"+rows+":"+ArtworkKeys.hash(metadataCacheStamp())+if(policy.mode==ListeningDayMode.AUTO)":$revision"else ""
    }

    internal fun weeklyNewIdentities(day:LocalDate,policy:ListeningDayPolicy,songIds:Set<String>,artistNames:Set<String>)=periodNewIdentities(day,DiaryRange.WEEK,policy,songIds,artistNames)

    @Synchronized internal fun readReportVersioned(bound:DayBounds,policy:ListeningDayPolicy):Pair<DiaryReport,Long> {updateProjectionRevision();val readRevision=revision;return readReport(bound,policy) to readRevision}
    /** Provisional day report avoids constructing the all-history projection at cold start. */
    @Synchronized internal fun readDayPreview(day:LocalDate,policy:ListeningDayPolicy)=readLocalDay(day,policy,false)

    /** The main day screen does not need lifetime calendar and artist-first data. */
    @Synchronized internal fun readDayScreen(day:LocalDate,policy:ListeningDayPolicy)=readLocalDay(day,policy,true)

    private fun readLocalDay(day:LocalDate,policy:ListeningDayPolicy,complete:Boolean):Pair<DiaryReport,Long>? {
        if(policy.mode==ListeningDayMode.AUTO)return null
        val started=android.os.SystemClock.elapsedRealtime()
        val start=day.minusDays(2).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        val end=day.plusDays(3).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        // Every row's day is recorded in its own time zone. A generous five-day window
        // covers zone offsets and the longest valid span while enabling day indexes.
        val firstDay=day.minusDays(3).toString();val lastDay=day.plusDays(3).toString()
        val records=ledger("SELECT * FROM diary_rows WHERE day >= '$firstDay' AND day <= '$lastDay' AND start < $end AND end > $start")
        val queried=android.os.SystemClock.elapsedRealtime()
        val parts=listeningDays(records.map{it.play},policy).filter{it.day==day}
        if(!complete&&parts.isEmpty())return null
        // Imported skip events and unknown artist aliases can depend on records outside this window.
        if(complete&&parts.any{it.play.imported||!AppleTrackImages.knownArtist(it.play.artist)})return null
        data class AlbumKey(val id:String,val title:String,val artist:String,val duration:Long)
        val albums=HashMap<AlbumKey,String>()
        val segments=parts.map{it.play}.map { play ->
            if(!complete||play.album.isNotBlank())play else {
                val album=albums.getOrPut(AlbumKey(play.id,play.title,play.artist,play.duration)){
                    AppleTrackImages.displayName(metadata.lookup(play)?.optString("collectionName").orEmpty())
                }
                if(album.isBlank())play else play.copy(album=album)
            }
        }.sortedBy{it.start}
        val history=segments.groupBy{it.visit to it.id}.values.map{items->val p=items.first();val ms=items.sumOf{it.ms};p.copy(start=items.minOf{it.start},end=items.maxOf{it.end},ms=ms,effective=DailyRows.effective(ms,p.duration))}.sortedBy{it.start}
        val known=history.filter{!it.imported||it.title!="未知歌曲"}
        val artists=history.filter{!it.imported||it.artist!="未知歌手"}.groupBy{it.artist}.map{(name,v)->ArtistStat(name,v.sumOf{it.ms},v.map{it.id}.distinct().size)}.sortedByDescending{it.ms}
        val report=DiaryReport(ms=segments.sumOf{it.ms},plays=history.count{it.effective>0},tracks=known.map{it.id}.distinct().size,artists=artists.size,history=history,segments=segments,topArtists=artists.take(40),from=day.toString(),to=day.toString(),dayTotals=mapOf(day.toString() to segments.sumOf{it.ms}),lastSync=preferences.getLong("last_sync",0),nowTitle=preferences.getString("now_title","").orEmpty(),nowTrackId=preferences.getString("now_track_id","").orEmpty(),recordState=preferences.getString("record_state","等待采集插件").orEmpty())
        if(!complete)return report to revision
        val since=rows("SELECT COALESCE(MIN(since),0) n FROM event_sources").getJSONObject(0).getLong("n")
        val covered=since>0&&day.plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()>since
        val cutoff=if(policy.mode==ListeningDayMode.FIXED)policy.hour.toLong()else 0L
        val skips=rows("SELECT at,zone FROM events WHERE day>=? AND day<=?",firstDay,lastDay).objects().count {
            java.time.Instant.ofEpochMilli(it.getLong("at")).atZone(java.time.ZoneId.of(it.getString("zone"))).minusHours(cutoff).toLocalDate()==day
        }.toLong()
        android.util.Log.i("DiaryTiming","day-local sql=${queried-started}ms total=${android.os.SystemClock.elapsedRealtime()-started}ms")
        return report.copy(manualSkips=skips.takeIf{covered},manualSkipsSince=if(covered)since else 0L,skipsByDay=if(skips>0)mapOf(day.toString() to skips)else emptyMap()) to revision
    }
    @Synchronized internal fun durationForBounds(bound:DayBounds,policy:ListeningDayPolicy):Long {
        updateProjectionRevision()
        if(projectionKey!=Triple(revision,policy,metadata.version()))readReport(bound,policy)
        val from=LocalDate.parse(bound.from);val to=LocalDate.parse(bound.to)
        return projectedDays.entries.asSequence().filter{it.key>=from&&it.key<=to}.sumOf{(_,parts)->parts.sumOf{it.play.ms}}
    }
    @Synchronized internal fun readAllReportVersioned(policy:ListeningDayPolicy):Pair<DiaryReport,Long> {updateProjectionRevision();val readRevision=revision;return readReport(null,policy) to readRevision}
    @Synchronized internal fun currentListeningDay(now:java.time.ZonedDateTime,policy:ListeningDayPolicy):LocalDate {
        if(policy.mode!=ListeningDayMode.AUTO || now.hour>=6)return listeningToday(now,policy)
        val date=now.toLocalDate()
        val timestamp=now.toInstant().toEpochMilli()
        // Automatic day selection only needs recent playback around the current night.
        // Avoid rebuilding the lifetime report while the collector is opening its connection.
        val first=date.minusDays(3).toString();val last=date.plusDays(1).toString()
        val recent=ledger("SELECT * FROM diary_rows WHERE day >= '$first' AND day <= '$last' AND start <= $timestamp")
        val previous=listeningDays(recent.map{it.play},policy).asSequence().filter{it.play.start<=timestamp}.maxByOrNull{it.play.end}
        return listeningToday(now,policy,previous)
    }
    private fun ledger(sql:String):List<LedgerRow> {
        val list=ArrayList<LedgerRow>();val textPool=HashMap<String,String>()
        fun text(value:String)=textPool.getOrPut(value){value}
        db.rawQuery(sql,null).use{c->
            val id=c.getColumnIndexOrThrow("track");val visit=c.getColumnIndexOrThrow("visit");val title=c.getColumnIndexOrThrow("title");val artist=c.getColumnIndexOrThrow("artist");val album=c.getColumnIndexOrThrow("album");val start=c.getColumnIndexOrThrow("start");val end=c.getColumnIndexOrThrow("end");val ms=c.getColumnIndexOrThrow("ms");val duration=c.getColumnIndexOrThrow("duration");val zone=c.getColumnIndexOrThrow("zone");val imported=c.getColumnIndexOrThrow("imported");val reason=c.getColumnIndexOrThrow("reason");val key=c.getColumnIndexOrThrow("row_key");val device=c.getColumnIndexOrThrow("device");val route=c.getColumnIndexOrThrow("route_source");val volume=c.getColumnIndexOrThrow("volume_pct")
            while(c.moveToNext())list+=LedgerRow(Play(text(c.getString(id)),text(c.getString(visit)),text(c.getString(title).ifBlank{"未知歌曲"}),text(c.getString(artist).ifBlank{"未知歌手"}),text(c.getString(album)),c.getLong(start),c.getLong(end),c.getLong(ms),c.getLong(duration),text(c.getString(zone)),imported=c.getInt(imported)==1,device=text(c.getString(device)),routeSource=text(c.getString(route)),volumePct=c.getInt(volume)),text(c.getString(reason)),c.getString(key))
        };return list
    }
    /** Typed cursor path avoids building two large JSON copies for the lifetime report. */
    @Synchronized internal fun readReport(bound:DayBounds?,policy:ListeningDayPolicy=ListeningDayPolicy()):DiaryReport {
        val key=Triple(revision,policy,metadata.version())
        if(projectionKey!=key){
            val started=android.os.SystemClock.elapsedRealtime()
            android.util.Log.i("DiaryTiming","projection start mode=${policy.mode} caller="+Throwable().stackTrace.drop(1).firstOrNull{it.className.contains("diary") }?.methodName)
            val imports=importedCache?:ledger("SELECT * FROM diary_rows WHERE imported=1").also{importedCache=it}
            val nativeRows=ledger("SELECT * FROM diary_rows WHERE imported=0")
            val queried=android.os.SystemClock.elapsedRealtime()
            projectedRows=imports+nativeRows
            val enrich=ledgerEnricher(projectedRows)
            var metadataMs=0L
            fun enrichRows(input:List<LedgerRow>):List<Play> {
                val start=android.os.SystemClock.elapsedRealtime()
                return input.map(enrich).also{metadataMs+=android.os.SystemClock.elapsedRealtime()-start}
            }
            if(policy.mode==ListeningDayMode.AUTO){
                // Auto day boundaries depend on previous plays from both sources.
                projected=listeningDays(enrichRows(projectedRows),policy)
            }else{
                val prior=importedProjection
                val cached=if(prior!=null&&prior.rows===imports&&prior.policy==policy&&prior.metadataVersion==key.third&&prior.catalogVersion==nativeCatalogVersion)prior.parts else {
                    val parts=listeningDays(enrichRows(imports),policy)
                    importedProjection=ImportedProjection(imports,policy,key.third,nativeCatalogVersion,parts)
                    parts
                }
                val nativeParts=listeningDays(enrichRows(nativeRows),policy)
                // Both inputs are chronological for normal records. Sorting the merged slices also
                // preserves a chronological report when spans from multiple sources overlap.
                projected=(cached+nativeParts).sortedBy{it.play.start}
            }
            projectedDays=projected.groupBy{it.day}
            val ends=HashMap<String,DatedPlay>();for(part in projected){val old=ends[part.play.visit];if(old==null||old.play.end<part.play.end)ends[part.play.visit]=part}
            importedSkipDays=projectedRows.asSequence().filter{it.play.imported&&AppleActivityImport.isSkip(it.reason)}.distinctBy{it.key}.mapNotNull{ends[it.play.visit]?.day?.toString()}.groupingBy{it}.eachCount().mapValues{it.value.toLong()}
            val firstByArtist=HashMap<String,LocalDate>()
            for(part in projected){val artist=part.play.artist;if(artist!="未知歌手"&&(firstByArtist[artist]==null||part.day<firstByArtist.getValue(artist)))firstByArtist[artist]=part.day}
            firstArtists=firstByArtist.mapValues{it.value.toString()}
            projectionKey=key
            val aggregated=android.os.SystemClock.elapsedRealtime()
            DailyDurationCache.write(context,policy,revision,projectedDays)
            android.util.Log.i("DiaryTiming","projection stages sql=${queried-started}ms metadata=${metadataMs}ms split-and-group=${aggregated-queried-metadataMs}ms cache=${android.os.SystemClock.elapsedRealtime()-aggregated}ms")
        }
        val first=projectedDays.keys.minOrNull();val last=projectedDays.keys.maxOrNull()
        val b=bound?:DayBounds((first?:LocalDate.now()).toString(),(last?:LocalDate.now()).toString())
        val from=LocalDate.parse(b.from);val to=LocalDate.parse(b.to)
        val parts=if(bound==null)projected else projectedDays.filterKeys{it>=from&&it<=to}.values.flatten().sortedBy{it.play.start}
        val segments=parts.map{it.play}
        val history=segments.groupBy{it.visit to it.id}.values.map{items->val p=items.first();val ms=items.sumOf{it.ms};p.copy(start=items.minOf{it.start},end=items.maxOf{it.end},ms=ms,effective=DailyRows.effective(ms,p.duration))}.sortedBy{it.start}
        val imported=segments.filter{it.imported};val known=history.filter{!it.imported||it.title!="未知歌曲"}
        val artists=history.filter{!it.imported||it.artist!="未知歌手"}.groupBy{it.artist}.map{(name,v)->ArtistStat(name,v.sumOf{it.ms},v.map{it.id}.distinct().size)}.sortedByDescending{it.ms}
        val skips=importedSkipDays.toMutableMap()
        val events=rows("SELECT * FROM events").objects()
        for(e in events){
            val at=e.getLong("at");val zone=java.time.ZoneId.of(e.getString("zone"));val local=java.time.Instant.ofEpochMilli(at).atZone(zone);val date=local.toLocalDate()
            val owner=if(policy.mode==ListeningDayMode.CALENDAR)date else if(policy.mode==ListeningDayMode.FIXED)local.minusHours(policy.hour.toLong()).toLocalDate() else {
                val previous=projected.lastOrNull{it.play.start<=at}
                if(local.hour<6&&previous!=null&&previous.day==date.minusDays(1)&&at-previous.play.end<4*3600000L)previous.day else date
            }
            skips[owner.toString()]=(skips[owner.toString()]?:0)+1
        }
        val totalSkips=skips.filterKeys{it>=b.from&&it<=b.to}.values.sum()
        val since=rows("SELECT COALESCE(MIN(since),0) n FROM event_sources").getJSONObject(0).getLong("n")
        val nativeCovered=since>0&&to.plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()>since
        val importedSince=imported.minOfOrNull{it.start}?:0
        return DiaryReport(ms=segments.sumOf{it.ms},plays=history.count{it.effective>0},tracks=known.map{it.id}.distinct().size,artists=artists.size,
            history=history,topArtists=artists.take(40),segments=segments,heat=projectedDays.keys.map{it.toString()}.toSet(),
            lastSync=preferences.getLong("last_sync",0),nowTitle=preferences.getString("now_title","").orEmpty(),nowTrackId=preferences.getString("now_track_id","").orEmpty(),recordState=preferences.getString("record_state","等待采集插件").orEmpty(),
            from=b.from,to=b.to,dayTotals=parts.groupBy{it.day.toString()}.mapValues{(_,v)->v.sumOf{it.play.ms}},firstRecorded=first?.toString().orEmpty(),
            artistFirst=firstArtists,
            manualSkips=if(nativeCovered||importedSince>0)totalSkips else null,manualSkipsSince=listOf(since.takeIf{nativeCovered}?:0,importedSince).filter{it>0}.minOrNull()?:0,
            importedRows=imported.size,missingTitles=history.count{it.imported&&it.title=="未知歌曲"},skipsByDay=skips)
    }
    @Synchronized fun writeBackup(writer:java.io.Writer){
        val out=android.util.JsonWriter(writer)
        out.beginObject();out.name("schemaVersion").value(1);out.name("format").value("listening-diary-app");out.name("sourceId").value("app");out.name("exportedAt").value(System.currentTimeMillis())
        for(table in listOf("tracks","spans","events","event_sources","import_batches","import_tracks","import_spans","manual_imports")){
            out.name(table).beginArray()
            db.rawQuery("SELECT * FROM $table",null).use{c->while(c.moveToNext()){out.beginObject();for(i in 0 until c.columnCount){out.name(c.getColumnName(i));when(c.getType(i)){android.database.Cursor.FIELD_TYPE_INTEGER->out.value(c.getLong(i));android.database.Cursor.FIELD_TYPE_NULL->out.nullValue();else->out.value(c.getString(i))}};out.endObject()}}
            out.endArray()
        };out.endObject();out.flush()
    }
    @Synchronized fun ingest(payload:JSONObject,importing:Boolean=false,lanIncremental:Boolean=false):Int {
        val explicit=payload.optString("format")=="listening-diary-app"
        val source=payload.optString("sourceId").ifBlank {preferences.getString("approved_source",null)?:"legacy"}
        require(source.matches(Regex("[A-Za-z0-9_-]{1,64}"))){"来源标识无效"}
        val tracks=payload.optJSONArray("tracks")?:JSONArray();val spans=payload.optJSONArray("spans")?:JSONArray()
        require(tracks.length()<=if(importing)100000 else 128);require(spans.length()<=if(importing)200000 else 128)
        val events=payload.optJSONArray("events")?:JSONArray()
        require(events.length()<=if(importing)200000 else 128)
        for(i in 0 until events.length()){
            val e=events.getJSONObject(i);require(e.getString("event_id").length in 1..128);LocalDate.parse(e.getString("day"));require(e.getString("kind") in listOf("next","previous","queue"));require(e.getLong("at")>=0&&e.getLong("position")>=0&&e.getLong("duration")>=0)
            for(k in listOf("from_track","to_track"))require(e.getString(k).length in 1..2048)
            val zone=java.time.ZoneId.of(e.getString("zone"));require(java.time.Instant.ofEpochMilli(e.getLong("at")).atZone(zone).toLocalDate().toString()==e.getString("day"))
            if(explicit)require(e.getString("remote_id").length<=2048&&e.getString("source").matches(Regex("[A-Za-z0-9_-]{1,64}")))
        }
        val sources=if(explicit)payload.optJSONArray("event_sources")?:JSONArray() else JSONArray()
        require(sources.length()<=10000);for(i in 0 until sources.length()){val item=sources.getJSONObject(i);require(item.getString("source").matches(Regex("[A-Za-z0-9_-]{1,64}"))&&item.getLong("since")>0)}
        // Validate the entire batch first. A malformed row must not partially commit other rows.
        for(i in 0 until tracks.length()){val t=tracks.getJSONObject(i);require(t.getString("id").length in 1..2048);for(k in listOf("title","artist","album"))require(t.optString(k).length<=4096)}
        for(i in 0 until spans.length()){val s=spans.getJSONObject(i);val start=s.getLong("start");val end=s.getLong("end");val ms=s.getLong("ms");require(start>=0&&end>start&&ms==end-start&&ms<=90_000_000){"记录时长无效"};LocalDate.parse(s.getString("day"));require(s.getString("track").length in 1..2048);require(s.getString("visit").length in 1..256);require(s.optString("zone").length<=128);require(s.optString("device").length<=160&&s.optString("route_source") in listOf("","audio_track","system_media"));require(s.optInt("volume_pct",-1) in -1..100);if(explicit)require(s.getString("remote_id").length<=160)}
        val restoreTables=if(importing&&explicit)validateImportBackup(payload)else emptyMap()
        val hasImportedRows=restoreTables.values.any{it.length()>0}
        var catalogChanged=false
        var firstSpanChanged=false
        val earliestNative=HashMap<Pair<String,String>,Long>()
        var changed=false
        db.beginTransaction()
        try {
            val changesBefore=db.rawQuery("SELECT total_changes()",null).use{it.moveToFirst();it.getLong(0)}
            if(!importing&&source!="legacy"&&preferences.getString("approved_source",null)==null){
                // First approved collector adopts an old plugin JSON import, preserving its row IDs.
                firstSpanChanged=true
                db.execSQL("DELETE FROM spans WHERE id IN (SELECT old.id FROM spans old JOIN spans newer ON newer.remote_id=?||substr(old.remote_id,7) WHERE old.remote_id LIKE 'legacy:%' AND newer.ms>=old.ms)",arrayOf(source))
                db.execSQL("UPDATE OR REPLACE spans SET remote_id=?||substr(remote_id,7),visit=?||substr(visit,7) WHERE remote_id LIKE 'legacy:%'",arrayOf(source,source))
            }
            for(i in 0 until tracks.length()){
                val t=tracks.getJSONObject(i);val id=t.getString("id")
                val same=db.rawQuery("SELECT title,artist,album,duration FROM tracks WHERE id=?",arrayOf(id)).use{c->c.moveToFirst()&&c.getString(0)==t.optString("title")&&c.getString(1)==t.optString("artist")&&c.getString(2)==t.optString("album")&&c.getLong(3)==t.optLong("duration")}
                if(!same){catalogChanged=true;val row=ContentValues().apply{put("id",id);put("title",t.optString("title"));put("artist",t.optString("artist"));put("album",t.optString("album"));put("duration",t.optLong("duration"))};db.insertWithOnConflict("tracks",null,row,SQLiteDatabase.CONFLICT_REPLACE)}
            }
            for(i in 0 until spans.length()){val s=spans.getJSONObject(i);val remote=if(explicit)s.getString("remote_id") else source+":"+s.optLong("sync_id",s.getLong("id"));val visit=if(explicit)s.getString("visit")else source+":"+s.getString("visit");
                val row=ContentValues().apply{put("remote_id",remote);put("visit",visit);put("device",s.optString("device"));put("route_source",s.optString("route_source"));put("volume_pct",s.optInt("volume_pct",-1));for(k in listOf("track","day","zone"))put(k,s.optString(k));for(k in listOf("start","end","ms"))put(k,s.getLong(k))}
                var exists=false
                var oldMs=0L
                var oldStart=0L
                var oldTrack="";var oldZone=""
                var same=false
                db.rawQuery("SELECT * FROM spans WHERE remote_id=?",arrayOf(remote)).use{cursor->
                    if(cursor.moveToFirst()){
                        exists=true
                        oldMs=cursor.getLong(cursor.getColumnIndexOrThrow("ms"))
                        oldStart=cursor.getLong(cursor.getColumnIndexOrThrow("start"))
                        oldTrack=cursor.getString(cursor.getColumnIndexOrThrow("track"));oldZone=cursor.getString(cursor.getColumnIndexOrThrow("zone"))
                        same=row.valueSet().all{(key,value)->cursor.getString(cursor.getColumnIndexOrThrow(key))==value.toString()}
                    }
                }
                if(!exists){
                    val identity=s.getString("track") to s.getString("zone")
                    if(!catalogChanged){
                        val prior=earliestNative.getOrPut(identity){db.rawQuery("SELECT MIN(start) FROM spans WHERE track=? AND zone=?",arrayOf(identity.first,identity.second)).use{it.moveToFirst();if(it.isNull(0))Long.MAX_VALUE else it.getLong(0)}}
                        if(s.getLong("start")<prior){firstSpanChanged=true;earliestNative[identity]=s.getLong("start")}
                    }
                }else if(!same&&s.getLong("ms")>=oldMs&&(oldStart!=s.getLong("start")||oldTrack!=s.getString("track")||oldZone!=s.getString("zone")))firstSpanChanged=true
                if(!exists)db.insertOrThrow("spans",null,row)
                else if(!same&&s.getLong("ms")>=oldMs)db.update("spans",row,"remote_id=?",arrayOf(remote))
            }
            for(i in 0 until events.length()){
                val e=events.getJSONObject(i);val row=ContentValues().apply{put("remote_id",if(explicit)e.getString("remote_id")else source+":"+e.getString("event_id"));put("source",if(explicit)e.getString("source")else source);for(k in listOf("event_id","day","zone","kind","from_track","to_track"))put(k,e.getString(k));for(k in listOf("at","position","duration"))put(k,e.getLong(k))}
                db.insertWithOnConflict("events",null,row,SQLiteDatabase.CONFLICT_IGNORE)
            }
            fun saveSource(id:String,since:Long){if(since>0){db.execSQL("INSERT OR IGNORE INTO event_sources(source,since) VALUES(?,?)",arrayOf<Any>(id,since));db.execSQL("UPDATE event_sources SET since=? WHERE source=? AND since>?",arrayOf<Any>(since,id,since))}}
            saveSource(source,payload.optLong("eventsSince",0))
            for(i in 0 until sources.length()){val item=sources.getJSONObject(i);saveSource(item.getString("source"),item.getLong("since"))}
            for((table,items) in restoreTables)for(i in 0 until items.length()){val item=items.getJSONObject(i);val row=ContentValues();item.keys().forEach{k->val value=item.get(k);if(value is Number)row.put(k,value.toLong())else row.put(k,value.toString())};db.insertWithOnConflict(table,null,row,SQLiteDatabase.CONFLICT_IGNORE)}
            changed=db.rawQuery("SELECT total_changes()",null).use{it.moveToFirst();it.getLong(0)}!=changesBefore
            db.setTransactionSuccessful()
        } finally {db.endTransaction()}
        if(changed&&firstSpanChanged)preferences.edit().putLong("first_span_revision",preferences.getLong("first_span_revision",0)+1).commit()
        if(catalogChanged){nativeCatalogVersion++;preferences.edit().putLong("catalog_revision",nativeCatalogVersion).commit()}
        if(changed){bumpRevision(if(lanIncremental)hasImportedRows else importing||hasImportedRows);if(hasImportedRows)importedCache=null}
        if(!importing)preferences.edit().putString("approved_source",source).putLong("last_sync",System.currentTimeMillis()).putLong("sync_serial",preferences.getLong("sync_serial",0)+1).putString("now_title",payload.optString("nowTitle")).putString("now_track_id",payload.optString("nowTrackId").takeIf{it.length<=2048}?:"").putString("record_state",payload.optString("status")).apply()
        payload.optJSONArray("art")?.let {art->for(i in 0 until minOf(8,art.length()))runCatching{val item=art.getJSONObject(i);val bytes=android.util.Base64.decode(item.getString("jpeg"),android.util.Base64.DEFAULT);require(bytes.size<=200000);val options=android.graphics.BitmapFactory.Options().apply{inJustDecodeBounds=true};android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size,options);require(options.outWidth in 1..2048&&options.outHeight in 1..2048);java.io.File(images,ArtworkKeys.hash(item.getString("id"))+".jpg").let{file->if(!file.exists()){val temp=java.io.File.createTempFile("native-",".tmp",images);try{temp.writeBytes(bytes);java.nio.file.Files.move(temp.toPath(),file.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING)}finally{temp.delete()}}}}}
        return spans.length()+events.length()
    }
    private fun rows(sql:String,vararg args:String):JSONArray {val a=JSONArray();db.rawQuery(sql,args).use{c->while(c.moveToNext()){val row=JSONObject();for(i in 0 until c.columnCount)row.put(c.getColumnName(i),if(c.getType(i)==android.database.Cursor.FIELD_TYPE_INTEGER)c.getLong(i)else c.getString(i));a.put(row)}};return a}
    @Synchronized fun report(day:LocalDate,range:DiaryRange):JSONObject {
        return report(bounds(day,range))
    }
    @Synchronized fun allReport():JSONObject {
        val dates=rows("SELECT MIN(day) first,MAX(day) last FROM diary_rows").getJSONObject(0)
        val today=LocalDate.now().toString()
        return report(DayBounds(dates.optString("first").takeUnless{it.isBlank()||it=="null"}?:today,dates.optString("last").takeUnless{it.isBlank()||it=="null"}?:today))
    }
    private fun report(b:DayBounds):JSONObject {
        val filter=" WHERE s.day>=? AND s.day<=? ";val args=arrayOf(b.from,b.to)
        return JSONObject().apply {
            val since=rows("SELECT COALESCE(MIN(since),0) n FROM event_sources").getJSONObject(0).getLong("n")
            val imported=rows("SELECT COUNT(*) n,COALESCE(MIN(start),0) since,COUNT(DISTINCT CASE WHEN reason IN ('TRACK_SKIPPED_FORWARDS','TRACK_SKIPPED_BACKWARDS','MANUALLY_SELECTED_PLAYBACK_OF_A_DIFF_ITEM') THEN batch||':'||row_key END) skips FROM diary_rows WHERE imported=1 AND day>=? AND day<=?",b.from,b.to).getJSONObject(0)
            val nativeCovered=since>0&&LocalDate.parse(b.to).plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()>since
            val importedSince=imported.getLong("since");put("imported_rows",imported.getInt("n"));put("manual_skips_since",listOf(since.takeIf{nativeCovered}?:0,importedSince).filter{it>0}.minOrNull()?:0)
            if(nativeCovered||importedSince>0)put("manual_skips",rows("SELECT COUNT(*) n FROM events WHERE day>=? AND day<=?",b.from,b.to).getJSONObject(0).getLong("n")+imported.getLong("skips"))
            put("from",b.from);put("to",b.to);put("selected",b.to);put("today",LocalDate.now().toString());put("zone",java.util.TimeZone.getDefault().id)
            put("summary",rows("SELECT COALESCE(SUM(s.ms),0) ms,COUNT(DISTINCT CASE WHEN s.imported=0 OR s.title NOT IN ('','未知歌曲') THEN s.track END) tracks,COUNT(DISTINCT CASE WHEN s.imported=0 OR s.artist NOT IN ('','未知歌手') THEN s.artist END) artists,COUNT(DISTINCT s.day) days FROM diary_rows s"+filter,*args).getJSONObject(0))
            put("plays",rows("SELECT COUNT(*) n FROM (SELECT s.visit FROM diary_rows s"+filter+"GROUP BY s.visit HAVING SUM(s.ms)>=CASE WHEN MAX(s.duration)>0 THEN MIN(${DailyRows.threshold()*1000},MAX(1,MAX(s.duration)/2)) ELSE ${DailyRows.threshold()*1000} END)",*args).getJSONObject(0).getLong("n"))
            put("daily",rows("SELECT s.visit AS visit,s.track AS id,s.title AS title,s.artist AS artist,s.album AS album,s.duration AS duration,MIN(s.start) start,MAX(s.end) end,SUM(s.ms) ms,MAX(s.zone) zone,MAX(s.imported) imported FROM diary_rows s"+filter+"GROUP BY s.visit ORDER BY start ASC",*args))
            put("artists",rows("SELECT s.artist AS artist,SUM(s.ms) ms,COUNT(DISTINCT s.track) tracks,COUNT(DISTINCT s.visit) starts FROM diary_rows s"+filter+"AND (s.imported=0 OR s.artist NOT IN ('','未知歌手')) GROUP BY s.artist ORDER BY ms DESC LIMIT 40",*args))
            put("day_totals",rows("SELECT s.day,SUM(s.ms) ms FROM diary_rows s"+filter+"GROUP BY s.day ORDER BY s.day",*args))
            put("first_recorded",rows("SELECT MIN(day) day FROM diary_rows").getJSONObject(0).optString("day",""))
            put("artist_first",rows("SELECT s.artist AS artist,MIN(s.day) day FROM diary_rows s GROUP BY s.artist"))
            put("heat",rows("SELECT day,SUM(ms) ms FROM diary_rows GROUP BY day ORDER BY day DESC LIMIT 366"))
            put("segments",rows("SELECT s.visit AS visit,s.start AS start,s.end AS end,s.ms AS ms,s.zone AS zone,s.track AS id,s.title AS title,s.artist AS artist,s.album AS album,s.duration AS duration,s.imported AS imported FROM diary_rows s"+filter+"ORDER BY s.start",*args))
            put("last_sync",preferences.getLong("last_sync",0));put("now_title",preferences.getString("now_title",""));put("now_track_id",preferences.getString("now_track_id",""));put("record_state",preferences.getString("record_state","等待采集插件"))
        }
    }
    private fun validateImportBackup(payload:JSONObject):Map<String,JSONArray> {
        val fields=mapOf("import_batches" to setOf("id","label","rows","ms","from_day","to_day","imported_at"),"import_tracks" to setOf("batch","id","title","artist","album","duration"),"import_spans" to setOf("batch","row_key","part","visit","track","day","zone","start","end","ms","reason","original_end","start_position","end_position","inferred"))
        val result=fields.mapValues{(table,keys)->val data=payload.optJSONArray(table)?:JSONArray();require(data.length()<=1000000);for(i in 0 until data.length()){val row=data.getJSONObject(i);require(row.keys().asSequence().toSet()==keys);require(row.getString(if(table=="import_batches")"id"else "batch").matches(Regex("[0-9a-f]{64}")));for(k in keys)if(row.get(k)is String)require(row.getString(k).length<=4096)
            if(table=="import_spans"){require(row.getLong("end")>row.getLong("start")&&row.getLong("end")-row.getLong("start")==row.getLong("ms"));LocalDate.parse(row.getString("day"));java.time.ZoneId.of(row.getString("zone"))}
            if(table=="import_batches"){LocalDate.parse(row.getString("from_day"));LocalDate.parse(row.getString("to_day"));require(row.getLong("ms")>0&&row.getInt("rows")>0)}
        };data}
        val manual=payload.optJSONArray("manual_imports")?:JSONArray();require(manual.length()<=100000)
        for(i in 0 until manual.length()){val r=manual.getJSONObject(i);require(r.keys().asSequence().toSet()==setOf("id","label","kind","rows","ms","from_day","to_day","imported_at"));require(r.getString("id").matches(Regex("[0-9a-f]{64}")));require(r.getString("kind") in listOf("网页记录","日记备份","插件备份","JSON 记录"));require(r.getString("label").length<=180&&r.getLong("ms")>=0&&r.getInt("rows")>=0);for(k in listOf("from_day","to_day")){val day=r.getString(k);if(day.isNotEmpty())LocalDate.parse(day)}}
        val ids=appleBatches().map{it.id}.toSet()+result.getValue("import_batches").objects().map{it.getString("id")}
        for(table in listOf("import_tracks","import_spans"))for(row in result.getValue(table).objects())require(row.getString("batch") in ids)
        return result+mapOf("manual_imports" to manual)
    }
    @Synchronized internal fun appleBatches():List<AppleBatch> = rows("SELECT * FROM import_batches ORDER BY imported_at DESC").objects().map{AppleBatch(it.getString("id"),it.getString("label"),it.getInt("rows"),it.getLong("ms"),it.getString("from_day"),it.getString("to_day"),it.getLong("imported_at"))}
    @Synchronized internal fun manualBatches():List<AppleBatch> = rows("SELECT * FROM manual_imports ORDER BY imported_at DESC").objects().map{AppleBatch("manual:"+it.getString("id"),it.getString("kind")+" · "+it.getString("label"),it.getInt("rows"),it.getLong("ms"),it.getString("from_day"),it.getString("to_day"),it.getLong("imported_at"))}
    @Synchronized internal fun rememberManualImport(bytes:ByteArray,payload:JSONObject,label:String){
        val id=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
        val source=payload.optString("sourceId");val kind=when{source=="web_preview"->"网页记录";payload.optString("format")=="listening-diary-app"->"日记备份";payload.has("plugin")->"插件备份";else->"JSON 记录"}
        val items=listOf(payload.optJSONArray("spans")?:JSONArray(),payload.optJSONArray("import_spans")?:JSONArray());val days=mutableListOf<String>();var count=0;var ms=0L
        for(a in items)for(i in 0 until a.length()){val row=a.getJSONObject(i);row.optString("day").takeIf{it.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))}?.let(days::add);count++;ms+=row.optLong("ms",0).coerceAtLeast(0)}
        val values=ContentValues().apply{put("id",id);put("label",label.take(180));put("kind",kind);put("rows",count);put("ms",ms);put("from_day",days.minOrNull().orEmpty());put("to_day",days.maxOrNull().orEmpty());put("imported_at",System.currentTimeMillis())}
        db.insertWithOnConflict("manual_imports",null,values,SQLiteDatabase.CONFLICT_IGNORE)
    }
    @Synchronized internal fun webImportSummary():AppleBatch? {
        val row=rows("SELECT COUNT(DISTINCT visit) n,COALESCE(SUM(ms),0) ms,MIN(day) first_day,MAX(day) last_day FROM spans WHERE remote_id LIKE 'web:%'").getJSONObject(0)
        if(row.getInt("n")==0)return null
        return AppleBatch("web_preview","网页听歌记录",row.getInt("n"),row.getLong("ms"),row.getString("first_day"),row.getString("last_day"),0)
    }
    @Synchronized internal fun importApple(plan:ApplePlan,check:()->Unit={}):Boolean {
        require(plan.rows.isNotEmpty()&&plan.rows.size<=500000);for(r in plan.rows)require(r.ms>0&&r.ms<=7*24*3600000L&&r.start>=946684800000L&&r.track.id.length<=2048&&r.key.length<=128)
        if(appleBatches().any{it.id==plan.fingerprint})return false
        val batch=plan.fingerprint;db.beginTransaction()
        try{
            val meta=ContentValues().apply{put("id",batch);put("label",plan.label);put("rows",plan.rows.size);put("ms",plan.ms);put("from_day",plan.from);put("to_day",plan.to);put("imported_at",System.currentTimeMillis())};db.insertOrThrow("import_batches",null,meta)
            for(t in plan.rows.map{it.track}.distinctBy{it.id}){val row=ContentValues().apply{put("batch",batch);put("id",t.id);put("title",t.title);put("artist",t.artist);put("album",t.album);put("duration",t.duration)};db.insertOrThrow("import_tracks",null,row)}
            for((index,r) in plan.rows.withIndex()){if(index%128==0)check();var start=r.start;val end=r.start+r.ms;var part=0;val zone=java.time.ZoneId.of(r.zone)
                while(start<end){val date=java.time.Instant.ofEpochMilli(start).atZone(zone).toLocalDate();val next=minOf(end,date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli());require(next>start)
                    val row=ContentValues().apply{put("batch",batch);put("row_key",r.key);put("part",part++);put("visit","apple:$batch:${r.visit}");put("track",r.track.id);put("day",date.toString());put("zone",r.zone);put("start",start);put("end",next);put("ms",next-start);put("reason",r.reason);put("original_end",r.originalEnd);put("start_position",r.startPosition);put("end_position",r.endPosition);put("inferred",if(r.inferred)1 else 0)};db.insertOrThrow("import_spans",null,row);start=next
                }
            }
            db.setTransactionSuccessful()
        }finally{db.endTransaction()};bumpRevision(true);importedCache=null;return true
    }
    @Synchronized internal fun removeApple(batch:String){db.beginTransaction();try{db.delete("import_spans","batch=?",arrayOf(batch));db.delete("import_tracks","batch=?",arrayOf(batch));db.delete("import_batches","id=?",arrayOf(batch));db.setTransactionSuccessful()}finally{db.endTransaction()};bumpRevision(true);importedCache=null}
    @Synchronized fun backup()=JSONObject().put("schemaVersion",1).put("format","listening-diary-app").put("sourceId","app").put("exportedAt",System.currentTimeMillis()).put("tracks",rows("SELECT * FROM tracks")).put("spans",rows("SELECT * FROM spans ORDER BY start")).put("events",rows("SELECT * FROM events ORDER BY at")).put("event_sources",rows("SELECT * FROM event_sources")).put("import_batches",rows("SELECT * FROM import_batches")).put("import_tracks",rows("SELECT * FROM import_tracks")).put("import_spans",rows("SELECT * FROM import_spans")).put("manual_imports",rows("SELECT * FROM manual_imports"))
    /** The change journal is installed only after explicit device-pairing opt-in. */
    @Synchronized internal fun lanStore():LanSyncStore {
        check(!projectionOnly);LanChangeJournal.install(db);val owner=this
        return object:LanSyncStore {
            override fun replicaId():String=synchronized(owner){db.rawQuery("SELECT value FROM lan_state WHERE name='replica_id'",null).use{check(it.moveToFirst());it.getString(0)}}
            override fun cursor(peer:String):Long=synchronized(owner){
                require(peer.matches(Regex("[a-f0-9]{32}")))
                db.rawQuery("SELECT value FROM lan_state WHERE name=?",arrayOf("peer:$peer")).use{if(it.moveToFirst())it.getString(0).toLong() else 0L}
            }
            override fun changesAfter(cursor:Long,limit:Int,skipOrigin:String?):List<JSONObject> = synchronized(owner){
                LanChangeJournal.recordsAfter(db,cursor,limit,skipOrigin).map{record->
                    if(record.skip)JSONObject().put("seq",record.change.sequence).put("skip",true)
                    else JSONObject().put("seq",record.change.sequence).put("entity",record.change.table)
                        .put("keys",JSONArray(record.change.keys)).put("deleted",record.row==null)
                        .put("clock",record.clock).put("origin",record.origin)
                        .apply{record.row?.let{put("row",it)}}
                }
            }
            override fun apply(peer:String,expectedCursor:Long,changes:List<JSONObject>)=synchronized(owner){
                require(peer.matches(Regex("[a-f0-9]{32}"))&&expectedCursor>=0&&changes.size<=256)
                require(cursor(peer)==expectedCursor){"Peer cursor changed"}
                var sequence=expectedCursor
                data class Pending(val entity:String,val keys:List<String>,val deleted:Boolean,val row:JSONObject?,val clock:Long,val origin:String)
                val valid=changes.mapNotNull{item->
                    val next=item.getLong("seq");require(next>sequence);sequence=next
                    if(item.optBoolean("skip"))return@mapNotNull null
                    val entity=item.getString("entity");val table=LanChangeJournal.tableFor(entity)?:error("Unknown sync entity")
                    val array=item.getJSONArray("keys");require(array.length()==3)
                    val keys=(0 until table.keys.size).map{array.getString(it).also{v->require(v.isNotEmpty()&&v.length<=2048)}}
                    val deleted=item.getBoolean("deleted");val row=if(deleted)null else item.getJSONObject("row")
                    if(row!=null)for((i,key) in table.keys.withIndex())require(row.getString(key)==keys[i])
                    val clock=item.getLong("clock");val origin=item.getString("origin")
                    require(clock in 1..9_000_000_000_000_000L&&origin.matches(Regex("[a-f0-9]{32}")))
                    Pending(entity,keys,deleted,row,clock,origin)
                }
                if(changes.isEmpty())return@synchronized
                db.beginTransaction()
                try{
                    var pending=JSONObject().put("format","listening-diary-app").put("sourceId","lan")
                    var hasRows=false;var removed=false
                    val queued=mutableListOf<Pending>()
                    fun saveVersion(item:Pending){
                        val padded=item.keys+List(3-item.keys.size){""}
                        db.execSQL("INSERT OR REPLACE INTO lan_versions(entity,key1,key2,key3,clock,origin) VALUES(?,?,?,?,?,?)",arrayOf<Any>(item.entity,*padded.toTypedArray(),item.clock,item.origin))
                    }
                    fun flush(){if(hasRows){ingest(pending,true,true);queued.forEach(::saveVersion);queued.clear();pending=JSONObject().put("format","listening-diary-app").put("sourceId","lan");hasRows=false}}
                    // A journal may mention the same key more than once; records resolve
                    // to its current state. Keep the last occurrence in this batch.
                    val current=linkedMapOf<Pair<String,List<String>>,Pending>()
                    for(item in valid)current[item.entity to item.keys]=item
                    for(item in current.values){
                        val padded=item.keys+List(3-item.keys.size){""}
                        val version=db.rawQuery("SELECT clock,origin FROM lan_versions WHERE entity=? AND key1=? AND key2=? AND key3=?",arrayOf(item.entity,*padded.toTypedArray())).use{c->if(c.moveToFirst())c.getLong(0) to c.getString(1) else null}
                        if(version!=null&&(item.clock<version.first||(item.clock==version.first&&item.origin<=version.second)))continue
                        db.execSQL("UPDATE lan_state SET value=MAX(CAST(value AS INTEGER),?) WHERE name='logical_clock'",arrayOf(item.clock))
                        if(item.deleted){
                            flush();val table=LanChangeJournal.tableFor(item.entity)!!
                            val where=table.keys.joinToString(" AND "){"\"$it\"=?"}
                            if(db.delete(table.name,where,item.keys.toTypedArray())>0)removed=true else db.execSQL("INSERT INTO lan_changes(entity,key1,key2,key3,deleted) VALUES(?,?,?,?,1)",arrayOf(item.entity,*padded.toTypedArray()))
                            saveVersion(item)
                        }else{
                            val list=pending.optJSONArray(item.entity)?:JSONArray().also{pending.put(item.entity,it)}
                            list.put(item.row);queued.add(item);hasRows=true
                        }
                    }
                    flush()
                    db.execSQL("INSERT OR REPLACE INTO lan_state(name,value) VALUES(?,?)",arrayOf("peer:$peer",sequence.toString()))
                    db.setTransactionSuccessful()
                    if(removed){bumpRevision(true);importedCache=null;nativeCatalogVersion++;preferences.edit().putLong("catalog_revision",nativeCatalogVersion).commit()}
                }finally{db.endTransaction()}
            }
        }
    }
    @Synchronized fun neededArtwork():String {
        val result=JSONArray()
        rows("SELECT track id FROM diary_rows GROUP BY track ORDER BY MAX(end) DESC LIMIT 300").objects().forEach{row->val id=row.getString("id");if(!java.io.File(images,ArtworkKeys.hash(id)+".jpg").isFile&&result.length()<80)result.put(id)}
        return result.toString()
    }
    companion object {
        @Volatile private var instance:DiaryDatabase?=null
        @Volatile private var projectionInstance:DiaryDatabase?=null
        @Volatile private var projectionOwner:DiaryDatabase?=null
        private var discoveryInstance:DiaryDatabase?=null
        private var discoveryOwner:DiaryDatabase?=null
        internal fun discovery(context:Context):DiaryDatabase {
            val owner=get(context)
            return synchronized(this){
                if(discoveryOwner!==owner||discoveryInstance==null){discoveryInstance=DiaryDatabase(context.applicationContext,true);discoveryOwner=owner}
                discoveryInstance!!
            }
        }
        fun get(context:Context):DiaryDatabase=instance?:synchronized(this){instance?:DiaryDatabase(context.applicationContext).also{instance=it}}
        internal fun projection(context:Context):DiaryDatabase {
            val owner=get(context) // Initialize and migrate schema before the independent reader opens it.
            return synchronized(this){
                if(projectionOwner!==owner||projectionInstance==null){projectionInstance=DiaryDatabase(context.applicationContext,true);projectionOwner=owner}
                projectionInstance!!
            }
        }
    }
}
