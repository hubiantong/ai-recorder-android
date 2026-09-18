package com.example.ai_recorder_android.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * SegmentDao 单测（in-memory Room）：insert/update/getByRecordingId/updateTranscriptionStatus。
 */
class SegmentDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: SegmentDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        dao = db.segmentDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun segment(
        recordingId: Long,
        segmentIndex: Int,
        startTime: Double? = null,
        endTime: Double? = null,
    ) = RecordingAudioSegmentEntity(
        recordingId = recordingId,
        segmentIndex = segmentIndex,
        audioPath = "segments/seg_${recordingId}_$segmentIndex.wav",
        startTime = startTime,
        endTime = endTime,
        duration = if (startTime != null && endTime != null) endTime - startTime else null,
        transcriptionStatus = RecordingStatus.PENDING,
        createdAt = 1_752_000_000_000L,
    )

    @Test
    fun insertUpdateGetByRecordingId_crud() = runBlocking {
        val rid = db.recordingDao().insertRecording(
            RecordingEntity(audioPath = "recordings/rec_test.wav", createdAt = 1_752_000_000_000L),
        )
        val s1 = dao.insert(segment(rid, segmentIndex = 0, startTime = 0.0, endTime = 5.0))
        val s2 = dao.insert(segment(rid, segmentIndex = 1, startTime = 5.0, endTime = 10.0))
        assertTrue(s1 > 0)
        assertTrue(s2 > 0)

        // segment_index 升序
        assertEquals(listOf(s1, s2), dao.getByRecordingId(rid).map { it.id })

        // update
        dao.update(
            dao.getByRecordingId(rid).first { it.id == s1 }.copy(
                transcriptionStatus = RecordingStatus.PROCESSING,
            ),
        )
        assertEquals(
            RecordingStatus.PROCESSING,
            dao.getByRecordingId(rid).first { it.id == s1 }.transcriptionStatus,
        )
    }

    @Test
    fun updateTranscriptionStatus_setsStatusErrorAndCompletedAt() = runBlocking {
        val rid = db.recordingDao().insertRecording(
            RecordingEntity(audioPath = "recordings/rec_test.wav", createdAt = 1_752_000_000_000L),
        )
        val sid = dao.insert(segment(rid, segmentIndex = 0))

        // FAILED + error：写入错误，completed_at 不写
        dao.updateTranscriptionStatus(sid, RecordingStatus.FAILED, error = "网络超时")
        var seg = dao.getByRecordingId(rid).single()
        assertEquals(RecordingStatus.FAILED, seg.transcriptionStatus)
        assertEquals("网络超时", seg.transcriptionError)
        assertNull(seg.completedAt)

        // COMPLETED：completed_at 写入；error 未提供时保留既有值（对齐 PC set_segment_status）
        dao.updateTranscriptionStatus(sid, RecordingStatus.COMPLETED)
        seg = dao.getByRecordingId(rid).single()
        assertEquals(RecordingStatus.COMPLETED, seg.transcriptionStatus)
        assertEquals("网络超时", seg.transcriptionError)
        assertTrue("completed_at 应写入", seg.completedAt != null)
    }
}