package com.shiguang.album.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.shiguang.album.data.model.ThemeMode

/* ============================================================================
 * 设计令牌（Design Tokens）
 *
 * 电视是「10-foot UI」：观者在 3 米外，所有对比度、字号、间距都要比手机大一档。
 * 主题分深/浅两套，强调色可替换（默认小米橙），全部通过 CompositionLocal 下发，
 * 切换主题时整棵树自动重组，不需要重启。
 * ========================================================================== */

/** 一套完整的色彩令牌。任何界面只能用这里定义的颜色，禁止写死 Hex */
data class TvColors(
    val isDark: Boolean,
    /** 页面底色（深色主题的最底层） */
    val background: Color,
    /** 比背景略亮一档，用于分区、列表隔行 */
    val backgroundAlt: Color,
    /** 卡片、弹窗底色 */
    val surface: Color,
    /** 悬浮层、输入框、次级按钮 */
    val surfaceHigh: Color,
    /** 分隔线、卡片描边 */
    val border: Color,
    /** 强调色（焦点、主按钮、进度） */
    val accent: Color,
    /** 强调色上的文字色（橙底要黑字，保证对比度） */
    val onAccent: Color,
    /**
     * 「可读版强调色」：拿强调色去写文字时的安全用量。
     *
     * 直接拿 [accent] 当文字色在浅色主题下会翻车 —— 橙 #FF6900 压在浅灰卡片上
     * 对比度只有 2.6:1，3 米外就是一团糊。这里按背景深浅把强调色提亮/压暗一档：
     * 深色主题往白里提 18%，浅色主题往黑里压 30%，颜色还是品牌色，但对比度都过 4.5:1。
     * 分组标题、页头副标题、脚注这些"小字但重要"的位置一律用它。
     */
    val accentText: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val danger: Color,
    val success: Color,
    val warning: Color,
    /**
     * 焦点环颜色。
     *
     * 刻意和 [accent] 分开：「选中」用强调色填充表达，「焦点在哪」用一圈高对比中性色表达。
     * 之前两者都用强调色，深色主题下橙描边贴着橙按钮、或者橙描边压在深灰卡片上，
     * 3 米外几乎看不出遥控器停在哪一行。深色下用近白、浅色下用近黑，对比度永远够。
     */
    val focusRing: Color,
    /**
     * 设置页「焦点板」底色。
     *
     * tvOS / Apple TV 的标志性做法：遥控器停在哪一行，那一行就整块变成近白色板，
     * 文字反成深色。它比"描一圈边"强得多 —— 3 米外一眼就知道焦点在哪，
     * 而且白板深字的对比度天然在 15:1 以上，不会出现深色主题下看不清的问题。
     * 浅色主题下反相成近黑板 + 白字，逻辑保持一致。
     */
    val focusPlate: Color,
    /** 焦点板上的主文字色 */
    val onFocusPlate: Color,
    /** 焦点板上的次要文字色（右侧的值、说明） */
    val onFocusPlateSecondary: Color,
    /** 弹窗遮罩 */
    val scrim: Color
)

/** 深色：电视主力场景，暗房间里不刺眼 */
private val DarkColors = TvColors(
    isDark = true,
    // 中性偏冷的石墨底色：比纯黑有层次，暗房间里也不会发灰发脏
    background = Color(0xFF0B0C10),
    backgroundAlt = Color(0xFF11131A),
    surface = Color(0xFF171A22),
    surfaceHigh = Color(0xFF1F232F),
    // 描边带一点冷色，和暖橙强调色形成冷暖对比，界面更有"设计感"
    border = Color(0xFF2C3242),
    accent = Color(0xFFFF6900),
    onAccent = Color(0xFF12141A),
    accentText = Color(0xFFFF8A33),
    // 不用纯白 #FFF，纯白在暗房间里会「发光刺眼」，用 96% 白
    textPrimary = Color(0xFFF5F7FA),
    textSecondary = Color(0xFFAEB5C4),
    textTertiary = Color(0xFF828B9C),
    danger = Color(0xFFFF5A5A),
    success = Color(0xFF3DD68C),
    warning = Color(0xFFFFC53D),
    // 深色下焦点环用近白：和橙填充的「选中」拉开区别，也保证 3 米外一眼能看到焦点
    focusRing = Color(0xFFF2F5FA),
    // 焦点板照 tvOS 的 Focus = #F5F5F7 / OnFocus = #1D1D1F，略压一点亮度避免暗房间刺眼
    focusPlate = Color(0xFFEDF0F5),
    onFocusPlate = Color(0xFF15171D),
    onFocusPlateSecondary = Color(0xFF5A6070),
    scrim = Color(0xE6000000)
)

