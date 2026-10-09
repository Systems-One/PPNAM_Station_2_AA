package com.mitas.ppnam.station2aa.ui.preparation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mitas.ppnam.station2aa.data.mqtt.outbox.CommandOutbox
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingCommand
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingStatus
import com.mitas.ppnam.station2aa.data.mqtt.outbox.UnresolvedReason
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.domain.model.ActivePreparation
import com.mitas.ppnam.station2aa.domain.model.MixProgress
import com.mitas.ppnam.station2aa.domain.model.PrepSnapshot
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.usecase.CollectionResult
import com.mitas.ppnam.station2aa.domain.usecase.CommandRecoveryUseCase
import com.mitas.ppnam.station2aa.domain.usecase.GeneralCollectionUseCase
import com.mitas.ppnam.station2aa.domain.usecase.JobLookupUseCase
import com.mitas.ppnam.station2aa.domain.usecase.PrepareForm
import com.mitas.ppnam.station2aa.domain.usecase.ReadResult
import com.mitas.ppnam.station2aa.domain.usecase.RecoveryOutcome
import com.mitas.ppnam.station2aa.domain.usecase.RecoveryResult
import com.mitas.ppnam.station2aa.ui.components.ConnectionStatus
import com.mitas.ppnam.station2aa.ui.components.connectionStatusIn
import com.mitas.ppnam.station2aa.ui.home.noticeText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PrepareUiState(
    val form: PrepareForm? = null,
    val loading: Boolean = true,
    val sending: Boolean = false,
    val message: String? = null,
    /** A same-session prepare Station 2 has not confirmed: Retry it, never send a new one. */
    val pending: PendingCommand? = null,
    /** Any unresolved prepare for this job, from any session. While set, a new prepare is refused. */
    val blocking: PendingCommand? = null,
    /** A manager must reconcile a prepare for this job; no Retry. */
    val managerHold: Boolean = false,
    val activePreparations: List<ActivePreparation> = emptyList(),
    val product: String = "",
    val progress: MixProgress? = null,
)

