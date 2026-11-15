package com.example.ai_recorder_android.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 品牌间距令牌：全 App 的留白与密度统一来源，替代页面里散落的 dp 字面量。
 *
 * 层级约定：
 * - 页面横向边距统一 [page]（16dp），纵向块间距 [sectionGap]（12dp）；
 * - 卡片内边距：标准区块 [cardPad]，紧凑列表项 [cardPadTight]；
 * - 行内元素间隔用 xs/sm，分组间隔用 md/lg。
 */
object Spacing {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp

    /** 页面横向边距（LazyColumn contentPadding / Column padding 的水平值）。 */
    val page: Dp = 16.dp

    /** 页面纵向区块间距（spacedBy / 区块间 Spacer）。 */
    val sectionGap: Dp = 12.dp

    /** 标准卡片内边距（SectionCard 默认）。 */
    val cardPad: Dp = 16.dp

    /** 紧凑卡片内边距（列表项卡片）。 */
    val cardPadTight: Dp = 12.dp

    /** 细分隔线高度。 */
    val hairline: Dp = 1.dp
}

/**
 * 图标尺寸令牌：统一各页面随缘的 20/24/28/56 用法。
 * 录音圆钮图标（28dp）作为控件特例保留 [control]。
 */
object IconSize {
    /** 嵌入文本/紧凑行内。 */
    val sm: Dp = 16.dp

    /** CTA 按钮内部、chip 内部。 */
    val md: Dp = 20.dp

    /** 导航、按钮、IconButton 默认。 */
    val lg: Dp = 24.dp

    /** 录音圆钮等大控件图标。 */
    val control: Dp = 28.dp

    /** 空态主图标。 */
    val xl: Dp = 56.dp
}