/** 浅色：白天客厅、或用户偏好亮色 */
private val LightColors = TvColors(
    isDark = false,
    background = Color(0xFFF2F4F9),
    backgroundAlt = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFEBEEF6),
    border = Color(0xFFDFE3EE),
    accent = Color(0xFFFF6900),
    onAccent = Color(0xFF12141A),
    accentText = Color(0xFFB34B00),
    textPrimary = Color(0xFF16161C),
    // 浅色主题原来 Secondary #5B5B68 / Tertiary #8E8E9C 压在"白卡片 + 透光壁纸"上，
    // 13sp 的小字对比度只有 3:1 出头，电视 3 米外基本读不出来。整体压深两档。
    textSecondary = Color(0xFF3F3F4A),
    textTertiary = Color(0xFF5F5F6B),
    danger = Color(0xFFE5484D),
    success = Color(0xFF12925A),
    warning = Color(0xFFB26A00),
    // 浅色下焦点环用近黑：白底上白环等于没画
    focusRing = Color(0xFF171922),
    // 浅色主题里焦点板反相成深色板 + 白字，观感与深色主题一致（"焦点永远是最抢眼的那块"）
    focusPlate = Color(0xFF1C1E24),
    onFocusPlate = Color(0xFFF7F9FC),
    onFocusPlateSecondary = Color(0xFFA8AEBE),
    scrim = Color(0x99000000)
)

/**
 * 压在某个底色上时，文字该用黑还是白。
 *
 * 0.179 是「黑字对比度 = 白字对比度」的那个临界亮度（WCAG 相对亮度）：
 * 底比它亮 → 黑字更清楚；比它暗 → 白字更清楚。这样无论用户把强调色换成
 * 橙、蓝、绿还是紫，选中项上的文字永远有 ≥4.5:1，不会出现「黑字压深底」。
 */
fun contrastOn(background: Color): Color =
    if (background.luminance() > 0.179f) Color(0xFF101218) else Color.White

/**
 * 把 6 位 RGB（比如手机页存下来的 `0xF43F5E`）补成不透明 ARGB。
 *
 * `androidx.compose.ui.graphics.Color(Long)` 把它当成 **ARGB** 解释，
 * 高 8 位是 0 就成了**全透明**。于是「强调色」画出来什么都没有、选中块看不见、
 * 而按这个底色算出来的文字色还是白的 —— 白字压白底，整块消失。
 * 这就是「选中项看不到字」的真正元凶：颜色本身是透明的，不是对比度不够。
 */
fun Long.toOpaqueArgb(): Long =
    if ((this ushr 24) and 0xFFL == 0L) this or 0xFF000000L else this

/**
 * 把强调色调成「当文字用也读得清」的版本。
 * @param dark 深色主题往白里提，浅色主题往黑里压
 */
fun accentAsText(accent: Color, dark: Boolean): Color =
    if (dark) lerp(accent, Color.White, 0.18f) else lerp(accent, Color.Black, 0.30f)

/** 用强调色覆盖一套配色（用于"主题色替换"） */
fun TvColors.withAccent(accent: Color, dark: Boolean = isDark): TvColors = copy(
    accent = accent,
    /*
     * 强调色上的文字色：一律走亮度判定，不再分「默认橙 / 自定义色」两条路。
     *
     * 之前默认橙走的是主题里写死的 onAccent —— 浅色主题写的是纯白 #FFFFFF，
     * 于是「深色/浅色/跟随系统」里选中那格是 **白字压橙底**，对比度只有 3:1，
     * 电视上一看就是"选中的字不见了"。反过来深色主题写的是近黑 #1A0D00，
     * 用户换成蓝紫这类偏暗的强调色后就成了黑字压深底，同样看不见。
     * 现在所有强调色都过 [contrastOn]，两个方向都不会再翻车。
     */
    onAccent = contrastOn(accent),
    accentText = accentAsText(accent, dark)
)

