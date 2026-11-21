package com.shiguang.album.ui.settings

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shiguang.album.data.store.ConfigStore
import com.shiguang.album.data.store.VideoWallpaperStore
import coil.compose.AsyncImage
import com.shiguang.album.AppContainer
import com.shiguang.album.data.model.FileRef
import com.shiguang.album.data.model.MediaEntry
import com.shiguang.album.data.model.MediaKind
import com.shiguang.album.data.model.ThemeMode
import com.shiguang.album.data.model.WallpaperKind
import com.shiguang.album.ui.components.AppBackground
import com.shiguang.album.ui.components.BuiltinWallpaperLayer
import com.shiguang.album.ui.components.BuiltinWallpapers
import com.shiguang.album.ui.components.SettingsFootNote
import com.shiguang.album.ui.components.SettingsGroup
import com.shiguang.album.ui.components.SettingsGroupTitle
import com.shiguang.album.ui.components.SettingsPageHeader
import com.shiguang.album.ui.components.SettingsRow
import com.shiguang.album.ui.components.SettingsSegmentBlock
import com.shiguang.album.ui.components.SettingsToggle
import com.shiguang.album.ui.components.TvButton
import com.shiguang.album.ui.theme.AccentPresets
import com.shiguang.album.ui.theme.TvTheme
import com.shiguang.album.ui.theme.cardFill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 电视端「外观」页。
 *
 * V1.7 按 aerial-tv / tvOS 的设置面板重排：大标题 + 分组卡片 + 「焦点是一块板」的行。
 * 功能一项没减，只是把原来"每个条目各自描边"的散装列表收成了分组，层级一下子清楚了。
 *
 * 壁纸图片/视频在电视上选（遥控器挑文件不如手机上舒服，但设置页里能一趟走完）。
 */
