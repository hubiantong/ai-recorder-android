package com.example.ai_recorder_android.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RecordingService / RecordingNotification 纯 JVM 单测。
 *
 * 覆盖可脱离 Android 运行时的部分：通知渠道常量、intent action 字符串、
 * 命令分发（commandFor）、通知权限辅助、时长格式化。
 * 前台服务行为（startForeground → 通知存在 → 停止落盘）见
 * `RecordingServiceInstrumentedTest`（真机验证）。
 */
class RecordingServiceTest {

    // ---- 通知渠道常量 ----

    @Test
    fun channelConstants_matchPlan() {
        assertEquals("recording_channel", RecordingNotification.CHANNEL_ID)
        assertEquals("录音中", RecordingNotification.CHANNEL_NAME)
        assertTrue("NOTIFICATION_ID 应为正数", RecordingNotification.NOTIFICATION_ID > 0)
    }

    // ---- intent action 常量 ----

    @Test
    fun actionConstants_areDistinctAndNamespaced() {
        val actions = setOf(
            RecordingService.ACTION_START_RECORDING,
            RecordingService.ACTION_STOP_RECORDING,
            RecordingService.ACTION_PAUSE,
            RecordingService.ACTION_RESUME,
        )
        assertEquals("4 个 action 应互不相同", 4, actions.size)
        actions.forEach {
            assertTrue("action 应带包名前缀: $it", it.startsWith("com.example.ai_recorder_android.action."))
        }
    }

    @Test
    fun extraKeys_areStable() {
        assertEquals("output_dir", RecordingService.EXTRA_OUTPUT_DIR)
    }

    // ---- 命令分发（状态机辅助） ----

    @Test
    fun commandFor_mapsKnownActions() {
        assertEquals(RecordingService.Companion.Command.START, RecordingService.commandFor(RecordingService.ACTION_START_RECORDING))
        assertEquals(RecordingService.Companion.Command.STOP, RecordingService.commandFor(RecordingService.ACTION_STOP_RECORDING))
        assertEquals(RecordingService.Companion.Command.PAUSE, RecordingService.commandFor(RecordingService.ACTION_PAUSE))
        assertEquals(RecordingService.Companion.Command.RESUME, RecordingService.commandFor(RecordingService.ACTION_RESUME))
    }

    @Test
    fun commandFor_unknownOrNull_isUnknown() {
        assertEquals(RecordingService.Companion.Command.UNKNOWN, RecordingService.commandFor(null))
        assertEquals(RecordingService.Companion.Command.UNKNOWN, RecordingService.commandFor(""))
        assertEquals(
            RecordingService.Companion.Command.UNKNOWN,
            RecordingService.commandFor("com.example.ai_recorder_android.action.NOPE"),
        )
    }

    // ---- 通知权限辅助（API 33+ 才需要 POST_NOTIFICATIONS） ----

    @Test
    fun requiresNotificationPermission_onlyOnApi33Plus() {
        assertFalse(RecordingNotification.requiresNotificationPermission(32))
        assertTrue(RecordingNotification.requiresNotificationPermission(33))
        assertTrue(RecordingNotification.requiresNotificationPermission(34))
    }

    // ---- 时长格式化 ----

    @Test
    fun formatElapsed_formatsMinutesAndSeconds() {
        assertEquals("00:00", RecordingNotification.formatElapsed(0))
        assertEquals("00:05", RecordingNotification.formatElapsed(5_000))
        assertEquals("01:00", RecordingNotification.formatElapsed(60_000))
        assertEquals("12:34", RecordingNotification.formatElapsed(754_000))
        assertEquals("00:00", RecordingNotification.formatElapsed(999)) // 不足 1s 截断
    }
}