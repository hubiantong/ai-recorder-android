package com.example.ai_recorder_android

import android.app.Application
import android.util.Log
import com.example.ai_recorder_android.core.audio.AudioRecorderEngineAdapter
import com.example.ai_recorder_android.core.audio.RecordingAudioEngineFactory
import com.example.ai_recorder_android.data.db.AppDatabase
import com.example.ai_recorder_android.data.repo.RecordingRepository
import com.example.ai_recorder_android.data.storage.StorageService
import com.example.ai_recorder_android.service.RecordingCoordinator
import com.example.ai_recorder_android.service.transcription.PostProcessingScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 应用级共享 [RecordingCoordinator] 单例 + 录音后处理链恢复入口。
 *
 * RecordingService / RecordingViewModel / PhotoAttachmentManager 三处复用同一实例，
 * 保证"录音中拍照 → addAttachment"命中活动会话（状态 RECORDING/PAUSED），
 * 避免各组件自建 coordinator 僵尸实例（从未 start，恒 IDLE）导致的
 * `IllegalStateException: 无活动录音`。
 *
 * 后处理链（转写 → 标题 → 笔记）由 [PostProcessingScheduler] 通过 WorkManager 调度：
 * WorkManager 自身持久化在途任务并在进程重启后恢复；[onCreate] 额外补投
 * 「已停止但无 AI 笔记」的录音，覆盖进程在投递前被杀的窗口。
 */
class App : Application() {

    /** 应用级 IO 作用域：与进程同生命周期（启动恢复扫描等）。 */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            try {
                PostProcessingScheduler.resumePending(this@App)
            } catch (e: Exception) {
                Log.e(TAG, "恢复未完成的录音后处理任务失败", e)
            }
        }
    }

    /** 共享录音编排层：懒加载单例（首次访问时装配 DB + Storage + 引擎工厂）。 */
    val recordingCoordinator: RecordingCoordinator by lazy {
        val db = AppDatabase.getInstance(this)
        val storage = StorageService.fromContext(this)
        RecordingCoordinator(
            repository = RecordingRepository(db.recordingDao(), db.attachmentDao(), storage),
            engineFactory = RecordingAudioEngineFactory { id, dir, events ->
                AudioRecorderEngineAdapter(id, dir, events)
            },
        )
    }

    companion object {
        private const val TAG = "App"
    }
}