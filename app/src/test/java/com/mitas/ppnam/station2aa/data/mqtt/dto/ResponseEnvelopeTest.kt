package com.mitas.ppnam.station2aa.data.mqtt.dto

import com.mitas.ppnam.station2aa.data.mqtt.ErrorCode
import com.mitas.ppnam.station2aa.data.mqtt.WireJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseEnvelopeTest {

    private fun parse(json: String) = WireJson.gson.fromJson(json, ResponseEnvelope::class.java)

    @Test
    fun `parses every rev2_1 reply field`() {
        val e = parse(
            """{"schemaVersion":"rev2.1","deviceId":"scanner_1","inResponseToMessageId":"m-1",
               "receivedAtUtc":"2026-09-30T10:00:00.000000Z","sentAtUtc":"2026-09-30T10:00:00.012500Z",
               "durationMs":12.5,"success":false,"error":"rev2_rejected",
               "operatorMessage":"Load a General Mixing JC first.","nextAction":"Correct the request.",
               "data":{"jobs":[]}}"""
        )
        assertEquals("m-1", e.inResponseToMessageId)
        assertEquals("2026-09-30T10:00:00.012500Z", e.sentAtUtc)
        assertEquals(12.5, e.durationMs!!, 0.0)
        assertFalse(e.success)
        assertEquals(ErrorCode.REV2_REJECTED, e.errorCode)
        assertEquals("Load a General Mixing JC first.", e.displayMessage)
    }

    @Test
    fun `an empty error on success reads as no error code`() {
        val e = parse("""{"success":true,"error":"","operatorMessage":"Current progress."}""")
        assertTrue(e.success)
        assertNull(e.errorCode)
    }

    @Test
    fun `a blank operator message reads as absent`() {
        assertNull(parse("""{"success":false,"operatorMessage":"  "}""").displayMessage)
    }

    @Test
    fun `parses a push's own fields`() {
        val e = parse(
            """{"schemaVersion":"rev2.1","deviceId":"scanner_1","messageId":"rev2-preparations-1",
               "timestampUtc":"2026-09-30T10:00:00.000000Z","mode":"General",
               "reason":"preparation_created","nextAction":"read"}"""
        )
        assertEquals("rev2-preparations-1", e.messageId)
        assertEquals("", e.inResponseToMessageId)
        assertEquals("General", e.mode)
        assertEquals("preparation_created", e.reason)
    }

    @Test
    fun `explicit nulls fall back to defaults`() {
        val e = parse("""{"error":null,"operatorMessage":null,"inResponseToMessageId":null}""")
        assertEquals("", e.error)
        assertEquals("", e.operatorMessage)
        assertEquals("", e.inResponseToMessageId)
    }
}
