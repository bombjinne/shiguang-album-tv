package com.shiguang.album.ui.home

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import com.shiguang.album.AppContainer
import com.shiguang.album.BuildConfig
import com.shiguang.album.data.model.ConnectionState
import com.shiguang.album.data.model.MediaEntry
import com.shiguang.album.data.model.MediaKind
import com.shiguang.album.data.model.PlayEndAction
import com.shiguang.album.data.model.PlayMode
import com.shiguang.album.data.model.Playlist
import com.shiguang.album.data.model.StorageSource
import com.shiguang.album.data.model.Wallpaper
import com.shiguang.album.ui.components.Caption
import com.shiguang.album.ui.components.QrCodeDialog
import com.shiguang.album.ui.components.TvBrandMark
import com.shiguang.album.ui.components.TvButton
import com.shiguang.album.ui.components.TvChip
import com.shiguang.album.ui.components.TvDivider
import com.shiguang.album.ui.components.WallpaperBackground
import com.shiguang.album.ui.components.needsImageResolve
import com.shiguang.album.ui.components.needsVideoResolve
import com.shiguang.album.ui.theme.TvTheme
import com.shiguang.album.ui.player.label
import com.shiguang.album.util.NetworkUtil
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * 首页：壁纸背景 + 清单卡片网格 + 底部状态胶囊。
 *
 * 遥控器：方向键移动焦点，OK 进入全屏播放，长按 OK / 菜单键弹出清单菜单。
 * 右上角三个入口：外观（主题/壁纸）、存储源、手机扫码。
 */
@Composable
fun HomeScreen(
    container: AppContainer,
    onPlay: (Playlist) -> Unit,
    onManageSources: () -> Unit,
    onAppearance: () -> Unit,
    onEditPlaylist: (String?) -> Unit
) {
    val scope = rememberCoroutineScope()
    val state = remember { HomeState(scope, container) }
    val appState by container.configStore.state.collectAsState()
    val colors = TvTheme.colors

    var showQr by remember { mutableStateOf(false) }
    var menuPlaylist by remember { mutableStateOf<Playlist?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }

    val qrFocus = remember { FocusRequester() }
    val firstCardFocus = remember { FocusRequester() }
    val emptyFocus = remember { FocusRequester() }

    // 首页按返回：先提示一次，2 秒内再按才真的退出。
    // 电视上遥控器返回键很容易误触，一次就退出去会让人莫名其妙。
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var backArmedAt by remember { mutableLongStateOf(0L) }
    BackHandler {
        val now = System.currentTimeMillis()
        if (now - backArmedAt < 2000L) {
            activity?.finish()
        } else {
            backArmedAt = now
            toast = "再按一次返回键退出应用"
        }
    }

    // 壁纸图片：远程源要先下载到缓存，这里统一解析成 File
    val wallpaper = appState.settings.wallpaper
    val wallpaperFile by produceState<File?>(initialValue = null, wallpaper) {
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

    // 视频壁纸：选择时已经落到 filesDir（VideoWallpaperStore），这里直接拿本地文件，0 等待。
    // 多段视频按 index.txt 的顺序交给 ExoPlayer，由它做轮播。
    val wallpaperVideos by produceState<List<File>>(initialValue = emptyList(), wallpaper) {
        value = if (wallpaper.kind == com.shiguang.album.data.model.WallpaperKind.VIDEO) {
            container.videoWallpaper.playableFiles()
        } else emptyList()
    }

    LaunchedEffect(appState.playlists) { state.refresh() }
    DisposableEffect(Unit) { onDispose { state.stop() } }

    LaunchedEffect(state.cards.size, showQr, menuPlaylist) {
        if (state.cards.isNotEmpty() && !showQr && menuPlaylist == null) {
            runCatching { firstCardFocus.requestFocus() }
        } else if (state.cards.isEmpty() && !state.loading && !showQr && menuPlaylist == null) {
            // 空态也要有落点，否则遥控器方向键无处可去
            runCatching { emptyFocus.requestFocus() }
        }
    }

    LaunchedEffect(toast) {
        if (toast != null) {
            delay(2500)
            toast = null
        }
    }

    Box(Modifier.fillMaxSize()) {
        WallpaperBackground(
            wallpaper = wallpaper,
            imageFile = wallpaperFile,
            videoFiles = wallpaperVideos
        )

        Column(Modifier.fillMaxSize()) {
            HomeTopBar(
                playlistCount = appState.playlists.size,
                sourceCount = appState.sources.size,
                qrFocusRequester = qrFocus,
                onQrClick = { showQr = true },
                onManageClick = onManageSources,
                onAppearanceClick = onAppearance,
                onNewPlaylist = { onEditPlaylist(null) }
            )

            Box(Modifier.weight(1f)) {
                when {
                    state.cards.isEmpty() && state.loading -> LoadingSkeleton()
                    state.cards.isEmpty() -> EmptyState(
                        firstFocus = emptyFocus,
                        onCreate = { onEditPlaylist(null) },
                        onQrClick = { showQr = true }
                    )
                    else -> PlaylistGrid(
                        cards = state.cards,
                        firstCardFocus = firstCardFocus,
                        showModeBadge = appState.settings.showModeBadge,
                        onPlay = onPlay,
                        onMenu = { menuPlaylist = it }
                    )
                }
            }

            HomeBottomBar(statuses = state.statuses, sources = appState.sources)
        }

        // Toast
        toast?.let { msg ->
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 92.dp)
                    .background(colors.surfaceHigh.copy(alpha = 0.96f), RoundedCornerShape(10.dp))
                    .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                    .padding(horizontal = 22.dp, vertical = 12.dp)
            ) {
                Text(msg, color = colors.textPrimary, fontSize = 15.sp)
            }
        }

        if (showQr) {
            QrCodeDialog(url = container.server.configUrl(), onDismiss = { showQr = false })
        }

        menuPlaylist?.let { pl ->
            PlaylistMenuDialog(
                playlist = pl,
                onDismiss = { menuPlaylist = null },
                onPlay = { menuPlaylist = null; onPlay(pl) },
                onDuplicate = {
                    menuPlaylist = null
                    scope.launch {
                        container.configStore.duplicatePlaylist(pl.id)
                        toast = "已复制清单「${pl.name}」"
                    }
                },
                onDelete = {
                    menuPlaylist = null
                    scope.launch {
                        container.configStore.deletePlaylist(pl.id)
                        state.invalidate(pl.id)
                        toast = "已删除「${pl.name}」"
                    }
                },
                onEdit = { menuPlaylist = null; onEditPlaylist(pl.id) },
                onRecount = {
                    menuPlaylist = null
                    state.recount(pl.id)
                    toast = "正在重新统计「${pl.name}」的文件数…"
                }
            )
        }
    }
}

