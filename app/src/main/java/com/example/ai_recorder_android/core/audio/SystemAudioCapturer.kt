package com.example.ai_recorder_android.core.audio

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.Log
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * 会议模式系统音频捕获（MediaProjection）。
 *
 * 对齐 PC `core/audio/sources.py` 音源策略，统一产出 16kHz / 16-bit / mono int16 帧：
 * - [MeetingAudioSource.MIC]：仅麦克风（普通模式，无需 MediaProjection 授权）
 * - [MeetingAudioSource.SYSTEM]：仅系统音频（AudioPlaybackCaptureConfiguration，API 29+）
 * - [MeetingAudioSource.MIXED]：mic + 系统混音（API 31+ 用 AudioMix，否则双 AudioRecord 手动混音）
 *
 * 授权流程（调用方，todo 16）：
 * 1. [createScreenCaptureIntent] 获取 Intent，Activity 用 startActivityForResult 启动
 * 2. 用户授权后 resultCode == Activity.RESULT_OK，把 resultCode/resultData 传给 [start]
 * 3. API 34+ 每次会话必须重新授权（MediaProjection 不可跨会话复用，Metis B1.2）；
 *    API 29-33 可复用已授权 projection（下次 start 无需重新授权）
 *
 * 本类不负责写 WAV / 音量 / 暂停（todo 8 AudioRecorderEngine），只产出帧回调。
 * 系统音频捕获仅能匹配 USAGE_UNKNOWN / USAGE_GAME / USAGE_MEDIA 的播放
 * （USAGE_VOICE_COMMUNICATION 等不可捕获，见 AudioPlaybackCaptureConfiguration 文档）。
 */
