package com.example.ai_recorder_android.cloud.asr

import com.example.ai_recorder_android.cloud.asr.dto.DashScopeRealtimeClientHeader
import com.example.ai_recorder_android.cloud.asr.dto.DashScopeRealtimeFinishTask
import com.example.ai_recorder_android.cloud.asr.dto.DashScopeRealtimeParameters
import com.example.ai_recorder_android.cloud.asr.dto.DashScopeRealtimeResultPayload
import com.example.ai_recorder_android.cloud.asr.dto.DashScopeRealtimeRunTask
import com.example.ai_recorder_android.cloud.asr.dto.DashScopeRealtimeRunTaskPayload
import com.example.ai_recorder_android.cloud.asr.dto.DashScopeRealtimeServerMessage
import com.example.ai_recorder_android.core.audio.Resampler
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.toByteString
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * DashScope Paraformer 实时语音识别（realtime 档）WebSocket 连接器。
 *
 * 协议依据 `.omo/notes/dashscope-realtime-protocol.md`（官方 action 协议）：
 * 1. 握手 header `Authorization: Bearer <api_key>`（无效 key → HTTP 401/403）
 * 2. 连接建立后发送 run-task（model=paraformer-realtime-8k-v2, format=pcm, sample_rate=8000）
 * 3. 收到 task-started 后发送二进制 PCM 帧（帧大小 = sampleRate / 5 = 1600 bytes @ 8kHz，100ms）
 * 4. 接收 result-generated：sentence_end=false → [DashScopeRealtimeCallback.onPartial]，
 *    sentence_end=true → [DashScopeRealtimeCallback.onSentence]
 * 5. finish-task → task-finished → 关闭连接
 *
 * 输入为 16kHz int16 mono PCM（录音引擎输出），内部经 [Resampler.to8k] 降采样到 8kHz 后分帧发送。
 * API key 由调用方注入（todo 17 接线时传 BuildConfig.DASHSCOPE_API_KEY），不在此硬编码。
 *
 * 连接失败（key 缺失/网络/握手 401/403）→ [DashScopeRealtimeException]（retryable=true），
 * 网络层失败经 [DashScopeRealtimeCallback.onError] 回调；task-failed 为不可重试错误。
 */