@Composable
fun AppearanceScreen(container: AppContainer, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val appState by container.configStore.state.collectAsState()
    val settings = appState.settings
    val colors = TvTheme.colors
    val wallpaper = settings.wallpaper

    var showImagePicker by remember { mutableStateOf(false) }
    var showVideoPicker by remember { mutableStateOf(false) }
    var videoBusy by remember { mutableStateOf(false) }
    var videoProgress by remember { mutableStateOf(0f) }
    var videoError by remember { mutableStateOf<String?>(null) }

    // 选中照片后要能马上看到长什么样：远程源（WebDAV/SMB）的图先落到本地缓存，再交给 Coil
    val previewFile by produceState<File?>(initialValue = null, wallpaper.file) {
        val ref = wallpaper.file
        value = if (ref != null && ref.path.isNotBlank()) {
            runCatching {
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

    // 视频壁纸已经是本地文件：clips() 按 index.txt 的顺序给清单，第一段拿来出缩略图
    var clipsVersion by remember { mutableStateOf(0) }
    val clips = remember(clipsVersion, wallpaper.kind) { container.videoWallpaper.clips() }
    val videoThumb by produceState<Bitmap?>(
        initialValue = null,
        clips.firstOrNull()?.file?.path,
        clips.size
    ) {
        value = clips.firstOrNull()?.let { withContext(Dispatchers.IO) { firstFrameOf(it.file) } }
    }
    val videoSize = clips.sumOf { it.sizeBytes }
    var showVideoList by remember { mutableStateOf(false) }

    // 选图/选视频弹窗打开时，返回键先关弹窗
    BackHandler(enabled = !showImagePicker && !showVideoPicker) { onBack() }

    fun apply(block: (com.shiguang.album.data.model.AppSettings) -> com.shiguang.album.data.model.AppSettings) {
        scope.launch { container.configStore.updateSettings(block) }
    }

    /**
     * 选完视频：先落到 filesDir，成功后才切壁纸（失败保持原样，不留黑屏）。
     *
     * [replace] true = 电视端「换一段」，清空旧清单只留这一段；
     * false = 追加（多段轮播）。
     */
    fun addVideo(ref: FileRef, replace: Boolean) {
        scope.launch {
            videoBusy = true
            videoProgress = 0f
            videoError = null
            if (replace) container.videoWallpaper.clear()
            val clip = container.videoWallpaper.addSource(ref) { p ->
                // 节流：进度回调很密，每 2% 才刷新一次界面
                if (p < 0f || p >= videoProgress + 0.02f || p >= 1f) videoProgress = p
            }
            videoBusy = false
            if (clip != null) {
                clipsVersion++
                apply {
                    it.copy(
                        wallpaper = it.wallpaper.copy(
                            kind = WallpaperKind.VIDEO,
                            file = ref,
                            dim = 0.5f
                        )
                    )
                }
            } else {
                videoError = "视频准备失败：源不可达，或者这台电视解不了这个格式"
            }
        }
    }

    AppBackground(container) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 12.dp)
        ) {
            SettingsPageHeader(
                title = "外观与壁纸",
                subtitle = "主题、背景和显示选项，改完立刻生效",
                onBack = onBack
            )

            Spacer(Modifier.height(20.dp))

            /* ------------------------------ 外观 ------------------------------ */
            SettingsGroupTitle("外观")
            Spacer(Modifier.height(8.dp))
            SettingsGroup {
                SettingsSegmentBlock(
                    title = "主题",
                    options = listOf(
                        ThemeMode.DARK to "深色",
                        ThemeMode.LIGHT to "浅色",
                        ThemeMode.AUTO to "跟随系统"
                    ),
                    selected = settings.themeMode,
                    note = "深色适合晚上关灯看，浅色适合白天客厅"
                ) { mode -> apply { it.copy(themeMode = mode) } }
                SettingsRowDividerSpacer()
                AccentRow(
                    selectedHex = settings.accentColor,
                    onPick = { hex -> apply { it.copy(accentColor = hex) } }
                )
            }

            Spacer(Modifier.height(22.dp))

            /* ------------------------------ 背景 ------------------------------ */
            SettingsGroupTitle("背景", note = "首页和设置页共用一张")
            Spacer(Modifier.height(8.dp))
            SettingsGroup {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        "类型",
                        color = colors.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        KindTile(
                            title = "纯色",
                            selected = wallpaper.kind == WallpaperKind.NONE,
                            scrim = false,
                            onClick = {
                                apply { it.copy(wallpaper = it.wallpaper.copy(kind = WallpaperKind.NONE)) }
                            }
                        ) { _, _ ->
                            // 用 surfaceHigh 而不是 background：浅色主题下 background 是浅灰，
                            // 再叠一层底部遮罩就变成"黑卡"，看着像选不中
                            Box(Modifier.fillMaxSize().background(colors.surfaceHigh))
                        }

                        KindTile(
                            title = "渐变",
                            selected = wallpaper.kind == WallpaperKind.GRADIENT,
                            scrim = false,
                            onClick = {
                                apply { it.copy(wallpaper = it.wallpaper.copy(kind = WallpaperKind.GRADIENT)) }
                            }
                        ) { dark, _ -> BuiltinWallpaperLayer(index = wallpaper.preset, dark = dark) }

                        KindTile(
                            title = "照片",
                            selected = wallpaper.kind == WallpaperKind.IMAGE,
                            onClick = {
                                if (wallpaper.file == null) showImagePicker = true
                                else apply { it.copy(wallpaper = it.wallpaper.copy(kind = WallpaperKind.IMAGE)) }
                            }
                        ) { _, _ ->
                            if (previewFile != null) {
                                AsyncImage(
                                    model = previewFile,
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                PhotoHint()
                            }
                        }

                        KindTile(
                            title = "视频",
                            badge = "动态",
                            selected = wallpaper.kind == WallpaperKind.VIDEO,
                            onClick = {
                                if (clips.isEmpty()) showVideoPicker = true
                                else apply { it.copy(wallpaper = it.wallpaper.copy(kind = WallpaperKind.VIDEO)) }
                            }
                        ) { _, _ ->
                            val bmp = videoThumb
                            if (bmp != null) {
                                androidx.compose.foundation.Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = null,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                VideoHint()
                            }
                        }
                    }
                }

                SettingsRowDividerSpacer()

                // 渐变预设：只在「渐变」类型下展开，避免整页太长
                if (wallpaper.kind == WallpaperKind.GRADIENT) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(
                            "预设",
                            color = colors.textPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            BuiltinWallpapers.forEachIndexed { index, preset ->
                                PresetTile(
                                    name = preset.name,
                                    selected = wallpaper.preset == index,
                                    onClick = {
                                        apply { it.copy(wallpaper = it.wallpaper.copy(preset = index)) }
                                    }
                                ) { dark -> BuiltinWallpaperLayer(index = index, dark = dark) }
                            }
                        }
                    }
                    SettingsRowDividerSpacer()
                }

                when (wallpaper.kind) {
                    WallpaperKind.IMAGE -> {
                        SettingsRow(
                            title = "壁纸照片",
                            value = wallpaper.file?.path?.substringAfterLast('/') ?: "未选择",
                            chevron = true,
                            onClick = { showImagePicker = true }
                        )
                        SettingsSegmentBlock(
                            title = "压暗程度",
                            options = listOf(
                                0.2f to "20%",
                                0.45f to "45%",
                                0.65f to "65%",
                                0.8f to "80%"
                            ),
                            selected = wallpaper.dim,
                            note = "压得越暗，标题越清楚"
                        ) { dim -> apply { it.copy(wallpaper = it.wallpaper.copy(dim = dim)) } }
                        SettingsRowDividerSpacer()
                        SettingsToggle(
                            title = "轻微模糊",
                            desc = "Android 12 及以上生效，人像照片更不抢眼",
                            checked = wallpaper.blur,
                            onToggle = {
                                apply { it.copy(wallpaper = it.wallpaper.copy(blur = !it.wallpaper.blur)) }
                            }
                        )
                        SettingsRow(
                            title = "换一张照片",
                            chevron = true,
                            divider = false,
                            onClick = { showImagePicker = true }
                        )
                    }

                    WallpaperKind.VIDEO -> {
                        SettingsRow(
                            title = "视频清单",
                            desc = when {
                                videoBusy -> "正在准备…"
                                clips.isEmpty() -> "还没有视频，点这里添加"
                                clips.size == 1 -> "1 段 · ${formatSize(videoSize)} · 静音循环"
                                else -> "${clips.size} 段轮播 · 共 ${formatSize(videoSize)} · 静音"
                            },
                            value = videoBusyText(videoBusy, videoProgress) ?: "${clips.size} 段",
                            chevron = !videoBusy,
                            onClick = { if (!videoBusy) showVideoList = true }
                        )
                        if (videoError != null) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                                Text(videoError!!, color = colors.warning, fontSize = 13.sp)
                            }
                            SettingsRowDividerSpacer()
                        }
                        SettingsSegmentBlock(
                            title = "压暗程度",
                            options = listOf(
                                0.3f to "30%",
                                0.5f to "50%",
                                0.7f to "70%",
                                0.85f to "85%"
                            ),
                            selected = wallpaper.dim,
                            note = "视频比较「跳」，默认 50% 已经能保证标题可读"
                        ) { dim -> apply { it.copy(wallpaper = it.wallpaper.copy(dim = dim)) } }
                        SettingsRowDividerSpacer()
                        SettingsToggle(
                            title = "轮播播放",
                            desc = if (clips.size <= 1) "只有一段时等于「单段循环」"
                            else "播完最后一段回到第一段接着放；关掉则停在最后一段",
                            checked = wallpaper.videoLoop,
                            onToggle = {
                                apply { it.copy(wallpaper = it.wallpaper.copy(videoLoop = !it.wallpaper.videoLoop)) }
                            }
                        )
                        SettingsRow(
                            title = "清空视频",
                            desc = "删掉全部本地副本（${formatSize(videoSize)}），把背景退回渐变"
                        ) {
                            scope.launch {
                                container.videoWallpaper.clear()
                                clipsVersion++
                                apply {
                                    it.copy(
                                        wallpaper = it.wallpaper.copy(
                                            kind = WallpaperKind.GRADIENT,
                                            file = null
                                        )
                                    )
                                }
                            }
                        }
                        SettingsRow(
                            title = "＋ 添加视频",
                            desc = "从存储源挑一段，追加到轮播清单末尾",
                            chevron = true,
                            divider = false,
                            onClick = { showVideoPicker = true }
                        )
                    }

                    else -> Unit
                }
            }

            if (wallpaper.kind == WallpaperKind.VIDEO) {
                SettingsFootNote(
                    "视频会先完整复制到本机（filesDir），之后每次开机都是本地播放、没有加载等待；" +
                        "多段视频按清单顺序轮播，手机扫码上传的视频也会排在这里。老盒子播不动 4K 就换 1080p。"
                )
            } else if (wallpaper.kind == WallpaperKind.IMAGE) {
                SettingsFootNote("从「电视本机」或任意 NAS 目录里挑一张；横图在电视上会按屏幕比例裁切铺满。")
            }

            Spacer(Modifier.height(22.dp))

            /* ---------------------------- 显示选项 ---------------------------- */
            SettingsGroupTitle("显示选项")
            Spacer(Modifier.height(8.dp))
            SettingsGroup {
                SettingsToggle(
                    title = "卡片显示播放模式",
                    desc = "在封面右上角标出「自动播放 / 手动翻页」",
                    checked = settings.showModeBadge,
                    divider = false,
                    onToggle = { apply { it.copy(showModeBadge = !it.showModeBadge) } }
                )
            }

            Spacer(Modifier.height(40.dp))
        }
    }

    if (showImagePicker) {
        TvSourceBrowserDialog(
            container = container,
            title = "选择壁纸图片",
            mode = BrowserMode.IMAGE,
            multiple = false,
            onDismiss = { showImagePicker = false },
            onConfirm = { refs ->
                refs.firstOrNull()?.let { ref ->
                    apply {
                        it.copy(
                            wallpaper = it.wallpaper.copy(
                                kind = WallpaperKind.IMAGE,
                                file = FileRef(ref.sourceId, ref.path)
                            )
                        )
                    }
                }
                showImagePicker = false
            }
        )
    }

    if (showVideoList) {
        VideoListDialog(
            clips = clips,
            onAdd = { showVideoList = false; showVideoPicker = true },
            onDelete = { name ->
                container.videoWallpaper.remove(name)
                clipsVersion++
                // 删空了就退回渐变，避免首页留一块占位
                if (container.videoWallpaper.isEmpty()) {
                    apply {
                        it.copy(wallpaper = it.wallpaper.copy(kind = WallpaperKind.GRADIENT, file = null))
                    }
                }
            },
            onMove = { from, to ->
                val names = clips.map { it.file.name }.toMutableList()
                if (from in names.indices && to in names.indices) {
                    val item = names.removeAt(from)
                    names.add(to, item)
                    container.videoWallpaper.reorder(names)
                    clipsVersion++
                }
            },
            onDismiss = { showVideoList = false }
        )
    }

    if (showVideoPicker) {
        // 有 Aerial 导出的视频就**直接落在那层目录**，省得每次都从根目录一层层点进去。
        // 目录不存在（没导出过）就退回默认根层，行为跟以前一致。
        val aerialDir = remember {
            runCatching {
                File(
                    android.os.Environment.getExternalStorageDirectory(),
                    "Movies/Aerial"
                ).takeIf { it.isDirectory }?.absolutePath
            }.getOrNull()
        }
        TvSourceBrowserDialog(
            container = container,
            title = "添加壁纸视频",
            mode = BrowserMode.VIDEO,
            multiple = true,
            initialSourceId = if (aerialDir != null) ConfigStore.BUILTIN_LOCAL_ID else null,
            initialPath = aerialDir.orEmpty(),
            onDismiss = { showVideoPicker = false },
            onConfirm = { refs ->
                refs.forEach { ref -> addVideo(FileRef(ref.sourceId, ref.path), replace = false) }
                showVideoPicker = false
            }
        )
    }
}

