package com.example.ai_recorder_android.ui.screens

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.ai_recorder_android.App
import com.example.ai_recorder_android.R
import com.example.ai_recorder_android.data.db.AppDatabase
import com.example.ai_recorder_android.data.db.RecordingDao
import com.example.ai_recorder_android.data.db.RecordingEntity
import com.example.ai_recorder_android.data.db.RecordingStatus
import com.example.ai_recorder_android.service.RecordingCoordinator
import com.example.ai_recorder_android.service.RecordingSessionState
import com.example.ai_recorder_android.ui.components.EmptyStateView
import com.example.ai_recorder_android.ui.components.ProcessingIndicator
import com.example.ai_recorder_android.ui.components.PulsingDot
import com.example.ai_recorder_android.ui.components.RecordingStatusChip
import com.example.ai_recorder_android.ui.components.RecordingTypeChip
import com.example.ai_recorder_android.ui.components.SectionCard
import com.example.ai_recorder_android.ui.components.SectionTitle
import com.example.ai_recorder_android.ui.theme.GradientAccentEnd
import com.example.ai_recorder_android.ui.theme.GradientAccentStart
import java.time.LocalTime
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** 品牌渐变（主 CTA / 品牌圆点共用，水平蓝→青）。 */
private val BrandGradientBrush =
    Brush.horizontalGradient(listOf(GradientAccentStart, GradientAccentEnd))

/**
 * 首页 UI 状态：工作台三问的直接回答——最近记录（[recentRecordings]）、
 * 已生成的 AI 笔记（[recentNotes]）、快速开始（UI 常驻 CTA）。
 *
 * - 「AI 笔记生成中」按 `PENDING/PROCESSING` 或 `COMPLETED 且 summary 未生成` 派生
 *   （对齐 [com.example.ai_recorder_android.data.db.RecordingDao.getRecordingsAwaitingPostProcessing] 语义）；
 *   会话活动中/过渡期视为 false——录音尚未停止、笔记尚未开始生成，全局指示由「录音进行中」横幅承担；
 * - [session] 来自共享 RecordingCoordinator（App 单例）：录音页被离开后服务仍在跑，
 *   首页据此显示「录音进行中」横幅。
 */
data class HomeUiState(
    val recentNotes: List<RecordingEntity> = emptyList(),
    val recentRecordings: List<RecordingEntity> = emptyList(),
    val hasProcessing: Boolean = false,
    val session: RecordingSessionState = RecordingSessionState(),
    val isLoading: Boolean = false,
)

/**
 * 首页 ViewModel：RecordingDao 构造注入（无 DI 框架，生产装配见 [factory]）。
 *
 * - **Flow 实时刷新**：AI 后处理链落库（转写/标题/笔记）后首页自动更新，
 *   「AI 处理中 → 已完成」无需手动刷新（与 HistoryViewModel 同源）；
 * - 录音会话状态直取 [RecordingCoordinator.sessionState]（App 单例）。
 */
class HomeViewModel(
    private val dao: RecordingDao,
    coordinator: RecordingCoordinator,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> =
        combine(dao.observeAllRecordings(), coordinator.sessionState) { recordings, session ->
            deriveHomeUiState(recordings, session)
        }
            // DAO 观察流异常时降级为空态（首页 CTA 常驻，不影响录音主链路）；记录日志便于排查
            .catch { e ->
                Log.w(TAG, "首页观察录音记录失败", e)
                emit(HomeUiState())
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = HomeUiState(isLoading = true),
            )

    companion object {
        private const val TAG = "HomeViewModel"

        /** 主页展示条数：最近录音 / 最近 AI 笔记。 */
        const val RECENT_RECORDINGS_LIMIT = 5
        const val RECENT_NOTES_LIMIT = 3

        fun factory(context: Context): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = context.applicationContext as App
                HomeViewModel(
                    dao = AppDatabase.getInstance(app).recordingDao(),
                    coordinator = app.recordingCoordinator,
                )
            }
        }
    }
}

/**
 * 纯派生逻辑（可 JVM 单测）：observeAllRecordings 已按 created_at 倒序，
 * 笔记筛选/截取保持该顺序（最新优先）。
 */
internal fun deriveHomeUiState(
    recordings: List<RecordingEntity>,
    session: RecordingSessionState,
): HomeUiState {
    // 待生成笔记的录音（对齐 getRecordingsAwaitingPostProcessing 语义）
    val hasPendingNotes = recordings.any {
        it.status == RecordingStatus.PENDING ||
            it.status == RecordingStatus.PROCESSING ||
            (it.status == RecordingStatus.COMPLETED && it.summary == null)
    }
    return HomeUiState(
        recentRecordings = recordings.take(HomeViewModel.RECENT_RECORDINGS_LIMIT),
        recentNotes = recordings.filter { it.summary != null }.take(HomeViewModel.RECENT_NOTES_LIMIT),
        // 会话活动中/过渡期（录音尚未停止、笔记尚未开始生成）：不显示全局处理指示，由「录音进行中」横幅承担
        hasProcessing = if (session.isActive || session.isBusy) false else hasPendingNotes,
        session = session,
    )
}

/**
 * 问候语（可 JVM 单测，按小时分段）：5–11 早上好 / 11–14 中午好 / 14–18 下午好 / 其余晚上好。
 */
internal fun homeGreeting(hour: Int): String = when (hour) {
    in 5..10 -> "早上好"
    in 11..13 -> "中午好"
    in 14..17 -> "下午好"
    else -> "晚上好"
}

