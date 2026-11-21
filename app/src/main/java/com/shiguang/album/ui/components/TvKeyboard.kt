package com.shiguang.album.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.shiguang.album.ui.theme.TvTheme
import kotlinx.coroutines.delay

/* ============================================================================
 * 电视端软键盘
 *
 * 电视没有物理键盘，而 WebDAV 地址、SMB 密码、清单名称都必须能输入，
 * 所以配置能力要脱离手机，就必须先有这把键盘。
 *
 * 版式（遥控器友好，10-foot）：
 *   左侧 = 主键区（英文 / 符号 / 中文三个页签切换）
 *   右侧 = 数字小键盘（选端口、时长这类场景一直用得上，不用来回切页签）
 *   底部 = 取消 / 完成
 *
 * 尺寸不写死：整块键区用 BoxWithConstraints 按可用宽高 ÷ 13 列 ÷ 4 行反算，
 * 所以 720p、1080p、超宽屏都不会出现"键盘被裁掉一半"的情况；
 * 字号跟着格子高度走（约 40%），保证"块变小、字变大"。
 * ========================================================================== */

private enum class KeyPanel(val label: String) {
    EN("英文"),
    SYMBOL("符号"),
    CN("中文")
}

/** 一个键位：单位宽度（1 = 一格，2 = 两格）+ 点击行为 */
private class KeySpec(
    val text: String,
    val span: Int = 1,
    val action: () -> Unit
)

private val GAP = 6.dp

/**
 * 单个键位。尺寸由父级按可用空间算出后传入，保证永远放得下。
 */
@Composable
private fun TvKey(
    text: String,
    width: Dp,
    height: Dp,
    fontSize: TextUnit,
    primary: Boolean = false,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    val colors = TvTheme.colors
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.05f else 1f,
        animationSpec = tween(130),
        label = "keyScale"
    )
    val bg by animateColorAsState(
        targetValue = when {
            primary -> colors.accent
            focused -> colors.accent.copy(alpha = 0.24f)
            else -> colors.surfaceHigh.copy(alpha = 0.85f)
        },
        animationSpec = tween(130),
        label = "keyBg"
    )

    Box(
        modifier = Modifier
            .size(width = width, height = height)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusBringIntoView()
            .onFocusChanged { focused = it.isFocused }
            .background(bg, RoundedCornerShape(10.dp))
            .border(
                2.dp,
                when {
                    focused -> if (primary) colors.onAccent else colors.focusRing
                    primary -> colors.accent.copy(alpha = 0.65f)
                    else -> colors.border.copy(alpha = 0.85f)
                },
                RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (primary) colors.onAccent else colors.textPrimary,
            fontSize = if (text.length > 2) (fontSize.value * 0.72f).sp else fontSize,
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1
        )
    }
}

/** 一行键位：按 span 排布，行内总宽度恒等于 10 格 */
@Composable
private fun KeyRow(
    keys: List<KeySpec>,
    unitW: Dp,
    rowH: Dp,
    fontSize: TextUnit,
    firstRequester: FocusRequester? = null,
    isPrimary: (KeySpec) -> Boolean = { false }
) {
    Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
        keys.forEachIndexed { index, key ->
            TvKey(
                text = key.text,
                width = unitW * key.span + GAP * (key.span - 1),
                height = rowH,
                fontSize = fontSize,
                primary = isPrimary(key),
                focusRequester = if (index == 0) firstRequester else null,
                onClick = key.action
            )
        }
    }
}

/**
 * 全屏软键盘对话框。
 *
 * @param numeric 只要数字与少量符号（端口、时长）
 * @param masked  密码输入，回显为圆点
 * @param replaceOnInput 带着旧值打开时，按第一个键直接覆盖旧值（改秒数、改毫秒数用）。
 *        改名字、改 URL 这类要追加的场景保持 false。
 */
