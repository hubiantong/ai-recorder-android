package com.example.ai_recorder_android.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * 品牌深色方案（主视觉）：深空海军蓝底 + 电光蓝主色 + 青/紫点缀。
 * 完整填充全部 M3 槽位，保证任何组件都不回退到默认紫色模板。
 */
private val BrandDarkColorScheme = darkColorScheme(
    primary = BrandBlue80,
    onPrimary = BrandBlue10,
    primaryContainer = BrandBlue30,
    onPrimaryContainer = BrandBlue90,
    inversePrimary = BrandDarkInversePrimary,
    secondary = BrandTeal80,
    onSecondary = BrandTeal10,
    secondaryContainer = BrandTeal30,
    onSecondaryContainer = BrandTeal90,
    tertiary = BrandViolet80,
    onTertiary = BrandViolet10,
    tertiaryContainer = BrandViolet30,
    onTertiaryContainer = BrandViolet90,
    background = BrandDarkBackground,
    onBackground = BrandDarkOnBackground,
    surface = BrandDarkSurface,
    onSurface = BrandDarkOnSurface,
    surfaceVariant = BrandDarkSurfaceVariant,
    onSurfaceVariant = BrandDarkOnSurfaceVariant,
    surfaceTint = BrandBlue80,
    inverseSurface = BrandDarkInverseSurface,
    inverseOnSurface = BrandDarkInverseOnSurface,
    error = BrandRedError80,
    onError = BrandRedErrorContainerDark,
    errorContainer = BrandRedErrorContainerDark,
    onErrorContainer = BrandRedOnErrorContainerDark,
    outline = BrandDarkOutline,
    outlineVariant = BrandDarkOutlineVariant,
    scrim = BrandDarkBackground,
    surfaceBright = BrandDarkSurfaceContainerHigh,
    surfaceDim = BrandDarkBackground,
    surfaceContainer = BrandDarkSurfaceContainer,
    surfaceContainerHigh = BrandDarkSurfaceContainerHigh,
    surfaceContainerHighest = Color(0xFF232D42),
    surfaceContainerLow = BrandDarkSurface,
    surfaceContainerLowest = Color(0xFF05080F),
)

/** 品牌浅色方案：同色相（电光蓝/青/紫）的浅色延续，保证品牌一致。 */
private val BrandLightColorScheme = lightColorScheme(
    primary = BrandBlue40,
    onPrimary = Color.White,
    primaryContainer = BrandBlue90,
    onPrimaryContainer = BrandBlue10,
    inversePrimary = BrandLightInversePrimary,
    secondary = BrandTeal40,
    onSecondary = Color.White,
    secondaryContainer = BrandTeal90,
    onSecondaryContainer = BrandTeal10,
    tertiary = BrandViolet40,
    onTertiary = Color.White,
    tertiaryContainer = BrandViolet90,
    onTertiaryContainer = BrandViolet10,
    background = BrandLightBackground,
    onBackground = BrandLightOnBackground,
    surface = BrandLightSurface,
    onSurface = BrandLightOnSurface,
    surfaceVariant = BrandLightSurfaceVariant,
    onSurfaceVariant = BrandLightOnSurfaceVariant,
    surfaceTint = BrandBlue40,
    inverseSurface = BrandLightInverseSurface,
    inverseOnSurface = BrandLightInverseOnSurface,
    error = BrandRedError40,
    onError = Color.White,
    errorContainer = BrandRedErrorContainerLight,
    onErrorContainer = BrandRedOnErrorContainerLight,
    outline = BrandLightOutline,
    outlineVariant = BrandLightOutlineVariant,
    scrim = BrandLightOnBackground,
    surfaceBright = BrandLightSurface,
    surfaceDim = BrandLightSurfaceContainer,
    surfaceContainer = BrandLightSurfaceContainer,
    surfaceContainerHigh = BrandLightSurfaceContainerHigh,
    surfaceContainerHighest = BrandLightSurfaceVariant,
    surfaceContainerLow = BrandLightSurface,
    surfaceContainerLowest = Color.White,
)

/**
 * 品牌主题入口。
 *
 * @param darkTheme 深浅模式（MainActivity 按 UserConfig.theme 决定：dark/light/system）
 * @param dynamicColor Android 12+ 动态配色（壁纸取色）；**默认关闭**，仅用户在设置页
 *   显式开启时覆盖品牌色（UserConfig.dynamicColor）。
 */
@Composable
fun AirecorderandroidTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> BrandDarkColorScheme
        else -> BrandLightColorScheme
    }

    CompositionLocalProvider(
        LocalBrandSemanticColors provides if (darkTheme) DarkBrandSemanticColors else LightBrandSemanticColors,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = AppShapes,
            content = content,
        )
    }
}
