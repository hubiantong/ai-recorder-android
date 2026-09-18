package com.example.ai_recorder_android.data.repo

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * 用户运行时配置（DataStore Preferences 持久化），字段对齐 PC `core/user_config.py` 的 UserConfig。
 *
 * 与 PC 的差异（Android 侧决策）：
 * - **theme 默认 `system`**（跟随系统，PC 默认 light/dark 二选一）；
 * - 不含 provider/base_url/api_key/vlm_model/llm_model 等密钥/端点字段——
 *   本应用凭证由 secrets.properties 经 BuildConfig 注入，不做运行期密钥输入；
 * - [asrTier] 读写均做清洗：空串/非法值回退默认 `free`（对齐 PC `is_transcription_configured`
 *   的 `bool(self.asr_tier)` 语义——空 tier 视为未配置）。
 *
 * 生产入口 [fromContext] 用 `preferencesDataStore` 委托（单例，文件 `user_config.preferences_pb`）；
 * 测试用 `PreferenceDataStoreFactory.create(produceFile = { 临时文件 })` 注入。
 */
class UserConfigRepository(private val dataStore: DataStore<Preferences>) {

    /**
     * 用户配置流：DataStore 每次变更发射最新值，未写入字段回退默认值。
     *
     * 读取失败兜底（官方 DataStore 错误处理范式）：文件损坏/IO 异常时记录日志并
     * 发射 [emptyPreferences]，下游 [toUserConfig] 对缺失键回退默认值，应用可正常启动；
     * 非 IO 异常原样抛出，不吞。
     */
    val userConfig: Flow<UserConfig> = dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                Log.e(TAG, "读取用户配置失败（文件可能损坏），回退默认值", exception)
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { it.toUserConfig() }

    // ---------- 转写 ----------

    /** 设置 ASR tier（free/realtime/pro_lite/pro_pro）；空串/非法值落库为 `free`。 */
    suspend fun setAsrTier(tier: String) {
        dataStore.edit { it[Keys.ASR_TIER] = sanitizeTier(tier) }
    }

    /** 设置转写语言（zh=中文 / en=英文 / 空=自动检测）。 */
    suspend fun setLanguage(language: String) {
        dataStore.edit { it[Keys.LANGUAGE] = language }
    }

    // ---------- VAD 自动分段 ----------

