package com.shiguang.album.ui.player

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * 在线音乐「试听」器。
 *
 * 和真正的背景音乐播放器（ExoPlayer / MusicController）分开：
 * 这里只回答一个问题——「这首能不能放」，退出搜索弹窗就立刻停，不影响正在播放的相册。
 */
class MusicPreviewPlayer {

    private var player: MediaPlayer? = null

    /** 正在试听的 key（source|id），null 表示没在放 */
    var playingKey by mutableStateOf<String?>(null)
        private set

    /** 最近一次失败原因，界面直接显示 */
    var lastError by mutableStateOf<String?>(null)
        private set

    fun isPlaying(key: String): Boolean = playingKey == key

    fun play(key: String, url: String) {
        stop()
        lastError = null
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setDataSource(url)
            mp.setOnPreparedListener { playingKey = key; runCatching { it.start() } }
            mp.setOnCompletionListener { playingKey = null }
            mp.setOnErrorListener { _, what, extra ->
                lastError = "播放失败（$what/$extra）"
                playingKey = null
                true
            }
            mp.prepareAsync()
            player = mp
            // 先标成"在放"，避免解析完到 onPrepared 之间按钮闪回「试听」
            playingKey = key
        } catch (t: Throwable) {
            runCatching { mp.release() }
            lastError = t.message ?: "无法试听"
            playingKey = null
        }
    }

    fun stop() {
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
        playingKey = null
    }
}

/** Compose 里用：离开界面自动释放，绝不会出现"关掉了还在唱歌" */
@Composable
fun rememberMusicPreview(): MusicPreviewPlayer {
    val player = remember { MusicPreviewPlayer() }
    DisposableEffect(player) {
        onDispose { player.stop() }
    }
    return player
}
