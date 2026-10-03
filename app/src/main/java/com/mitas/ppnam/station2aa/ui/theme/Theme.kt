package com.mitas.ppnam.station2aa.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val AppColorScheme = darkColorScheme(
    // primary is the readable tint: Material's default foreground uses (TextButton, OutlinedButton,
    // focus, indicators) sit on dark surfaces. Filled surfaces use BrandPrimary via brandButtonColors().
    primary = BrandTint,
    onPrimary = GraphiteBackground,
    primaryContainer = BrandPrimary,
    onPrimaryContainer = OnBrandPrimary,
    secondary = SuccessGreen,
    onSecondary = TextPrimary,
    background = GraphiteBackground,
    onBackground = TextPrimary,
    surface = GraphiteSurface,
    onSurface = TextPrimary,
    surfaceVariant = GraphiteSurfaceVariant,
    onSurfaceVariant = TextMuted,
    error = DangerRed,
    onError = TextPrimary,
    outline = GraphiteBorder
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(10.dp),
    extraLarge = RoundedCornerShape(12.dp)
)

@Composable
fun PPNAMStation2AATheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColorScheme,
        typography = Typography,
        shapes = AppShapes,
        content = content
    )
}

/** Filled-button colours: icon green with white text (the scheme's `primary` is the lighter tint). */
@Composable
fun brandButtonColors(): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = BrandPrimary,
    contentColor = OnBrandPrimary,
)
