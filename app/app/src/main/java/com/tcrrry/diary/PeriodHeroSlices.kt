package com.tcrrry.diary

import java.time.*

/** Calendar buckets are independent of visits: a visit may cross midnight or a week boundary. */
internal data class PeriodHeroSlice(
    val from: LocalDate,
    val to: LocalDate,
    val albums: List<AlbumStat>,
    val artists: List<ArtistStat>,
    val ms: Long
) {
    val key get() = "$from/$to"
    val lead get() = albums.firstOrNull()?.cover
}

internal fun periodHeroSlices(report: DiaryReport, day: LocalDate, range: DiaryRange): List<PeriodHeroSlice> {
    val b = bounds(day, range)
    val first = LocalDate.parse(b.from)
    val last = LocalDate.parse(b.to)
    val intervals = mutableListOf<Pair<LocalDate, LocalDate>>()
    var cursor = first
    while (cursor <= last) {
        val end = minOf(last, when (range) {
            DiaryRange.YEAR -> cursor.withDayOfMonth(cursor.lengthOfMonth())
            DiaryRange.MONTH -> cursor.plusDays((7 - cursor.dayOfWeek.value).toLong())
            else -> cursor
        })
        intervals += cursor to end
        cursor = end.plusDays(1)
    }
    val zones = report.segments.map { it.zone }.distinct().associateWith {
        runCatching { ZoneId.of(it) }.getOrDefault(ZoneId.systemDefault())
    }
    // Convert calendar boundaries once per zone, rather than for every saved playback checkpoint.
    val edges = zones.mapValues { (_, zone) -> intervals.map { (from, to) ->
        from.atStartOfDay(zone).toInstant().toEpochMilli() to to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    } }
    // Assign owned listening days once. The former interval-by-interval scan parsed every
    // saved date up to twelve times for a yearly report.
    val intervalByDay = HashMap<LocalDate,Int>()
    intervals.forEachIndexed { index, (from, to) ->
        var date=from
        while(date<=to){intervalByDay[date]=index;date=date.plusDays(1)}
    }
    val clippedByInterval=Array(intervals.size){ArrayList<Play>()}
    val dateCache=HashMap<String,LocalDate>()
    for(span in report.segments){
        if(span.listeningDay.isNotBlank()){
            val owned=dateCache.getOrPut(span.listeningDay){LocalDate.parse(span.listeningDay)}
            intervalByDay[owned]?.let{clippedByInterval[it].add(span)}
            continue
        }
        val elapsed=span.end-span.start
        if(elapsed<=0||span.ms<=0)continue
        edges.getValue(span.zone).forEachIndexed { index, (edgeStart,edgeEnd) ->
            val start=maxOf(span.start,edgeStart);val end=minOf(span.end,edgeEnd)
            if(end>start){
                val before=(span.ms*((start-span.start).toDouble()/elapsed)).toLong()
                val through=(span.ms*((end-span.start).toDouble()/elapsed)).toLong()
                if(through>before)clippedByInterval[index].add(span.copy(start=start,end=end,ms=through-before))
            }
        }
    }
    return intervals.mapIndexed { index, (from, to) ->
        val clipped=clippedByInterval[index]
        val songs = clipped.groupBy { it.id }.values.map { parts ->
            parts.first().copy(ms = parts.sumOf { it.ms }, start = parts.minOf { it.start }, end = parts.maxOf { it.end })
        }
        val albums = songs.groupBy { (it.album.ifBlank { it.title }) to it.artist }.map { (identity, tracks) ->
            AlbumStat(identity.first, identity.second, tracks, tracks.sumOf { it.ms })
        }.sortedWith(compareByDescending<AlbumStat> { it.ms }.thenBy { it.key })
        val artists = songs.filter { it.artist.isNotBlank() }.groupBy { it.artist }.map { (name, tracks) ->
            ArtistStat(name, tracks.sumOf { it.ms }, tracks.size)
        }.sortedWith(compareByDescending<ArtistStat> { it.ms }.thenBy { it.name })
        PeriodHeroSlice(from, to, albums, artists, clipped.sumOf { it.ms })
    }
}

internal fun heroSliceIndex(slices: List<PeriodHeroSlice>, day: LocalDate) =
    slices.indexOfFirst { day >= it.from && day <= it.to }.coerceAtLeast(0)

internal fun heroSliceLabel(slice: PeriodHeroSlice, range: DiaryRange) = when (range) {
    DiaryRange.YEAR -> "${slice.from.monthValue}月"
    DiaryRange.MONTH -> {
        val week = (slice.from.dayOfMonth - 1 + slice.from.withDayOfMonth(1).dayOfWeek.value - 1) / 7
        "${"一二三四五六"[week]}周"
    }
    else -> "周${"一二三四五六日"[slice.from.dayOfWeek.value - 1]}"
}

/** Keep the true calendar labels while omitting empty intervals from the hero selector. */
internal fun visiblePeriodHeroSlices(report:DiaryReport,day:LocalDate,range:DiaryRange)=periodHeroSlices(report,day,range).filter{it.ms>0}
