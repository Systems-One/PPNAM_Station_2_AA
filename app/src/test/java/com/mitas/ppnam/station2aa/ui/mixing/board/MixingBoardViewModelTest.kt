package com.mitas.ppnam.station2aa.ui.mixing.board

import com.mitas.ppnam.station2aa.data.rfid.ScanEventBus
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.domain.model.ActiveCycle
import com.mitas.ppnam.station2aa.domain.model.AreaOverview
import com.mitas.ppnam.station2aa.domain.model.Equipment
import com.mitas.ppnam.station2aa.domain.model.MachineCycleOutcome
import com.mitas.ppnam.station2aa.domain.model.MixingArea
import com.mitas.ppnam.station2aa.domain.model.ReadyCollection
import com.mitas.ppnam.station2aa.domain.model.ReadyMix
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.usecase.AuthUseCase
import com.mitas.ppnam.station2aa.domain.usecase.MixingBoardUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class MixingBoardViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var mockUseCase: MixingBoardUseCase
    private lateinit var mockScanEventBus: ScanEventBus
    private lateinit var mockMqttRepository: MqttRepository
    private lateinit var mockAuthUseCase: AuthUseCase
    private lateinit var mockSessionHolder: OperatorSessionHolder
    private lateinit var connectionState: MutableStateFlow<MqttConnectionState>
    private lateinit var viewModel: MixingBoardViewModel

    // Test fixtures shared with Task 5's tests.
    private fun equipment(
        code: String, role: String = "Mixer", status: String = "Available",
        area: MixingArea? = MixingArea.Main, enabled: Boolean = true,
        currentCycleId: String? = null, currentJc: String? = null,
        // scanAllowed is decided by the server and is NOT the same as isAvailable — it simply
        // means the server says this handheld may scan this machine now. Defaults to the
        // available case so existing fixtures read the same.
        scanAllowed: Boolean = enabled && status == "Available",
    ) = Equipment(
        machineCode = code, displayName = code, area = area, role = role,
        isEnabled = enabled, isAvailable = enabled && status == "Available", status = status,
        productLayer = null, currentCycleId = currentCycleId, currentJobCardNumber = currentJc,
        currentMixBatchIds = emptyList(), validDestinationMachineCodes = emptyList(),
        routeDescription = "",
        scanAllowed = scanAllowed,
    )

    private fun readyMix(
        id: String, jc: String = "510019068", validNext: List<String>,
        status: String = "ReadyForProduction", completionMode: String? = null,
        isAssignable: Boolean = true,
    ) = ReadyMix(
        mixBatchId = id, collectionId = "COL_$id", area = MixingArea.Main, jobCardNumber = jc,
        sourceMixerCode = "MXR-01", mixerDisplayName = "Main Mixer 1", status = status,
        validNextMachineCodes = validNext, nextStepDescription = "",
        completionMode = completionMode, isAssignable = isAssignable,
    )

    private fun readyCollection(
        id: String = "COL_1", jc: String = "510019068",
        validMixers: List<String> = listOf("MXR-01"),
    ) = ReadyCollection(
        jobCardNumber = jc, collectionId = id, productName = "HD Film",
        status = "IngredientsCollected", validMixerCodes = validMixers,
        nextAction = "scan_same_machine_to_finish",
    )

    private val mainOverview = AreaOverview(
        equipment = listOf(
            equipment("MXR-01"), equipment("MXR-02", status = "InUse", currentCycleId = "CYC_9"),
            equipment("EXT-03", role = "ProductionMachine"),
            equipment("EXT-04", role = "ProductionMachine"),
        ),
        activeCycles = listOf(ActiveCycle(
            cycleId = "CYC_9", machineCode = "MXR-02", area = MixingArea.Main, role = "Mixer",
            jobCardNumber = "510019068", collectionId = "COL_9", mixBatchIds = listOf("MIX_9"),
            productionRunId = null, startedAtUtc = "2026-07-21T08:00:00Z", startedByOperatorId = "OP-001")),
        readyMixes = listOf(readyMix("MIX_1", validNext = listOf("EXT-03", "EXT-04"))),
        activeRuns = emptyList(),
        readyCollections = listOf(readyCollection()),
    )

    private val readyCollections = listOf(readyCollection())

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockUseCase = mock()
        mockScanEventBus = mock()
        mockMqttRepository = mock()
        mockAuthUseCase = mock()
        mockSessionHolder = mock()
        connectionState = MutableStateFlow(MqttConnectionState.CONNECTED)

        whenever(mockMqttRepository.connectionState).thenReturn(connectionState)
        whenever(mockMqttRepository.stationOnline).thenReturn(MutableStateFlow(true))
        whenever(mockMqttRepository.clockSkewMillis).thenReturn(MutableStateFlow<Long?>(null))
        whenever(mockScanEventBus.events).thenReturn(MutableSharedFlow())
        whenever(mockSessionHolder.session).thenReturn(MutableStateFlow(null))

        viewModel = MixingBoardViewModel(
            mockUseCase, mockScanEventBus, mockMqttRepository, mockAuthUseCase, mockSessionHolder
        )
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `loadAreaPicker success carries the overview and the pending collection`() = runTest {
        whenever(mockUseCase.fetchOverview(anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(Result.success(mainOverview))
        viewModel.loadAreaPicker("COL_1")
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertTrue(state is MixingBoardUiState.AreaPicker)
        assertEquals("COL_1", (state as MixingBoardUiState.AreaPicker).pendingCollectionId)
        verify(mockUseCase).fetchOverview(isNull(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `loadAreaPicker failure sets Error`() = runTest {
        whenever(mockUseCase.fetchOverview(anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(Result.failure(Exception("boom")))
        viewModel.loadAreaPicker(null)
        advanceUntilIdle()
        assertEquals("boom", (viewModel.uiState.value as MixingBoardUiState.Error).message)
    }

    @Test
    fun `openArea loads the filtered overview and collections and pre-selects the pending collection`() = runTest {
        // Stub BOTH shapes before any call — the eager dispatcher runs launches immediately.
        whenever(mockUseCase.fetchOverview(anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(Result.success(mainOverview))
        whenever(mockUseCase.fetchReadyCollections(eq(MixingArea.Main))).thenReturn(Result.success(readyCollections))
        viewModel.loadAreaPicker("COL_1")
        advanceUntilIdle()

        viewModel.openArea(MixingArea.Main)
        advanceUntilIdle()

        val board = viewModel.uiState.value as MixingBoardUiState.Board
        assertEquals(MixingArea.Main, board.area)
        assertEquals(readyCollections, board.readyCollections)
        val sel = board.selection
        assertTrue(sel is BoardSelection.Collection)
        assertEquals("COL_1", (sel as BoardSelection.Collection).collectionId)
        // Collection selected -> available, enabled mixers highlight (MXR-01, not InUse MXR-02)
        assertEquals(setOf("MXR-01"), board.highlightedMachineCodes)
    }

    @Test
    fun `reconnect triggers a refresh of the current board`() = runTest {
        whenever(mockUseCase.fetchOverview(eq(MixingArea.Main), anyOrNull(), anyOrNull())).thenReturn(Result.success(mainOverview))
        whenever(mockUseCase.fetchReadyCollections(eq(MixingArea.Main))).thenReturn(Result.success(readyCollections))
        viewModel.openArea(MixingArea.Main)
        advanceUntilIdle()

        connectionState.value = MqttConnectionState.DISCONNECTED
        connectionState.value = MqttConnectionState.CONNECTED
        advanceUntilIdle()

        // openArea's overview fetch ran twice: once on entry, once on reconnect
        verify(mockUseCase, times(2)).fetchOverview(eq(MixingArea.Main), anyOrNull(), anyOrNull())
    }

    @Test
    fun `computeHighlightedMachines for a mix highlights its validNext machines the server allows scanning`() {
        val overview = mainOverview.copy(
            equipment = listOf(
                equipment("EXT-03", role = "ProductionMachine"),
                equipment("EXT-04", role = "ProductionMachine", status = "InUse", currentJc = "510019068",
                    scanAllowed = false),
            ),
            readyMixes = listOf(
                readyMix("MIX_1", validNext = listOf("EXT-03", "EXT-04")),
            ),
        )
        val highlights = computeHighlightedMachines(
            overview, BoardSelection.Mix("MIX_1", "510019068"))
        // EXT-03 is in validNext and scanAllowed; EXT-04 is in validNext but the server has not
        // marked it scanAllowed — a destination start now takes one mix into one run, so there is
        // no local accumulation branch to offer it anyway.
        assertEquals(setOf("EXT-03"), highlights)
    }

    @Test
    fun `computeHighlightedMachines for a collection highlights its valid mixers the server allows scanning`() {
        // The mixers a collection may start come from validMixerCodes — server-decided, never
        // inferred locally — intersected with the equipment the server says is scannable now.
        val overview = mainOverview.copy(
            equipment = listOf(
                equipment("MXR-01", scanAllowed = true),
                equipment("MXR-02", scanAllowed = true),
                // In validMixerCodes but the server has not marked it scanAllowed.
                equipment("MXR-03", scanAllowed = false),
                equipment("MXR-05", enabled = false, status = "Disabled"),
                equipment("EXT-03", role = "ProductionMachine"),
            ),
            readyCollections = listOf(readyCollection(validMixers = listOf("MXR-02", "MXR-03"))),
        )

        val highlights = computeHighlightedMachines(
            overview, BoardSelection.Collection("COL_1", "510019068"))

        assertEquals(setOf("MXR-02"), highlights)
    }

    @Test
    fun `a quarantined force-closed mix offers no destination at all`() {
        // Backend issue B2: a force-closed cycle still yielded a usable mix. 4.1 quarantines it,
        // and it stays unassignable until an audited Manager/Admin Release or Discard — which the
        // operator cannot do from the handheld.
        val overview = mainOverview.copy(
            readyMixes = listOf(
                readyMix("MIX_Q", validNext = listOf("EXT-03", "EXT-04"),
                    status = "Quarantined", completionMode = "ForceClosed", isAssignable = false)
            )
        )

        val highlights = computeHighlightedMachines(
            overview, BoardSelection.Mix("MIX_Q", "510019068"))

        assertTrue("a quarantined mix must not be routable to production", highlights.isEmpty())
    }

    @Test
    fun `machineTabOf puts mixers under Collections and everything else under Mixing`() {
        val equipment = listOf(
            equipment("MXR-01"),
            equipment("EXT-03", role = "ProductionMachine"),
            equipment("TRF-01", role = "Transfer"),
            equipment("UNK-01", role = ""), // unmodelled role — still reachable, not dropped
        )
        assertEquals(
            listOf("MXR-01"),
            equipment.filter { machineTabOf(it) == MachineTab.Collections }.map { it.machineCode })
        assertEquals(
            listOf("EXT-03", "TRF-01", "UNK-01"),
            equipment.filter { machineTabOf(it) == MachineTab.Mixing }.map { it.machineCode })
    }

    @Test
    fun `every highlighted machine is visible on some tab`() {
        val overview = mainOverview.copy(activeRuns = emptyList())
        val collectionHighlights =
            computeHighlightedMachines(overview, BoardSelection.Collection("COL_1", "510019068"))
        val mixHighlights =
            computeHighlightedMachines(overview, BoardSelection.Mix("MIX_1", "510019068"))
        val collectionsTab = overview.equipment
            .filter { machineTabOf(it) == MachineTab.Collections }.map { it.machineCode }.toSet()
        val mixingTab = overview.equipment
            .filter { machineTabOf(it) == MachineTab.Mixing }.map { it.machineCode }.toSet()
        // A collection's highlights all sit on the Collections tab, a mix's on the Mixing tab —
        // the tab the selection auto-switches to.
        assertTrue(collectionsTab.containsAll(collectionHighlights))
        assertTrue(mixingTab.containsAll(mixHighlights))
    }

    @Test
    fun `the auto-nav pre-selection is one-shot — a refresh does not re-assert it`() = runTest {
        whenever(mockUseCase.fetchOverview(anyOrNull(), anyOrNull(), anyOrNull())).thenReturn(Result.success(mainOverview))
        whenever(mockUseCase.fetchReadyCollections(eq(MixingArea.Main))).thenReturn(Result.success(readyCollections))
        viewModel.loadAreaPicker("COL_1")
        advanceUntilIdle()
        viewModel.openArea(MixingArea.Main)
        advanceUntilIdle()
        assertTrue((viewModel.uiState.value as MixingBoardUiState.Board).selection is BoardSelection.Collection)

        viewModel.refresh()
        advanceUntilIdle()

        assertTrue("a refresh must not re-assert the consumed auto-nav hint",
            (viewModel.uiState.value as MixingBoardUiState.Board).selection is BoardSelection.None)
    }

    private suspend fun openMainBoard() {
        whenever(mockUseCase.fetchOverview(eq(MixingArea.Main), anyOrNull(), anyOrNull())).thenReturn(Result.success(mainOverview))
        whenever(mockUseCase.fetchReadyCollections(eq(MixingArea.Main))).thenReturn(Result.success(readyCollections))
        viewModel.openArea(MixingArea.Main)
    }

    private val twoMixOverview = mainOverview.copy(
        readyMixes = listOf(
            readyMix("MIX_1", validNext = listOf("EXT-03")),
            readyMix("MIX_2", validNext = listOf("EXT-03")),
        ))

    @Test
    fun `selecting a second mix replaces the first rather than adding to it`() = runTest {
        whenever(mockUseCase.fetchOverview(anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(Result.success(twoMixOverview))
        whenever(mockUseCase.fetchReadyCollections(eq(MixingArea.Main))).thenReturn(Result.success(readyCollections))
        viewModel.openArea(MixingArea.Main)
        advanceUntilIdle()

        viewModel.selectMix("MIX_1")
        advanceUntilIdle()
        assertEquals(
            BoardSelection.Mix("MIX_1", "510019068"),
            (viewModel.uiState.value as MixingBoardUiState.Board).selection)

        viewModel.selectMix("MIX_2")
        advanceUntilIdle()
        assertEquals(
            BoardSelection.Mix("MIX_2", "510019068"),
            (viewModel.uiState.value as MixingBoardUiState.Board).selection)
    }

    @Test
    fun `selecting the same mix twice clears the selection`() = runTest {
        whenever(mockUseCase.fetchOverview(anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(Result.success(twoMixOverview))
        whenever(mockUseCase.fetchReadyCollections(eq(MixingArea.Main))).thenReturn(Result.success(readyCollections))
        viewModel.openArea(MixingArea.Main)
        advanceUntilIdle()

        viewModel.selectMix("MIX_1")
        viewModel.selectMix("MIX_1")
        advanceUntilIdle()

        assertEquals(
            BoardSelection.None,
            (viewModel.uiState.value as MixingBoardUiState.Board).selection)
    }

    @Test
    fun `selectCollection highlights mixers and selectMix highlights the mix's validNext machines`() = runTest {
        openMainBoard(); advanceUntilIdle()
        viewModel.selectCollection("COL_1")
        var board = viewModel.uiState.value as MixingBoardUiState.Board
        assertEquals(setOf("MXR-01"), board.highlightedMachineCodes)

        viewModel.clearSelection()
        viewModel.selectMix("MIX_1")
        board = viewModel.uiState.value as MixingBoardUiState.Board
        assertTrue(board.selection is BoardSelection.Mix)
        assertEquals(setOf("EXT-03", "EXT-04"), board.highlightedMachineCodes)

        // selecting the same mix again clears it -> selection collapses to None
        viewModel.selectMix("MIX_1")
        board = viewModel.uiState.value as MixingBoardUiState.Board
        assertTrue(board.selection is BoardSelection.None)
    }

    @Test
    fun `machineChosen with no selection opens the cycle sheet for a busy machine`() = runTest {
        openMainBoard(); advanceUntilIdle()
        viewModel.machineChosen("MXR-02")
        val board = viewModel.uiState.value as MixingBoardUiState.Board
        val sheet = board.sheet
        assertTrue(sheet is BoardSheet.CycleSheet)
        assertEquals("CYC_9", (sheet as BoardSheet.CycleSheet).cycle.cycleId)
    }

    @Test
    fun `machineChosen with no selection on an idle machine only messages`() = runTest {
        openMainBoard(); advanceUntilIdle()
        viewModel.machineChosen("MXR-01")
        val board = viewModel.uiState.value as MixingBoardUiState.Board
        assertTrue(board.sheet is BoardSheet.None)
    }

    @Test
    fun `machineChosen with a collection on a Rajoo mixer fetches dose rows`() = runTest {
        val rajooOverview = mainOverview.copy(equipment = listOf(
            equipment("RAJ-GM-01", area = MixingArea.Rajoo)))
        whenever(mockUseCase.fetchOverview(eq(MixingArea.Rajoo), anyOrNull(), anyOrNull())).thenReturn(Result.success(rajooOverview))
        whenever(mockUseCase.fetchReadyCollections(eq(MixingArea.Rajoo))).thenReturn(Result.success(readyCollections))
        whenever(mockUseCase.fetchCollectedMaterials("COL_1")).thenReturn(
            Result.success(listOf(
                com.mitas.ppnam.station2aa.domain.model.CollectedMaterial("MAT-1", "Resin", 550.0))))
        viewModel.openArea(MixingArea.Rajoo)
        advanceUntilIdle()
        viewModel.selectCollection("COL_1")
        viewModel.machineChosen("RAJ-GM-01")
        advanceUntilIdle()

        val sheet = (viewModel.uiState.value as MixingBoardUiState.Board).sheet
        assertTrue(sheet is BoardSheet.StartConfirm)
        val rows = (sheet as BoardSheet.StartConfirm).doseRows
        assertEquals("MAT-1", rows!!.single().materialCode)
        assertEquals(550.0, rows.single().collectedQty, 0.0)
    }

    @Test
    fun `confirmStart for a collection on a plain mixer calls startMixerFromCollection`() = runTest {
        openMainBoard(); advanceUntilIdle()
        whenever(mockUseCase.startMixerFromCollection(any(), any())).thenReturn(
            com.mitas.ppnam.station2aa.domain.model.MachineCycleOutcome.Accepted(
                action = "Started", machineCode = "MXR-01", cycleId = "CYC_1",
                mixBatchId = "MIX_5", productionRunId = null,
                affectedMixBatchIds = listOf("MIX_5"), alreadyFinished = false,
                forceClosed = false, approverDisplayName = null, areaStatus = mainOverview))
        viewModel.selectCollection("COL_1")
        viewModel.machineChosen("MXR-01")
        viewModel.confirmStart()
        advanceUntilIdle()

        verify(mockUseCase).startMixerFromCollection("MXR-01", "COL_1")
        val board = viewModel.uiState.value as MixingBoardUiState.Board
        assertTrue(board.selection is BoardSelection.None)
        assertTrue(board.sheet is BoardSheet.None)
        assertFalse(board.busy)
    }

    @Test
    fun `confirmStart for a mix on a downstream machine calls startProductionDestination`() = runTest {
        openMainBoard(); advanceUntilIdle()
        whenever(mockUseCase.startProductionDestination(any(), any())).thenReturn(
            MachineCycleOutcome.Accepted(
                action = "Started", machineCode = "EXT-03", cycleId = null,
                mixBatchId = "MIX_1", productionRunId = "RUN_1",
                affectedMixBatchIds = listOf("MIX_1"), alreadyFinished = false,
                forceClosed = false, approverDisplayName = null, areaStatus = mainOverview))
        viewModel.selectMix("MIX_1")
        viewModel.machineChosen("EXT-03")
        viewModel.confirmStart()
        advanceUntilIdle()

        verify(mockUseCase).startProductionDestination("EXT-03", "MIX_1")
    }

    @Test
    fun `confirmStart for mixes on a JANDI drum starts a transfer cycle`() = runTest {
        // The JANDI drum is a Transfer — a downstream machine that starts via
        // machine_cycle_start with a mixBatchId.
        val drumOverview = AreaOverview(
            equipment = listOf(equipment("JAN-DRUM-01", role = "Transfer", area = MixingArea.Jandi)),
            activeCycles = emptyList(),
            readyMixes = listOf(readyMix("MIX_7", validNext = listOf("JAN-DRUM-01"))),
            activeRuns = emptyList(),
            readyCollections = emptyList(),
        )
        whenever(mockUseCase.fetchOverview(eq(MixingArea.Jandi), anyOrNull(), anyOrNull()))
            .thenReturn(Result.success(drumOverview))
        whenever(mockUseCase.fetchReadyCollections(eq(MixingArea.Jandi))).thenReturn(Result.success(emptyList()))
        viewModel.openArea(MixingArea.Jandi); advanceUntilIdle()

        whenever(mockUseCase.startDrumTransfer(any(), any())).thenReturn(
            MachineCycleOutcome.Accepted(
                action = "Started", machineCode = "JAN-DRUM-01", cycleId = "CYC_7",
                mixBatchId = "MIX_7", productionRunId = null,
                affectedMixBatchIds = listOf("MIX_7"), alreadyFinished = false,
                forceClosed = false, approverDisplayName = null, areaStatus = drumOverview))
        viewModel.selectMix("MIX_7")
        viewModel.machineChosen("JAN-DRUM-01")
        viewModel.confirmStart()
        advanceUntilIdle()

        verify(mockUseCase).startDrumTransfer("JAN-DRUM-01", "MIX_7")
    }

    @Test
    fun `a rejected start applies the embedded areaStatus and keeps the selection`() = runTest {
        openMainBoard(); advanceUntilIdle()
        val refreshed = mainOverview.copy(equipment = listOf(equipment("MXR-01", status = "InUse")))
        whenever(mockUseCase.startMixerFromCollection(any(), any())).thenReturn(
            com.mitas.ppnam.station2aa.domain.model.MachineCycleOutcome.Rejected(
                errorCode = com.mitas.ppnam.station2aa.data.mqtt.ErrorCode.EQUIPMENT_IN_USE,
                reason = "Busy on another cycle.", areaStatus = refreshed))
        viewModel.selectCollection("COL_1")
        viewModel.machineChosen("MXR-01")
        viewModel.confirmStart()
        advanceUntilIdle()

        val board = viewModel.uiState.value as MixingBoardUiState.Board
        assertEquals("InUse", board.overview.equipment.single { it.machineCode == "MXR-01" }.status)
        assertTrue("selection survives a rejection so the operator can retry elsewhere",
            board.selection is BoardSelection.Collection)
        // and highlights were recomputed against the refreshed overview
        assertTrue(board.highlightedMachineCodes.isEmpty())
    }

    @Test
    fun `a rejection without areaStatus keeps the current board picture`() = runTest {
        openMainBoard(); advanceUntilIdle()
        whenever(mockUseCase.startMixerFromCollection(any(), any())).thenReturn(
            com.mitas.ppnam.station2aa.domain.model.MachineCycleOutcome.Rejected(
                errorCode = com.mitas.ppnam.station2aa.data.mqtt.ErrorCode.SESSION_REQUIRED,
                reason = "No valid session.", areaStatus = null))
        viewModel.selectCollection("COL_1")
        viewModel.machineChosen("MXR-01")
        viewModel.confirmStart()
        advanceUntilIdle()

        val board = viewModel.uiState.value as MixingBoardUiState.Board
        assertTrue("an envelope-level rejection must not blank the board",
            board.overview.equipment.isNotEmpty())
        assertTrue(board.overview.equipment.any { it.machineCode == "MXR-01" })
        assertTrue("selection survives a rejection so the operator can retry elsewhere",
            board.selection is BoardSelection.Collection)
    }

    @Test
    fun `a late area load cannot overwrite a newer one`() = runTest {
        val slowGate = kotlinx.coroutines.CompletableDeferred<Unit>()
        whenever(mockUseCase.fetchOverview(eq(MixingArea.Dolci), anyOrNull(), anyOrNull())).doSuspendableAnswer {
            slowGate.await()
            Result.success(mainOverview)
        }
        whenever(mockUseCase.fetchOverview(eq(MixingArea.Main), anyOrNull(), anyOrNull()))
            .thenReturn(Result.success(mainOverview))
        whenever(mockUseCase.fetchReadyCollections(eq(MixingArea.Main))).thenReturn(Result.success(readyCollections))

        viewModel.openArea(MixingArea.Dolci) // suspends on the gate
        viewModel.openArea(MixingArea.Main)  // cancels the Dolci load
        advanceUntilIdle()
        slowGate.complete(Unit)              // late Dolci result must be dead
        advanceUntilIdle()

        assertEquals(MixingArea.Main, (viewModel.uiState.value as MixingBoardUiState.Board).area)
    }

    @Test
    fun `rajoo confirm validates doses fail-closed`() = runTest {
        val rajooOverview = mainOverview.copy(equipment = listOf(
            equipment("RAJ-GM-01", area = MixingArea.Rajoo)))
        whenever(mockUseCase.fetchOverview(eq(MixingArea.Rajoo), anyOrNull(), anyOrNull())).thenReturn(Result.success(rajooOverview))
        whenever(mockUseCase.fetchReadyCollections(eq(MixingArea.Rajoo))).thenReturn(Result.success(readyCollections))
        whenever(mockUseCase.fetchCollectedMaterials(any())).thenReturn(
            Result.success(listOf(
                com.mitas.ppnam.station2aa.domain.model.CollectedMaterial("MAT-1", "Resin", 100.0))))
        viewModel.openArea(MixingArea.Rajoo)
        advanceUntilIdle()
        viewModel.selectCollection("COL_1")
        viewModel.machineChosen("RAJ-GM-01")
        advanceUntilIdle()

        viewModel.updateDose("MAT-1", "150.0") // above collected
        viewModel.confirmStart()
        advanceUntilIdle()

        val sheet = (viewModel.uiState.value as MixingBoardUiState.Board).sheet
        assertNotNull((sheet as BoardSheet.StartConfirm).validationError)
        verify(mockUseCase, never()).startRajooLayer(any(), any(), any())
    }

    @Test
    fun `a scan while a sheet is open is ignored`() = runTest {
        val events = MutableSharedFlow<com.mitas.ppnam.station2aa.data.rfid.ScanEvent>()
        whenever(mockScanEventBus.events).thenReturn(events)
        val vm = MixingBoardViewModel(mockUseCase, mockScanEventBus, mockMqttRepository, mockAuthUseCase, mockSessionHolder)
        whenever(mockUseCase.fetchOverview(eq(MixingArea.Main), anyOrNull(), anyOrNull())).thenReturn(Result.success(mainOverview))
        whenever(mockUseCase.fetchReadyCollections(eq(MixingArea.Main))).thenReturn(Result.success(readyCollections))
        vm.openArea(MixingArea.Main)
        advanceUntilIdle()
        vm.machineChosen("MXR-02") // opens the cycle sheet

        events.emit(com.mitas.ppnam.station2aa.data.rfid.ScanEvent.Barcode("MXR-01", "CODE128", java.time.Instant.now()))
        advanceUntilIdle()

        val sheet = (vm.uiState.value as MixingBoardUiState.Board).sheet
        assertTrue("cycle sheet must survive a stray scan", sheet is BoardSheet.CycleSheet)
    }

    @Test
    fun `a scan on the board dispatches machineChosen`() = runTest {
        val events = MutableSharedFlow<com.mitas.ppnam.station2aa.data.rfid.ScanEvent>()
        whenever(mockScanEventBus.events).thenReturn(events)
        val vm = MixingBoardViewModel(mockUseCase, mockScanEventBus, mockMqttRepository, mockAuthUseCase, mockSessionHolder)
        whenever(mockUseCase.fetchOverview(eq(MixingArea.Main), anyOrNull(), anyOrNull())).thenReturn(Result.success(mainOverview))
        whenever(mockUseCase.fetchReadyCollections(eq(MixingArea.Main))).thenReturn(Result.success(readyCollections))
        vm.openArea(MixingArea.Main)
        advanceUntilIdle()

        events.emit(com.mitas.ppnam.station2aa.data.rfid.ScanEvent.Barcode("MXR-02", "CODE128", java.time.Instant.now()))
        advanceUntilIdle()

        assertTrue((vm.uiState.value as MixingBoardUiState.Board).sheet is BoardSheet.CycleSheet)
    }

    @Test
    fun `finishCycle sends the stored cycle id and messages alreadyFinished as success`() = runTest {
        openMainBoard(); advanceUntilIdle()
        whenever(mockUseCase.finish("MXR-02", "CYC_9")).thenReturn(
            com.mitas.ppnam.station2aa.domain.model.MachineCycleOutcome.Accepted(
                action = "Finished", machineCode = "MXR-02", cycleId = "CYC_9",
                mixBatchId = "MIX_9", productionRunId = null,
                affectedMixBatchIds = listOf("MIX_9"), alreadyFinished = true,
                forceClosed = false, approverDisplayName = null, areaStatus = mainOverview))
        viewModel.machineChosen("MXR-02")
        viewModel.finishCycle()
        advanceUntilIdle()

        verify(mockUseCase).finish("MXR-02", "CYC_9")
        val board = viewModel.uiState.value as MixingBoardUiState.Board
        assertTrue(board.sheet is BoardSheet.None)
    }

    @Test
    fun `a failed (timed out) cycle action re-syncs the board from the server instead of trusting the stale cache`() = runTest {
        // The real bug this guards: a machine_cycle request can time out client-side (flaky
        // WiFi/slow backend) while Station 2 actually accepted it. The late confirmation gets
        // dropped by MqttRepositoryImpl's correlation matching, so the ONLY way the board learns
        // the true state is a fresh overview fetch — never trusting whatever it had cached before.
        val refreshedAfterServerActuallyFinished = mainOverview.copy(
            equipment = listOf(equipment("MXR-01"), equipment("MXR-02", status = "Available")),
            activeCycles = emptyList(),
        )
        whenever(mockUseCase.fetchOverview(eq(MixingArea.Main), anyOrNull(), anyOrNull())).thenReturn(
            Result.success(mainOverview), Result.success(refreshedAfterServerActuallyFinished))
        whenever(mockUseCase.fetchReadyCollections(eq(MixingArea.Main))).thenReturn(Result.success(readyCollections))
        viewModel.openArea(MixingArea.Main)
        advanceUntilIdle()
        viewModel.machineChosen("MXR-02")

        whenever(mockUseCase.finish("MXR-02", "CYC_9")).thenReturn(
            com.mitas.ppnam.station2aa.domain.model.MachineCycleOutcome.Failed("Timed out waiting for Station 2"))
        viewModel.finishCycle()
        advanceUntilIdle()

        verify(mockUseCase, times(2)).fetchOverview(eq(MixingArea.Main), anyOrNull(), anyOrNull())
        val board = viewModel.uiState.value as MixingBoardUiState.Board
        assertEquals("Available", board.overview.equipment.single { it.machineCode == "MXR-02" }.status)
        assertTrue(board.overview.activeCycles.isEmpty())
        assertTrue(board.sheet is BoardSheet.None)
        assertFalse(board.busy)
    }

    private val jandiOverview = AreaOverview(
        equipment = listOf(
            equipment("JAN-MIX-01", role = "Mixer", area = MixingArea.Jandi),
            equipment("JAN-04", role = "ProductionMachine", area = MixingArea.Jandi),
        ),
        activeCycles = emptyList(),
        readyMixes = emptyList(),
        activeRuns = emptyList(),
        readyCollections = listOf(readyCollection("COL_000124", validMixers = listOf("JAN-MIX-01"))),
    )

    private suspend fun openJandiBoard() {
        whenever(mockUseCase.fetchOverview(anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(Result.success(jandiOverview))
        whenever(mockUseCase.fetchReadyCollections(eq(MixingArea.Jandi)))
            .thenReturn(Result.success(jandiOverview.readyCollections))
        viewModel.openArea(MixingArea.Jandi)
    }

    private fun acceptedOutcome(overview: AreaOverview) = MachineCycleOutcome.Accepted(
        action = "Started", machineCode = "JAN-04", cycleId = "CYC_000140",
        mixBatchId = null, productionRunId = "RUN_000140", affectedMixBatchIds = emptyList(),
        alreadyFinished = false, forceClosed = false, approverDisplayName = null,
        areaStatus = overview,
    )

    @Test
    fun `a JANDI mixer start offers the three routes and blocks until one is chosen`() = runTest {
        // Not stubbed until needed: an unstubbed suspend fun returning a non-null sealed class
        // hands applyOutcome a null, which cannot match any `when` branch.
        whenever(mockUseCase.startJandiMixer(any(), any(), any()))
            .thenReturn(acceptedOutcome(jandiOverview))
        openJandiBoard()
        advanceUntilIdle()
        viewModel.selectCollection("COL_000124")
        viewModel.machineChosen("JAN-MIX-01")
        advanceUntilIdle()

        val sheet = (viewModel.uiState.value as MixingBoardUiState.Board).sheet
            as BoardSheet.StartConfirm
        assertEquals(listOf("JAN-02", "JAN-03", "JAN-DRUM-01"), sheet.routeOptions)
        assertNull(sheet.selectedRoute)

        // Confirming without a route must not reach the server.
        viewModel.confirmStart()
        advanceUntilIdle()
        verify(mockUseCase, never()).startJandiMixer(any(), any(), any())

        viewModel.selectRoute("JAN-DRUM-01")
        viewModel.confirmStart()
        advanceUntilIdle()
        verify(mockUseCase).startJandiMixer("JAN-MIX-01", "COL_000124", "JAN-DRUM-01")
    }

    @Test
    fun `a scanned Main mixer code is cached and used as the JANDI 4 source`() = runTest {
        // "The Android app may cache the scanned Main mixer code locally until the JANDI 4 start
        // request. There is no separate source-selection MQTT mutation."
        whenever(mockUseCase.startJandi4(any(), anyOrNull(), anyOrNull()))
            .thenReturn(acceptedOutcome(jandiOverview))
        openJandiBoard()
        advanceUntilIdle()

        viewModel.cacheMainSourceMixerCode("MXR-02")
        viewModel.machineChosen("JAN-04")
        advanceUntilIdle()
        viewModel.confirmStart()
        advanceUntilIdle()

        verify(mockUseCase).startJandi4("JAN-04", null, "MXR-02")
    }

    @Test
    fun `the cached Main mixer code is cleared once a JANDI 4 start is accepted`() = runTest {
        whenever(mockUseCase.startJandi4(any(), anyOrNull(), anyOrNull()))
            .thenReturn(acceptedOutcome(jandiOverview))
        whenever(mockUseCase.fetchOverview(eq(MixingArea.Main), anyOrNull(), anyOrNull()))
            .thenReturn(Result.success(AreaOverview.EMPTY))
        openJandiBoard()
        advanceUntilIdle()
        viewModel.cacheMainSourceMixerCode("MXR-02")
        viewModel.machineChosen("JAN-04")
        advanceUntilIdle()
        viewModel.confirmStart()
        advanceUntilIdle()
        verify(mockUseCase).startJandi4("JAN-04", null, "MXR-02")

        // A second JANDI 4 start must not silently reuse the consumed code. Finding 3's
        // sheet-local pre-flight now catches this before the use case is ever called — if the
        // stale code had leaked through, this would instead be a second startJandi4 invocation.
        viewModel.machineChosen("JAN-04")
        advanceUntilIdle()
        viewModel.confirmStart()
        advanceUntilIdle()
        verify(mockUseCase, times(1)).startJandi4(any(), anyOrNull(), anyOrNull())
        val sheet = (viewModel.uiState.value as MixingBoardUiState.Board).sheet
        assertTrue(sheet is BoardSheet.StartConfirm)
        assertNotNull((sheet as BoardSheet.StartConfirm).validationError)
    }

    // ---- Finding 1: cacheMainSourceMixerCode must be reachable via a scan --------------------

    @Test
    fun `a scanned Main mixer code reaches the cache through machineChosen, not the setter directly`() = runTest {
        // The review's specific criticism: the existing cache tests call cacheMainSourceMixerCode
        // directly, which gives false confidence that a real scan ever reaches it. This test drives
        // the cache exclusively through machineChosen, as a real scan would.
        openMainBoard(); advanceUntilIdle()
        val messages = mutableListOf<String>()
        val collectJob = launch { viewModel.messages.toList(messages) }

        viewModel.machineChosen("MXR-01") // Main-area Mixer, Available, no active cycle.
        advanceUntilIdle()

        assertTrue("operator should be told the code was cached for the next JANDI 4 start",
            messages.any { it.contains("MXR-01") })
        val board = viewModel.uiState.value as MixingBoardUiState.Board
        assertTrue("caching a Main mixer code must not open a sheet", board.sheet is BoardSheet.None)
        collectJob.cancel()

        // Prove the cache actually reached the JANDI 4 path — exercised only via machineChosen.
        whenever(mockUseCase.fetchOverview(eq(MixingArea.Main), anyOrNull(), anyOrNull()))
            .thenReturn(Result.success(AreaOverview.EMPTY))
        whenever(mockUseCase.startJandi4(any(), anyOrNull(), anyOrNull()))
            .thenReturn(acceptedOutcome(mainOverview))
        viewModel.machineChosen("JAN-04")
        advanceUntilIdle()
        viewModel.confirmStart()
        advanceUntilIdle()

        verify(mockUseCase).startJandi4("JAN-04", null, "MXR-01")
    }

    // ---- Finding 2: mainSourceOptions must not depend on the JANDI-scoped overview -----------

    @Test
    fun `the JANDI 4 sheet's mainSourceOptions fetches Main separately and excludes quarantined mixes`() = runTest {
        openJandiBoard(); advanceUntilIdle()
        // jandiOverview.readyMixes is empty — a Main mix ONLY ever shows up here via the
        // Main-area fetch that machineChosen must trigger for JAN-04.
        val eligible = readyMix("MIX_MAIN_1", validNext = listOf("EXT-01"))
        val quarantined = readyMix("MIX_MAIN_Q", validNext = listOf("EXT-01"),
            status = "Quarantined", completionMode = "ForceClosed", isAssignable = false)
        val mainOnly = AreaOverview(
            equipment = emptyList(), activeCycles = emptyList(),
            readyMixes = listOf(eligible, quarantined), activeRuns = emptyList(),
        )
        whenever(mockUseCase.fetchOverview(eq(MixingArea.Main), anyOrNull(), anyOrNull()))
            .thenReturn(Result.success(mainOnly))

        viewModel.machineChosen("JAN-04")
        advanceUntilIdle()

        val board = viewModel.uiState.value as MixingBoardUiState.Board
        assertFalse("busy must not be stranded once the sheet opens", board.busy)
        val sheet = board.sheet as BoardSheet.StartConfirm
        assertEquals(listOf("MIX_MAIN_1"), sheet.mainSourceOptions.map { it.mixBatchId })
    }

    // ---- Finding 3: JANDI 4 confirm with no source is a sheet-local validation error ---------

    @Test
    fun `confirming JANDI 4 with no source and no cached code sets a validation error and never calls startJandi4`() = runTest {
        openJandiBoard(); advanceUntilIdle()
        whenever(mockUseCase.fetchOverview(eq(MixingArea.Main), anyOrNull(), anyOrNull()))
            .thenReturn(Result.success(AreaOverview.EMPTY))
        viewModel.machineChosen("JAN-04")
        advanceUntilIdle()

        viewModel.confirmStart()
        advanceUntilIdle()

        val sheet = (viewModel.uiState.value as MixingBoardUiState.Board).sheet
        assertTrue("the sheet must stay open so the operator can fix it in place",
            sheet is BoardSheet.StartConfirm)
        assertNotNull((sheet as BoardSheet.StartConfirm).validationError)
        verify(mockUseCase, never()).startJandi4(any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `submitForceClose refuses blank credentials without touching the wire`() = runTest {
        openMainBoard(); advanceUntilIdle()
        viewModel.machineChosen("MXR-02")
        viewModel.openForceClose()
        viewModel.submitForceClose("", "", "")
        advanceUntilIdle()

        verify(mockUseCase, never()).forceClose(any(), any(), any(), any(), any())
        val sheet = (viewModel.uiState.value as MixingBoardUiState.Board).sheet
        assertNotNull((sheet as BoardSheet.ForceCloseDialog).validationError)
    }
}
