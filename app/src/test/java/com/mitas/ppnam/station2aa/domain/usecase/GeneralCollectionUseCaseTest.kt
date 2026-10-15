package com.mitas.ppnam.station2aa.domain.usecase

import com.google.gson.JsonParser
import com.mitas.ppnam.station2aa.contract.ContractFixtures
import com.mitas.ppnam.station2aa.data.mqtt.ErrorCode
import com.mitas.ppnam.station2aa.data.mqtt.FailureKind
import com.mitas.ppnam.station2aa.data.mqtt.MqttOutcome
import com.mitas.ppnam.station2aa.data.mqtt.WireJson
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2AdjustIngredientRequest
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2Adjustment
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2CaptureRequest
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2CommandResult
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2IdentifyRequest
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2Source
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2ChoiceAnswer
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2PrepareRequest
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2Snapshot
import com.mitas.ppnam.station2aa.data.mqtt.outbox.CommandOutcome
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingCommand
import com.mitas.ppnam.station2aa.data.mqtt.outbox.UnresolvedReason
import com.mitas.ppnam.station2aa.domain.model.SourceInfo
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2MachineFinishRequest
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2MachineStartRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertThrows
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
    fun `machineStart sends one outboxed machine_start for the prep`() = runTest {
        stubSend(CommandOutcome.Settled(MqttOutcome.Accepted(fixture("general_machine_start"))))
        val result = useCase.machineStart(" PREP_1 ", " MAIN-MIX-01 ")
        assertTrue(result is CollectionResult.Done)
        assertEquals(Rev2MachineStartRequest("PREP_1", "MAIN-MIX-01"), sentPayload())
        verify(mqtt, never()).request(any(), any(), any(), eq(Rev2Snapshot::class.java))
    }

    @Test
    fun `machineFinish sends the cycle id it was given and the exact scanned code`() = runTest {
        stubSend(CommandOutcome.Settled(MqttOutcome.Accepted(fixture("general_machine_finish"))))
        useCase.machineFinish("PREP_1", "main-mix-01", "CYC_1")
        assertEquals(Rev2MachineFinishRequest("PREP_1", "main-mix-01", "CYC_1"), sentPayload())
    }

    @Test
    fun `an unresolved machine command surfaces as Unresolved`() = runTest {
        stubSend(CommandOutcome.Unresolved(cmd, UnresolvedReason.RetryIdentical, null, null))
        assertTrue(useCase.machineStart("PREP_1", "M") is CollectionResult.Unresolved)
    }

    @Test
    fun `machine commands need a code and a cycle`() {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { useCase.machineStart("P", " ") } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { useCase.machineFinish("P", "M", "") } }
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

    // ---- identify ----

    private val src = Rev2Source(
        code = "TAG-RESIN", materialCode = "RESIN", name = "Resin", unit = "kg", bagSize = BigDecimal("25"),
        available = BigDecimal("100"), usable = true, reason = "",
    )

    private suspend fun stubRequest(outcome: MqttOutcome<Rev2Snapshot>) {
        whenever(mqtt.request(any(), any(), any(), eq(Rev2Snapshot::class.java))).thenReturn(outcome)
    }

    @Test
    fun `identify sends a plain request, never a command`() = runTest {
        stubRequest(MqttOutcome.Accepted(fixture("general_read_preparation").copy(source = src)))
        val result = useCase.identify(" PREP_1 ", " TAG-RESIN ") as IdentifyResult.Identified
        val payload = argumentCaptor<Any>()
        val req = argumentCaptor<String>()
        verify(mqtt).request(req.capture(), any(), payload.capture(), eq(Rev2Snapshot::class.java))
        assertEquals("rev2_general_requested", req.firstValue)
        assertEquals(Rev2IdentifyRequest("PREP_1", "TAG-RESIN"), payload.firstValue)
        assertEquals("identify", (payload.firstValue as Rev2IdentifyRequest).action)
        verify(mqtt, never()).sendCommand(any(), any(), any(), eq(Rev2Snapshot::class.java))
        assertEquals(
            SourceInfo("TAG-RESIN", "RESIN", "Resin", "kg", BigDecimal("25"), BigDecimal("100"), true, ""),
            result.source,
        )
        assertEquals("PREP_440fbd8f9cd3411e9be943a33f1a3ceb", result.snapshot.preparation?.id)
    }

    @Test
    fun `a non-positive bag size is no bag size`() = runTest {
        stubRequest(MqttOutcome.Accepted(Rev2Snapshot(source = src.copy(bagSize = BigDecimal.ZERO))))
        assertNull((useCase.identify("P", "T") as IdentifyResult.Identified).source.bagSize)
    }

    @Test
    fun `unknown_source is Unknown with the snapshot`() = runTest {
        val body = fixture("general_read_preparation").copy(
            result = Rev2CommandResult(success = false, message = "Unknown pallet.", errorCode = "unknown_source"),
        )
        stubRequest(MqttOutcome.Rejected(body, ErrorCode.REV2_REJECTED, "Unknown pallet."))
        val result = useCase.identify("P", "T") as IdentifyResult.Unknown
        assertEquals("Unknown pallet.", result.message)
        assertNotNull(result.snapshot)
    }

    @Test
    fun `unknown_source as the envelope error is Unknown`() = runTest {
        stubRequest(MqttOutcome.Rejected(null, ErrorCode("unknown_source"), null))
        val result = useCase.identify("P", "T") as IdentifyResult.Unknown
        assertNull(result.snapshot)
        assertTrue(result.message.isNotBlank())
    }

    @Test
    fun `an unsupported action falls back`() = runTest {
        for (code in listOf("action_not_allowed", "unsupported_action", "invalid_action", "unknown_action")) {
            stubRequest(MqttOutcome.Rejected(null, ErrorCode(code), "Action is not part of this process mode."))
            assertEquals(code, IdentifyResult.Unsupported(GeneralCollectionUseCase.IDENTIFY_FALLBACK, timedOut = false), useCase.identify("P", "T"))
        }
    }

    @Test
    fun `a success without a source falls back`() = runTest {
        stubRequest(MqttOutcome.Accepted(fixture("general_read_preparation")))
        assertTrue(useCase.identify("P", "T") is IdentifyResult.Unsupported)
    }

    @Test
    fun `no reply within the timeout falls back`() = runTest {
        stubRequest(MqttOutcome.NoResponse(FailureKind.Timeout))
        assertEquals(IdentifyResult.Unsupported(GeneralCollectionUseCase.IDENTIFY_FALLBACK, timedOut = true), useCase.identify("P", "T"))
    }

    @Test
    fun `not connected fails retryably, other rejections fail`() = runTest {
        stubRequest(MqttOutcome.NoResponse(FailureKind.NotConnected))
        assertEquals(true, (useCase.identify("P", "T") as IdentifyResult.Failed).retryable)
        stubRequest(MqttOutcome.NoResponse(FailureKind.MalformedResponse))
        assertEquals(false, (useCase.identify("P", "T") as IdentifyResult.Failed).retryable)
        stubRequest(MqttOutcome.Rejected(null, ErrorCode.OPERATOR_SESSION_INVALID, "Sign in again"))
        val failed = useCase.identify("P", "T") as IdentifyResult.Failed
        assertEquals("Sign in again", failed.message)
        assertEquals(false, failed.retryable)
    }

    // ---- add ingredient ----

    @Test
    fun `addIngredient sends one outboxed adjust_ingredient Add`() = runTest {
        stubSend(CommandOutcome.Settled(MqttOutcome.Accepted(fixture("general_add"))))
        val result = useCase.addIngredient(" PREP_1 ", 4, " TAG-ADD ", BigDecimal("4.000"), "  extra additive  ")
        assertTrue(result is CollectionResult.Done)
        assertEquals(
            Rev2AdjustIngredientRequest("PREP_1", 4, "extra additive", Rev2Adjustment(sourceCode = "TAG-ADD", targetQuantity = BigDecimal("4"))),
            sentPayload(),
        )
        verify(mqtt, never()).request(any(), any(), any(), eq(Rev2Snapshot::class.java))
    }

    @Test
    fun `addIngredient revision conflict is a rejection`() = runTest {
        stubSend(CommandOutcome.Settled(MqttOutcome.Rejected(fixture("general_add"), ErrorCode.COLLECTION_REVISION_CONFLICT, "changed")))
        val result = useCase.addIngredient("PREP_1", 4, "TAG-ADD", BigDecimal("4"), "r") as CollectionResult.Rejected
        assertEquals(ErrorCode.COLLECTION_REVISION_CONFLICT, result.error)
    }

    @Test
    fun `addIngredient requires a reason, a code and a positive quantity`() = runTest {
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { useCase.addIngredient("P", 1, "T", BigDecimal.ONE, "   ") } }
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { useCase.addIngredient("P", 1, "T", BigDecimal.ONE, "x".repeat(1001)) } }
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { useCase.addIngredient("P", 1, " ", BigDecimal.ONE, "r") } }
        assertThrows(IllegalArgumentException::class.java) { kotlinx.coroutines.runBlocking { useCase.addIngredient("P", 1, "T", BigDecimal.ZERO, "r") } }
    }
}
