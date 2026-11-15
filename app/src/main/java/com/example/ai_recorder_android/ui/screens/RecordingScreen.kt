package com.example.ai_recorder_android.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.ai_recorder_android.App
import com.example.ai_recorder_android.R
import com.example.ai_recorder_android.core.audio.RecordingSessionConfig
import com.example.ai_recorder_android.data.repo.UserConfig
import com.example.ai_recorder_android.data.repo.UserConfigRepository
import com.example.ai_recorder_android.service.RecordingCoordinator
import com.example.ai_recorder_android.service.RecordingPhase
import com.example.ai_recorder_android.service.RecordingService
import com.example.ai_recorder_android.service.RecordingSessionState
import com.example.ai_recorder_android.ui.components.BrandChip
import com.example.ai_recorder_android.ui.components.LabeledCircleButton
import com.example.ai_recorder_android.ui.components.PulsingDot
import com.example.ai_recorder_android.ui.components.WaveformView
import com.example.ai_recorder_android.ui.theme.LocalBrandSemanticColors
import com.example.ai_recorder_android.ui.theme.Spacing
import com.example.ai_recorder_android.ui.theme.tabularNums
import java.io.File
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 实时转写气泡条目（id 供 LazyColumn key 使用）。 */
data class PartialTranscript(val id: Long, val text: String)

/**
 * 录音页 ViewModel（todo 19，代码库首个 ViewModel）。
 *
 * **职责边界**：只持有 UI 展示状态（计时/波形/转写气泡/档位），**不实现录音逻辑**——
 * 实际录音由共享 [RecordingCoordinator]（App 单例）+ [RecordingService] 编排。
 *
 * **状态来源**：录音会话状态直接取自 [RecordingCoordinator.sessionState]（[sessionState]），
 * 点击只发送服务命令、不做乐观更新；按钮可用性/文案完全由 Coordinator 的真实阶段驱动，
 * 启动失败（[RecordingSessionState.errorMessage]）也会回流到 UI。
 *
 * 构造注入 [Context] + [RecordingCoordinator] + [UserConfigRepository]
 * （无 DI 框架，生产装配见 [factory]）。
 */
