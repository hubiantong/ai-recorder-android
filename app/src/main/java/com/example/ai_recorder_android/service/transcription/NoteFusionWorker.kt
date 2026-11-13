package com.example.ai_recorder_android.service.transcription

import com.example.ai_recorder_android.cloud.llm.DeepSeekConnector
import com.example.ai_recorder_android.cloud.llm.FusionChunk
import com.example.ai_recorder_android.cloud.llm.FusionNoteRequest
import com.example.ai_recorder_android.data.db.TranscriptChunkEntity

/**
 * 融合笔记生成 worker（todo 18）：转写文本 → DeepSeek 融合 → 存 Recording.summary。
 * 对齐 PC `core/fusion/note_fuser.py` 的 generate_note：
 * - 组装 [FusionNoteRequest]（标题 / 时长 / 带时间戳 chunks；照片附件由 todo 21 详情页扩展）
 * - 调 [DeepSeekConnector.generateFusionNote]（无 key / LLM 失败 → 内部规则模板回退，不抛异常）
 * - 结果经 [TranscriptionStore.saveSummary] 落库 Recording.summary
 */
class NoteFusionWorker(
    private val connector: DeepSeekConnector,
    private val store: TranscriptionStore,
) {

    /**
     * 生成融合笔记并保存到 Recording.summary，返回笔记文本。
     * 永不抛异常：connector 内部已处理无 key / LLM 失败回退规则模板。
     */
    suspend fun generateNote(
        recordingId: Long,
        title: String?,
        transcription: String,
        chunks: List<TranscriptChunkEntity>,
        durationSeconds: Double?,
    ): String {
        val request = FusionNoteRequest(
            title = title,
            durationSeconds = durationSeconds,
            transcription = transcription,
            chunks = chunks.map { it.toFusionChunk() },
        )
        val note = connector.generateFusionNote(request)
        store.saveSummary(recordingId, note)
        return note
    }

    private fun TranscriptChunkEntity.toFusionChunk() = FusionChunk(
        startTime = startTime,
        endTime = endTime,
        speaker = speakerName,
        content = content,
    )
}
