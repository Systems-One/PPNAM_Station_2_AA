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
import com.mitas.ppnam.station2aa.domain.usecase.toPrepSnapshot
import java.math.BigDecimal
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
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

    @Test
    fun `opening reads the exact PREP id`() = runTest {
        val vm = open()
        verify(useCase).read(prepId)
        verify(useCase, never()).prepare(any(), any(), anyOrNull(), any(), anyOrNull())
        assertEquals(PrepStage.Collecting, vm.uiState.value.stage)
        assertTrue(vm.uiState.value.canScan)
    }

    @Test
    fun `a scan stages and never sends`() = runTest {
        val vm = open()
        scan("  TAG-RESIN ")
        assertEquals(Staged.WholeBag("TAG-RESIN"), vm.uiState.value.staged)
        verify(useCase, never()).capture(any(), any(), anyOrNull())
        verify(useCase, never()).confirm(any())
    }

    @Test
    fun `scans are ignored while the screen is not active`() = runTest {
        val vm = open()
        vm.setScreenActive(false)
        scan("TAG-RESIN")
        assertNull(vm.uiState.value.staged)
    }

    @Test
    fun `record sends one capture with an omitted quantity, and the same code stays staged`() = runTest {
        val vm = open()
        whenever(useCase.capture(prepId, "TAG-RESIN", null)).thenReturn(CollectionResult.Done(afterOne))
        vm.onScan("TAG-RESIN")
        vm.recordStaged()
        verify(useCase, times(1)).capture(prepId, "TAG-RESIN", null)
        assertEquals(Staged.WholeBag("TAG-RESIN"), vm.uiState.value.staged)
        assertEquals(OutcomeMessage.Recorded("Recorded 10 kg Resin — 30 kg to go"), vm.uiState.value.message)
        assertEquals(afterOne, vm.uiState.value.snapshot)
        assertFalse(vm.uiState.value.inFlight)
    }

    @Test
    fun `two taps while in flight send one capture`() = runTest {
        val vm = open()
        val reply = CompletableDeferred<CollectionResult>()
        whenever(useCase.capture(prepId, "TAG-RESIN", null)).doSuspendableAnswer { reply.await() }
        vm.onScan("TAG-RESIN")
        vm.recordStaged()
        assertTrue(vm.uiState.value.inFlight)
        vm.recordStaged()
        reply.complete(CollectionResult.Done(afterOne))
        verify(useCase, times(1)).capture(any(), any(), anyOrNull())
        assertFalse(vm.uiState.value.inFlight)
    }

    @Test
    fun `a part bag above the picked row's remaining is refused locally`() = runTest {
        val vm = open(afterOne)
        vm.onScan("TAG-RESIN")
        vm.stagePart("RESIN")
        vm.onPartQuantity("30.001")
        vm.recordStaged()
        verify(useCase, never()).capture(any(), any(), anyOrNull())
        assertEquals(OutcomeMessage.Info("Enter up to 30 kg"), vm.uiState.value.message)
        assertEquals(Staged.PartBag("TAG-RESIN", "RESIN", "30.001"), vm.uiState.value.staged)
    }

    @Test
    fun `a part bag that is not a quantity is refused locally`() = runTest {
        val vm = open(afterOne)
        vm.onScan("TAG-RESIN")
        vm.stagePart("RESIN")
        vm.onPartQuantity("0")
        vm.recordStaged()
        verify(useCase, never()).capture(any(), any(), anyOrNull())
        assertEquals(OutcomeMessage.Info("Enter up to 30 kg"), vm.uiState.value.message)
    }

    @Test
    fun `a part bag equal to remaining is sent exactly, then the mode resets to whole bag`() = runTest {
        val vm = open(afterOne)
        whenever(useCase.capture(prepId, "TAG-RESIN", BigDecimal("30"))).thenReturn(CollectionResult.Done(finalCapture))
        vm.onScan("TAG-RESIN")
        vm.stagePart("RESIN")
        vm.onPartQuantity("30")
        vm.recordStaged()
        verify(useCase).capture(prepId, "TAG-RESIN", BigDecimal("30"))
        assertEquals(Staged.WholeBag("TAG-RESIN"), vm.uiState.value.staged)
        assertEquals(OutcomeMessage.Recorded("Recorded 30 kg Resin — 0 kg to go"), vm.uiState.value.message)
    }

    @Test
    fun `scanning the code staged as a part bag keeps the part mode`() = runTest {
        val vm = open(afterOne)
        vm.onScan("TAG-RESIN")
        vm.stagePart("RESIN")
        vm.onPartQuantity("5")
        vm.onScan("TAG-RESIN")
        assertEquals(Staged.PartBag("TAG-RESIN", "RESIN", "5"), vm.uiState.value.staged)
        vm.onScan("TAG-OTHER")
        assertEquals(Staged.WholeBag("TAG-OTHER"), vm.uiState.value.staged)
    }

    @Test
    fun `a part bag recorded against another material warns`() = runTest {
        // Two open rows: RESIN (remaining 30) and an added ALT (remaining 8). The operator picks ALT;
        // Station 2 decides the pallet is resin.
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
        whenever(useCase.capture(prepId, "TAG-RESIN", BigDecimal("5"))).thenReturn(CollectionResult.Done(after))
        vm.onScan("TAG-RESIN")
        vm.stagePart("ALT")
        vm.onPartQuantity("5")
        vm.recordStaged()
        assertEquals(
            OutcomeMessage.Recorded(
                "Recorded 5 kg Resin — 25 kg to go",
                warning = "Station 2 recorded this against Resin, not Alternative",
            ),
            vm.uiState.value.message,
        )
    }

    @Test
    fun `a rejected capture shows the server's exception record`() = runTest {
        val vm = open()
        val record = CollectionExceptionRecord(
            id = "EX-1", timestampUtc = "2026-10-01T08:00:00Z", operatorId = "OP-1", device = "scanner_1",
            messageId = "m-9", jobId = "510019068", batchId = null, kind = "Capture rejected",
            details = "Pallet not on the job", reason = "", reviewedAtUtc = null, reviewedBy = null, reviewReason = null,
        )
        val withRecord = opened.copy(commandExceptions = listOf(record))
        whenever(useCase.capture(prepId, "TAG-X", null)).thenReturn(
            CollectionResult.Rejected(ErrorCode("rev2_rejected"), "That pallet is not on this job", withRecord),
        )
        vm.onScan("TAG-X")
        vm.recordStaged()
        assertEquals(OutcomeMessage.Rejected("That pallet is not on this job", listOf(record)), vm.uiState.value.message)
        assertEquals(withRecord, vm.uiState.value.snapshot)
    }

    @Test
    fun `a sealed receipt is shown as a rejection`() = runTest {
        val vm = open()
        whenever(useCase.capture(prepId, "TAG-RESIN", null)).thenReturn(
            CollectionResult.Rejected(ErrorCode.RECEIPT_SEALED, "Station 2 never ran this, and it can no longer run.", opened),
        )
        vm.onScan("TAG-RESIN")
        vm.recordStaged()
        assertEquals(
            OutcomeMessage.Rejected("Station 2 never ran this, and it can no longer run.", emptyList()),
            vm.uiState.value.message,
        )
    }

    @Test
    fun `not sent shows the message and keeps the staged bag`() = runTest {
        val vm = open()
        whenever(useCase.capture(prepId, "TAG-RESIN", null)).thenReturn(CollectionResult.NotSent("Not connected — nothing was sent"))
        vm.onScan("TAG-RESIN")
        vm.recordStaged()
        assertEquals(OutcomeMessage.Info("Not connected — nothing was sent"), vm.uiState.value.message)
        assertEquals(Staged.WholeBag("TAG-RESIN"), vm.uiState.value.staged)
    }

    @Test
    fun `an unresolved capture blocks the next scan`() = runTest {
        val vm = open()
        val cmd = pending()
        whenever(useCase.capture(prepId, "TAG-RESIN", null)).thenAnswer {
            outbox.value = listOf(cmd)
            CollectionResult.Unresolved(cmd, UnresolvedReason.RetryIdentical, "Station 2 has not confirmed this yet.")
        }
        vm.onScan("TAG-RESIN")
        vm.recordStaged()
        assertEquals(listOf(cmd), vm.uiState.value.blocking)
        assertFalse(vm.uiState.value.canScan)
        vm.onScan("TAG-RESIN-2")
        assertTrue(vm.uiState.value.message is OutcomeMessage.Info)
        vm.recordStaged()
        verify(useCase, times(1)).capture(any(), any(), anyOrNull())
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
        assertNull(vm.uiState.value.staged)
        verify(useCase, never()).capture(any(), any(), anyOrNull())
    }

    @Test
    fun `message_id_conflict triggers recovery immediately`() = runTest {
        val vm = open()
        val cmd = pending()
        whenever(useCase.capture(prepId, "TAG-RESIN", null)).thenReturn(
            CollectionResult.Unresolved(cmd, UnresolvedReason.Recover, "Station 2 couldn't match this command."),
        )
        whenever(recovery.recover(cmd)).thenReturn(RecoveryResult.StillUnresolved(cmd, "no reply"))
        vm.onScan("TAG-RESIN")
        vm.recordStaged()
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
        assertNull(vm.uiState.value.staged)
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
        assertEquals(OutcomeMessage.Recorded("Recorded 10 kg Resin — 30 kg to go"), vm.uiState.value.message)
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
        whenever(useCase.capture(prepId, "TAG-RESIN", null)).thenReturn(
            CollectionResult.Unresolved(cmd, UnresolvedReason.Recover, "Station 2 couldn't match this command."),
        )
        whenever(recovery.recover(cmd)).thenReturn(result)
        vm.onScan("TAG-RESIN")
        vm.recordStaged()
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
        whenever(useCase.capture(prepId, "TAG-RESIN", null)).thenThrow(IllegalStateException("boom"))
        vm.onScan("TAG-RESIN")
        vm.recordStaged()
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
    fun `scans are ignored while the part-bag sheet is open`() = runTest {
        val vm = open(afterOne)
        vm.onScan("TAG-RESIN")
        vm.stagePart("RESIN")
        vm.onPartQuantity("5")
        vm.setPartSheetOpen(true)
        scan("TAG-OTHER")
        assertEquals(Staged.PartBag("TAG-RESIN", "RESIN", "5"), vm.uiState.value.staged)
        vm.setPartSheetOpen(false)
        scan("TAG-OTHER")
        assertEquals(Staged.WholeBag("TAG-OTHER"), vm.uiState.value.staged)
    }

    // ---- final review fixes ----

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
        vm.onScan("TAG-RESIN")
        vm.recordStaged()
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
        whenever(useCase.capture(prepId, "TAG-RESIN", null)).thenReturn(
            CollectionResult.Unresolved(cmd, UnresolvedReason.Recover, "Station 2 couldn't match this command."),
        )
        whenever(recovery.recover(cmd)).thenReturn(RecoveryResult.Resolved(cmd, RecoveryOutcome.Committed, "", otherRev2))
        vm.onScan("TAG-RESIN")
        vm.recordStaged()
        assertEquals(opened, vm.uiState.value.snapshot)
    }

    @Test
    fun `a rejection with no snapshot re-reads`() = runTest {
        val vm = open()
        whenever(useCase.capture(prepId, "TAG-X", null)).thenReturn(
            CollectionResult.Rejected(ErrorCode("rev2_rejected"), "That pallet is not on this job", null),
        )
        vm.onScan("TAG-X")
        vm.recordStaged()
        verify(useCase, times(2)).read(prepId)
        assertEquals(OutcomeMessage.Rejected("That pallet is not on this job", emptyList()), vm.uiState.value.message)
    }
}
