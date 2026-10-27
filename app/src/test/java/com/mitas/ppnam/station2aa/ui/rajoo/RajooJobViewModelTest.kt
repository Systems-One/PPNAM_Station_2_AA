package com.mitas.ppnam.station2aa.ui.rajoo

import androidx.lifecycle.SavedStateHandle
import com.google.gson.JsonParser
import com.mitas.ppnam.station2aa.contract.ContractFixtures1021
import com.mitas.ppnam.station2aa.data.mqtt.WireJson
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2ChoiceAnswer
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2Snapshot
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingCommand
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingStatus
import com.mitas.ppnam.station2aa.data.rfid.ScanEventBus
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.domain.model.ChoicePrompt
import com.mitas.ppnam.station2aa.domain.model.PrepSnapshot
import com.mitas.ppnam.station2aa.domain.model.SourceInfo
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.repository.ServerHint
import com.mitas.ppnam.station2aa.domain.usecase.AuthUseCase
import com.mitas.ppnam.station2aa.domain.usecase.CollectionResult
import com.mitas.ppnam.station2aa.domain.usecase.CommandRecoveryUseCase
import com.mitas.ppnam.station2aa.domain.usecase.IdentifyResult
import com.mitas.ppnam.station2aa.domain.usecase.PendingCommandCoordinator
import com.mitas.ppnam.station2aa.domain.usecase.RajooUseCase
import com.mitas.ppnam.station2aa.domain.usecase.ReadResult
import com.mitas.ppnam.station2aa.domain.usecase.RecoveryResult
import com.mitas.ppnam.station2aa.domain.usecase.toPrepSnapshot
import com.mitas.ppnam.station2aa.ui.preparation.CollectStep
import com.mitas.ppnam.station2aa.ui.preparation.ResultBanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import com.mitas.ppnam.station2aa.ui.preparation.IngredientDecisionKind

class RajooJobViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var useCase: RajooUseCase
    private lateinit var coordinator: PendingCommandCoordinator
    private lateinit var mqtt: MqttRepository
    private lateinit var scanBus: ScanEventBus
    private lateinit var outbox: MutableStateFlow<List<PendingCommand>>
    private val sessionHolder = OperatorSessionHolder()

    private fun snap(name: String): PrepSnapshot = WireJson.gson.fromJson(
        JsonParser.parseString(ContractFixtures1021.text("${name}_response.json")).asJsonObject["data"],
        Rev2Snapshot::class.java,
    ).toPrepSnapshot()

    /** Loaded on RAJ-EXT-01, no session yet, OPTIONAL needs a first-session choice. */
    private val looked by lazy { snap("rajoo_lookup") }
    /** Session RAJ_f4b7… active, RESIN 0 of 600. */
    private val started by lazy { snap("rajoo_start") }
    /** Session active, RESIN 10 of 600. */
    private val captured by lazy { snap("rajoo_capture") }
    /** Session finished, everything collected. */
    private val finished by lazy { snap("rajoo_finish") }

    private val session = "RAJ_f4b7fcfd49d748fc9f9743fce950a2fa"

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        useCase = mock()
        coordinator = mock()
        mqtt = mock()
        scanBus = mock()
        outbox = MutableStateFlow(emptyList())
        sessionHolder.set(OperatorSession("sess-1", "OP-1", "Op", "Worker"))
        whenever(scanBus.events).thenReturn(MutableSharedFlow())
        whenever(mqtt.connectionState).thenReturn(MutableStateFlow(MqttConnectionState.CONNECTED))
        whenever(mqtt.serverHints).thenReturn(MutableSharedFlow<ServerHint>())
        whenever(mqtt.stationOnline).thenReturn(MutableStateFlow(true))
        whenever(mqtt.clockSkewMillis).thenReturn(MutableStateFlow<Long?>(null))
        whenever(mqtt.deviceId).thenReturn("scanner_1")
        whenever(coordinator.pending).thenReturn(outbox)
        whenever(coordinator.notices).thenReturn(MutableStateFlow<List<RecoveryResult>>(emptyList()))
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    private suspend fun open(snapshot: PrepSnapshot): RajooJobViewModel {
        whenever(useCase.read("2")).thenReturn(ReadResult.Loaded(snapshot))
        return RajooJobViewModel(
            useCase, mock<CommandRecoveryUseCase>(), coordinator, mqtt, scanBus, sessionHolder, mock<AuthUseCase>(),
            SavedStateHandle(mapOf("jobCard" to "2")),
        )
    }

    private fun source(bag: String? = "10", available: String? = "1000") = SourceInfo(
        code = "TAG-RESIN", materialCode = "RESIN", name = "Example resin", unit = "kg",
        bagSize = bag?.let(::BigDecimal), available = available?.let(::BigDecimal), usable = true, reason = "",
    )

    @Test
    fun `the Rajoo machine code starts the first session, answering catalog choices as include`() = runTest {
        val vm = open(looked)
        vm.onScan("RAJ-EXT-01")
        assertEquals(RajooConfirm.Start, vm.uiState.value.confirm)
        verify(useCase, never()).identify(any(), any())

        // Contract §7: the catalog-matched code starts unanswered and must be chosen explicitly.
        assertEquals(mapOf("OPTIONAL" to null), vm.uiState.value.choices)
        whenever(useCase.start(any(), any(), anyOrNull(), anyOrNull())).thenReturn(CollectionResult.Done(started))
        vm.confirm()
        verify(useCase, never()).start(any(), any(), anyOrNull(), anyOrNull())
        assertEquals(RajooConfirm.Start, vm.uiState.value.confirm)

        vm.onChoice("OPTIONAL", false)
        vm.confirm()
        verify(useCase).start("2", "RAJ-EXT-01", 1, listOf(Rev2ChoiceAnswer("OPTIONAL", false)))
        assertEquals(session, vm.uiState.value.job!!.activeSessionId)
        assertNull(vm.uiState.value.confirm)
    }

    @Test
    fun `a first session that excludes every ingredient is refused before sending`() = runTest {
        val prompts = listOf(ChoicePrompt("RESIN", "", ""), ChoicePrompt("OPTIONAL", "", ""))
        val vm = open(looked.copy(requiredChoices = prompts))
        vm.onScan("RAJ-EXT-01")
        vm.onChoice("RESIN", false)
        vm.onChoice("OPTIONAL", false)
        assertTrue(vm.uiState.value.startProblems.any { it.contains("Excluding every ingredient") })
        vm.confirm()
        verify(useCase, never()).start(any(), any(), anyOrNull(), anyOrNull())
        // A code Station 2 didn't ask about can't be answered.
        vm.onChoice("UNKNOWN", true)
        assertFalse("UNKNOWN" in vm.uiState.value.choices)
    }

    @Test
    fun `without a session any other code is rejected and nothing is sent`() = runTest {
        val vm = open(looked)
        vm.onScan("MAIN-MIX-01")
        assertTrue(vm.uiState.value.collect.banner is ResultBanner.Error)
        assertNull(vm.uiState.value.confirm)
        verify(useCase, never()).identify(any(), any())
    }

    @Test
    fun `a later session sends no choices`() = runTest {
        val vm = open(finished.copy(rajoo = finished.rajoo!!.copy(materials = started.rajoo!!.materials)))
        vm.onScan("RAJ-EXT-01")
        assertEquals(RajooConfirm.Start, vm.uiState.value.confirm)
        whenever(useCase.start(any(), any(), anyOrNull(), anyOrNull())).thenReturn(CollectionResult.Done(started))
        vm.confirm()
        verify(useCase).start("2", "RAJ-EXT-01", null, null)
    }

    @Test
    fun `during a session a pallet is identified, prefilled to one bag, and captured in the session`() = runTest {
        val vm = open(started)
        whenever(useCase.identify("2", "TAG-RESIN")).thenReturn(IdentifyResult.Identified(source(), looked))
        vm.onScan("TAG-RESIN")
        val step = vm.uiState.value.collect.step as CollectStep.Identified
        assertEquals("10", step.amountText)
        // The identify reply's (job-less) snapshot never replaces the job on screen.
        assertEquals(session, vm.uiState.value.job!!.activeSessionId)

        whenever(useCase.capture(any(), any(), any(), anyOrNull())).thenReturn(CollectionResult.Done(captured))
        vm.submit()
        verify(useCase).capture("2", "TAG-RESIN", session, BigDecimal("10"))
        assertEquals(CollectStep.Idle, vm.uiState.value.collect.step)
        assertEquals("Recorded 10 kg Resin", (vm.uiState.value.collect.banner as ResultBanner.Accepted).text)
    }

    @Test
    fun `scanning the Rajoo machine code during a session ends it, and once complete closes the job`() = runTest {
        val vm = open(started)
        vm.onScan("RAJ-EXT-01")
        assertEquals(RajooConfirm.Finish, vm.uiState.value.confirm)
        verify(useCase, never()).identify(any(), any())
        whenever(useCase.finish(any(), any(), any())).thenReturn(CollectionResult.Done(finished))
        vm.confirm()
        verify(useCase).finish("2", "RAJ-EXT-01", session)

        vm.onScan("RAJ-EXT-01")
        assertEquals(RajooConfirm.Close, vm.uiState.value.confirm)
        whenever(useCase.close(any(), any())).thenReturn(CollectionResult.Done(snap("rajoo_close")))
        vm.confirm()
        verify(useCase).close("2", "RAJ-EXT-01")
        assertTrue(vm.uiState.value.job!!.closed)
        vm.onScan("RAJ-EXT-01")
        assertNull(vm.uiState.value.confirm)
    }

    private val additive get() = source(available = "3").copy(code = "TAG-ADD", materialCode = "ADDITIVE", name = "Example additive")

    @Test
    fun `an off-BOM pallet can be added to the full job with a reason, then recorded`() = runTest {
        val vm = open(captured)
        whenever(useCase.identify("2", "TAG-ADD")).thenReturn(IdentifyResult.Identified(additive, looked))
        vm.onScan("TAG-ADD")
        val step = vm.uiState.value.collect.step as CollectStep.NotInMix
        assertFalse(step.capAtStock)

        // The full-job total may exceed one pallet's stock (3 kg here).
        vm.onAmountChange("5")
        vm.addToMix()
        verify(useCase, never()).addIngredient(any(), any(), any(), any(), any(), any())   // no reason yet
        vm.onReasonChange("  extra additive ")
        whenever(useCase.addIngredient(any(), any(), any(), any(), any(), any())).thenReturn(CollectionResult.Done(snap("rajoo_add")))
        vm.addToMix()
        verify(useCase).addIngredient("2", session, captured.rajoo!!.collectionRevision, "TAG-ADD", BigDecimal("5"), "extra additive")
        verify(useCase, never()).capture(any(), any(), any(), anyOrNull())
        // Now an effective row: the same pallet comes back ready to record.
        val ready = vm.uiState.value.collect.step as CollectStep.Identified
        assertEquals("ADDITIVE", ready.fit.material.code)
    }

    @Test
    fun `an off-BOM pallet can be used instead of an uncaptured ingredient`() = runTest {
        val vm = open(captured)
        whenever(useCase.identify("2", "TAG-ADD")).thenReturn(IdentifyResult.Identified(additive, looked))
        vm.onScan("TAG-ADD")
        vm.pickSubstitute("OPTIONAL")
        vm.onAmountChange("100")
        vm.onReasonChange("OPTIONAL out of stock")
        whenever(useCase.substitute(any(), any(), any(), any(), any(), any(), any())).thenReturn(CollectionResult.Done(snap("rajoo_substitute")))
        vm.addToMix()
        verify(useCase).substitute("2", session, captured.rajoo!!.collectionRevision, "OPTIONAL", "TAG-ADD",
            BigDecimal("100"), "OPTIONAL out of stock")
        verify(useCase, never()).addIngredient(any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `a row's full-job total can be lowered with a reason, but not below what is collected`() = runTest {
        val vm = open(captured)   // RESIN 10 of 600
        vm.openDecision("RESIN")
        vm.onDecisionTotal("600")
        vm.onDecisionReason("less resin")
        vm.sendDecision()
        vm.onDecisionTotal("5")
        vm.sendDecision()
        verify(useCase, never()).changeQuantity(any(), any(), any(), any(), any(), any())
        assertTrue(vm.uiState.value.decision!!.problems.first().contains("already collected"))

        vm.onDecisionTotal("580")
        assertEquals(0, BigDecimal("-20").compareTo(vm.uiState.value.decision!!.difference))
        assertEquals(0, BigDecimal("570").compareTo(vm.uiState.value.decision!!.remainingAfter))
        whenever(useCase.changeQuantity(any(), any(), any(), any(), any(), any())).thenReturn(CollectionResult.Done(snap("rajoo_less")))
        vm.sendDecision()
        verify(useCase).changeQuantity("2", session, captured.rajoo!!.collectionRevision, "RESIN", BigDecimal("580"), "less resin")
        assertNull(vm.uiState.value.decision)
        verify(useCase, never()).capture(any(), any(), any(), anyOrNull())
    }

    @Test
    fun `only an uncaptured row can be omitted`() = runTest {
        val vm = open(captured)
        vm.openDecision("RESIN")
        assertFalse(vm.uiState.value.decision!!.canOmit)
        vm.onDecisionKind(IngredientDecisionKind.Omit)
        vm.onDecisionReason("r")
        vm.sendDecision()
        verify(useCase, never()).omit(any(), any(), any(), any(), any())
        vm.cancelDecision()

        vm.openDecision("OPTIONAL")
        vm.onDecisionKind(IngredientDecisionKind.Omit)
        vm.onDecisionReason("not needed")
        whenever(useCase.omit(any(), any(), any(), any(), any())).thenReturn(CollectionResult.Done(snap("rajoo_omit")))
        vm.sendDecision()
        verify(useCase).omit("2", session, captured.rajoo!!.collectionRevision, "OPTIONAL", "not needed")
    }

    @Test
    fun `decisions need the active session`() = runTest {
        val vm = open(looked)
        vm.openDecision("RESIN")
        assertNull(vm.uiState.value.decision)
    }

    @Test
    fun `an unanswered Rajoo command for this job blocks scanning and finishing`() = runTest {
        val vm = open(started)
        outbox.value = listOf(PendingCommand(
            messageId = "m-1", requestType = RajooUseCase.REQUEST, responseType = RajooUseCase.RESPONSE,
            action = "capture", targetId = "2", payload = "{}", operatorId = "OP-1", sessionId = "other",
            status = PendingStatus.Unresolved,
        ))
        assertFalse(vm.uiState.value.collect.canScan)
        vm.onScan("TAG-RESIN")
        verify(useCase, never()).identify(any(), any())
        vm.onScan("RAJ-EXT-01")
        assertNull(vm.uiState.value.confirm)   // ending the session waits too
    }

    @Test
    fun `a General command with the same target id does not block the Rajoo job`() = runTest {
        val vm = open(started)
        outbox.value = listOf(PendingCommand(
            messageId = "m-1", requestType = "rev2_general_requested", responseType = "rev2_general_result",
            action = "prepare", targetId = "2", payload = "{}", operatorId = "OP-1", sessionId = "other",
            status = PendingStatus.Unresolved,
        ))
        assertTrue(vm.uiState.value.collect.canScan)
    }

    @Test
    fun `Rajoo identify of an unknown pallet shows not recognised`() = runTest {
        val vm = open(started)
        whenever(useCase.identify(eq("2"), any())).thenReturn(IdentifyResult.Unknown("No pallet.", null))
        vm.onScan("NOPE")
        assertEquals(CollectStep.Unknown("No pallet."), vm.uiState.value.collect.step)
    }
}
