package com.mitas.ppnam.station2aa.ui.login

import com.mitas.ppnam.station2aa.data.rfid.ScanEvent
import com.mitas.ppnam.station2aa.data.rfid.ScanEventBus
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.usecase.AuthUseCase
import com.mitas.ppnam.station2aa.domain.usecase.LoginMethod
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class LoginViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var mockAuthUseCase: AuthUseCase
    private lateinit var mockScanEventBus: ScanEventBus
    private lateinit var mockMqttRepository: MqttRepository
    private lateinit var scanEvents: MutableSharedFlow<ScanEvent>
    private lateinit var viewModel: LoginViewModel

    private val sampleSession = OperatorSession(
        operatorSessionId = "sess-1",
        operatorId = "OP-1",
        operatorName = "Jane Smith",
        role = "Operator"
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockAuthUseCase = mock()
        mockScanEventBus = mock()
        mockMqttRepository = mock()
        scanEvents = MutableSharedFlow(extraBufferCapacity = 16)

        whenever(mockMqttRepository.connectionState)
            .thenReturn(MutableStateFlow(MqttConnectionState.DISCONNECTED))
        whenever(mockMqttRepository.stationOnline).thenReturn(MutableStateFlow(true))
        whenever(mockMqttRepository.clockSkewMillis).thenReturn(MutableStateFlow<Long?>(null))
        whenever(mockScanEventBus.events).thenReturn(scanEvents)

        viewModel = LoginViewModel(mockAuthUseCase, mockScanEventBus, mockMqttRepository)
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `initial state is Idle`() = runTest {
        assertTrue(viewModel.uiState.value is LoginUiState.Idle)
    }

    @Test
    fun `submitCredentials success sets LoggedIn and fires navigation event`() = runTest {
        whenever(mockAuthUseCase.login(LoginMethod.Credentials("operator1", "1234")))
            .thenReturn(Result.success(sampleSession))

        val navEvents = mutableListOf<String>()
        val job = launch(testDispatcher) { viewModel.navigationEvent.collect { navEvents.add(it) } }

        viewModel.submitCredentials("operator1", "1234")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is LoginUiState.LoggedIn)
        assertTrue(navEvents.contains("home"))
        job.cancel()
    }

    @Test
    fun `submitCredentials failure sets Error state`() = runTest {
        whenever(mockAuthUseCase.login(any()))
            .thenReturn(Result.failure(Exception("Invalid credentials")))

        viewModel.submitCredentials("operator1", "wrong")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is LoginUiState.Error)
        assertEquals("Invalid credentials", (state as LoginUiState.Error).message)
    }

    @Test
    fun `retry after error resets state to Idle`() = runTest {
        whenever(mockAuthUseCase.login(any()))
            .thenReturn(Result.failure(Exception("Invalid credentials")))
        viewModel.submitCredentials("operator1", "wrong")
        advanceUntilIdle()

        viewModel.retry()

        assertTrue(viewModel.uiState.value is LoginUiState.Idle)
    }

    @Test
    fun `badge scan while showing an error still attempts login`() = runTest {
        whenever(mockAuthUseCase.login(any()))
            .thenReturn(Result.failure(Exception("Invalid credentials")))
        viewModel.submitCredentials("operator1", "wrong")
        advanceUntilIdle()

        whenever(mockAuthUseCase.login(LoginMethod.Badge("TAG-JSMITH")))
            .thenReturn(Result.success(sampleSession))
        scanEvents.tryEmit(ScanEvent.RfidTag("TAG-JSMITH", Instant.now()))
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is LoginUiState.LoggedIn)
    }
}
