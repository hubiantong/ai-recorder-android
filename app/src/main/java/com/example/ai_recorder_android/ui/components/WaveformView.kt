package com.example.ai_recorder_android.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import com.example.ai_recorder_android.ui.theme.LocalBrandSemanticColors

/** 可视容量（柱位固定，历史不足时右对齐、超出时只画最新 160 根）。 */
private const val BAR_CAPACITY = 160

/**
 * 滚动柱状音频波形：柱子垂直居中上下镜像、圆头，最新帧固定在右端、随时间向左滚动。
 *
 * 数据源为引擎 PCM 帧包络（每 100ms 4 根，见 `AudioRecorderEngine.computeEnvelope`），
 * [bars] 为时间正序历史（末尾最新），滑窗裁剪由调用方负责（本组件最多渲染最新 160 根）。
 *
 * [active] = false（空闲/暂停）时波形整体变灰：有历史柱（暂停冻结）时呈低透明度圆点柱，
 * 空历史（空闲从未录音）时画一条低透明度中线示意波形出现的位置——
 * 与录音中的品牌渐变形成「一眼分辨」的状态对比。
 */
@Composable
fun WaveformView(
    bars: List<Float>,
    modifier: Modifier = Modifier,
    active: Boolean = true,
) {
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
    val accentStart = LocalBrandSemanticColors.current.accentGradientStart
    val accentEnd = LocalBrandSemanticColors.current.accentGradientEnd
    Canvas(modifier = modifier) {
        val slot = size.width / BAR_CAPACITY
        if (slot <= 0f) return@Canvas
        val barWidth = slot * 0.6f
        val centerY = size.height / 2f
        val maxHalf = size.height / 2f
        val minHalf = 2.dp.toPx()
        val visible = if (bars.size > BAR_CAPACITY) {
            bars.subList(bars.size - BAR_CAPACITY, bars.size)
        } else {
            bars
        }
        if (visible.isEmpty()) {
            // 空闲空历史：一条极淡中线示意波形将出现的位置（录音刚开始的短暂空窗不画）
            if (!active) {
                drawLine(
                    color = inactiveColor,
                    start = Offset(0f, centerY),
                    end = Offset(size.width, centerY),
                    strokeWidth = minHalf,
                    cap = StrokeCap.Round,
                )
            }
            return@Canvas
        }
        val offset = BAR_CAPACITY - visible.size
        for (j in visible.indices) {
            val slotIndex = offset + j
            val x = slotIndex * slot + slot / 2f
            val amplitude = visible[j].coerceIn(0f, 1f)
            val half = minHalf + (maxHalf - minHalf) * amplitude
            drawLine(
                color = barColor(slotIndex, active, inactiveColor, accentStart, accentEnd),
                start = Offset(x, centerY - half),
                end = Offset(x, centerY + half),
                strokeWidth = barWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** 柱色：活跃时横向铺品牌渐变（电光蓝→青），非活跃时统一低透明度灰。 */
private fun DrawScope.barColor(
    slotIndex: Int,
    active: Boolean,
    inactiveColor: Color,
    accentStart: Color,
    accentEnd: Color,
): Color {
    if (!active) return inactiveColor
    val fraction = slotIndex.toFloat() / (BAR_CAPACITY - 1)
    return lerp(accentStart, accentEnd, fraction)
}
