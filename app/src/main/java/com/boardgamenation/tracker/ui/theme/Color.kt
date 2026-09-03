package com.boardgamenation.tracker.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// A tabletop palette: felt green primary, meeple-amber secondary, dice-blue tertiary.
private val Green40 = Color(0xFF3A6B57)
private val Green80 = Color(0xFFA1D4B9)
private val Green90 = Color(0xFFBCF0D5)
private val Green10 = Color(0xFF00210F)
private val Green20 = Color(0xFF0C3B26)
private val Green30 = Color(0xFF23523C)

private val Amber40 = Color(0xFF7B5800)
private val Amber80 = Color(0xFFF7BD48)
private val Amber90 = Color(0xFFFFDEA6)
private val Amber10 = Color(0xFF271900)
private val Amber20 = Color(0xFF412D00)
private val Amber30 = Color(0xFF5D4200)

private val Blue40 = Color(0xFF3F5F8F)
private val Blue80 = Color(0xFFAAC7FF)
private val Blue90 = Color(0xFFD7E3FF)
private val Blue10 = Color(0xFF001B3D)
private val Blue20 = Color(0xFF25324B)
private val Blue30 = Color(0xFF264775)

private val Red40 = Color(0xFFBA1A1A)
private val Red80 = Color(0xFFFFB4AB)
private val Red90 = Color(0xFFFFDAD6)
private val Red10 = Color(0xFF410002)
private val Red20 = Color(0xFF690005)
private val Red30 = Color(0xFF93000A)

internal val LightColors = lightColorScheme(
    primary = Green40,
    onPrimary = Color.White,
    primaryContainer = Green90,
    onPrimaryContainer = Green10,
    secondary = Amber40,
    onSecondary = Color.White,
    secondaryContainer = Amber90,
    onSecondaryContainer = Amber10,
    tertiary = Blue40,
    onTertiary = Color.White,
    tertiaryContainer = Blue90,
    onTertiaryContainer = Blue10,
    error = Red40,
    onError = Color.White,
    errorContainer = Red90,
    onErrorContainer = Red10,
    background = Color(0xFFFBFDF8),
    onBackground = Color(0xFF191C1A),
    surface = Color(0xFFFBFDF8),
    onSurface = Color(0xFF191C1A),
    surfaceVariant = Color(0xFFDCE5DD),
    onSurfaceVariant = Color(0xFF404943),
    surfaceContainer = Color(0xFFEFF1EC),
    surfaceContainerHigh = Color(0xFFE9EBE6),
    surfaceContainerHighest = Color(0xFFE3E5E0),
    surfaceContainerLow = Color(0xFFF5F7F2),
    surfaceContainerLowest = Color.White,
    outline = Color(0xFF707972),
    outlineVariant = Color(0xFFC0C9C1)
)

internal val DarkColors = darkColorScheme(
    primary = Green80,
    onPrimary = Green20,
    primaryContainer = Green30,
    onPrimaryContainer = Green90,
    secondary = Amber80,
    onSecondary = Amber20,
    secondaryContainer = Amber30,
    onSecondaryContainer = Amber90,
    tertiary = Blue80,
    onTertiary = Blue20,
    tertiaryContainer = Blue30,
    onTertiaryContainer = Blue90,
    error = Red80,
    onError = Red20,
    errorContainer = Red30,
    onErrorContainer = Red90,
    background = Color(0xFF111412),
    onBackground = Color(0xFFE1E3DE),
    surface = Color(0xFF111412),
    onSurface = Color(0xFFE1E3DE),
    surfaceVariant = Color(0xFF404943),
    onSurfaceVariant = Color(0xFFC0C9C1),
    surfaceContainer = Color(0xFF1D201E),
    surfaceContainerHigh = Color(0xFF272B28),
    surfaceContainerHighest = Color(0xFF323633),
    surfaceContainerLow = Color(0xFF191C1A),
    surfaceContainerLowest = Color(0xFF0C0F0D),
    outline = Color(0xFF8A938C),
    outlineVariant = Color(0xFF404943)
)
