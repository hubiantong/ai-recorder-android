package com.example.ai_recorder_android

import android.app.Application
import com.example.ai_recorder_android.core.audio.AudioRecorderEngineAdapter
import com.example.ai_recorder_android.core.audio.RecordingAudioEngineFactory
import com.example.ai_recorder_android.data.db.AppDatabase
import com.example.ai_recorder_android.data.repo.RecordingRepository
import com.example.ai_recorder_android.data.storage.StorageService
import com.example.ai_recorder_android.service.RecordingCoordinator

/**
 * 应用级共享 [RecordingCoordinator] 单例。
 *
 * RecordingService / RecordingViewModel / PhotoAttachmentManager 三处复用同一实例，
 * 保证"录音中拍照 → addAttachment"命中活动会话（状态 RECORDING/PAUSED），
 * 避免各组件自建 coordinator 僵尸实例（从未 start，恒 IDLE）导致的
 * `IllegalStateException: 无活动录音`。
 */
class App : Application() {

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
}