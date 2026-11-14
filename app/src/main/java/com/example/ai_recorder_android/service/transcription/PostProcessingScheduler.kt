package com.example.ai_recorder_android.service.transcription

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.ai_recorder_android.cloud.llm.DeepSeekConnector
import com.example.ai_recorder_android.data.db.AppDatabase
import com.example.ai_recorder_android.data.db.RecordingEntity
import com.example.ai_recorder_android.data.repo.RecordingRepository
import com.example.ai_recorder_android.data.repo.UserConfigRepository
import com.example.ai_recorder_android.data.storage.StorageService
import java.io.File
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * 录音后处理工作流（P0）：停止录音后按固定顺序执行
 * **文件转写 → 自动标题 → 智能笔记**。
 *
 * **可靠性保证**：
 * - 全部步骤由 WorkManager 调度，WorkSpec 持久化在 WorkManager 数据库中，
 *   进程被杀后由系统自动恢复执行（含重试）；
 * - 每个 Worker 使用唯一 work name（按 recordingId），`ExistingWorkPolicy.KEEP`
 *   避免服务与启动恢复扫描重复投递；
 * - 转写失败指数退避重试（最多 [TranscriptionWorker.MAX_ATTEMPTS] 次），
 *   网络约束满足后才执行，避免无网时白白消耗重试次数；
 * - `App` 启动时调用 [PostProcessingScheduler.resumePending] 补投「已停止但无 AI 笔记」
 *   的录音，覆盖进程在 `enqueue` 之前被杀的窗口。
 *
 * 顺序约束：后一步依赖前一步落库结果（标题读 transcription、笔记读 chunks+title），
 * 因此用 `beginUniqueWork(...).then(...).then(...)` 串成严格顺序链。
 */
object PostProcessingScheduler {

    /** Worker 输入键：录音 id。 */
    const val KEY_RECORDING_ID = "recording_id"

    private const val UNIQUE_WORK_PREFIX = "post_process_recording_"
    private const val BACKOFF_SECONDS = 30L

    /** 唯一工作名：同一录音的整条后处理链共用（避免重复投递）。 */
    fun uniqueWorkName(recordingId: Long): String = "$UNIQUE_WORK_PREFIX$recordingId"

    /**
     * 投递（或复用）某录音的完整后处理链。录音 id 非法时静默跳过。
     *
     * 调用时机：
     * - [com.example.ai_recorder_android.service.RecordingService] 停止录音并落库后；
     * - `App` 启动恢复扫描（[resumePending]）。
     */
    fun enqueue(context: Context, recordingId: Long) {
        if (recordingId <= 0) return
        val workManager = WorkManager.getInstance(context.applicationContext)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val transcription = request<TranscriptionWorker>(recordingId, constraints)
        val title = request<AutoTitleTaskWorker>(recordingId, constraints)
        val note = request<NoteFusionTaskWorker>(recordingId, constraints)

        workManager
            .beginUniqueWork(uniqueWorkName(recordingId), ExistingWorkPolicy.KEEP, transcription)
            .then(title)
            .then(note)
            .enqueue()
    }

    /**
     * 进程启动恢复：把「录音已停止（COMPLETED）但尚无 AI 笔记（summary 为空）」的录音
     * 重新投递后处理链。在途任务由 WorkManager 自行恢复，这里只兜底未投递的窗口。
     *
     * 失败/已标记 FAILED 的录音不在扫描范围内，避免每次启动重复消耗云端调用。
     */
    suspend fun resumePending(context: Context) {
        val appContext = context.applicationContext
        val pending = AppDatabase.getInstance(appContext)
            .recordingDao()
            .getRecordingsAwaitingPostProcessing()
        pending.forEach { recording ->
            Log.i(TAG, "恢复未完成的后处理任务 recordingId=${recording.id}")
            enqueue(appContext, recording.id)
        }
    }

