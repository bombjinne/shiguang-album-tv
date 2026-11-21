package com.shiguang.album.ui.settings

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shiguang.album.AppContainer
import com.shiguang.album.data.model.ConnectionState
import com.shiguang.album.data.model.Playlist
import com.shiguang.album.data.model.SourceType
import com.shiguang.album.data.model.StorageSource
import com.shiguang.album.data.store.ConfigStore
import com.shiguang.album.ui.components.AppBackground
import com.shiguang.album.ui.components.QrCodeDialog
import com.shiguang.album.ui.components.SettingsBadge
import com.shiguang.album.ui.components.SettingsCount
import com.shiguang.album.ui.components.SettingsEmpty
import com.shiguang.album.ui.components.SettingsFootNote
import com.shiguang.album.ui.components.SettingsGroup
import com.shiguang.album.ui.components.SettingsGroupTitle
import com.shiguang.album.ui.components.SettingsPageHeader
import com.shiguang.album.ui.components.SettingsRow
import com.shiguang.album.ui.components.SettingsRowDivider
import com.shiguang.album.ui.components.SettingsToggle
import com.shiguang.album.ui.components.StatusDot
import com.shiguang.album.ui.components.TvButton
import com.shiguang.album.ui.components.TvKeyboardDialog
import com.shiguang.album.ui.components.TvSegmented
import com.shiguang.album.ui.player.label
import com.shiguang.album.ui.theme.TvTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/* ============================================================================
 * 电视端「管理」中心（V1.7 按 aerial-tv / tvOS 设置面板重排）
 *
 * 布局规则和 iOS/tvOS 设置一致：
 * - 顶栏是 27sp 大标题 + 一句说明，右侧放动作；
 * - 分类用胶囊分段（焦点即切换，遥控器左右一挪就换页）；
 * - 内容一律是「分组卡片」：卡片里每行左边标题、右边值，行间只有一根 hairline；
 *   不再给每个条目描边（描边一多整页就是一堵墙）。
 *
 * 功能一个没动：清单的编辑/复制/删除/排序、存储源的测试/编辑/删除、音乐服务地址，全在。
 * ========================================================================== */

private enum class ManageTab { PLAYLISTS, SOURCES, MUSIC }

