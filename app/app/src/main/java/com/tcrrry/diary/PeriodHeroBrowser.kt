@file:OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
package com.tcrrry.diary

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

internal val PeriodHeroPositionKey = SemanticsPropertyKey<Float>("PeriodHeroPosition")
internal val PeriodMotionChoiceKey = SemanticsPropertyKey<String>("PeriodMotionChoice")
internal val PeriodArtworkOverlayKey = SemanticsPropertyKey<Boolean>("PeriodArtworkOverlay")
internal val PeriodCoverMotionKey = SemanticsPropertyKey<Int>("PeriodCoverMotionKey")
internal val PeriodArtistMotionKey = SemanticsPropertyKey<Int>("PeriodArtistMotionKey")
internal val YearArtLayoutKey = SemanticsPropertyKey<YearArtLayout>("YearArtLayout")

/** Randomize independently for each layout and media type, but stay stable for the whole transition. */
internal fun periodMotionSlot(seed: Int, range: DiaryRange, artist: Boolean, count: Int): Int =
    if (count <= 0) -1 else kotlin.random.Random(seed xor (range.ordinal * 0x1f123bb5) xor (if (artist) 0x7238a1 else 0x1522f3)).nextInt(count)

/** Each visible item gets one unique shared key; shuffling pairs positions across periods. */
internal fun periodMotionKeys(seed: Int, range: DiaryRange, artist: Boolean, count: Int): List<Int> =
    (0 until count.coerceAtLeast(0)).shuffled(kotlin.random.Random(seed xor (range.ordinal * 0x1f123bb5) xor (if (artist) 0x7238a1 else 0x1522f3)))

/** Only shuffle items already displayed, leaving the underlying rankings untouched. */
internal fun <T> periodLayoutItems(items: List<T>, limit: Int, seed: Int): List<T> =
    items.take(limit).shuffled(kotlin.random.Random(seed))

internal fun periodLayoutSeed(seed: Int, key: String, artist: Boolean = false): Int =
    seed xor key.hashCode() xor (if (artist) 0x216491 else 0x453d21)

internal fun monthMosaicStyle(seed: Int, slice: PeriodHeroSlice): Int {
    val requested = kotlin.random.Random(periodLayoutSeed(seed, slice.key) xor 0x4ab11).nextInt(1, 4)
    return when {
        requested >= 3 && slice.artists.size >= 3 && slice.albums.size >= 3 -> 3
        requested >= 2 && slice.artists.size >= 2 && slice.albums.size >= 2 -> 2
        else -> 1
    }
}

internal data class MonthArtGeometry(val portraitFirst: Boolean, val smallFirst: Boolean, val albumWidthFraction: Float)
internal fun randomizedMonthArtGeometry(seed: Int, key: String): MonthArtGeometry {
    val random = kotlin.random.Random(periodLayoutSeed(seed, key, true))
    return MonthArtGeometry(random.nextBoolean(), random.nextBoolean(), .50f + random.nextFloat() * .09f)
}

