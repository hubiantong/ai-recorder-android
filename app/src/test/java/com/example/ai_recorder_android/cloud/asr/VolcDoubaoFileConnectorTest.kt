package com.example.ai_recorder_android.cloud.asr

import com.example.ai_recorder_android.cloud.asr.dto.VolcAudio
import com.example.ai_recorder_android.cloud.asr.dto.VolcAudioInfo
import com.example.ai_recorder_android.cloud.asr.dto.VolcQueryResponse
import com.example.ai_recorder_android.cloud.asr.dto.VolcResult
import com.example.ai_recorder_android.cloud.asr.dto.VolcSubmitRequest
import com.example.ai_recorder_android.cloud.asr.dto.VolcUtterance
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * pro_lite 档连接器纯 JVM 单测：
 * 公网 URL 构造、submit 请求体 DTO 序列化、轮询状态机（fake clock/delay）、512MB 预检、结果解析。
 * SFTP 集成需真机/真 key（F3 验证），本文件不触网。
 */
class VolcDoubaoFileConnectorTest {

    // ---- 公网 URL 构造（SftpUploader 纯函数）----

    @Test
    fun publicUrl_joinsBaseAndRemoteName() {
        assertEquals(
            "http://8.130.23.56/airecorder/recordings/abc123.wav",
            SftpUploader.publicUrl("http://8.130.23.56/airecorder/recordings", "abc123.wav"),
        )
    }

    @Test
    fun publicUrl_handlesTrailingSlashOnBase() {
        assertEquals(
            "http://8.130.23.56/airecorder/recordings/abc.wav",
            SftpUploader.publicUrl("http://8.130.23.56/airecorder/recordings/", "abc.wav"),
        )
    }

    @Test
    fun remoteFileName_keepsOriginalExtension() {
        assertEquals("deadbeef.wav", SftpUploader.remoteFileName("rec_123.wav", uuidHex = "deadbeef"))
    }

    @Test
    fun remoteFileName_defaultsToWavWhenNoExtension() {
        assertEquals("deadbeef.wav", SftpUploader.remoteFileName("recording", uuidHex = "deadbeef"))
    }

    @Test
    fun normalizedKey_convertsLiteralNewlinesToRealOnes() {
        assertEquals("line1\nline2", SftpUploader.normalizedKey("line1\\nline2"))
        // 已是真实换行时原样通过
        assertEquals("a\nb", SftpUploader.normalizedKey("a\nb"))
    }

    // ---- submit 请求体 DTO 序列化 ----

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun submitRequest_serializesFullBodyMatchingPc() {
        val req = VolcSubmitRequest(
            audio = VolcAudio(url = "http://8.130.23.56/airecorder/recordings/abc.wav"),
        )
        val s = json.encodeToString(VolcSubmitRequest.serializer(), req)
        val back = json.decodeFromString<VolcSubmitRequest>(s)

        assertEquals("airecorder", back.user.uid)
        assertEquals("wav", back.audio.format)
        assertEquals("http://8.130.23.56/airecorder/recordings/abc.wav", back.audio.url)
        assertEquals(16000, back.audio.rate)
        assertEquals(1, back.audio.channel)
        assertNull(back.audio.language)
        assertEquals("bigmodel", back.request.model_name)
        assertTrue(back.request.enable_itn)
        assertTrue(back.request.enable_punc)
        assertTrue(back.request.show_utterances)
        // encodeDefaults=true：user/request 对象必须出现在 JSON 中（PC 恒发送）
        assertTrue(s.contains("\"user\""))
        assertTrue(s.contains("\"request\""))
        assertFalse(s.contains("language"))
    }

    @Test
    fun submitRequest_includesLanguageWhenSet() {
        val req = VolcSubmitRequest(audio = VolcAudio(url = "http://x/1.wav", language = "zh"))
        val s = json.encodeToString(VolcSubmitRequest.serializer(), req)
        val back = json.decodeFromString<VolcSubmitRequest>(s)
        assertEquals("zh", back.audio.language)
    }

    @Test
    fun queryResponse_parsesResultAndUtterances() {
        val raw = """{"code":0,"audio_info":{"duration":12345},"result":{"text":"你好世界","utterances":[{"text":"你好","start_time":0,"end_time":1000},{"text":"世界","start_time":1000,"end_time":2000}]}}"""
        val resp = json.decodeFromString<VolcQueryResponse>(raw)
        assertEquals("你好世界", resp.result?.text)
        assertEquals(2, resp.result?.utterances?.size)
        assertEquals(12345L, resp.audio_info?.duration)
    }