/* -------------------------------- 组件 -------------------------------- */

@Composable
private fun SettingsRowDividerSpacer() {
    com.shiguang.album.ui.components.SettingsRowDivider()
}

private fun videoBusyText(busy: Boolean, progress: Float): String? = when {
    !busy -> null
    progress <= 0f -> "准备中…"
    progress >= 1f -> "收尾…"
    else -> "复制中 ${(progress * 100).toInt()}%"
}

private fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> "0 MB"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format("%.1f MB", bytes / 1024f / 1024f)
}

/** 读视频第一帧做缩略图（TCL 等厂商的 MediaMetadataRetriever 偶尔会失败，失败就返回 null 用占位） */
private fun firstFrameOf(file: File): Bitmap? {
    val mmr = MediaMetadataRetriever()
    return try {
        mmr.setDataSource(file.path)
        mmr.getFrameAtTime(0)
    } catch (_: Exception) {
        null
    } finally {
        runCatching { mmr.release() }
    }
}

/** 背景类型卡：168×94（照 tvOS 壁纸卡的尺寸），焦点白环 + 选中橙框 */
@Composable
private fun KindTile(
    title: String,
    selected: Boolean,
    badge: String? = null,
    scrim: Boolean = true,
    onClick: () -> Unit,
    draw: @Composable (dark: Boolean, colors: com.shiguang.album.ui.theme.TvColors) -> Unit
) {
    val colors = TvTheme.colors
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, tween(150), label = "tileScale")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(width = 168.dp, height = 94.dp)
                .onFocusChanged { focused = it.isFocused }
                .androidxScale(scale)
                .background(Color.Black, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .border(
                    if (focused) 3.dp else if (selected) 3.dp else 1.dp,
                    when {
                        focused -> colors.focusRing
                        selected -> colors.accent
                        else -> colors.border
                    },
                    RoundedCornerShape(12.dp)
                )
                .clickable(onClick = onClick)
        ) {
            draw(colors.isDark, colors)
            if (scrim) {
                // 照片/视频才压一层：这两类内容亮度不可控，不压会让角标和勾看不清
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f))
                            )
                        )
                )
            }
            if (badge != null) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
                        .padding(horizontal = 7.dp, vertical = 2.dp)
                ) {
                    Text(badge, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                }
            }
            if (selected) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(20.dp)
                        .background(colors.accent, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("✓", color = colors.onAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            title,
            color = if (selected || focused) colors.textPrimary else colors.textSecondary,
            fontSize = 12.sp
        )
    }
}

