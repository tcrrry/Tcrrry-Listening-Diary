package com.tcrrry.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal const val ANOMALY_DAILY_EFFECTIVE_MIN = 8
internal const val ANOMALY_CONSECUTIVE_MIN = 5
internal const val ANOMALY_BURST_RUN_MIN = 4

internal data class DailyAnomalyInfo(
    val trackId: String,
    val effectivePlays: Int,
    val totalVisits: Int,
    val maxConsecutivePlays: Int,
    val totalMs: Long,
    val recommendedPlays: Int
)

internal data class DailyCorrectionUndoInfo(
    val day: String,
    val trackId: String,
    val keptPlays: Int,
    val removedEffective: Int,
    val removedMs: Long,
    val createdAt: Long
)

/** Detects abnormally high single-day replay counts or long consecutive repeat streaks. */
internal fun analyzeDailyPlaybackAnomaly(dayHistory: List<Play>, trackId: String): DailyAnomalyInfo? {
    val sorted = dayHistory.sortedBy { it.start }
    val matched = sorted.filter { it.id == trackId }
    val effectivePlays = matched.count { it.effective > 0 }
    if (effectivePlays <= 1) return null

    var maxConsecutivePlays = 0
    var normalEffective = 0
    var burstCount = 0
    var index = 0
    while (index < sorted.size) {
        if (sorted[index].id != trackId) {
            index++
            continue
        }
        var runEffective = 0
        while (index < sorted.size && sorted[index].id == trackId) {
            if (sorted[index].effective > 0) runEffective++
            index++
        }
        if (runEffective > maxConsecutivePlays) maxConsecutivePlays = runEffective
        if (runEffective >= ANOMALY_BURST_RUN_MIN) burstCount++ else normalEffective += runEffective
    }

    if (effectivePlays < ANOMALY_DAILY_EFFECTIVE_MIN && maxConsecutivePlays < ANOMALY_CONSECUTIVE_MIN) {
        return null
    }

    val recommendedPlays = if (burstCount > 0) {
        (normalEffective + burstCount).coerceIn(1, minOf(3, effectivePlays - 1))
    } else {
        minOf(3, maxOf(1, effectivePlays / 4)).coerceAtMost(effectivePlays - 1)
    }

    return DailyAnomalyInfo(
        trackId = trackId,
        effectivePlays = effectivePlays,
        totalVisits = matched.size,
        maxConsecutivePlays = maxConsecutivePlays,
        totalMs = matched.sumOf { it.ms },
        recommendedPlays = recommendedPlays
    )
}

/** Keeps the earliest [targetEffectivePlays] effective visits and marks all later visits for removal. */
internal fun selectVisitsToRemoveForCorrection(dayTrackPlays: List<Play>, targetEffectivePlays: Int): List<Play> {
    val sorted = dayTrackPlays.sortedBy { it.start }
    if (targetEffectivePlays <= 0) return sorted
    var keptEffective = 0
    val toRemove = ArrayList<Play>()
    for (play in sorted) {
        if (keptEffective < targetEffectivePlays) {
            if (play.effective > 0) keptEffective++
        } else {
            toRemove.add(play)
        }
    }
    return toRemove
}