/**
 * 分组卡片的填充与描边。
 *
 * 照 tvOS 的 Fill（#2E787880，半透明白压深底）做的：卡片不是"另一种灰"，
 * 而是"底上盖一层极淡的白"，所以壁纸透过来的光会让卡片跟着环境变，
 * 比写死 #171A22 更有质感。浅色主题反过来压一层淡黑。
 */
val TvColors.cardFill: Color
    get() = if (isDark) Color.White.copy(alpha = 0.062f) else Color.Black.copy(alpha = 0.035f)

val TvColors.cardStroke: Color
    get() = if (isDark) Color.White.copy(alpha = 0.072f) else Color.Black.copy(alpha = 0.06f)

/* ---------------------------- 主题色的可选值 ---------------------------- */

/** 预设强调色，电视端与手机端共用同一组 */
val AccentPresets: List<Pair<String, Long>> = listOf(
    "小米橙" to 0xFFFF6900,
    "晴空蓝" to 0xFF3B82F6,
    "翡翠绿" to 0xFF10B981,
    "玫瑰红" to 0xFFF43F5E,
    "紫罗兰" to 0xFF8B5CF6,
    "琥珀金" to 0xFFF59E0B
)

/* ------------------------------ 壁纸 ------------------------------ */

/*
 * 内置壁纸已迁到 ui/components/BuiltinWallpapers.kt（底色渐变 + 径向光斑，程序绘制）。
 * 这里只保留字号与主题装配，避免两处定义出现配色漂移。
 */

/* ------------------------------ 字号阶梯 ------------------------------ */

private val TvTypography = Typography(
    headlineMedium = TextStyle(fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 18.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodySmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
)

/* ------------------------------ 主题装配 ------------------------------ */

val LocalTvColors = staticCompositionLocalOf { DarkColors }

/** 取色入口：`TvTheme.colors.surface` */
object TvTheme {
    val colors: TvColors
        @Composable @ReadOnlyComposable
        get() = LocalTvColors.current
}

/**
 * @param themeMode 深色 / 浅色 / 跟随系统
 * @param accentColor 强调色 Hex（Long），0 表示用默认橙
 * @param systemDark 跟随系统时，系统是否是深色
 */
@Composable
fun TvAlbumTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    accentColor: Long = 0xFFFF6900,
    systemDark: Boolean = true,
    content: @Composable () -> Unit
) {
    val dark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.AUTO -> systemDark
    }
    val base = if (dark) DarkColors else LightColors
    // 手机配置页早期存的是 6 位 RGB（如 0xF43F5E），直接 Color() 会得到 alpha=0 的透明色，
    // 必须补上不透明位；这里统一兜底，任何来源的强调色都先规范化。
    val accent = if (accentColor == 0L) base.accent else Color(accentColor.toOpaqueArgb())
    // 一律走 withAccent：默认橙也要经过 contrastOn，否则浅色主题会拿到写死的纯白 onAccent
    val colors = base.withAccent(accent, dark)

    val scheme = if (dark) {
        darkColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            background = colors.background,
            onBackground = colors.textPrimary,
            surface = colors.surface,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.surfaceHigh,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.border,
            error = colors.danger
        )
    } else {
        lightColorScheme(
            primary = colors.accent,
            onPrimary = colors.onAccent,
            background = colors.background,
            onBackground = colors.textPrimary,
            surface = colors.surface,
            onSurface = colors.textPrimary,
            surfaceVariant = colors.surfaceHigh,
            onSurfaceVariant = colors.textSecondary,
            outline = colors.border,
            error = colors.danger
        )
    }

    CompositionLocalProvider(LocalTvColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = TvTypography) {
            content()
        }
    }
}
