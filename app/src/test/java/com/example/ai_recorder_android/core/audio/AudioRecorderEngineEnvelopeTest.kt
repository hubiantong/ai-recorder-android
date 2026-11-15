package com.example.ai_recorder_android.core.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AudioRecorderEngine.computeEnvelope] 纯函数单测（无 Android 依赖，可 JVM 直跑）：
 * - 恒定幅值/静音/满幅方波的 RMS 标定（对齐 [AudioRecorderEngine.computeRms] 的 int16 满量程归一化）；
 * - 边界：空 PCM、奇数字节长度（截断悬挂字节）、尾桶吸收整除余数；
 * - 非法 bucketsPerFrame 快速失败。
 */
class AudioRecorderEngineEnvelopeTest {

    /** ShortArray → 小端 int16 PCM 字节（与引擎落盘/转发编码一致）。 */
    private fun pcmOf(vararg samples: Short): ByteArray {
        val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        buf.asShortBuffer().put(samples)
        return buf.array()
    }

    private fun constant(count: Int, amplitude: Short): ShortArray =
        ShortArray(count) { amplitude }

    // ---------- RMS 标定 ----------

    @Test
    fun `恒定半幅信号各桶RMS等于0点5`() {
        val envelope = AudioRecorderEngine.computeEnvelope(pcmOf(*constant(1600, 16384)))
        assertEquals(4, envelope.size)
        for (bar in envelope) {
            assertEquals(0.5f, bar, 1e-6f)
        }
    }

    @Test
    fun `满幅方波各桶RMS接近1`() {
        // +32767/-32767 交替：|x| 恒定满幅 → RMS = 32767/32768 ≈ 1.0
        val samples = ShortArray(1600) { if (it % 2 == 0) 32767 else -32767 }
        val envelope = AudioRecorderEngine.computeEnvelope(pcmOf(*samples))
        for (bar in envelope) {
            assertEquals(32767f / 32768f, bar, 1e-3f)
        }
    }

    @Test
    fun `静音帧全桶为零`() {
        val envelope = AudioRecorderEngine.computeEnvelope(pcmOf(*constant(1600, 0)))
        assertTrue(envelope.all { it == 0f })
    }

    // ---------- 边界 ----------

    @Test
    fun `空PCM返回全零包络`() {
        val envelope = AudioRecorderEngine.computeEnvelope(ByteArray(0))
        assertEquals(4, envelope.size)
        assertTrue(envelope.all { it == 0f })
    }

    @Test
    fun `奇数字节长度截断悬挂字节不崩溃`() {
        // 5 字节 = 2 个完整 int16 样本 + 1 个悬挂字节：只解码 2 个样本
        val samples = shortArrayOf(16384, 16384)
        val pcm = pcmOf(*samples) + byteArrayOf(0x7F)
        val envelope = AudioRecorderEngine.computeEnvelope(pcm)
        // 2 样本分 4 桶：前 3 桶空（0.0），尾桶吸收全部样本
        assertEquals(listOf(0f, 0f, 0f, 0.5f), envelope.toList())
    }

    @Test
    fun `尾桶吸收整除余数保证样本无遗漏`() {
        // 10 样本分 4 桶：bucketSize=2，前 3 桶各 2 样本，尾桶 4 样本
        val samples = shortArrayOf(
            16384, 16384, // 桶 0：半幅
            16384, 16384, // 桶 1：半幅
            0, 0,         // 桶 2：静音
            0, 0, 0, 0,   // 桶 3（尾桶，吸收余数）：静音
        )
        val envelope = AudioRecorderEngine.computeEnvelope(pcmOf(*samples))
        assertEquals(listOf(0.5f, 0.5f, 0f, 0f), envelope.toList())
    }

    @Test
    fun `自定义桶数生效`() {
        val envelope = AudioRecorderEngine.computeEnvelope(pcmOf(*constant(1600, 16384)), bucketsPerFrame = 8)
        assertEquals(8, envelope.size)
        assertTrue(envelope.all { it == 0.5f })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `桶数为零快速失败`() {
        AudioRecorderEngine.computeEnvelope(pcmOf(16384), bucketsPerFrame = 0)
    }
}
