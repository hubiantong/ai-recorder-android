package com.example.ai_recorder_android.ui.screens

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavBackStackEntry
import coil3.compose.AsyncImage
import com.example.ai_recorder_android.R
import com.example.ai_recorder_android.data.db.AppDatabase
import com.example.ai_recorder_android.data.db.AttachmentType
import com.example.ai_recorder_android.data.db.RecordingAttachmentEntity
import com.example.ai_recorder_android.data.db.RecordingDao
import com.example.ai_recorder_android.data.db.RecordingEntity
import com.example.ai_recorder_android.data.db.RecordingStatus
import com.example.ai_recorder_android.data.db.RecordingWithRelations
import com.example.ai_recorder_android.data.db.TranscriptChunkEntity
import com.example.ai_recorder_android.data.storage.StorageService
import com.example.ai_recorder_android.service.transcription.PostProcessingScheduler
import com.example.ai_recorder_android.ui.components.EmptyStateView
import com.example.ai_recorder_android.ui.components.ProcessingIndicator
import com.example.ai_recorder_android.ui.components.formatDuration
import com.example.ai_recorder_android.ui.components.RecordingStatusChip
import com.example.ai_recorder_android.ui.components.RecordingTypeChip
import com.example.ai_recorder_android.ui.components.SectionCard
import com.example.ai_recorder_android.ui.components.SectionTitle
import com.example.ai_recorder_android.ui.theme.IconSize
import com.example.ai_recorder_android.ui.theme.TimestampStyle
import com.example.ai_recorder_android.ui.theme.tabularNums
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 录音详情 ViewModel：注入 [RecordingDao]，经 [RecordingDao.observeRecordingWithRelations]
 * **实时观察**录音与子表（AI 后处理落库后详情自动刷新，如笔记生成完成）。
 *
 * [recordingId] 为 null（导航参数缺失/预览场景）时直接进入 error 空态，不查询数据库。
 * 音频文件存在性检查与图片附件过滤涉及磁盘 stat，切 IO 线程完成；
 * audioPath 未变化时复用上一个 [File] 实例，避免播放器被误重建导致播放中断。
 */
class DetailViewModel(
    private val recordingId: Long?,
    private val dao: RecordingDao,
    private val storage: StorageService,
    private val appContext: Context,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val recording: RecordingWithRelations? = null,
        val audioFile: File? = null,
        val imageAttachments: List<RecordingAttachmentEntity> = emptyList(),
        val error: String? = null,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            if (recordingId == null) {
                _uiState.value = UiState(loading = false, error = "未选择录音")
                return@launch
            }
            dao.observeRecordingWithRelations(recordingId).collect { data ->
                if (data == null) {
                    _uiState.value = UiState(loading = false, error = "录音不存在或已被删除")
                    return@collect
                }
                val resolvedFile = withContext(Dispatchers.IO) { resolveAudioFile(storage, data) }
                val prev = _uiState.value
                _uiState.value = UiState(
                    loading = false,
                    recording = data,
                    // 路径未变复用旧 File 实例：rememberMediaPlayer 按实例 keyed，避免误重建
                    audioFile = if (prev.audioFile?.absolutePath == resolvedFile?.absolutePath) {
                        prev.audioFile
                    } else {
                        resolvedFile
                    },
                    imageAttachments = withContext(Dispatchers.IO) {
                        data.attachments.filter {
                            it.attachmentType == AttachmentType.IMAGE &&
                                resolveOrNull(storage, it.storageLocator)?.isFile == true
                        }
                    },
                )
            }
        }
    }

    /** 保存标题编辑（空白视为回退默认标题 → title = null）。 */
    fun saveTitle(title: String) {
        val id = recordingId ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                dao.getRecordingById(id)?.let { current ->
                    val trimmed = title.trim()
                    dao.updateRecording(current.copy(title = trimmed.takeIf { it.isNotEmpty() }))
                }
            }
        }
    }

    /**
     * 重新生成标题与笔记（跳过转写）。结果经 [onResult] 回 UI 提示：
     * false = 转写缺失，无法重新生成。
     */
    fun regenerateNote(onResult: (Boolean) -> Unit) {
        val id = recordingId ?: return
        viewModelScope.launch {
            onResult(PostProcessingScheduler.reenqueueNoteGeneration(appContext, id))
        }
    }

    companion object {
        /** 工厂：从 CreationExtras 取 Application 构建 DAO（无 DI 框架，对齐项目现状）。 */
        fun factory(recordingId: Long?): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    ?: error("DetailViewModel 需要 Application 上下文")
                DetailViewModel(
                    recordingId = recordingId,
                    dao = AppDatabase.getInstance(app).recordingDao(),
                    storage = StorageService.fromContext(app),
                    appContext = app,
                )
            }
        }
    }
}

