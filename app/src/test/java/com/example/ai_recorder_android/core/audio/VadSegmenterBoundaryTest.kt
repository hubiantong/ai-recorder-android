package com.example.ai_recorder_android.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * VadSegmenter 参数化边界单测（纯 JVM，本机不跑 gradlew，其他机器验证）。
 *
 * 覆盖任务书要求的边界条件：
 * - [silenceThreshold]：0.0 / 1.0 / 负数（`isSpeech = level >= threshold` 的边界）
 * - [minSegment]：0 / 负数 / 超大值（stop() 与 finalizeSegment 的丢弃判定）
 * - [maxSegment] < [minSegment]：强制截断与丢弃判定的交互
 *
 * 帧大小 100ms（1600 样本 @16kHz），与 [VadSegmenterTest] 一致。
 * 时间戳语义：段截断时刻 = 静音开始 + silenceDuration（PC 状态机），见 VadSegmenterTest 注释。
 */
class VadSegmenterBoundaryTest {

    private val sampleRate = 16000
    private val frameSize = 1600 // 100ms @16kHz

    /** 1 帧 1kHz 正弦波（振幅 16000 → 归一化 RMS≈0.345）。 */
    private fun sineFrame(
        startSample: Long,
        freq: Double = 1000.0,
        amplitude: Int = 16000,
    ): ShortArray = ShortArray(frameSize) { i ->
        val t = (startSample + i).toDouble() / sampleRate
        (amplitude * sin(2.0 * PI * freq * t)).toInt().toShort()
    }

    private fun silenceFrame(): ShortArray = ShortArray(frameSize)

    // ---------- silenceThreshold 边界 ----------

    /** threshold=0.0：静音（level=0.0）满足 `0.0 >= 0.0` → 全帧视为语音，段从 0.0 开始。 */
    @Test
    fun thresholdZero_treatsSilenceAsSpeech() {
        val segments = mutableListOf<Segment>()
        val vad = VadSegmenter(
            silenceThreshold = 0.0,
            onSegmentFinalize = { _, seg -> segments.add(seg) },
        )
        vad.start()

        repeat(2720) { vad.consumeFrame(silenceFrame()) } // 272s 全静音

        // maxSegment(270s) 强制截断 → 段 [0.0, 270.0]
        assertEquals(1, segments.size)
        assertEquals(0.0, segments[0].startTime, 0.05)
        assertEquals(270.0, segments[0].endTime, 0.05)
        // 活动段 [270.1, 272.0] 由 stop() 返回
        val last = vad.stop()
        assertTrue(last != null)
        assertEquals(270.1, last!!.startTime, 0.05)
        assertEquals(272.0, last.endTime, 0.05)
    }

    /** threshold=1.0：正弦波 RMS≈0.345 < 1.0 → 永不判定为语音，0 段。 */
    @Test
    fun thresholdOne_neverDetectsSpeech() {
        val segments = mutableListOf<Segment>()
        val vad = VadSegmenter(
            silenceThreshold = 1.0,
            onSegmentFinalize = { _, seg -> segments.add(seg) },
        )
        vad.start()

        var sample = 0L
        repeat(50) { // 5s 大音量语音
            vad.consumeFrame(sineFrame(sample))
            sample += frameSize
        }

        assertEquals(0, segments.size)
        assertNull(vad.stop())
    }

    /** threshold=负数：level 恒 ≥ 0 > 负数 → 全帧视为语音，段从 0.0 开始。 */
    @Test
    fun thresholdNegative_treatsAllAsSpeech() {
        val segments = mutableListOf<Segment>()
        val vad = VadSegmenter(
            silenceThreshold = -0.5,
            onSegmentFinalize = { _, seg -> segments.add(seg) },
        )
        vad.start()

        repeat(20) { vad.consumeFrame(silenceFrame()) } // 2s 静音

        assertEquals(0, segments.size)
        val seg = vad.stop()
        assertTrue(seg != null)
        assertEquals(0.0, seg!!.startTime, 0.05)
        assertEquals(2.0, seg.endTime, 0.05)
        assertEquals(2.0, seg.duration, 0.05)
    }

    // ---------- minSegment 边界 ----------

    /** minSegment=0：`duration < 0` 恒假 → 极短段也保留。 */
    @Test
    fun minSegmentZero_keepsTinySegment() {
        val vad = VadSegmenter(minSegment = 0.0)
        vad.start()

        vad.consumeFrame(sineFrame(0L)) // 0.1s 语音

        val seg = vad.stop()
        assertTrue(seg != null)
        assertEquals(0.0, seg!!.startTime, 0.05)
        assertEquals(0.1, seg.endTime, 0.05)
        assertEquals(0.1, seg.duration, 0.05)
    }