@Composable
internal fun DailyPlaybackCorrectionCard(
    anomaly: DailyAnomalyInfo?,
    undoInfo: DailyCorrectionUndoInfo?,
    dayTrackPlays: List<Play>,
    busy: Boolean,
    onApply: (targetPlays: Int, removedEffective: Int, removedMs: Long, visitsToRemove: Set<String>) -> Unit,
    onUndo: () -> Unit
) {
    if (anomaly == null && undoInfo == null) return

    var dialogOpen by remember(anomaly?.trackId, anomaly?.effectivePlays) { mutableStateOf(false) }

    val cardTag = if (undoInfo != null && anomaly == null) "correction-undo-card" else "anomaly-correction-card"
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(cardTag)
            .clip(RoundedCornerShape(18.dp))
            .background(Accent.copy(alpha = .07f))
            .border(0.5.dp, Accent.copy(alpha = .18f), RoundedCornerShape(18.dp))
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            when {
                anomaly != null && undoInfo == null -> {
                    Text("检测到异常高频播放", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Accent)
                    val detail = if (anomaly.maxConsecutivePlays >= ANOMALY_CONSECUTIVE_MIN) {
                        "当日播放 ${anomaly.effectivePlays} 次 · 连续循环 ${anomaly.maxConsecutivePlays} 次"
                    } else {
                        "当日播放 ${anomaly.effectivePlays} 次 · ${shortDuration(anomaly.totalMs)}"
                    }
                    Text(detail, fontSize = 12.sp, color = Muted)
                }
                anomaly == null && undoInfo != null -> {
                    Text("已修正当日播放次数", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Accent)
                    Text(
                        "已移除 ${undoInfo.removedEffective} 次（约 ${shortDuration(undoInfo.removedMs)}），当前保留 ${undoInfo.keptPlays} 次",
                        fontSize = 12.sp,
                        color = Muted
                    )
                }
                else -> {
                    Text("已修正当日播放（仍偏高）", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Accent)
                    Text(
                        "当前 ${anomaly!!.effectivePlays} 次 · 已移除 ${undoInfo!!.removedEffective} 次",
                        fontSize = 12.sp,
                        color = Muted
                    )
                }
            }
        }
        Spacer(Modifier.width(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (undoInfo != null) {
                val undoLabel = if (busy) "处理中…" else if (anomaly == null) "撤销修正" else "撤销"
                Text(
                    undoLabel,
                    Modifier
                        .clip(CircleShape)
                        .background(if (anomaly == null) Accent.copy(alpha = if (busy) .55f else 1f) else Accent.copy(alpha = .14f))
                        .press { if (!busy) onUndo() }
                        .padding(horizontal = 13.dp, vertical = 7.dp)
                        .testTag("undo-correction-button"),
                    color = if (anomaly == null) Color.White else Accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (anomaly != null) {
                Text(
                    if (busy) "修正中…" else if (undoInfo == null) "修正次数" else "继续修正",
                    Modifier
                        .clip(CircleShape)
                        .background(Accent.copy(alpha = if (busy) .55f else 1f))
                        .press { if (!busy) dialogOpen = true }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                        .testTag("open-correction-dialog"),
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }

    if (dialogOpen && anomaly != null) {
        var targetPlays by remember(anomaly.trackId, anomaly.effectivePlays) {
            mutableIntStateOf(anomaly.recommendedPlays)
        }
        val toRemove = remember(dayTrackPlays, targetPlays) {
            selectVisitsToRemoveForCorrection(dayTrackPlays, targetPlays)
        }
        val removedEffective = toRemove.count { it.effective > 0 }
        val removedMs = toRemove.sumOf { it.ms }
        val presets = remember(anomaly.effectivePlays, anomaly.recommendedPlays) {
            buildList {
                add(anomaly.recommendedPlays to "一键推荐 (${anomaly.recommendedPlays}次)")
                for (count in listOf(1, 2, 3, 5)) {
                    if (count < anomaly.effectivePlays && count != anomaly.recommendedPlays) {
                        add(count to "${count}次")
                    }
                }
                add(0 to "全部清除")
            }
        }

        AlertDialog(
            onDismissRequest = { if (!busy) dialogOpen = false },
            title = { Text("修正当日播放记录", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        presets.forEach { (value, label) ->
                            FilterChip(
                                selected = targetPlays == value,
                                onClick = { targetPlays = value },
                                label = { Text(label, fontSize = 12.sp) }
                            )
                        }
                    }

                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(Muted.copy(alpha = .08f))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(
                            onClick = { if (targetPlays > 0) targetPlays-- },
                            enabled = targetPlays > 0 && !busy,
                            modifier = Modifier.testTag("correction-minus")
                        ) {
                            Text("−", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            if (targetPlays == 0) "清除当日全部记录" else "保留最早 $targetPlays 次有效播放",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.testTag("correction-target-label")
                        )
                        TextButton(
                            onClick = { if (targetPlays < anomaly.effectivePlays - 1) targetPlays++ },
                            enabled = targetPlays < anomaly.effectivePlays - 1 && !busy,
                            modifier = Modifier.testTag("correction-plus")
                        ) {
                            Text("+", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Text(
                        "将移除 $removedEffective 次有效播放（共 ${toRemove.size} 条记录），约减少 ${shortDuration(removedMs)}。\n修正将同步生效于日/周/月/年/全部统计与导出数据，采集器不会重复补回；修正后可在当前位置随时撤销。",
                        fontSize = 12.sp,
                        color = Muted
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val visits = toRemove.map { it.visit }.toSet()
                        dialogOpen = false
                        onApply(targetPlays, removedEffective, removedMs, visits)
                    },
                    enabled = toRemove.isNotEmpty() && !busy,
                    modifier = Modifier.testTag("confirm-correction")
                ) {
                    Text("确认修正", color = Accent, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { dialogOpen = false },
                    enabled = !busy
                ) {
                    Text("取消", color = Muted)
                }
            }
        )
    }
}
