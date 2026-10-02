package com.mitas.ppnam.station2aa.ui.settings

import com.mitas.ppnam.station2aa.data.identity.DeviceIdentity
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.data.settings.PinLockoutStore
import com.mitas.ppnam.station2aa.data.settings.SettingsRepository
import com.mitas.ppnam.station2aa.domain.model.AppSettings
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.usecase.AuthUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class SettingsViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private class InMemoryPinLockoutStore : PinLockoutStore {
        override var failedAttempts = 0
        override var lockedOutUntilMs = 0L
    }

    private lateinit var mockSettingsRepository: SettingsRepository
    private lateinit var mockMqttRepository: MqttRepository
    private lateinit var mockAuthUseCase: AuthUseCase
    private lateinit var mockSessionHolder: OperatorSessionHolder
    private lateinit var mockDeviceIdentity: DeviceIdentity
    private lateinit var store: InMemoryPinLockoutStore
    private lateinit var viewModel: SettingsViewModel

    private val stored = AppSettings(
        mqttHost = "10.0.2.2", mqttPort = 9001, mqttUseTls = false,
        mqttUsername = "test", mqttPassword = "stored-secret", requestTimeoutMs = 10_000L, autoLogoutMinutes = 15,
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockSettingsRepository = mock()
        mockMqttRepository = mock()
        mockAuthUseCase = mock()
        mockSessionHolder = mock()
        mockDeviceIdentity = mock()
        store = InMemoryPinLockoutStore()
        whenever(mockSessionHolder.session).thenReturn(MutableStateFlow(null))
        whenever(mockDeviceIdentity.deviceId()).thenReturn("scanner_5c64df8d86a8")

        whenever(mockSettingsRepository.settingsFlow).thenReturn(flowOf(stored))
        runBlocking { whenever(mockSettingsRepository.current()).thenReturn(stored) }
        whenever(mockMqttRepository.connectionState)
            .thenReturn(MutableStateFlow(MqttConnectionState.DISCONNECTED))
        whenever(mockMqttRepository.stationOnline).thenReturn(MutableStateFlow(true))
        whenever(mockMqttRepository.clockSkewMillis).thenReturn(MutableStateFlow<Long?>(null))

        viewModel = newViewModel()
    }

    private fun newViewModel() = SettingsViewModel(
        mockSettingsRepository, mockMqttRepository, mockAuthUseCase, mockSessionHolder,
        mockDeviceIdentity, store,
    )

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun wrongPin(vm: SettingsViewModel = viewModel) {
        vm.onPinChange("000000")
        vm.submitPin()
    }

    // ---- PIN gate ------------------------------------------------------------------------------

    @Test
    fun `initial pin state is Locked`() {
        assertTrue(viewModel.pinState.value is PinState.Locked)
    }

    @Test
    fun `correct PIN unlocks settings`() = runTest {
        viewModel.onPinChange("079545")
        viewModel.submitPin()
        assertTrue(viewModel.pinState.value is PinState.Unlocked)
        assertFalse(viewModel.pinError.value)
    }

    @Test
    fun `wrong PIN stays Locked, sets pinError and says how many attempts are left`() = runTest {
        wrongPin()
        assertTrue(viewModel.pinState.value is PinState.Locked)
        assertTrue(viewModel.pinError.value)
        assertEquals("", viewModel.pinInput.value)
        assertEquals("Incorrect PIN. 4 attempts left before lockout.", viewModel.pinErrorMessage.value)
    }

    @Test
    fun `onPinChange does not accept more than 6 digits or non-digits`() {
        viewModel.onPinChange("1234567")
        assertEquals("", viewModel.pinInput.value)
        viewModel.onPinChange("12a4")
        assertEquals("", viewModel.pinInput.value)
    }

    @Test
    fun `an empty Unlock is not counted as an attempt`() = runTest {
        viewModel.submitPin()
        assertNull(viewModel.pinErrorMessage.value)
        assertFalse(viewModel.pinError.value)
        assertEquals(0, store.failedAttempts)
    }

    @Test
    fun `failed attempts survive leaving the screen`() = runTest {
        repeat(2) { wrongPin() }
        val reopened = newViewModel()
        repeat(2) { wrongPin(reopened) }
        assertEquals("Incorrect PIN. 1 attempt left before lockout.", reopened.pinErrorMessage.value)
    }

    @Test
    fun `five wrong PINs lock the gate, disable Unlock and count down every second`() = runTest {
        viewModel.nowMs = { testScheduler.currentTime }
        repeat(5) { wrongPin() }
        assertTrue(viewModel.pinLockedOut.value)
        assertEquals("Too many attempts. Try again in 30s.", viewModel.pinLockoutMessage.value)
        assertNull(viewModel.pinErrorMessage.value)

        advanceTimeBy(1_001)
        assertEquals("Too many attempts. Try again in 29s.", viewModel.pinLockoutMessage.value)

        // The correct PIN is refused while locked out.
        viewModel.onPinChange("079545")
        viewModel.submitPin()
        assertTrue(viewModel.pinState.value is PinState.Locked)

        advanceTimeBy(30_000)
        assertFalse(viewModel.pinLockedOut.value)
        assertNull(viewModel.pinLockoutMessage.value)
        assertFalse(viewModel.pinError.value)

        viewModel.onPinChange("079545")
        viewModel.submitPin()
        assertTrue(viewModel.pinState.value is PinState.Unlocked)
    }

    @Test
    fun `a lockout survives a new ViewModel (screen left and reopened)`() = runTest {
        viewModel.nowMs = { testScheduler.currentTime }
        repeat(5) { wrongPin() }
        val reopened = newViewModel()
        reopened.nowMs = { testScheduler.currentTime }
        reopened.onPinChange("079545")
        reopened.submitPin()
        assertTrue(reopened.pinState.value is PinState.Locked)
        assertTrue(reopened.pinLockedOut.value)
    }

    @Test
    fun `a lockout whose deadline is implausibly far ahead is treated as expired`() = runTest {
        // Device clock stepped backwards after a lockout was written: the deadline would now be
        // minutes away. A lockout may never outlast its 30 s of real time.
        viewModel.nowMs = { testScheduler.currentTime }
        store.lockedOutUntilMs = testScheduler.currentTime + 10 * 60_000L
        viewModel.onPinChange("079545")
        viewModel.submitPin()
        assertTrue(viewModel.pinState.value is PinState.Unlocked)
        assertEquals(0L, store.lockedOutUntilMs)
    }

    // ---- Drafts and validation -----------------------------------------------------------------

    @Test
    fun `the draft is loaded from the stored settings with the password field left blank`() = runTest {
        assertEquals("10.0.2.2", viewModel.draftSettings.value.mqttHost)
        assertEquals("9001", viewModel.portText.value)
        assertEquals("10000", viewModel.timeoutText.value)
        assertEquals("15", viewModel.autoLogoutText.value)
        assertEquals("", viewModel.passwordText.value)
    }

    @Test
    fun `port text can be emptied and only takes up to five digits`() {
        viewModel.onPortChange("")
        assertEquals("", viewModel.portText.value)
        viewModel.onPortChange("9001")
        viewModel.onPortChange("900199")
        assertEquals("9001", viewModel.portText.value)
        viewModel.onPortChange("90a1")
        assertEquals("9001", viewModel.portText.value)
    }

    @Test
    fun `invalid port, timeout and auto sign-out are rejected inline and nothing is sent`() = runTest {
        viewModel.onPortChange("65536")
        viewModel.onTimeoutChange("0")
        viewModel.onAutoLogoutChange("1441")
        viewModel.updateDraft(viewModel.draftSettings.value.copy(mqttHost = "  "))

        viewModel.testAndApply()
        advanceUntilIdle()

        assertEquals("Host required", viewModel.hostError.value)
        assertEquals("Invalid port (1–65535)", viewModel.portError.value)
        assertEquals("Enter 1000–60000 ms", viewModel.timeoutError.value)
        assertEquals("Enter 0–1440", viewModel.autoLogoutError.value)
        assertTrue(viewModel.applyState.value is ApplyState.Idle)
        verify(mockMqttRepository, never()).reconnectWith(any())
        verify(mockSettingsRepository, never()).save(any())
    }

    @Test
    fun `a blank password keeps the stored one and a typed password replaces it`() = runTest {
        assertEquals("stored-secret", viewModel.validatedSettings()!!.mqttPassword)
        viewModel.onPasswordChange("new-secret")
        assertEquals("new-secret", viewModel.validatedSettings()!!.mqttPassword)
    }

    @Test
    fun `testAndApply on success saves the validated settings and resets to Locked`() = runTest {
        whenever(mockMqttRepository.reconnectWith(any())).thenReturn(Result.success(Unit))
        viewModel.onPinChange("079545")
        viewModel.submitPin()
        viewModel.onPortChange("1884")
        viewModel.onAutoLogoutChange("1")

        viewModel.testAndApply()
        advanceUntilIdle()

        val saved = argumentCaptor<AppSettings>()
        verify(mockSettingsRepository).save(saved.capture())
        assertEquals(1884, saved.firstValue.mqttPort)
        assertEquals(1, saved.firstValue.autoLogoutMinutes)
        assertEquals("stored-secret", saved.firstValue.mqttPassword)
        assertEquals(ApplyState.Success("Connected — settings saved"), viewModel.applyState.value)
        advanceTimeBy(2_100)
        assertTrue(viewModel.pinState.value is PinState.Locked)
    }

    @Test
    fun `testAndApply on failure shows operator wording, not the library message`() = runTest {
        whenever(mockMqttRepository.reconnectWith(any()))
            .thenReturn(Result.failure(RuntimeException("Connection refused")))
        viewModel.testAndApply()
        advanceUntilIdle()
        verify(mockSettingsRepository, never()).save(any())
        assertEquals(
            ApplyState.Failure("Could not connect to the broker. Check the host, port, username, password and TLS setting."),
            viewModel.applyState.value,
        )
    }

    @Test
    fun `a connect timeout names the 15 s wait`() = runTest {
        val timeout = runCatching { withTimeout(1) { kotlinx.coroutines.delay(10) } }.exceptionOrNull()
        assertTrue(timeout is TimeoutCancellationException)
        whenever(mockMqttRepository.reconnectWith(any())).thenReturn(Result.failure(timeout!!))
        viewModel.testAndApply()
        advanceUntilIdle()
        assertEquals(
            ApplyState.Failure("No answer from the broker within 15 s. Check the host, port and TLS setting."),
            viewModel.applyState.value,
        )
    }

    // ---- Diagnostics ---------------------------------------------------------------------------

    @Test
    fun `the device id reaches the UI as a StateFlow`() {
        // runBlocking, not runTest: the id is derived on Dispatchers.IO (a real thread), and
        // runTest's virtual clock would skip a withTimeout while that thread is still working.
        val id = runBlocking { withTimeout(5_000) { viewModel.deviceId.first { it.isNotBlank() } } }
        assertEquals("scanner_5c64df8d86a8", id)
    }
}