/**
 * 录音详情屏幕（Metis M5.10 音频回放 + P6.9 融合笔记）：
 * **AI 笔记为主体**（标题可编辑 + 复制/分享/重新生成操作行）+ 播放器 +
 * 转写文本（点击跳转播放、播放中自动高亮滚动）+ 照片附件网格。
 *
 * [recordingId] 为 null 时从导航参数读取（MainActivity 路由 `detail/{recordingId}`，
 * 经 [LocalViewModelStoreOwner]（NavHost 内即 NavBackStackEntry）取 arguments）。
 * [onBack] 为 null 时回退到系统返回分发器（NavHost 已注册 popBackStack 回调）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(
    recordingId: Long? = null,
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // 优先显式参数；否则从导航参数读取（NavHost 内 LocalViewModelStoreOwner 即 NavBackStackEntry）。
    // 注意：Navigation 路径参数以 String 存入 arguments，必须 getString 后转 Long；
    // getLong 会因类型不匹配返回 0L，导致恒查 id=0 而报"录音不存在"。
    val effectiveRecordingId = recordingId
        ?: (LocalViewModelStoreOwner.current as? NavBackStackEntry)?.arguments
            ?.getString("recordingId")
            ?.toLongOrNull()

    val context = LocalContext.current
    val viewModel: DetailViewModel = viewModel(
        factory = remember(effectiveRecordingId) { DetailViewModel.factory(effectiveRecordingId) },
    )
    val state by viewModel.uiState.collectAsState()

    val storage = remember { StorageService.fromContext(context) }
    val player = rememberMediaPlayer(state.audioFile)

    var playerReady by remember(player) { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var selectedAttachment by remember { mutableStateOf<RecordingAttachmentEntity?>(null) }
    var showEditTitle by remember { mutableStateOf(false) }

    DisposableEffect(player) {
        if (player != null) {
            player.setOnPreparedListener { playerReady = true }
            player.setOnCompletionListener {
                isPlaying = false
                positionMs = player.duration.toLong()
            }
        }
        onDispose {
            player?.setOnPreparedListener(null)
            player?.setOnCompletionListener(null)
        }
    }

    // 播放中每 500ms 刷新进度（LaunchedEffect 随 isPlaying 重启/取消）
    LaunchedEffect(player, isPlaying) {
        if (player == null || !isPlaying) return@LaunchedEffect
        while (isPlaying) {
            positionMs = player.currentPosition.toLong()
            delay(500)
        }
    }

    val togglePlay: () -> Unit = {
        val p = player
        if (p != null && playerReady) {
            if (isPlaying) {
                p.pause()
                isPlaying = false
            } else {
                p.start()
                isPlaying = true
            }
        }
    }

    val seekToMs: (Long) -> Unit = { targetMs ->
        val p = player
        if (p != null && playerReady) {
            val clamped = targetMs.coerceIn(0L, p.duration.toLong().coerceAtLeast(0L))
            p.seekTo(clamped.toInt())
            positionMs = clamped
        }
    }

    // 返回：优先显式回调，否则走系统返回分发器（NavHost 已注册 popBackStack 回调）
    val backDispatcher = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
    val handleBack: () -> Unit = onBack ?: { backDispatcher?.onBackPressed() }

    val clipboard = LocalClipboardManager.current

    // 品牌顶栏：常态与页面背景同色（沉浸），滚动后浮出 surfaceContainer 层级
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = state.recording?.recording?.title ?: "录音详情",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = handleBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
    ) { innerPadding ->
        val data = state.recording
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                data == null -> EmptyStateView(
                    title = state.error ?: "暂无数据",
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_history),
                            contentDescription = null,
                            modifier = Modifier.size(IconSize.xl),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    modifier = Modifier.align(Alignment.Center),
                )
                else -> DetailContent(
                    data = data,
                    storage = storage,
                    images = state.imageAttachments,
                    player = player,
                    playerReady = playerReady,
                    isPlaying = isPlaying,
                    positionMs = positionMs,
                    onTogglePlay = togglePlay,
                    onSeekMs = seekToMs,
                    onChunkClick = { seconds -> seekToMs((seconds * 1000).toLong()) },
                    onAttachmentClick = { selectedAttachment = it },
                    onEditTitle = { showEditTitle = true },
                    onCopyNote = {
                        val summary = data.recording.summary
                        if (!summary.isNullOrBlank()) {
                            clipboard.setText(AnnotatedString(summary))
                            android.widget.Toast.makeText(context, "已复制 AI 笔记", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    onShare = {
                        val shareText = buildShareText(data.recording)
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, shareText)
                        }
                        context.startActivity(Intent.createChooser(intent, "分享笔记"))
                    },
                    onRegenerate = {
                        viewModel.regenerateNote { ok ->
                            android.widget.Toast.makeText(
                                context,
                                if (ok) "已重新生成标题与笔记" else "转写缺失，无法重新生成",
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                )
            }
        }
    }

    // 标题编辑对话框
    if (showEditTitle) {
        val currentTitle = state.recording?.recording?.title.orEmpty()
        var draft by remember(showEditTitle) { mutableStateOf(currentTitle) }
        AlertDialog(
            onDismissRequest = { showEditTitle = false },
            title = { Text("编辑标题") },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    label = { Text("标题") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.saveTitle(draft)
                        showEditTitle = false
                    },
                    enabled = draft.isNotBlank(),
                ) {
                    Text("保存")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditTitle = false }) {
                    Text("取消")
                }
            },
        )
    }

    FullScreenImageViewer(
        attachment = selectedAttachment,
        storage = storage,
        onDismiss = { selectedAttachment = null },
    )
}

/** 分享文本：标题 + AI 笔记 + 转写全文（有则拼入）。 */
private fun buildShareText(recording: RecordingEntity): String = buildString {
    append(recording.title ?: "录音笔记")
    recording.summary?.takeIf { it.isNotBlank() }?.let {
        append("\n\n")
        append(it)
    }
    recording.transcription?.takeIf { it.isNotBlank() }?.let {
        append("\n\n—— 转写文本 ——\n")
        append(it)
    }
}

