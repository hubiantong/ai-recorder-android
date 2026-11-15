package com.example.ai_recorder_android.ui.screens

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.ai_recorder_android.core.audio.RecordingAudioEngineFactory
import com.example.ai_recorder_android.data.db.AppDatabase
import com.example.ai_recorder_android.data.db.RecordingEntity
import com.example.ai_recorder_android.data.db.RecordingStatus
import com.example.ai_recorder_android.data.repo.RecordingRepository
import com.example.ai_recorder_android.data.repo.UserConfigRepository
import com.example.ai_recorder_android.data.storage.StorageService
import com.example.ai_recorder_android.service.RecordingCoordinator
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Compose 关键路径 instrumented 测试 —— **真机/模拟器验证**。
 *
 * 本机 Gradle worker 编码 bug（learnings.md），此测试不在本机运行；
 * 在其他机器执行 `./gradlew :app:connectedDebugAndroidTest` 验证。
 *
 * 覆盖任务书要求：HomeScreen（工作台）/ RecordingScreen（专注录音页）/ HistoryScreen / SettingsScreen 基本渲染。
 * 依赖 `androidx.compose.ui:ui-test-junit4` + `ui-test-manifest`（debugImplementation，已配置）。
 */
@RunWith(AndroidJUnit4::class)
class ScreensComposeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // ---------- HomeScreen（AI 录音笔记工作台） ----------

    @Test
    fun homeScreen_rendersDashboardElements() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val storage = StorageService(File(context.cacheDir, "compose_test_home_storage"))
        val repo = RecordingRepository(db.recordingDao(), db.attachmentDao(), storage)
        val coordinator = RecordingCoordinator(
            repository = repo,
            engineFactory = RecordingAudioEngineFactory { _, _, _, _ -> error("测试不应创建引擎") },
        )
        val vm = HomeViewModel(db.recordingDao(), coordinator)

        // 一条已完成（含 AI 笔记）的录音：笔记区/录音区应展示，空态不出现
        runBlocking {
            db.recordingDao().insertRecording(
                RecordingEntity(
                    title = "测试会议录音",
                    audioPath = "test.wav",
                    summary = "## 会议摘要\n- 要点一",
                    status = RecordingStatus.COMPLETED,
                    createdAt = System.currentTimeMillis(),
                ),
            )
        }

        composeTestRule.setContent {
            HomeScreen(
                viewModel = vm,
                onStartRecordingClick = {},
                onOpenActiveRecordingClick = {},
                onRecordingClick = {},
                onSeeAllHistoryClick = {},
            )
        }

        // 主 CTA
        composeTestRule.onNodeWithText("开始录音").assertIsDisplayed()
        // 分区标题
        composeTestRule.onNodeWithText("最近 AI 笔记").assertIsDisplayed()
        composeTestRule.onNodeWithText("最近录音").assertIsDisplayed()
        // 笔记/录音卡片内容（标题 + 预览跳过 ## 标题行）
        composeTestRule.onNodeWithText("测试会议录音").assertIsDisplayed()
        composeTestRule.onNodeWithText("要点一", substring = true).assertIsDisplayed()
        // 有记录时空态不出现
        composeTestRule.onNodeWithText("还没有录音").assertDoesNotExist()
    }

    // ---------- RecordingScreen（专注录音页） ----------

    @Test
    fun recordingScreen_rendersCoreElements() {
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

        // 页面主角：空闲状态文案 + 大计时器归零
        composeTestRule.onNodeWithText("准备就绪").assertIsDisplayed()
        composeTestRule.onNodeWithText("00:00").assertIsDisplayed()
        // 主控：空闲态仅有开始录音圆钮（无停止钮）——圆钮语义经 icon contentDescription 暴露
        composeTestRule.onNodeWithContentDescription("开始录音").assertIsDisplayed()
        // 转写气泡空态
        composeTestRule.onNodeWithText("实时转写将显示在这里").assertIsDisplayed()
        // 档位胶囊（tier 来自 DataStore，用前缀匹配避免依赖持久化值）
        composeTestRule.onNodeWithText("档位 · ", substring = true).assertIsDisplayed()
        // 拍照 + 文件转写次级入口
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