@Composable
fun TvKeyboardDialog(
    title: String,
    initial: String = "",
    placeholder: String = "",
    numeric: Boolean = false,
    masked: Boolean = false,
    maxLength: Int = 160,
    replaceOnInput: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val colors = TvTheme.colors
    var text by remember { mutableStateOf(initial) }
    var caps by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(KeyPanel.EN) }

    /*
     * 「点一个数字就默认覆盖原来的」—— 电视上改"每张停留 5 秒"最常见的动作是整段重填，
     * 而不是在 5 后面接着打。所以数字类输入带着旧值打开时，第一个键视作"从头覆盖"，
     * 回显框加一圈橙边提示"现在整段是选中的"；只要按一次 ⌫ 或清空，就回到正常追加模式。
     */
    var replacePending by remember { mutableStateOf(replaceOnInput && initial.isNotEmpty()) }

    val firstKey = remember { FocusRequester() }
    val cnField = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    fun append(s: String) {
        if (replacePending) {
            replacePending = false
            if (s.length <= maxLength) text = s
            return
        }
        if (text.length + s.length <= maxLength) text += s
    }

    fun backspace() {
        replacePending = false
        if (text.isNotEmpty()) text = text.dropLast(1)
    }

    fun clearAll() {
        replacePending = false
        text = ""
    }

    // 中文页签：把焦点交给真实输入框并唤起系统输入法（电视自带的拼音/笔画键盘用遥控器可选字）
    LaunchedEffect(panel) {
        if (panel == KeyPanel.CN) {
            runCatching { cnField.requestFocus() }
            delay(120)
            runCatching { keyboard?.show() }
        } else {
            runCatching { keyboard?.hide() }
            runCatching { firstKey.requestFocus() }
        }
    }

    val letters = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    val symbolKeys = listOf(
        "@", "#", "%", "&", "*",
        "-", "_", "+", "=", "/",
        ":", ";", ",", ".", "?",
        "!", "'", "\"", "(", ")"
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.scrim)
                .onKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Back) {
                        onDismiss(); true
                    } else false
                },
            contentAlignment = Alignment.Center
        ) {
            BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 10.dp)) {
                Column(
                    Modifier
                        // 铺满屏幕宽度：电视上留出 960dp 上限会在右侧留一大块黑边，
                        // 键位只能挤在左边 2/3，又难看又难点
                        .fillMaxWidth()
                        .fillMaxHeight()
                        .background(colors.surface, RoundedCornerShape(18.dp))
                        .border(1.dp, colors.border, RoundedCornerShape(18.dp))
                        .padding(16.dp)
                ) {
                    /* ------------------------- 标题 + 页签 ------------------------- */
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            title,
                            color = colors.textPrimary,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (!numeric) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                KeyPanel.entries.forEach { p ->
                                    TvKey(
                                        text = p.label,
                                        width = 72.dp,
                                        height = 38.dp,
                                        fontSize = 15.sp,
                                        primary = panel == p,
                                        onClick = { panel = p }
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    /* --------------------------- 输入回显 --------------------------- */
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 46.dp)
                            .background(colors.surfaceHigh, RoundedCornerShape(10.dp))
                            .border(
                                if (replacePending) 2.dp else 1.dp,
                                if (replacePending) colors.accent else colors.border,
                                RoundedCornerShape(10.dp)
                            )
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        if (panel == KeyPanel.CN && !numeric) {
                            BasicTextField(
                                value = text,
                                onValueChange = { if (it.length <= maxLength) text = it },
                                singleLine = true,
                                textStyle = TextStyle(
                                    color = colors.textPrimary,
                                    fontSize = 20.sp
                                ),
                                cursorBrush = SolidColor(colors.accent),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Text,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(onDone = { onConfirm(text) }),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(cnField),
                                decorationBox = { inner ->
                                    if (text.isEmpty()) {
                                        Text(
                                            placeholder.ifEmpty { "用遥控器选字，或直接说/打字" },
                                            color = colors.textTertiary,
                                            fontSize = 16.sp
                                        )
                                    }
                                    inner()
                                }
                            )
                        } else {
                            val shown = when {
                                text.isEmpty() -> placeholder
                                masked -> "•".repeat(text.length.coerceAtMost(32))
                                else -> text
                            }
                            Text(
                                text = shown.ifEmpty { " " },
                                color = if (text.isEmpty()) colors.textTertiary else colors.textPrimary,
                                fontSize = 20.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Spacer(Modifier.height(10.dp))

                    /* ---------------------------- 键区 ---------------------------- */
                    // 13 格宽（10 格主键区 + 3 格数字小键盘）× 4 行，按可用空间反算格子尺寸
                    BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                        val totalUnits = if (numeric) 4 else 13
                        // 横向要扣掉：主键区 9 个格间距 + 小键盘 2 个格间距 + 两块之间的 10dp
                        val gapCount = if (numeric) 3 else 11
                        val panelGap = if (numeric) 0.dp else 10.dp
                        val cellW = (
                            (maxWidth - GAP * gapCount - panelGap) / totalUnits
                            ).coerceAtMost(if (numeric) 92.dp else 72.dp)
                        val cellH = (
                            (maxHeight - GAP * 3) / 4
                            ).coerceAtMost(if (numeric) 92.dp else cellW * 1.15f)
                        val fs = (cellH.value * 0.40f).coerceIn(14f, 34f).sp

                        if (numeric) {
                            NumericPad(
                                text = text,
                                cellW = cellW,
                                cellH = cellH,
                                fontSize = fs,
                                firstRequester = firstKey,
                                onKey = { append(it) },
                                onBackspace = { backspace() },
                                onClear = { clearAll() }
                            )
                        } else {
                            Row(
                                Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // 主键区
                                Column(
                                    Modifier.weight(1f).fillMaxHeight(),
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    when (panel) {
                                        KeyPanel.EN -> LetterPad(
                                            letters = letters,
                                            caps = caps,
                                            unitW = cellW,
                                            rowH = cellH,
                                            fontSize = fs,
                                            firstRequester = firstKey,
                                            onToggleCaps = { caps = !caps },
                                            onKey = { append(it) },
                                            onBackspace = { backspace() },
                                            onSpace = { append(" ") },
                                            onClear = { clearAll() }
                                        )

                                        KeyPanel.SYMBOL -> SymbolPad(
                                            symbols = symbolKeys,
                                            unitW = cellW,
                                            rowH = cellH,
                                            fontSize = fs,
                                            firstRequester = firstKey,
                                            onKey = { append(it) },
                                            onClear = { clearAll() }
                                        )

                                        KeyPanel.CN -> CnPad(
                                            unitW = cellW,
                                            rowH = cellH,
                                            fontSize = fs,
                                            onOpenIme = {
                                                runCatching { cnField.requestFocus() }
                                                keyboard?.show()
                                            },
                                            onSpace = { append(" ") },
                                            onBackspace = { backspace() }
                                        )
                                    }
                                }

                                // 右侧数字小键盘
                                NumPad(
                                    unitW = cellW,
                                    rowH = cellH,
                                    fontSize = fs,
                                    onKey = { append(it) },
                                    onBackspace = { backspace() }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))

                    /* ---------------------------- 动作 ---------------------------- */
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (replacePending) "直接按数字会覆盖原值 · 返回键 = 取消"
                            else "返回键 = 取消",
                            color = colors.textTertiary,
                            fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                        TvButton(text = "取消", onClick = onDismiss)
                        TvButton(
                            text = "完成",
                            primary = true,
                            onClick = { keyboard?.hide(); onConfirm(text) }
                        )
                    }
                }
            }
        }
    }
}

