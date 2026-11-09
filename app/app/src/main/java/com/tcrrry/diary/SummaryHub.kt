@file:OptIn(
    androidx.compose.animation.ExperimentalAnimationApi::class,
    androidx.compose.animation.ExperimentalSharedTransitionApi::class
)
package com.tcrrry.diary

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import com.kyant.backdrop.Backdrop
import java.time.*
import java.time.format.DateTimeFormatter
import kotlin.math.*

@Composable internal fun AppPeriodPicker(c: DiaryController, b: Backdrop) {
    val labels = listOf("日", "周", "月", "年", "全部")
    PeriodGlassPicker(
        if (c.summary) 4 else c.range.ordinal,
        { if (it == 4) c.openSummary() else c.select(range = DiaryRange.entries[it]) },
        b,
        count = 5,
        externalPosition = c.periodSwipePosition
    ) { i, active ->
        Text(labels[i], fontSize = 15.sp, color = if (active) Accent else Muted)
    }
}

private val GenrePalette = listOf(
    Color(0xfffb6f8b),
    Color(0xff7d9bff),
    Color(0xffba8cff),
    Color(0xff52c7b8),
    Color(0xfff2a365),
    Color(0xff8cc084),
    Color(0xffd994ad)
)

internal fun genreTint(index: Int, fallback: Color): Color =
    if(index>=0) GenrePalette[index % GenrePalette.size] else fallback

