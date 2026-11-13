package com.example.ai_recorder_android.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * 录音前台服务的通知：渠道创建 + 常驻通知构建 + "停止录音"动作。
 *
 * 纯 JVM 可测部分（常量、[requiresNotificationPermission]、[formatElapsed]）
 * 由 `RecordingServiceTest` 覆盖；Context 相关（渠道创建/通知构建）需真机验证。
 */
object RecordingNotification {

    /** 通知渠道 id（todo 10 任务书指定语义：`RecordingChannel`）。 */
    const val CHANNEL_ID = "recording_channel"

    /** 通知渠道显示名。 */
    const val CHANNEL_NAME = "录音中"

    /** 前台通知 id（常驻，停止动作更新同一 id）。 */
    const val NOTIFICATION_ID = 1001

    /** 停止动作 PendingIntent 的 requestCode（固定即可，FLAG_UPDATE_CURRENT 复用）。 */
    private const val STOP_REQUEST_CODE = 0x5243 // "RC"

    /**
     * API 33+（TIRAMISU）需要 POST_NOTIFICATIONS 运行时权限；更低版本无需。
     * 纯函数，供 UI（todo 16）在启动服务前请求权限。
     */
    fun requiresNotificationPermission(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.TIRAMISU

    /** 通知权限是否已授予：API 33+ 检查 POST_NOTIFICATIONS，更低版本恒 true。 */
    fun hasNotificationPermission(context: Context): Boolean =
        !requiresNotificationPermission(Build.VERSION.SDK_INT) ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    /**
     * 创建通知渠道（API 26+；更低版本无渠道概念直接跳过）。幂等：
     * 渠道已存在则跳过，重复调用安全。
     */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW, // 常驻录音指示，不打扰用户
        ).apply {
            description = "录音进行中的常驻通知"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * 构建"录音中"常驻通知：ongoing + 低优先级 + "停止录音"动作
     * （PendingIntent.getService → [RecordingService.ACTION_STOP_RECORDING]）。
     *
     * @param recordingId 录音 id（当前仅用于日志/调试，动作 intent 不携带）
     * @param elapsedMs   已录音时长（毫秒），显示在内容文本
     */
    fun buildRecordingNotification(context: Context, recordingId: Long, elapsedMs: Long): Notification {
        val stopPendingIntent = PendingIntent.getService(
            context,
            STOP_REQUEST_CODE,
            RecordingService.stopIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE, // API 23+ 必须 IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now) // 系统麦克风图标，避免新增资源
            .setContentTitle(CHANNEL_NAME)
            .setContentText("已录音 ${formatElapsed(elapsedMs)}")
            .setOngoing(true) // 常驻不可滑动清除
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "停止录音", stopPendingIntent)
            .build()
    }

    /** 格式化录音时长 `mm:ss`（纯 JVM，可单测）。 */
    fun formatElapsed(elapsedMs: Long): String {
        val totalSec = elapsedMs / 1000
        val minutes = totalSec / 60
        val seconds = totalSec % 60
        return "%02d:%02d".format(minutes, seconds)
    }

    /**
     * 构建「转写中」通知（录音停止后、云端转写完成前的过渡态）：无停止动作、不可清除。
     * 复用 [NOTIFICATION_ID]（与录音通知互斥出现，服务存活期内先后替换）。
     */
    fun buildTranscribingNotification(context: Context, recordingId: Long): Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("语音转写")
            .setContentText("正在转写录音 #$recordingId，完成后可在详情查看")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
}