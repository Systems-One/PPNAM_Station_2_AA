package com.mitas.ppnam.station2aa.ui.theme

import androidx.compose.ui.graphics.Color

// Background layers — matches WPF WindowBackgroundColor / AppBackgroundColor / PanelBackgroundColor
val GraphiteBackground     = Color(0xFF07101A)
val GraphiteSurface        = Color(0xFF102233)
val GraphiteSurfaceVariant = Color(0xFF14293D)
val GraphiteBorder         = Color(0xFF25384C)
// Resting outline of text fields (suite standard C1, Station 1 `field_stroke`): GraphiteBorder was
// below 3:1 on the card, so an empty field read as plain text. #7A8FA5 is ~4.6:1 on GraphiteSurface.
val FieldStroke            = Color(0xFF7A8FA5)

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
// Red TEXT for a dialog's destructive action (suite standard C6, Station 5 `danger_text`): DangerRed
// is ~4.3:1 on the dialog surface, this is ~5.3:1.
val DangerText             = Color(0xFFEF7B7B)

// Secondary accents — matches WPF OrangeColor / CyanColor / PurpleColor
val InfoBlue               = Color(0xFF2E77F5)
// Lighter tint of InfoBlue for blue status TEXT on graphite: InfoBlue is 3.9:1 on GraphiteSurface,
// this is 5.8:1 (visual critique 2026-11-06).
val InfoBlueTint           = Color(0xFF5B9BFF)
val IndigoAccent           = Color(0xFF8A63E8)
val WarningOrange          = Color(0xFFF0A13A)
val CyanAccent             = Color(0xFF25C7DA)
