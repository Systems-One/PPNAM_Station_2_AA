package com.mitas.ppnam.station2aa.data.mqtt

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField

/**
 * The one place the wire schema version is defined. Contract v4.1 rejects any request whose
 * schemaVersion is not exactly "4.1" (or "4.0" while Station 2 still has
 * `AllowSchema40Compatibility` on) with errorCode `unsupported_schema`.
 *
 * 4.1 is a hard cutover for authentication: Station 2 rejects ANY 4.1 JSON object containing a
 * `password` or `managerPassword` property with `plaintext_credentials_forbidden`, whatever the
 * message type. That is why the version bump and SCRAM-SHA-256 must ship together — see
 * [ScramCrypto].
 */
object MqttSchema {
    const val VERSION = "4.1"

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
