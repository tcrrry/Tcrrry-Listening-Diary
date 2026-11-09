@file:OptIn(
    androidx.compose.animation.ExperimentalSharedTransitionApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)
package com.tcrrry.diary

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import kotlin.math.*

internal val FavoriteOrbitPositionKey = SemanticsPropertyKey<Int>("FavoriteOrbitPosition")
internal val MusicTimelinePhaseKey = SemanticsPropertyKey<Float>("MusicTimelinePhase")

internal class MusicTimelineState {
    var phase by mutableFloatStateOf(0f)
    var dragging by mutableStateOf(false)
    var quick by mutableStateOf(false)
    var quickGoal by mutableFloatStateOf(0f)
    var quickHeld by mutableStateOf(false)
    var detailOpen by mutableStateOf(false)
    var lastTouch = 0L

    fun moveBy(amount: Float) {
        phase += amount
        lastTouch = SystemClock.uptimeMillis()
    }

    fun touch() {
        lastTouch = SystemClock.uptimeMillis()
    }

    fun enterQuick(count: Int) {
        if (count <= 0) return
        val maxIndex = (count - 1).coerceAtLeast(0).toFloat()
        val normalized = phase.mod(count.toFloat()).coerceIn(0f, maxIndex)
        phase = normalized
        quickGoal = normalized
        quick = true
        touch()
    }

    fun leaveQuick() {
        quick = false
        quickHeld = false
        dragging = false
        touch()
    }
}

/** Four close-set passes; the inset includes the largest rotated/focused cover footprint. */
internal fun musicTimelinePath(w: Float, h: Float) = Path().apply {
    fun x(f: Float) = w * (.10f + .80f * f)
    moveTo(x(.05f), h * .12f)
    cubicTo(x(.28f), h * .03f, x(.64f), h * .18f, x(.88f), h * .10f)
    cubicTo(x(.98f), h * .08f, x(.98f), h * .30f, x(.88f), h * .34f)
    cubicTo(x(.65f), h * .44f, x(.36f), h * .25f, x(.12f), h * .36f)
    cubicTo(x(.02f), h * .40f, x(.02f), h * .54f, x(.12f), h * .59f)
    cubicTo(x(.36f), h * .70f, x(.64f), h * .49f, x(.88f), h * .60f)
    cubicTo(x(.98f), h * .64f, x(.98f), h * .79f, x(.88f), h * .84f)
    cubicTo(x(.64f), h * .95f, x(.36f), h * .77f, x(.05f), h * .88f)
}

/** Stable, distinct partners drawn from the whole visible route, excluding its fading tips. */
internal fun summaryTimelineSlots(seed: Int, visible: Int): List<Int> {
    val candidates = if (visible > 4) (1 until visible - 1).toList() else (0 until visible).toList()
    return candidates.shuffled(kotlin.random.Random(seed)).take(5)
}

