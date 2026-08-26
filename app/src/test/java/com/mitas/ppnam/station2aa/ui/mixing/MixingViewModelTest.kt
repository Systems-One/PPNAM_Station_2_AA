package com.mitas.ppnam.station2aa.ui.mixing

import com.mitas.ppnam.station2aa.data.mqtt.NextAction
import com.mitas.ppnam.station2aa.data.rfid.ScanEventBus
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.data.session.canShow
import com.mitas.ppnam.station2aa.domain.model.ActiveJobsPage
import com.mitas.ppnam.station2aa.domain.model.BomLine
import com.mitas.ppnam.station2aa.domain.model.IngredientScanOutcome
import com.mitas.ppnam.station2aa.domain.model.ProductionOrder
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.usecase.AuthUseCase
import com.mitas.ppnam.station2aa.domain.usecase.MixingUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class MixingViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var mockUseCase: MixingUseCase
    private lateinit var mockScanEventBus: ScanEventBus
    private lateinit var mockMqttRepository: MqttRepository
    private lateinit var mockAuthUseCase: AuthUseCase
    private lateinit var mockSessionHolder: OperatorSessionHolder
    private lateinit var viewModel: MixingViewModel

    private val sampleOrder = ProductionOrder(
        docNo = "510019068",
        collectionId = "COL_000001",
        // bagSize set: this line is the bagged fixture the pre-existing bag-scan flow (confirmIngredientScan)
        // exercises throughout this file. The dedicated bulk fixture is bulkOrder, below.
        lines = listOf(BomLine(lineNumber = 0, itemCode = "MAT-001", itemName = "Resin", requiredQty = 1.0,
            bagSize = "25.000 kg"))
    )

    private val bulkOrder = ProductionOrder(
        docNo = "510019068",
        collectionId = "COL_1",
        lines = listOf(BomLine(lineNumber = 0, itemCode = "MAT-BULK", itemName = "LD Mix",
            requiredQty = 100.0, bagSize = null))
    )

    private fun sessionWithActions(vararg actions: String) = OperatorSession(
        operatorSessionId = "session-id",
        operatorId = "op-1",
        operatorName = "Test Operator",
        role = "Worker",
        allowedActions = actions.toList()
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockUseCase = mock()
        mockScanEventBus = mock()
        mockMqttRepository = mock()
        mockAuthUseCase = mock()
        mockSessionHolder = mock()

        whenever(mockMqttRepository.connectionState)
            .thenReturn(MutableStateFlow(MqttConnectionState.DISCONNECTED))
        whenever(mockMqttRepository.stationOnline).thenReturn(MutableStateFlow(true))
        whenever(mockMqttRepository.clockSkewMillis).thenReturn(MutableStateFlow<Long?>(null))
        whenever(mockScanEventBus.events).thenReturn(MutableSharedFlow())
        whenever(mockSessionHolder.session).thenReturn(MutableStateFlow(sessionWithActions("ingredient_collection_cancel")))

        viewModel = MixingViewModel(
            mockUseCase, mockScanEventBus, mockMqttRepository, mockAuthUseCase, mockSessionHolder
        )
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `lookupJob success sets OrderLoaded state`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is MixingUiState.OrderLoaded)
    }

    @Test
    fun `lookupJob failure sets Error state`() = runTest {
        whenever(mockUseCase.lookupJob(any(), any())).thenReturn(Result.failure(Exception("Not found")))
        viewModel.lookupJob("bad")
        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertTrue(state is MixingUiState.Error)
        assertEquals("Not found", (state as MixingUiState.Error).message)
    }

    @Test
    fun `lookupJob forwards collectionId to the use case`() = runTest {
        whenever(mockUseCase.lookupJob("510019068", "COL_000001")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068", "COL_000001")
        advanceUntilIdle()
        verify(mockUseCase).lookupJob("510019068", "COL_000001")
    }

    @Test
    fun `startListeningForPalletScans opens EnteringBagDetails on a pallet scan`() = runTest {
        val events = MutableSharedFlow<com.mitas.ppnam.station2aa.data.rfid.ScanEvent>()
        whenever(mockScanEventBus.events).thenReturn(events)
        val vm = MixingViewModel(mockUseCase, mockScanEventBus, mockMqttRepository, mockAuthUseCase, mockSessionHolder)
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        vm.lookupJob("510019068")
        advanceUntilIdle()

        vm.startListeningForPalletScans("510019068")
        events.emit(com.mitas.ppnam.station2aa.data.rfid.ScanEvent.RfidTag("EPC:300833", java.time.Instant.now()))
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state is MixingUiState.EnteringBagDetails)
        assertEquals("EPC:300833", (state as MixingUiState.EnteringBagDetails).palletTag)
    }

    @Test
    fun `startListeningForPalletScans opens EnteringBagDetails on a barcode scan too`() = runTest {
        val events = MutableSharedFlow<com.mitas.ppnam.station2aa.data.rfid.ScanEvent>()
        whenever(mockScanEventBus.events).thenReturn(events)
        val vm = MixingViewModel(mockUseCase, mockScanEventBus, mockMqttRepository, mockAuthUseCase, mockSessionHolder)
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        vm.lookupJob("510019068")
        advanceUntilIdle()

        vm.startListeningForPalletScans("510019068")
        events.emit(com.mitas.ppnam.station2aa.data.rfid.ScanEvent.Barcode("123456789012", "EAN13", java.time.Instant.now()))
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state is MixingUiState.EnteringBagDetails)
        assertEquals("123456789012", (state as MixingUiState.EnteringBagDetails).palletTag)
    }

    @Test
    fun `cancelBagEntry returns to OrderLoaded`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()

        val events = MutableSharedFlow<com.mitas.ppnam.station2aa.data.rfid.ScanEvent>()
        whenever(mockScanEventBus.events).thenReturn(events)
        val vm = MixingViewModel(mockUseCase, mockScanEventBus, mockMqttRepository, mockAuthUseCase, mockSessionHolder)
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        vm.lookupJob("510019068")
        advanceUntilIdle()
        vm.startListeningForPalletScans("510019068")
        events.emit(com.mitas.ppnam.station2aa.data.rfid.ScanEvent.RfidTag("EPC:300833", java.time.Instant.now()))
        advanceUntilIdle()

        vm.cancelBagEntry()

        assertTrue(vm.uiState.value is MixingUiState.OrderLoaded)
    }

    @Test
    fun `selectLine arms a line and OrderLoaded reflects the selection`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()

        viewModel.selectLine(0)

        val state = viewModel.uiState.value
        assertTrue(state is MixingUiState.OrderLoaded)
        assertEquals(0, (state as MixingUiState.OrderLoaded).selectedLineNumber)
    }

    @Test
    fun `selecting a line number that does not exist on the order is ignored`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()

        viewModel.selectLine(99)

        val state = viewModel.uiState.value
        assertTrue(state is MixingUiState.OrderLoaded)
        assertNull((state as MixingUiState.OrderLoaded).selectedLineNumber)
    }

    @Test
    fun `confirmIngredientScan with no line armed does not call scanIngredient and prompts for a line`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()

        val errors = mutableListOf<String>()
        val job = launch(testDispatcher) { viewModel.supervisorError.collect { errors.add(it) } }

        viewModel.confirmIngredientScan("EPC:300833", "full", 2.0)
        advanceUntilIdle()

        verify(mockUseCase, never()).scanIngredient(any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
        assertTrue(errors.isNotEmpty())
        assertTrue(viewModel.uiState.value is MixingUiState.OrderLoaded)
        job.cancel()
    }

    @Test
    fun `confirmIngredientScan on Accepted replaces order lines and returns to OrderLoaded`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()
        viewModel.selectLine(0)

        val updatedLine = BomLine(lineNumber = 0, itemCode = "MAT-001", itemName = "Resin", requiredQty = 1.0, remainingQty = 0.0)
        whenever(mockUseCase.scanIngredient("COL_000001", "EPC:300833", "MAT-001", bagSizeOption = "full", bagCount = 2.0))
            .thenReturn(Result.success(IngredientScanOutcome.Accepted(
                listOf(updatedLine),
                collectionSummary = "",
                collectionStatus = "Collecting",
                overCollectionToleranceBags = null,
                nextAction = NextAction.SCAN_INGREDIENT,
            )))

        viewModel.confirmIngredientScan("EPC:300833", "full", 2.0)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is MixingUiState.OrderLoaded)
        assertEquals(listOf(updatedLine), (state as MixingUiState.OrderLoaded).order.lines)
    }

    @Test
    fun `an accepted scan emits Accepted scan feedback`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()
        viewModel.selectLine(0)

        val feedback = mutableListOf<ScanFeedback>()
        val job = launch(testDispatcher) { viewModel.scanFeedback.collect { feedback.add(it) } }

        whenever(mockUseCase.scanIngredient("COL_000001", "EPC:300833", "MAT-001", bagSizeOption = "full", bagCount = 2.0))
            .thenReturn(Result.success(IngredientScanOutcome.Accepted(
                sampleOrder.lines,
                collectionSummary = "",
                collectionStatus = "Collecting",
                overCollectionToleranceBags = null,
                nextAction = NextAction.SCAN_INGREDIENT,
            )))
        viewModel.confirmIngredientScan("EPC:300833", "full", 2.0)
        advanceUntilIdle()

        assertEquals(listOf(ScanFeedback.Accepted), feedback)
        job.cancel()
    }

    @Test
    fun `a rejected scan emits Rejected scan feedback`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()
        viewModel.selectLine(0)

        val feedback = mutableListOf<ScanFeedback>()
        val job = launch(testDispatcher) { viewModel.scanFeedback.collect { feedback.add(it) } }

        whenever(mockUseCase.scanIngredient("COL_000001", "EPC:300833", "MAT-001", bagSizeOption = "full", bagCount = 2.0))
            .thenReturn(Result.success(IngredientScanOutcome.Rejected("Wrong material")))
        viewModel.confirmIngredientScan("EPC:300833", "full", 2.0)
        advanceUntilIdle()

        assertEquals(listOf(ScanFeedback.Rejected), feedback)
        job.cancel()
    }

    @Test
    fun `a pallet scan that opens the bag dialog emits Armed scan feedback`() = runTest {
        val events = MutableSharedFlow<com.mitas.ppnam.station2aa.data.rfid.ScanEvent>()
        whenever(mockScanEventBus.events).thenReturn(events)
        val vm = MixingViewModel(mockUseCase, mockScanEventBus, mockMqttRepository, mockAuthUseCase, mockSessionHolder)
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        vm.lookupJob("510019068")
        advanceUntilIdle()

        val feedback = mutableListOf<ScanFeedback>()
        val job = launch(testDispatcher) { vm.scanFeedback.collect { feedback.add(it) } }

        vm.startListeningForPalletScans("510019068")
        events.emit(com.mitas.ppnam.station2aa.data.rfid.ScanEvent.RfidTag("EPC:300833", java.time.Instant.now()))
        advanceUntilIdle()

        assertEquals(listOf(ScanFeedback.Armed), feedback)
        job.cancel()
    }

    @Test
    fun `a scan with no resolvable line emits Rejected scan feedback`() = runTest {
        // Two unsatisfied lines and nothing armed: the scan is refused locally with a prompt.
        val ambiguousOrder = sampleOrder.copy(lines = listOf(
            BomLine(lineNumber = 0, itemCode = "MAT-001", itemName = "Resin", requiredQty = 1.0, bagSize = "25.000 kg"),
            BomLine(lineNumber = 1, itemCode = "MAT-002", itemName = "Filler", requiredQty = 1.0, bagSize = "25.000 kg"),
        ))
        val events = MutableSharedFlow<com.mitas.ppnam.station2aa.data.rfid.ScanEvent>()
        whenever(mockScanEventBus.events).thenReturn(events)
        val vm = MixingViewModel(mockUseCase, mockScanEventBus, mockMqttRepository, mockAuthUseCase, mockSessionHolder)
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(ambiguousOrder))
        vm.lookupJob("510019068")
        advanceUntilIdle()

        val feedback = mutableListOf<ScanFeedback>()
        val job = launch(testDispatcher) { vm.scanFeedback.collect { feedback.add(it) } }

        vm.startListeningForPalletScans("510019068")
        events.emit(com.mitas.ppnam.station2aa.data.rfid.ScanEvent.RfidTag("EPC:300833", java.time.Instant.now()))
        advanceUntilIdle()

        assertEquals(listOf(ScanFeedback.Rejected), feedback)
        job.cancel()
    }

    @Test
    fun `an accepted scan refreshes the order's status and summary from the server`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()
        viewModel.selectLine(0)
        whenever(mockUseCase.scanIngredient(any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(Result.success(IngredientScanOutcome.Accepted(
                updatedLines = sampleOrder.lines,
                collectionSummary = "All products collected.",
                collectionStatus = "ReadyForMixing",
                overCollectionToleranceBags = 1.0,
                nextAction = com.mitas.ppnam.station2aa.data.mqtt.NextAction.OPEN_MIXING,
            )))
        viewModel.confirmIngredientScan("TAG-1", "full", 2.0)
        advanceUntilIdle()

        val state = viewModel.uiState.value as MixingUiState.OrderLoaded
        assertEquals("ReadyForMixing", state.order.collectionStatus)
        assertEquals("All products collected.", state.order.summary)
    }

    @Test
    fun `an accepted scan against a now-satisfied armed line disarms it`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()
        viewModel.selectLine(0)

        val satisfiedLine = BomLine(lineNumber = 0, itemCode = "MAT-001", itemName = "Resin", requiredQty = 1.0, remainingQty = 0.0)
        whenever(mockUseCase.scanIngredient("COL_000001", "EPC:300833", "MAT-001", bagSizeOption = "full", bagCount = 2.0))
            .thenReturn(Result.success(IngredientScanOutcome.Accepted(
                listOf(satisfiedLine),
                collectionSummary = "",
                collectionStatus = "Collecting",
                overCollectionToleranceBags = null,
                nextAction = NextAction.SCAN_INGREDIENT,
            )))

        viewModel.confirmIngredientScan("EPC:300833", "full", 2.0)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is MixingUiState.OrderLoaded)
        assertNull((state as MixingUiState.OrderLoaded).selectedLineNumber)
    }

    @Test
    fun `an accepted scan against a still-unsatisfied armed line keeps it armed`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()
        viewModel.selectLine(0)

        val partialLine = BomLine(lineNumber = 0, itemCode = "MAT-001", itemName = "Resin", requiredQty = 3.0, remainingQty = 1.0)
        whenever(mockUseCase.scanIngredient("COL_000001", "EPC:300833", "MAT-001", bagSizeOption = "full", bagCount = 2.0))
            .thenReturn(Result.success(IngredientScanOutcome.Accepted(
                listOf(partialLine),
                collectionSummary = "",
                collectionStatus = "Collecting",
                overCollectionToleranceBags = null,
                nextAction = NextAction.SCAN_INGREDIENT,
            )))

        viewModel.confirmIngredientScan("EPC:300833", "full", 2.0)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is MixingUiState.OrderLoaded)
        assertEquals(0, (state as MixingUiState.OrderLoaded).selectedLineNumber)
    }

    @Test
    fun `confirmIngredientScan on NeedsManagerApproval sets IngredientExceptionApproval state`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()
        viewModel.selectLine(0)

        whenever(mockUseCase.scanIngredient("COL_000001", "EPC:300833", "MAT-001", bagSizeOption = "full", bagCount = 2.0)).thenReturn(
            Result.success(
                IngredientScanOutcome.NeedsManagerApproval(
                    collectionId = "COL_000001", sourceBarcode = "EPC:300833",
                    requestedMaterialCode = "MAT-001", bagSizeOption = "full", bagCount = 2.0,
                    quantity = null,
                    reason = "Wrong material",
                )
            )
        )

        viewModel.confirmIngredientScan("EPC:300833", "full", 2.0)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is MixingUiState.IngredientExceptionApproval)
        assertEquals("Wrong material", (state as MixingUiState.IngredientExceptionApproval).reason)
    }

    @Test
    fun `confirmIngredientScan on NeedsRecovery sets PalletRecoveryPrompt state`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()
        viewModel.selectLine(0)

        whenever(mockUseCase.scanIngredient("COL_000001", "EPC:300833", "MAT-001", bagSizeOption = "full", bagCount = 2.0)).thenReturn(
            Result.success(IngredientScanOutcome.NeedsRecovery("Pallet not in Holding"))
        )

        viewModel.confirmIngredientScan("EPC:300833", "full", 2.0)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is MixingUiState.PalletRecoveryPrompt)
        assertEquals("EPC:300833", (state as MixingUiState.PalletRecoveryPrompt).palletTag)
    }

    @Test
    fun `submitting approval resubmits the pending scan with credentials`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()
        viewModel.selectLine(0)

        whenever(mockUseCase.scanIngredient("COL_000001", "EPC:300833", "MAT-001", bagSizeOption = "full", bagCount = 2.0)).thenReturn(
            Result.success(
                IngredientScanOutcome.NeedsManagerApproval(
                    collectionId = "COL_000001", sourceBarcode = "EPC:300833",
                    requestedMaterialCode = "MAT-001", bagSizeOption = "full", bagCount = 2.0,
                    quantity = null,
                    reason = "Wrong material",
                )
            )
        )
        viewModel.confirmIngredientScan("EPC:300833", "full", 2.0)
        advanceUntilIdle()

        val updatedLine = BomLine(lineNumber = 0, itemCode = "MAT-001", itemName = "Resin", requiredQty = 1.0, remainingQty = 0.0)
        whenever(
            mockUseCase.scanIngredient(
                "COL_000001", "EPC:300833", "MAT-001",
                bagSizeOption = "full", bagCount = 2.0, quantity = null,
                managerUsername = "manager1", managerPassword = "secret",
                auditReason = "Approved after verified spillage.",
            )
        ).thenReturn(Result.success(IngredientScanOutcome.Accepted(
                listOf(updatedLine),
                collectionSummary = "",
                collectionStatus = "Collecting",
                overCollectionToleranceBags = null,
                nextAction = NextAction.SCAN_INGREDIENT,
            )))

        viewModel.submitManagerApproval("manager1", "secret", "Approved after verified spillage.")
        advanceUntilIdle()

        verify(mockUseCase).scanIngredient(
            eq("COL_000001"), eq("EPC:300833"), eq("MAT-001"), eq("full"), eq(2.0), isNull(),
            eq("manager1"), eq("secret"), eq("Approved after verified spillage."),
        )
        assertTrue(viewModel.uiState.value is MixingUiState.OrderLoaded)
    }

    @Test
    fun `submitting approval with no pending scan is a no-op`() = runTest {
        viewModel.submitManagerApproval("manager1", "secret", "reason")
        advanceUntilIdle()

        verify(mockUseCase, never()).scanIngredient(any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `submitting approval a second time after it already resolved is a no-op`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()
        viewModel.selectLine(0)

        whenever(mockUseCase.scanIngredient("COL_000001", "EPC:300833", "MAT-001", bagSizeOption = "full", bagCount = 2.0)).thenReturn(
            Result.success(
                IngredientScanOutcome.NeedsManagerApproval(
                    collectionId = "COL_000001", sourceBarcode = "EPC:300833",
                    requestedMaterialCode = "MAT-001", bagSizeOption = "full", bagCount = 2.0,
                    quantity = null,
                    reason = "Wrong material",
                )
            )
        )
        viewModel.confirmIngredientScan("EPC:300833", "full", 2.0)
        advanceUntilIdle()

        val updatedLine = BomLine(lineNumber = 0, itemCode = "MAT-001", itemName = "Resin", requiredQty = 1.0, remainingQty = 0.0)
        whenever(
            mockUseCase.scanIngredient(
                "COL_000001", "EPC:300833", "MAT-001",
                bagSizeOption = "full", bagCount = 2.0, quantity = null,
                managerUsername = "manager1", managerPassword = "secret", auditReason = "reason",
            )
        ).thenReturn(Result.success(IngredientScanOutcome.Accepted(
                listOf(updatedLine),
                collectionSummary = "",
                collectionStatus = "Collecting",
                overCollectionToleranceBags = null,
                nextAction = NextAction.SCAN_INGREDIENT,
            )))
        viewModel.submitManagerApproval("manager1", "secret", "reason")
        advanceUntilIdle()

        // The approval already resolved (Accepted) — a second submission must not re-fire it.
        viewModel.submitManagerApproval("manager1", "secret", "reason")
        advanceUntilIdle()

        verify(mockUseCase, times(1)).scanIngredient(
            eq("COL_000001"), eq("EPC:300833"), eq("MAT-001"), eq("full"), eq(2.0), isNull(),
            eq("manager1"), eq("secret"), eq("reason"),
        )
    }

    @Test
    fun `submitManagerApproval refuses a blank audit reason without touching the wire`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()
        viewModel.selectLine(0)

        whenever(mockUseCase.scanIngredient("COL_000001", "EPC:300833", "MAT-001", bagSizeOption = "full", bagCount = 2.0)).thenReturn(
            Result.success(
                IngredientScanOutcome.NeedsManagerApproval(
                    collectionId = "COL_000001", sourceBarcode = "EPC:300833",
                    requestedMaterialCode = "MAT-001", bagSizeOption = "full", bagCount = 2.0,
                    quantity = null,
                    reason = "Wrong material",
                )
            )
        )
        viewModel.confirmIngredientScan("EPC:300833", "full", 2.0)
        advanceUntilIdle()

        viewModel.submitManagerApproval("manager1", "secret", "")
        advanceUntilIdle()

        // Never even attempted a resubmit with these credentials — the guard returns before the
        // wire call is built, not after a rejection.
        verify(mockUseCase, never()).scanIngredient(
            anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(),
            eq("manager1"), eq("secret"), anyOrNull(),
        )
        val state = viewModel.uiState.value
        assertTrue(state is MixingUiState.IngredientExceptionApproval)
        state as MixingUiState.IngredientExceptionApproval
        assertEquals("Wrong material", state.reason)
        assertEquals("Audit reason is required.", state.validationError)
    }

    @Test
    fun `submitManagerApproval refuses blank credentials without touching the wire`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()
        viewModel.selectLine(0)

        whenever(mockUseCase.scanIngredient("COL_000001", "EPC:300833", "MAT-001", bagSizeOption = "full", bagCount = 2.0)).thenReturn(
            Result.success(
                IngredientScanOutcome.NeedsManagerApproval(
                    collectionId = "COL_000001", sourceBarcode = "EPC:300833",
                    requestedMaterialCode = "MAT-001", bagSizeOption = "full", bagCount = 2.0,
                    quantity = null,
                    reason = "Wrong material",
                )
            )
        )
        viewModel.confirmIngredientScan("EPC:300833", "full", 2.0)
        advanceUntilIdle()

        viewModel.submitManagerApproval("", "", "reason")
        advanceUntilIdle()

        verify(mockUseCase, never()).scanIngredient(
            anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(),
            any(), any(), anyOrNull(),
        )
        val state = viewModel.uiState.value
        assertTrue(state is MixingUiState.IngredientExceptionApproval)
        assertEquals(
            "Manager username and password are required.",
            (state as MixingUiState.IngredientExceptionApproval).validationError,
        )
    }

    @Test
    fun `a fast double-tap on Approve fires exactly one credentialed resubmit`() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        runTest(dispatcher) {
            val vm = MixingViewModel(mockUseCase, mockScanEventBus, mockMqttRepository, mockAuthUseCase, mockSessionHolder)

            whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
            vm.lookupJob("510019068")
            advanceUntilIdle()
            vm.selectLine(0)

            whenever(mockUseCase.scanIngredient("COL_000001", "EPC:300833", "MAT-001", bagSizeOption = "full", bagCount = 2.0)).thenReturn(
                Result.success(
                    IngredientScanOutcome.NeedsManagerApproval(
                        collectionId = "COL_000001", sourceBarcode = "EPC:300833",
                        requestedMaterialCode = "MAT-001", bagSizeOption = "full", bagCount = 2.0,
                        quantity = null,
                        reason = "Wrong material",
                    )
                )
            )
            vm.confirmIngredientScan("EPC:300833", "full", 2.0)
            advanceUntilIdle()

            val approvalGate = CompletableDeferred<Result<IngredientScanOutcome>>()
            mockUseCase.stub {
                onBlocking {
                    scanIngredient(
                        eq("COL_000001"), eq("EPC:300833"), eq("MAT-001"), eq("full"), eq(2.0), isNull(),
                        eq("manager1"), eq("secret"), eq("Approved after verified spillage."),
                    )
                } doSuspendableAnswer { approvalGate.await() }
            }

            // The first tap's request is genuinely in flight (suspended on the gate) when the
            // second tap lands — this is what an unconfined dispatcher can't reproduce.
            vm.submitManagerApproval("manager1", "secret", "Approved after verified spillage.")
            runCurrent()
            // Inline-busy, not a whole-screen Loading: the line list stays rendered while the
            // request is out (see MixingUiState.OrderLoaded.pendingLabel).
            assertTrue(
                "sanity check: the first submission should be in flight before the double-tap",
                (vm.uiState.value as? MixingUiState.OrderLoaded)?.isBusy == true
            )
            vm.submitManagerApproval("manager1", "secret", "Approved after verified spillage.")
            runCurrent()

            val updatedLine = BomLine(lineNumber = 0, itemCode = "MAT-001", itemName = "Resin", requiredQty = 1.0, remainingQty = 0.0)
            approvalGate.complete(Result.success(IngredientScanOutcome.Accepted(
                listOf(updatedLine),
                collectionSummary = "",
                collectionStatus = "Collecting",
                overCollectionToleranceBags = null,
                nextAction = NextAction.SCAN_INGREDIENT,
            )))
            advanceUntilIdle()

            verify(mockUseCase, times(1)).scanIngredient(
                eq("COL_000001"), eq("EPC:300833"), eq("MAT-001"), eq("full"), eq(2.0), isNull(),
                eq("manager1"), eq("secret"), eq("Approved after verified spillage."),
            )
            assertTrue(vm.uiState.value is MixingUiState.OrderLoaded)
        }
    }

    @Test
    fun `cancelManagerApproval cancels an in-flight resubmit so a late response cannot overwrite state`() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        runTest(dispatcher) {
            val vm = MixingViewModel(mockUseCase, mockScanEventBus, mockMqttRepository, mockAuthUseCase, mockSessionHolder)

            whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
            vm.lookupJob("510019068")
            advanceUntilIdle()
            vm.selectLine(0)

            whenever(mockUseCase.scanIngredient("COL_000001", "EPC:300833", "MAT-001", bagSizeOption = "full", bagCount = 2.0)).thenReturn(
                Result.success(
                    IngredientScanOutcome.NeedsManagerApproval(
                        collectionId = "COL_000001", sourceBarcode = "EPC:300833",
                        requestedMaterialCode = "MAT-001", bagSizeOption = "full", bagCount = 2.0,
                        quantity = null,
                        reason = "Wrong material",
                    )
                )
            )
            vm.confirmIngredientScan("EPC:300833", "full", 2.0)
            advanceUntilIdle()
            assertTrue(vm.uiState.value is MixingUiState.IngredientExceptionApproval)

            val approvalGate = CompletableDeferred<Result<IngredientScanOutcome>>()
            mockUseCase.stub {
                onBlocking {
                    scanIngredient(
                        eq("COL_000001"), eq("EPC:300833"), eq("MAT-001"), eq("full"), eq(2.0), isNull(),
                        eq("manager1"), eq("secret"), eq("reason"),
                    )
                } doSuspendableAnswer { approvalGate.await() }
            }

            vm.submitManagerApproval("manager1", "secret", "reason")
            runCurrent()
            assertTrue(
                "sanity check: the resubmit should be in flight before it's cancelled",
                (vm.uiState.value as? MixingUiState.OrderLoaded)?.isBusy == true
            )

            vm.cancelManagerApproval()
            advanceUntilIdle()
            assertTrue(vm.uiState.value is MixingUiState.OrderLoaded)
            assertEquals(0, (vm.uiState.value as MixingUiState.OrderLoaded).selectedLineNumber)

            // The stale response lands late, after the operator has already moved on.
            val updatedLine = BomLine(lineNumber = 0, itemCode = "MAT-001", itemName = "Resin", requiredQty = 1.0, remainingQty = 0.0)
            approvalGate.complete(Result.success(IngredientScanOutcome.Accepted(
                listOf(updatedLine),
                collectionSummary = "",
                collectionStatus = "Collecting",
                overCollectionToleranceBags = null,
                nextAction = NextAction.SCAN_INGREDIENT,
            )))
            advanceUntilIdle()

            // Must still be the plain, unmutated OrderLoaded set by cancelManagerApproval — not
            // clobbered by the cancelled request's outcome.
            val state = vm.uiState.value
            assertTrue(state is MixingUiState.OrderLoaded)
            assertEquals(sampleOrder, (state as MixingUiState.OrderLoaded).order)
        }
    }

    @Test
    fun `submitShortBagWaiver refuses blank credentials or a blank audit reason without touching the wire`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()

        val errors = mutableListOf<String>()
        val job = launch(testDispatcher) { viewModel.supervisorError.collect { errors.add(it) } }

        viewModel.submitShortBagWaiver("MAT-001", 2.0, "manager1", "secret", "")
        advanceUntilIdle()

        verify(mockUseCase, never()).waiveShortBags(any(), any(), any(), any(), any(), any())
        assertTrue(errors.any { it.contains("Audit reason") })
        job.cancel()
    }

    @Test
    fun `submitShortBagWaiver refuses a blank material code without touching the wire`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()

        val errors = mutableListOf<String>()
        val job = launch(testDispatcher) { viewModel.supervisorError.collect { errors.add(it) } }

        viewModel.submitShortBagWaiver("", 2.0, "manager1", "secret", "Short by 2 bags")
        advanceUntilIdle()

        verify(mockUseCase, never()).waiveShortBags(any(), any(), any(), any(), any(), any())
        assertTrue(errors.any { it.contains("material", ignoreCase = true) })
        job.cancel()
    }

    @Test
    fun `a fast double-tap on Waive fires exactly one waiver call`() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        runTest(dispatcher) {
            val vm = MixingViewModel(mockUseCase, mockScanEventBus, mockMqttRepository, mockAuthUseCase, mockSessionHolder)

            whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
            vm.lookupJob("510019068")
            advanceUntilIdle()

            val waiverGate = CompletableDeferred<Result<IngredientScanOutcome>>()
            mockUseCase.stub {
                onBlocking {
                    waiveShortBags(eq("COL_000001"), eq("MAT-001"), eq(2.0), eq("manager1"), eq("secret"), eq("Short by 2 bags"))
                } doSuspendableAnswer { waiverGate.await() }
            }

            vm.submitShortBagWaiver("MAT-001", 2.0, "manager1", "secret", "Short by 2 bags")
            runCurrent()
            // Inline-busy, not a whole-screen Loading: the line list stays rendered while the
            // request is out (see MixingUiState.OrderLoaded.pendingLabel).
            assertTrue(
                "sanity check: the first submission should be in flight before the double-tap",
                (vm.uiState.value as? MixingUiState.OrderLoaded)?.isBusy == true
            )
            vm.submitShortBagWaiver("MAT-001", 2.0, "manager1", "secret", "Short by 2 bags")
            runCurrent()

            val updatedLine = BomLine(lineNumber = 0, itemCode = "MAT-001", itemName = "Resin", requiredQty = 1.0, remainingQty = 0.0)
            waiverGate.complete(Result.success(IngredientScanOutcome.Accepted(
                listOf(updatedLine),
                collectionSummary = "",
                collectionStatus = "Collecting",
                overCollectionToleranceBags = null,
                nextAction = NextAction.SCAN_INGREDIENT,
            )))
            advanceUntilIdle()

            verify(mockUseCase, times(1)).waiveShortBags(
                eq("COL_000001"), eq("MAT-001"), eq(2.0), eq("manager1"), eq("secret"), eq("Short by 2 bags"),
            )
        }
    }

    @Test
    fun `submitShortBagWaiver forwards to the use case with credentials`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()

        val updatedLine = BomLine(lineNumber = 0, itemCode = "MAT-001", itemName = "Resin", requiredQty = 1.0, remainingQty = 0.0)
        whenever(
            mockUseCase.waiveShortBags("COL_000001", "MAT-001", 2.0, "manager1", "secret", "Short by 2 bags")
        ).thenReturn(Result.success(IngredientScanOutcome.Accepted(
                listOf(updatedLine),
                collectionSummary = "",
                collectionStatus = "Collecting",
                overCollectionToleranceBags = null,
                nextAction = NextAction.SCAN_INGREDIENT,
            )))

        viewModel.submitShortBagWaiver("MAT-001", 2.0, "manager1", "secret", "Short by 2 bags")
        advanceUntilIdle()

        verify(mockUseCase).waiveShortBags("COL_000001", "MAT-001", 2.0, "manager1", "secret", "Short by 2 bags")
        assertTrue(viewModel.uiState.value is MixingUiState.OrderLoaded)
    }

    @Test
    fun `a rejected waiver sets ShortBagWaiverNeedsApproval and never populates the pending scan-approval state`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()

        whenever(mockUseCase.waiveShortBags("COL_000001", "MAT-001", 2.0, "manager1", "secret", "Short by 2 bags"))
            .thenReturn(
                Result.success(
                    IngredientScanOutcome.NeedsApprovalForWaiver(
                        collectionId = "COL_000001", requestedMaterialCode = "MAT-001",
                        shortBagCount = 2.0, reason = "Manager approval required",
                    )
                )
            )

        viewModel.submitShortBagWaiver("MAT-001", 2.0, "manager1", "secret", "Short by 2 bags")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is MixingUiState.ShortBagWaiverNeedsApproval)
        state as MixingUiState.ShortBagWaiverNeedsApproval
        assertEquals("MAT-001", state.requestedMaterialCode)
        assertEquals(2.0, state.shortBagCount, 0.0)
        assertEquals("Manager approval required", state.reason)

        // A rejected waiver must NEVER be resubmittable through the scan-resubmit path — verify
        // submitManagerApproval() (which only acts on pendingApproval) is a no-op here.
        viewModel.submitManagerApproval("manager1", "secret", "reason")
        advanceUntilIdle()
        verify(mockUseCase, never()).scanIngredient(any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `a stray scan while the waiver-approval dialog is open is ignored and the dialog survives`() = runTest {
        val events = MutableSharedFlow<com.mitas.ppnam.station2aa.data.rfid.ScanEvent>()
        whenever(mockScanEventBus.events).thenReturn(events)
        val vm = MixingViewModel(mockUseCase, mockScanEventBus, mockMqttRepository, mockAuthUseCase, mockSessionHolder)
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        vm.lookupJob("510019068")
        advanceUntilIdle()
        vm.startListeningForPalletScans("510019068")

        whenever(mockUseCase.waiveShortBags("COL_000001", "MAT-001", 2.0, "manager1", "secret", "reason"))
            .thenReturn(
                Result.success(
                    IngredientScanOutcome.NeedsApprovalForWaiver(
                        collectionId = "COL_000001", requestedMaterialCode = "MAT-001",
                        shortBagCount = 2.0, reason = "Manager approval required",
                    )
                )
            )
        vm.submitShortBagWaiver("MAT-001", 2.0, "manager1", "secret", "reason")
        advanceUntilIdle()
        assertTrue(vm.uiState.value is MixingUiState.ShortBagWaiverNeedsApproval)

        events.emit(com.mitas.ppnam.station2aa.data.rfid.ScanEvent.RfidTag("EPC:999999", java.time.Instant.now()))
        advanceUntilIdle()

        assertTrue(
            "a stray scan mid-waiver-dialog must not clobber the waiver-approval prompt",
            vm.uiState.value is MixingUiState.ShortBagWaiverNeedsApproval
        )
    }

    @Test
    fun `cancelShortBagWaiver cancels an in-flight waiver so a late response cannot overwrite state`() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        runTest(dispatcher) {
            val vm = MixingViewModel(mockUseCase, mockScanEventBus, mockMqttRepository, mockAuthUseCase, mockSessionHolder)

            whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
            vm.lookupJob("510019068")
            advanceUntilIdle()

            val waiverGate = CompletableDeferred<Result<IngredientScanOutcome>>()
            mockUseCase.stub {
                onBlocking {
                    waiveShortBags(eq("COL_000001"), eq("MAT-001"), eq(2.0), eq("manager1"), eq("secret"), eq("reason"))
                } doSuspendableAnswer { waiverGate.await() }
            }

            vm.submitShortBagWaiver("MAT-001", 2.0, "manager1", "secret", "reason")
            runCurrent()
            assertTrue(
                "sanity check: the waiver should be in flight before it's cancelled",
                (vm.uiState.value as? MixingUiState.OrderLoaded)?.isBusy == true
            )

            vm.cancelShortBagWaiver()
            advanceUntilIdle()
            assertTrue(vm.uiState.value is MixingUiState.OrderLoaded)

            // The stale response lands late, after the operator has already moved on.
            val updatedLine = BomLine(lineNumber = 0, itemCode = "MAT-001", itemName = "Resin", requiredQty = 1.0, remainingQty = 0.0)
            waiverGate.complete(Result.success(IngredientScanOutcome.Accepted(
                listOf(updatedLine),
                collectionSummary = "",
                collectionStatus = "Collecting",
                overCollectionToleranceBags = null,
                nextAction = NextAction.SCAN_INGREDIENT,
            )))
            advanceUntilIdle()

            // Must still be the plain, unmutated OrderLoaded set by cancelShortBagWaiver — not
            // clobbered by the cancelled request's outcome.
            val state = vm.uiState.value
            assertTrue(state is MixingUiState.OrderLoaded)
            assertEquals(sampleOrder, (state as MixingUiState.OrderLoaded).order)
        }
    }

    @Test
    fun `cancelShortBagWaiver dismisses a rejected waiver and returns to OrderLoaded`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()

        whenever(mockUseCase.waiveShortBags("COL_000001", "MAT-001", 2.0, "manager1", "secret", "Short by 2 bags"))
            .thenReturn(
                Result.success(
                    IngredientScanOutcome.NeedsApprovalForWaiver(
                        collectionId = "COL_000001", requestedMaterialCode = "MAT-001",
                        shortBagCount = 2.0, reason = "Manager approval required",
                    )
                )
            )
        viewModel.submitShortBagWaiver("MAT-001", 2.0, "manager1", "secret", "Short by 2 bags")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is MixingUiState.ShortBagWaiverNeedsApproval)

        viewModel.cancelShortBagWaiver()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is MixingUiState.OrderLoaded)
        assertEquals(sampleOrder, (state as MixingUiState.OrderLoaded).order)
    }

    @Test
    fun `openShortBagWaiver enters ShortBagWaiverEntry for a bagged line`() = runTest {
        val baggedOrder = sampleOrder.copy(lines = listOf(
            BomLine(lineNumber = 0, itemCode = "MAT-001", itemName = "Resin",
                requiredQty = 10.0, bagSize = "25.000 kg")))
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(baggedOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()
        viewModel.openShortBagWaiver("MAT-001")
        val state = viewModel.uiState.value
        assertTrue(state is MixingUiState.ShortBagWaiverEntry)
        assertEquals("MAT-001", (state as MixingUiState.ShortBagWaiverEntry).requestedMaterialCode)
        viewModel.dismissShortBagWaiverEntry()
        assertTrue(viewModel.uiState.value is MixingUiState.OrderLoaded)
    }

    @Test
    fun `a stray scan while the first-attempt waiver dialog is open is ignored`() = runTest {
        val events = MutableSharedFlow<com.mitas.ppnam.station2aa.data.rfid.ScanEvent>()
        whenever(mockScanEventBus.events).thenReturn(events)
        val vm = MixingViewModel(mockUseCase, mockScanEventBus, mockMqttRepository, mockAuthUseCase, mockSessionHolder)
        val baggedOrder = sampleOrder.copy(lines = listOf(
            BomLine(lineNumber = 0, itemCode = "MAT-001", itemName = "Resin",
                requiredQty = 10.0, bagSize = "25.000 kg")))
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(baggedOrder))
        vm.lookupJob("510019068")
        advanceUntilIdle()
        vm.startListeningForPalletScans("510019068")
        vm.openShortBagWaiver("MAT-001")

        events.emit(com.mitas.ppnam.station2aa.data.rfid.ScanEvent.RfidTag("EPC:STRAY", java.time.Instant.now()))
        advanceUntilIdle()

        assertTrue("dialog must survive a stray scan",
            vm.uiState.value is MixingUiState.ShortBagWaiverEntry)
    }

    @Test
    fun `confirmPalletRecovery on success retries the pending scan`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()
        viewModel.selectLine(0)

        whenever(mockUseCase.scanIngredient("COL_000001", "EPC:300833", "MAT-001", bagSizeOption = "full", bagCount = 2.0)).thenReturn(
            Result.success(IngredientScanOutcome.NeedsRecovery("Pallet not in Holding"))
        )
        viewModel.confirmIngredientScan("EPC:300833", "full", 2.0)
        advanceUntilIdle()

        whenever(mockUseCase.recoverHolding("COL_000001", "EPC:300833")).thenReturn(Result.success(Unit))
        val updatedLine = BomLine(lineNumber = 0, itemCode = "MAT-001", itemName = "Resin", requiredQty = 1.0, remainingQty = 0.0)
        whenever(mockUseCase.scanIngredient("COL_000001", "EPC:300833", "MAT-001", bagSizeOption = "full", bagCount = 2.0)).thenReturn(
            Result.success(IngredientScanOutcome.Accepted(
                listOf(updatedLine),
                collectionSummary = "",
                collectionStatus = "Collecting",
                overCollectionToleranceBags = null,
                nextAction = NextAction.SCAN_INGREDIENT,
            ))
        )

        viewModel.confirmPalletRecovery()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is MixingUiState.OrderLoaded)
        verify(mockUseCase).recoverHolding("COL_000001", "EPC:300833")
    }

    @Test
    fun `dismissPalletRecovery returns to OrderLoaded without retrying`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()
        viewModel.selectLine(0)

        whenever(mockUseCase.scanIngredient("COL_000001", "EPC:300833", "MAT-001", bagSizeOption = "full", bagCount = 2.0)).thenReturn(
            Result.success(IngredientScanOutcome.NeedsRecovery("Pallet not in Holding"))
        )
        viewModel.confirmIngredientScan("EPC:300833", "full", 2.0)
        advanceUntilIdle()

        viewModel.dismissPalletRecovery()

        assertTrue(viewModel.uiState.value is MixingUiState.OrderLoaded)
        verify(mockUseCase, never()).recoverHolding(any(), any())
    }

    @Test
    fun `dismissing an error returns to the loaded order rather than stranding the operator`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()
        viewModel.selectLine(0)

        whenever(mockUseCase.scanIngredient("COL_000001", "EPC:300833", "MAT-001", bagSizeOption = "full", bagCount = 2.0))
            .thenReturn(Result.failure(RuntimeException("Station 2 did not respond")))
        viewModel.confirmIngredientScan("EPC:300833", "full", 2.0)
        advanceUntilIdle()
        assertTrue(
            "sanity check: a failed scan attempt should leave the screen in Error",
            viewModel.uiState.value is MixingUiState.Error
        )

        viewModel.dismissError()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is MixingUiState.OrderLoaded)
        // The armed line survives the round trip through Error too.
        assertEquals(0, (state as MixingUiState.OrderLoaded).selectedLineNumber)
    }

    @Test
    fun `dismissing an error with no order loaded returns to Idle`() = runTest {
        whenever(mockUseCase.lookupJob("bad")).thenReturn(Result.failure(Exception("Not found")))
        viewModel.lookupJob("bad")
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is MixingUiState.Error)

        viewModel.dismissError()
        advanceUntilIdle()

        assertEquals(MixingUiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun `dismissError while not showing Error is a no-op`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()

        viewModel.dismissError()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is MixingUiState.OrderLoaded)
        assertEquals(sampleOrder, (state as MixingUiState.OrderLoaded).order)
    }

    @Test
    fun `cancelJob resets state on backend confirmation`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()

        whenever(mockUseCase.cancelJob(any(), any(), any(), any(), any())).thenReturn(
            Result.success(com.mitas.ppnam.station2aa.data.mqtt.dto.IngredientCollectionCancelResultResponse())
        )
        val outcomes = mutableListOf<CancelOutcome>()
        val job = launch(testDispatcher) { viewModel.cancelOutcome.collect { outcomes.add(it) } }

        viewModel.cancelJob(managerUsername = "manager1", managerPassword = "secret")
        advanceUntilIdle()

        assertEquals(MixingUiState.Idle, viewModel.uiState.value)
        assertTrue(outcomes.contains(CancelOutcome.Confirmed))
        job.cancel()
    }

    @Test
    fun `cancelJob on rejection keeps the order loaded and emits Failed`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()

        whenever(mockUseCase.cancelJob(any(), any(), any(), any(), any()))
            .thenReturn(Result.failure(Exception("Pre-mix has ingredient activity and cannot be closed.")))
        val outcomes = mutableListOf<CancelOutcome>()
        val job = launch(testDispatcher) { viewModel.cancelOutcome.collect { outcomes.add(it) } }

        viewModel.cancelJob(managerUsername = "manager1", managerPassword = "secret")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is MixingUiState.OrderLoaded)
        assertEquals(
            listOf(CancelOutcome.Failed("Pre-mix has ingredient activity and cannot be closed.")),
            outcomes
        )
        job.cancel()
    }

    @Test
    fun `cancelJob passes manager credentials through to the use case`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()
        whenever(mockUseCase.cancelJob(any(), any(), any(), any(), any())).thenReturn(
            Result.success(com.mitas.ppnam.station2aa.data.mqtt.dto.IngredientCollectionCancelResultResponse())
        )

        viewModel.cancelJob(managerUsername = "Manager1", managerPassword = "5678")
        advanceUntilIdle()

        verify(mockUseCase).cancelJob(
            eq("COL_000001"), eq("510019068"), any(), eq("Manager1"), eq("5678")
        )
    }

    @Test
    fun `cancelJob refuses to send without manager credentials`() = runTest {
        // v3 has no direct-cancel path: manager credentials are required on every privileged
        // action, checked against the approver's account, even when the sender is a Manager.
        // A job must be loaded first so this exercises the credential guard specifically,
        // rather than the pre-existing blank-currentOrderNo guard.
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()

        viewModel.cancelJob(managerUsername = "", managerPassword = "")
        advanceUntilIdle()

        verify(mockUseCase, never()).cancelJob(any(), any(), any(), any(), any())
    }

    @Test
    fun `cancelJob forwards the supplied manager credentials`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        whenever(mockUseCase.cancelJob(any(), any(), any(), eq("manager1"), eq("secret")))
            .thenReturn(Result.success(mock()))

        viewModel.lookupJob("510019068")
        advanceUntilIdle()
        viewModel.cancelJob(managerUsername = "manager1", managerPassword = "secret")
        advanceUntilIdle()

        verify(mockUseCase).cancelJob(any(), any(), any(), eq("manager1"), eq("secret"))
    }

    @Test
    fun `loadActiveJobs populates activeJobs on success`() = runTest {
        val jobs = listOf(
            com.mitas.ppnam.station2aa.data.mqtt.dto.ActiveJobCardSummary(
                jobCardNumber = "510019068", productName = "Layer Mash", status = "Open"
            )
        )
        whenever(mockUseCase.fetchActiveJobCards(any(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(Result.success(ActiveJobsPage(jobs = jobs)))

        viewModel.loadActiveJobs()
        advanceUntilIdle()

        assertEquals(jobs, viewModel.activeJobs.value)
        assertEquals(null, viewModel.activeJobsError.value)
    }

    @Test
    fun `loadActiveJobs sets activeJobsError on failure and leaves list untouched`() = runTest {
        whenever(mockUseCase.fetchActiveJobCards(any(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(Result.failure(Exception("Not connected to Station 2")))

        viewModel.loadActiveJobs()
        advanceUntilIdle()

        assertTrue(viewModel.activeJobs.value.isEmpty())
        assertEquals("Not connected to Station 2", viewModel.activeJobsError.value)
    }

    @Test
    fun `session reflects the operator session holder`() = runTest {
        assertEquals("Test Operator", viewModel.session.value?.operatorName)
    }

    @Test
    fun `logout calls AuthUseCase and fires logoutEvent`() = runTest {
        val events = mutableListOf<Unit>()
        val job = launch(testDispatcher) { viewModel.logoutEvent.collect { events.add(it) } }

        viewModel.logout()
        advanceUntilIdle()

        verify(mockAuthUseCase).logout()
        assertEquals(1, events.size)
        job.cancel()
    }

    @Test
    fun `a stray scan while the manager-approval dialog is open is ignored and the dialog survives`() = runTest {
        val events = MutableSharedFlow<com.mitas.ppnam.station2aa.data.rfid.ScanEvent>()
        whenever(mockScanEventBus.events).thenReturn(events)
        val vm = MixingViewModel(mockUseCase, mockScanEventBus, mockMqttRepository, mockAuthUseCase, mockSessionHolder)
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        vm.lookupJob("510019068")
        advanceUntilIdle()
        vm.selectLine(0)

        // Scan listening starts once when the job loads and stays active continuously —
        // it is not restarted per-dialog, so this reproduces the real collector lifecycle.
        vm.startListeningForPalletScans("510019068")

        whenever(mockUseCase.scanIngredient("COL_000001", "EPC:300833", "MAT-001", bagSizeOption = "full", bagCount = 2.0)).thenReturn(
            Result.success(
                IngredientScanOutcome.NeedsManagerApproval(
                    collectionId = "COL_000001", sourceBarcode = "EPC:300833",
                    requestedMaterialCode = "MAT-001", bagSizeOption = "full", bagCount = 2.0,
                    quantity = null,
                    reason = "Wrong material",
                )
            )
        )
        vm.confirmIngredientScan("EPC:300833", "full", 2.0)
        advanceUntilIdle()
        assertTrue(
            "sanity check: the manager-approval dialog should be showing before the stray scan arrives",
            vm.uiState.value is MixingUiState.IngredientExceptionApproval
        )

        // A stray read for a different pallet lands while the manager-approval dialog owns the
        // screen. Unguarded, the collector would blindly flip state to EnteringBagDetails and
        // dismiss the dialog out from under the operator, losing the pending exception.
        events.emit(com.mitas.ppnam.station2aa.data.rfid.ScanEvent.RfidTag("EPC:999999", java.time.Instant.now()))
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(
            "a stray scan mid-dialog must not clobber the manager-approval prompt",
            state is MixingUiState.IngredientExceptionApproval
        )
        assertEquals("Wrong material", (state as MixingUiState.IngredientExceptionApproval).reason)
    }

    @Test
    fun `a scan in the normal OrderLoaded state is still processed despite the guard`() = runTest {
        val events = MutableSharedFlow<com.mitas.ppnam.station2aa.data.rfid.ScanEvent>()
        whenever(mockScanEventBus.events).thenReturn(events)
        val vm = MixingViewModel(mockUseCase, mockScanEventBus, mockMqttRepository, mockAuthUseCase, mockSessionHolder)
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        vm.lookupJob("510019068")
        advanceUntilIdle()
        assertTrue(vm.uiState.value is MixingUiState.OrderLoaded)

        vm.startListeningForPalletScans("510019068")
        events.emit(com.mitas.ppnam.station2aa.data.rfid.ScanEvent.RfidTag("EPC:300833", java.time.Instant.now()))
        advanceUntilIdle()

        // Over-correcting into ignoring every scan is worse than the original bug: the operator
        // would think the reader is broken. A legitimate scan from the normal scanning state must
        // still open the bag-entry dialog.
        val state = vm.uiState.value
        assertTrue(state is MixingUiState.EnteringBagDetails)
        assertEquals("EPC:300833", (state as MixingUiState.EnteringBagDetails).palletTag)
    }

    @Test
    fun `a scan arriving while Error is showing still reaches the scan flow`() = runTest {
        val events = MutableSharedFlow<com.mitas.ppnam.station2aa.data.rfid.ScanEvent>()
        whenever(mockScanEventBus.events).thenReturn(events)
        val vm = MixingViewModel(mockUseCase, mockScanEventBus, mockMqttRepository, mockAuthUseCase, mockSessionHolder)
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        vm.lookupJob("510019068")
        advanceUntilIdle()
        vm.selectLine(0)

        vm.startListeningForPalletScans("510019068")

        whenever(mockUseCase.scanIngredient("COL_000001", "EPC:300833", "MAT-001", bagSizeOption = "full", bagCount = 2.0))
            .thenReturn(Result.failure(RuntimeException("Station 2 did not respond")))
        vm.confirmIngredientScan("EPC:300833", "full", 2.0)
        advanceUntilIdle()
        assertTrue(
            "sanity check: a failed scan attempt should leave the screen in Error",
            vm.uiState.value is MixingUiState.Error
        )

        // Error is a settled state, not an in-flight request or an open dialog. Rescanning from
        // here is a recovery path (dismissError() is another), so the guard must let it through
        // rather than trapping the operator behind a dead-end error card.
        events.emit(com.mitas.ppnam.station2aa.data.rfid.ScanEvent.RfidTag("EPC:300833", java.time.Instant.now()))
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(
            "a rescan over a settled Error must open EnteringBagDetails, not be dropped",
            state is MixingUiState.EnteringBagDetails
        )
        assertEquals("EPC:300833", (state as MixingUiState.EnteringBagDetails).palletTag)
    }

    @Test
    fun `pauseScanning cancels the active scan job so further scans are ignored`() = runTest {
        val events = MutableSharedFlow<com.mitas.ppnam.station2aa.data.rfid.ScanEvent>()
        whenever(mockScanEventBus.events).thenReturn(events)
        val vm = MixingViewModel(
            mockUseCase, mockScanEventBus, mockMqttRepository, mockAuthUseCase, mockSessionHolder
        )
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        vm.lookupJob("510019068")
        advanceUntilIdle()

        vm.startListeningForPalletScans("510019068")
        vm.pauseScanning()
        events.emit(com.mitas.ppnam.station2aa.data.rfid.ScanEvent.RfidTag("EPC:300833", java.time.Instant.now()))
        advanceUntilIdle()

        assertTrue(vm.uiState.value is MixingUiState.OrderLoaded)
    }

    @Test
    fun `a scan with a bulk line armed opens quantity entry, not the bag picker`() = runTest {
        val events = MutableSharedFlow<com.mitas.ppnam.station2aa.data.rfid.ScanEvent>()
        whenever(mockScanEventBus.events).thenReturn(events)
        val vm = MixingViewModel(mockUseCase, mockScanEventBus, mockMqttRepository, mockAuthUseCase, mockSessionHolder)
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(bulkOrder))
        vm.lookupJob("510019068")
        advanceUntilIdle()
        vm.selectLine(0)
        vm.startListeningForPalletScans("510019068")
        events.emit(com.mitas.ppnam.station2aa.data.rfid.ScanEvent.RfidTag("EPC:1", java.time.Instant.now()))
        advanceUntilIdle()
        assertTrue(vm.uiState.value is MixingUiState.EnteringQuantityDetails)
    }

    @Test
    fun `confirmQuantityScan sends the quantity shape for the armed bulk line`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(bulkOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()
        viewModel.selectLine(0)
        whenever(mockUseCase.scanIngredient(any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(Result.success(IngredientScanOutcome.Rejected("nope")))
        viewModel.confirmQuantityScan("EPC:1", 42.5)
        advanceUntilIdle()
        verify(mockUseCase).scanIngredient(eq("COL_1"), eq("EPC:1"), eq("MAT-BULK"),
            anyOrNull(), anyOrNull(), eq(42.5), anyOrNull(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `confirmIngredientScan refuses a bag entry against an armed bulk line`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(bulkOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()
        viewModel.selectLine(0)
        viewModel.confirmIngredientScan("EPC:1", "full", 2.0)
        advanceUntilIdle()
        verify(mockUseCase, never()).scanIngredient(any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull())
        assertTrue(viewModel.uiState.value is MixingUiState.OrderLoaded)
    }

    @Test
    fun `an accepted outcome with OPEN_MIXING emits the mixing-board navigation event`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(sampleOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()
        viewModel.selectLine(0)
        whenever(mockUseCase.scanIngredient(any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(Result.success(IngredientScanOutcome.Accepted(
                updatedLines = sampleOrder.lines,
                collectionSummary = "All products collected.",
                collectionStatus = "ReadyForMixing",
                overCollectionToleranceBags = 1.0,
                nextAction = com.mitas.ppnam.station2aa.data.mqtt.NextAction.OPEN_MIXING,
            )))

        val events = mutableListOf<String>()
        val collector = launch { viewModel.navigationEvent.collect { events.add(it) } }
        viewModel.confirmIngredientScan("TAG-1", "full", 2.0)
        advanceUntilIdle()
        collector.cancel()

        assertTrue(events.contains(MixingNavDestination.MIXING_BOARD))
    }

    @Test
    fun `submitManagerApproval resubmits a QUANTITY-shaped scan with the quantity intact`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(bulkOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()
        viewModel.selectLine(0)
        whenever(mockUseCase.scanIngredient(any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull(), anyOrNull()))
            .thenReturn(Result.success(IngredientScanOutcome.NeedsManagerApproval(
                collectionId = "COL_1", sourceBarcode = "EPC:1",
                requestedMaterialCode = "MAT-BULK",
                bagSizeOption = null, bagCount = null, quantity = 42.5,
                reason = "over-collection")))
        viewModel.confirmQuantityScan("EPC:1", 42.5)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is MixingUiState.IngredientExceptionApproval)

        viewModel.submitManagerApproval("manager1", "secret", "verified")
        advanceUntilIdle()

        verify(mockUseCase).scanIngredient(
            eq("COL_1"), eq("EPC:1"), eq("MAT-BULK"),
            isNull(), isNull(), eq(42.5),
            eq("manager1"), eq("secret"), eq("verified"))
    }

    @Test
    fun `openShortBagWaiver refuses a bulk line`() = runTest {
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(bulkOrder))
        viewModel.lookupJob("510019068")
        advanceUntilIdle()
        viewModel.openShortBagWaiver("MAT-BULK")
        assertTrue("a bulk line has no bag arithmetic to waive",
            viewModel.uiState.value is MixingUiState.OrderLoaded)
    }

    @Test
    fun `openShortBagWaiver refuses outside OrderLoaded`() = runTest {
        // Nothing loaded: Idle state, no cached order.
        viewModel.openShortBagWaiver("MAT-001")
        assertTrue(viewModel.uiState.value is MixingUiState.Idle)
    }

    // --- A2: the bag dialog must never open without a line to attribute the scan to ---

    private val twoOpenLinesOrder = ProductionOrder(
        docNo = "510019068",
        collectionId = "COL_000001",
        lines = listOf(
            BomLine(lineNumber = 0, itemCode = "MAT-001", itemName = "Resin", requiredQty = 10.0,
                remainingQty = 10.0, remainingBags = 4.0, bagSize = "25.000 kg"),
            BomLine(lineNumber = 1, itemCode = "MAT-002", itemName = "Filler", requiredQty = 5.0,
                remainingQty = 5.0, remainingBags = 2.0, bagSize = "25.000 kg"),
        )
    )

    @Test
    fun `a scan with no line armed and several open lines refuses up front instead of opening the dialog`() = runTest {
        val events = MutableSharedFlow<com.mitas.ppnam.station2aa.data.rfid.ScanEvent>()
        whenever(mockScanEventBus.events).thenReturn(events)
        val vm = MixingViewModel(mockUseCase, mockScanEventBus, mockMqttRepository, mockAuthUseCase, mockSessionHolder)
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(twoOpenLinesOrder))
        vm.lookupJob("510019068")
        advanceUntilIdle()
        vm.startListeningForPalletScans("510019068")

        val errors = mutableListOf<String>()
        val job = launch(testDispatcher) { vm.supervisorError.collect { errors.add(it) } }

        events.emit(com.mitas.ppnam.station2aa.data.rfid.ScanEvent.RfidTag("EPC:300833", java.time.Instant.now()))
        advanceUntilIdle()

        // The old behaviour opened the bag dialog anyway, let the operator pick a bag size and
        // type a count, then discarded the whole entry on Confirm with an auto-dismissing
        // snackbar and published nothing. Refusing here is the whole point.
        assertTrue(
            "the dialog must not open when the app cannot tell which line the pallet is for",
            vm.uiState.value is MixingUiState.OrderLoaded
        )
        assertTrue(errors.isNotEmpty())
        job.cancel()
    }

    @Test
    fun `a scan with no line armed auto-arms when exactly one line is still open`() = runTest {
        val events = MutableSharedFlow<com.mitas.ppnam.station2aa.data.rfid.ScanEvent>()
        whenever(mockScanEventBus.events).thenReturn(events)
        val vm = MixingViewModel(mockUseCase, mockScanEventBus, mockMqttRepository, mockAuthUseCase, mockSessionHolder)
        val oneOpenLine = twoOpenLinesOrder.copy(
            lines = listOf(
                twoOpenLinesOrder.lines[0].copy(remainingQty = 0.0, remainingBags = 0.0),
                twoOpenLinesOrder.lines[1],
            )
        )
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(oneOpenLine))
        vm.lookupJob("510019068")
        advanceUntilIdle()
        vm.startListeningForPalletScans("510019068")

        events.emit(com.mitas.ppnam.station2aa.data.rfid.ScanEvent.RfidTag("EPC:300833", java.time.Instant.now()))
        advanceUntilIdle()

        val state = vm.uiState.value
        assertTrue(state is MixingUiState.EnteringBagDetails)
        state as MixingUiState.EnteringBagDetails
        assertEquals(1, state.lineNumber)
        assertEquals("MAT-002", state.materialCode)
        // The dialog can now show what's still required instead of the operator having to
        // remember it from the card behind the dialog.
        assertEquals(2.0, state.remainingBags!!, 0.0)
    }

    @Test
    fun `the bag dialog carries the armed line's material and remaining bags`() = runTest {
        val events = MutableSharedFlow<com.mitas.ppnam.station2aa.data.rfid.ScanEvent>()
        whenever(mockScanEventBus.events).thenReturn(events)
        val vm = MixingViewModel(mockUseCase, mockScanEventBus, mockMqttRepository, mockAuthUseCase, mockSessionHolder)
        whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(twoOpenLinesOrder))
        vm.lookupJob("510019068")
        advanceUntilIdle()
        vm.selectLine(0)
        vm.startListeningForPalletScans("510019068")

        events.emit(com.mitas.ppnam.station2aa.data.rfid.ScanEvent.RfidTag("EPC:300833", java.time.Instant.now()))
        advanceUntilIdle()

        val state = vm.uiState.value as MixingUiState.EnteringBagDetails
        assertEquals(0, state.lineNumber)
        assertEquals("MAT-001", state.materialCode)
        assertEquals("Resin", state.materialName)
        assertEquals(4.0, state.remainingBags!!, 0.0)
        assertEquals("25.000 kg", state.bagSize)
    }

    // --- A5: a scan marks its own line pending instead of blanking the screen ---

    @Test
    fun `an in-flight scan keeps the order on screen and marks only its own line pending`() {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        runTest(dispatcher) {
            val vm = MixingViewModel(mockUseCase, mockScanEventBus, mockMqttRepository, mockAuthUseCase, mockSessionHolder)
            whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(twoOpenLinesOrder))
            vm.lookupJob("510019068")
            advanceUntilIdle()
            vm.selectLine(1)

            val gate = CompletableDeferred<Result<IngredientScanOutcome>>()
            mockUseCase.stub {
                onBlocking {
                    scanIngredient(eq("COL_000001"), eq("EPC:1"), eq("MAT-002"),
                        eq("full"), eq(2.0), isNull(), isNull(), isNull(), isNull())
                } doSuspendableAnswer { gate.await() }
            }

            vm.confirmIngredientScan("EPC:1", "full", 2.0)
            runCurrent()

            val state = vm.uiState.value
            assertTrue(
                "the operator must still be able to see what they have already collected",
                state is MixingUiState.OrderLoaded
            )
            state as MixingUiState.OrderLoaded
            assertEquals(twoOpenLinesOrder.lines, state.order.lines)
            assertTrue(state.isBusy)
            assertEquals(1, state.pendingLineNumber)

            gate.complete(Result.success(IngredientScanOutcome.Accepted(
                twoOpenLinesOrder.lines,
                collectionSummary = "",
                collectionStatus = "Collecting",
                overCollectionToleranceBags = 1.0,
                nextAction = NextAction.SCAN_INGREDIENT,
            )))
            advanceUntilIdle()

            val settled = vm.uiState.value as MixingUiState.OrderLoaded
            assertFalse("pending must clear once the response lands", settled.isBusy)
            assertNull(settled.pendingLineNumber)
            // The tolerance the dialog quotes comes from the server, not a hardcoded constant.
            assertEquals(1.0, vm.overCollectionToleranceBags.value!!, 0.0)
        }
    }

    @Test
    fun `a scan arriving while a request is in flight is ignored`() = runTest {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        runTest(dispatcher) {
            val events = MutableSharedFlow<com.mitas.ppnam.station2aa.data.rfid.ScanEvent>()
            whenever(mockScanEventBus.events).thenReturn(events)
            val vm = MixingViewModel(mockUseCase, mockScanEventBus, mockMqttRepository, mockAuthUseCase, mockSessionHolder)
            whenever(mockUseCase.lookupJob("510019068")).thenReturn(Result.success(twoOpenLinesOrder))
            vm.lookupJob("510019068")
            advanceUntilIdle()
            vm.selectLine(0)
            vm.startListeningForPalletScans("510019068")

            val gate = CompletableDeferred<Result<IngredientScanOutcome>>()
            mockUseCase.stub {
                onBlocking {
                    scanIngredient(any(), any(), any(), anyOrNull(), anyOrNull(), anyOrNull(),
                        anyOrNull(), anyOrNull(), anyOrNull())
                } doSuspendableAnswer { gate.await() }
            }
            vm.confirmIngredientScan("EPC:1", "full", 2.0)
            runCurrent()

            // Inline-busy replaced the whole-screen Loading state, so the scan guard has to key
            // off isBusy now — otherwise a stray read mid-request would race the response.
            events.emit(com.mitas.ppnam.station2aa.data.rfid.ScanEvent.RfidTag("EPC:STRAY", java.time.Instant.now()))
            runCurrent()

            assertTrue(vm.uiState.value is MixingUiState.OrderLoaded)
            assertEquals(0, (vm.uiState.value as MixingUiState.OrderLoaded).pendingLineNumber)

            gate.complete(Result.success(IngredientScanOutcome.Rejected("nope")))
            advanceUntilIdle()
        }
    }

    // --- A3: allowedActions gates what the UI OFFERS, never what it authorises ---

    @Test
    fun `canShow hides an action the session does not carry and shows one it does`() {
        val operator = sessionWithActions("scan_ingredient", "recover_holding")
        assertFalse(operator.canShow("machine_force_close"))
        assertTrue(operator.canShow("scan_ingredient"))
    }

    @Test
    fun `canShow fails open on an empty allowedActions list`() {
        // A session that arrived without the hint (older Station 2, trimmed payload) must render
        // the full UI and let the server reject — hiding controls the operator is entitled to
        // would be the worse failure.
        val noHint = sessionWithActions()
        assertTrue(noHint.canShow("machine_force_close"))
        assertTrue(noHint.canShow("anything_at_all"))
    }

    @Test
    fun `canShow on a null session offers nothing`() {
        val none: com.mitas.ppnam.station2aa.data.session.OperatorSession? = null
        assertFalse(none.canShow("machine_force_close"))
    }
}
