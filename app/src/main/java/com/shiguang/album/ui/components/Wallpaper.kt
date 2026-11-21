package com.shiguang.album.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.shiguang.album.data.model.Wallpaper
import com.shiguang.album.data.model.WallpaperKind
import com.shiguang.album.ui.theme.TvTheme
import java.io.File

/**
 * 全屏壁纸背景。
 *
 * 四种形态：
 * - NONE：用主题底色
 * - GRADIENT：内置渐变（深/浅主题各一套，名字相同）
 * - IMAGE：用户从任意存储源选的一张图，铺满 + 压暗，可选模糊（Android 12+）
 * - VIDEO：用户选的一段视频，静音循环播放（见 VideoBackdrop）
 *
 * 压暗层是必须的：不管什么壁纸，前景文字都要保证可读（这是电视 3 米外观看的硬要求）。
 */
@Composable
fun WallpaperBackground(
    wallpaper: Wallpaper,
    imageFile: File?,
    modifier: Modifier = Modifier,
    videoFiles: List<File> = emptyList()
) {
    val colors = TvTheme.colors

    Box(modifier.fillMaxSize().background(colors.background)) {
        when (wallpaper.kind) {
            WallpaperKind.NONE -> Unit

            WallpaperKind.GRADIENT -> BuiltinWallpaperLayer(
                index = wallpaper.preset,
                dark = colors.isDark
            )

            WallpaperKind.IMAGE -> {
                if (imageFile != null) {
                    val blurModifier =
                        if (wallpaper.blur && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            Modifier.blur(28.dp)
                        } else Modifier
                    AsyncImage(
                        model = imageFile,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().then(blurModifier)
                    )
                }
                // 图片还在下载/解码时先铺一层内置壁纸，避免一片黑
                if (imageFile == null) {
                    BuiltinWallpaperLayer(index = wallpaper.preset, dark = colors.isDark)
                }
            }

            WallpaperKind.VIDEO -> {
                if (videoFiles.isNotEmpty()) {
                    // 视频层自己带压暗（默认比图片更重一些），这里不再叠第二层
                    VideoBackdrop(
                        files = videoFiles,
                        dim = wallpaper.dim,
                        fallbackPreset = wallpaper.fallbackPreset,
                        loop = wallpaper.videoLoop
                    )
                } else {
                    // 视频还没落盘（或已被清理）：先用渐变顶上，绝不黑屏
                    BuiltinWallpaperLayer(index = wallpaper.fallbackPreset, dark = colors.isDark)
                }
            }
        }

        // 遮罩层：图片壁纸才需要；渐变本身已经足够暗，视频层内部已经压过。
        //
        // 颜色必须跟主题走：深色主题是"压黑 + 白字"，浅色主题是"提白 + 深字"。
        // 之前一律压黑，浅色主题下壁纸一暗、深色文字就整片看不见 ——
        // 这也是"背景图不明显"的根因（文字和背景糊成一团）。
        if (wallpaper.kind == WallpaperKind.IMAGE) {
            val alpha = wallpaper.dim.coerceIn(0f, 0.85f)
            val veil = if (colors.isDark) Color.Black else Color.White
            Box(Modifier.fillMaxSize().background(veil.copy(alpha = alpha)))
        }
    }
}

/** 判断一个壁纸配置是否需要解析图片文件 */
fun Wallpaper.needsImageResolve(): Boolean =
    kind == WallpaperKind.IMAGE && file != null && file.path.isNotBlank()

/** 判断一个壁纸配置是否需要准备视频文件 */
fun Wallpaper.needsVideoResolve(): Boolean =
    kind == WallpaperKind.VIDEO && file != null && file.path.isNotBlank()
