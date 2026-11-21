package com.shiguang.album.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
import com.shiguang.album.ui.theme.TvTheme
import com.shiguang.album.ui.theme.cardFill
import com.shiguang.album.ui.theme.cardStroke

/* ============================================================================
 * 设置页组件（参照 aerial-tv / tvOS 设置面板）
 *
 * 三条从 aerial-tv 抄过来的规则：
 *
 * 1. **焦点是一块板，不是一根线**。tvOS 里遥控器停在哪一行，那一行整块变成近白色板、
 *    文字反成深色（Palette.Focus #F5F5F7 / OnFocus #1D1D1F）。这种做法在 3 米外最清楚，
 *    也天然躲开了"深色主题下焦点和选中分不清"的老问题。
 * 2. **分组卡片包住一组设置**，卡片本身是半透明白（Fill #2E787880）压在半透明面板上，
 *    行与行之间只有 1 根 hairline（#1AFFFFFF），不再给每个条目描边 —— 描边一多，
 *    界面立刻变成"一堆框"，电视上看着很吵。
 * 3. **字号阶梯照搬 tvOS**：大标题 26sp、分组标题 13sp、行标题 16sp、行值 15sp、说明 13sp。
 *    原来一页里 17/19/22 混着用，层级反而糊。
 *
 * 圆角刻度：分组卡片 14dp、行内焦点板 10dp、图标块 9dp。
 * 动效：只有颜色 140ms + 缩放 1.015，跟 tvOS 一样"克制"，不做位移（整屏会抖）。
 * ========================================================================== */

private val PlateEase = CubicBezierEasing(0.23f, 1f, 0.32f, 1f)
private const val PLATE_MS = 140

/** 一页设置的骨架：大标题 + 可选说明 + 右侧操作 + 内容区（调用方自己套 verticalScroll） */
@Composable
fun SettingsPageHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    backFocus: FocusRequester? = null,
    modifier: Modifier = Modifier,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {}
) {
    val colors = TvTheme.colors
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            TvButton(text = "← 返回", focusRequester = backFocus, onClick = onBack)
            Spacer(Modifier.width(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = colors.textPrimary,
                fontSize = 27.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Spacer(Modifier.height(3.dp))
                Text(
                    subtitle,
                    // 副标题用「可读版强调色」：浅色主题下纯灰 13sp 在 3 米外读不出来，
                    // 换成压暗过的品牌色 —— 既是彩色、又保证对比度过 4.5:1
                    color = colors.accentText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/**
 * 分组标题：tvOS 的灰色小字，压在卡片上方。
 *
 * 颜色用 [com.shiguang.album.ui.theme.TvColors.accentText] 而不是灰 —— 浅色主题下
 * 13sp 的灰字压在"白卡片 + 透光壁纸"上只有 3:1，电视 3 米外基本读不出来。
 * 换成压暗/提亮过的品牌色后既是彩色，对比度也过 4.5:1。
 */
@Composable
fun SettingsGroupTitle(text: String, modifier: Modifier = Modifier, note: String? = null) {
    val colors = TvTheme.colors
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            color = colors.accentText,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp,
            modifier = Modifier.padding(start = 4.dp)
        )
        if (note != null) {
            Spacer(Modifier.width(8.dp))
            Text(note, color = colors.textSecondary, fontSize = 12.sp, maxLines = 1)
        }
    }
}

/**
 * 分组卡片。
 *
 * 行与行之间的 hairline 由 [SettingsRow] / [SettingsToggle] 的 [divider] 参数控制，
 * 卡片不自己猜 —— 最后一个条目记得传 false，否则卡片底部会多出一道内线。
 */
@Composable
fun SettingsGroup(
    modifier: Modifier = Modifier,
    padding: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = TvTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            // 半透明白压深底：比实心 surface 更像"毛玻璃上的浮层"，也是 tvOS Fill 的做法
            .background(colors.cardFill, RoundedCornerShape(14.dp))
            .border(1.dp, colors.cardStroke, RoundedCornerShape(14.dp))
            .clip(RoundedCornerShape(14.dp))
            .padding(padding),
        content = content
    )
}

/** 行间 hairline（左右缩进 16dp，和 tvOS 一样不顶到卡片边缘） */
@Composable
fun SettingsRowDivider(modifier: Modifier = Modifier) {
    val colors = TvTheme.colors
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .padding(horizontal = 16.dp)
            .background(
                if (colors.isDark) Color.White.copy(alpha = 0.08f)
                else Color.Black.copy(alpha = 0.07f)
            )
    )
}

