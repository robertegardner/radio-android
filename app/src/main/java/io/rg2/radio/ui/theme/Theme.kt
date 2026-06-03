package io.rg2.radio.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val RadioColors = darkColorScheme(
    primary = Amber,
    onPrimary = OnAmber,
    secondary = AmberDim,
    onSecondary = OnAmber,
    background = RadioBackground,
    onBackground = TextPrimary,
    surface = RadioSurface,
    onSurface = TextPrimary,
    surfaceVariant = RadioSurfaceHigh,
    onSurfaceVariant = TextFaint,
    outline = AmberDim,
    error = SignalBad,
)

/** App theme — always the dark amber-LCD scheme (no light variant by design). */
@Composable
fun RadioTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = RadioColors,
        content = content,
    )
}
