package com.example.ai_recorder_android.ui.screens

import android.content.Context
import android.util.Log
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import com.example.ai_recorder_android.ui.components.CardEmphasis
import com.example.ai_recorder_android.ui.components.EmptyStateView
import com.example.ai_recorder_android.ui.components.GradientPrimaryButton
import com.example.ai_recorder_android.ui.components.ProcessingIndicator
import com.example.ai_recorder_android.ui.components.PulsingDot
import com.example.ai_recorder_android.ui.components.RecordingListItem
import com.example.ai_recorder_android.ui.components.SectionCard
import com.example.ai_recorder_android.ui.components.SectionTitle
import com.example.ai_recorder_android.ui.theme.IconSize
import com.example.ai_recorder_android.ui.theme.LocalBrandSemanticColors
import com.example.ai_recorder_android.ui.theme.Spacing
import java.time.LocalTime
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

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

        /** 主页展示条数：最近录音（未生成笔记）/ 最近 AI 笔记。 */
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
    // 两区按 summary 是否已生成分区互补：已生成 →「最近 AI 笔记」，未生成（待处理/处理中/失败）→「最近录音」，
    // 同一条记录不会同时出现在两区
    val (noted, unNoted) = recordings.partition { it.summary != null }
    return HomeUiState(
        recentRecordings = unNoted.take(HomeViewModel.RECENT_RECORDINGS_LIMIT),
        recentNotes = noted.take(HomeViewModel.RECENT_NOTES_LIMIT),
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
        contentPadding = PaddingValues(
            start = Spacing.page,
            end = Spacing.page,
            top = Spacing.sm,
            bottom = Spacing.lg,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.sectionGap),
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
            GradientPrimaryButton(
                text = "开始录音",
                iconRes = R.drawable.ic_mic,
                onClick = onStartRecordingClick,
            )
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
                RecordingListItem(
                    recording = recording,
                    onClick = { onRecordingClick(recording.id) },
                    showTypeChip = false,
                )
            }
        }

        // 最近录音：仅未生成笔记的记录（待处理/处理中/失败；已生成笔记的在上方「最近 AI 笔记」区）
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
                RecordingListItem(
                    recording = recording,
                    onClick = { onRecordingClick(recording.id) },
                    // 录音区记录均未生成笔记，无预览可展示
                    showSummaryPreview = false,
                )
            }
        } else if (uiState.recentNotes.isNotEmpty()) {
            // 全部录音都已生成笔记：轻量提示 + 历史页入口（历史页唯一入口，不能随列表一起消失）
            item(key = "all_noted_hint") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "近期录音都已生成笔记",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onSeeAllHistoryClick) {
                        Text("查看全部历史")
                    }
                }
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
                            modifier = Modifier.size(IconSize.xl),
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
                    .background(LocalBrandSemanticColors.current.accentGradientBrush),
            )
            Spacer(modifier = Modifier.width(Spacing.sm))
            Text(
                text = "AI录音",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(modifier = Modifier.height(Spacing.lg))
        Text(
            text = greeting,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
            text = "开始一段新录音，或回顾最近的 AI 笔记",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 「录音进行中」横幅：脉冲红点 + 点按返回录音页（强调层级卡，含按压反馈）。 */
@Composable
private fun ActiveRecordingBanner(onClick: () -> Unit, modifier: Modifier = Modifier) {
    SectionCard(
        contentPadding = PaddingValues(horizontal = Spacing.cardPad, vertical = Spacing.md),
        onClick = onClick,
        emphasis = CardEmphasis.High,
        modifier = modifier,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PulsingDot()
            Spacer(modifier = Modifier.width(Spacing.sm))
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
