package com.example.ai_recorder_android.ui

import com.example.ai_recorder_android.data.db.RecordingEntity
import com.example.ai_recorder_android.data.db.RecordingStatus
import com.example.ai_recorder_android.service.RecordingPhase
import com.example.ai_recorder_android.service.RecordingSessionState
import com.example.ai_recorder_android.ui.screens.HomeViewModel
import com.example.ai_recorder_android.ui.screens.deriveHomeUiState
import com.example.ai_recorder_android.ui.screens.homeGreeting
import com.example.ai_recorder_android.ui.components.notePreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 首页纯派生逻辑单测（无 Android 依赖，可 JVM 直跑）：
 * - [deriveHomeUiState]：笔记/录音按 summary 分区互补（已生成 → 笔记区，未生成/处理中/失败 → 录音区）/ 条数上限 / 处理中判定（含 COMPLETED 无笔记窗口）/ 会话透传；
 * - [homeGreeting]：时段边界；
 * - [notePreview]：跳过 markdown `#` 标题行取首个内容行。
 */
class HomeUiLogicTest {

    private fun recording(
        id: Long,
        summary: String? = null,
        status: RecordingStatus = RecordingStatus.COMPLETED,
    ) = RecordingEntity(
        id = id,
        audioPath = "test_$id.wav",
        summary = summary,
        status = status,
        createdAt = id,
    )

    // ---------- deriveHomeUiState ----------

    @Test
    fun `空列表时各分区为空且无处理中`() {
        val state = deriveHomeUiState(emptyList(), RecordingSessionState())
        assertTrue(state.recentRecordings.isEmpty())
        assertTrue(state.recentNotes.isEmpty())
        assertFalse(state.hasProcessing)
        assertFalse(state.isLoading)
    }

    @Test
    fun `笔记只取有 summary 的记录并保持输入倒序`() {
        val recordings = listOf(
            recording(id = 1, summary = "笔记1"),
            recording(id = 2, status = RecordingStatus.PROCESSING),
            recording(id = 3, summary = "笔记3"),
            recording(id = 4, status = RecordingStatus.FAILED),
        )
        val state = deriveHomeUiState(recordings, RecordingSessionState())
        // 输入为 created_at 倒序（DAO 保证），筛选保持顺序 → 最新笔记优先
        assertEquals(listOf("笔记1", "笔记3"), state.recentNotes.map { it.summary })
        // 录音区只保留未生成笔记的记录（处理中/失败），已生成笔记的不重复出现
        assertEquals(listOf(2L, 4L), state.recentRecordings.map { it.id })
    }

    @Test
    fun `笔记与录音截取上限`() {
        // 混合数据（created_at 倒序，id 越大越新）：id 5-9 无笔记，id 1-4 有笔记
        val recordings = (9L downTo 1L).map { id ->
            if (id <= 4) recording(id = id, summary = "笔记$id") else recording(id = id)
        }
        val state = deriveHomeUiState(recordings, RecordingSessionState())
        assertEquals(HomeViewModel.RECENT_RECORDINGS_LIMIT, state.recentRecordings.size)
        assertEquals(HomeViewModel.RECENT_NOTES_LIMIT, state.recentNotes.size)
        assertEquals(listOf(9L, 8L, 7L, 6L, 5L), state.recentRecordings.map { it.id })
        assertEquals(listOf(4L, 3L, 2L), state.recentNotes.map { it.id })
    }

    @Test
    fun `两区按是否已生成笔记互补不重复`() {
        val recordings = listOf(
            recording(id = 1, summary = "笔记1"),
            recording(id = 2, summary = null, status = RecordingStatus.COMPLETED),
            recording(id = 3, status = RecordingStatus.FAILED),
        )
        val state = deriveHomeUiState(recordings, RecordingSessionState())
        val noteIds = state.recentNotes.map { it.id }.toSet()
        val recIds = state.recentRecordings.map { it.id }.toSet()
        // 每条记录恰好出现在一个区（失败记录归录音区）
        assertEquals(setOf(1L, 2L, 3L), noteIds + recIds)
        assertTrue(noteIds.intersect(recIds).isEmpty())
    }

