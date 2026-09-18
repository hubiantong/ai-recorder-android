package com.example.ai_recorder_android.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.example.ai_recorder_android.App
import java.io.File
import kotlinx.coroutines.*

/**
 * 前台录音服务：委托应用级共享 [RecordingCoordinator]（[App.recordingCoordinator]）编排录音，
 * 常驻通知（[RecordingNotification]）+ "停止录音"动作，停止时由 coordinator 保存 WAV 并落库。
 *
 * 生命周期（对齐 PC `pc_api/recording_service.py`）：
 * - `ACTION_START_RECORDING`（携带 outputDir）→ coordinator.startRecording → startForeground
 * - `ACTION_PAUSE` / `ACTION_RESUME` → 委托 coordinator（暂停不停止音频流）
 * - `ACTION_STOP_RECORDING` → coordinator.stop（finalize 段 WAV、段落库）→ 移除前台通知
 *
 * 降级策略：POST_NOTIFICATIONS 未授权（API 33+）或 startForeground 抛异常时
 * 服务仍继续录音（通知不显示），不崩溃。
 *
 * 注意：本 todo 不实现 MediaProjection（todo 11）；Manifest 已声明
 * `foregroundServiceType="microphone|mediaProjection"`，此处仅以 microphone 类型启动。
 */
class RecordingService : Service() {

    companion object {
        private const val TAG = "RecordingService"

        // ---- Intent action 常量（纯 JVM 可测） ----

        const val ACTION_START_RECORDING = "com.example.ai_recorder_android.action.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.example.ai_recorder_android.action.STOP_RECORDING"
        const val ACTION_PAUSE = "com.example.ai_recorder_android.action.PAUSE"
        const val ACTION_RESUME = "com.example.ai_recorder_android.action.RESUME"

        // ---- Extra 键 ----

        const val EXTRA_OUTPUT_DIR = "output_dir"

        /** 服务当前是否在录音（instrumented 测试断言用）。 */
        @Volatile
        var isRecording = false
            private set

        /** 服务是否已进入前台（startForeground 成功）。 */
        @Volatile
        var isForeground = false
            private set

        /** 当前录音 id；未录音为 -1。 */
        @Volatile
        var currentRecordingId = -1L
            private set

        /** 命令分发（纯 JVM 可测）：intent action → 服务命令。 */
        enum class Command { START, STOP, PAUSE, RESUME, UNKNOWN }

        fun commandFor(action: String?): Command = when (action) {
            ACTION_START_RECORDING -> Command.START
            ACTION_STOP_RECORDING -> Command.STOP
            ACTION_PAUSE -> Command.PAUSE
            ACTION_RESUME -> Command.RESUME
            else -> Command.UNKNOWN
        }

        // ---- Intent 工厂（UI / 通知动作用） ----

        fun startIntent(context: Context, outputDir: File): Intent =
            Intent(context, RecordingService::class.java)
                .setAction(ACTION_START_RECORDING)
                .putExtra(EXTRA_OUTPUT_DIR, outputDir.absolutePath)

        fun stopIntent(context: Context): Intent =
            Intent(context, RecordingService::class.java).setAction(ACTION_STOP_RECORDING)

        fun pauseIntent(context: Context): Intent =
            Intent(context, RecordingService::class.java).setAction(ACTION_PAUSE)

        fun resumeIntent(context: Context): Intent =
            Intent(context, RecordingService::class.java).setAction(ACTION_RESUME)
    }

    /** 共享编排层（App 单例）：录音状态机 + 落库 + 附件时间戳对齐。 */
    private val coordinator: RecordingCoordinator get() = (application as App).recordingCoordinator

    /** 服务协程作用域：命令委托 coordinator 的 suspend 方法（随服务销毁取消）。 */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 每秒刷新通知中的已录音时长。 */
    private val ticker = object : Runnable {
        override fun run() {
            val state = coordinator.currentState
            if (state == CoordinatorState.RECORDING || state == CoordinatorState.PAUSED) {
                updateNotification(coordinator.elapsedMs())
                mainHandler.postDelayed(this, 1000L)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        RecordingNotification.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (commandFor(intent?.action)) {
            Command.START -> if (intent != null) {
                handleStart(intent)
            } else {
                Log.w(TAG, "START 命令缺少 intent（系统重启服务场景），忽略")
            }
            Command.STOP -> handleStop()
            Command.PAUSE -> serviceScope.launch { coordinator.pause() }
            Command.RESUME -> serviceScope.launch { coordinator.resume() }
            Command.UNKNOWN -> Log.w(TAG, "忽略未知 action: ${intent?.action}")
        }
        return START_NOT_STICKY // 前台服务被系统杀后不自动重启（录音数据已丢，重启无意义）
    }

    private fun handleStart(intent: Intent) {
        val state = coordinator.currentState
        if (state == CoordinatorState.RECORDING || state == CoordinatorState.PAUSED) {
            Log.w(TAG, "已在录音中，忽略重复 START")
            return
        }
        val dir = File(intent.getStringExtra(EXTRA_OUTPUT_DIR) ?: filesDir.absolutePath)
        // 先调用 startForeground 满足系统5秒要求，再启动录音
        // （即使录音失败，服务也必须在5秒内调用 startForeground）
        startForegroundInternal(-1L)
        serviceScope.launch {
            try {
                coordinator.startRecording(outputDir = dir)
                currentRecordingId = coordinator.currentRecordingId ?: -1L
                isRecording = true
                mainHandler.post(ticker)
            } catch (e: Exception) {
                Log.e(TAG, "启动录音失败: outputDir=$dir", e)
                isRecording = false
                currentRecordingId = -1L
                if (isForeground) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    isForeground = false
                }
                stopSelf()
            }
        }
    }

    private fun startForegroundInternal(recordingId: Long) {
        if (!RecordingNotification.hasNotificationPermission(this)) {
            Log.w(TAG, "POST_NOTIFICATIONS 未授权：通知不显示，服务降级运行")
        }
        val notification = RecordingNotification.buildRecordingNotification(this, recordingId, 0L)
        try {
            // Manifest 声明 microphone|mediaProjection，此处仅以 microphone 类型启动
            // （MediaProjection 由 todo 11 实现，届时按需追加类型）。
            startForeground(
                RecordingNotification.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
            isForeground = true
        } catch (e: Exception) {
            // 权限缺失 / while-in-use 违规等：降级继续录音，不崩溃
            Log.w(TAG, "startForeground 失败，降级运行", e)
            isForeground = false
        }
    }

    private fun handleStop() {
        serviceScope.launch {
            try {
                coordinator.stop()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "停止时无活动录音", e)
            } catch (e: Exception) {
                // 保存失败也不得卡死前台服务（P0）：通知常驻会导致无法再次启动录音
                Log.e(TAG, "停止录音失败（保存可能不完整），仍移除前台通知并停止服务", e)
            } finally {
                // 不变量：无论 stop 成败，前台退出 + 状态复位必须执行
                mainHandler.removeCallbacks(ticker)
                isRecording = false
                isForeground = false
                currentRecordingId = -1L
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun updateNotification(elapsedMs: Long) {
        val id = currentRecordingId
        if (id < 0) return
        val notification = RecordingNotification.buildRecordingNotification(this, id, elapsedMs)
        NotificationManagerCompat.from(this).notify(RecordingNotification.NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(ticker)
        // 不强行停止共享 coordinator：服务销毁不代表会话结束（如进程内重建），
        // 防止误杀正在进行的录音会话。
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}