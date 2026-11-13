package com.example.ai_recorder_android.cloud.asr

import com.example.ai_recorder_android.BuildConfig
import com.example.ai_recorder_android.cloud.asr.dto.DashScopeFlashContent
import com.example.ai_recorder_android.cloud.asr.dto.DashScopeFlashInput
import com.example.ai_recorder_android.cloud.asr.dto.DashScopeFlashInputAudio
import com.example.ai_recorder_android.cloud.asr.dto.DashScopeFlashMessage
import com.example.ai_recorder_android.cloud.asr.dto.DashScopeFlashParameters
import com.example.ai_recorder_android.cloud.asr.dto.DashScopeFlashRequest
import com.example.ai_recorder_android.cloud.asr.dto.DashScopeFlashResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * 阿里云百炼 fun-asr-flash-2026-06-15 录音文件识别连接器（pro_pro 档）。
 * 对齐 PC `dashscope_funasr.py`：
 * - 同步多模态生成端点（非 submit+poll），一次请求即返回
 * - 音频以 base64 data URI 内联（无需上传 OSS / 公网 URL）
 * - 单音频 ≤ 5 分钟（_MAX_DURATION_SECONDS=300，上传前 WAV 头预检）
 * - 上下文增强：messages 前置 system 文本提升专有名词准确率
 *
 * 内存注意（Metis P6.6）：5 分钟 16k mono 16bit WAV ≈ 9.6MB，base64 ≈ 12.8MB。
 * base64 编码用流式（Base64.Encoder.wrap + 分块读文件），避免整文件与 base64 同时驻留内存。
 *
 * Must NOT：模型名之外的协议假设不做（Metis A3.5：fun-asr-flash 模型名时效性 F3 真机验证）。
 */