/* ------------------------------ 英文主键区 ------------------------------ */

@Composable
private fun LetterPad(
    letters: List<String>,
    caps: Boolean,
    unitW: Dp,
    rowH: Dp,
    fontSize: TextUnit,
    firstRequester: FocusRequester?,
    onToggleCaps: () -> Unit,
    onKey: (String) -> Unit,
    onBackspace: () -> Unit,
    onSpace: () -> Unit,
    onClear: () -> Unit
) {
    val rows = listOf(
        letters[0].map { KeySpec(if (caps) it.uppercase() else it.toString()) { onKey(if (caps) it.uppercase() else it.toString()) } },
        letters[1].map { KeySpec(if (caps) it.uppercase() else it.toString()) { onKey(if (caps) it.uppercase() else it.toString()) } } +
            KeySpec("大写", action = onToggleCaps),
        letters[2].map { KeySpec(if (caps) it.uppercase() else it.toString()) { onKey(if (caps) it.uppercase() else it.toString()) } } +
            KeySpec("⌫", action = onBackspace) +
            KeySpec("空格", span = 2, action = onSpace),
        listOf(
            KeySpec(".", action = { onKey(".") }),
            KeySpec("/", action = { onKey("/") }),
            KeySpec(":", action = { onKey(":") }),
            KeySpec("-", action = { onKey("-") }),
            KeySpec("_", action = { onKey("_") }),
            KeySpec("@", action = { onKey("@") }),
            KeySpec("#", action = { onKey("#") }),
            KeySpec("%", action = { onKey("%") }),
            KeySpec("清空", span = 2, action = onClear)
        )
    )

    Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
        rows.forEachIndexed { index, row ->
            KeyRow(
                keys = row,
                unitW = unitW,
                rowH = rowH,
                fontSize = fontSize,
                firstRequester = if (index == 0) firstRequester else null,
                isPrimary = { it.text == "大写" && caps }
            )
        }
    }
}

