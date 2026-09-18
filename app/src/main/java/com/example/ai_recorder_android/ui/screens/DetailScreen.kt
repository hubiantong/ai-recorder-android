package com.example.ai_recorder_android.ui.screens

import android.media.MediaPlayer
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavBackStackEntry
import coil3.compose.AsyncImage
import com.example.ai_recorder_android.R
import com.example.ai_recorder_android.data.db.AppDatabase
import com.example.ai_recorder_android.data.db.AttachmentType
import com.example.ai_recorder_android.data.db.RecordingAttachmentEntity
import com.example.ai_recorder_android.data.db.RecordingDao
import com.example.ai_recorder_android.data.db.RecordingStatus
import com.example.ai_recorder_android.data.db.RecordingWithRelations
import com.example.ai_recorder_android.data.db.TranscriptChunkEntity
import com.example.ai_recorder_android.data.storage.StorageService
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
 * 录音详情 ViewModel：注入 [RecordingDao]，加载 [RecordingWithRelations]。
 *
 * [recordingId] 为 null（导航参数缺失/预览场景）时直接进入 error 空态，不查询数据库。
 * 音频文件存在性检查与图片附件过滤涉及磁盘 stat，随 DB 查询一并切 IO 线程完成。
 */
class DetailViewModel(
    private val recordingId: Long?,
    private val dao: RecordingDao,
    private val storage: StorageService,
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
            val data = withContext(Dispatchers.IO) { dao.getRecordingWithRelations(recordingId) }
            _uiState.value = if (data == null) {
                UiState(loading = false, error = "录音不存在或已被删除")
            } else {
                withContext(Dispatchers.IO) {
                    UiState(
                        loading = false,
                        recording = data,
                        audioFile = resolveAudioFile(storage, data),
                        imageAttachments = data.attachments.filter {
                            it.attachmentType == AttachmentType.IMAGE &&
                                resolveOrNull(storage, it.storageLocator)?.isFile == true
                        },
                    )
                }
            }
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
                )
            }
        }
    }
}

/**
 * 录音详情屏幕（Metis M5.10 音频回放 + P6.9 融合笔记）：
 * 转写气泡（点击跳转播放位置）+ 融合笔记（Markdown 简化渲染）+ 照片附件网格 + 音频播放器。
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
    // 优先显式参数；否则从导航参数读取（NavHost 内 LocalViewModelStoreOwner 即 NavBackStackEntry）
    val effectiveRecordingId = recordingId
        ?: (LocalViewModelStoreOwner.current as? NavBackStackEntry)?.arguments
            ?.takeIf { it.containsKey("recordingId") }
            ?.getLong("recordingId")

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

    Scaffold(
        modifier = modifier.fillMaxSize(),
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
                data == null -> EmptyState(state.error, Modifier.align(Alignment.Center))
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
                )
            }
        }
    }

    FullScreenImageViewer(
        attachment = selectedAttachment,
        storage = storage,
        onDismiss = { selectedAttachment = null },
    )
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

/** 详情主体：单 LazyColumn 承载全部区块，空区块自动隐藏。 */
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
    modifier: Modifier = Modifier,
) {
    val chunks = data.chunks
    val summary = data.recording.summary

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { RecordingMeta(data) }

        if (player != null) {
            item {
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
            item { SectionTitle("转写文本") }
            items(chunks, key = { it.id }) { chunk ->
                TranscriptBubble(
                    chunk = chunk,
                    onClick = { chunk.startTime?.let(onChunkClick) },
                )
            }
        }

        if (!summary.isNullOrBlank()) {
            item { FusionNoteSection(summary) }
        }

        if (images.isNotEmpty()) {
            item { SectionTitle("照片附件 (${images.size})") }
            item { AttachmentsGrid(images, storage, onAttachmentClick) }
        }
    }
}

/** 头部：标题 + 时间/时长/会议模式 meta + 状态徽标。 */
@Composable
private fun RecordingMeta(data: RecordingWithRelations, modifier: Modifier = Modifier) {
    val rec = data.recording
    Column(modifier) {
        Text(
            text = rec.title ?: "录音 #${rec.id}",
            style = MaterialTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = buildString {
                append(formatDateTime(rec.createdAt))
                rec.audioDurationSeconds?.let { append("  ·  ${formatDuration(it)}") }
                if (rec.meetingMode) append("  ·  会议模式")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        StatusChip(rec.status)
    }
}

/** 状态徽标（对齐 PC `_format_status` 中文文案）。 */
@Composable
private fun StatusChip(status: RecordingStatus, modifier: Modifier = Modifier) {
    val label = when (status) {
        RecordingStatus.COMPLETED -> "已完成"
        RecordingStatus.PROCESSING -> "处理中"
        RecordingStatus.FAILED -> "失败"
        RecordingStatus.PENDING -> "待处理"
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** 极简音频播放器：播放/暂停 + 进度条 + 当前位置文本。 */
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
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            IconButton(onClick = onTogglePlay, enabled = playerReady) {
                if (isPlaying) {
                    PauseIcon()
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
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 暂停图标（material-icons-core 无 Pause，用 Canvas 双竖条绘制）。 */
@Composable
private fun PauseIcon(modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        drawRect(color, topLeft = Offset(w * 0.28f, h * 0.2f), size = Size(w * 0.18f, h * 0.6f))
        drawRect(color, topLeft = Offset(w * 0.54f, h * 0.2f), size = Size(w * 0.18f, h * 0.6f))
    }
}

/** 单条转写气泡：时间段 + 说话人 + 内容；点击跳转播放位置（对齐 PC TranscriptView）。 */
@Composable
private fun TranscriptBubble(
    chunk: TranscriptChunkEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatTime(chunk.startTime, chunk.endTime),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = chunk.speakerName ?: "转写",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
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
    Column(modifier) {
        SectionTitle("融合笔记")
        Spacer(Modifier.height(8.dp))
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(12.dp)) {
                blocks.forEach { block ->
                    when (block) {
                        is NoteBlock.Heading -> Text(
                            text = block.text,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
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
                            .clip(RoundedCornerShape(8.dp))
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

/** 区块标题。 */
@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier,
    )
}

/** 空态/错误态。 */
@Composable
private fun EmptyState(message: String?, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = message ?: "暂无数据",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

/** 时长秒数 → m:ss。 */
private fun formatDuration(seconds: Double): String {
    val total = seconds.toInt().coerceAtLeast(0)
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