    /**
     * 重新生成标题与笔记（详情页「重新生成」入口）：跳过转写步骤，
     * 直接串联 AutoTitleTaskWorker → NoteFusionTaskWorker。
     *
     * - 复用整条链的 unique 工作名（[uniqueWorkName]）+ [ExistingWorkPolicy.REPLACE]：
     *   在途的旧链被取消替换，且 App 启动恢复扫描天然去重；
     * - 转写缺失（transcription == null）时不投递（Worker 依赖转写结果），返回 false。
     *
     * @return 是否成功投递
     */
    suspend fun reenqueueNoteGeneration(context: Context, recordingId: Long): Boolean {
        if (recordingId <= 0) return false
        val appContext = context.applicationContext
        val recording = AppDatabase.getInstance(appContext).recordingDao().getRecordingById(recordingId)
        if (recording?.transcription == null) {
            Log.w(TAG, "重新生成笔记：转写缺失，跳过 recordingId=$recordingId")
            return false
        }
        val workManager = WorkManager.getInstance(appContext)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val title = request<AutoTitleTaskWorker>(recordingId, constraints)
        val note = request<NoteFusionTaskWorker>(recordingId, constraints)
        workManager
            .beginUniqueWork(uniqueWorkName(recordingId), ExistingWorkPolicy.REPLACE, title)
            .then(note)
            .enqueue()
        Log.i(TAG, "已投递重新生成笔记链 recordingId=$recordingId")
        return true
    }

    private inline fun <reified W : ListenableWorker> request(
        recordingId: Long,
        constraints: Constraints,
    ): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<W>()
            .setInputData(workDataOf(KEY_RECORDING_ID to recordingId))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(BACKOFF_SECONDS))
            .build()

    private const val TAG = "PostProcessingScheduler"
}

/**
 * 后处理 Worker 基类：解析录音 id、提供 DAO / 落库出口。
 *
 * 录音不存在（已被删除）时各步骤直接结束，不重试。
 */
abstract class RecordingPostProcessingWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    protected val recordingId: Long =
        inputData.getLong(PostProcessingScheduler.KEY_RECORDING_ID, -1L)

    protected val database: AppDatabase by lazy { AppDatabase.getInstance(applicationContext) }

    protected val store: TranscriptionStore by lazy {
        RoomTranscriptionStore(database.recordingDao(), database.segmentDao())
    }

    /** 读取当前录音；id 非法或行已删除返回 null。 */
    protected suspend fun loadRecording(): RecordingEntity? =
        recordingId.takeIf { it > 0 }?.let { database.recordingDao().getRecordingById(it) }

    protected fun outputData(): Data =
        workDataOf(PostProcessingScheduler.KEY_RECORDING_ID to recordingId)
}

/**
 * 第 1 步：ASR 文件转写。
 *
 * - 实时档停止时已把流式结果落库（transcription 非 null）→ 跳过，直接进入标题/笔记；
 * - 其余情况（文件档 / 实时档失败或无结果 / 进程重启后恢复）→ 用已持久化 WAV 文件转写；
 *   实时档经 [TranscriptionPipeline] 回退到 [REALTIME_FALLBACK_TIER]；
 * - 多段录音尚无整段 WAV 时，先合并段文件再转写；
 * - 转写失败标记 Recording FAILED，并按指数退避重试，超过上限后 Work 失败。
 */
