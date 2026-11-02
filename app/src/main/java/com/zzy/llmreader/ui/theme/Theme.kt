package com.zzy.llmreader.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.zzy.llmreader.data.AppTheme
import com.zzy.llmreader.data.ReaderFontFamily

// 安静、温暖的现代书房：低饱和墨绿、纸张色与柔和层级，适合长时间阅读。
private val LightColors = lightColorScheme(
    primary = Color(0xFF426457),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE9DE),
    onPrimaryContainer = Color(0xFF1B372A),
    secondary = Color(0xFF8B6B4A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0E5D3),
    onSecondaryContainer = Color(0xFF49351E),
    tertiary = Color(0xFF66778E),
    background = Color(0xFFF7F5EF),
    onBackground = Color(0xFF26332E),
    surface = Color(0xFFF7F5EF),
    onSurface = Color(0xFF26332E),
    surfaceVariant = Color(0xFFE9E9DF),
    onSurfaceVariant = Color(0xFF667068),
    outline = Color(0xFFC8CEC3),
    outlineVariant = Color(0xFFE0E3D9),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9CDB5),
    onPrimary = Color(0xFF173123),
    primaryContainer = Color(0xFF324D3D),
    onPrimaryContainer = Color(0xFFE0EFE1),
    secondary = Color(0xFFD8BD94),
    secondaryContainer = Color(0xFF493D2C),
    onSecondaryContainer = Color(0xFFF3E4CB),
    background = Color(0xFF171D19),
    onBackground = Color(0xFFE0E7DB),
    surface = Color(0xFF171D19),
    onSurface = Color(0xFFE0E7DB),
    surfaceVariant = Color(0xFF2A352D),
    onSurfaceVariant = Color(0xFFB2BDB0),
)

private val ReaderTypography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    titleMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
    titleSmall = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 28.sp, letterSpacing = 0.2.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
)

/** 把 [ReaderFontFamily] 映射到 Compose 的 [FontFamily]。 */
fun ReaderFontFamily.toFontFamily(): FontFamily? = when (this) {
    ReaderFontFamily.SYSTEM -> null
    ReaderFontFamily.SERIF -> FontFamily.Serif
    ReaderFontFamily.SANS_SERIF -> FontFamily.SansSerif
    ReaderFontFamily.MONOSPACE -> FontFamily.Monospace
}

/** 顶层别名，便于在不希望写 `with(theme){toFontFamily()}` 的调用点直接用。 */
fun resolveFontFamily(family: ReaderFontFamily): FontFamily? = family.toFontFamily()

/** 解析 [AppTheme] → 是否走 dark；@Composable 因为要读 `isSystemInDarkTheme()`。 */
@Composable
fun resolveDark(theme: AppTheme): Boolean = when (theme) {
    AppTheme.SYSTEM -> isSystemInDarkTheme()
    AppTheme.LIGHT -> false
    AppTheme.DARK -> true
}

/** 在基础 [Typography] 上叠加一个 [FontFamily]。null 表示沿用系统默认。 */
private fun Typography.withFontFamily(family: FontFamily?): Typography {
    if (family == null) return this
    return copy(
        displayLarge = displayLarge.copy(fontFamily = family),
        displayMedium = displayMedium.copy(fontFamily = family),
        displaySmall = displaySmall.copy(fontFamily = family),
        headlineLarge = headlineLarge.copy(fontFamily = family),
        headlineMedium = headlineMedium.copy(fontFamily = family),
        headlineSmall = headlineSmall.copy(fontFamily = family),
        titleLarge = titleLarge.copy(fontFamily = family),
        titleMedium = titleMedium.copy(fontFamily = family),
        titleSmall = titleSmall.copy(fontFamily = family),
        bodyLarge = bodyLarge.copy(fontFamily = family),
        bodyMedium = bodyMedium.copy(fontFamily = family),
        bodySmall = bodySmall.copy(fontFamily = family),
        labelLarge = labelLarge.copy(fontFamily = family),
        labelMedium = labelMedium.copy(fontFamily = family),
        labelSmall = labelSmall.copy(fontFamily = family),
    )
}

@Composable
fun LlmReaderTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // 默认走自定义阅读暖色，避免被系统取色覆盖
    fontFamily: ReaderFontFamily = ReaderFontFamily.SYSTEM,
    content: @Composable () -> Unit,
) {
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    val typography = ReaderTypography.withFontFamily(fontFamily.toFontFamily())
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}

