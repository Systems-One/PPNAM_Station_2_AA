package com.mitas.ppnam.station2aa.data.mqtt

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RequestEnvelopeTest {

    private data class Payload(val action: String, val jobCard: String? = null, val deviceId: String? = null)

    // Nested, not local: Gson silently excludes local classes.
    private data class Leaky(val action: String, val password: String)
    private data class Huge(val action: String, val filler: String)

    private fun build(payload: Any = Payload("read"), sessionId: String? = "sess-1") =
        JsonParser.parseString(
            RequestEnvelope.build(
                gson = WireJson.gson,
                payload = payload,
                messageId = "msg-1",
                deviceId = "scanner_abc",
                sessionId = sessionId,
                timestampUtc = "2026-09-30T10:00:00.000000Z",
            )
        ).asJsonObject

    @Test
    fun `writes every rev2_1 envelope field`() {
        val obj = build()
        assertEquals("rev2.1", obj["schemaVersion"].asString)
        assertEquals("scanner_abc", obj["deviceId"].asString)
        assertEquals("msg-1", obj["messageId"].asString)
        assertEquals("2026-09-30T10:00:00.000000Z", obj["timestampUtc"].asString)
        assertEquals("sess-1", obj["sessionId"].asString)
    }

    @Test
    fun `keeps the payload's own fields`() {
        assertEquals("lookup", build(Payload("lookup", jobCard = "510019296"))["action"].asString)
        assertEquals("510019296", build(Payload("lookup", jobCard = "510019296"))["jobCard"].asString)
    }

    @Test
    fun `omits a null payload field rather than sending null`() {
        assertFalse(build(Payload("read"))
            .has("jobCard"))
    }

    @Test
    fun `omits sessionId when there is no session`() {
        assertFalse(build(sessionId = null).has("sessionId"))
    }

    @Test
    fun `omits sessionId when it is blank`() {
        assertFalse(build(sessionId = "  ").has("sessionId"))
    }

    @Test
    fun `envelope fields win over a payload field of the same name`() {
        assertEquals("scanner_abc", build(Payload("read", deviceId = "forged"))["deviceId"].asString)
    }

    @Test
    fun `never writes the retired 4_1 envelope fields`() {
        val obj = build()
        assertFalse(obj.has("operatorSessionId"))
        assertFalse(obj.has("correlationKey"))
    }

    @Test
    fun `build refuses a payload carrying a password field`() {
        org.junit.Assert.assertThrows(PlaintextCredentialException::class.java) { build(Leaky("read", "hunter2")) }
    }

    @Test
    fun `build refuses an oversized request`() {
        org.junit.Assert.assertThrows(OversizedPayloadException::class.java) {
            build(Huge("read", "x".repeat(OutboundGuard.MAX_PAYLOAD_CHARS)))
        }
    }
}
