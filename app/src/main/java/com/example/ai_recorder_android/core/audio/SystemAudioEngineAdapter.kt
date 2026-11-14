package com.example.ai_recorder_android.core.audio

import android.content.Context
import android.media.projection.MediaProjectionManager
import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 系统音频采集引擎适配器：包装 [SystemAudioCapturer]，对齐 [AudioRecorderEngine] 的
 * 引擎语义（分段 WAV / RMS 音量 / 暂停丢帧 / PCM 帧回调），供编排层无差别使用。
 *
 * 与 [AudioRecorderEngine] 的差异仅在帧来源：mic 引擎自持 AudioRecord 读循环，
 * 本适配器消费 [SystemAudioCapturer.FrameListener] 的 16kHz mono int16 帧回调（捕获线程）。
 * MediaProjection 授权结果经 [config] 注入；system/mixed 音源要求已获授权
 * （ capturer.start 内部校验，失败抛 IllegalStateException）。
 *
 * 线程模型与 [AudioRecorderEngine] 一致（Metis P6.1）：
 * - 帧回调来自捕获线程，段文件开/关/写统一 `synchronized(segmentLock)`；
 * - 生命周期方法（start/pause/resume/stop）由编排层调用线程执行；
 * - [RecordingEngineEvents] 回调在锁外触发，不阻塞录音主路径。
 *
 * 引擎不可复用：一次 [stop] 后如需再次录音请重新经工厂创建。
 */