class DashScopeRealtimeConnector(
    private val apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    private val language: String? = null,
    private val url: String = DEFAULT_URL,
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = defaultJson(),
    private val callback: DashScopeRealtimeCallback,
) {
    private val taskId: String = UUID.randomUUID().toString()
    private val taskStarted = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val frameBuffer = PcmFrameBuffer(frameBytes(sampleRate))
    private val messageHandler = DashScopeRealtimeMessageHandler(json, callback)
    private var webSocket: WebSocket? = null

    /**
     * 建立 WebSocket 连接并发送 run-task。
     *
     * @throws DashScopeRealtimeException apiKey 为空时同步抛出（retryable=true，不发起网络请求）；
     *         网络/握手失败经 [DashScopeRealtimeCallback.onError] 异步回调（retryable=true）。
     */
    fun connect() {
        check(!closed.get()) { "DashScopeRealtimeConnector is closed" }
        if (apiKey.isBlank()) {
            throw DashScopeRealtimeException(
                "DASHSCOPE_API_KEY 未配置，无法连接 DashScope realtime",
                retryable = true,
            )
        }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("user-agent", "ai-recorder-android/1.0")
            .build()
        webSocket = client.newWebSocket(request, listener)
    }

    /**
     * 发送 16kHz int16 mono PCM（小端）。内部降采样到 8kHz 并切分为
     * [frameBytes] 大小的帧；task-started 之前的数据先缓冲，收到 task-started 后一并发送。
     */
    fun sendPcm16k(pcm16k: ByteArray) {
        check(!closed.get()) { "DashScopeRealtimeConnector is closed" }
        val resampled = Resampler.to8k(toShortArray(pcm16k))
        val frames = frameBuffer.add(toByteArray(resampled))
        if (taskStarted.get()) {
            frames.forEach { webSocket?.send(it.toByteString()) }
        }
    }

    /** 发送缓冲区内不足一帧的剩余 PCM（finish 前调用，避免尾部数据丢失）。 */
    fun flush() {
        val remaining = frameBuffer.remaining()
        if (remaining.isNotEmpty()) {
            frameBuffer.clear()
            if (taskStarted.get()) {
                webSocket?.send(remaining.toByteString())
            }
        }
    }

    /** 发送 finish-task 指令，通知服务端结束任务。 */
    fun finish() {
        flush()
        val message = DashScopeRealtimeFinishTask(
            header = DashScopeRealtimeClientHeader(action = "finish-task", taskId = taskId),
        )
        webSocket?.send(json.encodeToString(message))
    }

    /** 主动关闭连接（幂等）。 */
    fun close() {
        if (closed.compareAndSet(false, true)) {
            webSocket?.close(1000, "client close")
        }
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val message = DashScopeRealtimeRunTask(
                header = DashScopeRealtimeClientHeader(action = "run-task", taskId = taskId),
                payload = DashScopeRealtimeRunTaskPayload(
                    model = model,
                    parameters = DashScopeRealtimeParameters(
                        format = "pcm",
                        sampleRate = sampleRate,
                        languageHints = language?.let { listOf(it) },
                    ),
                ),
            )
            webSocket.send(json.encodeToString(message))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            messageHandler.handle(text, onTaskStarted = {
                taskStarted.set(true)
                flush()
            })
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            callback.onError(
                DashScopeRealtimeException(
                    "DashScope realtime 连接失败: ${t.message}",
                    cause = t,
                    retryable = true,
                ),
            )
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            callback.onClosed()
        }
    }

    companion object {
        /** 官方固定端点（华北2北京，旧域名仍可用；新域名需 WorkspaceId，留 F3 验证）。 */
        const val DEFAULT_URL = "wss://dashscope.aliyuncs.com/api-ws/v1/inference"

        /** realtime 档模型（8kHz 电话客服/语音信箱场景）。 */
        const val DEFAULT_MODEL = "paraformer-realtime-8k-v2"

        /** 8k-v2 模型仅支持 8000 Hz。 */
        const val DEFAULT_SAMPLE_RATE = 8000

        /**
         * 帧大小（bytes）= int(sample_rate * 0.2)，与 PC `dashscope_realtime.py:164` 对齐。
         * 整数运算 `sampleRate / 5` 等价且无浮点误差；8kHz → 1600 bytes = 800 样本 = 100ms
         * （官方 SDK 建议每帧约 100ms、1KB~16KB，1600 bytes 在区间内）。
         */
        fun frameBytes(sampleRate: Int): Int = sampleRate / 5

        /** 16bit mono PCM bytes → ShortArray（小端）。 */
        internal fun toShortArray(pcm: ByteArray): ShortArray {
            require(pcm.size % 2 == 0) { "PCM byte length must be even, got ${pcm.size}" }
            val samples = ShortArray(pcm.size / 2)
            for (i in samples.indices) {
                val lo = pcm[i * 2].toInt() and 0xFF
                val hi = pcm[i * 2 + 1].toInt() shl 8
                samples[i] = (hi or lo).toShort()
            }
            return samples
        }

        /** ShortArray → 16bit mono PCM bytes（小端）。 */
        internal fun toByteArray(samples: ShortArray): ByteArray {
            val pcm = ByteArray(samples.size * 2)
            for (i in samples.indices) {
                val v = samples[i].toInt()
                pcm[i * 2] = (v and 0xFF).toByte()
                pcm[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
            }
            return pcm
        }

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder().build()

        private fun defaultJson(): Json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * 流式识别结果回调（todo 17 接线管线）。
 *
 * 所有回调在 OkHttp WebSocket 线程触发，调用方需自行切线程（如 Dispatchers.Main）。
 */
interface DashScopeRealtimeCallback {
    /** 连接建立（WebSocket onOpen，run-task 已发送）。 */
    fun onOpen() {}

    /** 中间结果（sentence_end=false）。 */
    fun onPartial(text: String) {}

    /** 最终句子（sentence_end=true），begin/end 为毫秒时间戳（可能为 null）。 */
    fun onSentence(text: String, beginTimeMs: Int?, endTimeMs: Int?) {}

    /** 错误（连接失败 retryable=true；task-failed retryable=false）。 */
    fun onError(error: Throwable) {}

    /** 连接关闭。 */
    fun onClosed() {}
}

/** DashScope realtime 错误；[retryable]=true 表示可重试（连接失败），false 为任务级失败。 */
class DashScopeRealtimeException(
    message: String,
    cause: Throwable? = null,
    val retryable: Boolean = false,
) : Exception(message, cause)

/**
 * PCM 帧缓冲：累积字节流，按 [frameSize] 切出完整帧；不足一帧的余量可经 [remaining] 取出。
 * 纯 JVM 实现，可单测。
 */
class PcmFrameBuffer(private val frameSize: Int) {
    init {
        require(frameSize > 0) { "frameSize must be > 0, got $frameSize" }
    }

    private var buffer = ByteArray(0)

    /** 追加数据，返回切出的完整帧列表（余量留在缓冲内）。 */
    fun add(data: ByteArray): List<ByteArray> {
        if (data.isEmpty()) return emptyList()
        buffer = buffer + data
        val frames = mutableListOf<ByteArray>()
        var offset = 0
        while (buffer.size - offset >= frameSize) {
            frames.add(buffer.copyOfRange(offset, offset + frameSize))
            offset += frameSize
        }
        if (offset > 0) {
            buffer = buffer.copyOfRange(offset, buffer.size)
        }
        return frames
    }

    /** 当前缓冲的余量（不足一帧）。 */
    fun remaining(): ByteArray = buffer

    /** 清空缓冲。 */
    fun clear() {
        buffer = ByteArray(0)
    }
}

/**
 * 服务端消息分发：解析 JSON 信封（header.event）并按事件类型回调。
 * 独立成类便于纯 JVM 单测（喂 JSON 字符串断言回调序列）。
 */
internal class DashScopeRealtimeMessageHandler(
    private val json: Json,
    private val callback: DashScopeRealtimeCallback,
) {
    fun handle(text: String, onTaskStarted: () -> Unit = {}) {
        val message = try {
            json.decodeFromString<DashScopeRealtimeServerMessage>(text)
        } catch (e: Exception) {
            callback.onError(
                DashScopeRealtimeException("DashScope realtime 消息解析失败: ${e.message}", cause = e),
            )
            return
        }
        when (message.header.event) {
            "task-started" -> onTaskStarted()
            "result-generated" -> handleResultGenerated(message)
            "task-finished" -> callback.onClosed()
            "task-failed" -> {
                callback.onError(
                    DashScopeRealtimeException(
                        message.header.errorMessage ?: "task-failed",
                        retryable = false,
                    ),
                )
            }
            else -> Unit // 未知事件忽略（向前兼容）
        }
    }

    private fun handleResultGenerated(message: DashScopeRealtimeServerMessage) {
        val payload = try {
            json.decodeFromString<DashScopeRealtimeResultPayload>(message.payload.toString())
        } catch (e: Exception) {
            callback.onError(
                DashScopeRealtimeException("DashScope realtime 结果解析失败: ${e.message}", cause = e),
            )
            return
        }
        val sentence = payload.output?.sentence ?: return
        if (sentence.heartbeat) return // 心跳包跳过
        if (sentence.sentenceEnd) {
            callback.onSentence(sentence.text, sentence.beginTimeMs, sentence.endTimeMs)
        } else {
            callback.onPartial(sentence.text)
        }
    }
}