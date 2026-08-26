package com.mitas.ppnam.station2aa.ui.rfid

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mitas.ppnam.station2aa.data.rfid.ScanEvent
import com.mitas.ppnam.station2aa.data.rfid.ScanEventBus
import com.mitas.ppnam.station2aa.domain.model.PalletInfo
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.usecase.PalletUseCase
import com.mitas.ppnam.station2aa.ui.components.ConnectionStatus
import com.mitas.ppnam.station2aa.ui.components.connectionStatusFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class RfidUiState {
    object Idle : RfidUiState()
    object Loading : RfidUiState()
    object Recovering : RfidUiState()

    /**
     * A lookup that Station 2 answered. Note this covers found = false: a lookup that ran correctly
     * and found nothing is a result, not an error.
     */
    data class Result(val pallet: PalletInfo) : RfidUiState()

    /** Station 2 rejected the request, or we never heard back. */
    data class Error(val message: String) : RfidUiState()
}

@HiltViewModel
class RfidViewModel @Inject constructor(
    private val useCase: PalletUseCase,
    private val scanEventBus: ScanEventBus,
    private val mqttRepository: MqttRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<RfidUiState>(RfidUiState.Idle)
    val uiState: StateFlow<RfidUiState> = _uiState.asStateFlow()

    val connectionState: StateFlow<MqttConnectionState> = mqttRepository.connectionState

    val connectionStatus: StateFlow<ConnectionStatus> = connectionStatusFlow(
        mqttRepository.connectionState,
        mqttRepository.stationOnline,
        mqttRepository.clockSkewMillis,
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionStatus.Offline)

    private var scanJob: Job? = null

    fun startListening() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            scanEventBus.events.filterIsInstance<ScanEvent.RfidTag>().collect { event ->
                // A scan landing mid-request would start a second lookup racing the in-flight one to
                // write _uiState, and the loser's result would be silently discarded — including an
                // honest "recovered but still blocked" answer. Ignore scans until the current request
                // settles; rescanning over a settled Result/Error is still fine.
                when (_uiState.value) {
                    is RfidUiState.Loading, is RfidUiState.Recovering -> return@collect
                    else -> lookupPallet(event.tagId)
                }
            }
        }
    }

    fun lookupPallet(tagId: String) {
        viewModelScope.launch {
            _uiState.value = RfidUiState.Loading
            useCase.lookup(tagId)
                .onSuccess { pallet -> _uiState.value = RfidUiState.Result(pallet) }
                .onFailure { e -> _uiState.value = RfidUiState.Error(e.message ?: "Unknown error") }
        }
    }

    /**
     * Recovers the pallet currently on screen into Holding after a missed door read.
     *
     * Gated on the response's own `recoverable` flag — Station 2 decides recoverability, and the
     * client must not second-guess it.
     */
    fun recoverCurrentPallet() {
        val shown = (_uiState.value as? RfidUiState.Result)?.pallet ?: return
        if (!shown.recoverable) return
        viewModelScope.launch {
            _uiState.value = RfidUiState.Recovering
            useCase.recoverToHolding(
                palletRfidTag = shown.palletRfidTag,
                collectionId = null,
                auditReason = RECOVERY_REASON,
            )
                // The refreshed pallet may still be unusable — recovery registers arrival, it does
                // not clear a block. Show whatever Station 2 says rather than assuming success.
                .onSuccess { pallet -> _uiState.value = RfidUiState.Result(pallet) }
                .onFailure { e -> _uiState.value = RfidUiState.Error(e.message ?: "Recovery failed") }
        }
    }

    fun resetToIdle() {
        _uiState.value = RfidUiState.Idle
        startListening()
    }

    override fun onCleared() {
        super.onCleared()
        scanJob?.cancel()
    }

    private companion object {
        const val RECOVERY_REASON = "Pallet is physically at Station 2; fixed door read was missed."
    }
}
