package com.mitas.ppnam.station2aa.ui.preparation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mitas.ppnam.station2aa.data.mqtt.outbox.CommandOutbox
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingCommand
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingStatus
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.domain.model.ActivePreparation
import com.mitas.ppnam.station2aa.domain.model.MixProgress
import com.mitas.ppnam.station2aa.domain.model.PrepSnapshot
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.usecase.CollectionResult
import com.mitas.ppnam.station2aa.domain.usecase.GeneralCollectionUseCase
import com.mitas.ppnam.station2aa.domain.usecase.PrepareForm
import com.mitas.ppnam.station2aa.domain.usecase.ReadResult
import com.mitas.ppnam.station2aa.ui.components.ConnectionStatus
import com.mitas.ppnam.station2aa.ui.components.connectionStatusIn
import dagger.hilt.android.lifecycle.HiltViewModel
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
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    val jobCard: String = checkNotNull(savedStateHandle.get<String>("jobCard")) { "jobCard argument missing" }

    private val _uiState = MutableStateFlow(PrepareUiState())
    val uiState: StateFlow<PrepareUiState> = _uiState.asStateFlow()

    val connectionStatus: StateFlow<ConnectionStatus> = mqttRepository.connectionStatusIn(viewModelScope)

    private val _navigateToPrep = Channel<String>(Channel.BUFFERED)
    val navigateToPrep: Flow<String> = _navigateToPrep.receiveAsFlow()

    init {
        // Contract §11: always read fresh before a new preparation, even if detail was just on screen.
        reload()
        viewModelScope.launch {
            outbox.commands.collect { commands ->
                val sessionId = sessionHolder.session.value?.operatorSessionId
                val match = commands.firstOrNull {
                    it.action == "prepare" && it.targetId == jobCard &&
                        it.status == PendingStatus.Unresolved && sessionId != null && it.sessionId == sessionId
                }
                _uiState.update { it.copy(pending = match) }
            }
        }
        viewModelScope.launch {
            var previous: MqttConnectionState? = null
            mqttRepository.connectionState.collect { state ->
                val reconnected = previous != null && previous != MqttConnectionState.CONNECTED &&
                    state == MqttConnectionState.CONNECTED
                previous = state
                if (reconnected) reload()
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

    fun reload() {
        viewModelScope.launch {
            _uiState.update { it.copy(loading = true) }
            when (val result = useCase.read(jobCard)) {
                is ReadResult.Loaded -> applySnapshot(result.snapshot, message = null)
                is ReadResult.Failed -> _uiState.update { it.copy(loading = false, message = result.message) }
            }
        }
    }

    fun submit() {
        val state = _uiState.value
        val form = state.form ?: return
        if (state.sending || state.pending != null || form.problems.isNotEmpty()) return
        val mixCount = form.mixCount ?: return
        // Set before launching so a second tap in the same frame is ignored.
        _uiState.update { it.copy(sending = true, message = null) }
        viewModelScope.launch {
            val result = useCase.prepare(
                form.jobCard, mixCount, form.exceptionListRevision, form.answersForWire(),
                form.reason.takeIf { it.isNotBlank() },
            )
            handle(result)
        }
    }

    fun retryPending() {
        val state = _uiState.value
        val command = state.pending ?: return
        if (state.sending) return
        _uiState.update { it.copy(sending = true, message = null) }
        viewModelScope.launch { handle(useCase.retry(command)) }
    }

    private suspend fun handle(result: CollectionResult) {
        when (result) {
            is CollectionResult.Done -> {
                _uiState.update { it.copy(sending = false, pending = null) }
                val id = result.snapshot.resultTargetId
                if (id.isNullOrBlank()) {
                    applySnapshot(result.snapshot, "Prepared, but Station 2 did not say which preparation. Open it from the job.")
                } else {
                    _navigateToPrep.send(id)
                }
            }
            is CollectionResult.Rejected -> {
                _uiState.update { it.copy(sending = false, pending = null) }
                if (result.snapshot != null) applySnapshot(result.snapshot, result.message)
                else _uiState.update { it.copy(message = result.message) }
            }
            is CollectionResult.Unresolved ->
                _uiState.update { it.copy(sending = false, pending = result.command, message = result.message) }
            is CollectionResult.NotSent ->
                _uiState.update { it.copy(sending = false, message = result.message) }
            is CollectionResult.AlreadySettled -> {
                _uiState.update {
                    it.copy(sending = false, pending = null,
                        message = "Station 2 already settled that prepare. Check the job's preparations.")
                }
                reload()
            }
        }
    }

    /** Takes the snapshot's choices and counts; answers and the typed N survive via [PrepareForm.rebasedOn]. */
    private fun applySnapshot(snapshot: PrepSnapshot, message: String?) {
        val detail = snapshot.lookup.detail
        _uiState.update { state ->
            val form = state.form?.rebasedOn(snapshot) ?: PrepareForm.from(snapshot)
            state.copy(
                form = form,
                loading = false,
                message = message ?: if (form == null) "Station 2 sent no job to prepare" else null,
                activePreparations = detail?.mixProgress?.activePreparations.orEmpty(),
                product = detail?.product.orEmpty(),
                progress = detail?.mixProgress,
            )
        }
    }
}
