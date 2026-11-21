package com.shiguang.album.ui.player

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.shiguang.album.data.model.MusicConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * 背景音乐播放器。
 *
 * 用 MediaPlayer 而不是 ExoPlayer：音频场景不需要解码器切换，MediaPlayer 更轻、更省内存，
 * 电视上常见的 MP3 / FLAC / AAC 都能直接吃。音量独立于系统音量，用 [fadeTo] 做淡入淡出。
 */
class MusicController(
    private val context: Context,
    private val scope: CoroutineScope
) {

    private var player: MediaPlayer? = null
    private var fadeJob: Job? = null
    private var currentVolume: Float = 0f

    @Volatile
    private var paused = false

    fun play(file: File?, url: String?, config: MusicConfig, onComplete: (() -> Unit)? = null) {
        stop(fadeOut = false)
        currentVolume = 0f
        paused = false

        val mp = MediaPlayer()
        player = mp
        runCatching {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            // 有"播完回调"时（多首轮播）不能用单曲循环，否则永远停在同一首
            mp.isLooping = onComplete == null && config.loop
            if (!url.isNullOrBlank()) {
                mp.setDataSource(url)
            } else {
                mp.setDataSource(file!!.absolutePath)
            }
            mp.setVolume(0f, 0f)
            mp.setOnPreparedListener {
                if (paused) return@setOnPreparedListener
                runCatching { it.start() }
                fadeTo(config.volume, config.fadeMs)
            }
            mp.setOnCompletionListener {
                if (onComplete != null) runCatching { onComplete() }
            }
            mp.setOnErrorListener { _, _, _ ->
                runCatching { releasePlayer() }
                onComplete?.let { runCatching { it() } }
                true
            }
            mp.prepareAsync()
        }.onFailure {
            runCatching { mp.release() }
            if (player === mp) player = null
        }
    }

    /** 暂停播放（用于播放器暂停时把音乐一起暂停，观感更统一） */
    fun pause(config: MusicConfig) {
        paused = true
        fadeTo(0f, (config.fadeMs / 2).coerceAtLeast(150)) {
            runCatching { player?.pause() }
        }
    }

    fun resume(config: MusicConfig) {
        paused = false
        runCatching { player?.start() }
        fadeTo(config.volume, config.fadeMs)
    }

    /** 调节背景音乐音量（0f..1f），即时生效 */
    fun setVolume(v: Float, fadeMs: Int = 200) {
        fadeTo(v.coerceIn(0f, 1f), fadeMs)
    }

    fun stop(fadeOut: Boolean = true) {
        fadeJob?.cancel()
        val mp = player ?: return
        if (!fadeOut) {
            runCatching { mp.release() }
            player = null
            return
        }
        fadeJob = scope.launch {
            val steps = 12
            val start = currentVolume
            for (i in 1..steps) {
                runCatching { mp.setVolume(start * (1f - i / steps.toFloat()), start * (1f - i / steps.toFloat())) }
                delay(40)
            }
            runCatching { mp.release() }
            if (player === mp) player = null
        }
    }

    fun release() {
        fadeJob?.cancel()
        releasePlayer()
    }

    private fun releasePlayer() {
        runCatching { player?.release() }
        player = null
    }

    private fun fadeTo(target: Float, durationMs: Int, onDone: (() -> Unit)? = null) {
        fadeJob?.cancel()
        val mp = player
        if (mp == null) {
            onDone?.invoke()
            return
        }
        fadeJob = scope.launch {
            val start = currentVolume
            val steps = (durationMs / 40).coerceIn(1, 60)
            for (i in 1..steps) {
                if (!isActive) return@launch
                val v = start + (target - start) * (i / steps.toFloat())
                runCatching { mp.setVolume(v, v) }
                currentVolume = v
                delay(40)
            }
            currentVolume = target
            onDone?.invoke()
        }
    }
}
