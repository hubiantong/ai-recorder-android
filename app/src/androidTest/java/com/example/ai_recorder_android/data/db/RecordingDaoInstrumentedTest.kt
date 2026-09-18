package com.example.ai_recorder_android.data.db

import android.Manifest
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * RecordingDao instrumented 测试 —— **真机/模拟器验证**（Metis V4.4）。
 *
 * 本机 Gradle worker 编码 bug（learnings.md），此测试不在本机运行；
 * 在其他机器执行 `./gradlew :app:connectedDebugAndroidTest` 验证。
 *
 * 覆盖：真实 SQLite 上的 CRUD 往返、save_transcription 替换语义、
 * save_segment_transcription 续编 + 时间偏移 + 文本累加、级联删除。
 *
 * **权限说明**：任务书要求 GrantPermissionRule 处理 RECORD_AUDIO，但依赖清单
 * 无 `androidx.test:rules`（espresso-core 3.5.1 不传递该 artifact，已核实 gradle 缓存），
 * 且禁止改 build.gradle.kts → 沿用 RecordingServiceInstrumentedTest 的既有模式：
 * `UiAutomation.grantRuntimePermission`（androidx.test:monitor 传递依赖）。
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class RecordingDaoInstrumentedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var db: AppDatabase
    private lateinit var dao: RecordingDao
    private lateinit var segmentDao: SegmentDao
    private lateinit var attachmentDao: AttachmentDao

    @Before
    fun grantPermissions() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pkg = context.packageName
        instrumentation.uiAutomation.grantRuntimePermission(pkg, Manifest.permission.RECORD_AUDIO)
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
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

    @Test
    fun crud_roundTrip_onDevice() = runBlocking {
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

    @Test
    fun saveTranscription_replacesOldChunksAndUpdatesRecording_onDevice() = runBlocking {
        val id = dao.insertRecording(recording(createdAt = 1_752_000_000_000L))

        dao.insertChunk(chunk(id, 0, "旧一"))
        dao.insertChunk(chunk(id, 1, "旧二"))
        assertEquals(2, dao.getChunksForRecording(id).size)

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
        assertEquals(3, chunks.size)
        assertEquals(listOf(0, 1, 2), chunks.map { it.chunkIndex })
        assertEquals(listOf("新一", "新二", "新三"), chunks.map { it.content })

        val rec = dao.getRecordingById(id)
        assertEquals("新一 新二 新三", rec?.transcription)
        assertEquals(RecordingStatus.COMPLETED, rec?.status)
        assertEquals(3.0, rec?.audioDurationSeconds)
        assertTrue("completed_at 应被写入", rec?.completedAt != null)
    }

    @Test
    fun saveSegmentTranscription_continuesIndexWithOffsetAndAppends_onDevice() = runBlocking {
        val id = dao.insertRecording(recording(createdAt = 1_752_000_000_000L))

        dao.insertChunk(chunk(id, 0, "第一句"))
        dao.insertChunk(chunk(id, 1, "第二句"))
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
        assertEquals(listOf(0, 1, 2, 3), chunks.map { it.chunkIndex })
        assertEquals(10.5, chunks[2].startTime!!, 1e-9)
        assertEquals(12.0, chunks[2].endTime!!, 1e-9)
        assertEquals("段内第一句段内第二句", dao.getRecordingById(id)?.transcription)

        val seg = dao.getSegmentsForRecording(id).single()
        assertEquals(RecordingStatus.COMPLETED, seg.transcriptionStatus)
        assertTrue(seg.completedAt != null)
    }

    @Test
    fun deleteRecording_cascadesToChildren_onDevice() = runBlocking {
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