/* ------------------------------ 符号主键区 ------------------------------ */

@Composable
private fun SymbolPad(
    symbols: List<String>,
    unitW: Dp,
    rowH: Dp,
    fontSize: TextUnit,
    firstRequester: FocusRequester?,
    onKey: (String) -> Unit,
    onClear: () -> Unit
) {
    // 5 列 × 4 行，每格占 2 个单位（正好和英文键盘同宽 10 单位）
    val rows: List<List<KeySpec>> = run {
        val chunks = symbols.take(19).chunked(5).toMutableList()
        val last = chunks.removeAt(chunks.lastIndex)
            .map { s -> KeySpec(s, span = 2) { onKey(s) } }
            .toMutableList()
        last.add(KeySpec("清空", span = 2, action = onClear))
        chunks.map { line -> line.map { s -> KeySpec(s, span = 2) { onKey(s) } } } + listOf(last)
    }

    Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
        rows.forEachIndexed { index, row ->
            KeyRow(
                keys = row,
                unitW = unitW,
                rowH = rowH,
                fontSize = fontSize,
                firstRequester = if (index == 0) firstRequester else null
            )
        }
    }
}

/* ------------------------------- 中文页签 ------------------------------- */

@Composable
private fun CnPad(
    unitW: Dp,
    rowH: Dp,
    fontSize: TextUnit,
    onOpenIme: () -> Unit,
    onSpace: () -> Unit,
    onBackspace: () -> Unit
) {
    val colors = TvTheme.colors
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("中文用电视自带输入法", color = colors.textPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Text(
            "点下面的按钮唤起系统输入法，用遥控器方向键选拼音和候选字。\n电视没装中文输入法时，可以扫右上角二维码在手机上改名字。",
            color = colors.textTertiary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            lineHeight = 19.sp
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
            KeyRow(
                keys = listOf(
                    KeySpec("打开输入法", span = 3, action = onOpenIme),
                    KeySpec("空格", span = 2, action = onSpace),
                    KeySpec("⌫", span = 1, action = onBackspace)
                ),
                unitW = unitW,
                rowH = rowH,
                fontSize = fontSize
            )
        }
    }
}

/* ---------------------------- 数字小键盘 ---------------------------- */

@Composable
private fun NumPad(
    unitW: Dp,
    rowH: Dp,
    fontSize: TextUnit,
    onKey: (String) -> Unit,
    onBackspace: () -> Unit
) {
    val rows = listOf(
        listOf("7", "8", "9"),
        listOf("4", "5", "6"),
        listOf("1", "2", "3"),
        listOf("0", ".", "⌫")
    )
    Column(Modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(GAP)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
                row.forEach { k ->
                    TvKey(
                        text = k,
                        width = unitW,
                        height = rowH,
                        fontSize = fontSize,
                        onClick = { if (k == "⌫") onBackspace() else onKey(k) }
                    )
                }
            }
        }
    }
}

/** numeric 模式：只有数字与少量符号，4 列铺满整块区域 */
@Composable
private fun NumericPad(
    text: String,
    cellW: Dp,
    cellH: Dp,
    fontSize: TextUnit,
    firstRequester: FocusRequester?,
    onKey: (String) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit
) {
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0", ".", ":", "/", "⌫", "清空")
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        keys.chunked(4).forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(GAP)) {
                row.forEachIndexed { i, k ->
                    TvKey(
                        text = k,
                        width = cellW,
                        height = cellH,
                        fontSize = fontSize,
                        focusRequester = if (rowIndex == 0 && i == 0) firstRequester else null,
                        onClick = {
                            when (k) {
                                "⌫" -> onBackspace()
                                "清空" -> onClear()
                                else -> onKey(k)
                            }
                        }
                    )
                }
            }
            Spacer(Modifier.height(GAP))
        }
    }
}

