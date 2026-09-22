package dev.aegis.remote.android.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object OnyxColors {
    val Background = Color(0xFF000000)

    // A restrained black canvas keeps the remote desktop content in focus.
    // Elevation is communicated with a subtle tonal shift and hairlines, not
    // stacks of independently rounded cards.
    val Surface = Color(0xFF090A09)
    val ContainerLowest = Color(0xFF0C0E0D)
    val ContainerLow = Color(0xFF111312)
    val Container = Color(0xFF171A18)
    val ContainerHigh = Color(0xFF202421)
    val ContainerHighest = Color(0xFF2A302C)
    val OnSurface = Color(0xFFE7E9E7)
    val OnSurfaceStrong = Color(0xFFFFFFFF)
    val OnSurfaceVariant = Color(0xFFB7BCB8)
    val Outline = Color(0xFF8D9690)
    val OutlineVariant = Color(0xFF39413C)
    val Hairline = Color(0x20FFFFFF)
    val Primary = Color(0xFF6EE6A5)
    val OnPrimary = Color(0xFF003823)
    val PrimaryContainer = Color(0xFF17633F)
    val OnPrimaryContainer = Color(0xFFC5FFDC)
    val Error = Color(0xFFFFB4AB)
    val ErrorContainer = Color(0xFF93000A)
    val Warning = Color(0xFFFFC857)
}

private val OnyxColorScheme =
    darkColorScheme(
        primary = OnyxColors.Primary,
        onPrimary = OnyxColors.OnPrimary,
        primaryContainer = OnyxColors.PrimaryContainer,
        onPrimaryContainer = OnyxColors.OnPrimaryContainer,
        secondary = Color(0xFFC9C6C5),
        onSecondary = Color(0xFF313030),
        secondaryContainer = Color(0xFF484646),
        onSecondaryContainer = Color(0xFFE6E1E1),
        background = OnyxColors.Background,
        onBackground = OnyxColors.OnSurface,
        surface = OnyxColors.Surface,
        onSurface = OnyxColors.OnSurface,
        surfaceVariant = OnyxColors.ContainerHighest,
        onSurfaceVariant = OnyxColors.OnSurfaceVariant,
        outline = OnyxColors.Outline,
        outlineVariant = OnyxColors.OutlineVariant,
        error = OnyxColors.Error,
        onError = Color(0xFF690005),
        errorContainer = OnyxColors.ErrorContainer,
        onErrorContainer = Color(0xFFFFDAD6),
    )

// Inter can replace this family without changing any screen-level typography.
// SansSerif keeps the build self-contained while preserving the specified metrics.
private val OnyxTypography =
    Typography(
        headlineLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 22.sp,
                lineHeight = 28.sp,
                letterSpacing = (-0.2).sp,
            ),
        headlineMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp,
                lineHeight = 26.sp,
            ),
        titleLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                lineHeight = 24.sp,
            ),
        titleMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                lineHeight = 22.sp,
            ),
        bodyLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                lineHeight = 24.sp,
            ),
        bodyMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
        bodySmall =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            ),
        labelLarge =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center,
            ),
        labelMedium =
            TextStyle(
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.5.sp,
            ),
    )

private val OnyxShapes =
    Shapes(
        extraSmall = RoundedCornerShape(6.dp),
        small = RoundedCornerShape(10.dp),
        medium = RoundedCornerShape(14.dp),
        large = RoundedCornerShape(20.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )

@Composable
fun AegisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = OnyxColorScheme,
        typography = OnyxTypography,
        shapes = OnyxShapes,
        content = content,
    )
}
