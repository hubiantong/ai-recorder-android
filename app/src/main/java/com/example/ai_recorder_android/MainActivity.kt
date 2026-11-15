package com.example.ai_recorder_android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.ai_recorder_android.data.repo.UserConfig
import com.example.ai_recorder_android.data.repo.UserConfigRepository
import com.example.ai_recorder_android.ui.screens.DetailScreen
import com.example.ai_recorder_android.ui.screens.FileUploadScreen
import com.example.ai_recorder_android.ui.screens.HistoryScreen
import com.example.ai_recorder_android.ui.screens.HomeScreen
import com.example.ai_recorder_android.ui.screens.OnboardingScreen
import com.example.ai_recorder_android.ui.screens.RecordingScreen
import com.example.ai_recorder_android.ui.screens.SettingsScreen
import com.example.ai_recorder_android.ui.theme.AirecorderandroidTheme

/** 路由常量：底部导航三入口 + 无底部栏路由（onboarding/detail/file_upload/recording 全屏）。 */
object Routes {
    const val HOME = "home"
    const val HISTORY = "history"
    const val DETAIL = "detail"
    const val SETTINGS = "settings"
    const val ONBOARDING = "onboarding"
    const val FILE_UPLOAD = "file_upload"

    /**
     * 录音页路由（无底部栏的专注界面，从首页「开始录音」进入）。
     * autoStart=true：进入后自动请求麦克风权限并开始录音（首页 CTA 一键直达）。
     */
    const val RECORDING = "recording?autoStart={autoStart}"

    /** 构造录音页导航地址（省略 autoStart 时取默认值 false）。 */
    fun recordingRoute(autoStart: Boolean = false) =
        if (autoStart) "recording?autoStart=true" else "recording"
}

/** 底部导航项（home / history / settings 三个主入口）。 */
private data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: Int,
)

// 与全应用一致的中文文案（应用为单一中文界面，文案硬编码策略见项目约定）
private val bottomNavItems = listOf(
    BottomNavItem(Routes.HOME, "首页", R.drawable.ic_home),
    BottomNavItem(Routes.HISTORY, "历史", R.drawable.ic_history),
    BottomNavItem(Routes.SETTINGS, "设置", R.drawable.ic_settings),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            // 用户配置驱动主题（模式 + 动态配色）与启动目的地（onboarding 门控）
            val config by UserConfigRepository.fromContext(applicationContext)
                .userConfig
                .collectAsState(initial = null)
            AirecorderandroidTheme(
                darkTheme = when (config?.theme) {
                    "dark" -> true
                    "light" -> false
                    else -> isSystemInDarkTheme()
                },
                dynamicColor = config?.dynamicColor ?: false,
            ) {
                AirecorderandroidApp(config = config)
            }
        }
    }
}

/**
 * 应用根：NavHost + 底部导航。
 *
 * **onboarding 门控**：配置未加载完成前渲染品牌底色空白页（避免首帧闪 HOME 再跳引导）；
 * 加载完成后按 `onboardingDone` 决定 startDestination——未完成首启直接进引导页。
 * [config] 由 MainActivity 注入；预览传固定值即可预览主界面。
 */
@PreviewScreenSizes
@Composable
fun AirecorderandroidApp(config: UserConfig? = null) {
    if (config == null) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            // 配置加载中：暂不渲染导航，防止引导页/主页错误闪现
        }
        return
    }

    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    // 底部导航仅在三个主入口路由显示；onboarding/detail/file_upload 为全屏路由
    val showBottomBar = bottomNavItems.any { item ->
        currentDestination?.hierarchy?.any { it.route == item.route } == true
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomNavItems.forEach { item ->
                        val selected =
                            currentDestination?.hierarchy?.any { it.route == item.route } == true
                        NavigationBarItem(
                            icon = {
                                Icon(
                                    painterResource(item.icon),
                                    contentDescription = item.label
                                )
                            },
                            label = { Text(item.label) },
                            selected = selected,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            // 未完成首启引导：直接落在引导页（完成后 popUpTo 引导页自身，落 HOME）
            startDestination = if (config.onboardingDone) Routes.HOME else Routes.ONBOARDING,
            modifier = Modifier.padding(innerPadding),
            // 品牌页面过渡：淡入 + 轻微滑动（克制，200ms 量级）
            enterTransition = {
                fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 8 }
            },
            exitTransition = { fadeOut(tween(180)) },
            popEnterTransition = { fadeIn(tween(220)) },
            popExitTransition = {
                fadeOut(tween(180)) + slideOutHorizontally(tween(220)) { it / 8 }
            },
        ) {
            composable(Routes.HOME) {
                HomeScreen(
                    onStartRecordingClick = {
                        navController.navigate(Routes.recordingRoute(autoStart = true))
                    },
                    onOpenActiveRecordingClick = {
                        navController.navigate(Routes.recordingRoute())
                    },
                    onRecordingClick = { id -> navController.navigate("${Routes.DETAIL}/$id") },
                    onSeeAllHistoryClick = { navController.navigate(Routes.HISTORY) },
                )
            }
            composable(
                Routes.RECORDING,
                arguments = listOf(
                    navArgument("autoStart") {
                        type = NavType.BoolType
                        defaultValue = false
                    },
                ),
            ) { entry ->
                RecordingScreen(
                    onFileUploadClick = { navController.navigate(Routes.FILE_UPLOAD) },
                    onSettingsClick = { navController.navigate(Routes.SETTINGS) },
                    onBackClick = { navController.popBackStack() },
                    autoStart = entry.arguments?.getBoolean("autoStart") ?: false,
                )
            }
            composable(Routes.HISTORY) {
                HistoryScreen(
                    onRecordingClick = { id -> navController.navigate("${Routes.DETAIL}/$id") },
                    onFileUploadClick = { navController.navigate(Routes.FILE_UPLOAD) },
                )
            }
            composable("${Routes.DETAIL}/{recordingId}") {
                DetailScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.SETTINGS) { SettingsScreen() }
            composable(Routes.ONBOARDING) {
                OnboardingScreen(
                    onComplete = {
                        navController.navigate(Routes.HOME) {
                            popUpTo(Routes.ONBOARDING) { inclusive = true }
                        }
                    },
                )
            }
            composable(Routes.FILE_UPLOAD) {
                FileUploadScreen(onBack = { navController.popBackStack() })
            }
        }
    }
}
