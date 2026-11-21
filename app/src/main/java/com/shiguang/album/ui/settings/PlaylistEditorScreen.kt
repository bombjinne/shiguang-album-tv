package com.shiguang.album.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.shiguang.album.AppContainer
import com.shiguang.album.data.model.EffectType
import com.shiguang.album.data.model.FileRef
import com.shiguang.album.data.model.FolderRef
import com.shiguang.album.data.model.MediaEntry
import com.shiguang.album.data.model.MediaKind
import com.shiguang.album.data.model.MusicTrack
import com.shiguang.album.data.model.PlayEndAction
import com.shiguang.album.data.model.PlayMode
import com.shiguang.album.data.model.PlayOrder
import com.shiguang.album.data.model.Playlist
import com.shiguang.album.data.source.MediaTypes
import com.shiguang.album.ui.components.AppBackground
import com.shiguang.album.ui.components.Caption
import com.shiguang.album.ui.components.SectionTitle
import com.shiguang.album.ui.components.TvButton
import com.shiguang.album.ui.components.SettingsPageHeader
import com.shiguang.album.ui.components.TvInputRow
import com.shiguang.album.ui.components.TvKeyboardDialog
import com.shiguang.album.ui.components.TvSegmented
import com.shiguang.album.ui.components.TvSwitchRow
import com.shiguang.album.ui.player.hint
import com.shiguang.album.ui.player.label
import com.shiguang.album.ui.player.rememberMusicPreview
import com.shiguang.album.ui.theme.TvTheme
import com.shiguang.album.ui.theme.cardFill
import com.shiguang.album.ui.theme.cardStroke
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/* ============================================================================
 * 电视端「播放清单」编辑器
 *
 * 目标是：一个清单从零建起来，全程只用遥控器。
 * 内容选择走目录浏览器，文本走软键盘，选项用分段控件与按钮网格。
 * ========================================================================== */

private enum class Input { NAME, MUSIC_URL, FADE, STAY }

