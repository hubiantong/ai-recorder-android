package com.example.ai_recorder_android.data.db

import androidx.room.Embedded
import androidx.room.Relation

/**
 * Recording 1:N 聚合：chunks / audioSegments / attachments。
 * 对应 PC Recording 的三个 relationship（cascade="all, delete-orphan"）。
 * 查询需配合 @Transaction（见 [RecordingDao.getRecordingWithRelations]）。
 */
data class RecordingWithRelations(
    @Embedded val recording: RecordingEntity,
    @Relation(parentColumn = "id", entityColumn = "recording_id")
    val chunks: List<TranscriptChunkEntity>,
    @Relation(parentColumn = "id", entityColumn = "recording_id")
    val audioSegments: List<RecordingAudioSegmentEntity>,
    @Relation(parentColumn = "id", entityColumn = "recording_id")
    val attachments: List<RecordingAttachmentEntity>,
)