/** 笔记预览（可 JVM 单测）：跳过 markdown `#` 标题行与空行，取首个内容行，避免预览出现标记符号。 */
internal fun notePreview(summary: String?): String? {
    if (summary.isNullOrBlank()) return null
    return summary.lines()
        .firstOrNull { line ->
            val trimmed = line.trim()
            trimmed.isNotEmpty() && !trimmed.startsWith("#")
        }
        ?.trim()
}

/**
 * 首页：AI 录音笔记工作台（品牌问候 + 快速开始 + 最近 AI 笔记 + 最近录音及处理状态）。
 *
 * 录音过程元素（音量条/实时转写/档位）不在首页——点击「开始录音」进入
 * [RecordingScreen] 专注界面（onStartRecordingClick 由 MainActivity 接线导航）。
 *
 * @param onStartRecordingClick 主 CTA：跳转录音页并自动开始（autoStart=true）
 * @param onOpenActiveRecordingClick 「录音进行中」横幅：返回仍在进行中的录音页
 * @param onRecordingClick 录音/笔记卡片 → detail/{recordingId}
 * @param onSeeAllHistoryClick 「查看全部」→ 历史页
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.factory(LocalContext.current)),
    onStartRecordingClick: () -> Unit,
    onOpenActiveRecordingClick: () -> Unit,
    onRecordingClick: (Long) -> Unit,
    onSeeAllHistoryClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // 问候语在一次会话内稳定（重组不翻转）
    val greeting = remember { homeGreeting(LocalTime.now().hour) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 品牌标识 + 简洁问候
        item(key = "header") {
            HeaderSection(greeting = greeting)
        }

        // 录音进行中横幅：离开录音页后会话仍在服务里继续，此横幅是回家的路
        if (uiState.session.isActive || uiState.session.isBusy) {
            item(key = "active_banner") {
                ActiveRecordingBanner(onClick = onOpenActiveRecordingClick)
            }
        }

        // 主 CTA：开始录音
        item(key = "cta") {
            StartRecordingButton(onClick = onStartRecordingClick)
        }

        // 全局处理中指示（各条目状态另有 chip 体现）
        if (uiState.hasProcessing) {
            item(key = "processing") {
                ProcessingIndicator(text = "正在生成 AI 笔记…")
            }
        }

        // 最近 AI 笔记（无笔记时整段隐藏，不占位）
        if (uiState.recentNotes.isNotEmpty()) {
            item(key = "notes_header") {
                SectionTitle(text = "最近 AI 笔记")
            }
            items(uiState.recentNotes, key = { "note_${it.id}" }) { recording ->
                NoteCard(
                    recording = recording,
                    onClick = { onRecordingClick(recording.id) },
                )
            }
        }

        // 最近录音及处理状态（无记录时空态兜底）
        if (uiState.recentRecordings.isNotEmpty()) {
            item(key = "recordings_header") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle(text = "最近录音", modifier = Modifier.weight(1f))
                    TextButton(onClick = onSeeAllHistoryClick) {
                        Text("查看全部")
                    }
                }
            }
            items(uiState.recentRecordings, key = { "rec_${it.id}" }) { recording ->
                RecordingCard(
                    recording = recording,
                    onClick = { onRecordingClick(recording.id) },
                )
            }
        } else if (!uiState.isLoading) {
            item(key = "empty") {
                EmptyStateView(
                    title = "还没有录音",
                    description = "点击上方「开始录音」，生成你的第一段 AI 笔记",
                    icon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_mic),
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
        }
    }
}

/** 顶部品牌区：渐变圆点 + 应用名 + 问候语 + 一句副文案。 */
@Composable
private fun HeaderSection(greeting: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(BrandGradientBrush),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "AI录音",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = greeting,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "开始一段新录音，或回顾最近的 AI 笔记",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 「录音进行中」横幅：脉冲红点 + 点按返回录音页。 */
@Composable
private fun ActiveRecordingBanner(onClick: () -> Unit, modifier: Modifier = Modifier) {
    SectionCard(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PulsingDot()
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "录音进行中",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "点按继续",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** 主 CTA：品牌渐变胶囊按钮（蓝→青）+ 麦克风图标，全屏宽醒目入口。 */
@Composable
private fun StartRecordingButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(50))
            .background(BrandGradientBrush)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(R.drawable.ic_mic),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "开始录音",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
        }
    }
}

/**
 * AI 笔记卡片：标题 + 日期时长 + 摘要两行预览（`#` 标题行不进预览）。
 * 点击 → 详情页查看完整笔记。
 */
@Composable
private fun NoteCard(
    recording: RecordingEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        contentPadding = PaddingValues(12.dp),
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = recordingTitle(recording),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = recordingMeta(recording),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = notePreview(recording.summary) ?: "点击查看完整笔记",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 最近录音卡片：标题 + 类型/状态 chip + 日期时长。
 * - 失败（FAILED）：errorMessage 红字；
 * - 笔记未生成：「AI 笔记生成中…」（已完成的预览见上方笔记区，不重复展示）。
 */
@Composable
private fun RecordingCard(
    recording: RecordingEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        contentPadding = PaddingValues(12.dp),
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = recordingTitle(recording),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            RecordingTypeChip(meetingMode = recording.meetingMode)
            Spacer(modifier = Modifier.width(4.dp))
            RecordingStatusChip(
                status = recording.status,
                hasNote = recording.summary != null,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = recordingMeta(recording),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            recording.status == RecordingStatus.FAILED -> Text(
                text = recording.errorMessage ?: "录音处理失败",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            // 笔记未生成（含 COMPLETED 后转写完成、笔记仍在生成的窗口）：处理中提示
            recording.summary == null -> Text(
                text = "AI 笔记生成中…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
