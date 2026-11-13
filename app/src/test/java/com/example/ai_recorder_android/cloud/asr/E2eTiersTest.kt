package com.example.ai_recorder_android.cloud.asr

import com.example.ai_recorder_android.BuildConfig
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 四档连接器端到端验收测试（真实调用云端 API）。
 *
 * 规则：**有 key 时真实调用并断言期望输出；无 key / 占位 key 时跳过**（JUnit `Assume`，
 * 跳过用例在报告中标记为 ignored 而非失败）。
 *
 * 夹具：`/fixtures/test_3s_speech.wav`（Windows SAPI「Microsoft Huihui」合成中文语音
 * 「测试录音」，16kHz mono PCM_16，1.735s，55566 字节）。free 档断言转写文本含关键词
 * 「测试」；realtime 档断言收到 partial 回调；pro_lite 档断言 submit+query 完成（文本非空）；
 * pro_pro 档断言同步转写成功（文本非空）。
 *
 * 凭证来源：BuildConfig（由 app/secrets.properties 注入）。占位值 `YOUR_KEY_HERE`
 * 视为未配置。pro_lite 档额外要求 SFTP 私钥（AUDIO_HOST_PRIVATE_KEY）。
 *
 * ⚠️ 本机 Gradle worker 编码 bug（gradle/gradle#30304）无法运行 testDebugUnitTest，
 * 由其他机器配置真实 key 后执行 `./gradlew :app:testDebugUnitTest --tests "*E2eTiersTest"` 验证。
 */
class E2eTiersTest {

    private val fixtureWav: File by lazy {
        File(javaClass.getResource("/fixtures/test_3s_speech.wav")!!.toURI())
    }

    // ---- free 档：硅基流动 SiliconFlow（OpenAI 兼容端点）----

    @Test
    fun free_tier_siliconflow_transcribe() = runBlocking {
        assumeTrue(
            "SILICONFLOW_API_KEY 未配置，跳过 free 档端到端",
            hasKey(BuildConfig.SILICONFLOW_API_KEY),
        )
        val connector = SiliconFlowAsrConnector(apiKey = BuildConfig.SILICONFLOW_API_KEY)
        val resp = connector.transcribe(fixtureWav, language = "zh")
        assertTrue(
            "free 档转写文本应含关键词「$KEYWORD」，实际: '${resp.text}'",
            resp.text.contains(KEYWORD),
        )
    }

    // ---- realtime 档：阿里云百炼 DashScope WebSocket（paraformer-realtime-8k-v2）----

    @Test
    fun realtime_tier_dashscope_partial() {
        assumeTrue(
            "DASHSCOPE_API_KEY 未配置，跳过 realtime 档端到端",
            hasKey(BuildConfig.DASHSCOPE_API_KEY),
        )
        val latch = CountDownLatch(1)
        val partials = CopyOnWriteArrayList<String>()
        val sentences = CopyOnWriteArrayList<String>()
        val errors = AtomicReference<Throwable?>()
        val connector = DashScopeRealtimeConnector(
            apiKey = BuildConfig.DASHSCOPE_API_KEY,
            callback = object : DashScopeRealtimeCallback {
                override fun onPartial(text: String) {
                    partials.add(text)
                    if (text.isNotBlank()) latch.countDown()
                }

                // 短音频（~2s）服务端可能跳过中间 partial 直接给最终句：
                // 生产 RealtimeTranscriptionPipeline 以句子落库为准，这里同样接受 sentence
                override fun onSentence(text: String, beginTimeMs: Int?, endTimeMs: Int?) {
                    sentences.add(text)
                    if (text.isNotBlank()) latch.countDown()
                }

                override fun onError(error: Throwable) {
                    errors.set(error)
                    latch.countDown()
                }
            },
        )
        connector.debugFrameLogger = { println("WS-DIAG $it") }

        connector.connect()
        // 发送夹具 PCM（16kHz int16 mono 小端；连接器内部降采样 8kHz 并分帧，
        // task-started 前自动缓冲）
        val pcm = extractPcm16k(fixtureWav)
        var offset = 0
        while (offset < pcm.size) {
            val end = minOf(offset + 3200, pcm.size)
            connector.sendPcm16k(pcm.copyOfRange(offset, end))
            offset = end
        }

        val done = latch.await(REALTIME_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        connector.finish()
        connector.close()

        errors.get()?.let { throw it }
        assertTrue(
            "realtime 档应在 ${REALTIME_TIMEOUT_SECONDS}s 内收到 partial/sentence 回调" +
                "（partials=$partials, sentences=$sentences）",
            done && (partials.any { it.isNotBlank() } || sentences.any { it.isNotBlank() }),
        )
    }

    // ---- pro_lite 档：火山引擎 豆包录音文件识别 2.0（SFTP 上传 + submit + query）----

    @Test
    fun pro_lite_tier_volc_submit_query() = runBlocking {
        assumeTrue(
            "VOLC 凭证或 SFTP 私钥未配置，跳过 pro_lite 档端到端",
            hasKey(BuildConfig.VOLC_APP_ID) &&
                hasKey(BuildConfig.VOLC_ACCESS_TOKEN) &&
                hasKey(BuildConfig.AUDIO_HOST_PRIVATE_KEY),
        )
        val connector = VolcDoubaoFileConnector.fromBuildConfig()
        val result = connector.transcribe(fixtureWav, language = "zh")
        assertTrue(
            "pro_lite 档 submit+query 应完成（转写文本非空），实际: '${result.text}'",
            result.text.isNotBlank(),
        )
    }

    // ---- pro_pro 档：阿里云百炼 fun-asr-flash（同步 multimodal-generation）----

    @Test
    fun pro_pro_tier_dashscope_flash_transcribe() = runBlocking {
        assumeTrue(
            "DASHSCOPE_API_KEY 未配置，跳过 pro_pro 档端到端",
            hasKey(BuildConfig.DASHSCOPE_API_KEY),
        )
        val connector = DashScopeFlashAsrConnector(apiKey = BuildConfig.DASHSCOPE_API_KEY)
        val result = connector.transcribe(fixtureWav, language = "zh")
        assertTrue(
            "pro_pro 档转写应成功（文本非空），实际: '${result.text}'",
            result.text.isNotBlank(),
        )
    }

    companion object {
        /** 夹具语音内容关键词（SAPI 合成「测试录音」）。 */
        private const val KEYWORD = "测试"

        /** secrets.properties 模板占位值，视为未配置。 */
        private const val PLACEHOLDER = "YOUR_KEY_HERE"

        /** realtime 档等待 partial 回调的超时（秒）。 */
        private const val REALTIME_TIMEOUT_SECONDS = 60L

        private fun hasKey(value: String): Boolean = value.isNotBlank() && value != PLACEHOLDER

        /**
         * 剥离 WAV 头，返回 16kHz int16 mono PCM（小端）。
         * 按 RIFF 块遍历定位 data 块（兼容标准 44 字节头与 SAPI 的 fmtSize=18 头）。
         */
        private fun extractPcm16k(wav: File): ByteArray {
            val bytes = wav.readBytes()
            var offset = 12
            while (offset + 8 <= bytes.size) {
                val id = String(bytes, offset, 4, Charsets.US_ASCII)
                val size = leUInt32(bytes, offset + 4)
                if (id == "data") {
                    return bytes.copyOfRange(offset + 8, bytes.size)
                }
                offset += 8 + size + (size % 2)
            }
            error("WAV 缺少 data 块: ${wav.name}")
        }

        private fun leUInt32(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }
}