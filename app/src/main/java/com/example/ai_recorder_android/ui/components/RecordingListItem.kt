package com.example.ai_recorder_android.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.example.ai_recorder_android.data.db.RecordingEntity
import com.example.ai_recorder_android.data.db.RecordingStatus
import com.example.ai_recorder_android.ui.theme.Spacing
import com.example.ai_recorder_android.ui.theme.tabularNums
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 录音记录列表项卡片（全 App 唯一实现，替代首页/历史两份近重复代码）：
 * 标题 + 类型/状态 chip 行 + 日期·时长（tnum 数字排版）+ 按状态的内容行。
 *
 * - 失败（FAILED）：errorMessage 红字（最多两行）；
 * - 无笔记：「AI 笔记生成中…」（紫，AI 语义）；
 * - 已完成且 [showSummaryPreview]：AI 笔记首行内容预览（跳过 markdown 标题行）。
 *
 * 首页「最近录音」传 [showSummaryPreview] = false（笔记预览由笔记区承担，不重复展示）；
 * 首页「最近 AI 笔记」传 [showTypeChip] = false（纯笔记视角，不标状态）。
 */
@Composable
fun RecordingListItem(
    recording: RecordingEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showTypeChip: Boolean = true,
    showSummaryPreview: Boolean = true,
) {
    SectionCard(
        contentPadding = PaddingValues(Spacing.cardPadTight),
        onClick = onClick,
        modifier = modifier,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = recordingTitle(recording),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (showTypeChip) {
                Spacer(modifier = Modifier.width(Spacing.sm))
                RecordingTypeChip(meetingMode = recording.meetingMode)
                Spacer(modifier = Modifier.width(Spacing.xs))
                RecordingStatusChip(
                    status = recording.status,
                    hasNote = recording.summary != null,
                )
            }
        }
        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
            text = recordingMeta(recording),
            style = MaterialTheme.typography.bodySmall.tabularNums(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // 内容行自带与 meta 的间距：无内容行（已完成且不展示预览）时不留多余底部空白
        when {
            recording.status == RecordingStatus.FAILED -> {
                Spacer(modifier = Modifier.height(Spacing.sm))
                Text(
                    text = recording.errorMessage ?: "录音处理失败",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            recording.summary == null -> {
                Spacer(modifier = Modifier.height(Spacing.sm))
                Text(
                    text = "AI 笔记生成中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            showSummaryPreview -> {
                Spacer(modifier = Modifier.height(Spacing.sm))
                Text(
                    text = notePreview(recording.summary) ?: "点击查看完整笔记",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ---------- 纯函数（可 JVM 单测；首页/历史/列表项共用） ----------

/** 笔记预览：跳过 markdown `#` 标题行与空行，取首个内容行，避免预览出现标记符号。 */
internal fun notePreview(summary: String?): String? {
    if (summary.isNullOrBlank()) return null
    return summary.lines()
        .firstOrNull { line ->
            val trimmed = line.trim()
            trimmed.isNotEmpty() && !trimmed.startsWith("#")
        }
        ?.trim()
}

/** 列表标题：title 非空白用 title，否则默认 "录音 yyyy-MM-dd HH:mm:ss"（对齐 PC `_format_title`）。 */
internal fun recordingTitle(
    recording: RecordingEntity,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    recording.title?.takeIf { it.isNotBlank() }?.let { return it }
    return "录音 " + Instant.ofEpochMilli(recording.createdAt)
        .atZone(zoneId)
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.CHINA))
}

/** 副标题：MM-dd HH:mm · mm:ss（时长缺失时省略，对齐 PC `_format_meta`）。 */
internal fun recordingMeta(
    recording: RecordingEntity,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    val date = Instant.ofEpochMilli(recording.createdAt)
        .atZone(zoneId)
        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.CHINA))
    val duration = recording.audioDurationSeconds?.let { formatDuration(it) }
    return if (duration != null) "$date · $duration" else date
}

/** 时长格式化：mm:ss；≥1 小时显示 h:mm:ss；null 显示 00:00。 */
internal fun formatDuration(seconds: Double?): String {
    if (seconds == null) return "00:00"
    val total = seconds.toLong().coerceAtLeast(0L)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, secs)
    } else {
        "%02d:%02d".format(minutes, secs)
    }
}
