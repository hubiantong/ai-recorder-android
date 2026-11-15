package com.example.ai_recorder_android.core.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * AudioRecord 采集引擎：16kHz / 16-bit / mono，专用线程读循环，输出分段 WAV。
 *
 * 对齐 PC `core/audio/recorder.py` 的 AudioRecorder 语义：
 * - 16kHz / 16-bit / mono（Whisper 标准，全链路依赖）
 * - 暂停不停止音频流，仅丢弃帧；`totalPausedMs` 累计暂停时长（供时间轴对齐，todo 16 用）
 * - 分段录音：每段一个独立 WAV 文件 `seg_{rid}_{idx}.wav`（与 StorageService 的
 *   `segments/seg_{rid}_{idx}.wav` 命名一致），段边界由外部 VAD 决定（todo 16 接线）
 *
 * 线程模型（Metis P6.1）：
 * - 读循环在**专用非 suspend 线程**（`Thread` + `while(running)`），AudioRecord 的
 *   `READ_BLOCKING` 读 + 同步写文件，不做协程切换
 * - 状态机 `idle/recording/paused`：`@Volatile` 状态标志，切换方法 `synchronized(this)`
 * - 段文件（开/关/写）统一 `synchronized(segmentLock)` 串行化：`startSegment`/`endSegment`/
 *   `stop` 由外部线程调用，读循环线程写，二者互斥保证不会向已关闭文件写入
 * - 回调（[Callbacks]）在锁外调用，避免回调慢操作阻塞录音主路径
 *
 * 生命周期：`start()` →（可选 `pause()`/`resume()`、`startSegment()`/`endSegment()`）→ `stop()`。
 * 引擎不可复用：`stop()` 后如需再次录音请新建实例。
 */
