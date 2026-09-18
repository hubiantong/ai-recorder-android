package com.example.ai_recorder_android.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * VadSegmenter 纯 JVM 单测：合成音频驱动状态机，断言分段边界与 PC `vad_segmenter.py` 对齐。
 *
 * 帧大小：100ms（1600 样本 @16kHz）。PC 版帧大小由 sounddevice 回调决定（非固定值），
 * Kotlin 侧 [VadSegmenter.consumeFrame] 接受任意帧大小，测试取 100ms 便于快速喂帧。
 *
 * 时间戳语义（与 PC 对齐）：段截断时刻 = 静音开始 + silenceDuration(1.5s)，
 * 即段 end 包含 1.5s 静音尾巴。任务书 "end≈5.0s" 指语音内容结束时刻（2.0~5.0s），
 * 实际产出段边界为 [2.0, 6.5]，以 PC 状态机为准。
 */
class VadSegmenterTest {

    private val sampleRate = 16000
    private val frameSize = 1600 // 100ms @16kHz

    /** 生成 1 帧 1kHz 正弦波（int16，振幅 16000 → 归一化 RMS≈0.345 > 0.02）。 */
    private fun sineFrame(
        startSample: Long,
        freq: Double = 1000.0,
        amplitude: Int = 16000,
    ): ShortArray = ShortArray(frameSize) { i ->
        val t = (startSample + i).toDouble() / sampleRate
        (amplitude * sin(2.0 * PI * freq * t)).toInt().toShort()
    }

    private fun silenceFrame(): ShortArray = ShortArray(frameSize)