/** A compact, single-screen 3D album trajectory with long-press quick scrubbing like the Day view. */
@Composable internal fun MusicTimeline(
    c: DiaryController,
    facts: SummaryFacts,
    tint: (List<Color>) -> Unit,
    open: (Play, String) -> Unit,
    shared: SharedTransitionScope,
    summaryVisibility: AnimatedVisibilityScope,
    motion: MusicTimelineState
) {
    val favorites = facts.monthlyFavorites
    val navigating = summaryVisibility.transition.currentState != summaryVisibility.transition.targetState || c.periodSwipePosition != null
    val detail = LocalDetailActions.current
    val detailReady = detail?.visibility?.transition?.let {
        it.currentState == EnterExitState.Visible && it.targetState == EnterExitState.Visible
    } ?: true
    LaunchedEffect(detailReady) {
        if (detailReady && motion.detailOpen) {
            motion.detailOpen = false
            motion.touch()
        }
    }
    val active = c.summary && (c.foreground || c.demo) && !navigating && detailReady && !motion.detailOpen
    val count = favorites.size
    val inspection = LocalInspectionMode.current
    val haptic = LocalHapticFeedback.current

    BackHandler(enabled = motion.quick) {
        motion.leaveQuick()
    }

    LaunchedEffect(active, count, motion.quick) {
        if (active && count > 1) {
            var previous = 0L
            while (true) {
                withInfiniteAnimationFrameNanos { now ->
                    val elapsed = if (previous == 0L) 1f / 60f else ((now - previous) / 1_000_000_000f).coerceIn(0f, .05f)
                    previous = now
                    if (motion.quick && active) {
                        val maxIndex = (count - 1).coerceAtLeast(0).toFloat()
                        val target = motion.quickGoal.coerceIn(0f, maxIndex)
                        motion.phase = coverArcStep(motion.phase, target, elapsed, motion.quickHeld).coerceIn(0f, maxIndex)
                    } else if (active && !motion.dragging && (motion.lastTouch == 0L || SystemClock.uptimeMillis() - motion.lastTouch > 3000)) {
                        motion.phase = (motion.phase + elapsed * .16f).mod(count.toFloat())
                    }
                }
            }
        }
    }

    val density = LocalDensity.current
    val hardware = LocalView.current.isHardwareAccelerated && !inspection
    val timelineHeight = 342.dp
    val quickProgress by animateFloatAsState(
        targetValue = if (motion.quick) 1f else 0f,
        animationSpec = spring(.86f, 260f),
        label = "timeline quick mode"
    )

    // Pre-warm upcoming monthly cover bitmaps after the reveal transition settles
    val wholePhase = floor(motion.phase).toInt()
    LaunchedEffect(wholePhase, count, favorites, c.imageNetwork, c.imageEpoch) {
        if (count > 0) {
            kotlinx.coroutines.delay(360)
            val upcomingIds = (1..8).map { offset ->
                favorites[Math.floorMod(wholePhase - offset, count)].second.id
            }.distinct()
            c.images.warmCached(upcomingIds, 320)
        }
    }

    Column(Modifier.fillMaxWidth().testTag("summary-profile-hero")) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "你的音乐，慢慢有了轮廓",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (motion.quick && count > 0) {
                        val focusIdx = motion.quickGoal.roundToInt().coerceIn(0, count - 1)
                        val (ym, item) = favorites[focusIdx]
                        "${ym.year}年${ym.monthValue}月 · ${item.album.ifBlank { item.title }} · ${focusIdx + 1}/$count"
                    } else {
                        "全部记录 · ${facts.report.from}—${facts.report.to}"
                    },
                    Modifier.padding(top = 6.dp),
                    fontSize = 12.sp,
                    color = if (motion.quick) Accent else Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

        }

        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(timelineHeight)
                .clipToBounds()
                .testTag("favorite-timeline")
                .semantics {
                    this[FavoriteOrbitPositionKey] = floor(motion.phase).toInt()
                    this[MusicTimelinePhaseKey] = motion.phase
                }
                .pointerInput(count, motion, inspection) {
                    val measure = PathMeasure().apply {
                        setPath(musicTimelinePath(size.width.toFloat(), size.height.toFloat()), false)
                    }
                    fun nearest(position: Offset): Pair<Float, Float> {
                        var best = Float.MAX_VALUE
                        var distance = 0f
                        for (i in 0..240) {
                            val at = measure.length * i / 240f
                            val p = measure.getPosition(at)
                            val delta = (p - position).getDistance()
                            if (delta < best) {
                                best = delta
                                distance = at
                            }
                        }
                        return best to distance
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                        if (motion.quick) {
                            var dragged = false
                            var previous = down.position
                            val stepPx = (size.height / count.coerceAtLeast(1).toFloat()).coerceAtLeast(18f)
                            try {
                                do {
                                    val event = awaitPointerEvent(PointerEventPass.Main)
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    val total = change.position - down.position
                                    if (!dragged && total.getDistance() > viewConfiguration.touchSlop) {
                                        dragged = true
                                        motion.quickHeld = true
                                        motion.touch()
                                    }
                                    if (dragged) {
                                        val dy = change.position.y - previous.y
                                        val maxIndex = (count - 1).coerceAtLeast(0).toFloat()
                                        motion.quickGoal = (motion.quickGoal - dy * CoverArcDragGain / stepPx).coerceIn(0f, maxIndex)
                                        motion.touch()
                                        change.consume()
                                    }
                                    previous = change.position
                                } while (true)
                            } finally {
                                if (dragged) {
                                    motion.quickHeld = false
                                    motion.quickGoal = motion.quickGoal.roundToInt().toFloat()
                                    motion.touch()
                                }
                            }
                        } else if (inspection) {
                            val inTopRightMargin = down.position.x > size.width * 0.90f && down.position.y < size.height * 0.22f
                            val onLine = !inTopRightMargin && nearest(down.position).first <= 32.dp.toPx()
                            if (onLine) {
                                var owned = false
                                var previous = down.position
                                try {
                                    do {
                                        val event = awaitPointerEvent(PointerEventPass.Main)
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) break
                                        if (change.isConsumed && !owned) break
                                        if (!owned && (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                            owned = true
                                            motion.dragging = true
                                            motion.touch()
                                        }
                                        if (owned) {
                                            val delta = change.position - previous
                                            val distance = nearest(change.position).second
                                            val tangent = measure.getTangent(distance)
                                            motion.moveBy(
                                                -(delta.x * tangent.x + delta.y * tangent.y) * 8f /
                                                    measure.length.coerceAtLeast(1f)
                                            )
                                            change.consume()
                                        }
                                        previous = change.position
                                    } while (true)
                                } finally {
                                    if (owned) {
                                        motion.dragging = false
                                        motion.touch()
                                    }
                                }
                            }
                        }
                    }
                }
        ) {
            val width = maxWidth
            val accent = Accent
            // Bottom controls have their own permanent slot: the route never shifts sideways.
            val shiftLeftPx = 0f
            val widthPx = with(density) { width.toPx() }
            val heightPx = with(density) { timelineHeight.toPx() }
            val route = remember(widthPx, heightPx) { musicTimelinePath(widthPx, heightPx) }
            val routeMeasure = remember(route) { PathMeasure().apply { setPath(route, false) } }
            val strokes = remember(routeMeasure) {
                List(100) { step -> Path().also { routeMeasure.getSegment(
                    routeMeasure.length * step / 100f, routeMeasure.length * (step + 1) / 100f, it, true) } }
            }

            if (hardware) Canvas(Modifier.fillMaxSize().graphicsLayer {
                renderEffect = BlurEffect(2.6.dp.toPx(), 2.6.dp.toPx(), TileMode.Decal)
            }) {
                strokes.forEachIndexed { step, segment ->
                    val f = (step + .5f) / 100f
                    val tip = minOf(f, 1f - f)
                    if (tip < .10f) {
                        val fade = (tip / .055f).coerceIn(0f, 1f)
                        val softness = 1f - (tip / .10f).coerceIn(0f, 1f)
                        drawPath(segment, accent.copy(alpha = .38f * fade * softness),
                            style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Butt))
                    }
                }
            }
            Canvas(Modifier.fillMaxSize()) {
                strokes.forEachIndexed { step, segment ->
                    val f = (step + .5f) / 100f
                    val tip = (minOf(f, 1f - f) / .10f).coerceIn(0f, 1f)
                    val edge = tip * tip * (3f - 2f * tip)
                    drawPath(segment, accent.copy(alpha = (.38f + .06f * quickProgress) * edge),
                        style = Stroke(1.85.dp.toPx(), cap = StrokeCap.Butt, join = StrokeJoin.Round))
                }
                val measure = routeMeasure
                val dotCount = 14
                repeat(dotCount) { i ->
                    val dotProgress = ((motion.phase * 0.05f + (i + 0.5f) / dotCount)).mod(1f)
                    val dotAlpha = minOf((dotProgress * 10f).coerceIn(0f, 1f), ((1f - dotProgress) * 10f).coerceIn(0f, 1f))
                    val point = measure.getPosition(measure.length * (0.01f + 0.98f * dotProgress))
                    if (point.x.isFinite() && point.y.isFinite() && dotAlpha > 0.05f) {
                        drawCircle(
                            Color.White.copy(alpha = 0.92f * dotAlpha),
                            radius = 3.2.dp.toPx(),
                            center = point
                        )
                        drawCircle(
                            accent.copy(alpha = 0.65f * dotAlpha),
                            radius = 1.9.dp.toPx(),
                            center = point
                        )
                    }
                }
            }

            if (count > 0) {
                val phase = motion.phase
                val whole = floor(phase).toInt()
                val fraction = phase - floor(phase)
                val visible = minOf(count, 16)
                val partners = remember(c.summaryMotionSeed, visible, c.range) { summaryTimelineSlots(c.summaryMotionSeed, visible).take(if (c.range == DiaryRange.YEAR) 5 else 1) }
                val measure = routeMeasure
                val coverSize = width * 0.18f
                val focusIndex = if (motion.quick) motion.quickGoal.roundToInt().coerceIn(0, count - 1) else -1

                for (slot in 0..visible) {
                    val index = Math.floorMod(whole + slot, count)
                    val (month, play) = favorites[index]
                    val progress = ((slot - fraction) / visible.toFloat()).coerceIn(0f, 1f)
                    val distance = measure.length * (0.008f + 0.984f * progress)
                    val point = measure.getPosition(distance)
                    val tangent = measure.getTangent(distance)
                    val center = (point.x / widthPx.coerceAtLeast(1f)).coerceIn(0f, 1f)

                    // Smooth entry/exit fade and blur right at the two ends so top-left & bottom-right stay filled
                    val rawEdge = minOf(
                        (progress * 80f).coerceIn(0f, 1f),
                        ((1f - progress) * 80f).coerceIn(0f, 1f)
                    )
                    val edge = rawEdge * rawEdge * (3f - 2f * rawEdge)

                    // 3D orientation following both horizontal and vertical undulations
                    val yaw = (-tangent.x * 16f - (center - 0.5f) * 18f).coerceIn(-25f, 25f)
                    val pitch = (-11f - tangent.y * 4f).coerceIn(-16f, -7f)
                    val depth = (0.86f + 0.14f * abs(tangent.x)).coerceIn(0.86f, 1f)
                    val isFocused = motion.quick && index == focusIndex && slot < count
                    val zOrder = depth + progress * 0.15f + (if (isFocused) 0.5f else 0f)

                    val x = with(density) { (point.x - shiftLeftPx).toDp() } - coverSize / 2
                    val y = with(density) { point.y.toDp() } - coverSize * 0.52f
                    val key = "timeline:$month:${play.id}:slot:$slot"

                    if (edge > 0.01f) {
                        key(slot) {
                            val interaction = remember { MutableInteractionSource() }
                            val pressed by interaction.collectIsPressedAsState()
                            val pressScale by animateFloatAsState(
                                targetValue = if (pressed && !motion.quick) 0.94f else if (isFocused) 1.08f else 1f,
                                animationSpec = spring(.82f, 420f),
                                label = "timeline cover scale"
                            )
                            Column(
                                modifier = Modifier
                                    .offset(x = x, y = y)
                                    .width(coverSize)
                                    .zIndex(zOrder)
                                    .graphicsLayer {
                                        rotationZ = (-tangent.x * 2.2f).coerceIn(-2.8f, 2.8f)
                                        val s = (0.86f + 0.14f * edge) * (0.95f + depth * 0.05f) * pressScale * (1f - 0.03f * quickProgress)
                                        scaleX = s
                                        scaleY = s
                                        cameraDistance = with(density) { 650.dp.toPx() }
                                        alpha = edge.coerceIn(0f, 1f)
                                        renderEffect = if (hardware && edge < 0.94f) {
                                            val blurPx = ((1f - edge) * 5.5.dp.toPx()).coerceAtLeast(0.5f)
                                            BlurEffect(blurPx, blurPx, TileMode.Decal)
                                        } else null
                                    }
                                    .testTag("timeline-item:$month:$slot")
                                    .combinedClickable(
                                        interactionSource = interaction,
                                        indication = null,
                                        onLongClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            motion.enterQuick(count)
                                        },
                                        onClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            motion.touch()
                                            if (motion.quick) {
                                                motion.leaveQuick()
                                            } else {
                                                motion.detailOpen = true
                                                if (detail != null && play.album.isNotBlank()) detail.album(play, key)
                                                else open(play, key)
                                            }
                                        }
                                    ),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    Modifier
                                        .size(coverSize)
                                        .summaryArtworkMotion(
                                            shared,
                                            summaryVisibility,
                                            slot in partners,
                                            "summary-cover:${partners.indexOf(slot)}",
                                            RoundedCornerShape(10.dp)
                                        )
                                        .detailSource(key)
                                ) {
                                    CubeArtwork(
                                        c = c,
                                        id = play.id,
                                        yaw = yaw,
                                        pitch = pitch,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                Text(
                                    "${(month.year % 100).toString().padStart(2, '0')}.${month.monthValue.toString().padStart(2, '0')}",
                                    Modifier.offset(y = (-3).dp),
                                    fontSize = 9.sp,
                                    fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isFocused) Accent else Muted.copy(alpha = 0.78f),
                                    maxLines = 1,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                }


            }
        }
        Box(Modifier.fillMaxWidth().height(40.dp).testTag("timeline-controls-slot")) {
        if (motion.quick || quickProgress > .01f) Row(
            Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 20.dp)
                .graphicsLayer { alpha = quickProgress },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("1", Modifier.width(20.dp), fontSize = 10.sp, color = Muted)
            TimelineQuickScrubber(
                count = count,
                index = if (count > 0) motion.phase.mod(count.toFloat()).coerceIn(0f, (count - 1).coerceAtLeast(0).toFloat()) else 0f,
                enabled = motion.quick && count > 0,
                emphasis = quickProgress,
                modifier = Modifier.weight(1f).height(40.dp),
                choose = { value -> motion.touch(); motion.quickHeld = true; motion.quickGoal = value },
                finished = { motion.quickHeld = false; motion.quickGoal = motion.quickGoal.roundToInt().toFloat(); motion.touch() }
            )
            Text("$count", Modifier.width(28.dp), fontSize = 10.sp, color = Muted, textAlign = TextAlign.Center)
            Box(Modifier.width(64.dp), contentAlignment = Alignment.Center) {
                Text(if (motion.quick) "完成" else "长按浏览",
                    Modifier.clip(CircleShape)
                        .then(if (motion.quick) Modifier.background(Accent.copy(alpha = .10f)).press { motion.leaveQuick() } else Modifier)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    fontSize = 10.sp, color = if (motion.quick) Accent else Muted,
                    maxLines = 1)
            }
        }
        }

    }
}

