package com.example.ai_recorder_android.ui.theme

import androidx.compose.ui.graphics.Color

// =====================================================================================
// 品牌色板（深色科技风为主视觉；浅色为同色相品牌延续）
//
// 设计语言：深空海军蓝底 + 电光蓝主色 + 青/紫点缀；录音场景用录音红强调。
// 与默认 Material 紫色模板彻底分离；Dynamic Color 仅在用户显式开启时生效（见 Theme.kt）。
// =====================================================================================

// ---------- 深色方案（主视觉） ----------

// 底色层级：background < surface < surfaceContainer（卡片）< surfaceVariant（嵌套内容）
val BrandDarkBackground = Color(0xFF0A0E1A)
val BrandDarkSurface = Color(0xFF101624)
val BrandDarkSurfaceContainer = Color(0xFF161D2E)
val BrandDarkSurfaceContainerHigh = Color(0xFF1C2537)
val BrandDarkSurfaceVariant = Color(0xFF1A2334)
val BrandDarkOnBackground = Color(0xFFE4EAF5)
val BrandDarkOnSurface = Color(0xFFE4EAF5)
val BrandDarkOnSurfaceVariant = Color(0xFF9AA7BF)

// 主色：电光蓝
val BrandBlue80 = Color(0xFF5B8DEF) // dark primary
val BrandBlue90 = Color(0xFFD6E4FF) // primaryContainer（dark）
val BrandBlue30 = Color(0xFF2A4A8F)
val BrandBlue10 = Color(0xFF0A1E52)

// 次色：青（转写/成功语义）
val BrandTeal80 = Color(0xFF64D2C5)
val BrandTeal90 = Color(0xFFBFF2EA)
val BrandTeal30 = Color(0xFF164A44)
val BrandTeal10 = Color(0xFF05302B)

// 三色：紫（AI/智能语义）
val BrandViolet80 = Color(0xFF9B8CFF)
val BrandViolet90 = Color(0xFFE5DEFF)
val BrandViolet30 = Color(0xFF372A6E)
val BrandViolet10 = Color(0xFF220F52)

// 错误
val BrandRedError80 = Color(0xFFFF7A7A)
val BrandRedError40 = Color(0xFFC03A3A)
val BrandRedErrorContainerDark = Color(0xFF5C1A1A)
val BrandRedOnErrorContainerDark = Color(0xFFFFDAD6)
val BrandRedErrorContainerLight = Color(0xFFFFDAD6)
val BrandRedOnErrorContainerLight = Color(0xFF410002)

// 描边
val BrandDarkOutline = Color(0xFF4A5872)
val BrandDarkOutlineVariant = Color(0xFF29334A)
val BrandLightOutline = Color(0xFF7A8598)
val BrandLightOutlineVariant = Color(0xFFC7CFDD)

// ---------- 浅色方案（同色相品牌延续） ----------

val BrandLightBackground = Color(0xFFF6F8FC)
val BrandLightSurface = Color(0xFFFFFFFF)
val BrandLightSurfaceContainer = Color(0xFFF0F3FA)
val BrandLightSurfaceContainerHigh = Color(0xFFE9EEF7)
val BrandLightSurfaceVariant = Color(0xFFE3E9F4)
val BrandLightOnBackground = Color(0xFF1A2130)
val BrandLightOnSurface = Color(0xFF1A2130)
val BrandLightOnSurfaceVariant = Color(0xFF5A6478)

val BrandBlue40 = Color(0xFF2A55C4)
val BrandTeal40 = Color(0xFF1D7A6F)
val BrandViolet40 = Color(0xFF6E4FD1)

// 反色（浅色方案基色之后定义，BrandLightInversePrimary 依赖 BrandBlue40）
val BrandDarkInverseSurface = Color(0xFFE4EAF5)
val BrandDarkInverseOnSurface = Color(0xFF1A2334)
val BrandDarkInversePrimary = Color(0xFFA5C3FF)
val BrandLightInverseSurface = Color(0xFF2C3444)
val BrandLightInverseOnSurface = Color(0xFFEEF1F7)
val BrandLightInversePrimary = BrandBlue40

// ---------- 语义扩展色（M3 槽位之外的品牌语义，组件直接引用） ----------

/** 录音中：录音红（录音指示/脉冲）。 */
val RecordingRed = Color(0xFFFF4D5E)

/** 成功/已完成。 */
val SuccessGreen = Color(0xFF4ADE80)

/** 警告。 */
val WarningAmber = Color(0xFFFBBF24)

/** AI 处理中（与 tertiary 紫呼应）。 */
val ProcessingViolet = Color(0xFF9B8CFF)

/** 品牌渐变（强调元素，如音量条/主按钮）。 */
val GradientAccentStart = Color(0xFF5B8DEF)
val GradientAccentEnd = Color(0xFF64D2C5)
