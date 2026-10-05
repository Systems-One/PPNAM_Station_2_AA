package com.mitas.ppnam.station2aa.domain.usecase

import com.google.gson.JsonParser
import com.mitas.ppnam.station2aa.contract.ContractFixtures
import com.mitas.ppnam.station2aa.data.mqtt.ErrorCode
import com.mitas.ppnam.station2aa.data.mqtt.FailureKind
import com.mitas.ppnam.station2aa.data.mqtt.MqttOutcome
import com.mitas.ppnam.station2aa.data.mqtt.WireJson
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2RecoverRequest
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2Snapshot
import com.mitas.ppnam.station2aa.data.mqtt.outbox.InMemoryCommandOutbox
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingCommand
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingStatus
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

class CommandRecoveryUseCaseTest {

    private lateinit var mqtt: MqttRepository
    private lateinit var outbox: InMemoryCommandOutbox
    private lateinit var sessions: OperatorSessionHolder
    private lateinit var useCase: CommandRecoveryUseCase

    // The original capture from the committed-recovery example.
    private val command = PendingCommand(
        messageId = "example-008",
        requestType = "rev2_general_requested",
        responseType = "rev2_general_result",
        action = "capture",
        targetId = "PREP_440fbd8f9cd3411e9be943a33f1a3ceb",
        payload = ContractFixtures.text("general_capture_request.json"),
        fingerprint = "72593FAA534ABF7E39B154342A88D26B99E5F81B27A76317E24C8DD426B77BCF",
        operatorId = "OP-1",
        sessionId = "old-session",
    )

    private fun fixture(name: String): Rev2Snapshot = WireJson.gson.fromJson(
        JsonParser.parseString(ContractFixtures.text("${name}_response.json")).asJsonObject["data"], Rev2Snapshot::class.java,
    )

    @Before
    fun setup() {
        mqtt = mock()
        outbox = InMemoryCommandOutbox().apply { save(command) }
        sessions = OperatorSessionHolder().apply { set(OperatorSession("new-session", "OP-1", "Op", "Worker")) }
        useCase = CommandRecoveryUseCase(mqtt, outbox, sessions)
    }

    private suspend fun stub(outcome: MqttOutcome<Rev2Snapshot>) {
        whenever(mqtt.request(any(), any(), any(), eq(Rev2Snapshot::class.java))).thenReturn(outcome)
    }

    @Test
    fun `recover is sent in the original family with the original id and raw hash`() = runTest {
        stub(MqttOutcome.Accepted(fixture("general_recover_committed")))
        useCase.recover(command)
        val captor = argumentCaptor<Any>()
        verify(mqtt).request(eq("rev2_general_requested"), eq("rev2_general_result"), captor.capture(), eq(Rev2Snapshot::class.java))
        val sent = captor.firstValue as Rev2RecoverRequest
        assertEquals("recover", sent.action)
        assertEquals("example-008", sent.originalMessageId)
        assertEquals(command.fingerprint, sent.originalRequestFingerprint)
        assertEquals(command.targetId, sent.targetId)
    }

    @Test
    fun `committed resolves and leaves the outbox`() = runTest {
        stub(MqttOutcome.Accepted(fixture("general_recover_committed")))
        val result = useCase.recover(command) as RecoveryResult.Resolved
        assertEquals(RecoveryOutcome.Committed, result.outcome)
        assertTrue(outbox.commands.value.isEmpty())
    }

    @Test
    fun `not executed resolves as sealed`() = runTest {
        val snapshot = fixture("general_recover_not_executed")
        stub(MqttOutcome.Accepted(snapshot.copy(recovery = snapshot.recovery!!.copy(originalMessageId = "example-008"))))
        assertEquals(RecoveryOutcome.NotExecuted, (useCase.recover(command) as RecoveryResult.Resolved).outcome)
        assertTrue(outbox.commands.value.isEmpty())
    }

    @Test
    fun `a recovery reply about a different message does not resolve this one`() = runTest {
        stub(MqttOutcome.Accepted(fixture("general_recover_not_executed")))   // names example-delayed-bag
        assertTrue(useCase.recover(command) is RecoveryResult.StillUnresolved)
        assertEquals(1, outbox.commands.value.size)
    }

    @Test
    fun `owner mismatch needs a manager and is marked`() = runTest {
        stub(MqttOutcome.Rejected(null, ErrorCode.RECEIPT_OWNER_MISMATCH, "Another operator owns this receipt"))
        assertTrue(useCase.recover(command) is RecoveryResult.NeedsManager)
        assertEquals(PendingStatus.ManagerReconcile, outbox.commands.value.single().status)
    }

    @Test
    fun `no reply leaves it unresolved for another recovery`() = runTest {
        stub(MqttOutcome.NoResponse(FailureKind.Timeout))
        assertTrue(useCase.recover(command) is RecoveryResult.StillUnresolved)
        assertEquals(1, outbox.commands.value.size)
    }

    @Test
    fun `a different operator's command is not sent for recovery`() = runTest {
        sessions.set(OperatorSession("s", "OP-2", "Other", "Worker"))
        assertTrue(useCase.recover(command) is RecoveryResult.OtherOperator)
        verify(mqtt, never()).request(any(), any(), any(), eq(Rev2Snapshot::class.java))
    }
}
