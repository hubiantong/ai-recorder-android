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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * AttachmentDao 单测（in-memory Room）：insert/update/getByRecordingId/deleteById/updateFusedChunkId。
 */
@RunWith(RobolectricTestRunner::class)
class AttachmentDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: AttachmentDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        dao = db.attachmentDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun attachment(
        recordingId: Long,
        timestamp: Double,
        locator: String = "attachments/att_test.jpg",
    ) = RecordingAttachmentEntity(
        recordingId = recordingId,
        attachmentType = AttachmentType.IMAGE,
        storageLocator = locator,
        timestamp = timestamp,
        descriptionStatus = DescriptionStatus.PENDING,
        source = AttachmentSource.CAMERA,
        createdAt = 1_752_000_000_000L,
    )

    @Test
    fun insertUpdateGetByRecordingIdDelete_crud() = runBlocking {
        val rid = db.recordingDao().insertRecording(
            RecordingEntity(audioPath = "recordings/rec_test.wav", createdAt = 1_752_000_000_000L),
        )
        val a1 = dao.insert(attachment(rid, timestamp = 5.0, locator = "attachments/a1.jpg"))
        val a2 = dao.insert(attachment(rid, timestamp = 1.0, locator = "attachments/a2.jpg"))
        assertTrue(a1 > 0)
        assertTrue(a2 > 0)

        // timestamp 升序
        assertEquals(listOf(a2, a1), dao.getByRecordingId(rid).map { it.id })

        // update
        dao.update(
            dao.getByRecordingId(rid).first { it.id == a2 }.copy(
                description = "一张会议白板照片",
                descriptionStatus = DescriptionStatus.COMPLETED,
            ),
        )
        val updated = dao.getByRecordingId(rid).first { it.id == a2 }
        assertEquals("一张会议白板照片", updated.description)
        assertEquals(DescriptionStatus.COMPLETED, updated.descriptionStatus)

        // updateFusedChunkId：置值（fused_chunk_id 外键 → transcript_chunks.id，须为真实 chunk）
        // 再置 null 解除关联
        val chunkId = db.recordingDao().insertChunk(
            TranscriptChunkEntity(
                recordingId = rid,
                chunkIndex = 0,
                content = "被关联的 chunk",
                createdAt = 1_752_000_000_000L,
            ),
        )
        dao.updateFusedChunkId(a2, chunkId)
        assertEquals(chunkId, dao.getByRecordingId(rid).first { it.id == a2 }.fusedChunkId)
        dao.updateFusedChunkId(a2, null)
        assertNull(dao.getByRecordingId(rid).first { it.id == a2 }.fusedChunkId)

        // deleteById
        dao.deleteById(a1)
        assertEquals(1, dao.getByRecordingId(rid).size)
        assertEquals(listOf(a2), dao.getByRecordingId(rid).map { it.id })
    }
}