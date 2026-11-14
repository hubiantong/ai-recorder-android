package com.example.ai_recorder_android.core.audio

import android.content.Intent

/**
 * 单次录音会话的采集配置：开始录音前由 UI 从用户设置（`UserConfig`）组装，
 * 经 Intent extras 传给 [com.example.ai_recorder_android.service.RecordingService]，
 * 最终透传 [RecordingAudioEngineFactory.create] 决定启动哪个采集引擎。
 *
 * [audioSource] 词表与 `data.db.AudioSource` / `UserConfig.meetingAudioSource` 一致：
 * mic / system / mixed。
 *
 * 职责边界：
 * - **[audioSource] 单独决定采集器**：mic → 麦克风引擎（`AudioRecorderEngineAdapter`，无需授权）；
 *   system/mixed → 系统音频引擎（`SystemAudioEngineAdapter`，需 MediaProjection 授权）。
 * - [meetingMode] 仅作为语义标记落库（`recordings.meeting_mode`），不改变采集器选择——
 *   因此「会议模式开但音源为 mic」仍是普通麦克风录音。
 * - API 34+ 约束：system/mixed 时，服务必须先以含
 *   `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION` 的类型调用 startForeground，
 *   才允许 `MediaProjectionManager.getMediaProjection`（由 RecordingService 保证顺序）。
 *
 * 经 Intent 传递时用扁平 extras（见 RecordingService.startIntent / configFromIntent），
 * 本类只作为进程内值对象，不做 Parcelable（嵌套授权 Intent 走扁平 extra 更稳健）。
 */
data class RecordingSessionConfig(
    val meetingMode: Boolean = false,
    val audioSource: String = SOURCE_MIC,
    /** MediaProjection 授权结果（system/mixed 必需；mic 忽略）。 */
    val projectionResultCode: Int = 0,
    val projectionResultData: Intent? = null,
) {

    /** 是否需要屏幕捕获授权（决定引擎选择 + 前台服务类型 + 授权弹窗）。 */
    val needsProjection: Boolean get() = audioSource != SOURCE_MIC

    companion object {
        const val SOURCE_MIC = "mic"
        const val SOURCE_SYSTEM = "system"
        const val SOURCE_MIXED = "mixed"

        /** 默认配置：普通麦克风录音（无配置的调用方保持原行为）。 */
        val DEFAULT = RecordingSessionConfig()

        /** 非法/未知音源回退麦克风（防御外部输入，与 UserConfigRepository.sanitizeTier 同风格）。 */
        fun sanitizeSource(source: String?): String =
            if (source == SOURCE_SYSTEM || source == SOURCE_MIXED) source else SOURCE_MIC
    }
}
