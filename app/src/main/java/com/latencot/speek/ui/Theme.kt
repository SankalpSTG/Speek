package com.latencot.speek.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Speek's light theme: white, dark text, blue-600 accent. */
object Palette {
    val blue = Color(0xFF2563EB)
    val blueSoft = Color(0xFFDBEAFE)
    val ink = Color(0xFF0F172A)
    val muted = Color(0xFF64748B)
    val line = Color(0xFFE2E8F0)
    val agent = Color(0xFFF1F5F9)
    val amber = Color(0xFFB45309)
    val red = Color(0xFFB91C1C)
    val redSoft = Color(0xFFFEF2F2)
    val green = Color(0xFF15803D)
    val greenSoft = Color(0xFFF0FDF4)
    /** The chat background: a soft grey the white and blue bubbles sit on. */
    val wall = Color(0xFFEFF2F6)
}

@Composable
fun SpeekTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Palette.blue,
            onPrimary = Color.White,
            primaryContainer = Palette.blueSoft,
            background = Color.White,
            onBackground = Palette.ink,
            surface = Color.White,
            onSurface = Palette.ink,
            surfaceVariant = Palette.agent,
            onSurfaceVariant = Palette.muted,
            outline = Palette.line,
        ),
        content = content,
    )
}
