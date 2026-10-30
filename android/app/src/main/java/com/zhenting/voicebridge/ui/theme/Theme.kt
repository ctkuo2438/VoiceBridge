package com.zhenting.voicebridge.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = TealDark,
    onPrimary = Color(0xFF003730),
    primaryContainer = Color(0xFF174C45),
    onPrimaryContainer = Color(0xFFB2F1E4),
    secondary = Color(0xFFB1CCC4),
    tertiary = Color(0xFFD5C79F)
)

private val LightColorScheme = lightColorScheme(
    primary = TealLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC8EEE4),
    onPrimaryContainer = Color(0xFF063E35),
    secondary = Color(0xFF4D6B62),
    tertiary = Color(0xFF75613A)
)

@Composable
fun VoiceBridgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val accentScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val colorScheme = accentScheme.copy(
        background = if (darkTheme) Night else Mist,
        onBackground = if (darkTheme) Color(0xFFE2EEEA) else Ink,
        surface = if (darkTheme) Color(0xFF182321) else Color.White,
        onSurface = if (darkTheme) Color(0xFFE2EEEA) else Ink,
        onSurfaceVariant = if (darkTheme) Color(0xFFB0C1BA) else Color(0xFF566D64),
        surfaceContainerLowest = if (darkTheme) Color(0xFF0B1211) else Color.White,
        surfaceContainerLow = if (darkTheme) Color(0xFF17221F) else Color(0xFFF0F5F2),
        surfaceContainer = if (darkTheme) Color(0xFF1D2A26) else Color(0xFFEBF1ED),
        surfaceContainerHigh = if (darkTheme) Color(0xFF26352F) else Color(0xFFE2EBE5),
        outlineVariant = if (darkTheme) Color(0xFF394C43) else Color(0xFFDCE6DF)
    )

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp),
            extraLarge = RoundedCornerShape(28.dp)
        ),
        content = content
    )
}
