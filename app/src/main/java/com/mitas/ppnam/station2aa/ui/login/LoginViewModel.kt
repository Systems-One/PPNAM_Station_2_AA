package com.mitas.ppnam.station2aa.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mitas.ppnam.station2aa.data.rfid.ScanEvent
import com.mitas.ppnam.station2aa.data.rfid.ScanEventBus
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.usecase.AuthUseCase
import com.mitas.ppnam.station2aa.domain.usecase.LoginMethod
import com.mitas.ppnam.station2aa.ui.components.ConnectionStatus
import com.mitas.ppnam.station2aa.ui.components.connectionStatusFlow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class LoginUiState {
    object Idle : LoginUiState()
    object LoggingIn : LoginUiState()
    data class Error(val message: String) : LoginUiState()
    object LoggedIn : LoginUiState()
}

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authUseCase: AuthUseCase,
    private val scanEventBus: ScanEventBus,
    private val mqttRepository: MqttRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private val _navigationEvent = Channel<String>(Channel.BUFFERED)
    val navigationEvent: Flow<String> = _navigationEvent.receiveAsFlow()

    val connectionState: StateFlow<MqttConnectionState> = mqttRepository.connectionState

    val connectionStatus: StateFlow<ConnectionStatus> = connectionStatusFlow(
        mqttRepository.connectionState,
        mqttRepository.stationOnline,
        mqttRepository.clockSkewMillis,
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionStatus.Offline)

    private var badgeScanJob: Job? = null

    init {
        viewModelScope.launch { mqttRepository.connect() }
        startListeningForBadgeScans()
    }

    private fun startListeningForBadgeScans() {
        badgeScanJob?.cancel()
        badgeScanJob = viewModelScope.launch {
            scanEventBus.events.filterIsInstance<ScanEvent.RfidTag>().collect { event ->
                attemptLogin(LoginMethod.Badge(event.tagId))
            }
        }
    }

    fun submitCredentials(username: String, password: String) {
        attemptLogin(LoginMethod.Credentials(username, password))
    }

    private fun attemptLogin(method: LoginMethod) {
        // Blocks re-entry for the whole LoggingIn -> LoggedIn span, not just LoggingIn: a repeat
        // badge read (continuous-read RFID hardware commonly re-fires the same tag, or a second
        // tag lands nearby) arriving after success but before Compose has navigated away must not
        // start a second, concurrent login that could overwrite the just-established session with
        // a different operator.
        if (_uiState.value != LoginUiState.Idle && _uiState.value !is LoginUiState.Error) return
        viewModelScope.launch {
            _uiState.value = LoginUiState.LoggingIn
            authUseCase.login(method)
                .onSuccess {
                    _uiState.value = LoginUiState.LoggedIn
                    // The screen is on its way out; stop accepting further badge reads for it.
                    badgeScanJob?.cancel()
                    _navigationEvent.send("home")
                }
                .onFailure { e ->
                    _uiState.value = LoginUiState.Error(e.message ?: "Login failed")
                }
        }
    }

    fun retry() {
        _uiState.value = LoginUiState.Idle
    }

    override fun onCleared() {
        super.onCleared()
        badgeScanJob?.cancel()
    }
}
