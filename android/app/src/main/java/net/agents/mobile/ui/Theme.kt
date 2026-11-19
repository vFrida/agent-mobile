package net.agents.mobile.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val AgentColors = lightColorScheme(
    primary = Color(0xFF171717),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF3F3F3),
    onPrimaryContainer = Color(0xFF171717),
    secondary = Color(0xFF6F6F6F),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF8F8F8),
    onBackground = Color(0xFF171717),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF171717),
    surfaceVariant = Color(0xFFF3F3F3),
    onSurfaceVariant = Color(0xFF6F6F6F),
    surfaceContainer = Color(0xFFFCFCFC),
    outline = Color(0xFFE5E5E5),
    outlineVariant = Color(0xFFEDEDED),
    error = Color(0xFFDC2626),
    errorContainer = Color(0xFFFDECEC),
    onErrorContainer = Color(0xFF7F1D1D)
)

@Composable
fun AgentTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AgentColors, content = content)
}

val SuccessColor = Color(0xFF16A34A)
val WarningColor = Color(0xFFD97706)
