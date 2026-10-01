package com.mitas.ppnam.station2aa.ui.joblookup

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mitas.ppnam.station2aa.data.mqtt.MqttTopics
import com.mitas.ppnam.station2aa.data.rfid.ScanEvent
import com.mitas.ppnam.station2aa.data.rfid.ScanEventBus
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.domain.model.JobDetail
import com.mitas.ppnam.station2aa.domain.model.JobSummary
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.usecase.AuthUseCase
import com.mitas.ppnam.station2aa.domain.usecase.JobLookupResult
import com.mitas.ppnam.station2aa.domain.usecase.JobLookupUseCase
import com.mitas.ppnam.station2aa.ui.components.ConnectionStatus
import com.mitas.ppnam.station2aa.ui.components.connectionStatusFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class JobLookupUiState(
    val jobs: List<JobSummary> = emptyList(),
    val listLoading: Boolean = false,
    val listError: String? = null,
    val lookupInFlight: Boolean = false,
    val lookupError: String? = null,
    val detail: JobDetail? = null,
    val detailLoading: Boolean = false,
    val detailError: String? = null,
)

/**
 * Shared by Job Lookup and Job Detail (scoped to their nav graph), so a job loaded by lookup is
 * already on screen when Detail opens.
 */
@HiltViewModel
class JobLookupViewModel @Inject constructor(
    private val useCase: JobLookupUseCase,
    mqttRepository: MqttRepository,
    scanEventBus: ScanEventBus,
    private val sessionHolder: OperatorSessionHolder,
    private val authUseCase: AuthUseCase,
) : ViewModel() {

    private val _uiState = MutableStateFlow(JobLookupUiState())
    val uiState: StateFlow<JobLookupUiState> = _uiState.asStateFlow()

    val connectionStatus: StateFlow<ConnectionStatus> = connectionStatusFlow(
        mqttRepository.connectionState,
        mqttRepository.stationOnline,
        mqttRepository.clockSkewMillis,
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionStatus.Offline)

    val session: StateFlow<OperatorSession?> = sessionHolder.session

    private val _navigateToDetail = Channel<String>(Channel.BUFFERED)
    val navigateToDetail: Flow<String> = _navigateToDetail.receiveAsFlow()

    /**
     * The job card Detail is showing, or null on the list. Drives what a push or reconnect re-reads,
     * and which detail reply may still land. Volatile: the push handler reads it on the MQTT thread.
     */
    @Volatile
    private var viewedJobCard: String? = null

    /**
     * The job card a lookup has just loaded, consumed by the next [openDetail] so it does not read
     * the same job twice in a row. One-shot: any later open of the job reads it again.
     */
    private var freshFromLookup: String? = null

    @Volatile
    private var lookupScreenActive = false

    init {
        // rev2.1: after a preparation is created Station 2 pushes a hint telling General readers to
        // read again. It is a hint, not data, and never a reason to cancel a request in flight — so
        // it launches its own read alongside whatever is running.
        mqttRepository.setServerPushHandler { topic, envelope, _ ->
            if (MqttTopics.responseTypeOf(topic) != "active_job_cards_invalidated") return@setServerPushHandler
            if (envelope.mode.isNotBlank() && !envelope.mode.equals("General", ignoreCase = true)) return@setServerPushHandler
            // Without a session the read would be rejected — and there is nothing on screen to refresh.
            if (sessionHolder.session.value == null) return@setServerPushHandler
            val target = viewedJobCard
            if (target != null) loadDetail(target) else refreshList()
        }
        // Spec §6.4: Job Lookup reads on open and on reconnect. The first value is skipped — the
        // screen reads on resume — so only a transition back INTO CONNECTED triggers a read.
        viewModelScope.launch {
            var previous: MqttConnectionState? = null
            mqttRepository.connectionState.collect { state ->
                val reconnected = previous != null && previous != MqttConnectionState.CONNECTED &&
                    state == MqttConnectionState.CONNECTED
                previous = state
                if (!reconnected || sessionHolder.session.value == null) return@collect
                val target = viewedJobCard
                if (target != null) loadDetail(target) else refreshList()
            }
        }
        viewModelScope.launch {
            scanEventBus.events.collect { event ->
                if (!lookupScreenActive) return@collect
                lookup(
                    when (event) {
                        is ScanEvent.Barcode -> event.value
                        is ScanEvent.RfidTag -> event.tagId
                    }
                )
            }
        }
    }

    /** Called by Job Lookup as it becomes visible or hidden, so scans only act on that screen. */
    fun setLookupScreenActive(active: Boolean) {
        lookupScreenActive = active
        if (active) viewedJobCard = null
    }

    fun refreshList() {
        viewModelScope.launch {
            _uiState.update { it.copy(listLoading = true) }
            when (val result = useCase.read()) {
                is JobLookupResult.Loaded ->
                    _uiState.update { it.copy(jobs = result.snapshot.jobs, listLoading = false, listError = null) }
                is JobLookupResult.Failed -> _uiState.update { state ->
                    state.copy(
                        jobs = result.snapshot?.jobs ?: state.jobs,
                        listLoading = false,
                        listError = result.message,
                    )
                }
            }
        }
    }

    fun lookup(input: String) {
        if (_uiState.value.lookupInFlight) return
        viewModelScope.launch {
            _uiState.update { it.copy(lookupInFlight = true, lookupError = null) }
            when (val result = useCase.lookup(input)) {
                is JobLookupResult.Loaded -> {
                    val detail = result.snapshot.detail!!  // the use case guarantees it on Loaded
                    _uiState.update {
                        it.copy(
                            jobs = result.snapshot.jobs, detail = detail, detailError = null,
                            lookupInFlight = false,
                        )
                    }
                    freshFromLookup = detail.jobCard
                    _navigateToDetail.send(detail.jobCard)
                }
                is JobLookupResult.Failed -> _uiState.update { state ->
                    state.copy(
                        jobs = result.snapshot?.jobs ?: state.jobs,
                        lookupInFlight = false,
                        lookupError = result.message,
                    )
                }
            }
        }
    }

    /**
     * Detail is opening for [jobCard]. Spec §6.4: Detail reads its target on open — except straight
     * after a lookup loaded that very job. A cached detail of the same job stays on screen while it
     * reloads.
     */
    fun openDetail(jobCard: String) {
        viewedJobCard = jobCard
        val fresh = freshFromLookup
        freshFromLookup = null
        if (fresh == jobCard && _uiState.value.detail?.jobCard == jobCard) return
        if (_uiState.value.detail?.jobCard != jobCard) _uiState.update { it.copy(detail = null) }
        loadDetail(jobCard)
    }

    private fun loadDetail(jobCard: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(detailLoading = true, detailError = null) }
            val result = useCase.read(jobCard)
            // The operator may have moved on to another job while this read was out. Its job list is
            // still current, but its detail or error belongs to a job no longer on screen.
            val stillViewed = viewedJobCard == jobCard
            when (result) {
                is JobLookupResult.Loaded -> _uiState.update {
                    if (stillViewed) {
                        it.copy(jobs = result.snapshot.jobs, detail = result.snapshot.detail, detailLoading = false)
                    } else {
                        it.copy(jobs = result.snapshot.jobs)
                    }
                }
                is JobLookupResult.Failed -> _uiState.update {
                    when {
                        stillViewed -> it.copy(
                            jobs = result.snapshot?.jobs ?: it.jobs,
                            detailLoading = false,
                            detailError = result.message,
                        )
                        else -> it.copy(jobs = result.snapshot?.jobs ?: it.jobs)
                    }
                }
            }
        }
    }

    fun logout() {
        viewModelScope.launch { authUseCase.logout() }
    }

    @VisibleForTesting
    internal fun setLookupInFlightForTest(inFlight: Boolean) {
        _uiState.update { it.copy(lookupInFlight = inFlight) }
    }
}
