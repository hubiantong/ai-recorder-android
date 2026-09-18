package com.example.ai_recorder_android.core.audio

import kotlin.math.sqrt

/**
 * VAD 静音检测分段器：在音频线程跟踪 RMS，触发段截断。
 *
 * 纯 Kotlin 移植自 PC 版 `core/audio/vad_segmenter.py`，**不依赖任何 Android API**，
 * 保证纯 JVM 可单测。
 *
 * 状态机（WAITING / SPEAKING / SILENCE）：
 * - WAITING：段未开始（段起始或截断后）。首帧非静音 → SPEAKING 并打开段。
 * - SPEAKING：段内持续有语音。
 *   - 出现静音 → SILENCE（开始累计静音时长）。
 *   - 段累积时长 ≥ [maxSegment] → 强制截断（规避 pro_pro 5min 上限）。
 * - SILENCE：段内连续静音。
 *   - 静音时长 ≥ [silenceDuration] → 截断当前段，切回 WAITING。
 *   - 中途出现语音 → 切回 SPEAKING，清空静音计时。
 *   - 段累积时长 ≥ [maxSegment] → 强制截断。
 *
 * 时间戳机制（与 PC 对齐）：基于**音频帧累计时间**（[samplesConsumed] / [sampleRate]）
 * 而非墙钟。原因：① 测试可快速喂帧而不必等待真实墙钟；② 真实录音时音频时间是
 * 媒体时间，不受调度抖动影响，段时长与转写 segments 时间戳同源对齐。
 *
 * 线程安全（与 PC 对齐）：状态更新在锁内完成并返回待回调事件；回调在锁外调用，
 * 避免回调内争用导致死锁。回调 [onSegmentFinalize] 必须非阻塞。
 *
 * @param silenceThreshold 归一化 RMS 阈值（0~1，RMS/32768），约 -34dB
 * @param silenceDuration 触发截断的连续静音秒数
 * @param minSegment 短于此则丢弃（噪音过滤；实际仅在 [stop] 时生效，
 *   因为静音/max 截断路径的段时长恒 ≥ 1.5s，与 PC 行为一致）
 * @param maxSegment 强制截断上限（pro_pro 5min 留 30s 余量）
 * @param sampleRate 音频采样率，用于把帧数换算为秒
 * @param onSegmentFinalize 段截断回调（锁外调用，必须非阻塞）
 */
