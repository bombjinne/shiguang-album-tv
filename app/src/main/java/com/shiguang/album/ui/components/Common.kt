package com.shiguang.album.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.launch
import com.shiguang.album.ui.theme.TvTheme

/* ============================================================================
 * 电视端基础组件（10-foot UI 设计系统）
 *
 * 统一约定：
 * - 焦点反馈 = 放大 1.04 + 2dp 强调色描边 + 阴影，180ms ease-out，不做位移（位移会让整屏抖）
 * - 圆角刻度：按钮/输入 12dp、卡片 18dp、弹窗 22dp
 * - 所有颜色来自 TvTheme.colors，不写死 Hex，保证明暗主题都能用
 * - 次级按钮的底色比页面略亮一档、描边常驻：遥控器在 3 米外也能看清"哪里能按"
 * ========================================================================== */

private const val FOCUS_MS = 180

/**
 * 焦点进入可视区。
 *
 * `Column + verticalScroll` 不会像 LazyColumn 那样自动把焦点行滚进视野 ——
 * 遥控器一路往下按，焦点会"走"到屏幕外，用户的感觉就是「点不到了」。
 * 设置页内容一多（管理页清单列表、外观页视频清单）这个毛病立刻暴露。
 *
 * 这里在每行拿到焦点时请求一次 bringIntoView；延后一帧是因为焦点板有 1.2% 的缩放，
 * 立刻滚动会按缩放前的尺寸算位置，长列表末尾容易差半行。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.focusBringIntoView(): Modifier {
    val requester = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    return this
        .bringIntoViewRequester(requester)
        .onFocusChanged {
            if (it.isFocused) {
                scope.launch {
                    withFrameNanos { }
                    runCatching { requester.bringIntoView() }
                }
            }
        }
}



/**
 * 电视端按钮。
 * @param primary 主操作（强调色填充）
 * @param danger 危险操作（红字描边）
 */
@Composable
fun TvButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    danger: Boolean = false,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    val colors = TvTheme.colors
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.045f else 1f,
        animationSpec = tween(FOCUS_MS, easing = LinearOutSlowInEasing),
        label = "btnScale"
    )
    val bg by animateColorAsState(
        targetValue = when {
            primary -> colors.accent
            focused -> colors.surfaceHigh
            else -> colors.surfaceHigh.copy(alpha = 0.55f)
        },
        animationSpec = tween(FOCUS_MS),
        label = "btnBg"
    )
    val fg = when {
        primary -> colors.onAccent
        danger -> colors.danger
        else -> colors.textPrimary
    }
    val shape = RoundedCornerShape(12.dp)

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                // 主按钮放大时给一圈强调色光晕，比纯阴影更"电视"
                shadowElevation = if (focused) 14f else 0f
                this.shape = shape
            }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusBringIntoView()
            .onFocusChanged { focused = it.isFocused }
            .background(bg, shape)
            .border(
                // 主按钮已经是橙底，白环压在橙底上只有 2.6:1，反而不如深色环清楚；
                // onAccent 本来就是"和强调色对比最高的那个颜色"（≥4.2:1），所以主按钮用它。
                width = if (focused) (if (primary) 3.dp else 2.dp) else 1.dp,
                color = when {
                    focused && primary -> colors.onAccent
                    // 中性按钮：白环（深色主题）/ 近黑环（浅色主题），和"橙底 = 当前值"区分开
                    focused -> colors.focusRing
                    danger -> colors.danger.copy(alpha = 0.42f)
                    primary -> Color.Transparent
                    else -> colors.border.copy(alpha = 0.8f)
                },
                shape = shape
            )
            .clickable(onClick = onClick)
            // 电视上按钮不必做很高：42dp 仍然好按，但顶栏和列表能省下竖向空间
            .defaultMinSize(minWidth = 84.dp, minHeight = 42.dp)
            .padding(PaddingValues(horizontal = 18.dp, vertical = 8.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = fg,
            fontSize = 15.sp,
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 圆角图标按钮（顶栏用，44dp 方形圆角块，里面放 emoji/短文字即可） */
@Composable
fun TvIconButton(
    glyph: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    focusRequester: FocusRequester? = null,
    onClick: () -> Unit
) {
    val colors = TvTheme.colors
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.08f else 1f,
        animationSpec = tween(FOCUS_MS, easing = LinearOutSlowInEasing),
        label = "iconScale"
    )
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .size(44.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; this.shape = shape }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .focusBringIntoView()
            .onFocusChanged { focused = it.isFocused }
            .background(
                if (primary) colors.accent else colors.surfaceHigh.copy(alpha = if (focused) 1f else 0.6f),
                shape
            )
            .border(
                2.dp,
                when {
                    focused -> if (primary) colors.onAccent else colors.focusRing
                    else -> Color.Transparent
                },
                shape
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(glyph, fontSize = 19.sp, color = if (primary) colors.onAccent else colors.textPrimary)
    }
}

/**
 * 分段选择器：用于主题、动效、播放模式这类有限选项。
 *
 * [selectOnFocus] 决定「焦点即切换」还是「焦点到位后按 OK 才切换」：
 * - 顶部导航类（管理页的 播放清单/存储源/音乐服务、文件夹浏览器的存储源）用 true，
 *   遥控器左右一挪就换页，少按一次 OK；
 * - 页面内容里的选项（自动播放/手动播放、播放顺序……）必须用 false，
 *   否则用户只是想往下走，路过就把设置改了 —— 这是内容型分段控件的通用约定。
 */
@Composable
fun <T> TvSegmented(
    options: List<Pair<T, String>>,
    selected: T,
    modifier: Modifier = Modifier,
    selectOnFocus: Boolean = false,
    onSelect: (T) -> Unit
) {
    val colors = TvTheme.colors
    Row(
        modifier = modifier
            .background(colors.surfaceHigh.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .border(1.dp, colors.border.copy(alpha = 0.7f), RoundedCornerShape(14.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            var focused by remember { mutableStateOf(false) }
            val bg by animateColorAsState(
                targetValue = when {
                    active -> colors.accent
                    focused -> colors.border
                    else -> Color.Transparent
                },
                animationSpec = tween(150),
                label = "segBg"
            )
            val shape = RoundedCornerShape(10.dp)
            Box(
                modifier = Modifier
                    .focusBringIntoView()
                    .onFocusChanged {
                        focused = it.isFocused
                        if (selectOnFocus && it.isFocused && !active) onSelect(value)
                    }
                    .background(bg, shape)
                    .border(
                        // 选中态也要能被"看见焦点"：橙底上再套一圈高对比环，
                        // 否则移到当前选中的那格时界面毫无变化，像遥控器失灵
                        width = if (focused) 2.dp else 0.dp,
                        color = if (focused) {
                            if (active) colors.onAccent else colors.focusRing
                        } else Color.Transparent,
                        shape = shape
                    )
                    .clickable { onSelect(value) }
                    .defaultMinSize(minHeight = 38.dp)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    fontSize = 14.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = when {
                        active -> colors.onAccent
                        focused -> colors.textPrimary
                        else -> colors.textSecondary
                    }
                )
            }
        }
    }
}

/** 状态圆点（在线/离线/认证失败） */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(color, CircleShape)
            .defaultMinSize(minWidth = 8.dp, minHeight = 8.dp)
    )
}

