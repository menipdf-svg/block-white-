package com.meni.whitelistfilter.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/*
 * ===== מערכת העיצוב =====
 * צבעי מותג: ירוק הגנה עמוק (primary), עם משטחים בהירים/כהים שקטים — כמו הגדרות המערכת.
 * צבעי מצב (מותר / חסום / זהירות / ניטרלי) הם טוקנים סמנטיים נפרדים, עם צבע טקסט משלהם.
 * כל זוג טקסט/רקע עומד ב-WCAG AA (4.5:1 לטקסט רגיל). המצב תמיד מוצג גם בטקסט ולא רק בצבע.
 */

/** צבעי מצב. לכל צבע יש "על-צבע" שנבחר כדי לעמוד בניגודיות */
data class SemanticColors(
    val allowed: Color,
    val onAllowed: Color,
    val allowedContainer: Color,
    val onAllowedContainer: Color,
    val blocked: Color,
    val onBlocked: Color,
    val blockedContainer: Color,
    val onBlockedContainer: Color,
    val caution: Color,
    val onCaution: Color,
    val cautionContainer: Color,
    val onCautionContainer: Color,
    val neutral: Color,
    val onNeutral: Color,
    val neutralContainer: Color,
    val onNeutralContainer: Color
)

private val LightSemantic = SemanticColors(
    allowed = Color(0xFF1E6B52), onAllowed = Color.White,              // 6.4:1
    allowedContainer = Color(0xFFCDEFDC), onAllowedContainer = Color(0xFF00382A), // 10.6:1
    blocked = Color(0xFFB3261E), onBlocked = Color.White,              // 6.5:1
    blockedContainer = Color(0xFFF9DEDC), onBlockedContainer = Color(0xFF410E0B), // 12.8:1
    caution = Color(0xFF8A5A00), onCaution = Color.White,              // 5.9:1
    cautionContainer = Color(0xFFFFE7B3), onCautionContainer = Color(0xFF3D2600), // 11.8:1
    neutral = Color(0xFF37474F), onNeutral = Color.White,              // 9.7:1
    neutralContainer = Color(0xFFDDE5E9), onNeutralContainer = Color(0xFF1A262C)  // 12.1:1
)

private val DarkSemantic = SemanticColors(
    allowed = Color(0xFF86D7B4), onAllowed = Color(0xFF003828),        // 7.8:1
    allowedContainer = Color(0xFF005139), onAllowedContainer = Color(0xFFA2F2CF), // 7.2:1
    blocked = Color(0xFFFFB4AB), onBlocked = Color(0xFF690005),        // 7.7:1
    blockedContainer = Color(0xFF93000A), onBlockedContainer = Color(0xFFFFDAD6), // 7.2:1
    caution = Color(0xFFF2C15D), onCaution = Color(0xFF3D2800),        // 8.4:1
    cautionContainer = Color(0xFF5C4000), onCautionContainer = Color(0xFFFFE7B3), // 7.9:1
    neutral = Color(0xFFB0BEC5), onNeutral = Color(0xFF1F2B31),        // 7.6:1
    neutralContainer = Color(0xFF37474F), onNeutralContainer = Color(0xFFDDE5E9)  // 7.6:1
)

val LocalSemanticColors = staticCompositionLocalOf { LightSemantic }

/** צבעי המצב של המסך הנוכחי (מתחלף אוטומטית בין בהיר לכהה) */
val semantic: SemanticColors
    @Composable get() = LocalSemanticColors.current

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

/** מרווחים אחידים. כל מסך משתמש רק בערכים האלה */
object Spacing {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    /** השוליים של כל עמוד והריפוד בתוך קבוצה */
    val l = 16.dp
    /** רווח בין קבוצות */
    val xl = 24.dp
    val xxl = 32.dp
}

/** רדיוסים: קבוצות גדולות, שורות ורכיבי קלט */
object Radius {
    val group = RoundedCornerShape(20.dp)
    val row = RoundedCornerShape(16.dp)
    val tile = RoundedCornerShape(8.dp)
}

/** כותרות במשקל בינוני-חזק, כדי שההיררכיה תהיה ברורה */
private val Base = Typography()
private val AppTypography = Typography(
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.Medium),
    labelMedium = Base.labelMedium.copy(fontWeight = FontWeight.SemiBold)
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
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalSemanticColors provides if (dark) DarkSemantic else LightSemantic) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content
        )
    }
}
