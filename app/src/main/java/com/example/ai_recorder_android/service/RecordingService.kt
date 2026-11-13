package com.example.ai_recorder_android.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import com.example.ai_recorder_android.App
import com.example.ai_recorder_android.BuildConfig
import com.example.ai_recorder_android.cloud.asr.DashScopeRealtimeConnector
import com.example.ai_recorder_android.core.audio.PcmFrameDispatcher
import com.example.ai_recorder_android.service.transcription.DashScopeRealtimeAdapter
import com.example.ai_recorder_android.data.db.AppDatabase
import com.example.ai_recorder_android.data.repo.UserConfigRepository
import com.example.ai_recorder_android.data.storage.StorageService
import com.example.ai_recorder_android.service.transcription.DefaultFileTranscriberFactory
import com.example.ai_recorder_android.service.transcription.PostProcessingScheduler
import com.example.ai_recorder_android.service.transcription.RealtimeListeners
import com.example.ai_recorder_android.service.transcription.RealtimeTranscriptionPipeline
import com.example.ai_recorder_android.service.transcription.RoomTranscriptionStore
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

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

    /** 活动的实时转写管线（tier=realtime 时创建；停止后清空）。 */
    private var realtimePipeline: RealtimeTranscriptionPipeline? = null

    /** PCM 帧有界分发器：引擎线程只入队，网络投递在 IO 消费协程（不阻塞录音读循环）。 */
    private var pcmDispatcher: PcmFrameDispatcher? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 停止流程（含 realtime 收尾与后处理投递）是否进行中：重复 STOP 直接忽略，避免二次收尾。 */
    private var stopFlowInProgress = false

    /** 每秒刷新通知中的已录音时长。 */
    private val ticker = object : Runnable {
        override fun run() {
            if (coordinator.sessionState.value.isActive) {
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
        // 以 coordinator 的真实状态为准：启动中/录音中/停止中一律忽略重复 START，
        // 避免连点导致第二个 start 与首个 start/stop 竞争并互相关闭服务。
        val session = coordinator.sessionState.value
        if (session.isActive || session.isBusy) {
            Log.w(TAG, "会话进行中（phase=${session.phase}），忽略重复 START")
            return
        }
        val dir = File(intent.getStringExtra(EXTRA_OUTPUT_DIR) ?: filesDir.absolutePath)
        // 先调用 startForeground 满足系统5秒要求，再启动录音
        // （即使录音失败，服务也必须在5秒内调用 startForeground）
        startForegroundInternal(-1L)
        serviceScope.launch {
            try {
                val rec = coordinator.startRecording(outputDir = dir)
                currentRecordingId = rec.id
                isRecording = true
                mainHandler.post(ticker)
                tryStartRealtimeTranscription(rec.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "启动录音失败: outputDir=$dir", e)
                stopServiceForeground()
            }
        }
    }

    /**
     * realtime 档：启动 DashScope Paraformer 流式转写并挂接 PCM 帧投递。
     *
     * 引擎读循环线程（[AudioRecorderEngine] 100ms/帧）→ [PcmFrameDispatcher] 有界队列 →
     * IO 消费协程 → [RealtimeTranscriptionPipeline.feedPcm16k]，重采样/WS 发送不占用录音线程。
     * 启动失败（key 缺失/连接失败）仅记日志，停止后回退文件转写。
     */
    private suspend fun tryStartRealtimeTranscription(recordingId: Long) {
        val config = UserConfigRepository.fromContext(this).userConfig.first()
        if (config.asrTier != "realtime") return
        val db = AppDatabase.getInstance(this)
        val pipeline = RealtimeTranscriptionPipeline(
            connectorFactory = { cb ->
                DashScopeRealtimeAdapter(
                    DashScopeRealtimeConnector(apiKey = BuildConfig.DASHSCOPE_API_KEY, callback = cb),
                )
            },
            store = RoomTranscriptionStore(db.recordingDao(), db.segmentDao()),
        )
        try {
            pipeline.start(
                recordingId,
                RealtimeListeners(
                    onPartial = { text -> mainHandler.post { coordinator.transcriptListener?.invoke(true, text) } },
                    onSentence = { text, _, _ -> mainHandler.post { coordinator.transcriptListener?.invoke(false, text) } },
                    onError = { e -> Log.w(TAG, "实时转写错误（停止后将回退文件转写）", e) },
                ),
            )
            realtimePipeline = pipeline
            val dispatcher = PcmFrameDispatcher(
                consumer = { pcm -> pipeline.feedPcm16k(pcm) },
                onConsumerError = { e -> Log.w(TAG, "实时转写帧投递失败（录音不受影响，停止后回退文件转写）", e) },
            )
            pcmDispatcher = dispatcher
            coordinator.pcmListener = dispatcher::offer
            Log.i(TAG, "实时转写已启动 recordingId=$recordingId")
        } catch (e: Exception) {
            Log.w(TAG, "实时转写启动失败，停止后回退文件转写", e)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
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
            if (stopFlowInProgress) {
                Log.w(TAG, "停止流程进行中，忽略重复 STOP")
                return@launch
            }
            val session = coordinator.sessionState.value
            // STARTING 仍受理停止：coordinator.stop() 会在 mutex 上等待启动完成后收尾；
            // STOPPING（重复停止）/COMPLETED/IDLE 直接忽略，避免二次收尾与新会话互相干扰。
            if (!session.isActive && session.phase != RecordingPhase.STARTING) {
                Log.w(TAG, "停止时无活动会话（phase=${session.phase}），忽略")
                if (!session.isBusy) stopServiceForeground()
                return@launch
            }
            stopFlowInProgress = true
            try {
                performStop()
            } finally {
                stopFlowInProgress = false
            }
        }
    }

    /** 停止主流程：coordinator 收尾 → realtime 排空 → 投递后处理链 → 前台复位。 */
    private suspend fun performStop() {
        try {
            coordinator.stop()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "停止时无活动录音", e)
            tearDownRealtime()
            stopServiceForeground()
            return
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 保存失败也不得卡死前台服务（P0）：通知常驻会导致无法再次启动录音
            Log.e(TAG, "停止录音失败（保存可能不完整），仍移除前台通知并停止服务", e)
            tearDownRealtime()
            stopServiceForeground()
            return
        }

        // stop 成功后 coordinator 在 COMPLETED 中保留刚结束的会话 id
        val recordingId = coordinator.sessionState.value.recordingId

        // 音频文件与录音状态已由 coordinator.stop() 持久化。
        // realtime 档先在进程内收尾（流式句落库）；失败/无最终句时交给后处理链回退文件转写。
        val rt = realtimePipeline
        if (rt != null) {
            realtimePipeline = null
            coordinator.pcmListener = null
            // 先排空已入队 PCM（尾部语音不丢），再 finish，保证帧序与最终句完整
            pcmDispatcher?.closeAndDrain()
            pcmDispatcher = null
            try {
                rt.stop()
            } catch (e: Exception) {
                Log.e(TAG, "实时转写收尾失败，交由后处理链回退文件转写", e)
            }
        }

        // P0：停止后必须投递完整 AI 后处理链（转写 → 自动标题 → 融合笔记）。
        // 实时/文件转写均由此统一调度；WorkManager 持久化任务并在进程重启后自动恢复。
        if (recordingId != null && recordingId > 0) {
            try {
                PostProcessingScheduler.enqueue(applicationContext, recordingId)
                Log.i(TAG, "已投递录音后处理链 recordingId=$recordingId")
                toast("录音已保存，AI 处理中")
            } catch (e: Exception) {
                // 投递失败不阻塞服务退出；App 启动恢复扫描会补投未完成录音
                Log.e(TAG, "后处理链投递失败 recordingId=$recordingId（将由启动恢复补偿）", e)
            }
        } else {
            Log.e(TAG, "停止后缺少有效 recordingId，无法投递后处理链")
        }
        stopServiceForeground()
    }

    /** 摘除实时转写管线与 PCM 投递（停止失败路径兜底）。 */
    private fun tearDownRealtime() {
        val rt = realtimePipeline
        realtimePipeline = null
        coordinator.pcmListener = null
        pcmDispatcher?.close()
        pcmDispatcher = null
        if (rt != null) {
            serviceScope.launch {
                try {
                    rt.stop()
                } catch (e: Exception) {
                    Log.w(TAG, "实时转写收尾失败（停止异常路径）", e)
                }
            }
        }
    }

    private fun stopServiceForeground() {
        // 新会话已接管（上一次停止的收尾协程迟到）：不得摘掉新会话的前台通知/停止服务
        val session = coordinator.sessionState.value
        if (session.isActive || session.isBusy) {
            Log.i(TAG, "新会话进行中（phase=${session.phase}），保留前台服务")
            return
        }
        // 不变量：无论 stop 成败，前台退出 + 状态复位必须执行
        mainHandler.removeCallbacks(ticker)
        isRecording = false
        isForeground = false
        currentRecordingId = -1L
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
        // 防止误杀正在进行的录音会话。实时转写帧投递随服务一并释放，避免泄漏。
        coordinator.pcmListener = null
        pcmDispatcher?.close()
        pcmDispatcher = null
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}