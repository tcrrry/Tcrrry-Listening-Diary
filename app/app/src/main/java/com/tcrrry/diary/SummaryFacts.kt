package com.tcrrry.diary

import java.time.*
import org.json.JSONObject

internal data class ListeningDay(val date: LocalDate, val play: Play):java.io.Serializable

internal data class CatalogFact(
    val id: String,
    val albumId: String,
    val album: String,
    val artist: String,
    val total: Int,
    val genre: String
) {
    companion object {
        fun parse(row: JSONObject) = CatalogFact(
            row.optString("trackId"),
            row.optString("collectionId"),
            row.optString("collectionName"),
            row.optString("artistName"),
            if (row.optInt("discCount", 1) > 1) 0 else row.optInt("trackCount"),
            row.optString("primaryGenreName")
        )
    }
}

internal data class PreferenceBand(
    val from: YearMonth,
    val to: YearMonth,
    val artists: List<ArtistStat>,
    val caption: String = artists.firstOrNull()?.name.orEmpty()
):java.io.Serializable

internal data class GenreStat(
    val name: String,
    val ms: Long,
    val topArtists: List<String>,
    val trackCount: Int
)

internal data class MonthGenreSlice(
    val month: YearMonth,
    val totalMs: Long,
    val topGenres: List<Pair<String, Long>>,
    val leadPlay: Play?
)

internal data class ArtistTimelineBucket(
    val label: String,
    val fullRangeTitle: String,
    val yearBadge: String?,
    val ms: Long,
    val from: YearMonth,
    val to: YearMonth
)

/**
 * Normalizes fragmented Apple Music / iTunes primaryGenreName strings into clean, recognizable
 * mainstream categories (华语流行, J-Pop, K-Pop, 电子乐, R&B, 摇滚与另类, 说唱与嘻哈, 民谣与独立, 原声与纯音乐, 爵士与蓝调, 欧美流行).
 */