    @Test
    fun `全部录音已生成笔记时录音区为空`() {
        val recordings = (1L..4L).map { id -> recording(id = id, summary = "笔记$id") }
        val state = deriveHomeUiState(recordings, RecordingSessionState())
        assertTrue(state.recentRecordings.isEmpty())
        assertEquals(3, state.recentNotes.size)
    }

    @Test
    fun `处理中判定覆盖 PENDING PROCESSING 与 COMPLETED 无笔记窗口`() {
        assertTrue(
            deriveHomeUiState(
                listOf(recording(id = 1, status = RecordingStatus.PENDING)),
                RecordingSessionState(),
            ).hasProcessing,
        )
        assertTrue(
            deriveHomeUiState(
                listOf(recording(id = 1, status = RecordingStatus.PROCESSING)),
                RecordingSessionState(),
            ).hasProcessing,
        )
        // 转写已落库（COMPLETED）但笔记仍在生成——最常见的处理中窗口
        assertTrue(
            deriveHomeUiState(
                listOf(recording(id = 1, summary = null, status = RecordingStatus.COMPLETED)),
                RecordingSessionState(),
            ).hasProcessing,
        )
    }

    @Test
    fun `已完成有笔记与失败均不算处理中`() {
        assertFalse(
            deriveHomeUiState(
                listOf(recording(id = 1, summary = "笔记")),
                RecordingSessionState(),
            ).hasProcessing,
        )
        assertFalse(
            deriveHomeUiState(
                listOf(recording(id = 1, status = RecordingStatus.FAILED)),
                RecordingSessionState(),
            ).hasProcessing,
        )
    }

    @Test
    fun `录音会话活动中与过渡期不显示处理中指示`() {
        // 录音尚未停止、笔记尚未开始生成：全局指示由「录音进行中」横幅承担
        val recordings = listOf(recording(id = 1, status = RecordingStatus.PROCESSING))
        assertFalse(
            deriveHomeUiState(
                recordings,
                RecordingSessionState(phase = RecordingPhase.RECORDING),
            ).hasProcessing,
        )
        assertFalse(
            deriveHomeUiState(
                recordings,
                RecordingSessionState(phase = RecordingPhase.STOPPING),
            ).hasProcessing,
        )
        // 会话结束后同一批记录恢复显示
        assertTrue(
            deriveHomeUiState(recordings, RecordingSessionState()).hasProcessing,
        )
    }

    @Test
    fun `会话状态原样透传`() {
        val session = RecordingSessionState(phase = RecordingPhase.RECORDING, recordingId = 42L)
        val state = deriveHomeUiState(emptyList(), session)
        assertEquals(session, state.session)
        assertTrue(state.session.isActive)
    }

    // ---------- homeGreeting ----------

    @Test
    fun `问候语时段边界`() {
        assertEquals("晚上好", homeGreeting(0))
        assertEquals("晚上好", homeGreeting(4))
        assertEquals("早上好", homeGreeting(5))
        assertEquals("早上好", homeGreeting(10))
        assertEquals("中午好", homeGreeting(11))
        assertEquals("中午好", homeGreeting(13))
        assertEquals("下午好", homeGreeting(14))
        assertEquals("下午好", homeGreeting(17))
        assertEquals("晚上好", homeGreeting(18))
        assertEquals("晚上好", homeGreeting(23))
    }

    // ---------- notePreview ----------

    @Test
    fun `预览跳过标题行取首个内容行`() {
        assertEquals(
            "- 要点一",
            notePreview("## 会议摘要\n- 要点一\n\n## 下一步\n- 待办"),
        )
    }

    @Test
    fun `预览无标题行时取首行`() {
        assertEquals("纯文本笔记内容", notePreview("纯文本笔记内容\n第二行"))
    }

    @Test
    fun `预览对空摘要返回 null`() {
        assertNull(notePreview(null))
        assertNull(notePreview("   "))
        assertNull(notePreview("## 只有标题没有内容"))
    }
}