class RecordingViewModel(
    private val context: Context,
    private val coordinator: RecordingCoordinator,
    private val userConfigRepository: UserConfigRepository,
) : ViewModel() {

    /** 录音会话真实状态（唯一来源：Coordinator，UI 不自行推断）。 */
    val sessionState: StateFlow<RecordingSessionState> = coordinator.sessionState

    /** 波形历史（时间正序，末尾最新）：每帧 4 桶包络追加，滑窗 [WAVEFORM_MAX_BARS] 根 ≈ 4 秒。 */
    private val _waveform = MutableStateFlow<List<Float>>(emptyList())
    val waveform: StateFlow<List<Float>> = _waveform.asStateFlow()

    /** 录音已过时长（毫秒，扣除暂停）：200ms 轮询 Coordinator 唯一真源，暂停期间自然冻结。 */
    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    /** 实时转写 partial 气泡列表（realtime 档流式输出，todo 17 管线接线）。 */
    private val _partials = MutableStateFlow<List<PartialTranscript>>(emptyList())
    val partials: StateFlow<List<PartialTranscript>> = _partials.asStateFlow()

    /** 当前用户配置（DataStore）；未加载完成前为 null。 */
    private val _userConfig = MutableStateFlow<UserConfig?>(null)
    val userConfig: StateFlow<UserConfig?> = _userConfig.asStateFlow()

    /** 当前 ASR 档位（free/realtime/pro_lite/pro_pro），来自 UserConfig（DataStore）。 */
    private val _tier = MutableStateFlow("free")
    val tier: StateFlow<String> = _tier.asStateFlow()

    private var nextPartialId = 0L

    // 依赖清单无 lifecycle-viewmodel-ktx（viewModelScope 不可用），自建 scope 随 ViewModel 销毁
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 计时轮询任务：会话活动期间运行，非活动取消并归零（见 [startElapsedTicker]/[stopElapsedTicker]）。 */
    private var elapsedTicker: Job? = null

    init {
        // 波形回流：共享 coordinator 引擎线程回调 → 每帧 4 桶包络 → UI 波形
        coordinator.waveformListener = { onWaveformFrame(it) }
        // 实时转写回流：RecordingService（realtime 档）→ 主线程 → 字幕气泡
        coordinator.transcriptListener = { partial, text ->
            if (partial) onPartialTranscript(text) else onSentenceTranscript(text)
        }
        scope.launch {
            userConfigRepository.userConfig.collect { config ->
                _userConfig.value = config
                _tier.value = config.asrTier
            }
        }
        // 计时轮询随会话真实状态启停（RECORDING/PAUSED 活动，其余归零清波形）
        scope.launch {
            coordinator.sessionState.collect { state ->
                if (state.isActive) startElapsedTicker() else stopElapsedTicker()
            }
        }
    }

    private fun startElapsedTicker() {
        if (elapsedTicker?.isActive == true) return
        elapsedTicker = scope.launch {
            while (true) {
                _elapsedMs.value = coordinator.elapsedMs()
                delay(200)
            }
        }
    }

    private fun stopElapsedTicker() {
        elapsedTicker?.cancel()
        elapsedTicker = null
        _elapsedMs.value = 0L
        _waveform.value = emptyList()
    }

    // ---------- 录音控制（只发命令；状态由 Coordinator 回推） ----------

    /**
     * 开始录音：仅在 Coordinator 处于空闲/已完成/失败态时发送命令，不做乐观更新。
     *
     * 会话配置从 UserConfig 组装（[RecordingSessionConfig]）：
     * - 会议模式开启时按「默认音源」选择采集器（system/mixed 需 [resultCode]/[resultData]
     *   的 MediaProjection 授权结果，由 UI 授权弹窗提供）；
     * - 会议模式关闭时始终为麦克风录音（音源设置属于会议模式分组，不外溢到普通录音）。
     *
     * @param resultCode 系统音频授权结果码（普通麦克风录音忽略）
     * @param resultData 系统音频授权 Intent（普通麦克风录音忽略）
     */
    fun onStartClick(resultCode: Int = 0, resultData: Intent? = null) {
        scope.launch {
            val phase = sessionState.value.phase
            if (phase != RecordingPhase.IDLE && phase != RecordingPhase.COMPLETED && phase != RecordingPhase.ERROR) return@launch
            val config = userConfigRepository.userConfig.first()
            _partials.value = emptyList()
            _waveform.value = emptyList()
            val outputDir = File(context.filesDir, "recordings").apply { mkdirs() }
            val audioSource = if (config.meetingMode) {
                RecordingSessionConfig.sanitizeSource(config.meetingAudioSource)
            } else {
                RecordingSessionConfig.SOURCE_MIC
            }
            val sessionConfig = RecordingSessionConfig(
                meetingMode = config.meetingMode,
                audioSource = audioSource,
                projectionResultCode = resultCode,
                projectionResultData = resultData,
            )
            context.startForegroundService(RecordingService.startIntent(context, outputDir, sessionConfig))
        }
    }

    /** 暂停：仅在 Coordinator 真实处于 RECORDING 时发送。 */
    fun onPauseClick() {
        if (sessionState.value.phase != RecordingPhase.RECORDING) return
        context.startService(RecordingService.pauseIntent(context))
    }

    /** 继续：仅在 Coordinator 真实处于 PAUSED 时发送。 */
    fun onResumeClick() {
        if (sessionState.value.phase != RecordingPhase.PAUSED) return
        context.startService(RecordingService.resumeIntent(context))
    }

    /** 停止：仅在 Coordinator 真实存在活动会话时发送。 */
    fun onStopClick() {
        if (!sessionState.value.isActive) return
        // 会话结束，摘掉「正在识别」气泡（最终句由回调另行追加）
        _partials.value = _partials.value.filterNot { it.id == LIVE_PARTIAL_ID }
        context.startService(RecordingService.stopIntent(context))
    }

    // ---------- 外部事件入口（波形已接线；转写回流已接线 realtime 档） ----------

    /** 引擎波形包络回调（每帧 4 桶归一化 RMS）：追加历史并裁剪滑窗。 */
    fun onWaveformFrame(bars: FloatArray) {
        _waveform.value = (_waveform.value + bars.toList()).takeLast(WAVEFORM_MAX_BARS)
    }

    /**
     * 实时转写中间结果（realtime 档流式 partial）：替换「正在识别」气泡（固定 id）。
     */
    fun onPartialTranscript(text: String) {
        if (text.isBlank()) return
        val list = _partials.value.toMutableList()
        val live = PartialTranscript(LIVE_PARTIAL_ID, text)
        val index = list.indexOfFirst { it.id == LIVE_PARTIAL_ID }
        if (index >= 0) list[index] = live else list.add(live)
        _partials.value = list
    }

    /** 实时转写最终句：移除「正在识别」气泡并追加定稿句。 */
    fun onSentenceTranscript(text: String) {
        _partials.value = _partials.value
            .filterNot { it.id == LIVE_PARTIAL_ID && text.isNotBlank() }
            .let { if (text.isBlank()) it else it + PartialTranscript(nextPartialId++, text) }
    }

    override fun onCleared() {
        coordinator.waveformListener = null
        coordinator.transcriptListener = null
        scope.cancel()
        super.onCleared()
    }

    companion object {
        /** 「正在识别」气泡的固定 id（负数避免与定稿句 id 冲突）。 */
        private const val LIVE_PARTIAL_ID = -1L

        /** 波形滑窗上限（根）：每 100ms 4 根 → 约 4 秒可视历史。 */
        private const val WAVEFORM_MAX_BARS = 160

        /** 生产工厂：Context 装配共享 RecordingCoordinator（App 单例）+ UserConfigRepository。 */
        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = context.applicationContext
                RecordingViewModel(
                    context = app,
                    coordinator = (app as App).recordingCoordinator,
                    userConfigRepository = UserConfigRepository.fromContext(app),
                )
            }
        }
    }
}

