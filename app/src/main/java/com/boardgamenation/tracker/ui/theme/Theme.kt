package com.boardgamenation.tracker.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import com.boardgamenation.tracker.domain.model.ThemeMode

/**
 * Chart colours are provided through the theme rather than read from the system, so they
 * follow the user's explicit light/dark choice instead of only the device setting.
 */
val LocalChartColors = staticCompositionLocalOf { ChartTheme.colorsFor(darkTheme = false) }

@Composable
fun BoardGameNationTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val context = LocalContext.current
    val colorScheme = when {
        // Dynamic colour is a nicety for the app chrome. Chart marks deliberately opt
        // out of it; see ChartColors.
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }

    CompositionLocalProvider(LocalChartColors provides ChartTheme.colorsFor(darkTheme)) {
        MaterialTheme(colorScheme = colorScheme, typography = AppTypography, content = content)
    }
}
