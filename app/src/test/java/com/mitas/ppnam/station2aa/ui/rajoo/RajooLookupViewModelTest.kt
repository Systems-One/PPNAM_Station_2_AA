package com.mitas.ppnam.station2aa.ui.rajoo

import com.mitas.ppnam.station2aa.data.rfid.ScanEvent
import com.mitas.ppnam.station2aa.domain.feedback.ScanSound
import com.mitas.ppnam.station2aa.ui.heard
import com.google.gson.JsonParser
import com.mitas.ppnam.station2aa.contract.ContractFixtures1021
import com.mitas.ppnam.station2aa.data.mqtt.WireJson
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2Snapshot
import com.mitas.ppnam.station2aa.data.rfid.ScanEventBus
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.domain.model.PrepSnapshot
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.repository.ServerHint
import com.mitas.ppnam.station2aa.domain.usecase.AuthUseCase
import com.mitas.ppnam.station2aa.domain.usecase.RajooUseCase
import com.mitas.ppnam.station2aa.domain.usecase.ReadResult
import com.mitas.ppnam.station2aa.domain.usecase.toPrepSnapshot
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
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class RajooLookupViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var useCase: RajooUseCase
    private lateinit var mqtt: MqttRepository
    private lateinit var scanBus: ScanEventBus
    private val sessionHolder = OperatorSessionHolder()

    /** The Rajoo job list: one enabled machine, RAJ-EXT-01. */
    private val list: PrepSnapshot by lazy {
        WireJson.gson.fromJson(
            JsonParser.parseString(ContractFixtures1021.text("rajoo_read_list_response.json")).asJsonObject["data"],
            Rev2Snapshot::class.java,
        ).toPrepSnapshot()
    }

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        useCase = mock()
        mqtt = mock()
        scanBus = mock()
        sessionHolder.set(OperatorSession("sess-1", "OP-1", "Op", "Worker"))
        whenever(scanBus.events).thenReturn(MutableSharedFlow())
        whenever(mqtt.connectionState).thenReturn(MutableStateFlow(MqttConnectionState.CONNECTED))
        whenever(mqtt.serverHints).thenReturn(MutableSharedFlow<ServerHint>())
        whenever(mqtt.stationOnline).thenReturn(MutableStateFlow(true))
        whenever(mqtt.clockSkewMillis).thenReturn(MutableStateFlow<Long?>(null))
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    private suspend fun open(snapshot: PrepSnapshot): RajooLookupViewModel {
        whenever(useCase.read()).thenReturn(ReadResult.Loaded(snapshot))
        return RajooLookupViewModel(useCase, mock<AuthUseCase>(), mqtt, sessionHolder, scanBus)
    }

    @Test
    fun `the only enabled Rajoo machine is used without a scan`() = runTest {
        val vm = open(list)
        val state = vm.uiState.value
        assertEquals("RAJ-EXT-01", state.machine!!.code)
        assertTrue(state.machineAutoSelected)
        assertNull(state.error)

        // The first scan is the job card, and lookup names the machine's code.
        vm.onJobCard("2")
        assertTrue(vm.uiState.value.canLookup)
        whenever(useCase.lookup("2", "RAJ-EXT-01")).thenReturn(ReadResult.Loaded(list))
        vm.lookup()
        verify(useCase).lookup("2", "RAJ-EXT-01")

        // Nothing to rescan.
        vm.changeMachine()
        assertEquals("RAJ-EXT-01", vm.uiState.value.machine!!.code)
    }

    @Test
    fun `with several enabled Rajoo machines the operator scans which one`() = runTest {
        val one = list.machines.single()
        val two = one.copy(id = "RAJ-EXT-02", name = "Rajoo 2", code = "RAJ-EXT-02")
        val vm = open(list.copy(machines = listOf(one, two)))
        assertNull(vm.uiState.value.machine)
        assertFalse(vm.uiState.value.machineAutoSelected)

        vm.onMachineScan("RAJ-EXT-02")
        assertEquals("RAJ-EXT-02", vm.uiState.value.machine!!.id)
        assertFalse(vm.uiState.value.machineAutoSelected)
    }

    @Test
    fun `a disabled or uncommissioned machine is not used, and no usable machine says so`() = runTest {
        val disabled = list.machines.single().copy(enabled = false)
        val vm = open(list.copy(machines = listOf(disabled, disabled.copy(id = "RAJ-EXT-02", enabled = true, code = ""))))
        assertNull(vm.uiState.value.machine)
        assertEquals(RajooLookupViewModel.NO_MACHINE, vm.uiState.value.error)
        assertFalse(vm.uiState.value.canLookup)
    }

    @Test
    fun `the auto-selected machine follows Station 2's current list`() = runTest {
        val vm = open(list)
        assertTrue(vm.uiState.value.machineAutoSelected)
        // Disabled at the desktop meanwhile: the next read drops it.
        whenever(useCase.read()).thenReturn(ReadResult.Loaded(list.copy(machines = list.machines.map { it.copy(enabled = false) })))
        vm.refresh()
        assertNull(vm.uiState.value.machine)
        assertFalse(vm.uiState.value.machineAutoSelected)
    }

    // ---- scan sounds (suite rules S1 and S3) ----

    @Test
    fun `S1 a wrong or disabled Rajoo machine fails once, a valid one waits for the lookup's answer`() = runTest {
        val one = list.machines.single()
        val two = one.copy(id = "RAJ-EXT-02", name = "Rajoo 2", code = "RAJ-EXT-02", enabled = false)
        val vm = open(list.copy(machines = listOf(one, two)))
        val heard = heard(vm.scanSounds)
        vm.onMachineScan("NOPE")
        assertEquals(listOf(ScanSound.FAIL), heard)
        vm.onMachineScan("RAJ-EXT-02")
        assertEquals(listOf(ScanSound.FAIL, ScanSound.FAIL), heard)
        vm.onMachineScan("RAJ-EXT-01")
        vm.onJobCard("2")
        assertEquals(2, heard.size)
        whenever(useCase.lookup("2", "RAJ-EXT-01")).thenReturn(ReadResult.Loaded(list))
        vm.lookup()
        assertEquals(listOf(ScanSound.FAIL, ScanSound.FAIL, ScanSound.SUCCESS), heard)
    }

    @Test
    fun `S1 a refused job card lookup fails once, opening a listed job is silent`() = runTest {
        val vm = open(list)
        val heard = heard(vm.scanSounds)
        vm.onJobCard("9")
        whenever(useCase.lookup("9", "RAJ-EXT-01")).thenReturn(ReadResult.Failed("No such job", null, retryable = false))
        vm.lookup()
        assertEquals(listOf(ScanSound.FAIL), heard)
        whenever(useCase.lookup("2", "RAJ-EXT-01")).thenReturn(ReadResult.Loaded(list))
        vm.open(com.mitas.ppnam.station2aa.domain.model.JobSummary("2", "Job", 1, false))
        assertEquals(listOf(ScanSound.FAIL), heard)
    }

    @Test
    fun `S1 a badge scanned on Rajoo lookup is refused here and fails once`() = runTest {
        val scans = MutableSharedFlow<ScanEvent>(extraBufferCapacity = 8)
        whenever(scanBus.events).thenReturn(scans)
        val vm = open(list)
        vm.setScreenActive(true)
        val heard = heard(vm.scanSounds)
        scans.tryEmit(ScanEvent.RfidTag("505055AABBCCDDEEFF00112233445566", java.time.Instant.EPOCH))
        assertEquals(listOf(ScanSound.FAIL), heard)
        scans.tryEmit(ScanEvent.Barcode("2", "CODE128", java.time.Instant.EPOCH))
        assertEquals("2", vm.uiState.value.jobCard)
        assertEquals(listOf(ScanSound.FAIL), heard) // the job card is taken silently: Look up sounds
    }
}