/**
 * 录音页（专注录音）：录音状态为页面主角——状态文案 + 脉冲点 + 大计时器 + 动态波形，
 * 实时转写居中，圆形主控按钮（开始/暂停/继续/结束），拍照/文件转写收进次级区。
 *
 * 从首页「开始录音」进入的专注界面（无底部栏，返回回首页），不再是启动首页。
 *
 * @param onFileUploadClick 文件转写入口（跳转 Routes.FILE_UPLOAD）
 * @param onSettingsClick 设置入口（跳转 Routes.SETTINGS）
 * @param onBackClick 返回入口（null 时无返回按钮，保持旧渲染）
 * @param autoStart true：进入后自动请求麦克风权限并开始录音（首页 CTA 一键直达）
 */
@Composable
fun RecordingScreen(
    viewModel: RecordingViewModel = viewModel(factory = RecordingViewModel.factory(LocalContext.current)),
    onFileUploadClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
    onBackClick: (() -> Unit)? = null,
    autoStart: Boolean = false,
) {
    val session by viewModel.sessionState.collectAsState()
    val waveform by viewModel.waveform.collectAsState()
    val elapsedMs by viewModel.elapsedMs.collectAsState()
    val partials by viewModel.partials.collectAsState()
    val tier by viewModel.tier.collectAsState()

    // 录音权限请求：已授权时系统直接回调 granted，无需手动预检
    val context = LocalContext.current
    val userConfig by viewModel.userConfig.collectAsState()

    // 启动/停止失败（Coordinator 真实状态回推）→ 用户可见提示；失败态可直接重试
    LaunchedEffect(session.errorMessage) {
        session.errorMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    // 系统音频授权（会议模式 system/mixed 音源）：授权结果透传给服务，
    // 服务先以含 MEDIA_PROJECTION 的前台类型启动，再由引擎 getMediaProjection
    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            viewModel.onStartClick(result.resultCode, result.data)
        } else {
            Toast.makeText(context, "需要屏幕录制权限才能采集系统音频", Toast.LENGTH_LONG).show()
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            Toast.makeText(
                context,
                "录音需要麦克风权限，若已拒绝请在系统设置中开启后重试",
                Toast.LENGTH_LONG,
            ).show()
            return@rememberLauncherForActivityResult
        }
        // 会议模式 + system/mixed 音源：先弹 MediaProjection 授权，再开始录音
        val config = userConfig ?: UserConfig()
        val source = if (config.meetingMode) {
            RecordingSessionConfig.sanitizeSource(config.meetingAudioSource)
        } else {
            RecordingSessionConfig.SOURCE_MIC
        }
        if (source != RecordingSessionConfig.SOURCE_MIC) {
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            projectionLauncher.launch(manager.createScreenCaptureIntent())
        } else {
            viewModel.onStartClick()
        }
    }

    // 首页 CTA 一键直达：进入本页自动请求权限开始录音；会话已活动/忙碌（如从横幅返回）则跳过
    LaunchedEffect(Unit) {
        if (autoStart && !session.isActive && !session.isBusy) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // 拍照附件流程（todo 23）：相机 Intent → 保存附件 + 缩略图 + VLM 描述
    val photoManager = rememberPhotoAttachmentManager()
    val photoController = rememberPhotoCaptureController(photoManager)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.page),
    ) {
        // 顶栏：返回（从首页进入时）+ 档位（点击进设置）+ 设置齿轮
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBackClick != null) {
                IconButton(onClick = onBackClick) {
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_back),
                        contentDescription = "返回",
                    )
                }
            }
            BrandChip(
                text = "档位 · $tier",
                container = MaterialTheme.colorScheme.secondaryContainer,
                content = MaterialTheme.colorScheme.onSecondaryContainer,
                onClick = onSettingsClick,
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onSettingsClick) {
                Icon(
                    painter = painterResource(R.drawable.ic_settings),
                    contentDescription = "设置",
                )
            }
        }

        Spacer(modifier = Modifier.height(Spacing.lg))

        // 页面主角：录音状态 + 大计时器 + 动态波形
        RecordingHero(
            session = session,
            elapsedMs = elapsedMs,
            waveform = waveform,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 实时转写气泡区（占剩余空间）
        PartialTranscriptList(
            partials = partials,
            modifier = Modifier.weight(1f),
        )

        Spacer(modifier = Modifier.height(Spacing.lg))

        // 圆形主控：开始 / 暂停↔继续 + 结束（按 Coordinator 真实状态切换与启停）
        RecordingControls(
            session = session,
            onStartClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
            onStopClick = viewModel::onStopClick,
            onPauseClick = viewModel::onPauseClick,
            onResumeClick = viewModel::onResumeClick,
        )

        Spacer(modifier = Modifier.height(Spacing.md))

        // 次级区细分隔线：主控与辅助操作的视觉分界
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        )

        Spacer(modifier = Modifier.height(Spacing.sm))

        // 拍照（相机流程内建，仅录音中可用——对齐 Coordinator.addAttachment 需活动会话）
        // 与文件转写（恒可用）并列的紧凑次级入口行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            FilledTonalButton(
                onClick = { photoController.launch() },
                enabled = session.isActive,
                contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.xs),
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp),
            ) {
                Text("拍照", style = MaterialTheme.typography.bodyMedium)
            }
            FilledTonalButton(
                onClick = onFileUploadClick,
                contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.xs),
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp),
            ) {
                Text("文件转写", style = MaterialTheme.typography.bodyMedium)
            }
        }
        PhotoCaptureResult(controller = photoController, manager = photoManager)
    }
}

