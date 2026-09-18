package com.example.ai_recorder_android.cloud.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.util.Base64

/**
 * DashScopeFlashAsrConnector 纯 JVM 单测（本机不跑 gradlew，其他机器验证）。
 * 覆盖：请求体 DTO 序列化、响应解析（嵌套 output.output）、WAV 时长解析、
 * 5 分钟预检边界（300s 通过 / 301s 拒绝）、流式 base64 正确性。
 */
class DashScopeFlashAsrConnectorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newConnector(): DashScopeFlashAsrConnector =
        DashScopeFlashAsrConnector(apiKey = "test-key")

    // ---- 请求体 DTO 序列化 ----

    @Test
    fun buildRequestBody_serializesModelInputAudioAndParameters() {
        val json = newConnector().buildRequestBody(
            dataUri = "data:audio/wav;base64,QUJD",
            prompt = null,
            language = null,
        )
        assertTrue(json.contains("\"model\":\"fun-asr-flash-2026-06-15\""))
        assertTrue(json.contains("\"type\":\"input_audio\""))
        assertTrue(json.contains("\"input_audio\":{\"data\":\"data:audio/wav;base64,QUJD\"}"))
        assertTrue(json.contains("\"format\":\"wav\""))
        assertTrue(json.contains("\"sample_rate\":16000"))
        // 无 prompt / language 时不应出现对应字段（explicitNulls=false）
        assertFalse(json.contains("language_hints"))
        assertFalse(json.contains("\"text\""))
    }

    @Test
    fun buildRequestBody_withPromptPrependsSystemMessage() {
        val json = newConnector().buildRequestBody(
            dataUri = "data:audio/wav;base64,QUJD",
            prompt = "  会议专有名词：量子计算  ",
            language = "zh",
        )
        // system 消息在 user 之前
        val systemIdx = json.indexOf("\"role\":\"system\"")
        val userIdx = json.indexOf("\"role\":\"user\"")
        assertTrue(systemIdx in 0 until userIdx)
        assertTrue(json.contains("\"type\":\"text\",\"text\":\"会议专有名词：量子计算\""))
        assertTrue(json.contains("\"language_hints\":[\"zh\"]"))
    }

    // ---- 响应解析 ----

    @Test
    fun parseResponse_nestedOutputOutput_extractsTextSentenceAndWords() {
        val json = """
            {
              "output": {
                "output": {
                  "text": "你好世界",
                  "sentence": {
                    "text": "你好世界",
                    "begin_time": 0,
                    "end_time": 1200,
                    "words": [
                      {"text": "你好", "begin_time": 0, "end_time": 500, "punctuation": ""},
                      {"text": "世界", "begin_time": 500, "end_time": 1200, "punctuation": "。"}
                    ]
                  }
                }
              },
              "request_id": "req-123"
            }
        """.trimIndent()
        val result = newConnector().parseResponse(json, duration = 1.2, language = "zh")

        assertEquals("你好世界", result.text)
        assertEquals(1, result.segments!!.size)
        val seg = result.segments!![0]
        assertEquals("你好世界", seg.text)
        assertEquals(0.0, seg.startTime!!, 1e-9)
        assertEquals(1.2, seg.endTime!!, 1e-9)
        assertEquals(2, seg.words!!.size)
        val w0: DashScopeFlashWord = seg.words!![0]
        assertEquals("你好", w0.text)
        assertEquals(0.0, w0.start, 1e-9)
        assertEquals(0.5, w0.end, 1e-9)
        assertEquals("。", seg.words!![1].punctuation)
        assertEquals("dashscope_funasr", result.provider)
        assertEquals("fun-asr-flash-2026-06-15", result.model)
        assertEquals(1.2, result.duration, 1e-9)
        assertEquals("zh", result.language)
    }

    @Test
    fun parseResponse_outerTextFallback_whenNestedMissing() {
        val json = """{"output": {"text": "外层文本"}}""".trimIndent()
        val result = newConnector().parseResponse(json, duration = 5.0, language = null)
        assertEquals("外层文本", result.text)
        assertEquals(1, result.segments!!.size)
        assertEquals("外层文本", result.segments!![0].text)
        assertNull(result.segments!![0].words)
    }

    @Test
    fun parseResponse_errorMessage_throws() {
        val json = """{"code": "InvalidParameter", "message": "音频时长超限"}""".trimIndent()
        try {
            newConnector().parseResponse(json, duration = 1.0, language = null)
            fail("应抛出 DashScopeFlashException")
        } catch (e: DashScopeFlashException) {
            assertTrue(e.message!!.contains("音频时长超限"))
        }
    }

    // ---- WAV 时长解析 ----

    @Test
    fun wavDuration_parsesOneSecondWav() {
        val file = writeWav(byteRate = 32000, dataSize = 32000) // 1s @ 16k mono 16bit
        assertEquals(1.0, WavDuration.parseSeconds(file), 1e-9)
    }

    @Test
    fun wavDuration_skipsExtraChunksBeforeData() {
        // fmt 后插入 LIST 块（奇数 size 带 padding），再 data
        val file = tmp.newFile("extra_chunks.wav")
        RandomAccessFile(file, "rw").use { raf ->
            raf.write("RIFF".toByteArray(Charsets.US_ASCII))
            raf.write(intLe(36 + 10 + 32000)) // 4(WAVE) + 24(fmt) + 10(LIST) + 8+32000(data)
            raf.write("WAVE".toByteArray(Charsets.US_ASCII))
            writeFmtChunk(raf, byteRate = 32000)
            raf.write("LIST".toByteArray(Charsets.US_ASCII))
            raf.write(intLe(1)) // 奇数 size → 需 1 字节 padding
            raf.write(0x41)
            raf.write(0x00) // padding
            raf.write("data".toByteArray(Charsets.US_ASCII))
            raf.write(intLe(32000))
            raf.write(ByteArray(32000))
        }
        assertEquals(1.0, WavDuration.parseSeconds(file), 1e-9)
    }

    @Test
    fun wavDuration_streamedDataSize_usesFileLength() {
        // data size = 0xFFFFFFFF（流式 WAV）→ 回退文件长度推算
        val file = tmp.newFile("streamed.wav")
        RandomAccessFile(file, "rw").use { raf ->
            raf.write("RIFF".toByteArray(Charsets.US_ASCII))
            raf.write(intLe(36 + 64000))
            raf.write("WAVE".toByteArray(Charsets.US_ASCII))
            writeFmtChunk(raf, byteRate = 32000)
            raf.write("data".toByteArray(Charsets.US_ASCII))
            raf.write(intLe(0xFFFFFFFF.toInt()))
            raf.write(ByteArray(64000)) // 2s
        }
        assertEquals(2.0, WavDuration.parseSeconds(file), 1e-9)
    }

    @Test
    fun wavDuration_malformedFile_throws() {
        val file = tmp.newFile("bad.wav")
        file.writeBytes("RIFF".toByteArray(Charsets.US_ASCII)) // 只有 4 字节
        try {
            WavDuration.parseSeconds(file)
            fail("应抛出 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("WAV"))
        }
    }

    @Test
    fun wavDuration_missingDataChunk_throws() {
        val file = tmp.newFile("nodata.wav")
        RandomAccessFile(file, "rw").use { raf ->
            raf.write("RIFF".toByteArray(Charsets.US_ASCII))
            raf.write(intLe(36))
            raf.write("WAVE".toByteArray(Charsets.US_ASCII))
            writeFmtChunk(raf, byteRate = 32000)
        }
        try {
            WavDuration.parseSeconds(file)
            fail("应抛出 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("data"))
        }
    }

    // ---- 5 分钟预检边界 ----

    @Test
    fun checkDuration_exactly300Seconds_passes() {
        newConnector().checkDuration(300.0) // 不抛即通过
    }

    @Test
    fun checkDuration_over300Seconds_rejects() {
        val file = writeWav(byteRate = 32000, dataSize = 301 * 32000) // 301s
        val duration = WavDuration.parseSeconds(file)
        assertEquals(301.0, duration, 1e-9)
        try {
            newConnector().checkDuration(duration)
            fail("301s 应被拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("300"))
        }
    }

    @Test
    fun checkDuration_300SecondsBoundaryFile_passes() {
        val file = writeWav(byteRate = 32000, dataSize = 300 * 32000) // 恰好 300s
        val duration = WavDuration.parseSeconds(file)
        assertEquals(300.0, duration, 1e-9)
        newConnector().checkDuration(duration) // 不抛即通过
    }

    // ---- 流式 base64 ----

    @Test
    fun buildDataUri_streamsBase64Correctly() {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        val file = tmp.newFile("audio.wav")
        file.writeBytes(bytes)
        val expected = "data:audio/wav;base64," + Base64.getEncoder().encodeToString(bytes)
        assertEquals(expected, newConnector().buildDataUri(file))
    }

    // ---- 工具 ----

    /** 写标准 44 字节头 WAV（PCM mono 16bit @16000Hz），dataSize 字节数据。 */
    private fun writeWav(byteRate: Int, dataSize: Int): File {
        val file = tmp.newFile("test_${byteRate}_${dataSize}.wav")
        RandomAccessFile(file, "rw").use { raf ->
            raf.write("RIFF".toByteArray(Charsets.US_ASCII))
            raf.write(intLe(36 + dataSize))
            raf.write("WAVE".toByteArray(Charsets.US_ASCII))
            writeFmtChunk(raf, byteRate)
            raf.write("data".toByteArray(Charsets.US_ASCII))
            raf.write(intLe(dataSize))
            val buf = ByteArray(64 * 1024)
            var remaining = dataSize
            while (remaining > 0) {
                val n = minOf(remaining, buf.size)
                raf.write(buf, 0, n)
                remaining -= n
            }
        }
        return file
    }

    private fun writeFmtChunk(raf: RandomAccessFile, byteRate: Int) {
        raf.write("fmt ".toByteArray(Charsets.US_ASCII))
        raf.write(intLe(16))
        raf.write(shortLe(1)) // PCM
        raf.write(shortLe(1)) // mono
        raf.write(intLe(16000)) // sample rate
        raf.write(intLe(byteRate))
        raf.write(shortLe(2)) // block align
        raf.write(shortLe(16)) // bits per sample
    }

    private fun intLe(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte(),
    )

    private fun shortLe(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
    )
}