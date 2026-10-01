package com.mitas.ppnam.station2aa.data.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactTest {

    @Test
    fun `redacts every rev2_1 secret`() {
        val raw = """{"sessionId":"S1","clientProof":"S2","serverSignature":"S3",
            "clientFinalWithoutProof":"S4","salt":"S5","serverFirstMessage":"S6","serverNonce":"S7",
            "clientNonce":"S8","challengeId":"S9","password":"S10","managerPassword":"S11",
            "brokerPassword":"S12"}"""
        val out = Redact.payload(raw)
        (1..12).forEach { assertFalse("leaked S$it", out.contains("\"S$it\"")) }
    }

    @Test
    fun `redacts the session nested under data`() {
        val out = Redact.payload("""{"success":true,"data":{"session":{"sessionId":"secret-id","displayName":"Op"}}}""")
        assertFalse(out.contains("secret-id"))
        assertTrue(out.contains("Op"))
    }

    @Test
    fun `redacts case-insensitively and inside arrays`() {
        val out = Redact.payload("""{"items":[{"SESSIONID":"secret"}]}""")
        assertFalse(out.contains("secret"))
    }

    @Test
    fun `keeps non-secret fields readable`() {
        val out = Redact.payload("""{"action":"lookup","jobCard":"510019068","messageId":"m-1"}""")
        assertTrue(out.contains("510019068"))
        assertTrue(out.contains("m-1"))
    }

    @Test
    fun `unparseable input is reported, never echoed`() {
        assertEquals("<unparseable payload>", Redact.payload("password=hunter2"))
    }

    @Test
    fun `redacts a nested authorizationToken`() {
        val out = Redact.payload("""{"action":"manager_auth","data":{"authorizationToken":"tok-secret","x":1}}""")
        assertFalse(out.contains("tok-secret"))
        assertTrue(out.contains("manager_auth"))
    }
}