/**
 * Permanently reserved horizontal scrubber below the route; long press activates manual seeking.
 */
@Composable private fun TimelineQuickScrubber(
    count: Int,
    index: Float,
    enabled: Boolean,
    emphasis: Float,
    modifier: Modifier = Modifier,
    choose: (Float) -> Unit,
    finished: () -> Unit
) {
    val latestChoose by rememberUpdatedState(choose)
    val latestFinished by rememberUpdatedState(finished)
    val accent = Accent
    val muted = Muted
    val maxIdx = (count - 1).coerceAtLeast(0).toFloat()

    Canvas(
        modifier
            .testTag("timeline-quick-scrubber")
            .semantics {
                contentDescription = "快速定位音乐轨迹"
                progressBarRangeInfo = ProgressBarRangeInfo(index, 0f..maxIdx)
                if (enabled) setProgress {
                    choose(it.coerceIn(0f, maxIdx))
                    finished()
                    true
                }
            }
            .pointerInput(count, enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fun selectAt(x: Float) {
                        val pad = 10.dp.toPx()
                        val usable = (size.width - pad * 2f).coerceAtLeast(1f)
                        val fraction = ((x - pad) / usable).coerceIn(0f, 1f)
                        latestChoose(arcHistoryPosition(fraction, count))
                    }
                    selectAt(down.position.x)
                    down.consume()
                    try {
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            selectAt(change.position.x)
                            change.consume()
                        } while (true)
                    } finally {
                        latestFinished()
                    }
                }
            }
    ) {
        val padX = 10.dp.toPx()
        val usableW = (size.width - padX * 2f).coerceAtLeast(1f)
        fun point(f: Float): Offset {
            val x = padX + f * usableW
            val normalized = f * 2f - 1f
            val y = size.height * .52f - (1f - normalized * normalized) * 3.dp.toPx()
            return Offset(x, y)
        }
        val path = Path()
        for (step in 0..80) {
            val p = point(step / 80f)
            if (step == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        drawPath(path, muted.copy(alpha = 0.16f + .06f * emphasis), style = Stroke(4.5.dp.toPx(), cap = StrokeCap.Round))

        val fraction = if (count > 1) (index / (count - 1)).coerceIn(0f, 1f) else 0f
        val active = Path()
        for (step in 0..60) {
            val p = point(fraction * step / 60f)
            if (step == 0) active.moveTo(p.x, p.y) else active.lineTo(p.x, p.y)
        }
        drawPath(active, accent.copy(alpha = 0.28f + .47f * emphasis), style = Stroke(4.5.dp.toPx(), cap = StrokeCap.Round))
        val thumb = point(fraction)
        drawCircle(accent.copy(alpha = 0.04f + .10f * emphasis), (7f + 4f * emphasis).dp.toPx(), thumb)
        drawCircle(accent.copy(alpha = .45f + .55f * emphasis), (4.5f + 3f * emphasis).dp.toPx(), thumb)
    }
}
