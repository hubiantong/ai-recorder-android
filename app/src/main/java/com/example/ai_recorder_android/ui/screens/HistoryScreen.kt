package com.example.ai_recorder_android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ai_recorder_android.R
import com.example.ai_recorder_android.data.db.AppDatabase
import com.example.ai_recorder_android.data.db.RecordingDao
import com.example.ai_recorder_android.data.db.RecordingEntity
import com.example.ai_recorder_android.data.db.RecordingsByDay
import com.example.ai_recorder_android.ui.components.EmptyStateView
import com.example.ai_recorder_android.ui.components.RecordingStatusChip
import com.example.ai_recorder_android.ui.components.RecordingTypeChip
import com.example.ai_recorder_android.ui.components.SectionCard
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 历史列表 UI 状态：按天分组结果 + 加载/错误标志 + 当前查询（区分「无记录」与「搜索无结果」）。
 */
data class HistoryUiState(
    val groups: List<RecordingsByDay> = emptyList(),
    val isLoading: Boolean = false,
    val query: String = "",
    val error: String? = null,
)

/**
 * 历史列表 ViewModel：RecordingDao 构造注入。
 *
 * - 空白查询 → [RecordingDao.observeAllRecordings]；非空白 → [RecordingDao.observeSearchRecordings]
 *   （title OR transcription LIKE），结果按天分组；
 * - **Flow 实时刷新**：AI 后处理链落库（转写/标题/笔记）后列表自动更新，
 *   「AI 处理中 → 已完成」无需手动刷新；
 * - 查询异常 → [HistoryUiState.error]（错误态 + 重试）；
 * - 300ms 防抖由 UI 层 `snapshotFlow` + `debounce` 完成（任务书指定）。
 */
class HistoryViewModel(
    private val dao: RecordingDao,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private val query = MutableStateFlow("")

    /** 重试触发器：递增使 uiState 链重新订阅（错误态重试入口）。 */
    private val retryTrigger = MutableStateFlow(0)

    val uiState: StateFlow<HistoryUiState> = combine(query, retryTrigger) { q, _ -> q }
        .flatMapLatest { raw -> observe(raw) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HistoryUiState(isLoading = true),
        )

    fun onQueryChange(newQuery: String) {
        query.value = newQuery
    }

    /** 错误态重试：重新订阅观察流。 */
    fun retry() {
        retryTrigger.value += 1
    }

    private fun observe(rawQuery: String): Flow<HistoryUiState> {
        val trimmed = rawQuery.trim()
        val source = if (trimmed.isEmpty()) {
            dao.observeAllRecordings()
        } else {
            dao.observeSearchRecordings(trimmed)
        }
        return source
            .map { list ->
                HistoryUiState(groups = list.groupByDay(zoneId), query = trimmed, isLoading = false)
            }
            .catch { e ->
                emit(
                    HistoryUiState(
                        query = trimmed,
                        isLoading = false,
                        error = e.message ?: "加载录音记录失败",
                    ),
                )
            }
    }

    /** 搜索结果按本地日期（yyyy-MM-dd）分组；observe* 已倒序，groupBy 保持首次出现顺序。 */
    private fun List<RecordingEntity>.groupByDay(zoneId: ZoneId): List<RecordingsByDay> =
        groupBy { Instant.ofEpochMilli(it.createdAt).atZone(zoneId).toLocalDate().toString() }
            .map { (day, recordings) -> RecordingsByDay(day, recordings) }

    companion object {
        /** 搜索防抖时长（毫秒），UI 层 `snapshotFlow` + `debounce` 使用。 */
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}

/**
 * 录音历史列表：搜索栏 + 文件导入入口 + 按天分组列表（今天/昨天/其他日期）。
 *
 * 记录项卡片：标题 + 类型/状态 chip + 日期时长 + AI 摘要预览（处理中/失败有独立提示）。
 * 独立状态：加载中 / 无录音记录 / 搜索无结果 / 加载错误（可重试）。
 *
 * 点击记录项 → [onRecordingClick]（MainActivity 导航到 detail/{recordingId}）。
 */
@OptIn(FlowPreview::class)
@Composable
fun HistoryScreen(
    onRecordingClick: (Long) -> Unit,
    onFileUploadClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val viewModel: HistoryViewModel = viewModel {
        HistoryViewModel(AppDatabase.getInstance(context).recordingDao())
    }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var searchQuery by rememberSaveable { mutableStateOf("") }

    // 300ms 防抖：输入停止后才触发搜索（对齐 PC QTimer 250ms 防抖语义）
    LaunchedEffect(Unit) {
        snapshotFlow { searchQuery }
            .debounce(HistoryViewModel.SEARCH_DEBOUNCE_MS)
            .collect { viewModel.onQueryChange(it) }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("搜索标题或转写内容") },
                leadingIcon = { Icon(painter = painterResource(R.drawable.ic_search), contentDescription = null) },
                singleLine = true,
            )
            // 文件转写导入入口（上传本地音频 → 转写 → 落库为录音记录）
            IconButton(onClick = onFileUploadClick) {
                Icon(
                    painter = painterResource(R.drawable.ic_upload),
                    contentDescription = "文件转写",
                )
            }
        }
        when {
            uiState.error != null -> ErrorState(
                message = uiState.error ?: "加载录音记录失败",
                onRetry = viewModel::retry,
            )
            uiState.isLoading -> LoadingState()
            uiState.groups.isEmpty() && uiState.query.isNotBlank() -> SearchEmptyState(
                query = uiState.query,
            )
            uiState.groups.isEmpty() -> EmptyState()
            else -> RecordingList(
                groups = uiState.groups,
                onRecordingClick = onRecordingClick,
            )
        }
    }
}

