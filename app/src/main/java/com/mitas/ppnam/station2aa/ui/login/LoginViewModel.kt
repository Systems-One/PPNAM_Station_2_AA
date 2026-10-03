package com.mitas.ppnam.station2aa.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mitas.ppnam.station2aa.data.rfid.ScanEvent
import com.mitas.ppnam.station2aa.data.rfid.ScanEventBus
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.usecase.AuthUseCase
import com.mitas.ppnam.station2aa.ui.components.ConnectionStatus
import com.mitas.ppnam.station2aa.ui.components.connectionStatusIn
import dagger.hilt.android.lifecycle.HiltViewModel
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
    private val mqttRepository: MqttRepository,
    private val sessionHolder: OperatorSessionHolder,
    scanEventBus: ScanEventBus,
) : ViewModel() {

    /**
     * Login is the start destination, so this view model outlives the screen: it is still here
     * under Home. Badge scans act only while the login screen itself is showing, the way Job
     * Lookup gates its scans.
     */
    @Volatile
    private var loginScreenActive = false

    private val _uiState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private val _navigationEvent = Channel<String>(Channel.BUFFERED)
    val navigationEvent: Flow<String> = _navigationEvent.receiveAsFlow()

    val connectionState: StateFlow<MqttConnectionState> = mqttRepository.connectionState

    val connectionStatus: StateFlow<ConnectionStatus> = mqttRepository.connectionStatusIn(viewModelScope)

    init {
        // "Signed out after N minutes of inactivity." — shown once in the error slot, the way S1
        // does; a manual logout or a server-side session end carries no reason and shows nothing.
        sessionHolder.consumeSignedOutReason()?.let { _uiState.value = LoginUiState.Error(it) }
        viewModelScope.launch { mqttRepository.connect() }
        viewModelScope.launch {
            scanEventBus.events.collect { event ->
                // Only a card, only on the login screen, and never over a signed-in operator:
                // a scan on Home belongs to Home, not to a second login.
                if (event !is ScanEvent.RfidTag || !loginScreenActive || sessionHolder.session.value != null) return@collect
                attemptLogin { authUseCase.loginWithBadge(event.tagId) }
            }
        }
    }

    /** Called by the login screen as it becomes visible or hidden, so badge scans act only there. */
    fun setLoginScreenActive(active: Boolean) {
        loginScreenActive = active
    }

    fun submitCredentials(username: String, password: String) {
        if (!canStartLogin()) return
        // S1's rule, applied here too: a blank username used to go on the wire and come back as
        // the raw protocol text "username and clientNonce are required." (audit S2-07).
        if (username.isBlank() || password.isEmpty()) {
            _uiState.value = LoginUiState.Error(FILL_ALL_FIELDS)
            return
        }
        attemptLogin { authUseCase.login(username, password) }
    }

    // Blocks re-entry for the whole LoggingIn -> LoggedIn span: a second tap or scan arriving
    // after success but before Compose has navigated away must not start a second, concurrent
    // login.
    private fun canStartLogin() = _uiState.value == LoginUiState.Idle || _uiState.value is LoginUiState.Error

    private fun attemptLogin(login: suspend () -> Result<OperatorSession>) {
        if (!canStartLogin()) return
        // Claimed synchronously, before the first suspension, so two scans in one burst cannot
        // both pass canStartLogin().
        _uiState.value = LoginUiState.LoggingIn
        viewModelScope.launch {
            login()
                .onSuccess {
                    _uiState.value = LoginUiState.LoggedIn
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

    companion object {
        const val FILL_ALL_FIELDS = "Please fill in all fields"
    }
}
