package com.example.ai_recorder_android.data.repo

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * UserConfigRepository 单测（纯 JVM，`PreferenceDataStoreFactory` 临时文件注入）：
 * 空库默认值、全字段写→读回、脏数据 tier 回退 `free`（读路径）、
 * setter 清洗（写路径）、写→"重启"读回（scope 取消后重建实例）。
 *
 * 注意：同一文件同时存在多个 DataStore 实例会抛 IllegalStateException，
 * 故"重启"测试用显式 scope + cancel 释放文件后再重建。
 */
class UserConfigRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 每个测试方法独立临时文件 + 独立 DataStore 实例（返回实例供脏数据直写）。 */
    private fun newRepo(): Pair<UserConfigRepository, DataStore<Preferences>> {
        val file = File(tmp.root, "user_config.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(produceFile = { file })
        return UserConfigRepository(dataStore) to dataStore
    }

    // ---------- 默认值 ----------

    @Test
    fun defaults_whenStoreEmpty() = runBlocking {
        val (repo, _) = newRepo()
        val cfg = repo.userConfig.first()

        assertEquals("free", cfg.asrTier)
        assertEquals("zh", cfg.language)
        assertTrue(cfg.vadEnabled)
        assertEquals(0.02, cfg.vadSilenceThreshold, 1e-9)
        assertEquals(1.5, cfg.vadSilenceDuration, 1e-9)
        assertEquals(1.0, cfg.vadMinSegment, 1e-9)
        assertEquals(270.0, cfg.vadMaxSegment, 1e-9)
        assertFalse(cfg.meetingMode)
        assertEquals("mixed", cfg.meetingAudioSource)
        assertEquals("full", cfg.meetingScreenshotMode)
        assertTrue(cfg.meetingAutoFloat)
        assertEquals("ctrl+alt+s", cfg.meetingHotkey)
        assertEquals(0, cfg.meetingScreenIndex)
        assertEquals(0.01, cfg.meetingVadSilenceThreshold, 1e-9)
        assertEquals("system", cfg.theme)
        assertFalse(cfg.onboardingDone)
    }

    // ---------- 写 → 读回 ----------

    @Test
    fun writeThenReadBack_allFields() = runBlocking {
        val (repo, _) = newRepo()
        repo.setAsrTier("pro_pro")
        repo.setLanguage("en")
        repo.setVadEnabled(false)
        repo.setVadParams(silenceThreshold = 0.05, silenceDuration = 2.0, minSegment = 1.5, maxSegment = 300.0)
        repo.setMeetingMode(true)
        repo.setMeetingAudioSource("system")
        repo.setMeetingScreenshotMode("region")
        repo.setMeetingAutoFloat(false)
        repo.setMeetingHotkey("ctrl+shift+s")
        repo.setMeetingScreenIndex(1)
        repo.setMeetingVadSilenceThreshold(0.03)
        repo.setTheme("dark")
        repo.setOnboardingDone(true)

        val cfg = repo.userConfig.first()
        assertEquals("pro_pro", cfg.asrTier)
        assertEquals("en", cfg.language)
        assertFalse(cfg.vadEnabled)
        assertEquals(0.05, cfg.vadSilenceThreshold, 1e-9)
        assertEquals(2.0, cfg.vadSilenceDuration, 1e-9)
        assertEquals(1.5, cfg.vadMinSegment, 1e-9)
        assertEquals(300.0, cfg.vadMaxSegment, 1e-9)
        assertTrue(cfg.meetingMode)
        assertEquals("system", cfg.meetingAudioSource)
        assertEquals("region", cfg.meetingScreenshotMode)
        assertFalse(cfg.meetingAutoFloat)
        assertEquals("ctrl+shift+s", cfg.meetingHotkey)
        assertEquals(1, cfg.meetingScreenIndex)
        assertEquals(0.03, cfg.meetingVadSilenceThreshold, 1e-9)
        assertEquals("dark", cfg.theme)
        assertTrue(cfg.onboardingDone)
    }

    // ---------- 空/非法 tier 回退 free ----------

    @Test
    fun emptyTierInStore_fallsBackToFree() = runBlocking {
        val (repo, dataStore) = newRepo()
        // 绕过 setter 直写脏数据（key 名与仓库 Keys 对齐），模拟旧版本/损坏数据
        dataStore.edit { it[stringPreferencesKey("asr_tier")] = "" }
        assertEquals("free", repo.userConfig.first().asrTier)
    }

    @Test
    fun invalidTierInStore_fallsBackToFree() = runBlocking {
        val (repo, dataStore) = newRepo()
        dataStore.edit { it[stringPreferencesKey("asr_tier")] = "bogus_tier" }
        assertEquals("free", repo.userConfig.first().asrTier)
    }

    @Test
    fun setAsrTier_emptyOrInvalid_writesFree() = runBlocking {
        val (repo, _) = newRepo()
        repo.setAsrTier("")
        assertEquals("free", repo.userConfig.first().asrTier)

        repo.setAsrTier("bogus_tier")
        assertEquals("free", repo.userConfig.first().asrTier)
    }

    // ---------- 写 → 重启读回（模拟进程重启） ----------

    @Test
    fun writeThenRestart_readsBackFromFile() = runBlocking {
        val file = File(tmp.root, "user_config.preferences_pb")

        // 第一次"进程"：显式 scope，写入后 cancel 释放文件
        val scope1 = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val repo1 = UserConfigRepository(PreferenceDataStoreFactory.create(scope = scope1, produceFile = { file }))
        repo1.setAsrTier("realtime")
        repo1.setTheme("dark")
        scope1.cancel()

        // 第二次"进程"：同一文件重建实例，读回持久化值
        val scope2 = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val repo2 = UserConfigRepository(PreferenceDataStoreFactory.create(scope = scope2, produceFile = { file }))
        val cfg = repo2.userConfig.first()
        assertEquals("realtime", cfg.asrTier)
        assertEquals("dark", cfg.theme)
        // 未写入字段仍回退默认
        assertEquals("zh", cfg.language)
        scope2.cancel()
    }
}