/**
 * 基础行：左侧标题（可带说明），右侧值 / 尾部内容。
 *
 * 焦点处理刻意用 `focusable()` + 显式 KeyEvent，而不是只挂 `clickable`：
 * 电视上 `clickable` 走焦点搜索时经常被跳过（TvInputRow 踩过），
 * 表现就是"遥控器按到底也选不中这一行"。
 */
@Composable
private fun PlateRow(
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable (focused: Boolean, colors: com.shiguang.album.ui.theme.TvColors) -> Unit
) {
    val colors = TvTheme.colors
    var focused by remember { mutableStateOf(false) }
    val plate by animateColorAsState(
        targetValue = if (focused) colors.focusPlate else Color.Transparent,
        animationSpec = tween(PLATE_MS, easing = PlateEase),
        label = "plate"
    )
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.012f else 1f,
        animationSpec = tween(PLATE_MS, easing = PlateEase),
        label = "plateScale"
    )
    val shape = RoundedCornerShape(10.dp)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusBringIntoView()
            .onFocusChanged { focused = it.isFocused && enabled }
            .background(plate, shape)
            .then(
                if (onClick != null && enabled) {
                    Modifier
                        // onKeyEvent 必须在 focusable 之前：先拦 OK 键，再交给焦点系统
                        .onKeyEvent { e ->
                            val activate = e.type == KeyEventType.KeyUp &&
                                (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter)
                            if (activate) { onClick(); true } else false
                        }
                        .focusable(interactionSource = remember { MutableInteractionSource() })
                } else Modifier
            )
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        content(focused, colors)
    }
}

