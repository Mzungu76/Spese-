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
    primary = Color(0xFF2358D8), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE5FF), onPrimaryContainer = Color(0xFF001B55),
    secondary = Color(0xFF16A37F), onSecondary = Color.White,
    secondaryContainer = Color(0xFFC7F0E3), onSecondaryContainer = Color(0xFF00201A),
    tertiary = Color(0xFFE0742C), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDCC6), onTertiaryContainer = Color(0xFF301400),
    error = Color(0xFFD64545), onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF7F8FC), onBackground = Color(0xFF1A1C22),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF1A1C22),
    surfaceVariant = Color(0xFFE4E7F1), onSurfaceVariant = Color(0xFF444853),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF1F3FA),
    surfaceContainer = Color(0xFFECEFF7), surfaceContainerHigh = Color(0xFFE6E9F2), surfaceContainerHighest = Color(0xFFE0E3ED),
    outline = Color(0xFF757985), outlineVariant = Color(0xFFC5C8D4),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB1C5FF), onPrimary = Color(0xFF002A7E),
    primaryContainer = Color(0xFF0B3FB5), onPrimaryContainer = Color(0xFFDCE5FF),
    secondary = Color(0xFF6DD9B9), onSecondary = Color(0xFF00382B),
    secondaryContainer = Color(0xFF00513F), onSecondaryContainer = Color(0xFFC7F0E3),
    tertiary = Color(0xFFFFB68A), onTertiary = Color(0xFF512400),
    tertiaryContainer = Color(0xFF743500), onTertiaryContainer = Color(0xFFFFDCC6),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111318), onBackground = Color(0xFFE3E2E9),
    surface = Color(0xFF14161C), onSurface = Color(0xFFE3E2E9),
    surfaceVariant = Color(0xFF444853), onSurfaceVariant = Color(0xFFC5C8D4),
    surfaceContainerLowest = Color(0xFF0E1014), surfaceContainerLow = Color(0xFF1A1C22),
    surfaceContainer = Color(0xFF1E2027), surfaceContainerHigh = Color(0xFF282A31), surfaceContainerHighest = Color(0xFF33353C),
    outline = Color(0xFF8F92A0), outlineVariant = Color(0xFF444853),
)

private val Typo = Typography().let { t ->
    t.copy(
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.Medium),
    )
}

/** Cifre tabulari per gli importi (allineamento delle colonne). */
val MoneyStyle = TextStyle(fontFamily = FontFamily.Default, fontFeatureSettings = "tnum", letterSpacing = 0.sp)

@Composable
fun SpeseTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val fin = if (darkTheme) FinanceColors(Color(0xFF6DD9B9), Color(0xFFFF8A80), Color(0xFFFFB68A), Color(0xFFB0B3C0))
    else FinanceColors(Color(0xFF0C7A5E), Color(0xFFC0332F), Color(0xFFB85A14), Color(0xFF5E6270))
    CompositionLocalProvider(LocalFinanceColors provides fin) {
        MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, typography = Typo, content = content)
    }
}

object SpeseTheme { val finance: FinanceColors @Composable get() = LocalFinanceColors.current }
