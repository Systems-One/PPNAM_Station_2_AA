package com.mitas.ppnam.station2aa.ui.settings

import com.mitas.ppnam.station2aa.data.identity.DeviceIdentity
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.data.settings.SettingsRepository
import com.mitas.ppnam.station2aa.domain.model.AppSettings
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.usecase.AuthUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class SettingsViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var mockSettingsRepository: SettingsRepository
    private lateinit var mockMqttRepository: MqttRepository
    private lateinit var mockAuthUseCase: AuthUseCase
    private lateinit var mockSessionHolder: OperatorSessionHolder
    private lateinit var mockDeviceIdentity: DeviceIdentity
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockSettingsRepository = mock()
        mockMqttRepository = mock()
        mockAuthUseCase = mock()
        mockSessionHolder = mock()
        mockDeviceIdentity = mock()
        whenever(mockSessionHolder.session).thenReturn(MutableStateFlow(null))
        whenever(mockDeviceIdentity.deviceId()).thenReturn("scanner_5c64df8d86a8")

        whenever(mockSettingsRepository.settingsFlow).thenReturn(flowOf(AppSettings()))
        runBlocking { whenever(mockSettingsRepository.current()).thenReturn(AppSettings()) }
        whenever(mockMqttRepository.connectionState)
            .thenReturn(MutableStateFlow(MqttConnectionState.DISCONNECTED))
        whenever(mockMqttRepository.stationOnline).thenReturn(MutableStateFlow(true))
        whenever(mockMqttRepository.clockSkewMillis).thenReturn(MutableStateFlow<Long?>(null))

        viewModel = SettingsViewModel(
            mockSettingsRepository, mockMqttRepository, mockAuthUseCase, mockSessionHolder,
            mockDeviceIdentity,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

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
    fun `wrong PIN stays Locked and sets pinError`() = runTest {
        viewModel.onPinChange("000000")
        viewModel.submitPin()
        assertTrue(viewModel.pinState.value is PinState.Locked)
        assertTrue(viewModel.pinError.value)
        assertEquals("", viewModel.pinInput.value)
    }

    @Test
    fun `updateDraft replaces draft settings`() {
        val newSettings = AppSettings(mqttHost = "192.168.1.1")
        viewModel.updateDraft(newSettings)
        assertEquals("192.168.1.1", viewModel.draftSettings.value.mqttHost)
    }

    @Test
    fun `testAndApply on success saves settings and resets to Locked`() = runTest {
        whenever(mockMqttRepository.reconnectWith(any())).thenReturn(Result.success(Unit))
        viewModel.onPinChange("079545")
        viewModel.submitPin()

        viewModel.testAndApply()
        advanceUntilIdle()

        verify(mockSettingsRepository).save(any())
        assertTrue(viewModel.applyState.value is ApplyState.Success)
        advanceTimeBy(2100)
        assertTrue(viewModel.pinState.value is PinState.Locked)
    }

    @Test
    fun `testAndApply on failure sets Failure state and does not save`() = runTest {
        val error = RuntimeException("Connection refused")
        whenever(mockMqttRepository.reconnectWith(any())).thenReturn(Result.failure(error))

        viewModel.testAndApply()
        advanceUntilIdle()

        verify(mockSettingsRepository, never()).save(any())
        val state = viewModel.applyState.value
        assertTrue(state is ApplyState.Failure)
        assertEquals("Connection refused", (state as ApplyState.Failure).message)
    }

    @Test
    fun `onPinChange does not accept more than 6 digits`() {
        viewModel.onPinChange("1234567")
        assertEquals("", viewModel.pinInput.value)
    }
}
