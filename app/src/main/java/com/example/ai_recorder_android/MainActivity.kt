package com.example.ai_recorder_android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.PreviewScreenSizes
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ai_recorder_android.ui.screens.DetailScreen
import com.example.ai_recorder_android.ui.screens.FileUploadScreen
import com.example.ai_recorder_android.ui.screens.HistoryScreen
import com.example.ai_recorder_android.ui.screens.HomeScreen
import com.example.ai_recorder_android.ui.screens.OnboardingScreen
import com.example.ai_recorder_android.ui.screens.SettingsScreen
import com.example.ai_recorder_android.ui.theme.AirecorderandroidTheme

/** 路由常量：底部导航三入口 + 无底部栏路由。 */
object Routes {
    const val HOME = "home"
    const val HISTORY = "history"
    const val DETAIL = "detail"
    const val SETTINGS = "settings"
    const val ONBOARDING = "onboarding"
    const val FILE_UPLOAD = "file_upload"
}

/** 底部导航项（home / history / settings 三个主入口）。 */
private data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: Int,
)

private val bottomNavItems = listOf(
    BottomNavItem(Routes.HOME, "Home", R.drawable.ic_home),
    BottomNavItem(Routes.HISTORY, "History", R.drawable.ic_history),
    BottomNavItem(Routes.SETTINGS, "Settings", R.drawable.ic_settings),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AirecorderandroidTheme {
                AirecorderandroidApp()
            }
        }
    }
}

@PreviewScreenSizes
@Composable
fun AirecorderandroidApp() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    // 底部导航仅在三个主入口路由显示；detail/onboarding/file_upload 为全屏路由
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
            startDestination = Routes.HOME,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Routes.HOME) {
                HomeScreen(onSettingsClick = { navController.navigate(Routes.SETTINGS) })
            }
            composable(Routes.HISTORY) { HistoryScreen(navController = navController) }
            composable("${Routes.DETAIL}/{recordingId}") { DetailScreen() }
            composable(Routes.SETTINGS) { SettingsScreen() }
            composable(Routes.ONBOARDING) { OnboardingScreen() }
            composable(Routes.FILE_UPLOAD) { FileUploadScreen() }
        }
    }
}