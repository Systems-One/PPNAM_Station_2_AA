package com.mitas.ppnam.station2aa.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mitas.ppnam.station2aa.data.mqtt.outbox.PendingCommand
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.usecase.AuthUseCase
import com.mitas.ppnam.station2aa.domain.usecase.PendingCommandCoordinator
import com.mitas.ppnam.station2aa.domain.usecase.RecoveryResult
import com.mitas.ppnam.station2aa.ui.components.ConnectionStatus
import com.mitas.ppnam.station2aa.ui.components.connectionStatusIn
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val mqttRepository: MqttRepository,
    private val authUseCase: AuthUseCase,
    sessionHolder: OperatorSessionHolder,
    private val coordinator: PendingCommandCoordinator,
) : ViewModel() {

    val session: StateFlow<OperatorSession?> = sessionHolder.session

    val connectionStatus: StateFlow<ConnectionStatus> = mqttRepository.connectionStatusIn(viewModelScope)

    /** Every unanswered command on this scanner, whoever sent it. */
    val pendingCommands: StateFlow<List<PendingCommand>> = coordinator.pending

    val recoveryNotices: StateFlow<List<RecoveryResult>> = coordinator.notices

    init {
        // Home is where every login lands: recover what this operator left unresolved.
        recoverPending()
    }

    fun recoverPending() {
        viewModelScope.launch { coordinator.recoverForCurrentOperator() }
    }

    fun dismissNotice(notice: RecoveryResult) = coordinator.dismiss(notice)

    private val _logoutEvent = Channel<Unit>(Channel.BUFFERED)
    val logoutEvent: Flow<Unit> = _logoutEvent.receiveAsFlow()

    fun logout() {
        viewModelScope.launch {
            authUseCase.logout()
            _logoutEvent.send(Unit)
        }
    }
}
