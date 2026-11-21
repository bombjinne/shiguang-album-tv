package com.shiguang.album.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shiguang.album.AppContainer
import com.shiguang.album.data.model.EffectType
import com.shiguang.album.data.model.PlayEndAction
import com.shiguang.album.data.model.PlayMode
import com.shiguang.album.data.model.PlayOrder
import com.shiguang.album.ui.components.TvButton
import com.shiguang.album.ui.theme.TvTheme
import kotlinx.coroutines.delay

/**
 * 全屏播放页。
 *
 * 遥控器映射（与需求文档一致）：
 * - OK：暂停 / 继续
 * - 左 / 右：上一张 / 下一张
 * - 上 / 下：音量
 * - 菜单：播放设置（停留时长 / 动效 / 模式 / 顺序）
 * - 返回：退出到首页
 */
@Composable
fun PlayerScreen(
    container: AppContainer,
    playlistId: String,
    onExit: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val colors = TvTheme.colors
    val context = androidx.compose.ui.platform.LocalContext.current
    val controller = remember(playlistId) { PlayerController(context, container, scope, playlistId) }
    val ui = controller.ui

    val rootFocus = remember { FocusRequester() }
    var showSettings by remember { mutableStateOf(false) }
    var indicatorVisible by remember { mutableStateOf(false) }
    var arrowsVisible by remember { mutableStateOf(false) }
    // 「播完回首页」只允许触发一次：否则连按返回 / 重组都可能多退一层页面
    var leaving by remember { mutableStateOf(false) }

    DisposableEffect(playlistId) {
        controller.start()
        onDispose { controller.release() }
    }

    LaunchedEffect(Unit) { runCatching { rootFocus.requestFocus() } }
    LaunchedEffect(showSettings) {
        if (!showSettings) runCatching { rootFocus.requestFocus() }
    }

    // 每次切换：页码淡入 2 秒后消失；手动模式首次进入时短暂显示左右箭头提示
    LaunchedEffect(ui.pageToken) {
        indicatorVisible = true
        if (ui.playMode == PlayMode.MANUAL) arrowsVisible = true
        delay(2000)
        indicatorVisible = false
        delay(300)
        arrowsVisible = false
    }

    BackHandler { onExit() }

    /*
     * 「播完回首页」：控制器只把 ended 标出来，真正退出交给界面层，
     * 导航不该由播放控制器去碰。留 1.2 秒让最后一张停一下，不然是"啪"地黑屏。
     */
    LaunchedEffect(ui.ended) {
        if (!ui.ended) {
            leaving = false          // 从头重播了，下次播完还要能再退一次
            return@LaunchedEffect
        }
        if (ui.endAction == PlayEndAction.BACK_HOME && !leaving) {
            leaving = true
            delay(1200)
            onExit()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                if (showSettings) return@onKeyEvent false
                when (e.key) {
                    Key.DirectionRight -> { controller.next(); true }
                    Key.DirectionLeft -> { controller.prev(); true }
                    Key.DirectionUp -> { controller.volumeUp(); true }
                    Key.DirectionDown -> { controller.volumeDown(); true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { controller.togglePause(); true }
                    Key.Menu -> { showSettings = true; true }
                    else -> false
                }
            }
            .focusable()
    ) {
        // 画面：视频用 ExoPlayer，图片用带动效的双层过渡
        if (ui.isVideo && ui.videoFile != null) {
            VideoLayer(
                file = ui.videoFile,
                paused = ui.paused,
                onEnded = { controller.onVideoEnded() },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            SlideshowView(
                file = ui.currentFile,
                nextFile = ui.nextImageFile,
                effect = ui.effect,
                forward = ui.direction >= 0,
                modifier = Modifier.fillMaxSize()
            )
        }

        // 加载中：只在「一张都还没显示出来」时出现，切换过程中沿用上一张，避免闪黑
        if (ui.loading && ui.error == null && ui.currentFile == null && ui.videoFile == null) {
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(color = colors.accent, strokeWidth = 3.dp)
                Spacer(Modifier.height(14.dp))
                Text("正在加载…", color = colors.textSecondary, fontSize = 15.sp)
            }
        }

        // 出错
        ui.error?.let { msg ->
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(msg, color = colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(12.dp))
                Text("按返回键回到首页", color = colors.textSecondary, fontSize = 15.sp)
            }
        }

        // 页码指示器（右上角，淡入 2 秒后隐藏）
        AnimatedVisibility(
            visible = indicatorVisible,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(400)),
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            Box(
                Modifier
                    .padding(28.dp)
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text(ui.pageText, color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
        }

        // 手动模式的左右箭头提示
        AnimatedVisibility(
            visible = arrowsVisible,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(500)),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ArrowChip("←")
                ArrowChip("→")
            }
        }

        // 暂停遮罩
        AnimatedVisibility(visible = ui.paused) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.42f)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    PauseGlyph(88.dp)
                    Spacer(Modifier.height(22.dp))
                    Text(
                        text = ui.currentEntry?.name.orEmpty(),
                        color = colors.textPrimary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 64.dp)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "来源：${ui.sourceLabel}${if (ui.musicOn) " · 背景音乐播放中" else ""}",
                        color = colors.textSecondary,
                        fontSize = 14.sp
                    )
                }
            }
        }

        // 暂停时从底部滑入的控制栏
        AnimatedVisibility(
            visible = ui.paused,
            enter = slideInVertically(tween(220)) { it } + fadeIn(tween(200)),
            exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            ControlBar(
                fileName = ui.currentEntry?.name.orEmpty(),
                source = ui.sourceLabel,
                pageText = ui.pageText,
                playMode = ui.playMode,
                effect = ui.effect,
                staySeconds = ui.staySeconds
            )
        }

        // 播完提示：停在最后一张 / 即将回首页时给个明确的收尾说明，
        // 否则"画面不动了"会被当成卡死
        AnimatedVisibility(
            visible = ui.ended,
            enter = fadeIn(tween(220)),
            exit = fadeOut(tween(160)),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                Modifier
                    .padding(bottom = 52.dp)
                    .background(Color.Black.copy(alpha = 0.74f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 28.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "已播完 · 共 ${ui.entries.size} 张",
                    color = colors.textPrimary,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (ui.endAction == PlayEndAction.BACK_HOME) "即将返回首页…"
                    else "按 OK 重新播放 · 左右键翻看 · 返回键退出",
                    color = colors.textSecondary,
                    fontSize = 14.sp
                )
            }
        }

        if (showSettings) {
            PlayerSettingsDialog(
                playMode = ui.playMode,
                effect = ui.effect,
                staySeconds = ui.staySeconds,
                order = ui.order,
                endAction = ui.endAction,
                onToggleMode = { controller.togglePlayMode() },
                onCycleEffect = {
                    val all = listOf(
                        EffectType.FADE, EffectType.SLIDE_H, EffectType.SLIDE_V,
                        EffectType.ZOOM, EffectType.ROTATE, EffectType.MOSAIC, EffectType.RANDOM
                    )
                    val idx = all.indexOf(ui.effect)
                    controller.setEffect(all[(idx + 1) % all.size])
                },
                onCycleStay = { controller.cycleStay() },
                onCycleOrder = {
                    val all = listOf(PlayOrder.SEQUENCE, PlayOrder.SHUFFLE, PlayOrder.LOOP)
                    val idx = all.indexOf(ui.order)
                    controller.setOrder(all[(idx + 1) % all.size])
                },
                onCycleEndAction = {
                    val all = PlayEndAction.entries
                    val idx = all.indexOf(ui.endAction)
                    controller.setEndAction(all[(idx + 1) % all.size])
                },
                onDismiss = { showSettings = false }
            )
        }
    }
}

