package com.example.ai_recorder_android.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import com.example.ai_recorder_android.App
import com.example.ai_recorder_android.cloud.vlm.QwenVlConnector
import com.example.ai_recorder_android.data.db.AppDatabase
import com.example.ai_recorder_android.data.db.AttachmentDao
import com.example.ai_recorder_android.data.db.AttachmentSource
import com.example.ai_recorder_android.data.db.AttachmentType
import com.example.ai_recorder_android.data.db.DescriptionStatus
import com.example.ai_recorder_android.data.db.RecordingAttachmentEntity
import com.example.ai_recorder_android.data.storage.StorageService
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

/**
 * 拍照附件管理器（todo 23）：系统相机 Intent → 照片拷贝到 `filesDir/attachments/` →
 * 缩略图（BitmapFactory 采样）→ 时间轴对齐（[RecordingCoordinator.addAttachment]，
 * 对齐 PC `photo_timestamp()`）→ `RecordingAttachment(source=camera)` → VLM 描述（todo 15）。
 *
 * **职责边界**：
 * - 相机侧：创建输出临时文件（cacheDir/camera/）+ FileProvider URI（供
 *   `ActivityResultContracts.TakePicture()` 写入，相机应用需 URI 授权）；
 * - 保存侧：读照片字节 → [RecordingCoordinator.addAttachment]（内部做
 *   `photo_wall_time - recording_start_wall_time - 暂停` 对齐，Metis P6.8）→
 *   生成缩略图存 `attachments/att_{uuid}.jpg` → 回填 thumbnailLocator；
 * - VLM 侧：[describeAttachment] 调 [QwenVlConnector.describeImage] 生成描述，
 *   成功置 COMPLETED、失败置 FAILED（对齐 PC `set_attachment_status`），永不抛异常。
 *
 * 依赖注入（纯 JVM 可测）：[ThumbnailGenerator] / [VlmDescriber] 可注入假实现；
 * 生产装配见 [fromContext]。
 */
class PhotoAttachmentManager(
    private val storage: StorageService,
    private val coordinator: RecordingCoordinator,
    private val attachmentDao: AttachmentDao,
    private val thumbnailGenerator: ThumbnailGenerator = BitmapThumbnailGenerator(),
    private val vlDescriber: VlmDescriber? = null,
) {

    /**
     * 创建相机输出临时文件（`cacheDir/camera/photo_{uuid}.jpg`）。
     * 相机应用写入该文件，成功后由调用方 [savePhoto] 拷贝到附件目录并删除临时文件。
     */
    fun createCameraOutputFile(cacheDir: File): File {
        val dir = File(cacheDir, CAMERA_DIR).apply { mkdirs() }
        return File(dir, "photo_${UUID.randomUUID()}.jpg")
    }

    /** FileProvider URI（authority = `${packageName}.fileprovider`，见 Manifest provider）。 */
    fun cameraOutputUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /**
     * 保存照片附件（对齐 PC `add_attachment`）：
     * 1. 照片字节经 [RecordingCoordinator.addAttachment] 落库（时间戳 = 拍照墙钟时刻对齐后的录音时间轴秒数）；
     * 2. 生成缩略图存 `attachments/att_{uuid}.jpg`，回填 [RecordingAttachmentEntity.thumbnailLocator]。
     *
     * @param photoFile 相机输出的临时照片文件（成功后由调用方删除）
     * @param wallClockMs 拍照墙钟时刻（毫秒，`System.currentTimeMillis()` 语义）
     * @return 附件实体（含缩略图 locator）；录音不存在返回 null
     * @throws PhotoAttachmentException 照片文件缺失 / 无活动录音
     */
    suspend fun savePhoto(photoFile: File, wallClockMs: Long): RecordingAttachmentEntity? {
        if (!photoFile.isFile) throw PhotoAttachmentException("照片文件不存在: ${photoFile.path}")
        val bytes = photoFile.readBytes()
        val entity = try {
            coordinator.addAttachment(
                photoWallClockMs = wallClockMs,
                fileName = photoFile.name,
                data = bytes,
                source = AttachmentSource.CAMERA,
                attachmentType = AttachmentType.IMAGE,
            )
        } catch (e: IllegalStateException) {
            throw PhotoAttachmentException("请先开始录音再拍照", e)
        } ?: return null

        val thumb = thumbnailGenerator.generate(photoFile) ?: return entity
        val thumbLocator = storage.saveAttachment("thumb.jpg", thumb)
        val updated = entity.copy(thumbnailLocator = thumbLocator)
        attachmentDao.update(updated)
        return updated
    }

    /**
     * 生成附件 VLM 描述（对齐 PC `save_attachment_description` + `set_attachment_status`）：
     * 成功 → description 落库 + COMPLETED；失败/无 VLM 连接器 → FAILED。永不抛异常。
     */
    suspend fun describeAttachment(attachment: RecordingAttachmentEntity): RecordingAttachmentEntity {
        val describer = vlDescriber
        if (describer == null) {
            return markDescription(attachment, DescriptionStatus.FAILED, null)
        }
        return try {
            val imageFile = storage.resolvePath(attachment.storageLocator)
            val description = describer.describe(imageFile)
            markDescription(attachment, DescriptionStatus.COMPLETED, description)
        } catch (e: Exception) {
            markDescription(attachment, DescriptionStatus.FAILED, null)
        }
    }

    /** 解析附件缩略图文件（UI 预览用）；无缩略图返回 null。 */
    fun thumbnailFile(attachment: RecordingAttachmentEntity): File? =
        attachment.thumbnailLocator?.let { storage.resolvePath(it) }

    private suspend fun markDescription(
        attachment: RecordingAttachmentEntity,
        status: DescriptionStatus,
        description: String?,
    ): RecordingAttachmentEntity {
        val updated = attachment.copy(description = description, descriptionStatus = status)
        attachmentDao.update(updated)
        return updated
    }

    companion object {
        /** 相机临时输出目录名（cacheDir 下）。 */
        const val CAMERA_DIR = "camera"

        /** 缩略图 JPEG 压缩质量（0~100）。 */
        const val THUMBNAIL_QUALITY = 85

        /** 缩略图采样率（BitmapFactory inSampleSize，内存效率，任务书指定 4）。 */
        const val THUMBNAIL_IN_SAMPLE_SIZE = 4

        /**
         * 生产装配：AppDatabase 单例 + StorageService(filesDir) + 复用 App 共享
         * [RecordingCoordinator] 单例（与 RecordingService / RecordingViewModel 同一实例，
         * 保证录音中拍照命中活动会话）。
         * DASHSCOPE_API_KEY 未配置时 VLM 连接器构造失败 → vlDescriber 为 null，
         * 描述标记 FAILED 而非崩溃（对齐无 key 回退语义）。
         */
        fun fromContext(context: Context): PhotoAttachmentManager {
            val appContext = context.applicationContext
            val db = AppDatabase.getInstance(appContext)
            val storage = StorageService.fromContext(appContext)
            val coordinator = (appContext as App).recordingCoordinator
            val vlDescriber = try {
                val connector = QwenVlConnector()
                VlmDescriber { file -> connector.describeImage(imagePath = file).description }
            } catch (e: IllegalArgumentException) {
                null
            }
            return PhotoAttachmentManager(
                storage = storage,
                coordinator = coordinator,
                attachmentDao = db.attachmentDao(),
                vlDescriber = vlDescriber,
            )
        }
    }
}