@Composable
fun PlaylistEditorScreen(
    container: AppContainer,
    playlistId: String?,
    editorToken: Long = 0L,
    onBack: () -> Unit
) {
    val colors = TvTheme.colors
    val scope = rememberCoroutineScope()
    val store = container.configStore
    val sources = store.current.sources

    val original = remember(playlistId, editorToken) { playlistId?.let { store.playlist(it) } }
    var draft by remember(playlistId, editorToken) {
        // 老配置里音乐是单首（file / customUrl / online），读进来先折算成列表，
        // 之后编辑、保存都只认 tracks，避免"明明加了歌却只播一首"
        val normalized = original?.let { pl ->
            if (pl.music.tracks.isEmpty()) {
                val legacy = pl.music.allTracks
                if (legacy.isEmpty()) pl else pl.copy(music = pl.music.copy(tracks = legacy))
            } else pl
        }
        mutableStateOf(normalized ?: Playlist(name = "新的播放清单"))
    }
    var input by remember { mutableStateOf<Input?>(null) }
    var showFolderPicker by remember { mutableStateOf(false) }
    var showCoverPicker by remember { mutableStateOf(false) }
    var showMusicPicker by remember { mutableStateOf(false) }
    var showOnlineSearch by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }

    // 已选在线歌曲的试听：只确认"能不能放"，与相册播放器互不影响
    val musicPreview = rememberMusicPreview()
    var onlineStatus by remember { mutableStateOf<String?>(null) }
    var onlineStatusError by remember { mutableStateOf(false) }

    // 封面预览：走和首页同一套缩略图缓存，选完立刻能看到效果（顺便验证这张电视能不能解）
    val coverRef = draft.coverFile
    val coverPreview by produceState<File?>(initialValue = null, coverRef) {
        value = coverRef?.let { ref ->
            val source = store.source(ref.sourceId)
            if (source == null) null else runCatching {
                container.mediaCache.cover(
                    MediaEntry(
                        sourceId = source.id,
                        sourceName = source.name,
                        path = ref.path,
                        name = ref.path.substringAfterLast('/'),
                        kind = MediaKind.IMAGE
                    )
                )
            }.getOrNull()
        }
    }
    val coverProblem = coverRef?.path?.let { MediaTypes.unsupportedReason(it) }

    val nameFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { nameFocus.requestFocus() } }
    LaunchedEffect(toast) { if (toast != null) { delay(2200); toast = null } }
    // 有弹窗时把返回键让给弹窗：否则按一次返回会「关弹窗 + 退页面」两层一起退
    val dialogOpen = input != null || showFolderPicker || showCoverPicker ||
        showMusicPicker || showOnlineSearch
    BackHandler(enabled = !dialogOpen) { onBack() }

    fun save() {
        if (draft.name.isBlank()) { toast = "请先填写清单名称"; return }
        if (draft.folders.isEmpty()) { toast = "至少选择一个文件夹"; return }
        scope.launch {
            // 老字段跟第一首同步一下：手机配置页、旧版本读的还是 file/customUrl/online
            val first = draft.music.tracks.firstOrNull()
            val music = if (first == null) draft.music else draft.music.copy(
                file = first.file,
                customUrl = first.url,
                online = first.online
            )
            store.upsertPlaylist(draft.copy(name = draft.name.trim(), music = music))
            onBack()
        }
    }

    AppBackground(container) {
        Column(Modifier.fillMaxSize()) {
            SettingsPageHeader(
                title = if (original == null) "新建播放清单" else "编辑播放清单",
                subtitle = "文件夹、动效、背景音乐都在这一页",
                onBack = onBack,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 10.dp)
            ) {
                TvButton(text = "保存", primary = true, onClick = { save() })
            }

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    // 不用 LazyColumn：设置页内容量不大，全量组合才能保证
                    // 遥控器一路按下来每一行都算得到焦点（懒加载里没组合的行可能被跳过）
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TvInputRow(
                    label = "清单名称",
                    value = draft.name,
                    placeholder = "例如：全家福轮播",
                    focusRequester = nameFocus,
                    onClick = { input = Input.NAME }
                )

                /* ---------------- 内容 ---------------- */
                SectionTitle("内容")

                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(colors.cardFill, RoundedCornerShape(14.dp))
                        .border(1.dp, colors.cardStroke, RoundedCornerShape(14.dp))
                        .padding(16.dp)
                ) {
                    if (draft.folders.isEmpty()) {
                        Caption("还没有选择文件夹。清单的内容来自这些文件夹里的图片和视频。")
                    } else {
                        draft.folders.forEachIndexed { index, ref ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${store.source(ref.sourceId)?.name ?: "（源已删除）"} ／ ${ref.path.ifBlank { "/" }}",
                                    color = colors.textPrimary,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                TvButton(
                                    text = "移除",
                                    danger = true,
                                    onClick = {
                                        draft = draft.copy(
                                            folders = draft.folders.filterIndexed { i, _ -> i != index }
                                        )
                                    }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    TvButton(
                        text = "添加文件夹（可多选）",
                        primary = true,
                        onClick = { showFolderPicker = true }
                    )
                }

                TvSwitchRow(
                    label = "包含子文件夹",
                    hint = "递归播放所选文件夹下的所有层级",
                    checked = draft.includeSubFolders,
                    onChange = { draft = draft.copy(includeSubFolders = it) }
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 缩略图（16:9），选完马上能看到，不用回首页确认
                    Box(
                        Modifier
                            .width(112.dp)
                            .height(63.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.surfaceHigh)
                            .border(1.dp, colors.border, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (coverPreview != null) {
                            AsyncImage(
                                model = coverPreview,
                                contentDescription = "封面预览",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text("封面", color = colors.textTertiary, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    TvButton(text = "选择封面图", onClick = { showCoverPicker = true })
                    Spacer(Modifier.width(10.dp))
                    if (draft.coverFile != null) {
                        TvButton(
                            text = "清除封面",
                            onClick = { draft = draft.copy(coverFile = null) }
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Caption(
                            text = draft.coverFile?.path?.substringAfterLast('/') ?: "默认取第一张图"
                        )
                        if (coverProblem != null) {
                            Spacer(Modifier.height(3.dp))
                            Text(coverProblem, color = colors.warning, fontSize = 12.sp)
                        }
                    }
                }

                /* ---------------- 背景音乐 ---------------- */
                SectionTitle("背景音乐")

                TvSwitchRow(
                    label = "启用背景音乐",
                    hint = "音量独立于系统音量，切换相片时不会断",
                    checked = draft.music.enabled,
                    onChange = { draft = draft.copy(music = draft.music.copy(enabled = it)) }
                )

                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(colors.cardFill, RoundedCornerShape(14.dp))
                        .border(1.dp, colors.cardStroke, RoundedCornerShape(14.dp))
                        .padding(16.dp)
                ) {
                    val tracks = draft.music.tracks
                    if (tracks.isEmpty()) {
                        Caption("还没有音乐。可以加电视/NAS 上的音频文件、一条 http 直链，或直接搜在线歌曲。")
                    } else {
                        tracks.forEachIndexed { index, track ->
                            val trackKey = "track|${track.url}|${track.online?.source}|${track.online?.id}"
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${index + 1}.",
                                    color = colors.textTertiary,
                                    fontSize = 14.sp,
                                    modifier = Modifier.width(28.dp)
                                )
                                Text(
                                    text = track.title,
                                    color = colors.textPrimary,
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = track.kindLabel,
                                    color = colors.textTertiary,
                                    fontSize = 12.sp,
                                    modifier = Modifier.width(44.dp)
                                )
                                if (track.online != null) {
                                    TvButton(
                                        text = if (musicPreview.playingKey == trackKey) "停止" else "试听",
                                        onClick = {
                                            if (musicPreview.playingKey == trackKey) {
                                                musicPreview.stop()
                                                onlineStatus = null
                                            } else {
                                                onlineStatus = "正在获取播放地址…"
                                                onlineStatusError = false
                                                scope.launch {
                                                    container.onlineMusic.resolvePlayable(track.online)
                                                        .onSuccess { url ->
                                                            musicPreview.play(trackKey, url)
                                                            onlineStatus = "试听中：${track.title}"
                                                            onlineStatusError = false
                                                        }
                                                        .onFailure {
                                                            onlineStatus = "无法播放：${it.message ?: "未知原因"}，建议换一首"
                                                            onlineStatusError = true
                                                        }
                                                }
                                            }
                                        }
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                TvButton(
                                    text = "↑",
                                    onClick = {
                                        if (index > 0) {
                                            val list = tracks.toMutableList()
                                            list.add(index - 1, list.removeAt(index))
                                            draft = draft.copy(music = draft.music.copy(tracks = list))
                                        }
                                    }
                                )
                                Spacer(Modifier.width(6.dp))
                                TvButton(
                                    text = "↓",
                                    onClick = {
                                        if (index < tracks.lastIndex) {
                                            val list = tracks.toMutableList()
                                            list.add(index + 1, list.removeAt(index))
                                            draft = draft.copy(music = draft.music.copy(tracks = list))
                                        }
                                    }
                                )
                                Spacer(Modifier.width(6.dp))
                                TvButton(
                                    text = "移除",
                                    danger = true,
                                    onClick = {
                                        musicPreview.stop()
                                        draft = draft.copy(
                                            music = draft.music.copy(
                                                tracks = tracks.filterIndexed { i, _ -> i != index }
                                            )
                                        )
                                    }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TvButton(
                            text = "＋ 音乐文件",
                            primary = tracks.isEmpty(),
                            onClick = { showMusicPicker = true }
                        )
                        TvButton(text = "＋ 在线音乐", onClick = { showOnlineSearch = true })
                        TvButton(text = "＋ 直链", onClick = { input = Input.MUSIC_URL })
                        if (tracks.isNotEmpty()) {
                            TvButton(
                                text = "清空",
                                danger = true,
                                onClick = {
                                    musicPreview.stop()
                                    draft = draft.copy(music = draft.music.copy(tracks = emptyList()))
                                }
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Caption(
                        if (tracks.isEmpty()) "支持 MP3 / FLAC / AAC / WAV"
                        else "共 ${tracks.size} 首 · 按上面顺序播放，开关下面的「循环」决定播完是否回到第一首"
                    )
                    onlineStatus?.let { text ->
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = text,
                            color = if (onlineStatusError) colors.danger else colors.textTertiary,
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("音乐音量", color = colors.textSecondary, fontSize = 15.sp, modifier = Modifier.width(150.dp))
                    TvButton(
                        text = "－",
                        onClick = {
                            val v = (draft.music.volume - 0.05f).coerceIn(0f, 1f)
                            draft = draft.copy(music = draft.music.copy(volume = v))
                        }
                    )
                    Box(Modifier.width(90.dp), contentAlignment = Alignment.Center) {
                        Text(
                            "${(draft.music.volume * 100).toInt()}%",
                            color = colors.textPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    TvButton(
                        text = "＋",
                        onClick = {
                            val v = (draft.music.volume + 0.05f).coerceIn(0f, 1f)
                            draft = draft.copy(music = draft.music.copy(volume = v))
                        }
                    )
                    Spacer(Modifier.width(20.dp))
                    TvButton(
                        text = "淡入淡出 ${draft.music.fadeMs}ms",
                        onClick = { input = Input.FADE }
                    )
                }

                TvSwitchRow(
                    label = "循环播放音乐",
                    hint = if (draft.music.tracks.size > 1) "播完最后一首回到第一首" else "单曲循环",
                    checked = draft.music.loop,
                    onChange = { draft = draft.copy(music = draft.music.copy(loop = it)) }
                )

                /* ---------------- 播放设置 ---------------- */
                SectionTitle("切换动效")

                EffectType.entries.chunked(4).forEach { rowEffects ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        rowEffects.forEach { effect ->
                            TvButton(
                                text = effect.label(),
                                primary = draft.effect == effect,
                                onClick = { draft = draft.copy(effect = effect) }
                            )
                        }
                    }
                }

                SectionTitle("播放模式")

                TvSegmented(
                    options = listOf(
                        PlayMode.AUTO to "自动播放",
                        PlayMode.MANUAL to "手动播放"
                    ),
                    selected = draft.playMode,
                    onSelect = { draft = draft.copy(playMode = it) }
                )

                Caption(
                    if (draft.playMode == PlayMode.AUTO)
                        "按停留时长自动切换，OK 键暂停/继续"
                    else
                        "不自动切换，左右键翻页，适合边看边讲"
                )

                SectionTitle("播放顺序")

                TvSegmented(
                    options = listOf(
                        PlayOrder.SEQUENCE to "顺序",
                        PlayOrder.SHUFFLE to "随机",
                        PlayOrder.LOOP to "单张循环"
                    ),
                    selected = draft.order,
                    onSelect = { draft = draft.copy(order = it) }
                )

                SectionTitle("播完之后")

                TvSegmented(
                    options = listOf(
                        PlayEndAction.LOOP to "循环播放",
                        PlayEndAction.HOLD_LAST to "停在最后一张",
                        PlayEndAction.BACK_HOME to "回到首页"
                    ),
                    selected = draft.endAction,
                    onSelect = { draft = draft.copy(endAction = it) }
                )

                Caption(
                    // 单张循环永远到不了末尾，说明一下，免得用户以为这一项没生效
                    if (draft.order == PlayOrder.LOOP)
                        "当前是「单张循环」，会一直重复同一张，这一项不会触发"
                    else draft.endAction.hint(),
                    color = colors.textTertiary
                )

                // 每张停留：整行点开软键盘精填，右边 ± 一键微调。
                // 遥控器上"从 5 秒改成 6 秒"不该走一遍全屏键盘，所以给个直接步进的通道。
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TvInputRow(
                        label = "每张停留",
                        value = "${draft.staySeconds} 秒",
                        placeholder = "5 秒",
                        modifier = Modifier.weight(1f),
                        onClick = { input = Input.STAY }
                    )
                    Spacer(Modifier.width(10.dp))
                    TvButton(
                        text = "－ 1 秒",
                        onClick = {
                            draft = draft.copy(staySeconds = (draft.staySeconds - 1).coerceAtLeast(1))
                        }
                    )
                    Spacer(Modifier.width(8.dp))
                    TvButton(
                        text = "＋ 1 秒",
                        onClick = {
                            draft = draft.copy(staySeconds = (draft.staySeconds + 1).coerceAtMost(600))
                        }
                    )
                }

                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton(text = "保存清单", primary = true, onClick = { save() })
                    if (original != null) {
                        TvButton(
                            text = "删除清单",
                            danger = true,
                            onClick = {
                                scope.launch {
                                    store.deletePlaylist(draft.id)
                                    onBack()
                                }
                            }
                        )
                    }
                }

                if (sources.isEmpty()) {
                    Caption(
                        "提示：还没有存储源。先去「存储源」添加一个本地目录或 NAS，才能选文件夹。",
                        color = colors.warning
                    )
                }
                Spacer(Modifier.height(30.dp))
            }
        }

        toast?.let { msg ->
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 40.dp)
                    .background(colors.surfaceHigh, RoundedCornerShape(10.dp))
                    .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                    .padding(horizontal = 22.dp, vertical = 12.dp)
            ) { Text(msg, color = colors.textPrimary, fontSize = 15.sp) }
        }
    }

    /* ---------------------------- 弹层 ---------------------------- */

    input?.let { which ->
        val cfg: Triple<String, String, Boolean> = when (which) {
            Input.NAME -> Triple("清单名称", draft.name, false)
            Input.MUSIC_URL -> Triple("音乐直链（http/https）", "", false)
            Input.FADE -> Triple("淡入淡出时长（毫秒）", draft.music.fadeMs.toString(), true)
            Input.STAY -> Triple("每张停留秒数", draft.staySeconds.toString(), true)
        }
        TvKeyboardDialog(
            title = cfg.first,
            initial = cfg.second,
            numeric = cfg.third,
            // 秒数/毫秒数这类数字：带着旧值打开，按第一个数字就整段覆盖
            replaceOnInput = cfg.third,
            onDismiss = { input = null },
            onConfirm = { value ->
                draft = when (which) {
                    Input.NAME -> draft.copy(name = value)
                    Input.MUSIC_URL -> {
                        val url = value.trim()
                        if (url.isBlank()) draft
                        else draft.copy(
                            music = draft.music.copy(
                                tracks = draft.music.tracks + MusicTrack(url = url),
                                enabled = true
                            )
                        )
                    }
                    Input.FADE -> draft.copy(
                        music = draft.music.copy(fadeMs = value.toIntOrNull()?.coerceIn(0, 15000) ?: 1500)
                    )
                    Input.STAY -> draft.copy(
                        staySeconds = value.toIntOrNull()?.coerceIn(1, 600) ?: 5
                    )
                }
                input = null
            }
        )
    }

    if (showFolderPicker) {
        TvSourceBrowserDialog(
            container = container,
            title = "选择文件夹（可多选）",
            mode = BrowserMode.FOLDERS,
            multiple = true,
            onDismiss = { showFolderPicker = false },
            onConfirm = { refs ->
                val before = draft.folders.size
                val merged = (draft.folders + refs.map { FolderRef(it.sourceId, it.path) })
                    .distinctBy { it.sourceId + "|" + it.path }
                draft = draft.copy(folders = merged)
                showFolderPicker = false
                // 直接按「确定」纳入当前文件夹时，若这个文件夹已在清单里，要明确告诉用户，
                // 否则会以为按钮没生效
                val added = merged.size - before
                toast = when {
                    added > 0 -> "已加入 $added 个文件夹"
                    refs.isNotEmpty() -> "这个文件夹已经在清单里了"
                    else -> null
                }
            }
        )
    }

    if (showCoverPicker) {
        TvSourceBrowserDialog(
            container = container,
            title = "选择封面图",
            mode = BrowserMode.IMAGE,
            multiple = false,
            onDismiss = { showCoverPicker = false },
            onConfirm = { refs ->
                refs.firstOrNull()?.let {
                    draft = draft.copy(coverFile = FileRef(it.sourceId, it.path))
                }
                showCoverPicker = false
            }
        )
    }

    if (showOnlineSearch) {
        OnlineMusicSearchDialog(
            container = container,
            onDismiss = { showOnlineSearch = false },
            onPick = { songs ->
                // 批量入库：先把已经在列表里的剔掉，剩下的一次性追加，避免重复曲目
                val existed = draft.music.tracks
                    .mapNotNull { t -> t.online?.let { it.source + "|" + it.id } }
                    .toSet()
                val fresh = songs.filter { (it.source + "|" + it.id) !in existed }
                val dupCount = songs.size - fresh.size
                if (fresh.isNotEmpty()) {
                    draft = draft.copy(
                        music = draft.music.copy(
                            tracks = draft.music.tracks + fresh.map { MusicTrack(online = it) },
                            enabled = true
                        )
                    )
                }
                showOnlineSearch = false
                onlineStatus = if (fresh.isEmpty()) {
                    "这些歌都已经在音乐列表里了"
                } else {
                    "已添加 ${fresh.size} 首，可以直接用"
                }
                onlineStatusError = false
                toast = when {
                    fresh.isEmpty() -> "都已在音乐列表里，没有重复添加"
                    dupCount == 0 -> "已添加 ${fresh.size} 首"
                    else -> "已添加 ${fresh.size} 首（${dupCount} 首重复已跳过）"
                }
            }
        )
    }

    if (showMusicPicker) {
        TvSourceBrowserDialog(
            container = container,
            title = "选择背景音乐（可多选）",
            mode = BrowserMode.AUDIO,
            multiple = true,
            onDismiss = { showMusicPicker = false },
            onConfirm = { refs ->
                if (refs.isNotEmpty()) {
                    val existing = draft.music.tracks.mapNotNull { it.file?.let { f -> f.sourceId + "|" + f.path } }.toSet()
                    val added = refs
                        .filterNot { existing.contains(it.sourceId + "|" + it.path) }
                        .map { MusicTrack(file = FileRef(it.sourceId, it.path)) }
                    draft = draft.copy(
                        music = draft.music.copy(
                            tracks = draft.music.tracks + added,
                            enabled = true
                        )
                    )
                    toast = when {
                        added.size > 0 -> "已添加 ${added.size} 首音乐"
                        else -> "这些音乐已经在列表里了"
                    }
                }
                showMusicPicker = false
            }
        )
    }
}