/* ------------------------------ 小部件 ------------------------------ */

@Composable
private fun PauseGlyph(size: Dp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(size * 0.26f)
                .height(size)
                .background(Color.White, RoundedCornerShape(size * 0.08f))
        )
        Spacer(Modifier.width(size * 0.16f))
        Box(
            Modifier
                .width(size * 0.26f)
                .height(size)
                .background(Color.White, RoundedCornerShape(size * 0.08f))
        )
    }
}

@Composable
private fun ArrowChip(text: String) {
    Box(
        Modifier
            .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(50))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(text, color = Color.White.copy(alpha = 0.85f), fontSize = 24.sp)
    }
}

@Composable
private fun ControlBar(
    fileName: String,
    source: String,
    pageText: String,
    playMode: PlayMode,
    effect: EffectType,
    staySeconds: Int
) {
    val colors = TvTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.78f))
            .padding(horizontal = 44.dp, vertical = 16.dp)
    ) {
        Text(
            text = fileName,
            color = colors.textPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        Row {
            Text("来源：$source", color = colors.textSecondary, fontSize = 14.sp)
            Spacer(Modifier.width(18.dp))
            Text(
                "模式：${if (playMode == PlayMode.AUTO) "自动" else "手动"}",
                color = colors.textSecondary,
                fontSize = 14.sp
            )
            Spacer(Modifier.width(18.dp))
            Text("动效：${effect.label()}", color = colors.textSecondary, fontSize = 14.sp)
            Spacer(Modifier.width(18.dp))
            Text("停留：${staySeconds}s", color = colors.textSecondary, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            Text(pageText, color = colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun PlayerSettingsDialog(
    playMode: PlayMode,
    effect: EffectType,
    staySeconds: Int,
    order: PlayOrder,
    endAction: PlayEndAction,
    onToggleMode: () -> Unit,
    onCycleEffect: () -> Unit,
    onCycleStay: () -> Unit,
    onCycleOrder: () -> Unit,
    onCycleEndAction: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = TvTheme.colors
    val focus = remember { FocusRequester() }
    BackHandler { onDismiss() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.82f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .width(560.dp)
                .background(colors.surface, RoundedCornerShape(18.dp))
                .padding(28.dp)
        ) {
            Text("播放设置", color = colors.textPrimary, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(18.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton(
                    text = "播放模式：${if (playMode == PlayMode.AUTO) "自动" else "手动"}（点按切换）",
                    modifier = Modifier.fillMaxWidth(),
                    primary = true,
                    focusRequester = focus,
                    onClick = onToggleMode
                )
                TvButton(
                    text = "切换动效：${effect.label()}（点按切换）",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onCycleEffect
                )
                TvButton(
                    text = "停留时长：${staySeconds} 秒（点按切换）",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onCycleStay
                )
                TvButton(
                    text = "播放顺序：${order.text()}（点按切换）",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onCycleOrder
                )
                TvButton(
                    text = "播完之后：${endAction.label()}（点按切换）",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onCycleEndAction
                )
                TvButton(
                    text = "返回播放",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onDismiss
                )
            }
        }
    }
}

private fun PlayOrder.text(): String = when (this) {
    PlayOrder.SEQUENCE -> "顺序"
    PlayOrder.SHUFFLE -> "随机"
    PlayOrder.LOOP -> "单张循环"
}
