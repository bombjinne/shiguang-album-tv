package com.shiguang.album.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.shiguang.album.data.model.EffectType
import com.shiguang.album.ui.theme.TvTheme
import kotlinx.coroutines.launch
import java.io.File

/**
 * 相片切换视图。
 *
 * 用两层（旧图 + 新图）叠加，通过 Animatable 进度同时驱动两层的 graphicsLayer 变换，
 * 这样滑动 / 缩放 / 翻页 / 马赛克都能共用一套动画时钟，切换过程不掉帧。
 *
 * 马赛克动效是真正的「网格逐块揭开」：按对角线波次把已经亮起的格子组成 Path，
 * 再把新图裁到这个 Path 里。
 *
 * ---------------------------------------------------------------------------
 * 关于切换时的那一下黑屏：
 *
 * 旧写法是 `file` 一变就把新图换上、同时启动淡入，可新图还没解码完，那一层是空的，
 * 露出的就是黑底。远程源（WebDAV/SMB）尤其明显 —— 下载加解码要好几百毫秒。
 *
 * 现在拆成「先加载、再切换」两步：
 * 1. [nextFile] 进来就先偷偷解码一遍（alpha=0 的预载层），轮到它时通常已经在内存里；
 * 2. 新图 `onSuccess` 回调之后才启动切场动画；
 * 3. 新图没就绪之前，画面**一直停在上一张**，绝不先黑一下。
 *
 * 之所以走 Coil 的 AsyncImage 而不是自己 BitmapFactory：HEIC、EXIF 旋转、
 * 大图降采样都由它兜住，电视上跑过之后就别另开一条解码链路了。
 * ---------------------------------------------------------------------------
 */
@Composable
fun SlideshowView(
    file: File?,
    nextFile: File?,
    effect: EffectType,
    forward: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    /** 正在显示的图 */
    var current by remember { mutableStateOf<File?>(null) }
    /** 已经收到、正在后台解码的下一张；解码成功才允许切过去 */
    var pending by remember { mutableStateOf<File?>(null) }
    /** 正在淡出的上一张。刻意不主动清空 —— 任何时刻都有图垫底，永远不会露黑 */
    var outgoing by remember { mutableStateOf<File?>(null) }
    var resolved by remember { mutableStateOf(EffectType.FADE) }
    val progress = remember { Animatable(1f) }

    BoxWithConstraints(modifier.background(Color.Black)) {
        val density = LocalDensity.current
        val wPx = with(density) { maxWidth.toPx() }
        val hPx = with(density) { maxHeight.toPx() }
        val wInt = wPx.toInt().coerceAtLeast(1)
        val hInt = hPx.toInt().coerceAtLeast(1)

        // 提前把下一张喂给 Coil：缓存 key 用屏幕尺寸，和下面真正显示时一致，才能命中
        LaunchedEffect(nextFile, wInt, hInt) {
            val target = nextFile ?: return@LaunchedEffect
            if (target == current) return@LaunchedEffect
            runCatching {
                context.imageLoader.enqueue(
                    ImageRequest.Builder(context)
                        .data(target)
                        .size(wInt, hInt)
                        .crossfade(false)
                        .build()
                )
            }
        }

        // 收到新的一帧：先挂到 pending 层去解码，不立刻切动画
        LaunchedEffect(file) {
            val target = file ?: return@LaunchedEffect
            if (target == current) return@LaunchedEffect
            if (current == null) {
                // 第一张：直接占位，等它自己加载出来
                current = target
                progress.snapTo(1f)
            } else {
                pending = target
            }
        }

        /** pending 解码成功 → 正式切场（此刻新图已可绘制，动画一开始就是实的） */
        fun commit(target: File) {
            if (pending != target) return
            pending = null
            outgoing = current
            resolved = effect.resolveOnce()
            current = target
            scope.launch {
                progress.snapTo(0f)
                progress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(resolved.durationMs(), easing = FastOutSlowInEasing)
                )
            }
        }

        // 上一张：只在切换动画期间参与，动画结束后仍留在原位被新图完全遮住，
        // 这样即使新图因为缓存被回收又闪一下，底下还是照片而不是黑
        outgoing?.let { f ->
            Photo(
                file = f,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        applyExiting(resolved, progress.value, forward, wPx, hPx)
                    }
            )
        }

        current?.let { f ->
            Photo(
                file = f,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        applyEntering(resolved, progress.value, forward, wPx, hPx)
                    }
                    .then(
                        if (resolved == EffectType.MOSAIC) {
                            Modifier.mosaicReveal { progress.value }
                        } else Modifier
                    )
            )
        }

        // 预载层：透明但真实解码，解码完成就把画面切过去
        pending?.let { f ->
            Photo(
                file = f,
                invisible = true,
                onReady = { commit(f) },
                // 解不出来（源断了 / 格式不支持）也要往下走，否则会永远卡在上一张
                onFailed = { commit(f) },
                modifier = Modifier.fillMaxSize()
            )
        }

        if (current == null) {
            Text(
                text = "正在加载…",
                color = TvTheme.colors.textTertiary,
                fontSize = 18.sp,
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

@Composable
private fun Photo(
    file: File,
    modifier: Modifier,
    invisible: Boolean = false,
    onReady: () -> Unit = {},
    onFailed: () -> Unit = {}
) {
    val context = LocalContext.current
    AsyncImage(
        model = ImageRequest.Builder(context)
            .data(file)
            // 动效自己控制，关掉 Coil 的交叉淡入
            .crossfade(false)
            .build(),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = if (invisible) modifier.graphicsLayer { alpha = 0f } else modifier,
        // 解码完成才回调：这就是"切场"的触发点
        onSuccess = { onReady() },
        onError = { onFailed() }
    )
}

/** 马赛克逐格揭开：按 (行+列) 的对角波次点亮格子 */
private fun Modifier.mosaicReveal(progress: () -> Float): Modifier = drawWithContent {
    val p = progress().coerceIn(0f, 1f)
    if (p >= 1f) {
        drawContent()
        return@drawWithContent
    }
    val cols = 14
    val rows = 8
    val tileW = size.width / cols
    val tileH = size.height / rows
    val path = Path()
    for (r in 0 until rows) {
        for (c in 0 until cols) {
            val threshold = (r + c).toFloat() / (rows + cols - 2)
            if (p > threshold) {
                path.addRect(
                    Rect(
                        left = c * tileW,
                        top = r * tileH,
                        right = (c + 1) * tileW,
                        bottom = (r + 1) * tileH
                    )
                )
            }
        }
    }
    clipPath(path) { this@drawWithContent.drawContent() }
}
