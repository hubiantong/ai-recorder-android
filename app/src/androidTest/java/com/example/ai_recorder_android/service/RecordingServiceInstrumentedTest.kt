package com.example.ai_recorder_android.service

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * RecordingService 前台行为 instrumented 测试 —— **真机/模拟器验证**。
 *
 * 本机 Gradle worker 编码 bug（learnings.md），此测试不在本机运行；
 * 在其他机器执行 `./gradlew :app:connectedDebugAndroidTest` 验证。
 *
 * 覆盖（todo 10 验收标准）：
 * 1. startForegroundService(ACTION_START_RECORDING) → 服务进入前台 + 常驻通知存在
 * 2. ACTION_STOP_RECORDING → 引擎停止、WAV 落盘（seg_{rid}_0.wav）
 * 3. 降级路径：未授权 POST_NOTIFICATIONS 时服务仍可启动（不崩）
 *
 * 注：依赖清单无 androidx.test:rules（GrantPermissionRule 不可用），
 * 权限用 UiAutomation.grantRuntimePermission 授予（androidx.test:monitor 传递依赖）。
 */
@RunWith(AndroidJUnit4::class)
class RecordingServiceInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun grantPermissions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pkg = context.packageName
        instrumentation.uiAutomation.grantRuntimePermission(pkg, Manifest.permission.RECORD_AUDIO)
        instrumentation.uiAutomation.grantRuntimePermission(pkg, Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun startForeground_showsNotification_stopSavesWav() {
        val recordingId = System.currentTimeMillis()
        val outputDir = File(context.cacheDir, "rec_test_$recordingId")

        // 启动前台录音服务
        context.startForegroundService(RecordingService.startIntent(context, outputDir))

        // 1. 服务进入前台且录音中
        assertTrue(
            "服务应在 10s 内进入前台并开始录音",
            waitUntil(10_000) { RecordingService.isForeground && RecordingService.isRecording },
        )

        // 2. 常驻通知存在（渠道 recording_channel、id NOTIFICATION_ID）
        val nm = context.getSystemService(NotificationManager::class.java)
        val notificationVisible = nm.activeNotifications.any { it.id == RecordingNotification.NOTIFICATION_ID }
        assertTrue("前台通知应存在（id=${RecordingNotification.NOTIFICATION_ID}）", notificationVisible)

        // 3. 停止录音（通知"停止录音"动作走同一 ACTION_STOP_RECORDING）
        context.startService(RecordingService.stopIntent(context))
        assertTrue("服务应在 10s 内停止录音", waitUntil(10_000) { !RecordingService.isRecording })

        // 4. WAV 已落盘（44 字节 RIFF 头 + 数据；段文件名由 coordinator 引擎生成 seg_{rid}_{idx}.wav）
        val wavExists = outputDir.listFiles()?.any { it.name.startsWith("seg_") && it.extension == "wav" } == true
        assertTrue("停止后 outputDir 下应存在 seg_*.wav: ${outputDir.absolutePath}", wavExists)
    }

    @Test
    fun startWithoutNotificationPermission_stillRunsDegraded() {
        // 撤销通知权限后启动：服务应仍能录音（降级，通知不显示），不崩溃
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.revokeRuntimePermission(
            context.packageName,
            Manifest.permission.POST_NOTIFICATIONS,
        )
        val recordingId = System.currentTimeMillis() + 1
        val outputDir = File(context.cacheDir, "rec_test_deg_$recordingId")

        context.startForegroundService(RecordingService.startIntent(context, outputDir))

        assertTrue(
            "未授权通知权限时服务仍应录音（降级）",
            waitUntil(10_000) { RecordingService.isRecording },
        )
        context.startService(RecordingService.stopIntent(context))
        assertTrue(waitUntil(10_000) { !RecordingService.isRecording })
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            SystemClock.sleep(100)
        }
        return condition()
    }
}