class TranscriptionWorker(
    appContext: Context,
    params: WorkerParameters,
) : RecordingPostProcessingWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val recording = loadRecording() ?: return Result.failure()
        // 实时流式转写已在停止录音时落库，无需重复调用 ASR
        if (recording.transcription != null) return Result.success(outputData())

        val wav = resolveWavFile(recording)
        if (wav == null) {
            store.markRecordingFailed(recordingId, "音频文件缺失，无法转写（${recording.audioPath}）")
            return Result.failure()
        }

        val config = UserConfigRepository.fromContext(applicationContext).userConfig.first()
        return try {
            TranscriptionPipeline(
                store = store,
                transcriberFactory = DefaultFileTranscriberFactory(),
                // 接通设置页 VAD 四滑杆（此前一直用默认值）
                vadParams = VadParams(
                    silenceThreshold = config.vadSilenceThreshold,
                    silenceDuration = config.vadSilenceDuration,
                    minSegment = config.vadMinSegment,
                    maxSegment = config.vadMaxSegment,
                ),
            ).transcribe(
                recordingId = recordingId,
                wavFile = wav,
                tier = config.asrTier,
                vadEnabled = config.vadEnabled,
                language = config.language.ifBlank { null },
            )
            Result.success(outputData())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "转写失败 recordingId=$recordingId attempt=${runAttemptCount + 1}", e)
            if (runAttemptCount < MAX_ATTEMPTS - 1) Result.retry() else Result.failure()
        }
    }

    /**
     * 解析待转写 WAV：
     * 1. 优先使用 `Recordings.audioPath` 指向的整段文件；
     * 2. 缺失（多段录音尚未合并）→ 用段落文件合并出整段 WAV。
     */
    private suspend fun resolveWavFile(recording: RecordingEntity): File? {
        val storage = StorageService.fromContext(applicationContext)
        if (recording.audioPath.isNotBlank()) {
            val file = runCatching { storage.resolvePath(recording.audioPath) }.getOrNull()
            if (file != null && file.isFile && file.length() > 0L) return file
        }
        // 多段录音：合并段文件为整段 WAV（失败返回 null，由调用方标记 FAILED）
        return try {
            val locator = RecordingRepository(
                database.recordingDao(),
                database.attachmentDao(),
                storage,
            ).finalizeSegmented(recordingId, recording.audioDurationSeconds ?: 0.0)
            locator?.let { storage.resolvePath(it) }?.takeIf { it.isFile && it.length() > 0L }
        } catch (e: Exception) {
            Log.w(TAG, "合并段落文件失败 recordingId=$recordingId", e)
            null
        }
    }

    companion object {
        private const val TAG = "TranscriptionWorker"

        /** 转写最大尝试次数（首次 + 4 次指数退避重试）。 */
        const val MAX_ATTEMPTS = 5
    }
}

/**
 * 第 2 步：AI 自动标题（[AutoTitleWorker]）。
 * 依赖上一步落库的 transcription；无转写或无 key 时回退默认标题（不抛异常）。
 */
class AutoTitleTaskWorker(
    appContext: Context,
    params: WorkerParameters,
) : RecordingPostProcessingWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val recording = loadRecording() ?: return Result.success(outputData())
        return try {
            AutoTitleWorker(DeepSeekConnector(), store)
                .generateTitle(recordingId, recording.transcription.orEmpty())
            Result.success(outputData())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // generateTitle 内部已兜底默认标题；此处仅兜住落库失败等异常
            Log.w(TAG, "自动标题失败 recordingId=$recordingId attempt=${runAttemptCount + 1}", e)
            if (runAttemptCount < MAX_ATTEMPTS - 1) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "AutoTitleTaskWorker"
        const val MAX_ATTEMPTS = 3
    }
}

/**
 * 第 3 步：智能图文笔记（[NoteFusionWorker]）→ 最终 AI 录音笔记落库 Recording.summary。
 * 依赖前两步的 transcription / title / chunks；LLM 不可用时 NoteFusionWorker 回退规则模板。
 */
class NoteFusionTaskWorker(
    appContext: Context,
    params: WorkerParameters,
) : RecordingPostProcessingWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val recording = loadRecording() ?: return Result.success(outputData())
        return try {
            val chunks = database.recordingDao().getChunksForRecording(recordingId)
            NoteFusionWorker(DeepSeekConnector(), store).generateNote(
                recordingId = recordingId,
                title = recording.title,
                transcription = recording.transcription.orEmpty(),
                chunks = chunks,
                durationSeconds = recording.audioDurationSeconds,
            )
            Result.success(outputData())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "智能笔记生成失败 recordingId=$recordingId attempt=${runAttemptCount + 1}", e)
            if (runAttemptCount < MAX_ATTEMPTS - 1) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG = "NoteFusionTaskWorker"
        const val MAX_ATTEMPTS = 3
    }
}
