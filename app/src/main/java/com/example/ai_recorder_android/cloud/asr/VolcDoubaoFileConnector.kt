package com.example.ai_recorder_android.cloud.asr

import com.example.ai_recorder_android.BuildConfig
import com.example.ai_recorder_android.cloud.asr.dto.VolcAudio
import com.example.ai_recorder_android.cloud.asr.dto.VolcQueryResponse
import com.example.ai_recorder_android.cloud.asr.dto.VolcSubmitRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

// ---- 协议常量（对齐 PC volc_doubao_file.py）----

const val RESOURCE_ID_DOUBAO_2 = "volc.seedasr.auc"
const val VOLC_SUBMIT_URL = "https://openspeech.bytedance.com/api/v3/auc/bigmodel/submit"
const val VOLC_QUERY_URL = "https://openspeech.bytedance.com/api/v3/auc/bigmodel/query"

/** X-Api-Status-Code 状态码。 */
const val VOLC_STATUS_SUCCESS = 20000000
const val VOLC_STATUS_PROCESSING = 20000001
const val VOLC_STATUS_QUEUED = 20000002

/** 轮询参数：3s 间隔、30min 超时（Metis P6.7）。 */
const val VOLC_POLL_INTERVAL_MS = 3_000L
const val VOLC_POLL_TIMEOUT_MS = 30 * 60 * 1000L

/** 连接器上限 512MB（对齐 PC SPECIFICATIONS.max_file_size_bytes）。 */
const val VOLC_MAX_FILE_SIZE_BYTES = 512L * 1024 * 1024

data class VolcConfig(
    val appId: String,
    val accessToken: String,
    val resourceId: String = RESOURCE_ID_DOUBAO_2,
    val submitUrl: String = VOLC_SUBMIT_URL,
    val queryUrl: String = VOLC_QUERY_URL,
)

/** 转写结果（对齐 PC TranscriptionResponse 的 text/segments/duration）。 */
data class VolcTranscriptionResult(
    val text: String,
    val segments: List<VolcSegment>?,
    val durationMs: Long?,
)

data class VolcSegment(
    val text: String,
    val startMs: Long?,
    val endMs: Long?,
)

/** 单次 query 的结果分类（轮询状态机输入）。 */
internal sealed interface QueryOutcome {
    data class Success(val response: VolcQueryResponse) : QueryOutcome
    data class Processing(val statusCode: Int, val message: String) : QueryOutcome
    data class Failed(val statusCode: Int, val message: String, val body: String) : QueryOutcome
}

/**
 * 轮询状态机（纯逻辑，clock/delay 可注入单测）：
 * Success → 返回；Processing/Queued → delay 后继续；Failed → 抛 [VolcApiException]；
 * 超过 deadline → 抛 [VolcTimeoutException]（任务取消）。
 */
internal suspend fun pollUntilDone(
    query: suspend () -> QueryOutcome,
    delayFn: suspend (Long) -> Unit,
    clock: () -> Long,
    pollIntervalMs: Long,
    timeoutMs: Long,
): VolcQueryResponse {
    val deadline = clock() + timeoutMs
    while (clock() < deadline) {
        when (val outcome = query()) {
            is QueryOutcome.Success -> return outcome.response
            is QueryOutcome.Failed ->
                throw VolcApiException(outcome.statusCode, outcome.message, outcome.body)
            is QueryOutcome.Processing -> delayFn(pollIntervalMs)
        }
    }
    throw VolcTimeoutException(timeoutMs)
}

/** 上传前预检：>512MB 拒绝（连接器上限）。 */
internal fun checkFileSize(sizeBytes: Long, maxBytes: Long = VOLC_MAX_FILE_SIZE_BYTES) {
    if (sizeBytes > maxBytes) throw VolcFileTooLargeException(sizeBytes, maxBytes)
}

/**
 * 火山引擎 豆包录音文件识别 2.0 连接器（pro_lite 档）。
 * submit + query 两阶段：SFTP 上传音频 → 提交任务 → 轮询结果。
 *
 * 鉴权头（对齐 PC `volc_doubao_file.py`，THE reference）：
 * X-Api-App-Key=APP_ID、X-Api-Access-Key=Access Token、X-Api-Resource-Id=volc.seedasr.auc、
 * X-Api-Request-Id=task_id（submit 与 query 一致）、X-Api-Sequence=-1（仅 submit）。
 */
