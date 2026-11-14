package com.example.ai_recorder_android.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.dp
import com.example.ai_recorder_android.ui.theme.GradientAccentEnd
import com.example.ai_recorder_android.ui.theme.GradientAccentStart

/**
 * 品牌音量条：品牌渐变（电光蓝→青）+ 圆角 + 平滑过渡的 RMS 电平条。
 * 替代默认 LinearProgressIndicator（0.0~1.0 电平输入）。
 */
@Composable
fun BrandVolumeBar(
    level: Float,
    modifier: Modifier = Modifier,
) {
    val animated by animateFloatAsState(
        targetValue = level.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 100),
        label = "volumeLevel",
    )
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    BoxWithConstraints(
        modifier = modifier
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(trackColor),
    ) {
        if (animated > 0f) {
            Box(
                modifier = Modifier
                    .width(maxWidth * animated)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        Brush.horizontalGradient(listOf(GradientAccentStart, GradientAccentEnd)),
                    ),
            )
        }
    }
}
