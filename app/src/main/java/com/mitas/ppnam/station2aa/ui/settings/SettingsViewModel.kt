package com.mitas.ppnam.station2aa.ui.settings

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mitas.ppnam.station2aa.data.identity.DeviceIdentity
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.data.settings.SettingsRepository
import com.mitas.ppnam.station2aa.domain.model.AppSettings
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.usecase.AuthUseCase
import com.mitas.ppnam.station2aa.ui.components.ConnectionStatus
import com.mitas.ppnam.station2aa.ui.components.connectionStatusIn
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface PinState {
    object Locked : PinState
    object Unlocked : PinState
}

sealed interface ApplyState {
    object Idle : ApplyState
    object Testing : ApplyState
    data class Success(val message: String) : ApplyState
    data class Failure(val message: String) : ApplyState
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val mqttRepository: MqttRepository,
    private val authUseCase: AuthUseCase,
    sessionHolder: OperatorSessionHolder,
    private val deviceIdentity: DeviceIdentity,
) : ViewModel() {

    /**
     * Non-null once an operator is logged in. Settings is reachable from the login screen too
     * (broker config has to be editable before anyone can log in), so the logout affordance below
     * is conditional on this.
     */
    val session: StateFlow<OperatorSession?> = sessionHolder.session

    /**
     * A second route to switching operator. The only one used to be a text label in the top bar
     * that read as a status caption, and it was absent from Settings entirely. SessionWatcher
     * handles the navigation once the session goes null — this just clears it.
     */
    fun logout() {
        viewModelScope.launch { authUseCase.logout() }
    }

    private val correctPin = "079545"

    // No lockout meant the PIN gating broker host/credentials could be brute-forced with
    // unlimited retries. Locks out entry entirely for a cooldown after too many wrong attempts,
    // rather than just rate-limiting one attempt at a time.
    private var failedPinAttempts = 0
    private var lockedOutUntilMs = 0L

    var pinInput = mutableStateOf("")
        private set
    var pinState = mutableStateOf<PinState>(PinState.Locked)
        private set
    var pinError = mutableStateOf(false)
        private set

    /**
     * Why the last PIN attempt failed, or null. [pinError] alone drove nothing but the field's
     * red border, so a wrong PIN gave the operator no explanation at all — indistinguishable from
     * a mistyped character or a jammed key. Deliberately says nothing about the correct PIN's
     * length or shape.
     */
    var pinErrorMessage = mutableStateOf<String?>(null)
        private set
    var pinLockoutMessage = mutableStateOf<String?>(null)
        private set
    var applyState = mutableStateOf<ApplyState>(ApplyState.Idle)
        private set
    var draftSettings = mutableStateOf(AppSettings())
        private set

    /**
     * The derived scanner identity (fleet MQTT base standard §2) — read-only diagnostics, shown
     * so it can be read off the device for enrolment. Never editable: it is derived from
     * hardware once by [DeviceIdentity] and persisted, replacing the old configurable Device ID.
     */
    var deviceId = mutableStateOf("")
        private set

    val connectionState: StateFlow<MqttConnectionState> = mqttRepository.connectionState

    /**
     * Surfaced separately from [connectionStatus] so Diagnostics can show the broker link and
     * Station 2's presence on their own lines. The composite status collapses them into one
     * verdict, which is right for the top-bar pill but hides which of the two is actually down.
     */
    val stationOnline: StateFlow<Boolean> = mqttRepository.stationOnline

    val connectionStatus: StateFlow<ConnectionStatus> = mqttRepository.connectionStatusIn(viewModelScope)

    init {
        viewModelScope.launch {
            draftSettings.value = settingsRepository.current()
        }
        // Launched wholly on IO — the first derivation can touch SharedPreferences and
        // NetworkInterface. Snapshot-state writes are thread-safe, so no hop back to Main.
        viewModelScope.launch(Dispatchers.IO) {
            deviceId.value = deviceIdentity.deviceId()
        }
    }

    fun onPinChange(value: String) {
        if (value.length <= 6) {
            pinInput.value = value
            pinError.value = false
            pinErrorMessage.value = null
        }
    }

    fun submitPin() {
        val now = System.currentTimeMillis()
        if (now < lockedOutUntilMs) {
            val remainingSec = (lockedOutUntilMs - now + 999) / 1_000
            pinLockoutMessage.value = "Too many attempts. Try again in ${remainingSec}s."
            pinInput.value = ""
            pinError.value = true
            pinErrorMessage.value = null
            return
        }
        if (pinInput.value == correctPin) {
            failedPinAttempts = 0
            pinLockoutMessage.value = null
            pinState.value = PinState.Unlocked
            pinError.value = false
            pinErrorMessage.value = null
        } else {
            pinInput.value = ""
            pinError.value = true
            failedPinAttempts++
            if (failedPinAttempts >= MAX_PIN_ATTEMPTS) {
                lockedOutUntilMs = now + PIN_LOCKOUT_MS
                failedPinAttempts = 0
                pinErrorMessage.value = null
                pinLockoutMessage.value = "Too many attempts. Try again in ${PIN_LOCKOUT_MS / 1_000}s."
            } else {
                // The remaining-attempt count is the useful half: it tells the operator a lockout
                // is coming without revealing anything about the PIN itself.
                val left = MAX_PIN_ATTEMPTS - failedPinAttempts
                pinErrorMessage.value =
                    "Incorrect PIN. $left attempt${if (left == 1) "" else "s"} left before lockout."
                pinLockoutMessage.value = null
            }
        }
    }

    private companion object {
        const val MAX_PIN_ATTEMPTS = 5
        const val PIN_LOCKOUT_MS = 30_000L
    }

    fun updateDraft(settings: AppSettings) {
        draftSettings.value = settings
    }

    fun testAndApply() {
        applyState.value = ApplyState.Testing
        viewModelScope.launch {
            val result = mqttRepository.reconnectWith(draftSettings.value)
            if (result.isSuccess) {
                settingsRepository.save(draftSettings.value)
                applyState.value = ApplyState.Success("Connected — settings saved")
                delay(2_000)
                pinState.value = PinState.Locked
                pinInput.value = ""
            } else {
                val msg = result.exceptionOrNull()?.message ?: "Connection failed"
                applyState.value = ApplyState.Failure(msg)
            }
        }
    }
}
