package com.mitas.ppnam.station2aa.ui.login

import com.mitas.ppnam.station2aa.data.rfid.ScanEvent
import com.mitas.ppnam.station2aa.data.rfid.ScanEventBus
import com.mitas.ppnam.station2aa.data.session.OperatorSession
import com.mitas.ppnam.station2aa.data.session.OperatorSessionHolder
import com.mitas.ppnam.station2aa.domain.model.OperatorEntry
import com.mitas.ppnam.station2aa.domain.repository.MqttConnectionState
import com.mitas.ppnam.station2aa.domain.repository.MqttRepository
import com.mitas.ppnam.station2aa.domain.usecase.AuthUseCase
import com.mitas.ppnam.station2aa.domain.usecase.OperatorDirectoryUseCase
import com.mitas.ppnam.station2aa.ui.components.ConnectionStatus
import kotlinx.coroutines.CompletableDeferred
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
import java.time.Instant

class LoginViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private lateinit var mockAuthUseCase: AuthUseCase
    private lateinit var mockMqttRepository: MqttRepository
    private lateinit var mockDirectory: OperatorDirectoryUseCase
    private lateinit var connection: MutableStateFlow<MqttConnectionState>
    private lateinit var sessionHolder: OperatorSessionHolder
    private lateinit var scanBus: ScanEventBus
    private lateinit var scans: MutableSharedFlow<ScanEvent>
    private lateinit var viewModel: LoginViewModel

    private val badgeTag = "E2000017221101441890ABCD"

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
        sessionHolder = OperatorSessionHolder()

        connection = MutableStateFlow(MqttConnectionState.DISCONNECTED)
        whenever(mockMqttRepository.connectionState).thenReturn(connection)
        whenever(mockMqttRepository.stationOnline).thenReturn(MutableStateFlow(true))
        whenever(mockMqttRepository.clockSkewMillis).thenReturn(MutableStateFlow<Long?>(null))
        scanBus = mock()
        scans = MutableSharedFlow(extraBufferCapacity = 8)
        whenever(scanBus.events).thenReturn(scans)
        mockDirectory = mock()
        whenever(mockDirectory.cached()).thenReturn(cachedOperators)

        viewModel = newViewModel()
    }

    private fun newViewModel() =
        LoginViewModel(mockAuthUseCase, mockMqttRepository, sessionHolder, scanBus, mockDirectory)

    private val cachedOperators = listOf(OperatorEntry("op.cached", "Cached Operator"))
    private val freshOperators = listOf(
        OperatorEntry("op.anna", "Anna Able"),
        OperatorEntry("op.cached", "Cached Operator"),
    )

    // ---- operator directory ---------------------------------------------------------------

    @Test
    fun `the dropdown is seeded from the cached directory before any answer`() = runTest {
        assertEquals(cachedOperators, viewModel.operators.value)
        verify(mockDirectory, never()).refresh()
    }

    @Test
    fun `refreshes the directory when the broker connects on the login screen`() = runTest {
        whenever(mockDirectory.refresh()).thenReturn(freshOperators)
        viewModel.setLoginScreenActive(true)

        connection.value = MqttConnectionState.CONNECTED
        advanceUntilIdle()

        verify(mockDirectory, times(1)).refresh()
        assertEquals(freshOperators, viewModel.operators.value)
    }

    @Test
    fun `refreshes once more on every reconnect while the login screen is showing`() = runTest {
        whenever(mockDirectory.refresh()).thenReturn(freshOperators)
        viewModel.setLoginScreenActive(true)

        connection.value = MqttConnectionState.CONNECTED
        connection.value = MqttConnectionState.RECONNECTING
        connection.value = MqttConnectionState.CONNECTED
        advanceUntilIdle()

        verify(mockDirectory, times(2)).refresh()
    }

    @Test
    fun `resuming the login screen while already connected refreshes the directory`() = runTest {
        whenever(mockDirectory.refresh()).thenReturn(freshOperators)
        connection.value = MqttConnectionState.CONNECTED
        advanceUntilIdle()
        verify(mockDirectory, never()).refresh()

        viewModel.setLoginScreenActive(true)
        advanceUntilIdle()

        verify(mockDirectory, times(1)).refresh()
        assertEquals(freshOperators, viewModel.operators.value)
    }

    @Test
    fun `a connect while the login screen is hidden does not ask for the directory`() = runTest {
        whenever(mockDirectory.refresh()).thenReturn(freshOperators)
        viewModel.setLoginScreenActive(true)
        viewModel.setLoginScreenActive(false)

        connection.value = MqttConnectionState.CONNECTED
        advanceUntilIdle()

        verify(mockDirectory, never()).refresh()
        assertEquals(cachedOperators, viewModel.operators.value)
    }

    @Test
    fun `a connect while an operator is signed in does not ask for the directory`() = runTest {
        whenever(mockDirectory.refresh()).thenReturn(freshOperators)
        sessionHolder.set(sampleSession)
        viewModel.setLoginScreenActive(true)

        connection.value = MqttConnectionState.CONNECTED
        advanceUntilIdle()

        verify(mockDirectory, never()).refresh()
    }

    @Test
    fun `a failed refresh keeps the cached list`() = runTest {
        whenever(mockDirectory.refresh()).thenReturn(null)
        viewModel.setLoginScreenActive(true)

        connection.value = MqttConnectionState.CONNECTED
        advanceUntilIdle()

        verify(mockDirectory).refresh()
        assertEquals(cachedOperators, viewModel.operators.value)
    }

    @Test
    fun `a typed username that is not in the directory still logs in`() = runTest {
        whenever(mockAuthUseCase.login("not.listed", "1234")).thenReturn(Result.success(sampleSession))

        viewModel.submitCredentials("not.listed", "1234")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is LoginUiState.LoggedIn)
    }

    @Test
    fun `a badge scan on the login screen signs the holder in and navigates home`() = runTest {
        whenever(mockAuthUseCase.loginWithBadge(badgeTag)).thenReturn(Result.success(sampleSession))
        val navEvents = mutableListOf<String>()
        val job = launch(testDispatcher) { viewModel.navigationEvent.collect { navEvents.add(it) } }

        viewModel.setLoginScreenActive(true)
        scans.emit(ScanEvent.RfidTag(badgeTag, Instant.EPOCH))
        advanceUntilIdle()

        verify(mockAuthUseCase).loginWithBadge(badgeTag)
        assertTrue(viewModel.uiState.value is LoginUiState.LoggedIn)
        assertTrue(navEvents.contains("home"))
        job.cancel()
    }

    @Test
    fun `a badge scan while the login screen is not showing is ignored`() = runTest {
        scans.emit(ScanEvent.RfidTag(badgeTag, Instant.EPOCH))
        viewModel.setLoginScreenActive(true)
        viewModel.setLoginScreenActive(false)
        scans.emit(ScanEvent.RfidTag(badgeTag, Instant.EPOCH))
        advanceUntilIdle()

        verify(mockAuthUseCase, never()).loginWithBadge(any())
        assertTrue(viewModel.uiState.value is LoginUiState.Idle)
    }

    @Test
    fun `a barcode scan is not a badge`() = runTest {
        viewModel.setLoginScreenActive(true)
        scans.emit(ScanEvent.Barcode("510019068", "CODE128", Instant.EPOCH))
        advanceUntilIdle()

        verify(mockAuthUseCase, never()).loginWithBadge(any())
    }

    @Test
    fun `a refused badge shows the message and a later scan can still sign in`() = runTest {
        whenever(mockAuthUseCase.loginWithBadge(badgeTag))
            .thenReturn(Result.failure(Exception("This badge is not registered or is no longer active.")))
            .thenReturn(Result.success(sampleSession))
        viewModel.setLoginScreenActive(true)

        scans.emit(ScanEvent.RfidTag(badgeTag, Instant.EPOCH))
        advanceUntilIdle()
        assertEquals(LoginUiState.Error("This badge is not registered or is no longer active."), viewModel.uiState.value)

        scans.emit(ScanEvent.RfidTag(badgeTag, Instant.EPOCH))
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is LoginUiState.LoggedIn)
    }

    @Test
    fun `a second scan while a badge login is in flight is ignored`() = runTest {
        val gate = CompletableDeferred<Result<OperatorSession>>()
        whenever(mockAuthUseCase.loginWithBadge(badgeTag)).doSuspendableAnswer { gate.await() }
        viewModel.setLoginScreenActive(true)

        scans.emit(ScanEvent.RfidTag(badgeTag, Instant.EPOCH))
        scans.emit(ScanEvent.RfidTag(badgeTag, Instant.EPOCH))
        assertTrue(viewModel.uiState.value is LoginUiState.LoggingIn)
        gate.complete(Result.success(sampleSession))
        advanceUntilIdle()

        verify(mockAuthUseCase, times(1)).loginWithBadge(badgeTag)
        assertTrue(viewModel.uiState.value is LoginUiState.LoggedIn)
    }

    @Test
    fun `a badge scan is ignored once an operator is signed in`() = runTest {
        sessionHolder.set(sampleSession)
        val signedIn = newViewModel()
        signedIn.setLoginScreenActive(true)

        scans.emit(ScanEvent.RfidTag(badgeTag, Instant.EPOCH))
        advanceUntilIdle()

        verify(mockAuthUseCase, never()).loginWithBadge(any())
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

    @Test
    fun `the pill starts from the live connection state instead of flashing Offline`() = runTest {
        whenever(mockMqttRepository.connectionState)
            .thenReturn(MutableStateFlow(MqttConnectionState.CONNECTED))
        val connectedVm = newViewModel()
        // The debounced flow has not emitted yet (1.5 s away); the seed must already be right.
        assertEquals(ConnectionStatus.Connected, connectedVm.connectionStatus.value)
    }

    @Test
    fun `blank username or password shows the fill-all-fields message and sends nothing`() = runTest {
        viewModel.submitCredentials("", "pass")
        advanceUntilIdle()
        assertEquals(LoginUiState.Error(LoginViewModel.FILL_ALL_FIELDS), viewModel.uiState.value)

        viewModel.submitCredentials("   ", "pass")
        viewModel.submitCredentials("operator1", "")
        advanceUntilIdle()
        assertEquals(LoginUiState.Error(LoginViewModel.FILL_ALL_FIELDS), viewModel.uiState.value)
        verify(mockAuthUseCase, never()).login(any(), any())
    }

    @Test
    fun `a signed-out reason is shown once and then consumed`() = runTest {
        sessionHolder.clear("Signed out after 15 minutes of inactivity.")
        val first = newViewModel()
        assertEquals(LoginUiState.Error("Signed out after 15 minutes of inactivity."), first.uiState.value)
        val second = newViewModel()
        assertTrue(second.uiState.value is LoginUiState.Idle)
    }
}
