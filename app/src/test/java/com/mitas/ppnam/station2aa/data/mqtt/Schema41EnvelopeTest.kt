package com.mitas.ppnam.station2aa.data.mqtt

import com.google.gson.Gson
import com.mitas.ppnam.station2aa.data.mqtt.dto.ResponseEnvelope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Contract v4.1 §7 envelope rules.
 *
 * These cover the parts of the cutover that are invisible until they are wrong: a timestamp whose
 * precision drifts, and an `errorMessage` that silently loses to a legacy `reason`.
 */
class Schema41EnvelopeTest {

    private val gson = Gson()

    // ---- timestamps ---------------------------------------------------------------------

    @Test
    fun `a whole second still carries exactly six fractional digits`() {
        // Instant.toString() emits "2026-07-24T08:00:00Z" here — no fractional part at all — which
        // is why the formatter cannot just be toString().
        assertEquals(
            "2026-07-24T08:00:00.000000Z",
            MqttSchema.formatTimestamp(Instant.parse("2026-07-24T08:00:00Z")),
        )
    }

    @Test
    fun `millisecond precision is padded up to six digits`() {
        assertEquals(
            "2026-07-24T08:00:00.123000Z",
            MqttSchema.formatTimestamp(Instant.parse("2026-07-24T08:00:00.123Z")),
        )
    }

    @Test
    fun `nanosecond precision is truncated down to six digits`() {
        assertEquals(
            "2026-07-24T08:00:00.123456Z",
            MqttSchema.formatTimestamp(Instant.parse("2026-07-24T08:00:00.123456789Z")),
        )
    }

    @Test
    fun `every formatted timestamp matches the contract's shape`() {
        val shape = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z$""")
        listOf(
            "2026-07-24T08:00:00Z",
            "2026-01-01T00:00:00.000000001Z",
            "2026-12-31T23:59:59.999999999Z",
        ).forEach {
            val formatted = MqttSchema.formatTimestamp(Instant.parse(it))
            assertTrue("$formatted does not match the 4.1 shape", shape.matches(formatted))
        }
    }

    @Test
    fun `a formatted timestamp still round-trips as an Instant`() {
        val original = Instant.parse("2026-07-24T08:00:00.123456Z")
        assertEquals(original, Instant.parse(MqttSchema.formatTimestamp(original)))
    }

    // ---- error contract -----------------------------------------------------------------

    @Test
    fun `errorMessage wins over the rollout reason mirror`() {
        val envelope = gson.fromJson(
            """{"errorMessage":"Canonical text","reason":"Legacy mirror"}""",
            ResponseEnvelope::class.java,
        )

        assertEquals("Canonical text", envelope.displayMessage)
    }

    @Test
    fun `reason is used when errorMessage is absent or blank`() {
        // Backend issue B9: text arrived in `reason` with `errorMessage` missing entirely. During
        // rollout both spellings are live, so neither may be the only one we read.
        assertEquals(
            "Legacy mirror",
            gson.fromJson("""{"reason":"Legacy mirror"}""", ResponseEnvelope::class.java).displayMessage,
        )
        assertEquals(
            "Legacy mirror",
            gson.fromJson(
                """{"errorMessage":"","reason":"Legacy mirror"}""",
                ResponseEnvelope::class.java,
            ).displayMessage,
        )
    }

    @Test
    fun `no message at all reads as absent rather than empty string`() {
        assertNull(gson.fromJson("{}", ResponseEnvelope::class.java).displayMessage)
        assertNull(
            gson.fromJson("""{"errorMessage":"","reason":""}""", ResponseEnvelope::class.java)
                .displayMessage,
        )
    }

    @Test
    fun `the 4_1 diagnostic fields are parsed`() {
        val envelope = gson.fromJson(
            """
            {
              "messageId":"resp-1",
              "inResponseToMessageId":"req-1",
              "accepted":false,
              "errorCode":"validation_failed",
              "errorMessage":"Two fields are wrong",
              "exceptionId":"EXC_000042",
              "fieldErrors":{"bagCount":"must be positive","quantity":"not allowed with bagCount"},
              "serverReceivedAtUtc":"2026-07-24T08:00:00.000000Z",
              "serverSentAtUtc":"2026-07-24T08:00:00.250000Z",
              "processingDurationMs":250
            }
            """.trimIndent(),
            ResponseEnvelope::class.java,
        )

        assertEquals("EXC_000042", envelope.exceptionId)
        assertEquals(250L, envelope.processingDurationMs)
        assertEquals("2026-07-24T08:00:00.250000Z", envelope.serverSentAtUtc)
        assertEquals(2, envelope.fieldErrors?.size)
        assertEquals("must be positive", envelope.fieldErrors?.get("bagCount"))
    }

    @Test
    fun `an unknown error code passes through intact rather than failing the parse`() {
        // 4.1 adds codes we may not know yet; a code we don't recognise must stay visible.
        val envelope = gson.fromJson(
            """{"errorCode":"some_future_code"}""",
            ResponseEnvelope::class.java,
        )

        assertEquals("some_future_code", envelope.errorCode)
        assertEquals(ErrorCode("some_future_code"), ErrorCode(envelope.errorCode!!))
    }
}