    /** minSegment=负数：同 0，极短段保留。 */
    @Test
    fun minSegmentNegative_keepsTinySegment() {
        val vad = VadSegmenter(minSegment = -1.0)
        vad.start()

        vad.consumeFrame(sineFrame(0L)) // 0.1s 语音

        val seg = vad.stop()
        assertTrue(seg != null)
        assertEquals(0.1, seg!!.duration, 0.05)
    }

    /** minSegment=超大值：静音截断路径的段（2.0s）也被丢弃，stop() 返回 null。 */
    @Test
    fun minSegmentHuge_dropsShortSegments() {
        val segments = mutableListOf<Segment>()
        val vad = VadSegmenter(
            minSegment = 1000.0,
            onSegmentFinalize = { _, seg -> segments.add(seg) },
        )
        vad.start()

        var sample = 0L
        repeat(20) { // 2s 语音
            vad.consumeFrame(sineFrame(sample))
            sample += frameSize
        }
        repeat(20) { vad.consumeFrame(silenceFrame()) } // 2s 静音 → 3.5s 处截断

        // 段 [0.0, 3.5] duration=3.5 < 1000 → 丢弃，无回调
        assertEquals(0, segments.size)
        assertEquals("截断后段索引仍递增", 1, vad.segmentIndex)
        assertNull(vad.stop())
    }

    // ---------- maxSegment < minSegment 交互 ----------

    /** maxSegment=1.0 < minSegment=5.0：每次强制截断的段都被丢弃，0 回调。 */
    @Test
    fun maxSegmentLessThanMinSegment_dropsAllSegments() {
        val segments = mutableListOf<Segment>()
        val vad = VadSegmenter(
            minSegment = 5.0,
            maxSegment = 1.0,
            onSegmentFinalize = { _, seg -> segments.add(seg) },
        )
        vad.start()

        var sample = 0L
        repeat(34) { // 3.4s 连续语音
            vad.consumeFrame(sineFrame(sample))
            sample += frameSize
        }

        // 每 1.0s 强制截断一次但全部 < minSegment 被丢弃
        assertEquals(0, segments.size)
        assertEquals(3, vad.segmentIndex)
        assertNull(vad.stop())
    }

    /** maxSegment=1.0 < minSegment=0.5：截断段时长 1.0 ≥ 0.5 → 正常产出。 */
    @Test
    fun maxSegmentLessThanMinSegment_keepsLongEnoughSegments() {
        val segments = mutableListOf<Segment>()
        val vad = VadSegmenter(
            minSegment = 0.5,
            maxSegment = 1.0,
            onSegmentFinalize = { _, seg -> segments.add(seg) },
        )
        vad.start()

        var sample = 0L
        repeat(20) { // 2s 连续语音
            vad.consumeFrame(sineFrame(sample))
            sample += frameSize
        }

        // 段1 [0.0, 1.0]（maxSegment 截断，1.0 ≥ 0.5 保留）
        assertEquals(1, segments.size)
        assertEquals(0.0, segments[0].startTime, 0.05)
        assertEquals(1.0, segments[0].endTime, 0.05)
        // 活动段 [1.1, 2.0] 由 stop() 返回
        val last = vad.stop()
        assertTrue(last != null)
        assertEquals(1.1, last!!.startTime, 0.05)
        assertEquals(2.0, last.endTime, 0.05)
    }

    /** maxSegment=0.0：首个语音帧后每帧都触发截断（now - start ≥ 0 恒真）。 */
    @Test
    fun maxSegmentZero_finalizesEveryFrame() {
        val segments = mutableListOf<Segment>()
        val vad = VadSegmenter(
            maxSegment = 0.0,
            onSegmentFinalize = { _, seg -> segments.add(seg) },
        )
        vad.start()

        var sample = 0L
        repeat(10) { // 1s 语音
            vad.consumeFrame(sineFrame(sample))
            sample += frameSize
        }

        // 每 2 帧一次截断（帧0 开段、帧1 截断、帧2 开段、帧3 截断…），全部 < minSegment(1.0) 丢弃
        assertEquals(0, segments.size)
        assertEquals(5, vad.segmentIndex)
        assertNull(vad.stop())
    }
}