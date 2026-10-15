package com.mitas.ppnam.station2aa.ui.preparation

import androidx.lifecycle.SavedStateHandle
import com.google.gson.JsonParser
import com.mitas.ppnam.station2aa.contract.ContractFixtures
import com.mitas.ppnam.station2aa.data.mqtt.ErrorCode
import com.mitas.ppnam.station2aa.data.mqtt.WireJson
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2Snapshot
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingCommand
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingStatus
import com.mitas.ppnam.station2aa.data.mqtt.outbox.UnresolvedReason
import com.mitas.ppnam.station2aa.data.rfid.ScanEvent
import com.mitas.ppnam.station2aa.data.rfid.ScanEventBus
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.domain.model.CollectionExceptionRecord
import com.mitas.ppnam.station2aa.domain.model.PrepSnapshot
import com.mitas.ppnam.station2aa.domain.model.PrepStage
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.repository.ServerHint
import com.mitas.ppnam.station2aa.domain.usecase.AuthUseCase
import com.mitas.ppnam.station2aa.domain.usecase.CollectionResult
import com.mitas.ppnam.station2aa.domain.usecase.CommandRecoveryUseCase
import com.mitas.ppnam.station2aa.domain.usecase.GeneralCollectionUseCase
import com.mitas.ppnam.station2aa.domain.usecase.PendingCommandCoordinator
import com.mitas.ppnam.station2aa.domain.usecase.ReadResult
import com.mitas.ppnam.station2aa.domain.usecase.RecoveryOutcome
import com.mitas.ppnam.station2aa.domain.usecase.RecoveryResult
import com.mitas.ppnam.station2aa.domain.usecase.ReplacementOffer
import com.mitas.ppnam.station2aa.domain.model.CaptureIntent
import com.mitas.ppnam.station2aa.domain.model.AmountCheck
import com.mitas.ppnam.station2aa.domain.model.AmountProblem
import com.mitas.ppnam.station2aa.domain.model.PrepMaterial
import com.mitas.ppnam.station2aa.domain.model.SourceInfo
import com.mitas.ppnam.station2aa.domain.usecase.IdentifyResult
import com.mitas.ppnam.station2aa.domain.usecase.toPrepSnapshot
import java.math.BigDecimal
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

class PreparationViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private lateinit var useCase: GeneralCollectionUseCase
    private lateinit var recovery: CommandRecoveryUseCase
    private lateinit var coordinator: PendingCommandCoordinator
    private lateinit var mqtt: MqttRepository
    private lateinit var scanBus: ScanEventBus
    private lateinit var scans: MutableSharedFlow<ScanEvent>
    private lateinit var hints: MutableSharedFlow<ServerHint>
    private lateinit var connection: MutableStateFlow<MqttConnectionState>
    private lateinit var outbox: MutableStateFlow<List<PendingCommand>>
    private lateinit var notices: MutableStateFlow<List<RecoveryResult>>
    private lateinit var sessionHolder: OperatorSessionHolder

    private val prepId = "PREP_440fbd8f9cd3411e9be943a33f1a3ceb"

    private fun snap(name: String): PrepSnapshot = WireJson.gson.fromJson(
        JsonParser.parseString(ContractFixtures.text("${name}_response.json")).asJsonObject["data"],
        Rev2Snapshot::class.java,
    ).toPrepSnapshot()

    /** Collecting, RESIN 0 of 40. */
    private val opened by lazy { snap("general_read_preparation") }
    /** Collecting, RESIN 10 of 40 (remaining 30). */
    private val afterOne by lazy { snap("general_capture") }
    /** AwaitingConfirmation, RESIN 40 of 40. */
    private val finalCapture by lazy { snap("final_capture") }
    /** ReadyForMixer. */
    private val confirmed by lazy { snap("general_confirm") }

    private fun pending(
        id: String = "m-1",
        session: String = "sess-1",
        status: PendingStatus = PendingStatus.Unresolved,
        target: String = prepId,
    ) = PendingCommand(
        messageId = id, requestType = GeneralCollectionUseCase.REQUEST, responseType = GeneralCollectionUseCase.RESPONSE,
        action = "capture", targetId = target, payload = "{\"action\":\"capture\"}", operatorId = "OP-1",
        sessionId = session, status = status,
    )

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        useCase = mock()
        recovery = mock()
        coordinator = mock()
        mqtt = mock()
        scanBus = mock()
        scans = MutableSharedFlow(extraBufferCapacity = 8)
        hints = MutableSharedFlow(extraBufferCapacity = 8)
        connection = MutableStateFlow(MqttConnectionState.CONNECTED)
        outbox = MutableStateFlow(emptyList())
        sessionHolder = OperatorSessionHolder()
        sessionHolder.set(OperatorSession("sess-1", "OP-1", "Op", "Worker"))
        whenever(scanBus.events).thenReturn(scans)
        whenever(mqtt.connectionState).thenReturn(connection)
        whenever(mqtt.serverHints).thenReturn(hints)
        whenever(mqtt.stationOnline).thenReturn(MutableStateFlow(true))
        whenever(mqtt.clockSkewMillis).thenReturn(MutableStateFlow<Long?>(null))
        whenever(mqtt.deviceId).thenReturn("scanner_1")
        whenever(coordinator.pending).thenReturn(outbox)
        notices = MutableStateFlow(emptyList())
        whenever(coordinator.notices).thenReturn(notices)
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    private suspend fun open(snapshot: PrepSnapshot = opened, id: String = prepId): PreparationViewModel {
        whenever(useCase.read(id)).thenReturn(ReadResult.Loaded(snapshot))
        return PreparationViewModel(
            useCase, recovery, coordinator, mqtt, scanBus, sessionHolder, mock<AuthUseCase>(),
            SavedStateHandle(mapOf("prepId" to id)),
        ).also { it.setScreenActive(true) }
    }

    private fun scan(tag: String) = scans.tryEmit(ScanEvent.RfidTag(tag, Instant.EPOCH))


    // ---- test helpers for the collect flow ----

    private fun source(
        code: String = "TAG-RESIN", material: String = "RESIN", name: String = "Resin",
        bag: String? = null, available: String? = "100", usable: Boolean = true, reason: String = "",
    ) = SourceInfo(
        code = code, materialCode = material, name = name, unit = "kg", bagSize = bag?.let(::BigDecimal),
        available = available?.let(::BigDecimal), usable = usable, reason = reason,
    )

    /** Station 2 identifies [source] against [snapshot]; then the scan arrives. */
    private suspend fun scanIdentified(vm: PreparationViewModel, source: SourceInfo = source(), snapshot: PrepSnapshot = opened) {
        whenever(useCase.identify(prepId, source.code)).thenReturn(IdentifyResult.Identified(source, snapshot))
        vm.onScan(source.code)
    }

    private fun PreparationViewModel.step() = uiState.value.step

    private fun withMaterial(base: PrepSnapshot, material: PrepMaterial): PrepSnapshot =
        base.copy(preparation = base.preparation!!.copy(materials = base.preparation!!.materials + material))

    private fun withResinCollected(base: PrepSnapshot, collected: String?): PrepSnapshot =
        base.copy(preparation = base.preparation!!.copy(materials = base.preparation!!.materials.map {
            if (it.code == "RESIN") it.copy(collected = collected?.let(::BigDecimal) ?: it.required) else it
        }))

    private fun rejectionRecord() = CollectionExceptionRecord(
        id = "EX-1", timestampUtc = "2026-10-01T08:00:00Z", operatorId = "65cf955ae506431b9d0d0025ca9fe8fa", device = "scanner_1",
        messageId = "m-9", jobId = "510019068", batchId = null, kind = "Capture rejected",
        details = "Pallet not on the job", reason = "", reviewedAtUtc = null, reviewedBy = null, reviewReason = null,
    )

    @Test
    fun `opening reads the exact PREP id`() = runTest {
        val vm = open()
        verify(useCase).read(prepId)
        verify(useCase, never()).prepare(any(), any(), anyOrNull(), any(), anyOrNull())
        assertEquals(PrepStage.Collecting, vm.uiState.value.stage)
        assertTrue(vm.uiState.value.canScan)
        assertEquals(CollectStep.Idle, vm.step())
    }

    @Test
    fun `a scan identifies the pallet and never sends`() = runTest {
        open()
        whenever(useCase.identify(prepId, "TAG-RESIN")).thenReturn(IdentifyResult.Identified(source(), opened))
        scan("  TAG-RESIN ")
        verify(useCase).identify(prepId, "TAG-RESIN")
        verify(useCase, never()).capture(any(), any(), anyOrNull())
        verify(useCase, never()).confirm(any())
    }

    @Test
    fun `an identified in-mix pallet shows the guide and prefills the smaller of remaining and stock`() = runTest {
        val vm = open(afterOne)
        scanIdentified(vm, source(bag = "25", available = "100"), afterOne)
        val step = vm.step() as CollectStep.Identified
        assertEquals("30", step.amountText)                      // remaining 30
        assertEquals("1 bag + ≈ ¼", step.guide!!.text)
        assertEquals(AmountCheck.Valid(BigDecimal("30")), step.check)
        vm.cancelCollect()
        scanIdentified(vm, source(code = "TAG-2", bag = "25", available = "12"), afterOne)
        assertEquals("12", (vm.step() as CollectStep.Identified).amountText)   // pallet stock caps the prefill
    }

    @Test
    fun `scans are ignored while the screen is not active`() = runTest {
        val vm = open()
        vm.setScreenActive(false)
        scan("TAG-RESIN")
        assertEquals(CollectStep.Idle, vm.step())
        verify(useCase, never()).identify(any(), any())
    }

    @Test
    fun `submit with no bag size sends one capture of the exact amount and shows Accepted`() = runTest {
        val vm = open()
        whenever(useCase.capture(prepId, "TAG-RESIN", BigDecimal("10"))).thenReturn(CollectionResult.Done(afterOne))
        scanIdentified(vm)
        vm.onAmountChange("10")
        vm.submit()
        verify(useCase, times(1)).capture(prepId, "TAG-RESIN", BigDecimal("10"))
        assertEquals(CollectStep.Idle, vm.step())
        assertEquals(ResultBanner.Accepted("Recorded 10 kg Resin"), vm.uiState.value.banner)
        assertEquals(afterOne, vm.uiState.value.snapshot)
        assertFalse(vm.uiState.value.inFlight)
    }

    @Test
    fun `two taps on Submit while in flight send one capture`() = runTest {
        val vm = open()
        val reply = CompletableDeferred<CollectionResult>()
        whenever(useCase.capture(prepId, "TAG-RESIN", BigDecimal("40"))).doSuspendableAnswer { reply.await() }
        scanIdentified(vm)
        vm.submit()
        assertTrue(vm.uiState.value.inFlight)
        assertTrue(vm.step() is CollectStep.Submitting)
        vm.submit()
        reply.complete(CollectionResult.Done(finalCapture))
        verify(useCase, times(1)).capture(any(), any(), anyOrNull())
        assertFalse(vm.uiState.value.inFlight)
    }

    @Test
    fun `an amount above the row's remaining is refused locally and nothing is sent`() = runTest {
        val vm = open(afterOne)
        scanIdentified(vm, snapshot = afterOne)
        vm.onAmountChange("30.001")
        val step = vm.step() as CollectStep.Identified
        assertEquals(AmountCheck.Invalid(AmountProblem.MoreThanNeeded(BigDecimal("30"), "kg")), step.check)
        vm.submit()
        verify(useCase, never()).capture(any(), any(), anyOrNull())
        assertEquals(step, vm.step())
    }

    @Test
    fun `an amount above the pallet stock, zero, or blank is refused locally`() = runTest {
        val vm = open(afterOne)
        scanIdentified(vm, source(available = "5"), afterOne)
        vm.onAmountChange("5.5")
        assertTrue((vm.step() as CollectStep.Identified).check is AmountCheck.Invalid)
        vm.onAmountChange("0")
        assertTrue((vm.step() as CollectStep.Identified).check is AmountCheck.Invalid)
        vm.onAmountChange("")
        assertTrue((vm.step() as CollectStep.Identified).check is AmountCheck.Invalid)
        vm.submit()
        verify(useCase, never()).capture(any(), any(), anyOrNull())
    }

    @Test
    fun `an amount equal to remaining is sent exactly and reports the completed row`() = runTest {
        val vm = open(afterOne)
        whenever(useCase.capture(prepId, "TAG-RESIN", BigDecimal("30"))).thenReturn(CollectionResult.Done(finalCapture))
        scanIdentified(vm, snapshot = afterOne)
        vm.submit()
        verify(useCase).capture(prepId, "TAG-RESIN", BigDecimal("30"))
        assertEquals(ResultBanner.Accepted("Recorded 30 kg Resin", completedMaterialCode = "RESIN"), vm.uiState.value.banner)
    }

    @Test
    fun `with a bag size Submit sends one capture per bag then the rest, in order`() = runTest {
        val vm = open()
        val order = mutableListOf<BigDecimal?>()
        whenever(useCase.capture(eq(prepId), eq("TAG-RESIN"), anyOrNull())).thenAnswer {
            order += it.arguments[2] as BigDecimal?
            CollectionResult.Done(if (order.size == 2) finalCapture else afterOne)
        }
        scanIdentified(vm, source(bag = "25"))
        assertEquals("40", (vm.step() as CollectStep.Identified).amountText)
        vm.submit()
        assertEquals(listOf(BigDecimal("25"), BigDecimal("15")), order)
        assertEquals(CollectStep.Idle, vm.step())
        assertTrue(vm.uiState.value.banner is ResultBanner.Accepted)
        assertFalse(vm.uiState.value.inFlight)
    }

    @Test
    fun `a rejection part-way stops the sequence and reports how many were recorded`() = runTest {
        val vm = open()
        val record = rejectionRecord()
        whenever(useCase.capture(prepId, "TAG-RESIN", BigDecimal("25"))).thenReturn(CollectionResult.Done(afterOne))
        whenever(useCase.capture(prepId, "TAG-RESIN", BigDecimal("15"))).thenReturn(
            CollectionResult.Rejected(ErrorCode("rev2_rejected"), "Pallet is blocked", afterOne.copy(commandExceptions = listOf(record))),
        )
        scanIdentified(vm, source(bag = "25"))
        vm.submit()
        verify(useCase, times(2)).capture(any(), any(), anyOrNull())
        assertEquals(CollectStep.Idle, vm.step())
        assertEquals(
            ResultBanner.Partial(
                recorded = 1, total = 2, recordedKg = "25", rejection = "Pallet is blocked", loggedLine = record.loggedLine(),
            ),
            vm.uiState.value.banner,
        )
    }

    @Test
    fun `a lost reply part-way stops the sequence, never sends the next, and the hold blocks scans`() = runTest {
        val vm = open()
        val cmd = pending()
        whenever(useCase.capture(prepId, "TAG-RESIN", BigDecimal("25"))).thenReturn(CollectionResult.Done(afterOne))
        whenever(useCase.capture(prepId, "TAG-RESIN", BigDecimal("15"))).thenAnswer {
            outbox.value = listOf(cmd)
            CollectionResult.Unresolved(cmd, UnresolvedReason.RetryIdentical, "Station 2 has not confirmed this yet.")
        }
        scanIdentified(vm, source(bag = "25"))
        vm.submit()
        verify(useCase, times(2)).capture(any(), any(), anyOrNull())
        assertEquals(listOf(cmd), vm.uiState.value.blocking)
        assertEquals(CollectStep.Idle, vm.step())
        assertFalse(vm.uiState.value.canScan)
        scan("TAG-OTHER")
        verify(useCase, never()).identify(prepId, "TAG-OTHER")
    }

    @Test
    fun `an already-settled capture counts as done for that element and re-reads`() = runTest {
        val vm = open()
        whenever(useCase.capture(prepId, "TAG-RESIN", BigDecimal("25"))).thenReturn(CollectionResult.AlreadySettled("m"))
        whenever(useCase.capture(prepId, "TAG-RESIN", BigDecimal("15"))).thenReturn(CollectionResult.Done(finalCapture))
        scanIdentified(vm, source(bag = "25"))
        vm.submit()
        verify(useCase, times(2)).capture(any(), any(), anyOrNull())
        verify(useCase, times(2)).read(prepId)
        assertEquals(CollectStep.Idle, vm.step())
    }

    @Test
    fun `not sent on the first capture keeps the identified pallet so Submit can be tapped again`() = runTest {
        val vm = open()
        whenever(useCase.capture(prepId, "TAG-RESIN", BigDecimal("40")))
            .thenReturn(CollectionResult.NotSent("Not connected — nothing was sent"))
        scanIdentified(vm)
        val before = vm.step()
        vm.submit()
        assertEquals(OutcomeMessage.Info("Not connected — nothing was sent"), vm.uiState.value.message)
        assertEquals(before, vm.step())
        assertFalse(vm.uiState.value.inFlight)
    }

    @Test
    fun `a rejected capture shows the reason and the logged exception line`() = runTest {
        val vm = open()
        val record = rejectionRecord()
        val withRecord = opened.copy(commandExceptions = listOf(record))
        whenever(useCase.capture(prepId, "TAG-X", BigDecimal("40"))).thenReturn(
            CollectionResult.Rejected(ErrorCode("rev2_rejected"), "That pallet is not on this job", withRecord),
        )
        scanIdentified(vm, source(code = "TAG-X"))
        vm.submit()
        assertEquals(ResultBanner.Rejected("That pallet is not on this job", record.loggedLine()), vm.uiState.value.banner)
        assertEquals("Logged 1 Oct 2026 10:00 · operator 65cf…", record.loggedLine())
        assertEquals(withRecord, vm.uiState.value.snapshot)
        assertEquals(CollectStep.Idle, vm.step())
    }

    @Test
    fun `a sealed receipt is shown as a rejection`() = runTest {
        val vm = open()
        whenever(useCase.capture(prepId, "TAG-RESIN", BigDecimal("40"))).thenReturn(
            CollectionResult.Rejected(ErrorCode.RECEIPT_SEALED, "Station 2 never ran this, and it can no longer run.", opened),
        )
        scanIdentified(vm)
        vm.submit()
        assertEquals(
            ResultBanner.Rejected("Station 2 never ran this, and it can no longer run.", null),
            vm.uiState.value.banner,
        )
    }

    // ---- the Collect sheet protects the staged pallet ----

    @Test
    fun `a new scan while a pallet is identified is refused with Finish or cancel`() = runTest {
        val vm = open(afterOne)
        scanIdentified(vm, snapshot = afterOne)
        vm.onAmountChange("5")
        val staged = vm.step()
        scan("TAG-OTHER")
        verify(useCase, never()).identify(prepId, "TAG-OTHER")
        assertEquals(staged, vm.step())
        assertEquals(OutcomeMessage.Info(PreparationViewModel.FINISH_FIRST_MESSAGE), vm.uiState.value.message)
        assertEquals("Finish or cancel this pallet first", PreparationViewModel.FINISH_FIRST_MESSAGE)
        vm.cancelCollect()
        assertEquals(CollectStep.Idle, vm.step())
        scanIdentified(vm, source(code = "TAG-OTHER"), afterOne)
        assertEquals("TAG-OTHER", (vm.step() as CollectStep.Identified).source.code)
    }

    @Test
    fun `a new scan while identifying or picking a fallback row does not replace the step`() = runTest {
        val vm = open()
        val reply = CompletableDeferred<IdentifyResult>()
        whenever(useCase.identify(prepId, "TAG-A")).doSuspendableAnswer { reply.await() }
        vm.onScan("TAG-A")
        assertEquals(CollectStep.Identifying("TAG-A"), vm.step())
        vm.onScan("TAG-B")
        verify(useCase, never()).identify(prepId, "TAG-B")
        reply.complete(IdentifyResult.Unsupported(GeneralCollectionUseCase.IDENTIFY_FALLBACK))
        assertTrue(vm.step() is CollectStep.Fallback)
        vm.onScan("TAG-B")
        verify(useCase, never()).identify(prepId, "TAG-B")
        assertEquals("TAG-A", (vm.step() as CollectStep.Fallback).code)
    }

    @Test
    fun `cancelling while identifying drops a late reply`() = runTest {
        val vm = open()
        val reply = CompletableDeferred<IdentifyResult>()
        whenever(useCase.identify(prepId, "TAG-A")).doSuspendableAnswer { reply.await() }
        vm.onScan("TAG-A")
        vm.cancelCollect()
        reply.complete(IdentifyResult.Identified(source(code = "TAG-A"), opened))
        assertEquals(CollectStep.Idle, vm.step())
    }

    @Test
    fun `an unknown pallet shows its message and a later scan replaces it`() = runTest {
        val vm = open()
        whenever(useCase.identify(prepId, "TAG-?")).thenReturn(IdentifyResult.Unknown("Station 2 doesn't know this pallet code.", null))
        vm.onScan("TAG-?")
        assertEquals(CollectStep.Unknown("Station 2 doesn't know this pallet code."), vm.step())
        scanIdentified(vm)
        assertTrue(vm.step() is CollectStep.Identified)
    }

    @Test
    fun `an unusable pallet shows Station 2's reason and sends nothing`() = runTest {
        val vm = open()
        val src = source(usable = false, reason = "Pallet is blocked at Station 1")
        scanIdentified(vm, src)
        assertEquals(CollectStep.Unusable(src, "Pallet is blocked at Station 1"), vm.step())
        vm.submit()
        verify(useCase, never()).capture(any(), any(), anyOrNull())
    }

    @Test
    fun `an excluded material cannot be collected`() = runTest {
        val vm = open()
        val src = source(material = "OPTIONAL", name = "Optional ingredient")
        scanIdentified(vm, src)
        assertEquals(CollectStep.Excluded(src, "Optional ingredient"), vm.step())
        vm.submit()
        verify(useCase, never()).capture(any(), any(), anyOrNull())
    }

    @Test
    fun `an already complete material cannot be collected again`() = runTest {
        val complete = withResinCollected(opened, null)
        val vm = open(complete)
        scanIdentified(vm, snapshot = complete)
        assertTrue(vm.step() is CollectStep.Unusable)
        vm.submit()
        verify(useCase, never()).capture(any(), any(), anyOrNull())
    }

    // ---- Fallback when Station 2 cannot identify ----

    @Test
    fun `unsupported identify falls back to picking the ingredient, then one capture of the typed amount`() = runTest {
        val vm = open(afterOne)
        whenever(useCase.identify(prepId, "TAG-RESIN")).thenReturn(IdentifyResult.Unsupported(GeneralCollectionUseCase.IDENTIFY_FALLBACK))
        vm.onScan("TAG-RESIN")
        assertEquals(CollectStep.Fallback("TAG-RESIN", null, ""), vm.step())
        vm.submit()
        verify(useCase, never()).capture(any(), any(), anyOrNull())
        vm.pickFallbackMaterial("RESIN")
        assertEquals("30", (vm.step() as CollectStep.Fallback).amountText)
        vm.pickFallbackMaterial("OPTIONAL")                     // excluded rows cannot be picked
        assertEquals("RESIN", (vm.step() as CollectStep.Fallback).pickedMaterial)
        vm.onAmountChange("12.5")
        whenever(useCase.capture(prepId, "TAG-RESIN", BigDecimal("12.5"))).thenReturn(CollectionResult.Done(afterOne))
        vm.submit()
        verify(useCase, times(1)).capture(prepId, "TAG-RESIN", BigDecimal("12.5"))
        assertEquals(CollectStep.Idle, vm.step())
    }

    @Test
    fun `a fallback capture Station 2 books to another material warns`() = runTest {
        val prep = afterOne.preparation!!
        val alt = prep.materials.first { it.code == "RESIN" }.copy(
            code = "ALT", name = "Alternative", required = BigDecimal("8"), collected = BigDecimal.ZERO,
        )
        val before = afterOne.copy(preparation = prep.copy(materials = prep.materials + alt))
        val resin = prep.materials.first { it.code == "RESIN" }
        val after = before.copy(preparation = before.preparation!!.copy(materials = before.preparation!!.materials.map {
            if (it.code == "RESIN") resin.copy(collected = BigDecimal("15")) else it
        }))
        val vm = open(before)
        whenever(useCase.identify(prepId, "TAG-RESIN")).thenReturn(IdentifyResult.Unsupported(GeneralCollectionUseCase.IDENTIFY_FALLBACK))
        whenever(useCase.capture(prepId, "TAG-RESIN", BigDecimal("5"))).thenReturn(CollectionResult.Done(after))
        vm.onScan("TAG-RESIN")
        vm.pickFallbackMaterial("ALT")
        vm.onAmountChange("5")
        vm.submit()
        assertEquals(
            ResultBanner.Accepted("Recorded 5 kg Resin", warning = "Station 2 recorded this against Resin, not Alternative"),
            vm.uiState.value.banner,
        )
    }

    @Test
    fun `a failed identify shows the message and stays idle`() = runTest {
        val vm = open()
        whenever(useCase.identify(prepId, "TAG-RESIN")).thenReturn(IdentifyResult.Failed("Not connected", retryable = true))
        vm.onScan("TAG-RESIN")
        assertEquals(CollectStep.Idle, vm.step())
        assertEquals(OutcomeMessage.Info("Not connected"), vm.uiState.value.message)
    }

    // ---- Not in this mix ----

    private fun caco3(): PrepMaterial = PrepMaterial(
        code = "CACO3", name = "Calcium Carbonate", unit = "kg", perMix = BigDecimal.ZERO, required = BigDecimal("5"),
        collected = BigDecimal.ZERO, excluded = false, originalRequired = BigDecimal.ZERO,
    )

    private fun caSource() = source(code = "TAG-CA", material = "CACO3", name = "Calcium Carbonate", bag = "25")

    @Test
    fun `a pallet whose material is not in the mix needs an amount and a reason before adding`() = runTest {
        val vm = open()
        scanIdentified(vm, caSource())
        assertEquals(CollectStep.NotInMix(caSource(), "", ""), vm.step())
        vm.onAmountChange("5")
        vm.addToMix()                                            // no reason yet
        verify(useCase, never()).addIngredient(any(), any(), any(), any(), any())
        vm.onReasonChange("   ")
        vm.addToMix()
        verify(useCase, never()).addIngredient(any(), any(), any(), any(), any())
    }

    @Test
    fun `adding sends the PREP's current revision, never captures, then shows Identified for the same pallet`() = runTest {
        val vm = open()
        val added = withMaterial(opened, caco3())
        whenever(useCase.addIngredient(prepId, opened.preparation!!.collectionRevision, "TAG-CA", BigDecimal("5"), "Recipe correction"))
            .thenReturn(CollectionResult.Done(added))
        scanIdentified(vm, caSource())
        vm.onAmountChange("5")
        vm.onReasonChange("  Recipe correction ")
        vm.addToMix()
        verify(useCase, times(1)).addIngredient(
            prepId, opened.preparation!!.collectionRevision, "TAG-CA", BigDecimal("5"), "Recipe correction",
        )
        verify(useCase, never()).capture(any(), any(), anyOrNull())      // an adjustment never captures
        val step = vm.step() as CollectStep.Identified
        assertEquals("5", step.amountText)
        assertEquals("TAG-CA", step.source.code)
        assertEquals(added, vm.uiState.value.snapshot)
    }

    @Test
    fun `a revision conflict shows the reason, re-reads, and returns to idle without resending`() = runTest {
        val vm = open()
        whenever(useCase.addIngredient(any(), any(), any(), any(), any())).thenReturn(
            CollectionResult.Rejected(ErrorCode("collection_revision_conflict"), "The mix changed — try again", opened),
        )
        scanIdentified(vm, caSource())
        vm.onAmountChange("5")
        vm.onReasonChange("Why")
        vm.addToMix()
        verify(useCase, times(1)).addIngredient(any(), any(), any(), any(), any())
        verify(useCase, times(2)).read(prepId)
        assertEquals(CollectStep.Idle, vm.step())
        assertEquals(ResultBanner.Rejected("The mix changed — try again", null), vm.uiState.value.banner)
    }

    @Test
    fun `adding is blocked while a command is unresolved`() = runTest {
        val vm = open()
        scanIdentified(vm, caSource())
        vm.onAmountChange("5")
        vm.onReasonChange("Why")
        outbox.value = listOf(pending())
        vm.addToMix()
        verify(useCase, never()).addIngredient(any(), any(), any(), any(), any())
    }

    // ---- Mix complete ----

    @Test
    fun `Mix complete is offered in Collecting once every non-excluded row is complete`() = runTest {
        val vm = open(withResinCollected(opened, null))
        assertEquals(PrepStage.Collecting, vm.uiState.value.stage)
        assertTrue(vm.uiState.value.canConfirm)
        whenever(useCase.confirm(prepId)).thenReturn(CollectionResult.Done(confirmed))
        vm.confirm()
        verify(useCase, times(1)).confirm(prepId)
        assertEquals(PrepStage.ReadyForMixer, vm.uiState.value.stage)
    }

    @Test
    fun `Mix complete is not offered while a row is outstanding`() = runTest {
        val vm = open(afterOne)
        assertFalse(vm.uiState.value.canConfirm)
        vm.confirm()
        verify(useCase, never()).confirm(any())
    }

    @Test
    fun `an unresolved capture blocks the next scan and Submit`() = runTest {
        val vm = open()
        val cmd = pending()
        whenever(useCase.capture(prepId, "TAG-RESIN", BigDecimal("40"))).thenAnswer {
            outbox.value = listOf(cmd)
            CollectionResult.Unresolved(cmd, UnresolvedReason.RetryIdentical, "Station 2 has not confirmed this yet.")
        }
        scanIdentified(vm)
        vm.submit()
        assertEquals(listOf(cmd), vm.uiState.value.blocking)
        assertFalse(vm.uiState.value.canScan)
        assertEquals(CollectStep.Idle, vm.step())
        vm.onScan("TAG-RESIN-2")
        assertTrue(vm.uiState.value.message is OutcomeMessage.Info)
        verify(useCase, never()).identify(prepId, "TAG-RESIN-2")
        verify(useCase, times(1)).capture(any(), any(), anyOrNull())
    }

    @Test
    fun `Submit is refused when a command became unresolved after the pallet was identified`() = runTest {
        val vm = open()
        scanIdentified(vm)
        outbox.value = listOf(pending())
        vm.submit()
        verify(useCase, never()).capture(any(), any(), anyOrNull())
    }

    @Test
    fun `a read after an unresolved capture keeps it blocked`() = runTest {
        val vm = open()
        val cmd = pending()
        outbox.value = listOf(cmd)
        // A read showing the quantity went up is not proof the capture happened (contract §9).
        whenever(useCase.read(prepId)).thenReturn(ReadResult.Loaded(afterOne))
        vm.refresh()
        assertEquals(afterOne, vm.uiState.value.snapshot)
        assertEquals(listOf(cmd), vm.uiState.value.blocking)
        assertFalse(vm.uiState.value.canScan)
    }

    @Test
    fun `Retry republishes the identical command`() = runTest {
        val vm = open()
        val cmd = pending()
        outbox.value = listOf(cmd)
        whenever(useCase.retry(cmd)).thenAnswer {
            outbox.value = emptyList()
            CollectionResult.Done(afterOne)
        }
        vm.retry(cmd)
        verify(useCase).retry(cmd)
        verify(useCase, never()).capture(any(), any(), anyOrNull())
        assertEquals(afterOne, vm.uiState.value.snapshot)
        assertTrue(vm.uiState.value.blocking.isEmpty())
    }

    @Test
    fun `opening retries a same-session unresolved capture once`() = runTest {
        val cmd = pending()
        outbox.value = listOf(cmd)
        whenever(useCase.retry(cmd)).thenReturn(CollectionResult.Unresolved(cmd, UnresolvedReason.RetryIdentical, "x"))
        open()
        verify(useCase, times(1)).retry(cmd)
    }

    @Test
    fun `reconnect retries a same-session unresolved capture once`() = runTest {
        val vm = open()
        val cmd = pending()
        outbox.value = listOf(cmd)
        whenever(useCase.retry(cmd)).thenReturn(CollectionResult.Unresolved(cmd, UnresolvedReason.RetryIdentical, "x"))
        connection.value = MqttConnectionState.RECONNECTING
        connection.value = MqttConnectionState.CONNECTED
        verify(useCase, times(1)).retry(cmd)
        verify(useCase, times(2)).read(prepId)
        verify(useCase, never()).capture(any(), any(), anyOrNull())
        assertFalse(vm.uiState.value.canScan)
    }

    @Test
    fun `an earlier-session unresolved capture is not retried here`() = runTest {
        val cmd = pending(session = "sess-OLD")
        outbox.value = listOf(cmd)
        val vm = open()
        connection.value = MqttConnectionState.DISCONNECTED
        connection.value = MqttConnectionState.CONNECTED
        verify(useCase, never()).retry(any())
        assertEquals(listOf(cmd), vm.uiState.value.blocking)
        assertFalse(vm.uiState.value.canScan)
    }

    @Test
    fun `another PREP's unresolved command does not block this one`() = runTest {
        outbox.value = listOf(pending(target = "PREP_other", session = "sess-OLD"))
        val vm = open()
        assertTrue(vm.uiState.value.blocking.isEmpty())
        assertTrue(vm.uiState.value.canScan)
    }

    @Test
    fun `a manager-reconcile entry blocks scanning and confirm`() = runTest {
        outbox.value = listOf(pending(status = PendingStatus.ManagerReconcile))
        val vm = open(finalCapture)
        assertFalse(vm.uiState.value.canConfirm)
        vm.confirm()
        verify(useCase, never()).confirm(any())
        verify(useCase, never()).retry(any())

        outbox.value = listOf(pending(status = PendingStatus.ManagerReconcile))
        whenever(useCase.read(prepId)).thenReturn(ReadResult.Loaded(opened))
        vm.refresh()
        assertFalse(vm.uiState.value.canScan)
        vm.onScan("TAG-RESIN")
        assertEquals(CollectStep.Idle, vm.step())
        verify(useCase, never()).capture(any(), any(), anyOrNull())
    }

    @Test
    fun `message_id_conflict triggers recovery immediately`() = runTest {
        val vm = open()
        val cmd = pending()
        whenever(useCase.capture(prepId, "TAG-RESIN", BigDecimal("40"))).thenReturn(
            CollectionResult.Unresolved(cmd, UnresolvedReason.Recover, "Station 2 couldn't match this command."),
        )
        whenever(recovery.recover(cmd)).thenReturn(RecoveryResult.StillUnresolved(cmd, "no reply"))
        scanIdentified(vm)
        vm.submit()
        verify(recovery, times(1)).recover(cmd)
        assertTrue(vm.uiState.value.message is OutcomeMessage.Info)
        verify(useCase, times(1)).capture(any(), any(), anyOrNull())
    }

    @Test
    fun `an already-settled retry re-reads`() = runTest {
        val vm = open()
        val cmd = pending()
        whenever(useCase.retry(cmd)).thenReturn(CollectionResult.AlreadySettled(cmd.messageId))
        vm.retry(cmd)
        verify(useCase, times(2)).read(prepId)
    }

    @Test
    fun `confirm is offered only in AwaitingConfirmation with every included row complete`() = runTest {
        val ready = open(finalCapture)
        assertTrue(ready.uiState.value.canConfirm)
        assertFalse(ready.uiState.value.canScan)

        val collecting = open(afterOne)
        assertFalse(collecting.uiState.value.canConfirm)
        collecting.confirm()
        verify(useCase, never()).confirm(any())
    }

    @Test
    fun `confirm sends once and applies the result`() = runTest {
        val vm = open(finalCapture)
        whenever(useCase.confirm(prepId)).thenReturn(CollectionResult.Done(confirmed))
        vm.confirm()
        vm.confirm()
        verify(useCase, times(1)).confirm(prepId)
        assertEquals(PrepStage.ReadyForMixer, vm.uiState.value.stage)
    }

    @Test
    fun `an older read arriving after confirm does not regress the stage`() = runTest {
        val vm = open(finalCapture)
        val readA = CompletableDeferred<ReadResult>()
        whenever(useCase.read(prepId)).doSuspendableAnswer { readA.await() }
        vm.refresh()                                        // read A goes out first
        whenever(useCase.confirm(prepId)).thenReturn(CollectionResult.Done(confirmed))
        vm.confirm()                                        // confirm goes out later, answers first
        assertEquals(PrepStage.ReadyForMixer, vm.uiState.value.stage)
        readA.complete(ReadResult.Loaded(finalCapture))     // A's stale AwaitingConfirmation lands
        assertEquals(PrepStage.ReadyForMixer, vm.uiState.value.stage)
        assertFalse(vm.uiState.value.loading)
    }

    @Test
    fun `a hint re-reads and leaves pending commands alone`() = runTest {
        val cmd = pending(session = "sess-OLD")
        outbox.value = listOf(cmd)
        val vm = open()
        whenever(useCase.read(prepId)).thenReturn(ReadResult.Loaded(afterOne))
        hints.tryEmit(ServerHint("General", "preparation_created"))
        verify(useCase, times(2)).read(prepId)
        assertEquals(afterOne, vm.uiState.value.snapshot)
        assertEquals(listOf(cmd), vm.uiState.value.blocking)
        verify(useCase, never()).retry(any())
        verifyNoInteractions(recovery)
        verify(coordinator, never()).dismiss(any())
    }

    @Test
    fun `a Rajoo hint is ignored`() = runTest {
        open()
        hints.tryEmit(ServerHint("Rajoo", "preparation_created"))
        verify(useCase, times(1)).read(prepId)
    }

    @Test
    fun `a badge scan is refused`() = runTest {
        val vm = open()
        scan("505055AABBCC")
        assertEquals(CollectStep.Idle, vm.step())
        assertEquals(OutcomeMessage.Info("That is a user badge, not a pallet"), vm.uiState.value.message)
    }

    @Test
    fun `a failed read keeps the last snapshot and shows the error`() = runTest {
        val vm = open()
        whenever(useCase.read(prepId)).thenReturn(ReadResult.Failed("Station 2 did not respond.", null, retryable = true))
        vm.refresh()
        assertEquals(opened, vm.uiState.value.snapshot)
        assertEquals("Station 2 did not respond.", vm.uiState.value.readError)
    }

    // ---- Task 10: the confirm-replacement offer ----

    /** The delayed capture from the contract fixtures: TAG-RESIN, 10. */
    private val delayedPayload by lazy { ContractFixtures.text("general_delayed_sealed_request.json") }

    private fun notExecuted(action: String = "capture", target: String = prepId): RecoveryResult.Resolved {
        val c = pending(id = "m-old", session = "sess-OLD", target = target).copy(
            action = action,
            payload = if (action == "capture") delayedPayload else "{\"action\":\"$action\"}",
        )
        return RecoveryResult.Resolved(c, RecoveryOutcome.NotExecuted, "", Rev2Snapshot())
    }

    @Test
    fun `a notice for this PREP shows the offer after a fresh read`() = runTest {
        val vm = open()
        val fresh = CompletableDeferred<ReadResult>()
        whenever(useCase.read(prepId)).doSuspendableAnswer { fresh.await() }
        val notice = notExecuted()
        notices.value = listOf(notice)
        verify(useCase, times(2)).read(prepId)
        assertNull(vm.uiState.value.offer)                  // not before the read has applied
        fresh.complete(ReadResult.Loaded(afterOne))
        assertEquals(afterOne, vm.uiState.value.snapshot)
        assertEquals(
            ReplacementOffer.Capture(notice, prepId, CaptureIntent("TAG-RESIN", BigDecimal("10"))),
            vm.uiState.value.offer,
        )
        verify(useCase, never()).capture(any(), any(), anyOrNull())   // never sent without a tap
        // The same notice re-emitted is not offered (or read) again.
        notices.value = listOf(notice, notExecuted(target = "PREP_other"))
        verify(useCase, times(2)).read(prepId)
    }

    @Test
    fun `a notice for another PREP, or not not_executed, is not offered here`() = runTest {
        val vm = open()
        notices.value = listOf(
            notExecuted(target = "PREP_other"),
            notExecuted().copy(outcome = RecoveryOutcome.Committed),
            notExecuted(action = "prepare"),
        )
        assertNull(vm.uiState.value.offer)
        verify(useCase, times(1)).read(prepId)
    }

    @Test
    fun `accepting the offer sends a NEW capture with the same code and quantity and dismisses the notice`() = runTest {
        val vm = open()
        val notice = notExecuted()
        notices.value = listOf(notice)
        assertNotNull(vm.uiState.value.offer)
        whenever(useCase.capture(prepId, "TAG-RESIN", BigDecimal("10"))).thenReturn(CollectionResult.Done(afterOne))
        vm.acceptOffer()
        vm.acceptOffer()                                    // a double tap sends once
        verify(useCase, times(1)).capture(prepId, "TAG-RESIN", BigDecimal("10"))
        verify(useCase, never()).retry(any())
        verify(coordinator).dismiss(notice)
        assertNull(vm.uiState.value.offer)
        assertEquals(ResultBanner.Accepted("Recorded 10 kg Resin"), vm.uiState.value.banner)
    }

    @Test
    fun `the offer cannot be accepted while scanning is blocked`() = runTest {
        val vm = open()
        val notice = notExecuted()
        notices.value = listOf(notice)
        outbox.value = listOf(pending(session = "sess-OLD"))
        vm.acceptOffer()
        verify(useCase, never()).capture(any(), any(), anyOrNull())
        verify(useCase, never()).confirm(any())
        verify(coordinator, never()).dismiss(any())
        assertNotNull(vm.uiState.value.offer)
        assertTrue(vm.uiState.value.message is OutcomeMessage.Info)
    }

    @Test
    fun `declining dismisses without sending`() = runTest {
        val vm = open()
        val notice = notExecuted()
        notices.value = listOf(notice)
        vm.declineOffer()
        verify(coordinator).dismiss(notice)
        assertNull(vm.uiState.value.offer)
        verify(useCase, never()).capture(any(), any(), anyOrNull())
        verify(useCase, never()).retry(any())
    }

    @Test
    fun `a confirm offer sends a new confirm, only while confirm is allowed`() = runTest {
        val collecting = open(afterOne)
        notices.value = listOf(notExecuted(action = "confirm"))
        collecting.acceptOffer()
        verify(useCase, never()).confirm(any())

        val notice = notExecuted(action = "confirm")
        notices.value = emptyList()
        val ready = open(finalCapture)
        notices.value = listOf(notice)
        assertEquals(ReplacementOffer.Confirm(notice, prepId), ready.uiState.value.offer)
        whenever(useCase.confirm(prepId)).thenReturn(CollectionResult.Done(confirmed))
        ready.acceptOffer()
        verify(useCase, times(1)).confirm(prepId)
        verify(coordinator).dismiss(notice)
        assertEquals(PrepStage.ReadyForMixer, ready.uiState.value.stage)
    }

    @Test
    fun `a direct receipt_sealed reply re-reads, then offers the replacement`() = runTest {
        val vm = open()
        val cmd = pending().copy(payload = delayedPayload)
        val fresh = CompletableDeferred<ReadResult>()
        whenever(useCase.read(prepId)).doSuspendableAnswer { fresh.await() }
        whenever(useCase.retry(cmd)).thenReturn(
            CollectionResult.Rejected(ErrorCode.RECEIPT_SEALED, "Station 2 never ran this, and it can no longer run.", null),
        )
        vm.retry(cmd)
        verify(useCase, times(2)).read(prepId)
        assertNull(vm.uiState.value.offer)
        fresh.complete(ReadResult.Loaded(afterOne))
        assertEquals(
            ReplacementOffer.Capture(null, prepId, CaptureIntent("TAG-RESIN", BigDecimal("10"))),
            vm.uiState.value.offer,
        )
        verify(useCase, never()).capture(any(), any(), anyOrNull())
        verify(useCase, times(1)).retry(cmd)
        // Accepting a sealed offer has no notice to dismiss.
        whenever(useCase.capture(prepId, "TAG-RESIN", BigDecimal("10"))).thenReturn(CollectionResult.Done(afterOne))
        vm.acceptOffer()
        verify(useCase, times(1)).capture(prepId, "TAG-RESIN", BigDecimal("10"))
        verify(coordinator, never()).dismiss(any())
    }

    @Test
    fun `a not_executed recovery after message_id_conflict re-reads, then offers the replacement`() = runTest {
        val vm = open()
        val cmd = pending().copy(payload = delayedPayload)
        val result = RecoveryResult.Resolved(cmd, RecoveryOutcome.NotExecuted, "", Rev2Snapshot())
        whenever(useCase.capture(prepId, "TAG-RESIN", BigDecimal("40"))).thenReturn(
            CollectionResult.Unresolved(cmd, UnresolvedReason.Recover, "Station 2 couldn't match this command."),
        )
        whenever(recovery.recover(cmd)).thenReturn(result)
        scanIdentified(vm)
        vm.submit()
        verify(useCase, times(2)).read(prepId)
        assertEquals(
            ReplacementOffer.Capture(result, prepId, CaptureIntent("TAG-RESIN", BigDecimal("10"))),
            vm.uiState.value.offer,
        )
        verify(useCase, times(1)).capture(any(), any(), anyOrNull())
    }

    // ---- folded review items ----

    @Test
    fun `a read that throws shows a message instead of crashing`() = runTest {
        val vm = open()
        whenever(useCase.read(prepId)).thenThrow(IllegalStateException("boom"))
        vm.refresh()
        assertEquals(OutcomeMessage.Info("Could not read this preparation: boom"), vm.uiState.value.message)
        assertFalse(vm.uiState.value.loading)
        assertEquals(opened, vm.uiState.value.snapshot)
    }

    @Test
    fun `a capture that throws shows a message instead of crashing`() = runTest {
        val vm = open()
        whenever(useCase.capture(prepId, "TAG-RESIN", BigDecimal("40"))).thenThrow(IllegalStateException("boom"))
        scanIdentified(vm)
        vm.submit()
        assertEquals(
            OutcomeMessage.Info("Something went wrong sending this: boom. Check the preparation before trying again."),
            vm.uiState.value.message,
        )
        assertFalse(vm.uiState.value.inFlight)
    }

    @Test
    fun `an open-time retry before the first read shows the result message, not the whole total`() = runTest {
        val cmd = pending()
        outbox.value = listOf(cmd)
        whenever(useCase.retry(cmd)).thenReturn(CollectionResult.Done(finalCapture))
        val vm = open()
        assertEquals(OutcomeMessage.Info(finalCapture.resultMessage), vm.uiState.value.message)
    }

    @Test
    fun `a replacement that was not sent keeps the offer and its notice`() = runTest {
        val vm = open()
        val notice = notExecuted()
        notices.value = listOf(notice)
        val offer = vm.uiState.value.offer
        assertNotNull(offer)
        whenever(useCase.capture(prepId, "TAG-RESIN", BigDecimal("10")))
            .thenReturn(CollectionResult.NotSent("Not connected — nothing was sent"))
        vm.acceptOffer()
        verify(useCase, times(1)).capture(prepId, "TAG-RESIN", BigDecimal("10"))
        verify(coordinator, never()).dismiss(any())
        assertEquals(offer, vm.uiState.value.offer)
        assertEquals(OutcomeMessage.Info("Not connected — nothing was sent"), vm.uiState.value.message)
        // Once connected, the operator taps again: a NEW capture is sent, and Done dismisses the notice.
        whenever(useCase.capture(prepId, "TAG-RESIN", BigDecimal("10"))).thenReturn(CollectionResult.Done(afterOne))
        vm.acceptOffer()
        verify(useCase, times(2)).capture(prepId, "TAG-RESIN", BigDecimal("10"))
        verify(useCase, never()).retry(any())
        verify(coordinator).dismiss(notice)
        assertNull(vm.uiState.value.offer)
    }

    @Test
    fun `the offer stays until its send settles, a double tap sends once, and Unresolved dismisses`() = runTest {
        val vm = open()
        val notice = notExecuted()
        notices.value = listOf(notice)
        val reply = CompletableDeferred<CollectionResult>()
        whenever(useCase.capture(prepId, "TAG-RESIN", BigDecimal("10"))).doSuspendableAnswer { reply.await() }
        vm.acceptOffer()
        assertTrue(vm.uiState.value.inFlight)
        assertNotNull(vm.uiState.value.offer)
        verify(coordinator, never()).dismiss(any())
        vm.acceptOffer()                                    // second tap while in flight
        val cmd = pending(id = "m-new")
        outbox.value = listOf(cmd)
        reply.complete(CollectionResult.Unresolved(cmd, UnresolvedReason.RetryIdentical, "Station 2 has not confirmed this yet."))
        verify(useCase, times(1)).capture(prepId, "TAG-RESIN", BigDecimal("10"))
        verify(coordinator).dismiss(notice)
        assertNull(vm.uiState.value.offer)
        assertFalse(vm.uiState.value.canScan)
    }

    @Test
    fun `opening with the other-case PREP id stays blocked by an unresolved capture`() = runTest {
        val cmd = pending(session = "sess-OLD")             // sent while open as PREP_440fbd…
        outbox.value = listOf(cmd)
        val vm = open(id = prepId.uppercase())              // reopened as PREP_440FBD…
        assertEquals(listOf(cmd), vm.uiState.value.blocking)
        assertFalse(vm.uiState.value.canScan)
        scanIdentified(vm)
        vm.submit()
        verify(useCase, never()).capture(any(), any(), anyOrNull())
        // The snapshot for the same PREP in the other case is still this PREP's.
        assertEquals(opened, vm.uiState.value.snapshot)
    }

    @Test
    fun `a notice for this PREP in the other case is offered here`() = runTest {
        val vm = open(id = prepId.uppercase())
        notices.value = listOf(notExecuted())
        assertNotNull(vm.uiState.value.offer)
        whenever(useCase.capture(prepId.uppercase(), "TAG-RESIN", BigDecimal("10"))).thenReturn(CollectionResult.Done(afterOne))
        vm.acceptOffer()
        verify(useCase, times(1)).capture(prepId.uppercase(), "TAG-RESIN", BigDecimal("10"))
    }

    @Test
    fun `a read snapshot for a different PREP is not applied`() = runTest {
        val vm = open()
        val other = afterOne.copy(preparation = afterOne.preparation!!.copy(id = "PREP_other"))
        whenever(useCase.read(prepId)).thenReturn(ReadResult.Loaded(other))
        vm.refresh()
        assertEquals(opened, vm.uiState.value.snapshot)
        // A later snapshot for this PREP still applies.
        whenever(useCase.read(prepId)).thenReturn(ReadResult.Loaded(afterOne))
        vm.refresh()
        assertEquals(afterOne, vm.uiState.value.snapshot)
    }

    @Test
    fun `a recovery snapshot for a different PREP is not applied`() = runTest {
        val vm = open()
        val cmd = pending()
        val otherRev2 = WireJson.gson.fromJson(
            JsonParser.parseString(ContractFixtures.text("general_capture_response.json")).asJsonObject["data"],
            Rev2Snapshot::class.java,
        ).let { it.copy(preparation = it.preparation!!.copy(id = "PREP_other")) }
        whenever(useCase.capture(prepId, "TAG-RESIN", BigDecimal("40"))).thenReturn(
            CollectionResult.Unresolved(cmd, UnresolvedReason.Recover, "Station 2 couldn't match this command."),
        )
        whenever(recovery.recover(cmd)).thenReturn(RecoveryResult.Resolved(cmd, RecoveryOutcome.Committed, "", otherRev2))
        scanIdentified(vm)
        vm.submit()
        assertEquals(opened, vm.uiState.value.snapshot)
    }

    @Test
    fun `a rejection with no snapshot re-reads`() = runTest {
        val vm = open()
        whenever(useCase.capture(prepId, "TAG-X", BigDecimal("40"))).thenReturn(
            CollectionResult.Rejected(ErrorCode("rev2_rejected"), "That pallet is not on this job", null),
        )
        scanIdentified(vm, source(code = "TAG-X"))
        vm.submit()
        verify(useCase, times(2)).read(prepId)
        assertEquals(ResultBanner.Rejected("That pallet is not on this job", null), vm.uiState.value.banner)
    }

    // ---- fix(collect) 3: canConfirm ----

    @Test
    fun `awaiting confirmation can confirm even with no local rows or only excluded rows`() = runTest {
        val awaiting = finalCapture.preparation!!
        val empty = finalCapture.copy(preparation = awaiting.copy(materials = emptyList()))
        assertTrue(open(empty).uiState.value.canConfirm)
        val excluded = finalCapture.copy(preparation = awaiting.copy(materials = awaiting.materials.map { it.copy(excluded = true) }))
        assertTrue(open(excluded).uiState.value.canConfirm)
    }

    @Test
    fun `collecting with everything collected can confirm and with one outstanding cannot`() = runTest {
        assertTrue(open(withResinCollected(opened, null)).uiState.value.canConfirm)
        assertFalse(open(opened).uiState.value.canConfirm)
    }

    // ---- fix(collect) 1: a read racing the last capture must not drop its snapshot ----

    @Test
    fun `a hint read that lands during the last capture does not leave a stale snapshot`() = runTest {
        val vm = open()
        // A read answered while the last capture is in flight returns older data; later reads are the truth.
        var racing = false
        whenever(useCase.read(prepId)).thenAnswer { ReadResult.Loaded(if (racing) afterOne else finalCapture) }
        val gate = CompletableDeferred<Unit>()
        whenever(useCase.capture(prepId, "TAG-RESIN", BigDecimal("15"))).thenReturn(CollectionResult.Done(afterOne))
        whenever(useCase.capture(prepId, "TAG-RESIN", BigDecimal("10"))).doSuspendableAnswer {
            gate.await()
            CollectionResult.Done(finalCapture)
        }
        scanIdentified(vm, source(bag = "15"))
        vm.submit()
        // The last capture is in flight; a hint's read takes a higher sequence number.
        racing = true
        vm.refresh()
        racing = false
        gate.complete(Unit)
        assertEquals(finalCapture, vm.uiState.value.snapshot)
    }


    // ---- fix(collect) 2: nothing is sent between bags once the PREP is blocked ----

    @Test
    fun `a block that appears after the first bag stops the sequence and reports Recorded 1 of 3`() = runTest {
        val vm = open()
        val cmd = pending(status = PendingStatus.ManagerReconcile)
        whenever(useCase.capture(prepId, "TAG-RESIN", BigDecimal("15"))).doSuspendableAnswer {
            outbox.value = listOf(cmd)
            yield()   // let the outbox collector publish `blocking`
            CollectionResult.Done(afterOne)
        }
        scanIdentified(vm, source(bag = "15"))
        vm.submit()
        verify(useCase, times(1)).capture(any(), any(), anyOrNull())
        val text = (vm.uiState.value.message as OutcomeMessage.Info).text
        assertTrue(text, text.startsWith("Recorded 1 of 3 (15 kg)"))
    }
}