internal fun normalizeMusicGenre(rawGenre: String?, play: Play, artistFallbackGenre: String? = null): String {
    val raw = rawGenre?.trim()?.takeIf { it.isNotEmpty() && it != "未匹配" }
        ?: artistFallbackGenre?.trim()?.takeIf { it.isNotEmpty() && it != "未匹配" }
        ?: ""
    val lower = raw.lowercase()
    val sample = "${play.title} ${play.artist} ${play.album}"
    val hasKana = sample.any { ch ->
        ch in '\u3040'..'\u309f' || ch in '\u30a0'..'\u30ff' || ch in '\u31f0'..'\u31ff' || ch in '\uff66'..'\uff9f'
    }
    val hasHangul = sample.any { ch ->
        ch in '\uac00'..'\ud7af' || ch in '\u1100'..'\u11ff' || ch in '\u3130'..'\u318f'
    }
    val hasHanzi = !hasKana && !hasHangul && sample.any { ch -> ch in '\u4e00'..'\u9fff' }

    if (raw.isEmpty()) {
        return when {
            hasKana -> "J-Pop"
            hasHangul -> "K-Pop"
            hasHanzi -> "华语流行"
            else -> "未匹配"
        }
    }

    // 1. Explicit regional pop & anime/idol categories
    if (lower.contains("j-pop") || lower.contains("jpop") || lower.contains("japanese") ||
        lower.contains("anime") || lower.contains("anisong") || lower.contains("vocaloid") ||
        lower.contains("kayokyoku") || lower.contains("enka") || lower.contains("city pop") ||
        lower.contains("shibuya") || lower.contains("j-rock") || lower.contains("j-hip") ||
        lower.contains("日") || lower.contains("动画") || lower.contains("動畫") ||
        lower.contains("动漫") || lower.contains("動漫") || lower.contains("歌谣") || lower.contains("演歌")
    ) return "J-Pop"

    if (lower.contains("k-pop") || lower.contains("kpop") || lower.contains("korean") ||
        lower.contains("k-indie") || lower.contains("k-hip") || lower.contains("k-r&b") ||
        lower.contains("trot") || lower.contains("韩") || lower.contains("韓")
    ) return "K-Pop"

    if (lower.contains("mandopop") || lower.contains("cantopop") || lower.contains("tai-pop") ||
        lower.contains("c-pop") || lower.contains("cpop") || lower.contains("chinese") ||
        lower.contains("国语") || lower.contains("國語") || lower.contains("粤语") || lower.contains("粵語") ||
        lower.contains("华语") || lower.contains("華語") || lower.contains("台语") || lower.contains("台語") ||
        lower.contains("闽南") || lower.contains("中国") || lower.contains("中國") ||
        lower.contains("古风") || lower.contains("国风") || lower.contains("港乐")
    ) return "华语流行"

    // 2. Mainstream musical styles
    if (lower.contains("r&b") || lower.contains("rnb") || lower.contains("soul") ||
        lower.contains("neo-soul") || lower.contains("funk") || lower.contains("motown") ||
        lower.contains("灵魂") || lower.contains("靈魂") || lower.contains("放克") || lower.contains("节奏布鲁斯")
    ) return "R&B"

    if (lower.contains("electronic") || lower.contains("electronica") || lower.contains("edm") ||
        lower.contains("dance") || lower.contains("house") || lower.contains("techno") ||
        lower.contains("trance") || lower.contains("dubstep") || lower.contains("drum") ||
        lower.contains("ambient") || lower.contains("downtempo") || lower.contains("synth") ||
        lower.contains("garage") || lower.contains("breakbeat") || lower.contains("lofi") || lower.contains("lo-fi") ||
        lower.contains("电子") || lower.contains("電子") || lower.contains("舞曲") || lower.contains("电音") || lower.contains("环境")
    ) return "电子乐"

    if (lower.contains("hip-hop") || lower.contains("hip hop") || lower.contains("rap") ||
        lower.contains("trap") || lower.contains("drill") || lower.contains("grime") ||
        lower.contains("嘻哈") || lower.contains("说唱") || lower.contains("說唱") || lower.contains("饶舌")
    ) return "说唱与嘻哈"

    if (lower.contains("soundtrack") || lower.contains("score") || lower.contains("video game") ||
        lower.contains("musical") || lower.contains("classical") || lower.contains("orchestral") ||
        lower.contains("opera") || lower.contains("piano") || lower.contains("instrumental") ||
        lower.contains("new age") || lower.contains("chamber") ||
        lower.contains("原声") || lower.contains("原聲") || lower.contains("配乐") || lower.contains("配樂") ||
        lower.contains("电影") || lower.contains("游戏") || lower.contains("古典") || lower.contains("纯音乐") ||
        lower.contains("器乐") || lower.contains("新世纪") || lower.contains("钢琴")
    ) return "原声与纯音乐"

    if (lower.contains("jazz") || lower.contains("blues") || lower.contains("bossa") ||
        lower.contains("swing") || lower.contains("lounge") || lower.contains("easy listening") ||
        lower.contains("爵士") || lower.contains("蓝调") || lower.contains("藍調") || lower.contains("轻音乐")
    ) return "爵士与蓝调"

    if (lower.contains("folk") || lower.contains("country") || lower.contains("acoustic") ||
        lower.contains("americana") || lower.contains("bluegrass") ||
        lower.contains("民谣") || lower.contains("民謠") || lower.contains("乡村") || lower.contains("不插电")
    ) return "民谣与独立"

    if (lower.contains("rock") || lower.contains("metal") || lower.contains("punk") ||
        lower.contains("grunge") || lower.contains("britpop") || lower.contains("shoegaze") ||
        lower.contains("post-rock") || lower.contains("emo") ||
        lower.contains("摇滚") || lower.contains("搖滾") || lower.contains("金属") || lower.contains("朋克") || lower.contains("后摇")
    ) {
        return when {
            hasKana -> "J-Pop"
            else -> "摇滚与另类"
        }
    }

    if (lower.contains("alternative") || lower.contains("indie") || lower.contains("singer/songwriter") ||
        lower.contains("另类") || lower.contains("另類") || lower.contains("独立") || lower.contains("唱作")
    ) {
        return when {
            hasKana -> "J-Pop"
            hasHangul -> "K-Pop"
            hasHanzi -> "华语流行"
            else -> "摇滚与另类"
        }
    }

    // 3. Generic Pop / World / Music fallback using language script
    return when {
        hasKana -> "J-Pop"
        hasHangul -> "K-Pop"
        hasHanzi -> "华语流行"
        else -> "欧美流行"
    }
}