/** 解析 [AppTheme] → 是否走 dark。 */
@Composable
fun AppTheme.isDark(): Boolean = when (this) {
    AppTheme.SYSTEM -> isSystemInDarkTheme()
    AppTheme.LIGHT -> false
    AppTheme.DARK -> true
}
// ============ 阅读专用主题：白天 / 护眼 / 夜间 ============
// 不影响系统主题（书库、设置仍用 LlmReaderTheme），仅在阅读区局部覆盖。

/** 白天：与系统暖白底一致。 */
val DayReaderColors = LightColors

/** 护眼：偏黄护眼底，褐色文字。 */
val SepiaReaderColors = lightColorScheme(
    primary = Color(0xFF7A5B3A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEED9BD),
    onPrimaryContainer = Color(0xFF3A2412),
    secondary = Color(0xFF8E6E4F),
    secondaryContainer = Color(0xFFE8D5B8),
    onSecondaryContainer = Color(0xFF3A2412),
    background = Color(0xFFEFE2C8),
    onBackground = Color(0xFF3A2E20),
    surface = Color(0xFFEFE2C8),
    onSurface = Color(0xFF3A2E20),
    surfaceVariant = Color(0xFFE0D2B6),
    onSurfaceVariant = Color(0xFF6B5A45),
    outline = Color(0xFFB39B7A),
)

/** 夜间：近黑底，柔和灰白字，避免纯黑+纯白对比刺眼。 */
val NightReaderColors = darkColorScheme(
    primary = Color(0xFFB9A3FF),
    onPrimary = Color(0xFF1B0F46),
    primaryContainer = Color(0xFF3B2A6A),
    onPrimaryContainer = Color(0xFFE9DFFF),
    secondary = Color(0xFFCBB89A),
    secondaryContainer = Color(0xFF2E2A24),
    onSecondaryContainer = Color(0xFFE0D5C4),
    background = Color(0xFF121212),
    onBackground = Color(0xFFC8C2B8),
    surface = Color(0xFF121212),
    onSurface = Color(0xFFC8C2B8),
    surfaceVariant = Color(0xFF2A2622),
    onSurfaceVariant = Color(0xFF9C968B),
    outline = Color(0xFF55504A),
)

/**
 * 阅读区当前生效的字体。
 *
 * 正文是用 `AndroidView` + `TextView` 渲染的（为了 Spanned 富文本和文本选择），
 * 它**不继承** Compose 的 typography —— 所以「阅读字体」只改菜单文字、正文纹丝不动。
 * 这里把设置透出去，让 TextReader 能把它转成 Android Typeface。
 */
val LocalReaderFontFamily = androidx.compose.runtime.compositionLocalOf {
    ReaderFontFamily.SYSTEM
}

/** 在阅读区内套一层 MaterialTheme，仅替换 colorScheme，保留 typography（含字体）。 */
@Composable
fun ReaderThemeOverride(
    theme: com.zzy.llmreader.data.ReadingTheme,
    fontFamily: ReaderFontFamily = ReaderFontFamily.SYSTEM,
    content: @Composable () -> Unit,
) {
    val colors = when (theme) {
        com.zzy.llmreader.data.ReadingTheme.DAY -> DayReaderColors
        com.zzy.llmreader.data.ReadingTheme.SEPIA -> SepiaReaderColors
        com.zzy.llmreader.data.ReadingTheme.NIGHT -> NightReaderColors
    }
    val typography = ReaderTypography.withFontFamily(fontFamily.toFontFamily())
    androidx.compose.runtime.CompositionLocalProvider(LocalReaderFontFamily provides fontFamily) {
        MaterialTheme(colorScheme = colors, typography = typography, content = content)
    }
}

/** 把用户选的字体映射成 Android Typeface；null = 交给系统默认。 */
fun ReaderFontFamily.toTypeface(): android.graphics.Typeface? = when (this) {
    ReaderFontFamily.SYSTEM -> null
    ReaderFontFamily.SERIF -> android.graphics.Typeface.SERIF
    ReaderFontFamily.SANS_SERIF -> android.graphics.Typeface.SANS_SERIF
    ReaderFontFamily.MONOSPACE -> android.graphics.Typeface.MONOSPACE
}
