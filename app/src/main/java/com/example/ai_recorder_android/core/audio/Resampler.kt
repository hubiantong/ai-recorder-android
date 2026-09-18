package com.example.ai_recorder_android.core.audio

/**
 * 纯 JVM 重采样工具（不依赖任何 Android API，可单测）。
 *
 * 对齐 PC `core/audio/sources.py::_numpy_resample` 的线性插值语义：
 * 输出样本数 = round(n_in * out_rate / in_rate)，采样位置在 [0, n_in-1] 均匀分布，
 * 相邻样本线性插值（凸组合，结果不会超出输入样本范围，无需饱和截断）。
 * 16k→8k 时输出样本数 = 输入样本数 / 2。
 *
 * 供 realtime tier（todo 13，paraformer-realtime-8k-v2 需 8kHz PCM）使用。
 */
object Resampler {

    /** 全链路目标采样率（对齐 PC `TARGET_SAMPLE_RATE`，Whisper 标准）。 */
    const val TARGET_SAMPLE_RATE = 16000

    /** realtime tier 采样率（paraformer-realtime-8k-v2）。 */
    const val REALTIME_SAMPLE_RATE = 8000

    /**
     * 线性插值重采样 int16 mono 样本。
     *
     * @param input 输入样本（int16 mono）
     * @param inRate 输入采样率（Hz），必须 > 0
     * @param outRate 输出采样率（Hz），必须 > 0
     * @return 重采样后的样本，长度 = round(input.size * outRate / inRate)（至少 1）
     */
    fun resample(input: ShortArray, inRate: Int, outRate: Int): ShortArray {
        require(inRate > 0) { "inRate must be > 0, got $inRate" }
        require(outRate > 0) { "outRate must be > 0, got $outRate" }
        if (inRate == outRate) return input.copyOf()
        val nIn = input.size
        if (nIn == 0) return ShortArray(0)
        val nOut = Math.round(nIn.toDouble() * outRate / inRate).toInt().coerceAtLeast(1)
        if (nIn == 1 || nOut == 1) {
            // 单样本/单输出边界：重复首样本（对齐 PC np.repeat 分支）
            return ShortArray(nOut) { input[0] }
        }
        val out = ShortArray(nOut)
        val step = (nIn - 1).toDouble() / (nOut - 1).toDouble()
        for (i in 0 until nOut) {
            val pos = i * step
            val idx0 = pos.toInt()
            val idx1 = if (idx0 + 1 < nIn) idx0 + 1 else nIn - 1
            val frac = pos - idx0
            val sample = input[idx0] * (1.0 - frac) + input[idx1] * frac
            out[i] = Math.round(sample).toShort()
        }
        return out
    }

    /** 16kHz → 8kHz（realtime tier 用，输出样本数 = 输入 / 2）。 */
    fun to8k(input: ShortArray): ShortArray =
        resample(input, TARGET_SAMPLE_RATE, REALTIME_SAMPLE_RATE)
}