    // ---- 轮询状态机（fake clock/delay）----

    @Test
    fun pollUntilDone_returnsOnSuccessAfterProcessing() = runBlocking {
        var calls = 0
        val result = pollUntilDone(
            query = {
                calls++
                if (calls < 3) QueryOutcome.Processing(VOLC_STATUS_PROCESSING, "processing")
                else QueryOutcome.Success(VolcQueryResponse(result = VolcResult(text = "你好")))
            },
            delayFn = { delay(it) },
            clock = { 0L },
            pollIntervalMs = VOLC_POLL_INTERVAL_MS,
            timeoutMs = VOLC_POLL_TIMEOUT_MS,
        )
        assertEquals("你好", result.result?.text)
        assertEquals(3, calls)
    }

    @Test
    fun pollUntilDone_acceptsQueuedStatus() = runBlocking {
        var calls = 0
        val result = pollUntilDone(
            query = {
                calls++
                if (calls == 1) QueryOutcome.Processing(VOLC_STATUS_QUEUED, "queued")
                else QueryOutcome.Success(VolcQueryResponse(result = VolcResult(text = "ok")))
            },
            delayFn = { },
            clock = { 0L },
            pollIntervalMs = VOLC_POLL_INTERVAL_MS,
            timeoutMs = VOLC_POLL_TIMEOUT_MS,
        )
        assertEquals("ok", result.result?.text)
        assertEquals(2, calls)
    }

    @Test
    fun pollUntilDone_throwsApiExceptionOnFailureCode() {
        val ex = assertThrows(VolcApiException::class.java) {
            runBlocking {
                pollUntilDone(
                    query = { QueryOutcome.Failed(40000001, "bad request", "body") },
                    delayFn = { },
                    clock = { 0L },
                    pollIntervalMs = VOLC_POLL_INTERVAL_MS,
                    timeoutMs = VOLC_POLL_TIMEOUT_MS,
                )
            }
        }
        assertEquals(40000001, ex.statusCode)
        assertEquals("bad request", ex.apiMessage)
    }

    @Test
    fun pollUntilDone_timesOutAfterDeadline() {
        var now = 0L
        val ex = assertThrows(VolcTimeoutException::class.java) {
            runBlocking {
                pollUntilDone(
                    query = { QueryOutcome.Processing(VOLC_STATUS_PROCESSING, "processing") },
                    delayFn = { now += it },
                    clock = { now },
                    pollIntervalMs = 3_000L,
                    timeoutMs = 30_000L,
                )
            }
        }
        assertEquals(30_000L, ex.timeoutMs)
    }

    // ---- 512MB 预检 ----

    @Test
    fun checkFileSize_allowsExactly512Mb() {
        checkFileSize(512L * 1024 * 1024) // 不抛异常
    }

    @Test
    fun checkFileSize_rejectsOver512Mb() {
        val ex = assertThrows(VolcFileTooLargeException::class.java) {
            checkFileSize(512L * 1024 * 1024 + 1)
        }
        assertEquals(512L * 1024 * 1024, ex.maxBytes)
    }

    // ---- 结果解析 ----

    @Test
    fun parseResult_mapsUtterancesAndSkipsBlank() {
        val connector = VolcDoubaoFileConnector(
            config = VolcConfig(appId = "app", accessToken = "token"),
            uploader = SftpUploader(SftpConfig("", 22, "", "", "", "")),
        )
        val resp = VolcQueryResponse(
            audio_info = VolcAudioInfo(duration = 9000),
            result = VolcResult(
                text = "你好 世界",
                utterances = listOf(
                    VolcUtterance(text = "你好", start_time = 0, end_time = 1000),
                    VolcUtterance(text = "   ", start_time = 1000, end_time = 2000),
                    VolcUtterance(text = "世界", start_time = 2000, end_time = 3000),
                ),
            ),
        )
        val result = connector.parseResult(resp)
        assertEquals("你好 世界", result.text)
        assertEquals(2, result.segments?.size)
        assertEquals(0L, result.segments?.get(0)?.startMs)
        assertEquals(3000L, result.segments?.get(1)?.endMs)
        assertEquals(9000L, result.durationMs)
    }
}