class VadSegmenter(
    val silenceThreshold: Double = 0.02,
    val silenceDuration: Double = 1.5,
    val minSegment: Double = 1.0,
    val maxSegment: Double = 270.0,
    val sampleRate: Int = 16000,
    private val onSegmentFinalize: ((index: Int, segment: Segment) -> Unit)? = null,
) {

    /** 状态机状态。 */
    enum class State { WAITING, SPEAKING, SILENCE }

    /** 当前状态。 */
    var state: State = State.WAITING
        private set

    /** 下一个段索引（已产出段数，与 PC `_segment_index` 对齐）。 */
    var segmentIndex: Int = 0
        private set

    /** 已消费的音频采样数（音频时间 = samplesConsumed / sampleRate）。 */
    var samplesConsumed: Long = 0
        private set

    /** 当前段起始（音频相对秒）。 */
    var segmentStart: Double? = null
        private set

    /** 当前静音起始（音频相对秒）。 */
    var silenceStart: Double? = null
        private set

    private var stopped = true

    /** 开始追踪：重置状态机（对齐 PC `start()`）。 */
    fun start() {
        synchronized(this) {
            state = State.WAITING
            segmentStart = null
            silenceStart = null
            segmentIndex = 0
            samplesConsumed = 0
            stopped = false
        }
    }

    /**
     * 停止追踪。
     *
     * @return 最后未截断段信息，供调用方收尾转写；若当前无活动段
     *   （段起始为 null 或段过短且丢弃）返回 null。
     *   注意：此处返回的段不会经过 [onSegmentFinalize] 回调，调用方需
     *   自行决定是否触发该段转写（对齐 PC `stop()`）。
     */
    fun stop(): Segment? {
        synchronized(this) {
            stopped = true
            val start = segmentStart ?: return null
            // 用音频时间（非墙钟）计算末段结束时刻，与段截断时间戳同源
            val endTime = samplesConsumed.toDouble() / sampleRate
            val duration = endTime - start
            if (duration < minSegment) return null
            return Segment(start, endTime, duration)
        }
    }

    /** 音频线程入口：推进状态机（int16 样本帧，对齐 PC `consume_frame`）。 */
    fun consumeFrame(samples: ShortArray) {
        if (stopped) return
        val level = normalizedRms(samples)
        val pending = updateState(level, samples.size)
        if (pending != null) {
            onSegmentFinalize?.invoke(pending.index, pending.segment) // 锁外回调
        }
    }

    /** 音频线程入口：推进状态机（小端 int16 PCM 字节帧，Android AudioRecord 输出格式）。 */
    fun consumeFrame(bytes: ByteArray) {
        require(bytes.size % 2 == 0) { "PCM16 字节数必须为偶数，实际 ${bytes.size}" }
        val samples = ShortArray(bytes.size / 2)
        for (i in samples.indices) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt() and 0xFF
            samples[i] = ((hi shl 8) or lo).toShort()
        }
        consumeFrame(samples)
    }

    // ---------- 状态机（锁内） ----------

    /**
     * 推进状态机（内部持锁，调用方无需持锁）。
     *
     * @param level 归一化 RMS（0~1）
     * @param frames 本帧采样数，用于累计音频时间
     * @return 待回调事件（段索引 + 段边界）或 null
     */
    private fun updateState(level: Double, frames: Int): Pending? {
        synchronized(this) {
            if (stopped) return null
            // 当前帧的时间戳（帧开始时刻），基于音频采样数累计
            val now = samplesConsumed.toDouble() / sampleRate
            // 消费本帧采样（下一帧的时间戳将基于更新后的累计值）
            samplesConsumed += frames

            val isSpeech = level >= silenceThreshold
            var pending: Pending? = null

            when (state) {
                State.WAITING -> {
                    if (isSpeech) {
                        state = State.SPEAKING
                        segmentStart = now
                        silenceStart = null
                    }
                }
                State.SPEAKING -> {
                    if (!isSpeech) {
                        state = State.SILENCE
                        silenceStart = now
                    } else if (now - (segmentStart ?: now) >= maxSegment) {
                        // 状态机逻辑上 SPEAKING 时 segmentStart 恒非空；防御性回退 now
                        // （差值 0 不会触发误截断），避免 !! 崩溃
                        pending = finalizeSegment(now)
                    }
                }
                State.SILENCE -> {
                    if (isSpeech) {
                        // 静音打断，继续累积当前段
                        state = State.SPEAKING
                        silenceStart = null
                    } else {
                        // SILENCE 时 silenceStart/segmentStart 恒非空；防御性回退 now（差值 0 不触发截断）
                        if (now - (silenceStart ?: now) >= silenceDuration) {
                            pending = finalizeSegment(now)
                        } else if (now - (segmentStart ?: now) >= maxSegment) {
                            pending = finalizeSegment(now)
                        }
                    }
                }
            }
            return pending
        }
    }

    /**
     * 截断当前段（锁内调用），返回待回调事件或 null（段过短丢弃）。
     * 状态重置为 WAITING，[segmentIndex] 递增（对齐 PC `_finalize_segment_locked`）。
     */
    private fun finalizeSegment(now: Double): Pending? {
        val start = segmentStart
        if (start == null) {
            state = State.WAITING
            return null
        }
        val duration = now - start
        val idx = segmentIndex
        // 状态重置：进入下一段等待
        segmentIndex += 1
        segmentStart = null
        silenceStart = null
        state = State.WAITING
        if (duration < minSegment) return null
        return Pending(idx, Segment(start, now, duration))
    }

    /** 归一化 RMS（0~1）：sqrt(mean(samples^2)) / 32768，对齐 PC `consume_frame`。 */
    private fun normalizedRms(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0L
        for (s in samples) {
            val v = s.toInt()
            sum += v.toLong() * v.toLong()
        }
        val mean = sum.toDouble() / samples.size
        return sqrt(mean) / 32768.0
    }

    /** 待回调事件（段索引 + 段边界）。 */
    private data class Pending(val index: Int, val segment: Segment)
}

/**
 * 分段边界（秒，基于音频帧累计时间，与 PC 段截断时间戳同源）。
 *
 * @param startTime 段起始（音频相对秒）
 * @param endTime 段结束（截断时刻，音频相对秒）
 * @param duration 段时长（endTime - startTime）
 */
data class Segment(
    val startTime: Double,
    val endTime: Double,
    val duration: Double,
)