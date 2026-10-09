package com.mitas.ppnam.station2aa.domain.usecase

import com.google.gson.JsonParser
import com.mitas.ppnam.station2aa.contract.ContractFixtures
import com.mitas.ppnam.station2aa.data.mqtt.ErrorCode
import com.mitas.ppnam.station2aa.data.mqtt.FailureKind
import com.mitas.ppnam.station2aa.data.mqtt.MqttOutcome
import com.mitas.ppnam.station2aa.data.mqtt.WireJson
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2CaptureRequest
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2ChoiceAnswer
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2PrepareRequest
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2Snapshot
import com.mitas.ppnam.station2aa.data.mqtt.outbox.CommandOutcome
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingCommand
import com.mitas.ppnam.station2aa.data.mqtt.outbox.UnresolvedReason
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.IOException
import java.math.BigDecimal

class GeneralCollectionUseCaseTest {

    private lateinit var mqtt: MqttRepository
    private lateinit var useCase: GeneralCollectionUseCase

    private val cmd = PendingCommand(
        messageId = "m-1",
        requestType = "rev2_general_requested",
        responseType = "rev2_general_result",
        action = "capture",
        targetId = "PREP_1",
        payload = "{}",
        fingerprint = "FP",
        operatorId = "OP-1",
        sessionId = "s",
    )

    private fun fixture(name: String): Rev2Snapshot = WireJson.gson.fromJson(
        JsonParser.parseString(ContractFixtures.text("${name}_response.json")).asJsonObject["data"], Rev2Snapshot::class.java,
    )

    @Before
    fun setup() {
        mqtt = mock()
        useCase = GeneralCollectionUseCase(mqtt)
    }

    private suspend fun stubSend(outcome: CommandOutcome<Rev2Snapshot>) {
        whenever(mqtt.sendCommand(any(), any(), any(), eq(Rev2Snapshot::class.java))).thenReturn(outcome)
    }

    private suspend fun sentPayload(): Any {
        val payload = argumentCaptor<Any>()
        verify(mqtt).sendCommand(any(), any(), payload.capture(), eq(Rev2Snapshot::class.java))
        return payload.firstValue
    }

    @Test
    fun `capture sends one command with the code and an omitted quantity`() = runTest {
        stubSend(CommandOutcome.Settled(MqttOutcome.Accepted(fixture("general_capture"))))
        val result = useCase.capture("PREP_1", "TAG-1", null)
        assertTrue(result is CollectionResult.Done)
        val req = argumentCaptor<String>()
        val res = argumentCaptor<String>()
        val payload = argumentCaptor<Any>()
        verify(mqtt).sendCommand(req.capture(), res.capture(), payload.capture(), eq(Rev2Snapshot::class.java))
        assertEquals("rev2_general_requested", req.firstValue)
        assertEquals("rev2_general_result", res.firstValue)
        assertEquals(Rev2CaptureRequest("PREP_1", "TAG-1", null), payload.firstValue)
        verify(mqtt, never()).request(any(), any(), any(), eq(Rev2Snapshot::class.java))
    }

    @Test
    fun `a fraction is normalised before sending`() = runTest {
        stubSend(CommandOutcome.Settled(MqttOutcome.Accepted(fixture("general_capture"))))
        useCase.capture("P", "T", BigDecimal("2.500"))
        assertEquals("2.5", (sentPayload() as Rev2CaptureRequest).quantity.toString())
    }

    @Test
    fun `rev2_rejected keeps the snapshot and the server message`() = runTest {
        val msg = "This material is not outstanding on this preparation."
        stubSend(CommandOutcome.Settled(MqttOutcome.Rejected(fixture("general_capture"), ErrorCode.REV2_REJECTED, msg)))
        val result = useCase.capture("PREP_1", "TAG-1", null) as CollectionResult.Rejected
        assertEquals(msg, result.message)
        assertEquals(ErrorCode.REV2_REJECTED, result.error)
        assertNotNull(result.snapshot)
    }