/** 按录音文件创建 [MediaPlayer]（prepareAsync 异步就绪）；离开组合时 release。 */
@Composable
private fun rememberMediaPlayer(audioFile: File?): MediaPlayer? {
    val player = remember(audioFile) {
        if (audioFile == null) {
            null
        } else {
            MediaPlayer().apply {
                setDataSource(audioFile.absolutePath)
                prepareAsync()
            }
        }
    }
    DisposableEffect(player) {
        onDispose { player?.release() }
    }
    return player
}

/**
 * 详情主体：单 LazyColumn 承载全部区块（**AI 笔记为主体**）。
 * 区块顺序：标题/meta/状态 → 操作行 → AI 笔记 → 播放器 → 转写文本 → 照片附件。
 */
@Composable
private fun DetailContent(
    data: RecordingWithRelations,
    storage: StorageService,
    images: List<RecordingAttachmentEntity>,
    player: MediaPlayer?,
    playerReady: Boolean,
    isPlaying: Boolean,
    positionMs: Long,
    onTogglePlay: () -> Unit,
    onSeekMs: (Long) -> Unit,
    onChunkClick: (Double) -> Unit,
    onAttachmentClick: (RecordingAttachmentEntity) -> Unit,
    onEditTitle: () -> Unit,
    onCopyNote: () -> Unit,
    onShare: () -> Unit,
    onRegenerate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val chunks = data.chunks
    val recording = data.recording
    val listState = rememberLazyListState()

    // 当前播放位置对应的转写段（最后一个已开始的段；未播放为 -1）
    val activeChunkIndex = if (positionMs > 0) {
        chunks.indexOfLast { (it.startTime ?: 0.0) * 1000 <= positionMs }
    } else {
        -1
    }

    // 播放中自动滚动到当前段（区块前缀：meta + actions + note + player? + transcript 标题?）
    LaunchedEffect(activeChunkIndex, isPlaying) {
        if (isPlaying && activeChunkIndex >= 0 && chunks.isNotEmpty()) {
            val prefix = 3 + (if (player != null) 1 else 0) + 1
            runCatching { listState.animateScrollToItem(prefix + activeChunkIndex) }
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "meta") {
            RecordingMeta(
                data = data,
                onEditTitle = onEditTitle,
            )
        }

        item(key = "actions") {
            DetailActionsRow(
                hasNote = !recording.summary.isNullOrBlank(),
                hasTranscription = recording.transcription != null,
                onCopyNote = onCopyNote,
                onShare = onShare,
                onRegenerate = onRegenerate,
            )
        }

        item(key = "note") {
            NoteSection(
                recording = recording,
                hasTranscription = recording.transcription != null,
                onRegenerate = onRegenerate,
            )
        }

        if (player != null) {
            item(key = "player") {
                AudioPlayerSection(
                    player = player,
                    playerReady = playerReady,
                    isPlaying = isPlaying,
                    positionMs = positionMs,
                    onTogglePlay = onTogglePlay,
                    onSeekMs = onSeekMs,
                )
            }
        }

        if (chunks.isNotEmpty()) {
            item(key = "transcript_title") { SectionTitle("转写文本") }
            itemsIndexed(chunks, key = { _, chunk -> chunk.id }) { index, chunk ->
                TranscriptBubble(
                    chunk = chunk,
                    active = index == activeChunkIndex,
                    onClick = { chunk.startTime?.let(onChunkClick) },
                )
            }
        }

        if (images.isNotEmpty()) {
            item(key = "att_title") { SectionTitle("照片附件 (${images.size})") }
            item(key = "att_grid") { AttachmentsGrid(images, storage, onAttachmentClick) }
        }
    }
}

