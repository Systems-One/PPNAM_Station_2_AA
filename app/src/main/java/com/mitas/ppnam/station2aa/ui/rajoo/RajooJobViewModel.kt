package com.mitas.ppnam.station2aa.ui.rajoo

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mitas.ppnam.station2aa.UserTagPolicy
import com.mitas.ppnam.station2aa.data.mqtt.dto.Rev2ChoiceAnswer
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingCommand
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingStatus
import com.mitas.ppnam.station2aa.data.mqtt.outbox.UnresolvedReason
import com.mitas.ppnam.station2aa.data.rfid.ScanEvent
import com.mitas.ppnam.station2aa.data.rfid.ScanEventBus
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.domain.model.AmountCheck
import com.mitas.ppnam.station2aa.domain.model.ChoicePrompt
import com.mitas.ppnam.station2aa.domain.model.Machine
import com.mitas.ppnam.station2aa.domain.model.PrepMaterial
import com.mitas.ppnam.station2aa.domain.model.PrepSnapshot
import com.mitas.ppnam.station2aa.domain.model.RajooJob
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
import com.mitas.ppnam.station2aa.domain.usecase.IdentifyResult
import com.mitas.ppnam.station2aa.domain.usecase.PendingCommandCoordinator
import com.mitas.ppnam.station2aa.domain.usecase.RajooUseCase
import com.mitas.ppnam.station2aa.domain.usecase.ReadResult
import com.mitas.ppnam.station2aa.domain.usecase.RecoveryResult
import com.mitas.ppnam.station2aa.domain.usecase.toPrepSnapshot
import com.mitas.ppnam.station2aa.ui.components.ConnectionStatus
import com.mitas.ppnam.station2aa.ui.components.connectionStatusIn
import com.mitas.ppnam.station2aa.ui.home.noticeText
import com.mitas.ppnam.station2aa.ui.preparation.CollectStep
import com.mitas.ppnam.station2aa.ui.preparation.OutcomeMessage
import com.mitas.ppnam.station2aa.ui.preparation.PreparationUiState
import com.mitas.ppnam.station2aa.ui.preparation.PreparationViewModel
import com.mitas.ppnam.station2aa.ui.preparation.ResultBanner
import com.mitas.ppnam.station2aa.ui.preparation.loggedLine
import com.mitas.ppnam.station2aa.ui.preparation.plainText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject
import com.mitas.ppnam.station2aa.ui.preparation.IngredientDecisionKind
import com.mitas.ppnam.station2aa.ui.preparation.IngredientDecision

/** A session action the Rajoo machine-code scan asked for, waiting for the operator's tap. */
sealed interface RajooConfirm {
    data object Start : RajooConfirm
    data object Finish : RajooConfirm
    data object Close : RajooConfirm
}

/**
 * [collect] is the shared collect-screen state (its `prepId` is the JC and its snapshot's
 * `preparation` is the job's cumulative rows), so the General Collect sheet, bag guide and hold
 * banners render Rajoo unchanged.
 */
