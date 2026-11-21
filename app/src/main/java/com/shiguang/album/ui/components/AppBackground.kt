package com.shiguang.album.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.shiguang.album.AppContainer
import com.shiguang.album.data.model.MediaEntry
import com.shiguang.album.data.model.MediaKind
import com.shiguang.album.data.model.WallpaperKind
import com.shiguang.album.ui.theme.TvTheme
import java.io.File

/**
 * 设置页统一背景：和首页同一张壁纸 + 一层可读性遮罩。
 *
 * 之前每个设置页都是 `background(colors.background)` —— 一片纯色，
 * 用户在外面看到的是自己的照片壁纸，一进设置就"掉了"，像换了个 App。
 *
 * 遮罩比例是按可读性定的：深色 0.46、浅色 0.58（浅色壁纸更亮，需要压得更狠一点）。
 * 壁纸很花的时候，设置页的行本身就是半透明白的分组卡片，文字不会掉进杂色里。
 */
@Composable
fun AppBackground(
    container: AppContainer,
    modifier: Modifier = Modifier,
    scrim: Float? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val appState by container.configStore.state.collectAsState()
    val wallpaper = appState.settings.wallpaper
    val colors = TvTheme.colors

    // 图片壁纸：远程源先落到本地缓存（和首页同一套逻辑）
    val imageFile by produceState<File?>(initialValue = null, wallpaper.file) {
        value = if (wallpaper.needsImageResolve()) {
            val ref = wallpaper.file
            if (ref == null) null else runCatching {
                container.mediaCache.file(
                    MediaEntry(
                        sourceId = ref.sourceId,
                        sourceName = "",
                        path = ref.path,
                        name = ref.path.substringAfterLast('/'),
                        kind = MediaKind.IMAGE
                    )
                )
            }.getOrNull()
        } else null
    }

    // 视频壁纸：设置页照样播（aerial 的面板就是压在动态壁纸上的）。
    // 页面栈是互斥的，同一时刻只有一个 ExoPlayer 实例，不会抢解码器。
    val videoFiles = remember(wallpaper.kind) {
        if (wallpaper.kind == WallpaperKind.VIDEO) container.videoWallpaper.playableFiles()
        else emptyList()
    }

    Box(modifier.fillMaxSize().background(colors.background)) {
        WallpaperBackground(
            wallpaper = wallpaper,
            imageFile = imageFile,
            videoFiles = videoFiles
        )
        // 可读性遮罩：比首页轻一点，让用户看得出"这还是我那面墙"
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    if (colors.isDark) Color.Black.copy(alpha = scrim ?: 0.46f)
                    else Color.White.copy(alpha = scrim ?: 0.58f)
                )
        )
        content()
    }
}
