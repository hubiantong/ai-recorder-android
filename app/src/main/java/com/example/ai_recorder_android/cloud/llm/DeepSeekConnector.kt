package com.example.ai_recorder_android.cloud.llm

import com.example.ai_recorder_android.BuildConfig
import com.example.ai_recorder_android.cloud.llm.dto.DeepSeekChatRequest
import com.example.ai_recorder_android.cloud.llm.dto.DeepSeekChatResponse
import com.example.ai_recorder_android.cloud.llm.dto.DeepSeekMessage
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
import java.util.concurrent.TimeUnit

/**
 * 文本 LLM 连接器：主用智谱 GLM-4.7-Flash，备用 DeepSeek。
 * 主用失败（网络/非 2xx/业务错误）立即切换备用模型。
 */
class DeepSeekConnector(
    private val apiKey: String = BuildConfig.DEEPSEEK_API_KEY,
    private val client: OkHttpClient = defaultClient(),
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val model: String = DEFAULT_MODEL,
    private val maxTokens: Int = DEFAULT_MAX_TOKENS,
) {

    val hasApiKey: Boolean get() = apiKey.isNotBlank()

    /**
     * 生成图文融合笔记。主备均失败 → 规则模板笔记（不抛异常）。
     * 主用 GLM key 来自 BuildConfig.ZHIPU_API_KEY（secrets.properties），缺失时跳过主用。
     */
    suspend fun generateFusionNote(request: FusionNoteRequest): String {
        return try {
            val content = chat(system = SYSTEM_PROMPT, user = buildPrompt(request), maxTokens = maxTokens, temperature = null)
            content.ifBlank { buildTemplateNote(request) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            buildTemplateNote(request)
        }
    }

    /**
     * 生成 ≤12 字会话标题。文本过短 / 主备均失败 → 返回空串（调用方回退默认标题如日期）。
     */
    suspend fun generateTitle(transcript: String): String {
        if (transcript.trim().length < MIN_TITLE_INPUT_CHARS) return ""
        return try {
            val content = chat(system = TITLE_SYSTEM_PROMPT, user = transcript.take(TITLE_INPUT_LIMIT), maxTokens = TITLE_MAX_TOKENS, temperature = TITLE_TEMPERATURE)
            cleanTitle(content)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ""
        }
    }

    /** 融合笔记用户 prompt（对齐 PC _build_prompt）。 */
    internal fun buildPrompt(request: FusionNoteRequest): String {
        val parts = mutableListOf<String>()
        parts.add("# 录音标题：${request.title.orEmpty()}")
        request.durationSeconds?.let { parts.add("# 录音时长：${"%.1f".format(it)} 秒") }

        parts.add("\n# 录音转写文本（带时间戳）：")
        if (request.chunks.isNotEmpty()) {
            for (c in request.chunks) {
                val ts = if (c.startTime != null && c.endTime != null) {
                    "[${"%.1f".format(c.startTime)}-${"%.1f".format(c.endTime)}s] "
                } else {
                    ""
                }
                val sp = c.speaker?.let { "[$it] " }.orEmpty()
                parts.add("$ts$sp${c.content}")
            }
        } else {
            parts.add(request.transcription.ifBlank { "（无转写文本）" })
        }

        if (request.attachments.isNotEmpty()) {
            parts.add("\n# 拍摄的照片及 VLM 描述：")
            for (a in request.attachments) {
                parts.add("- [${"%.1f".format(a.timestamp)}s] ${a.description.ifBlank { "（无描述）" }}")
            }
        } else {
            parts.add("\n# 拍摄的照片：无")
        }
        return parts.joinToString("\n")
    }

    /**
     * 规则模板笔记（无 LLM，完全本地，对齐 PC _build_template_note）：
     * `# 标题 / ## 主题摘要（前 200 字）/ ## 关键要点 / ## 时间轴要点（照片+转写按时间合并）`。
     */
    internal fun buildTemplateNote(request: FusionNoteRequest): String {
        val parts = mutableListOf<String>()
        parts.add("# ${request.title?.ifBlank { null } ?: "录音笔记"}")
        request.durationSeconds?.let { parts.add("\n> 录音时长：${"%.1f".format(it)} 秒\n") }

        val fullText = if (request.chunks.isNotEmpty()) {
            request.chunks.joinToString("") { it.content }
        } else {
            request.transcription.orEmpty()
        }

        parts.add("## 主题摘要\n")
        val summary = fullText.take(SUMMARY_CHARS)
        parts.add(summary + if (fullText.length > SUMMARY_CHARS) "..." else "")

        parts.add("\n## 关键要点\n")
        if (request.chunks.isNotEmpty()) {
            for (c in request.chunks) {
                val ts = c.startTime?.let { "[${"%.0f".format(it)}s] " }.orEmpty()
                parts.add("- $ts${c.content}")
            }
        } else if (request.transcription.isNotBlank()) {
            for (line in request.transcription.split('\n')) {
                if (line.isNotBlank()) parts.add("- ${line.trim()}")
            }
        }

        parts.add("\n## 时间轴要点\n")
        val timeline = mutableListOf<Triple<Double, String, String>>()
        for (c in request.chunks) timeline.add(Triple(c.startTime ?: 0.0, "text", c.content))
        for (a in request.attachments) timeline.add(Triple(a.timestamp, "photo", a.description))
        timeline.sortBy { it.first }
        for ((ts, kind, content) in timeline) {
            if (kind == "photo") parts.add("- [${"%.0f".format(ts)}s] [照片] $content")
            else parts.add("- [${"%.0f".format(ts)}s] $content")
        }
        return parts.joinToString("\n")
    }

    internal fun parseChatResponse(jsonText: String): String {
        val data = json.decodeFromString<DeepSeekChatResponse>(jsonText)
        data.error?.let { err ->
            if (!err.message.isNullOrBlank()) {
                throw DeepSeekException("DeepSeek 返回错误: ${err.message}")
            }
        }
        return data.choices?.firstOrNull()?.message?.content.orEmpty()
    }

    /**
     * 统一 LLM 调用：主用 GLM，失败立即切换备用 DeepSeek；两者均失败抛异常。
     */
    private suspend fun chat(
        system: String,
        user: String,
        maxTokens: Int,
        temperature: Double?,
    ): String = withContext(Dispatchers.IO) {
        val primaryError: Exception? = if (PRIMARY_API_KEY.isBlank()) {
            // ZHIPU_API_KEY 未配置（secrets.properties 缺失/留空）：跳过主用，直接走备用。
            IllegalStateException("ZHIPU_API_KEY 未配置（app/secrets.properties）")
        } else {
            try {
                return@withContext callChat(
                    baseUrl = PRIMARY_BASE_URL, apiKey = PRIMARY_API_KEY,
                    model = PRIMARY_MODEL, system = system, user = user,
                    maxTokens = maxTokens, temperature = temperature,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }
        }

        // ---- 备用：DeepSeek（key 为空时短路，不发必失败的网络请求）----
        if (!hasApiKey) {
            throw DeepSeekException(
                "LLM 调用失败（主用 GLM: ${primaryError?.message}；备用 DeepSeek: 未配置 API key）",
            )
        }
        try {
            callChat(baseUrl, apiKey, model, system, user, maxTokens, temperature)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw DeepSeekException(
                "LLM 调用失败（主用 GLM: ${primaryError?.message}；备用 DeepSeek: ${e.message}）",
                e,
            )
        }
    }

    /** 单次 OpenAI 兼容 chat 调用。 */
    private fun callChat(
        baseUrl: String, apiKey: String, model: String,
        system: String, user: String, maxTokens: Int, temperature: Double?,
    ): String {
        val request = DeepSeekChatRequest(
            model = model,
            messages = listOf(
                DeepSeekMessage(role = "system", content = system),
                DeepSeekMessage(role = "user", content = user),
            ),
            maxTokens = maxTokens,
            temperature = temperature,
        )
        val httpRequest = Request.Builder()
            .url("$baseUrl/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(json.encodeToString(request).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return client.newCall(httpRequest).execute().use { resp ->
            val respBody = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw DeepSeekException("LLM 调用失败 [$model ${resp.code}]: ${respBody.take(500)}")
            }
            parseChatResponse(respBody)
        }
    }

    companion object {
        // ---- 主用：智谱 BigModel GLM-4.7-Flash ----
        const val PRIMARY_BASE_URL = "https://open.bigmodel.cn/api/paas/v4"

        /** 注入自 BuildConfig.ZHIPU_API_KEY（secrets.properties）；缺失为 ""。 */
        val PRIMARY_API_KEY = BuildConfig.ZHIPU_API_KEY
        const val PRIMARY_MODEL = "glm-4.7-flash"

        // ---- 备用：DeepSeek ----
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_MODEL = "deepseek-v4-flash"
        const val DEFAULT_MAX_TOKENS = 2048
        const val PROVIDER_NAME = "deepseek"

        /** 自动标题参数（对齐 PC generate_title）。 */
        const val TITLE_MAX_TOKENS = 64
        const val TITLE_TEMPERATURE = 0.3
        const val TITLE_INPUT_LIMIT = 1500
        const val TITLE_MAX_CHARS = 12
        const val MIN_TITLE_INPUT_CHARS = 20

        /** 规则模板主题摘要取转写前 200 字（对齐 PC _build_template_note）。 */
        const val SUMMARY_CHARS = 200

        /** 融合笔记系统提示词（对齐 PC note_fuser.SYSTEM_PROMPT）。 */
        val SYSTEM_PROMPT = """
            你是一个会议/课堂笔记助手。根据录音转写文本和照片描述，生成结构化的图文融合笔记。

            输出要求：
            1. 使用 Markdown 格式
            2. 结构包含：## 主题摘要、## 关键要点、## 时间轴要点
            3. 在相关位置嵌入照片引用，格式：![照片描述](attachment:时间戳秒数)
            4. 结合照片描述与转写上下文，突出重要时刻
            5. 语言简洁，重点突出，避免照抄转写原文
        """.trimIndent()

        /** 自动标题系统提示词（对齐 PC note_fuser.TITLE_SYSTEM_PROMPT）。 */
        val TITLE_SYSTEM_PROMPT = """
            你是会话标题生成器。根据录音转写文本,用中文输出一个 ≤12 字的简洁标题。
            要求:
            - 抓住核心主题/讨论内容
            - 不用句号、引号
            - 不用"关于""主题是"等冗余前缀
            - 只输出标题文本本身,不要解释
        """.trimIndent()

        /**
         * 标题清洗（对齐 PC generate_title 清理正则，顺序敏感）：
         * 1. 去中间换行 / Tab 等空白 → 2. 去两侧中英文句末标点 → 3. 去两侧引号 → 4. 截断 ≤12 字。
         *
         * 标点集合含全角 `。！？，；：、` 与半角 `.!,?;:`（中文 IME 输入产生全角标点，
         * 半角标点来自英文输入；V4.5 中文 IME 用例要求两者均剥离）。
         */
        internal fun cleanTitle(raw: String): String {
            var title = raw.trim()
            title = title.replace(Regex("\\s+"), "")
            title = title.replace(Regex("^[。.!?,;：:、！？，；]+"), "")
            title = title.replace(Regex("[。.!?,;：:、！？，；]+$"), "")
            title = title.replace(Regex("^[\"'「」『』《》]+"), "")
            title = title.replace(Regex("[\"'「」『』《》]+$"), "")
            return title.take(TITLE_MAX_CHARS)
        }

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}

/** 连接器调用失败（网络 / 非 2xx / 业务错误码）。 */
class DeepSeekException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 融合笔记输入（对齐 PC _gather_materials 的语义，由 todo 18 worker 组装）。 */
data class FusionNoteRequest(
    val title: String? = null,
    val durationSeconds: Double? = null,
    val transcription: String = "",
    val chunks: List<FusionChunk> = emptyList(),
    val attachments: List<FusionAttachment> = emptyList(),
)

data class FusionChunk(
    val startTime: Double? = null,
    val endTime: Double? = null,
    val speaker: String? = null,
    val content: String,
)

data class FusionAttachment(
    val timestamp: Double,
    val description: String,
)