/* -------------------------------- 顶部栏 -------------------------------- */

@Composable
private fun HomeTopBar(
    playlistCount: Int,
    sourceCount: Int,
    qrFocusRequester: FocusRequester,
    onQrClick: () -> Unit,
    onManageClick: () -> Unit,
    onAppearanceClick: () -> Unit,
    onNewPlaylist: () -> Unit
) {
    val colors = TvTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // 顶部留 14dp：贴到最上沿在电视上会被 overscan 切掉一点，看着也憋
            .padding(top = 14.dp, start = 32.dp, end = 32.dp)
            .height(60.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TvBrandMark("拾")
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = "拾光相册",
                color = colors.textPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp
            )
            Spacer(Modifier.height(1.dp))
            Text(
                text = if (playlistCount == 0) "还没有播放清单"
                else "$playlistCount 个播放清单 · $sourceCount 个存储源",
                color = colors.textTertiary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.weight(1f))

        // 右侧统一用文字胶囊：emoji 图标在电视上会被系统渲染成彩色贴纸，
        // 和整体冷灰配色打架，改成「图标不出场、文字说清楚」更稳。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TvButton(text = "外观", onClick = onAppearanceClick)
            TvButton(text = "存储源", onClick = onManageClick)
            TvButton(text = "手机配置", onClick = onQrClick)
            Spacer(Modifier.width(2.dp))
            TvButton(
                text = "＋ 新建清单",
                primary = true,
                focusRequester = qrFocusRequester,
                onClick = onNewPlaylist
            )
        }
    }
}

/* -------------------------------- 底部栏 -------------------------------- */

@Composable
private fun HomeBottomBar(statuses: Map<String, ConnectionState>, sources: List<StorageSource>) {
    val colors = TvTheme.colors
    val clock by produceState(initialValue = NetworkUtil.clockText()) {
        while (true) {
            value = NetworkUtil.clockText()
            delay(15_000)
        }
    }

    // 底栏做成一条细状态带：贴着屏幕底，只报时间、日期、各源在线状态
    Column(Modifier.fillMaxWidth()) {
        TvDivider(Modifier.padding(horizontal = 32.dp).fillMaxWidth().background(colors.border.copy(alpha = 0.35f)))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(clock, color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(10.dp))
            Text(NetworkUtil.dateText(), color = colors.textTertiary, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                sources.take(4).forEach { src ->
                    val st = statuses[src.id] ?: ConnectionState.UNKNOWN
                    TvChip(
                        text = src.name,
                        // 源名可能很长（"客厅 NAS · WebDAV"），限宽避免把版本号挤出屏幕
                        modifier = Modifier.widthIn(max = 150.dp),
                        dot = when (st) {
                            ConnectionState.ONLINE -> colors.success
                            ConnectionState.AUTH_FAILED -> colors.danger
                            ConnectionState.OFFLINE -> colors.textTertiary
                            ConnectionState.UNKNOWN -> colors.textTertiary
                        }
                    )
                }
                if (sources.isEmpty()) TvChip(text = "尚未添加存储源")
            }
            Spacer(Modifier.width(12.dp))
            // 版本号放底栏最右：出问题时一眼能说出装的是哪版
            Text(
                text = "v${BuildConfig.VERSION_NAME}",
                color = colors.textTertiary,
                fontSize = 12.sp,
                maxLines = 1
            )
        }
    }
}