data class RajooUiState(
    val collect: PreparationUiState,
    val confirm: RajooConfirm? = null,
    /**
     * The first session's explicit Include/Exclude answers (contract §7), null = unanswered.
     * Never pre-filled; sent with `rajoo_start` and frozen by Station 2 after that.
     */
    val choices: Map<String, Boolean?> = emptyMap(),
    /** A tapped row's Less/More/Omit decision being filled in (contract §6). */
    val decision: IngredientDecision? = null,
) {
    val snapshot: PrepSnapshot? get() = collect.snapshot
    val job: RajooJob? get() = collect.snapshot?.rajoo
    val busy: Boolean get() = collect.inFlight || collect.blocking.isNotEmpty()

    /** The first session must answer each catalog-matched code; later sessions send none. */
    val choicePrompts: List<ChoicePrompt>
        get() = if (job?.choicesFrozen == false) snapshot?.requiredChoices.orEmpty() else emptyList()

    /** Why `rajoo_start` can't be sent yet; empty when it can. */
    val startProblems: List<String>
        get() = buildList {
            val prompts = choicePrompts
            if (prompts.isEmpty()) return@buildList
            prompts.forEach { if (choices[it.code] == null) add("Choose Include or Exclude for ${it.code}") }
            if (snapshot?.exceptionListRevision == null) add("Reload the job — Station 2 sent no ingredient list revision")
            val included = job?.materials.orEmpty().filter { !it.excluded && it.required.signum() > 0 }
            if (included.isNotEmpty() && included.all { choices[it.code] == false }) {
                add("Excluding every ingredient leaves nothing to collect")
            }
        }

    /** Rows can be changed only in the exact active session of an open job (contract §6). */
    val canDecide: Boolean
        get() = job?.let { !it.closed && it.hasActiveSession } == true && !busy && collect.step == CollectStep.Idle

    /** The one Rajoo machine this job was looked up on. */
    val machine: Machine?
        get() = job?.machineId?.let { id -> snapshot?.machines.orEmpty().firstOrNull { it.id.equals(id, true) || it.code.equals(id, true) } }

    /** The code start, finish and close all send (contract §5): the machine's commissioned code, else its id. */
    val machineCode: String? get() = machine?.code?.takeIf { it.isNotBlank() } ?: job?.machineId

    /** What scanning the Rajoo machine code does now (process flow page 4), or null when it does nothing. */
    val machineScanAction: RajooConfirm?
        get() {
            val job = job ?: return null
            return when {
                job.closed -> null
                job.hasActiveSession -> RajooConfirm.Finish
                job.allCollected -> RajooConfirm.Close
                else -> RajooConfirm.Start
            }
        }

    fun isMachineCode(code: String): Boolean {
        val m = machine
        return code.equals(machineCode, true) || (m != null && (code.equals(m.id, true) || code.equals(m.code, true)))
    }
}

/**
 * One Rajoo job card (contract §5, process flow page 4): scan the Rajoo machine code to start a
 * session, scan each bag's pallet against the cumulative full-job targets, scan the same code to
 * end the session, and scan it once more to close the job when everything is in.
 * The same safety rules as the PREP screen: nothing new is sent while an unresolved command for
 * this JC is in the outbox, Retry republishes the identical bytes, and a snapshot is applied only
 * if it is newer than the one on screen.
 */
