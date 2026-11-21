package com.shiguang.album.ui.player

import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.GraphicsLayerScope
import com.shiguang.album.data.model.EffectType
import com.shiguang.album.data.model.PlayEndAction
import kotlin.math.min

/** 动效中文名 */
fun EffectType.label(): String = when (this) {
    EffectType.FADE -> "淡入淡出"
    EffectType.SLIDE_H -> "左右滑动"
    EffectType.SLIDE_V -> "上下滑动"
    EffectType.ZOOM -> "缩放推进"
    EffectType.ROTATE -> "旋转翻页"
    EffectType.MOSAIC -> "马赛克溶解"
    EffectType.RANDOM -> "随机动效"
}

/** 「播完之后」中文名：清单编辑器与播放页设置共用一套说法 */
fun PlayEndAction.label(): String = when (this) {
    PlayEndAction.LOOP -> "循环播放"
    PlayEndAction.HOLD_LAST -> "停在最后一张"
    PlayEndAction.BACK_HOME -> "回到首页"
}

/** 一句话说明，放在选项下面，避免用户猜"到底停还是退" */
fun PlayEndAction.hint(): String = when (this) {
    PlayEndAction.LOOP -> "一遍放完回到第一张接着放，适合一直当背景播放"
    PlayEndAction.HOLD_LAST -> "停在最后一张不再自动切换，音乐继续；按 OK 可以重新从头放"
    PlayEndAction.BACK_HOME -> "最后一张停留结束后自动退出播放页，回到首页"
}

fun EffectType.shortLabel(): String = when (this) {
    EffectType.FADE -> "淡入"
    EffectType.SLIDE_H -> "横向"
    EffectType.SLIDE_V -> "纵向"
    EffectType.ZOOM -> "缩放"
    EffectType.ROTATE -> "翻页"
    EffectType.MOSAIC -> "溶解"
    EffectType.RANDOM -> "随机"
}

/**
 * 「随机动效」在每次切换时抽一个具体动效，避免整场都是同一种。
 * 为了让「随机」有质感，这里刻意排除了 FADE 以外的低信息量组合。
 */
fun EffectType.resolveOnce(): EffectType {
    if (this != EffectType.RANDOM) return this
    val pool = listOf(
        EffectType.FADE,
        EffectType.SLIDE_H,
        EffectType.SLIDE_V,
        EffectType.ZOOM,
        EffectType.ROTATE,
        EffectType.MOSAIC
    )
    return pool.random()
}

/**
 * 入场层变换：p 从 0 → 1。
 * forward = true 表示「下一张」（新图从右侧/下方进入）。
 */
fun GraphicsLayerScope.applyEntering(
    effect: EffectType,
    p: Float,
    forward: Boolean,
    width: Float,
    height: Float
) {
    when (effect) {
        EffectType.FADE -> {
            alpha = p
        }

        EffectType.SLIDE_H -> {
            alpha = 1f
            translationX = (if (forward) width else -width) * (1f - p)
        }

        EffectType.SLIDE_V -> {
            alpha = 1f
            translationY = (if (forward) height else -height) * (1f - p)
        }

        EffectType.ZOOM -> {
            val s = 0.92f + 0.08f * p
            scaleX = s
            scaleY = s
            alpha = p
        }

        EffectType.ROTATE -> {
            // 绕左/右侧边做 Y 轴翻转，配合 cameraDistance 有立体翻书感
            transformOrigin = TransformOrigin(if (forward) 0f else 1f, 0.5f)
            rotationY = (if (forward) 1f else -1f) * 78f * (1f - p)
            cameraDistance = 18f * density
            alpha = min(1f, p * 1.8f)
        }

        EffectType.MOSAIC -> {
            // 具体马赛克网格裁切在 SlideshowView 里用 Path 做，这里只控制整体透明度
            alpha = 1f
        }

        EffectType.RANDOM -> alpha = p
    }
}

/** 出场层变换：p 从 0 → 1（旧图退场） */
fun GraphicsLayerScope.applyExiting(
    effect: EffectType,
    p: Float,
    forward: Boolean,
    width: Float,
    height: Float
) {
    when (effect) {
        EffectType.FADE -> {
            alpha = 1f - p
        }

        EffectType.SLIDE_H -> {
            alpha = 1f
            translationX = (if (forward) -width else width) * p
        }

        EffectType.SLIDE_V -> {
            alpha = 1f
            translationY = (if (forward) -height else height) * p
        }

        EffectType.ZOOM -> {
            val s = 1f + 0.14f * p
            scaleX = s
            scaleY = s
            alpha = 1f - p
        }

        EffectType.ROTATE -> {
            transformOrigin = TransformOrigin(if (forward) 1f else 0f, 0.5f)
            rotationY = (if (forward) -1f else 1f) * 78f * p
            cameraDistance = 18f * density
            alpha = 1f - p
        }

        EffectType.MOSAIC -> {
            alpha = 1f - p
        }

        EffectType.RANDOM -> alpha = 1f - p
    }
}

/** 切换动画时长（毫秒）：滑动略快，翻页略慢，观感更自然 */
fun EffectType.durationMs(): Int = when (this) {
    EffectType.SLIDE_H, EffectType.SLIDE_V -> 380
    EffectType.ROTATE -> 520
    EffectType.MOSAIC -> 620
    else -> 420
}
