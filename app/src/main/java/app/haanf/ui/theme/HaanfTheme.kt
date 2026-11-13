package app.haanf.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.haanf.R
import app.haanf.training.Zone

/** The palette from docs/design/README.md. Light only, because dark mode is not designed. */
object HaanfColors {
    val Background = Color(0xFFF3F5F6)
    val Card = Color(0xFFFFFFFF)
    val Text = Color(0xFF101418)
    val TextSecondary = Color(0xFF56616B)
    val Divider = Color(0xFFDDE2E6)
    val Primary = Color(0xFF1F5BFF)
    val WarningBackground = Color(0xFFFFF0E0)
    val WarningIcon = Color(0xFF9A3C00)
    val InfoBackground = Color(0xFFE4ECFF)
    val InputBorder = Color(0xFFA9B3BC)
    val SwitchOff = Color(0xFF737F8A)
    val WeekFuture = Color(0xFFD5DBE0)
    val Dark = Color(0xFF101418)
    val White = Color(0xFFFFFFFF)
}

/** Zone colours passed a colour-blind check. Always show a zone's name with its colour. */
fun Zone.color(): Color = when (this) {
    Zone.ZONE_1 -> Color(0xFF009FB0)
    Zone.ZONE_2 -> Color(0xFF1F5BFF)
    Zone.ZONE_3 -> Color(0xFFF2B700)
    Zone.ZONE_4 -> Color(0xFFE8850C)
    Zone.ZONE_5 -> Color(0xFFC93400)
}

/** White on Zones 2 and 5, dark text on the rest. */
fun Zone.onColor(): Color = when (this) {
    Zone.ZONE_2, Zone.ZONE_5 -> HaanfColors.White
    else -> HaanfColors.Text
}

val Barlow = FontFamily(
    Font(R.font.barlow_regular, FontWeight.Normal),
    Font(R.font.barlow_medium, FontWeight.Medium),
    Font(R.font.barlow_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_bold, FontWeight.Bold),
)

val BarlowCondensed = FontFamily(
    Font(R.font.barlow_condensed_medium, FontWeight.Medium),
    Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_condensed_bold, FontWeight.Bold),
)

/** Headings and big numbers. */
fun condensed(size: TextUnit, weight: FontWeight = FontWeight.Bold, lineHeight: Double = 1.0) = TextStyle(
    fontFamily = BarlowCondensed,
    fontWeight = weight,
    fontSize = size,
    lineHeight = lineHeight.em,
)

/** Body text. */
fun body(size: TextUnit, weight: FontWeight = FontWeight.Normal, lineHeight: Double = 1.35) = TextStyle(
    fontFamily = Barlow,
    fontWeight = weight,
    fontSize = size,
    lineHeight = lineHeight.em,
)

private val typography = Typography(
    displayLarge = condensed(57.sp),
    displayMedium = condensed(45.sp),
    displaySmall = condensed(36.sp),
    headlineLarge = condensed(44.sp),
    headlineMedium = condensed(40.sp),
    headlineSmall = condensed(30.sp),
    titleLarge = body(20.sp, FontWeight.Bold),
    titleMedium = body(17.sp, FontWeight.Bold),
    titleSmall = body(16.sp, FontWeight.Bold),
    bodyLarge = body(16.sp),
    bodyMedium = body(15.sp),
    bodySmall = body(14.sp),
    labelLarge = body(16.sp, FontWeight.SemiBold),
    labelMedium = body(14.sp, FontWeight.SemiBold),
    labelSmall = body(13.sp, FontWeight.SemiBold),
)

private val colors = lightColorScheme(
    primary = HaanfColors.Primary,
    onPrimary = HaanfColors.White,
    primaryContainer = HaanfColors.InfoBackground,
    onPrimaryContainer = HaanfColors.Text,
    secondaryContainer = HaanfColors.InfoBackground,
    onSecondaryContainer = HaanfColors.Text,
    background = HaanfColors.Background,
    onBackground = HaanfColors.Text,
    surface = HaanfColors.Background,
    onSurface = HaanfColors.Text,
    surfaceVariant = HaanfColors.Card,
    onSurfaceVariant = HaanfColors.TextSecondary,
    surfaceContainer = HaanfColors.Card,
    surfaceContainerHigh = HaanfColors.Card,
    surfaceContainerHighest = HaanfColors.Card,
    surfaceContainerLow = HaanfColors.Card,
    outline = HaanfColors.InputBorder,
    outlineVariant = HaanfColors.Divider,
    error = HaanfColors.WarningIcon,
)

@Composable
fun HaanfTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
