package com.mitas.ppnam.station2aa.ui.settings

import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mitas.ppnam.station2aa.data.identity.DeviceIdentity
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.data.settings.PinLockoutStore
import com.mitas.ppnam.station2aa.data.settings.SettingsRepository
import com.mitas.ppnam.station2aa.domain.model.AppSettings
import com.mitas.ppnam.station2aa.domain.model.AutoLogout
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.usecase.AuthUseCase
import com.mitas.ppnam.station2aa.ui.components.ConnectionStatus
import com.mitas.ppnam.station2aa.ui.components.connectionStatusIn
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    deviceIdentity: DeviceIdentity,
    private val pinLockoutStore: PinLockoutStore,
) : ViewModel() {

    /**
     * Non-null once an operator is logged in. Settings is reachable from the login screen too
     * (broker config has to be editable before anyone can log in), so the logout affordance below
     * is conditional on this.
     */
    val session: StateFlow<OperatorSession?> = sessionHolder.session

    /**
     * A second route to switching operator. SessionWatcher handles the navigation once the
     * session goes null — this just clears it.
     */
    fun logout() {
        viewModelScope.launch { authUseCase.logout() }
    }

    // ---- Supervisor PIN gate -------------------------------------------------------------------

    private val correctPin = "079545"

    /** Wall-clock, because the lockout deadline is persisted across process restarts. Test seam. */
    @VisibleForTesting
    internal var nowMs: () -> Long = { System.currentTimeMillis() }

    var pinInput = mutableStateOf("")
        private set
    var pinState = mutableStateOf<PinState>(PinState.Locked)
        private set
    var pinError = mutableStateOf(false)
        private set

    /**
     * Why the last PIN attempt failed, or null. Deliberately says nothing about the correct PIN's
     * length or shape.
     */
    var pinErrorMessage = mutableStateOf<String?>(null)
        private set
    var pinLockoutMessage = mutableStateOf<String?>(null)
        private set

    /** True while the cooldown runs: the field AND Unlock are disabled, not merely painted red. */
    var pinLockedOut = mutableStateOf(false)
        private set
    private var lockoutTicker: Job? = null

    // ---- Draft -----------------------------------------------------------------------------------

    var applyState = mutableStateOf<ApplyState>(ApplyState.Idle)
        private set

    /** Host, WebSocket, TLS and username drafts, plus the stored password (never shown). */
    var draftSettings = mutableStateOf(AppSettings())
        private set

    // Numeric fields are kept as the text the operator typed. Parsing on every keystroke
    // (`toIntOrNull() ?: old`) made the Port field impossible to empty and threw the caret to
    // position 0 — "9001" became "90019" (audit S2-03). They are parsed once, on Test & Apply.
    var portText = mutableStateOf("")
        private set
    var timeoutText = mutableStateOf("")
        private set
    var autoLogoutText = mutableStateOf("")
        private set

    /** What was typed into Password. Blank means "keep the provisioned password" (S1's rule). */
    var passwordText = mutableStateOf("")
        private set

    var hostError = mutableStateOf<String?>(null)
        private set
    var portError = mutableStateOf<String?>(null)
        private set
    var timeoutError = mutableStateOf<String?>(null)
        private set
    var autoLogoutError = mutableStateOf<String?>(null)
        private set

    // ---- Diagnostics -----------------------------------------------------------------------------

    private val _deviceId = MutableStateFlow("")

    /**
     * The derived scanner identity (fleet MQTT base standard §2) — read-only diagnostics, shown so
     * it can be read off the device for enrolment. A StateFlow, not a snapshot state written from
     * Dispatchers.IO: that write never reached the composable and the row stayed blank (audit S2-04).
     */
    val deviceId: StateFlow<String> = _deviceId.asStateFlow()

    val connectionState: StateFlow<MqttConnectionState> = mqttRepository.connectionState

    /**
     * Surfaced separately from [connectionStatus] so Diagnostics can show the broker link and
     * Station 2's presence on their own lines.
     */
    val stationOnline: StateFlow<Boolean> = mqttRepository.stationOnline

    val connectionStatus: StateFlow<ConnectionStatus> = mqttRepository.connectionStatusIn(viewModelScope)

    init {
        viewModelScope.launch {
            val current = settingsRepository.current()
            draftSettings.value = current
            portText.value = current.mqttPort.toString()
            timeoutText.value = current.requestTimeoutMs.toString()
            autoLogoutText.value = current.autoLogoutMinutes.toString()
        }
        // Deriving the id can touch SharedPreferences and NetworkInterface — off Main.
        viewModelScope.launch(Dispatchers.IO) {
            _deviceId.value = deviceIdentity.deviceId()
        }
        if (remainingLockoutMs() > 0) startLockoutTicker()
    }

    fun onPinChange(value: String) {
        if (pinLockedOut.value) return
        if (value.length <= 6 && value.all { it in '0'..'9' }) {
            pinInput.value = value
            pinError.value = false
            pinErrorMessage.value = null
        }
    }

    /**
     * Milliseconds of lockout left, clamped: a deadline more than one lockout away can only come
     * from a device clock that was stepped backwards after it was written, and a lockout may never
     * outlast its 30 s of real time — such a deadline is discarded.
     */
    private fun remainingLockoutMs(): Long {
        val remaining = pinLockoutStore.lockedOutUntilMs - nowMs()
        if (remaining > PIN_LOCKOUT_MS) {
            pinLockoutStore.lockedOutUntilMs = 0L
            return 0L
        }
        return remaining
    }

    fun submitPin() {
        if (remainingLockoutMs() > 0) {
            startLockoutTicker()
            pinInput.value = ""
            return
        }
        // An empty Unlock is a mis-tap, not a guess: it must not burn an attempt (audit group c).
        if (pinInput.value.isBlank()) return
        if (pinInput.value == correctPin) {
            pinLockoutStore.failedAttempts = 0
            pinLockoutMessage.value = null
            pinState.value = PinState.Unlocked
            pinError.value = false
            pinErrorMessage.value = null
            return
        }
        pinInput.value = ""
        pinError.value = true
        val failed = pinLockoutStore.failedAttempts + 1
        if (failed >= MAX_PIN_ATTEMPTS) {
            pinLockoutStore.lockedOutUntilMs = nowMs() + PIN_LOCKOUT_MS
            pinLockoutStore.failedAttempts = 0
            pinErrorMessage.value = null
            startLockoutTicker()
        } else {
            pinLockoutStore.failedAttempts = failed
            // The remaining-attempt count is the useful half: it tells the operator a lockout is
            // coming without revealing anything about the PIN itself.
            val left = MAX_PIN_ATTEMPTS - failed
            pinErrorMessage.value =
                "Incorrect PIN. $left attempt${if (left == 1) "" else "s"} left before lockout."
            pinLockoutMessage.value = null
        }
    }

    /** Re-renders the countdown every second and re-enables the gate when it reaches zero. */
    private fun startLockoutTicker() {
        lockoutTicker?.cancel()
        lockoutTicker = viewModelScope.launch {
            while (true) {
                val remaining = remainingLockoutMs()
                if (remaining <= 0) break
                pinLockedOut.value = true
                pinError.value = true
                pinLockoutMessage.value = "Too many attempts. Try again in ${(remaining + 999) / 1_000}s."
                delay(1_000)
            }
            pinLockedOut.value = false
            pinError.value = false
            pinLockoutMessage.value = null
        }
    }

    private companion object {
        const val MAX_PIN_ATTEMPTS = 5
        const val PIN_LOCKOUT_MS = 30_000L
        const val MIN_TIMEOUT_MS = 1_000L
        const val MAX_TIMEOUT_MS = 60_000L
        const val CONNECT_TIMEOUT_SECONDS = 15
    }

    // ---- Draft editing ---------------------------------------------------------------------------

    fun updateDraft(settings: AppSettings) {
        draftSettings.value = settings
        hostError.value = null
    }

    private fun digitsOnly(text: String, maxLength: Int): Boolean =
        text.length <= maxLength && text.all { it in '0'..'9' }

    fun onPortChange(text: String) {
        if (digitsOnly(text, 5)) { portText.value = text; portError.value = null }
    }

    fun onTimeoutChange(text: String) {
        if (digitsOnly(text, 6)) { timeoutText.value = text; timeoutError.value = null }
    }

    fun onAutoLogoutChange(text: String) {
        if (digitsOnly(text, 4)) { autoLogoutText.value = text; autoLogoutError.value = null }
    }

    fun onPasswordChange(text: String) {
        passwordText.value = text
    }

    /**
     * The settings to apply, or null with the inline errors set. S1's host/port rules, plus the
     * two numeric extras this app has.
     */
    @VisibleForTesting
    internal fun validatedSettings(): AppSettings? {
        val draft = draftSettings.value
        val host = draft.mqttHost.trim()
        val port = AppSettings.parsePort(portText.value)
        val timeout = timeoutText.value.toLongOrNull()?.takeIf { it in MIN_TIMEOUT_MS..MAX_TIMEOUT_MS }
        val autoLogout = AutoLogout.parseMinutes(autoLogoutText.value)
        hostError.value = if (host.isBlank()) "Host required" else null
        portError.value = if (port == null) "Invalid port (1–65535)" else null
        timeoutError.value = if (timeout == null) "Enter $MIN_TIMEOUT_MS–$MAX_TIMEOUT_MS ms" else null
        autoLogoutError.value = if (autoLogout == null) "Enter 0–${AutoLogout.MAX_MINUTES}" else null
        if (host.isBlank() || port == null || timeout == null || autoLogout == null) return null
        return draft.copy(
            mqttHost = host,
            mqttPort = port,
            mqttUsername = draft.mqttUsername.trim(),
            // The field never echoes the stored password; blank keeps it (S1's rule, static-20).
            mqttPassword = passwordText.value.ifBlank { draft.mqttPassword },
            requestTimeoutMs = timeout,
            autoLogoutMinutes = autoLogout,
        )
    }

    fun testAndApply() {
        val settings = validatedSettings() ?: return
        applyState.value = ApplyState.Testing
        viewModelScope.launch {
            val result = mqttRepository.reconnectWith(settings)
            if (result.isSuccess) {
                settingsRepository.save(settings)
                draftSettings.value = settings
                passwordText.value = ""
                applyState.value = ApplyState.Success("Connected — settings saved")
                delay(2_000)
                pinState.value = PinState.Locked
                pinInput.value = ""
            } else {
                applyState.value = ApplyState.Failure(result.exceptionOrNull().toOperatorMessage())
            }
        }
    }

    /** Library text ("Timed out waiting for 15000 ms", "Connection refused") never reaches the screen. */
    private fun Throwable?.toOperatorMessage(): String = when (this) {
        is TimeoutCancellationException ->
            "No answer from the broker within $CONNECT_TIMEOUT_SECONDS s. Check the host, port and TLS setting."
        else -> "Could not connect to the broker. Check the host, port, username, password and TLS setting."
    }
}