/** 渐变预设小卡：108×62 */
@Composable
private fun PresetTile(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    draw: @Composable (dark: Boolean) -> Unit
) {
    val colors = TvTheme.colors
    var focused by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(width = 108.dp, height = 62.dp)
                .onFocusChanged { focused = it.isFocused }
                .background(Color.Black, RoundedCornerShape(10.dp))
                .clip(RoundedCornerShape(10.dp))
                .border(
                    if (focused) 3.dp else if (selected) 2.dp else 1.dp,
                    if (focused) colors.focusRing else if (selected) colors.accent else colors.border,
                    RoundedCornerShape(10.dp)
                )
                .clickable(onClick = onClick)
        ) {
            draw(colors.isDark)
            if (selected) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .size(16.dp)
                        .background(colors.accent, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("✓", color = colors.onAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            name,
            color = if (selected || focused) colors.textPrimary else colors.textSecondary,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 强调色一行：6 个圆点 + 名称，跟在分组卡片里 */
@Composable
private fun AccentRow(selectedHex: Long, onPick: (Long) -> Unit) {
    val colors = TvTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("强调色", color = colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AccentPresets.forEach { (name, hex) ->
                AccentSwatch(
                    name = name,
                    color = Color(hex),
                    // 按 RGB 比较：老配置里可能存的是 6 位 RGB（无 alpha），
                    // 直接和 8 位的预设值比会永远不等，选中的圆点就丢了
                    selected = (selectedHex and 0xFFFFFFL) == (hex and 0xFFFFFFL),
                    onClick = { onPick(hex) }
                )
            }
        }
    }
}

@Composable
private fun AccentSwatch(
    name: String,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit
) {
    val colors = TvTheme.colors
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.1f else 1f, tween(150), label = "swatchScale")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(46.dp)
                .onFocusChanged { focused = it.isFocused }
                .androidxScale(scale)
                .background(color, CircleShape)
                .border(
                    if (focused || selected) 3.dp else 1.dp,
                    when {
                        focused -> colors.focusRing
                        selected -> colors.textPrimary
                        else -> colors.border
                    },
                    CircleShape
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (selected) {
                Text(
                    "✓",
                    color = if (color.luminance() > 0.22f) Color(0xFF12141A) else Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            name,
            color = if (focused || selected) colors.textPrimary else colors.textSecondary,
            fontSize = 11.sp
        )
    }
}

/** 「照片」卡没有图时的占位：一个相框轮廓，不要纯黑 */
@Composable
private fun PhotoHint() {
    Box(Modifier.fillMaxSize().background(Color(0xFF2A3040)), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(width = 42.dp, height = 32.dp)
                .border(2.dp, Color.White.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
        )
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 52.dp, bottom = 26.dp)
                .size(9.dp)
                .background(Color.White.copy(alpha = 0.55f), CircleShape)
        )
    }
}

