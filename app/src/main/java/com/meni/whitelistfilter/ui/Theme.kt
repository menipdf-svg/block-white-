package com.meni.whitelistfilter.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** פלטת צבעים אחת לכל האפליקציה: ירוק עמוק ורגוע, עם משטחים בהירים/כהים נקיים */
private val LightColors = lightColorScheme(
    primary = Color(0xFF1E6B52),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFBFF0D8),
    onPrimaryContainer = Color(0xFF00382A),
    secondary = Color(0xFF4C6358),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCEE9DA),
    onSecondaryContainer = Color(0xFF092016),
    background = Color(0xFFF4F8F5),
    onBackground = Color(0xFF171D1A),
    surface = Color(0xFFF4F8F5),
    onSurface = Color(0xFF171D1A),
    surfaceVariant = Color(0xFFDCE5DD),
    onSurfaceVariant = Color(0xFF404944),
    outline = Color(0xFF707973),
    outlineVariant = Color(0xFFC0C9C2),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEEF3EF),
    surfaceContainer = Color(0xFFE8EEE9),
    surfaceContainerHigh = Color(0xFFE2E8E3),
    surfaceContainerHighest = Color(0xFFDCE3DD)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF86D7B4),
    onPrimary = Color(0xFF003828),
    primaryContainer = Color(0xFF005139),
    onPrimaryContainer = Color(0xFFA2F2CF),
    secondary = Color(0xFFB3CCBF),
    onSecondary = Color(0xFF1F352B),
    secondaryContainer = Color(0xFF354B41),
    onSecondaryContainer = Color(0xFFCEE9DA),
    background = Color(0xFF0F1512),
    onBackground = Color(0xFFDFE4DE),
    surface = Color(0xFF0F1512),
    onSurface = Color(0xFFDFE4DE),
    surfaceVariant = Color(0xFF404944),
    onSurfaceVariant = Color(0xFFC0C9C2),
    outline = Color(0xFF8A938C),
    outlineVariant = Color(0xFF404944),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    surfaceContainerLowest = Color(0xFF0A0F0D),
    surfaceContainerLow = Color(0xFF171D1A),
    surfaceContainer = Color(0xFF1B211E),
    surfaceContainerHigh = Color(0xFF252B28),
    surfaceContainerHighest = Color(0xFF303633)
)

/** כותרות במשקל בינוני-חזק, כדי שההיררכיה תהיה ברורה */
private val Base = Typography()
private val AppTypography = Typography(
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.Medium)
)

/** פינות מעוגלות בנדיבות: כרטיסים, חלונות ושדות */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
