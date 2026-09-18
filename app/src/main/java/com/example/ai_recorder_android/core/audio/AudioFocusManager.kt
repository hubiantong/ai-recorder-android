package com.example.ai_recorder_android.core.audio

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * 音频焦点管理（Metis M5.5）：录音期间请求 AUDIOFOCUS_GAIN，
 * 焦点丢失（来电 / 其他 App 抢占）→ 回调 [FocusListener.onFocusLoss] 供 todo 16 暂停录音。
 *
 * 用法：
 * ```
 * val focusManager = AudioFocusManager(audioManager)
 * focusManager.request { permanent -> /* 暂停录音 */ }
 * // 停止录音时：
 * focusManager.abandon()
 * ```
 */
class AudioFocusManager(private val audioManager: AudioManager) {

    /** 焦点丢失回调（todo 16 用于暂停录音）。 */
    fun interface FocusListener {
        /**
         * @param permanent true = 永久丢失（应放弃焦点并停止录音）；
         *                  false = 临时丢失（可暂停等待恢复）
         */
        fun onFocusLoss(permanent: Boolean)
    }

    private var focusRequest: AudioFocusRequest? = null
    private var listener: FocusListener? = null

    /** 是否已持有音频焦点。 */
    val isHoldingFocus: Boolean
        get() = focusRequest != null

    /**
     * 请求音频焦点（AUDIOFOCUS_GAIN）。
     *
     * @param listener 焦点丢失回调
     * @return true = 请求成功（AUDIOFOCUS_REQUEST_GRANTED）；false = 请求失败
     */
    fun request(listener: FocusListener): Boolean {
        abandon()
        this.listener = listener
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setOnAudioFocusChangeListener { change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> listener.onFocusLoss(permanent = true)
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> listener.onFocusLoss(permanent = false)
                }
            }
            .build()
        focusRequest = request
        return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    /** 放弃音频焦点并清除回调。 */
    fun abandon() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
        listener = null
    }
}