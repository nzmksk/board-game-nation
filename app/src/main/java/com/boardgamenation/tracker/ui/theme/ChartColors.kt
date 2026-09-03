package com.boardgamenation.tracker.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * Chart colours, deliberately fixed rather than derived from the Material theme.
 *
 * The rest of the app happily follows the wallpaper via dynamic colour, but chart marks
 * do not: a palette is only legible if its hues stay separable under colour-vision
 * deficiency and keep enough contrast against the surface, and neither property survives
 * being regenerated from whatever image the user set this morning. These eight were
 * validated as a set against this app's own light and dark surfaces — lightness band,
 * chroma floor, adjacent-pair CVD separation, normal-vision separation and contrast.
 *
 * Slot order is the safety mechanism, not decoration: hues are assigned to players in
 * this fixed order and never cycled or reshuffled, so a player keeps their colour when
 * the set of players on screen changes.
 *
 * Three light-mode slots sit below 3:1 against the light surface. That is allowed only
 * because nothing here is ever encoded by colour alone: every chart row and every timer
 * zone carries the player's name beside the swatch.
 */
@Immutable
data class ChartColors(
    /** Categorical identity: players, and nothing else. */
    val series: List<Color>,

    /** Single-hue magnitude, used for every one-series bar and line. */
    val magnitude: Color,

    /** Diverging poles for signed values, with a neutral, hueless midpoint. */
    val divergingHigh: Color,
    val divergingLow: Color,
    val divergingNeutral: Color,

    val grid: Color,
    val axis: Color,
    val mutedInk: Color,

    /** Reserved for state, never for a series. */
    val good: Color,
    val warning: Color,
    val critical: Color
) {
    /** Assigns by index, folding past the eighth back onto the ramp only as a last resort. */
    fun forIndex(index: Int): Color = series[index.mod(series.size)]

    /**
     * A player's own colour when they have set one, otherwise their slot. Parsing is
     * lenient because the value can arrive from a hand-edited CSV.
     */
    fun forPlayer(colorHex: String?, index: Int): Color {
        val parsed = colorHex?.trim()?.removePrefix("#")?.takeIf { it.length == 6 }
            ?.toLongOrNull(16)
        return parsed?.let { Color(it or 0xFF000000L) } ?: forIndex(index)
    }
}

private val LightChartColors = ChartColors(
    series = listOf(
        Color(0xFF2A78D6), // blue
        Color(0xFFEB6834), // orange
        Color(0xFF1BAF7A), // aqua
        Color(0xFFEDA100), // yellow
        Color(0xFFE87BA4), // magenta
        Color(0xFF008300), // green
        Color(0xFF4A3AA7), // violet
        Color(0xFFE34948) // red
    ),
    magnitude = Color(0xFF2A78D6),
    divergingHigh = Color(0xFFD03B3B),
    divergingLow = Color(0xFF2A78D6),
    divergingNeutral = Color(0xFFF0EFEC),
    grid = Color(0xFFE1E0D9),
    axis = Color(0xFFC3C2B7),
    mutedInk = Color(0xFF898781),
    good = Color(0xFF0CA30C),
    warning = Color(0xFFFAB219),
    critical = Color(0xFFD03B3B)
)

private val DarkChartColors = ChartColors(
    series = listOf(
        Color(0xFF3987E5),
        Color(0xFFD95926),
        Color(0xFF199E70),
        Color(0xFFC98500),
        Color(0xFFD55181),
        Color(0xFF008300),
        Color(0xFF9085E9),
        Color(0xFFE66767)
    ),
    magnitude = Color(0xFF3987E5),
    divergingHigh = Color(0xFFE66767),
    divergingLow = Color(0xFF3987E5),
    divergingNeutral = Color(0xFF383835),
    grid = Color(0xFF2C2C2A),
    axis = Color(0xFF383835),
    mutedInk = Color(0xFF898781),
    good = Color(0xFF0CA30C),
    warning = Color(0xFFFAB219),
    critical = Color(0xFFD03B3B)
)

object ChartTheme {
    val colors: ChartColors
        @Composable
        @ReadOnlyComposable
        get() = if (isSystemInDarkTheme()) DarkChartColors else LightChartColors

    fun colorsFor(darkTheme: Boolean): ChartColors = if (darkTheme) DarkChartColors else LightChartColors
}