/**
 * 页面主角：状态行（脉冲点 + 文案）+ 大计时器（tnum 等宽数字防跳动）+ 滚动波形。
 *
 * 状态配色：录音中=脉冲红点红字、已暂停=琥珀点暗字、其余灰字；
 * 波形录音中走品牌渐变，暂停/空闲灰态平线（冻结的历史柱保留但变灰）。
 */
@Composable
private fun RecordingHero(
    session: RecordingSessionState,
    elapsedMs: Long,
    waveform: List<Float>,
) {
    val isRecording = session.phase == RecordingPhase.RECORDING
    val isPaused = session.phase == RecordingPhase.PAUSED
    val semantic = LocalBrandSemanticColors.current
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isRecording || isPaused) {
                PulsingDot(color = if (isRecording) semantic.recording else semantic.warning)
                Spacer(modifier = Modifier.width(Spacing.sm))
            }
            Text(
                text = statusText(session.phase),
                style = MaterialTheme.typography.labelLarge,
                color = when {
                    isRecording -> semantic.recording
                    isPaused -> semantic.warning
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        Spacer(modifier = Modifier.height(Spacing.md))
        Text(
            text = formatElapsed(elapsedMs),
            style = MaterialTheme.typography.displayLarge.tabularNums().copy(
                color = if (isPaused) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            ),
        )
        Spacer(modifier = Modifier.height(Spacing.lg))
        WaveformView(
            bars = waveform,
            active = isRecording,
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp),
        )
    }
}