/** All derived figures share the recorded segments, including their own timezone and credited duration. */
internal class SummaryFacts(source: DiaryReport):java.io.Serializable {
    val report=source.copy(history=ArrayList(source.history),segments=ArrayList(source.segments),topArtists=ArrayList(source.topArtists),heat=HashSet(source.heat),dayTotals=HashMap(source.dayTotals),artistFirst=HashMap(source.artistFirst),skipsByDay=HashMap(source.skipsByDay))
    val days: List<ListeningDay> = run {
        val result=ArrayList<ListeningDay>(report.segments.size)
        val zones=HashMap<String,ZoneId>()
        for(span in report.segments){
            val zone=zones.getOrPut(span.zone){runCatching { ZoneId.of(span.zone) }.getOrDefault(ZoneId.systemDefault())}
            val length=span.end-span.start
            var cursor=span.start
            while(length>0 && cursor<span.end && span.ms>0){
                val date=Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
                val end=minOf(span.end,date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
                if(end<=cursor)break
                val before=(span.ms*((cursor-span.start).toDouble()/length)).toLong()
                val through=(span.ms*((end-span.start).toDouble()/length)).toLong()
                if(through>before){
                    val owner=span.listeningDay.takeIf{it.isNotBlank()}?.let{LocalDate.parse(it)}?:date
                    val part=if(cursor==span.start && end==span.end && through-before==span.ms)span else span.copy(start=cursor,end=end,ms=through-before)
                    result.add(ListeningDay(owner,part))
                }
                cursor=end
            }
        }
        result
    }

    val totals = days.groupBy { it.date }.mapValues { (_, v) -> v.sumOf { it.play.ms } }

    val months = run {
        val observed = days.groupBy { YearMonth.from(it.date) }.toSortedMap()
        if (observed.isEmpty()) observed else {
            val filled = sortedMapOf<YearMonth, List<ListeningDay>>()
            var month = observed.firstKey()
            while (month <= observed.lastKey()) {
                filled[month] = observed[month].orEmpty()
                month = month.plusMonths(1)
            }
            filled
        }
    }

    val spansMultipleYears: Boolean = months.keys.let { it.size > 1 && it.first().year != it.last().year }
    val recordedYears: List<Int> = months.entries.filter { it.value.isNotEmpty() }.map { it.key.year }.distinct().sorted()

    /**
     * Monthly favorite album trajectory: selects the #1 album of each month by total effective plays
     * and listening duration (falling back to top track when album metadata is absent).
     */
    val monthlyFavorites = months.mapNotNull { (month, rows) ->
        val visits = rows.groupBy { it.play.visit to it.play.id }.values.map { parts ->
            val first = parts.first().play
            val ms = parts.sumOf { it.play.ms }
            first.copy(ms = ms, effective = DailyRows.effective(ms, first.duration))
        }.filter { !it.imported || it.title != "未知歌曲" }

        val topAlbumPlay = visits
            .filter { it.album.isNotBlank() }
            .groupBy { it.album to it.artist }
            .maxWithOrNull(
                compareBy<Map.Entry<Pair<String, String>, List<Play>>> { (_, tracks) -> tracks.sumOf { it.effective } }
                    .thenBy { (_, tracks) -> tracks.sumOf { it.ms } }
            )?.value?.let { tracks ->
                sortedPlays(tracks, SortOrder.COUNT).firstOrNull()
            }

        val chosen = topAlbumPlay ?: sortedPlays(visits, SortOrder.COUNT).firstOrNull()
        chosen?.let { month to it }
    }

    private val artistDayCounts = days.groupBy { it.play.artist }.mapValues { (_, v) -> v.map { it.date }.distinct().size }
    private val albumDayCounts = days.groupBy { it.play.album to it.play.artist }.mapValues { (_, v) -> v.map { it.date }.distinct().size }
    private val trackMonthCounts = days.groupBy { it.play.id }.mapValues { (_, v) -> v.map { YearMonth.from(it.date) }.distinct().size }
    private val firstPlayByArtist: Map<String, Play> = report.history
        .filter { it.artist.isNotBlank() && (!it.imported || it.artist != "未知歌手") && it.ms > 0 }
        .groupBy { it.artist }
        .mapValues { (_, list) -> list.minByOrNull { it.start }!! }
    private val firstPlayByTrack: Map<String, Play> = report.history
        .filter { it.id.isNotBlank() && it.ms > 0 }
        .groupBy { it.id }
        .mapValues { (_, list) -> list.minByOrNull { it.start }!! }
    private val activeYearsByArtist: Map<String, List<Int>> = days
        .filter { it.play.ms > 0 && it.play.artist.isNotBlank() }
        .groupBy { it.play.artist }
        .mapValues { (_, list) -> list.map { it.date.year }.distinct().sorted() }
    private val artistMonthMs: Map<String, Map<YearMonth, Long>> = run {
        val map = mutableMapOf<String, MutableMap<YearMonth, Long>>()
        for (row in days) {
            val artist = row.play.artist
            if (artist.isBlank() || row.play.ms <= 0L) continue
            val ym = YearMonth.from(row.date)
            val inner = map.getOrPut(artist) { mutableMapOf() }
            inner[ym] = (inner[ym] ?: 0L) + row.play.ms
        }
        map
    }

    val albums = reportAlbums(report)
    val artists = report.history
        .filter { it.artist.isNotBlank() && (!it.imported || it.artist != "未知歌手") }
        .groupBy { it.artist }
        .map { (name, v) -> ArtistStat(name, v.sumOf { it.ms }, v.map { it.id }.distinct().size) }
        .sortedByDescending { it.ms }
    val songs = sortedPlays(report.history.filter { !it.imported || it.title != "未知歌曲" }, SortOrder.COUNT)

    /** Songs that kept returning across multiple months, ordered by month span then play count. */
    val returningSongs: List<Play> = songs
        .sortedWith(
            compareByDescending<Play> { trackMonthCounts[it.id] ?: 0 }
                .thenByDescending { it.effective }
                .thenByDescending { it.ms }
        )
        .take(6)

    /** Precomputed hourly bins and session summary so the UI never recomputes them on the main thread. */
    val hourlyBins: List<Long> = HourlyListening.hours(report.segments)
    val sessions: List<ListeningSession> = ListeningSessions.group(report.segments)
    val peakHourSummary: String = run {
        var bestStart = 20
        var bestSum = -1L
        for (h in 0 until 24) {
            val pairSum = hourlyBins[h] + hourlyBins[(h + 1) % 24]
            if (pairSum > bestSum) {
                bestSum = pairSum
                bestStart = h
            }
        }
        if (bestSum <= 0L) "记录聆听时段与回听习惯"
        else {
            val endHour = (bestStart + 2) % 24
            val periodTag = when (bestStart) {
                in 5..8 -> "清晨"
                in 9..11 -> "上午"
                in 12..13 -> "午间"
                in 14..17 -> "午后"
                in 18..21 -> "晚间"
                else -> "深夜"
            }
            "偏爱${periodTag} %02d:00–%02d:00 · 共%d段聆听".format(bestStart, endHour, sessions.size)
        }
    }

    fun artistDays(name: String) = artistDayCounts[name] ?: 0
    fun albumDays(album: AlbumStat) = albumDayCounts[album.title to album.artist] ?: 0
    fun songMonths(id: String) = trackMonthCounts[id] ?: 0
    fun artistFirstPlay(name: String): Play? = firstPlayByArtist[name]
    fun trackFirstPlay(id: String): Play? = firstPlayByTrack[id]
    fun artistActiveYears(name: String): List<Int> = activeYearsByArtist[name] ?: recordedYears
    fun share(ms: Long) = if (report.ms > 0) (ms * 100 / report.ms).toInt() else 0
    fun monthlyShare(name: String): List<Float> {
        val byMonth = artistMonthMs[name].orEmpty()
        return months.map { (ym, v) ->
            val total = v.sumOf { it.play.ms }
            if (total > 0) (byMonth[ym] ?: 0L).toFloat() / total else 0f
        }
    }

    fun artistPlaysInBucket(artist: String, from: YearMonth, to: YearMonth): List<Play> {
        val startDay = from.atDay(1)
        val endDay = to.atEndOfMonth()
        val matching = days.filter { it.play.artist == artist && it.date >= startDay && it.date <= endDay }
        return matching
            .groupBy { it.play.visit to it.play.id }
            .values
            .map { parts ->
                val first = parts.first().play
                val ms = parts.sumOf { it.play.ms }
                first.copy(
                    start = parts.minOf { it.play.start },
                    end = parts.maxOf { it.play.end },
                    ms = ms,
                    effective = DailyRows.effective(ms, first.duration)
                )
            }
            .sortedBy { it.start }
    }

    /**
     * Adaptive-granularity timeline buckets for an artist so cross-year histories never cram
     * dozens of tiny bars together, while always clearly marking year transitions.
     */
    fun artistTimelineBuckets(artist: String, yearFilter: Int? = null): List<ArtistTimelineBucket> {
        val byMonth = artistMonthMs[artist].orEmpty()
        if (yearFilter != null) {
            return (1..12).map { m ->
                val ym = YearMonth.of(yearFilter, m)
                val ms = byMonth[ym] ?: 0L
                ArtistTimelineBucket(
                    label = "${m}月",
                    fullRangeTitle = "${yearFilter}年${m}月",
                    yearBadge = if (m == 1) "${yearFilter}年" else null,
                    ms = ms,
                    from = ym,
                    to = ym
                )
            }
        }

        val allMonths = months.keys.toList()
        if (allMonths.isEmpty()) return emptyList()
        val firstHeardIdx = allMonths.indexOfFirst { (byMonth[it] ?: 0L) > 0L }
        val trimmed = if (firstHeardIdx > 0) allMonths.drop(firstHeardIdx) else allMonths
        val totalMonths = trimmed.size
        val step = when {
            totalMonths <= 8 -> 1
            totalMonths <= 16 -> 2
            totalMonths <= 24 -> 3
            totalMonths <= 48 -> 6
            else -> 12
        }
        val chunks = trimmed.chunked(step)
        var previousYear = -1
        return chunks.map { chunk ->
            val from = chunk.first()
            val to = chunk.last()
            val ms = chunk.sumOf { byMonth[it] ?: 0L }
            val crossesOrStartsYear = from.year != previousYear || from.year != to.year
            previousYear = to.year
            val yearBadge = if (crossesOrStartsYear) "${from.year}年" else null
            val fy = (from.year % 100).toString().padStart(2, '0')
            val ty = (to.year % 100).toString().padStart(2, '0')
            val label = when {
                from == to -> if (spansMultipleYears) "$fy.${from.monthValue.toString().padStart(2, '0')}" else "${from.monthValue}月"
                from.year == to.year && step >= 6 -> "${fy}年${if (from.monthValue <= 6) "上" else "下"}"
                from.year == to.year -> "$fy.${from.monthValue}–${to.monthValue}"
                else -> "$fy.${from.monthValue}–$ty.${to.monthValue}"
            }
            val fullTitle = when {
                from == to -> "${from.year}年${from.monthValue}月"
                from.year == to.year -> "${from.year}年${from.monthValue}月–${to.monthValue}月"
                else -> "${from.year}年${from.monthValue}月–${to.year}年${to.monthValue}月"
            }
            ArtistTimelineBucket(
                label = label,
                fullRangeTitle = fullTitle,
                yearBadge = yearBadge,
                ms = ms,
                from = from,
                to = to
            )
        }
    }

    fun artistMilestoneText(artist: String): String {
        val byMonth = artistMonthMs[artist].orEmpty()
        val peak = byMonth.entries.filter { it.value > 0L }.maxByOrNull { it.value }
            ?: return "${artistDays(artist)}天听过"
        val peakStr = "${peak.key.year}年${peak.key.monthValue}月"
        return "高峰在 $peakStr（${shortDuration(peak.value)}）· 累计 ${artistDays(artist)} 天听过"
    }

    private val cachedBands: List<PreferenceBand> = run {
        val keys = months.keys.toList()
        if (keys.isEmpty()) emptyList() else {
            val size = ((keys.size + 2) / 3).coerceAtLeast(1)
            val rawBands = keys.chunked(size).map { chunk ->
                val rows = chunk.flatMap { months.getValue(it) }
                val ranked = rows
                    .filter { !it.play.imported || it.play.artist != "未知歌手" }
                    .groupBy { it.play.artist }
                    .map { (name, v) -> ArtistStat(name, v.sumOf { it.play.ms }, v.map { it.play.id }.distinct().size) }
                    .sortedByDescending { it.ms }
                    .take(3)
                PreferenceBand(chunk.first(), chunk.last(), ranked)
            }
            val usedPrimary = mutableSetOf<String>()
            rawBands.map { band ->
                val first = band.artists.firstOrNull()?.name.orEmpty()
                val second = band.artists.getOrNull(1)?.name.orEmpty()
                val caption = if (first.isNotBlank() && !usedPrimary.add(first) && second.isNotBlank()) {
                    "$first · $second"
                } else {
                    first
                }
                band.copy(caption = caption)
            }
        }
    }

    fun bands(): List<PreferenceBand> = cachedBands

    fun within(day: LocalDate, range: DiaryRange): SummaryFacts {
        val bounds = bounds(day, range)
        val from = LocalDate.parse(bounds.from)
        val to = LocalDate.parse(bounds.to)
        val parts = days.filter { it.date >= from && it.date <= to }
        val history = parts.groupBy { it.play.visit to it.play.id }.values.map { rows ->
            val first = rows.first().play
            val ms = rows.sumOf { it.play.ms }
            first.copy(
                start = rows.minOf { it.play.start },
                end = rows.maxOf { it.play.end },
                ms = ms,
                effective = DailyRows.effective(ms, first.duration)
            )
        }.sortedBy { it.start }
        return SummaryFacts(
            report.copy(
                ms = history.sumOf { it.ms },
                plays = history.sumOf { it.effective }.toInt(),
                tracks = history.filter { !it.imported || it.title != "未知歌曲" }.map { it.id }.distinct().size,
                artists = history.filter { !it.imported || it.artist != "未知歌手" }.map { it.artist }.distinct().size,
                manualSkips = report.manualSkips?.let { report.skipsByDay.filterKeys { d -> d >= bounds.from && d <= bounds.to }.values.sum() },
                importedRows = parts.count { it.play.imported },
                missingTitles = history.count { it.imported && it.title == "未知歌曲" },
                history = history,
                segments = parts.map { it.play },
                from = bounds.from,
                to = bounds.to,
                dayTotals = parts.groupBy { it.date.toString() }.mapValues { (_, v) -> v.sumOf { it.play.ms } }
            )
        )
    }
}

internal fun normalizedCatalogName(value: String) = AppleTrackImages.normalized(value)

internal fun albumCoverage(album: AlbumStat, facts: Map<String, CatalogFact>): Pair<Int, Int>? {
    val songs = album.songs.distinctBy { it.id }
    val matched = songs.map { facts[it.id] ?: return null }
    if (matched.isEmpty() || matched.map { it.albumId }.distinct().size != 1 || matched.first().albumId.isBlank()) return null
    if (matched.any { it.total <= 0 || normalizedCatalogName(it.album) != normalizedCatalogName(album.title) || normalizedCatalogName(it.artist) != normalizedCatalogName(album.artist) }) return null
    if (matched.map { it.total }.distinct().size != 1 || matched.first().total < songs.size) return null
    return songs.size to matched.first().total
}
