package com.shiguang.album.ui.settings

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shiguang.album.AppContainer
import com.shiguang.album.data.model.MediaEntry
import com.shiguang.album.data.model.MediaKind
import com.shiguang.album.data.source.MediaTypes
import com.shiguang.album.ui.components.TvButton
import com.shiguang.album.ui.components.TvSegmented
import com.shiguang.album.ui.theme.TvTheme
import kotlinx.coroutines.launch
import java.io.File

/* ============================================================================
 * 电视端「文件夹 / 文件」浏览器
 *
 * 这是脱离手机配置的关键一环：原来选文件夹只能在手机网页上点，
 * 现在遥控器可以直接进入目录、勾选文件夹、选封面、选音乐。
 *
 * 交互：
 * - 目录：OK 进入下一层
 * - 文件：OK 选中/取消（只对允许的类型开放）
 * - 「↑ 上一级」/ 返回键：回到上一层；已经在根目录时返回键关掉弹窗
 * - 「加入当前目录」：在 FOLDERS 模式下把当前路径直接加进已选
 * ========================================================================== */

enum class BrowserMode { FOLDERS, IMAGE, AUDIO, VIDEO }

/** 选中项：跨存储源用 sourceId 区分 */
data class PickedRef(val sourceId: String, val path: String, val name: String)

