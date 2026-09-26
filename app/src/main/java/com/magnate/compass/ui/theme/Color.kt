package com.magnate.compass.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.magnate.compass.data.entity.TAG_PALETTE_SIZE

// ————————————————————————————————————————————————————————————
//  design-gui.md §12.1 色彩
//  深色为默认主题。语义色在深浅两套主题下色相一致，仅调整明度以满足对比度。
// ————————————————————————————————————————————————————————————

// —— 深色 ——
private val DarkBackground = Color(0xFF121212)
private val DarkSurface = Color(0xFF1E1E1E)
private val DarkSurfaceVariant = Color(0xFF2A2A2A)
private val DarkOnSurface = Color(0xFFE6E6E6)
private val DarkOnSurfaceVariant = Color(0xFF9E9E9E)
private val DarkOutline = Color(0xFF4A4A4A)
private val DarkPrimary = Color(0xFF64B5F6)
private val DarkNeedle = Color(0xFFEF5350)
private val DarkSuccess = Color(0xFF66BB6A)
private val DarkWarning = Color(0xFFFFA726)
private val DarkError = Color(0xFFEF5350)

// —— 浅色 ——
private val LightBackground = Color(0xFFFAFAFA)
private val LightSurface = Color(0xFFFFFFFF)
private val LightSurfaceVariant = Color(0xFFF0F0F0)
private val LightOnSurface = Color(0xFF1A1A1A)
private val LightOnSurfaceVariant = Color(0xFF5F5F5F)
private val LightOutline = Color(0xFFBDBDBD)
private val LightPrimary = Color(0xFF1565C0)
private val LightNeedle = Color(0xFFD32F2F)
private val LightSuccess = Color(0xFF2E7D32)
private val LightWarning = Color(0xFFE65100)
private val LightError = Color(0xFFC62828)

internal val MagnateDarkColorScheme = androidx.compose.material3.darkColorScheme(
    primary = DarkPrimary,
    onPrimary = Color(0xFF0A1929),
    primaryContainer = DarkPrimary,
    onPrimaryContainer = Color(0xFF0A1929),
    secondary = DarkPrimary,
    onSecondary = Color(0xFF0A1929),
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    surfaceContainer = DarkSurface,
    surfaceContainerHigh = DarkSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutline,
    error = DarkError,
    onError = Color(0xFF2A0A0A),
    scrim = Color(0xCC000000),
)

internal val MagnateLightColorScheme = androidx.compose.material3.lightColorScheme(
    primary = LightPrimary,
    onPrimary = Color.White,
    primaryContainer = LightPrimary,
    onPrimaryContainer = Color.White,
    secondary = LightPrimary,
    onSecondary = Color.White,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    surfaceContainer = LightSurface,
    surfaceContainerHigh = LightSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutline,
    error = LightError,
    onError = Color.White,
    scrim = Color(0x66000000),
)

/**
 * Material3 的 [androidx.compose.material3.ColorScheme] 里没有「指针」「正常」「警告」这三个语义位，
 * 但它们贯穿罗盘、磁场卡片、坐标徽标与场景条，因此单列一份。
 */
@Immutable
data class MagnateSemanticColors(
    /** 指针与 N 标记 */
    val needle: Color,
    /** 精度高、磁场正常、定位准 */
    val success: Color,
    /** 精度偏低、倾斜、无坐标、场景缺坐标 */
    val warning: Color,
)

internal val DarkSemanticColors = MagnateSemanticColors(
    needle = DarkNeedle,
    success = DarkSuccess,
    warning = DarkWarning,
)

internal val LightSemanticColors = MagnateSemanticColors(
    needle = LightNeedle,
    success = LightSuccess,
    warning = LightWarning,
)

val LocalMagnateSemanticColors = staticCompositionLocalOf { DarkSemanticColors }

// ————————————————————————————————————————————————————————————
//  design-gui.md §12.2 标签调色板
//  以索引 0–7 存储，不存 hex，便于主题适配。
// ————————————————————————————————————————————————————————————

private val TagPaletteDark = listOf(
    Color(0xFFEF5350), // 0 红
    Color(0xFFFFA726), // 1 橙
    Color(0xFFFFCA28), // 2 黄
    Color(0xFF66BB6A), // 3 绿
    Color(0xFF26C6DA), // 4 青
    Color(0xFF42A5F5), // 5 蓝
    Color(0xFFAB47BC), // 6 紫
    Color(0xFF8D6E63), // 7 棕
)

private val TagPaletteLight = listOf(
    Color(0xFFC62828),
    Color(0xFFE65100),
    Color(0xFFF9A825),
    Color(0xFF2E7D32),
    Color(0xFF00838F),
    Color(0xFF1565C0),
    Color(0xFF6A1B9A),
    Color(0xFF4E342E),
)

fun tagPalette(darkTheme: Boolean): List<Color> =
    if (darkTheme) TagPaletteDark else TagPaletteLight

/** 越界索引回落到 0，避免历史数据里的脏索引让界面崩掉。 */
fun tagColor(index: Int, darkTheme: Boolean): Color {
    val palette = tagPalette(darkTheme)
    return palette[((index % TAG_PALETTE_SIZE) + TAG_PALETTE_SIZE) % TAG_PALETTE_SIZE]
}
