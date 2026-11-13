package com.example.ai_recorder_android.cloud.vlm

import com.example.ai_recorder_android.BuildConfig
import com.example.ai_recorder_android.cloud.vlm.dto.QwenVlChatRequest
import com.example.ai_recorder_android.cloud.vlm.dto.QwenVlChatResponse
import com.example.ai_recorder_android.cloud.vlm.dto.QwenVlContentPart
import com.example.ai_recorder_android.cloud.vlm.dto.QwenVlImageUrl
import com.example.ai_recorder_android.cloud.vlm.dto.QwenVlMessage
import com.example.ai_recorder_android.cloud.vlm.dto.QwenVlUsage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * VLM 云端连接器：主用智谱 GLM-4.6V-Flash，第二备用硅基流动 DeepSeek-OCR，
 * 末级备用 Qwen-VL（DashScope OpenAI 兼容模式）。三者均为 OpenAI chat/completions 兼容协议。
 * 前级失败（网络/非 2xx/业务错误）立即切换下一级。
 */
class QwenVlConnector(
    private val apiKey: String = BuildConfig.DASHSCOPE_API_KEY,
    private val client: OkHttpClient = defaultClient(),
    private val baseUrl: String = COMPATIBLE_BASE_URL,
    private val model: String = DEFAULT_MODEL,
    private val maxTokens: Int = DEFAULT_MAX_TOKENS,
) {

    // 主用 GLM key 由 BuildConfig.ZHIPU_API_KEY 注入（secrets.properties，缺失为 "" 时跳过主用）；
    // 备用 key 为空时备用调用会抛 401，由上层回退处理。

    /**
     * 生成图片描述。imagePath 与 imageBase64 至少提供一个。
     * @param imageMimeType 仅 imageBase64 路径使用（默认 image/jpeg）；imagePath 按扩展名推断。
     */
    suspend fun describeImage(
        imagePath: File? = null,
        imageBase64: String? = null,
        imageMimeType: String = "image/jpeg",
        prompt: String = DEFAULT_PROMPT,
        contextText: String? = null,
        model: String? = null,
        maxTokens: Int? = null,
    ): QwenVlResult = withContext(Dispatchers.IO) {
        val imageUrl = when {
            imagePath != null -> {
                if (!imagePath.isFile) throw QwenVlException("图片不存在: ${imagePath.path}")
                val mime = guessMimeType(imagePath.name) ?: imageMimeType
                val b64 = Base64.getEncoder().encodeToString(imagePath.readBytes())
                "data:$mime;base64,$b64"
            }
            imageBase64 != null -> "data:$imageMimeType;base64,$imageBase64"
            else -> throw QwenVlException("未提供图片（imagePath 或 imageBase64 至少一个）")
        }

        // ---- 主用：GLM ----
        val effectivePrimaryModel = model ?: PRIMARY_MODEL
        val primaryError: Exception? = if (PRIMARY_API_KEY.isBlank()) {
            // ZHIPU_API_KEY 未配置（secrets.properties 缺失/留空）：跳过主用，直接走备用。
            IllegalStateException("ZHIPU_API_KEY 未配置（app/secrets.properties）")
        } else {
            try {
                return@withContext callChat(
                    baseUrl = PRIMARY_BASE_URL, apiKey = PRIMARY_API_KEY,
                    model = effectivePrimaryModel,
                    payload = buildRequestPayload(
                        effectivePrimaryModel,
                        maxTokens ?: this@QwenVlConnector.maxTokens,
                        prompt, contextText, imageUrl,
                    ),
                    provider = PRIMARY_PROVIDER_NAME,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }
        }

        // ---- 备用 1：硅基流动 DeepSeek-OCR ----
        val secondaryError: Exception? = if (SILICONFLOW_API_KEY.isBlank()) {
            IllegalStateException("SILICONFLOW_API_KEY 未配置（app/secrets.properties）")
        } else {
            try {
                return@withContext describeViaSiliconFlow(
                    imageUrl,
                    prompt,
                    contextText,
                    maxTokens ?: this@QwenVlConnector.maxTokens,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }
        }

        // ---- 备用 2：Qwen-VL ----
        // 不可复用前级 payload：会把其他模型名发给 DashScope（model not found）
        try {
            val backupModel = this@QwenVlConnector.model
            callChat(
                baseUrl, apiKey, backupModel,
                payload = buildRequestPayload(
                    backupModel,
                    maxTokens ?: this@QwenVlConnector.maxTokens,
                    prompt, contextText, imageUrl,
                ),
                provider = PROVIDER_NAME,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw QwenVlException(
                "VLM 调用失败（主用 GLM: ${primaryError?.message}；" +
                    "备用 DeepSeek-OCR: ${secondaryError?.message}；备用 Qwen-VL: ${e.message}）",
                e,
            )
        }
    }

    /** 备用 1：硅基流动 DeepSeek-OCR（internal 供 E2E 单独验证该档）。 */
    internal suspend fun describeViaSiliconFlow(
        imageDataUrl: String,
        prompt: String,
        contextText: String?,
        maxTokens: Int,
    ): QwenVlResult = callChat(
        baseUrl = SILICONFLOW_BASE_URL,
        apiKey = SILICONFLOW_API_KEY,
        model = SILICONFLOW_OCR_MODEL,
        payload = buildRequestPayload(SILICONFLOW_OCR_MODEL, maxTokens, prompt, contextText, imageDataUrl),
        provider = SILICONFLOW_PROVIDER_NAME,
    )

    /** 构造请求 body JSON。 */
    private fun buildRequestPayload(
        effectiveModel: String,
        mt: Int,
        prompt: String,
        contextText: String?,
        imageUrl: String,
    ): String {
        val request = QwenVlChatRequest(
            model = effectiveModel,
            messages = listOf(
                QwenVlMessage(
                    role = "user",
                    content = listOf(
                        QwenVlContentPart(type = "image_url", imageUrl = QwenVlImageUrl(url = imageUrl)),
                        QwenVlContentPart(type = "text", text = buildPrompt(prompt, contextText)),
                    ),
                )
            ),
            maxTokens = mt,
        )
        return json.encodeToString(request)
    }

    /** 统一 OpenAI 兼容 chat 调用。 */
    private fun callChat(
        baseUrl: String, apiKey: String, model: String,
        payload: String, provider: String,
    ): QwenVlResult {
        val httpRequest = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(httpRequest).execute().use { resp ->
            val respBody = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw QwenVlException("VLM 调用失败 [$model ${resp.code}]: ${respBody.take(500)}")
            }
            return parseResponse(respBody, model, provider)
        }
    }

    /** 结合转写上下文构造提示词（对齐 PC qwen_vl.py）。 */
    internal fun buildPrompt(prompt: String, contextText: String?): String {
        val base = prompt.trim().ifEmpty { DEFAULT_PROMPT }
        val ctx = contextText?.trim().orEmpty()
        return if (ctx.isEmpty()) {
            base
        } else {
            "$base\n\n以下是录音中此刻附近的转写文本，请结合它理解图片语境：\n$ctx"
        }
    }

    internal fun guessMimeType(fileName: String): String? = when (fileName.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        else -> null
    }

    internal fun parseResponse(jsonText: String, effectiveModel: String, provider: String = PROVIDER_NAME): QwenVlResult {
        val data = json.decodeFromString<QwenVlChatResponse>(jsonText)
        data.error?.let { err ->
            if (!err.message.isNullOrBlank()) {
                throw QwenVlException("VLM 返回错误: ${err.message}")
            }
        }
        val description = data.choices?.firstOrNull()?.message?.content.orEmpty()
        return QwenVlResult(
            description = description,
            usage = data.usage,
            provider = provider,
            model = effectiveModel,
            rawResponse = jsonText,
        )
    }

    companion object {
        // ---- 主用：智谱 BigModel GLM-4.6V-Flash ----
        const val PRIMARY_BASE_URL = "https://open.bigmodel.cn/api/paas/v4"

        /** 注入自 BuildConfig.ZHIPU_API_KEY（secrets.properties）；缺失为 ""。 */
        val PRIMARY_API_KEY = BuildConfig.ZHIPU_API_KEY
        const val PRIMARY_MODEL = "glm-4.6v-flash"
        const val PRIMARY_PROVIDER_NAME = "glm_vl"

        // ---- 备用 1：硅基流动 DeepSeek-OCR（OpenAI 兼容 chat/completions）----
        const val SILICONFLOW_BASE_URL = "https://api.siliconflow.cn/v1"

        /** 注入自 BuildConfig.SILICONFLOW_API_KEY（secrets.properties）；缺失为 ""。 */
        val SILICONFLOW_API_KEY = BuildConfig.SILICONFLOW_API_KEY
        const val SILICONFLOW_OCR_MODEL = "deepseek-ai/DeepSeek-OCR"
        const val SILICONFLOW_PROVIDER_NAME = "siliconflow_ocr"

        // ---- 备用 2：Qwen-VL（DashScope 兼容模式）----
        const val COMPATIBLE_BASE_URL = "https://dashscope.aliyuncs.com/compatible-mode/v1"
        const val DEFAULT_MODEL = "qwen-vl-max"
        const val DEFAULT_MAX_TOKENS = 500
        const val DEFAULT_PROMPT = "请描述这张图片的内容。"
        const val PROVIDER_NAME = "qwen_vl"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}

/** 连接器调用失败（网络 / 非 2xx / 业务错误码）。 */
class QwenVlException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 归一化 VLM 结果（对齐 PC VLMResponse 语义）。 */
data class QwenVlResult(
    val description: String,
    val usage: QwenVlUsage?,
    val provider: String,
    val model: String,
    val rawResponse: String,
)