@Composable
fun TvSourceBrowserDialog(
    container: AppContainer,
    title: String,
    mode: BrowserMode,
    multiple: Boolean = false,
    initialSourceId: String? = null,
    initialPath: String = "",
    onDismiss: () -> Unit,
    onConfirm: (List<PickedRef>) -> Unit
) {
    val colors = TvTheme.colors
    val scope = rememberCoroutineScope()
    val sources = container.configStore.current.sources

    var sourceId by remember {
        mutableStateOf(initialSourceId ?: sources.firstOrNull()?.id.orEmpty())
    }
    // 导航历史：进目录压栈，「上一级」弹栈。
    // 比按路径字符串算父目录可靠 —— 本地源、WebDAV、SMB 的根规则各不相同，算错会跳出被纳管目录。
    val history = remember {
        mutableStateListOf<String>().apply {
            add("")
            if (initialPath.isNotBlank()) add(initialPath)
        }
    }
    val path = history.lastOrNull().orEmpty()
    var entries by remember { mutableStateOf<List<MediaEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val picked = remember { mutableStateListOf<PickedRef>() }

    val listFocus = remember { FocusRequester() }
    val backFocus = remember { FocusRequester() }

    fun reload() {
        if (sourceId.isBlank()) return
        loading = true
        error = null
        scope.launch {
            val result = runCatching { container.repository.list(sourceId, path) }
            entries = result.getOrElse {
                error = it.message ?: "读取目录失败"
                emptyList()
            }.sortedWith(compareByDescending<MediaEntry> { it.isDirectory }.thenBy { it.name.lowercase() })
            loading = false
            runCatching { listFocus.requestFocus() }
        }
    }

    fun canGoUp(): Boolean = history.size > 1

    fun goUp(): Boolean {
        if (!canGoUp()) return false
        history.removeAt(history.lastIndex)
        return true
    }

    LaunchedEffect(sourceId, path) { reload() }
    LaunchedEffect(sourceId) { if (sources.isEmpty()) runCatching { backFocus.requestFocus() } }

    fun toggle(ref: PickedRef) {
        val existing = picked.indexOfFirst { it.sourceId == ref.sourceId && it.path == ref.path }
        if (existing >= 0) {
            picked.removeAt(existing)
        } else {
            if (!multiple) picked.clear()
            picked.add(ref)
        }
    }

    fun accepts(entry: MediaEntry): Boolean = when (mode) {
        BrowserMode.FOLDERS -> entry.isDirectory
        // 选封面时把本机解码不了的格式（低版本 Android 上的 HEIC/AVIF）隐藏掉：
        // 选中了也是一块黑图，不如不让选
        BrowserMode.IMAGE -> entry.isDirectory ||
            (entry.kind == MediaKind.IMAGE && MediaTypes.isDecodableImage(entry.name))
        BrowserMode.AUDIO -> entry.isDirectory || entry.kind == MediaKind.AUDIO
        // 视频壁纸：只列视频，目录照常可进
        BrowserMode.VIDEO -> entry.isDirectory || entry.kind == MediaKind.VIDEO
    }

    val hint = when (mode) {
        BrowserMode.FOLDERS ->
            if (path.isBlank()) "先进入一个文件夹，按「确定」就纳入它"
            else "按「确定」直接纳入当前文件夹"
        BrowserMode.IMAGE ->
            if (MediaTypes.isDecodableImage("probe.heic")) "只显示图片，OK 选中"
            else "只显示图片，OK 选中（HEIC/AVIF 这台电视解不了，已隐藏）"
        BrowserMode.AUDIO -> "只显示音频文件"
        BrowserMode.VIDEO -> "只显示视频文件（建议选 1080p、30 秒以内的短片，占用更小）"
    }

    /** 当前所在目录（用于「确定」直接把当前文件夹纳入，不必先点「加入当前目录」） */
    fun currentRef(): PickedRef? {
        if (sourceId.isBlank()) return null
        val name = path.substringAfterLast('/').ifBlank {
            sources.firstOrNull { it.id == sourceId }?.name?.ifBlank { "根目录" } ?: "根目录"
        }
        return PickedRef(sourceId, path, name)
    }

    /**
     * 确定的语义：
     * - 已经勾了东西 → 只确认勾选的（勾选优先，避免莫名其妙多带一个父目录）
     * - 什么都没勾，且是选文件夹 → 把「当前所在的这个文件夹」直接纳入
     * 原来必须先点「把当前目录加入」，多一步且容易漏，用户以为确定没反应。
     */
    fun confirm() {
        if (picked.isNotEmpty()) {
            onConfirm(picked.toList())
        } else if (mode == BrowserMode.FOLDERS && path.isNotBlank()) {
            currentRef()?.let { onConfirm(listOf(it)) }
        }
    }

    // 根目录（path 为空）不参与「确定即纳入」：根层只有子目录，纳进去等于纳了个空清单
    val canConfirmByCurrent =
        mode == BrowserMode.FOLDERS && picked.isEmpty() && sourceId.isNotBlank() && path.isNotBlank()

    Dialog(
        // 遥控器返回键：先退一级目录，已经在根目录才关弹窗
        onDismissRequest = { if (!goUp()) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier.fillMaxSize().background(colors.scrim),
            contentAlignment = Alignment.Center
        ) {
            Column(
                Modifier
                    .widthIn(max = 1100.dp)
                    .fillMaxHeight(0.94f)
                    .background(colors.surface, RoundedCornerShape(16.dp))
                    .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(12.dp))
                    Text(hint, color = colors.textTertiary, fontSize = 12.sp)
                    Spacer(Modifier.weight(1f))
                    // 存储源切换（顶部导航：焦点移过去就切源）
                    if (sources.size > 1) {
                        TvSegmented(
                            options = sources.take(6).map { s ->
                                s.id to s.name.ifBlank { "未命名源" }
                            },
                            selected = sourceId,
                            selectOnFocus = true,
                            onSelect = { id ->
                                if (id != sourceId) {
                                    sourceId = id
                                    history.clear()
                                    history.add("")
                                    picked.clear()
                                }
                            }
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // 导航行：上一级 / 根目录 / 当前路径
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TvButton(
                        text = "↑ 上一级",
                        primary = canGoUp(),
                        onClick = { goUp() }
                    )
                    Spacer(Modifier.width(8.dp))
                    TvButton(
                        text = "根目录",
                        onClick = {
                            while (history.size > 1) history.removeAt(history.lastIndex)
                        }
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = "${sources.firstOrNull { it.id == sourceId }?.name ?: "（无存储源）"} ／ ${path.ifBlank { "/" }}",
                        color = colors.textSecondary,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(8.dp))

                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(colors.surfaceHigh.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                ) {
                    when {
                        sources.isEmpty() -> CenterHint("还没有存储源，请先添加一个")
                        loading -> CircularProgressIndicator(
                            color = colors.accent,
                            strokeWidth = 3.dp,
                            modifier = Modifier.align(Alignment.Center).width(36.dp)
                        )
                        error != null -> CenterHint("读取失败：$error")
                        entries.isEmpty() -> CenterHint("这个文件夹里是空的")
                        else -> LazyColumn(Modifier.fillMaxSize().padding(6.dp)) {
                            items(entries, key = { it.key }) { entry ->
                                BrowserRow(
                                    entry = entry,
                                    accepted = accepts(entry),
                                    selected = picked.any { it.path == entry.path && it.sourceId == entry.sourceId },
                                    focusRequester = if (entry == entries.first()) listFocus else null,
                                    onClick = {
                                        if (entry.isDirectory) {
                                            history.add(entry.path)
                                        } else if (accepts(entry)) {
                                            toggle(
                                                PickedRef(
                                                    sourceId = entry.sourceId,
                                                    path = entry.path,
                                                    name = entry.name
                                                )
                                            )
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                // 已选
                if (picked.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "已选 ${picked.size} 项：" + picked.joinToString("、") { it.name },
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.height(10.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (mode == BrowserMode.FOLDERS) {
                        TvButton(
                            text = if (picked.any { it.sourceId == sourceId && it.path == path }) "移出当前文件夹"
                            else "＋ 加入当前文件夹",
                            onClick = {
                                currentRef()?.let { toggle(it) }
                            }
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TvButton(
                        text = "关闭",
                        focusRequester = backFocus,
                        onClick = onDismiss
                    )
                    TvButton(
                        text = when {
                            picked.isNotEmpty() -> "确定（${picked.size}）"
                            canConfirmByCurrent -> "确定 · 纳入当前文件夹"
                            else -> "确定"
                        },
                        primary = picked.isNotEmpty() || canConfirmByCurrent,
                        onClick = { confirm() }
                    )
                }
            }
        }
    }
}

@Composable
private fun CenterHint(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = TvTheme.colors.textSecondary, fontSize = 14.sp)
    }
}

@Composable
private fun BrowserRow(
    entry: MediaEntry,
    accepted: Boolean,
    selected: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit
) {
    val colors = TvTheme.colors
    var focused by remember { mutableStateOf(false) }
    val bg by animateColorAsState(
        targetValue = when {
            selected -> colors.accent.copy(alpha = 0.16f)
            focused -> colors.surfaceHigh
            else -> colors.surface.copy(alpha = 0.5f)
        },
        animationSpec = tween(150),
        label = "rowBg"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .background(bg, RoundedCornerShape(8.dp))
            .border(
                2.dp,
                if (focused) colors.focusRing else colors.border.copy(alpha = 0.5f),
                RoundedCornerShape(8.dp)
            )
            .clickable(enabled = accepted || entry.isDirectory, onClick = onClick)
            .heightIn(min = 44.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = when {
                entry.isDirectory -> "📁"
                entry.kind == MediaKind.AUDIO -> "🎵"
                entry.kind == MediaKind.VIDEO -> "🎬"
                else -> "🖼"
            },
            fontSize = 15.sp
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = entry.name,
            color = if (accepted || entry.isDirectory) colors.textPrimary else colors.textTertiary,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Text("已选", color = colors.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        } else if (entry.isDirectory) {
            Text("›", color = colors.textTertiary, fontSize = 18.sp)
        } else if (!accepted) {
            Text("不支持", color = colors.textTertiary, fontSize = 12.sp)
        }
    }
}

/* ============================================================================
 * 电视本机目录浏览器（本地存储源专用）
 *
 * 本机目录不是通过存储源访问的（存储源本身就在这里定义），
 * 所以直接用 java.io.File 逐层列目录。
 * ========================================================================== */

@Composable
fun TvLocalDirDialog(
    title: String = "选择本机文件夹",
    initialPaths: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onConfirm: (List<String>) -> Unit
) {
    val colors = TvTheme.colors
    val picked = remember { mutableStateListOf<String>().apply { addAll(initialPaths) } }

    fun roots(): List<File> {
        val list = mutableListOf<File>()
        File("/storage/emulated/0").takeIf { it.isDirectory }?.let { list += it }
        File("/storage").listFiles()?.filter { it.isDirectory }?.forEach { list += it }
        File("/sdcard").takeIf { it.isDirectory }?.let { list += it }
        return list.distinctBy { it.absolutePath }
    }

    val rootFiles = remember { roots() }
    val start = remember {
        rootFiles.firstOrNull { it.absolutePath == "/storage/emulated/0" } ?: rootFiles.firstOrNull()
    }
    // 目录栈：和上面的文件浏览器一样，返回键 = 退一级
    val stack = remember { mutableStateListOf<File>().apply { if (start != null) add(start) } }
    val current = stack.lastOrNull()

    val dirs = remember(current) {
        current?.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }

    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(current) { runCatching { firstFocus.requestFocus() } }

    fun goUp(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    Dialog(
        onDismissRequest = { if (!goUp()) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(Modifier.fillMaxSize().background(colors.scrim), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .widthIn(max = 1040.dp)
                    .fillMaxHeight(0.94f)
                    .background(colors.surface, RoundedCornerShape(16.dp))
                    .border(1.dp, colors.border, RoundedCornerShape(16.dp))
                    .padding(16.dp)
            ) {
                Text(title, color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TvButton(text = "↑ 上一级", primary = stack.size > 1, onClick = { goUp() })
                    Spacer(Modifier.width(8.dp))
                    TvButton(
                        text = "存储根目录",
                        onClick = {
                            val root = rootFiles.firstOrNull { it.absolutePath == "/storage/emulated/0" }
                                ?: rootFiles.firstOrNull()
                            if (root != null) {
                                stack.clear()
                                stack.add(root)
                            }
                        }
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        current?.absolutePath ?: "（无法访问存储）",
                        color = colors.textSecondary,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(8.dp))

                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(colors.surfaceHigh.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                ) {
                    if (dirs.isEmpty()) {
                        CenterHint("这里没有子文件夹（也可能是系统限制，Android 11+ 部分目录不可读）")
                    } else {
                        LazyColumn(Modifier.fillMaxSize().padding(6.dp)) {
                            items(dirs, key = { it.absolutePath }) { dir ->
                                TvLocalDirRow(
                                    dir = dir,
                                    selected = picked.contains(dir.absolutePath),
                                    focusRequester = if (dir == dirs.first()) firstFocus else null,
                                    onEnter = { stack.add(dir) },
                                    onPick = {
                                        if (picked.contains(dir.absolutePath)) {
                                            picked.remove(dir.absolutePath)
                                        } else picked.add(dir.absolutePath)
                                    }
                                )
                            }
                        }
                    }
                }

                if (picked.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "已选 ${picked.size} 个：" + picked.joinToString("、") { it.substringAfterLast('/') },
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(Modifier.height(10.dp))

                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val cur = current?.absolutePath
                    TvButton(
                        text = if (cur != null && picked.contains(cur)) "移出当前文件夹" else "＋ 加入当前文件夹",
                        onClick = {
                            if (cur == null) return@TvButton
                            if (picked.contains(cur)) picked.remove(cur) else picked.add(cur)
                        }
                    )
                    Spacer(Modifier.weight(1f))
                    TvButton(text = "关闭", onClick = onDismiss)
                    TvButton(
                        text = when {
                            picked.isNotEmpty() -> "确定（${picked.size}）"
                            cur != null -> "确定 · 用当前文件夹"
                            else -> "确定"
                        },
                        primary = picked.isNotEmpty() || cur != null,
                        onClick = {
                            // 什么都没勾时，直接拿当前所在目录（不用先点「加入当前文件夹」）
                            if (picked.isNotEmpty()) onConfirm(picked.toList())
                            else if (cur != null) onConfirm(listOf(cur))
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun TvLocalDirRow(
    dir: File,
    selected: Boolean,
    focusRequester: FocusRequester?,
    onEnter: () -> Unit,
    onPick: () -> Unit
) {
    val colors = TvTheme.colors
    var focused by remember { mutableStateOf(false) }
    val bg by animateColorAsState(
        targetValue = when {
            selected -> colors.accent.copy(alpha = 0.16f)
            focused -> colors.surfaceHigh
            else -> colors.surface.copy(alpha = 0.5f)
        },
        animationSpec = tween(150),
        label = "dirBg"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .background(bg, RoundedCornerShape(8.dp))
            .border(
                2.dp,
                if (focused) colors.focusRing else colors.border.copy(alpha = 0.5f),
                RoundedCornerShape(8.dp)
            )
            .heightIn(min = 46.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("📁", fontSize = 15.sp)
        Spacer(Modifier.width(12.dp))
        Text(
            dir.name,
            color = colors.textPrimary,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Text("已选", color = colors.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(10.dp))
        }
        // 两个动作分开：进入 vs 选中，避免遥控器二义性
        TvButton(text = "进入", onClick = onEnter)
        Spacer(Modifier.width(6.dp))
        TvButton(
            text = if (selected) "取消选" else "选中",
            primary = !selected,
            onClick = onPick
        )
    }
}
