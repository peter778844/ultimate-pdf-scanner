package de.ultimatepdfscanner.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Brand palette, derived from the logo blue #154889. The logo has no second colour, so the
 * whole app is blue on blue-tinted neutrals; amber and red only ever mean warning and error.
 */
object Brand {
    val Blue = Color(0xFF154889)
    val BlueLight = Color(0xFFA9C7FF)
    val CameraBg = Color(0xFF0B1422) // camera and crop: near-black navy
    val Editor = Color(0xFF0F1620) // dark editing screens (page view, annotate)
    val EditorBar = Color(0xFF18212D) // their tool bars
    val Overlay = Color(0xFF5AA2FF) // live page outline, active tools on dark screens: bright logo blue, visible on paper and desk
}

private val Light = lightColorScheme(
    primary = Brand.Blue, onPrimary = Color.White,
    primaryContainer = Color(0xFFD8E4F7), onPrimaryContainer = Color(0xFF0B2A52),
    secondary = Color(0xFF4E6584), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE6F3), onSecondaryContainer = Color(0xFF0F2236),
    tertiary = Color(0xFF2F6FB8), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD5E5FA), onTertiaryContainer = Color(0xFF0A2B52),
    background = Color(0xFFF5F7FA), onBackground = Color(0xFF16202C),
    surface = Color(0xFFF5F7FA), onSurface = Color(0xFF16202C),
    surfaceVariant = Color(0xFFE2E8F0), onSurfaceVariant = Color(0xFF4A5868),
    surfaceTint = Brand.Blue,
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFEEF2F7), surfaceContainerHigh = Color(0xFFE7ECF2), surfaceContainerHighest = Color(0xFFDFE5ED),
    outline = Color(0xFF7A8696), outlineVariant = Color(0xFFD3DAE3),
    error = Color(0xFFBA1A1A), onError = Color.White, errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
)

private val Dark = darkColorScheme(
    primary = Brand.BlueLight, onPrimary = Color(0xFF002F64),
    primaryContainer = Color(0xFF1D4E8F), onPrimaryContainer = Color(0xFFD8E4F7),
    secondary = Color(0xFFB6C8E2), onSecondary = Color(0xFF203247),
    secondaryContainer = Color(0xFF34485F), onSecondaryContainer = Color(0xFFDCE6F3),
    tertiary = Color(0xFF9CC4F5), onTertiary = Color(0xFF00315C),
    tertiaryContainer = Color(0xFF1F4A7A), onTertiaryContainer = Color(0xFFD5E5FA),
    background = Color(0xFF0F141B), onBackground = Color(0xFFE1E6ED),
    surface = Color(0xFF0F141B), onSurface = Color(0xFFE1E6ED),
    surfaceVariant = Color(0xFF2F3640), onSurfaceVariant = Color(0xFFB4BECB),
    surfaceTint = Brand.BlueLight,
    surfaceContainerLowest = Color(0xFF0A0F15), surfaceContainerLow = Color(0xFF161C24),
    surfaceContainer = Color(0xFF1A2029), surfaceContainerHigh = Color(0xFF242B35), surfaceContainerHighest = Color(0xFF2F3640),
    outline = Color(0xFF8892A0), outlineVariant = Color(0xFF3B4450),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005), errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
)

/** Default type scale with firmer titles: headings read as headings without larger sizes. */
private val Type = Typography().let { t ->
    t.copy(
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun ScanTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = Type,
        shapes = Shapes(extraSmall = RoundedCornerShape(6.dp), small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(14.dp),
            large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp)),
        content = content,
    )
}

/** Status colours for page quality (warning amber, error red), readable in light and dark theme. */
object Status {
    val Warn: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFFFFB95C) else Color(0xFFB25E00)
    val WarnContainer: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF3A2A12) else Color(0xFFFFF1DC)
    val OnWarnContainer: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFFFFE2BC) else Color(0xFF3D2500)
    val Error: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFFFF8A80) else Color(0xFFC62828)
    val Ok: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF6FDDB0) else Color(0xFF1B8A5A)
}
