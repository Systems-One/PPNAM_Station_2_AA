package com.mitas.ppnam.station2aa.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    private val mqttRepository: MqttRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private val _navigationEvent = Channel<String>(Channel.BUFFERED)
    val navigationEvent: Flow<String> = _navigationEvent.receiveAsFlow()

    val connectionState: StateFlow<MqttConnectionState> = mqttRepository.connectionState

    val connectionStatus: StateFlow<ConnectionStatus> = mqttRepository.connectionStatusIn(viewModelScope)

    init {
        viewModelScope.launch { mqttRepository.connect() }
    }

    fun submitCredentials(username: String, password: String) {
        // Blocks re-entry for the whole LoggingIn -> LoggedIn span: a second tap arriving after
        // success but before Compose has navigated away must not start a second, concurrent login.
        if (_uiState.value != LoginUiState.Idle && _uiState.value !is LoginUiState.Error) return
        // S1's rule, applied here too: a blank username used to go on the wire and come back as
        // the raw protocol text "username and clientNonce are required." (audit S2-07).
        if (username.isBlank() || password.isEmpty()) {
            _uiState.value = LoginUiState.Error(FILL_ALL_FIELDS)
            return
        }
        viewModelScope.launch {
            _uiState.value = LoginUiState.LoggingIn
            authUseCase.login(username, password)
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