@Composable
fun ManageScreen(
    container: AppContainer,
    onBack: () -> Unit,
    onEditPlaylist: (String?) -> Unit,
    onEditSource: (String?) -> Unit,
    onAppearance: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val appState by container.configStore.state.collectAsState()
    val colors = TvTheme.colors

    var tab by remember { mutableStateOf(ManageTab.PLAYLISTS) }
    var statuses by remember { mutableStateOf<Map<String, ConnectionState>>(emptyMap()) }
    var showQr by remember { mutableStateOf(false) }
    var showUrlKeyboard by remember { mutableStateOf(false) }
    var musicUrl by remember(appState.settings.musicServerUrl) {
        mutableStateOf(appState.settings.musicServerUrl)
    }
    var toast by remember { mutableStateOf<String?>(null) }

    // 二维码/软键盘弹窗打开时，返回键先关弹窗
    BackHandler(enabled = !showQr && !showUrlKeyboard) { onBack() }
    LaunchedEffect(toast) { if (toast != null) { delay(2200); toast = null } }

    LaunchedEffect(appState.sources) {
        container.repository.testAll()
        statuses = container.repository.status.value
    }

    fun move(index: Int, delta: Int) {
        val list = appState.playlists.sortedBy { it.sortIndex }.toMutableList()
        val target = index + delta
        if (target !in list.indices) return
        val tmp = list[index]
        list[index] = list[target]
        list[target] = tmp
        scope.launch {
            container.configStore.reorderPlaylists(list.map { it.id })
            toast = "顺序已更新"
        }
    }

    AppBackground(container) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 12.dp)
        ) {
            /* ---------------------------- 顶栏 ---------------------------- */
            SettingsPageHeader(
                title = "管理",
                subtitle = "所有配置都能用遥控器完成，改完立刻生效",
                onBack = onBack
            ) {
                TvButton(text = "外观与壁纸", onClick = onAppearance)
                Spacer(Modifier.width(10.dp))
                TvButton(text = "手机配置", primary = true, onClick = { showQr = true })
            }

            Spacer(Modifier.height(16.dp))

            TvSegmented(
                options = listOf(
                    ManageTab.PLAYLISTS to "播放清单 ${appState.playlists.size}",
                    ManageTab.SOURCES to "存储源 ${appState.sources.size}",
                    ManageTab.MUSIC to "音乐服务"
                ),
                selected = tab,
                // 顶部导航 tab：焦点移过去就切页
                selectOnFocus = true,
                onSelect = { tab = it }
            )

            Spacer(Modifier.height(18.dp))

            when (tab) {
                ManageTab.PLAYLISTS -> PlaylistsTab(
                    playlists = appState.playlists.sortedBy { it.sortIndex },
                    sourceNameOf = { id -> appState.sources.firstOrNull { it.id == id }?.name },
                    onNew = {
                        if (appState.sources.isEmpty()) toast = "请先添加存储源"
                        else onEditPlaylist(null)
                    },
                    onEdit = onEditPlaylist,
                    onDuplicate = { playlist ->
                        scope.launch {
                            container.configStore.duplicatePlaylist(playlist.id)
                            toast = "已复制「${playlist.name}」"
                        }
                    },
                    onDelete = { playlist ->
                        scope.launch {
                            container.configStore.deletePlaylist(playlist.id)
                            toast = "已删除「${playlist.name}」"
                        }
                    },
                    onMove = ::move
                )

                ManageTab.SOURCES -> SourcesTab(
                    sources = appState.sources,
                    statuses = statuses,
                    onAdd = { onEditSource(null) },
                    onTestAll = {
                        scope.launch {
                            container.repository.testAll()
                            statuses = container.repository.status.value
                            toast = "检测完成"
                        }
                    },
                    onTest = { source ->
                        scope.launch {
                            val r = container.repository.testSource(source.id)
                            statuses = container.repository.status.value
                            toast = if (r.ok) "连接正常：${r.message}" else "连接失败：${r.message}"
                        }
                    },
                    onEdit = { id -> onEditSource(id) },
                    onDelete = { source ->
                        scope.launch {
                            container.configStore.deleteSource(source.id)
                            toast = "已删除「${source.name}」"
                        }
                    }
                )

                ManageTab.MUSIC -> MusicTab(
                    modeText = container.onlineMusic.modeText(),
                    musicUrl = musicUrl,
                    onEditUrl = { showUrlKeyboard = true },
                    onSave = {
                        scope.launch {
                            container.configStore.updateSettings {
                                it.copy(musicServerUrl = musicUrl.trim())
                            }
                            toast = if (musicUrl.isBlank()) "已改回内置直连" else "已保存音乐服务地址"
                        }
                    },
                    onTest = {
                        scope.launch {
                            toast = "正在测试…"
                            container.onlineMusic.test(musicUrl)
                                .onSuccess { toast = it }
                                .onFailure { toast = it.message ?: "连接失败" }
                        }
                    },
                    onReset = {
                        musicUrl = ""
                        scope.launch {
                            container.configStore.updateSettings { it.copy(musicServerUrl = "") }
                            toast = "已改回内置直连（网易云 + 酷我）"
                        }
                    }
                )
            }

            Spacer(Modifier.height(40.dp))
        }

        toast?.let { msg ->
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
                    .background(colors.surfaceHigh, RoundedCornerShape(12.dp))
                    .padding(horizontal = 22.dp, vertical = 12.dp)
            ) { Text(msg, color = colors.textPrimary, fontSize = 15.sp) }
        }

        if (showUrlKeyboard) {
            TvKeyboardDialog(
                title = "在线音乐服务地址",
                initial = musicUrl,
                onDismiss = { showUrlKeyboard = false },
                onConfirm = { value ->
                    musicUrl = value.trim()
                    showUrlKeyboard = false
                }
            )
        }

        if (showQr) {
            QrCodeDialog(url = container.server.configUrl(), onDismiss = { showQr = false })
        }
    }
}

/* ---------------------------- 播放清单 ---------------------------- */

