package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val JarvisDarkColorScheme = darkColorScheme(
    primary = JarvisCyanPrimary,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerCyan,
    onPrimaryContainer = OnPrimaryContainerCyan,
    secondary = SecondaryBlue,
    onSecondary = OnSecondaryDark,
    background = JarvisBlack,
    onBackground = JarvisTextPrimary,
    surface = JarvisDarkNavy,
    onSurface = JarvisTextPrimary,
    surfaceVariant = JarvisSurfaceNavy,
    onSurfaceVariant = JarvisTextSecondary,
    outline = JarvisBorderCyan,
    outlineVariant = JarvisBorderSubtle
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false, // Use customized JARVIS theme
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = JarvisDarkColorScheme,
        typography = Typography,
        content = content
    )
}
