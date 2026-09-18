package com.example.ai_recorder_android.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Resampler 纯 JVM 单测（不依赖 Android，本机可写不可跑，其他机器验证）。
 * 核心断言：1s 16kHz（16000 样本）→ 8kHz 输出样本数 = 8000。
 */
class ResamplerTest {

    @Test
    fun resample16kTo8k_halvesSampleCount() {
        // 1s @ 16kHz
        val input = ShortArray(16000) { (it % 1000).toShort() }
        val out = Resampler.resample(input, 16000, 8000)
        assertEquals(8000, out.size)
    }

    @Test
    fun to8k_halvesSampleCount() {
        val input = ShortArray(16000) { (it % 1000).toShort() }
        val out = Resampler.to8k(input)
        assertEquals(8000, out.size)
    }

    @Test
    fun resample_sameRate_returnsCopy() {
        val input = shortArrayOf(1, 2, 3, 4, 5)
        val out = Resampler.resample(input, 16000, 16000)
        assertEquals(input.size, out.size)
        assertTrue(input.contentEquals(out))
        // 返回副本而非原数组引用
        assertTrue(out !== input)
    }

    @Test
    fun resample_emptyInput_returnsEmpty() {
        val out = Resampler.resample(ShortArray(0), 16000, 8000)
        assertEquals(0, out.size)
    }

    @Test
    fun resample_singleSample_repeats() {
        val out = Resampler.resample(shortArrayOf(42), 16000, 8000)
        assertEquals(1, out.size)
        assertEquals(42, out[0].toInt())
    }

    @Test
    fun resample_linearRamp_interpolatesAccurately() {
        // 线性信号 0..15999：线性插值应精确还原（误差 < 1）
        val input = ShortArray(16000) { it.toShort() }
        val out = Resampler.resample(input, 16000, 8000)
        assertEquals(8000, out.size)
        for (j in 0 until 8000) {
            val expected = j * 15999.0 / 7999.0
            assertTrue(
                "j=$j: 实际 ${out[j]} vs 期望 $expected",
                Math.abs(out[j] - expected) < 1.0,
            )
        }
    }

    @Test
    fun resample_linearInterpolation_preservesRange() {
        // 输入范围 0..31998，插值（凸组合）结果不应越界
        val input = ShortArray(16000) { (it * 2).toShort() }
        val out = Resampler.resample(input, 16000, 8000)
        assertEquals(8000, out.size)
        assertTrue(out.all { it >= 0 })
        assertTrue(out.all { it <= 32767 })
        // 首尾样本保留
        assertEquals(input[0].toInt(), out[0].toInt())
        assertEquals(input.last().toInt(), out.last().toInt())
    }

    @Test
    fun resample_16kTo8k_sineWave_keepsAmplitude() {
        // 1kHz 正弦 @16kHz（峰值 10000），重采样到 8kHz 后峰值应保持
        val input = ShortArray(16000) { i ->
            (Math.sin(2.0 * Math.PI * 1000.0 * i / 16000.0) * 10000).toInt().toShort()
        }
        val out = Resampler.resample(input, 16000, 8000)
        assertEquals(8000, out.size)
        val peak = out.maxOf { Math.abs(it.toInt()) }
        assertTrue("峰值应接近 10000，实际 $peak", peak in 9000..10000)
    }

    @Test
    fun resample_oddInputLength_roundsOutput() {
        // 奇数长度：n_out = round(16001 * 8000 / 16000) = round(8000.5) = 8001
        val input = ShortArray(16001) { 1 }
        val out = Resampler.resample(input, 16000, 8000)
        assertEquals(8001, out.size)
    }

    @Test
    fun resample_invalidRates_throws() {
        try {
            Resampler.resample(shortArrayOf(1), 0, 8000)
            assertTrue("应抛 IllegalArgumentException", false)
        } catch (e: IllegalArgumentException) {
            // expected
        }
        try {
            Resampler.resample(shortArrayOf(1), 16000, -1)
            assertTrue("应抛 IllegalArgumentException", false)
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}