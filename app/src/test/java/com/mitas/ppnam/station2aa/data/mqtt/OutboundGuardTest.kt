package com.mitas.ppnam.station2aa.data.mqtt

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OutboundGuardTest {

    private fun check(raw: String) = OutboundGuard.assertNoCredentialFields(JsonParser.parseString(raw))

    @Test
    fun `allows a message with no credential field`() =
        check("""{"action":"lookup","jobCard":"510019068","sessionId":"s"}""")

    @Test
    fun `rejects a top-level password`() {
        assertThrows(PlaintextCredentialException::class.java) { check("""{"password":"x"}""") }
    }

    @Test
    fun `rejects any field name containing password, whatever the case`() {
        assertThrows(PlaintextCredentialException::class.java) { check("""{"managerPASSWORD":"x"}""") }
        assertThrows(PlaintextCredentialException::class.java) { check("""{"passwordHash":"x"}""") }
    }

    @Test
    fun `rejects a credential nested at any depth`() {
        assertThrows(PlaintextCredentialException::class.java) { check("""{"a":{"b":{"password":"x"}}}""") }
    }

    @Test
    fun `rejects a credential inside an array element`() {
        assertThrows(PlaintextCredentialException::class.java) { check("""{"items":[{"u":"a"},{"password":"x"}]}""") }
    }

    @Test
    fun `allows the SCRAM proof fields`() =
        check("""{"challengeId":"c","clientFinalWithoutProof":"c=biws,r=n","clientProof":"dGVzdA=="}""")

    @Test
    fun `a value containing the word password is fine — only names are checked`() =
        check("""{"note":"password reset requested"}""")

    @Test
    fun `reports the path of the offending field`() {
        val thrown = assertThrows(PlaintextCredentialException::class.java) { check("""{"outer":{"password":"x"}}""") }
        assertEquals("outer/password", thrown.path)
    }

    @Test
    fun `a payload at the size limit passes`() =
        OutboundGuard.assertWithinSize("x".repeat(OutboundGuard.MAX_PAYLOAD_CHARS))

    @Test
    fun `a payload one character over the limit is refused`() {
        assertThrows(OversizedPayloadException::class.java) {
            OutboundGuard.assertWithinSize("x".repeat(OutboundGuard.MAX_PAYLOAD_CHARS + 1))
        }
    }
}
