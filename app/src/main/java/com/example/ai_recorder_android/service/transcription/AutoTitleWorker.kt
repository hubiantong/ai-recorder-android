package com.example.ai_recorder_android.service.transcription

import com.example.ai_recorder_android.cloud.llm.DeepSeekConnector
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 自动标题 worker（todo 18）：LLM 标题（≤12 字）-> 失败回退默认标题（如日期）。
 * 对齐 PC `pc_api/gui/auto_title_worker.py` + `note_fuser.generate_title`：
 * - 主用 GLM（硬编码 key）失败自动切备用 DeepSeek；短文本 / 主备均失败返回空串
 * - 空串：回退默认标题 `录音 yyyy-MM-dd HH:mm:ss`（对齐 PC create_recording 默认标题）
 * - 结果经 [TranscriptionStore.saveTitle] 落库 Recording.title
 */
class AutoTitleWorker(
    private val connector: DeepSeekConnector,
    private val store: TranscriptionStore,
) {

    /**
     * 生成标题并保存到 Recording.title，返回标题文本。
     * 永不抛异常：主备 LLM 均失败一律回退默认标题。
     */
    suspend fun generateTitle(
        recordingId: Long,
        transcription: String,
    ): String {
        val title = connector.generateTitle(transcription).ifBlank { defaultTitle() }
        store.saveTitle(recordingId, title)
        return title
    }

    companion object {
        /** 默认标题（对齐 PC `create_recording` 的 `录音 %Y-%m-%d %H:%M:%S`）。 */
        internal fun defaultTitle(nowMs: Long = System.currentTimeMillis()): String =
            "录音 " + Instant.ofEpochMilli(nowMs)
                .atZone(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
    }
}