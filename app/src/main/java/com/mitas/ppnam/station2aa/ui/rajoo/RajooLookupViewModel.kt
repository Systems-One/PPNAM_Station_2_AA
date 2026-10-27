package com.mitas.ppnam.station2aa.ui.rajoo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mitas.ppnam.station2aa.UserTagPolicy
import com.mitas.ppnam.station2aa.data.rfid.ScanEvent
import com.mitas.ppnam.station2aa.data.rfid.ScanEventBus
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.domain.model.JobSummary
import com.mitas.ppnam.station2aa.domain.model.Machine
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.usecase.AuthUseCase
import com.mitas.ppnam.station2aa.domain.usecase.RajooUseCase
import com.mitas.ppnam.station2aa.domain.usecase.ReadResult
import com.mitas.ppnam.station2aa.ui.components.ConnectionStatus
import com.mitas.ppnam.station2aa.ui.components.connectionStatusIn
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class RajooLookupUiState(
    val jobs: List<JobSummary> = emptyList(),
    /** Station 2's configured Rajoo machine(s); the plant has one. */
    val machines: List<Machine> = emptyList(),
    /**
     * The Rajoo machine `lookup` names. Filled in automatically when Station 2 lists exactly one
     * enabled, commissioned Rajoo machine; otherwise set by scanning one of them.
     */
    val machine: Machine? = null,
    /** [machine] came from Station 2's list, not a scan: there is nothing to rescan. */
    val machineAutoSelected: Boolean = false,
    val jobCard: String = "",
    val loading: Boolean = false,
    val looking: Boolean = false,
    val error: String? = null,
) {
    val canLookup: Boolean get() = machine != null && jobCard.isNotBlank() && jobCard.all(Char::isDigit) && !looking
}

/**
 * Rajoo entry: look up the job card on the Rajoo machine (contract §5 `lookup` names its `code`).
 * The plant has one Rajoo machine, so when Station 2 lists exactly one enabled, commissioned
 * machine it is used without a scan. Only with several does the operator scan which one — an
 * invalid or disabled code is rejected and must be rescanned. Station 2 returns the full
 * Production Order BOM, new or resumed.
 */
@HiltViewModel
class RajooLookupViewModel @Inject constructor(
    private val useCase: RajooUseCase,
    private val authUseCase: AuthUseCase,
    mqttRepository: MqttRepository,
    sessionHolder: OperatorSessionHolder,
    scanEventBus: ScanEventBus,
) : ViewModel() {

    private val _uiState = MutableStateFlow(RajooLookupUiState())
    val uiState: StateFlow<RajooLookupUiState> = _uiState.asStateFlow()

    private val _opened = MutableSharedFlow<String>(extraBufferCapacity = 1)
    /** A job card that loaded: open its screen. */
    val opened: SharedFlow<String> = _opened.asSharedFlow()

    val connectionStatus: StateFlow<ConnectionStatus> = mqttRepository.connectionStatusIn(viewModelScope)
    val session: StateFlow<OperatorSession?> = sessionHolder.session

    @Volatile
    private var screenActive = false

    init {
        viewModelScope.launch {
            mqttRepository.serverHints.collect { hint ->
                if (hint.mode.equals("Rajoo", ignoreCase = true) && sessionHolder.session.value != null) refresh()
            }
        }
        // Without a machine yet (several listed), a scan picks it. After that a scan is the job card.
        viewModelScope.launch {
            scanEventBus.events.collect { event ->
                if (!screenActive) return@collect
                val code = when (event) {
                    is ScanEvent.Barcode -> event.value
                    is ScanEvent.RfidTag -> event.tagId
                }.trim()
                if (code.isEmpty() || UserTagPolicy.isUserTag(code)) return@collect
                if (_uiState.value.machine == null) onMachineScan(code) else onJobCard(code)
            }
        }
        refresh()
    }

    fun setScreenActive(active: Boolean) { screenActive = active }

    /** Confirms the operator is at Rajoo: the code must be a configured, enabled Rajoo machine. */
    fun onMachineScan(code: String) {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return
        val machine = _uiState.value.machines.firstOrNull { it.code.equals(trimmed, true) || it.id.equals(trimmed, true) }
        _uiState.update {
            when {
                machine == null -> it.copy(error = "$trimmed is not the Rajoo machine code — rescan")
                !machine.enabled -> it.copy(error = "${machine.name} is disabled — ask an admin to enable it")
                else -> it.copy(machine = machine, machineAutoSelected = false, error = null)
            }
        }
    }

    /** Back to the machine scan (e.g. the wrong code was accepted). Not offered for the only machine. */
    fun changeMachine() = _uiState.update {
        if (it.machineAutoSelected) it else it.copy(machine = null, jobCard = "", error = null)
    }

    fun onJobCard(text: String) = _uiState.update { it.copy(jobCard = text.trim(), error = null) }

    fun refresh() {
        _uiState.update { it.copy(loading = true) }
        viewModelScope.launch {
            when (val r = useCase.read()) {
                is ReadResult.Loaded -> _uiState.update { s ->
                    val machines = r.snapshot.machines
                    val usable = machines.filter { it.enabled && it.code.isNotBlank() }
                    val only = usable.singleOrNull()
                    if (only != null) {
                        // The one Rajoo machine: use it, refreshed to its current commissioned code.
                        return@update s.copy(loading = false, jobs = r.snapshot.lookup.jobs, machines = machines,
                            machine = only, machineAutoSelected = true, error = null)
                    }
                    // Several (or none): a scanned machine re-commissioned or disabled meanwhile must be scanned again.
                    val still = s.machine?.takeUnless { s.machineAutoSelected }
                        ?.let { m -> machines.firstOrNull { it.id == m.id && it.enabled } }
                    s.copy(loading = false, jobs = r.snapshot.lookup.jobs, machines = machines, machine = still,
                        machineAutoSelected = false, error = if (usable.isEmpty()) NO_MACHINE else null)
                }
                is ReadResult.Failed -> _uiState.update { it.copy(loading = false, error = r.message) }
            }
        }
    }

    /** Opens a listed job: a closed one for its history, an open one by looking it up again (resume). */
    fun open(job: JobSummary) {
        if (job.closed) { _opened.tryEmit(job.jobCard); return }
        onJobCard(job.jobCard)
        lookup()
    }

    /** `lookup` the job card on the confirmed Rajoo machine; on success open the job. */
    fun lookup() {
        val state = _uiState.value
        val machine = state.machine ?: return
        if (!state.canLookup) return
        _uiState.update { it.copy(looking = true, error = null) }
        viewModelScope.launch {
            when (val r = useCase.lookup(state.jobCard, machine.code.ifBlank { machine.id })) {
                is ReadResult.Loaded -> {
                    _uiState.update { it.copy(looking = false) }
                    _opened.tryEmit(r.snapshot.rajoo?.id ?: state.jobCard)
                    refresh()
                }
                is ReadResult.Failed -> _uiState.update { it.copy(looking = false, error = r.message) }
            }
        }
    }

    fun logout() {
        viewModelScope.launch { authUseCase.logout() }
    }

    companion object {
        const val NO_MACHINE = "No Rajoo machine is enabled and commissioned in Station 2 — ask an admin"
    }
}
