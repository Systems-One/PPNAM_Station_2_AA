package com.mitas.ppnam.station2aa.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
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
    outline = GraphiteBorder,
    // Roles the scheme used to leave at Material's baseline (purple-grey) values. They leaked into
    // every component that reads them: the selected Include/Exclude segment was #4A4458 mauve, the
    // Settings switch track and the Home "unanswered command" card a warm #36343B grey (visual
    // critique 2026-11-06, P2). Map them onto the graphite + icon-green palette.
    secondaryContainer = BrandPrimary,
    onSecondaryContainer = OnBrandPrimary,
    surfaceContainerLowest = GraphiteBackground,
    surfaceContainerLow = GraphiteSurface,
    surfaceContainer = GraphiteSurface,
    surfaceContainerHigh = GraphiteSurfaceVariant,
    surfaceContainerHighest = GraphiteSurfaceVariant,
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

/**
 * Suite standard C7: buttons use 14dp corners (Station 5 login `cornerRadius=14dp`) instead of
 * Material 3's fully round pill, so filled and outlined actions match the other PPNAM handhelds.
 */
val AppButtonShape = RoundedCornerShape(14.dp)

/**
 * Suite standard C1: outlined text fields readable at rest. FieldStroke at rest, the brand tint when
 * focused, FieldStroke at 38% when disabled (Station 1 `res/color/text_field_stroke.xml`).
 */
@Composable
fun appTextFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = BrandTint,
    unfocusedBorderColor = FieldStroke,
    disabledBorderColor = FieldStroke.copy(alpha = 0.38f),
    focusedLabelColor = BrandTint,
    cursorColor = BrandTint,
)

/**
 * Suite standard C6: a dialog's neutral / Cancel action is white (TextPrimary); only a destructive
 * action is coloured (DangerText). Was the scheme's green, so Cancel read as the go-ahead.
 */
@Composable
fun neutralTextButtonColors(): ButtonColors = ButtonDefaults.textButtonColors(contentColor = TextPrimary)
