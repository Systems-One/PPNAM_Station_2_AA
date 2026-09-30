package com.mitas.ppnam.station2aa.data.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The wire schema version and the timestamp shape every rev2.1 message must carry. A timestamp
 * whose precision drifts is invisible until it is wrong on the wire.
 */
class MqttSchemaTest {

    @Test
    fun `the wire schema version is rev2_1`() {
        assertEquals("rev2.1", MqttSchema.VERSION)
    }

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
            assertTrue("$formatted does not match the rev2.1 shape", shape.matches(formatted))
        }
    }

    @Test
    fun `a formatted timestamp still round-trips as an Instant`() {
        val original = Instant.parse("2026-07-24T08:00:00.123456Z")
        assertEquals(original, Instant.parse(MqttSchema.formatTimestamp(original)))
    }
}