/** 头部：标题（+ 编辑按钮）+ 时间/时长/会议模式 meta + 类型/状态 chip。 */
@Composable
private fun RecordingMeta(data: RecordingWithRelations, onEditTitle: () -> Unit, modifier: Modifier = Modifier) {
    val rec = data.recording
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = rec.title ?: "录音 #${rec.id}",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onEditTitle) {
                Icon(painterResource(R.drawable.ic_edit), contentDescription = "编辑标题")
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = buildString {
                append(formatDateTime(rec.createdAt))
                rec.audioDurationSeconds?.let { append("  ·  ${formatDuration(it)}") }
                if (rec.meetingMode) append("  ·  会议模式")
            },
            style = MaterialTheme.typography.bodySmall.tabularNums(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RecordingTypeChip(meetingMode = rec.meetingMode)
            Spacer(Modifier.width(8.dp))
            RecordingStatusChip(
                status = rec.status,
                hasNote = rec.summary != null,
            )
        }
    }
}

/** 操作行：复制笔记 / 分享 / 重新生成（按可用数据启用）。 */
@Composable
private fun DetailActionsRow(
    hasNote: Boolean,
    hasTranscription: Boolean,
    onCopyNote: () -> Unit,
    onShare: () -> Unit,
    onRegenerate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = onCopyNote,
            enabled = hasNote,
            modifier = Modifier.weight(1f),
        ) {
            Text("复制笔记", maxLines = 1)
        }
        OutlinedButton(
            onClick = onShare,
            enabled = hasNote || hasTranscription,
            modifier = Modifier.weight(1f),
        ) {
            Text("分享", maxLines = 1)
        }
        OutlinedButton(
            onClick = onRegenerate,
            enabled = hasTranscription,
            modifier = Modifier.weight(1f),
        ) {
            Text("重新生成", maxLines = 1)
        }
    }
}