/** 按天分组列表：组头（今天/昨天/日期）+ 组内记录项。 */
@Composable
private fun RecordingList(
    groups: List<RecordingsByDay>,
    onRecordingClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        groups.forEach { group ->
            item(key = "header_${group.day}") {
                HistorySectionHeader(day = group.day)
            }
            items(group.recordings, key = { it.id }) { recording ->
                RecordingItem(
                    recording = recording,
                    onClick = { onRecordingClick(recording.id) },
                )
            }
        }
    }
}

/** 分组标题：今天 / 昨天 / 其他日期（yyyy年M月d日）。 */
@Composable
private fun HistorySectionHeader(day: String, modifier: Modifier = Modifier) {
    Text(
        text = dayLabel(day),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/**
 * 单条录音卡片：标题 + 类型/状态 chip + 日期 · 时长 + AI 摘要预览。
 * - 处理中（COMPLETED 且 summary 未生成）：「AI 笔记生成中…」；
 * - 失败（FAILED）：errorMessage 红字；
 * - 已完成：摘要两行省略预览。
 */
@Composable
private fun RecordingItem(
    recording: RecordingEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        contentPadding = PaddingValues(12.dp),
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
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
        Spacer(modifier = Modifier.height(8.dp))
        when {
            recording.status == com.example.ai_recorder_android.data.db.RecordingStatus.FAILED -> Text(
                text = recording.errorMessage ?: "录音处理失败",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            recording.summary != null -> Text(
                text = recording.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            else -> Text(
                text = "AI 笔记生成中…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 加载中：居中转圈。 */
@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
    }
}

/** 空状态：尚无任何录音记录。 */
@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    EmptyStateView(
        title = "暂无录音记录",
        description = "点击底部「首页」开始录音，或在右上角导入音频文件转写",
        icon = {
            Icon(
                painter = painterResource(R.drawable.ic_history),
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = modifier.fillMaxSize(),
    )
}

/** 搜索无结果：与「无记录」区分的独立状态。 */
@Composable
private fun SearchEmptyState(query: String, modifier: Modifier = Modifier) {
    EmptyStateView(
        title = "未找到相关录音",
        description = "没有与「$query」匹配的标题或转写内容，换个关键词试试",
        icon = {
            Icon(
                painter = painterResource(R.drawable.ic_search),
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = modifier.fillMaxSize(),
    )
}

/** 加载错误：错误信息 + 重试按钮。 */
@Composable
private fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    EmptyStateView(
        title = "加载失败",
        description = message,
        icon = {
            Icon(
                painter = painterResource(R.drawable.ic_history),
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.error,
            )
        },
        action = {
            Button(onClick = onRetry) {
                Text("重试")
            }
        },
        modifier = modifier.fillMaxSize(),
    )
}

// ---------- 纯函数（可 JVM 单测） ----------

/**
 * 分组标题映射：今天 / 昨天 / 其他日期（yyyy年M月d日，中文 locale）。
 * [day] 为 DAO 生成的 "yyyy-MM-dd" 键；解析失败时原样返回（防御脏数据）。
 */
internal fun dayLabel(day: String, today: LocalDate = LocalDate.now()): String {
    val date = runCatching { LocalDate.parse(day) }.getOrNull() ?: return day
    return when (date) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> date.format(DateTimeFormatter.ofPattern("yyyy年M月d日", Locale.CHINA))
    }
}

/** 列表标题：title 非空白用 title，否则默认 "录音 yyyy-MM-dd HH:mm:ss"（对齐 PC `_format_title`）。 */
internal fun recordingTitle(
    recording: RecordingEntity,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    recording.title?.takeIf { it.isNotBlank() }?.let { return it }
    return "录音 " + Instant.ofEpochMilli(recording.createdAt)
        .atZone(zoneId)
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.CHINA))
}

/** 副标题：MM-dd HH:mm · mm:ss（时长缺失时省略，对齐 PC `_format_meta`）。 */
internal fun recordingMeta(
    recording: RecordingEntity,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    val date = Instant.ofEpochMilli(recording.createdAt)
        .atZone(zoneId)
        .format(DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.CHINA))
    val duration = recording.audioDurationSeconds?.let { formatDuration(it) }
    return if (duration != null) "$date · $duration" else date
}

/** 时长格式化：mm:ss；≥1 小时显示 h:mm:ss；null 显示 00:00。 */
internal fun formatDuration(seconds: Double?): String {
    if (seconds == null) return "00:00"
    val total = seconds.toLong().coerceAtLeast(0L)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val secs = total % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, secs)
    } else {
        "%02d:%02d".format(minutes, secs)
    }
}
