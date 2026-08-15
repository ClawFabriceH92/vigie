package com.fabrice.vigie.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Palette « sentinelle » : bleu nuit + ambre
val NightBlue = Color(0xFF0D2B4E)
val DeepNight = Color(0xFF0A1F38)
val Amber = Color(0xFFC9972B)
val AmberLight = Color(0xFFE3B75C)
val Cream = Color(0xFFFAF6EF)
val AlertRed = Color(0xFFC62828)
val AlertRedLight = Color(0xFFE57373)
val TrustGreen = Color(0xFF2E7D32)
val TrustGreenLight = Color(0xFF81C784)
val OfflineGrey = Color(0xFF757575)

private val DarkColors = darkColorScheme(
    primary = Amber,
    onPrimary = DeepNight,
    secondary = AmberLight,
    onSecondary = DeepNight,
    background = DeepNight,
    onBackground = Cream,
    surface = NightBlue,
    onSurface = Cream,
    surfaceVariant = Color(0xFF16385E),
    onSurfaceVariant = Color(0xFFB8C7DA),
    error = AlertRedLight,
    onError = DeepNight,
)

private val LightColors = lightColorScheme(
    primary = NightBlue,
    onPrimary = Color.White,
    secondary = Amber,
    onSecondary = DeepNight,
    background = Cream,
    onBackground = Color(0xFF1A1A1A),
    surface = Color.White,
    onSurface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFFE8ECF2),
    onSurfaceVariant = Color(0xFF4A5568),
    error = AlertRed,
    onError = Color.White,
)

private val VigieShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val VigieTypography = Typography(
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 22.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 17.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.Bold, fontSize = 24.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 15.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 13.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
)

@Composable
fun VigieTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = VigieShapes,
        typography = VigieTypography,
        content = content,
    )
}
