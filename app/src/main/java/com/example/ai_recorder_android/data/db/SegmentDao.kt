package com.example.ai_recorder_android.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/**
 * 音频段 DAO（表 recording_audio_segments），语义对齐 PC `recording_service.py` 段相关方法。
 */
@Dao
interface SegmentDao {

    @Insert
    suspend fun insert(segment: RecordingAudioSegmentEntity): Long

    @Update
    suspend fun update(segment: RecordingAudioSegmentEntity)

    /** 按录音查段，segment_index 升序（对齐 PC `list_audio_segments`）。 */
    @Query("SELECT * FROM recording_audio_segments WHERE recording_id = :recordingId ORDER BY segment_index ASC")
    suspend fun getByRecordingId(recordingId: Long): List<RecordingAudioSegmentEntity>

    /**
     * 更新段转写状态（对齐 PC `set_segment_status`）：
     * - status 必更新；error 仅在非 null 时写入（保留既有错误）；
     * - status=COMPLETED 时记录 completed_at。
     */
    @Query(
        """
        UPDATE recording_audio_segments
        SET transcription_status = :status,
            transcription_error = CASE WHEN :error IS NOT NULL THEN :error ELSE transcription_error END,
            completed_at = CASE WHEN :status = 'COMPLETED' THEN :now ELSE completed_at END
        WHERE id = :segmentId
        """,
    )
    suspend fun updateTranscriptionStatus(
        segmentId: Long,
        status: RecordingStatus,
        error: String? = null,
        now: Long = System.currentTimeMillis(),
    )
}