@HiltViewModel
class PrepareViewModel @Inject constructor(
    private val useCase: GeneralCollectionUseCase,
    private val outbox: CommandOutbox,
    private val sessionHolder: OperatorSessionHolder,
    private val mqttRepository: MqttRepository,
    private val recovery: CommandRecoveryUseCase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val jobCard: String = checkNotNull(savedStateHandle.get<String>("jobCard")) { "jobCard argument missing" }

    private val _uiState = MutableStateFlow(PrepareUiState())
    val uiState: StateFlow<PrepareUiState> = _uiState.asStateFlow()

    val connectionStatus: StateFlow<ConnectionStatus> = mqttRepository.connectionStatusIn(viewModelScope)

    private val _navigateToPrep = Channel<String>(Channel.BUFFERED)
    val navigateToPrep: Flow<String> = _navigateToPrep.receiveAsFlow()

    private val retrying = mutableSetOf<String>()

    init {
        // Contract section 11: always read fresh before a new preparation. Spec section 6: first retry
        // this session's unresolved prepare, once.
        refresh()
        viewModelScope.launch {
            outbox.commands.collect { commands ->
                val mine = commands.filter {
                    it.action == "prepare" && it.targetId == jobCard &&
                        (it.status == PendingStatus.Unresolved || it.status == PendingStatus.ManagerReconcile)
                }
                val retryable = ownUnresolved(commands).firstOrNull()
                _uiState.update {
                    it.copy(
                        pending = retryable,
                        blocking = retryable ?: mine.firstOrNull(),
                        managerHold = mine.any { c -> c.status == PendingStatus.ManagerReconcile },
                    )
                }
            }
        }
        viewModelScope.launch {
            var previous: MqttConnectionState? = null
            mqttRepository.connectionState.collect { state ->
                val reconnected = previous != null && previous != MqttConnectionState.CONNECTED &&
                    state == MqttConnectionState.CONNECTED
                previous = state
                // Signed out: nothing to retry or read for (as PreparationViewModel).
                if (reconnected && sessionHolder.session.value != null) refresh()
            }
        }
        viewModelScope.launch {
            mqttRepository.serverHints.collect { hint ->
                if (hint.mode.isNotBlank() && !hint.mode.equals("General", ignoreCase = true)) return@collect
                // Never disturb a prepare in flight; the answers survive the re-read either way.
                if (!_uiState.value.sending) reload()
            }
        }
    }

    fun onMixCount(text: String) = updateForm { it.copy(mixCountText = text) }

    fun onAnswer(code: String, include: Boolean) = updateForm { it.copy(answers = it.answers + (code to include)) }

    fun onReason(text: String) = updateForm { it.copy(reason = text) }

    private fun updateForm(change: (PrepareForm) -> PrepareForm) {
        _uiState.update { state -> state.form?.let { state.copy(form = change(it)) } ?: state }
    }

    /** This session's unresolved prepares for this job. An earlier session's are the coordinator's to recover. */
    private fun ownUnresolved(commands: List<PendingCommand>): List<PendingCommand> {
        val sessionId = sessionHolder.session.value?.operatorSessionId ?: return emptyList()
        return commands.filter {
            it.action == "prepare" && it.targetId == jobCard &&
                it.status == PendingStatus.Unresolved && it.sessionId == sessionId
        }
    }

    /** On open and on reconnect: retry this session's unresolved prepare once, then read. */
    private fun refresh() {
        viewModelScope.launch {
            var done = false
            for (command in ownUnresolved(outbox.commands.value)) {
                if (doRetry(command) is CollectionResult.Done) done = true
            }
            if (!done) read()
        }
    }

    fun reload() {
        viewModelScope.launch { read() }
    }

    private suspend fun read() {
        _uiState.update { it.copy(loading = true) }
        try {
            when (val result = useCase.read(jobCard)) {
                is ReadResult.Loaded -> applySnapshot(result.snapshot, message = null)
                is ReadResult.Failed -> _uiState.update { it.copy(loading = false, message = result.message) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update { it.copy(loading = false, message = "Could not read this job: ${e.message}") }
        }
    }

    fun submit() {
        val state = _uiState.value
        val form = state.form ?: return
        if (state.sending || state.blocking != null || form.problems.isNotEmpty()) return
        val mixCount = form.mixCount ?: return
        // Set before launching so a second tap in the same frame is ignored.
        _uiState.update { it.copy(sending = true, message = null) }
        viewModelScope.launch {
            try {
                val result = useCase.prepare(
                    form.jobCard, mixCount, form.exceptionListRevision, form.answersForWire(),
                    form.reason.takeIf { it.isNotBlank() },
                )
                handle(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sendFailed(e)
            }
        }
    }

    fun retryPending() {
        val command = _uiState.value.pending ?: return
        viewModelScope.launch { doRetry(command) }
    }

    /** Republishes [command] (identical bytes, same id). Null when a send is already in flight. */
    private suspend fun doRetry(command: PendingCommand): CollectionResult? {
        if (_uiState.value.sending || !retrying.add(command.messageId)) return null
        _uiState.update { it.copy(sending = true, message = null) }
        return try {
            useCase.retry(command).also { handle(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            sendFailed(e)
            null
        } finally {
            retrying.remove(command.messageId)
        }
    }

    /** A send threw: whether it went out is unknown, so say so, free the buttons and re-read. */
    private suspend fun sendFailed(e: Exception) {
        _uiState.update {
            it.copy(
                sending = false,
                message = "Something went wrong sending this: ${e.message}. Check the job's preparations before trying again.",
            )
        }
        read()
    }

    private suspend fun handle(result: CollectionResult) {
        when (result) {
            is CollectionResult.Done -> {
                _uiState.update { it.copy(sending = false, pending = null, blocking = null) }
                val id = result.snapshot.resultTargetId
                if (id.isNullOrBlank()) {
                    applySnapshot(result.snapshot, "Prepared, but Station 2 did not say which preparation. Open it from the job.")
                } else {
                    _navigateToPrep.send(id)
                }
            }
            is CollectionResult.Rejected -> {
                _uiState.update { it.copy(sending = false, pending = null, blocking = null) }
                if (result.snapshot != null) {
                    applySnapshot(result.snapshot, result.message)
                } else {
                    // Spec section 7: a rejection with no state attached still means "read again".
                    _uiState.update { it.copy(message = result.message) }
                    read()
                }
            }
            is CollectionResult.Unresolved -> {
                val retryable = result.reason == UnresolvedReason.RetryIdentical
                _uiState.update {
                    it.copy(
                        sending = false,
                        pending = if (retryable) result.command else null,
                        blocking = result.command,
                        managerHold = it.managerHold || result.reason == UnresolvedReason.ManagerReconcile,
                        message = result.message,
                    )
                }
                // message_id_conflict: an identical retry cannot work; ask Station 2 what happened instead.
                if (result.reason == UnresolvedReason.Recover) recoverNow(result.command)
            }
            is CollectionResult.NotSent ->
                _uiState.update { it.copy(sending = false, message = result.message) }
            is CollectionResult.AlreadySettled -> {
                _uiState.update {
                    it.copy(
                        sending = false, pending = null, blocking = null,
                        message = "Station 2 already settled that prepare. Check the job's preparations.",
                    )
                }
                read()
            }
        }
    }

    private suspend fun recoverNow(command: PendingCommand) {
        _uiState.update { it.copy(sending = true) }
        val result = try {
            recovery.recover(command)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            RecoveryResult.StillUnresolved(command, "Could not check this prepare: ${e.message}")
        }
        // Same wording as the PREP screen and Home ("already done. It was not repeated.").
        val text = result.noticeText(mqttRepository.deviceId)
        _uiState.update {
            if (result is RecoveryResult.Resolved) it.copy(sending = false, pending = null, blocking = null, message = text)
            else it.copy(sending = false, message = text)
        }
        if (result !is RecoveryResult.Resolved) return
        // A committed prepare already made a PREP: open it, so Prepare is not offered again for a second one.
        val made = result.snapshot.recovery?.result?.targetId?.trim()
        if (result.outcome == RecoveryOutcome.Committed && made != null && JobLookupUseCase.isPrepId(made)) {
            _navigateToPrep.send(made)
        }
        read()
    }

    /**
     * Takes the snapshot's choices and counts; answers and the typed N survive via [PrepareForm.rebasedOn].
     * A background re-read passes no [message] and leaves the one on screen alone.
     */
    private fun applySnapshot(snapshot: PrepSnapshot, message: String?) {
        val detail = snapshot.lookup.detail
        _uiState.update { state ->
            val form = state.form?.rebasedOn(snapshot) ?: PrepareForm.from(snapshot)
            state.copy(
                form = form,
                loading = false,
                message = message ?: state.message ?: if (form == null) "Station 2 sent no job to prepare" else null,
                activePreparations = detail?.mixProgress?.activePreparations.orEmpty(),
                product = detail?.product.orEmpty(),
                progress = detail?.mixProgress,
            )
        }
    }
}
