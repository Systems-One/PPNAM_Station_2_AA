package com.mitas.ppnam.station2aa.data.mqtt

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField

/**
 * The one place the wire schema version is defined. Station 2 answers any request whose
 * `schemaVersion` is not exactly `rev2.1` with `invalid_envelope`.
 */
object MqttSchema {
    const val VERSION = "rev2.1"

    /** The contract revision this build implements. Station 2 echoes its own in every reply. */
    const val CONTRACT_REVISION = "2026-10-01"

    /**
     * Contract §4.1: "Every contract timestamp is UTC RFC 3339 with exactly six fractional digits
     * and `Z`."
     *
     * `Instant.toString()` does NOT satisfy this. It emits the *shortest* representation that
     * round-trips, so it drops the fractional part entirely on a whole second
     * (`2026-07-24T07:36:00Z`) and emits 3 or 9 digits otherwise. Station 2's parsers are
     * documented as tolerant of older valid representations, so this was survivable on 4.0 — but
     * "exactly six" is now the stated wire format, and a tolerant parser is not something to build
     * a contract cutover on.
     *
     * `appendFraction(NANO_OF_SECOND, 6, 6, true)` pads and truncates to exactly six digits.
     */
    private val RFC3339_MICROS: DateTimeFormatter = DateTimeFormatterBuilder()
        .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
        .appendFraction(ChronoField.NANO_OF_SECOND, 6, 6, true)
        .appendLiteral('Z')
        .toFormatter()
        .withZone(ZoneOffset.UTC)

    /** Formats [instant] as UTC RFC 3339 with exactly six fractional digits and a literal `Z`. */
    fun formatTimestamp(instant: Instant): String = RFC3339_MICROS.format(instant)
}
