package com.example.dronzer.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val CyberDarkColorScheme = darkColorScheme(
    primary = HackerGreen,
    secondary = CyberCyan,
    tertiary = TerminalGreen,
    background = CyberBlack,
    surface = CyberDarkGray,
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onTertiary = Color.Black,
    onBackground = HackerGreen,
    onSurface = HackerGreen,
    primaryContainer = HackerGreen.copy(alpha = 0.1f),
    onPrimaryContainer = HackerGreen,
    error = CyberRed,
    onError = Color.White
)

private val CyberLightColorScheme = lightColorScheme(
    primary = CyberBlue,
    secondary = CyberMagenta,
    tertiary = CyberNeonGreen,
    background = Color.White,
    surface = Color(0xFFF0F0F0),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.Black,
    onBackground = CyberBlack,
    onSurface = CyberBlack
)

@Composable
fun DronzerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is disabled by default for the Cyber theme to maintain the aesthetic
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> CyberDarkColorScheme
        else -> CyberLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes,
        content = content
    )
}
