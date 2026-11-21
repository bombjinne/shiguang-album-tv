package com.shiguang.album.ui.components

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.shiguang.album.ui.theme.TvTheme
import java.io.File

/**
 * 视频壁纸层（参照 aerial-tv 的 VideoWallpaper）。
 *
 * 几个从 aerial-tv 学来的细节：
 * - **静音**：壁纸永远不出声，音轨直接不参与选择，省解码；
 * - **单文件循环**（REPEAT_MODE_ONE），片尾不黑屏；
 * - **铺满裁切**（RESIZE_MODE_ZOOM），竖屏视频也不会留两条黑边；
 * - **首帧之前铺渐变**：`setShutterBackgroundColor(TRANSPARENT)` + 底层内置壁纸，
 *   冷启动时看到的是渐变淡入，不是一块黑；
 * - **离开页面就 release**：电视内存小，留着解码器不放会拖垮后台的投屏/音乐。
 *
 * 解码失败（老盒子没有 H.265、视频文件损坏）时回调 [onFailed]，由调用方降级成静帧壁纸 ——
 * 绝不能停在黑屏上让用户以为 App 崩了。
 */
@Composable
fun VideoBackdrop(
    files: List<File>,
    modifier: Modifier = Modifier,
    dim: Float = 0.4f,
    fallbackPreset: Int = 0,
    loop: Boolean = true,
    onFailed: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val colors = TvTheme.colors
    // 用「路径 + 长度」做 key：手机上传同名文件覆盖时长度会变，能正确重建播放器
    val key = remember(files) { files.joinToString("|") { "${it.path}#${it.length()}" } }
    var ready by remember(key) { mutableStateOf(false) }
    var failed by remember(key) { mutableStateOf(false) }

    val player = remember(key) {
        ExoPlayer.Builder(context).build().apply {
            if (files.isEmpty()) {
                // 空清单：不 prepare，直接走渐变兜底
                return@apply
            }
            setMediaItems(files.map { MediaItem.fromUri(Uri.fromFile(it)) })
            // 单段用 ONE，多段按设置决定「循环轮播」还是「播完停住」
            repeatMode = when {
                files.size == 1 -> Player.REPEAT_MODE_ONE
                loop -> Player.REPEAT_MODE_ALL
                else -> Player.REPEAT_MODE_OFF
            }
            volume = 0f
            playWhenReady = true
            prepare()
        }
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                ready = true
            }

            override fun onPlayerError(error: PlaybackException) {
                failed = true
                onFailed?.invoke()
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }

    // App 切后台就暂停：电视上"回到桌面还在解码视频"是实打实的耗电和发热
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle, player) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> player.pause()
                Lifecycle.Event.ON_START -> if (!failed) player.play()
                else -> Unit
            }
        }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }

    Box(modifier.fillMaxSize().background(colors.background)) {
        // 底衬：首帧还没出来（或解码失败）时看到的是渐变，不是黑色
        BuiltinWallpaperLayer(index = fallbackPreset, dark = colors.isDark)

        if (!failed && files.isNotEmpty()) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        // 让 PlayerView 的黑色"快门"透明，底层渐变才能透出来
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        this.player = player
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // 遮罩层：视频比图片更"跳"，默认值给得比图片高一点，保证标题永远可读。
        // 同样跟主题走 —— 浅色主题必须提白，否则深色标题压在暗视频上等于消失。
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    (if (colors.isDark) Color.Black else Color.White)
                        .copy(alpha = dim.coerceIn(0f, 0.85f))
                )
        )

        if (!ready && !failed && files.isNotEmpty()) {
            // 首帧之前的极轻遮罩，避免渐变突然被视频替换时"闪一下"
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.18f)))
        }
    }
}
