package com.example.ai_recorder_android.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import java.time.Instant
import java.time.ZoneId

/**
 * 按天分组的录音列表（todo 20 历史列表用）。
 *
 * [day] 为本地日期键（"yyyy-MM-dd"），[recordings] 组内按 created_at 倒序。
 */
data class RecordingsByDay(
    val day: String,
    val recordings: List<RecordingEntity>,
)

/**
 * 录音主 DAO，语义对齐 PC `pc_api/recording_service.py`。
 *
 * - [saveTranscription] ↔ PC `save_transcription`：事务内清旧 chunks 后批量插入新 chunks，
 *   同时更新 recordings.transcription / status=COMPLETED / audio_duration_seconds / completed_at。
 * - [saveSegmentTranscription] ↔ PC `save_segment_transcription`：`MAX(chunk_index)+1` 全局续编、
 *   段内时间戳 + seg.start_time 偏移、Recording.transcription 文本累加。
 */
@Dao
interface RecordingDao {

    // ---------- Recording CRUD ----------

    @Insert
    suspend fun insertRecording(recording: RecordingEntity): Long

    @Update
    suspend fun updateRecording(recording: RecordingEntity)

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun getRecordingById(id: Long): RecordingEntity?

    @Query("DELETE FROM recordings WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** 全部录音，按 created_at 倒序（对齐 PC `list_recordings`）。 */
    @Query("SELECT * FROM recordings ORDER BY created_at DESC")
    suspend fun getAllRecordings(): List<RecordingEntity>

    // ---------- 历史分组 / 搜索 ----------

    /**
     * 按本地日期（yyyy-MM-dd）分组；组序与组内均按 created_at 倒序
     * （getAllRecordings 已倒序，groupBy 保持首次出现顺序）。
     * [zoneId] 可注入以便测试确定性，默认系统时区。
     */
    suspend fun getRecordingsGroupedByDay(zoneId: ZoneId = ZoneId.systemDefault()): List<RecordingsByDay> =
        getAllRecordings()
            .groupBy { Instant.ofEpochMilli(it.createdAt).atZone(zoneId).toLocalDate().toString() }
            .map { (day, recordings) -> RecordingsByDay(day, recordings) }

    /**
     * 按标题/转写内容搜索（对齐 PC `search_recordings`：title OR transcription LIKE）。
     * 空白（trim 后为空）查询返回全部。SQLite LIKE 对 ASCII 大小写不敏感（与 PC ilike 行为一致）。
     */
    @Query(
        """
        SELECT * FROM recordings
        WHERE trim(:query) = ''
           OR title LIKE '%' || :query || '%'
           OR transcription LIKE '%' || :query || '%'
        ORDER BY created_at DESC
        """,
    )
    suspend fun searchRecordings(query: String): List<RecordingEntity>

    // ---------- TranscriptChunk ----------

    @Insert
    suspend fun insertChunk(chunk: TranscriptChunkEntity): Long

    @Insert
    suspend fun insertChunks(chunks: List<TranscriptChunkEntity>)

    @Query("SELECT * FROM transcript_chunks WHERE recording_id = :recordingId ORDER BY chunk_index ASC")
    suspend fun getChunksForRecording(recordingId: Long): List<TranscriptChunkEntity>

    @Query("DELETE FROM transcript_chunks WHERE recording_id = :recordingId")
    suspend fun deleteChunksForRecording(recordingId: Long)

    // ---------- save_transcription 语义（PC `save_transcription`） ----------

    /**
     * 保存整文件转写结果（对齐 PC `save_transcription`）：
     * 1. 更新 recordings：transcription=文本、status=COMPLETED、completed_at=now、可选 audio_duration_seconds；
     * 2. 事务内先 DELETE 该录音全部旧 chunks（重新转写时清理）；
     * 3. 批量插入新 chunks，chunk_index 按 0..n-1 顺序重排（对齐 PC `enumerate(response.segments)`）。
     *
     * 录音不存在时抛 [IllegalArgumentException]（对齐 PC ValueError）。
     * 返回插入的 chunk 数量。
     */
    @Transaction
    suspend fun saveTranscription(
        recordingId: Long,
        transcriptionText: String,
        chunks: List<TranscriptChunkEntity>,
        durationSeconds: Double? = null,
    ): Int {
        if (getRecordingById(recordingId) == null) {
            throw IllegalArgumentException("Recording $recordingId not found")
        }
        val now = System.currentTimeMillis()
        updateRecordingTranscription(recordingId, transcriptionText, now, durationSeconds)
        deleteChunksForRecording(recordingId)
        val normalized = chunks.mapIndexed { i, c -> c.copy(chunkIndex = i) }
        insertChunks(normalized)
        return normalized.size
    }

    // ---------- save_segment_transcription 语义（PC `save_segment_transcription`） ----------

    /**
     * 保存单段转写结果（对齐 PC `save_segment_transcription`）：
     * 1. 按 (recordingId, segmentIndex) 定位段，不存在抛 [IllegalArgumentException]（对齐 PC ValueError）；
     * 2. 段标记 transcription_status=COMPLETED、清 transcription_error、记 completed_at；
     * 3. 新 chunk 的 chunk_index 从 `MAX(chunk_index)+1` 全局续编（跨段递增）；
     * 4. 段内时间戳 + seg.start_time 偏移为相对录音时间（null 时间戳保持 null）；
     * 5. 空白内容 chunk 跳过（对齐 PC `if not s.text.strip(): continue`）；
     * 6. Recording.transcription 文本累加（`COALESCE(transcription, '') || :newText`，
     *    列初始为 NULL 时先归零为空串再拼接，避免 SQLite `NULL || x = NULL` 吞掉文本）。
     *
     * 返回实际插入的 chunk 数量。
     */
    @Transaction
    suspend fun saveSegmentTranscription(
        recordingId: Long,
        segmentIndex: Int,
        chunks: List<TranscriptChunkEntity>,
    ): Int {
        val segment = getSegmentByIndex(recordingId, segmentIndex)
            ?: throw IllegalArgumentException("Segment (recordingId=$recordingId, segmentIndex=$segmentIndex) not found")

        markSegmentCompleted(segment.id, System.currentTimeMillis())

        val segOffset = segment.startTime ?: 0.0
        val nonBlank = chunks.filter { it.content.isNotBlank() }

        val nextIndex = getNextChunkIndex(recordingId)
        val newChunks = nonBlank.mapIndexed { i, c ->
            c.copy(
                chunkIndex = nextIndex + i,
                startTime = c.startTime?.plus(segOffset),
                endTime = c.endTime?.plus(segOffset),
            )
        }
        insertChunks(newChunks)

        val newText = nonBlank.joinToString("") { it.content }
        if (newText.isNotEmpty()) {
            appendTranscription(recordingId, newText)
        }
        return newChunks.size
    }

    // ---------- 按 recordingId 查子表 ----------

    /** 插入一段音频行（对齐 PC `add_audio_segment` 的段行落库）。返回新段 id。 */
    @Insert
    suspend fun insertSegment(segment: RecordingAudioSegmentEntity): Long

    @Query("SELECT * FROM recording_audio_segments WHERE recording_id = :recordingId ORDER BY segment_index ASC")
    suspend fun getSegmentsForRecording(recordingId: Long): List<RecordingAudioSegmentEntity>

    @Query("SELECT * FROM recording_attachments WHERE recording_id = :recordingId ORDER BY timestamp ASC")
    suspend fun getAttachmentsForRecording(recordingId: Long): List<RecordingAttachmentEntity>

    // ---------- 聚合查询 ----------

    @Transaction
    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun getRecordingWithRelations(id: Long): RecordingWithRelations?

    // ---------- 内部 SQL（供 @Transaction 默认方法调用） ----------

    @Query(
        """
        UPDATE recordings
        SET transcription = :transcriptionText,
            status = 'COMPLETED',
            audio_duration_seconds = CASE WHEN :durationSeconds IS NULL THEN audio_duration_seconds ELSE :durationSeconds END,
            completed_at = :now
        WHERE id = :recordingId
        """,
    )
    suspend fun updateRecordingTranscription(
        recordingId: Long,
        transcriptionText: String,
        now: Long,
        durationSeconds: Double?,
    )

    @Query("SELECT * FROM recording_audio_segments WHERE recording_id = :recordingId AND segment_index = :segmentIndex")
    suspend fun getSegmentByIndex(recordingId: Long, segmentIndex: Int): RecordingAudioSegmentEntity?

    @Query(
        """
        UPDATE recording_audio_segments
        SET transcription_status = 'COMPLETED',
            transcription_error = NULL,
            completed_at = :now
        WHERE id = :segmentId
        """,
    )
    suspend fun markSegmentCompleted(segmentId: Long, now: Long)

    @Query("SELECT COALESCE(MAX(chunk_index), -1) + 1 FROM transcript_chunks WHERE recording_id = :recordingId")
    suspend fun getNextChunkIndex(recordingId: Long): Int

    @Query("UPDATE recordings SET transcription = COALESCE(transcription, '') || :newText WHERE id = :recordingId")
    suspend fun appendTranscription(recordingId: Long, newText: String)
}