package net.lighttools.lightmux.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Background,
    secondary = AccentDim,
    background = Background,
    onBackground = Foreground,
    surface = Surface,
    onSurface = Foreground,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = ForegroundDim,
    error = Danger,
)

private val LightColors = lightColorScheme(
    primary = AccentLight,
    secondary = AccentDim,
    error = Danger,
)

@Composable
fun LightmuxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = LightmuxTypography,
        content = content,
    )
}
