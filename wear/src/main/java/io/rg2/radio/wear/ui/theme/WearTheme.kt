package io.rg2.radio.wear.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

// Amber-LCD palette, matching the phone app.
val Amber = Color(0xFFFFAE3A)
val OnAmber = Color(0xFF1A1100)
val MoswinGreen = Color(0xFF6DF09B)
val WearBackground = Color(0xFF0B0B0F)
val WearSurface = Color(0xFF1B1B22)
val TextPrimary = Color(0xFFECECEC)
val TextDim = Color(0xFF9A9AA2)
val SignalGood = Color(0xFF4CCB7A)
val SignalBad = Color(0xFFE0533D)

private val WearColors = Colors(
    primary = Amber,
    onPrimary = OnAmber,
    secondary = MoswinGreen,
    onSecondary = OnAmber,
    background = WearBackground,
    onBackground = TextPrimary,
    surface = WearSurface,
    onSurface = TextPrimary,
    error = SignalBad,
)

@Composable
fun RadioWearTheme(content: @Composable () -> Unit) {
    MaterialTheme(colors = WearColors, content = content)
}
