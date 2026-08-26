package com.mitas.ppnam.station2aa.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Tracking is size-specific, never one value for all sizes: large text tightens as it grows,
// small text opens up for glanceability on the handheld's screen; body stays at 0. Leading runs
// the other way — tight on headings, comfortable on body copy. Everything is sp, so the
// operator's system text-size setting scales all of it together.
val Typography = Typography(
    displaySmall  = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Normal,
        letterSpacing = (-0.4).sp, lineHeight = 32.sp),
    headlineMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.3).sp, lineHeight = 28.sp),
    headlineSmall  = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.2).sp, lineHeight = 24.sp),
    titleLarge     = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium,
        letterSpacing = 0.sp, lineHeight = 24.sp),
    bodyLarge      = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp, lineHeight = 24.sp),
    bodyMedium     = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp, lineHeight = 20.sp),
    labelSmall     = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium,
        letterSpacing = 0.4.sp, lineHeight = 14.sp)
)
