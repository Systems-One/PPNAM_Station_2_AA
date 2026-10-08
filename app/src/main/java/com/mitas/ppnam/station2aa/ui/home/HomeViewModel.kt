package com.mitas.ppnam.station2aa.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mitas.ppnam.station2aa.data.identity.DeviceIdentity
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingCommand
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.usecase.AuthUseCase
import com.mitas.ppnam.station2aa.domain.usecase.ClearResult
import com.mitas.ppnam.station2aa.domain.usecase.PendingCommandCoordinator
import com.mitas.ppnam.station2aa.domain.usecase.RecoveryResult
import com.mitas.ppnam.station2aa.ui.components.ConnectionStatus
import com.mitas.ppnam.station2aa.ui.components.connectionStatusIn
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val mqttRepository: MqttRepository,
    private val authUseCase: AuthUseCase,
    sessionHolder: OperatorSessionHolder,
    private val coordinator: PendingCommandCoordinator,
    deviceIdentity: DeviceIdentity,
) : ViewModel() {

    /** This scanner: an unanswered command from another one can only be settled by a manager. */
    val deviceId: String by lazy { deviceIdentity.deviceId() }

    val session: StateFlow<OperatorSession?> = sessionHolder.session

    val connectionStatus: StateFlow<ConnectionStatus> = mqttRepository.connectionStatusIn(viewModelScope)

    /** Every unanswered command on this scanner, whoever sent it. */
    val pendingCommands: StateFlow<List<PendingCommand>> = coordinator.pending

    /** Only the signed-in operator's notices, filtered live so an operator switch never leaks them. */
    val recoveryNotices: StateFlow<List<RecoveryResult>> =
        combine(coordinator.notices, sessionHolder.session) { notices, session ->
            notices.visibleTo(session?.operatorId)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        // Home is where every login lands: recover what this operator left unresolved.
        recoverPending()
        // A broker reconnect is the next chance to learn what became of a StillUnresolved command.
        // A StateFlow only emits changes, so each CONNECTED here is a transition. The current state
        // is skipped: the call above already covers it.
        viewModelScope.launch {
            mqttRepository.connectionState
                .drop(1)
                .filter { it == MqttConnectionState.CONNECTED }
                .collect { coordinator.recoverForCurrentOperator() }
        }
    }

    fun recoverPending() {
        viewModelScope.launch { coordinator.recoverForCurrentOperator() }
    }

    fun dismissNotice(notice: RecoveryResult) = coordinator.dismiss(notice)

    private val _clearError = MutableStateFlow<String?>(null)
    /** Why the last clear did not happen, for the card to show; null when it worked. */
    val clearError: StateFlow<String?> = _clearError.asStateFlow()

    /** Anyone signed in may clear a reconciled command; the coordinator records who and when. */
    fun clearReconciled(messageId: String) {
        viewModelScope.launch {
            _clearError.value = when (val result = coordinator.clearReconciled(messageId)) {
                ClearResult.Cleared, ClearResult.NotFound -> null
                ClearResult.NotSignedIn -> "Sign in to clear it."
                ClearResult.NotAwaitingReconciliation -> "Not cleared: Station 2 can still settle this one."
                is ClearResult.NotRecorded ->
                    "Not cleared: the scanner could not record it (${result.cause.message}). Try again."
            }
        }
    }

    private val _logoutEvent = Channel<Unit>(Channel.BUFFERED)
    val logoutEvent: Flow<Unit> = _logoutEvent.receiveAsFlow()

    fun logout() {
        viewModelScope.launch {
            authUseCase.logout()
            _logoutEvent.send(Unit)
        }
    }
}