    private fun ShortArray.toLittleEndianBytes(): ByteArray {
        val bytes = ByteArray(size * 2)
        for (i in indices) {
            val v = this[i].toInt()
            bytes[i * 2] = (v and 0xFF).toByte()
            bytes[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    // ---- 任务书核心场景 ----

    /** 前 2s 静音 + 3s 语音（1kHz 正弦）+ 2s 静音 → 产出 1 个段。 */
    @Test
    fun syntheticAudio_silenceSpeechSilence_producesOneSegment() {
        val segments = mutableListOf<Segment>()
        val vad = VadSegmenter(onSegmentFinalize = { _, seg -> segments.add(seg) })
        vad.start()

        var sample = 0L
        repeat(20) { vad.consumeFrame(silenceFrame()) } // 前 2s 静音
        sample += 20L * frameSize
        repeat(30) { // 3s 语音
            vad.consumeFrame(sineFrame(sample))
            sample += frameSize
        }
        repeat(20) { vad.consumeFrame(silenceFrame()) } // 2s 静音

        assertEquals(1, segments.size)
        val seg = segments[0]
        assertEquals(2.0, seg.startTime, 0.05)
        // PC 语义：静音持续 1.5s 后截断 → end = 语音结束 5.0 + 1.5 = 6.5
        assertEquals(6.5, seg.endTime, 0.05)
        assertEquals(4.5, seg.duration, 0.05)
    }

    /** 连续语音 >270s → maxSegment 强制切段。 */
    @Test
    fun continuousSpeech_overMaxSegment_splitsIntoSegments() {
        val segments = mutableListOf<Segment>()
        val vad = VadSegmenter(onSegmentFinalize = { _, seg -> segments.add(seg) })
        vad.start()

        var sample = 0L
        repeat(2710) { // 271s 连续语音
            vad.consumeFrame(sineFrame(sample))
            sample += frameSize
        }
        repeat(20) { vad.consumeFrame(silenceFrame()) } // 2s 静音收尾

        // 段1 [0.0, 270.0]（maxSegment 强制截断）+ 段2 [270.0, 272.5]（静音截断）
        assertEquals(2, segments.size)
        assertEquals(0.0, segments[0].startTime, 0.05)
        assertEquals(270.0, segments[0].endTime, 0.05)
        assertEquals(270.0, segments[1].startTime, 0.05)
        assertEquals(272.5, segments[1].endTime, 0.05)
    }

    /** 全静音输入 → 产出 0 段，stop() 返回 null。 */
    @Test
    fun allSilence_producesNoSegments() {
        val segments = mutableListOf<Segment>()
        val vad = VadSegmenter(onSegmentFinalize = { _, seg -> segments.add(seg) })
        vad.start()

        repeat(50) { vad.consumeFrame(silenceFrame()) } // 5s 静音

        assertEquals(0, segments.size)
        assertNull(vad.stop())
    }

    // ---- PC 语义补充场景 ----

    /** minSegment：末段过短（<1.0s）在 stop() 时丢弃。 */
    @Test
    fun shortTrailingSegment_droppedOnStop() {
        val segments = mutableListOf<Segment>()
        val vad = VadSegmenter(onSegmentFinalize = { _, seg -> segments.add(seg) })
        vad.start()

        var sample = 0L
        repeat(5) { // 0.5s 语音
            vad.consumeFrame(sineFrame(sample))
            sample += frameSize
        }
        repeat(2) { vad.consumeFrame(silenceFrame()) } // 0.2s 静音（不触发截断）

        assertEquals(0, segments.size)
        // 末段 duration = 0.7s < minSegment(1.0s) → 丢弃
        assertNull(vad.stop())
    }

    /** 静音打断（< silenceDuration）→ 段保持打开，继续累积。 */
    @Test
    fun silenceInterruption_keepsSegmentOpen() {
        val segments = mutableListOf<Segment>()
        val vad = VadSegmenter(onSegmentFinalize = { _, seg -> segments.add(seg) })
        vad.start()

        var sample = 0L
        repeat(10) { // 1s 语音
            vad.consumeFrame(sineFrame(sample))
            sample += frameSize
        }
        repeat(5) { vad.consumeFrame(silenceFrame()) } // 0.5s 静音（< 1.5s，不截断）
        sample += 5L * frameSize
        repeat(10) { // 1s 语音
            vad.consumeFrame(sineFrame(sample))
            sample += frameSize
        }
        repeat(20) { vad.consumeFrame(silenceFrame()) } // 2s 静音 → 截断

        assertEquals(1, segments.size)
        val seg = segments[0]
        assertEquals(0.0, seg.startTime, 0.05)
        // 静音从 2.5s 开始，1.5s 后截断 → end = 4.0
        assertEquals(4.0, seg.endTime, 0.05)
        assertEquals(4.0, seg.duration, 0.05)
    }

    /** stop() 返回活动末段（不经回调）。 */
    @Test
    fun stop_returnsActiveSegment() {
        val segments = mutableListOf<Segment>()
        val vad = VadSegmenter(onSegmentFinalize = { _, seg -> segments.add(seg) })
        vad.start()

        var sample = 0L
        repeat(20) { // 2s 语音
            vad.consumeFrame(sineFrame(sample))
            sample += frameSize
        }

        assertEquals(0, segments.size)
        val seg = vad.stop()
        assertTrue(seg != null)
        assertEquals(0.0, seg!!.startTime, 0.05)
        assertEquals(2.0, seg.endTime, 0.05)
        assertEquals(2.0, seg.duration, 0.05)
    }

    /** ByteArray（小端 int16）输入与 ShortArray 输入行为一致。 */
    @Test
    fun byteArrayInput_matchesShortArrayBehavior() {
        val segments = mutableListOf<Segment>()
        val vad = VadSegmenter(onSegmentFinalize = { _, seg -> segments.add(seg) })
        vad.start()

        var sample = 0L
        repeat(10) { // 1s 语音（ByteArray 小端）
            vad.consumeFrame(sineFrame(sample).toLittleEndianBytes())
            sample += frameSize
        }
        repeat(20) { vad.consumeFrame(silenceFrame()) } // 2s 静音

        assertEquals(1, segments.size)
        val seg = segments[0]
        assertEquals(0.0, seg.startTime, 0.05)
        // 静音从 1.0s 开始，1.5s 后截断 → end = 2.5
        assertEquals(2.5, seg.endTime, 0.05)
    }

    /** 奇数长度 ByteArray 应拒绝（PCM16 必须成对）。 */
    @Test(expected = IllegalArgumentException::class)
    fun byteArrayInput_oddLength_rejected() {
        val vad = VadSegmenter()
        vad.start()
        vad.consumeFrame(byteArrayOf(0, 1, 2))
    }
}