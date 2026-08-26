package com.mitas.ppnam.station2aa.ui.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

private val TIME_ONLY: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DATE_AND_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM HH:mm")

/**
 * Renders a Station 2 UTC timestamp as something a shop-floor operator can read.
 *
 * Station 2 sends .NET round-trip timestamps like `2026-07-23T11:38:28.2733333+00:00` — seven
 * fractional digits and an explicit offset. [Instant.parse] handles both, but the raw string was
 * being printed verbatim into the active-cycle sheet.
 *
 * Today's timestamps render as time alone ("14:07"); anything older carries the date too, so a
 * cycle left running overnight can't be misread as having started minutes ago. An unparseable
 * value falls back to the raw string rather than showing nothing — a timestamp we can't format is
 * still evidence, and silently blanking it would hide a real backend problem.
 */
fun formatStationTimestamp(raw: String, zone: ZoneId = ZoneId.systemDefault()): String {
    if (raw.isBlank()) return ""
    val instant = try {
        Instant.parse(raw)
    } catch (e: Exception) {
        return raw
    }
    val local = instant.atZone(zone)
    val today = Instant.now().atZone(zone).toLocalDate()
    return if (local.toLocalDate() == today) local.format(TIME_ONLY) else local.format(DATE_AND_TIME)
}

/**
 * How far ahead of the handheld a Station 2 timestamp may sit and still be treated as "now".
 *
 * The two clocks are known to drift — the app tracks it explicitly and surfaces a "Clock out of
 * sync" connection state — so a cycle started a moment ago can legitimately carry a timestamp a
 * few seconds in the future. Within this window that reads as "just now"; beyond it the timestamp
 * is not trustworthy enough to derive an elapsed time from at all.
 */
private const val CLOCK_SKEW_TOLERANCE_SECONDS = 120L

/**
 * "12 min ago" style elapsed text for a Station 2 UTC timestamp, or null when it cannot be parsed
 * or lies implausibly far in the future. Callers pair it with [formatStationTimestamp] — the
 * absolute time is the fact, the relative one is the quick read.
 *
 * Works in seconds, not minutes: [ChronoUnit.MINUTES] truncates toward zero, so a timestamp 30 s
 * in the future measures as 0 rather than negative and the skew check never fires.
 */
fun formatElapsedSince(raw: String, now: Instant = Instant.now()): String? {
    if (raw.isBlank()) return null
    val instant = try {
        Instant.parse(raw)
    } catch (e: Exception) {
        return null
    }
    val seconds = ChronoUnit.SECONDS.between(instant, now)
    if (seconds < -CLOCK_SKEW_TOLERANCE_SECONDS) return null
    val minutes = (seconds / 60L).coerceAtLeast(0L)
    return when {
        minutes < 1L -> "just now"
        minutes < 60L -> "$minutes min ago"
        minutes < 60L * 24L -> "${minutes / 60} h ${minutes % 60} min ago"
        else -> "${minutes / (60 * 24)} d ago"
    }
}
