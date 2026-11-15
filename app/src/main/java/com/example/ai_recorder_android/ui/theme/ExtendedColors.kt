package com.example.ai_recorder_android.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * M3 槽位之外的品牌语义色：录音状态（红/琥珀）、AI 处理（紫）、品牌强调渐变。
 * 通过 CompositionLocal 注入，深浅主题各自取值（浅色下加深保证可读对比），
 * 组件统一经 `LocalBrandSemanticColors.current` 读取，不再直接引用裸色值。
 */
@Immutable
data class BrandSemanticColors(
    /** 录音中/录音指示（脉冲点、状态文本）。 */
    val recording: Color,
    /** 警告/暂停中。 */
    val warning: Color,
    /** AI 处理中（与 tertiary 紫呼应）。 */
    val processing: Color,
    /** 品牌强调渐变起止（主 CTA、波形激活段）。 */
    val accentGradientStart: Color,
    val accentGradientEnd: Color,
) {
    /** 横向品牌渐变（蓝→青），用于主 CTA 背景等大面积强调。 */
    val accentGradientBrush: Brush
        get() = Brush.horizontalGradient(listOf(accentGradientStart, accentGradientEnd))
}

/** 深色方案语义色（主视觉，取原始品牌色值）。 */
val DarkBrandSemanticColors = BrandSemanticColors(
    recording = Color(0xFFFF4D5E),
    warning = Color(0xFFFBBF24),
    processing = Color(0xFF9B8CFF),
    accentGradientStart = Color(0xFF5B8DEF),
    accentGradientEnd = Color(0xFF64D2C5),
)

/** 浅色方案语义色：同色相加深，保证在白底上的文本可读性。 */
val LightBrandSemanticColors = BrandSemanticColors(
    recording = Color(0xFFD93644),
    warning = Color(0xFFB45309),
    processing = Color(0xFF6E4FD1),
    // 渐变用于白字填充容器，浅色下保持鲜艳品牌观感
    accentGradientStart = Color(0xFF5B8DEF),
    accentGradientEnd = Color(0xFF64D2C5),
)

val LocalBrandSemanticColors = staticCompositionLocalOf { DarkBrandSemanticColors }