/** 14sp 辅助文字 */
@Composable
fun Caption(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    Text(
        text = text,
        color = color ?: TvTheme.colors.textSecondary,
        fontSize = 14.sp,
        modifier = modifier,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
    )
}

/**
 * 分组标题。
 *
 * V1.7 起改成 tvOS 的写法：**小号强调色标题**（13sp / 600），不再用「强调色竖条 + 19sp 标题」。
 * 原因很实际 —— 竖条 + 大标题会让每个区块都像一级标题，一页里出现五六个之后，
 * 层级反而全平了；小字压在分组卡片上方，主次一眼分明。
 *
 * 颜色用 accentText（按背景提亮/压暗过的品牌色）而不是纯灰：
 * 浅色主题的灰字在 3 米外几乎读不出来，用户明确要求"彩色、鲜艳一点"。
 */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    val colors = TvTheme.colors
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            color = colors.accentText,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.4.sp,
            modifier = Modifier.padding(start = 4.dp)
        )
        if (trailing != null) {
            Box(Modifier.padding(start = 12.dp)) { trailing() }
        }
    }
}

/** 细分割线 */
@Composable
fun TvDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(1.dp)
            .background(TvTheme.colors.border.copy(alpha = 0.6f))
    )
}

/* ------------------------------ 页面骨架 ------------------------------ */

/**
 * 二级页统一页头：左「返回」+ 标题 + 说明，右侧放本页专属操作。
 *
 * 之前每个页面各写一段 Row，标题字号、间距、返回按钮位置都不一样，
 * 来回切页时观感很乱。收敛到这里之后，所有二级页的头长得完全一致。
 */
@Composable
fun TvPageHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    backFocus: FocusRequester? = null,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {}
) {
    val colors = TvTheme.colors
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            TvButton(text = "← 返回", focusRequester = backFocus, onClick = onBack)
            Spacer(Modifier.width(18.dp))
        }
        Column {
            Text(
                title,
                color = colors.textPrimary,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    color = colors.textTertiary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** 一块"表面"：比页面亮一档的圆角卡片，承载一组设置项 */
@Composable
fun TvCard(
    modifier: Modifier = Modifier,
    padding: Dp = 18.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = TvTheme.colors
    Column(
        modifier = modifier
            .background(colors.surface.copy(alpha = 0.92f), RoundedCornerShape(18.dp))
            .border(1.dp, colors.border.copy(alpha = 0.9f), RoundedCornerShape(18.dp))
            .padding(padding),
        content = content
    )
}

/** 小胶囊：用于「自动/手动」「N 个文件」这类轻量信息 */
@Composable
fun TvChip(
    text: String,
    modifier: Modifier = Modifier,
    dot: Color? = null,
    background: Color? = null,
    foreground: Color? = null
) {
    val colors = TvTheme.colors
    Row(
        modifier = modifier
            .background(background ?: colors.surfaceHigh.copy(alpha = 0.72f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (dot != null) {
            StatusDot(dot, Modifier.size(7.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text,
            color = foreground ?: colors.textSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 顶栏品牌块：渐变圆角方块 + 一个字，代替纯文字标题，视觉上更"有产品感" */
@Composable
fun TvBrandMark(glyph: String, modifier: Modifier = Modifier, size: Dp = 38.dp) {
    val colors = TvTheme.colors
    Box(
        modifier = modifier
            .size(size)
            .background(
                Brush.linearGradient(
                    listOf(colors.accent, colors.accent.copy(alpha = 0.55f))
                ),
                RoundedCornerShape(size * 0.32f)
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            glyph,
            color = colors.onAccent,
            fontSize = (size.value * 0.46f).sp,
            fontWeight = FontWeight.Bold
        )
    }
}
