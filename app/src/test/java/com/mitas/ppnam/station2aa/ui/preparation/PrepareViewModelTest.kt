package com.mitas.ppnam.station2aa.ui.preparation

import androidx.lifecycle.SavedStateHandle
import com.google.gson.JsonParser
import com.mitas.ppnam.station2aa.contract.ContractFixtures
import com.mitas.ppnam.station2aa.data.mqtt.ErrorCode
import com.mitas.ppnam.station2aa.data.mqtt.WireJson
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2ChoiceAnswer
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2Snapshot
import com.mitas.ppnam.station2aa.data.mqtt.outbox.InMemoryCommandOutbox
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingCommand
import com.mitas.ppnam.station2aa.data.mqtt.outbox.UnresolvedReason
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.domain.model.PrepSnapshot
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.repository.ServerHint
import com.mitas.ppnam.station2aa.domain.usecase.CollectionResult
import com.mitas.ppnam.station2aa.domain.usecase.CommandRecoveryUseCase
import com.mitas.ppnam.station2aa.domain.usecase.RecoveryOutcome
import com.mitas.ppnam.station2aa.domain.usecase.RecoveryResult
import com.mitas.ppnam.station2aa.domain.usecase.GeneralCollectionUseCase
import com.mitas.ppnam.station2aa.domain.usecase.ReadResult
import com.mitas.ppnam.station2aa.domain.usecase.toPrepSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class PrepareViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var useCase: GeneralCollectionUseCase
    private lateinit var mqtt: MqttRepository
    private lateinit var outbox: InMemoryCommandOutbox
    private lateinit var sessionHolder: OperatorSessionHolder
    private lateinit var hints: MutableSharedFlow<ServerHint>
    private lateinit var recovery: CommandRecoveryUseCase
    private lateinit var connection: MutableStateFlow<MqttConnectionState>

    private fun snapshot(name: String): PrepSnapshot {
        val data = JsonParser.parseString(ContractFixtures.text("${name}_response.json")).asJsonObject["data"]
        return WireJson.gson.fromJson(data, Rev2Snapshot::class.java).toPrepSnapshot()
    }

    private val jobSnapshot get() = snapshot("general_read_job")

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        useCase = mock()
        mqtt = mock()
        outbox = InMemoryCommandOutbox()
        recovery = mock()
        sessionHolder = OperatorSessionHolder()
        sessionHolder.set(OperatorSession("sess-1", "OP-1", "Op", "Worker"))
        hints = MutableSharedFlow(extraBufferCapacity = 8)
        connection = MutableStateFlow(MqttConnectionState.CONNECTED)
        whenever(mqtt.connectionState).thenReturn(connection)
        whenever(mqtt.serverHints).thenReturn(hints)
        whenever(mqtt.stationOnline).thenReturn(MutableStateFlow(true))
        whenever(mqtt.clockSkewMillis).thenReturn(MutableStateFlow<Long?>(null))
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    private suspend fun newVm(): PrepareViewModel {
        whenever(useCase.read("1")).thenReturn(ReadResult.Loaded(jobSnapshot))
        return PrepareViewModel(useCase, outbox, sessionHolder, mqtt, recovery, SavedStateHandle(mapOf("jobCard" to "1")))
    }

    private fun pendingPrepare(sessionId: String = "sess-1") = PendingCommand(
        messageId = "m-1", requestType = "rev2_general_requested", responseType = "rev2_general_result",
        action = "prepare", targetId = "1", payload = "{}", fingerprint = "FP", operatorId = "OP-1",
        sessionId = sessionId,
    )

    private fun PrepareViewModel.fillAll() {
        onAnswer("OPTIONAL", false)
        onMixCount("4")
    }

    @Test
    fun `opening reads the job fresh even if detail was on screen`() = runTest {
        val vm = newVm()
        advanceUntilIdle()
        verify(useCase, times(1)).read("1")
        assertNotNull(vm.uiState.value.form)
        assertEquals(false, vm.uiState.value.loading)
    }

    @Test
    fun `submit is impossible until every code is answered`() = runTest {
        val vm = newVm()
        advanceUntilIdle()
        vm.onMixCount("4")
        vm.submit()
        advanceUntilIdle()
        verify(useCase, never()).prepare(any(), any(), anyOrNull(), any(), anyOrNull())
    }

    @Test
    fun `submit sends the explicit answers and the read's revision`() = runTest {
        val vm = newVm()
        advanceUntilIdle()
        whenever(useCase.prepare(any(), any(), anyOrNull(), any(), anyOrNull()))
            .thenReturn(CollectionResult.NotSent("Not connected"))
        vm.fillAll()
        vm.submit()
        advanceUntilIdle()
        verify(useCase).prepare("1", 4, 1, listOf(Rev2ChoiceAnswer("OPTIONAL", false)), null)
    }

    @Test
    fun `success navigates to the new PREP`() = runTest {
        val vm = newVm()
        advanceUntilIdle()
        whenever(useCase.prepare(any(), any(), anyOrNull(), any(), anyOrNull()))
            .thenReturn(CollectionResult.Done(snapshot("general_prepare")))
        vm.fillAll()
        vm.submit()
        advanceUntilIdle()
        assertEquals("PREP_440fbd8f9cd3411e9be943a33f1a3ceb", vm.navigateToPrep.first())
    }

    @Test
    fun `a rejection re-applies the snapshot and keeps the answers`() = runTest {
        val vm = newVm()
        advanceUntilIdle()
        val base = jobSnapshot
        val detail = base.lookup.detail!!
        val fresh = base.copy(
            lookup = base.lookup.copy(
                detail = detail.copy(mixProgress = detail.mixProgress!!.copy(availableToPrepareMixes = 52)),
            ),
        )
        val msg = "Choose a positive whole number up to 52"
        whenever(useCase.prepare(any(), any(), anyOrNull(), any(), anyOrNull()))
            .thenReturn(CollectionResult.Rejected(ErrorCode.REV2_REJECTED, msg, fresh))
        vm.fillAll()
        vm.submit()
        advanceUntilIdle()
        val state = vm.uiState.value
        assertEquals(52, state.form!!.available)
        assertEquals(false, state.form!!.answers["OPTIONAL"])
        assertEquals("4", state.form!!.mixCountText)
        assertEquals(msg, state.message)
        assertEquals(false, state.sending)
    }

    @Test
    fun `an unresolved prepare is shown with Retry, and Retry republishes it`() = runTest {
        val vm = newVm()
        advanceUntilIdle()
        val cmd = pendingPrepare()
        whenever(useCase.prepare(any(), any(), anyOrNull(), any(), anyOrNull()))
            .thenReturn(CollectionResult.Unresolved(cmd, UnresolvedReason.RetryIdentical, "not confirmed"))
        whenever(useCase.retry(cmd)).thenReturn(CollectionResult.Done(snapshot("general_prepare")))
        vm.fillAll()
        vm.submit()
        advanceUntilIdle()
        assertEquals(cmd, vm.uiState.value.pending)
        vm.retryPending()
        advanceUntilIdle()
        verify(useCase).retry(cmd)
        verify(useCase, times(1)).prepare(any(), any(), anyOrNull(), any(), anyOrNull())
        assertNull(vm.uiState.value.pending)
    }

    @Test
    fun `opening with a same-session unresolved prepare for this JC offers Retry instead of a new form submit`() = runTest {
        val cmd = pendingPrepare()
        outbox.save(cmd)
        whenever(useCase.retry(cmd)).thenReturn(CollectionResult.Unresolved(cmd, UnresolvedReason.RetryIdentical, "not confirmed"))
        val vm = newVm()
        advanceUntilIdle()
        assertEquals(cmd, vm.uiState.value.pending)
        vm.fillAll()
        vm.submit()
        advanceUntilIdle()
        verify(useCase, never()).prepare(any(), any(), anyOrNull(), any(), anyOrNull())
    }

    @Test
    fun `an earlier-session unresolved prepare blocks submit, is not retried and offers no Retry`() = runTest {
        outbox.save(pendingPrepare(sessionId = "old"))
        val vm = newVm()
        advanceUntilIdle()
        assertNull(vm.uiState.value.pending)
        assertNotNull(vm.uiState.value.blocking)
        vm.fillAll()
        vm.submit()
        advanceUntilIdle()
        verify(useCase, never()).prepare(any(), any(), anyOrNull(), any(), anyOrNull())
        verify(useCase, never()).retry(any())
    }

    @Test
    fun `a prepare marked ManagerReconcile keeps submit blocked`() = runTest {
        val cmd = pendingPrepare()
        outbox.save(cmd)
        whenever(useCase.retry(cmd)).thenReturn(CollectionResult.Unresolved(cmd, UnresolvedReason.RetryIdentical, "x"))
        val vm = newVm()
        advanceUntilIdle()
        outbox.markManagerReconcile("m-1")
        advanceUntilIdle()
        val state = vm.uiState.value
        assertNull(state.pending)
        assertNotNull(state.blocking)
        assertTrue(state.managerHold)
        vm.fillAll()
        vm.submit()
        advanceUntilIdle()
        verify(useCase, never()).prepare(any(), any(), anyOrNull(), any(), anyOrNull())
    }

    @Test
    fun `reconnect retries this session's unresolved prepare once, then reads`() = runTest {
        val vm = newVm()
        advanceUntilIdle()
        val cmd = pendingPrepare()
        outbox.save(cmd)
        whenever(useCase.retry(cmd)).thenReturn(CollectionResult.Unresolved(cmd, UnresolvedReason.RetryIdentical, "x"))
        connection.value = MqttConnectionState.DISCONNECTED
        advanceUntilIdle()
        connection.value = MqttConnectionState.CONNECTED
        advanceUntilIdle()
        verify(useCase, times(1)).retry(cmd)
        verify(useCase, times(2)).read("1")
        assertNotNull(vm.uiState.value.pending)
    }

    @Test
    fun `a rejection without a snapshot re-reads`() = runTest {
        val vm = newVm()
        advanceUntilIdle()
        whenever(useCase.prepare(any(), any(), anyOrNull(), any(), anyOrNull()))
            .thenReturn(CollectionResult.Rejected(ErrorCode.RECEIPT_SEALED, "sealed", null))
        vm.fillAll()
        vm.submit()
        advanceUntilIdle()
        verify(useCase, times(2)).read("1")
        assertEquals("sealed", vm.uiState.value.message)
    }

    @Test
    fun `a message_id_conflict is recovered at once instead of offering Retry`() = runTest {
        val vm = newVm()
        advanceUntilIdle()
        val cmd = pendingPrepare()
        whenever(useCase.prepare(any(), any(), anyOrNull(), any(), anyOrNull()))
            .thenReturn(CollectionResult.Unresolved(cmd, UnresolvedReason.Recover, "checking"))
        whenever(recovery.recover(cmd)).thenReturn(RecoveryResult.StillUnresolved(cmd, "Still checking"))
        vm.fillAll()
        vm.submit()
        advanceUntilIdle()
        verify(recovery).recover(cmd)
        verify(useCase, never()).retry(any())
        assertNull(vm.uiState.value.pending)
        assertNotNull(vm.uiState.value.blocking)
        assertEquals(
            "prepare 1: still unresolved — Still checking. It is checked again when the scanner reconnects or you tap Check again.",
            vm.uiState.value.message,
        )
    }

    @Test
    fun `a background re-read keeps the message on screen`() = runTest {
        val vm = newVm()
        advanceUntilIdle()
        whenever(useCase.prepare(any(), any(), anyOrNull(), any(), anyOrNull()))
            .thenReturn(CollectionResult.Rejected(ErrorCode.REV2_REJECTED, "Try fewer", jobSnapshot))
        vm.fillAll()
        vm.submit()
        advanceUntilIdle()
        hints.tryEmit(ServerHint("General", "preparation_created"))
        advanceUntilIdle()
        assertEquals("Try fewer", vm.uiState.value.message)
    }

    @Test
    fun `double tap sends once`() = runTest {
        val vm = newVm()
        advanceUntilIdle()
        whenever(useCase.prepare(any(), any(), anyOrNull(), any(), anyOrNull())).doSuspendableAnswer {
            delay(1000)
            CollectionResult.NotSent("Not connected")
        }
        vm.fillAll()
        vm.submit()
        vm.submit()
        advanceUntilIdle()
        verify(useCase, times(1)).prepare(any(), any(), anyOrNull(), any(), anyOrNull())
    }

    @Test
    fun `a hint while sending does not re-read`() = runTest {
        val vm = newVm()
        advanceUntilIdle()
        whenever(useCase.prepare(any(), any(), anyOrNull(), any(), anyOrNull())).doSuspendableAnswer {
            delay(1000)
            CollectionResult.NotSent("Not connected")
        }
        vm.fillAll()
        vm.submit()
        dispatcher.scheduler.runCurrent()   // the send is now in flight
        hints.tryEmit(ServerHint("General", "preparation_created"))
        dispatcher.scheduler.runCurrent()
        verify(useCase, times(1)).read("1")
        advanceUntilIdle()
    }

    @Test
    fun `a hint when idle re-reads and keeps the answers`() = runTest {
        val vm = newVm()
        advanceUntilIdle()
        vm.fillAll()
        hints.tryEmit(ServerHint("General", "preparation_created"))
        advanceUntilIdle()
        verify(useCase, times(2)).read("1")
        assertEquals(false, vm.uiState.value.form!!.answers["OPTIONAL"])
        assertTrue(vm.uiState.value.form!!.problems.isEmpty())
    }

    // ---- final review fixes ----

    private fun rev2(name: String): Rev2Snapshot = WireJson.gson.fromJson(
        JsonParser.parseString(ContractFixtures.text("${name}_response.json")).asJsonObject["data"],
        Rev2Snapshot::class.java,
    )

    @Test
    fun `a prepare that throws resets sending and shows the message`() = runTest {
        val vm = newVm()
        advanceUntilIdle()
        whenever(useCase.prepare(any(), any(), anyOrNull(), any(), anyOrNull())).thenThrow(IllegalStateException("boom"))
        vm.fillAll()
        vm.submit()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.sending)
        assertTrue(vm.uiState.value.message!!.contains("boom"))
    }

    @Test
    fun `a retry that throws resets sending and shows the message`() = runTest {
        val cmd = pendingPrepare()
        outbox.save(cmd)
        whenever(useCase.retry(cmd))
            .thenThrow(IllegalStateException("boom"))
            .thenReturn(CollectionResult.Unresolved(cmd, UnresolvedReason.RetryIdentical, "x"))
        val vm = newVm()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.sending)
        assertTrue(vm.uiState.value.message!!.contains("boom"))
        // The command is still retryable by hand.
        vm.retryPending()
        advanceUntilIdle()
        verify(useCase, times(2)).retry(cmd)
    }

    @Test
    fun `a read that throws stops loading and shows the message`() = runTest {
        whenever(useCase.read("1")).thenThrow(IllegalStateException("boom"))
        val vm = PrepareViewModel(useCase, outbox, sessionHolder, mqtt, recovery, SavedStateHandle(mapOf("jobCard" to "1")))
        advanceUntilIdle()
        assertFalse(vm.uiState.value.loading)
        assertTrue(vm.uiState.value.message!!.contains("boom"))
    }

    @Test
    fun `a committed prepare recovery opens the PREP it made and says it was not repeated`() = runTest {
        val vm = newVm()
        advanceUntilIdle()
        val cmd = pendingPrepare()
        whenever(useCase.prepare(any(), any(), anyOrNull(), any(), anyOrNull()))
            .thenReturn(CollectionResult.Unresolved(cmd, UnresolvedReason.Recover, "checking"))
        val result = RecoveryResult.Resolved(cmd, RecoveryOutcome.Committed, "Prepared", rev2("general_recover_committed"))
        whenever(recovery.recover(cmd)).thenReturn(result)
        val routes = mutableListOf<String>()
        val job = launch { vm.navigateToPrep.toList(routes) }
        vm.fillAll()
        vm.submit()
        advanceUntilIdle()
        assertEquals(listOf("PREP_440fbd8f9cd3411e9be943a33f1a3ceb"), routes)
        assertEquals("prepare 1: Station 2 had already done this. It was not repeated.", vm.uiState.value.message)
        verify(useCase, times(2)).read("1")
        verify(useCase, times(1)).prepare(any(), any(), anyOrNull(), any(), anyOrNull())
        job.cancel()
    }

    @Test
    fun `a committed prepare recovery without a PREP id shows the notice and re-reads`() = runTest {
        val vm = newVm()
        advanceUntilIdle()
        val cmd = pendingPrepare()
        whenever(useCase.prepare(any(), any(), anyOrNull(), any(), anyOrNull()))
            .thenReturn(CollectionResult.Unresolved(cmd, UnresolvedReason.Recover, "checking"))
        whenever(recovery.recover(cmd)).thenReturn(RecoveryResult.Resolved(cmd, RecoveryOutcome.Committed, "Prepared", Rev2Snapshot()))
        val routes = mutableListOf<String>()
        val job = launch { vm.navigateToPrep.toList(routes) }
        vm.fillAll()
        vm.submit()
        advanceUntilIdle()
        assertTrue(routes.isEmpty())
        assertEquals("prepare 1: Station 2 had already done this. It was not repeated.", vm.uiState.value.message)
        verify(useCase, times(2)).read("1")
        job.cancel()
    }

    @Test
    fun `reconnect without a session neither retries nor reads`() = runTest {
        newVm()
        advanceUntilIdle()
        outbox.save(pendingPrepare())
        sessionHolder.clear()
        connection.value = MqttConnectionState.DISCONNECTED
        advanceUntilIdle()
        connection.value = MqttConnectionState.CONNECTED
        advanceUntilIdle()
        verify(useCase, never()).retry(any())
        verify(useCase, times(1)).read("1")
    }
}
