package com.mitas.ppnam.station2aa.ui.rfid

import com.mitas.ppnam.station2aa.data.rfid.ScanEvent
import com.mitas.ppnam.station2aa.data.rfid.ScanEventBus
import com.mitas.ppnam.station2aa.domain.model.PalletInfo
import com.mitas.ppnam.station2aa.domain.model.PalletState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.usecase.PalletUseCase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.stub
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class RfidViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var useCase: PalletUseCase
    private lateinit var bus: ScanEventBus
    private lateinit var mqtt: MqttRepository
    private lateinit var viewModel: RfidViewModel

    private fun pallet(
        tag: String = "TAG-1",
        found: Boolean = true,
        usable: Boolean = true,
        recoverable: Boolean = false,
        state: PalletState = PalletState.Holding,
        blocked: Boolean = false,
    ) = PalletInfo(
        found = found, usable = usable, recoverable = recoverable,
        palletRfidTag = tag, palletId = "PAL-001", productCode = "1600000301",
        productName = "HD WHITE", batchNumber = "BATCH-01", remainingQuantity = 625.0,
        remainingBags = 25.0, unit = "kg", localLocation = "Holding",
        palletState = state, blocked = blocked,
    )

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        useCase = mock()
        mqtt = mock()
        whenever(mqtt.connectionState).thenReturn(
            kotlinx.coroutines.flow.MutableStateFlow(
                com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState.CONNECTED
            )
        )
        whenever(mqtt.stationOnline).thenReturn(kotlinx.coroutines.flow.MutableStateFlow(true))
        whenever(mqtt.clockSkewMillis).thenReturn(kotlinx.coroutines.flow.MutableStateFlow<Long?>(null))
        bus = mock()
        whenever(bus.events).thenReturn(MutableSharedFlow())
        viewModel = RfidViewModel(useCase, bus, mqtt)
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a usable pallet surfaces as a result`() = runTest {
        whenever(useCase.lookup("TAG-1")).thenReturn(Result.success(pallet()))

        viewModel.lookupPallet("TAG-1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is RfidUiState.Result)
        assertTrue((state as RfidUiState.Result).pallet.usable)
    }

    @Test
    fun `an unknown tag is a result rather than an error`() = runTest {
        whenever(useCase.lookup("NOPE")).thenReturn(
            Result.success(pallet(found = false, usable = false, state = PalletState.Unknown))
        )

        viewModel.lookupPallet("NOPE")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("found=false must not be an Error", state is RfidUiState.Result)
        assertEquals(false, (state as RfidUiState.Result).pallet.found)
    }

    @Test
    fun `a transport failure surfaces as an error`() = runTest {
        whenever(useCase.lookup("TAG-1")).thenReturn(Result.failure(Exception("Not connected to Station 2")))

        viewModel.lookupPallet("TAG-1")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is RfidUiState.Error)
        assertEquals("Not connected to Station 2", (state as RfidUiState.Error).message)
    }

    @Test
    fun `recovering a pallet sends its tag and replaces the shown result`() = runTest {
        whenever(useCase.lookup("TAG-1")).thenReturn(
            Result.success(pallet(usable = false, recoverable = true, state = PalletState.AtStation1))
        )
        whenever(useCase.recoverToHolding(eq("TAG-1"), eq(null), any()))
            .thenReturn(Result.success(pallet()))

        viewModel.lookupPallet("TAG-1")
        advanceUntilIdle()
        viewModel.recoverCurrentPallet()
        advanceUntilIdle()

        verify(useCase).recoverToHolding(eq("TAG-1"), eq(null), any())
        val state = viewModel.uiState.value as RfidUiState.Result
        assertEquals(PalletState.Holding, state.pallet.palletState)
        assertTrue(state.pallet.usable)
    }

    @Test
    fun `a recovery that leaves the pallet blocked shows the honest result`() = runTest {
        whenever(useCase.lookup("TAG-1")).thenReturn(
            Result.success(pallet(usable = false, recoverable = true, state = PalletState.AtStation1, blocked = true))
        )
        whenever(useCase.recoverToHolding(eq("TAG-1"), eq(null), any())).thenReturn(
            Result.success(pallet(usable = false, blocked = true, state = PalletState.Holding))
        )

        viewModel.lookupPallet("TAG-1")
        advanceUntilIdle()
        viewModel.recoverCurrentPallet()
        advanceUntilIdle()

        val state = viewModel.uiState.value as RfidUiState.Result
        assertEquals(PalletState.Holding, state.pallet.palletState)
        assertEquals(false, state.pallet.usable)
        assertTrue(state.pallet.blocked)
    }

    @Test
    fun `a failed recovery surfaces as an error`() = runTest {
        whenever(useCase.lookup("TAG-1")).thenReturn(
            Result.success(pallet(usable = false, recoverable = true, state = PalletState.AtStation1))
        )
        whenever(useCase.recoverToHolding(eq("TAG-1"), eq(null), any()))
            .thenReturn(Result.failure(Exception("Consumed pallets cannot be recovered.")))

        viewModel.lookupPallet("TAG-1")
        advanceUntilIdle()
        viewModel.recoverCurrentPallet()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is RfidUiState.Error)
        assertEquals("Consumed pallets cannot be recovered.", (state as RfidUiState.Error).message)
    }

    @Test
    fun `recovery is a no-op when no pallet is on screen`() = runTest {
        viewModel.recoverCurrentPallet()
        advanceUntilIdle()

        verify(useCase, never()).recoverToHolding(any(), any(), any())
    }

    @Test
    fun `recovery is a no-op when the shown pallet is not recoverable`() = runTest {
        whenever(useCase.lookup("TAG-1")).thenReturn(Result.success(pallet(recoverable = false)))

        viewModel.lookupPallet("TAG-1")
        advanceUntilIdle()
        viewModel.recoverCurrentPallet()
        advanceUntilIdle()

        verify(useCase, never()).recoverToHolding(any(), any(), any())
    }

    @Test
    fun `a stray scan during recovery is ignored, and the honest recovery result survives`() = runTest {
        val events = MutableSharedFlow<ScanEvent>()
        whenever(bus.events).thenReturn(events)
        val vm = RfidViewModel(useCase, bus, mqtt)

        whenever(useCase.lookup("TAG-1")).thenReturn(
            Result.success(pallet(tag = "TAG-1", usable = false, recoverable = true, state = PalletState.AtStation1))
        )
        // A stray scan for a different pallet lands while recovery is in flight. If the ViewModel
        // does not guard against it, this stubbed lookup fires and clobbers the Recovering state.
        whenever(useCase.lookup("TAG-2")).thenReturn(
            Result.success(pallet(tag = "TAG-2", usable = true, state = PalletState.Holding))
        )
        val recoveryGate = CompletableDeferred<Unit>()
        useCase.stub {
            onBlocking { recoverToHolding(eq("TAG-1"), eq(null), any()) } doSuspendableAnswer {
                recoveryGate.await()
                Result.success(pallet(tag = "TAG-1", usable = false, blocked = true, state = PalletState.Holding))
            }
        }

        vm.startListening()
        // A SharedFlow with no subscribers silently drops emissions rather than suspending, so the
        // collector launched by startListening() must actually be running (subscribed) before we emit.
        runCurrent()
        events.emit(ScanEvent.RfidTag("TAG-1", Instant.now()))
        advanceUntilIdle()

        vm.recoverCurrentPallet()
        runCurrent()
        assertTrue(
            "sanity check: recovery should be in flight before the stray scan arrives",
            vm.uiState.value is RfidUiState.Recovering
        )

        // The stray scan arrives mid-recovery.
        events.emit(ScanEvent.RfidTag("TAG-2", Instant.now()))
        advanceUntilIdle()

        verify(useCase, never()).lookup("TAG-2")
        assertTrue(
            "a scan mid-recovery must not start a second lookup or disturb the in-flight state",
            vm.uiState.value is RfidUiState.Recovering
        )

        recoveryGate.complete(Unit)
        advanceUntilIdle()

        val state = vm.uiState.value as RfidUiState.Result
        assertEquals(PalletState.Holding, state.pallet.palletState)
        assertEquals(false, state.pallet.usable)
        assertTrue("the honest recovery result must survive, not be clobbered by the stray scan", state.pallet.blocked)
    }

    @Test
    fun `a scan while idle still triggers a lookup`() = runTest {
        val events = MutableSharedFlow<ScanEvent>()
        whenever(bus.events).thenReturn(events)
        val vm = RfidViewModel(useCase, bus, mqtt)
        whenever(useCase.lookup("TAG-1")).thenReturn(Result.success(pallet()))

        vm.startListening()
        runCurrent()
        events.emit(ScanEvent.RfidTag("TAG-1", Instant.now()))
        advanceUntilIdle()

        verify(useCase).lookup("TAG-1")
        assertTrue(vm.uiState.value is RfidUiState.Result)
    }
}
