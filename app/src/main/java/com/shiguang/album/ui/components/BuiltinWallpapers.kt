package com.shiguang.album.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/* ============================================================================
 * 内置壁纸
 *
 * 早期版本只有「纯渐变」，而且默认那套配色是 #0B1026 → #16233F 这种极暗的蓝，
 * 在电视上一看就是一片黑，很容易被当成"没内置壁纸"。
 *
 * 现在改成「底色渐变 + 2~3 个径向光斑」的程序化绘制：
 * - 不占安装包体积（没有图片资源）
 * - 任意分辨率都不会糊，4K 电视上同样干净
 * - 光斑位置刻意避开左上角标题区，保证文字始终压在暗底上
 * ========================================================================== */

data class BuiltinWallpaper(
    val name: String,
    /** 深色主题：底色渐变（上 → 中 → 下） */
    val darkBase: List<Color>,
    /** 深色主题：光斑颜色（左下、右侧、顶部） */
    val darkGlows: List<Color>,
    val lightBase: List<Color>,
    val lightGlows: List<Color>
)

val BuiltinWallpapers: List<BuiltinWallpaper> = listOf(
    BuiltinWallpaper(
        name = "星夜蓝",
        darkBase = listOf(Color(0xFF070C1E), Color(0xFF0E2246), Color(0xFF05070F)),
        darkGlows = listOf(Color(0xFF3B82F6), Color(0xFF22D3EE), Color(0xFF6366F1)),
        lightBase = listOf(Color(0xFFEEF3FF), Color(0xFFFFFFFF), Color(0xFFE7EEFF)),
        lightGlows = listOf(Color(0xFF93C5FD), Color(0xFFA5F3FC), Color(0xFFBFDBFE))
    ),
    BuiltinWallpaper(
        name = "落日橙",
        darkBase = listOf(Color(0xFF180703), Color(0xFF3A1608), Color(0xFF0E0402)),
        darkGlows = listOf(Color(0xFFFF6900), Color(0xFFFBBF24), Color(0xFFF43F5E)),
        lightBase = listOf(Color(0xFFFFF3E8), Color(0xFFFFFBF6), Color(0xFFFFE9D6)),
        lightGlows = listOf(Color(0xFFFDBA74), Color(0xFFFDE68A), Color(0xFFFDA4AF))
    ),
    BuiltinWallpaper(
        name = "极光绿",
        darkBase = listOf(Color(0xFF03100C), Color(0xFF0A2F26), Color(0xFF020A08)),
        darkGlows = listOf(Color(0xFF10B981), Color(0xFF34D399), Color(0xFF22D3EE)),
        lightBase = listOf(Color(0xFFE9FBF4), Color(0xFFF7FFFC), Color(0xFFDDF5EA)),
        lightGlows = listOf(Color(0xFF6EE7B7), Color(0xFFA7F3D0), Color(0xFF99F6E4))
    ),
    BuiltinWallpaper(
        name = "玫瑰紫",
        darkBase = listOf(Color(0xFF12061A), Color(0xFF2C1040), Color(0xFF0A0310)),
        darkGlows = listOf(Color(0xFFA855F7), Color(0xFFEC4899), Color(0xFF6366F1)),
        lightBase = listOf(Color(0xFFF8EFFF), Color(0xFFFFF7FF), Color(0xFFF3E3FF)),
        lightGlows = listOf(Color(0xFFD8B4FE), Color(0xFFF9A8D4), Color(0xFFE9D5FF))
    ),
    BuiltinWallpaper(
        name = "石墨灰",
        darkBase = listOf(Color(0xFF09090C), Color(0xFF191921), Color(0xFF050507)),
        darkGlows = listOf(Color(0xFF64748B), Color(0xFF94A3B8), Color(0xFF475569)),
        lightBase = listOf(Color(0xFFF2F3F7), Color(0xFFFCFCFE), Color(0xFFEDEFF4)),
        lightGlows = listOf(Color(0xFFCBD5E1), Color(0xFFE2E8F0), Color(0xFFDDE3EC))
    ),
    BuiltinWallpaper(
        name = "晨曦粉",
        darkBase = listOf(Color(0xFF1A0A12), Color(0xFF3D1930), Color(0xFF10060C)),
        darkGlows = listOf(Color(0xFFF472B6), Color(0xFFFDA4AF), Color(0xFFFB923C)),
        lightBase = listOf(Color(0xFFFFF0F5), Color(0xFFFFFAFC), Color(0xFFFFE4EE)),
        lightGlows = listOf(Color(0xFFFBCFE8), Color(0xFFFECDD3), Color(0xFFFED7AA))
    )
)

/**
 * 画一层内置壁纸。[index] 越界时回落到第一套，保证旧配置（preset 被改坏）也不会白屏。
 */
@Composable
fun BuiltinWallpaperLayer(index: Int, dark: Boolean, modifier: Modifier = Modifier) {
    val preset = BuiltinWallpapers.getOrNull(index) ?: BuiltinWallpapers.first()
    val base = if (dark) preset.darkBase else preset.lightBase
    val glows = if (dark) preset.darkGlows else preset.lightGlows
    val glowAlpha = if (dark) 0.5f else 0.55f

    Box(modifier.fillMaxSize().background(Brush.linearGradient(base))) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            // 光斑：左下、右侧中部、顶部偏右 —— 故意都远离左上角的标题
            val spots = listOf(
                Triple(0.18f, 0.88f, 0.78f),
                Triple(0.90f, 0.42f, 0.62f),
                Triple(0.62f, 0.00f, 0.42f)
            )
            glows.forEachIndexed { i, color ->
                val (fx, fy, fr) = spots[i % spots.size]
                val center = Offset(w * fx, h * fy)
                val radius = maxOf(w, h) * fr
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(color.copy(alpha = glowAlpha), color.copy(alpha = 0f)),
                        center = center,
                        radius = radius
                    ),
                    radius = radius,
                    center = center
                )
            }
        }
    }
}
