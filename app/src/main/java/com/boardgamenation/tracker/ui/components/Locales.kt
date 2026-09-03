package com.boardgamenation.tracker.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLocale
import java.util.Locale

/**
 * The locale to format with inside a composable.
 *
 * `Locale.getDefault()` is not observable state, so a screen that formats a number with it
 * keeps whatever locale was current when it first composed and does not follow the user
 * changing theirs. `LocalLocale` is observable, and its `platformLocale` is the
 * `java.util.Locale` that `String.format` and the java.time formatters want.
 */
@Composable
fun currentLocale(): Locale = LocalLocale.current.platformLocale