/** 录音阶段 → 用户可读状态文案（正在启动/保存对应 STARTING/STOPPING 过渡期）。 */
private fun statusText(phase: RecordingPhase): String = when (phase) {
    RecordingPhase.IDLE -> "准备就绪"
    RecordingPhase.STARTING -> "正在启动…"
    RecordingPhase.RECORDING -> "录音中"
    RecordingPhase.PAUSED -> "已暂停"
    RecordingPhase.STOPPING -> "正在保存…"
    RecordingPhase.COMPLETED -> "录音已结束"
    RecordingPhase.ERROR -> "录音失败，可重试"
}

/** 计时格式：不足 1 小时 MM:SS，满 1 小时 HH:MM:SS（Locale.US 固定拉丁数字）。 */
private fun formatElapsed(ms: Long): String {
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }
}

/** 实时转写气泡列表：品牌圆角卡片 + 进出场动画；空态显示提示文案。 */
@Composable
private fun PartialTranscriptList(
    partials: List<PartialTranscript>,
    modifier: Modifier = Modifier,
) {
    if (partials.isEmpty()) {
        Box(
            modifier = modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "实时转写将显示在这里",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(partials, key = { it.id }) { partial ->
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.animateItem(
                    fadeInSpec = tween(180),
                    fadeOutSpec = tween(180),
                ),
            ) {
                Text(
                    text = partial.text,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * 录音主控：圆形大按钮 + 下方文字标签，按 Coordinator 真实阶段切换。
 *
 * 会话活动：主圆钮「暂停 ↔ 继续」（72dp，主色）+「结束」圆钮（64dp，错误容器色）；
 * 空闲/完成/失败：「开始录音」大圆钮（80dp，主色，失败可直接重试）；
 * STARTING/STOPPING 过渡期全部禁用（避免快速连点产生乱序命令）。
 */
@Composable
private fun RecordingControls(
    session: RecordingSessionState,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onPauseClick: () -> Unit,
    onResumeClick: () -> Unit,
) {
    val canStart = !session.isActive && !session.isBusy
    val canStop = session.isActive
    val canPause = session.phase == RecordingPhase.RECORDING
    val canResume = session.phase == RecordingPhase.PAUSED

    if (session.isActive) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(40.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LabeledCircleButton(
                iconRes = if (canPause) R.drawable.ic_pause else R.drawable.ic_play,
                label = if (canPause) "暂停" else "继续",
                enabled = canPause || canResume,
                onClick = if (canPause) onPauseClick else onResumeClick,
                size = 72.dp,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
            LabeledCircleButton(
                iconRes = R.drawable.ic_stop,
                label = "结束",
                enabled = canStop,
                onClick = onStopClick,
                size = 64.dp,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    } else {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            LabeledCircleButton(
                iconRes = R.drawable.ic_mic,
                label = "开始录音",
                enabled = canStart,
                onClick = onStartClick,
                size = 80.dp,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}
