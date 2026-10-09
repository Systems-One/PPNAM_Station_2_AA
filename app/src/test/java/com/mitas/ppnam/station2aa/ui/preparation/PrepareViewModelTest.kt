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
import com.mitas.ppnam.station2aa.domain.usecase.GeneralCollectionUseCase
import com.mitas.ppnam.station2aa.domain.usecase.ReadResult
import com.mitas.ppnam.station2aa.domain.usecase.toPrepSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
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
        sessionHolder = OperatorSessionHolder()
        sessionHolder.set(OperatorSession("sess-1", "OP-1", "Op", "Worker"))
        hints = MutableSharedFlow(extraBufferCapacity = 8)
        whenever(mqtt.connectionState).thenReturn(MutableStateFlow(MqttConnectionState.CONNECTED))
        whenever(mqtt.serverHints).thenReturn(hints)
        whenever(mqtt.stationOnline).thenReturn(MutableStateFlow(true))
        whenever(mqtt.clockSkewMillis).thenReturn(MutableStateFlow<Long?>(null))
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    private suspend fun newVm(): PrepareViewModel {
        whenever(useCase.read("1")).thenReturn(ReadResult.Loaded(jobSnapshot))
        return PrepareViewModel(useCase, outbox, sessionHolder, mqtt, SavedStateHandle(mapOf("jobCard" to "1")))
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
        val vm = newVm()
        advanceUntilIdle()
        assertEquals(cmd, vm.uiState.value.pending)
        vm.fillAll()
        vm.submit()
        advanceUntilIdle()
        verify(useCase, never()).prepare(any(), any(), anyOrNull(), any(), anyOrNull())
    }

    @Test
    fun `an unresolved prepare from another session is not offered here`() = runTest {
        outbox.save(pendingPrepare(sessionId = "old"))
        val vm = newVm()
        advanceUntilIdle()
        assertNull(vm.uiState.value.pending)
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
}
