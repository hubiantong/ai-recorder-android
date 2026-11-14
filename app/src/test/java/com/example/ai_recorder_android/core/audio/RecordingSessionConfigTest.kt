package com.example.ai_recorder_android.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [RecordingSessionConfig] 纯 JVM 语义测试：音源词表防御 + 授权需求判定。
 */
class RecordingSessionConfigTest {

    @Test
    fun default_isMicRecording_withoutProjection() {
        val config = RecordingSessionConfig.DEFAULT

        assertEquals(RecordingSessionConfig.SOURCE_MIC, config.audioSource)
        assertFalse(config.meetingMode)
        assertFalse(config.needsProjection)
    }

    @Test
    fun needsProjection_forSystemAndMixedSources() {
        assertTrue(
            RecordingSessionConfig(audioSource = RecordingSessionConfig.SOURCE_SYSTEM).needsProjection,
        )
        assertTrue(
            RecordingSessionConfig(audioSource = RecordingSessionConfig.SOURCE_MIXED).needsProjection,
        )
    }

    @Test
    fun micSource_neverNeedsProjection_evenInMeetingMode() {
        // 会议模式仅语义落库，mic 音源不改变采集器/授权需求
        val config = RecordingSessionConfig(meetingMode = true, audioSource = RecordingSessionConfig.SOURCE_MIC)

        assertTrue(config.meetingMode)
        assertFalse(config.needsProjection)
    }

    @Test
    fun sanitizeSource_keepsValidSourcesAndFallsBackToMic() {
        assertEquals(
            RecordingSessionConfig.SOURCE_SYSTEM,
            RecordingSessionConfig.sanitizeSource(RecordingSessionConfig.SOURCE_SYSTEM),
        )
        assertEquals(
            RecordingSessionConfig.SOURCE_MIXED,
            RecordingSessionConfig.sanitizeSource(RecordingSessionConfig.SOURCE_MIXED),
        )
        assertEquals(
            RecordingSessionConfig.SOURCE_MIC,
            RecordingSessionConfig.sanitizeSource(RecordingSessionConfig.SOURCE_MIC),
        )
        assertEquals("空串回退 mic", RecordingSessionConfig.SOURCE_MIC, RecordingSessionConfig.sanitizeSource(""))
        assertEquals("null 回退 mic", RecordingSessionConfig.SOURCE_MIC, RecordingSessionConfig.sanitizeSource(null))
        assertEquals(
            "非法值回退 mic",
            RecordingSessionConfig.SOURCE_MIC,
            RecordingSessionConfig.sanitizeSource("bluetooth"),
        )
    }
}
