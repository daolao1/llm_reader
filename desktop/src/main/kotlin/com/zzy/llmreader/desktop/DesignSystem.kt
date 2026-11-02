package com.zzy.llmreader.desktop

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 有辨识度但克制：午夜蓝 + 蜂蜜橙。阅读区域独立于应用配色。 */
internal object ReaderDesign {
    val LightColors: ColorScheme = lightColorScheme(
        primary = Color(0xFF203D50),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFDCE6EB),
        onPrimaryContainer = Color(0xFF162F3F),
        secondary = Color(0xFF9B6438),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFF2E5D5),
        onSecondaryContainer = Color(0xFF533A22),
        tertiary = Color(0xFF657B73),
        background = Color(0xFFF3F4F2),
        onBackground = Color(0xFF202C32),
        surface = Color(0xFFFCFCF9),
        onSurface = Color(0xFF202C32),
        surfaceVariant = Color(0xFFE7EAE6),
        onSurfaceVariant = Color(0xFF69746F),
        surfaceContainerLow = Color(0xFFF3F4F2),
        surfaceContainer = Color(0xFFFCFCF9),
        outline = Color(0xFFD0D8D4),
        outlineVariant = Color(0xFFE2E7E2),
        errorContainer = Color(0xFFF8E2DC),
        onErrorContainer = Color(0xFF762F25),
    )
    val DarkColors: ColorScheme = darkColorScheme(
        primary = Color(0xFFB5CEDC),
        onPrimary = Color(0xFF19303D),
        primaryContainer = Color(0xFF334C5A),
        onPrimaryContainer = Color(0xFFE0EDF1),
        secondary = Color(0xFFE1B680),
        secondaryContainer = Color(0xFF493C2D),
        onSecondaryContainer = Color(0xFFF3E1C7),
        background = Color(0xFF151C20),
        onBackground = Color(0xFFE0E7E4),
        surface = Color(0xFF1C2529),
        onSurface = Color(0xFFE0E7E4),
        surfaceVariant = Color(0xFF2B3639),
        onSurfaceVariant = Color(0xFFB1BDB6),
        surfaceContainerLow = Color(0xFF151C20),
        surfaceContainer = Color(0xFF1C2529),
        outline = Color(0xFF45554F),
        outlineVariant = Color(0xFF303D38),
    )
    val Typography = Typography(
        displaySmall = TextStyle(fontSize = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
        headlineLarge = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
        headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = TextStyle(fontSize = 19.sp, fontWeight = FontWeight.SemiBold),
        titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
        bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 28.sp),
        bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
        bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 19.sp),
        labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
        labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    )
    val ScreenPadding = 32.dp
    val CardRadius = 18.dp
}

@Composable
internal fun ReaderTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) ReaderDesign.DarkColors else ReaderDesign.LightColors,
        typography = ReaderDesign.Typography,
        content = content,
    )
}

internal val SerifReading = FontFamily.Serif

internal fun coverPalette(seed: String): Pair<Color, Color> {
    val palettes = listOf(
        Color(0xFF294B5C) to Color(0xFF16303F),
        Color(0xFF8F6E4C) to Color(0xFF5C4431),
        Color(0xFF627C71) to Color(0xFF354C43),
        Color(0xFF7F6B79) to Color(0xFF4D3F4B),
        Color(0xFF697D93) to Color(0xFF3D4C62),
        Color(0xFF9B7566) to Color(0xFF684C43),
    )
    return palettes[(seed.hashCode().toUInt() % palettes.size.toUInt()).toInt()]
}
