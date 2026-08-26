package com.mitas.ppnam.station2aa.ui.util

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class TimeFormatTest {

    private val utc = ZoneId.of("UTC")

    @Test
    fun `formats a dotnet round-trip timestamp with seven fractional digits`() {
        // Exactly the shape Station 2 sends, and exactly the string that was being printed raw
        // into the active-cycle sheet: "Started 2026-07-23T11:38:28.2733333+00:00 by ".
        val formatted = formatStationTimestamp("2026-07-23T11:38:28.2733333+00:00", utc)
        assertTrue("expected a readable HH:mm, got '$formatted'", formatted.contains("11:38"))
        assertFalse(formatted.contains("T"))
    }

    @Test
    fun `an older timestamp carries its date so an overnight cycle cannot read as minutes old`() {
        val formatted = formatStationTimestamp("2020-01-05T06:07:00Z", utc)
        assertTrue(formatted, formatted.contains("5 Jan"))
        assertTrue(formatted, formatted.contains("06:07"))
    }

    @Test
    fun `an unparseable value falls back to the raw string rather than blanking`() {
        assertEquals("not-a-timestamp", formatStationTimestamp("not-a-timestamp", utc))
    }

    @Test
    fun `blank in, blank out`() {
        assertEquals("", formatStationTimestamp("", utc))
        assertNull(formatElapsedSince(""))
    }

    @Test
    fun `elapsed text reads in minutes, hours then days`() {
        val now = Instant.parse("2026-07-23T12:00:00Z")
        assertEquals("just now", formatElapsedSince("2026-07-23T11:59:30Z", now))
        assertEquals("12 min ago", formatElapsedSince("2026-07-23T11:48:00Z", now))
        assertEquals("2 h 30 min ago", formatElapsedSince("2026-07-23T09:30:00Z", now))
        assertEquals("3 d ago", formatElapsedSince("2026-07-20T11:00:00Z", now))
    }

    @Test
    fun `a small forward clock skew still reads as just now`() {
        // The two clocks are known to drift — the app has a whole "Clock out of sync" connection
        // state for it — so a cycle started a moment ago can carry a timestamp seconds ahead of
        // the handheld. That must not suppress the reading.
        val now = Instant.parse("2026-07-23T12:00:00Z")
        assertEquals("just now", formatElapsedSince("2026-07-23T12:00:30Z", now))
    }

    @Test
    fun `a timestamp implausibly far in the future yields no elapsed text`() {
        // Beyond plausible skew the timestamp is not trustworthy enough to derive an elapsed
        // time from; the caller falls back to the absolute time instead of printing a negative.
        val now = Instant.parse("2026-07-23T12:00:00Z")
        assertNull(formatElapsedSince("2026-07-23T14:00:00Z", now))
    }

    @Test
    fun `unparseable input yields no elapsed text`() {
        assertNull(formatElapsedSince("garbage"))
    }
}
