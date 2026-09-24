package dev.hamstercage.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared visual tokens. The selected launcher art is separate from private aesthetic references. */
object CageStyle {
    val Background = Color(0xFF0D1017)
    val Surface = Color(0xFF151A24)
    val Raised = Color(0xFF1C2330)
    val Warm = Color(0xFF241E1A)
    val Amber = Color(0xFFF2B84B)
    val Orange = Color(0xFFD8844D)
    val Peach = Color(0xFFF2B09C)
    val Pink = Color(0xFFE58BAD)
    val Text = Color(0xFFF4F1EA)
    val Secondary = Color(0xFFB9C0CC)
    val Muted = Color(0xFF9AA5B6)
    val Outline = Color(0xFF2B3443)
    val Danger = Color(0xFFFFA8B0)
    val Space = 20.dp
    val Gap = 12.dp
    val Radius = 20.dp
    val Tight = 4.dp
    val Small = 8.dp
    val Large = 24.dp
    val TouchTarget = 48.dp
    val StrokeWidth = 1.dp
    val IconSize = 24.dp
    val SmallRadius = 10.dp
    val MediumRadius = 16.dp
    val LargeRadius = 26.dp
    const val LargeFontThreshold = 1.5f
}

@Composable
fun HamsterTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = CageStyle.Amber, onPrimary = CageStyle.Background,
            primaryContainer = CageStyle.Warm, onPrimaryContainer = CageStyle.Amber,
            secondary = CageStyle.Peach, onSecondary = CageStyle.Background,
            secondaryContainer = CageStyle.Raised, onSecondaryContainer = CageStyle.Peach,
            tertiary = CageStyle.Pink, onTertiary = CageStyle.Background,
            background = CageStyle.Background, onBackground = CageStyle.Text,
            surface = CageStyle.Surface, onSurface = CageStyle.Text,
            surfaceVariant = CageStyle.Raised, onSurfaceVariant = CageStyle.Secondary,
            outline = CageStyle.Outline, error = CageStyle.Danger
        ),
        shapes = Shapes(
            small = RoundedCornerShape(CageStyle.SmallRadius), medium = RoundedCornerShape(CageStyle.MediumRadius),
            large = RoundedCornerShape(CageStyle.Radius), extraLarge = RoundedCornerShape(CageStyle.LargeRadius)
        ),
        typography = Typography(
            displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 44.sp, lineHeight = 50.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1.5).sp),
            headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 30.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.6).sp),
            headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 23.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
            titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 20.sp, lineHeight = 27.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 25.sp),
            bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 21.sp),
            labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
            labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.8.sp)
        ), content = content
    )
}

@Composable
fun DestinationIcon(index: Int, selected: Boolean) {
    val ink = if (selected) CageStyle.Amber else CageStyle.Secondary
    Canvas(Modifier.size(CageStyle.IconSize)) {
        val u = size.width / 24f
        val stroke = Stroke(1.65f * u)
        when (index) {
            0 -> {
                drawRoundRect(ink, Offset(2*u, 3*u), Size(20*u,18*u), CornerRadius(3*u), style=stroke)
                drawLine(ink,Offset(7*u,15*u),Offset(7*u,17*u),2*u)
                drawLine(ink,Offset(12*u,10*u),Offset(12*u,17*u),2*u)
                drawLine(ink,Offset(17*u,7*u),Offset(17*u,17*u),2*u)
            }
            1 -> {
                drawCircle(ink,9*u,Offset(12*u,12*u),style=stroke)
                drawLine(ink,Offset(12*u,6*u),Offset(12*u,12*u),1.65f*u)
                drawLine(ink,Offset(12*u,12*u),Offset(16*u,14*u),1.65f*u)
            }
            2 -> {
                drawRoundRect(ink,Offset(5*u,2*u),Size(14*u,20*u),CornerRadius(2*u),style=stroke)
                for (x in listOf(9f,15f)) for (y in listOf(7f,12f)) drawCircle(ink,1*u,Offset(x*u,y*u))
                drawLine(ink,Offset(12*u,17*u),Offset(12*u,22*u),2*u)
            }
            else -> {
                for ((y,x) in listOf(6f to 9f,12f to 16f,18f to 8f)) {
                    drawLine(ink,Offset(3*u,y*u),Offset(21*u,y*u),1.6f*u)
                    drawCircle(CageStyle.Surface,2.5f*u,Offset(x*u,y*u))
                    drawCircle(ink,2.5f*u,Offset(x*u,y*u),style=stroke)
                }
            }
        }
    }
}