/**
 * 可点击的输入行：显示当前值，OK 键弹出软键盘。
 * 电视端所有文本输入都走这一行，保持交互一致。
 */
@Composable
fun TvInputRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    placeholder: String = "未设置",
    masked: Boolean = false,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    val colors = TvTheme.colors
    var focused by remember { mutableStateOf(false) }
    val bg by animateColorAsState(
        targetValue = if (focused) colors.surfaceHigh else colors.surfaceHigh.copy(alpha = 0.55f),
        animationSpec = tween(160),
        label = "inputBg"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusBringIntoView()
            .onFocusChanged { focused = it.isFocused }
            .background(bg, RoundedCornerShape(10.dp))
            .border(
                2.dp,
                if (focused) colors.focusRing else colors.border,
                RoundedCornerShape(10.dp)
            )
            /*
             * 这里必须显式 focusable + 自己接 OK 键。
             * 只挂 clickable 时，遥控器从上面一路按下来会「跳过」这一行 ——
             * 「每张停留」常年选不中、改不了就是这个原因；TvSwitchRow 早就踩过同一个坑。
             * 为了不重复触发，这里就不用 clickable，触摸改走 detectTapGestures。
             */
            .focusable()
            .onKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown &&
                    (e.key == Key.DirectionCenter || e.key == Key.Enter ||
                        e.key == Key.NumPadEnter || e.key == Key.Spacebar)
                ) {
                    onClick(); true
                } else false
            }
            .pointerInput(Unit) { detectTapGestures { onClick() } }
            .defaultMinSize(minHeight = 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = colors.textSecondary,
            fontSize = 15.sp,
            modifier = Modifier.width(150.dp)
        )
        Text(
            text = when {
                value.isEmpty() -> placeholder
                masked -> "•".repeat(value.length.coerceAtMost(20))
                else -> value
            },
            color = if (value.isEmpty()) colors.textTertiary else colors.textPrimary,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start,
            modifier = Modifier.weight(1f)
        )
        Text("OK 编辑", color = colors.textTertiary, fontSize = 13.sp)
    }
}

/**
 * 开关行：OK 键切换（「包含子文件夹」「循环播放」这类二值项）。
 *
 * V1.7 起和设置页的行统一：**焦点是一块近白板**（tvOS 的 Plate），
 * 板上的标题/说明/开关全部反相成深色。之前是"深底 + 一圈白描边"，
 * 在一堆深色卡片里其实很难判断遥控器到底停在哪一行。
 */
@Composable
fun TvSwitchRow(
    label: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    hint: String? = null,
    focusRequester: FocusRequester? = null,
    onChange: (Boolean) -> Unit
) {
    val colors = TvTheme.colors
    var focused by remember { mutableStateOf(false) }
    val plate by animateColorAsState(
        targetValue = if (focused) colors.focusPlate else Color.Transparent,
        animationSpec = tween(140),
        label = "switchPlate"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusBringIntoView()
            .onFocusChanged { focused = it.isFocused }
            // 显式 focusable：只依赖 clickable 自带的可聚焦性时，
            // 遥控器从上面一路按下来会「跳过」这一行，导致开关根本选不中
            .focusable()
            .onKeyEvent { e ->
                // KeyUp 触发：长按 OK 不会连续翻转
                if (e.type == KeyEventType.KeyUp &&
                    (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter)
                ) {
                    onChange(!checked)
                    true
                } else false
            }
            .pointerInput(Unit) { detectTapGestures { onChange(!checked) } }
            .background(plate, RoundedCornerShape(10.dp))
            .defaultMinSize(minHeight = 52.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                color = if (focused) colors.onFocusPlate else colors.textPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
            if (hint != null) {
                Text(
                    hint,
                    color = if (focused) colors.onFocusPlateSecondary else colors.textTertiary,
                    fontSize = 13.sp
                )
            }
        }
        TvSwitch(checked = checked, onPlate = focused)
    }
}