class SystemAudioCapturer(
    private val context: Context,
    private val mediaProjectionManager: MediaProjectionManager,
) {

    /** 会议模式音源（对齐 PC AudioSource 常量：mic / system / mixed）。 */
    enum class MeetingAudioSource(val value: String) {
        MIXED("mixed"),
        SYSTEM("system"),
        MIC("mic");

        companion object {
            fun fromValue(value: String?): MeetingAudioSource? =
                entries.firstOrNull { it.value == value }
        }
    }

    /** 帧回调（音频线程调用，必须非阻塞、轻量）。 */
    fun interface FrameListener {
        /** @param samples 16kHz mono int16 样本 */
        fun onFrame(samples: ShortArray)
    }

    private val tag = "SystemAudioCapturer"

    private val running = AtomicBoolean(false)
    private var projection: MediaProjection? = null
    private var records: List<AudioRecord> = emptyList()
    private var threads: List<Thread> = emptyList()
    private var mixer: FrameMixer? = null

    /** 创建屏幕捕获授权 Intent（Activity 用 startActivityForResult 启动）。 */
    fun createScreenCaptureIntent(): Intent = mediaProjectionManager.createScreenCaptureIntent()

    /**
     * 开始捕获。
     *
     * @param resultCode Activity.RESULT_OK 表示用户已授权；否则抛 [IllegalStateException]
     * @param resultData 授权返回的 Intent
     * @param source 音源模式
     * @param listener 帧回调
     * @throws IllegalStateException 已在捕获中 / MediaProjection 授权被拒绝
     * @throws UnsupportedOperationException 设备不支持请求的音频参数（AudioRecord 创建失败）
     */
    fun start(
        resultCode: Int,
        resultData: Intent,
        source: MeetingAudioSource,
        listener: FrameListener,
    ) {
        if (running.get()) throw IllegalStateException("Already capturing")
        if (source != MeetingAudioSource.MIC && resultCode != Activity.RESULT_OK) {
            throw IllegalStateException("MediaProjection permission denied (resultCode=$resultCode)")
        }
        running.set(true)
        try {
            when (source) {
                MeetingAudioSource.MIC -> startMicOnly(listener)
                MeetingAudioSource.SYSTEM -> startSystemOnly(resultCode, resultData, listener)
                MeetingAudioSource.MIXED -> startMixed(resultCode, resultData, listener)
            }
        } catch (e: Exception) {
            running.set(false)
            stop()
            throw e
        }
    }

    /** 停止捕获并释放资源。API 34+ 会话结束必须 stop projection；API 29-33 保留供下次复用。 */
    fun stop() {
        running.set(false)
        threads.forEach { t -> t.interrupt() }
        threads.forEach { t -> runCatching { t.join(500) } }
        threads = emptyList()
        records.forEach { r -> runCatching { r.stop() } }
        records.forEach { r -> runCatching { r.release() } }
        records = emptyList()
        mixer = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            projection?.let { runCatching { it.stop() } }
            projection = null
        }
    }

    // ---------- 音源路径 ----------

    private fun startMicOnly(listener: FrameListener) {
        val record = buildMicRecord(Resampler.TARGET_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO)
        records = listOf(record)
        startReadThread(record, "audio-capture-mic") { chunk ->
            // 16kHz mono 直出
            listener.onFrame(chunk)
        }
    }

    private fun startSystemOnly(resultCode: Int, resultData: Intent, listener: FrameListener) {
        val projection = obtainProjection(resultCode, resultData)
        val record = buildSystemRecord(projection)
        val sampleRate = record.sampleRate
        val channels = record.channelCount
        records = listOf(record)
        startReadThread(record, "audio-capture-system") { chunk ->
            listener.onFrame(to16kMono(chunk, sampleRate, channels))
        }
    }

    private fun startMixed(resultCode: Int, resultData: Intent, listener: FrameListener) {
        val projection = obtainProjection(resultCode, resultData)
        startMixedManually(projection, listener)
    }

    /** API 29+：双 AudioRecord 手动混音（16kHz 对齐后叠加）。 */
    private fun startMixedManually(projection: MediaProjection, listener: FrameListener) {
        val mixer = FrameMixer(listener)
        this.mixer = mixer

        val micRecord = buildMicRecord(Resampler.TARGET_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO)
        val systemRecord = buildSystemRecord(projection)
        val systemRate = systemRecord.sampleRate
        val systemChannels = systemRecord.channelCount

        records = listOf(micRecord, systemRecord)
        startReadThread(micRecord, "audio-capture-mic") { chunk ->
            mixer.onMicFrame(chunk) // 16kHz mono
        }
        startReadThread(systemRecord, "audio-capture-system") { chunk ->
            mixer.onSystemFrame(to16kMono(chunk, systemRate, systemChannels))
        }
    }

    // ---------- 构建 ----------

    private fun buildMicRecord(sampleRate: Int, channelMask: Int): AudioRecord {
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val bufferSize = maxOf(minBuf * 2, sampleRate * 2) // 至少 1s
        return AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .build()
    }

    private fun buildSystemRecord(projection: MediaProjection): AudioRecord {
        // 请求 16kHz mono；实际采样率/声道由系统决定（通常 48kHz stereo），
        // 读循环中用 record.sampleRate / record.channelCount 检测并转换。
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(Resampler.TARGET_SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val minBuf = AudioRecord.getMinBufferSize(
            Resampler.TARGET_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = maxOf(minBuf * 2, Resampler.TARGET_SAMPLE_RATE * 2)
        return AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSize)
            .setAudioPlaybackCaptureConfig(buildCaptureConfig(projection))
            .build()
    }

    private fun buildCaptureConfig(projection: MediaProjection): AudioPlaybackCaptureConfiguration =
        AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .build()

    // ---------- MediaProjection ----------

    private fun obtainProjection(resultCode: Int, resultData: Intent): MediaProjection =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            obtainProjectionApi34(resultCode, resultData)
                ?: throw IllegalStateException("MediaProjection 获取失败（API 34+）")
        } else {
            // API 29-33：复用已授权 projection（无需重新授权）
            projection ?: mediaProjectionManager.getMediaProjection(resultCode, resultData)
                ?.also { projection = it }
                ?: throw IllegalStateException("MediaProjection 获取失败")
        }

    /** API 34+：每次会话必须重新授权（旧 projection 先 stop，Metis B1.2）。 */
    private fun obtainProjectionApi34(resultCode: Int, resultData: Intent): MediaProjection? {
        projection?.let { runCatching { it.stop() } }
        return mediaProjectionManager.getMediaProjection(resultCode, resultData)
            ?.also { projection = it }
    }

    // ---------- 读循环与格式转换 ----------

    private fun startReadThread(record: AudioRecord, name: String, onChunk: (ShortArray) -> Unit) {
        val thread = Thread({
            val buf = ShortArray(READ_FRAME_SIZE)
            while (running.get()) {
                val n = record.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (n > 0) {
                    try {
                        onChunk(buf.copyOf(n))
                    } catch (e: Exception) {
                        Log.w(tag, "帧回调异常: ${e.message}")
                    }
                } else if (n < 0) {
                    Log.w(tag, "AudioRecord.read 错误码: $n")
                    break
                }
            }
        }, name)
        threads = threads + thread
        thread.start()
    }

    /** 任意采样率/声道 → 16kHz mono int16（下混 + 重采样）。 */
    private fun to16kMono(chunk: ShortArray, sampleRate: Int, channels: Int): ShortArray {
        val mono = if (channels > 1) stereoToMono(chunk) else chunk
        return if (sampleRate == Resampler.TARGET_SAMPLE_RATE) {
            mono
        } else {
            Resampler.resample(mono, sampleRate, Resampler.TARGET_SAMPLE_RATE)
        }
    }

    /** 立体声 int16 → mono（左右平均，对齐 PC `_stereo_to_mono`）。 */
    private fun stereoToMono(stereo: ShortArray): ShortArray {
        val n = stereo.size / 2
        val mono = ShortArray(n)
        for (i in 0 until n) {
            mono[i] = ((stereo[2 * i].toInt() + stereo[2 * i + 1].toInt()) / 2).toShort()
        }
        return mono
    }

    private fun releaseRecords() {
        records.forEach { r -> runCatching { r.stop() } }
        records.forEach { r -> runCatching { r.release() } }
        records = emptyList()
    }

    private fun stopThreads() {
        threads.forEach { t -> t.interrupt() }
        threads.forEach { t -> runCatching { t.join(500) } }
        threads = emptyList()
    }

    /**
     * 手动混音器：两路 16kHz mono int16 帧配对叠加（饱和截断防溢出）。
     *
     * 对齐 PC `_MixedSource` 语义：
     * - 两路都有数据 → 按最小长度配对叠加，剩余帧留队
     * - 单路累积 ≥ 0.5s（8000 帧）→ 降级单路直出（sticky，避免反复进/退降级 flapping）
     * - 每路缓冲上限 1s（16000 帧），溢出丢弃最旧帧
     */
    private class FrameMixer(private val listener: FrameListener) {
        private val micQueue = ArrayDeque<ShortArray>()
        private val systemQueue = ArrayDeque<ShortArray>()
        private var micFrames = 0
        private var systemFrames = 0
        private var degradedMic = false
        private var degradedSystem = false

        @Synchronized
        fun onMicFrame(samples: ShortArray) {
            micQueue.addLast(samples)
            micFrames += samples.size
            trimIfNeeded(micQueue, micFrames) { micFrames = it }
            drain()
        }

        @Synchronized
        fun onSystemFrame(samples: ShortArray) {
            systemQueue.addLast(samples)
            systemFrames += samples.size
            trimIfNeeded(systemQueue, systemFrames) { systemFrames = it }
            drain()
        }

        private fun trimIfNeeded(queue: ArrayDeque<ShortArray>, total: Int, setTotal: (Int) -> Unit) {
            var t = total
            while (queue.isNotEmpty() && t > MAX_BUFFER_FRAMES) {
                t -= queue.removeFirst().size
            }
            setTotal(t)
        }

        private fun drain() {
            if (micQueue.isEmpty() && systemQueue.isEmpty()) return
            // 情况 1：两路都有数据 → 配对混音（降级 flag 保持，不退出 — 避免 flapping）
            if (micQueue.isNotEmpty() && systemQueue.isNotEmpty()) {
                val mic = concat(micQueue)
                val sys = concat(systemQueue)
                micQueue.clear()
                systemQueue.clear()
                micFrames = 0
                systemFrames = 0
                val n = min(mic.size, sys.size)
                listener.onFrame(mixSaturating(mic, sys, n))
                if (mic.size > n) {
                    micQueue.addLast(mic.copyOfRange(n, mic.size))
                    micFrames = mic.size - n
                }
                if (sys.size > n) {
                    systemQueue.addLast(sys.copyOfRange(n, sys.size))
                    systemFrames = sys.size - n
                }
                return
            }
            // 情况 2：仅 mic 有数据 → 已降级或累积 ≥ 阈值则直出，否则留队等配对
            if (micQueue.isNotEmpty()) {
                if (degradedMic || micFrames >= DEGRADE_THRESHOLD) {
                    degradedMic = true
                    val mic = concat(micQueue)
                    micQueue.clear()
                    micFrames = 0
                    listener.onFrame(mic)
                }
                return
            }
            // 情况 3：仅 system 有数据（对称处理）
            if (systemQueue.isNotEmpty()) {
                if (degradedSystem || systemFrames >= DEGRADE_THRESHOLD) {
                    degradedSystem = true
                    val sys = concat(systemQueue)
                    systemQueue.clear()
                    systemFrames = 0
                    listener.onFrame(sys)
                }
            }
        }

        private fun concat(queue: ArrayDeque<ShortArray>): ShortArray {
            val total = queue.sumOf { it.size }
            val out = ShortArray(total)
            var offset = 0
            for (chunk in queue) {
                chunk.copyInto(out, offset)
                offset += chunk.size
            }
            return out
        }

        /** int32 累积后饱和截断回 int16（对齐 PC `_mix_int16`）。 */
        private fun mixSaturating(a: ShortArray, b: ShortArray, n: Int): ShortArray {
            val out = ShortArray(n)
            for (i in 0 until n) {
                val sum = a[i].toInt() + b[i].toInt()
                out[i] = when {
                    sum > 32767 -> 32767
                    sum < -32768 -> -32768
                    else -> sum.toShort()
                }
            }
            return out
        }

        companion object {
            /** 单路缓冲上限（1s @ 16kHz），对齐 PC `_MIXED_MAX_BUF_FRAMES`。 */
            private const val MAX_BUFFER_FRAMES = 16000
            /** 降级阈值（0.5s @ 16kHz），对齐 PC `_MIXED_DEGRADE_THRESHOLD`。 */
            private const val DEGRADE_THRESHOLD = MAX_BUFFER_FRAMES / 2
        }
    }

    companion object {
        /** 读块大小（帧数）：1024 帧 @16kHz ≈ 64ms。 */
        private const val READ_FRAME_SIZE = 1024
    }
}