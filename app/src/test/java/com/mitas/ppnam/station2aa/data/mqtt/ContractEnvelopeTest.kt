package com.mitas.ppnam.station2aa.data.mqtt

import com.hivemq.client.mqtt.datatypes.MqttQos
import com.mitas.ppnam.station2aa.contract.ContractFixtures
import com.mitas.ppnam.station2aa.data.identity.DeviceIdentity
import com.mitas.ppnam.station2aa.data.mqtt.dto.ResponseEnvelope
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.data.settings.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ContractEnvelopeTest {

    private fun repo(): MqttRepositoryImpl {
        val identity = mock<DeviceIdentity>()
        whenever(identity.deviceId()).thenReturn("scanner_1")
        return MqttRepositoryImpl(
            clientFactory = mock(),
            settingsRepository = mock<SettingsRepository>(),
            sessionHolder = OperatorSessionHolder(),
            deviceIdentity = identity,
        )
    }

    @Test
    fun `the contract revision is 2026-10-01 on wire schema rev2_1`() {
        assertEquals("rev2.1", MqttSchema.VERSION)
        assertEquals("2026-10-01", MqttSchema.CONTRACT_REVISION)
    }

    @Test
    fun `the response envelope carries contractRevision and requestFingerprint`() {
        val env = WireJson.gson.fromJson(ContractFixtures.text("general_add_response.json"), ResponseEnvelope::class.java)
        assertEquals("2026-10-01", env.contractRevision)
        assertEquals(64, env.requestFingerprint.length)
        assertEquals("read_saved_state", env.nextAction)
    }

    @Test
    fun `new error codes parse from the stale-decision example`() {
        val env = WireJson.gson.fromJson(
            ContractFixtures.text("general_revision_rejected_response.json"), ResponseEnvelope::class.java,
        )
        assertEquals(ErrorCode.COLLECTION_REVISION_CONFLICT, env.errorCode)
        val sealed = WireJson.gson.fromJson(
            ContractFixtures.text("general_delayed_sealed_response.json"), ResponseEnvelope::class.java,
        )
        assertEquals(ErrorCode.RECEIPT_SEALED, sealed.errorCode)
    }

    @Test
    fun `any parsed reply records the server's contract revision, even an unmatched one`() {
        val repo = repo()
        assertNull(repo.serverContractRevision.value)
        repo.handleIncomingResponse(
            "PPNAM/station_2/scanner_1/res/rev2_general_result",
            ContractFixtures.bytes("general_read_list_response.json"),
        )
        assertEquals("2026-10-01", repo.serverContractRevision.value)
    }

    @Test
    fun `the size limit counts UTF-8 bytes, not characters`() {
        // 33,000 two-byte characters: under the limit in chars, over it in bytes.
        val text = "é".repeat(33_000)
        assertThrows(OversizedPayloadException::class.java) { OutboundGuard.assertWithinSize(text) }
        OutboundGuard.assertWithinSize("é".repeat(32_768)) // exactly 65,536 bytes: allowed
    }

    @Test
    fun `responses subscribe at QoS 1 and presence at QoS 2`() {
        assertEquals(MqttQos.AT_LEAST_ONCE, MqttTopics.RESPONSE_QOS)
        assertEquals(MqttQos.EXACTLY_ONCE, MqttTopics.PRESENCE_QOS)
    }
}
