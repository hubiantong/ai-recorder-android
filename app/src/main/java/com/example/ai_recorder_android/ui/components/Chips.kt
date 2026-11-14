package com.example.ai_recorder_android.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.ai_recorder_android.data.db.AudioSource
import com.example.ai_recorder_android.data.db.RecordingStatus

/**
 * 品牌胶囊 chip：统一 TierChip/StatusChip 的复制粘贴样式。
 * pill 形（50% 圆角）+ 着色容器 + labelMedium。
 */
@Composable
fun BrandChip(
    text: String,
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
        color = container,
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = content,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

/**
 * 录音处理状态 chip：`status` + `hasNote`（summary 非空）映射为三态——
 * - 失败（FAILED）：错误红容器
 * - AI 处理中（PROCESSING/PENDING，或 COMPLETED 但笔记未生成）：紫（AI 语义）
 * - 已完成：青（成功语义）
 */
@Composable
fun RecordingStatusChip(status: RecordingStatus, hasNote: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (label, container, content) = when {
        status == RecordingStatus.FAILED -> Triple("失败", scheme.errorContainer, scheme.onErrorContainer)
        status == RecordingStatus.COMPLETED && hasNote -> Triple("已完成", scheme.secondaryContainer, scheme.onSecondaryContainer)
        else -> Triple("AI 处理中", scheme.tertiaryContainer, scheme.onTertiaryContainer)
    }
    BrandChip(text = label, container = container, content = content, modifier = modifier)
}

/**
 * 录音类型 chip：会议模式 → 「会议」（主色，强调）；普通录音 → 「标准」（中性）。
 */
@Composable
fun RecordingTypeChip(meetingMode: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    if (meetingMode) {
        BrandChip(text = "会议", container = scheme.primaryContainer, content = scheme.onPrimaryContainer, modifier = modifier)
    } else {
        BrandChip(text = "标准", container = scheme.surfaceVariant, content = scheme.onSurfaceVariant, modifier = modifier)
    }
}

/** 音源标签（会议模式音源）：mic → 麦克风 / system → 系统音频 / mixed → 混合。 */
fun AudioSource.label(): String = when (this) {
    AudioSource.MIC -> "麦克风"
    AudioSource.SYSTEM -> "系统音频"
    AudioSource.MIXED -> "混合音源"
}