@Composable
private fun PlaylistsTab(
    playlists: List<Playlist>,
    sourceNameOf: (String) -> String?,
    onNew: () -> Unit,
    onEdit: (String) -> Unit,
    onDuplicate: (Playlist) -> Unit,
    onDelete: (Playlist) -> Unit,
    onMove: (Int, Int) -> Unit
) {
    val colors = TvTheme.colors

    SettingsGroupTitle("全部清单", note = "清单 = 文件夹 + 背景音乐 + 切换动效")
    Spacer(Modifier.height(8.dp))
    SettingsGroup {
        SettingsRow(
            title = "＋ 新建播放清单",
            desc = "从存储源里挑文件夹，配一套播放方式和音乐",
            chevron = true,
            onClick = onNew
        )
        if (playlists.isEmpty()) {
            Box(Modifier.padding(16.dp)) {
                SettingsEmpty("还没有播放清单，点上面那行新建一个")
            }
        }
    }

    if (playlists.isNotEmpty()) {
        Spacer(Modifier.height(18.dp))
        playlists.forEachIndexed { index, playlist ->
            PlaylistCard(
                playlist = playlist,
                index = index,
                total = playlists.size,
                sourceNames = playlist.folders.mapNotNull { ref -> sourceNameOf(ref.sourceId) }.distinct(),
                onEdit = { onEdit(playlist.id) },
                onDuplicate = { onDuplicate(playlist) },
                onDelete = { onDelete(playlist) },
                onUp = { onMove(index, -1) },
                onDown = { onMove(index, 1) }
            )
            Spacer(Modifier.height(10.dp))
        }
    }

    SettingsFootNote("提示：按「↑ / ↓」调整首页卡片的左右顺序；删除不可恢复。")
}

