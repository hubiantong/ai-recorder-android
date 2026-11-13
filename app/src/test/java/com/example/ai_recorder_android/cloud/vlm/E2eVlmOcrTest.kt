package com.example.ai_recorder_android.cloud.vlm

import com.example.ai_recorder_android.BuildConfig
import java.io.File
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * SiliconFlow DeepSeek-OCR 端到端验收（真实调用云端 API）。
 *
 * 夹具：`/fixtures/ocr_text.png`（含 ASCII 文本 "REC 12345" 的 480x160 PNG，
 * System.Drawing 预生成，避开 Android 单测无 java.awt 的限制）。
 * 经 [QwenVlConnector.describeViaSiliconFlow]（备用 1 档）生成描述，
 * 断言描述文本包含图中数字关键词。
 *
 * 有 key 时真实调用；无 key / 占位 key 时跳过（JUnit Assume）。
 */
class E2eVlmOcrTest {

    @Test
    fun siliconflow_deepseek_ocr_reads_text() = runBlocking {
        assumeTrue(
            "SILICONFLOW_API_KEY 未配置，跳过 DeepSeek-OCR 端到端",
            hasKey(BuildConfig.SILICONFLOW_API_KEY),
        )
        val pngBytes = File(
            javaClass.getResource("/fixtures/ocr_text.png")!!.toURI(),
        ).readBytes()
        val connector = QwenVlConnector()
        val result = connector.describeViaSiliconFlow(
            imageDataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(pngBytes),
            prompt = "请读出图片中的文字。",
            contextText = null,
            maxTokens = 300,
        )
        assertTrue(
            "DeepSeek-OCR 描述应包含图中数字 12345，实际: '${result.description}'",
            result.description.contains("12345"),
        )
    }

    private fun hasKey(value: String): Boolean = value.isNotBlank() && value != "YOUR_KEY_HERE"
}
