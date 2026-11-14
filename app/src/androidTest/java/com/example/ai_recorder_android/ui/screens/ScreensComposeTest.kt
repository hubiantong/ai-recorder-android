package com.example.ai_recorder_android.ui.screens

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.navigation.compose.rememberNavController
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ai_recorder_android.core.audio.RecordingAudioEngineFactory
import com.example.ai_recorder_android.data.db.AppDatabase
import com.example.ai_recorder_android.data.repo.RecordingRepository
import com.example.ai_recorder_android.data.repo.UserConfigRepository
import com.example.ai_recorder_android.data.storage.StorageService
import com.example.ai_recorder_android.service.RecordingCoordinator
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose 关键路径 instrumented 测试 —— **真机/模拟器验证**。
 *
 * 本机 Gradle worker 编码 bug（learnings.md），此测试不在本机运行；
 * 在其他机器执行 `./gradlew :app:connectedDebugAndroidTest` 验证。
 *
 * 覆盖任务书要求：HomeScreen（[RecordingScreen]）/ HistoryScreen / SettingsScreen 基本渲染。
 * 依赖 `androidx.compose.ui:ui-test-junit4` + `ui-test-manifest`（debugImplementation，已配置）。
 */
@RunWith(AndroidJUnit4::class)
class ScreensComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ---------- HomeScreen（RecordingScreen） ----------

    @Test
    fun homeScreen_rendersCoreElements() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val storage = StorageService(File(context.cacheDir, "compose_test_storage"))
        val repo = RecordingRepository(db.recordingDao(), db.attachmentDao(), storage)
        val coordinator = RecordingCoordinator(
            repository = repo,
            engineFactory = RecordingAudioEngineFactory { _, _, _, _ -> error("测试不应创建引擎") },
        )
        val vm = RecordingViewModel(context, coordinator, UserConfigRepository.fromContext(context))

        composeTestRule.setContent {
            RecordingScreen(
                viewModel = vm,
                onFileUploadClick = {},
                onSettingsClick = {},
            )
        }

        // 录音控制按钮
        composeTestRule.onNodeWithText("开始录音").assertIsDisplayed()
        composeTestRule.onNodeWithText("停止").assertIsDisplayed()
        // 音量指示条
        composeTestRule.onNodeWithText("音量").assertIsDisplayed()
        // 转写气泡空态
        composeTestRule.onNodeWithText("实时转写将显示在这里").assertIsDisplayed()
        // 档位胶囊（tier 来自 DataStore，用前缀匹配避免依赖持久化值）
        composeTestRule.onNodeWithText("档位 · ", substring = true).assertIsDisplayed()
        // 拍照 + 文件转写快捷入口
        composeTestRule.onNodeWithText("拍照").assertIsDisplayed()
        composeTestRule.onNodeWithText("文件转写").assertIsDisplayed()
    }

    // ---------- HistoryScreen ----------

    @Test
    fun historyScreen_rendersSearchBar() {
        composeTestRule.setContent {
            HistoryScreen(
                onRecordingClick = {},
                onFileUploadClick = {},
            )
        }

        // 搜索栏占位文案（不依赖 DB 状态，确定性渲染）
        composeTestRule.onNodeWithText("搜索标题或转写内容").assertIsDisplayed()
    }

    // ---------- SettingsScreen ----------

    @Test
    fun settingsScreen_rendersSections() {
        composeTestRule.setContent {
            SettingsScreen()
        }

        // 标题 + 分区头
        composeTestRule.onNodeWithText("设置").assertIsDisplayed()
        composeTestRule.onNodeWithText("转写模型").assertIsDisplayed()
        composeTestRule.onNodeWithText("VAD 自动分段").assertIsDisplayed()
        composeTestRule.onNodeWithText("会议模式").assertIsDisplayed()
        // 档位下拉当前值（默认 free → 免费版）
        composeTestRule.onNodeWithText("免费版").assertIsDisplayed()
    }
}