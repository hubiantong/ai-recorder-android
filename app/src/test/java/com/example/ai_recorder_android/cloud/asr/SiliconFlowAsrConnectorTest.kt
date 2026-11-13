package com.example.ai_recorder_android.cloud.asr

import com.example.ai_recorder_android.cloud.asr.dto.OpenAiWhisperResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicReference

/**
 * SiliconFlowAsrConnector 纯 JVM 单测（JUnit4，不依赖 Android 环境）。
 *
 * 覆盖：
 * - DTO 解析：夹具 JSON（含未知字段，验证 ignoreUnknownKeys）
 * - 25MB 预检边界：恰好 25MB 通过、25MB+1 拒绝（稀疏文件，秒建）
 * - 空 key → ApiKeyException（不发请求）
 * - 请求构建：fake Call.Factory 捕获 Request，断言 URL/方法/Bearer/multipart 各 part
 * - HTTP 非 2xx → AsrApiException
 *
 * 本机 Gradle worker 编码 bug 无法运行 testDebugUnitTest，由其他机器验证。
 */
class SiliconFlowAsrConnectorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val maxBytes = SiliconFlowAsrConnector.MAX_FILE_SIZE_BYTES

    // ---- DTO 解析（夹具 JSON） ----

    @Test
    fun dto_parsesVerboseJsonFixture() {
        val jsonText = readFixture("/fixtures/whisper_verbose_json_sample.json")
        val resp = SiliconFlowAsrConnector.defaultJson()
            .decodeFromString<OpenAiWhisperResponse>(jsonText)

        assertEquals("测试录音。", resp.text)
        assertEquals("zh", resp.language)
        assertEquals(3.0, resp.duration!!, 1e-9)
        assertEquals(2, resp.segments.size)
        assertEquals("测试", resp.segments[0].text)
        assertEquals(0.0, resp.segments[0].start!!, 1e-9)
        assertEquals(1.5, resp.segments[0].end!!, 1e-9)
        assertEquals("录音。", resp.segments[1].text)
        assertEquals(1.5, resp.segments[1].start!!, 1e-9)
        assertEquals(3.0, resp.segments[1].end!!, 1e-9)
    }

    // ---- 25MB 预检边界（Metis B1.1：以连接器 25MB 为准） ----

    @Test
    fun precheck_25mbExactly_passes() {
        val file = sparseFile(maxBytes)
        // 恰好 25MB：不抛异常即通过
        SiliconFlowAsrConnector(apiKey = "test-key").precheck(file)
    }

    @Test
    fun precheck_25mbPlus1_rejects() {
        val file = sparseFile(maxBytes + 1)
        try {
            SiliconFlowAsrConnector(apiKey = "test-key").precheck(file)
            fail("应抛 FileTooLargeException")
        } catch (e: FileTooLargeException) {
            assertEquals(maxBytes + 1, e.fileSizeBytes)
            assertEquals(maxBytes, e.maxSizeBytes)
        }
    }

    // ---- 空 key → ApiKeyException（不发请求） ----

    @Test
    fun transcribe_emptyKey_throwsApiKeyException() = runBlocking {
        val connector = SiliconFlowAsrConnector(apiKey = "")
        val file = tmp.newFile("tiny.wav").apply { writeBytes(ByteArray(100)) }
        try {
            connector.transcribe(file)
            fail("应抛 ApiKeyException")
        } catch (e: ApiKeyException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    // ---- 请求构建 + 响应解析（拦截器捕获请求，替代 mockwebserver，零额外依赖） ----

    @Test
    fun transcribe_buildsMultipartRequestAndParsesResponse() = runBlocking {
        val fixtureWav = fixtureFile("/fixtures/test_3s_known.wav")
        val jsonText = readFixture("/fixtures/whisper_verbose_json_sample.json")
        val connector = SiliconFlowAsrConnector(
            apiKey = "test-key",
            client = capturingClient(200, jsonText),
        )

        val resp = connector.transcribe(fixtureWav, language = "zh")

        val request = capturedRequest.get() ?: error("未发起请求")
        assertEquals(SiliconFlowAsrConnector.ENDPOINT, request.url.toString())
        assertEquals("POST", request.method)
        assertEquals("Bearer test-key", request.header("Authorization"))

        val multipart = request.body as MultipartBody
        assertEquals(MultipartBody.FORM, multipart.type)
        assertEquals("FunAudioLLM/SenseVoiceSmall", multipart.partNamed("model")!!.body.utf8())
        assertEquals("verbose_json", multipart.partNamed("response_format")!!.body.utf8())
        assertEquals("zh", multipart.partNamed("language")!!.body.utf8())
        val filePart = multipart.partNamed("file")!!
        assertTrue(
            filePart.headers?.get("Content-Disposition")
                ?.contains("filename=\"test_3s_known.wav\"") == true,
        )
        assertEquals("audio/wav", filePart.body.contentType().toString())

        assertEquals("测试录音。", resp.text)
        assertEquals("zh", resp.language)
        assertEquals(2, resp.segments.size)
    }

    @Test
    fun transcribe_httpError_throwsAsrApiException() = runBlocking {
        val fixtureWav = fixtureFile("/fixtures/test_3s_known.wav")
        val connector = SiliconFlowAsrConnector(
            apiKey = "bad-key",
            client = capturingClient(401, """{"error":"invalid api key"}"""),
        )
        try {
            connector.transcribe(fixtureWav)
            fail("应抛 AsrApiException")
        } catch (e: AsrApiException) {
            assertEquals(401, e.statusCode)
        }
    }

    // ---- 辅助 ----

    private fun sparseFile(size: Long): File {
        val f = tmp.newFile("sparse_$size.bin")
        RandomAccessFile(f, "rw").use { it.setLength(size) }
        return f
    }

    private fun readFixture(path: String): String =
        javaClass.getResourceAsStream(path)?.readBytes()?.toString(Charsets.UTF_8)
            ?: error("夹具缺失: $path")

    private fun fixtureFile(path: String): File =
        File(javaClass.getResource(path)!!.toURI())

    private val capturedRequest = AtomicReference<Request?>()

    /** 捕获请求并返回固定响应的 client（OkHttp 5 已移除 Builder.callFactory，改用拦截器）。 */
    private fun capturingClient(code: Int, body: String): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                capturedRequest.set(chain.request())
                jsonResponse(code, body, chain.request())
            }
            .build()

    private fun RequestBody.utf8(): String {
        val sink = Buffer()
        writeTo(sink)
        return sink.readUtf8()
    }

    private fun jsonResponse(code: Int, body: String, request: Request): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Error")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

    private fun MultipartBody.partNamed(name: String): MultipartBody.Part? =
        parts.firstOrNull { it.headers?.get("Content-Disposition")?.contains("name=\"$name\"") == true }
}