/** 说明文字：跟着焦点板反色，白板上要变深灰 */
@Composable
private fun rowText(text: String, focused: Boolean, primary: Boolean = true, maxLines: Int = 1) {
    val colors = TvTheme.colors
    Text(
        text = text,
        color = when {
            focused -> if (primary) colors.onFocusPlate else colors.onFocusPlateSecondary
            primary -> colors.textPrimary
            else -> colors.textTertiary
        },
        fontSize = if (primary) 16.sp else 13.sp,
        fontWeight = if (primary) FontWeight.Medium else FontWeight.Normal,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}

/** 普通设置行：点一下执行动作，右侧显示当前值（可选箭头） */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    value: String? = null,
    desc: String? = null,
    chevron: Boolean = false,
    divider: Boolean = true,
    focusRequester: FocusRequester? = null,
    onClick: (() -> Unit)? = null
) {
    Column(modifier) {
        PlateRow(focusRequester = focusRequester, onClick = onClick) { focused, colors ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    rowText(title, focused)
                    if (desc != null) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            desc,
                            color = if (focused) colors.onFocusPlateSecondary else colors.textTertiary,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (value != null) {
                    Spacer(Modifier.width(16.dp))
                    Text(
                        value,
                        color = if (focused) colors.onFocusPlateSecondary else colors.textSecondary,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (chevron) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "›",
                        color = if (focused) colors.onFocusPlateSecondary else colors.textTertiary,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
        if (divider) SettingsRowDivider()
    }
}

/**
 * 开关行：整行可点（遥控器 OK 直接切）。
 *
 * 开关本体在焦点板上要反相 —— 白板还是"白钮 + 浅轨道"就等于看不见开关在哪。
 */
@Composable
fun SettingsToggle(
    title: String,
    desc: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    divider: Boolean = true,
    focusRequester: FocusRequester? = null,
    onToggle: () -> Unit
) {
    Column(modifier) {
        PlateRow(focusRequester = focusRequester, onClick = onToggle) { focused, colors ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    rowText(title, focused)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        desc,
                        color = if (focused) colors.onFocusPlateSecondary else colors.textTertiary,
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(16.dp))
                TvSwitch(checked = checked, onPlate = focused)
            }
        }
        if (divider) SettingsRowDivider()
    }
}

/** tvOS 风格开关：52×30，钮 24dp */
@Composable
fun TvSwitch(checked: Boolean, onPlate: Boolean = false, modifier: Modifier = Modifier) {
    val colors = TvTheme.colors
    val track by animateColorAsState(
        targetValue = when {
            checked -> colors.accent
            // 未选中的轨道必须能看清：焦点白板上用 20% 黑，普通深底用半透明白
            onPlate -> Color.Black.copy(alpha = 0.18f)
            else -> if (colors.isDark) Color.White.copy(alpha = 0.26f) else Color.Black.copy(alpha = 0.16f)
        },
        animationSpec = tween(160),
        label = "track"
    )
    val thumb by animateColorAsState(
        targetValue = when {
            // 橙底上的钮沿用 onAccent（深色），白板上未选中用纯白
            checked -> colors.onAccent
            onPlate -> Color.White
            else -> colors.textPrimary
        },
        animationSpec = tween(160),
        label = "thumb"
    )
    Box(
        modifier
            .size(width = 52.dp, height = 30.dp)
            .background(track, RoundedCornerShape(15.dp))
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(Modifier.size(24.dp).background(thumb, CircleShape))
    }
}

/**
 * 一行里放分段选择：标题在上，控件在下一行靠左。
 *
 * 页面内的分段控件一律 `selectOnFocus = false`（用户明确要求：内容型 tab 不该焦点即切换，
 * 否则"想往下走"路过就改了设置）。
 */
@Composable
fun <T> SettingsSegmentBlock(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    modifier: Modifier = Modifier,
    note: String? = null,
    onSelect: (T) -> Unit
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(title, color = TvTheme.colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        if (note != null) {
            Spacer(Modifier.height(2.dp))
            Text(note, color = TvTheme.colors.textTertiary, fontSize = 13.sp, maxLines = 2)
        }
        Spacer(Modifier.height(10.dp))
        TvSegmented(
            options = options,
            selected = selected,
            onSelect = onSelect
        )
    }
}

/** 一段说明文字（卡片外面用，比如"改完立刻生效"） */
@Composable
fun SettingsFootNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        // 脚注同分组标题：彩色可读版，不再用压深的灰
        color = TvTheme.colors.accentText,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        modifier = modifier.padding(start = 4.dp, top = 6.dp)
    )
}

/** 行内小徽标（"内置"/"默认"/"推荐"） */
@Composable
fun SettingsBadge(text: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    val colors = TvTheme.colors
    Box(
        modifier
            .background(
                if (accent) colors.accent.copy(alpha = 0.16f) else colors.surfaceHigh,
                RoundedCornerShape(6.dp)
            )
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text,
            // 直接拿 accent 当 11sp 文字色在浅色主题上对比度不够，用可读版
            color = if (accent) colors.accentText else colors.textSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/** 列表为空时的占位（跟分组卡片同一套圆角，不再单独描边） */
@Composable
fun SettingsEmpty(text: String, modifier: Modifier = Modifier) {
    val colors = TvTheme.colors
    Box(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 96.dp)
            .background(
                if (colors.isDark) Color.White.copy(alpha = 0.04f) else Color.Black.copy(alpha = 0.03f),
                RoundedCornerShape(14.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = colors.textSecondary, fontSize = 15.sp)
    }
}

/** 数字徽章：分类栏上的计数 */
@Composable
fun SettingsCount(count: Int, modifier: Modifier = Modifier) {
    val colors = TvTheme.colors
    Box(
        modifier
            .background(colors.surfaceHigh, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text("$count", color = colors.textSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** 横向排布一组按钮，间距统一 10dp（避免每处手写 Spacer） */
@Composable
fun SettingsActionBar(modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}