/** 「视频」卡没有首帧时的占位：胶片底 + 播放三角 */
@Composable
private fun VideoHint() {
    Box(Modifier.fillMaxSize().background(Color(0xFF3A2418)), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(34.dp)
                .background(Color.White.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("▶", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
        }
    }
}

/** 用 graphicsLayer 做缩放的小扩展（避免每个卡片重复写 lambda） */
private fun Modifier.androidxScale(scale: Float): Modifier = this.graphicsLayer {
    scaleX = scale
    scaleY = scale
}


/**
 * 视频清单弹窗（多段轮播的增删排序）。
 *
 * 做成弹窗而不是铺在外观页里：一段视频一行、再加一排按钮，页面会立刻被撑成两屏，
 * 而"管清单"本身就是低频操作 —— 收进弹窗，主页面只留一行摘要（"3 段轮播 · 共 45 MB"）。
 */
@Composable
private fun VideoListDialog(
    clips: List<VideoWallpaperStore.Clip>,
    onAdd: () -> Unit,
    onDelete: (String) -> Unit,
    onMove: (Int, Int) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = TvTheme.colors
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(620.dp)
                .background(colors.surface, RoundedCornerShape(20.dp))
                .border(1.dp, colors.border, RoundedCornerShape(20.dp))
                .padding(24.dp)
        ) {
            Text(
                "视频清单",
                color = colors.textPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                if (clips.isEmpty()) "还没有视频。可以从存储源挑，也可以在手机配置页直接上传。"
                else "按下面的顺序轮播 · 共 ${clips.size} 段 · ${formatSize(clips.sumOf { it.sizeBytes })}",
                color = colors.textTertiary,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(16.dp))

            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                clips.forEachIndexed { index, clip ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(colors.cardFill, RoundedCornerShape(10.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(26.dp)
                                .background(colors.surfaceHigh, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("${index + 1}", color = colors.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                clip.title,
                                color = colors.textPrimary,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                buildString {
                                    append(formatSize(clip.sizeBytes))
                                    if (clip.uploaded) append(" · 手机上传") else append(" · 存储源")
                                },
                                color = colors.textTertiary,
                                fontSize = 12.sp
                            )
                        }
                        TvButton(text = "↑", onClick = { onMove(index, index - 1) })
                        Spacer(Modifier.width(6.dp))
                        TvButton(text = "↓", onClick = { onMove(index, index + 1) })
                        Spacer(Modifier.width(6.dp))
                        TvButton(text = "删除", danger = true, onClick = { onDelete(clip.file.name) })
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TvButton(
                    text = "＋ 添加视频",
                    primary = true,
                    focusRequester = firstFocus,
                    onClick = onAdd
                )
                TvButton(text = "完成", onClick = onDismiss)
            }
        }
    }
}

/** 供手机端预览壁纸用（预留） */
fun previewWallpaperHex(w: com.shiguang.album.data.model.Wallpaper, dark: Boolean): List<Color> {
    val preset = BuiltinWallpapers.getOrNull(w.preset) ?: BuiltinWallpapers.first()
    return if (dark) preset.darkBase else preset.lightBase
}
