package com.mitas.ppnam.station2aa.ui.joblookup

import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mitas.ppnam.station2aa.UserTagPolicy
import com.mitas.ppnam.station2aa.data.mqtt.outbox.CommandOutbox
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingCommand
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingStatus
import com.mitas.ppnam.station2aa.data.rfid.ScanEvent
import com.mitas.ppnam.station2aa.data.rfid.ScanEventBus
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.domain.feedback.ScanRepeatGate
import com.mitas.ppnam.station2aa.domain.feedback.ScanSound
import com.mitas.ppnam.station2aa.domain.feedback.ScanSounds
import com.mitas.ppnam.station2aa.domain.model.JobDetail
import com.mitas.ppnam.station2aa.domain.model.JobSummary
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.usecase.AuthUseCase
import com.mitas.ppnam.station2aa.domain.usecase.JobLookupResult
import com.mitas.ppnam.station2aa.domain.usecase.JobLookupUseCase
import com.mitas.ppnam.station2aa.ui.components.ConnectionStatus
import com.mitas.ppnam.station2aa.ui.components.connectionStatusIn
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class JobLookupUiState(
    val jobs: List<JobSummary> = emptyList(),
    val listLoading: Boolean = false,
    val listError: String? = null,
    val lookupInFlight: Boolean = false,
    val lookupError: String? = null,
    /** Whether the lookup error is a timeout / not-connected one, where Retry can help. */
    val lookupRetryable: Boolean = false,
    val detail: JobDetail? = null,
    val detailLoading: Boolean = false,
    val detailError: String? = null,
    /** An Unresolved `prepare` for the viewed job: Prepare is blocked until it is retried on the Prepare screen. */
    val pendingPrepare: PendingCommand? = null,
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
    private val outbox: CommandOutbox,
) : ViewModel() {

    private val _uiState = MutableStateFlow(JobLookupUiState())
    val uiState: StateFlow<JobLookupUiState> = _uiState.asStateFlow()

    val connectionStatus: StateFlow<ConnectionStatus> = mqttRepository.connectionStatusIn(viewModelScope)

    val session: StateFlow<OperatorSession?> = sessionHolder.session

    private val _scanSounds = MutableSharedFlow<ScanSound>(extraBufferCapacity = 8)
    /**
     * Suite rule S1: a scanned or typed job card sounds on Station 2's answer to its lookup; a
     * badge, or a different code while a lookup is in flight, fails here. Played by Job Lookup.
     */
    val scanSounds: SharedFlow<ScanSound> = _scanSounds.asSharedFlow()
    /** Suite rule S3: repeat reads of one trigger pull sound once. */
    private val repeats = ScanRepeatGate()

    /** The input of the lookup in flight, so a repeat read of it stays silent (S3). */
    private var lookupInFlightInput: String? = null

    private val _navigateToDetail = Channel<String>(Channel.BUFFERED)
    val navigateToDetail: Flow<String> = _navigateToDetail.receiveAsFlow()

    private val _navigateToPrep = Channel<String>(Channel.BUFFERED)
    val navigateToPrep: Flow<String> = _navigateToPrep.receiveAsFlow()

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

    /** What the last [lookup] was asked for — typed or scanned — so Retry can repeat it. */
    private var lastLookupInput: String? = null

    init {
        viewModelScope.launch { outbox.commands.collect { refreshPendingPrepare() } }
        // rev2.1: after a preparation is created Station 2 pushes a hint telling General readers to
        // read again. It is a hint, not data, and never a reason to cancel a request in flight — so
        // it launches its own read alongside whatever is running.
        viewModelScope.launch {
            mqttRepository.serverHints.collect { hint ->
                if (hint.mode.isNotBlank() && !hint.mode.equals("General", ignoreCase = true)) return@collect
                // Without a session the read would be rejected — and there is nothing on screen to refresh.
                if (sessionHolder.session.value == null) return@collect
                val target = viewedJobCard
                if (target != null) loadDetail(target) else refreshList()
            }
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
                if (event is ScanEvent.RfidTag && UserTagPolicy.isUserTag(event.tagId)) {
                    // A badge is for login, never a job card: say so instead of a digits-only error.
                    _uiState.update {
                        it.copy(lookupError = JobLookupUseCase.USER_BADGE_MESSAGE, lookupRetryable = false)
                    }
                    val badge = event.tagId.trim()
                    sound(badge, ScanSound.FAIL, quiet = repeats.isRepeat(badge))
                    return@collect
                }
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
        if (active) {
            viewedJobCard = null
            refreshPendingPrepare()
        }
    }

    fun refreshList() {
        viewModelScope.launch {
            _uiState.update { it.copy(listLoading = true) }
            when (val result = useCase.read()) {
                is JobLookupResult.Loaded ->
                    _uiState.update { it.copy(jobs = result.snapshot.jobs, listLoading = false, listError = null) }
                is JobLookupResult.OpenPreparation -> Unit
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

    /** A scanned or typed job card: its answer sounds (suite rule S1). */
    fun lookup(input: String) = lookup(input, sound = true)

    private fun lookup(input: String, sound: Boolean) {
        val code = input.trim()
        if (_uiState.value.lookupInFlight) {
            // S3: a repeat read of the job card in flight is the same scan; another code is refused.
            if (sound && !repeats.isRepeat(code, lookupInFlightInput)) sound(code, ScanSound.FAIL)
            return
        }
        lastLookupInput = input
        lookupInFlightInput = code
        val quiet = !sound || repeats.isRepeat(code)
        viewModelScope.launch {
            _uiState.update { it.copy(lookupInFlight = true, lookupError = null, lookupRetryable = false) }
            val result = useCase.lookup(input)
            lookupInFlightInput = null
            sound(code, ScanSounds.forJobLookup(result), quiet)
            when (result) {
                is JobLookupResult.Loaded -> {
                    val detail = result.snapshot.detail!!  // the use case guarantees it on Loaded
                    _uiState.update {
                        it.copy(
                            // detailLoading too: a read abandoned earlier would otherwise leave
                            // this freshly loaded job spinning, since its late reply no longer
                            // applies to the job on screen.
                            jobs = result.snapshot.jobs, detail = detail, detailError = null,
                            detailLoading = false, lookupInFlight = false,
                        )
                    }
                    freshFromLookup = detail.jobCard
                    _navigateToDetail.send(detail.jobCard)
                }
                is JobLookupResult.OpenPreparation -> {
                    _uiState.update { it.copy(lookupInFlight = false) }
                    _navigateToPrep.send(result.prepId)
                }
                is JobLookupResult.Failed -> _uiState.update { state ->
                    state.copy(
                        jobs = result.snapshot?.jobs ?: state.jobs,
                        lookupInFlight = false,
                        lookupError = result.message,
                        lookupRetryable = result.retryable,
                    )
                }
            }
        }
    }

    /** The Retry button next to a lookup error. A no-op until something has been looked up. */
    fun retryLookup() {
        lastLookupInput?.let { lookup(it, sound = false) } // a button tap, not a scan
    }

    /** Emits [sound] unless [quiet]; a FAIL also quiets [code]'s repeat reads for the S3 window. */
    private fun sound(code: String, sound: ScanSound, quiet: Boolean = false) {
        if (sound == ScanSound.FAIL) repeats.failed(code)
        if (!quiet) _scanSounds.tryEmit(sound)
    }

    /**
     * Detail is opening for [jobCard]. Spec §6.4: Detail reads its target on open — except straight
     * after a lookup loaded that very job. A cached detail of the same job stays on screen while it
     * reloads.
     */
    fun openDetail(jobCard: String) {
        viewedJobCard = jobCard
        refreshPendingPrepare()
        val fresh = freshFromLookup
        freshFromLookup = null
        if (fresh == jobCard && _uiState.value.detail?.jobCard == jobCard) return
        if (_uiState.value.detail?.jobCard != jobCard) _uiState.update { it.copy(detail = null) }
        loadDetail(jobCard)
    }

    private fun refreshPendingPrepare() {
        val job = viewedJobCard
        val match = outbox.commands.value.firstOrNull {
            job != null && it.action == "prepare" && it.targetId == job && (it.status == PendingStatus.Unresolved || it.status == PendingStatus.ManagerReconcile)
        }
        _uiState.update { it.copy(pendingPrepare = match) }
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
                // read() never opens a preparation; only lookup() does.
                is JobLookupResult.OpenPreparation -> Unit
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
