package com.example.ai_recorder_android.cloud.asr

import com.example.ai_recorder_android.BuildConfig
import com.example.ai_recorder_android.cloud.asr.dto.OpenAiWhisperResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * free 档 ASR 连接器：硅基流动 SiliconFlow OpenAI 兼容端点。
 *
 * 对齐 PC `core/transcription/connectors/openai_whisper.py`：
 * - POST `https://api.siliconflow.cn/v1/audio/transcriptions`（multipart/form-data）
 * - model = `FunAudioLLM/SenseVoiceSmall`（tiers.py free 档）
 * - response_format = `verbose_json`（返回 segments 带 start/end，供照片时间轴对齐）
 * - language 可选（PC 仅在 request.language 非空时携带）
 * - Bearer 鉴权，key 来自 BuildConfig.SILICONFLOW_API_KEY（缺失为 ""）
 *
 * 上传前客户端预检：文件 ≤ 25MB（Metis B1.1：以连接器 `max_file_size_bytes=25MB` 为准，
 * tiers.py 描述中的 50MB 有误），超限抛 [FileTooLargeException]。
 *
 * 构造参数可注入（apiKey/client/json），便于纯 JVM 单测：
 * - apiKey 默认取 BuildConfig，测试传 "" 验证 [ApiKeyException]
 * - client 默认生产 OkHttpClient，测试注入 fake `Call.Factory` 验证请求构建与响应解析
 */
class SiliconFlowAsrConnector(
    private val apiKey: String = BuildConfig.SILICONFLOW_API_KEY,
    private val client: OkHttpClient = defaultClient(),
    private val json: Json = defaultJson(),
) {

    /**
     * 转写单个音频文件。
     *
     * @param file 待转写音频（WAV/MP3 等，≤25MB）
     * @param language 可选语言提示（如 "zh"），null 时由服务端自动检测
     * @throws ApiKeyException key 缺失/空白
     * @throws FileTooLargeException 文件超过 25MB 上限（上传前预检）
     * @throws AsrApiException HTTP 非 2xx 或响应解析失败
     */
    suspend fun transcribe(file: File, language: String? = null): OpenAiWhisperResponse {
        if (apiKey.isBlank()) throw ApiKeyException()
        precheck(file)

        val request = buildRequest(file, language)
        val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
        response.use { resp ->
            val body = resp.body?.string() ?: throw AsrApiException(resp.code, null)
            if (!resp.isSuccessful) throw AsrApiException(resp.code, body)
            return try {
                json.decodeFromString<OpenAiWhisperResponse>(body)
            } catch (e: SerializationException) {
                throw AsrApiException(resp.code, body, e)
            }
        }
    }

    /** 上传前预检：文件大小 ≤ 25MB，超限抛 [FileTooLargeException]。 */
    fun precheck(file: File) {
        val size = file.length()
        if (size > MAX_FILE_SIZE_BYTES) throw FileTooLargeException(size, MAX_FILE_SIZE_BYTES)
    }

    private fun buildRequest(file: File, language: String?): Request {
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", MODEL)
            .addFormDataPart("file", file.name, file.asRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("response_format", RESPONSE_FORMAT)
            .apply { if (language != null) addFormDataPart("language", language) }
            .build()

        return Request.Builder()
            .url(ENDPOINT)
            .header("Authorization", "Bearer $apiKey")
            .post(multipart)
            .build()
    }

    companion object {
        const val ENDPOINT = "https://api.siliconflow.cn/v1/audio/transcriptions"
        const val MODEL = "FunAudioLLM/SenseVoiceSmall"
        const val RESPONSE_FORMAT = "verbose_json"

        /** 上传前预检上限：25MB（Metis B1.1，以连接器为准，tiers.py 的 50MB 描述有误）。 */
        const val MAX_FILE_SIZE_BYTES = 25L * 1024 * 1024

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()

        fun defaultJson(): Json = Json { ignoreUnknownKeys = true }
    }
}