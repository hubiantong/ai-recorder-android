package com.example.ai_recorder_android.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * RecordingDao 单测（in-memory Room）：
 * CRUD、created_at 倒序、按天分组、搜索、save_transcription 替换语义、
 * save_segment_transcription 的 MAX(chunk_index)+1 续编 + 时间偏移 + 文本累加、级联删除。
 */
class RecordingDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: RecordingDao
    private lateinit var segmentDao: SegmentDao
    private lateinit var attachmentDao: AttachmentDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        dao = db.recordingDao()
        segmentDao = db.segmentDao()
        attachmentDao = db.attachmentDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun recording(
        createdAt: Long,
        title: String = "测试录音",
        audioPath: String = "recordings/rec_test.wav",
    ) = RecordingEntity(
        title = title,
        audioPath = audioPath,
        status = RecordingStatus.PENDING,
        audioSource = AudioSource.MIC,
        meetingMode = false,
        createdAt = createdAt,
    )

    private fun chunk(
        recordingId: Long,
        index: Int,
        content: String,
        startTime: Double? = null,
        endTime: Double? = null,
    ) = TranscriptChunkEntity(
        recordingId = recordingId,
        chunkIndex = index,
        content = content,
        startTime = startTime,
        endTime = endTime,
        createdAt = 1_752_000_000_000L,
    )

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

    private fun attachment(recordingId: Long, timestamp: Double) = RecordingAttachmentEntity(
        recordingId = recordingId,
        attachmentType = AttachmentType.IMAGE,
        storageLocator = "attachments/att_test.jpg",
        timestamp = timestamp,
        descriptionStatus = DescriptionStatus.PENDING,
        source = AttachmentSource.CAMERA,
        createdAt = 1_752_000_000_000L,
    )

    // ---------- CRUD ----------

    @Test
    fun insertUpdateGetDelete_crud() = runBlocking {
        val id = dao.insertRecording(recording(createdAt = 1_752_000_000_000L))
        assertTrue("插入应返回正 id", id > 0)

        val loaded = dao.getRecordingById(id)
        assertEquals("测试录音", loaded?.title)
        assertEquals(RecordingStatus.PENDING, loaded?.status)
        assertEquals(AudioSource.MIC, loaded?.audioSource)

        dao.updateRecording(loaded!!.copy(title = "改名后", status = RecordingStatus.COMPLETED))
        val updated = dao.getRecordingById(id)
        assertEquals("改名后", updated?.title)
        assertEquals(RecordingStatus.COMPLETED, updated?.status)

        dao.deleteById(id)
        assertNull("删除后应查不到", dao.getRecordingById(id))
    }

    // ---------- 列表倒序 ----------

    @Test
    fun getAllRecordings_orderedByCreatedAtDesc() = runBlocking {
        val older = dao.insertRecording(recording(createdAt = 1_000L, title = "最早"))
        val middle = dao.insertRecording(recording(createdAt = 2_000L, title = "中间"))
        val newest = dao.insertRecording(recording(createdAt = 3_000L, title = "最新"))

        assertEquals(listOf(newest, middle, older), dao.getAllRecordings().map { it.id })
    }

    // ---------- 搜索 ----------

    @Test
    fun searchRecordings_matchesTitleAndTranscription() = runBlocking {
        dao.insertRecording(recording(createdAt = 1_000L, title = "产品会议纪要"))
        dao.insertRecording(
            recording(createdAt = 2_000L, title = "周报", audioPath = "recordings/rec2.wav")
                .copy(transcription = "讨论了预算与排期"),
        )

        assertEquals(1, dao.searchRecordings("会议").size)
        assertEquals(1, dao.searchRecordings("预算").size)
        assertTrue(dao.searchRecordings("不存在的词").isEmpty())
        // 空白查询返回全部（对齐 PC search_recordings）
        assertEquals(2, dao.searchRecordings("").size)
        assertEquals(2, dao.searchRecordings("   ").size)
    }

    // ---------- 按天分组 ----------

    @Test
    fun getRecordingsGroupedByDay_groupsByLocalDate() = runBlocking {
        val zone = ZoneId.of("Asia/Shanghai")
        // 2026-08-15T10:00 / 20:00 +08:00 与 2026-08-14T10:00 +08:00
        val aug15Morning = 1_786_845_600_000L
        val aug15Evening = 1_786_881_600_000L
        val aug14Morning = 1_786_759_200_000L

        dao.insertRecording(recording(createdAt = aug15Evening, title = "晚间"))
        dao.insertRecording(recording(createdAt = aug14Morning, title = "昨日"))
        dao.insertRecording(recording(createdAt = aug15Morning, title = "晨间"))

        val grouped = dao.getRecordingsGroupedByDay(zone)
        assertEquals(2, grouped.size)
        assertEquals("2026-08-15", grouped[0].day)
        assertEquals(listOf("晚间", "晨间"), grouped[0].recordings.map { it.title })
        assertEquals("2026-08-14", grouped[1].day)
        assertEquals(listOf("昨日"), grouped[1].recordings.map { it.title })
    }

    // ---------- save_transcription 语义 ----------

    @Test
    fun saveTranscription_replacesOldChunksAndUpdatesRecording() = runBlocking {
        val id = dao.insertRecording(recording(createdAt = 1_752_000_000_000L))

        // 先直接插入 2 个旧 chunk
        dao.insertChunk(chunk(id, 0, "旧一"))
        dao.insertChunk(chunk(id, 1, "旧二"))
        assertEquals(2, dao.getChunksForRecording(id).size)

        // saveTranscription 替换为 3 个新 chunk（传入 chunk_index 故意乱序，应被重排为 0..2）
        val added = dao.saveTranscription(
            recordingId = id,
            transcriptionText = "新一 新二 新三",
            chunks = listOf(
                chunk(id, 99, "新一", startTime = 0.0, endTime = 1.0),
                chunk(id, 99, "新二", startTime = 1.0, endTime = 2.0),
                chunk(id, 99, "新三", startTime = 2.0, endTime = 3.0),
            ),
            durationSeconds = 3.0,
        )

        assertEquals(3, added)
        val chunks = dao.getChunksForRecording(id)
        assertEquals("旧 chunks 应被清空，只剩 3 个新 chunk", 3, chunks.size)
        assertEquals(listOf(0, 1, 2), chunks.map { it.chunkIndex })
        assertEquals(listOf("新一", "新二", "新三"), chunks.map { it.content })

        val rec = dao.getRecordingById(id)
        assertEquals("新一 新二 新三", rec?.transcription)
        assertEquals(RecordingStatus.COMPLETED, rec?.status)
        assertEquals(3.0, rec?.audioDurationSeconds)
        assertTrue("completed_at 应被写入", rec?.completedAt != null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun saveTranscription_recordingNotFound_throws() = runBlocking {
        dao.saveTranscription(recordingId = 999L, transcriptionText = "x", chunks = emptyList())
    }

    // ---------- save_segment_transcription 语义 ----------

    @Test
    fun saveSegmentTranscription_continuesChunkIndexWithOffsetAndAppendsText() = runBlocking {
        val id = dao.insertRecording(recording(createdAt = 1_752_000_000_000L))

        // 已有 2 个 chunk（index 0,1）
        dao.insertChunk(chunk(id, 0, "第一句"))
        dao.insertChunk(chunk(id, 1, "第二句"))

        // 段 0：start_time=10.0（相对录音）
        segmentDao.insert(segment(id, segmentIndex = 0, startTime = 10.0, endTime = 15.0))

        val added = dao.saveSegmentTranscription(
            recordingId = id,
            segmentIndex = 0,
            chunks = listOf(
                chunk(id, 0, "段内第一句", startTime = 0.5, endTime = 2.0),
                chunk(id, 0, "段内第二句", startTime = 2.0, endTime = 3.5),
            ),
        )

        assertEquals(2, added)
        val chunks = dao.getChunksForRecording(id)
        assertEquals(4, chunks.size)
        // MAX(chunk_index)+1 全局续编：新 chunk 从 2 开始
        assertEquals(listOf(0, 1, 2, 3), chunks.map { it.chunkIndex })
        // 时间偏移 +seg.start_time(10.0)
        assertEquals(10.5, chunks[2].startTime!!, 1e-9)
        assertEquals(12.0, chunks[2].endTime!!, 1e-9)
        assertEquals(12.0, chunks[3].startTime!!, 1e-9)
        assertEquals(13.5, chunks[3].endTime!!, 1e-9)

        // 文本累加
        assertEquals("段内第一句段内第二句", dao.getRecordingById(id)?.transcription)

        // 段状态 COMPLETED + completed_at
        val seg = dao.getSegmentsForRecording(id).single()
        assertEquals(RecordingStatus.COMPLETED, seg.transcriptionStatus)
        assertTrue(seg.completedAt != null)
    }

    @Test
    fun saveSegmentTranscription_skipsBlankChunks() = runBlocking {
        val id = dao.insertRecording(recording(createdAt = 1_752_000_000_000L))
        segmentDao.insert(segment(id, segmentIndex = 0, startTime = 0.0, endTime = 1.0))

        val added = dao.saveSegmentTranscription(
            recordingId = id,
            segmentIndex = 0,
            chunks = listOf(
                chunk(id, 0, "有效内容"),
                chunk(id, 0, "   "),
                chunk(id, 0, ""),
            ),
        )

        assertEquals("空白 chunk 应被跳过", 1, added)
        val chunks = dao.getChunksForRecording(id)
        assertEquals(1, chunks.size)
        assertEquals("有效内容", chunks[0].content)
        assertEquals(0, chunks[0].chunkIndex)
        assertEquals("有效内容", dao.getRecordingById(id)?.transcription)
    }

    @Test(expected = IllegalArgumentException::class)
    fun saveSegmentTranscription_segmentNotFound_throws() = runBlocking {
        val id = dao.insertRecording(recording(createdAt = 1_752_000_000_000L))
        dao.saveSegmentTranscription(recordingId = id, segmentIndex = 5, chunks = listOf(chunk(id, 0, "x")))
    }

    // ---------- 级联删除 ----------

    @Test
    fun deleteRecording_cascadesToChunksSegmentsAttachments() = runBlocking {
        val id = dao.insertRecording(recording(createdAt = 1_752_000_000_000L))
        dao.insertChunk(chunk(id, 0, "内容"))
        segmentDao.insert(segment(id, segmentIndex = 0))
        attachmentDao.insert(attachment(id, timestamp = 1.0))

        assertEquals(1, dao.getChunksForRecording(id).size)
        assertEquals(1, dao.getSegmentsForRecording(id).size)
        assertEquals(1, dao.getAttachmentsForRecording(id).size)

        dao.deleteById(id)

        assertNull(dao.getRecordingById(id))
        assertTrue("chunks 应级联删除", dao.getChunksForRecording(id).isEmpty())
        assertTrue("segments 应级联删除", dao.getSegmentsForRecording(id).isEmpty())
        assertTrue("attachments 应级联删除", dao.getAttachmentsForRecording(id).isEmpty())
    }
}