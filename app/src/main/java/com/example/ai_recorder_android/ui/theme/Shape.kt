package com.example.ai_recorder_android.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 品牌圆角体系：
 * - small(8dp)：小控件（输入框、小标签）
 * - medium(12dp)：标准卡片内容块
 * - large(16dp)：SectionCard / 气泡 / 播放器卡片
 * - extraLarge(28dp)：全屏底部弹层、主视觉卡片
 * 胶囊（chips）统一 50% 圆角（RoundedCornerShape(50)），不入 Shapes。
 */
val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