class AudioRecorderEngine(
    /** 录音 id，用于分段文件名 `seg_{rid}_{idx}.wav`。 */
    private val recordingId: Long,
    /** 分段 WAV 输出目录（自动创建）。 */
    private val outputDir: File,
    private val callbacks: Callbacks,
) {

    /** 引擎回调（均在引擎线程或调用线程触发，不在锁内）。 */
    interface Callbacks {
        /** 音量电平（RMS 归一化 0.0~1.0），供 UI 音量指示条；暂停时回调 0.0。 */
        fun onLevel(rms: Double)

        /** 某段已关闭（文件已 finalize 可读），供 todo 16 入库。 */
        fun onSegmentClosed(index: Int, file: File, durationMs: Long)

        /** 读循环异常（如 AudioRecord 读失败）；引擎已尽力关闭段文件。 */
        fun onError(error: Throwable)

        /**
         * 录音中的 16kHz/16-bit/mono PCM 帧（小端，100ms 一帧，引擎线程回调），
         * 供实时转写（todo 17）投递流式 ASR；暂停期间不回调。默认空实现（无需 PCM 的场景零成本）。
         */
        fun onPcmFrame(pcm: ByteArray) {}
    }

    enum class State { IDLE, RECORDING, PAUSED }

    companion object {
        /** 采样率 16kHz（对齐 PC，Whisper 标准）。 */
        const val SAMPLE_RATE = 16000
        const val CHANNEL_IN_MONO = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING_PCM_16BIT = AudioFormat.ENCODING_PCM_16BIT

        /** 读循环块大小：1600 样本 = 100ms @16kHz。 */
        const val FRAME_SIZE = 1600

        /** 归一化 RMS：sqrt(sum(x^2)/n) / 32768.0（int16 满量程），结果 0.0~1.0。 */
        fun computeRms(buffer: ShortArray, count: Int): Double {
            require(count in 0..buffer.size) { "count 越界: $count (buffer.size=${buffer.size})" }
            if (count == 0) return 0.0
            var sum = 0.0
            for (i in 0 until count) {
                val v = buffer[i].toDouble()
                sum += v * v
            }
            return sqrt(sum / count) / 32768.0
        }

        /**
         * 波形包络：把一帧小端 int16 PCM（16kHz mono）均分成 [bucketsPerFrame] 段，
         * 逐段计算归一化 RMS（0.0~1.0，同 [computeRms] 标定），供 UI 波形绘制。
         *
         * 每帧 100ms × 每帧 [bucketsPerFrame] 根柱 ≈ 40 柱/秒（默认 4 桶），
         * 纹理密度对齐语音备忘录类 App 的真波形观感。
         * PCM 长度不足（含 0）时对应桶返回 0.0；与 [computeRms] 一样为纯函数，供引擎线程调用。
         */
        fun computeEnvelope(pcm: ByteArray, bucketsPerFrame: Int = 4): FloatArray {
            require(bucketsPerFrame > 0) { "bucketsPerFrame 必须为正: $bucketsPerFrame" }
            val samples = ShortArray(pcm.size / 2)
            ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
            val envelope = FloatArray(bucketsPerFrame)
            if (samples.isEmpty()) return envelope
            val bucketSize = samples.size / bucketsPerFrame
            for (b in 0 until bucketsPerFrame) {
                val start = b * bucketSize
                // 尾桶吸收整除余数，保证样本无遗漏
                val end = if (b == bucketsPerFrame - 1) samples.size else start + bucketSize
                envelope[b] = computeRmsRange(samples, start, end).toFloat()
            }
            return envelope
        }

        /** [start, end) 区间归一化 RMS（int16 满量程标定）；区间为空返回 0.0。 */
        private fun computeRmsRange(buffer: ShortArray, start: Int, end: Int): Double {
            if (end <= start) return 0.0
            var sum = 0.0
            for (i in start until end) {
                val v = buffer[i].toDouble()
                sum += v * v
            }
            return sqrt(sum / (end - start)) / 32768.0
        }
    }

    @Volatile
    private var state = State.IDLE

    /** 读循环运行标志；stop() 先置 false 再 join，读循环在 read 返回后检查退出。 */
    @Volatile
    private var running = false

    private var thread: Thread? = null
    private var audioRecord: AudioRecord? = null

    /** 段文件锁：开/关/写统一串行化（读循环线程写，外部线程切段）。 */
    private val segmentLock = Any()

    /** 当前活动段；null 表示无活动段（未启动或已被 endSegment 关闭）。 */
    @Volatile
    private var activeSegment: SegmentWriter? = null

    @Volatile
    private var segmentIndex = 0

    /** 累计暂停时长（毫秒，单调时钟），供时间轴对齐（todo 16）。 */
    @Volatile
    private var totalPausedMs = 0L

    /** 本次暂停开始时刻；0 表示当前未在暂停。 */
    @Volatile
    private var pauseStartMs = 0L

    @Volatile
    private var recordingStartMs = 0L

    val isRecording: Boolean get() = state != State.IDLE
    val isPaused: Boolean get() = state == State.PAUSED

    /** 累计暂停时长（毫秒）。暂停期间不写 PCM，此值供时间轴对齐扣除。 */
    val pausedDurationMs: Long get() = totalPausedMs

    /** 当前段序号（0-based）；未启动为 0。 */
    val currentSegmentIndex: Int get() = segmentIndex

    /**
     * 录音已过时长（毫秒，扣除暂停）：供 UI 计时与时间轴对齐。
     * 未录音返回 0。
     */
    fun elapsedMs(): Long {
        if (recordingStartMs == 0L) return 0L
        var elapsed = SystemClock.elapsedRealtime() - recordingStartMs - totalPausedMs
        if (pauseStartMs != 0L) elapsed -= SystemClock.elapsedRealtime() - pauseStartMs
        return maxOf(0L, elapsed)
    }

    /**
     * 开始录音：状态 IDLE→RECORDING，自动开启段 0（`seg_{rid}_0.wav`），
     * 启动专用读循环线程。
     *
     * @throws IllegalStateException 已在录音中
     * @throws IllegalStateException AudioRecord 初始化失败（如缺少 RECORD_AUDIO 权限）
     */
    fun start() {
        synchronized(this) {
            check(state == State.IDLE) { "已在录音中（state=$state）" }
            outputDir.mkdirs()
            val record = createAudioRecord()
            // 必须显式 startRecording：否则 read() 恒返回 0，WAV 只有 44 字节头（无音频）
            record.startRecording()
            check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                "AudioRecord 启动失败（recordingState=${record.recordingState}）"
            }
            audioRecord = record
            segmentIndex = 0
            totalPausedMs = 0L
            pauseStartMs = 0L
            recordingStartMs = SystemClock.elapsedRealtime()
            synchronized(segmentLock) {
                openSegmentLocked(0)
            }
            state = State.RECORDING
            running = true
            val t = Thread(::readLoop, "AudioRecorderEngine-$recordingId")
            thread = t
            t.start()
        }
    }

    /**
     * 暂停：状态 RECORDING→PAUSED。音频流不停止，读循环继续读但丢弃 PCM
     * （不写文件、不计音量），暂停时长累计到 [totalPausedMs]。
     * 仅在录音中生效，重复调用忽略。
     */
    fun pause() {
        synchronized(this) {
            if (state != State.RECORDING) return
            state = State.PAUSED
            pauseStartMs = SystemClock.elapsedRealtime()
        }
        callbacks.onLevel(0.0)
    }

    /** 恢复：状态 PAUSED→RECORDING，累计本次暂停时长。仅在暂停中生效。 */
    fun resume() {
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
     * 开启新段（VAD 切段接口，todo 16 接线）：关闭当前段（若有）并开启
     * `seg_{rid}_{index}.wav`。段序号必须严格递增（防乱序覆盖）。
     *
     * @throws IllegalStateException 未在录音中
     * @throws IllegalArgumentException index <= 当前段序号
     */
    fun startSegment(index: Int) {
        require(index >= 0) { "segment index 必须 >= 0: $index" }
        val closed: SegmentWriter?
        synchronized(segmentLock) {
            check(state != State.IDLE) { "未在录音中" }
            check(index > segmentIndex) { "段序号必须递增: 当前 $segmentIndex, 收到 $index" }
            closed = closeActiveSegmentLocked()
            segmentIndex = index
            openSegmentLocked(index)
        }
        closed?.let { callbacks.onSegmentClosed(it.index, it.file, it.durationMs()) }
    }

    /**
     * 结束当前段（VAD 段边界接口）：关闭当前段文件并 finalize，
     * 之后读到的帧丢弃（不写），直到下一次 [startSegment]。
     * 未在录音中或已无活动段时忽略。
     */
    fun endSegment() {
        val closed: SegmentWriter?
        synchronized(segmentLock) {
            if (state == State.IDLE) return
            closed = closeActiveSegmentLocked()
        }
        closed?.let { callbacks.onSegmentClosed(it.index, it.file, it.durationMs()) }
    }

    /**
     * 停止录音：状态→IDLE，置 running=false 并 join 读循环线程，
     * 关闭并 finalize 当前段（若有），release AudioRecord。
     * 未在录音中时忽略（幂等）。
     */
    fun stop() {
        val t: Thread?
        synchronized(this) {
            if (state == State.IDLE) return
            state = State.IDLE
            running = false
            t = thread
            thread = null
        }
        // 锁外 join：读循环只依赖 running/state（volatile）退出，不持有 this 锁，
        // 不会死锁；join 超时后 release 会让阻塞的 read 返回并退出。
        t?.join(2000)
        val closed: SegmentWriter?
        synchronized(segmentLock) {
            closed = closeActiveSegmentLocked()
        }
        closed?.let { callbacks.onSegmentClosed(it.index, it.file, it.durationMs()) }
        synchronized(this) {
            audioRecord?.release()
            audioRecord = null
        }
    }

    // ---- 读循环（专用线程） ----

    private fun readLoop() {
        val record = audioRecord ?: return
        val buffer = ShortArray(FRAME_SIZE)
        // 读循环线程独占的 int16→byte 转换缓冲（无并发访问）
        val pcmBytes = ByteArray(FRAME_SIZE * 2)
        val byteBuffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
        try {
            while (running) {
                val read = record.read(buffer, 0, FRAME_SIZE, AudioRecord.READ_BLOCKING)
                if (read <= 0) continue
                when (state) {
                    State.PAUSED -> {
                        // 暂停：丢弃 PCM，音量归零（对齐 PC：暂停时 level=0）
                        callbacks.onLevel(0.0)
                    }
                    State.RECORDING -> {
                        synchronized(segmentLock) {
                            activeSegment?.write(buffer, read, byteBuffer, pcmBytes)
                        }
                        callbacks.onLevel(computeRms(buffer, read))
                        // 实时转写投递（write 后 pcmBytes 前 read*2 字节即本帧小端 PCM）
                        callbacks.onPcmFrame(pcmBytes.copyOf(read * 2))
                    }
                    State.IDLE -> break // stop() 已置 IDLE，退出循环
                }
            }
        } catch (e: Exception) {
            if (running) callbacks.onError(e)
        } finally {
            // 线程退出路径（正常 stop 或异常）：关闭活动段并通知。
            // stop() 正常路径 join 后关闭会因 activeSegment 已 null 而幂等跳过。
            val closed: SegmentWriter?
            synchronized(segmentLock) {
                closed = closeActiveSegmentLocked()
            }
            closed?.let { callbacks.onSegmentClosed(it.index, it.file, it.durationMs()) }
        }
    }

    // ---- 段文件管理（调用方持 segmentLock） ----

    /** 开启段 index 的 WAV 文件并置为活动段。调用方持 segmentLock（start() 启动阶段无并发）。 */
    private fun openSegmentLocked(index: Int) {
        val file = File(outputDir, "seg_${recordingId}_${index}.wav")
        val raf = RandomAccessFile(file, "rw")
        WavFileWriter.writeHeader(raf, WavFileWriter.DEFAULT_SAMPLE_RATE, WavFileWriter.DEFAULT_CHANNELS, WavFileWriter.DEFAULT_BITS_PER_SAMPLE)
        activeSegment = SegmentWriter(index, file, raf, SystemClock.elapsedRealtime())
    }

    /** 关闭当前段（finalize 回填大小）并置空；无活动段返回 null。调用方持 segmentLock。 */
    private fun closeActiveSegmentLocked(): SegmentWriter? {
        val seg = activeSegment ?: return null
        activeSegment = null
        seg.close()
        return seg
    }

    // ---- AudioRecord ----

    @Suppress("DEPRECATION") // 旧构造器在 API 31+ deprecated 但 minSdk 29 需兼容
    private fun createAudioRecord(): AudioRecord {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN_MONO, ENCODING_PCM_16BIT)
        val bufferSize = maxOf(minBuffer, FRAME_SIZE * 2 * 2) // 至少 2 个读块
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_IN_MONO,
            ENCODING_PCM_16BIT,
            bufferSize,
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord 初始化失败（可能缺少 RECORD_AUDIO 权限）"
        }
        return record
    }

    /**
     * 单个分段 WAV 的写句柄。只在读循环线程写、外部线程（切段/停止）关闭，
     * 二者经 [segmentLock] 互斥，保证不会向已关闭文件写入。
     */
    private class SegmentWriter(
        val index: Int,
        val file: File,
        private val raf: RandomAccessFile,
        private val openedAtMs: Long,
    ) {
        /** 写入 count 个 int16 样本（小端）。调用方持 segmentLock。 */
        fun write(buffer: ShortArray, count: Int, byteBuffer: ByteBuffer, pcmBytes: ByteArray) {
            byteBuffer.clear()
            byteBuffer.asShortBuffer().put(buffer, 0, count)
            raf.write(pcmBytes, 0, count * 2)
        }

        /** finalize 头部大小字段并关闭。调用方持 segmentLock。 */
        fun close() {
            WavFileWriter.finalize(raf, raf.channel.size().toInt() - WavFileWriter.HEADER_SIZE)
            raf.close()
        }

        fun durationMs(): Long = SystemClock.elapsedRealtime() - openedAtMs
    }
}