/**
 * AI 笔记区（详情页主体）：
 * - 有笔记 → Markdown 卡片；
 * - FAILED → 错误卡（原因 + 重新生成入口）；
 * - 其余（后处理链未跑完）→ 处理中指示卡。
 */
@Composable
private fun NoteSection(
    recording: RecordingEntity,
    hasTranscription: Boolean,
    onRegenerate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val summary = recording.summary
    when {
        !summary.isNullOrBlank() -> FusionNoteSection(summary, modifier)
        recording.status == RecordingStatus.FAILED -> SectionCard(modifier = modifier) {
            Text(
                text = "AI 处理失败",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = recording.errorMessage ?: "处理过程中出现错误，可尝试重新生成",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (hasTranscription) {
                TextButton(onClick = onRegenerate, modifier = Modifier.padding(top = 4.dp)) {
                    Text("重新生成")
                }
            }
        }
        else -> SectionCard(modifier = modifier) {
            ProcessingIndicator(text = "AI 笔记生成中…（转写 → 标题 → 笔记）")
        }
    }
}

/** 极简音频播放器：播放/暂停 + 进度条 + 当前位置文本（等宽时间戳）。 */
@Composable
private fun AudioPlayerSection(
    player: MediaPlayer,
    playerReady: Boolean,
    isPlaying: Boolean,
    positionMs: Long,
    onTogglePlay: () -> Unit,
    onSeekMs: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val durationMs = if (playerReady) player.duration.toLong() else 0L
    SectionCard(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        modifier = modifier,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onTogglePlay, enabled = playerReady) {
                if (isPlaying) {
                    Icon(painterResource(R.drawable.ic_pause), contentDescription = "暂停")
                } else {
                    Icon(painterResource(R.drawable.ic_play), contentDescription = "播放")
                }
            }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                var dragPosition by remember { mutableStateOf<Float?>(null) }
                val maxMs = durationMs.toFloat().coerceAtLeast(1f)
                val sliderValue = (dragPosition ?: positionMs.toFloat()).coerceIn(0f, maxMs)
                Slider(
                    value = sliderValue,
                    onValueChange = { dragPosition = it },
                    onValueChangeFinished = {
                        dragPosition?.let { onSeekMs(it.toLong()) }
                        dragPosition = null
                    },
                    valueRange = 0f..maxMs,
                    enabled = playerReady,
                )
                Text(
                    text = "${formatMs(positionMs)} / ${formatMs(durationMs)}",
                    style = TimestampStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 单条转写气泡：时间段 + 说话人 + 内容；点击跳转播放位置（对齐 PC TranscriptView）。
 * [active] = 当前播放位置所在段：主色描边 + 高亮容器色，实现时间轴联动。
 */
@Composable
private fun TranscriptBubble(
    chunk: TranscriptChunkEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = if (active) scheme.secondaryContainer else scheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatTime(chunk.startTime, chunk.endTime),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (active) scheme.onSecondaryContainer else scheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = chunk.speakerName ?: "转写",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Text(
                text = chunk.content,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** 融合笔记：Markdown 简化渲染（## → 加粗标题、- / * → 圆点列表，无 Markwon 依赖）。 */
@Composable
private fun FusionNoteSection(summary: String, modifier: Modifier = Modifier) {
    val blocks = remember(summary) { parseMarkdown(summary) }
    SectionCard(modifier = modifier) {
        blocks.forEach { block ->
            when (block) {
                is NoteBlock.Heading -> Text(
                    text = block.text,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp),
                )

                is NoteBlock.Bullet -> Row(Modifier.padding(top = 4.dp)) {
                    Text("•", color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(block.text, style = MaterialTheme.typography.bodyMedium)
                }

                is NoteBlock.Paragraph -> Text(
                    text = block.text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** 笔记渲染块。 */
private sealed interface NoteBlock {
    data class Heading(val text: String) : NoteBlock
    data class Bullet(val text: String) : NoteBlock
    data class Paragraph(val text: String) : NoteBlock
}

/** 极简 Markdown 行解析：`#`/`##` → Heading，`-`/`*` → Bullet，其余 → Paragraph。 */
private fun parseMarkdown(markdown: String): List<NoteBlock> =
    markdown.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { line ->
            when {
                line.startsWith("## ") -> NoteBlock.Heading(line.removePrefix("## ").trim())
                line.startsWith("# ") -> NoteBlock.Heading(line.removePrefix("# ").trim())
                line.startsWith("- ") -> NoteBlock.Bullet(line.removePrefix("- ").trim())
                line.startsWith("* ") -> NoteBlock.Bullet(line.removePrefix("* ").trim())
                else -> NoteBlock.Paragraph(line)
            }
        }
        .toList()

/** 照片附件网格：3 列 LazyVerticalGrid + Coil AsyncImage；点击打开全屏查看。 */
@Composable
private fun AttachmentsGrid(
    attachments: List<RecordingAttachmentEntity>,
    storage: StorageService,
    onAttachmentClick: (RecordingAttachmentEntity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = 4.dp
    BoxWithConstraints(modifier) {
        // 单元格为正方形：边长 = (可用宽 - 2×间距) / 3；网格高度按行数精确计算，
        // 避免嵌套在 LazyColumn 内出现无限高度约束崩溃。
        val cellSize = (maxWidth - spacing * 2) / 3
        val rows = (attachments.size + 2) / 3
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.height(cellSize * rows.toFloat() + spacing * (rows - 1).toFloat()),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            items(attachments, key = { it.id }) { attachment ->
                val file = resolveOrNull(storage, attachment.storageLocator)
                if (file != null) {
                    AsyncImage(
                        model = file,
                        contentDescription = attachment.description ?: "照片附件",
                        contentScale = ContentScale.Crop,
                        placeholder = ColorPainter(MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(MaterialTheme.shapes.small)
                            .clickable { onAttachmentClick(attachment) },
                    )
                }
            }
        }
    }
}

/** 全屏图片查看器（Dialog 铺满屏幕，黑底 Fit 显示 + 右上角关闭）。 */
@Composable
private fun FullScreenImageViewer(
    attachment: RecordingAttachmentEntity?,
    storage: StorageService,
    onDismiss: () -> Unit,
) {
    if (attachment == null) return
    val file = resolveOrNull(storage, attachment.storageLocator)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            if (file != null) {
                AsyncImage(
                    model = file,
                    contentDescription = attachment.description ?: "照片附件",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(8.dp),
            ) {
                Icon(painterResource(R.drawable.ic_close), contentDescription = "关闭", tint = Color.White)
            }
        }
    }
}

// ---------- 纯函数工具 ----------

/** 转写时间段：m:ss 或 m:ss–m:ss（对齐 PC `_fmt_time`）。 */
private fun formatTime(start: Double?, end: Double?): String {
    val s = (start ?: 0.0).toInt().coerceAtLeast(0)
    val base = "%d:%02d".format(s / 60, s % 60)
    if (end == null) return base
    val e = end.toInt().coerceAtLeast(0)
    return "$base–%d:%02d".format(e / 60, e % 60)
}

/** 播放器毫秒 → m:ss。 */
private fun formatMs(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}

/** epoch 毫秒 → "yyyy-MM-dd HH:mm"（本地时区）。 */
private fun formatDateTime(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))

/** 安全解析 locator；非法路径返回 null（不抛异常）。 */
private fun resolveOrNull(storage: StorageService, locator: String): File? =
    try {
        storage.resolvePath(locator)
    } catch (_: IllegalArgumentException) {
        null
    }

/** 解析录音音频文件；locator 为空/非法/文件不存在返回 null。 */
private fun resolveAudioFile(storage: StorageService, data: RecordingWithRelations): File? {
    val locator = data.recording.audioPath
    if (locator.isBlank()) return null
    val file = resolveOrNull(storage, locator) ?: return null
    return if (file.isFile) file else null
}
