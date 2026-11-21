package com.shiguang.album.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shiguang.album.AppContainer
import com.shiguang.album.data.model.OnlineSong
import com.shiguang.album.ui.components.TvButton
import com.shiguang.album.ui.components.TvKeyboardDialog
import com.shiguang.album.ui.components.focusBringIntoView
import com.shiguang.album.ui.player.rememberMusicPreview
import com.shiguang.album.ui.theme.TvTheme
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 在线音乐搜索：给播放清单挑背景音乐用。
 *
 * 默认走内置直连（网易云 + 酷我），填了自建聚合服务就走服务。
 *
 * 关键改动：
 * - **支持多选批量添加**：每首歌左边有勾选框，底部「添加 N 首」一次收下，
 *   不用为了加 5 首背景音乐来回开关弹窗。批量添加时按每批 3 首并发解析地址，
 *   解析不了的跳过并把名字列出来，不会因为一首死链整批失败。
 * - 每首歌都能「试听」（先解析地址 → MediaPlayer 直接放 8 秒就够判断）
 * - 单首「选用」前自动做一次播放测试，放不了就留在弹窗里说明原因
 */
@Composable
fun OnlineMusicSearchDialog(
    container: AppContainer,
    onDismiss: () -> Unit,
    onPick: (List<OnlineSong>) -> Unit
) {
    val colors = TvTheme.colors
    val scope = rememberCoroutineScope()
    // 默认走内置直连（网易云 + 酷我），填了自建服务就走服务的多平台聚合
    val serverMode = container.onlineMusic.isServerConfigured()

    var keyword by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<OnlineSong>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showKeyboard by remember { mutableStateOf(false) }
    var searched by remember { mutableStateOf(false) }

    /** 已勾选的歌（source|id），批量添加用 */
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    /** 批量添加进度：已处理 / 总数；null 表示当前没有在批量添加 */
    var batchProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    // 正在解析/测试的歌曲 key（source|id）
    var busyKey by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }

    val preview = rememberMusicPreview()

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { firstFocus.requestFocus() } }

    fun keyOf(song: OnlineSong): String = song.source + "|" + song.id

    fun doSearch(word: String) {
        if (word.isBlank()) return
        keyword = word
        loading = true
        error = null
        searched = true
        // 换了一批结果，旧的勾选不再有意义
        selected = emptySet()
        preview.stop()
        scope.launch {
            container.onlineMusic.search(word)
                .onSuccess { results = it }
                .onFailure {
                    results = emptyList()
                    error = it.message ?: "搜索失败"
                }
            loading = false
        }
    }

    fun toggleSelect(song: OnlineSong) {
        val key = keyOf(song)
        selected = if (key in selected) selected - key else selected + key
    }

    /** 试听 / 停止：解析地址 → 播放；失败就把原因写在底部 */
    fun togglePreview(song: OnlineSong) {
        val key = keyOf(song)
        if (preview.isPlaying(key)) {
            preview.stop()
            status = null
            statusIsError = false
            return
        }
        preview.stop()
        busyKey = key
        status = "正在获取播放地址…"
        statusIsError = false
        scope.launch {
            container.onlineMusic.resolvePlayable(song)
                .onSuccess { url ->
                    preview.play(key, url)
                    status = "试听中：${song.displayName}"
                    statusIsError = false
                }
                .onFailure {
                    status = "「${song.name}」无法试听：${it.message ?: "未知原因"}"
                    statusIsError = true
                }
            busyKey = null
        }
    }

    /** 选用（单首）：先测一遍再收下，避免把死链存进清单 */
    fun pick(song: OnlineSong) {
        val key = keyOf(song)
        busyKey = key
        status = "正在测试「${song.name}」能否播放…"
        statusIsError = false
        scope.launch {
            container.onlineMusic.resolvePlayable(song)
                .onSuccess {
                    preview.stop()
                    onPick(listOf(song))
                }
                .onFailure {
                    status = "「${song.name}」无法播放：${it.message ?: "未知原因"}，换一首试试"
                    statusIsError = true
                    busyKey = null
                }
        }
    }

    /**
     * 批量添加：一次把勾选的歌都收下。
     *
     * 逐首串行解析，5 首歌要等十几秒，用户会以为卡死；全并发又容易被音源限流。
     * 折中按每批 3 首并发，底部状态实时显示「测试中 3/7」。
     * 解析失败的**不阻断整批** —— 跳过去，最后把名字列出来让用户自己决定。
     */
    fun addSelected() {
        val songs = results.filter { keyOf(it) in selected }
        if (songs.isEmpty()) {
            status = "先勾选几首再加，或者点「全选」"
            statusIsError = true
            return
        }
        preview.stop()
        batchProgress = 0 to songs.size
        status = "正在测试 ${songs.size} 首…"
        statusIsError = false
        scope.launch {
            val ok = mutableListOf<OnlineSong>()
            val failed = mutableListOf<String>()
            var done = 0
            for (batch in songs.chunked(3)) {
                val checked = coroutineScope {
                    batch.map { song ->
                        async { song to container.onlineMusic.resolvePlayable(song) }
                    }.awaitAll()
                }
                checked.forEach { (song, result) ->
                    if (result.isSuccess) ok += song else failed += song.name
                    done++
                }
                batchProgress = done to songs.size
                status = "正在测试 $done/${songs.size}…"
            }
            batchProgress = null
            when {
                ok.isEmpty() -> {
                    status = "这 ${songs.size} 首都放不了，换个关键词试试"
                    statusIsError = true
                }
                failed.isEmpty() -> {
                    selected = emptySet()
                    onPick(ok)
                }
                else -> {
                    selected = failed.mapNotNull { name -> results.firstOrNull { it.name == name } }
                        .map { keyOf(it) }.toSet()
                    status = "已添加 ${ok.size} 首；${failed.size} 首放不了已跳过：${failed.take(3).joinToString("、")}" +
                        if (failed.size > 3) "…" else ""
                    statusIsError = false
                    onPick(ok)
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(Modifier.fillMaxSize().background(colors.scrim), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .widthIn(max = 1100.dp)
                    .fillMaxHeight(0.92f)
                    .background(colors.surface, RoundedCornerShape(16.dp))
                    .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "搜索在线音乐",
                        color = colors.textPrimary,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        if (serverMode) "走自建聚合服务（13 个音源）" else "内置直连：网易云 + 酷我",
                        color = colors.textTertiary,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.weight(1f))
                    TvButton(text = "关闭", onClick = onDismiss)
                }

                Spacer(Modifier.height(10.dp))

                // 搜索行
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .weight(1f)
                            .background(colors.surfaceHigh, RoundedCornerShape(9.dp))
                            .border(1.dp, colors.border, RoundedCornerShape(9.dp))
                            .clickable { showKeyboard = true }
                            .heightIn(min = 46.dp)
                            .padding(horizontal = 14.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Text(
                            if (keyword.isBlank()) "点这里输入歌名或歌手" else keyword,
                            color = if (keyword.isBlank()) colors.textTertiary else colors.textPrimary,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    TvButton(
                        text = if (loading) "搜索中…" else "搜索",
                        primary = true,
                        focusRequester = firstFocus,
                        onClick = { if (!loading) doSearch(keyword) }
                    )
                }

                Spacer(Modifier.height(10.dp))

                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(colors.surfaceHigh.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                ) {
                    when {
                        loading -> CircularProgressIndicator(
                            color = colors.accent,
                            strokeWidth = 3.dp,
                            modifier = Modifier.align(Alignment.Center).width(36.dp)
                        )
                        error != null -> CenterTip("搜索失败：$error")
                        !searched -> CenterTip("输入歌名或歌手，比如「周杰伦」")
                        results.isEmpty() -> CenterTip("没有搜到结果，换个关键词试试")
                        else -> LazyColumn(Modifier.fillMaxSize().padding(6.dp)) {
                            items(results, key = { it.source + "|" + it.id }) { song ->
                                val key = keyOf(song)
                                SongRow(
                                    song = song,
                                    checked = key in selected,
                                    playing = preview.isPlaying(key),
                                    busy = busyKey == key,
                                    onToggleSelect = { toggleSelect(song) },
                                    onPreview = { togglePreview(song) },
                                    onPick = { pick(song) }
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // 批量操作条：勾选 → 一次添加多首
                if (results.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TvButton(
                            text = "全选",
                            onClick = { selected = results.map { keyOf(it) }.toSet() }
                        )
                        Spacer(Modifier.width(8.dp))
                        TvButton(
                            text = "清空选择",
                            onClick = { selected = emptySet() }
                        )
                        Spacer(Modifier.width(14.dp))
                        Text(
                            if (selected.isEmpty()) "勾选多首可以一次加进清单"
                            else "已选 ${selected.size} / ${results.size} 首",
                            color = if (selected.isEmpty()) colors.textTertiary else colors.accentText,
                            fontSize = 13.sp,
                            fontWeight = if (selected.isEmpty()) FontWeight.Normal else FontWeight.SemiBold
                        )
                        Spacer(Modifier.weight(1f))
                        TvButton(
                            text = when {
                                batchProgress != null -> "添加中 ${batchProgress!!.first}/${batchProgress!!.second}"
                                selected.isEmpty() -> "添加已选"
                                else -> "添加已选 ${selected.size} 首"
                            },
                            primary = true,
                            onClick = { if (batchProgress == null && selected.isNotEmpty()) addSelected() }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                }

                // 状态/提示行：试听结果、测试失败原因都在这里说清楚
                Text(
                    text = status ?: if (results.isEmpty()) {
                        "先搜索，再勾选多首一次添加；单首也可以直接点「选用」"
                    } else {
                        "勾选后点「添加已选」批量入库；点「试听」可以逐首试听"
                    },
                    color = when {
                        statusIsError -> colors.danger
                        preview.playingKey != null -> colors.accent
                        else -> colors.textTertiary
                    },
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    if (showKeyboard) {
        TvKeyboardDialog(
            title = "搜索关键词",
            initial = keyword,
            onDismiss = { showKeyboard = false },
            onConfirm = { value ->
                showKeyboard = false
                doSearch(value)
            }
        )
    }
}

@Composable
private fun CenterTip(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            color = TvTheme.colors.textSecondary,
            fontSize = 15.sp,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
    }
}

/**
 * 批量勾选框。
 *
 * 刻意用 `focusable()` + 显式 KeyEvent 而不是 `clickable`：电视上 `clickable` 在焦点搜索里
 * 经常被跳过，表现就是遥控器怎么按都选不中这个方块（设置页的 PlateRow 踩过同一个坑）。
 * 尺寸给到 28dp —— 10 英尺外看得清，手指点也不会太窄。
 */
@Composable
private fun SelectBox(checked: Boolean, onToggle: () -> Unit) {
    val colors = TvTheme.colors
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .size(28.dp)
            .focusBringIntoView()
            .onFocusChanged { focused = it.isFocused }
            .background(if (checked) colors.accent else Color.Transparent, shape)
            .border(
                width = if (focused) 2.dp else 1.5.dp,
                color = when {
                    focused -> colors.focusRing
                    checked -> colors.accent
                    else -> colors.textTertiary.copy(alpha = 0.7f)
                },
                shape = shape
            )
            .onKeyEvent { e ->
                val activate = e.type == KeyEventType.KeyUp &&
                    (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter)
                if (activate) { onToggle(); true } else false
            }
            .focusable(interactionSource = remember { MutableInteractionSource() }),
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            Text("✓", color = colors.onAccent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SongRow(
    song: OnlineSong,
    checked: Boolean,
    playing: Boolean,
    busy: Boolean,
    onToggleSelect: () -> Unit,
    onPreview: () -> Unit,
    onPick: () -> Unit
) {
    val colors = TvTheme.colors
    var focused by remember { mutableStateOf(false) }
    val bg by animateColorAsState(
        targetValue = when {
            checked -> colors.accent.copy(alpha = 0.18f)
            playing -> colors.accent.copy(alpha = 0.12f)
            focused -> colors.surfaceHigh
            else -> colors.surface.copy(alpha = 0.5f)
        },
        animationSpec = tween(150),
        label = "songBg"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .onFocusChanged { focused = it.isFocused }
            .background(bg, RoundedCornerShape(8.dp))
            .border(
                2.dp,
                when {
                    checked -> colors.accent.copy(alpha = 0.55f)
                    focused -> colors.focusRing
                    else -> colors.border.copy(alpha = 0.5f)
                },
                RoundedCornerShape(8.dp)
            )
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 勾选框：遥控器聚焦后按 OK 切换，和「试听/选用」三个焦点并列，左右按就能走到
        SelectBox(checked = checked, onToggle = onToggleSelect)
        Spacer(Modifier.width(10.dp))
        Text(if (playing) "🔊" else "🎵", fontSize = 15.sp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.name.ifBlank { "（无标题）" },
                color = colors.textPrimary,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                listOfNotNull(
                    song.artist.takeIf { it.isNotBlank() },
                    song.album.takeIf { it.isNotBlank() }
                ).joinToString(" · ").ifBlank { "未知歌手" },
                color = colors.textSecondary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            sourceLabel(song.source),
            color = colors.textTertiary,
            fontSize = 11.sp
        )
        if (song.vip) {
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .background(colors.warning.copy(alpha = 0.18f), RoundedCornerShape(5.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp)
            ) {
                Text("VIP", color = colors.warning, fontSize = 10.sp)
            }
        }
        Spacer(Modifier.width(10.dp))
        Text(
            formatDuration(song.durationSec),
            color = colors.textTertiary,
            fontSize = 12.sp
        )
        Spacer(Modifier.width(12.dp))
        TvButton(
            text = when {
                busy -> "测试中"
                playing -> "停止"
                else -> "试听"
            },
            onClick = { if (!busy) onPreview() }
        )
        Spacer(Modifier.width(6.dp))
        TvButton(
            text = "选用",
            primary = true,
            onClick = { if (!busy) onPick() }
        )
    }
}

/** 平台标识转中文，认不出来的原样显示 */
private fun sourceLabel(source: String): String = when (source.lowercase()) {
    "netease" -> "网易云"
    "qq" -> "QQ音乐"
    "kugou" -> "酷狗"
    "kuwo" -> "酷我"
    "migu" -> "咪咕"
    "bilibili" -> "B站"
    "soda" -> "汽水"
    "fivesing", "5sing" -> "5sing"
    "jamendo" -> "Jamendo"
    "joox" -> "JOOX"
    "qianqian" -> "千千"
    "apple" -> "Apple"
    "spotify" -> "Spotify"
    else -> source
}

private fun formatDuration(sec: Int): String {
    if (sec <= 0) return "--:--"
    val m = sec / 60
    val s = sec % 60
    return "%d:%02d".format(m, s)
}