@Composable
private fun PlaylistCard(
    playlist: Playlist,
    index: Int,
    total: Int,
    sourceNames: List<String>,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onUp: () -> Unit,
    onDown: () -> Unit
) {
    val colors = TvTheme.colors
    // 整块是一个分组卡片：上半部分是"设置行"（点进去编辑），下半部分是一排操作按钮
    SettingsGroup {
        SettingsRow(
            title = playlist.name,
            desc = buildString {
                append("${playlist.folders.size} 个文件夹")
                append(" · ${playlist.effect.label()}")
                append(" · ${if (playlist.playMode.name == "AUTO") "自动" else "手动"}")
                append(" · ${playlist.staySeconds}s")
                if (sourceNames.isNotEmpty()) append(" · ${sourceNames.joinToString("、")}")
            },
            value = "编辑",
            chevron = true,
            divider = false,
            onClick = onEdit
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TvButton(text = "复制", onClick = onDuplicate)
            TvButton(text = "删除", danger = true, onClick = onDelete)
            Spacer(Modifier.weight(1f))
            TvButton(text = "↑", onClick = onUp)
            TvButton(text = "↓", onClick = onDown)
            Text(
                "${index + 1} / $total",
                color = colors.textTertiary,
                fontSize = 12.sp,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

/* ---------------------------- 存储源 ---------------------------- */

@Composable
private fun SourcesTab(
    sources: List<StorageSource>,
    statuses: Map<String, ConnectionState>,
    onAdd: () -> Unit,
    onTestAll: () -> Unit,
    onTest: (StorageSource) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (StorageSource) -> Unit
) {
    SettingsGroupTitle("存储源", note = "本地 / WebDAV / SMB")
    Spacer(Modifier.height(8.dp))
    SettingsGroup {
        SettingsRow(
            title = "＋ 添加存储源",
            desc = "电视本机文件夹、NAS 的 WebDAV 或 SMB 共享",
            chevron = true,
            onClick = onAdd
        )
        SettingsRow(
            title = "全部重新检测",
            desc = "依次连一遍，结果标在下面每一行",
            value = "${sources.count { statuses[it.id] == ConnectionState.ONLINE }} / ${sources.size} 在线",
            divider = sources.isNotEmpty(),
            onClick = onTestAll
        )
        if (sources.isEmpty()) {
            Box(Modifier.padding(16.dp)) {
                SettingsEmpty("还没有存储源。本地文件夹、WebDAV、SMB 都能在这里添加")
            }
        }
    }

    if (sources.isNotEmpty()) {
        Spacer(Modifier.height(18.dp))
        sources.forEach { source ->
            SourceCard(
                source = source,
                status = statuses[source.id] ?: ConnectionState.UNKNOWN,
                deletable = source.id != ConfigStore.BUILTIN_LOCAL_ID,
                onTest = { onTest(source) },
                onEdit = { onEdit(source.id) },
                onDelete = { onDelete(source) }
            )
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun SourceCard(
    source: StorageSource,
    status: ConnectionState,
    deletable: Boolean,
    onTest: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val colors = TvTheme.colors
    val (dotColor, statusText) = when (status) {
        ConnectionState.ONLINE -> colors.success to "在线"
        ConnectionState.OFFLINE -> colors.warning to "离线"
        ConnectionState.AUTH_FAILED -> colors.danger to "认证失败"
        ConnectionState.UNKNOWN -> colors.textTertiary to "未检测"
    }

    SettingsGroup {
        SettingsRow(
            title = source.name.ifBlank { "未命名源" },
            desc = "$statusText · ${source.typeText()} · ${source.detail()}",
            value = null,
            chevron = true,
            divider = false,
            onClick = onEdit
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusDot(dotColor)
            Spacer(Modifier.width(2.dp))
            Text("$statusText · ${source.typeText()}", color = colors.textSecondary, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            TvButton(text = "测试", onClick = onTest)
            TvButton(text = "编辑", onClick = onEdit)
            if (deletable) {
                TvButton(text = "删除", danger = true, onClick = onDelete)
            } else {
                SettingsBadge("内置", accent = false)
            }
        }
    }
}

/* ---------------------------- 音乐服务 ---------------------------- */

@Composable
private fun MusicTab(
    modeText: String,
    musicUrl: String,
    onEditUrl: () -> Unit,
    onSave: () -> Unit,
    onTest: () -> Unit,
    onReset: () -> Unit
) {
    val colors = TvTheme.colors

    SettingsGroupTitle("当前音源")
    Spacer(Modifier.height(8.dp))
    SettingsGroup {
        SettingsRow(
            title = "在线音乐",
            desc = "内置直连网易云 + 酷我，装完就能搜歌当背景音乐",
            value = modeText,
            chevron = false,
            divider = true,
            onClick = null
        )
        SettingsRow(
            title = "自建服务地址",
            desc = "留空即用内置直连；填了就走你自己的 go-music-dl",
            value = musicUrl.ifBlank { "未设置" },
            chevron = true,
            divider = false,
            onClick = onEditUrl
        )
    }

    Spacer(Modifier.height(12.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TvButton(text = "保存地址", primary = true, onClick = onSave)
        TvButton(text = "测试连接", onClick = onTest)
        if (musicUrl.isNotBlank()) {
            TvButton(text = "改回内置直连", danger = true, onClick = onReset)
        }
    }

    Spacer(Modifier.height(22.dp))

    SettingsGroupTitle("想听 QQ / 酷狗 / 咪咕 才需要自建", note = "可选")
    Spacer(Modifier.height(8.dp))
    SettingsGroup {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "go-music-dl 本身就是个「自己跑起来」的服务 —— 官方没有公网地址，安卓版也是把服务内嵌在本机。",
                color = colors.textSecondary,
                fontSize = 13.sp,
                lineHeight = 19.sp
            )
            Text(
                "1. NAS / 电脑上 docker compose up -d（镜像 guohuiyuan/go-music-dl）",
                color = colors.textSecondary,
                fontSize = 13.sp
            )
            Text(
                "2. 默认 8080 端口，地址形如 http://192.168.1.10:8080/music",
                color = colors.textSecondary,
                fontSize = 13.sp
            )
            Text(
                "3. 填到上面的输入框并测试连接；不填不影响内置直连",
                color = colors.textSecondary,
                fontSize = 13.sp
            )
        }
    }

    SettingsFootNote(
        "内置直连只做「搜索 + 取公开播放地址」，不下载、不破解会员内容；VIP 曲目会标出来，放不了就换一首。"
    )
}

/* ---------------------------- 文案映射 ---------------------------- */

private fun SourceType.label(): String = when (this) {
    SourceType.LOCAL -> "本地存储"
    SourceType.WEBDAV -> "WebDAV"
    SourceType.SMB -> "SMB 共享"
}

private fun StorageSource.typeText(): String = type.label()

private fun StorageSource.detail(): String = when (type) {
    SourceType.LOCAL -> localPaths.joinToString("、").ifBlank { "未选择目录" }
    SourceType.WEBDAV -> "${if (secure) "https" else "http"}://$host${if (port != 0) ":$port" else ""}$shareName"
    SourceType.SMB -> "smb://$host/${shareName.trim('/')}"
}
