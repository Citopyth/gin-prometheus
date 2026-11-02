package de.privat.boom2local.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * A fixed dark scheme. No dynamic colour, which keeps the app off the wallpaper-derived
 * palette API and makes screenshots of the security screen look the same on every phone.
 */
private val Scheme = darkColorScheme(
    primary = Color(0xFF7FD1AE),
    onPrimary = Color(0xFF05130D),
    primaryContainer = Color(0xFF1C3A2E),
    onPrimaryContainer = Color(0xFFBFEFD9),
    secondary = Color(0xFF8FB8FF),
    onSecondary = Color(0xFF071327),
    background = Color(0xFF101418),
    onBackground = Color(0xFFE4E8EC),
    surface = Color(0xFF171C21),
    onSurface = Color(0xFFE4E8EC),
    surfaceVariant = Color(0xFF222932),
    onSurfaceVariant = Color(0xFFB9C2CC),
    error = Color(0xFFFF9A90),
    onError = Color(0xFF2B0A08),
    outline = Color(0xFF55606B),
)

@Composable
fun Boom2Theme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