/* -------------------------------- 卡片网格 -------------------------------- */

@Composable
private fun PlaylistGrid(
    cards: List<PlaylistCardUi>,
    firstCardFocus: FocusRequester,
    showModeBadge: Boolean,
    onPlay: (Playlist) -> Unit,
    onMenu: (Playlist) -> Unit
) {
    // 卡片高度按「可用高度 / 3 行」反算：720p、1080p(2x)、超宽屏都能一屏看到 3 行，
    // 不会出现"卡片巨大但只看得到一行"的情况。
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val rows = 3
        val gap = 20.dp
        val cardHeight = ((maxHeight - 10.dp - gap * (rows - 1)) / rows)
            .coerceIn(132.dp, 208.dp)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 262.dp),
            contentPadding = PaddingValues(horizontal = 32.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(gap),
            verticalArrangement = Arrangement.spacedBy(gap),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(items = cards, key = { _, card -> card.playlist.id }) { index, card ->
                PlaylistCard(
                    card = card,
                    height = cardHeight,
                    focusRequester = if (index == 0) firstCardFocus else null,
                    showModeBadge = showModeBadge,
                    onPlay = { onPlay(card.playlist) },
                    onMenu = { onMenu(card.playlist) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistCard(
    card: PlaylistCardUi,
    height: androidx.compose.ui.unit.Dp,
    focusRequester: FocusRequester?,
    showModeBadge: Boolean,
    onPlay: () -> Unit,
    onMenu: () -> Unit
) {
    val colors = TvTheme.colors
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        // 1.05 而不是 1.08：三列并排时放大太多会互相挤压，1.05 刚好"跳出来"又不打扰邻居
        targetValue = if (focused) 1.05f else 1f,
        animationSpec = tween(200, easing = LinearOutSlowInEasing),
        label = "cardScale"
    )
    val borderColor by animateColorAsState(
        targetValue = if (focused) colors.accent else colors.border.copy(alpha = 0.55f),
        animationSpec = tween(200),
        label = "cardBorder"
    )
    val shape = RoundedCornerShape(18.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                shadowElevation = if (focused) 26f else 8f
                this.shape = shape
            }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.Menu) {
                    onMenu(); true
                } else false
            }
            .combinedClickable(onClick = onPlay, onLongClick = onMenu)
            .border(if (focused) 3.dp else 1.dp, borderColor, shape)
            .clip(shape)
            .background(colors.surface)
    ) {
        // 封面解码失败时不要留一块黑：直接退回渐变占位，用户至少知道"这张是占位不是坏图"
        var coverFailed by remember(card.coverFile) { mutableStateOf(false) }
        if (card.coverFile != null && !coverFailed) {
            AsyncImage(
                model = card.coverFile,
                contentDescription = card.playlist.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onState = { s ->
                    if (s is AsyncImagePainter.State.Error) coverFailed = true
                }
            )
        } else {
            // 无封面：用渐变占位，比纯灰更耐看
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(colors.surfaceHigh, colors.surface)
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "拾光",
                    color = colors.textTertiary.copy(alpha = 0.55f),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // 底部渐变：保证任意封面上文字都可读
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(132.dp)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.45f to Color.Black.copy(alpha = 0.35f),
                        1f to Color.Black.copy(alpha = 0.92f)
                    )
                )
        )

        Column(
            Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Text(
                text = card.playlist.name,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = when {
                    (card.count ?: 0) > 0 ->
                        "${card.count} 个文件 · ${card.playlist.staySeconds}s · ${card.playlist.effect.label()}" +
                            // 播完不是循环的话，卡片上标一下，避免"放完停了"被当成出错
                            when (card.playlist.endAction) {
                                PlayEndAction.LOOP -> ""
                                PlayEndAction.HOLD_LAST -> " · 播完停住"
                                PlayEndAction.BACK_HOME -> " · 播完退出"
                            }
                    card.count == 0 -> "空清单 · 还没有可播放的照片"
                    else -> "统计中…"
                },
                color = Color.White.copy(alpha = 0.78f),
                fontSize = 12.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // 右上角：播放模式角标
        if (showModeBadge) {
            val manual = card.playlist.playMode == PlayMode.MANUAL
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp)
                    .background(
                        if (manual) colors.accent.copy(alpha = 0.96f) else Color.Black.copy(alpha = 0.42f),
                        RoundedCornerShape(50)
                    )
                    .border(
                        1.dp,
                        if (manual) Color.Transparent else Color.White.copy(alpha = 0.22f),
                        RoundedCornerShape(50)
                    )
                    .padding(horizontal = 11.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (manual) "手动翻页" else "自动播放",
                    color = if (manual) colors.onAccent else Color.White.copy(alpha = 0.92f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        // 焦点态：中央播放圆钮
        AnimatedVisibility(
            visible = focused,
            enter = fadeIn(tween(160)),
            exit = fadeOut(tween(120)),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Box(
                Modifier
                    .size(58.dp)
                    .background(colors.accent, CircleShape)
                    .border(2.dp, colors.onAccent.copy(alpha = 0.25f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("▶", color = colors.onAccent, fontSize = 21.sp)
            }
        }
    }
}

/* -------------------------------- 骨架 / 空态 -------------------------------- */

@Composable
private fun LoadingSkeleton() {
    val colors = TvTheme.colors
    // 呼吸式占位：静止的灰块在电视上看着像"卡住了"，缓慢明暗变化能传达"正在加载"
    val pulse by rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0.35f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "skeletonAlpha"
    )
    Row(
        Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        repeat(4) {
            Box(
                Modifier
                    .weight(1f)
                    .height(168.dp)
                    .background(colors.surface.copy(alpha = pulse), RoundedCornerShape(18.dp))
                    .border(1.dp, colors.border.copy(alpha = pulse * 0.5f), RoundedCornerShape(18.dp))
            )
        }
    }
}

@Composable
private fun EmptyState(
    firstFocus: FocusRequester,
    onCreate: () -> Unit,
    onQrClick: () -> Unit
) {
    val colors = TvTheme.colors
    // 注意：套一层滚动容器 + heightIn(min = 视口高)。
    // 直接放 Column 时，高度不够会把后面的子项（主色按钮）压成一条绿线；
    // 滚动容器给的是无限高约束，子项永远拿到完整高度，内容多时还能上下滚。
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                Modifier
                    .size(88.dp)
                    .background(
                        Brush.linearGradient(
                            listOf(colors.surfaceHigh, colors.surface)
                        ),
                        CircleShape
                    )
                    .border(1.dp, colors.border, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "拾光",
                    color = colors.accent,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(16.dp))
            Text(
                text = "还没有播放清单",
                color = colors.textPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Caption("按「＋ 新建清单」，用遥控器选几个文件夹就行")
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TvButton(
                    text = "＋ 新建播放清单",
                    primary = true,
                    focusRequester = firstFocus,
                    onClick = onCreate
                )
                TvButton(text = "用手机配置", onClick = onQrClick)
            }
            Spacer(Modifier.height(10.dp))
            Caption("也可以先在右上角「外观」里挑个主题和壁纸", color = colors.textTertiary)
        }
    }
}

/* ------------------------------ 清单菜单弹窗 ------------------------------ */

@Composable
private fun PlaylistMenuDialog(
    playlist: Playlist,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onRecount: () -> Unit
) {
    val colors = TvTheme.colors
    val focus = remember { FocusRequester() }
    androidx.activity.compose.BackHandler { onDismiss() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(
        Modifier.fillMaxSize().background(colors.scrim),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .width(540.dp)
                .background(colors.surface, RoundedCornerShape(20.dp))
                .border(1.dp, colors.border, RoundedCornerShape(20.dp))
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                playlist.name,
                color = colors.textPrimary,
                fontSize = 23.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(8.dp))
            Caption(
                "${playlist.folders.size} 个文件夹 · ${playlist.effect.label()} · 每张 ${playlist.staySeconds}s · " +
                    if (playlist.playMode == PlayMode.MANUAL) "手动" else "自动"
            )
            Spacer(Modifier.height(24.dp))

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton("开始播放", Modifier.fillMaxWidth(), primary = true, focusRequester = focus, onClick = onPlay)
                TvButton("编辑清单", Modifier.fillMaxWidth(), onClick = onEdit)
                TvButton("重新统计文件数", Modifier.fillMaxWidth(), onClick = onRecount)
                TvButton("复制清单", Modifier.fillMaxWidth(), onClick = onDuplicate)
                TvButton("删除清单", Modifier.fillMaxWidth(), danger = true, onClick = onDelete)
                TvButton("取消", Modifier.fillMaxWidth(), onClick = onDismiss)
            }
        }
    }
}

/** 从 Compose 的 Context 里扒出宿主 Activity（用来手动 finish） */
private fun Context.findActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