@HiltViewModel
class RajooJobViewModel @Inject constructor(
    private val useCase: RajooUseCase,
    private val recovery: CommandRecoveryUseCase,
    private val coordinator: PendingCommandCoordinator,
    private val mqttRepository: MqttRepository,
    scanEventBus: ScanEventBus,
    private val sessionHolder: OperatorSessionHolder,
    private val authUseCase: AuthUseCase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val jobCard: String = checkNotNull(savedStateHandle.get<String>(JOB_CARD_ARG)) { "jobCard is required" }

    private val _uiState = MutableStateFlow(RajooUiState(PreparationUiState(prepId = jobCard)))
    val uiState: StateFlow<RajooUiState> = _uiState.asStateFlow()

    val connectionStatus: StateFlow<ConnectionStatus> = mqttRepository.connectionStatusIn(viewModelScope)
    val session: StateFlow<OperatorSession?> = sessionHolder.session

    private var issued = 0L
    private var applied = 0L
    private var readsInFlight = 0
    private var mutationsInFlight = 0
    private val retrying = mutableSetOf<String>()
    private var identifyJob: Job? = null

    @Volatile
    private var screenActive = false

    init {
        viewModelScope.launch {
            coordinator.pending.collect { all -> updateCollect { it.copy(blocking = all.filter(::blocksThisJob)) } }
        }
        viewModelScope.launch {
            mqttRepository.serverHints.collect { hint ->
                if (hint.mode.isNotBlank() && !hint.mode.equals("Rajoo", ignoreCase = true)) return@collect
                if (sessionHolder.session.value == null) return@collect
                read()
            }
        }
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
        viewModelScope.launch {
            scanEventBus.events.collect { event ->
                if (!screenActive) return@collect
                onScan(when (event) {
                    is ScanEvent.Barcode -> event.value
                    is ScanEvent.RfidTag -> event.tagId
                })
            }
        }
        retryOwnUnresolved()
        read()
    }

    fun setScreenActive(active: Boolean) { screenActive = active }

    fun logout() {
        viewModelScope.launch { authUseCase.logout() }
    }

    fun refresh() = read()

    /**
     * Every scan on this screen (process flow page 4): the Rajoo machine code starts a session,
     * ends the running one, or closes the completed job; anything else during a session is a
     * pallet tag / label. Nothing is sent until the operator taps the confirmation.
     */
    fun onScan(code: String) {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return
        if (UserTagPolicy.isUserTag(trimmed)) return show(PreparationViewModel.BADGE_MESSAGE)
        val state = _uiState.value
        val job = state.job ?: return show("This job card hasn't loaded yet")
        when {
            job.closed -> show("This job card is closed")
            state.isMachineCode(trimmed) -> machineScan(state)
            job.hasActiveSession -> palletScan(trimmed, state)
            else -> setBanner(ResultBanner.Error("Not the Rajoo machine code — $trimmed",
                "Scan ${state.machine?.name ?: "the Rajoo machine"} to start a session"))
        }
    }

    // ---- session ----

    private fun machineScan(state: RajooUiState) {
        if (state.busy) return show(whyBusy(state))
        if (state.collect.step != CollectStep.Idle) return show(PreparationViewModel.FINISH_FIRST_MESSAGE)
        val action = state.machineScanAction ?: return
        updateCollect { it.copy(banner = null, message = null) }
        // A first session starts with every catalog-matched code unanswered (contract §7: explicit).
        val choices = if (action == RajooConfirm.Start) state.choicePrompts.associate { it.code to null } else emptyMap()
        _uiState.update { it.copy(confirm = action, choices = choices) }
    }

    fun cancelConfirm() {
        _uiState.update { it.copy(confirm = null, choices = emptyMap()) }
    }

    /** The operator's explicit Include/Exclude for one first-session [code]. */
    fun onChoice(code: String, include: Boolean) {
        _uiState.update { s -> if (s.choicePrompts.any { it.code == code }) s.copy(choices = s.choices + (code to include)) else s }
    }

    /** The Start / End / Close tap: re-checked against the CURRENT job before anything is sent. */
    fun confirm() {
        val state = _uiState.value
        val confirm = state.confirm ?: return
        val job = state.job ?: return
        if (state.busy) return show(whyBusy(state))
        // Unanswered first-session choices keep the dialog open for the operator to finish.
        if (confirm == RajooConfirm.Start && state.machineScanAction == confirm) {
            state.startProblems.firstOrNull()?.let { return show(it) }
        }
        _uiState.update { it.copy(confirm = null, choices = emptyMap()) }
        if (state.machineScanAction != confirm) return show("The job changed — scan the Rajoo machine again")
        val code = state.machineCode ?: return show("This job has no Rajoo machine")
        when (confirm) {
            RajooConfirm.Start -> {
                // The first session sends the explicit answers and the read's catalog revision.
                val prompts = state.choicePrompts
                val first = !job.choicesFrozen
                val answers = if (first) prompts.map { Rev2ChoiceAnswer(it.code, state.choices.getValue(it.code)!!) } else null
                val revision = if (first) state.snapshot?.exceptionListRevision else null
                mutate { useCase.start(jobCard, code, revision, answers) }
            }
            RajooConfirm.Finish -> {
                val sessionId = job.activeSessionId ?: return show("No session is running")
                mutate { useCase.finish(jobCard, code, sessionId) }
            }
            RajooConfirm.Close -> mutate { useCase.close(jobCard, code) }
        }
    }

    // ---- collecting ----

    private fun palletScan(code: String, state: RajooUiState) {
        if (!state.collect.canScan) return show(whyBusy(state))
        if (state.collect.step.protectsPallet) return show(PreparationViewModel.FINISH_FIRST_MESSAGE)
        identifyJob?.cancel()
        updateCollect { it.copy(step = CollectStep.Identifying(code), banner = null, message = null) }
        identifyJob = viewModelScope.launch {
            val result = try {
                useCase.identify(jobCard, code)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                IdentifyResult.Failed("Could not identify this pallet: ${e.message}", retryable = false)
            }
            if ((_uiState.value.collect.step as? CollectStep.Identifying)?.code != code) return@launch
            // Rajoo's identify reply carries no job: the rows on screen stay the last full snapshot's.
            val snapshot = _uiState.value.snapshot
            when (result) {
                is IdentifyResult.Identified -> updateCollect {
                    it.copy(step = if (snapshot == null) CollectStep.Unknown("This job card hasn't loaded yet") else stepFor(result.source, snapshot))
                }
                is IdentifyResult.Unknown -> updateCollect { it.copy(step = CollectStep.Unknown(result.message)) }
                is IdentifyResult.Unsupported -> updateCollect { it.copy(step = CollectStep.Unknown(result.message)) }
                is IdentifyResult.Failed -> {
                    updateCollect { it.copy(step = CollectStep.Idle) }
                    show(result.message)
                }
            }
        }
    }

    private fun stepFor(source: SourceInfo, snapshot: PrepSnapshot): CollectStep {
        if (!source.usable) return CollectStep.Unusable(source, source.reason.ifBlank { "Station 2 can't use this pallet" })
        return when (val fit = snapshot.classify(source)) {
            is SourceFit.InMix -> identified(source, fit.material, prefill(source, fit.material))
            is SourceFit.Complete -> CollectStep.Unusable(source, "${fit.material.name} is already fully collected for this job")
            is SourceFit.Excluded -> CollectStep.Excluded(source, fit.material.name)
            // Contract §6: an off-BOM pallet can be added, or used instead of an uncaptured row,
            // with a reason. Its amount is the full-job total, which may span several pallets.
            SourceFit.NotInMix -> CollectStep.NotInMix(source, "", "", capAtStock = false)
        }
    }

    fun onReasonChange(text: String) = updateCollect { s ->
        val step = s.step as? CollectStep.NotInMix ?: return@updateCollect s
        s.copy(step = step.copy(reason = text))
    }

    /** Not-in-job pallet: [materialCode] is the uncaptured row it replaces, or null to add it. */
    fun pickSubstitute(materialCode: String?) = updateCollect { s ->
        val step = s.step as? CollectStep.NotInMix ?: return@updateCollect s
        s.copy(step = step.copy(substituteFor = materialCode))
    }

    /**
     * Sends the not-in-job pallet's decision in the active session: `Substitute` when a row was
     * picked, else `Add`. Never captures; on Done the pallet comes back as Identified to record.
     */
    fun addToMix() {
        val state = _uiState.value
        if (state.collect.inFlight) return
        val step = state.collect.step as? CollectStep.NotInMix ?: return
        if (!state.collect.canScan) return show(whyBusy(state))
        val valid = step.check as? AmountCheck.Valid ?: return
        if (!step.canAdd) return
        val job = state.job ?: return
        val sessionId = job.activeSessionId ?: return show("No session is active")
        val replaced = step.substituteFor
        mutate(endsStep = true, onDone = { seq, done -> decisionDone(seq, done, step.source) }) {
            if (replaced != null) {
                useCase.substitute(jobCard, sessionId, job.collectionRevision, replaced, step.source.code, valid.amount, step.reason.trim())
            } else {
                useCase.addIngredient(jobCard, sessionId, job.collectionRevision, step.source.code, valid.amount, step.reason.trim())
            }
        }
    }

    private fun decisionDone(seq: Long, done: CollectionResult.Done, source: SourceInfo) {
        applyIfNewer(seq, done.snapshot)
        val snapshot = _uiState.value.snapshot
        val fit = snapshot?.classify(source)
        if (fit is SourceFit.InMix) {
            updateCollect { it.copy(step = identified(source, fit.material, prefill(source, fit.material))) }
        } else {
            updateCollect { it.copy(step = CollectStep.Idle) }
            show("Saved — scan the pallet again to collect it")
            read()
        }
    }

    // ---- Less / More / Omit on a row (contract §6) ----

    /** Opens the decision for the tapped row; only in the exact active session. */
    fun openDecision(materialCode: String) {
        val state = _uiState.value
        if (!state.canDecide) return show(if (state.job?.hasActiveSession == true) whyBusy(state) else "Start a session first — scan the Rajoo machine code")
        val rows = state.job?.materials.orEmpty()
        val row = rows.firstOrNull { it.code == materialCode } ?: return
        val decision = IngredientDecision.on(row, rows) ?: return show("${row.name} is excluded — it can't be changed")
        _uiState.update { it.copy(decision = decision) }
    }

    fun onDecisionKind(kind: IngredientDecisionKind) = updateDecision { it.copy(kind = kind) }
    fun onDecisionTotal(text: String) = updateDecision { it.copy(totalText = text) }
    fun onDecisionReason(text: String) = updateDecision { it.copy(reason = text) }
    fun cancelDecision() = _uiState.update { it.copy(decision = null) }

    /** Sends the decision against the CURRENT row and job revision; nothing is captured. */
    fun sendDecision() {
        val state = _uiState.value
        val draft = state.decision ?: return
        if (state.busy) return show(whyBusy(state))
        val job = state.job ?: return
        val sessionId = job.activeSessionId ?: return show("No session is active")
        // Re-check against the row as it stands now, not as it was when the dialog opened.
        val decision = job.materials.firstOrNull { it.code == draft.material.code }
            ?.let { row -> IngredientDecision.on(row, job.materials) }
            ?.copy(kind = draft.kind, totalText = draft.totalText, reason = draft.reason)
            ?: run { cancelDecision(); return show("${draft.material.name} changed — check the job and try again") }
        if (!decision.canSend) {
            _uiState.update { it.copy(decision = decision) }
            return show(decision.problems.first())
        }
        _uiState.update { it.copy(decision = null) }
        val code = decision.material.code
        val reason = decision.reason.trim()
        mutate {
            when (decision.kind) {
                IngredientDecisionKind.Quantity ->
                    useCase.changeQuantity(jobCard, sessionId, job.collectionRevision, code, decision.newTotal!!, reason)
                IngredientDecisionKind.Omit -> useCase.omit(jobCard, sessionId, job.collectionRevision, code, reason)
            }
        }
    }

    private fun updateDecision(f: (IngredientDecision) -> IngredientDecision) =
        _uiState.update { s -> s.decision?.let { s.copy(decision = f(it)) } ?: s }

    private fun prefill(source: SourceInfo, row: PrepMaterial): String {
        // One bag at a time by default: a Rajoo target is the whole job, often many bags.
        val bag = source.bagSize?.takeIf { it.signum() > 0 }
        var amount = row.remaining
        if (bag != null && bag < amount) amount = bag
        source.available?.let { if (it < amount) amount = it }
        return amount.plainText()
    }

    private fun identified(source: SourceInfo, material: PrepMaterial, text: String): CollectStep.Identified {
        val unit = source.unit.ifBlank { material.unit }.ifBlank { "kg" }
        val check = validateAmount(text, material.remaining, source.available, unit)
        val guide = (check as? AmountCheck.Valid)?.let { bagGuide(it.amount, source.bagSize) }
        return CollectStep.Identified(source, SourceFit.InMix(material), text, guide, check)
    }

    fun cancelCollect() {
        if (_uiState.value.collect.inFlight) return
        identifyJob?.cancel()
        identifyJob = null
        updateCollect { it.copy(step = CollectStep.Idle) }
    }

    fun onAmountChange(text: String) {
        updateCollect { s ->
            val rows = s.snapshot?.preparation?.materials.orEmpty()
            s.copy(step = when (val step = s.step) {
                is CollectStep.Identified -> identified(step.source, rows.firstOrNull { it.code == step.fit.material.code } ?: step.fit.material, text)
                is CollectStep.NotInMix -> step.copy(amountText = text)
                else -> step
            })
        }
    }

    /** Submit: one capture per bag plus the exact rest, sent in order, stopping at the first not Done. */
    fun submit() {
        val state = _uiState.value
        val step = state.collect.step as? CollectStep.Identified ?: return
        if (!state.collect.canScan) return show(whyBusy(state))
        val sessionId = state.job?.activeSessionId ?: return show("No session is active")
        val row = state.snapshot?.preparation?.materials.orEmpty().firstOrNull { it.code == step.fit.material.code }
        if (row == null || row.excluded) {
            updateCollect { it.copy(step = CollectStep.Idle) }
            return show("${step.fit.material.name} is no longer on this job — scan again")
        }
        val current = identified(step.source, row, step.amountText)
        val valid = current.check as? AmountCheck.Valid ?: return updateCollect { it.copy(step = current) }
        runCaptures(step, sessionId, splitCaptures(valid.amount, step.source.bagSize))
    }

    private fun runCaptures(step: CollectStep.Identified, sessionId: String, parts: List<BigDecimal>) {
        if (parts.isEmpty()) return
        val before = _uiState.value.snapshot
        mutationsInFlight++
        updateCollect { it.copy(inFlight = true, banner = null, message = null,
            step = CollectStep.Submitting(step.source.code, step.fit.material.name, 0, parts.size)) }
        viewModelScope.launch {
            try {
                var recorded = 0
                var last: PrepSnapshot? = null
                for (quantity in parts) {
                    if (recorded > 0 && _uiState.value.collect.blocking.isNotEmpty()) {
                        updateCollect { it.copy(step = CollectStep.Idle) }
                        return@launch show("Recorded $recorded of ${parts.size} — resolve the unanswered command first")
                    }
                    val seq = ++issued
                    val result = useCase.capture(jobCard, step.source.code, sessionId, quantity)
                    if (result is CollectionResult.Done) {
                        recorded++
                        last = result.snapshot
                        applyIfNewer(seq, result.snapshot)
                        updateCollect { it.copy(step = CollectStep.Submitting(step.source.code, step.fit.material.name, recorded, parts.size)) }
                        continue
                    }
                    updateCollect { it.copy(step = if (result is CollectionResult.NotSent && recorded == 0) step else CollectStep.Idle) }
                    handleNotDone(seq, result, prefix = if (recorded > 0) "Recorded $recorded of ${parts.size} — then: " else "")
                    return@launch
                }
                if (last != null && _uiState.value.snapshot !== last && isThisJob(last.rajoo?.id)) {
                    applied = ++issued
                    updateCollect { it.copy(snapshot = last) }
                }
                updateCollect { it.copy(step = CollectStep.Idle) }
                val banner = last?.let { PreparationViewModel.doneBanner(before, it, null) }
                if (banner != null) setBanner(banner) else show(last?.resultMessage ?: "Recorded")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updateCollect { it.copy(step = CollectStep.Idle) }
                show("Something went wrong sending this: ${e.message}. Check the job before trying again.")
                read()
            } finally {
                mutationsInFlight--
                updateCollect { it.copy(inFlight = mutationsInFlight > 0) }
            }
        }
    }

    fun retry(command: PendingCommand) {
        if (!retrying.add(command.messageId)) return
        mutate(onFinally = { retrying.remove(command.messageId) }) { useCase.retry(command) }
    }

    // ---- plumbing ----

    private fun isThisJob(id: String?): Boolean = id != null && id.equals(jobCard, ignoreCase = true)

    private fun blocksThisJob(c: PendingCommand): Boolean =
        c.requestType == RajooUseCase.REQUEST && isThisJob(c.targetId) &&
            (c.status == PendingStatus.Unresolved || c.status == PendingStatus.ManagerReconcile)

    private fun retryOwnUnresolved() {
        val sessionId = sessionHolder.session.value?.operatorSessionId ?: return
        coordinator.pending.value
            .filter { blocksThisJob(it) && it.status == PendingStatus.Unresolved && it.sessionId == sessionId }
            .forEach(::retry)
    }

    private fun read() {
        val seq = ++issued
        readsInFlight++
        updateCollect { it.copy(loading = true) }
        viewModelScope.launch {
            try {
                when (val result = useCase.read(jobCard)) {
                    is ReadResult.Loaded -> {
                        val fresh = takeIfNewer(seq, result.snapshot)
                        if (fresh != null) updateCollect { it.copy(snapshot = fresh, readError = null) }
                    }
                    is ReadResult.Failed -> {
                        val fresh = takeIfNewer(seq, result.snapshot)
                        updateCollect { it.copy(snapshot = fresh ?: it.snapshot, readError = result.message) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                show("Could not read this job card: ${e.message}")
            } finally {
                readsInFlight--
                updateCollect { it.copy(loading = readsInFlight > 0) }
            }
        }
    }

    private fun mutate(
        endsStep: Boolean = false,
        onFinally: () -> Unit = {},
        onDone: (suspend (Long, CollectionResult.Done) -> Unit)? = null,
        call: suspend () -> CollectionResult,
    ) {
        val seq = ++issued
        mutationsInFlight++
        updateCollect { it.copy(inFlight = true, banner = null, message = null) }
        viewModelScope.launch {
            try {
                val result = call()
                if (result is CollectionResult.Done) {
                    if (onDone != null) onDone(seq, result) else {
                        applyIfNewer(seq, result.snapshot)
                        setBanner(ResultBanner.Accepted(result.snapshot.resultMessage.ifBlank { "Done" }))
                    }
                } else {
                    if (endsStep && result !is CollectionResult.NotSent) updateCollect { it.copy(step = CollectStep.Idle) }
                    handleNotDone(seq, result)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (endsStep) updateCollect { it.copy(step = CollectStep.Idle) }
                show("Something went wrong sending this: ${e.message}. Check the job before trying again.")
                read()
            } finally {
                mutationsInFlight--
                onFinally()
                updateCollect { it.copy(inFlight = mutationsInFlight > 0) }
            }
        }
    }

    private suspend fun handleNotDone(seq: Long, result: CollectionResult, prefix: String = "") {
        when (result) {
            is CollectionResult.Done -> Unit
            is CollectionResult.Rejected -> {
                val fresh = takeIfNewer(seq, result.snapshot)
                val logged = result.snapshot?.commandExceptions.orEmpty().maxByOrNull { it.timestampUtc }?.loggedLine()
                updateCollect { s ->
                    s.copy(snapshot = fresh ?: s.snapshot, message = null,
                        banner = ResultBanner.Rejected(prefix + result.message, logged, "Not done"))
                }
                if (result.snapshot == null) read()
            }
            is CollectionResult.Unresolved ->
                if (result.reason == UnresolvedReason.Recover) recoverNow(result.command, prefix) else show(prefix + result.message)
            is CollectionResult.NotSent -> show(prefix + result.message)
            is CollectionResult.AlreadySettled -> read()
        }
    }

    private suspend fun recoverNow(command: PendingCommand, prefix: String) {
        val seq = ++issued
        val result = try {
            recovery.recover(command)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RecoveryResult.StillUnresolved(command, "Could not check this command: ${e.message}")
        }
        val fresh = (result as? RecoveryResult.Resolved)?.let { takeIfNewer(seq, it.snapshot.toPrepSnapshot()) }
        updateCollect { s -> s.copy(snapshot = fresh ?: s.snapshot, message = OutcomeMessage.Info(prefix + result.noticeText(mqttRepository.deviceId))) }
    }

    private fun applyIfNewer(seq: Long, snapshot: PrepSnapshot?) {
        val fresh = takeIfNewer(seq, snapshot) ?: return
        updateCollect { it.copy(snapshot = fresh) }
    }

    /** Only a full Rajoo snapshot for THIS job, newer than the one on screen. */
    private fun takeIfNewer(seq: Long, snapshot: PrepSnapshot?): PrepSnapshot? {
        if (snapshot == null || seq <= applied || !isThisJob(snapshot.rajoo?.id)) return null
        applied = seq
        return snapshot
    }

    private fun updateCollect(f: (PreparationUiState) -> PreparationUiState) {
        _uiState.update { it.copy(collect = f(it.collect)) }
    }

    private fun setBanner(banner: ResultBanner) = updateCollect { it.copy(banner = banner) }

    private fun show(text: String) = updateCollect { it.copy(message = OutcomeMessage.Info(text)) }

    private fun whyBusy(state: RajooUiState): String = when {
        state.collect.inFlight -> "Wait — Station 2 is still answering"
        state.collect.blocking.isNotEmpty() -> "Resolve the unanswered command first — don't scan again"
        state.job == null -> "This job card hasn't loaded yet"
        state.job?.hasActiveSession != true -> "Start a session first — scan the Rajoo machine code"
        else -> "Not now"
    }

    companion object {
        const val JOB_CARD_ARG = "jobCard"
    }
}