/** 拍照附件流程错误（用户可见消息）。 */
class PhotoAttachmentException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 缩略图生成器（可注入假实现，纯 JVM 单测）。 */
fun interface ThumbnailGenerator {
    /** 生成 JPEG 缩略图字节；解码失败返回 null（不抛异常）。 */
    fun generate(imageFile: File): ByteArray?
}

/** BitmapFactory 两遍采样缩略图：先读 bounds，再按最长边 ≤ [BitmapThumbnailGenerator.MAX_LONGEST_EDGE_PX] 计算 inSampleSize（下限为任务书指定的 4）。 */
class BitmapThumbnailGenerator(
    private val inSampleSize: Int = PhotoAttachmentManager.THUMBNAIL_IN_SAMPLE_SIZE,
) : ThumbnailGenerator {

    override fun generate(imageFile: File): ByteArray? {
        val path = imageFile.absolutePath
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply {
            this.inSampleSize = resolveInSampleSize(bounds.outWidth, bounds.outHeight)
        }
        val bitmap = BitmapFactory.decodeFile(path, options) ?: return null
        return try {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, PhotoAttachmentManager.THUMBNAIL_QUALITY, out)
            out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    /** 取注入下限与「最长边压到 MAX_LONGEST_EDGE_PX 以内所需 2 的幂」的较大者，防超大图 OOM。 */
    private fun resolveInSampleSize(width: Int, height: Int): Int {
        var sample = inSampleSize
        var longest = maxOf(width, height)
        while (longest / sample > MAX_LONGEST_EDGE_PX) {
            sample = sample shl 1
        }
        return sample
    }

    companion object {
        /** 解码后位图最长边上限（VLM 输入与时间轴缩略图均足够）。 */
        const val MAX_LONGEST_EDGE_PX = 2048
    }
}

/** VLM 图片描述器（可注入假实现；生产装配包装 [QwenVlConnector.describeImage]）。 */
fun interface VlmDescriber {
    /** 生成图片描述文本；失败抛异常（由 [PhotoAttachmentManager.describeAttachment] 捕获置 FAILED）。 */
    suspend fun describe(imageFile: File): String
}