/** One pager per calendar period. Artwork stays mounted while an adjacent interval slides into view. */
@Composable internal fun PeriodHeroBrowser(
    c: DiaryController,
    slices: List<PeriodHeroSlice>,
    range: DiaryRange,
    pager: PagerState,
    onTint: (List<Color>) -> Unit,
    open: (Play, String) -> Unit,
    allowAuto: Boolean = false,
    artistOpen: (String) -> Unit = {},
    shared: SharedTransitionScope? = null,
    periodVisibility: AnimatedVisibilityScope? = null,
    summaryVisibility: AnimatedVisibilityScope? = null
) {
    if (slices.isEmpty()) return
    val scope = rememberCoroutineScope()
    val rail = rememberLazyListState()
    val view = LocalView.current
    val inspection = LocalInspectionMode.current
    val active by remember(pager, slices.size) { derivedStateOf { heroLogicalIndex(pager.currentPage, slices.size) } }
    val settled by remember(pager, slices.size) { derivedStateOf { heroLogicalIndex(pager.settledPage, slices.size) } }
    LaunchedEffect(settled) { rail.animateScrollToItem((settled - 1).coerceAtLeast(0)) }
    val autoLast = slices.indexOfLast { !it.from.isAfter(java.time.LocalDate.now()) }.coerceAtLeast(0)
    LaunchedEffect(pager.settledPage, pager.isScrollInProgress, slices.size) {
        if (slices.size > 1 && !pager.isScrollInProgress) {
            if (pager.settledPage == 0) pager.scrollToPage(slices.size)
            else if (pager.settledPage == slices.size + 1) pager.scrollToPage(1)
        }
    }

    // Keep Android 14-16 VRR / OriginOS display locked to 120Hz while the pager is actively animating
    LaunchedEffect(pager.isScrollInProgress, inspection) {
        if (pager.isScrollInProgress && !inspection) {
            while (isActive && pager.isScrollInProgress) {
                boostViewFrameRate(view)
                withFrameNanos { }
            }
        }
    }

    // Non-state holder so launching auto-scroll never triggers a recomposition on frame 1 of the animation
    val autoJob = remember { arrayOfNulls<Job>(1) }
    var touching by remember { mutableStateOf(false) }
    var touchEpoch by remember { mutableIntStateOf(0) }
    LaunchedEffect(allowAuto) { if (!allowAuto) autoJob[0]?.cancel() }
    DisposableEffect(Unit) { onDispose { autoJob[0]?.cancel() } }
    LaunchedEffect(slices.map { it.key }) { autoJob[0]?.cancel() }

    val destinationSeed = c.periodLayoutSeed(range)
    val layoutSeed = remember(c.day, range, destinationSeed) { destinationSeed xor c.day.hashCode() }
    // Build geometry only for composed/current pages; do not lay out the whole year on entry.
    val yearLayouts = remember(layoutSeed) { mutableMapOf<Triple<String, Int, Int>, YearArtLayout>() }
    fun layoutFor(slice: PeriodHeroSlice): YearArtLayout {
        val count = slice.albums.sumOf { it.songs.size }
        val key = Triple(slice.key, count, slice.artists.size)
        return yearLayouts.getOrPut(key) { randomizedYearArtLayout(periodLayoutSeed(layoutSeed, slice.key), count, slice.artists.size) }
    }

    LaunchedEffect(allowAuto, pager.settledPage, pager.isScrollInProgress, slices, touching, touchEpoch) {
        if (allowAuto && !touching && !pager.isScrollInProgress && autoLast > 0) {
            // Pre-warm the next slice's artwork bitmaps while idle so frame 1 of auto-scroll never stalls
            if (!inspection && slices.size > 1) {
                val nextIdx = heroLogicalIndex(pager.settledPage + 1, slices.size)
                val nextSlice = slices.getOrNull(nextIdx)
                if (nextSlice != null) {
                    withContext(Dispatchers.Default) {
                        val ids = buildList {
                            nextSlice.albums.take(4).forEach { add(it.cover.id) }
                            nextSlice.albums.flatMap { it.songs }.take(6).forEach { add(it.id) }
                            if (c.artistPhotosEnabled) nextSlice.artists.take(3).forEach { add("artist:${it.name}") }
                        }.distinct()
                        c.images.warmCached(ids, 720)
                    }
                }
            }
            delay(5500)
            if (!pager.isScrollInProgress) {
                if (!inspection) boostViewFrameRate(view)
                autoJob[0] = scope.launch {
                    pager.animateScrollToPage(
                        (pager.settledPage + 1).coerceAtMost(pager.pageCount - 1),
                        animationSpec = tween(560, easing = FastOutSlowInEasing)
                    )
                }
            }
        }
    }

    val ink = LocalContentColor.current
    val tintEnabled = c.range == range && !c.summary
    // Use settled slice for ambient backdrop tint so the heavy full-screen blur shader does not re-run mid-scroll
    val tintSlice = slices[settled]
    val motionSlice = slices[settled]
    val yearLayout = if (range == DiaryRange.YEAR) layoutFor(motionSlice) else YearArtLayout(emptyList(), emptyList())
    val monthStyle = monthMosaicStyle(layoutSeed, motionSlice)
    val coverCount = when (range) {
        DiaryRange.WEEK -> minOf(1, motionSlice.albums.size)
        DiaryRange.MONTH -> minOf(if (monthStyle == 2) 2 else 3, motionSlice.albums.size)
        else -> minOf(yearLayout.songs.size, motionSlice.albums.sumOf { it.songs.size })
    }
    val artistCount = minOf(
        when (range) {
            DiaryRange.YEAR -> yearLayout.artists.size
            DiaryRange.MONTH -> monthStyle
            else -> 1
        },
        motionSlice.artists.size
    )
    val coverKeys = remember(c.periodMotionSeed, range, coverCount) { periodMotionKeys(c.periodMotionSeed, range, false, coverCount) }
    val artistKeys = remember(c.periodMotionSeed, range, artistCount) { periodMotionKeys(c.periodMotionSeed, range, true, artistCount) }
    val tintId = remember(range, tintSlice.key, tintEnabled, c.artistPhotosEnabled) {
        if (!tintEnabled) null else {
            val covers = when (range) {
                DiaryRange.WEEK -> tintSlice.albums.take(1).map { it.cover.id }
                DiaryRange.MONTH -> periodLayoutItems(
                    tintSlice.albums,
                    if (monthMosaicStyle(layoutSeed, tintSlice) == 2) 2 else 3,
                    periodLayoutSeed(layoutSeed, tintSlice.key)
                ).map { it.cover.id }
                else -> periodLayoutItems(
                    tintSlice.albums.flatMap { it.songs }.sortedWith(compareByDescending<Play> { it.ms }.thenBy { it.id }),
                    layoutFor(tintSlice).songs.size,
                    periodLayoutSeed(layoutSeed, tintSlice.key)
                ).map { it.id }
            }
            val portraits = if (c.artistPhotosEnabled) {
                (if (range == DiaryRange.YEAR) periodLayoutItems(tintSlice.artists, layoutFor(tintSlice).artists.size, periodLayoutSeed(layoutSeed, tintSlice.key, true))
                else if (range == DiaryRange.MONTH) periodLayoutItems(tintSlice.artists, monthMosaicStyle(layoutSeed, tintSlice), periodLayoutSeed(layoutSeed, tintSlice.key, true))
                else tintSlice.artists.take(1)).map { "artist:${it.name}" }
            } else emptyList()
            val candidates = (covers + portraits).distinct().filter { c.images.peek(it, 720) != null }
            val choices = candidates.filter { it != c.lastPeriodTintId }.ifEmpty { candidates }
            (choices.randomOrNull() ?: tintSlice.lead?.id)?.also { c.lastPeriodTintId = it }
        }
    }
    LaunchedEffect(tintId, tintEnabled) { if (tintEnabled && tintId == null) onTint(listOf(Color(0xff62697c), Color(0xff62697c))) }
    if (tintEnabled && tintId != null) Artwork(c, tintId, Modifier.size(0.dp), onTint = onTint)

    Column(
        Modifier
            .fillMaxWidth()
            .testTag("period-hero")
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val held = event.changes.any { it.pressed }
                        if (held != touching) {
                            touching = held
                            touchEpoch++
                            autoJob[0]?.cancel()
                        }
                    }
                }
            }
            .semantics {
                stateDescription = slices[settled].key
                this[PeriodMotionChoiceKey] = "${coverKeys.joinToString(":")},${artistKeys.joinToString(":")}"
            }
    ) {
        LazyRow(
            state = rail,
            modifier = Modifier.fillMaxWidth().testTag("period-hero-dates"),
            contentPadding = PaddingValues(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally)
        ) {
            itemsIndexed(slices, key = { _, slice -> slice.key }) { index, slice ->
                val selected = index == active
                Column(
                    Modifier
                        .press {
                            if (!inspection) boostViewFrameRate(view)
                            scope.launch { pager.animateScrollToPage(heroRealPage(index, slices.size), animationSpec = spring(.9f, 240f)) }
                        }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(heroSliceLabel(slice, range), fontSize = if (range == DiaryRange.YEAR) 17.sp else 14.sp, color = if (selected) ink else Muted)
                    val width by animateDpAsState(if (selected) 22.dp else 0.dp, spring(.85f, 280f), label = "interval underline")
                    Box(Modifier.padding(top = 4.dp).width(width).height(2.5.dp).background(Accent, CircleShape))
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val pageWidth = maxWidth - 42.dp
            val typeScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
            val cardHeight = periodHeroCardHeight(pageWidth, range, typeScale)
            HorizontalPager(
                state = pager,
                pageSize = PageSize.Fixed(pageWidth),
                contentPadding = PaddingValues(horizontal = (maxWidth - pageWidth) / 2),
                pageSpacing = 14.dp,
                beyondViewportPageCount = 1,
                key = { page ->
                    if (slices.size > 1 && (page == 0 || page == slices.size + 1)) "sentinel:$page"
                    else periodPagerKey(slices, heroLogicalIndex(page, slices.size))
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(cardHeight)
                    .testTag("period-hero-pager")
                    .semantics { this[PeriodHeroPositionKey] = pager.currentPage + pager.currentPageOffsetFraction }
            ) { index ->
                val logicalIndex = heroLogicalIndex(index, slices.size)
                val slice = slices.getOrNull(logicalIndex) ?: return@HorizontalPager
                val isPrimaryPage = index == pager.settledPage || (slices.size > 1 && pager.settledPage == slices.size + 1 && index == 1) || (slices.size > 1 && pager.settledPage == 0 && index == slices.size)
                Column(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val distance = abs(pager.currentPage - index + pager.currentPageOffsetFraction).coerceIn(0f, 1f)
                            scaleX = 1f - distance * .045f
                            scaleY = scaleX
                            alpha = 1f - distance * .22f
                            translationY = distance * 3.dp.toPx()
                        }
                ) {
                    if (slice.lead == null) EmptyInterval(cardHeight)
                    else if (range == DiaryRange.WEEK) DayAlbumCards(c, slice, isPrimaryPage, onTint, open, artistOpen, shared, periodVisibility, summaryVisibility, tintEnabled)
                    else if (range == DiaryRange.YEAR) YearFreeform(c, slice, isPrimaryPage, onTint, open, artistOpen, shared, periodVisibility, summaryVisibility, tintEnabled, coverKeys, artistKeys, layoutSeed, layoutFor(slice))
                    else MonthRectangularMosaic(c, slice, isPrimaryPage, onTint, open, artistOpen, shared, periodVisibility, summaryVisibility, tintEnabled, coverKeys, artistKeys, layoutSeed)
                }
            }
        }
    }
}

private fun periodHeroCardHeight(pageWidth: Dp, range: DiaryRange, typeScale: Float): Dp = when (range) {
    DiaryRange.WEEK -> (pageWidth - 12.dp) / 2 + 58.dp * typeScale
    DiaryRange.YEAR -> pageWidth * 1.035f + 28.dp * (typeScale - 1f)
    else -> ((pageWidth - 8.dp) * .55f) * 1.5f + 8.dp
}

/** Keep the stats at their destination while the report and artwork are being prepared. */
@Composable internal fun PeriodHeroPlaceholder(range: DiaryRange) {
    Column(Modifier.fillMaxWidth().testTag("period-hero-placeholder")) {
        Column(Modifier.padding(vertical = 8.dp).alpha(0f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (range == DiaryRange.YEAR) "10月" else "周一", fontSize = if (range == DiaryRange.YEAR) 17.sp else 14.sp)
            Box(Modifier.padding(top = 4.dp).width(22.dp).height(2.5.dp))
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val scale = LocalDensity.current.fontScale.coerceAtLeast(1f)
            Spacer(Modifier.height(periodHeroCardHeight(maxWidth - 42.dp, range, scale)))
        }
    }
}

/** Week: two independent daily cards; the top artist is never presented as the album's artist. */
@Composable private fun DayAlbumCards(
    c: DiaryController,
    slice: PeriodHeroSlice,
    active: Boolean,
    tint: (List<Color>) -> Unit,
    open: (Play, String) -> Unit,
    artistOpen: (String) -> Unit,
    shared: SharedTransitionScope?,
    periodVisibility: AnimatedVisibilityScope?,
    summaryVisibility: AnimatedVisibilityScope?,
    tintEnabled: Boolean
) {
    val album = slice.albums.first()
    val artist = slice.artists.firstOrNull()
    val typeScale = LocalDensity.current.fontScale.coerceAtLeast(1f)
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("period-hero-layout-week")) {
        val width = (maxWidth - 12.dp) / 2
        val image = width - 16.dp
        val height = image + 66.dp * typeScale
        val shape = RoundedCornerShape(20.dp)
        val surface = Muted.copy(alpha = .035f)
        val outline = Muted.copy(alpha = .08f)
        Row(Modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(
                Modifier
                    .size(width, height)
                    .testTag("week-album-card:${slice.key}")
                    .clip(shape)
                    .background(surface)
                    .border(.5.dp, outline, shape)
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                HeroAlbum(c, album, Modifier.size(image).summaryArtworkMotion(shared, summaryVisibility, active, if(c.range == DiaryRange.WEEK)"summary-cover:0"else "inactive-week-summary-cover:0", RoundedCornerShape(17.dp)), active, tint, slice.key, open, shared, periodVisibility, summaryVisibility, tintActive = false)
                Text(album.title, Modifier.fillMaxWidth().detailCaptionFade().testTag("week-album-title:${slice.key}").padding(top = 8.dp), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                Text(album.artist, Modifier.detailCaptionFade(), fontSize = 13.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (artist != null) Column(
                Modifier
                    .size(width, height)
                    .testTag("week-artist-card:${slice.key}")
                    .clip(shape)
                    .background(surface)
                    .border(.5.dp, outline, shape)
                    .press { artistOpen(artist.name) }
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Artwork(
                    c,
                    "artist:${artist.name}",
                    Modifier
                        .size(image)
                        .periodArtistMotion(shared, periodVisibility, active)
                        .detailSource("period-artist:WEEK:${artist.name}", active, CircleShape)
                        .clip(CircleShape),
                    edge = 720
                )
                Text(artist.name, Modifier.fillMaxWidth().detailCaptionFade().padding(top = 8.dp), fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Month: keep the same balanced grid while varying the visible order and side. */
@Composable private fun MonthRectangularMosaic(
    c: DiaryController,
    slice: PeriodHeroSlice,
    active: Boolean,
    tint: (List<Color>) -> Unit,
    open: (Play, String) -> Unit,
    artistOpen: (String) -> Unit,
    shared: SharedTransitionScope?,
    periodVisibility: AnimatedVisibilityScope?,
    summaryVisibility: AnimatedVisibilityScope?,
    tintEnabled: Boolean,
    coverKeys: List<Int>,
    artistKeys: List<Int>,
    layoutSeed: Int
) {
    val style = remember(slice, layoutSeed) { monthMosaicStyle(layoutSeed, slice) }
    val albums = remember(slice, layoutSeed, style) { periodLayoutItems(slice.albums, if (style == 2) 2 else 3, periodLayoutSeed(layoutSeed, slice.key)) }
    val artists = remember(slice, layoutSeed, style) { periodLayoutItems(slice.artists, style, periodLayoutSeed(layoutSeed, slice.key, true)) }
    if (style > 1) {
        MultiArtistMonth(c, slice, albums, artists, active, open, artistOpen, shared, periodVisibility, coverKeys, artistKeys, layoutSeed, summaryVisibility)
        return
    }
    val artist = artists.firstOrNull()
    val geometry = remember(slice.key, layoutSeed) { randomizedMonthArtGeometry(layoutSeed, slice.key) }
    val portraitFirst = geometry.portraitFirst
    val smallFirst = geometry.smallFirst
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("period-hero-layout-month")) {
        val gap = 8.dp
        val right = (maxWidth - gap) * geometry.albumWidthFraction
        val height = periodHeroCardHeight(maxWidth, DiaryRange.MONTH, 1f)
        val small = minOf((right - gap) / 2, height - right - gap)
        val left = maxWidth - right - gap
        @Composable fun Portrait() {
            Box(
                Modifier
                    .size(left, height)
                    .periodArtistMotion(shared, periodVisibility, active && artist != null, RoundedCornerShape(18.dp), artistKeys.firstOrNull() ?: 0)
                    .testTag("month-portrait:${slice.key}")
                    .semantics { if (artist != null) contentDescription = artist.name }
                    .clip(RoundedCornerShape(18.dp))
                    .then(if (artist != null) Modifier.press { artistOpen(artist.name) } else Modifier)
            ) {
                if (artist != null) {
                    Artwork(c, "artist:${artist.name}", Modifier.fillMaxSize().detailSource("period-artist:MONTH:${artist.name}", active, RoundedCornerShape(18.dp)), contentScale = ContentScale.Crop)
                    TileName(artist.name, "", Modifier.align(Alignment.BottomCenter))
                } else {
                    HeroAlbum(c, albums.first(), Modifier.size(left).align(Alignment.Center), false, tint, slice.key, open, shared, periodVisibility, summaryVisibility)
                }
            }
        }
        @Composable fun AlbumTiles() {
            Column(Modifier.width(right).height(height).testTag("month-album-grid:${slice.key}"), verticalArrangement = Arrangement.spacedBy(gap, Alignment.CenterVertically)) {
                @Composable fun MainAlbum() {
                    Box(
                        Modifier
                            .size(right)
                            .testTag("month-main-tile:${slice.key}")
                            .summaryArtworkMotion(shared, summaryVisibility, active && c.range == DiaryRange.MONTH, "summary-cover:0", RoundedCornerShape(17.dp))
                            .periodCoverMotion(shared, periodVisibility, active, RoundedCornerShape(17.dp), coverKeys.getOrElse(0) { 0 })
                            .clip(RoundedCornerShape(15.dp))
                    ) {
                        HeroAlbum(c, albums.first(), Modifier.fillMaxSize(), false, tint, slice.key, open, shared, periodVisibility, summaryVisibility, tintActive = false, detailActive = active)
                        TileName(albums.first().title, albums.first().artist, Modifier.align(Alignment.BottomCenter))
                    }
                }
                @Composable fun SmallAlbums() {
                    Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                        repeat(2) { index ->
                            albums.getOrNull(index + 1)?.let {
                                HeroAlbum(c, it, Modifier.size(small), active, tint, slice.key, open, shared, periodVisibility, summaryVisibility, motionKey = coverKeys.getOrElse(index + 1) { 0 })
                            } ?: Spacer(Modifier.size(small))
                        }
                    }
                }
                if (smallFirst) {
                    SmallAlbums()
                    MainAlbum()
                } else {
                    MainAlbum()
                    SmallAlbums()
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.spacedBy(gap)) {
            if (portraitFirst) {
                Portrait()
                AlbumTiles()
            } else {
                AlbumTiles()
                Portrait()
            }
        }
    }
}

/** Aligned rectangular mosaic: a full-height tile beside a mixed-size grid. */
@Composable private fun MultiArtistMonth(
    c: DiaryController,
    slice: PeriodHeroSlice,
    albums: List<AlbumStat>,
    artists: List<ArtistStat>,
    active: Boolean,
    open: (Play, String) -> Unit,
    artistOpen: (String) -> Unit,
    shared: SharedTransitionScope?,
    visibility: AnimatedVisibilityScope?,
    coverKeys: List<Int>,
    artistKeys: List<Int>,
    layoutSeed: Int,
    summaryVisibility: AnimatedVisibilityScope?
) {
    val order = remember(slice.key, layoutSeed, albums.size, artists.size) {
        (albums.indices.map { false to it } + artists.indices.map { true to it }).shuffled(kotlin.random.Random(periodLayoutSeed(layoutSeed, slice.key) xor 0x7a12))
    }
    val geometry = remember(slice.key, layoutSeed) { randomizedMonthArtGeometry(layoutSeed, slice.key) }
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("period-hero-layout-month")) {
        val gap = 8.dp
        val height = periodHeroCardHeight(maxWidth, DiaryRange.MONTH, 1f)
        val right = (maxWidth - gap) * geometry.albumWidthFraction
        val left = maxWidth - right - gap
        @Composable fun Tile(item: Pair<Boolean, Int>, modifier: Modifier) {
            val index = item.second
            val shape = RoundedCornerShape(16.dp)
            if (item.first) {
                val artist = artists[index]
                Box(
                    modifier
                        .then(if (index == 0) Modifier.testTag("month-portrait:${slice.key}") else Modifier.testTag("month-artist-$index:${slice.key}"))
                        .semantics { contentDescription = artist.name }
                        .periodArtistMotion(shared, visibility, active, shape, artistKeys.getOrElse(index) { index })
                        .clip(shape)
                        .press { artistOpen(artist.name) }
                ) {
                    Artwork(c, "artist:${artist.name}", Modifier.fillMaxSize().detailSource("period-artist:MONTH:${artist.name}", active, shape), contentScale = ContentScale.Crop)
                    TileName(artist.name, "", Modifier.align(Alignment.BottomCenter), compact = true)
                }
            } else {
                val album = albums[index]
                Box(
                    modifier
                        .testTag("month-grid-album-$index:${slice.key}")
                        .summaryArtworkMotion(shared, summaryVisibility, active && c.range == DiaryRange.MONTH && index == 0, "summary-cover:0", shape)
                        .periodCoverMotion(shared, visibility, active, shape, coverKeys.getOrElse(index) { index })
                        .clip(shape)
                        .press { open(album.cover, "interval:${slice.key}:${album.key}") }
                ) {
                    Artwork(c, album.cover.id, Modifier.fillMaxSize().detailSource("interval:${slice.key}:${album.key}", active, shape), contentScale = ContentScale.Crop)
                    TileName(album.title, album.artist, Modifier.align(Alignment.BottomCenter), compact = true)
                }
            }
        }
        @Composable fun Tall() { Tile(order.first(), Modifier.size(left, height)) }
        @Composable fun Grid() {
            Column(Modifier.size(right, height).testTag("month-album-grid:${slice.key}"), verticalArrangement = Arrangement.spacedBy(gap)) {
                if (order.size == 4) {
                    val large = (height - gap) * .64f
                    val small = height - gap - large
                    @Composable fun Large() { Tile(order[1], Modifier.fillMaxWidth().height(large)) }
                    @Composable fun Small() {
                        Row(Modifier.fillMaxWidth().height(small), horizontalArrangement = Arrangement.spacedBy(gap)) {
                            Tile(order[2], Modifier.weight(1f).fillMaxHeight())
                            Tile(order[3], Modifier.weight(1f).fillMaxHeight())
                        }
                    }
                    if (geometry.smallFirst) {
                        Small()
                        Large()
                    } else {
                        Large()
                        Small()
                    }
                } else {
                    val top = (height - gap) * .54f
                    val bottom = height - gap - top
                    Row(Modifier.fillMaxWidth().height(top), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        Tile(order[1], Modifier.weight(1f).fillMaxHeight())
                        Tile(order[2], Modifier.weight(1f).fillMaxHeight())
                    }
                    Row(Modifier.fillMaxWidth().height(bottom), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        Tile(order[3], Modifier.weight(1f).fillMaxHeight())
                        Tile(order[4], Modifier.weight(1f).fillMaxHeight())
                        Tile(order[5], Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
        if (order.size == 6) {
            Column(Modifier.fillMaxWidth().height(height).testTag("month-album-grid:${slice.key}"), verticalArrangement = Arrangement.spacedBy(gap)) {
                repeat(2) { row ->
                    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        repeat(3) { col ->
                            val wide = if (geometry.portraitFirst) col == 1 else col == row
                            Tile(order[row * 3 + col], Modifier.weight(if (wide) 1.2f else .9f).fillMaxHeight())
                        }
                    }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.spacedBy(gap)) {
                if (geometry.portraitFirst) {
                    Tall()
                    Grid()
                } else {
                    Grid()
                    Tall()
                }
            }
        }
    }
}

/** Year: independent square covers yield to nearby portraits, leaving real transparent curved gutters. */
@Composable private fun YearFreeform(
    c: DiaryController,
    slice: PeriodHeroSlice,
    active: Boolean,
    tint: (List<Color>) -> Unit,
    open: (Play, String) -> Unit,
    artistOpen: (String) -> Unit,
    shared: SharedTransitionScope?,
    periodVisibility: AnimatedVisibilityScope?,
    summaryVisibility: AnimatedVisibilityScope?,
    tintEnabled: Boolean,
    coverKeys: List<Int>,
    artistKeys: List<Int>,
    layoutSeed: Int,
    layout: YearArtLayout
) {
    val songs = remember(slice, layoutSeed) {
        periodLayoutItems(
            slice.albums.flatMap { it.songs }.sortedWith(compareByDescending<Play> { it.ms }.thenBy { it.id }),
            layout.songs.size,
            periodLayoutSeed(layoutSeed, slice.key)
        )
    }
    val artists = remember(slice, layoutSeed) { periodLayoutItems(slice.artists, layout.artists.size, periodLayoutSeed(layoutSeed, slice.key, true)) }
    val portraits = layout.artists.take(artists.size)
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("period-hero-layout-year").semantics { this[YearArtLayoutKey] = layout }) {
        val unit = maxWidth
        Box(Modifier.fillMaxWidth().height(unit * 1.035f)) {
            layout.songs.forEachIndexed { index, slot ->
                songs.getOrNull(index)?.let { song ->
                    val shape = remember(slot, portraits) { NotchedArtworkShape(artworkNotches(slot, portraits)) }
                    val (startInset, endInset) = remember(slot, portraits, unit) { artworkCaptionInsets(slot, portraits, 4.dp / unit) }
                    HeroSong(
                        c,
                        song,
                        Modifier.offset(x = unit * slot.x, y = unit * slot.y).size(unit * slot.side).testTag("year-song-$index:${slice.key}"),
                        active,
                        active && index < 5,
                        index,
                        coverKeys.getOrElse(index) { 0 },
                        tint,
                        slice.key,
                        open,
                        shape,
                        unit * startInset,
                        unit * endInset,
                        slot.side < .22f,
                        shared,
                        periodVisibility,
                        summaryVisibility,
                        tintEnabled
                    )
                }
            }
            artists.forEachIndexed { index, artist ->
                val slot = portraits[index]
                FloatingArtist(
                    c,
                    artist.name,
                    unit * slot.side,
                    Modifier.zIndex(2f).offset(x = unit * slot.x, y = unit * slot.y),
                    "year-artist-$index:${slice.key}",
                    shared,
                    periodVisibility,
                    summaryVisibility,
                    active,
                    active && index == 0,
                    artistKeys.getOrElse(index) { 0 }
                ) { artistOpen(artist.name) }
            }
        }
    }
}

@Composable private fun HeroSong(
    c: DiaryController,
    song: Play,
    modifier: Modifier,
    active: Boolean,
    summaryActive: Boolean,
    summaryIndex: Int,
    motionKey: Int,
    tint: (List<Color>) -> Unit,
    key: String,
    open: (Play, String) -> Unit,
    shape: Shape = RoundedCornerShape(14.dp),
    textStartInset: Dp = 0.dp,
    textInset: Dp = 0.dp,
    titleOnly: Boolean = false,
    shared: SharedTransitionScope?,
    periodVisibility: AnimatedVisibilityScope?,
    summaryVisibility: AnimatedVisibilityScope?,
    tintEnabled: Boolean
) {
    Box(
        modifier
            .summaryArtworkMotion(shared, summaryVisibility, summaryActive, "summary-cover:$summaryIndex", RoundedCornerShape(18.dp))
            .periodCoverMotion(shared, periodVisibility, active, shape, motionKey)
            .clip(shape)
            .semantics { contentDescription = "${song.title} · ${song.artist}" }
            .press { open(song, "year-song:$key:${song.id}") }
    ) {
        Artwork(c, song.id, Modifier.fillMaxSize().detailSource("year-song:$key:${song.id}", active, shape), onTint = null)
        TileName(song.title, if (titleOnly) "" else song.artist, Modifier.align(Alignment.BottomCenter), compact = true, startInset = textStartInset, endInset = textInset)
    }
}

@Composable private fun FloatingArtist(
    c: DiaryController,
    name: String,
    side: Dp,
    modifier: Modifier,
    tag: String,
    shared: SharedTransitionScope?,
    periodVisibility: AnimatedVisibilityScope?,
    summaryVisibility: AnimatedVisibilityScope?,
    active: Boolean,
    summaryActive: Boolean,
    motionKey: Int,
    click: () -> Unit
) {
    Box(
        modifier
            .size(side)
            .summaryArtworkMotion(shared, summaryVisibility, summaryActive, "summary-artist", CircleShape)
            .periodArtistMotion(shared, periodVisibility, active, CircleShape, motionKey)
            .testTag(tag)
            .semantics { contentDescription = name }
            .clip(CircleShape)
            .press(click)
    ) {
        Artwork(c, "artist:$name", Modifier.fillMaxSize().detailSource("period-artist:YEAR:$name", active, CircleShape), edge = 720, contentScale = ContentScale.Crop)
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(side * .36f)
                .detailCaptionFade()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .72f)))),
            contentAlignment = Alignment.Center
        ) {
            Text(name, Modifier.fillMaxWidth().padding(horizontal = side * .15f), fontSize = 13.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

@Composable private fun TileName(title: String, artist: String, modifier: Modifier, compact: Boolean = false, startInset: Dp = 0.dp, endInset: Dp = 0.dp) {
    Column(
        modifier.detailCaptionFade()
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .68f))))
            .padding(
                start = (if (compact) 7.dp else 10.dp) + startInset,
                end = (if (compact) 7.dp else 10.dp) + endInset,
                top = if (compact) 4.dp else 8.dp,
                bottom = if (compact) 4.dp else 8.dp
            )
    ) {
        Text(title, fontSize = if (compact) 11.sp else 15.sp, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (artist.isNotBlank()) Text(artist, fontSize = if (compact) 11.sp else 12.sp, color = Color.White.copy(alpha = .86f), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable private fun HeroAlbum(
    c: DiaryController,
    album: AlbumStat,
    modifier: Modifier,
    active: Boolean,
    tint: (List<Color>) -> Unit,
    key: String,
    open: (Play, String) -> Unit,
    shared: SharedTransitionScope?,
    periodVisibility: AnimatedVisibilityScope?,
    summaryVisibility: AnimatedVisibilityScope?,
    tintActive: Boolean = active,
    motionKey: Int = 0,
    detailActive: Boolean = active
) {
    Artwork(
        c,
        album.cover.id,
        modifier
            .periodCoverMotion(shared, periodVisibility, active, RoundedCornerShape(15.dp), motionKey)
            .detailSource("interval:$key:${album.key}", detailActive, RoundedCornerShape(15.dp))
            .testTag("hero-album:$key:${album.cover.id}")
            .clip(RoundedCornerShape(15.dp))
            .press { open(album.cover, "interval:$key:${album.key}") },
        onTint = if (tintActive) tint else null
    )
}

@Composable private fun EmptyInterval(height: Dp) {
    Box(Modifier.fillMaxWidth().height(height).background(Muted.copy(alpha = .035f), RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
        Text("暂无记录", fontSize = 17.sp, color = Muted)
    }
}

internal val SummaryArtworkKey = SemanticsPropertyKey<String>("SummaryArtworkKey")
internal val SummaryArtworkMatchedKey = SemanticsPropertyKey<Boolean>("SummaryArtworkMatched")
/**
 * One stable outer transition owns the artwork travelling between period views and summary.
 * Crucially, only mount `sharedBounds("summary-cover:...")` when the summary transition is actually active
 * (entering/exiting Summary or dragging into Summary), so Day ↔ Week ↔ Month transitions never have
 * two nested `sharedBounds` competing over the same cover!
 */
@Composable internal fun Modifier.summaryArtworkMotion(
    shared: SharedTransitionScope?,
    visibility: AnimatedVisibilityScope?,
    active: Boolean,
    key: String,
    shape: Shape
): Modifier {
    if (shared == null || visibility == null || !active) return this
    val summaryMoving = visibility.transition.currentState != visibility.transition.targetState || visibility.transition.isRunning
    // Keep the modifier/layout nodes stable across detail return; only its rendering changes.
    val detailTransition = LocalDetailActions.current?.visibility?.transition
    val detailMoving = detailTransition != null && (detailTransition.currentState != EnterExitState.Visible || detailTransition.targetState != EnterExitState.Visible)
    return with(shared) {
        val state = rememberSharedContentState(key)
        this@summaryArtworkMotion
            .sharedBounds(
                state,
                visibility,
                enter = if (detailMoving) EnterTransition.None else fadeIn(tween(200)),
                exit = if (detailMoving) ExitTransition.None else fadeOut(tween(200)),
                renderInOverlayDuringTransition = ownedSharedOverlay(shared, visibility, !detailMoving),
                clipInOverlayDuringTransition = OverlayClip(shape),
                resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
                boundsTransform = { _, _ -> tween(320, easing = LinearEasing) }
            )
            .semantics { this[SummaryArtworkKey] = key; this[SummaryArtworkMatchedKey] = state.isMatchFound }
    }
}

/** The primary cover is a shared slot; different albums cross-fade while the bounds move. */
@Composable internal fun Modifier.periodCoverMotion(
    shared: SharedTransitionScope?,
    visibility: AnimatedVisibilityScope?,
    active: Boolean,
    shape: Shape = RoundedCornerShape(17.dp),
    motionKey: Int = 0,
    clipOverlay: Boolean = true
): Modifier {
    if (shared == null || visibility == null || !active) return this
    // Keep the modifier/layout nodes stable across detail return; only its rendering changes.
    val detailTransition = LocalDetailActions.current?.visibility?.transition
    val detailMoving = detailTransition != null && (detailTransition.currentState != EnterExitState.Visible || detailTransition.targetState != EnterExitState.Visible)
    val overlay = ownedSharedOverlay(shared, visibility, !detailMoving)
    return with(shared) {
        this@periodCoverMotion
            .sharedBounds(
                rememberSharedContentState("period-cover-art:$motionKey"),
                visibility,
                enter = if (detailMoving) EnterTransition.None else fadeIn(tween(180)),
                exit = if (detailMoving) ExitTransition.None else fadeOut(tween(180)),
                renderInOverlayDuringTransition = overlay,
                clipInOverlayDuringTransition = if(clipOverlay)OverlayClip(shape)else OverlayClip(CoverShadowOverlayShape),
                resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
                boundsTransform = { _, _ -> tween(320, easing = LinearEasing) }
            )
            .semantics { this[PeriodCoverMotionKey] = motionKey; this[PeriodArtworkOverlayKey] = overlay }
    }
}

/** Compose can briefly request a key from the old page count during a list replacement. */
internal fun periodPagerKey(slices: List<PeriodHeroSlice>, index: Int): String =
    slices.getOrNull(index)?.key ?: "pending-page:$index"

@Composable internal fun Modifier.periodArtistMotion(
    shared: SharedTransitionScope?,
    visibility: AnimatedVisibilityScope?,
    active: Boolean,
    shape: Shape = CircleShape,
    motionKey: Int = 0
): Modifier {
    if (shared == null || visibility == null || !active) return this
    // Keep the modifier/layout nodes stable across detail return; only its rendering changes.
    val detailTransition = LocalDetailActions.current?.visibility?.transition
    val detailMoving = detailTransition != null && (detailTransition.currentState != EnterExitState.Visible || detailTransition.targetState != EnterExitState.Visible)
    val overlay = ownedSharedOverlay(shared, visibility, !detailMoving)
    return with(shared) {
        this@periodArtistMotion
            .sharedBounds(
                rememberSharedContentState("period-artist-art:$motionKey"),
                visibility,
                enter = if (detailMoving) EnterTransition.None else fadeIn(tween(260)),
                exit = if (detailMoving) ExitTransition.None else fadeOut(tween(210)),
                renderInOverlayDuringTransition = overlay,
                clipInOverlayDuringTransition = OverlayClip(shape),
                resizeMode = SharedTransitionScope.ResizeMode.scaleToBounds(),
                boundsTransform = { _, _ -> tween(320, easing = LinearEasing) }
            )
            .semantics { this[PeriodArtistMotionKey] = motionKey; this[PeriodArtworkOverlayKey] = overlay }
    }
}

/** Include the external shadow in the moving layer; the artwork itself still clips its corners. */
private object CoverShadowOverlayShape:Shape{
    override fun createOutline(size:androidx.compose.ui.geometry.Size,layoutDirection:LayoutDirection,density:Density):Outline{
        val margin=with(density){32.dp.toPx()}
        return Outline.Rectangle(androidx.compose.ui.geometry.Rect(-margin,-margin,size.width+margin,size.height+margin))
    }
}
