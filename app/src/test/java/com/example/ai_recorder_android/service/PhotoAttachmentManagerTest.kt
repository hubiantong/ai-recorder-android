package com.example.ai_recorder_android.service

import com.example.ai_recorder_android.core.audio.RecordingAudioEngine
import com.example.ai_recorder_android.core.audio.RecordingAudioEngineFactory
import com.example.ai_recorder_android.core.audio.RecordingEngineEvents
import com.example.ai_recorder_android.data.db.AttachmentDao
import com.example.ai_recorder_android.data.db.AttachmentSource
import com.example.ai_recorder_android.data.db.AttachmentType
import com.example.ai_recorder_android.data.db.AudioSource
import com.example.ai_recorder_android.data.db.DescriptionStatus
import com.example.ai_recorder_android.data.db.RecordingAttachmentEntity
import com.example.ai_recorder_android.data.db.RecordingEntity
import com.example.ai_recorder_android.data.db.RecordingStatus
import com.example.ai_recorder_android.data.repo.RecordingStore
import com.example.ai_recorder_android.data.storage.StorageService
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * PhotoAttachmentManager 单测（纯 JVM：真实 StorageService(TemporaryFolder) +
 * 假 RecordingStore/引擎/AttachmentDao/缩略图/VLM）：
 * 保存流程（拷贝+缩略图+实体）、无活动录音/文件缺失异常、VLM 描述成功/失败/无连接器、
 * 相机输出文件创建、缩略图 locator 解析。
 */
class PhotoAttachmentManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var storage: StorageService
    private lateinit var store: FakeRecordingStore
    private lateinit var factory: FakeEngineFactory
    private lateinit var coordinator: RecordingCoordinator
    private lateinit var attachmentDao: FakeAttachmentDao
    private lateinit var manager: PhotoAttachmentManager

    @Before
    fun setUp() {
        storage = StorageService(tmp.newFolder("storage"))
        store = FakeRecordingStore()
        factory = FakeEngineFactory()
        coordinator = RecordingCoordinator(
            repository = store,
            engineFactory = factory,
            ioDispatcher = Dispatchers.Unconfined,
        )
        attachmentDao = FakeAttachmentDao()
        manager = PhotoAttachmentManager(
            storage = storage,
            coordinator = coordinator,
            attachmentDao = attachmentDao,
            thumbnailGenerator = ThumbnailGenerator { THUMB_BYTES },
        )
    }

    // ---------- 保存流程 ----------

    @Test
    fun savePhoto_copiesPhotoCreatesAttachmentAndThumbnail() = runBlocking {
        coordinator.startRecording(tmp.newFolder(), "测试", AudioSource.MIC, false)
        val photo = tmp.newFile("photo.jpg").apply { writeBytes(PHOTO_BYTES) }

        val entity = manager.savePhoto(photo, wallClockMs = System.currentTimeMillis())

        assertNotNull(entity)
        assertEquals("附件来源应为 camera", AttachmentSource.CAMERA, entity!!.source)
        assertEquals(AttachmentType.IMAGE, entity.attachmentType)
        assertEquals(DescriptionStatus.PENDING, entity.descriptionStatus)
        assertNotNull("缩略图 locator 应回填", entity.thumbnailLocator)
        assertTrue("缩略图文件应存在", storage.exists(entity.thumbnailLocator!!))
        assertTrue("缩略图内容应为生成字节", storage.readFile(entity.thumbnailLocator!!).contentEquals(THUMB_BYTES))

        val call = store.attachments.single()
        assertEquals("照片字节应传给存储层", PHOTO_BYTES.toList(), call.data.toList())
        assertEquals(AttachmentSource.CAMERA, call.source)
        assertTrue("时间戳应 >= 0（拍照时刻对齐录音开始）", call.timestamp >= 0.0)
        assertEquals("缩略图应经 DAO 回填", 1, attachmentDao.updated.size)
        assertEquals(entity.thumbnailLocator, attachmentDao.updated.single().thumbnailLocator)
    }

    @Test
    fun savePhoto_thumbnailNull_keepsEntityWithoutThumbnail() = runBlocking {
        coordinator.startRecording(tmp.newFolder())
        val photo = tmp.newFile("photo.jpg").apply { writeBytes(PHOTO_BYTES) }
        val noThumbManager = PhotoAttachmentManager(
            storage = storage,
            coordinator = coordinator,
            attachmentDao = attachmentDao,
            thumbnailGenerator = ThumbnailGenerator { null },
        )

        val entity = noThumbManager.savePhoto(photo, System.currentTimeMillis())

        assertNotNull(entity)
        assertNull("缩略图生成失败不应回填 locator", entity!!.thumbnailLocator)
        assertTrue("不应触发 DAO 更新", attachmentDao.updated.isEmpty())
    }

    @Test
    fun savePhoto_whenNoActiveRecording_throwsPhotoAttachmentException() {
        val photo = tmp.newFile("photo.jpg").apply { writeBytes(PHOTO_BYTES) }

        val ex = assertThrows(PhotoAttachmentException::class.java) {
            runBlocking { manager.savePhoto(photo, System.currentTimeMillis()) }
        }

        assertTrue(ex.message!!.contains("开始录音"))
        assertTrue("无活动录音不应落库", store.attachments.isEmpty())
    }

    @Test
    fun savePhoto_whenPhotoFileMissing_throws() = runBlocking {
        coordinator.startRecording(tmp.newFolder())

        val ex = assertThrows(PhotoAttachmentException::class.java) {
            runBlocking { manager.savePhoto(File(tmp.root, "missing.jpg"), System.currentTimeMillis()) }
        }

        assertTrue(ex.message!!.contains("不存在"))
    }

    // ---------- VLM 描述 ----------

    @Test
    fun describeAttachment_success_marksCompletedAndSavesDescription() = runBlocking {
        coordinator.startRecording(tmp.newFolder())
        val photo = tmp.newFile("photo.jpg").apply { writeBytes(PHOTO_BYTES) }
        val entity = manager.savePhoto(photo, System.currentTimeMillis())!!
        val vlmManager = PhotoAttachmentManager(
            storage = storage,
            coordinator = coordinator,
            attachmentDao = attachmentDao,
            thumbnailGenerator = ThumbnailGenerator { THUMB_BYTES },
            vlDescriber = VlmDescriber { "一张会议白板照片" },
        )

        val updated = vlmManager.describeAttachment(entity)

        assertEquals(DescriptionStatus.COMPLETED, updated.descriptionStatus)
        assertEquals("一张会议白板照片", updated.description)
        assertEquals("描述应落库", "一张会议白板照片", attachmentDao.updated.last().description)
    }

    @Test
    fun describeAttachment_failure_marksFailed() = runBlocking {
        coordinator.startRecording(tmp.newFolder())
        val photo = tmp.newFile("photo.jpg").apply { writeBytes(PHOTO_BYTES) }
        val entity = manager.savePhoto(photo, System.currentTimeMillis())!!
        val vlmManager = PhotoAttachmentManager(
            storage = storage,
            coordinator = coordinator,
            attachmentDao = attachmentDao,
            thumbnailGenerator = ThumbnailGenerator { THUMB_BYTES },
            vlDescriber = VlmDescriber { throw RuntimeException("VLM 超时") },
        )

        val updated = vlmManager.describeAttachment(entity)

        assertEquals("VLM 失败应置 FAILED 而非抛异常", DescriptionStatus.FAILED, updated.descriptionStatus)
        assertNull(updated.description)
    }

    @Test
    fun describeAttachment_noVlm_marksFailed() = runBlocking {
        coordinator.startRecording(tmp.newFolder())
        val photo = tmp.newFile("photo.jpg").apply { writeBytes(PHOTO_BYTES) }
        val entity = manager.savePhoto(photo, System.currentTimeMillis())!!

        val updated = manager.describeAttachment(entity)

        assertEquals("无 VLM 连接器应置 FAILED", DescriptionStatus.FAILED, updated.descriptionStatus)
    }

    // ---------- 相机文件 / 缩略图解析 ----------

    @Test
    fun createCameraOutputFile_createsJpgInCameraDir() {
        val cacheDir = tmp.newFolder("cache")

        val file = manager.createCameraOutputFile(cacheDir)

        assertTrue("输出文件应在 cacheDir/camera/ 下", file.parentFile.path.startsWith(File(cacheDir, "camera").path))
        assertTrue(file.name.endsWith(".jpg"))
        assertTrue(file.name.startsWith("photo_"))
    }

    @Test
    fun thumbnailFile_resolvesLocator() = runBlocking {
        coordinator.startRecording(tmp.newFolder())
        val photo = tmp.newFile("photo.jpg").apply { writeBytes(PHOTO_BYTES) }
        val entity = manager.savePhoto(photo, System.currentTimeMillis())!!

        val thumb = manager.thumbnailFile(entity)

        assertNotNull(thumb)
        assertTrue(thumb!!.isFile)
        assertTrue(thumb.path.startsWith(storage.resolvePath("attachments").path))
    }

    // ---------- 假实现 ----------

    private class FakeRecordingStore : RecordingStore {
        val created = mutableListOf<RecordingEntity>()
        val attachments = mutableListOf<AttachmentCall>()
        var nextId = 1L

        data class AttachmentCall(
            val recordingId: Long,
            val fileName: String,
            val data: ByteArray,
            val timestamp: Double,
            val source: AttachmentSource,
        )

        override suspend fun create(
            title: String?,
            audioSource: AudioSource?,
            meetingMode: Boolean,
        ): RecordingEntity {
            val entity = RecordingEntity(
                id = nextId++,
                title = title ?: "录音 测试",
                audioPath = "",
                status = RecordingStatus.PENDING,
                audioSource = audioSource,
                meetingMode = meetingMode,
                createdAt = System.currentTimeMillis(),
            )
            created += entity
            return entity
        }

        override suspend fun start(recordingId: Long): RecordingEntity? =
            created.firstOrNull { it.id == recordingId }

        override suspend fun stop(recordingId: Long, durationSeconds: Double): RecordingEntity? =
            created.firstOrNull { it.id == recordingId }

        override suspend fun store(recordingId: Long, fileName: String, data: ByteArray): String? =
            "recordings/rec_$recordingId.wav"

        override suspend fun delete(recordingId: Long) = Unit

        override suspend fun finalizeSegmented(recordingId: Long, totalDuration: Double): String? =
            "recordings/rec_${recordingId}_merged.wav"

        override suspend fun addSegment(
            recordingId: Long,
            segmentIndex: Int,
            data: ByteArray,
            startTime: Double,
            endTime: Double,
            duration: Double,
        ): Long? = 1L

        override suspend fun addAttachment(
            recordingId: Long,
            fileName: String,
            data: ByteArray,
            timestamp: Double,
            source: AttachmentSource,
            attachmentType: AttachmentType,
        ): RecordingAttachmentEntity? {
            attachments += AttachmentCall(recordingId, fileName, data, timestamp, source)
            return RecordingAttachmentEntity(
                id = 1L,
                recordingId = recordingId,
                attachmentType = attachmentType,
                storageLocator = "attachments/att_test.jpg",
                timestamp = timestamp,
                descriptionStatus = DescriptionStatus.PENDING,
                source = source,
                createdAt = System.currentTimeMillis(),
            )
        }
    }

    private class FakeAttachmentDao : AttachmentDao {
        val updated = mutableListOf<RecordingAttachmentEntity>()

        override suspend fun insert(attachment: RecordingAttachmentEntity): Long = 1L

        override suspend fun update(attachment: RecordingAttachmentEntity) {
            updated += attachment
        }

        override suspend fun getByRecordingId(recordingId: Long): List<RecordingAttachmentEntity> = emptyList()

        override suspend fun deleteById(id: Long) = Unit

        override suspend fun updateFusedChunkId(attachmentId: Long, fusedChunkId: Long?) = Unit
    }

    private class FakeEngineFactory : RecordingAudioEngineFactory {
        override fun create(recordingId: Long, outputDir: File, events: RecordingEngineEvents): RecordingAudioEngine =
            FakeEngine()
    }

    private class FakeEngine : RecordingAudioEngine {
        override val isRecording: Boolean get() = true
        override val isPaused: Boolean get() = false
        override val pausedDurationMs: Long get() = 0L
        override fun elapsedMs(): Long = 0L
        override fun start() = Unit
        override fun pause() = Unit
        override fun resume() = Unit
        override fun stop() = Unit
    }

    companion object {
        private val PHOTO_BYTES = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        private val THUMB_BYTES = byteArrayOf(0x10, 0x20, 0x30)
    }
}