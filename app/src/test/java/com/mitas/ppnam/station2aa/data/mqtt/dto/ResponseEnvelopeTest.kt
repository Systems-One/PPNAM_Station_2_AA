package com.mitas.ppnam.station2aa.data.mqtt.dto

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseEnvelopeTest {

    private val gson = Gson()

    @Test
    fun `parses a full accepted response envelope`() {
        val json = """
            {
              "messageId": "server-generated",
              "inResponseToMessageId": "machine-start-0001",
              "schemaVersion": "4.0",
              "deviceId": "handheld_1",
              "operatorSessionId": "session-id",
              "timestampUtc": "2026-07-16T10:30:01Z",
              "correlationKey": "COL_000123",
              "accepted": true,
              "reason": null,
              "errorCode": null,
              "nextAction": "scan_same_machine_to_finish"
            }
        """.trimIndent()

        val env = gson.fromJson(json, ResponseEnvelope::class.java)

        assertEquals("machine-start-0001", env.inResponseToMessageId)
        assertEquals("4.0", env.schemaVersion)
        assertEquals("COL_000123", env.correlationKey)
        assertTrue(env.accepted)
        assertNull(env.reason)
        assertNull(env.errorCode)
        assertEquals("scan_same_machine_to_finish", env.nextAction)
    }

    @Test
    fun `parses a rejected response envelope carrying an error code`() {
        val json = """
            {
              "inResponseToMessageId": "ingredient-0001",
              "accepted": false,
              "reason": "Manager approval required.",
              "errorCode": "validation_failed",
              "nextAction": "retry_with_manager_approval"
            }
        """.trimIndent()

        val env = gson.fromJson(json, ResponseEnvelope::class.java)

        assertEquals("ingredient-0001", env.inResponseToMessageId)
        assertEquals(false, env.accepted)
        assertEquals("Manager approval required.", env.reason)
        assertEquals("validation_failed", env.errorCode)
    }

    @Test
    fun `absent fields fall back to safe defaults`() {
        val env = gson.fromJson("{}", ResponseEnvelope::class.java)

        assertEquals("", env.inResponseToMessageId)
        assertEquals(false, env.accepted)
        assertNull(env.correlationKey)
        assertNull(env.nextAction)
    }

    @Test
    fun `a response with errorCode omitted entirely parses as no error`() {
        val json = """{"messageId":"S2-1","inResponseToMessageId":"m-1","schemaVersion":"4.0","accepted":true}"""
        val env = Gson().fromJson(json, ResponseEnvelope::class.java)
        assertNull(env.errorCode)
        assertTrue(env.accepted)
    }
}
