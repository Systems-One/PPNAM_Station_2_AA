package com.mitas.ppnam.station2aa.ui.preparation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mitas.ppnam.station2aa.UserTagPolicy
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
import com.mitas.ppnam.station2aa.domain.model.QuantityInput
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.usecase.AuthUseCase
import com.mitas.ppnam.station2aa.domain.usecase.CollectionResult
import com.mitas.ppnam.station2aa.domain.usecase.CommandRecoveryUseCase
import com.mitas.ppnam.station2aa.domain.usecase.GeneralCollectionUseCase
import com.mitas.ppnam.station2aa.domain.usecase.PendingCommandCoordinator
import com.mitas.ppnam.station2aa.domain.usecase.ReadResult
import com.mitas.ppnam.station2aa.domain.usecase.RecoveryResult
import com.mitas.ppnam.station2aa.domain.usecase.toPrepSnapshot
import com.mitas.ppnam.station2aa.ui.components.ConnectionStatus
import com.mitas.ppnam.station2aa.ui.components.connectionStatusIn
import com.mitas.ppnam.station2aa.ui.home.noticeText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject

sealed interface Staged {
    val code: String
    data class WholeBag(override val code: String) : Staged
    data class PartBag(override val code: String, val materialCode: String, val quantityText: String) : Staged
}

data class PreparationUiState(
    val prepId: String,
    val snapshot: PrepSnapshot? = null,
    val loading: Boolean = false,
    val readError: String? = null,
    val inFlight: Boolean = false,
    val staged: Staged? = null,
    /** Unresolved/ManagerReconcile outbox entries whose targetId is this PREP. */
    val blocking: List<PendingCommand> = emptyList(),
    val message: OutcomeMessage? = null,
) {
    val stage: PrepStage? get() = snapshot?.preparation?.stage
    val canScan: Boolean get() = stage == PrepStage.Collecting && !inFlight && blocking.isEmpty()
    val canConfirm: Boolean get() = stage == PrepStage.AwaitingConfirmation &&
        snapshot?.preparation?.allIncludedCollected == true && !inFlight && blocking.isEmpty()
}

sealed interface OutcomeMessage {
    data class Recorded(val text: String, val warning: String? = null) : OutcomeMessage
    data class Rejected(val text: String, val records: List<CollectionExceptionRecord>) : OutcomeMessage
    data class Info(val text: String) : OutcomeMessage
}

/**
 * The PREP screen: stage a scanned pallet, record it, confirm collection, and retry a command whose
 * reply was lost. Safety rules (plan Review Focus 1–3):
 * - while an Unresolved/ManagerReconcile command targets this PREP, nothing new is sent: scans and
 *   confirm are blocked, and Retry republishes the identical saved command;
 * - [PreparationUiState.blocking] comes from the outbox only — a read is not proof (contract §9);
 * - every request takes a local sequence number before it suspends, and a snapshot is applied only
 *   if it is newer than the last one applied, so a stale reply never rolls the stage back.
 *
 * All state below is touched only on the main thread (viewModelScope).
 */