    @Test
    fun `receipt_sealed is a definite rejection`() = runTest {
        stubSend(CommandOutcome.Settled(MqttOutcome.Rejected(fixture("general_capture"), ErrorCode.RECEIPT_SEALED, "sealed")))
        val result = useCase.confirm("PREP_1") as CollectionResult.Rejected
        assertEquals(ErrorCode.RECEIPT_SEALED, result.error)
    }

    @Test
    fun `no reply is unresolved with the saved command`() = runTest {
        stubSend(CommandOutcome.Unresolved(cmd, UnresolvedReason.RetryIdentical, null, null))
        assertEquals(
            CollectionResult.Unresolved(cmd, UnresolvedReason.RetryIdentical, "Station 2 has not confirmed this yet. Don't repeat it — retry."),
            useCase.capture("PREP_1", "TAG-1", null),
        )
    }

    @Test
    fun `not connected sends nothing`() = runTest {
        stubSend(CommandOutcome.Settled(MqttOutcome.NoResponse(FailureKind.NotConnected)))
        assertEquals(CollectionResult.NotSent("Not connected — nothing was sent"), useCase.capture("PREP_1", "TAG-1", null))
    }

    @Test
    fun `a failed save sends nothing`() = runTest {
        stubSend(CommandOutcome.NotSaved(IOException()))
        assertEquals(
            CollectionResult.NotSent("The scanner couldn't save this, so nothing was sent. Try again."),
            useCase.capture("PREP_1", "TAG-1", null),
        )
    }

    @Test
    fun `retry republishes via retryCommand only`() = runTest {
        whenever(mqtt.retryCommand(eq(cmd), eq(Rev2Snapshot::class.java)))
            .thenReturn(CommandOutcome.Settled(MqttOutcome.Accepted(fixture("general_capture"))))
        assertTrue(useCase.retry(cmd) is CollectionResult.Done)
        verify(mqtt, never()).sendCommand(any(), any(), any(), eq(Rev2Snapshot::class.java))
    }

    @Test
    fun `retry of a settled command is AlreadySettled`() = runTest {
        whenever(mqtt.retryCommand(eq(cmd), eq(Rev2Snapshot::class.java))).thenReturn(CommandOutcome.NoLongerPending("m"))
        assertEquals(CollectionResult.AlreadySettled("m"), useCase.retry(cmd))
    }

    @Test
    fun `prepare sends the explicit answers and revision`() = runTest {
        stubSend(CommandOutcome.Settled(MqttOutcome.Accepted(fixture("general_capture"))))
        useCase.prepare("1", 4, 1, listOf(Rev2ChoiceAnswer("OPTIONAL", false)), "r")
        assertEquals(Rev2PrepareRequest("1", 4, 1, listOf(Rev2ChoiceAnswer("OPTIONAL", false)), "r"), sentPayload())
    }

    @Test
    fun `prepare trims a blank reason to null`() = runTest {
        stubSend(CommandOutcome.Settled(MqttOutcome.Accepted(fixture("general_capture"))))
        useCase.prepare("1", 4, 1, emptyList(), "   ")
        assertNull((sentPayload() as Rev2PrepareRequest).reason)
    }

    @Test
    fun `read of a PREP returns its snapshot`() = runTest {
        val snap = fixture("general_read_preparation")
        whenever(mqtt.request(any(), any(), any(), eq(Rev2Snapshot::class.java))).thenReturn(MqttOutcome.Accepted(snap))
        val result = useCase.read("PREP_440fbd8f9cd3411e9be943a33f1a3ceb") as ReadResult.Loaded
        assertEquals("PREP_440fbd8f9cd3411e9be943a33f1a3ceb", result.snapshot.preparation?.id)
    }

    @Test
    fun `read of an unknown target fails without a crash`() = runTest {
        val msg = "The selected target was not found."
        whenever(mqtt.request(any(), any(), any(), eq(Rev2Snapshot::class.java)))
            .thenReturn(MqttOutcome.Rejected(fixture("general_read_preparation"), ErrorCode.REV2_REJECTED, msg))
        val result = useCase.read("NOPE") as ReadResult.Failed
        assertEquals(msg, result.message)
        assertNotNull(result.snapshot)
        assertEquals(false, result.retryable)
    }
}