class VolcDoubaoFileConnector(
    private val config: VolcConfig,
    private val uploader: SftpUploader,
    private val client: OkHttpClient = defaultClient(),
    // explicitNulls=false：audio.language=null 时不发送 "language":null（对齐 PC 省略 None 字段）
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false },
    private val delayFn: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    /** 转写本地音频文件（>512MB 拒绝；超时抛 [VolcTimeoutException] 由调用方取消任务）。 */
    suspend fun transcribe(localFile: File, language: String? = null): VolcTranscriptionResult {
        validateConfig()
        checkFileSize(localFile.length())

        // 1. SFTP 上传到公网服务器——火山服务端需 HTTP URL 下载音频
        val audioUrl = uploader.upload(localFile)

        // 2. submit（task_id 在 submit 与 query 间保持一致）
        val taskId = UUID.randomUUID().toString().replace("-", "")
        val submitBody = VolcSubmitRequest(audio = VolcAudio(url = audioUrl, language = language))
        submit(taskId, submitBody)

        // 3. 轮询 query（3s 间隔、30min 超时）
        val response = pollUntilDone(
            query = { query(taskId) },
            delayFn = delayFn,
            clock = clock,
            pollIntervalMs = VOLC_POLL_INTERVAL_MS,
            timeoutMs = VOLC_POLL_TIMEOUT_MS,
        )
        return parseResult(response)
    }

    private fun validateConfig() {
        if (config.appId.isBlank()) {
            throw VolcConfigException("VOLC_APP_ID 未配置（pro_lite 档需要火山引擎凭证）")
        }
        if (config.accessToken.isBlank()) {
            throw VolcConfigException("VOLC_ACCESS_TOKEN 未配置（pro_lite 档需要火山引擎凭证）")
        }
    }

    private fun headers(taskId: String, withSequence: Boolean): Headers {
        val builder = Headers.Builder()
            .add("X-Api-App-Key", config.appId)
            .add("X-Api-Access-Key", config.accessToken)
            .add("X-Api-Resource-Id", config.resourceId)
            .add("X-Api-Request-Id", taskId)
            .add("Content-Type", "application/json")
        if (withSequence) builder.add("X-Api-Sequence", "-1")
        return builder.build()
    }

    private suspend fun submit(taskId: String, body: VolcSubmitRequest) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(config.submitUrl)
            .headers(headers(taskId, withSequence = true))
            .post(json.encodeToString(body).toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).execute().use { resp ->
            val statusCode = resp.header("X-Api-Status-Code")?.toIntOrNull() ?: 0
            val statusMsg = resp.header("X-Api-Message") ?: ""
            if (statusCode != VOLC_STATUS_SUCCESS) {
                throw VolcApiException(statusCode, statusMsg, resp.body?.string()?.take(300) ?: "")
            }
        }
    }

    private suspend fun query(taskId: String): QueryOutcome = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(config.queryUrl)
            .headers(headers(taskId, withSequence = false))
            .post("{}".toRequestBody(JSON_MEDIA_TYPE))
            .build()
        client.newCall(request).execute().use { resp ->
            val statusCode = resp.header("X-Api-Status-Code")?.toIntOrNull() ?: 0
            val statusMsg = resp.header("X-Api-Message") ?: ""
            val bodyText = resp.body?.string() ?: ""
            when {
                statusCode == VOLC_STATUS_SUCCESS -> {
                    val parsed = try {
                        json.decodeFromString<VolcQueryResponse>(bodyText)
                    } catch (e: Exception) {
                        throw VolcApiException(statusCode, "query 返回非 JSON", bodyText.take(300))
                    }
                    QueryOutcome.Success(parsed)
                }
                statusCode == VOLC_STATUS_PROCESSING || statusCode == VOLC_STATUS_QUEUED ->
                    QueryOutcome.Processing(statusCode, statusMsg)
                else -> QueryOutcome.Failed(statusCode, statusMsg, bodyText.take(300))
            }
        }
    }

    /** 解析 query 响应 → 文本 + 分段（空白段跳过，对齐 PC `_parse_result`）。 */
    internal fun parseResult(response: VolcQueryResponse): VolcTranscriptionResult {
        val result = response.result
        val fullText = result?.text ?: ""
        val segments = result?.utterances
            ?.mapNotNull { utt ->
                val text = utt.text?.trim().orEmpty()
                if (text.isEmpty()) null else VolcSegment(text, utt.start_time, utt.end_time)
            }
            ?.ifEmpty { null }
        return VolcTranscriptionResult(
            text = fullText,
            segments = segments,
            durationMs = response.audio_info?.duration,
        )
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

        /** 生产入口：从 BuildConfig 读取凭证（secrets.properties 注入）。 */
        fun fromBuildConfig(): VolcDoubaoFileConnector = VolcDoubaoFileConnector(
            config = VolcConfig(
                appId = BuildConfig.VOLC_APP_ID,
                accessToken = BuildConfig.VOLC_ACCESS_TOKEN,
            ),
            uploader = SftpUploader.fromBuildConfig(),
        )
    }
}

// ---- 用户可见错误（todo 16/17 捕获后展示）----

/** 火山 API 返回错误码（submit/query 阶段）。 */
class VolcApiException(
    val statusCode: Int,
    val apiMessage: String,
    val body: String,
) : Exception("火山引擎 API 错误 (status=$statusCode, msg=$apiMessage, body=$body)")

/** 轮询超时（30min），任务应取消。 */
class VolcTimeoutException(val timeoutMs: Long) :
    Exception("火山引擎识别超时（超过 ${timeoutMs / 60_000} 分钟），任务已取消")

/** 文件超过连接器上限 512MB。 */
class VolcFileTooLargeException(val sizeBytes: Long, val maxBytes: Long) :
    Exception("音频文件过大（${sizeBytes / 1024 / 1024}MB > ${maxBytes / 1024 / 1024}MB 上限）")

/** 凭证缺失等配置错误。 */
class VolcConfigException(message: String) : Exception(message)