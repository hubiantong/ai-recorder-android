package com.example.ai_recorder_android.core.audio

import java.io.File

/**
 * 录音引擎抽象（todo 16 编排层依赖，纯 JVM 单测可注入假引擎）。
 *
 * 语义对齐 PC `core/audio/recorder.py` 的 AudioRecorder 公开接口：
 * - [start] / [pause] / [resume] / [stop] 生命周期
 * - [pausedDurationMs] 累计暂停时长、[elapsedMs] 扣除暂停的已过时长，供时间轴对齐
 *
 * 真实实现为 [AudioRecorderEngineAdapter]（包装 todo 8 [AudioRecorderEngine]）。
 * 引擎不可复用：一次 [stop] 后如需再次录音请重新经工厂创建。
 */
interface RecordingAudioEngine {
    /** 是否在录音中（含暂停态）。 */
    val isRecording: Boolean

    /** 是否暂停中。 */
    val isPaused: Boolean

    /** 累计暂停时长（毫秒）。暂停期间不写 PCM，时间轴对齐时扣除。 */
    val pausedDurationMs: Long

    /** 录音已过时长（毫秒，扣除暂停）；未录音返回 0。 */
    fun elapsedMs(): Long

    /**
     * 开始录音（自动开启段 0）。
     * @throws IllegalStateException 已在录音中或 AudioRecord 初始化失败
     */
    fun start()

    /** 暂停：仅在录音中生效，重复调用忽略（不停止音频流，仅丢弃帧）。 */
    fun pause()

    /** 恢复：仅在暂停中生效。 */
    fun resume()

    /** 停止录音（finalize 全部段文件并 release 资源）。未在录音中时幂等。 */
    fun stop()
}

/**
 * 引擎事件（由 [AudioRecorderEngineAdapter] 从 [AudioRecorderEngine.Callbacks] 转发）。
 *
 * 事件在**引擎线程或调用线程**触发（不在引擎内部锁内），
 * 回调内**不得做阻塞/挂起操作**；编排层用 [java.util.concurrent.locks] 无关的
 * 轻量同步（见 [RecordingAudioEngine] 实现方）保证线程安全。
 */
interface RecordingEngineEvents {
    /** 音量电平（RMS 归一化 0.0~1.0），供 UI 音量指示条；暂停时回调 0.0。 */
    fun onLevel(rms: Double)

    /** 某段已关闭（文件已 finalize 可读），供编排层落库。 */
    fun onSegmentClosed(index: Int, file: File, durationMs: Long)

    /** 读循环异常（如 AudioRecord 读失败）；引擎已尽力关闭段文件。 */
    fun onError(error: Throwable)

    /** 录音中 16kHz/16-bit/mono PCM 帧（100ms 一帧，引擎线程）；暂停不回调。默认空实现。 */
    fun onPcmFrame(pcm: ByteArray) {}
}

/**
 * 将 todo 8 [AudioRecorderEngine] 适配为 [RecordingAudioEngine]。
 * 构造即创建引擎实例（引擎不可复用，每次录音新建）；引擎回调转发给 [events]。
 */
class AudioRecorderEngineAdapter(
    recordingId: Long,
    outputDir: File,
    private val events: RecordingEngineEvents,
) : RecordingAudioEngine {

    private val engine = AudioRecorderEngine(
        recordingId = recordingId,
        outputDir = outputDir,
        callbacks = object : AudioRecorderEngine.Callbacks {
            override fun onLevel(rms: Double) = events.onLevel(rms)

            override fun onSegmentClosed(index: Int, file: File, durationMs: Long) =
                events.onSegmentClosed(index, file, durationMs)

            override fun onError(error: Throwable) = events.onError(error)

            override fun onPcmFrame(pcm: ByteArray) = events.onPcmFrame(pcm)
        },
    )

    override val isRecording: Boolean get() = engine.isRecording
    override val isPaused: Boolean get() = engine.isPaused
    override val pausedDurationMs: Long get() = engine.pausedDurationMs
    override fun elapsedMs(): Long = engine.elapsedMs()
    override fun start() = engine.start()
    override fun pause() = engine.pause()
    override fun resume() = engine.resume()
    override fun stop() = engine.stop()
}

/**
 * 引擎工厂（todo 16 编排层注入用，单测替换为假工厂）。
 * 每次 [create] 返回独立引擎实例（引擎不可复用）。
 */
fun interface RecordingAudioEngineFactory {
    fun create(recordingId: Long, outputDir: File, events: RecordingEngineEvents): RecordingAudioEngine
}
