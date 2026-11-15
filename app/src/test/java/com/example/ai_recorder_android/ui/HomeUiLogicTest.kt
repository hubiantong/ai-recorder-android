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
 * - [deriveHomeUiState]：笔记筛选（summary 非空）/ 条数上限 / 处理中判定（含 COMPLETED 无笔记窗口）/ 会话透传；
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
        // 录音区展示全部（含处理中/失败，状态由 UI chip 体现）
        assertEquals(4, state.recentRecordings.size)
    }

    @Test
    fun `笔记与录音截取上限`() {
        val recordings = (1L..7L).map { id -> recording(id = id, summary = "笔记$id") }
        val state = deriveHomeUiState(recordings, RecordingSessionState())
        assertEquals(HomeViewModel.RECENT_RECORDINGS_LIMIT, state.recentRecordings.size)
        assertEquals(HomeViewModel.RECENT_NOTES_LIMIT, state.recentNotes.size)
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
