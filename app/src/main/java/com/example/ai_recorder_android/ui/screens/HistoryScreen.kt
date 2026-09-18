package com.example.ai_recorder_android.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import com.example.ai_recorder_android.R
import com.example.ai_recorder_android.Routes
import com.example.ai_recorder_android.data.db.AppDatabase
import com.example.ai_recorder_android.data.db.RecordingDao
import com.example.ai_recorder_android.data.db.RecordingEntity
import com.example.ai_recorder_android.data.db.RecordingsByDay
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 历史列表 UI 状态：按天分组结果 + 加载标志。 */
data class HistoryUiState(
    val groups: List<RecordingsByDay> = emptyList(),
    val isLoading: Boolean = false,
)

/**
 * 历史列表 ViewModel：RecordingDao 构造注入。
 *
 * - 空白查询 → [RecordingDao.getRecordingsGroupedByDay]（按天分组，组序/组内均倒序）；
 * - 非空白查询 → [RecordingDao.searchRecordings]（title OR transcription LIKE），
 *   结果同样按天分组（对齐 PC `refresh()` 对搜索结果分组的行为）；
 * - 300ms 防抖由 UI 层 `snapshotFlow` + `debounce` 完成（任务书指定），
 *   本类只负责把防抖后的查询映射为加载结果。
 */
class HistoryViewModel(
    private val dao: RecordingDao,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) : ViewModel() {

    private val query = MutableStateFlow("")

    val uiState: StateFlow<HistoryUiState> = query
        .mapLatest { raw -> load(raw) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = HistoryUiState(isLoading = true),
        )

    fun onQueryChange(newQuery: String) {
        query.value = newQuery
    }

    private suspend fun load(rawQuery: String): HistoryUiState {
        val trimmed = rawQuery.trim()
        val groups = if (trimmed.isEmpty()) {
            dao.getRecordingsGroupedByDay(zoneId)
        } else {
            dao.searchRecordings(trimmed).groupByDay(zoneId)
        }
        return HistoryUiState(groups = groups)
    }

    /** 搜索结果按本地日期（yyyy-MM-dd）分组；searchRecordings 已倒序，groupBy 保持首次出现顺序。 */
    private fun List<RecordingEntity>.groupByDay(zoneId: ZoneId): List<RecordingsByDay> =
        groupBy { Instant.ofEpochMilli(it.createdAt).atZone(zoneId).toLocalDate().toString() }
            .map { (day, recordings) -> RecordingsByDay(day, recordings) }

    companion object {
        /** 搜索防抖时长（毫秒），UI 层 `snapshotFlow` + `debounce` 使用。 */
        const val SEARCH_DEBOUNCE_MS = 300L
    }
}

/**
 * 录音历史列表：搜索栏 + 按天分组列表（今天/昨天/其他日期）+ 空状态。
 *
 * 点击记录项 → `navController.navigate("detail/{recordingId}")`。
 */
@OptIn(FlowPreview::class)
@Composable
fun HistoryScreen(
    navController: NavHostController,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
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
        TextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("搜索标题或转写内容") },
            leadingIcon = { Icon(painter = painterResource(R.drawable.ic_search), contentDescription = null) },
            singleLine = true,
        )
        when {
            uiState.isLoading -> LoadingState()
            uiState.groups.isEmpty() -> EmptyState()
            else -> RecordingList(
                groups = uiState.groups,
                onRecordingClick = { id -> navController.navigate("${Routes.DETAIL}/$id") },
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
    LazyColumn(modifier = modifier.fillMaxSize()) {
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

/** 单条录音：标题（或默认"录音 yyyy-MM-dd HH:mm:ss"）+ 日期 · 时长。 */
@Composable
private fun RecordingItem(
    recording: RecordingEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = {
            Text(
                text = recordingTitle(recording),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = { Text(text = recordingMeta(recording)) },
        modifier = modifier.clickable(onClick = onClick),
    )
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

/** 空状态：居中图标 + "暂无录音记录"。 */
@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_history),
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "暂无录音记录",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
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