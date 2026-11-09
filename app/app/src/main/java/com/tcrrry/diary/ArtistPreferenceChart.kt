package com.tcrrry.diary

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable internal fun ArtistPreferenceTrajectoryCard(
    c: DiaryController,
    facts: SummaryFacts,
    artist: String,
    modifier: Modifier = Modifier
) {
    val activeYears = remember(facts, artist) { facts.artistActiveYears(artist) }
    var selectedYear by rememberSaveable(artist) { mutableStateOf<Int?>(null) }
    var selectedBucketIndex by rememberSaveable(artist, selectedYear) { mutableIntStateOf(-1) }

    val buckets = remember(facts, artist, selectedYear) {
        facts.artistTimelineBuckets(artist, selectedYear)
    }
    val milestone = remember(facts, artist) {
        facts.artistMilestoneText(artist)
    }
    val firstPlay = remember(facts, artist) {
        facts.artistFirstPlay(artist)
    }
    val detail = LocalDetailActions.current

    Column(modifier.fillMaxWidth()) {
        // Full-width heading on its own line so it never wraps vertically
        Text(
            "这份偏爱，怎样发生",
            Modifier.fillMaxWidth(),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )

        // Horizontally scrollable year pills showing all active years (2023, 2024, 2025, 2026...)
        if (activeYears.size > 1) {
            LazyRow(
                Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item(key = "all") {
                    YearFilterPill(
                        label = "全部 (${activeYears.first()}–${activeYears.last()})",
                        active = selectedYear == null
                    ) {
                        selectedYear = null
                        selectedBucketIndex = -1
                    }
                }
                items(activeYears, key = { it }) { y ->
                    YearFilterPill(
                        label = "${y}年",
                        active = selectedYear == y
                    ) {
                        selectedYear = y
                        selectedBucketIndex = -1
                    }
                }
            }
        }

        if (buckets.isNotEmpty()) {
            val maxMs = (buckets.maxOfOrNull { it.ms } ?: 0L).coerceAtLeast(1L)
            val peakIdx = buckets.indexOfFirst { it.ms == maxMs && it.ms > 0L }
            var shown by remember(artist, selectedYear) { mutableStateOf(false) }
            LaunchedEffect(artist, selectedYear) { shown = true }
            val animatedLevels = buckets.mapIndexed { i, b ->
                animateFloatAsState(
                    targetValue = if (shown && maxMs > 0L) (b.ms.toFloat() / maxMs).coerceIn(0f, 1f) else 0f,
                    animationSpec = tween(460, delayMillis = i * 22, easing = FastOutSlowInEasing),
                    label = "artist bar $i"
                ).value
            }
            val accent = Accent
            val muted = Muted

            Column(
                Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(Muted.copy(alpha = .045f))
                    .padding(horizontal = 14.dp, vertical = 14.dp)
            ) {
                // Pure rounded bar chart (no Bézier envelope line, no truncated badge)
                Row(
                    Modifier.fillMaxWidth().height(92.dp),
                    horizontalArrangement = Arrangement.spacedBy(if (buckets.size > 8) 5.dp else 10.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    buckets.forEachIndexed { i, b ->
                        val isSelected = i == selectedBucketIndex
                        val isPeak = i == peakIdx
                        val hasPlay = b.ms > 0L
                        Column(
                            Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .press {
                                    if (hasPlay) {
                                        selectedBucketIndex = if (selectedBucketIndex == i) -1 else i
                                    }
                                },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom
                        ) {
                            Text(
                                text = formatCompactArtistDuration(b.ms),
                                fontSize = if (buckets.size > 8) 8.sp else 9.sp,
                                color = if (isSelected || isPeak) accent else Muted,
                                fontWeight = if (isSelected || isPeak) FontWeight.Bold else FontWeight.Medium,
                                textAlign = TextAlign.Center,
                                maxLines = 1
                            )
                            Spacer(Modifier.height(4.dp))
                            Box(
                                Modifier
                                    .fillMaxWidth(if (buckets.size > 8) .72f else .62f)
                                    .fillMaxHeight(animatedLevels[i].coerceIn(.03f, 1f))
                                    .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp, bottomStart = 3.dp, bottomEnd = 3.dp))
                                    .background(
                                        when {
                                            !hasPlay -> muted.copy(alpha = .10f)
                                            isSelected -> accent
                                            isPeak -> accent.copy(alpha = .88f)
                                            else -> accent.copy(alpha = .30f + .45f * animatedLevels[i])
                                        }
                                    )
                            )
                        }
                    }
                }

                // Time bucket labels row
                Row(Modifier.fillMaxWidth().padding(top = 7.dp)) {
                    buckets.forEachIndexed { i, b ->
                        val highlight = i == selectedBucketIndex || (selectedBucketIndex == -1 && i == peakIdx)
                        Text(
                            text = b.label,
                            modifier = Modifier.weight(1f),
                            fontSize = if (buckets.size > 8) 9.sp else 10.sp,
                            color = if (highlight) accent else Muted,
                            fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // Selected bucket detail expansion (when tapping a bar like 2023年 4月)
                val chosenBucket = buckets.getOrNull(selectedBucketIndex)
                AnimatedVisibility(
                    visible = chosenBucket != null && chosenBucket.ms > 0L,
                    enter = expandVertically(tween(220)) + fadeIn(tween(180)),
                    exit = shrinkVertically(tween(180)) + fadeOut(tween(120))
                ) {
                    if (chosenBucket != null) {
                        val bucketPlays = remember(facts, artist, chosenBucket.from, chosenBucket.to) {
                            facts.artistPlaysInBucket(artist, chosenBucket.from, chosenBucket.to)
                        }
                        Column(
                            Modifier
                                .padding(top = 12.dp)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(Accent.copy(alpha = .06f))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${chosenBucket.fullRangeTitle} · 共 ${bucketPlays.size} 次聆听记录（${shortDuration(chosenBucket.ms)}）",
                                    Modifier.weight(1f),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Accent
                                )
                                Text(
                                    "收起",
                                    Modifier.press { selectedBucketIndex = -1 },
                                    fontSize = 11.sp,
                                    color = Muted
                                )
                            }
                            bucketPlays.take(5).forEach { p ->
                                val key = "bucket-play:${chosenBucket.from}:${p.visit}"
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .press { detail?.track?.invoke(p, key) }
                                        .padding(vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Artwork(c, p.id, Modifier.size(34.dp).detailSource(key).clip(RoundedCornerShape(7.dp)), edge = 160)
                                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                        Text(p.title, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            formatEncounterTimestamp(p.start, p.zone, c.use12Hour),
                                            fontSize = 11.sp,
                                            color = Muted,
                                            maxLines = 1
                                        )
                                    }
                                    Text(shortDuration(p.ms), fontSize = 11.sp, color = Accent)
                                }
                            }
                        }
                    }
                }
            }
        }

        // First Encounter specific card (shows exact date, time, song title, and duration of the very first play)
        if (firstPlay != null) {
            val firstSource = remember(firstPlay.visit) { "artist-first-encounter:${firstPlay.visit}" }
            Row(
                Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(Accent.copy(alpha = .065f))
                    .press { detail?.track?.invoke(firstPlay, firstSource) }
                    .padding(13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Artwork(
                    c,
                    firstPlay.id,
                    Modifier.size(46.dp).detailSource(firstSource).clip(RoundedCornerShape(10.dp)),
                    edge = 160
                )
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .clip(CircleShape)
                                .background(Accent.copy(alpha = .16f))
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text("初次相遇", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Accent)
                        }
                        Text(
                            "  " + formatEncounterTimestamp(firstPlay.start, firstPlay.zone, c.use12Hour),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Text(
                        "《${firstPlay.title}》 · 听了 ${shortDuration(firstPlay.ms)}" +
                            if (firstPlay.album.isNotBlank()) " · ${firstPlay.album}" else "",
                        Modifier.padding(top = 4.dp),
                        fontSize = 12.sp,
                        color = Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                DiaryGlyph("next", Modifier.size(15.dp), color = Accent)
            }
        }

        Text(
            milestone + "（点击柱状图可查看当期明细）",
            Modifier.padding(top = 8.dp),
            fontSize = 12.sp,
            color = Muted
        )
    }
}

internal fun formatEncounterTimestamp(epochMs: Long, zoneId: String, use12Hour: Boolean): String {
    val zone = runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.systemDefault())
    val pattern = if (use12Hour) "yyyy年M月d日 a h:mm" else "yyyy年M月d日 HH:mm"
    return Instant.ofEpochMilli(epochMs).atZone(zone).format(DateTimeFormatter.ofPattern(pattern, Locale.CHINA))
}

internal fun formatEncounterDateShort(epochMs: Long, zoneId: String): String {
    val zone = runCatching { ZoneId.of(zoneId) }.getOrDefault(ZoneId.systemDefault())
    return Instant.ofEpochMilli(epochMs).atZone(zone).format(DateTimeFormatter.ofPattern("yyyy.MM.dd", Locale.CHINA))
}

@Composable internal fun YearFilterPill(
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(if (active) Accent.copy(alpha = .15f) else Muted.copy(alpha = .08f))
            .press(onClick)
            .padding(horizontal = 11.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            color = if (active) Accent else Muted,
            maxLines = 1
        )
    }
}

private fun formatCompactArtistDuration(ms: Long): String {
    if (ms <= 0L) return ""
    val mins = ms / 60000L
    return when {
        mins < 1L -> "<1m"
        mins < 60L -> "${mins}m"
        else -> "%.1fh".format(Locale.ROOT, ms / 3600000f)
    }
}
