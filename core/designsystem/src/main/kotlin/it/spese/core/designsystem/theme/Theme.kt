package it.spese.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

/** Colori semantici finanziari, adattati a tema chiaro/scuro. */
@Immutable
data class FinanceColors(val positive: Color, val negative: Color, val warning: Color, val neutral: Color)

val LocalFinanceColors = staticCompositionLocalOf { FinanceColors(Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified) }

private val LightColors = lightColorScheme(
    primary = Color(0xFF0A5CE0), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE7FF), onPrimaryContainer = Color(0xFF001A4D),
    secondary = Color(0xFF0C7A5E), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE7FF), onSecondaryContainer = Color(0xFF001A4D),
    tertiary = Color(0xFFB85A14), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDCC6), onTertiaryContainer = Color(0xFF301400),
    error = Color(0xFFC0332F), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF5F6F8), onBackground = Color(0xFF0B0C0F),
    surface = Color(0xFFF5F6F8), onSurface = Color(0xFF0B0C0F),
    surfaceVariant = Color(0xFFE6E8EE), onSurfaceVariant = Color(0xFF5A6070),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFEEF0F5), surfaceContainerHigh = Color(0xFFE6E9F0), surfaceContainerHighest = Color(0xFFDFE3EB),
    outline = Color(0xFF8A90A0), outlineVariant = Color(0xFFD5D9E2),
)

/** Tema scuro stile "banca digitale": nero profondo, carte grigio carbone, numeri bianchi, un solo accento blu. */
private val DarkColors = darkColorScheme(
    primary = Color(0xFF4C8DFF), onPrimary = Color(0xFF00163D),
    primaryContainer = Color(0xFF16315F), onPrimaryContainer = Color(0xFFD8E5FF),
    secondary = Color(0xFF34D399), onSecondary = Color(0xFF00382A),
    secondaryContainer = Color(0xFF1D3B6E), onSecondaryContainer = Color(0xFFDCE8FF),
    tertiary = Color(0xFFFFB020), onTertiary = Color(0xFF3A2600),
    tertiaryContainer = Color(0xFF4A3300), onTertiaryContainer = Color(0xFFFFE2A8),
    error = Color(0xFFFF6B6B), onError = Color(0xFF3A0008),
    errorContainer = Color(0xFF4A1616), onErrorContainer = Color(0xFFFFD9D9),
    background = Color(0xFF0B0C0F), onBackground = Color(0xFFF5F6F8),
    surface = Color(0xFF0B0C0F), onSurface = Color(0xFFF5F6F8),
    surfaceVariant = Color(0xFF23272D), onSurfaceVariant = Color(0xFF9AA0AB),
    surfaceContainerLowest = Color(0xFF000000), surfaceContainerLow = Color(0xFF15171B),
    surfaceContainer = Color(0xFF1B1E23), surfaceContainerHigh = Color(0xFF23272D), surfaceContainerHighest = Color(0xFF2B3036),
    outline = Color(0xFF4A505A), outlineVariant = Color(0xFF2A2E35),
)

private val Typo = Typography().let { t ->
    t.copy(
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 46.sp, letterSpacing = (-0.5).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold, fontSize = 30.sp, letterSpacing = (-0.3).sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.Medium),
        labelMedium = t.labelMedium.copy(fontWeight = FontWeight.Medium, fontSize = 12.sp),
        bodyMedium = t.bodyMedium.copy(fontSize = 14.sp),
    )
}

/** Cifre tabulari per gli importi (allineamento delle colonne). */
val MoneyStyle = TextStyle(fontFamily = FontFamily.Default, fontFeatureSettings = "tnum", letterSpacing = 0.sp)

@Composable
fun SpeseTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val fin = if (darkTheme) FinanceColors(Color(0xFF34D399), Color(0xFFFF6B6B), Color(0xFFFFB020), Color(0xFF9AA0AB))
    else FinanceColors(Color(0xFF0C7A5E), Color(0xFFC0332F), Color(0xFFB85A14), Color(0xFF5E6270))
    CompositionLocalProvider(LocalFinanceColors provides fin) {
        MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, typography = Typo, content = content)
    }
}

object SpeseTheme { val finance: FinanceColors @Composable get() = LocalFinanceColors.current }
