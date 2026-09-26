package com.magnate.compass.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * 深色为设计的主推主题（design-gui.md §12.1「户外可读」），但两套配色都完整实现，
 * 因此跟随系统设置——用户在强光下把系统切到浅色时应用应一并变浅。
 */
@Composable
fun MagnateTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) MagnateDarkColorScheme else MagnateLightColorScheme
    val semanticColors = if (darkTheme) DarkSemanticColors else LightSemanticColors

    CompositionLocalProvider(LocalMagnateSemanticColors provides semanticColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = MagnateTypography,
            content = content,
        )
    }
}
