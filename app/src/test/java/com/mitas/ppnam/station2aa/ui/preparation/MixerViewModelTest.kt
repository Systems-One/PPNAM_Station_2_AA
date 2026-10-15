package com.mitas.ppnam.station2aa.ui.preparation

import androidx.lifecycle.SavedStateHandle
import com.mitas.ppnam.station2aa.data.mqtt.ErrorCode
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingCommand
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingStatus
import com.mitas.ppnam.station2aa.data.mqtt.outbox.UnresolvedReason
import com.mitas.ppnam.station2aa.data.rfid.ScanEvent
import com.mitas.ppnam.station2aa.data.rfid.ScanEventBus
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.domain.model.JobLookupSnapshot
import com.mitas.ppnam.station2aa.domain.model.Machine
import com.mitas.ppnam.station2aa.domain.model.MachineKind
import com.mitas.ppnam.station2aa.domain.model.MachineScan
import com.mitas.ppnam.station2aa.domain.model.PrepSnapshot
import com.mitas.ppnam.station2aa.domain.model.PrepStage
import com.mitas.ppnam.station2aa.domain.model.Preparation
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.repository.ServerHint
import com.mitas.ppnam.station2aa.domain.usecase.AuthUseCase
import com.mitas.ppnam.station2aa.domain.usecase.CollectionResult
import com.mitas.ppnam.station2aa.domain.usecase.CommandRecoveryUseCase
import com.mitas.ppnam.station2aa.domain.usecase.GeneralCollectionUseCase
import com.mitas.ppnam.station2aa.domain.usecase.PendingCommandCoordinator
import com.mitas.ppnam.station2aa.domain.usecase.ReadResult
import com.mitas.ppnam.station2aa.domain.usecase.RecoveryResult
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class MixerViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var useCase: GeneralCollectionUseCase
    private lateinit var scans: MutableSharedFlow<ScanEvent>
    private lateinit var hints: MutableSharedFlow<ServerHint>
    private lateinit var outbox: MutableStateFlow<List<PendingCommand>>
    private lateinit var sessionHolder: OperatorSessionHolder
    private lateinit var coordinator: PendingCommandCoordinator
    private lateinit var mqtt: MqttRepository
    private lateinit var scanBus: ScanEventBus

    private val prepId = "PREP_440fbd8f9cd3411e9be943a33f1a3ceb"

    private val mixer = Machine("M_MAIN", "Mixer 2", "Main line", "MAIN-MIX-02", true, MachineKind.Main, false, null, listOf("M_EXT"))
    private val otherMixer = Machine("M_OTHER", "Mixer 3", "Main line", "MAIN-MIX-03", true, MachineKind.Main, false, null, emptyList())
    private val offMixer = Machine("M_OFF", "Mixer 5", "Main line", "MAIN-MIX-05", false, MachineKind.Main, false, null, emptyList())
    private val ext = Machine("M_EXT", "Extruder 7", "Ext", "EXT-07", true, MachineKind.Unknown, true, null, emptyList())
    private val machines = listOf(mixer, otherMixer, offMixer, ext)

    private fun snap(
        stage: PrepStage, mixed: Int = 0, produced: Int = 0, n: Int = 4, mixerId: String? = null,
        productionId: String? = null, cycleId: String? = null,
    ) = PrepSnapshot(
        lookup = JobLookupSnapshot(emptyList(), null),
        preparation = Preparation(prepId, "JOB", n, mixed, produced, stage, stage.name, 1, null, null, null, emptyList(),
            mixerId = mixerId, productionId = productionId, cycleId = cycleId),
        requiredChoices = emptyList(), exceptionListRevision = null, collectionExceptions = emptyList(),
        commandExceptions = emptyList(), resultTargetId = null, resultMessage = "", machines = machines, preparations = emptyList(),
    )

    private val ready = snap(PrepStage.ReadyForMixer)
    private val mixing = snap(PrepStage.Mixing, mixerId = "M_MAIN", cycleId = "CYC_A")

    private fun pending(action: String = "machine_start") = PendingCommand(
        messageId = "m-1", requestType = GeneralCollectionUseCase.REQUEST, responseType = GeneralCollectionUseCase.RESPONSE,
        action = action, targetId = prepId, payload = "{}", operatorId = "OP-1", sessionId = "sess-1",
        status = PendingStatus.Unresolved,
    )

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        useCase = mock()
        coordinator = mock()
        mqtt = mock()
        scanBus = mock()
        scans = MutableSharedFlow(extraBufferCapacity = 8)
        hints = MutableSharedFlow(extraBufferCapacity = 8)
        outbox = MutableStateFlow(emptyList())
        sessionHolder = OperatorSessionHolder()
        sessionHolder.set(OperatorSession("sess-1", "OP-1", "Op", "Worker"))
        whenever(scanBus.events).thenReturn(scans)
        whenever(mqtt.connectionState).thenReturn(MutableStateFlow(MqttConnectionState.CONNECTED))
        whenever(mqtt.serverHints).thenReturn(hints)
        whenever(mqtt.stationOnline).thenReturn(MutableStateFlow(true))
        whenever(mqtt.clockSkewMillis).thenReturn(MutableStateFlow<Long?>(null))
        whenever(mqtt.deviceId).thenReturn("scanner_1")
        whenever(coordinator.pending).thenReturn(outbox)
        whenever(coordinator.notices).thenReturn(MutableStateFlow<List<RecoveryResult>>(emptyList()))
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    private suspend fun open(snapshot: PrepSnapshot): PreparationViewModel {
        whenever(useCase.read(prepId)).thenReturn(ReadResult.Loaded(snapshot))
        return PreparationViewModel(
            useCase, mock<CommandRecoveryUseCase>(), coordinator, mqtt, scanBus, sessionHolder, mock<AuthUseCase>(),
            SavedStateHandle(mapOf("prepId" to prepId)),
        ).also { it.setScreenActive(true) }
    }

    private fun scan(tag: String) = scans.tryEmit(ScanEvent.RfidTag(tag, Instant.EPOCH))
    private fun PreparationViewModel.confirmStep() = uiState.value.machine as? MachineStep.Confirm

    @Test
    fun `scanning a mixer shows the confirm card and sends nothing`() = runTest {
        val vm = open(ready)
        vm.onScan("main-mix-02")
        assertTrue(vm.confirmStep()!!.scan is MachineScan.StartMix)
        verify(useCase, never()).machineStart(any(), any())
    }

    @Test
    fun `start mix Done shows a green banner and clears the card`() = runTest {
        val vm = open(ready)
        whenever(useCase.machineStart(prepId, "MAIN-MIX-02")).thenReturn(
            CollectionResult.Done(snap(PrepStage.Mixing, mixerId = "M_MAIN", cycleId = "CYC_A")))
        vm.onScan("MAIN-MIX-02")
        vm.confirmMachine()
        assertEquals(ResultBanner.Accepted("Mix 1 started on Mixer 2"), vm.uiState.value.banner)
        assertEquals(MachineStep.Idle, vm.uiState.value.machine)
        assertEquals(PrepStage.Mixing, vm.uiState.value.stage)
    }

    @Test
    fun `start mix Rejected shows Station 2's reason`() = runTest {
        val vm = open(ready)
        whenever(useCase.machineStart(prepId, "MAIN-MIX-02")).thenReturn(
            CollectionResult.Rejected(ErrorCode("rev2_rejected"), "Mixer 2 is used by PREP_91C2", null))
        vm.onScan("MAIN-MIX-02")
        vm.confirmMachine()
        assertEquals(ResultBanner.Rejected("Mixer 2 is used by PREP_91C2", null, "Not done"), vm.uiState.value.banner)
        assertEquals(MachineStep.Idle, vm.uiState.value.machine)
    }

    @Test
    fun `start mix Unresolved holds the PREP and blocks scans`() = runTest {
        val vm = open(ready)
        val cmd = pending()
        whenever(useCase.machineStart(prepId, "MAIN-MIX-02")).thenAnswer {
            outbox.value = listOf(cmd)
            CollectionResult.Unresolved(cmd, UnresolvedReason.RetryIdentical, "no reply")
        }
        vm.onScan("MAIN-MIX-02")
        vm.confirmMachine()
        assertEquals(listOf(cmd), vm.uiState.value.blocking)
        assertEquals(MachineStep.Idle, vm.uiState.value.machine)
        vm.onScan("MAIN-MIX-03")
        assertEquals(MachineStep.Idle, vm.uiState.value.machine)
    }

    @Test
    fun `not sent keeps the card so the tap can be repeated`() = runTest {
        val vm = open(ready)
        whenever(useCase.machineStart(prepId, "MAIN-MIX-02")).thenReturn(CollectionResult.NotSent("Not connected"))
        vm.onScan("MAIN-MIX-02")
        vm.confirmMachine()
        assertTrue(vm.confirmStep() != null)
        assertEquals(OutcomeMessage.Info("Not connected"), vm.uiState.value.message)
    }

    @Test
    fun `finish sends the cycleId of the current snapshot, not the one at scan time`() = runTest {
        val vm = open(mixing)
        vm.onScan("MAIN-MIX-02")
        assertEquals("CYC_A", (vm.confirmStep()!!.scan as MachineScan.Finish).cycleId)
        whenever(useCase.read(prepId)).thenReturn(ReadResult.Loaded(snap(PrepStage.Mixing, mixerId = "M_MAIN", cycleId = "CYC_B")))
        vm.refresh()
        whenever(useCase.machineFinish(prepId, "MAIN-MIX-02", "CYC_B")).thenReturn(
            CollectionResult.Done(snap(PrepStage.ReadyForNextMix, mixed = 1, mixerId = "M_MAIN")))
        vm.confirmMachine()
        verify(useCase).machineFinish(prepId, "MAIN-MIX-02", "CYC_B")
        verify(useCase, never()).machineFinish(prepId, "MAIN-MIX-02", "CYC_A")
        assertEquals(ResultBanner.Accepted("Mix 1 finished"), vm.uiState.value.banner)
    }

    @Test
    fun `a wrong machine on finish never sends`() = runTest {
        val vm = open(mixing)
        vm.onScan("MAIN-MIX-03")
        assertEquals(MachineStep.Idle, vm.uiState.value.machine)
        assertEquals(ResultBanner.Error("Only Mixer 2 can finish this mix"), vm.uiState.value.banner)
        vm.confirmMachine()
        verify(useCase, never()).machineFinish(any(), any(), any())
    }

    @Test
    fun `an unknown code is a red banner`() = runTest {
        val vm = open(ready)
        vm.onScan("PALLET-1")
        assertEquals(ResultBanner.Error("Not a machine — PALLET-1"), vm.uiState.value.banner)
    }

    @Test
    fun `a disabled machine shows the card but never sends`() = runTest {
        val vm = open(ready)
        vm.onScan("MAIN-MIX-05")
        assertTrue(vm.confirmStep()!!.scan is MachineScan.Disabled)
        vm.confirmMachine()
        verify(useCase, never()).machineStart(any(), any())
    }

    @Test
    fun `a blocked PREP never sends and refuses the scan`() = runTest {
        val vm = open(ready)
        outbox.value = listOf(pending("capture"))
        vm.onScan("MAIN-MIX-02")
        assertEquals(MachineStep.Idle, vm.uiState.value.machine)
        verify(useCase, never()).machineStart(any(), any())
    }

    @Test
    fun `a block that appears while the card is open stops the tap`() = runTest {
        val vm = open(ready)
        vm.onScan("MAIN-MIX-02")
        outbox.value = listOf(pending("capture"))
        vm.confirmMachine()
        verify(useCase, never()).machineStart(any(), any())
    }

    @Test
    fun `a scan while the card is open is refused`() = runTest {
        val vm = open(ready)
        vm.onScan("MAIN-MIX-02")
        vm.onScan("MAIN-MIX-03")
        assertEquals("MAIN-MIX-02", vm.confirmStep()!!.code)
        assertEquals(OutcomeMessage.Info(PreparationViewModel.MACHINE_FINISH_FIRST_MESSAGE), vm.uiState.value.message)
    }

    @Test
    fun `scans reach the machine flow only while the screen is active`() = runTest {
        val vm = open(ready)
        vm.setScreenActive(false)
        scan("MAIN-MIX-02")
        assertEquals(MachineStep.Idle, vm.uiState.value.machine)
        vm.setScreenActive(true)
        scan("MAIN-MIX-02")
        assertTrue(vm.confirmStep() != null)
    }

    @Test
    fun `a stage change while the card is open cancels it`() = runTest {
        val vm = open(ready)
        vm.onScan("MAIN-MIX-02")
        whenever(useCase.read(prepId)).thenReturn(ReadResult.Loaded(mixing))
        vm.refresh()
        assertEquals(MachineStep.Idle, vm.uiState.value.machine)
        vm.confirmMachine()
        verify(useCase, never()).machineStart(any(), any())
    }

    @Test
    fun `cancel drops the card`() = runTest {
        val vm = open(ready)
        vm.onScan("MAIN-MIX-02")
        vm.cancelMachine()
        assertEquals(MachineStep.Idle, vm.uiState.value.machine)
    }

    @Test
    fun `the last production finishing reports the preparation complete`() = runTest {
        val producing = snap(PrepStage.Producing, mixed = 4, produced = 3, mixerId = "M_MAIN", productionId = "M_EXT", cycleId = "CYC_P")
        val vm = open(producing)
        whenever(useCase.machineFinish(prepId, "EXT-07", "CYC_P")).thenReturn(
            CollectionResult.Done(snap(PrepStage.Completed, mixed = 4, produced = 4, mixerId = "M_MAIN", productionId = "M_EXT")))
        vm.onScan("EXT-07")
        vm.confirmMachine()
        assertEquals(ResultBanner.Accepted("Preparation complete"), vm.uiState.value.banner)
    }

    @Test
    fun `a production finish that is not the last says Production k finished`() = runTest {
        val producing = snap(PrepStage.Producing, mixed = 4, produced = 1, mixerId = "M_MAIN", productionId = "M_EXT", cycleId = "CYC_P")
        val vm = open(producing)
        whenever(useCase.machineFinish(prepId, "EXT-07", "CYC_P")).thenReturn(
            CollectionResult.Done(snap(PrepStage.ReadyForProduction, mixed = 4, produced = 2, mixerId = "M_MAIN", productionId = "M_EXT")))
        vm.onScan("EXT-07")
        vm.confirmMachine()
        assertEquals(ResultBanner.Accepted("Production 2 finished"), vm.uiState.value.banner)
        verify(useCase, times(1)).machineFinish(any(), any(), any())
    }
}
