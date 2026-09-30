package com.mitas.ppnam.station2aa.ui.login

import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.usecase.AuthUseCase
import kotlinx.coroutines.Dispatchers
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
    private lateinit var mockMqttRepository: MqttRepository
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
        mockMqttRepository = mock()

        whenever(mockMqttRepository.connectionState)
            .thenReturn(MutableStateFlow(MqttConnectionState.DISCONNECTED))
        whenever(mockMqttRepository.stationOnline).thenReturn(MutableStateFlow(true))
        whenever(mockMqttRepository.clockSkewMillis).thenReturn(MutableStateFlow<Long?>(null))

        viewModel = LoginViewModel(mockAuthUseCase, mockMqttRepository)
    }

    @After
    fun tearDown() { Dispatchers.resetMain() }

    @Test
    fun `initial state is Idle`() = runTest {
        assertTrue(viewModel.uiState.value is LoginUiState.Idle)
    }

    @Test
    fun `submitCredentials success sets LoggedIn and fires navigation event`() = runTest {
        whenever(mockAuthUseCase.login("operator1", "1234"))
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
        whenever(mockAuthUseCase.login(any(), any()))
            .thenReturn(Result.failure(Exception("Invalid credentials")))

        viewModel.submitCredentials("operator1", "wrong")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue(state is LoginUiState.Error)
        assertEquals("Invalid credentials", (state as LoginUiState.Error).message)
    }

    @Test
    fun `retry after error resets state to Idle`() = runTest {
        whenever(mockAuthUseCase.login(any(), any()))
            .thenReturn(Result.failure(Exception("Invalid credentials")))
        viewModel.submitCredentials("operator1", "wrong")
        advanceUntilIdle()

        viewModel.retry()

        assertTrue(viewModel.uiState.value is LoginUiState.Idle)
    }
}