class DashScopeFlashAsrConnector(
    private val apiKey: String = BuildConfig.DASHSCOPE_API_KEY,
    private val client: OkHttpClient = defaultClient(),
    private val genUrl: String = GEN_URL,
    private val model: String = DEFAULT_MODEL,
    private val sampleRate: Int = DEFAULT_SAMPLE_RATE,
) {

    init {
        require(apiKey.isNotBlank()) { "DASHSCOPE_API_KEY 未配置，无法使用 pro_pro 档 fun-asr-flash" }
    }

    /**
     * 同步转写：预检时长 → 流式 base64 → POST generation 端点 → 解析响应。
     * 时长 > 300s 抛 [IllegalArgumentException]（上层分段或改用异步接口）。
     */
    suspend fun transcribe(
        file: File,
        prompt: String? = null,
        language: String? = null,
    ): DashScopeFlashResult = withContext(Dispatchers.IO) {
        val duration = WavDuration.parseSeconds(file)
        checkDuration(duration)
        val dataUri = buildDataUri(file)
        val body = buildRequestBody(dataUri, prompt, language)
        val httpRequest = Request.Builder()
            .url(genUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("X-DashScope-SSE", "disable")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(httpRequest).execute().use { resp ->
            val respBody = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw DashScopeFlashException(
                    "fun-asr-flash 调用失败 [${resp.code}]: ${respBody.take(500)}"
                )
            }
            parseResponse(respBody, duration, language)
        }
    }

    /** 5 分钟预检边界：> 300s 拒绝（纯 JVM 可测）。 */
    internal fun checkDuration(duration: Double) {
        if (duration > MAX_DURATION_SECONDS) {
            throw IllegalArgumentException(
                "fun-asr-flash 同步接口限制 ≤ ${MAX_DURATION_SECONDS}s，" +
                    "当前音频 ${"%.1f".format(duration)}s，请在上层分段或改用异步接口"
            )
        }
    }

    /**
     * 流式 base64（Metis P6.6）：分块读文件喂给 Base64 编码器，避免整文件入内存。
     * 峰值内存 ≈ base64 字符串（12.8MB）+ 64KB 块缓冲，而非 原始文件 + base64 双份。
     */
    internal fun buildDataUri(file: File): String {
        val bos = ByteArrayOutputStream()
        val encoder = Base64.getEncoder().wrap(bos)
        file.inputStream().use { input ->
            val buf = ByteArray(CHUNK_SIZE)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                encoder.write(buf, 0, n)
            }
        }
        encoder.close()
        return "data:audio/wav;base64," + String(bos.toByteArray(), Charsets.US_ASCII)
    }

    internal fun buildRequestBody(dataUri: String, prompt: String?, language: String?): String {
        val messages = mutableListOf<DashScopeFlashMessage>()
        val userPrompt = prompt?.trim().orEmpty()
        if (userPrompt.isNotEmpty()) {
            messages.add(
                DashScopeFlashMessage(
                    role = "system",
                    content = listOf(DashScopeFlashContent(type = "text", text = userPrompt)),
                )
            )
        }
        messages.add(
            DashScopeFlashMessage(
                role = "user",
                content = listOf(
                    DashScopeFlashContent(
                        type = "input_audio",
                        inputAudio = DashScopeFlashInputAudio(data = dataUri),
                    )
                ),
            )
        )
        val request = DashScopeFlashRequest(
            model = model,
            input = DashScopeFlashInput(messages = messages),
            parameters = DashScopeFlashParameters(
                format = "wav",
                sampleRate = sampleRate,
                languageHints = language?.let { listOf(it) },
            ),
        )
        return json.encodeToString(request)
    }

    /** 解析响应：优先嵌套层 output.output，回退外层 output（对齐 PC _parse_response）。 */
    internal fun parseResponse(jsonText: String, duration: Double, language: String?): DashScopeFlashResult {
        val data = json.decodeFromString<DashScopeFlashResponse>(jsonText)
        if (!data.message.isNullOrBlank()) {
            throw DashScopeFlashException(
                "fun-asr-flash 返回错误: code=${data.code ?: "-"}, message=${data.message}"
            )
        }
        val outer = data.output
        val inner = outer?.output
        val fullText = (inner?.text ?: outer?.text ?: "").trim()

        val sentence = inner?.sentence ?: outer?.sentence
        val segments = mutableListOf<DashScopeFlashSegment>()
        val sentText = sentence?.text?.trim().orEmpty().ifEmpty { fullText }
        if (sentText.isNotEmpty()) {
            val words = sentence?.words?.mapNotNull { w ->
                if (w.text.isEmpty()) null
                else DashScopeFlashWord(
                    text = w.text,
                    start = (w.beginTime ?: 0L) / 1000.0,
                    end = (w.endTime ?: 0L) / 1000.0,
                    punctuation = w.punctuation,
                )
            }
            segments.add(
                DashScopeFlashSegment(
                    text = sentText,
                    startTime = sentence?.beginTime?.let { it / 1000.0 },
                    endTime = sentence?.endTime?.let { it / 1000.0 },
                    words = words,
                )
            )
        }
        return DashScopeFlashResult(
            text = fullText,
            segments = segments.ifEmpty { null },
            duration = duration,
            language = language,
            provider = PROVIDER_NAME,
            model = model,
            rawResponse = jsonText,
        )
    }

    companion object {
        const val GEN_URL =
            "https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation"
        const val DEFAULT_MODEL = "fun-asr-flash-2026-06-15"
        const val DEFAULT_SAMPLE_RATE = 16000
        const val PROVIDER_NAME = "dashscope_funasr"

        /** fun-asr-flash 同步接口限制：单音频 ≤ 5 分钟。 */
        const val MAX_DURATION_SECONDS = 300

        /** HTTP 超时：5 分钟音频 + 网络余量（对齐 PC _HTTP_TIMEOUT=180s）。 */
        const val HTTP_TIMEOUT_SECONDS = 180L

        private const val CHUNK_SIZE = 64 * 1024
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        // encodeDefaults=true：format/sample_rate 需随请求发送（对齐 PC dashscope_funasr.py）
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(HTTP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }
}