@HiltViewModel
class PreparationViewModel @Inject constructor(
    private val useCase: GeneralCollectionUseCase,
    private val recovery: CommandRecoveryUseCase,
    private val coordinator: PendingCommandCoordinator,
    private val mqttRepository: MqttRepository,
    scanEventBus: ScanEventBus,
    private val sessionHolder: OperatorSessionHolder,
    private val authUseCase: AuthUseCase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val prepId: String = checkNotNull(savedStateHandle.get<String>(PREP_ID_ARG)) { "prepId is required" }

    private val _uiState = MutableStateFlow(PreparationUiState(prepId = prepId))
    val uiState: StateFlow<PreparationUiState> = _uiState.asStateFlow()

    val connectionStatus: StateFlow<ConnectionStatus> = mqttRepository.connectionStatusIn(viewModelScope)

    val session: StateFlow<OperatorSession?> = sessionHolder.session

    /** The last sequence number handed out, and the newest whose snapshot is on screen. */
    private var issued = 0L
    private var applied = 0L
    private var readsInFlight = 0
    private var mutationsInFlight = 0
    /** Message ids being retried now, so a double tap on Retry publishes once. */
    private val retrying = mutableSetOf<String>()

    @Volatile
    private var screenActive = false

    init {
        // Rule 2: blocking is the outbox's view of this PREP, never a read's.
        viewModelScope.launch {
            coordinator.pending.collect { all ->
                _uiState.update { it.copy(blocking = all.filter(::blocksThisPrep)) }
            }
        }
        // Rule 5: a push is a reason to read again; it never touches the outbox.
        viewModelScope.launch {
            mqttRepository.serverHints.collect { hint ->
                if (hint.mode.isNotBlank() && !hint.mode.equals("General", ignoreCase = true)) return@collect
                if (sessionHolder.session.value == null) return@collect
                read()
            }
        }
        // Rule 4: on each transition back INTO CONNECTED, retry this session's unresolved commands, then read.
        viewModelScope.launch {
            var previous: MqttConnectionState? = null
            mqttRepository.connectionState.collect { state ->
                val reconnected = previous != null && previous != MqttConnectionState.CONNECTED &&
                    state == MqttConnectionState.CONNECTED
                previous = state
                if (!reconnected || sessionHolder.session.value == null) return@collect
                retryOwnUnresolved()
                read()
            }
        }
        // Rule 9: scans reach this screen only while it is visible.
        viewModelScope.launch {
            scanEventBus.events.collect { event ->
                if (!screenActive) return@collect
                onScan(
                    when (event) {
                        is ScanEvent.Barcode -> event.value
                        is ScanEvent.RfidTag -> event.tagId
                    }
                )
            }
        }
        // Rule 3: on open, retry this session's unresolved commands and read the PREP.
        retryOwnUnresolved()
        read()
    }

    /** Called by the PREP screen as it becomes visible or hidden, so scans only act on that screen. */
    fun setScreenActive(active: Boolean) {
        screenActive = active
    }

    fun refresh() = read()

    fun onScan(code: String) {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return
        if (UserTagPolicy.isUserTag(trimmed)) {
            show(OutcomeMessage.Info(BADGE_MESSAGE))
            return
        }
        val state = _uiState.value
        if (!state.canScan) {
            show(OutcomeMessage.Info(whyNoScan(state)))
            return
        }
        _uiState.update {
            val current = it.staged
            it.copy(staged = if (current is Staged.PartBag && current.code == trimmed) current else Staged.WholeBag(trimmed))
        }
    }

    fun stageWholeBag() {
        _uiState.update { s -> s.copy(staged = s.staged?.let { Staged.WholeBag(it.code) }) }
    }

    fun stagePart(materialCode: String) {
        _uiState.update { s ->
            val staged = s.staged ?: return@update s
            val keepText = (staged as? Staged.PartBag)?.takeIf { it.materialCode == materialCode }?.quantityText
            s.copy(staged = Staged.PartBag(staged.code, materialCode, keepText ?: ""))
        }
    }

    fun onPartQuantity(text: String) {
        _uiState.update { s ->
            val staged = s.staged as? Staged.PartBag ?: return@update s
            s.copy(staged = staged.copy(quantityText = text))
        }
    }

    fun clearStaged() {
        _uiState.update { it.copy(staged = null) }
    }

    /** Rule 7: one deliberate tap sends one capture of the staged bag. */
    fun recordStaged() {
        val state = _uiState.value
        if (state.inFlight || !state.canScan) return
        val staged = state.staged ?: return
        val quantity: BigDecimal? = when (staged) {
            is Staged.WholeBag -> null
            is Staged.PartBag -> {
                val material = state.snapshot?.preparation?.materials?.firstOrNull { it.code == staged.materialCode }
                    ?: return show(OutcomeMessage.Info(PICK_MATERIAL_MESSAGE))
                val q = QuantityInput.parse(staged.quantityText)
                if (q == null || !material.fits(q)) {
                    return show(OutcomeMessage.Info("Enter up to ${material.remaining.plain()} ${material.unit}"))
                }
                q
            }
        }
        mutate(fromCapture = true, part = staged as? Staged.PartBag) { useCase.capture(prepId, staged.code, quantity) }
    }

    /** Rule 8: confirm collection, only when Station 2 is waiting for it. */
    fun confirm() {
        if (!_uiState.value.canConfirm) return
        mutate(fromCapture = false, part = null) { useCase.confirm(prepId) }
    }

    /** Republishes [command]'s identical saved bytes — never a new request for the same intent. */
    fun retry(command: PendingCommand) {
        if (!retrying.add(command.messageId)) return
        mutate(fromCapture = false, part = null, onFinally = { retrying.remove(command.messageId) }) {
            useCase.retry(command)
        }
    }

    fun logout() {
        viewModelScope.launch { authUseCase.logout() }
    }

    private fun blocksThisPrep(c: PendingCommand): Boolean =
        c.targetId == prepId && (c.status == PendingStatus.Unresolved || c.status == PendingStatus.ManagerReconcile)

    /**
     * Rule 3/4: this session's own unresolved commands are retried identically here. One from an
     * earlier session is the coordinator's to recover after sign-in.
     */
    private fun retryOwnUnresolved() {
        val sessionId = sessionHolder.session.value?.operatorSessionId ?: return
        coordinator.pending.value
            .filter { blocksThisPrep(it) && it.status == PendingStatus.Unresolved && it.sessionId == sessionId }
            .forEach(::retry)
    }

    private fun read() {
        val seq = ++issued
        readsInFlight++
        _uiState.update { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                when (val result = useCase.read(prepId)) {
                    is ReadResult.Loaded -> {
                        val fresh = takeIfNewer(seq, result.snapshot)
                        if (fresh != null) _uiState.update { it.copy(snapshot = fresh, readError = null) }
                    }
                    is ReadResult.Failed -> {
                        val fresh = takeIfNewer(seq, result.snapshot)
                        _uiState.update { it.copy(snapshot = fresh ?: it.snapshot, readError = result.message) }
                    }
                }
            } finally {
                readsInFlight--
                _uiState.update { it.copy(loading = readsInFlight > 0) }
            }
        }
    }

    /**
     * Runs one mutation. [part] is the part bag being recorded (for the wrong-row warning);
     * [fromCapture] keeps the staged pallet ready for its next bag.
     */
    private fun mutate(
        fromCapture: Boolean,
        part: Staged.PartBag?,
        onFinally: () -> Unit = {},
        call: suspend () -> CollectionResult,
    ) {
        val seq = ++issued
        val before = _uiState.value.snapshot
        mutationsInFlight++
        _uiState.update { it.copy(inFlight = true) }
        viewModelScope.launch {
            try {
                handle(seq, call(), before, part, fromCapture)
            } finally {
                mutationsInFlight--
                onFinally()
                _uiState.update { it.copy(inFlight = mutationsInFlight > 0) }
            }
        }
    }

    private suspend fun handle(
        seq: Long,
        result: CollectionResult,
        before: PrepSnapshot?,
        part: Staged.PartBag?,
        fromCapture: Boolean,
    ) {
        when (result) {
            is CollectionResult.Done -> {
                val fresh = takeIfNewer(seq, result.snapshot)
                val message = doneMessage(before, result.snapshot, part)
                _uiState.update { s ->
                    s.copy(
                        snapshot = fresh ?: s.snapshot,
                        message = message,
                        staged = if (fromCapture) s.staged?.let { Staged.WholeBag(it.code) } else s.staged,
                    )
                }
            }
            // receipt_sealed lands here too; Task 10 replaces it with a replacement offer.
            is CollectionResult.Rejected -> {
                val fresh = takeIfNewer(seq, result.snapshot)
                _uiState.update { s ->
                    s.copy(
                        snapshot = fresh ?: s.snapshot,
                        message = OutcomeMessage.Rejected(result.message, result.snapshot?.commandExceptions.orEmpty()),
                    )
                }
            }
            // No local change: the outbox now holds the command, and blocking follows from it.
            is CollectionResult.Unresolved -> {
                if (result.reason == UnresolvedReason.Recover) recoverNow(result.command)
                else show(OutcomeMessage.Info(result.message))
            }
            is CollectionResult.NotSent -> show(OutcomeMessage.Info(result.message))
            is CollectionResult.AlreadySettled -> read()
        }
    }

    /** message_id_conflict: ask Station 2 at once what became of the command (Task 10 adds the offer). */
    private suspend fun recoverNow(command: PendingCommand) {
        val seq = ++issued
        val result = try {
            recovery.recover(command)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RecoveryResult.StillUnresolved(command, "Could not check this command: ${e.message}")
        }
        val fresh = (result as? RecoveryResult.Resolved)?.let { takeIfNewer(seq, it.snapshot.toPrepSnapshot()) }
        _uiState.update { s ->
            s.copy(snapshot = fresh ?: s.snapshot, message = OutcomeMessage.Info(result.noticeText(mqttRepository.deviceId)))
        }
    }

    /** Rule 1: claims [seq] as the newest applied snapshot, or returns null for a stale one. */
    private fun takeIfNewer(seq: Long, snapshot: PrepSnapshot?): PrepSnapshot? {
        if (snapshot == null || seq <= applied) return null
        applied = seq
        return snapshot
    }

    private fun show(message: OutcomeMessage) {
        _uiState.update { it.copy(message = message) }
    }

    private fun whyNoScan(state: PreparationUiState): String = when {
        state.inFlight -> "Wait — Station 2 is still answering the last bag"
        state.blocking.isNotEmpty() -> "Resolve the unanswered command first — don't scan again"
        state.stage == null -> "This preparation hasn't loaded yet"
        else -> "This preparation is not collecting (${state.snapshot?.preparation?.stageRaw})"
    }

    companion object {
        const val PREP_ID_ARG = "prepId"
        const val BADGE_MESSAGE = "That is a user badge, not a pallet"
        private const val PICK_MATERIAL_MESSAGE = "Pick the material this bag is for"

        /** "Recorded 10 kg Resin — 30 kg to go", from the row whose collected grew. */
        internal fun doneMessage(before: PrepSnapshot?, after: PrepSnapshot, part: Staged.PartBag?): OutcomeMessage {
            val old = before?.preparation?.materials.orEmpty().associateBy { it.code }
            val row = after.preparation?.materials?.firstOrNull {
                it.collected > (old[it.code]?.collected ?: BigDecimal.ZERO)
            } ?: return OutcomeMessage.Info(after.resultMessage)
            val amount = row.collected - (old[row.code]?.collected ?: BigDecimal.ZERO)
            val text = "Recorded ${amount.plain()} ${row.unit} ${row.name} — ${row.remaining.plain()} ${row.unit} to go"
            val warning = part?.takeIf { it.materialCode != row.code }?.let { p ->
                "Station 2 recorded this against ${row.name}, not ${old[p.materialCode]?.name ?: p.materialCode}"
            }
            return OutcomeMessage.Recorded(text, warning)
        }

        private fun BigDecimal.plain(): String =
            if (signum() == 0) "0" else stripTrailingZeros().toPlainString()
    }
}
