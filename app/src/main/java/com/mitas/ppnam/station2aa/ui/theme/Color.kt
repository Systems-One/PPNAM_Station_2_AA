package com.mitas.ppnam.station2aa.ui.theme

import androidx.compose.ui.graphics.Color

// Background layers — matches WPF WindowBackgroundColor / AppBackgroundColor / PanelBackgroundColor
val GraphiteBackground     = Color(0xFF07101A)
val GraphiteSurface        = Color(0xFF102233)
val GraphiteSurfaceVariant = Color(0xFF14293D)
val GraphiteBorder         = Color(0xFF25384C)

// Text — matches WPF TextColor / SecondaryTextColor
val TextPrimary            = Color(0xFFEDF4FB)
val TextMuted              = Color(0xFF9BAEC0)

// Primary accent — the Station 2 launcher-icon green (UI_Design README: app2 #1D6B45), so the
// app carries its icon identity on screen the way Stations 1/3/5 do. Was a blue misnamed
// "BrandPrimary" that made S2 and S4 look like the same app (audit S2-16 / static-02).
val BrandPrimary           = Color(0xFF1D6B45)
// Lighter tint of the same green for primary drawn as TEXT / icon / outline / cursor / progress on
// the dark graphite surfaces: BrandPrimary is only ~2.8:1 there, BrandTint is >= 4.5:1.
val BrandTint              = Color(0xFF6CCB95)
val OnBrandPrimary         = Color(0xFFFFFFFF)   // on-primary (white text on green buttons)

// Status colours — matches WPF GreenColor / RedColor
val SuccessGreen           = Color(0xFF2BC36D)
val DangerRed              = Color(0xFFE25C5C)

// Secondary accents — matches WPF OrangeColor / CyanColor / PurpleColor
val InfoBlue               = Color(0xFF2E77F5)
val IndigoAccent           = Color(0xFF8A63E8)
val WarningOrange          = Color(0xFFF0A13A)
val CyanAccent             = Color(0xFF25C7DA)
