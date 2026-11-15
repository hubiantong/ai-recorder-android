package com.example.ai_recorder_android.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ai_recorder_android.ui.theme.Spacing

/** 卡片强调层级：Normal 常规区块；High 强调卡（如「录音进行中」横幅），容器色抬一级。 */
enum class CardEmphasis { Normal, High }

/**
 * 品牌区块卡片（全 App 唯一卡片体系，替代散落的 Surface/Card 复制）：
 * - 圆角取 [MaterialTheme.shapes.large]，容器色 surfaceContainer（High 抬到 surfaceContainerHigh）；
 * - 1dp outlineVariant 细描边（满值——M3 中 outlineVariant 本就是 subtle border 语义，
 *   深色补层次、浅色补足卡片与背景的对比）；
 * - [onClick] 可选：统一走 [brandPress]（按压缩放 + 波纹），调用方不再自行贴 clickable。
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(Spacing.cardPad),
    onClick: (() -> Unit)? = null,
    emphasis: CardEmphasis = CardEmphasis.Normal,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.large
    val pressable = if (onClick != null) {
        modifier.brandPress(onClick = onClick, clipShape = shape)
    } else {
        modifier
    }
    Surface(
        shape = shape,
        color = if (emphasis == CardEmphasis.High) scheme.surfaceContainerHigh else scheme.surfaceContainer,
        border = BorderStroke(1.dp, scheme.outlineVariant),
        modifier = pressable.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

/** 区块标题（详情/设置等页面的分区头）。 */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}
