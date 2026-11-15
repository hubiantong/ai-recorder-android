package com.example.ai_recorder_android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ai_recorder_android.ui.theme.IconSize
import com.example.ai_recorder_android.ui.theme.LocalBrandSemanticColors
import com.example.ai_recorder_android.ui.theme.Spacing

/**
 * 品牌主 CTA：渐变胶囊按钮（蓝→青，白字）+ 可选图标 + 按压缩放反馈。
 * 全 App 的全屏宽主入口共用（首页「开始录音」、引导「开始使用」、文件转写「开始转写」等）；
 * disabled 时回退中性容器色，与录音圆钮的禁用态一致。
 */
@Composable
fun GradientPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconRes: Int? = null,
    enabled: Boolean = true,
    height: Dp = 56.dp,
) {
    val scheme = MaterialTheme.colorScheme
    val semantic = LocalBrandSemanticColors.current
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .brandPress(onClick = onClick, enabled = enabled, clipShape = shape)
            .background(
                brush = if (enabled) semantic.accentGradientBrush else SolidColor(scheme.surfaceVariant),
                shape = shape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (iconRes != null) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = null,
                    tint = if (enabled) Color.White else scheme.onSurfaceVariant,
                    modifier = Modifier.size(IconSize.md),
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) Color.White else scheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 圆形主控按钮 + 下方文字标签（录音页控制区：开始/暂停/结束）。
 * Material Button 自带波纹与禁用态，这里叠加品牌按压缩放（[brandPressScale]）。
 * 无障碍语义由 icon 的 contentDescription 承载，下方可见标签仅供视觉（不重复聚焦）。
 */
@Composable
fun LabeledCircleButton(
    iconRes: Int,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    size: Dp,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = containerColor,
                contentColor = contentColor,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            contentPadding = PaddingValues(0.dp),
            interactionSource = interactionSource,
            modifier = Modifier
                .size(size)
                .brandPressScale(interactionSource),
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = label,
                modifier = Modifier.size(IconSize.control),
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // 可见标签纯视觉：无障碍语义已由按钮 icon 的 contentDescription 承载，避免 TalkBack 重复朗读
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}