@Composable internal fun SummaryHub(
    c: DiaryController,
    b: Backdrop,
    settings: () -> Unit,
    open: (Play, String) -> Unit,
    tint: (List<Color>) -> Unit,
    shared: SharedTransitionScope,
    summaryVisibility: AnimatedVisibilityScope,
    timeline: MusicTimelineState = remember { MusicTimelineState() }
) {
    var stack by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var forward by remember { mutableStateOf(true) }
    val facts = c.summaryFacts
    val states = rememberSaveableStateHolder()

    // Sorting & view mode states for detail pages
    var trendDescending by rememberSaveable { mutableStateOf(false) }
    var trailType by rememberSaveable { mutableIntStateOf(0) }
    var favoriteAlbumsSelected by rememberSaveable { mutableStateOf(false) } // Default chronological (正序)
    var genreViewMode by rememberSaveable { mutableIntStateOf(0) } // 0 = 占比, 1 = 轨迹
    var genreTimelineDescending by rememberSaveable { mutableStateOf(false) } // Default chronological (正序)
    var artistSortMode by rememberSaveable { mutableIntStateOf(0) } // 0 = 常听, 1 = 时间
    var artistDescending by rememberSaveable { mutableStateOf(true) } // 常听默认倒序(最多在前)，时间默认正序(最早初遇在前)

    fun navigate(value: String) {
        forward = true
        stack = ArrayList(stack + value)
    }

    fun back() {
        forward = false
        if (stack.isNotEmpty()) stack = ArrayList(stack.dropLast(1)) else c.select()
    }

    BackHandler { back() }
    val route = stack.lastOrNull().orEmpty()
    val destination = route.ifEmpty { "home:0" }

    // Compute mainstream genre stats off the main thread for large real-world libraries
    val cachedGenres by produceState(
        initialValue = if (facts.songs.size <= 200) mainstreamGenreStats(facts, c.catalogFacts) else emptyList(),
        facts,
        c.catalogFacts
    ) {
        value = if (facts.songs.size <= 200) {
            mainstreamGenreStats(facts, c.catalogFacts)
        } else {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                mainstreamGenreStats(facts, c.catalogFacts)
            }
        }
    }
    val matchedGenres = remember(cachedGenres) {
        cachedGenres.filter { it.name != "未匹配" }
    }
    val trailEntries by produceState<List<AllTrailEntry>?>(null, facts, destination == "trend") {
        if (destination == "trend") value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { allTrailEntries(facts) }
    }
    val allAlbums by produceState<List<LibraryGroup>>(emptyList(), facts.albums) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { libraryAlbumGroups(facts.albums) }
    }
    val returningAll by produceState<List<Play>>(emptyList(), facts, destination == "returning") {
        if (destination == "returning") value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            facts.songs.sortedWith(compareByDescending<Play> { facts.songMonths(it.id) }.thenByDescending { it.effective }.thenByDescending { it.ms })
        }
    }
    val needGenreTrajectory = destination == "genres"
    val monthlyGenreTrajectory by produceState(
        initialValue = emptyList<MonthGenreSlice>(),
        facts,
        c.catalogFacts,
        needGenreTrajectory
    ) {
        if (needGenreTrajectory) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                monthlyGenreSlices(facts, c.catalogFacts)
            }
        }
    }

    @Composable fun Header() {
        Column(Modifier.padding(horizontal = 20.dp).padding(top = 15.dp, bottom = 3.dp)) {
            Text("听歌日记", fontSize = 13.sp, color = Muted)
            Row(
                Modifier.padding(top = 4.dp, bottom = 19.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("全部", Modifier.weight(1f), fontSize = 36.sp, fontWeight = FontWeight.ExtraBold)
                Box(
                    Modifier
                        .size(39.dp)
                        .background(Muted.copy(alpha = .08f), CircleShape)
                        .press(settings),
                    contentAlignment = Alignment.Center
                ) {
                    DiaryGlyph("more", Modifier.size(22.dp), label = "设置")
                }
            }
            Box(
                with(shared) {
                    Modifier.sharedElement(
                        rememberSharedContentState("summary-picker"),
                        summaryVisibility,
                        boundsTransform = { _, _ -> tween(320, easing = LinearEasing) }
                    )
                }
            ) {
                AppPeriodPicker(c, b)
            }
        }
    }

    AnimatedContent(
        destination,
        transitionSpec = {
            fadeIn(tween(280)) + slideInHorizontally { if (forward) it / 14 else -it / 14 } togetherWith fadeOut(tween(150))
        },
        label = "summary sections and details"
    ) { page ->
        states.SaveableStateProvider(page) {
            val list = rememberLazyListState()
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .then(if (page.startsWith("home:")) Modifier.periodSwipe(c) else Modifier)
                    .statusBarsPadding()
                    .testTag("summary-scroll"),
                state = list,
                contentPadding = PaddingValues(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(19.dp)
            ) {
                if (page.startsWith("home:")) {
                    summaryItem("header") { Header() }
                    if (c.demo) {
                        summaryItem {
                            Text("交互演示 · 示例记录", Modifier.padding(horizontal = 20.dp), fontSize = 10.sp, color = Muted)
                        }
                    }
                    if (c.summaryLoading && facts.report.history.isEmpty()) {
                        summaryItem {
                            Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(25.dp), strokeWidth = 2.dp)
                            }
                        }
                    } else if (c.summaryError != null && facts.report.history.isEmpty()) {
                        summaryItem {
                            SummaryPanel {
                                Text(c.summaryError!!, fontSize = 15.sp)
                                Text(
                                    "重新读取",
                                    Modifier.press { c.refreshSummary() }.padding(top = 12.dp),
                                    fontSize = 15.sp,
                                    color = Accent
                                )
                            }
                        }
                    } else if (facts.report.history.isEmpty() && !c.summary) {
                        summaryItem {
                            Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(25.dp), strokeWidth = 2.dp)
                            }
                        }
                    } else if (facts.report.history.isEmpty()) {
                        summaryItem {
                            SummaryPanel {
                                Text("音乐画像，从下一首开始", fontSize = 25.sp)
                                Text("有了听歌记录，这里就会慢慢丰富。", Modifier.padding(top = 12.dp), fontSize = 15.sp, color = Muted)
                            }
                        }
                    } else {
                        summaryItem("profile-hero") {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                MusicTimeline(c, facts, tint, open, shared, summaryVisibility, timeline)

                            }
                        }
                        summaryItem("lifetime-stats") {
                            ListeningStats(facts.report, Modifier.padding(horizontal = 17.dp)) { c.notice = it }
                        }
                        summaryItem("all-trail") {
                            Box(Modifier.padding(horizontal = 16.dp)) { AllYearRings(facts, c.catalogFacts) { navigate("trend") } }
                        }
                        summaryItem("all-favorites") {
                            Column(Modifier.padding(horizontal = 18.dp).testTag("all-favorites")) {
                                FavoriteHeading("最爱歌曲", favoriteAlbumsSelected, { favoriteAlbumsSelected = it }) { navigate(if (favoriteAlbumsSelected) "albums" else "songs") }
                                if (favoriteAlbumsSelected) FavoriteAlbumList(c, allAlbums.take(5))
                                else facts.songs.take(5).forEachIndexed { index, play -> SongReportRow(c, play, index, play.artist) { open(play, "all-song:${play.id}") } }
                            }
                        }
                        summaryItem("all-artists") {
                            Column(Modifier.padding(horizontal = 17.dp).testTag("all-artists")) {
                                MemoryHeading("常听歌手") { navigate("artists") }
                                Spacer(Modifier.height(15.dp))
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    items(facts.artists.take(10), key = { it.name }) { artist ->
                                        val detail = LocalDetailActions.current
                                        Column(Modifier.width(92.dp).press { detail?.artist?.invoke(artist.name, "all-artist:${artist.name}") ?: navigate("artist:${artist.name}") }, horizontalAlignment = Alignment.CenterHorizontally) {
                                            Artwork(c, "artist:${artist.name}", Modifier.size(68.dp).clip(CircleShape), edge = 160)
                                            Text(artist.name, Modifier.padding(top = 8.dp), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("听了${facts.artistDays(artist.name)}天", Modifier.padding(top = 3.dp), fontSize = 12.sp, color = Muted)
                                        }
                                    }
                                }
                            }
                        }
                        summaryItem("long-term") {
                            Column(Modifier.padding(horizontal = 19.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    SummaryHeading("长期偏爱")
                                    Spacer(Modifier.weight(1f))
                                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                        Text("全部歌手", Modifier.press { navigate("artists") }, color = Accent, fontSize = 13.sp)
                                        Text("全部专辑", Modifier.press { navigate("albums") }, color = Accent, fontSize = 13.sp)
                                    }
                                }
                                facts.artists.firstOrNull()?.let { a ->
                                    SummaryLink(
                                        c,
                                        "artist:${a.name}",
                                        a.name,
                                        "${facts.artistDays(a.name)}天听过 · 占听歌时长${facts.share(a.ms)}% · ${reportHours(a.ms)}小时",
                                        true
                                    ) { navigate("artist:${a.name}") }
                                }
                                facts.albums.firstOrNull()?.let { album ->
                                    SummaryLink(
                                        c,
                                        album.cover.id,
                                        "最常回听的专辑",
                                        album.title + " · " + album.artist + " · " + coverageText(album, c.catalogFacts)
                                    ) { navigate("album:${album.key}") }
                                }
                            }
                        }
                        summaryItem("all-genres") {
                            SummaryPanel {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    SummaryHeading("音乐风格"); Spacer(Modifier.weight(1f))
                                    Text("风格轨迹 ›", Modifier.press { genreViewMode = 1; navigate("genres") }, fontSize = 12.sp, color = Muted)
                                }
                                GenreDistribution(matchedGenres, Modifier.padding(top = 14.dp), maxRows = 8, minShare = 0.0)
                            }
                        }
                        summaryItem("all-habits") {
                            SummaryPanel {
                                SummaryHeading("听歌习惯")
                                Text(facts.peakHourSummary, Modifier.padding(top = 10.dp), fontSize = 13.sp, color = Muted)
                                val longest = facts.sessions.maxOfOrNull { it.ms } ?: 0L
                                Text("最长连续聆听 ${shortDuration(longest)}", Modifier.padding(top = 6.dp), fontSize = 13.sp, color = Muted)
                            }
                        }
                        if (facts.returningSongs.isNotEmpty()) {
                            summaryItem("returning-songs") {
                                SummaryPanel {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        SummaryHeading("一直在回来的歌")
                                        Spacer(Modifier.weight(1f))
                                        Text("全部 ›", Modifier.testTag("all-returning-more").press { navigate("returning") }, fontSize = 13.sp, color = Muted)
                                    }
                                    Text("跨越不同月份反复聆听的常伴旋律", Modifier.padding(top = 4.dp, bottom = 6.dp), fontSize = 12.sp, color = Muted)
                                    facts.returningSongs.take(3).forEachIndexed { i, p ->
                                        SongReportRow(c, p, i, "${p.artist} · 跨${facts.songMonths(p.id).coerceAtLeast(1)}个月听过") { open(p, "returning-song:${p.id}") }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    summaryItem("back") {
                        Row(
                            Modifier.padding(horizontal = 20.dp, vertical = 14.dp).press { back() },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            DiaryGlyph("back", Modifier.size(22.dp), color = Accent)
                            Text("听歌日记", fontSize = 16.sp, color = Accent)
                        }
                    }
                    when (page) {
                        "artists" -> summaryItem {
                            SummaryPanel {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        SummaryHeading(if (artistSortMode == 0) "长期偏爱" else "相遇时间线")
                                        Text(
                                            if (artistSortMode == 0) "按累计聆听时长排序" else "按首次相遇的时间先后排序",
                                            Modifier.padding(top = 4.dp),
                                            fontSize = 12.sp,
                                            color = Muted
                                        )
                                    }
                                    SmallTabs(
                                        labels = listOf("常听", "时间"),
                                        selected = artistSortMode
                                    ) { mode ->
                                        if (artistSortMode != mode) {
                                            artistSortMode = mode
                                            artistDescending = (mode == 0) // 常听默认最多在前，时间默认最早初遇在前(正序)
                                        }
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (artistDescending) "倒序 ⇅" else "正序 ⇅",
                                        Modifier
                                            .clip(CircleShape)
                                            .background(Accent.copy(alpha = .10f))
                                            .press { artistDescending = !artistDescending }
                                            .padding(horizontal = 10.dp, vertical = 5.dp),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Accent
                                    )
                                }
                                Spacer(Modifier.height(6.dp))
                                val orderedArtists = remember(facts.artists, artistSortMode, artistDescending) {
                                    when (artistSortMode) {
                                        0 -> if (artistDescending) facts.artists else facts.artists.asReversed()
                                        else -> {
                                            val byTime = facts.artists.sortedBy { a ->
                                                facts.artistFirstPlay(a.name)?.start ?: Long.MAX_VALUE
                                            }
                                            if (artistDescending) byTime.asReversed() else byTime
                                        }
                                    }
                                }
                                orderedArtists.forEach { a ->
                                    ArtistSummaryRow(c, facts, a.name, showFullEncounterTime = artistSortMode == 1) {
                                        navigate("artist:${a.name}")
                                    }
                                }
                            }
                        }
                        "albums" -> summaryItem {
                            SummaryPanel {
                                SummaryHeading("常听专辑")
                                SummaryAlbumGrid(c, facts.albums, c.catalogFacts) { navigate("album:${it.key}") }
                            }
                        }
                        "songs" -> {
                            summaryItem { Column(Modifier.padding(horizontal = 20.dp)) { SummaryHeading("常听排行"); Text("${facts.songs.size}首歌曲 · 按播放次数", Modifier.padding(top = 6.dp), fontSize = 12.sp, color = Muted) } }
                            itemsIndexed(facts.songs, key = { _, p -> "all-song:${p.id}" }) { index, play ->
                                Box(Modifier.padding(horizontal = 18.dp)) { SongReportRow(c, play, index, play.artist) { open(play, "all-rank:${play.id}") } }
                            }
                        }
                        "returning" -> {
                            summaryItem { Column(Modifier.padding(horizontal = 20.dp)) { SummaryHeading("一直在回来的歌"); Text("按听过的月份和播放次数排序", Modifier.padding(top = 6.dp), fontSize = 12.sp, color = Muted) } }
                            itemsIndexed(returningAll, key = { _, p -> "returning:${p.id}" }) { index, play ->
                                Box(Modifier.padding(horizontal = 18.dp)) { SongReportRow(c, play, index, "${play.artist} · 跨${facts.songMonths(play.id)}个月听过") { open(play, "returning-all:${play.id}") } }
                            }
                        }
                        "trend" -> summaryItem {
                            SummaryPanel {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    SummaryHeading("偏好轨迹")
                                    Spacer(Modifier.weight(1f))
                                    SmallTabs(listOf("歌曲", "专辑", "歌手"), trailType) { trailType = it }
                                }
                                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("每个月听得最多的${listOf("歌曲", "专辑", "歌手")[trailType]}", Modifier.weight(1f), fontSize = 12.sp, color = Muted)
                                    Text(if (trendDescending) "倒序 ⇅" else "正序 ⇅", Modifier.press { trendDescending = !trendDescending }, fontSize = 12.sp, color = Accent)
                                }
                                val entries = trailEntries
                                if (entries == null) Text("正在整理轨迹…", Modifier.padding(top = 16.dp), fontSize = 12.sp, color = Muted)
                                else {
                                    var lastYear = -1
                                    (if (trendDescending) entries.asReversed() else entries).forEach { entry ->
                                        if (entry.month.year != lastYear) { lastYear = entry.month.year; YearSectionBadge(lastYear) }
                                        if (trailType == 2) entry.artist?.let { artist ->
                                            val subtitle="当月听过${entry.artistDays}天 · ${shortDuration(entry.artistMs)}"+(entry.artistTopTrack?.let{" · 《$it》"}?:"")
                                            MonthlyArtistTrailRow(c, entry.month, artist, subtitle) { navigate("artist:$artist") }
                                        } else {
                                            val play = if (trailType == 0) entry.song else entry.album
                                            if (play != null) MonthlyTrailPlayRow(c, entry.month, play, trailType == 1, if (trailType == 0) entry.songMs else entry.albumMs, open)
                                        }
                                    }
                                }
                            }
                        }
                        "genres" -> summaryItem {
                            SummaryPanel {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        SummaryHeading("音乐风格")
                                        Text(
                                            if (genreViewMode == 0) "归纳为主流曲风并按聆听时长统计" else "按月份记录你的主打曲风演变",
                                            Modifier.padding(top = 4.dp),
                                            fontSize = 12.sp,
                                            color = Muted
                                        )
                                    }
                                    SmallTabs(
                                        labels = listOf("占比", "轨迹"),
                                        selected = genreViewMode
                                    ) { genreViewMode = it }
                                }
                                if (matchedGenres.isEmpty()) {
                                    Text("暂无风格数据", Modifier.padding(top = 16.dp), fontSize = 16.sp, color = Muted)
                                } else {
                                    AnimatedContent(
                                        targetState = genreViewMode,
                                        transitionSpec = {
                                            fadeIn(tween(240)) + slideInHorizontally { if (targetState == 1) it / 12 else -it / 12 } togetherWith fadeOut(tween(150))
                                        },
                                        label = "genre view mode"
                                    ) { mode ->
                                        if (mode == 0) {
                                            GenreDistribution(matchedGenres, Modifier.padding(top = 14.dp))
                                        } else {
                                            val colorMap = remember(matchedGenres) {
                                                matchedGenres.mapIndexed { idx, g -> g.name to idx }.toMap()
                                            }
                                            Column(Modifier.padding(top = 10.dp)) {
                                                Row(
                                                    Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    // Legend for top 4 genres
                                                    Row(
                                                        Modifier.weight(1f),
                                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        matchedGenres.take(4).forEachIndexed { idx, g ->
                                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                                Box(Modifier.size(7.dp).background(genreTint(idx, Accent), CircleShape))
                                                                Text(g.name, Modifier.padding(start = 4.dp), fontSize = 11.sp, color = Muted, maxLines = 1)
                                                            }
                                                        }
                                                    }
                                                    Text(
                                                        if (genreTimelineDescending) "倒序 ⇅" else "正序 ⇅",
                                                        Modifier
                                                            .clip(CircleShape)
                                                            .background(Accent.copy(alpha = .10f))
                                                            .press { genreTimelineDescending = !genreTimelineDescending }
                                                            .padding(horizontal = 10.dp, vertical = 4.dp),
                                                        fontSize = 11.sp,
                                                        color = Accent
                                                    )
                                                }
                                                val slices = if (genreTimelineDescending) monthlyGenreTrajectory.asReversed() else monthlyGenreTrajectory
                                                var currentYear = -1
                                                slices.forEach { slice ->
                                                    if (slice.month.year != currentYear) {
                                                        currentYear = slice.month.year
                                                        YearSectionBadge(slice.month.year)
                                                    }
                                                    GenreMonthTimelineRow(
                                                        c = c,
                                                        slice = slice,
                                                        colorMap = colorMap,
                                                        open = open
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        "habits" -> summaryItem {
                            SummaryPanel {
                                SummaryHeading("你的听歌节奏")
                                val bins = facts.hourlyBins
                                val maxBin = (bins.maxOrNull() ?: 1L).coerceAtLeast(1L)
                                SharePlot(
                                    bins.map { it.toFloat() / maxBin },
                                    List(24) { if (it % 6 == 0) "%02d".format(it) else "" }
                                )
                                val sessions = facts.sessions
                                Text("${sessions.size}段聆听", Modifier.padding(top = 18.dp), fontSize = 24.sp)
                                Text(
                                    "平均每段${shortDuration(if (sessions.isEmpty()) 0 else facts.report.ms / sessions.size)} · ${facts.peakHourSummary}",
                                    fontSize = 14.sp,
                                    color = Muted
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun YearSectionBadge(year: Int) {
    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .clip(CircleShape)
                .background(Accent.copy(alpha = .13f))
                .padding(horizontal = 10.dp, vertical = 3.dp)
        ) {
            Text("${year}年", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Accent)
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f).height(1.dp).background(Muted.copy(alpha = .12f)))
    }
}

@Composable private fun MonthlyArtistTrailRow(
    c: DiaryController,
    month: YearMonth,
    artist: String,
    subtitle: String,
    click: () -> Unit
) {
    val detail = LocalDetailActions.current
    val source = remember(month, artist) { "trend:${month}:$artist" }
    Row(
        Modifier
            .fillMaxWidth()
            .press { detail?.artist?.invoke(artist, source) ?: click() }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.width(44.dp), horizontalAlignment = Alignment.Start) {
            Text("${month.monthValue}月", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text("'${(month.year % 100).toString().padStart(2, '0')}", fontSize = 10.sp, color = Muted)
        }
        Artwork(c, "artist:$artist", Modifier.size(46.dp).detailSource(source).clip(CircleShape), edge = 160)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(artist, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, Modifier.padding(top = 2.dp), fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DiaryGlyph("next", Modifier.size(15.dp), color = Muted)
    }
}

@Composable private fun MonthlyTrailPlayRow(c:DiaryController,month:YearMonth,play:Play,album:Boolean,ms:Long,open:(Play,String)->Unit){
    val detail=LocalDetailActions.current
    val source=remember(month,play.id,album){"trail:${month}:${if(album)"album" else "song"}:${play.id}"}
    Row(Modifier.fillMaxWidth().testTag("trail-entry:${month}").press{
        if(album)detail?.album?.invoke(play,source) ?: open(play,source)
        else detail?.track?.invoke(play,source) ?: open(play,source)
    }.padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically){
        Column(Modifier.width(44.dp)) { Text("${month.monthValue}月",fontSize=16.sp,fontWeight=FontWeight.Bold);Text("'${(month.year%100).toString().padStart(2,'0')}",fontSize=10.sp,color=Muted) }
        Artwork(c,play.id,Modifier.size(46.dp).detailSource(source).clip(RoundedCornerShape(10.dp)),edge=160)
        Column(Modifier.weight(1f).padding(horizontal=12.dp)){
            Text(if(album)play.album else play.title,fontSize=16.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)
            Text("${play.artist} · ${shortDuration(ms)}",Modifier.padding(top=2.dp),fontSize=12.sp,color=Muted,maxLines=1,overflow=TextOverflow.Ellipsis)
        }
        DiaryGlyph("next",Modifier.size(15.dp),color=Muted)
    }
}

@Composable private fun GenreMonthTimelineRow(
    c: DiaryController,
    slice: MonthGenreSlice,
    colorMap: Map<String, Int>,
    open: (Play, String) -> Unit
) {
    val detail = LocalDetailActions.current
    val lead = slice.leadPlay
    val source = remember(slice.month, lead?.id) { "genre-timeline:${slice.month}:${lead?.id}" }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Muted.copy(alpha = .045f))
            .then(
                if (lead != null) Modifier.press { detail?.track?.invoke(lead, source) ?: open(lead, source) }
                else Modifier
            )
            .padding(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${slice.month.monthValue}月",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(42.dp)
            )
            val summaryText = slice.topGenres.take(3).joinToString(" · ") { (g, ms) ->
                val pct = ((ms * 100f) / slice.totalMs.coerceAtLeast(1L)).roundToInt().coerceAtLeast(1)
                "$g ${pct}%"
            }
            Text(
                summaryText,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                "${reportHours(slice.totalMs)}h",
                fontSize = 12.sp,
                color = Muted
            )
        }
        // Multi-segment stacked bar showing the genre mix of that month
        Row(
            Modifier
                .padding(top = 8.dp)
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(Muted.copy(alpha = .10f)),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            slice.topGenres.take(3).forEachIndexed { idx, (g, ms) ->
                val weight = (ms.toFloat() / slice.totalMs.coerceAtLeast(1L)).coerceAtLeast(.04f)
                val color = genreTint(colorMap[g] ?: idx, Accent)
                Box(
                    Modifier
                        .weight(weight)
                        .fillMaxHeight()
                        .background(color.copy(alpha = .85f), CircleShape)
                )
            }
        }
        if (lead != null) {
            Row(
                Modifier.fillMaxWidth().padding(top = 9.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Artwork(c, lead.id, Modifier.size(30.dp).detailSource(source).clip(RoundedCornerShape(7.dp)), edge = 160)
                Text(
                    "主打曲目：${lead.title} · ${lead.artist}",
                    Modifier.padding(start = 9.dp).weight(1f),
                    fontSize = 12.sp,
                    color = Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable private fun SummaryPanel(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Muted.copy(alpha = .045f))
            .padding(17.dp)
            .animateContentSize(spring(.95f, 280f)),
        content = content
    )
}

@Composable private fun SummaryHeading(title: String) {
    Text(title, fontSize = 23.sp, fontWeight = FontWeight.Bold)
}

@Composable private fun SummaryLink(
    c: DiaryController,
    id: String?,
    title: String,
    subtitle: String,
    circle: Boolean = false,
    click: () -> Unit
) {
    val detail = LocalDetailActions.current
    val source = rememberSaveable { java.util.UUID.randomUUID().toString() }
    Row(
        Modifier
            .fillMaxWidth()
            .testTag("summary-link:$title")
            .clip(RoundedCornerShape(20.dp))
            .background(Muted.copy(alpha = .05f))
            .press {
                if (id == null) click()
                else if (id.startsWith("artist:")) detail?.artist?.invoke(id.removePrefix("artist:"), source)
                else c.summaryFacts.report.history.firstOrNull { it.id == id }?.let { detail?.album?.invoke(it, source) }
            }
            .padding(13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (id != null) {
            Artwork(
                c,
                id,
                Modifier
                    .size(53.dp)
                    .detailSource(source)
                    .clip(if (circle) CircleShape else RoundedCornerShape(11.dp)),
                edge = 160
            )
        } else {
            Box(
                Modifier
                    .size(53.dp)
                    .background(Accent.copy(alpha = .13f), RoundedCornerShape(13.dp)),
                contentAlignment = Alignment.Center
            ) {
                SummaryCategoryIcon(title == "听歌节奏")
            }
        }
        Column(Modifier.weight(1f).padding(horizontal = 13.dp)) {
            Text(title, fontSize = 17.sp)
            Text(subtitle, Modifier.padding(top = 3.dp), fontSize = 12.sp, color = Muted, maxLines = 2)
        }
        DiaryGlyph("next", Modifier.size(16.dp), color = Muted)
    }
}

private fun formatPreferenceBandLabel(band: PreferenceBand): String {
    val fy = (band.from.year % 100).toString().padStart(2, '0')
    val ty = (band.to.year % 100).toString().padStart(2, '0')
    return when {
        band.from == band.to -> "${fy}年${band.from.monthValue}月"
        band.from.year == band.to.year -> "${fy}年${band.from.monthValue}—${band.to.monthValue}月"
        else -> "${fy}.${band.from.monthValue}—${ty}.${band.to.monthValue}"
    }
}

@Composable private fun PreferenceTrail(c: DiaryController, facts: SummaryFacts, click: () -> Unit) {
    val detail = LocalDetailActions.current
    val bands = remember(facts) { facts.bands() }
    Column(Modifier.padding(horizontal = 20.dp).press(click)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("偏好轨迹", Modifier.weight(1f), fontSize = 24.sp)
            Text("探索", fontSize = 13.sp, color = Muted)
            DiaryGlyph("next", Modifier.size(15.dp), color = Muted)
        }
        Row(Modifier.padding(top = 17.dp)) {
            bands.forEach { band ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        formatPreferenceBandLabel(band),
                        fontSize = 12.sp,
                        color = Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        band.artists.forEach { a ->
                            val source = "preference:${band.from}:${a.name}"
                            Artwork(
                                c,
                                "artist:${a.name}",
                                Modifier
                                    .size(30.dp)
                                    .detailSource(source)
                                    .clip(CircleShape)
                                    .press { detail?.artist?.invoke(a.name, source) },
                                edge = 160
                            )
                        }
                    }
                    Text(
                        band.caption,
                        Modifier.padding(top = 6.dp).padding(horizontal = 4.dp),
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        Box(Modifier.padding(top = 14.dp).fillMaxWidth().height(1.dp).background(Accent.copy(alpha = .22f)))
    }
}

@Composable private fun ArtistSummaryRow(
    c: DiaryController,
    facts: SummaryFacts,
    name: String,
    showFullEncounterTime: Boolean = false,
    click: () -> Unit
) {
    val a = facts.artists.find { it.name == name } ?: return
    val detail = LocalDetailActions.current
    val source = remember(name) { "summary-artist-row:$name" }
    val firstPlay = remember(facts, name) { facts.artistFirstPlay(name) }
    val subtitle = remember(facts, a, firstPlay, showFullEncounterTime, c.use12Hour) {
        if (firstPlay == null) {
            "${facts.artistDays(name)}天 · ${reportHours(a.ms)}小时"
        } else {
            val zone = runCatching { ZoneId.of(firstPlay.zone) }.getOrDefault(ZoneId.systemDefault())
            val timeStr = Instant.ofEpochMilli(firstPlay.start).atZone(zone).format(
                DateTimeFormatter.ofPattern(
                    if (showFullEncounterTime) {
                        if (c.use12Hour) "yyyy年M月d日 a h:mm" else "yyyy年M月d日 HH:mm"
                    } else {
                        "yyyy.MM.dd"
                    },
                    java.util.Locale.CHINA
                )
            )
            val songTitle = if (firstPlay.imported && firstPlay.title == "未知歌曲") "" else "《${firstPlay.title}》"
            if (showFullEncounterTime) {
                "$timeStr 初遇$songTitle · ${facts.artistDays(name)}天 · ${reportHours(a.ms)}h"
            } else {
                "${facts.artistDays(name)}天 · ${reportHours(a.ms)}小时 · 初遇 $timeStr $songTitle".trim()
            }
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .press { detail?.artist?.invoke(name, source) ?: click() }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Artwork(c, "artist:$name", Modifier.size(49.dp).detailSource(source).clip(CircleShape), edge = 160)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, Modifier.padding(top = 2.dp), fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DiaryGlyph("next", Modifier.size(15.dp), color = Muted)
    }
}

private fun coverageText(album: AlbumStat, catalog: Map<String, CatalogFact>): String =
    albumCoverage(album, catalog)?.let { "已听${it.first}/${it.second}首" } ?: "已听${album.songs.map { it.id }.distinct().size}首"

@Composable private fun Coverage(album: AlbumStat, catalog: Map<String, CatalogFact>) {
    Text(coverageText(album, catalog), Modifier.padding(top = 9.dp), fontSize = 14.sp, color = Muted)
    albumCoverage(album, catalog)?.let { (n, total) ->
        val level by animateFloatAsState(n.toFloat() / total, tween(550), label = "album coverage")
        Box(
            Modifier
                .padding(top = 9.dp)
                .fillMaxWidth()
                .height(7.dp)
                .clip(CircleShape)
                .background(Muted.copy(alpha = .12f))
        ) {
            Box(Modifier.fillMaxWidth(level).fillMaxHeight().background(Accent, CircleShape))
        }
    }
}

@Composable private fun SummaryAlbumGrid(
    c: DiaryController,
    albums: List<AlbumStat>,
    catalog: Map<String, CatalogFact>,
    click: (AlbumStat) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        albums.chunked(2).forEach { pair ->
            Row(Modifier.padding(top = 13.dp), horizontalArrangement = Arrangement.spacedBy(17.dp)) {
                pair.forEach { a ->
                    val detail = LocalDetailActions.current
                    val source = rememberSaveable(a.key) { java.util.UUID.randomUUID().toString() }
                    Column(
                        Modifier
                            .weight(1f)
                            .testTag("summary-album:${a.key}")
                            .press { detail?.album?.invoke(a.cover, source) ?: click(a) }
                    ) {
                        Artwork(c, a.cover.id, Modifier.fillMaxWidth().aspectRatio(1f).detailSource(source).clip(RoundedCornerShape(16.dp)))
                        Text(a.title, Modifier.padding(top = 7.dp), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Coverage(a, catalog)
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

internal fun buildArtistGenreFallback(songs: List<Play>, catalog: Map<String, CatalogFact>): Map<String, String> {
    val map = mutableMapOf<String, String>()
    songs.forEach { play ->
        val g = catalog[play.id]?.genre?.takeIf { it.isNotBlank() }
        if (g != null && play.artist.isNotBlank() && play.artist !in map) {
            map[play.artist] = g
        }
    }
    return map
}

private fun mainstreamGenreStats(facts: SummaryFacts, catalog: Map<String, CatalogFact>): List<GenreStat> =
    mainstreamGenreStats(facts.songs, catalog)

/** The daily and lifetime views deliberately share one categorization and artist fallback path. */
internal fun mainstreamGenreStats(songs: List<Play>, catalog: Map<String, CatalogFact>, artistContext: List<Play> = songs): List<GenreStat> {
    val artistFallback = buildArtistGenreFallback(artistContext, catalog)
    return songs
        .filter { !it.imported || it.title != "未知歌曲" }
        .groupBy { song ->
            normalizeMusicGenre(catalog[song.id]?.genre, song, artistFallback[song.artist])
        }
        .map { (genreName, tracks) ->
            val topArtists = tracks
                .filter { it.artist.isNotBlank() && it.artist != "未知歌手" }
                .groupBy { it.artist }
                .entries
                .sortedByDescending { (_, list) -> list.sumOf { it.ms } }
                .take(3)
                .map { it.key }
            GenreStat(
                name = genreName,
                ms = tracks.sumOf { it.ms },
                topArtists = topArtists,
                trackCount = tracks.map { it.id }.distinct().size
            )
        }
        .sortedByDescending { it.ms }
}

private fun monthlyGenreSlices(facts: SummaryFacts, catalog: Map<String, CatalogFact>): List<MonthGenreSlice> {
    val artistFallback = buildArtistGenreFallback(facts.songs, catalog)
    return facts.months.entries.mapNotNull { (month, rows) ->
        val valid = rows.map { it.play }.filter { !it.imported || it.title != "未知歌曲" }
        val totalMs = valid.sumOf { it.ms }
        if (totalMs <= 0L) return@mapNotNull null
        val byGenre = valid.groupBy { play ->
            normalizeMusicGenre(catalog[play.id]?.genre, play, artistFallback[play.artist])
        }.filterKeys { it != "未匹配" }
        if (byGenre.isEmpty()) return@mapNotNull null
        val rankedGenres = byGenre.map { (g, list) -> g to list.sumOf { it.ms } }
            .sortedByDescending { it.second }
        val topGenrePlays = byGenre[rankedGenres.first().first].orEmpty()
        val leadPlay = topGenrePlays
            .groupBy { it.id }
            .maxByOrNull { (_, v) -> v.sumOf { it.ms } }
            ?.value?.firstOrNull()
        MonthGenreSlice(
            month = month,
            totalMs = rankedGenres.sumOf { it.second }.coerceAtLeast(1L),
            topGenres = rankedGenres,
            leadPlay = leadPlay
        )
    }
}

@Composable private fun SharePlot(values: List<Float>, labels: List<String>, percentage: Boolean = false) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val ceiling = if (percentage) (ceil((values.maxOrNull() ?: 0f) * 10) / 10).coerceAtLeast(.1f) else 1f
    Column(Modifier.fillMaxWidth().padding(top = 17.dp)) {
        if (percentage) Text("${(ceiling * 100).roundToInt()}%", fontSize = 10.sp, color = Muted)
        Row(
            Modifier.fillMaxWidth().height(100.dp),
            horizontalArrangement = Arrangement.spacedBy(if (values.size > 20) 4.dp else 7.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            values.forEachIndexed { i, v ->
                val normalized = (v / ceiling).coerceIn(0f, 1f)
                val h by animateFloatAsState(if (shown) normalized else 0f, tween(500, i * 8), label = "summary plot $i")
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(h.coerceAtLeast(.012f))
                        .clip(RoundedCornerShape(5.dp))
                        .background(Accent.copy(alpha = if (v > 0) .25f + .65f * normalized else .10f))
                )
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 7.dp).height(17.dp)) {
            val cell = maxWidth / labels.size.coerceAtLeast(1)
            val labelWidth = cell.coerceAtLeast(24.dp)
            labels.forEachIndexed { i, label ->
                if (label.isNotEmpty()) {
                    Text(
                        label,
                        Modifier.offset(x = cell * (i + .5f) - labelWidth / 2).width(labelWidth),
                        fontSize = 10.sp,
                        color = Muted,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

private fun LazyListScope.summaryItem(key: Any? = null, content: @Composable () -> Unit) {
    item(key = key) {
        Box(
            Modifier
                .fillMaxWidth()
                .animateItem(
                    fadeInSpec = tween(220),
                    fadeOutSpec = tween(140),
                    placementSpec = spring(.95f, 300f)
                )
        ) {
            content()
        }
    }
}

@Composable private fun SummaryCategoryIcon(rhythm: Boolean) {
    val accent = Accent
    Canvas(Modifier.size(28.dp)) {
        if (rhythm) {
            val levels = listOf(.45f, .75f, 1f, .62f, .38f)
            levels.forEachIndexed { i, v ->
                val width = size.width * .12f
                val h = size.height * v
                drawRoundRect(
                    accent,
                    Offset(size.width * (.05f + i * .19f), size.height - h),
                    androidx.compose.ui.geometry.Size(width, h),
                    androidx.compose.ui.geometry.CornerRadius(width / 2)
                )
            }
        } else {
            drawCircle(Color(0xff8a9eff).copy(alpha = .66f), size.width * .30f, Offset(size.width * .35f, size.height * .35f))
            drawCircle(accent.copy(alpha = .5f), size.width * .30f, Offset(size.width * .64f, size.height * .38f))
            drawCircle(Color(0xff92a5e4).copy(alpha = .6f), size.width * .30f, Offset(size.width * .53f, size.height * .67f))
        }
    }
}