    suspend fun setVadEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.VAD_ENABLED] = enabled }
    }

    /** 批量设置 VAD 四参数（对齐 PC vad_segmenter 默认值：0.02 / 1.5s / 1.0s / 270s）。 */
    suspend fun setVadParams(
        silenceThreshold: Double,
        silenceDuration: Double,
        minSegment: Double,
        maxSegment: Double,
    ) {
        dataStore.edit {
            it[Keys.VAD_SILENCE_THRESHOLD] = silenceThreshold
            it[Keys.VAD_SILENCE_DURATION] = silenceDuration
            it[Keys.VAD_MIN_SEGMENT] = minSegment
            it[Keys.VAD_MAX_SEGMENT] = maxSegment
        }
    }

    // ---------- 会议模式 ----------

    suspend fun setMeetingMode(enabled: Boolean) {
        dataStore.edit { it[Keys.MEETING_MODE] = enabled }
    }

    /** 会议音源：system / mixed / mic（默认 mixed）。 */
    suspend fun setMeetingAudioSource(source: String) {
        dataStore.edit { it[Keys.MEETING_AUDIO_SOURCE] = source }
    }

    /** 截屏模式：full / region（默认 full）。 */
    suspend fun setMeetingScreenshotMode(mode: String) {
        dataStore.edit { it[Keys.MEETING_SCREENSHOT_MODE] = mode }
    }

    /** 开始录音时自动收起为悬浮窗。 */
    suspend fun setMeetingAutoFloat(autoFloat: Boolean) {
        dataStore.edit { it[Keys.MEETING_AUTO_FLOAT] = autoFloat }
    }

    /** 截屏全局快捷键（PC 默认 ctrl+alt+s；Android 侧由 todo 16 决定是否可用）。 */
    suspend fun setMeetingHotkey(hotkey: String) {
        dataStore.edit { it[Keys.MEETING_HOTKEY] = hotkey }
    }

    /** 截屏显示器序号（0=主屏）。 */
    suspend fun setMeetingScreenIndex(index: Int) {
        dataStore.edit { it[Keys.MEETING_SCREEN_INDEX] = index }
    }

    /** 会议模式系统音频 VAD 阈值（默认 0.01，比麦克风略低）。 */
    suspend fun setMeetingVadSilenceThreshold(threshold: Double) {
        dataStore.edit { it[Keys.MEETING_VAD_SILENCE_THRESHOLD] = threshold }
    }

    // ---------- UI ----------

    /** 主题：system / light / dark（默认 system=跟随系统）。 */
    suspend fun setTheme(theme: String) {
        dataStore.edit { it[Keys.THEME] = theme }
    }

    /** 首启引导是否已完成。 */
    suspend fun setOnboardingDone(done: Boolean) {
        dataStore.edit { it[Keys.ONBOARDING_DONE] = done }
    }

    // ---------- 内部 ----------

    private fun Preferences.toUserConfig(): UserConfig {
        val d = UserConfig()
        return UserConfig(
            asrTier = sanitizeTier(this[Keys.ASR_TIER] ?: d.asrTier),
            language = this[Keys.LANGUAGE] ?: d.language,
            vadEnabled = this[Keys.VAD_ENABLED] ?: d.vadEnabled,
            vadSilenceThreshold = this[Keys.VAD_SILENCE_THRESHOLD] ?: d.vadSilenceThreshold,
            vadSilenceDuration = this[Keys.VAD_SILENCE_DURATION] ?: d.vadSilenceDuration,
            vadMinSegment = this[Keys.VAD_MIN_SEGMENT] ?: d.vadMinSegment,
            vadMaxSegment = this[Keys.VAD_MAX_SEGMENT] ?: d.vadMaxSegment,
            meetingMode = this[Keys.MEETING_MODE] ?: d.meetingMode,
            meetingAudioSource = this[Keys.MEETING_AUDIO_SOURCE] ?: d.meetingAudioSource,
            meetingScreenshotMode = this[Keys.MEETING_SCREENSHOT_MODE] ?: d.meetingScreenshotMode,
            meetingAutoFloat = this[Keys.MEETING_AUTO_FLOAT] ?: d.meetingAutoFloat,
            meetingHotkey = this[Keys.MEETING_HOTKEY] ?: d.meetingHotkey,
            meetingScreenIndex = this[Keys.MEETING_SCREEN_INDEX] ?: d.meetingScreenIndex,
            meetingVadSilenceThreshold = this[Keys.MEETING_VAD_SILENCE_THRESHOLD] ?: d.meetingVadSilenceThreshold,
            theme = this[Keys.THEME] ?: d.theme,
            onboardingDone = this[Keys.ONBOARDING_DONE] ?: d.onboardingDone,
        )
    }

    private object Keys {
        val ASR_TIER = stringPreferencesKey("asr_tier")
        val LANGUAGE = stringPreferencesKey("language")
        val VAD_ENABLED = booleanPreferencesKey("vad_enabled")
        val VAD_SILENCE_THRESHOLD = doublePreferencesKey("vad_silence_threshold")
        val VAD_SILENCE_DURATION = doublePreferencesKey("vad_silence_duration")
        val VAD_MIN_SEGMENT = doublePreferencesKey("vad_min_segment")
        val VAD_MAX_SEGMENT = doublePreferencesKey("vad_max_segment")
        val MEETING_MODE = booleanPreferencesKey("meeting_mode")
        val MEETING_AUDIO_SOURCE = stringPreferencesKey("meeting_audio_source")
        val MEETING_SCREENSHOT_MODE = stringPreferencesKey("meeting_screenshot_mode")
        val MEETING_AUTO_FLOAT = booleanPreferencesKey("meeting_auto_float")
        val MEETING_HOTKEY = stringPreferencesKey("meeting_hotkey")
        val MEETING_SCREEN_INDEX = intPreferencesKey("meeting_screen_index")
        val MEETING_VAD_SILENCE_THRESHOLD = doublePreferencesKey("meeting_vad_silence_threshold")
        val THEME = stringPreferencesKey("theme")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
    }

    companion object {
        private const val TAG = "UserConfigRepository"

        /** 合法 ASR tier（对齐 PC `core/transcription/tiers.py`）。 */
        val VALID_ASR_TIERS = setOf("free", "realtime", "pro_lite", "pro_pro")

        private val Context.userConfigDataStore by preferencesDataStore(name = "user_config")

        /** 生产入口：应用级单例 DataStore（文件 `user_config.preferences_pb`）。 */
        fun fromContext(context: Context): UserConfigRepository =
            UserConfigRepository(context.userConfigDataStore)

        /** 空串/非法 tier 回退默认 `free`（对齐 PC 空 tier=未配置语义）。 */
        fun sanitizeTier(tier: String?): String =
            if (tier != null && tier in VALID_ASR_TIERS) tier else "free"
    }
}

/**
 * 用户配置值对象（不可变），默认值对齐 PC `core/user_config.py`：
 * - VAD 四参数默认 0.02 / 1.5s / 1.0s / 270s（PC vad_segmenter 默认值）；
 * - 会议模式参数默认 mixed / full / auto_float=true / ctrl+alt+s / 主屏 / 0.01；
 * - theme 默认 `system`（Android 跟随系统，PC 为 light/dark）；
 * - onboarding_done 默认 false。
 */
data class UserConfig(
    val asrTier: String = "free",
    val language: String = "zh",
    val vadEnabled: Boolean = true,
    val vadSilenceThreshold: Double = 0.02,
    val vadSilenceDuration: Double = 1.5,
    val vadMinSegment: Double = 1.0,
    val vadMaxSegment: Double = 270.0,
    val meetingMode: Boolean = false,
    val meetingAudioSource: String = "mixed",
    val meetingScreenshotMode: String = "full",
    val meetingAutoFloat: Boolean = true,
    val meetingHotkey: String = "ctrl+alt+s",
    val meetingScreenIndex: Int = 0,
    val meetingVadSilenceThreshold: Double = 0.01,
    val theme: String = "system",
    val onboardingDone: Boolean = false,
)