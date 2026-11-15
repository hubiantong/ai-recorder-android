package com.example.ai_recorder_android.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 组件尺寸令牌：控件本身的固有尺寸（间距用 [Spacing]，图标用 [IconSize]）。
 *
 * 录音主控圆钮为设计规格尺寸（开始 80 / 暂停↔继续 72 / 结束 64），
 * 档位差异即层级差异，勿在调用点各自散落字面量。
 */
object Sizes {
    /** 「开始录音」大圆钮。 */
    val circleStart: Dp = 80.dp

    /** 「暂停 ↔ 继续」主圆钮。 */
    val circlePrimary: Dp = 72.dp

    /** 「结束」圆钮。 */
    val circleStop: Dp = 64.dp

    /** 主控圆钮之间的间距。 */
    val controlsGap: Dp = 40.dp

    /** 次级紧凑按钮（拍照/文件转写）高度。 */
    val secondaryButtonHeight: Dp = 40.dp

    /** 波形最小高度（小屏兜底）。 */
    val waveHeightMin: Dp = 72.dp

    /** 波形最大高度（平板/折叠屏封顶）。 */
    val waveHeightMax: Dp = 120.dp

    /**
     * 波形高度占屏幕高度比例：0.12 × 屏高，833dp 屏 ≈ 100dp（与历史固定值持平），
     * 结果经 [Sizes.waveHeightMin]～[Sizes.waveHeightMax] 夹取。
     */
    const val waveScreenFraction: Float = 0.12f
}
