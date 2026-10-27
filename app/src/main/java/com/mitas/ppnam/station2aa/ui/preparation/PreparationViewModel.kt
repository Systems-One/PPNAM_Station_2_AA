package com.mitas.ppnam.station2aa.ui.preparation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mitas.ppnam.station2aa.UserTagPolicy
import com.mitas.ppnam.station2aa.data.mqtt.ErrorCode
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingCommand
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingStatus
import com.mitas.ppnam.station2aa.data.mqtt.outbox.UnresolvedReason
import com.mitas.ppnam.station2aa.data.rfid.ScanEvent
import com.mitas.ppnam.station2aa.data.rfid.ScanEventBus
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.domain.model.MachineScan
import com.mitas.ppnam.station2aa.domain.model.PrepSnapshot
import com.mitas.ppnam.station2aa.domain.model.savedMixer
import com.mitas.ppnam.station2aa.domain.model.savedProduction
import com.mitas.ppnam.station2aa.domain.model.matchMachine
import com.mitas.ppnam.station2aa.domain.model.scanMachine
import com.mitas.ppnam.station2aa.domain.model.PrepStage
import com.mitas.ppnam.station2aa.domain.model.AmountCheck
import com.mitas.ppnam.station2aa.domain.model.PrepMaterial
import com.mitas.ppnam.station2aa.domain.model.SourceFit
import com.mitas.ppnam.station2aa.domain.model.SourceInfo
import com.mitas.ppnam.station2aa.domain.model.bagGuide
import com.mitas.ppnam.station2aa.domain.model.classify
import com.mitas.ppnam.station2aa.domain.model.splitCaptures
import com.mitas.ppnam.station2aa.domain.model.validateAmount
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.usecase.AuthUseCase
import com.mitas.ppnam.station2aa.domain.usecase.CollectionResult
import com.mitas.ppnam.station2aa.domain.usecase.CommandRecoveryUseCase
import com.mitas.ppnam.station2aa.domain.usecase.GeneralCollectionUseCase
import com.mitas.ppnam.station2aa.domain.usecase.IdentifyResult
import com.mitas.ppnam.station2aa.domain.usecase.PendingCommandCoordinator
import com.mitas.ppnam.station2aa.domain.usecase.ReadResult
import com.mitas.ppnam.station2aa.domain.usecase.RecoveryOutcome
import com.mitas.ppnam.station2aa.domain.usecase.RecoveryResult
import com.mitas.ppnam.station2aa.domain.usecase.ReplacementOffer
import com.mitas.ppnam.station2aa.domain.usecase.toPrepSnapshot
import com.mitas.ppnam.station2aa.ui.components.ConnectionStatus
import com.mitas.ppnam.station2aa.ui.components.connectionStatusIn
import com.mitas.ppnam.station2aa.ui.home.noticeText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject

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

    /** The `identify` request in flight, so Cancel (or a newer scan) drops a late reply. */
    private var identifyJob: Job? = null

    /** Message ids already turned into an offer (or found to need none), so each is offered once. */
    private val offered = mutableSetOf<String>()
    /** Offers waiting behind the one on screen. */
    private val queuedOffers = ArrayDeque<ReplacementOffer>()

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
        // Spec §6: a NotExecuted capture/confirm for this PREP becomes an offer, after a fresh read.
        viewModelScope.launch {
            coordinator.notices.collect { notices ->
                notices.forEach { notice ->
                    if (notice !is RecoveryResult.Resolved || notice.outcome != RecoveryOutcome.NotExecuted) return@forEach
                    if (!isThisPrep(notice.command.targetId)) return@forEach
                    offerAfterRead(notice.command.messageId, ReplacementOffer.from(notice))
                }
            }
        }
        // Rule 9: scans reach this screen only while it is visible; onScan refuses to replace a staged pallet.
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
        // A stage change while a machine card is open cancels it (the action may no longer apply).
        viewModelScope.launch {
            _uiState.map { it.stage }.distinctUntilChanged().collect {
                _uiState.update { s ->
                    if (s.machine is MachineStep.Confirm) {
                        s.copy(machine = MachineStep.Idle, message = OutcomeMessage.Info("The preparation moved on — scan the machine again"))
                    } else s
                }
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

    /**
     * A pallet code from the scanner or the type-the-code field: asks Station 2 what it is. Never
     * sends a capture. Refused while blocked, and refused (not replaced) while a pallet is staged.
     */
    fun onScan(code: String) {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return
        if (UserTagPolicy.isUserTag(trimmed)) {
            show(OutcomeMessage.Info(BADGE_MESSAGE))
            return
        }
        val state = _uiState.value
        if (state.stage?.isMachineStage == true) {
            machineScan(trimmed, state)
            return
        }
        // A machine code before the mixer stage is the wrong step, not an unknown pallet.
        state.snapshot?.matchMachine(trimmed)?.let { machine ->
            show(OutcomeMessage.Info("That's the ${machine.name} code — collect every ingredient and tap Mix complete first"))
            return
        }
        if (!state.canScan) {
            show(OutcomeMessage.Info(whyNoScan(state)))
            return
        }
        // Rule: while the Collect screen holds a pallet, a new scan must not silently replace it.
        if (state.step.protectsPallet) {
            show(OutcomeMessage.Info(FINISH_FIRST_MESSAGE))
            return
        }
        identify(trimmed)
    }

    // ---- mixer / production stage ----

    /** A machine code in a mixer/production stage: classify it against the snapshot; never sends. */
    private fun machineScan(code: String, state: PreparationUiState) {
        if (!state.canScanMachine) {
            show(OutcomeMessage.Info(whyNoMachineScan(state)))
            return
        }
        if (state.machine != MachineStep.Idle) {
            show(OutcomeMessage.Info(MACHINE_FINISH_FIRST_MESSAGE))
            return
        }
        val snapshot = state.snapshot ?: return
        _uiState.update { it.copy(banner = null, message = null) }
        when (val scan = snapshot.scanMachine(code)) {
            is MachineScan.NotAMachine -> setBanner(ResultBanner.Error("Not a machine — ${scan.code}"))
            is MachineScan.NotActive -> setBanner(
                ResultBanner.Error("Only ${scan.activeName} can finish this ${if (state.stage == PrepStage.Producing) "production" else "mix"}"),
            )
            is MachineScan.NotNow -> show(OutcomeMessage.Info(scan.message))
            else -> _uiState.update { it.copy(machine = MachineStep.Confirm(code, scan)) }
        }
    }

    private fun setBanner(banner: ResultBanner) {
        _uiState.update { it.copy(banner = banner) }
    }

    fun cancelMachine() {
        _uiState.update { if (it.machine is MachineStep.Confirm) it.copy(machine = MachineStep.Idle) else it }
    }

    /**
     * The Start / Finish tap. Everything is re-derived from the CURRENT snapshot (never the scan
     * result): if the stage moved so the same action no longer applies, the card is cancelled and
     * nothing is sent. The cycle id of a finish is the current preparation's.
     */
    fun confirmMachine() {
        val state = _uiState.value
        val confirm = state.machine as? MachineStep.Confirm ?: return
        if (state.inFlight) return
        if (!state.canScanMachine) return show(OutcomeMessage.Info(whyNoMachineScan(state)))
        val snapshot = state.snapshot ?: return
        val fresh = snapshot.scanMachine(confirm.code)
        val send: suspend () -> CollectionResult = when {
            fresh is MachineScan.StartMix && confirm.scan is MachineScan.StartMix && fresh.machine.id == confirm.scan.machine.id ->
                { { useCase.machineStart(prepId, confirm.code) } }
            fresh is MachineScan.StartProduction && confirm.scan is MachineScan.StartProduction && fresh.machine.id == confirm.scan.machine.id ->
                { { useCase.machineStart(prepId, confirm.code) } }
            fresh is MachineScan.Finish && confirm.scan is MachineScan.Finish && fresh.machine.id == confirm.scan.machine.id ->
                { { useCase.machineFinish(prepId, confirm.code, fresh.cycleId) } }
            fresh is MachineScan.Disabled -> return   // nothing to send
            else -> {
                _uiState.update { it.copy(machine = MachineStep.Idle) }
                return show(OutcomeMessage.Info("The preparation changed — scan the machine again"))
            }
        }
        _uiState.update { it.copy(machine = MachineStep.Sending) }
        mutate(rejectedTitle = "Not done") {
            val result = try {
                send()
            } catch (e: Exception) {
                _uiState.update { it.copy(machine = MachineStep.Idle) }
                throw e
            }
            // Nothing went out: the card stays so the operator can tap again.
            _uiState.update { it.copy(machine = if (result is CollectionResult.NotSent) confirm else MachineStep.Idle) }
            result
        }
    }

    private fun whyNoMachineScan(state: PreparationUiState): String = when {
        state.inFlight -> "Wait — Station 2 is still answering"
        state.blocking.isNotEmpty() -> "Resolve the unanswered command first — don't scan again"
        else -> "This preparation hasn't loaded yet"
    }

    /** Cancel / Done on the Collect screen: drop the step. Not while captures are being sent. */
    fun cancelCollect() {
        if (_uiState.value.inFlight) return
        identifyJob?.cancel()
        identifyJob = null
        _uiState.update { it.copy(step = CollectStep.Idle) }
    }

    fun onAmountChange(text: String) {
        _uiState.update { s ->
            val materials = s.snapshot?.preparation?.materials.orEmpty()
            val step = when (val current = s.step) {
                is CollectStep.Identified -> identified(current.source, current.fit.copy(material = freshRow(materials, current.fit.material)), text)
                is CollectStep.NotInMix -> current.copy(amountText = text)
                is CollectStep.Fallback -> {
                    val row = materials.firstOrNull { it.code == current.pickedMaterial }
                    if (row == null) current else current.copy(amountText = text, check = fallbackCheck(text, row))
                }
                else -> current
            }
            s.copy(step = step)
        }
    }

    fun onReasonChange(text: String) {
        _uiState.update { s ->
            val step = s.step as? CollectStep.NotInMix ?: return@update s
            s.copy(step = step.copy(reason = text.take(GeneralCollectionUseCase.ADJUST_REASON_MAX)))
        }
    }

    /** Fallback: the operator taps the ingredient this pallet is for; the amount is prefilled to its remaining. */
    fun pickFallbackMaterial(materialCode: String) {
        _uiState.update { s ->
            val step = s.step as? CollectStep.Fallback ?: return@update s
            val row = s.snapshot?.preparation?.materials.orEmpty()
                .firstOrNull { it.code == materialCode && !it.excluded && !it.isComplete } ?: return@update s
            val text = row.remaining.plainText()
            s.copy(step = step.copy(pickedMaterial = row.code, amountText = text, check = fallbackCheck(text, row)))
        }
    }

    /**
     * Submit on the Collect screen: one capture per bag, then the exact rest (or one capture of the
     * whole amount without a bag size), sent one after the other, stopping at the first that is not
     * Done. One deliberate tap sends one such sequence.
     */
    fun submit() {
        val state = _uiState.value
        if (state.inFlight) return
        val materials = state.snapshot?.preparation?.materials.orEmpty()
        val plan: SubmitPlan = when (val step = state.step) {
            is CollectStep.Identified -> {
                if (!state.canScan) return show(OutcomeMessage.Info(whyNoScan(state)))
                // Re-check against the freshest row: a read may have landed since the amount was typed.
                val row = materials.firstOrNull { it.code == step.fit.material.code }
                if (row == null || row.excluded) {
                    _uiState.update { it.copy(step = CollectStep.Idle) }
                    return show(OutcomeMessage.Info("${step.fit.material.name} is no longer in this mix — scan again"))
                }
                val current = identified(step.source, step.fit.copy(material = row), step.amountText)
                val valid = current.check as? AmountCheck.Valid
                if (valid == null) {
                    _uiState.update { it.copy(step = current) }
                    return
                }
                SubmitPlan(step.source.code, step.source.name, splitCaptures(valid.amount, step.source.bagSize), null)
            }
            is CollectStep.Fallback -> {
                if (!state.canScan) return show(OutcomeMessage.Info(whyNoScan(state)))
                val row = materials.firstOrNull { it.code == step.pickedMaterial } ?: return
                val valid = fallbackCheck(step.amountText, row) as? AmountCheck.Valid ?: return
                SubmitPlan(step.code, row.name, splitCaptures(valid.amount, null), row.code)
            }
            else -> return
        }
        if (plan.parts.isEmpty()) return
        runCaptures(plan, state.step, state.snapshot)
    }

    /**
     * Add the scanned (not-in-mix) pallet's material to this PREP with the operator's reason. Sends
     * the PREP's current collection revision; never captures. On Done the pallet comes back as
     * Identified so the operator taps Submit.
     */
    fun addToMix() {
        val state = _uiState.value
        if (state.inFlight) return
        val step = state.step as? CollectStep.NotInMix ?: return
        if (!state.canScan) return show(OutcomeMessage.Info(whyNoScan(state)))
        val valid = step.check as? AmountCheck.Valid ?: return
        if (!step.canAdd) return
        val revision = state.snapshot?.preparation?.collectionRevision ?: return
        mutate(endsStep = true, onDone = { seq, done -> addDone(seq, done, step.source) }) {
            useCase.addIngredient(prepId, revision, step.source.code, valid.amount, step.reason.trim())
        }
    }

    private suspend fun addDone(seq: Long, done: CollectionResult.Done, source: SourceInfo) {
        val fresh = takeIfNewer(seq, done.snapshot)
        if (fresh != null) _uiState.update { it.copy(snapshot = fresh) }
        val fit = _uiState.value.snapshot?.classify(source)
        if (fit is SourceFit.InMix) {
            _uiState.update { it.copy(step = identified(source, fit.material, prefill(source, fit.material))) }
        } else {
            _uiState.update { it.copy(step = CollectStep.Idle) }
            show(OutcomeMessage.Info("Added — scan the pallet again to collect it"))
            read()
        }
    }

    // ---- Less / More / Omit on a row (contract §6) ----

    /** Opens the decision for the tapped row; only while the PREP still takes decisions. */
    fun openDecision(materialCode: String) {
        val state = _uiState.value
        if (!state.canDecide) return show(OutcomeMessage.Info(whyNoDecision(state)))
        val rows = state.snapshot?.preparation?.materials.orEmpty()
        val row = rows.firstOrNull { it.code == materialCode } ?: return
        val decision = IngredientDecision.on(row, rows)
            ?: return show(OutcomeMessage.Info("${row.name} is excluded — it can't be changed"))
        _uiState.update { it.copy(decision = decision) }
    }

    fun onDecisionKind(kind: IngredientDecisionKind) = updateDecision { it.copy(kind = kind) }
    fun onDecisionTotal(text: String) = updateDecision { it.copy(totalText = text) }
    fun onDecisionReason(text: String) = updateDecision { it.copy(reason = text) }
    fun cancelDecision() = _uiState.update { it.copy(decision = null) }

    /** Sends the decision against the CURRENT row and PREP revision; nothing is captured. */
    fun sendDecision() {
        val state = _uiState.value
        val draft = state.decision ?: return
        if (state.inFlight || state.blocking.isNotEmpty()) return show(OutcomeMessage.Info(whyNoDecision(state)))
        val prep = state.snapshot?.preparation ?: return
        // Re-check against the row as it stands now, not as it was when the dialog opened.
        val decision = prep.materials.firstOrNull { it.code == draft.material.code }
            ?.let { row -> IngredientDecision.on(row, prep.materials) }
            ?.copy(kind = draft.kind, totalText = draft.totalText, reason = draft.reason)
            ?: run { cancelDecision(); return show(OutcomeMessage.Info("${draft.material.name} changed — check the preparation and try again")) }
        if (!decision.canSend) {
            _uiState.update { it.copy(decision = decision) }
            return show(OutcomeMessage.Info(decision.problems.first()))
        }
        _uiState.update { it.copy(decision = null) }
        val code = decision.material.code
        val reason = decision.reason.trim()
        mutate {
            when (decision.kind) {
                IngredientDecisionKind.Quantity ->
                    useCase.changeQuantity(prepId, prep.collectionRevision, code, decision.newTotal!!, reason)
                IngredientDecisionKind.Omit -> useCase.omit(prepId, prep.collectionRevision, code, reason)
            }
        }
    }

    private fun updateDecision(f: (IngredientDecision) -> IngredientDecision) =
        _uiState.update { s -> s.decision?.let { s.copy(decision = f(it)) } ?: s }

    private fun whyNoDecision(state: PreparationUiState): String = when {
        state.inFlight -> "Wait — Station 2 is still answering"
        state.blocking.isNotEmpty() -> "Resolve the unanswered command first"
        state.stage == null -> "This preparation hasn't loaded yet"
        state.step != CollectStep.Idle -> "Finish or cancel the current pallet first"
        else -> "Ingredients can't be changed now (${state.snapshot?.preparation?.stageRaw})"
    }

    private fun identify(code: String) {
        identifyJob?.cancel()
        val seq = ++issued
        _uiState.update { it.copy(step = CollectStep.Identifying(code), banner = null, message = null) }
        identifyJob = viewModelScope.launch {
            val result = try {
                useCase.identify(prepId, code)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                IdentifyResult.Failed("Could not identify this pallet: ${e.message}", retryable = false)
            }
            // Cancelled, or another step took over while Station 2 was answering.
            if ((_uiState.value.step as? CollectStep.Identifying)?.code != code) return@launch
            when (result) {
                is IdentifyResult.Identified -> {
                    applyPreparationIfNewer(seq, result.snapshot)
                    val snapshot = _uiState.value.snapshot
                    _uiState.update {
                        it.copy(step = if (snapshot == null) CollectStep.Unknown(IDENTIFY_NO_PREP) else stepFor(result.source, snapshot))
                    }
                }
                is IdentifyResult.Unknown -> {
                    applyPreparationIfNewer(seq, result.snapshot)
                    _uiState.update { it.copy(step = CollectStep.Unknown(result.message)) }
                }
                is IdentifyResult.Unsupported ->
                    _uiState.update { it.copy(step = CollectStep.Fallback(code, null, "", timedOut = result.timedOut)) }
                is IdentifyResult.Failed -> {
                    _uiState.update { it.copy(step = CollectStep.Idle) }
                    show(OutcomeMessage.Info(result.message))
                }
            }
        }
    }

    private fun applyIfNewer(seq: Long, snapshot: PrepSnapshot?) {
        val fresh = takeIfNewer(seq, snapshot) ?: return
        _uiState.update { it.copy(snapshot = fresh) }
    }

    /**
     * For `identify`'s compact reply (contract §4.1): only its `preparation` is fresh. The job's
     * mix progress, machines, preparations and exception lists stay as the last full snapshot had them.
     */
    private fun applyPreparationIfNewer(seq: Long, snapshot: PrepSnapshot?) {
        val fresh = takeIfNewer(seq, snapshot) ?: return
        _uiState.update { it.copy(snapshot = it.snapshot?.copy(preparation = fresh.preparation) ?: fresh) }
    }

    /** What the identified pallet means for this PREP. */
    private fun stepFor(source: SourceInfo, snapshot: PrepSnapshot): CollectStep {
        if (!source.usable) return CollectStep.Unusable(source, source.reason.ifBlank { "Station 2 can't use this pallet" })
        return when (val fit = snapshot.classify(source)) {
            is SourceFit.InMix -> identified(source, fit.material, prefill(source, fit.material))
            is SourceFit.Complete -> CollectStep.Unusable(source, "${fit.material.name} is already fully collected")
            is SourceFit.Excluded -> CollectStep.Excluded(source, fit.material.name)
            SourceFit.NotInMix -> CollectStep.NotInMix(source, "", "")
        }
    }

    /** `min(remaining, available on the pallet)`; the row's remaining when the stock is unknown. */
    private fun prefill(source: SourceInfo, row: PrepMaterial): String {
        val remaining = row.remaining
        val available = source.available
        return (if (available != null && available < remaining) available else remaining).plainText()
    }

    private fun identified(source: SourceInfo, material: PrepMaterial, text: String): CollectStep.Identified =
        identified(source, SourceFit.InMix(material), text)

    private fun identified(source: SourceInfo, fit: SourceFit.InMix, text: String): CollectStep.Identified {
        val unit = source.unit.ifBlank { fit.material.unit }.ifBlank { "kg" }
        val check = validateAmount(text, fit.material.remaining, source.available, unit)
        val guide = (check as? AmountCheck.Valid)?.let { bagGuide(it.amount, source.bagSize) }
        return CollectStep.Identified(source, fit, text, guide, check)
    }

    private fun freshRow(materials: List<PrepMaterial>, row: PrepMaterial): PrepMaterial =
        materials.firstOrNull { it.code == row.code } ?: row

    private fun fallbackCheck(text: String, row: PrepMaterial): AmountCheck =
        validateAmount(text, row.remaining, null, row.unit.ifBlank { "kg" })

    private class SubmitPlan(val code: String, val name: String, val parts: List<BigDecimal>, val pickedCode: String?)

    private fun runCaptures(plan: SubmitPlan, previous: CollectStep, before: PrepSnapshot?) {
        val total = plan.parts.size
        mutationsInFlight++
        _uiState.update {
            it.copy(
                inFlight = true, banner = null, message = null,
                step = CollectStep.Submitting(plan.code, plan.name, 0, total),
            )
        }
        viewModelScope.launch {
            try {
                var recorded = 0
                var recordedKg = BigDecimal.ZERO
                var lastDone: PrepSnapshot? = null
                var needRead = false
                for (quantity in plan.parts) {
                    // Another command for this PREP became unresolved meanwhile: send nothing more.
                    if (recorded > 0 && _uiState.value.blocking.isNotEmpty()) {
                        _uiState.update { it.copy(step = CollectStep.Idle) }
                        show(OutcomeMessage.Info(RecordedSoFar(recorded, total, recordedKg).prefix() + "resolve the unanswered command first"))
                        return@launch
                    }
                    val seq = ++issued
                    val result = useCase.capture(prepId, plan.code, quantity)
                    if (result is CollectionResult.Done || result is CollectionResult.AlreadySettled) {
                        recorded++
                        recordedKg += quantity
                        if (result is CollectionResult.Done) {
                            lastDone = result.snapshot
                            applyIfNewer(seq, result.snapshot)
                        } else {
                            needRead = true
                        }
                        _uiState.update { it.copy(step = CollectStep.Submitting(plan.code, plan.name, recorded, total)) }
                        continue
                    }
                    // Not Done: stop here. Earlier captures stay recorded; never auto-continue.
                    val sofar = if (recorded > 0) RecordedSoFar(recorded, total, recordedKg) else null
                    if (result is CollectionResult.NotSent && sofar == null) {
                        _uiState.update { it.copy(step = previous) }   // nothing went out: Submit can be tapped again
                    } else {
                        _uiState.update { it.copy(step = CollectStep.Idle) }
                    }
                    handleNotDone(seq, result, retried = null, sofar = sofar)
                    return@launch
                }
                // A hint/reconnect read may have taken a higher sequence number while a capture was
                // in flight and made takeIfNewer drop that capture's snapshot: the last Done reply
                // is the freshest word on the PREP, so make sure it is what is on screen.
                if (lastDone != null && _uiState.value.snapshot !== lastDone && isThisPrep(lastDone.preparation?.id)) {
                    applied = ++issued
                    _uiState.update { it.copy(snapshot = lastDone) }
                    needRead = true   // the racing read may still land after this; the next read settles it
                }
                _uiState.update { it.copy(step = CollectStep.Idle) }
                val banner = lastDone?.let { doneBanner(before, it, plan.pickedCode) }
                if (banner != null) _uiState.update { it.copy(banner = banner) }
                else show(OutcomeMessage.Info(lastDone?.resultMessage ?: "Station 2 had already recorded this"))
                if (needRead) read()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(step = CollectStep.Idle) }
                show(OutcomeMessage.Info("Something went wrong sending this: ${e.message}. Check the preparation before trying again."))
                read()
            } finally {
                mutationsInFlight--
                _uiState.update { it.copy(inFlight = mutationsInFlight > 0) }
            }
        }
    }

    private class RecordedSoFar(val count: Int, val total: Int, val kg: BigDecimal)


    /** Rule 8: confirm collection, only when Station 2 is waiting for it. */
    fun confirm() {
        if (!_uiState.value.canConfirm) return
        mutate { useCase.confirm(prepId) }
    }

    /** Republishes [command]'s identical saved bytes — never a new request for the same intent. */
    fun retry(command: PendingCommand) {
        if (!retrying.add(command.messageId)) return
        mutate(retried = command, onFinally = { retrying.remove(command.messageId) }) {
            useCase.retry(command)
        }
    }

    /**
     * Review Focus 6: the operator tapped [Record bag] / [Confirm collection] on the offer. Sends a
     * NEW capture/confirm (a new message id) — never a retry of the sealed one — through the same
     * guards as a deliberate tap. Blocked: nothing is sent and the offer stays.
     *
     * The offer (the only record of the lost bag's code and quantity) and its notice stay until the
     * send settles: [CollectionResult.NotSent] keeps both, so the operator can tap again. `inFlight`
     * is set before this returns, so a second tap meanwhile is refused.
     */
    fun acceptOffer() {
        val state = _uiState.value
        val offer = state.offer ?: return
        if (!isThisPrep(offer.prepId)) return
        when (offer) {
            is ReplacementOffer.Capture ->
                if (state.inFlight || !state.canScan) return show(OutcomeMessage.Info(whyNoScan(state)))
            is ReplacementOffer.Confirm ->
                if (state.inFlight || !state.canConfirm) return show(OutcomeMessage.Info(whyNoConfirm(state)))
        }
        when (offer) {
            is ReplacementOffer.Capture -> mutate(replacing = offer) {
                useCase.capture(prepId, offer.intent.code, offer.intent.quantity)
            }
            is ReplacementOffer.Confirm -> mutate(replacing = offer) {
                useCase.confirm(prepId)
            }
        }
    }

    /** [Don't record]: dismiss the notice; nothing is sent. Not while the offer's own send is in flight. */
    fun declineOffer() {
        val state = _uiState.value
        val offer = state.offer ?: return
        if (state.inFlight) return
        closeOffer(offer)
    }

    private fun closeOffer(offer: ReplacementOffer) {
        if (_uiState.value.offer != offer) return
        offer.notice?.let(coordinator::dismiss)
        _uiState.update { it.copy(offer = queuedOffers.removeFirstOrNull()) }
    }

    /** Reads the PREP, then (once the read has finished) shows [offer] — at most once per [messageId]. */
    private fun offerAfterRead(messageId: String, offer: ReplacementOffer?) {
        if (!offered.add(messageId)) return
        if (offer == null || !isThisPrep(offer.prepId)) return
        read {
            if (_uiState.value.offer == null) _uiState.update { it.copy(offer = offer) } else queuedOffers.addLast(offer)
        }
    }

    fun logout() {
        viewModelScope.launch { authUseCase.logout() }
    }

    /** PREP ids are compared ignoring case: the same PREP may be opened as `PREP_440fbd…` or `PREP_440FBD…`. */
    private fun isThisPrep(id: String?): Boolean = id != null && id.equals(prepId, ignoreCase = true)

    private fun blocksThisPrep(c: PendingCommand): Boolean =
        isThisPrep(c.targetId) && (c.status == PendingStatus.Unresolved || c.status == PendingStatus.ManagerReconcile)

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

    /** [then] runs once the read has finished (applied, failed, or stale) — never on cancellation. */
    private fun read(then: (() -> Unit)? = null) {
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                show(OutcomeMessage.Info("Could not read this preparation: ${e.message}"))
            } finally {
                readsInFlight--
                _uiState.update { it.copy(loading = readsInFlight > 0) }
            }
            then?.invoke()
        }
    }

    /**
     * Runs one mutation. [retried] is the command being republished, if any (a `receipt_sealed`
     * reply to it becomes a replacement offer); [replacing] is the offer this send answers, closed
     * once the send settles unless nothing was sent. [endsStep] marks a send that consumes the
     * Collect step (add to mix): any answer but NotSent returns the screen to Idle. [onDone]
     * replaces the default handling of a Done reply.
     */
    private fun mutate(
        retried: PendingCommand? = null,
        replacing: ReplacementOffer? = null,
        endsStep: Boolean = false,
        onFinally: () -> Unit = {},
        onDone: (suspend (Long, CollectionResult.Done) -> Unit)? = null,
        rejectedTitle: String? = null,
        call: suspend () -> CollectionResult,
    ) {
        val seq = ++issued
        val before = _uiState.value.snapshot
        mutationsInFlight++
        _uiState.update { it.copy(inFlight = true) }
        viewModelScope.launch {
            try {
                val result = call()
                if (result is CollectionResult.Done && onDone != null) {
                    onDone(seq, result)
                } else {
                    handle(seq, result, before, retried, rejectedTitle)
                    if (endsStep && result !is CollectionResult.NotSent && result !is CollectionResult.Done) {
                        _uiState.update { it.copy(step = CollectStep.Idle) }
                        // A rejection that carried state (revision conflict): read again before the next scan.
                        if (result is CollectionResult.Rejected && result.snapshot != null) read()
                    }
                }
                // Only NotSent proves nothing went out; any other answer settles the offer.
                if (replacing != null && result !is CollectionResult.NotSent) closeOffer(replacing)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Unknown whether it went out: close the offer rather than invite a second capture.
                replacing?.let(::closeOffer)
                if (endsStep) _uiState.update { it.copy(step = CollectStep.Idle) }
                show(OutcomeMessage.Info("Something went wrong sending this: ${e.message}. Check the preparation before trying again."))
                read()
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
        retried: PendingCommand?,
        rejectedTitle: String? = null,
    ) {
        if (result is CollectionResult.Done) {
            val fresh = takeIfNewer(seq, result.snapshot)
            val banner = doneBanner(before, result.snapshot, null) ?: machineDoneBanner(before, result.snapshot)
            _uiState.update { s ->
                s.copy(
                    snapshot = fresh ?: s.snapshot,
                    banner = banner ?: s.banner,
                    message = if (banner == null) OutcomeMessage.Info(result.snapshot.resultMessage) else s.message,
                )
            }
        } else {
            handleNotDone(seq, result, retried, sofar = null, rejectedTitle = rejectedTitle)
        }
    }

    /** Everything but Done. [sofar] is what an earlier capture of the same Submit already recorded. */
    private suspend fun handleNotDone(
        seq: Long, result: CollectionResult, retried: PendingCommand?, sofar: RecordedSoFar?, rejectedTitle: String? = null,
    ) {
        when (result) {
            is CollectionResult.Done -> Unit
            is CollectionResult.Rejected -> {
                val fresh = takeIfNewer(seq, result.snapshot)
                val logged = loggedLineOf(result.snapshot)
                val banner = if (sofar == null) {
                    ResultBanner.Rejected(result.message, logged, rejectedTitle ?: "Not recorded")
                } else {
                    ResultBanner.Partial(sofar.count, sofar.total, sofar.kg.plainText(), result.message, logged)
                }
                _uiState.update { s -> s.copy(snapshot = fresh ?: s.snapshot, banner = banner, message = null) }
                // Spec §6/§7: a retry answered receipt_sealed never ran and never will. Re-read, then offer.
                if (result.error == ErrorCode.RECEIPT_SEALED && retried != null) {
                    offerAfterRead(retried.messageId, ReplacementOffer.fromSealed(retried))
                } else if (result.snapshot == null) {
                    // Spec §7: a rejection with no state attached still means "read again".
                    read()
                }
            }
            // No local change: the outbox now holds the command, and blocking follows from it.
            is CollectionResult.Unresolved -> {
                if (result.reason == UnresolvedReason.Recover) recoverNow(result.command, sofar.prefix())
                else show(OutcomeMessage.Info(sofar.prefix() + result.message))
            }
            is CollectionResult.NotSent -> show(OutcomeMessage.Info(sofar.prefix() + result.message))
            is CollectionResult.AlreadySettled -> read()
        }
    }

    private fun RecordedSoFar?.prefix(): String =
        if (this == null) "" else "Recorded $count of $total (${kg.plainText()} kg) — then: "

    private fun loggedLineOf(snapshot: PrepSnapshot?): String? =
        snapshot?.commandExceptions.orEmpty().maxByOrNull { it.timestampUtc }?.loggedLine()

    /** message_id_conflict: ask Station 2 at once what became of the command; NotExecuted becomes an offer. */
    private suspend fun recoverNow(command: PendingCommand, prefix: String = "") {
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
            s.copy(snapshot = fresh ?: s.snapshot, message = OutcomeMessage.Info(prefix + result.noticeText(mqttRepository.deviceId)))
        }
        if (result is RecoveryResult.Resolved && result.outcome == RecoveryOutcome.NotExecuted) {
            offerAfterRead(command.messageId, ReplacementOffer.from(result))
        }
    }

    /**
     * Rule 1: claims [seq] as the newest applied snapshot, or returns null for a stale one — or one
     * that is not this PREP's (spec §4.1: `snapshot.preparation.id == prepId`, ignoring case).
     */
    private fun takeIfNewer(seq: Long, snapshot: PrepSnapshot?): PrepSnapshot? {
        if (snapshot == null || seq <= applied || !isThisPrep(snapshot.preparation?.id)) return null
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

    private fun whyNoConfirm(state: PreparationUiState): String = when {
        state.inFlight -> "Wait — Station 2 is still answering"
        state.blocking.isNotEmpty() -> "Resolve the unanswered command first"
        state.stage == null -> "This preparation hasn't loaded yet"
        else -> "Station 2 is not waiting for confirmation (${state.snapshot?.preparation?.stageRaw})"
    }

    companion object {
        const val PREP_ID_ARG = "prepId"
        const val BADGE_MESSAGE = "That is a user badge, not a pallet"
        const val FINISH_FIRST_MESSAGE = "Finish or cancel this pallet first"
        const val MACHINE_FINISH_FIRST_MESSAGE = "Finish or cancel first"
        private const val IDENTIFY_NO_PREP = "This preparation hasn't loaded yet"

        /**
         * "Recorded 10 kg Resin", from the row whose collected grew; null when nothing can be told
         * from the diff (no earlier read, or no row grew). [pickedCode] is the row the operator picked
         * in the fallback, to warn when Station 2 booked the capture elsewhere.
         */
        /**
         * What a machine command did, read from the stage and counts: "Mix 1 started on Mixer 2",
         * "Mix 1 finished", "Production 2 finished", "Preparation complete". Null when nothing moved.
         */
        internal fun machineDoneBanner(before: PrepSnapshot?, after: PrepSnapshot): ResultBanner.Accepted? {
            val b = before?.preparation ?: return null
            val a = after.preparation ?: return null
            val text = when {
                a.stage == PrepStage.Completed && b.stage != PrepStage.Completed -> "Preparation complete"
                a.mixed > b.mixed -> "Mix ${a.mixed} finished"
                a.produced > b.produced -> "Production ${a.produced} finished"
                a.stage == PrepStage.Mixing && b.stage != PrepStage.Mixing ->
                    "Mix ${a.mixed + 1} started" + (after.savedMixer()?.let { " on ${it.name}" } ?: "")
                a.stage == PrepStage.Producing && b.stage != PrepStage.Producing ->
                    "Production ${a.produced + 1} started" + (after.savedProduction()?.let { " on ${it.name}" } ?: "")
                else -> return null
            }
            return ResultBanner.Accepted(text)
        }

        internal fun doneBanner(before: PrepSnapshot?, after: PrepSnapshot, pickedCode: String?): ResultBanner.Accepted? {
            // Nothing read yet (an open-time retry): a diff would report the row's whole total.
            val beforePrep = before?.preparation ?: return null
            val old = beforePrep.materials.associateBy { it.code }
            val row = after.preparation?.materials?.firstOrNull {
                it.collected > (old[it.code]?.collected ?: BigDecimal.ZERO)
            } ?: return null
            val amount = row.collected - (old[row.code]?.collected ?: BigDecimal.ZERO)
            val text = "Recorded ${amount.plainText()} ${row.unit} ${row.name}"
            val completed = row.code.takeIf { row.isComplete && old[row.code]?.isComplete != true }
            val warning = pickedCode?.takeIf { it != row.code }?.let { picked ->
                "Station 2 recorded this against ${row.name}, not ${old[picked]?.name ?: picked}"
            }
            return ResultBanner.Accepted(text, completed, warning)
        }
    }
}