class SystemAudioEngineAdapter(
    context: Context,
    private val recordingId: Long,
    private val outputDir: File,
    private val config: RecordingSessionConfig,
    private val events: RecordingEngineEvents,
) : RecordingAudioEngine {

    private enum class State { IDLE, RECORDING, PAUSED }

    private val capturer = SystemAudioCapturer(
        context = context.applicationContext,
        mediaProjectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
            as MediaProjectionManager,
    )

    @Volatile
    private var state = State.IDLE

    /** 累计暂停时长（毫秒）；暂停期间不写 PCM，时间轴对齐时扣除。 */
    @Volatile
    private var totalPausedMs = 0L

    /** 本次暂停开始时刻；0 表示当前未在暂停。 */
    @Volatile
    private var pauseStartMs = 0L

    @Volatile
    private var recordingStartMs = 0L

    /** 段文件锁：开/关/写统一串行化（捕获线程写，编排层线程开/关段）。 */
    private val segmentLock = Any()

    private var activeSegment: SegmentWriter? = null
    private var segmentIndex = 0

    override val isRecording: Boolean get() = state != State.IDLE
    override val isPaused: Boolean get() = state == State.PAUSED
    override val pausedDurationMs: Long get() = totalPausedMs

    override fun elapsedMs(): Long {
        if (recordingStartMs == 0L) return 0L
        var elapsed = SystemClock.elapsedRealtime() - recordingStartMs - totalPausedMs
        if (pauseStartMs != 0L) elapsed -= SystemClock.elapsedRealtime() - pauseStartMs
        return maxOf(0L, elapsed)
    }

    /**
     * 开始录音：开启段 0 → 启动 [SystemAudioCapturer]（按 [config.audioSource] 走
     * mic/system/mixed 路径）。
     *
     * @throws IllegalStateException 已在录音中 / 缺少 MediaProjection 授权结果 /
     *   设备不支持请求的音频参数
     */
    override fun start() {
        synchronized(this) {
            check(state == State.IDLE) { "已在录音中（state=$state）" }
            outputDir.mkdirs()
            segmentIndex = 0
            totalPausedMs = 0L
            pauseStartMs = 0L
            recordingStartMs = SystemClock.elapsedRealtime()
            synchronized(segmentLock) {
                openSegmentLocked(0)
                state = State.RECORDING
            }
        }
        try {
            capturer.start(
                resultCode = config.projectionResultCode,
                resultData = config.projectionResultData ?: android.content.Intent(),
                source = toMeetingSource(config.audioSource),
                listener = ::onFrame,
            )
        } catch (e: Exception) {
            // 启动失败回滚：不残留半开段文件，会话回到 IDLE（对齐 AudioRecorderEngine 失败语义）
            synchronized(this) { state = State.IDLE }
            val failed: SegmentWriter?
            synchronized(segmentLock) {
                failed = closeActiveSegmentLocked()
            }
            failed?.let { runCatching { it.file.delete() } }
            recordingStartMs = 0L
            throw e
        }
    }

    /** 暂停：RECORDING→PAUSED。捕获流不停止，帧丢弃（不写文件、不计音量）。 */
    override fun pause() {
        synchronized(this) {
            if (state != State.RECORDING) return
            state = State.PAUSED
            pauseStartMs = SystemClock.elapsedRealtime()
        }
        events.onLevel(0.0)
    }

    /** 恢复：PAUSED→RECORDING，累计本次暂停时长。 */
    override fun resume() {
        synchronized(this) {
            if (state != State.PAUSED) return
            if (pauseStartMs != 0L) {
                totalPausedMs += SystemClock.elapsedRealtime() - pauseStartMs
                pauseStartMs = 0L
            }
            state = State.RECORDING
        }
    }

    /**
     * 停止录音：先置 IDLE（捕获线程帧回调自然丢弃）→ capturer.stop（中断并 join
     * 捕获线程，API 34+ 结束 projection 会话）→ finalize 当前段并回调 [RecordingEngineEvents.onSegmentClosed]。
     * 未在录音中时幂等。
     */
    override fun stop() {
        synchronized(this) {
            if (state == State.IDLE) return
            state = State.IDLE
        }
        capturer.stop()
        val closed: SegmentWriter?
        synchronized(segmentLock) {
            closed = closeActiveSegmentLocked()
        }
        closed?.let { events.onSegmentClosed(it.index, it.file, it.durationMs()) }
        synchronized(this) {
            recordingStartMs = 0L
            pauseStartMs = 0L
        }
    }

    // ---- 帧消费（捕获线程回调） ----

    /** 捕获线程帧回调：写段 WAV + RMS 音量 + 小端 PCM 转发（语义对齐 AudioRecorderEngine.readLoop）。 */
    private fun onFrame(samples: ShortArray) {
        when (state) {
            State.PAUSED -> {
                // 暂停：丢弃 PCM，音量归零
                events.onLevel(0.0)
                return
            }
            State.RECORDING -> Unit
            State.IDLE -> return // stop() 已置 IDLE（在途帧丢弃）
        }
        synchronized(segmentLock) {
            if (state != State.RECORDING) return
            activeSegment?.write(samples)
        }
        events.onLevel(AudioRecorderEngine.computeRms(samples, samples.size))
        events.onPcmFrame(samples.toLittleEndianBytes())
    }

    private fun toMeetingSource(source: String): SystemAudioCapturer.MeetingAudioSource =
        when (source) {
            RecordingSessionConfig.SOURCE_SYSTEM -> SystemAudioCapturer.MeetingAudioSource.SYSTEM
            RecordingSessionConfig.SOURCE_MIXED -> SystemAudioCapturer.MeetingAudioSource.MIXED
            else -> SystemAudioCapturer.MeetingAudioSource.MIC
        }

    // ---- 段文件管理（调用方持 segmentLock） ----

    private fun openSegmentLocked(index: Int) {
        val file = File(outputDir, "seg_${recordingId}_${index}.wav")
        val raf = RandomAccessFile(file, "rw")
        WavFileWriter.writeHeader(
            raf,
            WavFileWriter.DEFAULT_SAMPLE_RATE,
            WavFileWriter.DEFAULT_CHANNELS,
            WavFileWriter.DEFAULT_BITS_PER_SAMPLE,
        )
        activeSegment = SegmentWriter(index, file, raf, SystemClock.elapsedRealtime())
    }

    private fun closeActiveSegmentLocked(): SegmentWriter? {
        val seg = activeSegment ?: return null
        activeSegment = null
        seg.close()
        return seg
    }

    /**
     * 单个分段 WAV 写句柄：只在捕获线程写、编排层线程关闭，经 [segmentLock] 互斥。
     * 混音器可能输出大于 1024 帧的合并帧，故按帧长度分配写缓冲。
     */
    private class SegmentWriter(
        val index: Int,
        val file: File,
        private val raf: RandomAccessFile,
        private val openedAtMs: Long,
    ) {
        /** 写入 samples（16kHz mono int16，小端落盘）。调用方持 segmentLock。 */
        fun write(samples: ShortArray) {
            val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            buf.asShortBuffer().put(samples)
            raf.write(buf.array())
        }

        /** finalize 头部大小字段并关闭。调用方持 segmentLock。 */
        fun close() {
            WavFileWriter.finalize(raf, raf.channel.size().toInt() - WavFileWriter.HEADER_SIZE)
            raf.close()
        }

        fun durationMs(): Long = SystemClock.elapsedRealtime() - openedAtMs
    }

    private fun ShortArray.toLittleEndianBytes(): ByteArray {
        val buf = ByteBuffer.allocate(size * 2).order(ByteOrder.LITTLE_ENDIAN)
        buf.asShortBuffer().put(this)
        return buf.array()
    }
}