/** 连接器调用失败（网络 / 非 2xx / 业务错误码）。 */
class DashScopeFlashException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 归一化转写结果（对齐 PC TranscriptionResponse 语义，时间戳已转秒）。 */
data class DashScopeFlashResult(
    val text: String,
    val segments: List<DashScopeFlashSegment>?,
    val duration: Double,
    val language: String?,
    val provider: String,
    val model: String,
    val rawResponse: String,
)

data class DashScopeFlashSegment(
    val text: String,
    val startTime: Double?,
    val endTime: Double?,
    val words: List<DashScopeFlashWord>?,
)

data class DashScopeFlashWord(
    val text: String,
    val start: Double,
    val end: Double,
    val punctuation: String,
)

/**
 * WAV 头时长解析（纯 JVM，可单测）。
 * 遍历 RIFF 块找 fmt（byteRate）与 data（size），duration = dataSize / byteRate。
 * 流式 WAV 的 data size 可能为 0 / 0xFFFFFFFF，回退用文件长度推算。
 */
object WavDuration {

    fun parseSeconds(file: File): Double {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 12) throw IllegalArgumentException("文件过短，不是有效 WAV")
            val riff = ByteArray(4)
            raf.readFully(riff)
            require(riff.contentEquals("RIFF".toByteArray(Charsets.US_ASCII))) { "不是 RIFF/WAV 文件" }
            raf.skipBytes(4) // RIFF chunk size
            val wave = ByteArray(4)
            raf.readFully(wave)
            require(wave.contentEquals("WAVE".toByteArray(Charsets.US_ASCII))) { "不是 WAVE 文件" }

            var byteRate = 0L
            var dataSize = -1L
            var dataOffset = -1L
            while (raf.filePointer < raf.length()) {
                val id = ByteArray(4)
                raf.readFully(id)
                val size = readLeUInt32(raf)
                when (String(id, Charsets.US_ASCII)) {
                    "fmt " -> {
                        // audioFormat(2) + channels(2) + sampleRate(4) + byteRate(4) = 12 字节
                        raf.skipBytes(2)
                        raf.skipBytes(2)
                        raf.skipBytes(4)
                        byteRate = readLeUInt32(raf)
                        skipChunk(raf, size, consumed = 12)
                    }
                    "data" -> {
                        dataOffset = raf.filePointer
                        dataSize = size
                        break
                    }
                    else -> skipChunk(raf, size, consumed = 0)
                }
            }
            if (byteRate <= 0) throw IllegalArgumentException("WAV fmt 块缺失或 byteRate 无效")
            if (dataOffset < 0) throw IllegalArgumentException("WAV 缺少 data 块")
            val effectiveDataSize = if (dataSize == 0L || dataSize == 0xFFFFFFFFL) {
                raf.length() - dataOffset
            } else {
                dataSize
            }
            return effectiveDataSize.toDouble() / byteRate.toDouble()
        }
    }

    /** 跳过 chunk 剩余字节；RIFF 块按 2 字节对齐，奇数 size 补 1 字节。 */
    private fun skipChunk(raf: RandomAccessFile, chunkSize: Long, consumed: Long) {
        val remaining = chunkSize - consumed
        if (remaining > 0) raf.skipBytes(remaining.toInt())
        if (chunkSize % 2 == 1L) raf.skipBytes(1)
    }

    private fun readLeUInt32(raf: RandomAccessFile): Long {
        val b = ByteArray(4)
        raf.readFully(b)
        return (b[0].toLong() and 0xFF) or
            ((b[1].toLong() and 0xFF) shl 8) or
            ((b[2].toLong() and 0xFF) shl 16) or
            ((b[3].toLong() and 0xFF) shl 24)
    }
}